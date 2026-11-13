# Colorization of black and white photos for 3Denlive (run by com.cubaix.TDenlive.ai.ColorGenerator).
#
# DDColor (https://github.com/piddnad/DDColor, Apache-2.0; weights piddnad/ddcolor_* on Hugging Face): the model
# predicts the a / b channels (CIE Lab) of the photo from its lightness seen at 512 x 512; they are enlarged to
# the size of the photo and joined to its own lightness, so that the details and the size of the photo are kept
# exactly (only the colors are added). Inference code of its authors (ddcolor/model.py and the modules of
# basicsr/archs/ddcolor_arch_utils it imports, downloaded by 3Denlive into --code-dir at a fixed commit); the
# basicsr package itself (training) is not needed: its two levels are declared here.
# CUDA, MPS or the CPU (--device cpu forces it), fp32.
#
# Protocol on stdout, one line per message:
#  STATUS <key>              step (key of the AI.* language entries)
#  DEVICE <cuda|mps|cpu>     device used
#  STEP <done> <total>       images colorized
#  PEAK <stage> <bytes>      peak GPU memory reserved (CUDA)
#  DONE <path>               last image written
#  ERROR <OOM|text>          failure
import argparse
import json
import os
import sys
import time
import traceback
import types

# CIE Lab as OpenCV computes it for float images (sRGB D65), the color space DDColor was trained in
RGB2XYZ = [[0.412453, 0.357580, 0.180423], [0.212671, 0.715160, 0.072169], [0.019334, 0.119193, 0.950227]]
WHITE = [0.950456, 1.0, 1.088754]


def out(*aArgs):
	print(*aArgs, flush=True)


def rgb2lab(torch, aRgb):
	# aRgb: (..., 3) in 0..1 -> (..., 3) L 0..100, a / b about -128..127
	aLin = torch.where(aRgb > 0.04045, ((aRgb + 0.055) / 1.055) ** 2.4, aRgb / 12.92)
	aM = torch.tensor(RGB2XYZ, dtype=aRgb.dtype, device=aRgb.device)
	aXyz = aLin @ aM.T / torch.tensor(WHITE, dtype=aRgb.dtype, device=aRgb.device)
	aF = torch.where(aXyz > 0.008856, aXyz.clamp(min=1e-12) ** (1.0 / 3.0), 7.787 * aXyz + 16.0 / 116.0)
	aY = aXyz[..., 1]
	aL = torch.where(aY > 0.008856, 116.0 * aF[..., 1] - 16.0, 903.3 * aY)
	return torch.stack([aL, 500.0 * (aF[..., 0] - aF[..., 1]), 200.0 * (aF[..., 1] - aF[..., 2])], dim=-1)


def lab2rgb(torch, aLab):
	aL, aA, aB = aLab[..., 0], aLab[..., 1], aLab[..., 2]
	aFy = (aL + 16.0) / 116.0
	aFx = aFy + aA / 500.0
	aFz = aFy - aB / 200.0
	aF = torch.stack([aFx, aFy, aFz], dim=-1)
	aXyz = torch.where(aF > 0.206893, aF ** 3, (aF - 16.0 / 116.0) / 7.787)
	aXyz = torch.cat([aXyz[..., :1], torch.where((aL > 7.9996).unsqueeze(-1), aXyz[..., 1:2], (aL / 903.3).unsqueeze(-1)), aXyz[..., 2:]], dim=-1)
	aXyz = aXyz * torch.tensor(WHITE, dtype=aLab.dtype, device=aLab.device)
	aM = torch.linalg.inv(torch.tensor(RGB2XYZ, dtype=torch.float64)).to(aLab.dtype).to(aLab.device)
	aLin = (aXyz @ aM.T).clamp(0.0, 1.0)
	return torch.where(aLin > 0.0031308, 1.055 * aLin ** (1.0 / 2.4) - 0.055, 12.92 * aLin).clamp(0.0, 1.0)


def main():
	aP = argparse.ArgumentParser()
	aP.add_argument("--model-dir", required=True)
	aP.add_argument("--code-dir", required=True)
	# Several photos: --input and --out repeated, in the same order
	aP.add_argument("--input", action="append", required=True)
	aP.add_argument("--out", action="append", required=True)
	aP.add_argument("--size", type=int, default=512)
	aP.add_argument("--device", default="auto")
	aArgs = aP.parse_args()
	if len(aArgs.input) != len(aArgs.out):
		raise ValueError("as many --out as --input")

	out("STATUS Starting")
	import torch
	import torch.nn.functional as F
	import numpy as np
	from PIL import Image

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

	# basicsr and basicsr.archs: empty packages (their __init__ would import the training code)
	aArchs = os.path.join(aArgs.code_dir, "basicsr", "archs")
	for aName, aPath in (("basicsr", os.path.join(aArgs.code_dir, "basicsr")), ("basicsr.archs", aArchs)):
		aMod = types.ModuleType(aName)
		aMod.__path__ = [aPath]
		sys.modules[aName] = aMod
	sys.path.insert(0, aArgs.code_dir)
	import importlib.util
	aSpec = importlib.util.spec_from_file_location("ddcolor_model", os.path.join(aArgs.code_dir, "ddcolor", "model.py"))
	aModelMod = importlib.util.module_from_spec(aSpec)
	aSpec.loader.exec_module(aModelMod)

	out("STATUS LoadingColorModel")
	with open(os.path.join(aArgs.model_dir, "config.json"), encoding="utf-8") as aF:
		aConfig = json.load(aF)
	aConfig["input_size"] = [aArgs.size, aArgs.size]
	aModel = aModelMod.DDColor(**aConfig)
	aState = torch.load(os.path.join(aArgs.model_dir, "pytorch_model.bin"), map_location="cpu", weights_only=True)
	if isinstance(aState, dict) and "params" in aState:
		aState = aState["params"]
	aMissing, aUnexpected = aModel.load_state_dict(aState, strict=False)
	if aMissing:
		print("Missing weights: " + str(aMissing[:10]), file=sys.stderr, flush=True)
	del aState
	aModel.to(aDevice)
	aModel.eval()

	aN = len(aArgs.input)
	for i, (aIn, aOut) in enumerate(zip(aArgs.input, aArgs.out)):
		out("STATUS Colorizing")
		out("STEP " + str(i) + " " + str(aN))
		aT0 = time.time()
		aImg = Image.open(aIn)
		aAlpha = aImg.getchannel("A") if aImg.mode in ("RGBA", "LA") else None
		aRgb = torch.from_numpy(np.asarray(aImg.convert("RGB")).copy()).float().div(255.0)
		aH, aW = aRgb.shape[:2]
		# Lightness of the photo at its size (kept as it is)
		aL = rgb2lab(torch, aRgb)[..., :1]
		# Model input: the lightness at size x size, as a gray RGB image
		aSmall = F.interpolate(aRgb.permute(2, 0, 1).unsqueeze(0), size=(aArgs.size, aArgs.size), mode="bilinear", align_corners=False)
		aSmallL = rgb2lab(torch, aSmall[0].permute(1, 2, 0).clamp(0, 1))[..., :1]
		aGray = lab2rgb(torch, torch.cat([aSmallL, torch.zeros_like(aSmallL), torch.zeros_like(aSmallL)], dim=-1))
		with torch.no_grad():
			aAb = aModel(aGray.permute(2, 0, 1).unsqueeze(0).to(aDevice)).float().cpu()
		# a / b enlarged to the photo, joined to its lightness
		aAb = F.interpolate(aAb, size=(aH, aW), mode="bilinear", align_corners=False)[0].permute(1, 2, 0)
		aRes = lab2rgb(torch, torch.cat([aL, aAb], dim=-1))
		aRes = Image.fromarray((aRes * 255.0).round().byte().numpy())
		if aAlpha is not None:
			aRes.putalpha(aAlpha)
		os.makedirs(os.path.dirname(os.path.abspath(aOut)), exist_ok=True)
		aRes.save(aOut, "PNG")
		print("Colorized " + aIn + " (" + str(aW) + "x" + str(aH) + ") in " + str(round(time.time() - aT0, 1)) + " s -> " + aOut, file=sys.stderr, flush=True)
		out("STEP " + str(i + 1) + " " + str(aN))
	if aCuda:
		out("PEAK color " + str(torch.cuda.max_memory_reserved(aDevice)))
	out("DONE " + aArgs.out[-1])
	return 0


if __name__ == "__main__":
	try:
		sys.exit(main())
	except Exception as e:
		traceback.print_exc()
		aOom = "out of memory" in str(e).lower() or type(e).__name__ == "OutOfMemoryError"
		out("ERROR " + ("OOM" if aOom else (type(e).__name__ + ": " + str(e)).replace("\n", " ")))
		sys.exit(1)
