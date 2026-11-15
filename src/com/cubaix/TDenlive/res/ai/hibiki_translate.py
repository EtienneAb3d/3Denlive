# Direct voice translation (speech to speech) of a video for 3Denlive (run by com.cubaix.TDenlive.ai.SpeechGenerator).
#
# Hibiki-Zero (Kyutai, 2026, https://github.com/kyutai-labs/hibiki-zero, weights kyutai/hibiki-zero-3b-pytorch-bf16,
# CC BY-NC-SA 4.0): a 3B decoder that listens to the speech (Mimi codec tokens, 12.5 Hz) and speaks its English
# translation at the same time, with the voice of the speaker (voice transfer), plus the text of the translation.
# French, Spanish, Portuguese, German -> English. No transcription, no text to speech: one model, one voice per
# speaker, the intonation of the original.
# The model was trained on sequences of up to 120 s: the sound is cut into pieces of at most PIECE_S s at the
# quietest point near their end (as speech_transcribe.py), translated one batch after the other; the speech of each
# piece is put back at the time of the original speech (the delay of the simultaneous translation removed). The
# text tokens, timed by the frame they come with, give the English subtitles (grouped by speech_transcribe.group()).
# Inference as hibiki_zero.run.generate() of its authors, with the moshi package only. CUDA (fp16), MPS (bf16) or
# the CPU (fp32, very slow).
#
# Protocol on stdout, one line per message:
#  STATUS <key>              step (key of the AI.* language entries)
#  DEVICE <cuda|mps|cpu>     device used
#  STEP <done> <total>       seconds of sound translated
#  PEAK <stage> <bytes>      peak GPU memory reserved (CUDA)
#  DONE <path>               WAV written
#  ERROR <OOM|text>          failure
import argparse
import math
import os
import sys
import time
from pathlib import Path

PIECE_S = 100.0
CUT_WINDOW_S = 25.0
FRAME_S = 0.02
# Silence added after each piece (s): time for the translation to end
TAIL_S = 8.0
# Pieces translated at once (batch)
BATCH = 2


def out(*aArgs):
	print(*aArgs, flush=True)


def log(aText):
	print(aText, file=sys.stderr, flush=True)


def main(aArgv):
	aP = argparse.ArgumentParser()
	aP.add_argument("--model-dir", required=True)
	aP.add_argument("--device", default="auto")
	aP.add_argument("--audio", required=True)
	aP.add_argument("--srt")
	aP.add_argument("--out", required=True)
	aP.add_argument("--seed", type=int, default=42)
	aArgs = aP.parse_args(aArgv)

	out("STATUS Starting")
	import numpy as np
	import torch

	if aArgs.device != "cpu" and torch.cuda.is_available():
		aDevice = torch.device("cuda", 0)
	elif aArgs.device != "cpu" and getattr(torch.backends, "mps", None) is not None and torch.backends.mps.is_available():
		aDevice = torch.device("mps")
	else:
		aDevice = torch.device("cpu")
	out("DEVICE " + aDevice.type)
	if aDevice.type == "cpu":
		torch.set_num_threads(max(1, os.cpu_count() or 1))
	aDtype = torch.float16 if aDevice.type == "cuda" else torch.bfloat16 if aDevice.type == "mps" else torch.float32

	import ai_serve
	aMimi, aTok, aLm, aInfo = ai_serve.cached(("hibiki", aArgs.model_dir, str(aDevice)), lambda: load(aArgs.model_dir, aDevice, aDtype))
	torch.manual_seed(aArgs.seed)

	import sphn
	aSrc, aSr = sphn.read(aArgs.audio, sample_rate=aMimi.sample_rate)
	aSrc = np.asarray(aSrc, dtype=np.float32).mean(axis=0)
	aTotal = len(aSrc) / aSr
	aPieces = cut(aSrc, aSr, np)
	log("Audio: " + str(round(aTotal, 1)) + " s, " + str(len(aPieces)) + " pieces")

	out("STATUS TranslatingVoice")
	out("STEP 0 " + str(int(aTotal)))
	aMix = np.zeros(len(aSrc) + aSr * 10, dtype=np.float32)
	aWords = []
	aDone = 0.0
	for b in range(0, len(aPieces), BATCH):
		aBatch = aPieces[b:b + BATCH]
		aT0 = time.time()
		aBase = aDone
		aRes = translate(aMimi, aTok, aLm, aInfo, [aSrc[s:e] for s, e in aBatch], aDevice, torch,
			lambda aSec: out("STEP " + str(int(min(aTotal, aBase + aSec))) + " " + str(int(aTotal))))
		for (s, e), (aWav, aTimed) in zip(aBatch, aRes):
			# The translation comes a few seconds after the speech: put back at the time of the original speech
			aShift = max(0.0, onset(aWav, aSr, np) - onset(aSrc[s:e], aSr, np))
			aCut = int(aShift * aSr)
			aW = aWav[aCut:]
			o = s
			if o + len(aW) > len(aMix):
				aMix = np.concatenate([aMix, np.zeros(o + len(aW) - len(aMix), dtype=np.float32)])
			aMix[o:o + len(aW)] += aW
			for aText, aT in aTimed:
				aWords.append((aText, max(0.0, s / aSr + aT - aShift)))
			log("Piece " + str(round(s / aSr, 1)) + "-" + str(round(e / aSr, 1)) + " s: delay " + str(round(aShift, 2)) + " s")
			aDone += (e - s) / aSr
		log("Batch of " + str(len(aBatch)) + " in " + str(round(time.time() - aT0, 1)) + " s")
		out("STEP " + str(int(min(aTotal, aDone))) + " " + str(int(aTotal)))
	if aDevice.type == "cuda":
		out("PEAK hibiki " + str(torch.cuda.max_memory_reserved(aDevice)))
	aPeak = float(np.abs(aMix).max()) if len(aMix) else 0
	if aPeak > 0.97:
		aMix *= 0.97 / aPeak
	aEnd = len(aSrc)
	while aEnd < len(aMix) and abs(aMix[aEnd:aEnd + aSr]).max(initial=0) > 1e-3:
		aEnd += aSr
	os.makedirs(os.path.dirname(os.path.abspath(aArgs.out)), exist_ok=True)
	import wave
	with wave.open(aArgs.out, "wb") as aF:
		aF.setnchannels(1)
		aF.setsampwidth(2)
		aF.setframerate(aSr)
		aF.writeframes((np.clip(aMix[:aEnd], -1, 1) * 32767).astype("<i2").tobytes())
	if aArgs.srt:
		import speech_transcribe
		aTimedWords = []
		for i, (aText, aT) in enumerate(aWords):
			aNext = aWords[i + 1][1] if i + 1 < len(aWords) else aT + 0.5
			aTimedWords.append((aText, aT, max(aT + 0.08, min(aNext, aT + 0.8))))
		speech_transcribe.writeSrt(aArgs.srt, speech_transcribe.group(aTimedWords, False))
	log("Translated voice: " + str(round(aEnd / aSr, 1)) + " s -> " + aArgs.out)
	out("DONE " + aArgs.out)
	return 0


def load(aDir, aDevice, aDtype):
	from moshi.models import loaders
	out("STATUS LoadingVoiceModel")
	aD = Path(aDir)
	aInfo = loaders.CheckpointInfo.from_hf_repo("kyutai/hibiki-zero-3b-pytorch-bf16"
		, moshi_weights=aD / "hibiki-pytorch-77f82164@110.safetensors"
		, mimi_weights=aD / "mimi-pytorch-e351c8d8@125.safetensors"
		, tokenizer=aD / "tokenizer_spm_48k_multi6_2.model"
		, config_path=aD / "config.json")
	aMimi = aInfo.get_mimi(device=aDevice)
	aTok = aInfo.get_text_tokenizer()
	aLm = aInfo.get_moshi(device=aDevice, dtype=aDtype, fuse_lora=True)
	return aMimi, aTok, aLm, aInfo


def translate(aMimi, aTok, aLm, aInfo, aWavs, aDevice, torch, aProgress=None):
	"""[(translated wav (numpy, sample rate of Mimi), [(word, time in s)])] of each wav (numpy) of aWavs
	aProgress(seconds of sound of aWavs done) called every 2 s of sound"""
	from moshi.models import LMGen
	from moshi.run_inference import get_condition_tensors
	aSr = aMimi.sample_rate
	aDurations = [len(w) / aSr for w in aWavs]
	# Silence after the speech, so that the translation (a few seconds behind) can end
	aLen = max(len(w) for w in aWavs) + int(TAIL_S * aSr)
	aBatch = torch.zeros(len(aWavs), 1, aLen, dtype=torch.float32)
	for i, w in enumerate(aWavs):
		aBatch[i, 0, :len(w)] = torch.from_numpy(w)
	aB = len(aWavs)
	aCond = get_condition_tensors(aInfo.model_type, aLm, batch_size=aB, cfg_coef=1.0)
	aGen = LMGen(aLm, cfg_coef=1.0, condition_tensors=aCond, **aInfo.lm_gen_config)
	aFrame = int(aSr / aMimi.frame_rate)
	with torch.no_grad():
		aCodes = aMimi.encode(aBatch.to(aDevice))
		# End of each input marked (as add_input_eos() of hibiki_zero)
		aEos = torch.tensor([min(math.ceil(d * aMimi.frame_rate), aCodes.shape[-1] - 1) for d in aDurations])[:, None, None].to(aCodes.device)
		aIdx = torch.arange(0, aCodes.shape[-1])[None, None].to(aCodes.device)
		aCodes = torch.where(aIdx == aEos, torch.full([1], aMimi.cardinality, device=aCodes.device), aCodes)
		aWarm = aMimi.encode(torch.zeros(aB, 1, aFrame * aGen.max_delay, dtype=torch.float32, device=aCodes.device))
	aText = []
	aAudio = []
	with torch.no_grad(), aGen.streaming(aB):
		for s in range(aWarm.shape[-1]):
			aGen.step(aWarm[:, :, s:s + 1])
		for s in range(aCodes.shape[-1]):
			t = aGen.step(aCodes[:, :, s:s + 1])
			if aProgress is not None and s % 25 == 0:
				aT = s / aMimi.frame_rate
				aProgress(sum(min(aT, d) for d in aDurations))
			if t is not None:
				aText.append(t[:, 0, :])
				aAudio.append(t[:, 1:, :])
	aTexts = torch.concat(aText, dim=-1)
	with torch.no_grad():
		aOut = aMimi.decode(torch.concat(aAudio, dim=-1)).float().cpu()
	aRes = []
	for i in range(aB):
		aT = aTexts[i].tolist()
		aEnd = aT.index(aTok.eos_id()) if aTok.eos_id() in aT else len(aT)
		aWords = []
		for f, tok in enumerate(aT[:aEnd]):
			if tok <= aTok.pad_id():
				continue
			aPiece = aTok.id_to_piece(tok)
			aTime = f / aMimi.frame_rate
			# A number glued to a word (no word start in its tokens) starts a new word
			aGlued = bool(aWords) and aPiece[:1].isdigit() and aWords[-1][0][-1:].isalpha()
			if aPiece.startswith("▁") or not aWords or aGlued:
				w = aPiece.replace("▁", "").strip()
				if w:
					aWords.append([w, aTime])
			else:
				aWords[-1][0] += aPiece
		aWav = aOut[i, 0, :int(aEnd * aSr / aMimi.frame_rate)].numpy()
		aRes.append((aWav, [tuple(w) for w in aWords]))
	return aRes


def energy(aAudio, aSr, np):
	aN = int(FRAME_S * aSr)
	aF = len(aAudio) // aN
	if aF <= 0:
		return np.zeros(0, dtype=np.float32)
	return np.sqrt((aAudio[:aF * aN].reshape(aF, aN) ** 2).mean(axis=1) + 1e-12)


def onset(aAudio, aSr, np):
	"""Time of the first speech (s)"""
	aE = energy(aAudio, aSr, np)
	if len(aE) == 0:
		return 0.0
	aOn = np.nonzero(aE >= max(np.percentile(aE, 95) * 0.1, 10 ** (-45 / 20)))[0]
	return aOn[0] * FRAME_S if len(aOn) else 0.0


def cut(aAudio, aSr, np):
	"""Pieces (start, end in samples) of at most PIECE_S s cut at the quietest point near their end"""
	aE = energy(aAudio, aSr, np)
	if len(aE) == 0:
		return []
	aN = int(FRAME_S * aSr)
	aMax = int(PIECE_S / FRAME_S)
	aWin = int(CUT_WINDOW_S / FRAME_S)
	aK = max(1, int(0.4 / FRAME_S))
	aSmooth = np.convolve(aE, np.ones(aK) / aK, mode="same")
	aPieces = []
	f = 0
	while f < len(aE):
		if len(aE) - f <= aMax:
			aEnd = len(aE)
		else:
			aLo = f + aMax - aWin
			aEnd = aLo + int(np.argmin(aSmooth[aLo:f + aMax]))
		aPieces.append((f * aN, min(len(aAudio), aEnd * aN)))
		f = aEnd
	return aPieces


if __name__ == "__main__":
	# One translation, or resident (--serve: the model stays loaded between the requests)
	import ai_serve
	ai_serve.run(main)
