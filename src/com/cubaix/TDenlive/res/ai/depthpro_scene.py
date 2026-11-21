# Navigable 3D scene from one photo for 3Denlive with Depth Pro (run by com.cubaix.TDenlive.ai.SceneGenerator).
#
# Depth Pro (Bochkovskii et al., Apple, ICLR 2025, https://github.com/apple/ml-depth-pro; weights apple/DepthPro-hf,
# Apple Machine Learning Research Model licence: research only): a metric depth map with sharp edges and the field
# of view of the photo, from an image of 1536 x 1536. Native in transformers (DepthProForDepthEstimation); its image
# processor needs torchvision (absent from the shared environment): the preprocessing of DepthProImageProcessor is
# done here (0..1, normalized by 0.5 / 0.5, then resized bilinearly without antialiasing), and its post-processing
# (canonical inverse depth * width / focal, inverted).
# Output: one 3D Gaussian per pixel of a grid of about --pixels pixels at the ratio of the photo, row by row (the
# grid of the visible layer of a Flash3D scene: model3d.GaussianScene.hasGrid() meshes it), back projected with the
# focal length estimated (principal point at the center), each a small disc facing the camera of the size of its
# pixel, opaque, of the color of the photo. Same PLY / compact files as flash3d_scene.py, in the frame of the camera
# of the photo (x right, y down, z forward, metres), "comment 3Denlive camera fx fy cx cy width height".
# CUDA or MPS (fp16), or the CPU (fp32; --device cpu forces it). Measured on Apple Silicon (MPS): 2.7 s a photo once
# loaded, about 6 GB.
#
# Protocol on stdout, one line per message:
#  STATUS <key>              step (key of the AI.* language entries)
#  DEVICE <cuda|mps|cpu>     device used
#  STEP <done> <total>       images done
#  PEAK <stage> <bytes>      peak GPU memory reserved (CUDA)
#  DONE <path>               last scene written
#  ERROR <OOM|text>          failure
import argparse
import math
import os
import sys
import time

SH_C0 = 0.28209479177387814
# Model input (fixed by the network)
SIDE = 1536


def out(*aArgs):
	print(*aArgs, flush=True)


def main(aArgv):
	aP = argparse.ArgumentParser()
	aP.add_argument("--model-dir", required=True)
	# Several photos: --input and --out repeated, in the same order
	aP.add_argument("--input", action="append", required=True)
	aP.add_argument("--out", action="append", required=True)
	# Pixels of the grid of the scene (one Gaussian each)
	aP.add_argument("--pixels", type=int, default=4 * 256 * 384)
	aP.add_argument("--device", default="auto")
	aP.add_argument("--compact", action="store_true")
	aArgs = aP.parse_args(aArgv)
	if len(aArgs.input) != len(aArgs.out):
		raise ValueError("as many --out as --input")

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

	aModel = ai_serve.cached(("depthpro", aArgs.model_dir, str(aDevice)), load)
	aN = len(aArgs.input)
	for i, (aIn, aOut) in enumerate(zip(aArgs.input, aArgs.out)):
		out("STATUS SceneComputing")
		out("STEP " + str(i) + " " + str(aN))
		scene(aArgs, aModel, aDevice, torch, aIn, aOut)
		out("STEP " + str(i + 1) + " " + str(aN))
	if aCuda:
		out("PEAK scene " + str(torch.cuda.max_memory_reserved(aDevice)))
	out("DONE " + aArgs.out[-1])
	return 0


def loadModel(aArgs, aDevice, torch):
	out("STATUS SceneLoading")
	from transformers.models.depth_pro.modeling_depth_pro import DepthProForDepthEstimation
	# fp16 on the GPU (measured on MPS: the same depths within 0.1 %, half the memory, faster)
	aDtype = torch.float32 if aDevice.type == "cpu" else torch.float16
	aModel = DepthProForDepthEstimation.from_pretrained(aArgs.model_dir, dtype=aDtype)
	aModel.to(aDevice)
	aModel.eval()
	return {"model": aModel, "dtype": aDtype}


def gridSize(aW, aH, aPixels):
	"""Grid at the ratio of the photo, about aPixels pixels, never larger than the photo"""
	aR = aW / float(aH)
	h = max(2, int(round(math.sqrt(aPixels / aR))))
	w = max(2, int(round(h * aR)))
	if w > aW or h > aH:
		w, h = aW, aH
	return w, h


def scene(aArgs, aHolder, aDevice, torch, aIn, aOut):
	import numpy as np
	import torch.nn.functional as F
	from PIL import Image, ImageOps
	aT0 = time.time()
	aImg = ImageOps.exif_transpose(Image.open(aIn)).convert("RGB")
	aGW, aGH = gridSize(aImg.width, aImg.height, aArgs.pixels)
	aModel = aHolder["model"]
	# Preprocessing of DepthProImageProcessor: rescale and normalize first, then resize (bilinear, no antialiasing)
	aX = torch.from_numpy(np.asarray(aImg).copy()).float().div(255.0).permute(2, 0, 1).unsqueeze(0)
	aX = (aX - 0.5) / 0.5
	aX = F.interpolate(aX, size=(SIDE, SIDE), mode="bilinear", align_corners=False, antialias=False)
	with torch.no_grad():
		aO = aModel(pixel_values=aX.to(aDevice, aHolder["dtype"]))
	aInv = aO.predicted_depth.float().reshape(1, 1, SIDE, SIDE)
	aFov = float(aO.field_of_view.float().reshape(-1)[0])
	# Focal length at the width of the grid, depth in metres (post_process_depth_estimation)
	aF = 0.5 * aGW / math.tan(0.5 * math.radians(aFov))
	aInv = F.interpolate(aInv, size=(aGH, aGW), mode="bilinear", align_corners=False, antialias=True)
	aZ = 1.0 / torch.clamp(aInv * (aGW / aF), min=1e-4, max=1e4)
	aZ = aZ.reshape(aGH, aGW).cpu().numpy().astype(np.float64)
	aCx, aCy = aGW / 2.0, aGH / 2.0
	aU = (np.arange(aGW) + 0.5 - aCx)[None, :]
	aV = (np.arange(aGH) + 0.5 - aCy)[:, None]
	aPos = np.stack([aU * aZ / aF, aV * aZ / aF, aZ], axis=-1).reshape(-1, 3)
	aN = aPos.shape[0]
	aCol = np.asarray(aImg.resize((aGW, aGH), Image.LANCZOS)).reshape(-1, 3).astype(np.float64) / 255.0
	# Disc facing the camera, the size of its pixel (radius about 0.6 pixel), thin in depth
	aS = (0.6 * aZ / aF).reshape(-1, 1)
	aScales = np.concatenate([aS, aS, aS * 0.1], axis=1)
	aRots = np.tile(np.array([[1.0, 0.0, 0.0, 0.0]]), (aN, 1))
	aOpac = np.full((aN, 1), 0.995)
	aDc = (aCol - 0.5) / SH_C0
	os.makedirs(os.path.dirname(os.path.abspath(aOut)), exist_ok=True)
	aCam = (aF, aF, aCx, aCy, aGW, aGH)
	if aArgs.compact:
		writeCompact(np, aOut, aPos, aScales, aRots, aOpac, aCol, aCam)
	else:
		aCols = ["x", "y", "z", "nx", "ny", "nz", "f_dc_0", "f_dc_1", "f_dc_2",
				"opacity", "scale_0", "scale_1", "scale_2", "rot_0", "rot_1", "rot_2", "rot_3"]
		aData = np.concatenate([aPos, np.zeros_like(aPos), aDc, np.log(aOpac / (1 - aOpac)), np.log(aScales), aRots], axis=1).astype("<f4")
		aHeader = "ply\nformat binary_little_endian 1.0\n"
		aHeader += "comment 3Denlive Depth Pro scene of " + os.path.basename(aIn).replace("\n", " ") + "\n"
		aHeader += "comment 3Denlive camera %.6f %.6f %.6f %.6f %d %d\n" % aCam
		aHeader += "element vertex " + str(aN) + "\n"
		for c in aCols:
			aHeader += "property float " + c + "\n"
		aHeader += "end_header\n"
		aTmp = aOut + ".part"
		with open(aTmp, "wb") as aFile:
			aFile.write(aHeader.encode("ascii"))
			aFile.write(aData.tobytes())
		os.replace(aTmp, aOut)
	print("Depth Pro scene of " + aIn + " (" + str(aGW) + "x" + str(aGH) + ", depth median " + str(round(float(np.median(aZ)), 2))
			+ " m, fov " + str(round(aFov, 1)) + " deg, fx " + str(round(aF, 1)) + ") in " + str(round(time.time() - aT0, 1))
			+ " s -> " + aOut, file=sys.stderr, flush=True)


def writeCompact(np, aOut, aPos, aScales, aRots, aOpac, aCol, aCam):
	"""Compact scene (model3d.GaussianScene.loadCompact()), as flash3d_scene.writeCompact()"""
	aN = aPos.shape[0]
	aP = np.clip(aPos, -65000, 65000).astype("<f2")
	aLogS = np.log(np.clip(aScales, 1e-7, 1e4)).astype("<f2")
	aRot = np.clip(np.round(aRots * 127), -127, 127).astype("i1")
	aRgba = np.clip(np.round(np.concatenate([aCol, aOpac], axis=1) * 255), 0, 255).astype("u1")
	aHeader = "3DGSC1\n%d %.6f %.6f %.6f %.6f %d %d\n" % ((aN,) + tuple(aCam))
	aTmp = aOut + ".part"
	with open(aTmp, "wb") as aF:
		aF.write(aHeader.encode("ascii"))
		for aA in (aP, aLogS, aRot, aRgba):
			aF.write(np.ascontiguousarray(aA).tobytes())
	os.replace(aTmp, aOut)


if __name__ == "__main__":
	import ai_serve
	ai_serve.run(main)
