# MusicGen text to music for 3Denlive (run by com.cubaix.TDenlive.ai.SoundGenerator).
#
# facebook/musicgen-small / medium / large (transformers MusicgenForConditionalGeneration, CC-BY-NC-4.0 weights):
# a T5 text encoder, an autoregressive decoder of EnCodec tokens (50 per second, 4 codebooks with a delay pattern)
# and the EnCodec decoder, 32 kHz mono. Classifier free guidance (guidance 3), sampling top-k 250.
# Runs on CUDA, MPS or the CPU (--device cpu forces it). fp16 on CUDA, fp32 elsewhere.
# The model files are downloaded by 3Denlive before (no network access here).
# --continue <wav>: the sound of the file continued up to --seconds in all (the file itself at the start of the
# output). The model sees at most WINDOW seconds (the given audio included): a longer sound is continued window
# after window, each one from the last CONTEXT seconds.
#
# Protocol on stdout, one line per message:
#  STATUS <key>              step (key of the AI.* language entries)
#  DEVICE <cuda|mps|cpu>     device used
#  STEP <done> <total>       generated tokens (EnCodec frames)
#  PEAK <stage> <bytes>      peak GPU memory reserved (CUDA)
#  DONE <path>               WAV written
#  ERROR <OOM|text>          failure
import argparse
import os
import random
import sys
import traceback
import wave

os.environ.setdefault("HF_HUB_OFFLINE", "1")
os.environ.setdefault("TRANSFORMERS_OFFLINE", "1")


# Seconds seen by the model at once, and seconds of the end given back as the start of the next window
WINDOW = 30.0
CONTEXT = 20.0
# Crossfade between the given sound and the continuation
FADE = 0.05


def out(*aArgs):
	print(*aArgs, flush=True)


def readWav(np, aPath, aRate):
	"""WAV file as mono float32 -1..1 at aRate (linear resampling)"""
	with wave.open(aPath, "rb") as aW:
		aCh = aW.getnchannels()
		aWidth = aW.getsampwidth()
		aR = aW.getframerate()
		aData = aW.readframes(aW.getnframes())
	if aWidth != 2:
		raise ValueError("16 bit WAV expected: " + aPath)
	aA = np.frombuffer(aData, dtype="<i2").astype(np.float32) / 32768.0
	aA = aA.reshape(-1, aCh).mean(axis=1)
	if aR != aRate and len(aA) > 1:
		aN = int(round(len(aA) * aRate / aR))
		aA = np.interp(np.arange(aN) * (aR / aRate), np.arange(len(aA)), aA).astype(np.float32)
	return aA


def main(aArgv):
	aP = argparse.ArgumentParser()
	aP.add_argument("--model-dir", required=True)
	aP.add_argument("--prompt-file", required=True)
	aP.add_argument("--seconds", type=float, default=10.0)
	aP.add_argument("--guidance", type=float, default=3.0)
	aP.add_argument("--device", default="auto")
	aP.add_argument("--seed", type=int, default=-1)
	aP.add_argument("--continue", dest="cont", default=None)
	# This file created by 3Denlive: the generation stops (ERROR STOPPED, the model stays loaded)
	aP.add_argument("--stop-file", default=None)
	aP.add_argument("--out", required=True)
	aArgs = aP.parse_args(aArgv)
	with open(aArgs.prompt_file, encoding="utf-8") as aF:
		aPrompt = aF.read().strip()

	out("STATUS Starting")
	import numpy as np
	import torch

	if aArgs.device != "cpu" and torch.cuda.is_available():
		aDevice = torch.device("cuda", 0)
	elif aArgs.device != "cpu" and getattr(torch.backends, "mps", None) is not None and torch.backends.mps.is_available():
		aDevice = torch.device("mps")
	else:
		aDevice = torch.device("cpu")
	aCuda = aDevice.type == "cuda"
	out("DEVICE " + aDevice.type)
	print("Device: " + str(aDevice) + (" " + torch.cuda.get_device_name(0) if aCuda else ""), file=sys.stderr, flush=True)
	if aDevice.type == "cpu":
		torch.set_num_threads(max(1, os.cpu_count() or 1))

	from transformers import AutoProcessor, MusicgenForConditionalGeneration
	from transformers import LogitsProcessor, LogitsProcessorList

	aSeed = aArgs.seed if aArgs.seed >= 0 else random.randint(0, 2**31 - 1)
	print("Seed: " + str(aSeed), file=sys.stderr, flush=True)
	torch.manual_seed(aSeed)

	out("STATUS LoadingMusic")
	aDType = torch.float16 if aCuda else torch.float32

	def load():
		aPr = AutoProcessor.from_pretrained(aArgs.model_dir)
		aMo = MusicgenForConditionalGeneration.from_pretrained(aArgs.model_dir, dtype=aDType)
		aMo.to(aDevice)
		aMo.eval()
		return aPr, aMo

	# Loaded once in resident mode
	import ai_serve
	aProc, aModel = ai_serve.cached(("musicgen", aArgs.model_dir, str(aDevice)), load)

	aRate = aModel.config.audio_encoder.sampling_rate
	aFrameRate = getattr(aModel.config.audio_encoder, "frame_rate", None) or 50
	# The delay pattern costs (codebooks - 1) steps before the first full frame
	aCodebooks = aModel.config.decoder.num_codebooks

	def tokens(aSeconds):
		return int(round(aSeconds * aFrameRate)) + aCodebooks - 1

	# The start given (--continue), then the windows to generate
	aStart = readWav(np, aArgs.cont, aRate) if aArgs.cont else None
	aHave = 0.0 if aStart is None else len(aStart) / aRate
	aPlan = []
	aT = aHave
	aCtx = 0.0 if aStart is None else min(aHave, CONTEXT)
	while aT < aArgs.seconds - 0.05:
		aNew = min(aArgs.seconds - aT, WINDOW - aCtx)
		aPlan.append(aNew)
		aT += aNew
		aCtx = min(aT, CONTEXT)
	aTotal = sum(tokens(x) for x in aPlan)

	# Called once per generated token (a streamer is not passed to the sampling loop by every transformers version)
	class Progress(LogitsProcessor):
		def __init__(self):
			self.n = 0

		def __call__(self, aIds, aScores):
			self.n += 1
			if aArgs.stop_file and self.n % 10 == 0 and os.path.exists(aArgs.stop_file):
				raise ai_serve.Stopped()
			if self.n % 5 == 0 or self.n >= aTotal:
				out("STEP " + str(min(self.n, aTotal)) + " " + str(aTotal))
			return aScores

	out("STATUS GeneratingMusic")
	aProgress = Progress()
	aA = aStart
	for aNew in aPlan:
		if aA is None:
			aIn = aProc(text=[aPrompt], padding=True, return_tensors="pt").to(aDevice)
			aSkip = 0
		else:
			aCtxA = aA[-int(round(CONTEXT * aRate)):]
			aIn = aProc(audio=aCtxA, sampling_rate=aRate, text=[aPrompt], padding=True, return_tensors="pt").to(aDevice)
			aSkip = len(aCtxA)
		if aCuda and "input_values" in aIn:
			aIn["input_values"] = aIn["input_values"].to(aDType)
		with torch.no_grad():
			aOut = aModel.generate(**aIn, do_sample=True, guidance_scale=aArgs.guidance, max_new_tokens=tokens(aNew)
				, logits_processor=LogitsProcessorList([aProgress]))
		# (a new list per call: generate() adds its own processors to it)
		# (1, channels, samples) in -1..1: the given context (encoded again: not the same samples) first, then
		# the new part, joined to the given sound by a short crossfade (else a click)
		aG = aOut[0].float().cpu().numpy().mean(axis=0)
		if os.environ.get("MUSICGEN_DEBUG"):
			np.save(os.environ["MUSICGEN_DEBUG"], aG)
		if aA is None:
			aA = aG[:int(round(aNew * aRate))]
		else:
			aF = min(int(FADE * aRate), aSkip)
			aG = aG[aSkip - aF:aSkip + int(round(aNew * aRate))]
			aW = np.linspace(0, 1, aF, dtype=np.float32)
			aA = np.concatenate([aA[:len(aA) - aF], aA[len(aA) - aF:] * (1 - aW) + aG[:aF] * aW, aG[aF:]])
	if aCuda:
		out("PEAK musicgen " + str(torch.cuda.max_memory_reserved(aDevice)))

	out("STATUS WritingMusic")
	aA = aA[:int(round(aArgs.seconds * aRate))].reshape(1, -1)
	aPeak = float(np.abs(aA).max()) if aA.size > 0 else 0.0
	if aPeak > 0.99:
		aA = aA * (0.99 / aPeak)
	aPcm = (np.clip(aA, -1, 1) * 32767).round().astype("<i2")
	with wave.open(aArgs.out, "wb") as aW:
		aW.setnchannels(aPcm.shape[0])
		aW.setsampwidth(2)
		aW.setframerate(aRate)
		aW.writeframes(np.ascontiguousarray(aPcm.T).tobytes())
	out("DONE " + aArgs.out)
	return 0


if __name__ == "__main__":
	# One generation, or resident (--serve: the model stays loaded between the requests)
	import ai_serve
	ai_serve.run(main)
