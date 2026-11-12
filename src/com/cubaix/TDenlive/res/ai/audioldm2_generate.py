# AudioLDM 2 text to sound effects for 3Denlive (run by com.cubaix.TDenlive.ai.SoundGenerator).
#
# cvssp/audioldm2 (diffusers AudioLDM2Pipeline, CC-BY-NC-SA-4.0 weights): CLAP + Flan-T5 text encoders, GPT-2
# language model, latent diffusion UNet, VAE and HiFi-GAN vocoder, 16 kHz mono. Made for general sounds
# (sound effects, ambiences), less for music.
# Runs on CUDA, MPS or the CPU (--device cpu forces it). fp16 on CUDA, fp32 elsewhere.
# The model files are downloaded by 3Denlive before (no network access here).
#
# Protocol on stdout, one line per message:
#  STATUS <key>              step (key of the AI.* language entries)
#  DEVICE <cuda|mps|cpu>     device used
#  STEP <done> <total>       denoising steps
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


# Usual negative prompt of AudioLDM 2
NEGATIVE = "low quality, average quality, noise, distortion"


def main():
	aP = argparse.ArgumentParser()
	aP.add_argument("--model-dir", required=True)
	aP.add_argument("--prompt-file", required=True)
	aP.add_argument("--seconds", type=float, default=5.0)
	aP.add_argument("--steps", type=int, default=200)
	aP.add_argument("--guidance", type=float, default=3.5)
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

	from diffusers import AudioLDM2Pipeline

	# The GPT-2 loop of the pipeline calls private methods of transformers that version 5 removed:
	# the same loop without cache (8 tokens by default, negligible cost)
	def generateLanguageModel(self, inputs_embeds=None, max_new_tokens=8, **aKwargs):
		aMask = aKwargs.get("attention_mask")
		aN = max_new_tokens if max_new_tokens is not None else self.language_model.config.max_new_tokens
		for _ in range(aN):
			aOut = self.language_model(inputs_embeds=inputs_embeds, attention_mask=aMask, use_cache=False
				, output_hidden_states=True, return_dict=True)
			inputs_embeds = torch.cat([inputs_embeds, aOut.hidden_states[-1][:, -1:, :]], dim=1)
			if aMask is not None:
				aMask = torch.cat([aMask, aMask.new_ones((aMask.shape[0], 1))], dim=1)
		return inputs_embeds[:, -aN:, :]

	AudioLDM2Pipeline.generate_language_model = generateLanguageModel

	aSeed = aArgs.seed if aArgs.seed >= 0 else random.randint(0, 2**31 - 1)
	print("Seed: " + str(aSeed), file=sys.stderr, flush=True)

	out("STATUS LoadingSfx")
	aDType = torch.float16 if aCuda else torch.float32
	aPipe = AudioLDM2Pipeline.from_pretrained(aArgs.model_dir, torch_dtype=aDType)
	aPipe.to(aDevice)
	aPipe.set_progress_bar_config(disable=True)
	aRate = getattr(aPipe.vocoder.config, "sampling_rate", None) or 16000

	# Old style callback of AudioLDM2Pipeline (no callback_on_step_end)
	def onStep(aStep, aTimestep, aLatents):
		out("STEP " + str(aStep + 1) + " " + str(aArgs.steps))

	out("STATUS GeneratingSfx")
	# The generator on the CPU: same results whatever the device
	aGen = torch.Generator("cpu").manual_seed(aSeed)
	with torch.no_grad():
		aAudio = aPipe(aPrompt, negative_prompt=NEGATIVE, num_inference_steps=aArgs.steps, guidance_scale=aArgs.guidance
			, audio_length_in_s=aArgs.seconds, num_waveforms_per_prompt=1, generator=aGen
			, callback=onStep, callback_steps=1).audios[0]
	if aCuda:
		out("PEAK audioldm2 " + str(torch.cuda.max_memory_reserved(aDevice)))

	out("STATUS WritingSfx")
	aA = np.asarray(aAudio, dtype=np.float32).reshape(-1)
	aA = aA[:int(round(aArgs.seconds * aRate))]
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
