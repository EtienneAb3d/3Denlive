# Subtitles of the speech of a video for 3Denlive (run by com.cubaix.TDenlive.ai.SpeechGenerator).
#
# Speech recognition, then the words with their times grouped into subtitles (SRT):
#  - qwen3: Qwen/Qwen3-ASR-1.7B-hf (Apache-2.0, 30 languages + 22 Chinese dialects, language identified), its
#    words timed by Qwen/Qwen3-ForcedAligner-0.6B-hf (Apache-2.0, 11 languages: zh en yue fr de it ja ko pt ru es;
#    in another language the times of the words are spread over the speech of each piece);
#  - parakeet: nvidia/parakeet-tdt-0.6b-v3 (CC-BY-4.0, 25 European languages, much faster), whose transducer gives
#    the time of each token itself.
# Both are native in transformers. Unlike Whisper (an encoder-decoder trained on weakly labelled subtitles),
# neither invents text on silences or music; the pieces with no speech energy are not even given to the model.
# The audio (16 kHz mono s16le raw, extracted by 3Denlive with ffmpeg) is cut into pieces of at most PIECE_S
# seconds at the quietest point near their end.
# CUDA, MPS or the CPU (--device cpu forces it); bf16 on a GPU, fp32 on the CPU.
#
# Protocol on stdout, one line per message:
#  STATUS <key>              step (key of the AI.* language entries)
#  DEVICE <cuda|mps|cpu>     device used
#  STEP <done> <total>       seconds of audio done
#  LANG <name>               language recognized (English name, "" if unknown)
#  PEAK <stage> <bytes>      peak GPU memory reserved (CUDA)
#  DONE <path>               SRT written (UTF-8)
#  ERROR <OOM|text>          failure
import argparse
import os
import re
import sys
import time

SR = 16000
# Longest piece given to the recognizer and to the aligner (s), and the window where its end is looked for
PIECE_S = 30.0
CUT_WINDOW_S = 10.0
# Analysis frames of the energy (s)
FRAME_S = 0.02
# Subtitles: characters per line, lines, longest duration, pause that always starts a new subtitle (s)
LINE_CHARS = 42
MAX_LINES = 2
MAX_CUE_S = 6.0
PAUSE_S = 0.7
MIN_CUE_S = 1.0
# Languages written without spaces between the words
NO_SPACE = {"Chinese", "Japanese", "Cantonese"}
ALIGNER_LANGUAGES = {"Chinese", "English", "Cantonese", "French", "German", "Italian", "Japanese", "Korean"
	, "Portuguese", "Russian", "Spanish"}
# Sentence ends and softer breaks
END_PUNCT = tuple(".?!…。？！")
SOFT_PUNCT = tuple(",;:，；：、")


def out(*aArgs):
	print(*aArgs, flush=True)


def log(aText):
	print(aText, file=sys.stderr, flush=True)


def main(aArgv):
	aP = argparse.ArgumentParser()
	aP.add_argument("--engine", choices=["qwen3", "parakeet"], required=True)
	aP.add_argument("--model-dir", required=True)
	aP.add_argument("--aligner-dir")
	aP.add_argument("--audio", required=True)
	aP.add_argument("--language", default="")
	aP.add_argument("--out", required=True)
	aP.add_argument("--device", default="auto")
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
	aDtype = torch.float32 if aDevice.type == "cpu" else torch.bfloat16

	import ai_serve
	aModels = ai_serve.cached(("speech", aArgs.engine, aArgs.model_dir, aArgs.aligner_dir, str(aDevice))
		, lambda: load(aArgs, aDevice, aDtype, torch))

	aAudio = np.fromfile(aArgs.audio, dtype="<i2").astype(np.float32) / 32768.0
	aTotal = len(aAudio) / SR
	log("Audio: " + str(round(aTotal, 1)) + " s")
	aPieces = cut(aAudio, np)
	out("STATUS Transcribing")
	out("STEP 0 " + str(int(aTotal)))
	aWords = []
	aLangs = {}
	for aStart, aEnd in aPieces:
		aT0 = time.time()
		aSamples = aAudio[aStart:aEnd]
		if aArgs.engine == "qwen3":
			aW, aLang = qwen3(aModels, aSamples, aArgs.language, aDevice, aDtype, torch, np)
		else:
			aW, aLang = parakeet(aModels, aSamples, aDevice, aDtype, torch)
		if aLang:
			aLangs[aLang] = aLangs.get(aLang, 0) + (aEnd - aStart)
		for aWord in aW:
			aWords.append((aWord[0], aWord[1] + aStart / SR, aWord[2] + aStart / SR))
		log("Piece " + str(round(aStart / SR, 1)) + "-" + str(round(aEnd / SR, 1)) + " s (" + str(aLang) + "): "
			+ str(len(aW)) + " words in " + str(round(time.time() - aT0, 1)) + " s")
		out("STEP " + str(int(aEnd / SR)) + " " + str(int(aTotal)))
	if aDevice.type == "cuda":
		out("PEAK speech " + str(torch.cuda.max_memory_reserved(aDevice)))
	aLang = max(aLangs, key=aLangs.get) if aLangs else (aArgs.language or "")
	out("LANG " + aLang)
	aCues = group(aWords, aLang in NO_SPACE)
	writeSrt(aArgs.out, aCues)
	log(str(len(aCues)) + " subtitles -> " + aArgs.out)
	out("DONE " + aArgs.out)
	return 0


def load(aArgs, aDevice, aDtype, torch):
	from transformers import AutoProcessor
	out("STATUS LoadingSpeechModel")
	aM = {}
	if aArgs.engine == "qwen3":
		from transformers import Qwen3ASRForConditionalGeneration, Qwen3ASRForTokenClassification
		aM["proc"] = AutoProcessor.from_pretrained(aArgs.model_dir)
		aM["model"] = Qwen3ASRForConditionalGeneration.from_pretrained(aArgs.model_dir, dtype=aDtype).to(aDevice).eval()
		if aArgs.aligner_dir:
			aM["aproc"] = AutoProcessor.from_pretrained(aArgs.aligner_dir)
			aM["aligner"] = Qwen3ASRForTokenClassification.from_pretrained(aArgs.aligner_dir, dtype=aDtype).to(aDevice).eval()
	else:
		from transformers import ParakeetForTDT
		aM["proc"] = AutoProcessor.from_pretrained(aArgs.model_dir)
		aM["model"] = ParakeetForTDT.from_pretrained(aArgs.model_dir, dtype=aDtype).to(aDevice).eval()
	return aM


def energy(aAudio, np):
	aN = int(FRAME_S * SR)
	aF = len(aAudio) // aN
	if aF <= 0:
		return np.zeros(0, dtype=np.float32)
	return np.sqrt((aAudio[:aF * aN].reshape(aF, aN) ** 2).mean(axis=1) + 1e-12)


def cut(aAudio, np):
	"""Pieces (start, end in samples) of at most PIECE_S s cut at the quietest point near their end, without the
	stretches of silence (no speech energy: nothing to recognize, nothing to invent)"""
	aE = energy(aAudio, np)
	if len(aE) == 0:
		return []
	# Silence: well under the level of the speech (adaptive, 30 dB under the loud frames), and absolute floor
	aLoud = np.percentile(aE, 95)
	aSilence = max(aLoud * 0.03, 10 ** (-50 / 20))
	aPieces = []
	aN = int(FRAME_S * SR)
	aMax = int(PIECE_S / FRAME_S)
	aWin = int(CUT_WINDOW_S / FRAME_S)
	# Smoothed energy (0.3 s) to find the pauses
	aK = max(1, int(0.3 / FRAME_S))
	aSmooth = np.convolve(aE, np.ones(aK) / aK, mode="same")
	f = 0
	while f < len(aE):
		# Leading silence skipped (0.2 s kept before the speech)
		while f < len(aE) and aE[f] < aSilence:
			f += 1
		if f >= len(aE):
			break
		aS = max(0, f - int(0.2 / FRAME_S))
		if len(aE) - aS <= aMax:
			aEnd = len(aE)
		else:
			aLo = aS + aMax - aWin
			aEnd = aLo + int(np.argmin(aSmooth[aLo:aS + aMax]))
		# Trailing silence skipped (0.3 s kept after the speech)
		aLast = aEnd
		while aLast > aS and aE[aLast - 1] < aSilence:
			aLast -= 1
		if aLast > aS:
			aPieces.append((aS * aN, min(len(aAudio), min(aEnd, aLast + int(0.3 / FRAME_S)) * aN)))
		f = max(aEnd, aS + 1)
	return aPieces


def qwen3(aM, aSamples, aLanguage, aDevice, aDtype, torch, np):
	aProc = aM["proc"]
	aIn = aProc.apply_transcription_request(audio=[aSamples], language=aLanguage or None)
	aIn = aIn.to(aDevice, aDtype)
	with torch.inference_mode():
		aIds = aM["model"].generate(**aIn, max_new_tokens=int(len(aSamples) / SR * 12) + 32, do_sample=False)
	aParsed = aProc.decode(aIds[:, aIn["input_ids"].shape[1]:], return_format="parsed")[0]
	aText = (aParsed.get("transcription") or "").strip()
	aLang = aLanguage or aParsed.get("language") or ""
	if not aText:
		return [], aLang
	if "aligner" in aM and aLang in ALIGNER_LANGUAGES:
		aAP = aM["aproc"]
		aAIn, aWordLists = aAP.prepare_forced_aligner_inputs(audio=[aSamples], transcript=[aText], language=aLang)
		aAIn = aAIn.to(aDevice, aDtype)
		with torch.inference_mode():
			aLogits = aM["aligner"](**aAIn).logits
		aTimes = aAP.decode_forced_alignment(logits=aLogits, input_ids=aAIn["input_ids"], word_lists=aWordLists
			, timestamp_token_id=aM["aligner"].config.timestamp_token_id)[0]
		aAligned = [(t["text"], float(t["start_time"]), float(t["end_time"])) for t in aTimes]
		return attachPunctuation(aText, aAligned, aLang in NO_SPACE), aLang
	return spread(aText, aSamples, aLang in NO_SPACE, np), aLang


def parakeet(aM, aSamples, aDevice, aDtype, torch):
	aProc = aM["proc"]
	aIn = aProc(aSamples, sampling_rate=SR, return_tensors="pt").to(aDevice, aDtype)
	with torch.inference_mode():
		aOut = aM["model"].generate(**aIn, return_dict_in_generate=True)
	aText, aTimes = aProc.decode(aOut.sequences[0:1], durations=aOut.durations[0:1], skip_special_tokens=True)
	aWords = []
	for t in aTimes[0]:
		aTok = t["token"]
		if not aTok:
			continue
		if aTok[0].isspace() or not aWords:
			if aTok.strip():
				aWords.append([aTok.strip(), t["start"], t["end"]])
		else:
			aWords[-1][0] += aTok
			aWords[-1][2] = max(aWords[-1][2], t["end"])
	return [tuple(w) for w in aWords], ""


def core(aS):
	return "".join(c for c in aS.lower() if c.isalnum())


def attachPunctuation(aText, aAligned, aNoSpace):
	"""Words of the transcript (with their punctuation and case) timed by the aligned words (without them)"""
	if aNoSpace:
		aTokens = [c for c in aText if not c.isspace()]
	else:
		aTokens = aText.split()
	aWords = []
	a = 0
	for aTok in aTokens:
		aCore = core(aTok)
		if not aCore:
			# Punctuation alone: joined to the previous word
			if aWords:
				aWords[-1][0] += ("" if aNoSpace or aTok in ",.;:!?…" else " ") + aTok
			continue
		aStart = None
		aEnd = None
		aGot = ""
		while a < len(aAligned) and len(aGot) < len(aCore):
			aGot += core(aAligned[a][0])
			aStart = aAligned[a][1] if aStart is None else aStart
			aEnd = aAligned[a][2]
			a += 1
		if aStart is None:
			# Aligner ended early: at the end of the previous word
			aStart = aEnd = aWords[-1][2] if aWords else 0.0
		aWords.append([aTok, aStart, aEnd])
	return [tuple(w) for w in aWords]


def spread(aText, aSamples, aNoSpace, np):
	"""No aligner for this language: the words spread over the speech of the piece in proportion of their length"""
	aTokens = [c for c in aText if not c.isspace()] if aNoSpace else aText.split()
	aE = energy(aSamples, np)
	aSpeech = np.nonzero(aE >= max(np.percentile(aE, 95) * 0.03, 10 ** (-50 / 20)))[0] if len(aE) else []
	aS = aSpeech[0] * FRAME_S if len(aSpeech) else 0.0
	aD = (aSpeech[-1] + 1) * FRAME_S - aS if len(aSpeech) else len(aSamples) / SR
	aTotal = sum(len(t) + 1 for t in aTokens) or 1
	aWords = []
	aPos = 0
	for t in aTokens:
		aW0 = aS + aD * aPos / aTotal
		aPos += len(t) + 1
		aWords.append((t, aW0, aS + aD * (aPos - 1) / aTotal))
	return aWords


def join(aWords, aNoSpace):
	return ("" if aNoSpace else " ").join(w[0] for w in aWords).strip()


def group(aWords, aNoSpace):
	"""Subtitles (start, end, text in 1 or 2 lines) from the timed words: a new one at a long pause, at the end of a
	sentence, at a comma once long enough, before it gets too long to read"""
	aMaxChars = LINE_CHARS * MAX_LINES
	aCues = []
	aCur = []
	for i, w in enumerate(aWords):
		if aCur:
			aText = join(aCur + [w], aNoSpace)
			aPrev = aCur[-1][0]
			aFull = len(aText) > aMaxChars or w[2] - aCur[0][1] > MAX_CUE_S
			aBreak = (w[1] - aCur[-1][2] >= PAUSE_S
				or (aPrev.endswith(END_PUNCT) and len(join(aCur, aNoSpace)) >= 12)
				or (aPrev.endswith(SOFT_PUNCT) and len(join(aCur, aNoSpace)) >= aMaxChars * 0.6))
			if aFull and not aBreak:
				# Too long: cut after the last punctuation of its second half or so, else here
				for k in range(len(aCur) - 2, max(0, len(aCur) // 3) - 1, -1):
					if aCur[k][0].endswith(END_PUNCT + SOFT_PUNCT):
						aCues.append(aCur[:k + 1])
						aCur = aCur[k + 1:]
						break
				else:
					# No punctuation: not after a short word (article, preposition), carried to the next one
					k = len(aCur)
					while k > max(1, len(aCur) - 2) and not aNoSpace and len(core(aCur[k - 1][0])) <= 3:
						k -= 1
					aCues.append(aCur[:k])
					aCur = aCur[k:]
			if aBreak:
				aCues.append(aCur)
				aCur = []
		aCur.append(w)
	if aCur:
		aCues.append(aCur)
	aRes = []
	for k, c in enumerate(aCues):
		aStart = c[0][1]
		aEnd = c[-1][2] + 0.2
		aNext = aCues[k + 1][0][1] if k + 1 < len(aCues) else None
		# Long enough to be read, never over the next one
		aEnd = max(aEnd, aStart + MIN_CUE_S)
		if aNext is not None:
			aEnd = min(aEnd, aNext - 0.04)
		aEnd = max(aEnd, aStart + 0.3)
		aRes.append((aStart, aEnd, wrap(join(c, aNoSpace), aNoSpace)))
	return aRes


def wrap(aText, aNoSpace):
	"""Two balanced lines when too long for one"""
	if len(aText) <= LINE_CHARS:
		return aText
	if aNoSpace:
		aMid = len(aText) // 2
		return aText[:aMid] + "\n" + aText[aMid:]
	aBest = None
	for m in re.finditer(" ", aText):
		aD = abs(m.start() - len(aText) / 2)
		if aBest is None or aD < aBest[0]:
			aBest = (aD, m.start())
	if aBest is None:
		return aText
	return aText[:aBest[1]] + "\n" + aText[aBest[1] + 1:]


def clock(aS):
	aMs = int(round(aS * 1000))
	return "%02d:%02d:%02d,%03d" % (aMs // 3600000, aMs // 60000 % 60, aMs // 1000 % 60, aMs % 1000)


def writeSrt(aPath, aCues):
	os.makedirs(os.path.dirname(os.path.abspath(aPath)), exist_ok=True)
	with open(aPath, "w", encoding="utf-8") as aF:
		for i, (s, e, t) in enumerate(aCues):
			aF.write(str(i + 1) + "\n" + clock(s) + " --> " + clock(e) + "\n" + t + "\n\n")


if __name__ == "__main__":
	# One transcription, or resident (--serve: the models stay loaded between the requests)
	import ai_serve
	ai_serve.run(main)
