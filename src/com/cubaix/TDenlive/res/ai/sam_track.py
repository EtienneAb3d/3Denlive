# Objects delimited by clicks and tracked in a video, for the masks of 3Denlive (run by
# com.cubaix.TDenlive.ai.MaskGenerator, resident: see ai_serve.py).
#
# SAM 2.1 (Meta, Apache-2.0; facebook/sam2.1-hiera-* in the transformers format, Sam2VideoModel and its
# Sam2VideoInferenceSession; the processor of transformers is not used: it needs torchvision, which is not in the
# shared environment, so its steps are done here: image resized to 1024 x 1024 and normalized, click coordinates
# scaled to 1024, a box given as two points of labels 2 / 3 before the clicks, masks enlarged bilinearly). The frames of the
# work copy (JPEG or PNG <frames>/<n>.jpg|png, numbered from 0, made by 3Denlive at a reduced size) are read when
# the model needs them (a few kept), so that a long video does not fill the memory. A stereo frame holds several
# views side by side (--views): each view has its own tracking session, the objects having the same ids in all of
# them. A prompt in a view seeds the other views at the same place shifted by the parallax found by matching the
# object between the views (box + the same points + points inside the mask), unless the user gave one in them too.
# A prompt of an object on a frame is points (in / out), a box (with or without points), or a mask painted by the
# user (the brush of 3Denlive): the mask is the result of that frame as it is (written at the work size, finer than
# the 256 x 256 output of the model) and a conditioning frame of the tracking.
# The result of a frame is a 16-bit gray PNG <labels>/<n>.png of the size of the work frame: bit k-1 set where the
# object k is (at most 16 objects, overlaps kept).
#
# Model arguments (command line): --model-dir, --device (auto: CUDA, MPS, else the CPU).
# Requests (one per line in resident mode): --op and its arguments:
#  open    --frames <dir> --count <n> --views "x,y,w,h;..." --labels <dir>   a new video (the session is reset)
#  points  --obj <k> --view <v> --frame <n> --points "x,y,l;..." [--box "x0,y0,x1,y1"]  points of the object in the
#          view (view coordinates, l 1 = in, 0 = out), all those of that frame, and its box; none: removed
#  mask    --obj <k> --view <v> --frame <n> --mask <png>         mask of the object in the view (white = in, the size
#          of the view in the work frame), replaces its points / box on that frame
#  delete  --obj <k>                                             object removed everywhere (the tracking of the
#          others kept: nothing computed again)
#  track   [--stop-file <path>] [--resume <frame>]               propagation over the whole video; --resume: only
#          from that frame to the end (the frames before kept), plus the frames of the tracking not computed yet
#          (stopped), when the session was tracked with the same objects since its last rebuild (else the whole
#          video; TRACKMODE full|resume <first frame> tells which); --to <frame>: only up to that frame (a step
#          of the editor: the frames from the resume to it, forward, or backward when it is before the first prompt)
#  close                                                         the session forgotten (the model stays loaded)
#  export  --out <path> --view-size <w>x<h> [--ffmpeg <path> --fps <fps>]   the mask of all the objects, white on
#          black, the views side by side at their full size: a PNG (one frame) or a video (ffmpeg)
#
# Protocol on stdout, one line per message:
#  STATUS <key>         step (key of the AI.* language entries)
#  DEVICE <cuda|mps|cpu>
#  STEP <done> <total>  frames tracked / written
#  LABELS <n>           labels of the frame n written
#  PEAK <stage> <bytes> peak GPU memory reserved (CUDA)
#  ENDS <obj>:<fwd>:<back>;...  objects that left the image (see END_FRAMES): no longer computed from fwd on, or up
#          to back backward (- : none); at the end of track / prompt / delete
#  DONE <text>          end of the request
#  ERROR <OOM|STOPPED|text>
import argparse
import os
import sys
import time

MAX_OBJECTS = 16
# Input of the model (preprocessor_config.json of the SAM 2.1 repositories)
INPUT_SIZE = 1024
MEAN = [0.485, 0.456, 0.406]
STD = [0.229, 0.224, 0.225]
# Points inside the mask of an object given to the other view of a stereo frame (see Context.seed())
SEED_GRID = 3
# Frames kept in memory, processed for the model
FRAME_CACHE = 12
# Outputs of the tracked frames kept in the session (not the conditioning frames, the frames clicked): those within
# KEEP_FRAMES of the frame computed, the memory the model reads (6 previous masks, 15 previous object pointers).
# Kept all, they filled the GPU memory as the tracking advanced in the video (15 GB at frame 300 for 7 objects in 2
# views on Apple Silicon: swap, frames 3 times slower)
KEEP_FRAMES = 16
# Frames computed again before a tracking resumed far from the last frame tracked (their outputs dropped): the memory
# of the frame resumed (labels not written again)
WARM_FRAMES = 6
# An object absent (empty mask in every view) on END_FRAMES frames in a row is no longer computed after them in that
# direction (it left the image: its cost stops), until a prompt of it on a later frame (or before, backward)
END_FRAMES = 5
# Continuity of a tracked object: the parts of its mask not connected to its mask of the frame before (in the order
# of the tracking) grown by CONTINUITY_REACH pixels of the 256 x 256 output of the model (about 5 % of the image: the
# motion of a frame) are dropped, from the result and from the memory of the model. SAM otherwise gave the object to
# another one entering the image (a person leaving on the right, its mask spread to the one entering on the left,
# then on it alone): the object never became absent, and was "recycled"
CONTINUITY_REACH = 12
CONTINUITY_PART = 0.5
# Not during the CONTINUITY_AFTER frames after a prompt of the object: SAM completes it from the clicked part (other
# parts of it appear, not connected)
CONTINUITY_AFTER = 10


def out(*aArgs):
	print(*aArgs, flush=True)


def log(aText):
	print(aText, file=sys.stderr, flush=True)


class State:
	"""The video open: its frames, views, sessions (one per view) and the prompts of the objects"""
	def __init__(self):
		self.frames = None
		self.count = 0
		self.views = []
		self.labels = None
		self.sessions = []
		# prompts[obj][view][frame] = {"points": [(x, y, l)...] (view coordinates), "box": [x0, y0, x1, y1] or
		# None, "auto": True when seeded from another view}
		self.prompts = {}
		# Tracking of the session: the objects it was made with and the frames computed since (None: none, or the
		# session rebuilt: the outputs of the tracked frames, the memory of the next ones, are lost)
		self.done = None
		# Objects ended (see END_FRAMES): ends[obj] = {"fwd": first frame no longer computed forward, "back": last
		# frame no longer computed backward}; absent[obj] = [last frame computed, reverse, frames absent in a row]
		self.ends = {}
		self.absent = {}
		# has[obj] = the frames whose labels hold the object (see forget())
		self.has = {}


_S = State()


def framePath(aDir, aN):
	for aExt in (".jpg", ".png"):
		aP = os.path.join(aDir, "%06d" % aN + aExt)
		if os.path.isfile(aP):
			return aP
	raise FileNotFoundError("Frame " + str(aN) + " not found in " + aDir)


class LazyFrames(dict):
	"""The processed frames of a view, read from the files when the model asks for them (dict of the session)"""
	def __init__(self, aState, aView, aDtype, torch):
		super().__init__()
		self.state = aState
		self.view = aView
		self.dtype = aDtype
		self.torch = torch
		self.order = []

	def __len__(self):
		return self.state.count

	def __contains__(self, aK):
		return 0 <= aK < self.state.count

	def __getitem__(self, aN):
		if dict.__contains__(self, aN):
			return dict.__getitem__(self, aN)
		import numpy as np
		from PIL import Image
		x, y, w, h = self.state.views[self.view]
		aImg = Image.open(framePath(self.state.frames, aN)).convert("RGB").crop((x, y, x + w, y + h))
		aImg = aImg.resize((INPUT_SIZE, INPUT_SIZE), Image.BILINEAR)
		aA = (np.asarray(aImg, dtype=np.float32) / 255.0 - MEAN) / STD
		aPix = self.torch.from_numpy(aA.transpose(2, 0, 1).copy()).to(self.dtype)
		dict.__setitem__(self, aN, aPix)
		self.order.append(aN)
		while len(self.order) > FRAME_CACHE:
			dict.pop(self, self.order.pop(0), None)
		return aPix

	def __setitem__(self, aN, aV):
		pass


def main(aArgv):
	global _S
	aP = argparse.ArgumentParser()
	aP.add_argument("--model-dir", required=True)
	aP.add_argument("--device", default="auto")
	aP.add_argument("--op", required=True)
	aP.add_argument("--frames")
	aP.add_argument("--count", type=int, default=0)
	aP.add_argument("--views", default="")
	aP.add_argument("--labels")
	aP.add_argument("--obj", type=int, default=1)
	aP.add_argument("--view", type=int, default=0)
	aP.add_argument("--frame", type=int, default=0)
	aP.add_argument("--points", default="")
	aP.add_argument("--box", default="")
	aP.add_argument("--mask", default=None)
	aP.add_argument("--stop-file", default=None)
	aP.add_argument("--resume", type=int, default=None)
	aP.add_argument("--to", type=int, default=None)
	aP.add_argument("--out")
	aP.add_argument("--view-size", default="")
	aP.add_argument("--ffmpeg", default="ffmpeg")
	aP.add_argument("--fps", default="25")
	aArgs = aP.parse_args(aArgv)

	import torch
	import ai_serve

	if aArgs.device != "cpu" and torch.cuda.is_available():
		aDevice = torch.device("cuda", 0)
	elif aArgs.device != "cpu" and getattr(torch.backends, "mps", None) is not None and torch.backends.mps.is_available():
		aDevice = torch.device("mps")
	else:
		aDevice = torch.device("cpu")
	if aDevice.type == "cpu":
		torch.set_num_threads(max(1, os.cpu_count() or 1))

	def load():
		out("STATUS LoadingMaskModel")
		from transformers import Sam2VideoModel
		# bf16 on CUDA (as the examples of transformers); fp16 on MPS: 1.7 times faster than fp32 with the same
		# masks (IoU 0.999 measured), bf16 slower there; fp32 on the CPU. SDPA attention (the memory attention,
		# 4096 queries x the memory of 7 frames, is most of the time of a tracked frame)
		aDtype = torch.bfloat16 if aDevice.type == "cuda" else (torch.float16 if aDevice.type == "mps" else torch.float32)
		aModel = Sam2VideoModel.from_pretrained(aArgs.model_dir, attn_implementation="sdpa").to(aDevice, dtype=aDtype)
		aModel.eval()
		log("SAM 2.1 loaded from " + aArgs.model_dir + " on " + str(aDevice))
		return aModel, aDtype

	aModel, aDtype = ai_serve.cached(("sam2", aArgs.model_dir, str(aDevice)), load)
	out("DEVICE " + aDevice.type)
	aCtx = Context(aModel, aDtype, aDevice, torch)
	with torch.inference_mode():
		if aArgs.op == "open":
			return aCtx.open(aArgs)
		if aArgs.op == "close":
			# The session forgotten (its memory given back), the model stays loaded
			_S = State()
			import gc
			gc.collect()
			if aDevice.type == "cuda":
				torch.cuda.empty_cache()
			elif aDevice.type == "mps":
				torch.mps.empty_cache()
			out("DONE close")
			return 0
		if _S.frames is None:
			raise RuntimeError("No video open")
		if aArgs.op == "points":
			aPts = parsePoints(aArgs.points)
			aBox = [float(a) for a in aArgs.box.split(",")] if aArgs.box.strip() else None
			aPr = {"points": aPts, "box": aBox, "mask": None, "auto": False} if aPts or aBox else None
			return aCtx.setPrompt(aArgs.obj, aArgs.view, aArgs.frame, aPr)
		if aArgs.op == "mask":
			return aCtx.setPrompt(aArgs.obj, aArgs.view, aArgs.frame,
					{"points": [], "box": None, "mask": readMask(aArgs.mask, aArgs.view), "auto": False})
		if aArgs.op == "delete":
			return aCtx.delete(aArgs.obj)
		if aArgs.op == "track":
			return aCtx.track(aArgs.stop_file, aArgs.resume, aArgs.to)
		if aArgs.op == "export":
			return aCtx.export(aArgs)
	raise ValueError("Unknown op " + aArgs.op)


def readMask(aPath, aView):
	"""Mask painted for a view: bool array of the size of the view"""
	import numpy as np
	from PIL import Image
	w, h = _S.views[aView][2], _S.views[aView][3]
	aImg = Image.open(aPath).convert("L")
	if aImg.size != (w, h):
		aImg = aImg.resize((w, h), Image.NEAREST)
	return np.asarray(aImg) > 127


def parsePoints(aS):
	aPts = []
	for aP in aS.split(";"):
		aP = aP.strip()
		if aP:
			x, y, l = aP.split(",")
			aPts.append((float(x), float(y), int(l)))
	return aPts


class Context:
	def __init__(self, aModel, aDtype, aDevice, torch):
		self.model = aModel
		self.dtype = aDtype
		self.device = aDevice
		self.torch = torch

	def newSession(self, aView):
		from transformers.models.sam2_video.modeling_sam2_video import Sam2VideoInferenceSession
		# The memory of the tracked frames grows with the video: in the RAM on CUDA (the GPU memory is smaller)
		aStateDev = "cpu" if self.device.type == "cuda" else self.device
		aS = Sam2VideoInferenceSession(video=None, video_height=_S.views[aView][3], video_width=_S.views[aView][2],
				inference_device=self.device, inference_state_device=aStateDev, video_storage_device="cpu", dtype=self.dtype)
		aS.processed_frames = LazyFrames(_S, aView, self.dtype, self.torch)
		return aS

	def open(self, aArgs):
		_S.frames = aArgs.frames
		_S.count = aArgs.count
		_S.labels = aArgs.labels
		_S.views = []
		for aV in aArgs.views.split(";"):
			if aV.strip():
				_S.views.append([int(float(a)) for a in aV.split(",")])
		_S.prompts = {}
		_S.done = None
		_S.ends = {}
		_S.absent = {}
		_S.has = {}
		_S.sessions = [self.newSession(v) for v in range(len(_S.views))]
		os.makedirs(_S.labels, exist_ok=True)
		log("Mask video open: " + str(_S.count) + " frames, views " + str(_S.views))
		out("DONE open")
		return 0

	def apply(self, aView, aObj, aFrame, aPr):
		"""Gives the prompt of the object on that frame to the session of the view"""
		torch = self.torch
		aSess = _S.sessions[aView]
		aIdx = aSess.obj_id_to_idx(aObj)
		if aPr.get("mask") is not None:
			# As the processor of transformers: the mask at 1024 x 1024 (bilinear, antialiased), thresholded
			import torch.nn.functional as F
			aM = torch.from_numpy(aPr["mask"].astype("float32"))[None, None].to(self.device)
			aM = (F.interpolate(aM, size=(INPUT_SIZE, INPUT_SIZE), mode="bilinear", align_corners=False, antialias=True) >= 0.5).float()
			aSess.add_mask_inputs(aIdx, aFrame, aM)
			aSess.remove_point_inputs(aIdx, aFrame)
			aSess.obj_with_new_inputs = [aObj]
			return
		w, h = _S.views[aView][2], _S.views[aView][3]
		aSx, aSy = INPUT_SIZE / w, INPUT_SIZE / h
		aCoords = []
		aLabels = []
		if aPr.get("box") is not None:
			x0, y0, x1, y1 = aPr["box"]
			aCoords += [[x0 * aSx, y0 * aSy], [x1 * aSx, y1 * aSy]]
			aLabels += [2, 3]
		for x, y, l in aPr["points"]:
			aCoords.append([x * aSx, y * aSy])
			aLabels.append(l)
		aSess.add_point_inputs(aIdx, aFrame, {
				"point_coords": torch.tensor(aCoords, dtype=torch.float32).reshape(1, 1, -1, 2),
				"point_labels": torch.tensor(aLabels, dtype=torch.int32).reshape(1, 1, -1)})
		aSess.remove_mask_inputs(aIdx, aFrame)
		aSess.obj_with_new_inputs = [aObj]

	def masksAt(self, aView, aFrame):
		"""{obj: bool mask (view size)} of all the objects of the view at the frame (computed)"""
		aSess = _S.sessions[aView]
		if aSess.get_obj_num() == 0:
			return {}
		return self.toMasks(aView, self.forward(aView, aFrame))

	def forward(self, aView, aFrame, aReverse=False):
		"""The model on a frame of a view. A frame where an object gets points becomes a conditioning frame of that
		object, also when it was already tracked (a correction), as add_all_frames_to_correct_as_cond of SAM 2.1:
		transformers keeps a correction as a tracked frame, computed again without its points by the next pass
		(the points then lost, and not used as the memory of the tracking)"""
		aSess = _S.sessions[aView]
		aNew = list(aSess.obj_with_new_inputs)
		aOld = {}
		for aObj in aNew:
			aIdx = aSess._obj_id_to_idx.get(aObj)
			if aIdx is not None and aFrame in aSess.frames_tracked_per_obj[aIdx]:
				# A frame corrected again: its previous result is removed first. Else the memory of the new result
				# is encoded into that previous result (transformers stores it in the conditioning output of the
				# frame when there is one), and the new one, without memory, broke the frames that use it
				# (KeyError maskmem_features)
				aPrev = aSess.output_dict_per_obj[aIdx]["cond_frame_outputs"].pop(aFrame, None)
				if aPrev is not None:
					aOld[aIdx] = aPrev
		try:
			aOut = self.runFrame(aSess, aFrame, aReverse)
		except Exception:
			for aIdx, aPrev in aOld.items():
				aSess.output_dict_per_obj[aIdx]["cond_frame_outputs"].setdefault(aFrame, aPrev)
			raise
		for aObj in aNew:
			aIdx = aSess._obj_id_to_idx.get(aObj)
			if aIdx is None or (aFrame not in aSess.point_inputs_per_obj[aIdx] and aFrame not in aSess.mask_inputs_per_obj[aIdx]):
				continue
			aOD = aSess.output_dict_per_obj[aIdx]
			aCorr = aOD["non_cond_frame_outputs"].pop(aFrame, None)
			if aCorr is not None:
				# Computed with the memory and the points: kept as it is, reused by the next passes
				aOD["cond_frame_outputs"][aFrame] = aCorr
		return aOut

	def ended(self, aObj, aFrame):
		"""The object left the image before that frame (forward) or after it (backward), without a prompt of it
		since"""
		aE = _S.ends.get(aObj)
		if not aE:
			return False
		aFrames = {f for aFs in _S.prompts.get(aObj, {}).values() for f in aFs}
		aF = aE.get("fwd")
		if aF is not None and aFrame >= aF and not any(aF <= f <= aFrame for f in aFrames):
			return True
		aB = aE.get("back")
		return aB is not None and aFrame <= aB and not any(aFrame <= f <= aB for f in aFrames)

	def runFrame(self, aSess, aFrame, aReverse):
		"""Sam2VideoModel.forward of transformers 5 (frame_idx given, not streaming) on the objects not ended only,
		the high resolution masks not kept in the outputs (only used to encode the memory of the frame)"""
		torch = self.torch
		m = self.model
		aIds, aMasks, aEnc, aHigh, aLogits, aFromPts = [], [], [], [], [], []
		for aIdx in range(aSess.get_obj_num()):
			aObj = aSess.obj_idx_to_id(aIdx)
			aNewIn = aObj in aSess.obj_with_new_inputs
			if not aNewIn and self.ended(aObj, aFrame):
				continue
			aFirst = self.firstPrompt(aObj)
			if not aNewIn and aFirst is not None and (aFrame >= aFirst if aReverse else aFrame < aFirst):
				# Forward before its first prompt (it does not exist there yet: computed backward, by the pass of its
				# start), or backward over the frames it was tracked forward: not computed, its labels kept
				continue
			aReverseObj = aReverse
			if not aNewIn and aFrame in aSess.output_dict_per_obj[aIdx]["cond_frame_outputs"]:
				aPred = aSess.get_output(aIdx, aFrame, "pred_masks", is_conditioning_frame=True)
				aInit = True
			else:
				aInit = False
				aPts = None
				aMsk = None
				if aNewIn:
					aInit = aFrame not in aSess.frames_tracked_per_obj[aIdx]
					if aInit:
						aReverseObj = False
					aPts = aSess.point_inputs_per_obj[aIdx].get(aFrame, None)
					aMsk = aSess.mask_inputs_per_obj[aIdx].get(aFrame, None)
					if aPts is not None or aMsk is not None:
						aSess.obj_with_new_inputs.remove(aObj)
				aCur = m._run_single_frame_inference(inference_session=aSess, obj_idx=aIdx, frame_idx=aFrame, batch_size=1,
						is_init_cond_frame=aInit, point_inputs=aPts, mask_inputs=aMsk, reverse=aReverseObj, streaming=False)
				if not aInit and aPts is None and aMsk is None and self.sincePrompt(aObj, aFrame, aReverseObj) > CONTINUITY_AFTER:
					self.continuity(aSess, aIdx, aFrame, aReverseObj, aCur)
				aSess.store_output(aIdx, aFrame, output_value=aCur, is_conditioning_frame=aInit)
				aPred = aCur["pred_masks"]
				if m.num_maskmem > 0:
					aEnc.append(aIdx)
					aHigh.append(aCur["high_res_masks"])
					aLogits.append(aCur["object_score_logits"])
					aFromPts.append(aPts is not None or aMsk is not None)
			aIds.append(aObj)
			aMasks.append(aPred)
			if not aInit:
				aSess.frames_tracked_per_obj[aIdx][aFrame] = {"reverse": aReverseObj}
		m._batch_encode_memories(inference_session=aSess, frame_idx=aFrame, objects_needing_memory_encoding=aEnc,
				high_res_masks_for_memory=aHigh, object_score_logits_for_memory=aLogits, is_mask_from_pts_per_obj=aFromPts)
		for aIdx in aEnc:
			aOD = aSess.output_dict_per_obj[aIdx]
			for aKey in ("cond_frame_outputs", "non_cond_frame_outputs"):
				aO = aOD[aKey].get(aFrame)
				if aO is not None:
					aO.pop("high_res_masks", None)
					if "maskmem_features" in aO:
						# Slices of the memories of all the objects: copied, the batch is freed
						aO["maskmem_features"] = aO["maskmem_features"].clone()
						aO["maskmem_pos_enc"] = aO["maskmem_pos_enc"].clone()

		class Out:
			pass
		aOut = Out()
		aOut.object_ids = aIds
		aOut.pred_masks = torch.cat(aMasks, dim=0) if aMasks else None
		return aOut

	@staticmethod
	def firstPrompt(aObj):
		aFrames = [f for aFs in _S.prompts.get(aObj, {}).values() for f in aFs]
		return min(aFrames) if aFrames else None

	@staticmethod
	def sincePrompt(aObj, aFrame, aReverse):
		"""Frames since the last prompt of the object in the order of the tracking (large: none before)"""
		aFrames = [f for aFs in _S.prompts.get(aObj, {}).values() for f in aFs]
		aD = [f - aFrame if aReverse else aFrame - f for f in aFrames]
		aD = [d for d in aD if d >= 0]
		return min(aD) if aD else 1 << 30

	def sendEnds(self):
		"""ENDS obj:fwd:back;... the objects ended (- : none), for the editor"""
		aParts = []
		for aObj, aE in sorted(_S.ends.items()):
			if aObj in _S.prompts and (aE.get("fwd") is not None or aE.get("back") is not None):
				aParts.append(str(aObj) + ":" + ("-" if aE.get("fwd") is None else str(aE["fwd"])) + ":"
						+ ("-" if aE.get("back") is None else str(aE["back"])))
		out("ENDS " + ";".join(aParts))

	def continuity(self, aSess, aIdx, aFrame, aReverse, aCur):
		"""The parts of the mask of a tracked object (aCur, output of the model) not connected to its mask of the
		frame before (aFrame -1, or +1 backward) grown by CONTINUITY_REACH dropped (logits made negative in the low and
		high resolution masks); nothing left: the object absent (score logit negative, encoded as such in the memory)"""
		torch = self.torch
		import torch.nn.functional as F
		aPrevF = aFrame + (1 if aReverse else -1)
		aOD = aSess.output_dict_per_obj[aIdx]
		aPrev = aOD["cond_frame_outputs"].get(aPrevF) or aOD["non_cond_frame_outputs"].get(aPrevF)
		if aPrev is None or aPrev.get("pred_masks") is None:
			return
		aCurM = aCur["pred_masks"] > 0
		if not bool(aCurM.any()):
			return
		aPrevM = (aPrev["pred_masks"].to(aCurM.device) > 0).float()
		# Reach: CONTINUITY_REACH, at least CONTINUITY_PART of the largest side of the object (its parts appear one
		# after the other: legs behind an obstacle...)
		aYs = torch.nonzero(aPrevM[0, 0].amax(1) > 0)
		aXs = torch.nonzero(aPrevM[0, 0].amax(0) > 0)
		aSide = 0 if len(aYs) == 0 else max(int(aYs[-1] - aYs[0]), int(aXs[-1] - aXs[0])) + 1
		r = min(aPrevM.shape[-1] // 2, max(CONTINUITY_REACH, int(round(CONTINUITY_PART * aSide))))
		aGrown = F.max_pool2d(F.max_pool2d(aPrevM, (2 * r + 1, 1), stride=1, padding=(r, 0)), (1, 2 * r + 1), stride=1, padding=(0, r))
		aKeep = (aGrown > 0) & aCurM
		# Morphological reconstruction: the connected parts of the mask reached from there (8 neighbors)
		aCurF = aCurM.float()
		while True:
			aN = aKeep
			for _ in range(16):
				aN = (F.max_pool2d(aN.float(), 3, stride=1, padding=1) * aCurF) > 0
			if torch.equal(aN, aKeep):
				break
			aKeep = aN
		aDrop = aCurM & ~aKeep
		if not bool(aDrop.any()):
			return
		aLow = aCur["pred_masks"]
		aCur["pred_masks"] = torch.where(aDrop, torch.full_like(aLow, -10.0), aLow)
		aHigh = aCur.get("high_res_masks")
		if aHigh is not None:
			aD = F.interpolate(aDrop.float(), size=aHigh.shape[-2:], mode="nearest") > 0
			aCur["high_res_masks"] = torch.where(aD, torch.full_like(aHigh, -10.0), aHigh)
		if not bool(aKeep.any()):
			aCur["object_score_logits"] = torch.full_like(aCur["object_score_logits"], -10.0)
		aObj = aSess.obj_idx_to_id(aIdx)
		log("Mask tracking: object " + str(aObj) + " frame " + str(aFrame) + ": " + str(int(aDrop.sum())) + " / "
				+ str(int(aCurM.sum())) + " points (reach " + str(r) + ") not connected to its mask of the frame before, dropped")

	def prune(self, aFrame):
		"""Outputs of the tracked frames farther than KEEP_FRAMES from aFrame dropped (the conditioning ones kept)"""
		for aSess in _S.sessions:
			for aOD in aSess.output_dict_per_obj.values():
				aNC = aOD["non_cond_frame_outputs"]
				for f in [f for f in aNC if abs(f - aFrame) > KEEP_FRAMES]:
					del aNC[f]

	def toMasks(self, aView, aOut):
		import torch.nn.functional as F
		w, h = _S.views[aView][2], _S.views[aView][3]
		if aOut.pred_masks is None:
			return {}
		aM = F.interpolate(aOut.pred_masks.float(), (h, w), mode="bilinear", align_corners=False)
		aM = (aM[:, 0] > 0.0).cpu().numpy()
		return {aObj: aM[i] for i, aObj in enumerate(aOut.object_ids)}

	def gray(self, aFrame, aView):
		import numpy as np
		from PIL import Image
		x, y, w, h = _S.views[aView]
		aImg = Image.open(framePath(_S.frames, aFrame)).convert("L").crop((x, y, x + w, y + h))
		return np.asarray(aImg, dtype=np.float32)

	def parallax(self, aObj, aFrom, aTo, aFrame, aMask, x0, x1, y0, y1):
		"""Horizontal shift of the object (its mask in aFrom, box x0..x1, y0..y1) in the view aTo: the one that best
		matches its pixels along the lines (a small cost for large shifts)"""
		import numpy as np
		aA = self.gray(aFrame, aFrom)
		aB = self.gray(aFrame, aTo)
		w = min(aA.shape[1], aB.shape[1])
		# Every other pixel: enough for the shift, 4 times faster
		aPatch = aA[y0:y1:2, x0:x1:2]
		aM = aMask[y0:y1:2, x0:x1:2]
		aMax = max(4, int(w * 0.15))
		aBest, aBestDx = None, 0
		for aDx in range(-aMax, aMax + 1):
			if x0 + aDx < 0 or x1 + aDx > w:
				continue
			aC = aB[y0:y1:2, x0 + aDx:x1 + aDx:2]
			if aC.shape != aPatch.shape:
				continue
			aCost = float(np.abs(aC - aPatch)[aM].mean()) + 0.02 * abs(aDx)
			if aBest is None or aCost < aBest:
				aBest, aBestDx = aCost, aDx
		log("Mask object " + str(aObj) + " frame " + str(aFrame) + ": parallax " + str(aBestDx) + " px (view " + str(aFrom) + " -> " + str(aTo) + ")")
		return aBestDx

	@staticmethod
	def box(aMask):
		"""Box x0, x1, y0, y1 of a mask, None if empty"""
		import numpy as np
		aYs, aXs = np.nonzero(aMask)
		if len(aXs) == 0:
			return None
		return int(aXs.min()), int(aXs.max()) + 1, int(aYs.min()), int(aYs.max()) + 1

	def seedMask(self, aObj, aFrom, aTo, aFrame, aMask):
		"""Prompt of the view aTo from a mask painted in aFrom: that mask shifted by the parallax of the object (the
		shape drawn by the user kept; the points of seed() would let the model draw it again), None if empty"""
		import numpy as np
		aB = self.box(aMask)
		if aB is None:
			return None
		aDx = self.parallax(aObj, aFrom, aTo, aFrame, aMask, *aB)
		h, w = _S.views[aTo][3], _S.views[aTo][2]
		aSrc = np.zeros((h, w), dtype=bool)
		aSrc[:min(h, aMask.shape[0]), :min(w, aMask.shape[1])] = aMask[:h, :w]
		aOut = np.zeros((h, w), dtype=bool)
		if aDx >= 0:
			aOut[:, aDx:] = aSrc[:, :w - aDx]
		else:
			aOut[:, :w + aDx] = aSrc[:, -aDx:]
		return {"points": [], "box": None, "mask": aOut, "auto": True}

	def seed(self, aObj, aFrom, aTo, aFrame, aMask, aPts):
		"""Prompt of the object in the view aTo from its mask in aFrom: the same box and points shifted by the
		parallax that best matches the pixels of the object (search along the lines), plus points inside the
		mask spread over the object, None if no mask. The inside points carry the whole object: on a
		frame already tracked, the box and the clicks alone are not enough for the model to take a correction
		(a zone added in one view was not added in the other)"""
		import numpy as np
		aB = self.box(aMask)
		if aB is None:
			return None
		x0, x1, y0, y1 = aB
		aBestDx = self.parallax(aObj, aFrom, aTo, aFrame, aMask, x0, x1, y0, y1)
		# Inside points: one per cell of a SEED_GRID x SEED_GRID grid over the box that holds some of the mask (so
		# that every part of the object gets one, a thin zone added included), the median pixel of the cell, taken
		# in the mask eroded by 2 pixels when the cell still has some (away from the edges)
		aEr = aMask.copy()
		for _ in range(2):
			aE = aEr.copy()
			aE[1:, :] &= aEr[:-1, :]
			aE[:-1, :] &= aEr[1:, :]
			aE[:, 1:] &= aEr[:, :-1]
			aE[:, :-1] &= aEr[:, 1:]
			aEr = aE
		aInside = []
		aGX = np.linspace(x0, x1, SEED_GRID + 1).astype(int)
		aGY = np.linspace(y0, y1, SEED_GRID + 1).astype(int)
		for j in range(SEED_GRID):
			for i in range(SEED_GRID):
				for aM2 in (aEr, aMask):
					aCy, aCx = np.nonzero(aM2[aGY[j]:max(aGY[j + 1], aGY[j] + 1), aGX[i]:max(aGX[i + 1], aGX[i] + 1)])
					if len(aCx) > 0:
						k = len(aCx) // 2
						aInside.append((float(aGX[i] + aCx[k] + aBestDx), float(aGY[j] + aCy[k]), 1))
						break
		return {"points": [(p[0] + aBestDx, p[1], p[2]) for p in aPts] + aInside,
				"box": [x0 + aBestDx, y0, x1 + aBestDx, y1], "auto": True}

	def setPrompt(self, aObj, aView, aFrame, aPr):
		"""The prompt of the object in the view on the frame (points / box / mask, None: removed), the other views
		seeded from its result, the labels of the frame written"""
		import numpy as np
		if aObj < 1 or aObj > MAX_OBJECTS:
			raise ValueError("Object id out of 1.." + str(MAX_OBJECTS))
		aObjP = _S.prompts.setdefault(aObj, {})
		_S.absent.pop(aObj, None)
		# The other views follow this one (seeded), unless the user gave a prompt in them on this frame
		aOthers = [v for v in range(len(_S.views)) if v != aView and aObjP.get(v, {}).get(aFrame, {"auto": True})["auto"]]
		aRemoved = False
		if aPr is not None:
			aObjP.setdefault(aView, {})[aFrame] = aPr
			# Replaces the prompt of the object on this frame
			self.apply(aView, aObj, aFrame, aPr)
			aMasks = self.masksAt(aView, aFrame)
			aMask = aPr["mask"] if aPr.get("mask") is not None else aMasks.get(aObj)
			for v in aOthers:
				if aPr.get("mask") is not None:
					aSeed = self.seedMask(aObj, aView, v, aFrame, aMask)
				else:
					aSeed = self.seed(aObj, aView, v, aFrame, aMask, aPr["points"]) if aMask is not None else None
				if aSeed is None and aPr.get("mask") is not None:
					# Everything erased: the object is not in the other view either
					aSeed = {"points": [], "box": None, "mask": np.zeros((_S.views[v][3], _S.views[v][2]), dtype=bool), "auto": True}
				if aSeed is not None:
					aObjP.setdefault(v, {})[aFrame] = aSeed
					self.apply(v, aObj, aFrame, aSeed)
				elif aObjP.get(v, {}).pop(aFrame, None) is not None:
					aRemoved = True
		else:
			aRemoved = aObjP.get(aView, {}).pop(aFrame, None) is not None
			for v in aOthers:
				if aObjP.get(v, {}).pop(aFrame, None) is not None:
					aRemoved = True
		if not any(aObjP.values()):
			_S.prompts.pop(aObj, None)
		if aRemoved:
			self.rebuild()
		self.forget(aObj, aFrame)
		self.writeFrame(aFrame)
		self.sendEnds()
		out("DONE prompt")
		return 0

	def forget(self, aObj, aFrame):
		"""A prompt of the object changed on aFrame: its masks computed after it (in the order of the tracking) are out
		of date, removed from the labels (the editor showed them, then computed them again: an old version seen). The
		frames after it, and, when aFrame is its first prompt, those before it (its backward pass); the other objects
		are tracked on their own: kept"""
		import numpy as np
		from PIL import Image
		aFirst = self.firstPrompt(aObj)
		aFs = sorted(n for n in _S.has.get(aObj, ()) if n > aFrame or (aFirst is not None and aFirst >= aFrame and n < aFrame))
		if not aFs:
			return
		aBit = np.uint16(1 << (aObj - 1))
		for n in aFs:
			aPath = os.path.join(_S.labels, "%06d.png" % n)
			if os.path.isfile(aPath):
				aL = np.asarray(Image.open(aPath), dtype=np.uint16)
				saveLabels(aPath, aL & ~aBit)
				out("LABELS " + str(n))
			_S.has[aObj].discard(n)
		if _S.done is not None:
			# Computed again by the next tracking (the backward frames too)
			_S.done["frames"] -= set(aFs)
		log("Mask: object " + str(aObj) + " changed on frame " + str(aFrame) + ", its masks of " + str(len(aFs)) + " frames removed")

	def rebuild(self):
		"""New sessions with the prompts of the objects (after a removal: the model can't forget an object)"""
		_S.sessions = [self.newSession(v) for v in range(len(_S.views))]
		_S.done = None
		_S.ends = {}
		_S.absent = {}
		for aObj in sorted(_S.prompts):
			for aView, aFrames in _S.prompts[aObj].items():
				for i, (aFrame, aPr) in enumerate(sorted(aFrames.items())):
					self.apply(aView, aObj, aFrame, aPr)
					if i > 0:
						# A correction: computed with the memory of the frames given before (seen as tracked), as
						# when it was clicked, not from its points alone
						aSess = _S.sessions[aView]
						aSess.frames_tracked_per_obj[aSess.obj_id_to_idx(aObj)][aFrame] = {"reverse": False}
					self.forward(aView, aFrame)

	@staticmethod
	def removeObject(aSess, aObj):
		"""The object taken out of the session, the others kept with their memory and outputs (their indexes shifted).
		The objects are tracked independently (own memory, own outputs): nothing to compute again for the others"""
		aIdx = aSess._obj_id_to_idx.get(aObj)
		if aIdx is None:
			return
		aIds = [o for o in aSess._obj_id_to_idx if o != aObj]
		aOld = {o: aSess._obj_id_to_idx[o] for o in aIds}
		for aName in ("point_inputs_per_obj", "mask_inputs_per_obj", "output_dict_per_obj", "frames_tracked_per_obj"):
			aD = getattr(aSess, aName)
			aNew = {i: aD[aOld[o]] for i, o in enumerate(aIds)}
			aD.clear()
			aD.update(aNew)
		aSess._obj_id_to_idx.clear()
		aSess._obj_idx_to_id.clear()
		for i, o in enumerate(aIds):
			aSess._obj_id_to_idx[o] = i
			aSess._obj_idx_to_id[i] = o
		aSess.obj_ids = list(aIds)
		aSess.obj_with_new_inputs = [o for o in aSess.obj_with_new_inputs if o != aObj]

	def delete(self, aObj):
		"""Object removed: out of the sessions and of the labels; the tracking of the others kept as it is (a tracking
		then resumes where it would have)"""
		import numpy as np
		from PIL import Image
		_S.ends.pop(aObj, None)
		_S.absent.pop(aObj, None)
		_S.has.pop(aObj, None)
		if _S.prompts.pop(aObj, None) is not None:
			for aSess in _S.sessions:
				self.removeObject(aSess, aObj)
			if _S.done is not None:
				_S.done["objs"].discard(aObj)
				aBack = _S.done.get("backObjs")
				if aBack:
					# The backward pass still due: only for the other objects added since
					aBack.discard(aObj)
					aStarts = [f for o in aBack if o in _S.prompts for aFs in _S.prompts[o].values() for f in aFs]
					_S.done["back"] = set(range(0, min(aStarts))) if aStarts else set()
				if not _S.done["objs"]:
					_S.done = None
		# Its bit cleared in the frames already computed
		aBit = np.uint16(1 << (aObj - 1))
		for aName in os.listdir(_S.labels):
			if aName.endswith(".png"):
				aPath = os.path.join(_S.labels, aName)
				aL = np.asarray(Image.open(aPath), dtype=np.uint16)
				if (aL & aBit).any():
					saveLabels(aPath, aL & ~aBit)
		self.sendEnds()
		out("DONE delete")
		return 0

	def writeFrame(self, aFrame, aMasksPerView=None):
		import numpy as np
		aW = max(v[0] + v[2] for v in _S.views)
		aH = max(v[1] + v[3] for v in _S.views)
		aL = np.zeros((aH, aW), dtype=np.uint16)
		aPath = os.path.join(_S.labels, "%06d.png" % aFrame)
		aOld = None
		for v, (x, y, w, h) in enumerate(_S.views):
			aMasks = dict(aMasksPerView[v] if aMasksPerView is not None else self.masksAt(v, aFrame))
			# The objects not computed on this frame (before their first prompt forward...): their labels kept, unless
			# ended there
			aKeep = 0
			for aObj in _S.prompts:
				if aObj not in aMasks and 1 <= aObj <= MAX_OBJECTS and not self.ended(aObj, aFrame):
					aKeep |= 1 << (aObj - 1)
			if aKeep:
				if aOld is None:
					from PIL import Image
					aOld = np.asarray(Image.open(aPath), dtype=np.uint16) if os.path.isfile(aPath) else False
				if aOld is not False:
					aL[y:y + h, x:x + w] |= aOld[y:y + h, x:x + w] & np.uint16(aKeep)
			# A painted mask is the result of its frame as it is (the model output is 256 x 256)
			for aObj, aObjP in _S.prompts.items():
				aPr = aObjP.get(v, {}).get(aFrame)
				if aPr is not None and aPr.get("mask") is not None:
					aMasks[aObj] = aPr["mask"]
			for aObj, aM in aMasks.items():
				if 1 <= aObj <= MAX_OBJECTS and aObj in _S.prompts:
					aL[y:y + h, x:x + w][aM] |= np.uint16(1 << (aObj - 1))
		saveLabels(aPath, aL)
		aAll = int(np.bitwise_or.reduce(aL.ravel())) if aL.size else 0
		for aObj in range(1, MAX_OBJECTS + 1):
			if aAll & (1 << (aObj - 1)):
				_S.has.setdefault(aObj, set()).add(aFrame)
			elif aObj in _S.has:
				_S.has[aObj].discard(aFrame)
		out("LABELS " + str(aFrame))

	def track(self, aStop, aResume=None, aTo=None):
		torch = self.torch
		aStarts = [f for aObjP in _S.prompts.values() for aFrames in aObjP.values() for f in aFrames]
		if not aStarts:
			raise ValueError("NOOBJECT")
		aStart = min(aStarts)
		aObjs = set(_S.prompts)
		if aResume is not None and _S.done is not None and aObjs <= _S.done["objs"]:
			# From the frame asked, or from the first frame of the forward pass not computed (stopped before)
			aUndone = [f for f in range(aStart, _S.count) if f not in _S.done["frames"]]
			s = max(0, min(aResume, aUndone[0] if aUndone else _S.count))
			aFwd = max(s, aStart)
			aOrder = [(f, False) for f in range(aFwd, _S.count)]
			if s <= aStart:
				# From the first prompt or before: the start of the video backward again
				aOrder += [(f, True) for f in range(aStart - 1, -1, -1)]
			else:
				aOrder += [(f, True) for f in range(aStart - 1, -1, -1) if f not in _S.done["frames"]]
			out("TRACKMODE resume " + str(aOrder[0][0] if aOrder else _S.count))
		elif aResume is not None and _S.done is not None and _S.done["objs"] and _S.done["objs"] <= aObjs:
			# Objects added since the last tracking (the others unchanged, the session kept): forward from the first
			# prompt of the new ones, the frames before kept for the others; the new ones get the frames before
			# their first prompt by a backward pass at the next tracking without --to ("back")
			aNew = aObjs - _S.done["objs"]
			aNewStart = min(f for o in aNew for aFrames in _S.prompts[o].values() for f in aFrames)
			aUndone = [f for f in range(aStart, _S.count) if f not in _S.done["frames"]]
			s = max(0, min(aResume, aNewStart, aUndone[0] if aUndone else _S.count))
			aOrder = [(f, False) for f in range(max(s, aStart), _S.count)]
			_S.done.setdefault("back", set()).update(range(0, aNewStart))
			_S.done.setdefault("backObjs", set()).update(aNew)
			_S.done["objs"] = set(aObjs)
			out("TRACKMODE resume " + str(aOrder[0][0] if aOrder else _S.count))
		else:
			# Forward from the first frame clicked to the end, then backward to the start
			# (backward from the latest first prompt: the objects clicked later get the frames before it)
			aLast = max(self.firstPrompt(o) for o in aObjs)
			aOrder = [(f, False) for f in range(aStart, _S.count)] + [(f, True) for f in range(aLast - 1, -1, -1)]
			_S.done = {"objs": aObjs, "frames": set()}
			out("TRACKMODE full " + str(aStart))
		if aTo is None and _S.done.get("back"):
			# The frames before the first prompt of objects added: backward, after the forward pass
			aHave = set(aOrder)
			aOrder += [(f, True) for f in sorted(_S.done["back"], reverse=True) if (f, True) not in aHave]
		if aTo is not None:
			# A step: forward up to it, or backward down to it before the first prompt
			if aTo >= aStart:
				aOrder = [(f, r) for f, r in aOrder if not r and f <= aTo]
			else:
				aOrder = [(f, r) for f, r in aOrder if r and f >= aTo]
		# Computed again: not done until then (a stop leaves them to the next resume)
		_S.done["frames"] -= {f for f, r in aOrder}
		aT0 = time.time()
		# The ends found beyond the start of the passes are found again (the frames before them computed again)
		aFwd0 = min((f for f, r in aOrder if not r), default=None)
		aBack0 = max((f for f, r in aOrder if r), default=None)
		for aE in _S.ends.values():
			if aFwd0 is not None and aE.get("fwd") is not None and aE["fwd"] > aFwd0:
				aE["fwd"] = None
			if aBack0 is not None and aE.get("back") is not None and aE["back"] < aBack0:
				aE["back"] = None
		if aFwd0 is not None and aFwd0 > aStart:
			# Resumed where the memory of the frames before was dropped: those frames computed again first
			aWarm = [f for f in range(max(aStart, aFwd0 - WARM_FRAMES), aFwd0)
					if any(aIdx in aS.output_dict_per_obj and f in aS.frames_tracked_per_obj.get(aIdx, {})
						and f not in aS.output_dict_per_obj[aIdx]["non_cond_frame_outputs"]
						and f not in aS.output_dict_per_obj[aIdx]["cond_frame_outputs"]
						and not self.ended(aS.obj_idx_to_id(aIdx), f)
						for aS in _S.sessions for aIdx in range(aS.get_obj_num()))]
			if aWarm:
				for f in range(aWarm[0], aFwd0):
					for v in range(len(_S.views)):
						if _S.sessions[v].get_obj_num() > 0:
							self.forward(v, f)
					self.prune(f)
				log("Mask tracking: " + str(aFwd0 - aWarm[0]) + " frames computed again for the memory of frame " + str(aFwd0))
		for i, (aFrame, aReverse) in enumerate(aOrder):
			if aStop and os.path.exists(aStop):
				import ai_serve
				raise ai_serve.Stopped()
			aPer = []
			for v in range(len(_S.views)):
				aSess = _S.sessions[v]
				if aSess.get_obj_num() == 0:
					aPer.append({})
					continue
				aOut = self.forward(v, aFrame, aReverse)
				aPer.append(self.toMasks(v, aOut))
			self.writeFrame(aFrame, aPer)
			self.prune(aFrame)
			self.checkEnds(aFrame, aReverse, aPer)
			_S.done["frames"].add(aFrame)
			out("STEP " + str(i + 1) + " " + str(len(aOrder)))
		if aTo is None:
			_S.done["back"] = set()
			_S.done["backObjs"] = set()
		if self.device.type == "mps":
			torch.mps.empty_cache()
		elif self.device.type == "cuda":
			torch.cuda.empty_cache()
		aActive = sorted(o for o in _S.prompts if not self.ended(o, aOrder[-1][0])) if aOrder else []
		log("Mask tracking: " + str(len(aOrder)) + " frames x " + str(len(_S.views)) + " views in " + str(round(time.time() - aT0, 1)) + " s, objects computed " + str(aActive))
		if self.device.type == "cuda":
			out("PEAK mask " + str(torch.cuda.max_memory_reserved(self.device)))
		self.sendEnds()
		out("DONE track")
		return 0

	def checkEnds(self, aFrame, aReverse, aPer):
		"""Objects absent on END_FRAMES frames in a row (in the order of the tracking) ended after that frame"""
		for aObj in _S.prompts:
			if self.ended(aObj, aFrame):
				continue
			if not any(aObj in aMasks for aMasks in aPer):
				# Not computed on this frame (before its first prompt...)
				continue
			aSeen = any(aMasks.get(aObj) is not None and aMasks[aObj].any() for aMasks in aPer)
			aA = _S.absent.get(aObj)
			if aSeen:
				_S.absent.pop(aObj, None)
				continue
			if aA is not None and aA[1] == aReverse and aA[0] == aFrame + (1 if aReverse else -1):
				aA[0] = aFrame
				aA[2] += 1
			else:
				aA = _S.absent[aObj] = [aFrame, aReverse, 1]
			if aA[2] >= END_FRAMES:
				aE = _S.ends.setdefault(aObj, {})
				aE["back" if aReverse else "fwd"] = aFrame - 1 if aReverse else aFrame + 1
				_S.absent.pop(aObj, None)
				log("Mask tracking: object " + str(aObj) + " absent since " + str(aFrame + ((END_FRAMES - 1) if aReverse else -(END_FRAMES - 1)))
						+ ", no longer computed " + ("before " if aReverse else "after ") + str(aFrame))

	def export(self, aArgs):
		import subprocess
		import numpy as np
		from PIL import Image
		aVW, aVH = [int(a) for a in aArgs.view_size.lower().split("x")]
		aN = len(_S.views)
		aOutW, aOutH = aVW * aN, aVH
		aKeep = np.uint16(sum(1 << (k - 1) for k in _S.prompts if 1 <= k <= MAX_OBJECTS))
		aBlank = np.zeros((aOutH, aOutW), dtype=np.uint8)

		def frame(aF):
			aPath = os.path.join(_S.labels, "%06d.png" % aF)
			if not os.path.isfile(aPath):
				return aBlank
			aL = np.asarray(Image.open(aPath), dtype=np.uint16) & aKeep
			aOut = Image.new("L", (aOutW, aOutH), 0)
			for v, (x, y, w, h) in enumerate(_S.views):
				aV = Image.fromarray(np.where(aL[y:y + h, x:x + w] != 0, 255, 0).astype(np.uint8))
				# Bilinear: soft edges of a pixel or two at the full size, not stairs
				aOut.paste(aV.resize((aVW, aVH), Image.BILINEAR), (v * aVW, 0))
			return np.asarray(aOut, dtype=np.uint8)

		os.makedirs(os.path.dirname(os.path.abspath(aArgs.out)), exist_ok=True)
		out("STATUS WritingMask")
		if _S.count <= 1 or aArgs.out.lower().endswith(".png"):
			Image.fromarray(frame(0)).save(aArgs.out, "PNG")
			out("DONE " + aArgs.out)
			return 0
		aCmd = [aArgs.ffmpeg, "-y", "-v", "error", "-f", "rawvideo", "-pix_fmt", "gray", "-s", str(aOutW) + "x" + str(aOutH),
				"-r", aArgs.fps, "-i", "-", "-c:v", "libx264", "-preset", "medium", "-crf", "12", "-pix_fmt", "yuv420p",
				"-movflags", "+faststart", aArgs.out]
		log("Mask video: " + " ".join(aCmd))
		aP = subprocess.Popen(aCmd, stdin=subprocess.PIPE)
		try:
			for aF in range(_S.count):
				aP.stdin.write(frame(aF).tobytes())
				if aF % 10 == 0 or aF == _S.count - 1:
					out("STEP " + str(aF + 1) + " " + str(_S.count))
		finally:
			aP.stdin.close()
			aP.wait()
		if aP.returncode != 0:
			raise RuntimeError("ffmpeg failed (" + str(aP.returncode) + ")")
		out("DONE " + aArgs.out)
		return 0


def saveLabels(aPath, aL):
	import numpy as np
	from PIL import Image
	aTmp = aPath + ".tmp.png"
	# Little endian 16 bits: mode I;16 (recent Pillow infers it from the array)
	Image.fromarray(np.ascontiguousarray(aL, dtype="<u2")).save(aTmp, "PNG")
	os.replace(aTmp, aPath)


if __name__ == "__main__":
	import ai_serve
	ai_serve.run(main)
