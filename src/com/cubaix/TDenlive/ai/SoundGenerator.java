package com.cubaix.TDenlive.ai;

import java.io.File;
import java.lang.management.ManagementFactory;
import java.util.Locale;
import java.util.Vector;

import com.cubaix.TDenlive.depth.DepthEstimator;
import com.cubaix.TDenlive.depth.DepthModel;

/**
 * Text to sound, run by a Python script of res/ai/ in the Python environment shared with WanGenerator
 * (see PythonAI), with one of two generators:
 * - music: MusicGen (Meta, https://huggingface.co/facebook/musicgen-small, -medium, -large; CC-BY-NC-4.0),
 * musicgen_generate.py (transformers), 32 kHz mono, at most 30 s;
 * - sound effects: Woosh (Sony AI, https://github.com/SonyResearch/Woosh, CC-BY-NC-4.0 weights), Woosh-DFlow
 * (distilled, 4 steps) or Woosh-Flow, woosh_generate.py with the woosh package of its authors (GitHub archive at
 * WOOSH_COMMIT) in its own environment (WOOSH_ENV), 48 kHz mono; its weights are zip files of the GitHub release
 * (archives). AudioLDM 2 and MOSS-SoundEffect, tried before, were removed (far worse).
 * The model chosen is downloaded at its first use into ~/3Denlive/Models/<model>/.
 * Unlike Wan, they also run on the CPU (slower): it is used when there is no GPU, or when the NVIDIA GPU
 * has not enough free memory for the model. Output: WAV.
 */
public class SoundGenerator extends PythonAI {
	static final long GB = 1024L*1024*1024;
	public static final String MUSIC = "music";
	public static final String SFX = "sfx";

	public static class Model {
		//MUSIC or SFX
		public final String kind;
		public final String id;
		public final String repo;
		final String dir;
		final String script;
		//Parameters (for the user)
		public final String params;
		//Files: path, size (0: any)
		final Object[][] files;
		//Memory needed: NVIDIA GPU (fp16), Apple Silicon (fp32, unified memory), RAM to run on the CPU (fp32)
		public final long cudaMemory;
		public final long macMemory;
		public final long cpuMemory;
		public final double maxSeconds;
		public final int sampleRate;
		//Python environment (null: the shared one)
		Env env = null;
		//Denoising steps (0: those of the script)
		int steps = 0;
		//Model of a family given to the script (--variant; null: none)
		String variant = null;
		//Zip archives unpacked into the folder of the model: {url, size, entry prefix kept, folder of those
		//entries (relative to the model folder), name}; unpacked when <dir>/.unpacked-<name> exists
		Object[][] archives = new Object[0][];

		Model(String aKind,String aId,String aRepo,String aDir,String aScript,String aParams,Object[][] aFiles
				,long aCuda,long aMac,long aCpu,double aMaxSeconds,int aRate) {
			kind = aKind;
			id = aId;
			repo = aRepo;
			dir = aDir;
			script = aScript;
			params = aParams;
			files = aFiles;
			cudaMemory = aCuda;
			macMemory = aMac;
			cpuMemory = aCpu;
			maxSeconds = aMaxSeconds;
			sampleRate = aRate;
		}

		public File getDir(String aTdeDir) {
			return new File(DepthModel.getModelsDir(aTdeDir), dir);
		}

		public long downloadSize() {
			long aS = 0;
			for(Object[] aF : files) {
				aS += (Long)aF[1];
			}
			for(Object[] aA : archives) {
				aS += (Long)aA[1];
			}
			return aS;
		}

		public long bytesToDownload(String aTdeDir) {
			long aS = PythonAI.bytesToDownload(getDir(aTdeDir), files);
			for(Object[] aA : archives) {
				if(!isUnpacked(aTdeDir, aA)) {
					aS += (Long)aA[1];
				}
			}
			return aS;
		}

		boolean isUnpacked(String aTdeDir,Object[] aArchive) {
			return unpackedMarker(aTdeDir, aArchive).isFile();
		}

		File unpackedMarker(String aTdeDir,Object[] aArchive) {
			return new File(getDir(aTdeDir), ".unpacked-"+aArchive[4]);
		}

		public boolean isDownloaded(String aTdeDir) {
			return bytesToDownload(aTdeDir) == 0;
		}

		File getCodeDir(String aTdeDir) {
			return new File(getDir(aTdeDir), "code");
		}

		Env env() {
			return env == null ? SHARED : env;
		}

		/**
		 * Disk size of the Python environment of the model still to install (0 if installed)
		 */
		public long envBytesToInstall(String aTdeDir) {
			return PythonAI.envBytesToInstall(aTdeDir, env());
		}

		/**
		 * GPU memory needed on this computer
		 */
		public long gpuMemory() {
			return isMac() ? macMemory : cudaMemory;
		}
	}

	//Configuration and tokenizer (T5) of every MusicGen model
	static final Object[][] MUSICGEN_FILES = {
		{"config.json", 0L},
		{"generation_config.json", 0L},
		{"preprocessor_config.json", 0L},
		{"special_tokens_map.json", 0L},
		{"spiece.model", 791656L},
		{"tokenizer.json", 2422095L},
		{"tokenizer_config.json", 0L},
	};

	static Model musicGen(String aId,String aParams,Object[][] aWeights,long aCuda,long aMac,long aCpu) {
		Object[][] aFiles = new Object[MUSICGEN_FILES.length+aWeights.length][];
		System.arraycopy(MUSICGEN_FILES, 0, aFiles, 0, MUSICGEN_FILES.length);
		System.arraycopy(aWeights, 0, aFiles, MUSICGEN_FILES.length, aWeights.length);
		String aName = "musicgen-"+aId.toLowerCase();
		return new Model(MUSIC, aId, "facebook/"+aName, aName, "musicgen_generate.py", aParams, aFiles
				, aCuda, aMac, aCpu, 30, 32000);
	}

	//Memories: small measured on Apple Silicon (4 GB peak in fp32), the others from their fp32 weights
	//(8 and 13.7 GB) plus the same activations
	public static final Model SMALL = musicGen("Small", "300 M", new Object[][] {
		{"model.safetensors", 2364427288L},
	}, 3*GB, 6*GB, 8*GB);
	//Medium and Large exist only as PyTorch .bin files (read by transformers with torch.load weights_only)
	public static final Model MEDIUM = musicGen("Medium", "1.5 G", new Object[][] {
		{"pytorch_model.bin", 8042351585L},
	}, 6*GB, 10*GB, 16*GB);
	public static final Model LARGE = musicGen("Large", "3.3 G", new Object[][] {
		{"pytorch_model.bin.index.json", 0L},
		{"pytorch_model-00001-of-00002.bin", 9987827539L},
		{"pytorch_model-00002-of-00002.bin", 3731608779L},
	}, 10*GB, 16*GB, 24*GB);
	public static final Model[] MUSIC_MODELS = {SMALL, MEDIUM, LARGE};

	//Woosh: the woosh package of its authors (GitHub archive at this commit: only woosh/, into code/), the
	//tokenizer and configuration of RoBERTa-large (its weights are in TextConditionerA), the zip files of the
	//release v1.0.0 (checkpoints/<name>/config.yaml + weights.safetensors): autoencoder, text encoder, flow
	static final String WOOSH_COMMIT = "7f238bbd5d6dbc94d27190c07827b2d0eaefa268";
	static final String WOOSH_RELEASE = "https://github.com/SonyResearch/Woosh/releases/download/v1.0.0/";
	//Versions tested with that commit (its pyproject: torch 2.8.0, transformers >= 4.57.2), without the demo,
	//video and training packages (gradio, av, lightning), not used by the text to audio models
	public static final Env WOOSH_ENV = new Env("woosh-env", "1", new String[] {"torch==2.8.0", "torchaudio==2.8.0"
			, "einops==0.8.2", "hydra-core==1.3.8", "omegaconf==2.3.1", "timm==1.0.31", "torchdiffeq==0.2.5"
			, "transformers==4.57.3", "hear21passt==0.0.26", "pydantic==2.14.3", "numpy==2.5.4", "soundfile==0.14.0"
			, "requests"}, new String[0]);
	static final String ROBERTA = HF+"FacebookAI/roberta-large/resolve/main/";

	//Memories: 6.5 GB peak measured on Apple Silicon (Woosh-DFlow, 5 s, fp32, process footprint), the others
	//estimated from the weights (3.7 GB in fp32)
	static Model woosh(String aId,String aFlowZip,long aFlowSize,String aParams,int aSteps) {
		Model aM = new Model(SFX, aId, "SonyResearch/Woosh", "Woosh", "woosh_generate.py", aParams, new Object[][] {
			{"roberta-large/config.json", 482L, ROBERTA+"config.json"},
			{"roberta-large/vocab.json", 898823L, ROBERTA+"vocab.json"},
			{"roberta-large/merges.txt", 456318L, ROBERTA+"merges.txt"},
			{"roberta-large/tokenizer.json", 1355863L, ROBERTA+"tokenizer.json"},
			{"roberta-large/tokenizer_config.json", 25L, ROBERTA+"tokenizer_config.json"},
		}, 5*GB, 7*GB, 8*GB, 10, 48000);
		aM.env = WOOSH_ENV;
		aM.variant = aId;
		aM.steps = aSteps;
		aM.archives = new Object[][] {
			{"https://github.com/SonyResearch/Woosh/archive/"+WOOSH_COMMIT+".zip", 0L, "Woosh-"+WOOSH_COMMIT+"/woosh/", "code/woosh/", "code-"+WOOSH_COMMIT.substring(0, 8)},
			{WOOSH_RELEASE+"Woosh-AE.zip", 822991075L, "checkpoints/", "checkpoints/", "Woosh-AE"},
			{WOOSH_RELEASE+"TextConditionerA.zip", 1297121262L, "checkpoints/", "checkpoints/", "TextConditionerA"},
			{WOOSH_RELEASE+aFlowZip, aFlowSize, "checkpoints/", "checkpoints/", aId},
		};
		return aM;
	}

	//Distilled: 4 steps, a few seconds; the original: adaptive ODE solver (dopri5), slower, a bit finer
	public static final Model WOOSH_DFLOW = woosh("Woosh-DFlow", "Woosh-DFlow.zip", 1281505601L, "350 M", 4);
	public static final Model WOOSH_FLOW = woosh("Woosh-Flow", "Woosh-Flow.zip", 1253883744L, "350 M", 0);
	//Woosh-DFlow first: advised (same memory, faster; defaultModel() keeps the first of equal needs)
	public static final Model[] SFX_MODELS = {WOOSH_DFLOW, WOOSH_FLOW};

	public static final double MIN_SECONDS = 1;
	//Longest music made by continuing a music (MusicGen sees 30 s at once: continued window after window)
	public static final double CONTINUE_MAX_SECONDS = 600;

	/**
	 * Models of a kind (MUSIC or SFX), from the smallest
	 */
	public static Model[] models(String aKind) {
		return SFX.equals(aKind) ? SFX_MODELS : MUSIC_MODELS;
	}

	public static Model byId(String aKind,String aId) {
		for(Model aM : models(aKind)) {
			if(aM.id.equalsIgnoreCase(aId)) {
				return aM;
			}
		}
		return null;
	}

	/**
	 * Model of a kind advised for this computer: the largest that fits in the available GPU memory
	 * (PythonAI.availableGpuMemory(): the model kept loaded counts as free, as the memory warning of the
	 * form), the smallest on the CPU
	 */
	public static Model defaultModel(String aKind) {
		Model[] aModels = models(aKind);
		if(!hasGpu()) {
			return aModels[0];
		}
		long aMem = availableGpuMemory();
		if(aMem < 0) {
			return aModels[0];
		}
		Model aBestM = aModels[0];
		for(Model aM : aModels) {
			//Equal needs: the first one (the faster)
			if(aM.gpuMemory() <= aMem && aM.gpuMemory() > aBestM.gpuMemory()) {
				aBestM = aM;
			}
		}
		return aBestM;
	}

	/**
	 * Python and PyTorch exist for this platform (GPU or CPU)
	 */
	public static boolean isPlatformSupported() {
		return hasPython();
	}

	/**
	 * Physical memory of the computer (bytes, -1 if unknown)
	 */
	public static long ramSize() {
		try {
			return ((com.sun.management.OperatingSystemMXBean)ManagementFactory.getOperatingSystemMXBean()).getTotalMemorySize();
		}
		catch(Throwable t) {
			return -1;
		}
	}

	/**
	 * Device for aModel: null for the GPU, else the reason of the CPU (DepthEstimator.CPU_REASON_*).
	 * On an NVIDIA GPU without enough free memory, the CPU is used; on Apple Silicon the memory is shared,
	 * the GPU is always used.
	 */
	public static String cpuReason(Model aModel) {
		//Already loaded on the GPU: stays there (its own memory is not free)
		if("auto".equals(loadedDevice(tag(aModel))) && hasGpu()) {
			return null;
		}
		if(!hasGpu()) {
			return DepthEstimator.CPU_REASON_NO_GPU;
		}
		if(!isMac()) {
			long aAvail = availableGpuMemory();
			if(aAvail >= 0 && aAvail < aModel.cudaMemory) {
				return DepthEstimator.CPU_REASON_GPU_MEMORY;
			}
		}
		return null;
	}

	/**
	 * Name of aModel kept loaded (PythonAI.Resident)
	 */
	public static String tag(Model aModel) {
		return "sound "+aModel.id;
	}

	/**
	 * aModel is the model kept loaded
	 */
	public static boolean isLoaded(Model aModel) {
		return PythonAI.isLoaded(tag(aModel));
	}

	Model model;
	//"No background noise" (sound effects): words added to the prompt by the form, where they are seen (said
	//positively: the CLAP text encoder of Woosh reads a negation badly)
	public static final String CLEAN_PROMPT = ", isolated sound on a silent background, clean close studio recording";

	/**
	 * aPrompt with the words of "No background noise" at its end (once)
	 */
	public static String withClean(String aPrompt) {
		String aP = withoutClean(aPrompt).replaceAll("[\\s.,;]+$", "");
		return aP.isEmpty() ? aP : aP+CLEAN_PROMPT;
	}

	/**
	 * aPrompt without the words of "No background noise"
	 */
	public static String withoutClean(String aPrompt) {
		return aPrompt.replace(CLEAN_PROMPT, "").replace(CLEAN_PROMPT.substring(2), "").trim();
	}

	//MusicGen: the generation stops when this file exists (GenerationException STOPPED, the model stays loaded)
	public File stopFile = null;
	//Device used by the script (DEVICE message): cuda, mps or cpu
	public volatile String device = null;

	public SoundGenerator(String aTdeDir,Model aModel,Progress aProgress) {
		super(aTdeDir, aProgress);
		model = aModel;
	}

	@Override
	Env env() {
		return model.env();
	}

	@Override
	String stepKey() {
		return SFX.equals(model.kind) ? "AI.GeneratingSfx" : "AI.GeneratingMusic";
	}

	@Override
	boolean handleLine(String aL) {
		if(aL.startsWith("DEVICE ")) {
			device = aL.substring(7).trim();
			System.out.println("AI sound device: "+device);
			return true;
		}
		return false;
	}

	/**
	 * Install what is missing, then generate aOut (WAV) from the prompt of aPromptFile (UTF-8)
	 * @return false if canceled
	 */
	public boolean generate(File aPromptFile,double aSeconds,File aOut) throws Exception {
		return generate(aPromptFile, aSeconds, null, aOut);
	}

	/**
	 * Same, the sound of aContinue (WAV, MusicGen only, null: none) continued up to aSeconds in all
	 * @return false if canceled
	 */
	public boolean generate(File aPromptFile,double aSeconds,File aContinue,File aOut) throws Exception {
		if(!installEnv() || !downloadModel() || canceled()) {
			return false;
		}
		//Another model kept loaded is unloaded first, so that its memory counts as free for the device
		if(!isLoaded(model)) {
			beforeLoad(tag(model));
		}
		File aDir = model.getDir(tdeDir);
		File aScript = copyScript(model.script, aDir);
		//Arguments of the model (the same: the model kept loaded is used again)
		Vector<String> aCmd = pythonCommand(aScript);
		aCmd.add("--model-dir"); aCmd.add(aDir.getAbsolutePath());
		if(model.variant != null) {
			aCmd.add("--code-dir"); aCmd.add(model.getCodeDir(tdeDir).getAbsolutePath());
		}
		if(model.variant != null) {
			aCmd.add("--variant"); aCmd.add(model.variant);
		}
		aCmd.add("--device"); aCmd.add(cpuReason(model) == null ? "auto" : "cpu");
		//Arguments of this generation
		Vector<String> aReq = new Vector<String>();
		aReq.add("--prompt-file"); aReq.add(aPromptFile.getAbsolutePath());
		aReq.add("--seconds"); aReq.add(String.format(Locale.ROOT, "%.1f", aSeconds));
		if(model.steps > 0) {
			aReq.add("--steps"); aReq.add(""+model.steps);
		}
		if(aContinue != null) {
			aReq.add("--continue"); aReq.add(aContinue.getAbsolutePath());
		}
		if(stopFile != null && MUSIC.equals(model.kind)) {
			aReq.add("--stop-file"); aReq.add(stopFile.getAbsolutePath());
		}
		aReq.add("--out"); aReq.add(aOut.getAbsolutePath());
		String aDone = runResident(tag(model), model.gpuMemory(), aCmd, aReq);
		if(aDone == null || canceled()) {
			aOut.delete();
			return false;
		}
		if(!aOut.isFile()) {
			throw new Exception("No sound produced (see log/3De.log)");
		}
		return true;
	}

	/**
	 * Download (resumed) and unpack a zip archive of the model (see Model.archives), then delete it
	 * @return false if canceled
	 */
	boolean downloadArchive(Object[] aA) throws Exception {
		if(model.isUnpacked(tdeDir, aA)) {
			return true;
		}
		File aDir = model.getDir(tdeDir);
		String aName = (String)aA[4];
		File aZip = new File(aDir, aName+".zip");
		Vector<String[]> aFiles = new Vector<String[]>();
		aFiles.add(new String[] {(String)aA[0], aZip.getName(), ""+aA[1]});
		if(!downloadFiles(aDir, aFiles, "AI.DownloadingSoundModel") || canceled()) {
			return false;
		}
		status("AI.UnpackingSoundModel", aName, 0, 0);
		String aFrom = (String)aA[2];
		File aTo = new File(aDir, ((String)aA[3]).replace('/', File.separatorChar));
		String aRoot = aDir.getCanonicalPath()+File.separator;
		int aN = 0;
		try(java.util.zip.ZipInputStream aZ = new java.util.zip.ZipInputStream(
				new java.io.BufferedInputStream(java.nio.file.Files.newInputStream(aZip.toPath()), 1 << 20))) {
			java.util.zip.ZipEntry aE;
			while((aE = aZ.getNextEntry()) != null) {
				if(canceled()) {
					return false;
				}
				String aPath = aE.getName();
				if(aE.isDirectory() || !aPath.startsWith(aFrom)) {
					continue;
				}
				File aF = new File(aTo, aPath.substring(aFrom.length()).replace('/', File.separatorChar)).getCanonicalFile();
				//Nothing outside the folder of the model
				if(!aF.getPath().startsWith(aRoot)) {
					continue;
				}
				aF.getParentFile().mkdirs();
				java.nio.file.Files.copy(aZ, aF.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
				aN++;
			}
		}
		if(aN == 0) {
			throw new Exception("Nothing to unpack in "+aZip.getName());
		}
		java.nio.file.Files.write(model.unpackedMarker(tdeDir, aA).toPath(), (aA[0]+"\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));
		aZip.delete();
		System.out.println("AI sound model: "+aN+" files unpacked from "+aName);
		return true;
	}

	/**
	 * Model files (resumed file by file)
	 * @return false if canceled
	 */
	boolean downloadModel() throws Exception {
		Vector<String[]> aFiles = new Vector<String[]>();
		for(Object[] aF : model.files) {
			//A file of another repository: its URL
			String aUrl = aF.length > 2 ? (String)aF[2] : HF+model.repo+"/resolve/main/"+aF[0];
			aFiles.add(new String[] {aUrl, (String)aF[0], ""+aF[1]});
		}
		if(!downloadFiles(model.getDir(tdeDir), aFiles, "AI.DownloadingSoundModel")) {
			return false;
		}
		for(Object[] aA : model.archives) {
			if(!downloadArchive(aA)) {
				return false;
			}
		}
		return true;
	}
}
