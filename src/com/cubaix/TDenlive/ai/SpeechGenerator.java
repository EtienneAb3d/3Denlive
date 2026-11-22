package com.cubaix.TDenlive.ai;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.Locale;
import java.util.Vector;

import com.cubaix.TDenlive.depth.DepthEstimator;
import com.cubaix.TDenlive.depth.DepthModel;
import com.cubaix.TDenlive.ffmpeg.Ffmpeg;
import com.cubaix.TDenlive.utils.SubtitleFile;

/**
 * Speech of a video (or of a sound file): subtitles in one or several languages, or voice translation (dubbing).
 * A cascade of open models, each run by a script of res/ai/ (see PythonAI):
 * - speech recognition (res/ai/speech_transcribe.py, shared environment): Qwen3-ASR-1.7B with the timing of its
 * words by Qwen3-ForcedAligner-0.6B (Apache-2.0, 30 languages, language identified; advised), or Parakeet TDT
 * 0.6B v3 (NVIDIA, CC-BY-4.0, 25 European languages, much faster). Neither is Whisper: no text invented on the
 * silences or the music. The words are grouped into subtitles (SRT) of at most 2 lines of 42 characters;
 * - translation of the subtitles: the LLM of the application (PromptExtender, Qwen3-VL-4B-Instruct, mode
 * "translate" of prompt_extend.py), sentence by sentence;
 * - voice (res/ai/speech_dub.py, its own environment TTS_ENV: qwen-tts needs transformers 4.57.3): Qwen3-TTS
 * Base 1.7B or 0.6B (Apache-2.0, 10 languages) speaks each translated sentence with the voice of the original
 * speech (cloned from its own audio), at the time of the original; the result is a WAV of the length of the video.
 * The models go into ~/3Denlive/Models/<dir>/ at their first use; the audio is extracted by ffmpeg into the
 * WorkCache.
 */
public class SpeechGenerator extends PythonAI {
	static final long GB = 1024L*1024*1024;

	/**
	 * Files of a Hugging Face repository downloaded into Models/<dir>/: {path, size (0: small, any)}
	 */
	static class Part {
		final String repo;
		final String dir;
		final Object[][] files;

		Part(String aRepo,String aDir,Object[][] aFiles) {
			repo = aRepo;
			dir = aDir;
			files = aFiles;
		}

		File getDir(String aTdeDir) {
			return new File(DepthModel.getModelsDir(aTdeDir), dir);
		}

		long size() {
			long aS = 0;
			for(Object[] aF : files) {
				aS += (Long)aF[1];
			}
			return aS;
		}
	}

	/**
	 * A model: its parts, the memory it needs, its engine (argument of the script)
	 */
	public static class Model {
		public final String id;
		//Name shown and parameters (for the user)
		public final String name;
		public final String params;
		final String engine;
		final Part[] parts;
		//Memory needed on the GPU (estimate: bf16 weights + activations)
		public final long gpuMemory;
		//Languages it recognizes (English names, as the scripts and the LLM name them)
		public final String[] languages;

		Model(String aId,String aName,String aParams,String aEngine,long aGpu,String[] aLanguages,Part... aParts) {
			id = aId;
			name = aName;
			params = aParams;
			engine = aEngine;
			gpuMemory = aGpu;
			languages = aLanguages;
			parts = aParts;
		}

		public long downloadSize() {
			long aS = 0;
			for(Part aP : parts) {
				aS += aP.size();
			}
			return aS;
		}

		public long bytesToDownload(String aTdeDir) {
			long aB = 0;
			for(Part aP : parts) {
				aB += PythonAI.bytesToDownload(aP.getDir(aTdeDir), aP.files);
			}
			return aB;
		}

		public boolean isDownloaded(String aTdeDir) {
			return bytesToDownload(aTdeDir) == 0;
		}

		public boolean recognizes(String aLanguage) {
			for(String aL : languages) {
				if(aL.equals(aLanguage)) {
					return true;
				}
			}
			return false;
		}
	}

	//Languages of Qwen3-ASR (the names its processor knows), in the order of their codes
	public static final String[][] QWEN3_LANGUAGES = {
		{"ar", "Arabic"}, {"yue", "Cantonese"}, {"zh", "Chinese"}, {"cs", "Czech"}, {"da", "Danish"}, {"nl", "Dutch"},
		{"en", "English"}, {"fil", "Filipino"}, {"fi", "Finnish"}, {"fr", "French"}, {"de", "German"}, {"el", "Greek"},
		{"hi", "Hindi"}, {"hu", "Hungarian"}, {"id", "Indonesian"}, {"it", "Italian"}, {"ja", "Japanese"},
		{"ko", "Korean"}, {"mk", "Macedonian"}, {"ms", "Malay"}, {"fa", "Persian"}, {"pl", "Polish"},
		{"pt", "Portuguese"}, {"ro", "Romanian"}, {"ru", "Russian"}, {"es", "Spanish"}, {"sv", "Swedish"},
		{"th", "Thai"}, {"tr", "Turkish"}, {"vi", "Vietnamese"},
	};
	//Languages of Parakeet TDT 0.6B v3
	static final String[] PARAKEET_LANGUAGES = {"Bulgarian", "Croatian", "Czech", "Danish", "Dutch", "English"
			, "Estonian", "Finnish", "French", "German", "Greek", "Hungarian", "Italian", "Latvian", "Lithuanian"
			, "Maltese", "Polish", "Portuguese", "Romanian", "Slovak", "Slovenian", "Spanish", "Swedish", "Russian"
			, "Ukrainian"};
	//Languages spoken by Qwen3-TTS
	public static final String[] TTS_LANGUAGES = {"Chinese", "English", "Japanese", "Korean", "German", "French"
			, "Russian", "Portuguese", "Spanish", "Italian"};
	//Languages offered for the subtitles (translated by the LLM): those of Qwen3-ASR and a few more
	public static final String[][] SUBTITLE_LANGUAGES;
	static {
		Vector<String[]> aL = new Vector<String[]>();
		for(String[] aQ : QWEN3_LANGUAGES) {
			aL.add(aQ);
		}
		for(String[] aX : new String[][] {{"bg", "Bulgarian"}, {"ca", "Catalan"}, {"hr", "Croatian"}, {"he", "Hebrew"}
				, {"no", "Norwegian"}, {"sk", "Slovak"}, {"sl", "Slovenian"}, {"uk", "Ukrainian"}}) {
			aL.add(aX);
		}
		SUBTITLE_LANGUAGES = aL.toArray(new String[0][]);
	}

	static String[] qwen3Names() {
		String[] aN = new String[QWEN3_LANGUAGES.length];
		for(int i = 0;i < aN.length;i++) {
			aN[i] = QWEN3_LANGUAGES[i][1];
		}
		return aN;
	}

	public static final Model QWEN3_ASR = new Model("Qwen3ASR", "Qwen3-ASR 1.7B + ForcedAligner 0.6B", "2.3 G", "qwen3", 7*GB, qwen3Names()
		, new Part("Qwen/Qwen3-ASR-1.7B-hf", "Qwen3-ASR-1.7B", new Object[][] {
			{"chat_template.jinja", 0L}, {"config.json", 0L}, {"generation_config.json", 0L}, {"processor_config.json", 0L}
			, {"tokenizer.json", 11429653L}, {"tokenizer_config.json", 0L}, {"model.safetensors", 4076193080L}})
		, new Part("Qwen/Qwen3-ForcedAligner-0.6B-hf", "Qwen3-ForcedAligner-0.6B", new Object[][] {
			{"chat_template.jinja", 0L}, {"config.json", 0L}, {"processor_config.json", 0L}
			, {"tokenizer.json", 11429842L}, {"tokenizer_config.json", 0L}, {"model.safetensors", 1835545960L}}));
	public static final Model PARAKEET = new Model("Parakeet", "Parakeet TDT 0.6B v3", "0.6 G", "parakeet", 3*GB, PARAKEET_LANGUAGES
		, new Part("nvidia/parakeet-tdt-0.6b-v3", "parakeet-tdt-0.6b-v3", new Object[][] {
			{"config.json", 0L}, {"generation_config.json", 0L}, {"processor_config.json", 0L}
			, {"tokenizer.json", 1159960L}, {"tokenizer_config.json", 0L}, {"model.safetensors", 2508311120L}}));
	public static final Model[] ASR_MODELS = {QWEN3_ASR, PARAKEET};

	static Part ttsPart(String aSize,long aWeights) {
		String aName = "Qwen3-TTS-12Hz-"+aSize+"-Base";
		return new Part("Qwen/"+aName, aName, new Object[][] {
			{"config.json", 0L}, {"generation_config.json", 0L}, {"merges.txt", 1671839L}, {"preprocessor_config.json", 0L}
			, {"tokenizer_config.json", 0L}, {"vocab.json", 2776833L}, {"speech_tokenizer/config.json", 0L}
			, {"speech_tokenizer/configuration.json", 0L}, {"speech_tokenizer/preprocessor_config.json", 0L}
			, {"speech_tokenizer/model.safetensors", 682293092L}, {"model.safetensors", aWeights}});
	}
	public static final Model TTS_17 = new Model("TTS17", "Qwen3-TTS 1.7B Base", "1.7 G", "qwen3-tts", 6*GB, TTS_LANGUAGES, ttsPart("1.7B", 3857413744L));
	public static final Model TTS_06 = new Model("TTS06", "Qwen3-TTS 0.6B Base", "0.6 G", "qwen3-tts", 4*GB, TTS_LANGUAGES, ttsPart("0.6B", 1829344272L));
	//Direct speech to speech translation with voice transfer: Hibiki-Zero (Kyutai, 2026, CC BY-NC-SA 4.0), French,
	//Spanish, Portuguese, German -> English only; no transcription, translation or text to speech
	public static final String[] HIBIKI_SOURCES = {"French", "Spanish", "Portuguese", "German"};
	public static final Model HIBIKI = new Model("Hibiki", "Hibiki-Zero 3B", "3 G", "hibiki", 10*GB, HIBIKI_SOURCES
		, new Part("kyutai/hibiki-zero-3b-pytorch-bf16", "hibiki-zero-3b", new Object[][] {
			{"config.json", 0L}, {"tokenizer_spm_48k_multi6_2.model", 857314L}
			, {"mimi-pytorch-e351c8d8@125.safetensors", 384644900L}, {"hibiki-pytorch-77f82164@110.safetensors", 6263420344L}}));
	public static final Model[] TTS_MODELS = {TTS_17, TTS_06, HIBIKI};
	//Voice models of the cascade (any language of TTS_LANGUAGES), the largest first
	public static final Model[] QWEN_TTS_MODELS = {TTS_17, TTS_06};

	/**
	 * Voice model advised for this computer: the largest Qwen3-TTS that fits in the available GPU memory
	 * (PythonAI.availableGpuMemory(): the model kept loaded counts as free), the smallest on the CPU
	 */
	public static Model defaultTts() {
		if(!hasGpu()) {
			return TTS_06;
		}
		long aMem = availableGpuMemory();
		if(aMem < 0) {
			return TTS_17;
		}
		for(Model aM : QWEN_TTS_MODELS) {
			if(aM.gpuMemory <= aMem) {
				return aM;
			}
		}
		return TTS_06;
	}
	//Native voices of the cascade (VOICE_FEMALE / VOICE_MALE): designed once per language by Qwen3-TTS VoiceDesign
	//(1.7B only) from a description, kept in Models/Qwen3-TTS-voices/, then cloned for all the sentences
	public static final String VOICE_FEMALE = "female";
	public static final String VOICE_MALE = "male";
	public static final String VOICE_ORIGINAL = "";
	//A native voice for each speaker (recognized by its voice, man / woman by its pitch): among 4 per gender
	//(voice_<gender>[_<n>]_<language>.wav, n = 2..4), designed when first needed
	public static final String VOICE_SPEAKERS = "speakers";
	static final int PERSONAS = 4;
	static final Part DESIGN = new Part("Qwen/Qwen3-TTS-12Hz-1.7B-VoiceDesign", "Qwen3-TTS-12Hz-1.7B-VoiceDesign", new Object[][] {
		{"config.json", 0L}, {"generation_config.json", 0L}, {"merges.txt", 1671839L}, {"preprocessor_config.json", 0L}
		, {"tokenizer_config.json", 0L}, {"vocab.json", 2776833L}, {"speech_tokenizer/config.json", 0L}
		, {"speech_tokenizer/configuration.json", 0L}, {"speech_tokenizer/preprocessor_config.json", 0L}
		, {"speech_tokenizer/model.safetensors", 682293092L}, {"model.safetensors", 3833402552L}});

	/**
	 * Bytes to download for a native voice of aLanguage (0: its reference is already designed, or the model is
	 * there)
	 */
	public static long designBytesToDownload(String aTdeDir,String aVoice,String aLanguage) {
		if(aVoice == null || aVoice.isEmpty()) {
			return 0;
		}
		if(VOICE_SPEAKERS.equals(aVoice)) {
			boolean aAll = true;
			for(String aG : new String[] {VOICE_MALE, VOICE_FEMALE}) {
				for(int n = 1;n <= PERSONAS;n++) {
					aAll &= referenceFile(aTdeDir, n == 1 ? aG : aG+"_"+n, aLanguage).isFile();
				}
			}
			if(aAll) {
				return 0;
			}
		}
		else if(referenceFile(aTdeDir, aVoice, aLanguage).isFile()) {
			return 0;
		}
		return PythonAI.bytesToDownload(DESIGN.getDir(aTdeDir), DESIGN.files);
	}

	static File voicesDir(String aTdeDir) {
		return new File(DepthModel.getModelsDir(aTdeDir), "Qwen3-TTS-voices");
	}

	//As speech_dub.py names it
	static File referenceFile(String aTdeDir,String aVoice,String aLanguage) {
		return new File(voicesDir(aTdeDir), "voice_"+aVoice+"_"+aLanguage.toLowerCase()+".wav");
	}
	//moshi 0.2.13 (the inference package of Kyutai, torch < 2.10); not the hibiki-zero package (its server needs
	//gradio and jupyter)
	public static final Env HIBIKI_ENV = new Env("hibiki-env", "1", new String[] {"moshi==0.2.13"}, new String[0]);

	//qwen-tts 0.1.1 needs transformers 4.57.3 and accelerate 1.12.0 (pinned by its package)
	public static final Env TTS_ENV = new Env("tts-env", "1", new String[] {"qwen-tts==0.1.1", "torch", "torchaudio"}, new String[0]);
	//Added to the shared environment for Parakeet (its feature extractor needs librosa)
	static final String[] SHARED_EXTRA = {"librosa"};

	public static Model byId(Model[] aModels,String aId) {
		for(Model aM : aModels) {
			if(aM.id.equalsIgnoreCase(aId)) {
				return aM;
			}
		}
		return null;
	}

	public static boolean isPlatformSupported() {
		return hasPython();
	}

	/**
	 * Name of a language (English name) in the language of the interface (aLng: EN, FR...)
	 */
	public static String displayName(String aLanguage,String aLng) {
		String aCode = code(aLanguage);
		if(aCode == null) {
			return aLanguage;
		}
		Locale aUi = Locale.forLanguageTag(aLng == null ? "en" : aLng.toLowerCase());
		String aN = Locale.forLanguageTag(aCode).getDisplayLanguage(aUi);
		if(aN == null || aN.isEmpty() || aN.equalsIgnoreCase(aCode)) {
			return aLanguage;
		}
		return aN.substring(0, 1).toUpperCase(aUi)+aN.substring(1);
	}

	/**
	 * ISO code of a language (English name), null if unknown
	 */
	public static String code(String aLanguage) {
		for(String[] aL : SUBTITLE_LANGUAGES) {
			if(aL[1].equals(aLanguage)) {
				return aL[0];
			}
		}
		for(String aC : Locale.getISOLanguages()) {
			if(aLanguage.equals(Locale.forLanguageTag(aC).getDisplayLanguage(Locale.ENGLISH))) {
				return aC;
			}
		}
		return null;
	}

	/**
	 * Device: null for the GPU, else the reason of the CPU (DepthEstimator.CPU_REASON_*)
	 */
	public static String cpuReason(Model aModel) {
		if("auto".equals(loadedDevice(tag(aModel))) && hasGpu()) {
			return null;
		}
		if(!hasGpu()) {
			return DepthEstimator.CPU_REASON_NO_GPU;
		}
		if(!isMac()) {
			long aAvail = availableGpuMemory();
			if(aAvail >= 0 && aAvail < aModel.gpuMemory) {
				return DepthEstimator.CPU_REASON_GPU_MEMORY;
			}
		}
		return null;
	}

	public static String tag(Model aModel) {
		return "speech "+aModel.id;
	}

	/**
	 * Python environments still to install for these models (bytes)
	 */
	public static long envBytesToInstall(String aTdeDir,Model aTts) {
		long aB = PythonAI.envBytesToInstall(aTdeDir, SHARED);
		if(aTts == HIBIKI) {
			return PythonAI.envBytesToInstall(aTdeDir, HIBIKI_ENV);
		}
		if(aTts != null) {
			aB += PythonAI.envBytesToInstall(aTdeDir, TTS_ENV);
		}
		return aB;
	}

	/**
	 * What was produced: the SRT files with their language (English name; the spoken one first when asked), the
	 * dubbed voice (null for subtitles only), the language recognized ("" if unknown)
	 */
	public static class Result {
		public final Vector<File> files = new Vector<File>();
		public final Vector<String> languages = new Vector<String>();
		public File voice = null;
		public String spoken = "";
	}

	//Environment of the script being run (installEnv(), pythonCommand())
	Env env = SHARED;
	//Language recognized by the last transcription
	volatile String recognized = "";
	//Step of the STEP messages (the last STATUS)
	volatile String stepKey = "AI.Transcribing";
	//LLM of the translation while it runs (closed by a cancel)
	volatile PromptExtender extender = null;
	File workDir;

	//Audio stream of the source used (several: languages...), 0: the first
	public int audioTrack = 0;

	public SpeechGenerator(String aTdeDir,String aWorkDir,Progress aProgress) {
		super(aTdeDir, aProgress);
		workDir = new File(aWorkDir);
	}

	@Override
	Env env() {
		return env;
	}

	@Override
	String stepKey() {
		return stepKey;
	}

	@Override
	void status(String aKey,String aDetail,long aDone,long aTotal) {
		if(aTotal <= 0 && !aKey.startsWith("AI.Downloading") && !aKey.startsWith("AI.Installing")) {
			stepKey = aKey;
		}
		super.status(aKey, aDetail, aDone, aTotal);
	}

	@Override
	boolean handleLine(String aL) {
		if(aL.startsWith("LANG ")) {
			recognized = aL.substring(5).trim();
			System.out.println("AI speech: language recognized: "+recognized);
			return true;
		}
		if(aL.startsWith("DEVICE ")) {
			System.out.println("AI speech device: "+aL.substring(7).trim());
			return true;
		}
		return false;
	}

	@Override
	public void cancel() {
		super.cancel();
		PromptExtender aPE = extender;
		if(aPE != null) {
			aPE.close();
		}
	}

	@Override
	public boolean isRunning() {
		PromptExtender aPE = extender;
		return super.isRunning() || (aPE != null && aPE.isRunning());
	}

	/**
	 * Subtitles of the speech of aSource in each language of aTargets (English names; "" or the spoken language:
	 * the transcription itself, else translated), files <aBase> - <CODE>.srt of aOutDir
	 * @param aOwn subtitles of the speech given by the user (.srt, .vtt): used instead of the recognition, else null
	 * @param aSpoken the language spoken (English name; with aOwn: the language of the file), "" to recognize it
	 * @return null if canceled
	 */
	public Result subtitles(File aSource,File aOwn,Model aAsr,String aSpoken,Vector<String> aTargets,File aOutDir,String aBase) throws Exception {
		Result aR = new Result();
		File aRaw = new File(workDir, "AI_Speech_"+System.nanoTime()+".raw");
		try {
			//Own subtitles: the sound is not needed
			if(aOwn == null && !extract(aSource, aRaw, null)) {
				return null;
			}
			File aOrig = aOwn != null ? ownSubtitles(aOwn) : transcribe(aAsr, aRaw, aSpoken, aOutDir, aBase);
			if(aOrig == null) {
				return null;
			}
			aR.spoken = aSpoken.isEmpty() ? recognized : aSpoken;
			for(String aT : aTargets) {
				if(aT.isEmpty() || aT.equals(aR.spoken)) {
					File aF = target(aOutDir, aBase, aR.spoken.isEmpty() ? "" : aR.spoken, ".srt");
					Files.copy(aOrig.toPath(), aF.toPath(), StandardCopyOption.REPLACE_EXISTING);
					aR.files.add(0, aF);
					aR.languages.add(0, aR.spoken);
				}
			}
			for(String aT : aTargets) {
				if(aT.isEmpty() || aT.equals(aR.spoken)) {
					continue;
				}
				File aF = translate(aOrig, aR.spoken, aT, aOutDir, aBase);
				if(aF == null) {
					deleteAll(aR);
					return null;
				}
				aR.files.add(aF);
				aR.languages.add(aT);
			}
			aOrig.delete();
			return aR;
		}
		catch(Exception e) {
			deleteAll(aR);
			throw e;
		}
		finally {
			aRaw.delete();
		}
	}

	/**
	 * Voice translation of aSource into aTarget (English name, one of TTS_LANGUAGES): the WAV <aBase> -
	 * <CODE>.wav of the length of the source, with the subtitles of the original speech and of the translation
	 * @param aOwn subtitles of the original speech given by the user (.srt, .vtt): used instead of the recognition
	 * (not with HIBIKI), else null
	 * @return null if canceled
	 */
	public Result dub(File aSource,File aOwn,Model aAsr,String aSpoken,String aTarget,Model aTts,String aVoice,File aOutDir,String aBase) throws Exception {
		if(aTts == HIBIKI && aOwn == null) {
			return dubDirect(aSource, aOutDir, aBase);
		}
		Result aR = new Result();
		File aRaw = new File(workDir, "AI_Speech_"+System.nanoTime()+".raw");
		File aWav = new File(workDir, "AI_Speech_"+System.nanoTime()+".wav");
		File aCues = new File(workDir, "AI_Speech_"+System.nanoTime()+".json");
		try {
			if(!extract(aSource, aRaw, aWav)) {
				return null;
			}
			File aOrig = aOwn != null ? ownSubtitles(aOwn) : transcribe(aAsr, aRaw, aSpoken, aOutDir, aBase);
			if(aOrig == null) {
				return null;
			}
			aR.spoken = aSpoken.isEmpty() ? recognized : aSpoken;
			File aSrt = target(aOutDir, aBase, aR.spoken, ".srt");
			Files.move(aOrig.toPath(), aSrt.toPath(), StandardCopyOption.REPLACE_EXISTING);
			aR.files.add(aSrt);
			aR.languages.add(aR.spoken);
			File aTr = aSrt;
			if(!aTarget.equals(aR.spoken)) {
				aTr = translate(aSrt, aR.spoken, aTarget, aOutDir, aBase);
				if(aTr == null) {
					deleteAll(aR);
					return null;
				}
				aR.files.add(aTr);
				aR.languages.add(aTarget);
			}
			//Original and translated text of each subtitle, for the voice
			Vector<SubtitleFile.Cue> aO = SubtitleFile.read(aSrt.getPath());
			Vector<SubtitleFile.Cue> aT = SubtitleFile.read(aTr.getPath());
			StringBuilder aJ = new StringBuilder("[");
			for(int i = 0;i < aO.size() && i < aT.size();i++) {
				aJ.append(i > 0 ? ",\n" : "").append("{\"start\": ").append(aO.elementAt(i).startMS/1000.0)
					.append(", \"end\": ").append(aO.elementAt(i).endMS/1000.0)
					.append(", \"src\": \"").append(PromptExtender.quote(aO.elementAt(i).text.replace('\n', ' ')))
					.append("\", \"text\": \"").append(PromptExtender.quote(aT.elementAt(i).text.replace('\n', ' '))).append("\"}");
			}
			aJ.append("]");
			Files.write(aCues.toPath(), aJ.toString().getBytes(StandardCharsets.UTF_8));
			File aOut = target(aOutDir, aBase, aTarget, ".wav");
			if(!speak(aTts, aVoice, aWav, aCues, aTarget, aRaw.length()/2/16000.0, aOut)) {
				aOut.delete();
				deleteAll(aR);
				return null;
			}
			aR.voice = aOut;
			return aR;
		}
		catch(Exception e) {
			deleteAll(aR);
			throw e;
		}
		finally {
			aRaw.delete();
			aWav.delete();
			aCues.delete();
		}
	}

	/**
	 * Direct voice translation into English by Hibiki-Zero: the WAV <aBase> - EN.wav of the length of the source,
	 * and the English subtitles of what it says (its text tokens, timed: a by-product, the voice does not come from
	 * them)
	 * @return null if canceled
	 */
	Result dubDirect(File aSource,File aOutDir,String aBase) throws Exception {
		Result aR = new Result();
		File aRaw = new File(workDir, "AI_Speech_"+System.nanoTime()+".raw");
		File aWav = new File(workDir, "AI_Speech_"+System.nanoTime()+".wav");
		try {
			if(!extract(aSource, aRaw, aWav)) {
				return null;
			}
			env = HIBIKI_ENV;
			if(!installEnv() || canceled()) {
				return null;
			}
			if(!download(HIBIKI, "AI.DownloadingVoiceModel")) {
				return null;
			}
			if(!isLoaded(tag(HIBIKI))) {
				beforeLoad(tag(HIBIKI));
			}
			File aDir = HIBIKI.parts[0].getDir(tdeDir);
			File aScript = copyScript("hibiki_translate.py", aDir);
			//Its grouping of the words into subtitles
			copyScript("speech_transcribe.py", aDir);
			Vector<String> aCmd = pythonCommand(aScript);
			aCmd.add("--model-dir"); aCmd.add(aDir.getAbsolutePath());
			aCmd.add("--device"); aCmd.add(cpuReason(HIBIKI) == null ? "auto" : "cpu");
			aOutDir.mkdirs();
			File aOut = target(aOutDir, aBase, "English", ".wav");
			File aSrt = target(aOutDir, aBase, "English", ".srt");
			Vector<String> aReq = new Vector<String>();
			aReq.add("--audio"); aReq.add(aWav.getAbsolutePath());
			aReq.add("--srt"); aReq.add(aSrt.getAbsolutePath());
			aReq.add("--out"); aReq.add(aOut.getAbsolutePath());
			String aDone = runResident(tag(HIBIKI), HIBIKI.gpuMemory, aCmd, aReq);
			if(aDone == null || canceled()) {
				aOut.delete();
				aSrt.delete();
				return null;
			}
			if(!aOut.isFile()) {
				throw new Exception("No sound produced (see log/3De.log)");
			}
			if(aSrt.isFile() && !SubtitleFile.read(aSrt.getPath()).isEmpty()) {
				aR.files.add(aSrt);
				aR.languages.add("English");
			}
			else {
				aSrt.delete();
			}
			aR.voice = aOut;
			return aR;
		}
		finally {
			env = SHARED;
			aRaw.delete();
			aWav.delete();
		}
	}

	static void deleteAll(Result aR) {
		for(File aF : aR.files) {
			aF.delete();
		}
		if(aR.voice != null) {
			aR.voice.delete();
		}
	}

	/**
	 * <aBase> - <CODE><aExt> in aDir (<aBase><aExt> without language), _2, _3... when taken
	 */
	static File target(File aDir,String aBase,String aLanguage,String aExt) {
		String aCode = code(aLanguage);
		String aB = aBase+(aLanguage.isEmpty() ? "" : " - "+(aCode != null ? aCode.toUpperCase() : aLanguage));
		File aF = new File(aDir, aB+aExt);
		for(int n = 2;aF.exists();n++) {
			aF = new File(aDir, aB+"_"+n+aExt);
		}
		return aF;
	}

	/**
	 * The audio stream audioTrack (0: the first) of aSource as 16 kHz mono s16le (aRaw), and as a 24 kHz mono WAV (aWav, may be null)
	 * @return false if canceled
	 */
	boolean extract(File aSource,File aRaw,File aWav) throws Exception {
		String aMap = "0:a:"+Math.max(0, audioTrack);
		status("AI.ExtractingAudio", aSource.getName(), 0, 0);
		workDir.mkdirs();
		String aFfmpeg = Ffmpeg.ffmpegPath();
		if(aFfmpeg == null) {
			throw new Exception("ffmpeg not found");
		}
		Vector<String> aCmd = new Vector<String>();
		aCmd.add(aFfmpeg); aCmd.add("-y"); aCmd.add("-v"); aCmd.add("error"); aCmd.add("-i"); aCmd.add(aSource.getAbsolutePath());
		aCmd.add("-map"); aCmd.add(aMap); aCmd.add("-vn"); aCmd.add("-ac"); aCmd.add("1"); aCmd.add("-ar"); aCmd.add("16000");
		aCmd.add("-f"); aCmd.add("s16le"); aCmd.add(aRaw.getAbsolutePath());
		if(aWav != null) {
			aCmd.add("-map"); aCmd.add(aMap); aCmd.add("-vn"); aCmd.add("-ac"); aCmd.add("1"); aCmd.add("-ar"); aCmd.add("24000");
			aCmd.add(aWav.getAbsolutePath());
		}
		ProcessBuilder aPB = new ProcessBuilder(aCmd);
		aPB.redirectErrorStream(true);
		StringBuilder aOut = new StringBuilder();
		int aCode = runProcess(aPB, aL -> aOut.append(aL).append('\n'));
		if(canceled()) {
			return false;
		}
		if(aCode != 0 || !aRaw.isFile() || aRaw.length() < 3200) {
			System.err.println(aOut);
			throw new GenerationException("NOSOUND");
		}
		return true;
	}

	/**
	 * The subtitles of the user (.srt or .vtt) copied as a SRT of the WorkCache (not recomputed; the file itself is
	 * left untouched)
	 */
	File ownSubtitles(File aOwn) throws Exception {
		Vector<SubtitleFile.Cue> aCues = SubtitleFile.read(aOwn.getPath());
		if(aCues.isEmpty()) {
			throw new GenerationException("NOSPEECH");
		}
		System.out.println("AI speech: subtitles of the user: "+aOwn.getPath()+" ("+aCues.size()+")");
		recognized = "";
		File aSrt = new File(workDir, "AI_Speech_"+System.nanoTime()+".srt");
		writeSrt(aSrt, aCues);
		return aSrt;
	}

	/**
	 * Install what is missing and transcribe aRaw into a SRT of the WorkCache
	 * @return the SRT, null if canceled
	 */
	File transcribe(Model aAsr,File aRaw,String aSpoken,File aOutDir,String aBase) throws Exception {
		env = SHARED;
		if(!installEnv() || canceled()) {
			return null;
		}
		if(aAsr == PARAKEET && !installExtra(SHARED, SHARED_EXTRA, "speech") || canceled()) {
			return null;
		}
		if(!download(aAsr, "AI.DownloadingSpeechModel")) {
			return null;
		}
		if(!isLoaded(tag(aAsr))) {
			beforeLoad(tag(aAsr));
		}
		File aScript = copyScript("speech_transcribe.py", aAsr.parts[0].getDir(tdeDir));
		Vector<String> aCmd = pythonCommand(aScript);
		aCmd.add("--engine"); aCmd.add(aAsr.engine);
		aCmd.add("--model-dir"); aCmd.add(aAsr.parts[0].getDir(tdeDir).getAbsolutePath());
		if(aAsr.parts.length > 1) {
			aCmd.add("--aligner-dir"); aCmd.add(aAsr.parts[1].getDir(tdeDir).getAbsolutePath());
		}
		aCmd.add("--device"); aCmd.add(cpuReason(aAsr) == null ? "auto" : "cpu");
		File aSrt = new File(workDir, "AI_Speech_"+System.nanoTime()+".srt");
		Vector<String> aReq = new Vector<String>();
		aReq.add("--audio"); aReq.add(aRaw.getAbsolutePath());
		aReq.add("--language"); aReq.add(aSpoken);
		aReq.add("--out"); aReq.add(aSrt.getAbsolutePath());
		recognized = "";
		String aDone = runResident(tag(aAsr), aAsr.gpuMemory, aCmd, aReq);
		if(aDone == null || canceled()) {
			aSrt.delete();
			return null;
		}
		if(!aSrt.isFile() || SubtitleFile.read(aSrt.getPath()).isEmpty()) {
			aSrt.delete();
			throw new GenerationException("NOSPEECH");
		}
		return aSrt;
	}

	/**
	 * The subtitles of aSrt translated by the LLM into aTarget: <aBase> - <CODE>.srt of aOutDir, same times
	 * @return null if canceled
	 */
	File translate(File aSrt,String aSource,String aTarget,File aOutDir,String aBase) throws Exception {
		Vector<SubtitleFile.Cue> aCues = SubtitleFile.read(aSrt.getPath());
		StringBuilder aLines = new StringBuilder();
		for(SubtitleFile.Cue aC : aCues) {
			aLines.append(aLines.length() > 0 ? "\n" : "").append(aC.text.replace('\n', ' ').trim());
		}
		PromptExtender aPE = PromptExtender.attach(tdeDir, progress);
		extender = aPE;
		try {
			status("AI.TranslationStarting", displayName(aTarget, "EN"), 0, 0);
			String aT = aPE.translate(aLines.toString(), aTarget, aSource);
			if(aT == null || canceled()) {
				return null;
			}
			String[] aTr = aT.split("\n", -1);
			Vector<SubtitleFile.Cue> aOut = new Vector<SubtitleFile.Cue>();
			for(int i = 0;i < aCues.size();i++) {
				String aText = i < aTr.length ? aTr[i].trim() : "";
				aOut.add(new SubtitleFile.Cue(aCues.elementAt(i).startMS, aCues.elementAt(i).endMS, wrap(aText, aTarget)));
			}
			aOutDir.mkdirs();
			File aF = target(aOutDir, aBase, aTarget, ".srt");
			writeSrt(aF, aOut);
			return aF;
		}
		finally {
			extender = null;
			aPE.detach();
		}
	}

	/**
	 * Two balanced lines when longer than 42 characters (no space: in the middle)
	 */
	static String wrap(String aText,String aLanguage) {
		if(aText.length() <= 42) {
			return aText;
		}
		if(aText.indexOf(' ') < 0) {
			int aMid = aText.length()/2;
			return aText.substring(0, aMid)+"\n"+aText.substring(aMid);
		}
		int aBest = -1;
		for(int i = 0;i < aText.length();i++) {
			if(aText.charAt(i) == ' ' && (aBest < 0 || Math.abs(i-aText.length()/2) < Math.abs(aBest-aText.length()/2))) {
				aBest = i;
			}
		}
		return aText.substring(0, aBest)+"\n"+aText.substring(aBest+1);
	}

	public static void writeSrt(File aF,Vector<SubtitleFile.Cue> aCues) throws Exception {
		StringBuilder aSB = new StringBuilder();
		int n = 1;
		for(SubtitleFile.Cue aC : aCues) {
			if(aC.text.trim().isEmpty()) {
				continue;
			}
			aSB.append(n++).append('\n').append(clock(aC.startMS)).append(" --> ").append(clock(aC.endMS)).append('\n')
				.append(aC.text.trim()).append("\n\n");
		}
		aF.getParentFile().mkdirs();
		Files.write(aF.toPath(), aSB.toString().getBytes(StandardCharsets.UTF_8));
	}

	static String clock(long aMS) {
		return String.format("%02d:%02d:%02d,%03d", aMS/3600000, aMS/60000%60, aMS/1000%60, aMS%1000);
	}

	/**
	 * Install what is missing and speak the translated subtitles of aCues with the cloned voice into aOut
	 * @return false if canceled
	 */
	boolean speak(Model aTts,String aVoice,File aWav,File aCues,String aLanguage,double aSeconds,File aOut) throws Exception {
		env = TTS_ENV;
		try {
			if(!installEnv() || canceled()) {
				return false;
			}
			if(!download(aTts, "AI.DownloadingVoiceModel")) {
				return false;
			}
			boolean aNative = aVoice != null && !aVoice.isEmpty();
			if(aNative && designBytesToDownload(tdeDir, aVoice, aLanguage) > 0
					&& !download(new Model("Design", "", "", "", 0, new String[0], DESIGN), "AI.DownloadingVoiceModel")) {
				return false;
			}
			if(!isLoaded(tag(aTts))) {
				beforeLoad(tag(aTts));
			}
			File aDir = aTts.parts[0].getDir(tdeDir);
			File aScript = copyScript("speech_dub.py", aDir);
			Vector<String> aCmd = pythonCommand(aScript);
			aCmd.add("--model-dir"); aCmd.add(aDir.getAbsolutePath());
			aCmd.add("--device"); aCmd.add(cpuReason(aTts) == null ? "auto" : "cpu");
			Vector<String> aReq = new Vector<String>();
			aReq.add("--audio"); aReq.add(aWav.getAbsolutePath());
			aReq.add("--cues"); aReq.add(aCues.getAbsolutePath());
			aReq.add("--language"); aReq.add(aLanguage);
			aReq.add("--duration"); aReq.add(String.format(Locale.ROOT, "%.3f", aSeconds));
			aReq.add("--out"); aReq.add(aOut.getAbsolutePath());
			if(aNative) {
				aReq.add("--voice"); aReq.add(aVoice);
				aReq.add("--design-dir"); aReq.add(DESIGN.getDir(tdeDir).getAbsolutePath());
				aReq.add("--ref-cache"); aReq.add(voicesDir(tdeDir).getAbsolutePath());
			}
			String aDone = runResident(tag(aTts), aTts.gpuMemory, aCmd, aReq);
			if(aDone == null || canceled()) {
				return false;
			}
			if(!aOut.isFile()) {
				throw new Exception("No sound produced (see log/3De.log)");
			}
			return true;
		}
		finally {
			env = SHARED;
		}
	}

	/**
	 * Files of the model (resumed file by file)
	 * @return false if canceled
	 */
	boolean download(Model aModel,String aKey) throws Exception {
		for(Part aP : aModel.parts) {
			Vector<String[]> aFiles = new Vector<String[]>();
			for(Object[] aF : aP.files) {
				aFiles.add(new String[] {HF+aP.repo+"/resolve/main/"+aF[0], (String)aF[0], ""+aF[1]});
			}
			if(!downloadFiles(aP.getDir(tdeDir), aFiles, aKey) || canceled()) {
				return false;
			}
		}
		return true;
	}
}
