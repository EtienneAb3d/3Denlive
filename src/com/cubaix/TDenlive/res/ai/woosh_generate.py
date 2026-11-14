# Woosh text to sound effects for 3Denlive (run by com.cubaix.TDenlive.ai.SoundGenerator).
#
# Woosh (Sony AI, https://github.com/SonyResearch/Woosh, code MIT, weights CC-BY-NC-4.0): Woosh-AE (Vocos
# autoencoder, 48 kHz mono, 100 latent frames per second), TextConditionerA (CLAP text encoder on RoBERTa-large)
# and a latent flow model: Woosh-Flow (flow matching, adaptive ODE solver dopri5) or Woosh-DFlow (distilled
# flow map, 4 Euler steps). Trained on 5 s (501 latent frames): longer sounds are generated in one piece up to
# MAX_FRAMES (the RoPE of the model is made for twice its training length).
# Runs on CUDA, MPS or the CPU (--device cpu forces it), fp32 (float64 for the ODE on CUDA / CPU, as the
# examples of the authors).
# The inference code (--code-dir: the woosh package) and the model files (--model-dir: checkpoints/<name>/,
# roberta-large/ tokenizer and configuration) are downloaded by 3Denlive before (no network access here).
#
# Protocol on stdout, one line per message:
#  STATUS <key>              step (key of the AI.* language entries)
#  DEVICE <cuda|mps|cpu>     device used
#  STEP <done> <total>       denoising steps (Woosh-DFlow; Woosh-Flow: number of model evaluations, no total)
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

RATE = 48000
FRAMES_PER_SECOND = 100
# 5 s of training, twice at most
TRAIN_FRAMES = 501
MAX_FRAMES = 1001


def out(*aArgs):
	print(*aArgs, flush=True)


def main(aArgv):
	aP = argparse.ArgumentParser()
	aP.add_argument("--model-dir", required=True)
	aP.add_argument("--code-dir", required=True)
	aP.add_argument("--variant", default="Woosh-DFlow")
	aP.add_argument("--prompt-file", required=True)
	aP.add_argument("--seconds", type=float, default=5.0)
	aP.add_argument("--steps", type=int, default=4)
	aP.add_argument("--guidance", type=float, default=4.5)
	aP.add_argument("--device", default="auto")
	aP.add_argument("--seed", type=int, default=-1)
	aP.add_argument("--out", required=True)
	aArgs = aP.parse_args(aArgv)
	with open(aArgs.prompt_file, encoding="utf-8") as aF:
		aPrompt = aF.read().strip()
	aOut = os.path.abspath(aArgs.out)

	out("STATUS Starting")
	# The configurations name their components by paths relative to the model folder (checkpoints/...), and
	# the text encoder loads "roberta-large", found there as a folder
	os.chdir(aArgs.model_dir)
	if aArgs.code_dir not in sys.path:
		sys.path.insert(0, aArgs.code_dir)
	import numpy as np
	import torch

	if aArgs.device != "cpu" and torch.cuda.is_available():
		aDevice = "cuda"
	elif aArgs.device != "cpu" and getattr(torch.backends, "mps", None) is not None and torch.backends.mps.is_available():
		aDevice = "mps"
	else:
		aDevice = "cpu"
	aCuda = aDevice == "cuda"
	out("DEVICE " + aDevice)
	print("Device: " + aDevice + (" " + torch.cuda.get_device_name(0) if aCuda else ""), file=sys.stderr, flush=True)
	if aDevice == "cpu":
		torch.set_num_threads(max(1, os.cpu_count() or 1))

	from woosh.components.base import LoadConfig

	aSeed = aArgs.seed if aArgs.seed >= 0 else random.randint(0, 2**31 - 1)
	print("Seed: " + str(aSeed), file=sys.stderr, flush=True)

	out("STATUS LoadingSfx")
	aDistilled = "DFlow" in aArgs.variant
	aPath = os.path.join("checkpoints", aArgs.variant)

	def load():
		if aDistilled:
			from woosh.model.flowmap_from_pretrained import FlowMapFromPretrained
			aL = FlowMapFromPretrained(LoadConfig(path=aPath))
		else:
			from woosh.model.ldm import LatentDiffusionModel
			aL = LatentDiffusionModel(LoadConfig(path=aPath))
		return aL.eval().to(aDevice)

	# Loaded once in resident mode
	import ai_serve
	aLdm = ai_serve.cached(("woosh", aArgs.model_dir, aArgs.variant, aDevice), load)

	# Whole sound in one piece; at least the training length (shorter: cut after)
	aFrames = int(round(aArgs.seconds * FRAMES_PER_SECOND)) + 1
	aFrames = max(TRAIN_FRAMES, min(MAX_FRAMES, aFrames))
	# The noise on the CPU: same results whatever the device
	aGen = torch.Generator("cpu").manual_seed(aSeed)
	aNoise = torch.randn(1, 128, aFrames, generator=aGen).to(aDevice)
	torch.manual_seed(aSeed)

	out("STATUS GeneratingSfx")
	aCond = aLdm.get_cond({"audio": None, "description": [aPrompt]}, no_dropout=True, device=aDevice)
	# Progress: one STEP per evaluation of the model
	aCount = [0]
	aTotal = aArgs.steps if aDistilled else 0

	def onForward(aModule, aIn, aOutput):
		aCount[0] += 1
		out("STEP " + str(aCount[0]) + " " + str(aTotal))

	aHook = None
	try:
		aHook = aLdm.register_forward_hook(onForward) if not aDistilled else None
	except Exception:
		aHook = None
	with torch.inference_mode():
		if aDistilled:
			from woosh.inference.flowmap_sampler import sample_euler
			# Schedule of the authors for 4 steps
			aRenoise = [0, 0.5, 0.5, 0.3] if aArgs.steps == 4 else 0.3
			aOrig = aLdm._denoise_dict_no_param

			def denoise(*a, **k):
				aR = aOrig(*a, **k)
				onForward(None, None, None)
				return aR

			aLdm._denoise_dict_no_param = denoise
			try:
				aX = sample_euler(model=aLdm, noise=aNoise, cond=aCond, num_steps=aArgs.steps, renoise=aRenoise
					, cfg=aArgs.guidance)
			finally:
				# The cached model keeps its own method
				del aLdm._denoise_dict_no_param
		else:
			from woosh.inference.flowmatching_sampler import flowmatching_integrate
			aX = flowmatching_integrate(aLdm, noise=aNoise, cond=aCond, cfg=aArgs.guidance, atol=0.001
				, rtol=0.001, device=aDevice, dtype=torch.float32 if aDevice == "mps" else torch.float64)
		if aHook is not None:
			aHook.remove()
		aAudio = aLdm.autoencoder.inverse(aX)
	if aCuda:
		out("PEAK woosh " + str(torch.cuda.max_memory_reserved()))

	out("STATUS WritingSfx")
	aA = aAudio[0].detach().to("cpu", torch.float32).numpy().reshape(-1)
	aA = aA[:int(round(aArgs.seconds * RATE))]
	# Short fades against the clicks of the cut
	aFade = min(len(aA) // 4, int(0.01 * RATE))
	if aFade > 0:
		aA[-aFade:] *= np.linspace(1, 0, aFade, dtype=np.float32)
	# Its sounds come out very quiet (peaks of 0.01 to 0.3): peak normalized to -1 dBFS
	aPeak = float(np.abs(aA).max()) if aA.size > 0 else 0.0
	if aPeak > 1e-6:
		aA = aA * (0.89 / aPeak)
	aPcm = (np.clip(aA, -1, 1) * 32767).round().astype("<i2")
	with wave.open(aOut, "wb") as aW:
		aW.setnchannels(1)
		aW.setsampwidth(2)
		aW.setframerate(RATE)
		aW.writeframes(aPcm.tobytes())
	out("DONE " + aOut)
	return 0


if __name__ == "__main__":
	# One generation, or resident (--serve: the model stays loaded between the requests)
	import ai_serve
	ai_serve.run(main)
