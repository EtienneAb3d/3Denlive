# MOSS-SoundEffect v2.0 text to sound effects for 3Denlive (run by com.cubaix.TDenlive.ai.SoundGenerator).
#
# OpenMOSS-Team/MOSS-SoundEffect-v2.0 (Apache-2.0): DiT 1.3 B with flow matching, Qwen3 1.7 B text encoder,
# DAC VAE, 48 kHz mono, at most 30 s (the latent of 30 s is always denoised, then cut to the duration).
# Its inference code (moss_soundeffect_v2 of github.com/OpenMOSS/MOSS-TTS, Apache-2.0) is downloaded by 3Denlive
# into --code-dir, with the model files, and runs in its own Python environment (pinned transformers 4.57).
# bf16 on CUDA and MPS, fp32 on the CPU (--device cpu forces it). No network access here.
#
# Protocol on stdout, one line per message:
#  STATUS <key>              step (key of the AI.* language entries)
#  DEVICE <cuda|mps|cpu>     device used
#  STEP <done> <total>       denoising steps
#  PEAK <stage> <bytes>      peak GPU memory reserved (CUDA), or allocated by the driver (MPS)
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
# No compilation by TorchDynamo / Triton (minutes at the first call, errors on some systems)
os.environ.setdefault("TORCHDYNAMO_DISABLE", "1")


def out(*aArgs):
	print(*aArgs, flush=True)


def main():
	aP = argparse.ArgumentParser()
	aP.add_argument("--model-dir", required=True)
	aP.add_argument("--code-dir", required=True)
	aP.add_argument("--prompt-file", required=True)
	aP.add_argument("--seconds", type=float, default=10.0)
	aP.add_argument("--steps", type=int, default=100)
	aP.add_argument("--guidance", type=float, default=4.0)
	aP.add_argument("--device", default="auto")
	aP.add_argument("--seed", type=int, default=-1)
	aP.add_argument("--out", required=True)
	aArgs = aP.parse_args()
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

	# The VAE file is a plain state dict: never try to import it as a torch.package (which runs code),
	# load it as weights only (torch.load with weights_only, the default of PyTorch >= 2.6)
	from audiotools.ml import BaseModel

	def noPackage(cls, *aA, **aK):
		raise RuntimeError("torch.package loading disabled")

	BaseModel._load_package = classmethod(noPackage)

	sys.path.insert(0, aArgs.code_dir)
	from moss_soundeffect_v2 import MossSoundEffectPipeline

	# The timestep embedding is computed in float64, which MPS does not have: the same computation on the CPU,
	# the result back on the device (the RoPE frequencies are computed on the CPU at the loading)
	def sinusoidalEmbedding1d(dim, position):
		# To the CPU first, then float64 (a conversion on MPS fails)
		aPos = position.detach().cpu().to(torch.float64)
		aS = torch.outer(aPos, torch.pow(10000, -torch.arange(dim // 2, dtype=torch.float64).div(dim // 2)))
		aX = torch.cat([torch.cos(aS), torch.sin(aS)], dim=1)
		return aX.to(device=position.device, dtype=position.dtype)

	import importlib
	for aMod in ("moss_soundeffect_v2.diffsynth.models.wan_video_dit", "moss_soundeffect_v2.diffsynth.models.wan_audio_dit"
			, "moss_soundeffect_v2.diffsynth.pipelines.wan_audio"):
		aM = importlib.import_module(aMod)
		if hasattr(aM, "sinusoidal_embedding_1d"):
			aM.sinusoidal_embedding_1d = sinusoidalEmbedding1d
		# RoPE frequencies: complex128 (from float64), which MPS does not have, in complex64 (the precision of the
		# rotations computed from them); computed on the CPU when the DiT is built, then moved with it
		aOrig = getattr(aM, "precompute_freqs_cis", None)
		if aOrig is not None:
			aM.precompute_freqs_cis = (lambda o: lambda *aA, **aK: o(*aA, **aK).to(torch.complex64))(aOrig)

	aSeed = aArgs.seed if aArgs.seed >= 0 else random.randint(0, 2**31 - 1)
	print("Seed: " + str(aSeed), file=sys.stderr, flush=True)

	out("STATUS LoadingSfx")
	aDType = torch.float32 if aDevice.type == "cpu" else torch.bfloat16
	aPipe = MossSoundEffectPipeline.from_pretrained(aArgs.model_dir, torch_dtype=aDType, device=str(aDevice))

	def steps(aIt, **aK):
		aList = list(aIt)
		for i, aT in enumerate(aList):
			yield aT
			out("STEP " + str(i + 1) + " " + str(len(aList)))

	out("STATUS GeneratingSfx")
	aSeconds = max(0.1, min(float(aPipe.max_inference_seconds), aArgs.seconds))
	with torch.no_grad():
		aAudio = aPipe(prompt=aPrompt, seconds=aSeconds, num_inference_steps=aArgs.steps, cfg_scale=aArgs.guidance
			, seed=aSeed, progress_bar_cmd=steps)
	if aCuda:
		out("PEAK moss " + str(torch.cuda.max_memory_reserved(aDevice)))
	elif aDevice.type == "mps":
		out("PEAK moss " + str(torch.mps.driver_allocated_memory()))
	aRate = int(aPipe.sample_rate)

	out("STATUS WritingSfx")
	# (B, C, T): first waveform, channels mixed down to mono
	aA = aAudio[0].detach().float().cpu().numpy()
	aA = aA.mean(axis=0) if aA.ndim > 1 else aA
	aA = aA[:int(round(aSeconds * aRate))]
	aPeak = float(np.abs(aA).max()) if aA.size > 0 else 0.0
	if aPeak > 0.99:
		aA = aA * (0.99 / aPeak)
	aPcm = (np.clip(aA, -1, 1) * 32767).round().astype("<i2")
	with wave.open(aArgs.out, "wb") as aW:
		aW.setnchannels(1)
		aW.setsampwidth(2)
		aW.setframerate(aRate)
		aW.writeframes(aPcm.tobytes())
	out("DONE " + aArgs.out)
	return 0


if __name__ == "__main__":
	try:
		sys.exit(main())
	except Exception as e:
		traceback.print_exc()
		aOom = "out of memory" in str(e).lower() or type(e).__name__ == "OutOfMemoryError"
		out("ERROR " + ("OOM" if aOom else (type(e).__name__ + ": " + str(e)).replace("\n", " ")))
		sys.exit(1)
