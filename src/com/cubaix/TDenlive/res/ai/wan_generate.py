# Wan2.2-TI2V-5B text to video for 3Denlive (run by com.cubaix.TDenlive.ai.WanGenerator).
#
# Quantized loading, in three stages so that the GPU holds only one model at a time:
#  1. text encoder (UMT5-XXL): 8 bits (bitsandbytes) on an NVIDIA GPU, else bf16 on the CPU;
#     the prompt embeddings are kept, the encoder is freed;
#  2. transformer: GGUF Q8_0 (QuantStack/Wan2.2-TI2V-5B-GGUF, dequantized layer by layer in bf16);
#  3. VAE (fp32, tiled decoding) once the transformer is freed.
# Optional reference image (--image): cropped to the ratio of the video, encoded by the VAE (before the transformer
# is loaded) as the first latent frame, which stays as it is during the denoising (timestep 0 for its tokens):
# the video starts from the image and animates it according to the prompt (the "I" of TI2V).
# The model files are downloaded by 3Denlive before (no network access here).
#
# Protocol on stdout, one line per message:
#  STATUS <key>              step (key of the AI.* language entries)
#  STEP <done> <total>       denoising steps
#  PREVIEW <png> <frames>    approximate preview: the latent frames side by side (latent -> RGB linear map)
#  PEAK <stage> <bytes>      peak GPU memory reserved by the stage (CUDA)
#  DONE <path>               video written
#  ERROR <OOM|NOGPU|text>    failure
import argparse
import gc
import os
import random
import subprocess
import sys
import traceback

os.environ.setdefault("HF_HUB_OFFLINE", "1")
os.environ.setdefault("TRANSFORMERS_OFFLINE", "1")
os.environ.setdefault("PYTORCH_CUDA_ALLOC_CONF", "expandable_segments:True")


def out(*aArgs):
	print(*aArgs, flush=True)


# Official negative prompt of Wan
NEGATIVE = ("色调艳丽，过曝，静态，细节模糊不清，字幕，风格，作品，画作，画面，静止，整体发灰，最差质量，低质量，"
	"JPEG压缩残留，丑陋的，残缺的，多余的手指，画得不好的手部，画得不好的脸部，畸形的，毁容的，形态畸形的肢体，"
	"手指融合，静止不动的画面，杂乱的背景，三条腿，背景人很多，倒着走")

# Latent (48 channels, normalized) -> RGB in -1..1, from ComfyUI (comfy/latent_formats.py, Wan22)
LATENT_RGB = [
	[0.0119, 0.0103, 0.0046], [-0.1062, -0.0504, 0.0165], [0.0140, 0.0409, 0.0491], [-0.0813, -0.0677, 0.0607],
	[0.0656, 0.0851, 0.0808], [0.0264, 0.0463, 0.0912], [0.0295, 0.0326, 0.0590], [-0.0244, -0.0270, 0.0025],
	[0.0443, -0.0102, 0.0288], [-0.0465, -0.0090, -0.0205], [0.0359, 0.0236, 0.0082], [-0.0776, 0.0854, 0.1048],
	[0.0564, 0.0264, 0.0561], [0.0006, 0.0594, 0.0418], [-0.0319, -0.0542, -0.0637], [-0.0268, 0.0024, 0.0260],
	[0.0539, 0.0265, 0.0358], [-0.0359, -0.0312, -0.0287], [-0.0285, -0.1032, -0.1237], [0.1041, 0.0537, 0.0622],
	[-0.0086, -0.0374, -0.0051], [0.0390, 0.0670, 0.2863], [0.0069, 0.0144, 0.0082], [0.0006, -0.0167, 0.0079],
	[0.0313, -0.0574, -0.0232], [-0.1454, -0.0902, -0.0481], [0.0714, 0.0827, 0.0447], [-0.0304, -0.0574, -0.0196],
	[0.0401, 0.0384, 0.0204], [-0.0758, -0.0297, -0.0014], [0.0568, 0.1307, 0.1372], [-0.0055, -0.0310, -0.0380],
	[0.0239, -0.0305, 0.0325], [-0.0663, -0.0673, -0.0140], [-0.0416, -0.0047, -0.0023], [0.0166, 0.0112, -0.0093],
	[-0.0211, 0.0011, 0.0331], [0.1833, 0.1466, 0.2250], [-0.0368, 0.0370, 0.0295], [-0.3441, -0.3543, -0.2008],
	[-0.0479, -0.0489, -0.0420], [-0.0660, -0.0153, 0.0800], [-0.0101, 0.0068, 0.0156], [-0.0690, -0.0452, -0.0927],
	[-0.0145, 0.0041, 0.0015], [0.0421, 0.0451, 0.0373], [0.0504, -0.0483, -0.0356], [-0.0837, 0.0168, 0.0055],
]
LATENT_RGB_BIAS = [0.0317, -0.0878, -0.1388]


def main():
	aP = argparse.ArgumentParser()
	aP.add_argument("--model-dir", required=True)
	aP.add_argument("--gguf", required=True)
	aP.add_argument("--prompt-file", required=True)
	aP.add_argument("--frames", type=int, default=121)
	aP.add_argument("--width", type=int, default=1280)
	aP.add_argument("--height", type=int, default=704)
	aP.add_argument("--steps", type=int, default=50)
	aP.add_argument("--guidance", type=float, default=5.0)
	aP.add_argument("--fps", type=int, default=24)
	aP.add_argument("--seed", type=int, default=-1)
	aP.add_argument("--image", default=None)
	aP.add_argument("--preview", required=True)
	aP.add_argument("--ffmpeg", required=True)
	aP.add_argument("--out", required=True)
	aArgs = aP.parse_args()
	with open(aArgs.prompt_file, encoding="utf-8") as aF:
		aPrompt = aF.read().strip()

	out("STATUS Starting")
	import numpy as np
	import torch
	from PIL import Image

	if torch.cuda.is_available():
		aDevice = torch.device("cuda", 0)
	elif getattr(torch.backends, "mps", None) is not None and torch.backends.mps.is_available():
		aDevice = torch.device("mps")
	else:
		out("ERROR NOGPU")
		return 3
	aCuda = aDevice.type == "cuda"
	print("Device: " + str(aDevice) + (" " + torch.cuda.get_device_name(0) if aCuda else ""), file=sys.stderr, flush=True)

	def freeMemory():
		gc.collect()
		if aCuda:
			torch.cuda.empty_cache()
		elif aDevice.type == "mps":
			torch.mps.empty_cache()

	def peak(aStage):
		if aCuda:
			out("PEAK " + aStage + " " + str(torch.cuda.max_memory_reserved(aDevice)))
			torch.cuda.reset_peak_memory_stats(aDevice)

	from transformers import AutoTokenizer, UMT5EncoderModel
	from diffusers import WanPipeline, WanTransformer3DModel, AutoencoderKLWan, UniPCMultistepScheduler, GGUFQuantizationConfig

	aSeed = aArgs.seed if aArgs.seed >= 0 else random.randint(0, 2**31 - 1)
	print("Seed: " + str(aSeed), file=sys.stderr, flush=True)

	# 1. Text encoder
	out("STATUS LoadingText")
	aTok = AutoTokenizer.from_pretrained(aArgs.model_dir, subfolder="tokenizer")
	aTE = None
	aTEDevice = torch.device("cpu")
	if aCuda:
		try:
			from transformers import BitsAndBytesConfig
			aTE = UMT5EncoderModel.from_pretrained(aArgs.model_dir, subfolder="text_encoder"
				, quantization_config=BitsAndBytesConfig(load_in_8bit=True)
				, torch_dtype=torch.bfloat16, device_map={"": aDevice.index or 0})
			aTEDevice = aDevice
		except Exception:
			traceback.print_exc()
			print("8 bits text encoder not usable: CPU", file=sys.stderr, flush=True)
			aTE = None
			freeMemory()
	if aTE is None:
		aTE = UMT5EncoderModel.from_pretrained(aArgs.model_dir, subfolder="text_encoder", torch_dtype=torch.bfloat16)
	out("STATUS EncodingText")
	aTextPipe = WanPipeline(tokenizer=aTok, text_encoder=aTE, vae=None, scheduler=None, transformer=None)
	with torch.no_grad():
		aPE, aNE = aTextPipe.encode_prompt(prompt=aPrompt, negative_prompt=NEGATIVE, do_classifier_free_guidance=True
			, num_videos_per_prompt=1, max_sequence_length=512, device=aTEDevice)
	aPE = aPE.to("cpu")
	aNE = aNE.to("cpu")
	del aTextPipe, aTE
	freeMemory()
	peak("text")

	# VAE: on the CPU until needed (image encoding, decoding)
	aVae = AutoencoderKLWan.from_pretrained(aArgs.model_dir, subfolder="vae", torch_dtype=torch.float32)
	aMean = torch.tensor(aVae.config.latents_mean).view(1, aVae.config.z_dim, 1, 1, 1)
	aStd = torch.tensor(aVae.config.latents_std).view(1, aVae.config.z_dim, 1, 1, 1)
	aST = aVae.config.scale_factor_temporal
	aSS = aVae.config.scale_factor_spatial
	aLF = (aArgs.frames - 1) // aST + 1
	aLH = aArgs.height // aSS
	aLW = aArgs.width // aSS
	# First latent frame kept (0) or generated (1)
	aMask = torch.ones(1, 1, aLF, aLH, aLW, dtype=torch.float32)
	aCond = None

	# 2. Reference image (image to video): encoded by the VAE as the first latent frame, kept as it is
	if aArgs.image:
		out("STATUS EncodingImage")
		aImg = Image.open(aArgs.image).convert("RGB")
		# Cropped to the ratio of the video (centered), then resized
		aR = aArgs.width / aArgs.height
		aIW, aIH = aImg.size
		if aIW / aIH > aR:
			aCW = int(round(aIH * aR))
			aImg = aImg.crop(((aIW - aCW) // 2, 0, (aIW - aCW) // 2 + aCW, aIH))
		else:
			aCH = int(round(aIW / aR))
			aImg = aImg.crop((0, (aIH - aCH) // 2, aIW, (aIH - aCH) // 2 + aCH))
		aImg = aImg.resize((aArgs.width, aArgs.height), Image.LANCZOS)
		aT = torch.from_numpy(np.asarray(aImg).copy()).float().div(127.5).sub(1.0)
		aT = aT.permute(2, 0, 1).unsqueeze(0).unsqueeze(2)
		aVae.to(aDevice)
		with torch.no_grad():
			aEnc = aVae.encode(aT.to(aDevice, torch.float32)).latent_dist.mode()
		aCond = ((aEnc.float().cpu() - aMean) / aStd)
		del aEnc, aT
		aVae.to("cpu")
		freeMemory()
		aMask[:, :, 0] = 0
		peak("image")

	# 3. Transformer (GGUF) and denoising (as WanPipeline / WanImageToVideoPipeline with expand_timesteps)
	out("STATUS LoadingVideo")
	aTr = WanTransformer3DModel.from_single_file(aArgs.gguf
		, quantization_config=GGUFQuantizationConfig(compute_dtype=torch.bfloat16)
		, config=aArgs.model_dir, subfolder="transformer", torch_dtype=torch.bfloat16)
	aTr.to(aDevice)
	aSched = UniPCMultistepScheduler.from_pretrained(aArgs.model_dir, subfolder="scheduler")

	aFactors = torch.tensor(LATENT_RGB, dtype=torch.float32)
	aBias = torch.tensor(LATENT_RGB_BIAS, dtype=torch.float32)
	aPreviewTmp = aArgs.preview + ".tmp.png"

	def preview(aX0):
		# aX0: (1, 48, F, h, w) -> frames side by side
		aL = aX0[0].float().cpu()
		aRgb = torch.einsum("cfhw,cr->fhwr", aL, aFactors) + aBias
		aRgb = ((aRgb + 1.0) / 2.0).clamp(0, 1).mul(255).byte().numpy()
		aStrip = np.concatenate(list(aRgb), axis=1)
		Image.fromarray(aStrip).save(aPreviewTmp, "PNG")
		os.replace(aPreviewTmp, aArgs.preview)
		out("PREVIEW " + aArgs.preview + " " + str(aRgb.shape[0]))

	out("STATUS Generating")
	out("STEP 0 " + str(aArgs.steps))
	aGen = torch.Generator(device="cpu").manual_seed(aSeed)
	aLatents = torch.randn((1, aVae.config.z_dim, aLF, aLH, aLW), generator=aGen, dtype=torch.float32).to(aDevice)
	aMaskD = aMask.to(aDevice)
	aCondD = aCond.to(aDevice) if aCond is not None else None
	aPED = aPE.to(aDevice, torch.bfloat16)
	aNED = aNE.to(aDevice, torch.bfloat16)
	aSched.set_timesteps(aArgs.steps, device=aDevice)
	aSched.set_begin_index(0)

	def withImage(aL):
		return aL if aCondD is None else (1 - aMaskD) * aCondD + aMaskD * aL

	with torch.no_grad():
		for i, t in enumerate(aSched.timesteps):
			aIn = withImage(aLatents).to(torch.bfloat16)
			# One timestep per token: 0 for the kept image frame
			aTs = (aMaskD[0][0][:, ::2, ::2] * t).flatten().unsqueeze(0)
			aCondPred = aTr(hidden_states=aIn, timestep=aTs, encoder_hidden_states=aPED, return_dict=False)[0]
			aUncond = aTr(hidden_states=aIn, timestep=aTs, encoder_hidden_states=aNED, return_dict=False)[0]
			aV = (aUncond + aArgs.guidance * (aCondPred - aUncond)).float()
			del aCondPred, aUncond
			# Preview of the predicted clean video (flow matching: x0 = x_t - sigma * v)
			try:
				preview(withImage(aLatents - float(aSched.sigmas[i]) * aV))
			except Exception:
				traceback.print_exc()
			aLatents = aSched.step(aV, t, aLatents, return_dict=False)[0]
			out("STEP " + str(i + 1) + " " + str(aArgs.steps))
	aLatents = withImage(aLatents).to("cpu")
	del aTr, aPED, aNED, aMaskD, aCondD
	freeMemory()
	peak("transformer")

	# 4. VAE decoding
	out("STATUS Decoding")
	aVae.to(aDevice)
	try:
		aVae.enable_tiling()
	except Exception:
		traceback.print_exc()
	aLatents = (aLatents.float() * aStd + aMean).to(aDevice, torch.float32)
	with torch.no_grad():
		aVideo = aVae.decode(aLatents, return_dict=False)[0]
	# (1, 3, F, H, W) in -1..1
	aVideo = ((aVideo[0].float().clamp(-1, 1) + 1.0) * 127.5).round().byte().permute(1, 2, 3, 0).cpu().numpy()
	del aLatents
	freeMemory()
	peak("vae")

	# Encoding (H.264, high quality)
	out("STATUS Encoding")
	aF, aH, aW, _ = aVideo.shape
	aCmd = [aArgs.ffmpeg, "-y", "-loglevel", "error", "-f", "rawvideo", "-pix_fmt", "rgb24", "-s", str(aW) + "x" + str(aH)
		, "-r", str(aArgs.fps), "-i", "-", "-c:v", "libx264", "-preset", "medium", "-crf", "14", "-pix_fmt", "yuv420p", aArgs.out]
	aProc = subprocess.Popen(aCmd, stdin=subprocess.PIPE)
	for f in range(aF):
		aProc.stdin.write(np.ascontiguousarray(aVideo[f]).tobytes())
	aProc.stdin.close()
	if aProc.wait() != 0:
		out("ERROR ffmpeg " + str(aProc.returncode))
		return 4
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
