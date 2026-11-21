# Navigable 3D scene from one photo for 3Denlive (run by com.cubaix.TDenlive.ai.SceneGenerator).
#
# Flash3D (Szymanowicz, Insafutdinov et al., 3DV 2025, https://github.com/eldar/flash3d; weights
# einsafutdinov/flash3d, MIT): the metric depth of UniDepth V1 ViT-L (lpiccinelli/unidepth-v1-vitl14, CC-BY-NC-4.0)
# and a ResNet-50 predict 2 Gaussians per pixel (position, scale, rotation, opacity, color with view dependence), the
# second one behind the first (what the photo hides). Inference code of the Space of its authors
# (einsafutdinov/flash3d, folder flash3d/, downloaded by 3Denlive into --code-dir at a fixed commit), without
# xformers, wandb, cv2 nor matplotlib (stubs below), the networks built without any download (UniDepth from the
# files of --unidepth-dir, the ImageNet weights of the ResNet replaced by those of the checkpoint).
# xformers: UniDepth only uses its NystromAttention on tensors (b, n, h, d), whose "sequence" for xformers is then h
# (the 8 heads), fewer than its 128 landmarks: it is the exact softmax attention over the last two dimensions,
# done here.
# Output: a binary PLY of the 3D Gaussian Splatting format (x y z, f_dc, f_rest of degree 1, opacity before the
# sigmoid, log scales, rotation w x y z), in the frame of the camera of the photo (x right, y down, z forward, metres)
# with "comment 3Denlive camera fx fy cx cy width height" (intrinsics of the photo at the size of the Gaussians).
# CUDA, MPS or the CPU (--device cpu forces it), fp32.
#
# Protocol on stdout, one line per message:
#  STATUS <key>              step (key of the AI.* language entries)
#  DEVICE <cuda|mps|cpu>     device used
#  STEP <done> <total>       images done
#  PEAK <stage> <bytes>      peak GPU memory reserved (CUDA)
#  DONE <path>               last scene written
#  ERROR <OOM|text>          failure
import argparse
import json
import math
import os
import sys
import time
import types


def out(*aArgs):
	print(*aArgs, flush=True)


def stubs(torch):
	"""Modules imported by the code of Flash3D / UniDepth but not used for the inference"""

	class NystromAttention(torch.nn.Module):
		def __init__(self, *aArgs, **aKw):
			super().__init__()

		def forward(self, q, k, v, key_padding_mask=None, *aArgs, **aKw):
			# xformers scaled_dot_product_attention: softmax(q k^T / sqrt(d)) v on the last two dimensions
			aAtt = torch.matmul(q / math.sqrt(k.size(-1)), k.transpose(-2, -1))
			if key_padding_mask is not None:
				aAtt = aAtt + key_padding_mask
			return torch.matmul(torch.softmax(aAtt, dim=-1), v)

	aX = types.ModuleType("xformers")
	aX.__path__ = []
	aXc = types.ModuleType("xformers.components")
	aXc.__path__ = []
	aXa = types.ModuleType("xformers.components.attention")
	aXa.NystromAttention = NystromAttention
	sys.modules["xformers"] = aX
	sys.modules["xformers.components"] = aXc
	sys.modules["xformers.components.attention"] = aXa
	for aName in ("wandb", "cv2", "matplotlib"):
		try:
			__import__(aName)
		except Exception:
			aM = types.ModuleType(aName)
			aM.setNumThreads = lambda *a, **k: None
			sys.modules[aName] = aM
	if "matplotlib.cm" not in sys.modules:
		try:
			import matplotlib.cm
		except Exception:
			aCm = types.ModuleType("matplotlib.cm")
			sys.modules["matplotlib"].cm = aCm
			sys.modules["matplotlib.cm"] = aCm


def main(aArgv):
	aP = argparse.ArgumentParser()
	aP.add_argument("--model-dir", required=True)
	aP.add_argument("--unidepth-dir", required=True)
	aP.add_argument("--code-dir", required=True)
	# Several photos: --input and --out repeated, in the same order
	aP.add_argument("--input", action="append", required=True)
	aP.add_argument("--out", action="append", required=True)
	# Pixels of the image given to the model (2 Gaussians each): 256 x 384 for its training
	aP.add_argument("--pixels", type=int, default=256 * 384)
	aP.add_argument("--device", default="auto")
	# Compact file (frames of a video): see writeCompact()
	aP.add_argument("--compact", action="store_true")
	aArgs = aP.parse_args(aArgv)
	if len(aArgs.input) != len(aArgs.out):
		raise ValueError("as many --out as --input")

	out("STATUS Starting")
	import torch
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

	import ai_serve

	def load():
		return loadModel(aArgs, aDevice, torch)

	# Loaded once in resident mode (the size of the image is given at each request)
	aModel = ai_serve.cached(("flash3d", aArgs.model_dir, aArgs.unidepth_dir, aArgs.code_dir, str(aDevice)), load)
	aN = len(aArgs.input)
	for i, (aIn, aOut) in enumerate(zip(aArgs.input, aArgs.out)):
		out("STATUS SceneComputing")
		out("STEP " + str(i) + " " + str(aN))
		scene(aArgs, aModel, aDevice, torch, np, Image, aIn, aOut)
		out("STEP " + str(i + 1) + " " + str(aN))
	if aCuda:
		out("PEAK scene " + str(torch.cuda.max_memory_reserved(aDevice)))
	out("DONE " + aArgs.out[-1])
	return 0


def loadModel(aArgs, aDevice, torch):
	stubs(torch)
	aCode = os.path.join(aArgs.code_dir, "flash3d")
	if aCode not in sys.path:
		sys.path.insert(0, aCode)
	from omegaconf import OmegaConf
	import torchvision.models as tvm
	from unidepth.models.unidepthv1 import UniDepthV1
	import networks.resnet_encoder as resnet_encoder

	out("STATUS SceneLoading")
	# UniDepth from its local files (config.json + model.safetensors), not from the hub
	with open(os.path.join(aArgs.unidepth_dir, "config.json"), encoding="utf-8") as aF:
		aUdConfig = json.load(aF)

	def localUniDepth(cls, *aA, **aKw):
		from safetensors.torch import load_file
		aM = cls(json.loads(json.dumps(aUdConfig)))
		aState = load_file(os.path.join(aArgs.unidepth_dir, "model.safetensors"))
		aMissing, aUnexpected = aM.load_state_dict(aState, strict=False)
		if aMissing:
			print("UniDepth missing weights: " + str(len(aMissing)) + " " + str(aMissing[:5]), file=sys.stderr, flush=True)
		return aM

	UniDepthV1.from_pretrained = classmethod(localUniDepth)
	# ResNet-50 without its ImageNet weights (downloaded otherwise): those of the checkpoint replace them
	resnet_encoder.RESNETS = {18: (tvm.resnet18, None), 50: (tvm.resnet50, None)}
	from networks.gaussian_predictor import GaussianPredictor

	aCfg = OmegaConf.load(os.path.join(aArgs.model_dir, "config_re10k_v1.yaml"))
	aCfg.optimiser.batch_size = 1
	aState = torch.load(os.path.join(aArgs.model_dir, "model_re10k_v1.pth"), map_location="cpu", weights_only=True)
	aWeights = aState["model"] if "model" in aState else aState
	# The predictor is built for the size of each image (its back projection depends on it): built again when it
	# changes, the networks kept
	return {"cfg": aCfg, "weights": aWeights, "cls": GaussianPredictor, "OmegaConf": OmegaConf, "size": None, "model": None}


def predictor(aHolder, aH, aW, aDevice, torch):
	if aHolder["size"] == (aH, aW):
		return aHolder["model"]
	aCfg = aHolder["cfg"]
	aCfg.dataset.height = aH
	aCfg.dataset.width = aW
	aOld = aHolder["model"]
	if aOld is None:
		aModel = aHolder["cls"](aCfg)
		aNew = {}
		aOwn = aModel.state_dict()
		for k, v in aHolder["weights"].items():
			if "backproject_depth" in k or k not in aOwn:
				continue
			aNew[k] = v
		aMissing, aUnexpected = aModel.load_state_dict(aNew, strict=False)
		aMissing = [k for k in aMissing if "backproject_depth" not in k and "unidepth" not in k]
		if aMissing:
			print("Flash3D missing weights: " + str(len(aMissing)) + " " + str(aMissing[:5]), file=sys.stderr, flush=True)
		aHolder["weights"] = None
		aModel.to(aDevice)
	else:
		# Same networks, back projection of the new size
		from networks.layers import BackprojectDepth
		aModel = aOld
		aPad = aCfg.dataset.pad_border_aug
		for aS in aCfg.model.scales:
			h = aH // (2 ** aS)
			w = aW // (2 ** aS)
			aModel.backproject_depth[str(aS)] = BackprojectDepth(aCfg.model.gaussians_per_pixel, h + 2 * aPad, w + 2 * aPad, shift_rays_half_pixel=0.5).to(aDevice)
	aModel.set_eval()
	aHolder["size"] = (aH, aW)
	aHolder["model"] = aModel
	return aModel


def modelSize(aW, aH, aPixels):
	"""Sides multiple of 32 (the networks) closest to the ratio of the photo, about aPixels pixels"""
	aR = aW / float(aH)
	aBest = None
	for h in range(64, 2049, 32):
		for w in range(64, 2049, 32):
			if w * h > aPixels * 1.1 or w * h < aPixels * 0.8:
				continue
			aD = abs(math.log(w / float(h) / aR))
			if aBest is None or aD < aBest[0] - 1e-9 or (aD < aBest[0] + 1e-9 and w * h > aBest[1] * aBest[2]):
				aBest = (aD, w, h)
	return (aBest[1], aBest[2]) if aBest else (384, 256)


def scene(aArgs, aHolder, aDevice, torch, np, Image, aIn, aOut):
	import torch.nn.functional as F
	from einops import rearrange
	aT0 = time.time()
	from PIL import ImageOps
	# Upright as the photo is seen (EXIF orientation of the cameras)
	aImg = ImageOps.exif_transpose(Image.open(aIn)).convert("RGB")
	aW, aH = modelSize(aImg.width, aImg.height, aArgs.pixels)
	aModel = predictor(aHolder, aH, aW, aDevice, torch)
	aCfg = aHolder["cfg"]
	aPad = aCfg.dataset.pad_border_aug
	aG = aCfg.model.gaussians_per_pixel
	aSmall = aImg.resize((aW, aH), Image.BICUBIC)
	aX = torch.from_numpy(np.asarray(aSmall).copy()).float().div(255.0).permute(2, 0, 1).unsqueeze(0)
	aX = F.pad(aX, (aPad, aPad, aPad, aPad), value=0.0).to(aDevice)
	with torch.no_grad():
		aO = aModel({("color_aug", 0, 0): aX})
	aHP = aH + 2 * aPad
	aWP = aW + 2 * aPad

	def crop(t):
		return t[..., aPad:aHP - aPad, aPad:aWP - aPad]

	aMeans = aO[("gauss_means", 0, 0)]
	aMeans = rearrange(aMeans, "b c (h w) -> b c h w", h=aHP, w=aWP)
	aMeans = rearrange(crop(aMeans), "(b v) c h w -> b (v h w) c", v=aG)[0, :, :3]

	def flat(aKey):
		return rearrange(crop(aO[(aKey, 0, 0)]), "(b v) c h w -> b (v h w) c", v=aG)[0]

	aScales = flat("gauss_scaling").clamp(min=1e-8)
	aRots = torch.nn.functional.normalize(flat("gauss_rotation"), dim=-1)
	aOpac = flat("gauss_opacity").clamp(1e-6, 1 - 1e-6)
	aDc = flat("gauss_features_dc")
	aRest = flat("gauss_features_rest") if ("gauss_features_rest", 0, 0) in aO else None
	aK = aO[("K_src", 0, 0)][0].float().cpu()
	aFx, aFy = float(aK[0, 0]), float(aK[1, 1])
	# Principal point of the photo (the padding removed)
	aCx, aCy = float(aK[0, 2]) - aPad, float(aK[1, 2]) - aPad

	aN = aMeans.shape[0]
	os.makedirs(os.path.dirname(os.path.abspath(aOut)), exist_ok=True)
	if aArgs.compact:
		writeCompact(np, aOut, aMeans, aScales, aRots, aOpac, aDc, (aFx, aFy, aCx, aCy, aW, aH))
		print("Compact scene of " + aIn + " (" + str(aW) + "x" + str(aH) + ", " + str(aN) + " Gaussians) in "
				+ str(round(time.time() - aT0, 1)) + " s -> " + aOut, file=sys.stderr, flush=True)
		return
	aCols = ["x", "y", "z", "nx", "ny", "nz", "f_dc_0", "f_dc_1", "f_dc_2"]
	aParts = [aMeans, torch.zeros_like(aMeans), aDc]
	if aRest is not None:
		# (sh c) channels of Flash3D -> f_rest_<c*3+sh> of the 3DGS files (all the coefficients of red first)
		aSh = rearrange(aRest, "n (sh c) -> n c sh", c=3).reshape(aN, -1)
		aCols += ["f_rest_" + str(i) for i in range(aSh.shape[1])]
		aParts.append(aSh)
	aCols += ["opacity", "scale_0", "scale_1", "scale_2", "rot_0", "rot_1", "rot_2", "rot_3"]
	aParts += [torch.log(aOpac / (1 - aOpac)), torch.log(aScales), aRots]
	aData = torch.cat([p.float().cpu() for p in aParts], dim=1).numpy().astype("<f4")
	aHeader = "ply\nformat binary_little_endian 1.0\n"
	aHeader += "comment 3Denlive Flash3D scene of " + os.path.basename(aIn).replace("\n", " ") + "\n"
	aHeader += "comment 3Denlive camera %.6f %.6f %.6f %.6f %d %d\n" % (aFx, aFy, aCx, aCy, aW, aH)
	aHeader += "element vertex " + str(aN) + "\n"
	for c in aCols:
		aHeader += "property float " + c + "\n"
	aHeader += "end_header\n"
	aTmp = aOut + ".part"
	with open(aTmp, "wb") as aF:
		aF.write(aHeader.encode("ascii"))
		aF.write(aData.tobytes())
	os.replace(aTmp, aOut)
	aZ = aMeans[:, 2].float().cpu()
	print("Scene of " + aIn + " (" + str(aW) + "x" + str(aH) + ", " + str(aN) + " Gaussians, depth median "
			+ str(round(float(aZ.median()), 2)) + " m, fx " + str(round(aFx, 1)) + ") in " + str(round(time.time() - aT0, 1))
			+ " s -> " + aOut, file=sys.stderr, flush=True)


def writeCompact(np, aOut, aMeans, aScales, aRots, aOpac, aDc, aCam):
	"""Compact scene (model3d.GaussianScene.loadCompact()): "3DGSC1", the line "n fx fy cx cy width height", then
	little endian positions float16 x 3n, log scales float16 x 3n, rotations int8 x 4n (w x y z times 127), colors
	(0.5 + C0 f_dc, the view dependence dropped) and opacity uint8 x 4n: about 20 bytes a Gaussian"""
	aN = aMeans.shape[0]
	aPos = np.clip(aMeans.float().cpu().numpy(), -65000, 65000).astype("<f2")
	aLogS = np.log(np.clip(aScales.float().cpu().numpy(), 1e-7, 1e4)).astype("<f2")
	aRot = np.clip(np.round(aRots.float().cpu().numpy() * 127), -127, 127).astype("i1")
	aCol = np.clip(0.5 + 0.28209479177387814 * aDc.float().cpu().numpy(), 0, 1)
	aRgba = np.concatenate([aCol, aOpac.float().cpu().numpy().reshape(aN, 1)], axis=1)
	aRgba = np.clip(np.round(aRgba * 255), 0, 255).astype("u1")
	aHeader = "3DGSC1\n%d %.6f %.6f %.6f %.6f %d %d\n" % ((aN,) + tuple(aCam))
	aTmp = aOut + ".part"
	with open(aTmp, "wb") as aF:
		aF.write(aHeader.encode("ascii"))
		for aA in (aPos, aLogS, aRot, aRgba):
			aF.write(np.ascontiguousarray(aA).tobytes())
	os.replace(aTmp, aOut)


if __name__ == "__main__":
	# One scene, or resident (--serve: the model stays loaded between the requests)
	import ai_serve
	ai_serve.run(main)
