# Creative filling of the holes of the views of a 3D scene for 3Denlive (run by com.cubaix.TDenlive.ai.FillGenerator,
# for model3d.SmartFill).
#
# Stable Diffusion XL Inpainting 0.1 (diffusers/stable-diffusion-xl-1.0-inpainting-0.1, CreativeML Open RAIL++-M):
# a diffusion model that invents plausible content in large holes (the light inpainting models only continue the
# textures around them, a blur far from the edges). Native in diffusers (StableDiffusionXLInpaintPipeline), fp16
# weights (variant fp16), on CUDA or MPS in fp16 (its VAE is upcast to fp32 by the pipeline: force_upcast), on the CPU
# in fp32 (very slow). The prompt asks for the background (the holes of a view are what the objects hid) and the
# negative prompt for no new object.
# Request: --input image (RGB, sides multiple of 8, the holes pre-filled by a diffusion of the colors), --mask (L,
# 255 = hole), --out (PNG RGB of the same size: the image made by the model, the caller keeps its holes only),
# --seed (the same for both eyes: closer contents), --steps.
#
# Protocol on stdout, one line per message:
#  STATUS <key>              step (key of the AI.* language entries)
#  DEVICE <cuda|mps|cpu>     device used
#  STEP <done> <total>       denoising steps done
#  PEAK <stage> <bytes>      peak GPU memory reserved (CUDA)
#  DONE <path>               image written
#  ERROR <OOM|text>          failure
import argparse
import os
import sys
import time

PROMPT = "seamless continuation of the background of the scene, same place, same lighting, same perspective, sharp natural photo"
NEGATIVE = "person, people, face, animal, new object, text, letters, watermark, logo, frame, border, blurry, smeared, distorted, duplicate"


def out(*aArgs):
	print(*aArgs, flush=True)


def main(aArgv):
	aP = argparse.ArgumentParser()
	aP.add_argument("--model-dir", required=True)
	aP.add_argument("--device", default="auto")
	aP.add_argument("--input", required=True)
	aP.add_argument("--mask", required=True)
	aP.add_argument("--out", required=True)
	aP.add_argument("--seed", type=int, default=1234)
	aP.add_argument("--steps", type=int, default=20)
	aP.add_argument("--guidance", type=float, default=6.0)
	aP.add_argument("--prompt", default=PROMPT)
	aP.add_argument("--negative", default=NEGATIVE)
	aArgs = aP.parse_args(aArgv)

	out("STATUS Starting")
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

	import ai_serve

	def load():
		return loadModel(aArgs, aDevice, torch)

	aPipe = ai_serve.cached(("sdxl-inpaint", aArgs.model_dir, str(aDevice)), load)
	fill(aArgs, aPipe, aDevice, torch)
	if aCuda:
		out("PEAK fill " + str(torch.cuda.max_memory_reserved(aDevice)))
	out("DONE " + aArgs.out)
	return 0


def loadModel(aArgs, aDevice, torch):
	out("STATUS FillLoading")
	from diffusers import StableDiffusionXLInpaintPipeline
	aDtype = torch.float32 if aDevice.type == "cpu" else torch.float16
	aPipe = StableDiffusionXLInpaintPipeline.from_pretrained(aArgs.model_dir, torch_dtype=aDtype, variant="fp16", use_safetensors=True)
	aPipe.to(aDevice)
	aPipe.set_progress_bar_config(disable=True)
	if aDevice.type == "mps":
		# Less memory at the attention of the large images
		aPipe.enable_attention_slicing()
	return aPipe


def fill(aArgs, aPipe, aDevice, torch):
	from PIL import Image
	aT0 = time.time()
	aImg = Image.open(aArgs.input).convert("RGB")
	aMask = Image.open(aArgs.mask).convert("L")
	aW, aH = aImg.width // 8 * 8, aImg.height // 8 * 8
	if (aW, aH) != aImg.size:
		aImg = aImg.resize((aW, aH), Image.LANCZOS)
		aMask = aMask.resize((aW, aH), Image.NEAREST)
	aSteps = max(1, aArgs.steps)
	out("STATUS FillComputing")

	def onStep(aPipeline, aStep, aTimestep, aKwargs):
		out("STEP " + str(aStep + 1) + " " + str(aSteps))
		if os.path.exists(aArgs.out + ".stop"):
			aPipeline._interrupt = True
		return aKwargs

	aGen = torch.Generator(device="cpu").manual_seed(aArgs.seed)
	with torch.no_grad():
		aRes = aPipe(prompt=aArgs.prompt, negative_prompt=aArgs.negative, image=aImg, mask_image=aMask,
				width=aW, height=aH, num_inference_steps=aSteps, strength=0.99, guidance_scale=aArgs.guidance,
				generator=aGen, callback_on_step_end=onStep).images[0]
	if aRes.size != (aW, aH):
		aRes = aRes.resize((aW, aH), Image.LANCZOS)
	os.makedirs(os.path.dirname(os.path.abspath(aArgs.out)), exist_ok=True)
	aTmp = aArgs.out + ".part.png"
	aRes.save(aTmp)
	os.replace(aTmp, aArgs.out)
	print("SDXL fill " + str(aW) + "x" + str(aH) + " in " + str(round(time.time() - aT0, 1)) + " s -> " + aArgs.out, file=sys.stderr, flush=True)


if __name__ == "__main__":
	import ai_serve
	ai_serve.run(main)
