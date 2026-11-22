# Separation of the tracks of a sound for 3Denlive (run by com.cubaix.TDenlive.ai.RemixGenerator).
#
# Hybrid Transformer Demucs (Rouard, Massa, Defossez, ICASSP 2023, https://github.com/facebookresearch/demucs,
# code and weights MIT): the sound is split into drums, bass, other and vocals (+ guitar and piano for the 6 sources
# model). The demucs package is used for its models only (no torchaudio input / output): the weights (.th files of
# dl.fbaipublicfiles.com, downloaded by 3Denlive into --model-dir) are loaded with torch.load (weights_only=False:
# the files hold the class and the arguments of the model), several files make a bag of models (the fine tuned
# model: one model per source). The input is a WAV at 44.1 kHz stereo extracted by ffmpeg; it is normalized as the
# command line of demucs does, split into overlapping segments (apply_model), and each source is written as a
# 16 bits WAV at 44.1 kHz.
# CUDA, MPS or the CPU (--device cpu forces it); on a failure of the GPU, the CPU is used.
#
# Protocol on stdout, one line per message:
#  STATUS <key>              step (key of the AI.* language entries)
#  DEVICE <cuda|mps|cpu>     device used
#  STEP <done> <total>       segments separated
#  STEM <name> <path>        a source written
#  PEAK <stage> <bytes>      peak GPU memory reserved (CUDA)
#  DONE <folder>             all the sources written
#  ERROR <OOM|text>          failure
import argparse
import os
import sys
import time

import ai_serve


def out(*aArgs):
	print(*aArgs, flush=True)


def load(aArgs, aDevice):
	import torch
	from demucs.states import load_model
	from demucs.apply import BagOfModels
	aModels = []
	for aF in aArgs.weights:
		aPkg = torch.load(os.path.join(aArgs.model_dir, aF), map_location="cpu", weights_only=False)
		aM = load_model(aPkg)
		aM.eval()
		aModels.append(aM)
	if len(aModels) == 1:
		aModel = aModels[0]
	else:
		# One model per source (htdemucs_ft): model k gives source k
		aN = len(aModels)
		aWeights = [[1.0 if s == k else 0.0 for s in range(aN)] for k in range(aN)]
		aModel = BagOfModels(aModels, aWeights)
	aModel.to(aDevice)
	return aModel


def separate(aModel, aMix, aDevice, aShifts):
	import torch
	import demucs.apply as apply_mod

	# Progress: apply_model wraps its segments in tqdm when progress is asked; one wrapper per model of the bag
	# and per shift
	aCalls = [0]
	aParts = (len(aModel.models) if hasattr(aModel, "models") else 1) * max(1, aShifts)

	class Steps:
		def __init__(self, aIt, **aKw):
			self.it = list(aIt)
			self.call = aCalls[0]
			aCalls[0] += 1

		def __iter__(self):
			aN = len(self.it)
			for i, aX in enumerate(self.it):
				yield aX
				out("STEP", self.call * aN + i + 1, aParts * aN)

	aOld = apply_mod.tqdm.tqdm
	apply_mod.tqdm.tqdm = Steps
	try:
		with torch.no_grad():
			return apply_mod.apply_model(aModel, aMix[None], device=aDevice, shifts=aShifts, split=True
					, overlap=0.25, progress=True, num_workers=0)[0]
	finally:
		apply_mod.tqdm.tqdm = aOld


def main(aArgv):
	aP = argparse.ArgumentParser()
	aP.add_argument("--model-dir", required=True)
	aP.add_argument("--model", required=True)
	# Weight files of the model (several: a bag, one model per source)
	aP.add_argument("--weights", action="append", required=True)
	aP.add_argument("--device", default="auto")
	aP.add_argument("--input")
	aP.add_argument("--out-dir")
	aP.add_argument("--shifts", type=int, default=1)
	aArgs = aP.parse_args(aArgv)
	if not aArgs.input or not aArgs.out_dir:
		raise ValueError("--input and --out-dir needed")

	out("STATUS Starting")
	import numpy as np
	import soundfile as sf
	import torch

	if aArgs.device != "cpu" and torch.cuda.is_available():
		aDevice = torch.device("cuda", 0)
	elif aArgs.device != "cpu" and getattr(torch.backends, "mps", None) is not None and torch.backends.mps.is_available():
		aDevice = torch.device("mps")
	else:
		aDevice = torch.device("cpu")

	out("STATUS RemixLoading")
	aKey = (aArgs.model_dir, tuple(aArgs.weights), str(aDevice))
	aModel = ai_serve.cached(aKey, lambda: load(aArgs, aDevice))
	out("DEVICE", aDevice.type)

	aWav, aRate = sf.read(aArgs.input, dtype="float32", always_2d=True)
	if aRate != aModel.samplerate:
		raise ValueError("sample rate %d, %d expected" % (aRate, aModel.samplerate))
	aMix = torch.from_numpy(aWav.T.copy())
	if aMix.shape[0] == 1:
		aMix = aMix.repeat(2, 1)
	aMix = aMix[:aModel.audio_channels]
	if aMix.shape[1] < aRate // 10:
		raise ValueError("NOSOUND")
	# Normalization of the demucs command line
	aRef = aMix.mean(0)
	aMean = aRef.mean()
	aStd = aRef.std().clamp(min=1e-8)
	aIn = (aMix - aMean) / aStd

	out("STATUS RemixSeparating")
	aT0 = time.time()
	if aDevice.type == "cuda":
		torch.cuda.reset_peak_memory_stats()
	try:
		aSources = separate(aModel, aIn, aDevice, aArgs.shifts)
	except Exception as e:
		if aDevice.type == "cpu" or "out of memory" in str(e).lower():
			raise
		# A GPU without an operation of the model: the CPU
		print("Separation failed on " + aDevice.type + ", CPU used: " + str(e), file=sys.stderr, flush=True)
		aDevice = torch.device("cpu")
		aModel.to(aDevice)
		out("DEVICE", "cpu")
		aSources = separate(aModel, aIn, aDevice, aArgs.shifts)
	if aDevice.type == "cuda":
		out("PEAK", "separation", torch.cuda.max_memory_reserved())
	print("Separation: %.1f s for %.1f s of sound" % (time.time() - aT0, aMix.shape[1] / aRate), file=sys.stderr, flush=True)
	aSources = aSources * aStd + aMean

	out("STATUS RemixWriting")
	os.makedirs(aArgs.out_dir, exist_ok=True)
	for aName, aS in zip(aModel.sources, aSources):
		aPath = os.path.join(aArgs.out_dir, aName + ".wav")
		aData = aS.cpu().numpy().T
		sf.write(aPath, np.clip(aData, -1.0, 1.0), aRate, subtype="PCM_16")
		out("STEM", aName, aPath)
	out("DONE", aArgs.out_dir)
	return 0


if __name__ == "__main__":
	ai_serve.run(main)
