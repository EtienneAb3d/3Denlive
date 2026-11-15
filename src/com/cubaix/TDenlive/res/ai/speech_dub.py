# Voice translation (dubbing) of a video for 3Denlive (run by com.cubaix.TDenlive.ai.SpeechGenerator).
#
# The speech was transcribed and translated before (speech_transcribe.py, then the LLM of 3Denlive): each subtitle
# of the translation is spoken by Qwen3-TTS Base (Qwen/Qwen3-TTS-12Hz-1.7B-Base or 0.6B, Apache-2.0, 10 languages:
# zh en ja ko de fr ru pt es it), sentence by sentence (subtitles of a same sentence joined), with the voice of the
# original speech cloned from the longest stretch of speech of the video (one voice for all the sentences). Each
# line is placed at the time of its subtitle; too long for its place, it is sped up (at most MAX_RATE, pitch kept),
# then the following ones are delayed if needed. The voice alone (the original sound is mixed in the montage).
# Its own environment (qwen-tts needs transformers 4.57.3). CUDA, MPS or the CPU (--device cpu forces it).
#
# Protocol on stdout, one line per message:
#  STATUS <key>              step (key of the AI.* language entries)
#  DEVICE <cuda|mps|cpu>     device used
#  STEP <done> <total>       subtitles spoken
#  PEAK <stage> <bytes>      peak GPU memory reserved (CUDA)
#  DONE <path>               WAV written
#  ERROR <OOM|text>          failure
import argparse
import json
import math
import os
import re
import sys
import time

# Speed up of a line at most (beyond, it is delayed into the silence that follows)
MAX_RATE = 1.3
# Voice of the speaker (--voice ""): the speaker of each sentence is recognized by the embedding of its voice, taken
# in the middle of the sentence (its ends may overlap the previous / next speaker: MID_S at most on each side of its
# middle, EDGE_S kept off its ends; a short sentence (< MID_MIN_S) without SHORT_EDGE_S at each end). The sentences
# are grouped by speaker (average linkage, cosine >= SPEAKER_SIM; measured: narrator 0.98-0.99 between its sentences,
# 0.94-0.96 with the people interviewed); a sentence under SHORT_S (too little voice) joins the nearest speaker. Each
# speaker gets the mean embedding of its sentences (weighted by their duration): the same voice for all its
# sentences (a reference per sentence gave a different voice to each one), no reference text needed (x-vector: the
# timbre, less the accent than a reference cloned with its text)
MID_MIN_S = 4.0
MID_S = 3.0
EDGE_S = 0.8
SHORT_EDGE_S = 0.15
SHORT_S = 1.5
SPEAKER_SIM = 0.96
GAP_S = 0.05
# Steady voice: each line is generated up to TRIES times (another draw each time) and the draw kept is the closest
# to its speaker: voice embedding (cosine with that of the reference of the speaker, and with the mean of the lines of
# that speaker already kept, KEPT_WEIGHT of the cosine once there is one: the voice heard before) and pitch
# (semitones from the median pitch of the lines of that speaker already kept from the second one, else from the
# reference), score = cosine - PITCH_COST per semitone; a draw with both cosines >= VOICE_OK / KEPT_OK and at most
# PITCH_OK semitones away is kept at once. Measured: every draw is close to the reference (0.99) but two lines of the
# same speaker may differ (0.96: another voice heard, 2 semitones higher), so the reference alone does not hold it
TRIES = 4
VOICE_OK = 0.98
KEPT_OK = 0.985
KEPT_WEIGHT = 0.7
PITCH_OK = 1.0
PITCH_COST = 0.01
# A sentence between two sentences of the same other speaker (pauses under NEIGHBOR_GAP_S) takes their speaker when
# it is short (SHORT_S) or hardly closer to its own (SPEAKER_MARGIN): no switch of voice for a single sentence
NEIGHBOR_GAP_S = 1.5
SPEAKER_MARGIN = 0.01
# Codec frames of Qwen3-TTS-12Hz per second of sound, and seconds per character of a slow speech
TOKENS_PER_S = 12.5
CHAR_S = 0.09
# Native voice (--voice female|male): designed once per language by Qwen3-TTS VoiceDesign from a description, then
# cloned by the Base model for all the sentences (the "Voice Design then Clone" workflow of its authors): the same
# voice from the first sentence to the last, with the accent of the language, whatever the original speaker. The
# reference is kept in --ref-cache: the same voice from a video to the next.
VOICES = {
	"female": "A native %LANG% speaking woman in her thirties, warm, clear and natural voice, calm and steady pace, "
		"neutral standard %LANG% accent, professional documentary narrator, close microphone, no background noise.",
	"male": "A native %LANG% speaking man in his forties, warm, clear and natural voice, calm and steady pace, "
		"neutral standard %LANG% accent, professional documentary narrator, close microphone, no background noise.",
}
# Native voice for each speaker (--voice speakers): the speakers recognized as for the voice of the speaker (see
# SPEAKER_SIM), each one gets a native voice of its gender (median pitch of its voice under MALE_F0 Hz: a man) among
# PERSONAS (the first one of each gender is that of VOICES, given to the speaker of that gender who speaks most),
# then the one whose pitch offset (Hz, from that first persona) is the closest to the offset of the speaker from the
# first speaker of its gender, among those not given yet: the right gender, a lower / higher voice than the other
# speakers when it is, distinct voices, the pronunciation of a native speaker of the language. Ages stay close to
# those of VOICES: the pitch does not tell the age
MALE_F0 = 165.0
PERSONAS = {
	"male": [(VOICES["male"], 0.0),
		("A native %LANG% speaking man in his fifties, deeper, slightly husky and natural voice, calm and steady pace, "
			"neutral standard %LANG% accent, close microphone, no background noise.", -15.0),
		("A native %LANG% speaking man in his thirties, lighter, clear and natural voice, steady pace, "
			"neutral standard %LANG% accent, close microphone, no background noise.", 15.0),
		("A native %LANG% speaking man in his sixties, low, warm and gravelly voice, relaxed pace, "
			"neutral standard %LANG% accent, close microphone, no background noise.", -25.0)],
	"female": [(VOICES["female"], 0.0),
		("A native %LANG% speaking woman in her fifties, lower, warm and natural voice, calm and steady pace, "
			"neutral standard %LANG% accent, close microphone, no background noise.", -20.0),
		("A native %LANG% speaking woman in her twenties, lighter, bright and natural voice, steady pace, "
			"neutral standard %LANG% accent, close microphone, no background noise.", 20.0),
		("A native %LANG% speaking woman in her forties, soft, slightly husky and natural voice, relaxed pace, "
			"neutral standard %LANG% accent, close microphone, no background noise.", -10.0)],
}
# Text of the reference in each language (about 10 s, varied sounds, neutral tone)
REF_TEXTS = {
	"english": "Welcome to this short film. Over the next few minutes, we will travel through the mountains, meet the people who live there, and discover how their valley has changed over the years.",
	"french": "Bienvenue dans ce court film. Pendant les prochaines minutes, nous allons traverser les montagnes, rencontrer ceux qui y vivent, et découvrir comment leur vallée a changé au fil des années.",
	"german": "Willkommen zu diesem kurzen Film. In den nächsten Minuten reisen wir durch die Berge, treffen die Menschen, die dort leben, und entdecken, wie sich ihr Tal im Laufe der Jahre verändert hat.",
	"spanish": "Bienvenidos a esta breve película. En los próximos minutos recorreremos las montañas, conoceremos a las personas que viven allí y descubriremos cómo ha cambiado su valle con los años.",
	"italian": "Benvenuti in questo breve film. Nei prossimi minuti attraverseremo le montagne, incontreremo le persone che ci vivono e scopriremo come la loro valle è cambiata nel corso degli anni.",
	"portuguese": "Bem-vindos a este pequeno filme. Nos próximos minutos, vamos atravessar as montanhas, conhecer as pessoas que lá vivem e descobrir como o seu vale mudou ao longo dos anos.",
	"russian": "Добро пожаловать на этот короткий фильм. В ближайшие минуты мы пройдём через горы, познакомимся с людьми, которые там живут, и узнаем, как изменилась их долина за эти годы.",
	"chinese": "欢迎观看这部短片。在接下来的几分钟里，我们将穿越群山，认识生活在那里的人们，并了解他们的山谷多年来发生了怎样的变化。",
	"japanese": "この短い映画へようこそ。これから数分間、山々を旅し、そこに暮らす人々に出会い、彼らの谷が長い年月の中でどのように変わってきたのかを見ていきます。",
	"korean": "이 짧은 영화에 오신 것을 환영합니다. 앞으로 몇 분 동안 우리는 산을 넘고, 그곳에 사는 사람들을 만나며, 그들의 계곡이 세월에 따라 어떻게 변해 왔는지 알아볼 것입니다.",
}
# Loudness of every line (RMS), the same for all
LINE_RMS = 0.08


def out(*aArgs):
	print(*aArgs, flush=True)


def log(aText):
	print(aText, file=sys.stderr, flush=True)


def main(aArgv):
	aP = argparse.ArgumentParser()
	aP.add_argument("--model-dir", required=True)
	aP.add_argument("--device", default="auto")
	aP.add_argument("--audio", required=True)
	aP.add_argument("--cues", required=True)
	aP.add_argument("--language", required=True)
	aP.add_argument("--duration", type=float, default=0)
	aP.add_argument("--out", required=True)
	aP.add_argument("--voice", default="", choices=["", "female", "male", "speakers"])
	aP.add_argument("--design-dir")
	aP.add_argument("--ref-cache")
	aArgs = aP.parse_args(aArgv)

	out("STATUS Starting")
	import numpy as np
	import torch
	import soundfile
	import librosa

	if aArgs.device != "cpu" and torch.cuda.is_available():
		aDevice = torch.device("cuda", 0)
	elif aArgs.device != "cpu" and getattr(torch.backends, "mps", None) is not None and torch.backends.mps.is_available():
		aDevice = torch.device("mps")
	else:
		aDevice = torch.device("cpu")
	out("DEVICE " + aDevice.type)
	if aDevice.type == "cpu":
		torch.set_num_threads(max(1, os.cpu_count() or 1))

	import ai_serve

	def load():
		out("STATUS LoadingVoiceModel")
		from qwen_tts import Qwen3TTSModel
		aDtype = torch.float32 if aDevice.type == "cpu" else torch.bfloat16
		return Qwen3TTSModel.from_pretrained(aArgs.model_dir, dtype=aDtype, device_map=str(aDevice))

	aTts = ai_serve.cached(("tts", aArgs.model_dir, str(aDevice)), load)

	aSrc, aSr = soundfile.read(aArgs.audio, dtype="float32", always_2d=True)
	aSrc = aSrc.mean(axis=1)
	with open(aArgs.cues, encoding="utf-8") as aF:
		aCues = sentences([c for c in json.load(aF) if c.get("text", "").strip()])
	aDur = max(aArgs.duration, len(aSrc) / aSr, max([c["end"] for c in aCues], default=0) + 1)
	aLang = aArgs.language.lower()

	aRefs = None
	if aArgs.voice in ("female", "male"):
		aRef = nativeReferences(aArgs, aLang, [(aArgs.voice, 0)], aDevice, torch, np, soundfile)[(aArgs.voice, 0)]
		out("STATUS CloningVoice")
		aPrompt = aTts.create_voice_clone_prompt(ref_audio=(aRef[0], aRef[2]), ref_text=aRef[1])
		aSpeakers = [aPrompt] * len(aCues)
		log("Native voice: " + aArgs.voice + " " + aLang)
	elif aArgs.voice == "speakers":
		out("STATUS CloningVoice")
		aOf, aGroups, aEmb, aWeights = speakerGroups(aTts, aCues, aSrc, aSr, np)
		aPersonas = choosePersonas(aCues, aSrc, aSr, aOf, aGroups, np)
		aRefs = nativeReferences(aArgs, aLang, sorted(set(aPersonas)), aDevice, torch, np, soundfile)
		out("STATUS CloningVoice")
		aPrompts = {k: aTts.create_voice_clone_prompt(ref_audio=(r[0], r[2]), ref_text=r[1]) for k, r in aRefs.items()}
		aRefs = {k: r + (aPrompts[k],) for k, r in aRefs.items()}
		aSpeakers = [aPrompts[aPersonas[aOf[i]]] for i in range(len(aCues))]
	else:
		out("STATUS CloningVoice")
		aOf, aGroups, aEmb, aWeights = speakerGroups(aTts, aCues, aSrc, aSr, np)
		aSpeakers = speakerPrompts(aOf, aGroups, aEmb, aWeights, aCues, np)

	# Voice embedding (normalized) and pitch of the reference of each prompt, pitches and voice embeddings of the lines
	# kept for each one
	aRefVoice = {}
	for aP in aSpeakers:
		if id(aP) not in aRefVoice:
			aV = aP[0].ref_spk_embedding.float().cpu().numpy().ravel()
			aRefVoice[id(aP)] = [aV / max(float(np.linalg.norm(aV)), 1e-9), refPitch(aP, aRefs if aArgs.voice == "speakers" else None, aArgs, aLang, np), [], []]

	out("STATUS SpeakingTranslation")
	aN = len(aCues)
	out("STEP 0 " + str(aN))
	aOutSr = None
	aLines = []
	for i, c in enumerate(aCues):
		aT0 = time.time()
		aPrompt = aSpeakers[i]
		aText = " ".join(c["text"].split())
		# Expected duration: that of the original, or that of the text at a slow pace when longer. The model
		# sometimes misses its end token and goes on (8192 tokens = 11 min in its configuration): its tokens are
		# limited to about twice the expected duration, a line still far too long is generated again (another
		# draw), then cut with a fade out
		aExpect = max(c["end"] - c["start"], len(aText) * CHAR_S)
		aMaxTok = int(TOKENS_PER_S * (aExpect * 2 + 3))
		aW = None
		aBest = None
		aRef = aRefVoice[id(aPrompt)]
		aPitchRef = float(np.median(aRef[2])) if len(aRef[2]) >= 2 else aRef[1] if aRef[1] > 0 else (aRef[2][0] if aRef[2] else 0.0)
		aKept = None
		if aRef[3]:
			aKept = np.mean(aRef[3], axis=0)
			aKept = aKept / max(float(np.linalg.norm(aKept)), 1e-9)
		for aTry in range(TRIES):
			# The same draws for a line whatever the run: reproducible
			torch.manual_seed(1000 + 7919 * i + aTry)
			aWavs, aOutSr = aTts.generate_voice_clone(text=aText, language=aLang, voice_clone_prompt=aPrompt, max_new_tokens=aMaxTok)
			aTryW, _ = librosa.effects.trim(np.asarray(aWavs[0], dtype=np.float32), top_db=40)
			if len(aTryW) / aOutSr > aExpect * 1.6 + 1.5:
				log("Line " + str(i + 1) + ": " + str(round(len(aTryW) / aOutSr, 1)) + " s for " + str(round(aExpect, 1)) + " s expected, generated again")
				if aW is None or (aBest is None and len(aTryW) < len(aW)):
					aW = aTryW
				continue
			aV = aTts.create_voice_clone_prompt(ref_audio=(aTryW, aOutSr), x_vector_only_mode=True)[0].ref_spk_embedding.float().cpu().numpy().ravel()
			aV = aV / max(float(np.linalg.norm(aV)), 1e-9)
			aSim = float(aV @ aRef[0])
			aSimKept = float(aV @ aKept) if aKept is not None else aSim
			aF0 = medianPitch(aTryW, aOutSr, np)
			aSt = abs(12 * math.log2(aF0 / aPitchRef)) if aF0 > 0 and aPitchRef > 0 else 0.0
			aScore = (1 - KEPT_WEIGHT) * aSim + KEPT_WEIGHT * aSimKept - PITCH_COST * aSt if aKept is not None else aSim - PITCH_COST * aSt
			if aBest is None or aScore > aBest[0]:
				aBest = (aScore, aSim, aSt, aF0, aSimKept, aV)
				aW = aTryW
			if aSim >= VOICE_OK and aSimKept >= KEPT_OK and aSt <= PITCH_OK:
				break
		if aBest is not None:
			if aBest[3] > 0:
				aRef[2].append(aBest[3])
			aRef[3].append(aBest[5])
			log("Line " + str(i + 1) + ": voice " + str(round(aBest[1], 3)) + " (lines before " + str(round(aBest[4], 3)) + "), "
				+ str(round(aBest[2], 1)) + " semitones, " + str(aTry + 1) + " draw(s)")
		# The same loudness for all the lines
		aRms = float(np.sqrt(np.mean(aW ** 2))) if len(aW) else 0
		if aRms > 1e-4:
			aW = aW * (LINE_RMS / aRms)
		aMaxLen = int((aExpect * 1.6 + 1.5) * aOutSr)
		if len(aW) > aMaxLen:
			aFade = min(aMaxLen, int(0.3 * aOutSr))
			aW = aW[:aMaxLen].copy()
			aW[-aFade:] *= np.linspace(1.0, 0.0, aFade, dtype=np.float32)
		aLines.append(aW)
		log("Line " + str(i + 1) + "/" + str(aN) + " (" + str(round(len(aW) / aOutSr, 2)) + " s for "
			+ str(round(c["end"] - c["start"], 2)) + " s) in " + str(round(time.time() - aT0, 1)) + " s: " + aText)
		out("STEP " + str(i + 1) + " " + str(aN))
	if aDevice.type == "cuda":
		out("PEAK tts " + str(torch.cuda.max_memory_reserved(aDevice)))
	if aOutSr is None:
		aOutSr = 24000

	# Placement: at the time of its subtitle, sped up when it is longer than its place
	aMix = np.zeros(int(aDur * aOutSr) + aOutSr, dtype=np.float32)
	aCursor = 0.0
	for i, (c, aW) in enumerate(zip(aCues, aLines)):
		aStart = max(c["start"], aCursor)
		aNext = aCues[i + 1]["start"] if i + 1 < len(aCues) else aDur
		aRoom = max(0.2, aNext - GAP_S - aStart)
		aLen = len(aW) / aOutSr
		if aLen > aRoom:
			aRate = min(MAX_RATE, aLen / aRoom)
			aW = stretch(aW, aRate, aOutSr, np)
			aLen = len(aW) / aOutSr
		s = int(aStart * aOutSr)
		if s + len(aW) > len(aMix):
			aMix = np.concatenate([aMix, np.zeros(s + len(aW) - len(aMix), dtype=np.float32)])
		aMix[s:s + len(aW)] += aW
		aCursor = aStart + aLen + GAP_S
	aPeak = float(np.abs(aMix).max()) if len(aMix) else 0
	if aPeak > 0.97:
		aMix *= 0.97 / aPeak
	aMix = aMix[:int(max(aDur, aCursor) * aOutSr)]
	os.makedirs(os.path.dirname(os.path.abspath(aArgs.out)), exist_ok=True)
	soundfile.write(aArgs.out, aMix, aOutSr, subtype="PCM_16")
	log("Dubbed voice: " + str(round(len(aMix) / aOutSr, 1)) + " s -> " + aArgs.out)
	out("DONE " + aArgs.out)
	return 0


def voiceFile(aCache, aGender, aN, aLang):
	"""Cached reference of the persona aN of aGender (the first one: that of --voice female|male)"""
	return os.path.join(aCache, "voice_" + aGender + ("" if aN == 0 else "_" + str(aN + 1)) + "_" + aLang + ".wav")


def nativeReferences(aArgs, aLang, aKeys, aDevice, torch, np, soundfile):
	"""{(gender, persona): (samples, text, sample rate)}: the native voices of aLang, designed once by VoiceDesign
	then kept in --ref-cache (the design model is loaded only when one is missing, freed after)"""
	aText = REF_TEXTS.get(aLang, REF_TEXTS["english"])
	aRes = {}
	aDesign = None
	for aG, aN in aKeys:
		aFile = voiceFile(aArgs.ref_cache, aG, aN, aLang) if aArgs.ref_cache else None
		if aFile and os.path.isfile(aFile):
			aW, aSr = soundfile.read(aFile, dtype="float32")
			aRes[(aG, aN)] = (aW, aText, aSr)
			continue
		out("STATUS DesigningVoice")
		if aDesign is None:
			from qwen_tts import Qwen3TTSModel
			aDtype = torch.float32 if aDevice.type == "cpu" else torch.bfloat16
			aDesign = Qwen3TTSModel.from_pretrained(aArgs.design_dir, dtype=aDtype, device_map=str(aDevice))
		torch.manual_seed(20260 + 101 * aN + (0 if aG == "male" else 7))
		aInstruct = PERSONAS[aG][aN][0].replace("%LANG%", aLang.capitalize())
		aWavs, aSr = aDesign.generate_voice_design(text=aText, language=aLang, instruct=aInstruct, max_new_tokens=int(TOKENS_PER_S * 30))
		aW = np.asarray(aWavs[0], dtype=np.float32)
		if aFile:
			os.makedirs(aArgs.ref_cache, exist_ok=True)
			soundfile.write(aFile, aW, aSr, subtype="PCM_16")
		log("Native voice designed: " + str(round(len(aW) / aSr, 1)) + " s, " + aInstruct)
		aRes[(aG, aN)] = (aW, aText, aSr)
	if aDesign is not None:
		del aDesign
		import gc
		gc.collect()
		if aDevice.type == "cuda":
			torch.cuda.empty_cache()
		elif aDevice.type == "mps":
			torch.mps.empty_cache()
	return aRes


def medianPitch(aAudio, aSr, np):
	"""Median pitch (Hz) of the voiced frames of aAudio, 0 if none"""
	import librosa
	if len(aAudio) < aSr // 2:
		return 0.0
	aF0 = librosa.yin(aAudio, fmin=60, fmax=400, sr=aSr, frame_length=2048)
	aRms = librosa.feature.rms(y=aAudio, frame_length=2048, hop_length=512)[0]
	n = min(len(aF0), len(aRms))
	aF0, aRms = aF0[:n], aRms[:n]
	# Voiced: the loudest half of the frames (yin gives a value everywhere)
	aKeep = aRms >= np.median(aRms)
	return float(np.median(aF0[aKeep])) if aKeep.any() else 0.0


def refPitch(aPrompt, aRefs, aArgs, aLang, np):
	"""Median pitch of the reference audio of a prompt (native voices: ICL with a designed reference), 0 if unknown
	(x-vector of the speaker: no reference audio, the lines kept give it)"""
	if not aPrompt[0].icl_mode:
		return 0.0
	if aArgs.voice in ("female", "male"):
		aFile = voiceFile(aArgs.ref_cache, aArgs.voice, 0, aLang) if aArgs.ref_cache else None
		if aFile and os.path.isfile(aFile):
			import soundfile
			aW, aSr = soundfile.read(aFile, dtype="float32")
			return medianPitch(aW, aSr, np)
		return 0.0
	for aKey, aR in (aRefs or {}).items():
		if aR[3] is aPrompt:
			return medianPitch(aR[0], aR[2], np)
	return 0.0


def choosePersonas(aCues, aSrc, aSr, aOf, aGroups, np):
	"""(gender, persona) of each speaker of aGroups: gender by its median pitch, the persona closest in pitch among
	those of its gender not given yet (the speakers that speak most first)"""
	aStats = []
	for g in range(len(aGroups)):
		aMembers = [i for i in range(len(aCues)) if aOf[i] == g]
		aParts = []
		for i in aMembers:
			s, e = voiceSpan(aCues[i])
			aParts.append(aSrc[int(s * aSr):int(e * aSr)])
		aF0 = medianPitch(np.concatenate(aParts) if aParts else np.zeros(0, dtype=np.float32), aSr, np)
		aStats.append((sum(aCues[i]["end"] - aCues[i]["start"] for i in aMembers), aF0, g))
	aRes = [None] * len(aGroups)
	aUsed = set()
	aFirst = {}
	for aDur, aF0, g in sorted(aStats, reverse=True):
		aG = "male" if 0 < aF0 < MALE_F0 else "female" if aF0 >= MALE_F0 else "male"
		aFree = [n for n in range(len(PERSONAS[aG])) if (aG, n) not in aUsed] or list(range(len(PERSONAS[aG])))
		if aG not in aFirst:
			# The first speaker of a gender: its first persona (that of --voice female|male)
			aFirst[aG] = aF0
			aN = aFree[0]
		else:
			aOff = aF0 - aFirst[aG]
			aN = min(aFree, key=lambda n: abs(PERSONAS[aG][n][1] - aOff))
		aUsed.add((aG, aN))
		aRes[g] = (aG, aN)
		log("Speaker " + str(g + 1) + ": " + str(round(aDur, 1)) + " s, pitch " + str(round(aF0)) + " Hz -> " + aG + " voice " + str(aN + 1))
	return aRes


def stretch(aW, aRate, aSr, np):
	"""aW spoken aRate times faster, pitch and timbre kept: WSOLA (grains of 30 ms every 15 ms, Hann windows, each
	taken at its place on the time line of the source, moved by at most 10 ms to best continue the previous one).
	Measured: the phase vocoder of librosa (time_stretch) changes the timbre of a voice sped up 1.2 to 1.3 times so
	much that it sounds like another speaker"""
	if aRate <= 1.0 or len(aW) == 0:
		return aW
	aN = int(0.030 * aSr)
	aHop = aN // 2
	aTol = int(0.010 * aSr)
	aWin = np.hanning(aN).astype(np.float32)
	aOutLen = int(len(aW) / aRate)
	aX = np.concatenate([np.zeros(aTol, dtype=np.float32), aW.astype(np.float32), np.zeros(2 * aN + 2 * aTol + int(aHop * aRate) + 2, dtype=np.float32)])
	aOut = np.zeros(aOutLen + aN, dtype=np.float32)
	aNorm = np.zeros(aOutLen + aN, dtype=np.float32)
	aPrev = -1
	k = 0
	while k * aHop < aOutLen:
		# Its place in the source (with the padding), then the offset that best continues the previous grain
		aIdeal = int(k * aHop * aRate) + aTol
		if aPrev < 0:
			aPos = aIdeal
		else:
			aNext = aX[aPrev + aHop:aPrev + aHop + aN]
			aCand = aX[aIdeal - aTol:aIdeal + aTol + aN]
			aPos = aIdeal - aTol + int(np.argmax(np.correlate(aCand, aNext, mode="valid")))
		o = k * aHop
		aOut[o:o + aN] += aX[aPos:aPos + aN] * aWin
		aNorm[o:o + aN] += aWin
		aPrev = aPos
		k += 1
	aOut = aOut / np.maximum(aNorm, 1e-3)
	return aOut[:aOutLen]


def splitCues(aCues):
	"""Subtitles made elsewhere (a file of the user: rolling captions, cut anywhere) brought to the speech: the end
	of one that overlaps the next one at the start of that one (the next text is already spoken), its text cut at
	its inner sentence ends, the times shared in proportion of the characters (a sentence, and so a speaker, per
	piece)"""
	aRes = []
	for k, c in enumerate(aCues):
		s, e = c["start"], c["end"]
		if k + 1 < len(aCues) and s < aCues[k + 1]["start"] < e:
			e = aCues[k + 1]["start"]
		aText = " ".join(c["text"].split())
		aParts = [p for p in re.split(r"(?<=[.?!…。？！])\s+(?=\S)", aText) if p]
		aSrc = [p for p in re.split(r"(?<=[.?!…。？！])\s+(?=\S)", " ".join(c.get("src", "").split())) if p]
		if len(aParts) <= 1 or e - s < 1.0:
			aRes.append({"start": s, "end": e, "src": c.get("src", ""), "text": c["text"]})
			continue
		aTotal = float(sum(len(p) for p in aParts))
		t = s
		for j, p in enumerate(aParts):
			aEnd = e if j == len(aParts) - 1 else t + (e - s) * len(p) / aTotal
			# The original text cut the same way when it has as many sentences, else the translation decides
			aRes.append({"start": t, "end": aEnd, "src": aSrc[j] if len(aSrc) == len(aParts) else "", "text": p})
			t = aEnd
	return aRes


def sentences(aCues):
	"""Subtitles that follow each other within a sentence joined (spoken in one go: natural intonation)"""
	aRes = []
	for c in splitCues(aCues):
		p = aRes[-1] if aRes else None
		if p is not None and c["start"] - p["end"] < 0.5 and not (p["src"] or p["text"]).rstrip().endswith(tuple(".?!…。？！")) \
				and c["end"] - p["start"] <= 15:
			p["end"] = c["end"]
			p["src"] = p["src"].rstrip() + " " + c.get("src", "").strip()
			p["text"] = p["text"].rstrip() + " " + c["text"].strip()
		else:
			aRes.append({"start": c["start"], "end": c["end"], "src": c.get("src", "").strip(), "text": c["text"].strip()})
	return aRes


def voiceSpan(c):
	"""Part of the sentence c whose voice is measured (s): its middle, away from its ends"""
	aD = c["end"] - c["start"]
	if aD >= MID_MIN_S:
		aMid = c["start"] + aD / 2
		aHalf = min(MID_S, aD / 2 - EDGE_S)
		return aMid - aHalf, aMid + aHalf
	aEdge = min(SHORT_EDGE_S, aD / 4)
	return c["start"] + aEdge, c["end"] - aEdge


def speakerGroups(aTts, aCues, aSrc, aSr, np):
	"""Speakers of the sentences (see SPEAKER_SIM): (speaker index of each sentence, sentences of each speaker,
	voice embedding of each sentence, weight of each one)"""
	aEmb = []
	aWeights = []
	for c in aCues:
		s, e = voiceSpan(c)
		aW = aSrc[int(s * aSr):int(e * aSr)]
		if len(aW) < aSr // 4:
			aW = aSrc[int(c["start"] * aSr):max(int(c["end"] * aSr), int(c["start"] * aSr) + aSr // 2)]
		aEmb.append(aTts.create_voice_clone_prompt(ref_audio=(aW, aSr), x_vector_only_mode=True)[0].ref_spk_embedding)
		aWeights.append(max(0.1, e - s))
	aN = len(aEmb)
	if aN == 0:
		return [], [], [], []
	aV = np.stack([t.float().cpu().numpy().ravel() for t in aEmb])
	aU = aV / np.maximum(np.linalg.norm(aV, axis=1, keepdims=True), 1e-9)
	aSim = aU @ aU.T
	aLong = [i for i in range(aN) if aCues[i]["end"] - aCues[i]["start"] >= SHORT_S] or list(range(aN))
	# Average linkage clustering of the long enough sentences
	aGroups = [[i] for i in aLong]
	while len(aGroups) > 1:
		aBest = None
		for a in range(len(aGroups)):
			for b in range(a + 1, len(aGroups)):
				m = float(aSim[np.ix_(aGroups[a], aGroups[b])].mean())
				if aBest is None or m > aBest[0]:
					aBest = (m, a, b)
		if aBest[0] < SPEAKER_SIM:
			break
		aGroups[aBest[1]] += aGroups[aBest[2]]
		del aGroups[aBest[2]]
	aOf = {}
	for g, aG in enumerate(aGroups):
		for i in aG:
			aOf[i] = g
	for i in range(aN):
		if i not in aOf:
			aOf[i] = max(range(len(aGroups)), key=lambda g: float(aSim[i, aGroups[g]].mean()))
	# A single sentence of another speaker between two of the same one: theirs when short or ambiguous
	aMean = lambda i, g: float(aSim[i, aGroups[g]].mean())
	for i in range(1, aN - 1):
		p, n = aOf[i - 1], aOf[i + 1]
		if p != n or p == aOf[i]:
			continue
		if aCues[i]["start"] - aCues[i - 1]["end"] > NEIGHBOR_GAP_S or aCues[i + 1]["start"] - aCues[i]["end"] > NEIGHBOR_GAP_S:
			continue
		if aCues[i]["end"] - aCues[i]["start"] < SHORT_S or aMean(i, aOf[i]) - aMean(i, p) < SPEAKER_MARGIN:
			log("Sentence " + str(i + 1) + ": speaker " + str(aOf[i] + 1) + " -> " + str(p + 1) + " (as its neighbors)")
			aOf[i] = p
	# Speakers left without sentence
	aUsedG = sorted(set(aOf.values()))
	aGroups = [[i for i in aGroups[g] if aOf[i] == g] or [i for i in range(aN) if aOf[i] == g] for g in aUsedG]
	aNew = {g: k for k, g in enumerate(aUsedG)}
	return [aNew[aOf[i]] for i in range(aN)], aGroups, aEmb, aWeights


def speakerPrompts(aOf, aGroups, aEmb, aWeights, aCues, np):
	"""Voice clone prompt of each sentence: the mean voice embedding of its speaker (x-vector, no reference text)"""
	from qwen_tts.inference.qwen3_tts_model import VoiceClonePromptItem
	aN = len(aEmb)
	aV = np.stack([t.float().cpu().numpy().ravel() for t in aEmb])
	aPrompts = []
	for g, aG in enumerate(aGroups):
		aMembers = [i for i in range(aN) if aOf[i] == g]
		aW = np.array([aWeights[i] for i in aMembers], dtype=np.float32)
		aMean = (aV[aMembers] * aW[:, None]).sum(axis=0) / aW.sum()
		aT = aEmb[aMembers[0]]
		aItem = VoiceClonePromptItem(ref_code=None, ref_spk_embedding=__import__("torch").from_numpy(aMean).reshape(aT.shape).to(dtype=aT.dtype, device=aT.device)
			, x_vector_only_mode=True, icl_mode=False, ref_text=None)
		aPrompts.append([aItem])
		log("Speaker " + str(g + 1) + ": " + str(len(aMembers)) + " sentences, " + str(round(sum(aCues[i]["end"] - aCues[i]["start"] for i in aMembers), 1)) + " s")
	return [aPrompts[aOf[i]] for i in range(aN)]


if __name__ == "__main__":
	# One dubbing, or resident (--serve: the model stays loaded between the requests)
	import ai_serve
	ai_serve.run(main)
