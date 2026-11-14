# Prompt extension for the AI video generator of 3Denlive (run by com.cubaix.TDenlive.ai.PromptExtender).
#
# Qwen/Qwen3-VL-4B-Instruct (Apache-2.0, multilingual vision language model, transformers
# Qwen3VLForConditionalGeneration) rewrites the prompt of the user, written in any language, into one detailed
# English paragraph in the style of the captions Wan2.2 was trained with (as the prompt extension advised by the
# authors of Wan). With a reference image (first frame of the video), it sees the image and describes it. Music and
# sound effects panels: the prompt of a sound suited to an image (modes music, sfx; sfx: "prompt|seconds"). bf16 on
# CUDA, MPS or the CPU (--device cpu forces it). The model files are downloaded by 3Denlive before (no network
# access here).
# No torchvision in the environment: the processor of transformers (whose video part needs it) is not used; the
# image goes through the PIL image processor and the image tokens are expanded here, as Qwen3VLProcessor does.
#
# Protocol on stdout, one line per message:
#  STATUS <key>              step (key of the AI.* language entries)
#  DEVICE <cuda|mps|cpu>     device used
#  STEP <done> <total>       generated tokens
#  PEAK <stage> <bytes>      peak GPU memory reserved (CUDA)
#  DONE <path>               English prompt written (UTF-8)
#  RESULT <json string>      --serve: English prompt of a request (one JSON line per request on stdin)
#  FAIL <OOM|text>           --serve: failure of a request (the server goes on)
#  ERROR <OOM|text>          failure
import argparse
import json
import os
import random
import re
import sys
import traceback

os.environ.setdefault("HF_HUB_OFFLINE", "1")
os.environ.setdefault("TRANSFORMERS_OFFLINE", "1")

COMMON = """You write prompts for Wan2.2, an AI video generator that only understands English and works best with \
one detailed, concrete paragraph.
Rewrite the user's request, whatever its language, into such a prompt. The request defines the video: your job is \
to make it precise and concrete, never to change it.
- Write in English only, one paragraph of 50 to 110 words, plain text: no title, no list, no quotes, no comment.
- Keep the intent exactly: what the user asks for happens, and nothing else happens. Do not add actions, motions, \
events, characters, objects or camera movements the user did not ask for, and do not drop or soften anything the \
user asked for.
- You may add only descriptive details that make the request concrete: the appearance of what is there, the \
place, the light, the colors, the atmosphere, the style, the framing, and how the requested change or motion \
unfolds (speed, order, direction).
- When the request is a change of the picture itself (colorization, light, time of day, season, weather, style, a \
transformation), or when it says that the scene or the camera is still, everything else stays still: the subjects \
do not move, and the camera is static unless the user asks for a camera movement. Describe only that change.
- When the request asks for a motion, describe that motion, and only that motion, precisely.
- A request to animate or bring to life the picture without saying how ("anime cette image", "animate this \
picture") asks for subtle, natural motions of what is there (breathing, wind, water, light), nothing more. The \
French verb "animer" ("anime") means to animate, never the Japanese anime style.
- Camera: keep the camera movement of the user; if none is given, use a static camera, except when the requested \
action clearly needs a simple one (following a subject that travels).
- Describe a single short continuous shot of about 5 seconds: no cut, no scene change, no text or logo.
- If the user gives a visual style (cartoon, painting, black and white...), keep it; otherwise aim for a realistic, \
cinematic look.
Answer with the prompt only."""

T2V = COMMON + """
The video is generated from the text only: describe the whole scene the request implies."""

I2V = COMMON + """
The image given with the request is the first frame of the video: the video model must continue it exactly, so \
your prompt must describe its layout precisely before the motion. Look at it carefully:
- A precise description of the first frame comes with the request: it is right, follow it exactly, even when \
the request suggests otherwise (the request says what happens next, not how the first frame is).
- Begin with the words "In the first frame," and describe its configuration from that description, with no \
motion yet: for each main subject, its place in the frame (left, center, \
right; foreground, middle ground, background), its size, the direction it faces and the direction it is heading \
(toward the viewer, away, toward the left or the right edge of the frame, diagonally toward the bottom left \
corner...), its posture, and how the subjects are linked (an animal harnessed in front of a sleigh, a person \
seated behind it...). Then the setting (place, buildings, season, weather, light) and the viewpoint of the camera \
(eye level, high, low; distance).
- Name what the image really shows (a donkey is not a horse), and describe the colors as they are: a black and \
white or sepia image is described as such, with no invented color.
- Then apply the request to these elements: every motion starts from this configuration and follows the \
direction the subjects already face, unless the user asks otherwise; when the request implies another direction, \
describe the turn explicitly from the heading of the first frame (the donkey, first heading toward the bottom \
left corner, turns to its left...). Then describe the motion of the camera. Left and \
right always mean the left and the right of the frame as the viewer sees it.
- The image only gives the context: it never adds motion or action. Never contradict the image and do not add \
elements that are not in it, unless the user asks for them.
- For this image to video case, write 90 to 160 words."""

# First step with an image: the layout of the first frame described alone, before the request is read (a small
# model mixes the motion asked into what it sees otherwise); cached per image
LAYOUT = """You describe a photo precisely for an AI video generator that will continue it as the first frame of a \
video. Describe only what is visible, as it is, with no motion and no story.
- For each main subject: what it is exactly (a donkey is not a horse), its place in the frame (left, center, right; \
foreground, middle ground, background), its size, the direction it faces and the direction it is heading \
(toward the viewer, away, toward the left or the right edge, diagonally toward a corner of the frame), its \
posture, and how the subjects are linked (an animal harnessed in front of a sleigh, people seated in it...).
- Then the setting (place, buildings, season, weather, light, shadows), the colors as they are (a black and white \
or sepia photo is said to be one, with no invented color) and the viewpoint of the camera (eye level, high, low; \
distance).
- Left and right are those of the frame as the viewer sees it.
Answer in English, one paragraph of 60 to 120 words, plain text."""

# Without an image (text only request in image mode): the image can't be seen
I2V_BLIND = COMMON + """
The video starts from an image chosen by the user, that you cannot see: it is the first frame. Apply the request \
to that image, refer to its content generically when needed ("the person in the image", "the scene", "the \
landscape"), and do not describe an appearance that could contradict the image (colors, clothes, number of people) \
unless the user mentions it."""

# Sound from an image (music or sound effects panel): the model looks at the image and writes the prompt of a
# sound suited to it, following the request of the user if any (any language)
MUSIC = """You write prompts for MusicGen, an AI music generator that only understands English. Look at the \
image and write the prompt of an instrumental music that would suit it as a soundtrack: its mood, its place, its \
time, its light and its pace.
- One line in English, 15 to 35 words, plain text, in this style: "slow orchestral elegy for full strings with \
soft horns and long sustained chords, solemn and noble, 56 bpm".
- Give the genre, the main instruments, the mood and the feel, and end with the tempo as ", <number> bpm".
- Instrumental only: no voice, no lyrics, no song title, no artist name.
- If the user gives a request (in any language), it comes first: keep its genre, instruments or mood, and adapt \
the rest to the image.
Answer with the prompt only."""

SFX = """You write prompts for an AI sound effects generator that only understands English. Look at the image \
and write the prompt of the sound that this scene would make, as if it were recorded there.
- One line in English, 6 to 20 words, plain text, in this style: "heavy rain falling on a tin roof with distant \
thunder", "footsteps walking slowly on a gravel path".
- Write what one would HEAR there, not what one sees: the main sound source with a verb of sound (barks, \
rustles, creaks, crunches, rumbles, beats, jingles, whistles, splashes...), concretely; add the ambience only if \
it matters. No music, no speech, no words spoken.
- Name what the image really shows (a donkey is not a horse) and only what is there: invent no sound source.
- Describe sounds only: leave out what makes no sound (buildings, colors, reflections, the look of the place), \
except a material or a surface that shapes the sound (snow, gravel, a wooden floor, a large hall). A still \
landscape sounds through its wind, leaves, birds or water, if they are there.
- If the user gives a request (in any language), it comes first: describe the sound asked, in the setting of the \
image.
- Then add "|" and the duration in seconds that suits this sound, from 1 to 10: a single impact 1 to 3, a short \
event 4 to 6, a continuous activity (walking, pulling, playing, rustling) or an ambience 8 to 10. For example: \
"a wooden door creaks open slowly|4", "a donkey pulling a sleigh through deep snow, bells jingling|8".
Answer with that line only."""

# Longest side of the image given to the model (enough to recognize the scene, few image tokens)
IMAGE_SIDE = 768


def out(*aArgs):
	print(*aArgs, flush=True)


def main():
	aP = argparse.ArgumentParser()
	aP.add_argument("--model-dir", required=True)
	aP.add_argument("--prompt-file")
	aP.add_argument("--mode", default="t2v", choices=["t2v", "i2v", "music", "sfx"])
	aP.add_argument("--image")
	aP.add_argument("--max-tokens", type=int, default=320)
	aP.add_argument("--device", default="auto")
	aP.add_argument("--seed", type=int, default=-1)
	aP.add_argument("--out")
	aP.add_argument("--serve", action="store_true")
	aArgs = aP.parse_args()

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

	from transformers import AutoTokenizer, Qwen3VLForConditionalGeneration
	from transformers import LogitsProcessor, LogitsProcessorList
	try:
		from transformers import Qwen2VLImageProcessorPil as ImageProcessor
	except ImportError:
		from transformers import Qwen2VLImageProcessor as ImageProcessor
	from PIL import Image

	aSeed = aArgs.seed if aArgs.seed >= 0 else random.randint(0, 2**31 - 1)
	print("Seed: " + str(aSeed), file=sys.stderr, flush=True)
	torch.manual_seed(aSeed)

	out("STATUS LoadingLlm")
	aTok = AutoTokenizer.from_pretrained(aArgs.model_dir)
	aImageProc = ImageProcessor.from_pretrained(aArgs.model_dir)
	with open(os.path.join(aArgs.model_dir, "chat_template.json"), encoding="utf-8") as aF:
		aTemplate = json.load(aF)["chat_template"]
	aModel = Qwen3VLForConditionalGeneration.from_pretrained(aArgs.model_dir, dtype=torch.bfloat16)
	aModel.to(aDevice)
	aModel.eval()
	aMax = aArgs.max_tokens

	class Progress(LogitsProcessor):
		def __init__(self):
			self.n = 0

		def __call__(self, aIds, aScores):
			self.n += 1
			if self.n % 5 == 0:
				out("STEP " + str(min(self.n, aMax)) + " " + str(aMax))
			return aScores

	def generate(aSystem, aUserText, aImg):
		aUser = ([{"type": "image"}] if aImg is not None else []) + [{"type": "text", "text": aUserText}]
		aMessages = [
			{"role": "system", "content": [{"type": "text", "text": aSystem}]},
			{"role": "user", "content": aUser},
		]
		aText = aTok.apply_chat_template(aMessages, chat_template=aTemplate, tokenize=False, add_generation_prompt=True)
		aExtra = {}
		if aImg is not None:
			aPix = aImageProc(images=[aImg], return_tensors="pt")
			# One <|image_pad|> per merged patch, as Qwen3VLProcessor
			aN = int(aPix["image_grid_thw"][0].prod()) // (aImageProc.merge_size ** 2)
			aText = aText.replace("<|image_pad|>", "<|placeholder|>" * aN, 1).replace("<|placeholder|>", "<|image_pad|>")
			aExtra = {"pixel_values": aPix["pixel_values"].to(aDevice, dtype=torch.bfloat16), "image_grid_thw": aPix["image_grid_thw"].to(aDevice)}
		aIn = aTok([aText], return_tensors="pt").to(aDevice)
		with torch.no_grad():
			# Sampling parameters advised for Qwen3-VL-Instruct
			aIds = aModel.generate(**aIn, **aExtra, max_new_tokens=aMax, do_sample=True, temperature=0.7, top_p=0.8, top_k=20
				, logits_processor=LogitsProcessorList([Progress()]))
		if aCuda:
			out("PEAK llm " + str(torch.cuda.max_memory_reserved(aDevice)))
		aR = aTok.decode(aIds[0][aIn.input_ids.shape[1]:], skip_special_tokens=True).strip()
		# Safety: no reasoning block, one paragraph, no surrounding quotes
		aR = re.sub(r"<think>.*?</think>", "", aR, flags=re.S).strip()
		return re.sub(r"\s+", " ", aR).strip().strip('"').strip()

	# Layout of the first frame per image (path, date, size)
	aLayouts = {}

	def extend(aPrompt, aMode, aImagePath=None):
		aImg = None
		if aMode in ("i2v", "music", "sfx") and aImagePath:
			aImg = Image.open(aImagePath).convert("RGB")
			aImg.thumbnail((IMAGE_SIDE, IMAGE_SIDE))
		aUserText = aPrompt
		if aMode in ("music", "sfx"):
			aSystem = MUSIC if aMode == "music" else SFX
			aUserText = ("Request of the user: " + aPrompt) if aPrompt else "No request: only the image."
		elif aMode == "i2v":
			aSystem = I2V if aImg is not None else I2V_BLIND
			if aImg is not None:
				aKey = (os.path.abspath(aImagePath), os.path.getmtime(aImagePath), os.path.getsize(aImagePath))
				if aKey not in aLayouts:
					out("STATUS DescribingImage")
					aLayouts[aKey] = generate(LAYOUT, "Describe this photo.", aImg)
					print("First frame layout: " + aLayouts[aKey], file=sys.stderr, flush=True)
				aUserText = "Description of the first frame (exact):\n" + aLayouts[aKey] + "\n\nRequest of the user:\n" + aPrompt
		else:
			aSystem = T2V
		out("STATUS ExtendingPrompt")
		aR = generate(aSystem, aUserText, aImg)
		print("Prompt: " + aPrompt.replace("\n", " ") + "\nExtended prompt (" + aMode + (", image seen" if aImg is not None else "") + "): " + aR, file=sys.stderr, flush=True)
		return aR

	if not aArgs.serve:
		with open(aArgs.prompt_file, encoding="utf-8") as aF:
			aR = extend(aF.read().strip(), aArgs.mode, aArgs.image)
		with open(aArgs.out, "w", encoding="utf-8") as aF:
			aF.write(aR)
		out("DONE " + aArgs.out)
		return 0

	# Server: one JSON request per line on stdin {"mode": "t2v"|"i2v"|"music"|"sfx", "prompt": "...", "image": path or absent}, until stdin is closed
	out("STATUS LlmReady")
	for aLine in sys.stdin:
		aLine = aLine.strip()
		if not aLine:
			continue
		try:
			aReq = json.loads(aLine)
			aR = extend(aReq.get("prompt", "").strip(), aReq.get("mode", "t2v"), aReq.get("image"))
			out("RESULT " + json.dumps(aR))
		except Exception as e:
			traceback.print_exc()
			aOom = "out of memory" in str(e).lower() or type(e).__name__ == "OutOfMemoryError"
			out("FAIL " + ("OOM" if aOom else (type(e).__name__ + ": " + str(e)).replace("\n", " ")))
			if aCuda:
				torch.cuda.empty_cache()
		out("STATUS LlmReady")
	return 0


if __name__ == "__main__":
	try:
		sys.exit(main())
	except Exception as e:
		traceback.print_exc()
		aOom = "out of memory" in str(e).lower() or type(e).__name__ == "OutOfMemoryError"
		out("ERROR " + ("OOM" if aOom else (type(e).__name__ + ": " + str(e)).replace("\n", " ")))
		sys.exit(1)
