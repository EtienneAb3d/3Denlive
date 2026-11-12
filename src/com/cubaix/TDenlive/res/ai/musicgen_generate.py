# MusicGen text to music for 3Denlive (run by com.cubaix.TDenlive.ai.SoundGenerator).
#
# facebook/musicgen-small / medium / large (transformers MusicgenForConditionalGeneration, CC-BY-NC-4.0 weights):
# a T5 text encoder, an autoregressive decoder of EnCodec tokens (50 per second, 4 codebooks with a delay pattern)
# and the EnCodec decoder, 32 kHz mono. Classifier free guidance (guidance 3), sampling top-k 250.
# Runs on CUDA, MPS or the CPU (--device cpu forces it). fp16 on CUDA, fp32 elsewhere.
# The model files are downloaded by 3Denlive before (no network access here).
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


def out(*aArgs):
	print(*aArgs, flush=True)


def main():
	aP = argparse.ArgumentParser()
	aP.add_argument("--model-dir", required=True)
	aP.add_argument("--prompt-file", required=True)
	aP.add_argument("--seconds", type=float, default=10.0)
	aP.add_argument("--guidance", type=float, default=3.0)
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

	from transformers import AutoProcessor, MusicgenForConditionalGeneration
	from transformers import LogitsProcessor, LogitsProcessorList

	aSeed = aArgs.seed if aArgs.seed >= 0 else random.randint(0, 2**31 - 1)
	print("Seed: " + str(aSeed), file=sys.stderr, flush=True)
	torch.manual_seed(aSeed)

	out("STATUS LoadingMusic")
	aProc = AutoProcessor.from_pretrained(aArgs.model_dir)
	aDType = torch.float16 if aCuda else torch.float32
	aModel = MusicgenForConditionalGeneration.from_pretrained(aArgs.model_dir, dtype=aDType)
	aModel.to(aDevice)
	aModel.eval()

	aRate = aModel.config.audio_encoder.sampling_rate
	aFrameRate = getattr(aModel.config.audio_encoder, "frame_rate", None) or 50
	# The delay pattern costs (codebooks - 1) steps before the first full frame
	aCodebooks = aModel.config.decoder.num_codebooks
	aTokens = int(round(aArgs.seconds * aFrameRate)) + aCodebooks - 1

	# Called once per generated token (a streamer is not passed to the sampling loop by every transformers version)
	class Progress(LogitsProcessor):
		def __init__(self):
			self.n = 0

		def __call__(self, aIds, aScores):
			self.n += 1
			if self.n % 5 == 0 or self.n >= aTokens:
				out("STEP " + str(min(self.n, aTokens)) + " " + str(aTokens))
			return aScores

	out("STATUS GeneratingMusic")
	aIn = aProc(text=[aPrompt], padding=True, return_tensors="pt").to(aDevice)
	with torch.no_grad():
		aAudio = aModel.generate(**aIn, do_sample=True, guidance_scale=aArgs.guidance, max_new_tokens=aTokens
			, logits_processor=LogitsProcessorList([Progress()]))
	if aCuda:
		out("PEAK musicgen " + str(torch.cuda.max_memory_reserved(aDevice)))

	out("STATUS WritingMusic")
	# (1, channels, samples) in -1..1
	aA = aAudio[0].float().cpu().numpy()
	aA = aA[:, :int(round(aArgs.seconds * aRate))]
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
	try:
		sys.exit(main())
	except Exception as e:
		traceback.print_exc()
		aOom = "out of memory" in str(e).lower() or type(e).__name__ == "OutOfMemoryError"
		out("ERROR " + ("OOM" if aOom else (type(e).__name__ + ": " + str(e)).replace("\n", " ")))
		sys.exit(1)
