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
 * - sound effects: AudioLDM 2 (https://huggingface.co/cvssp/audioldm2, audioldm2-large; CC-BY-NC-SA-4.0),
 * audioldm2_generate.py (diffusers), 16 kHz mono; or MOSS-SoundEffect v2.0
 * (https://huggingface.co/OpenMOSS-Team/MOSS-SoundEffect-v2.0, Apache-2.0), moss_soundeffect_generate.py with
 * the inference code of its authors (downloaded from GitHub at a fixed commit, MOSS_CODE_FILES) in its own
 * Python environment (MOSS_ENV: the versions of transformers / diffusers it was made with), 48 kHz mono.
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
		//Inference code downloaded with the model into <dir>/code/: base URL, files {path, size (0: any)}
		String codeUrl = null;
		Object[][] codeFiles = null;
		//Denoising steps (0: those of the script)
		int steps = 0;

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
			return aS;
		}

		public long bytesToDownload(String aTdeDir) {
			return PythonAI.bytesToDownload(getDir(aTdeDir), files);
		}

		public boolean isDownloaded(String aTdeDir) {
			if(codeFiles != null) {
				for(Object[] aF : codeFiles) {
					if(!new File(getCodeDir(aTdeDir), ((String)aF[0]).replace('/', File.separatorChar)).isFile()) {
						return false;
					}
				}
			}
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

	//AudioLDM 2 (safetensors only): the same files for both, except the UNet
	static Object[][] audioLdm2Files(long aUnetSize) {
		return new Object[][] {
			{"model_index.json", 0L},
			{"feature_extractor/preprocessor_config.json", 0L},
			{"language_model/config.json", 0L},
			{"language_model/model.safetensors", 497772432L},
			{"projection_model/config.json", 0L},
			{"projection_model/diffusion_pytorch_model.safetensors", 4737688L},
			{"scheduler/scheduler_config.json", 0L},
			{"text_encoder/config.json", 0L},
			{"text_encoder/model.safetensors", 776327432L},
			{"text_encoder_2/config.json", 0L},
			{"text_encoder_2/model.safetensors", 1364951064L},
			{"tokenizer/merges.txt", 0L},
			{"tokenizer/special_tokens_map.json", 0L},
			{"tokenizer/tokenizer.json", 2108774L},
			{"tokenizer/tokenizer_config.json", 0L},
			{"tokenizer/vocab.json", 0L},
			{"tokenizer_2/special_tokens_map.json", 0L},
			{"tokenizer_2/spiece.model", 0L},
			{"tokenizer_2/tokenizer.json", 2422164L},
			{"tokenizer_2/tokenizer_config.json", 0L},
			{"unet/config.json", 0L},
			{"unet/diffusion_pytorch_model.safetensors", aUnetSize},
			{"vae/config.json", 0L},
			{"vae/diffusion_pytorch_model.safetensors", 221530308L},
			{"vocoder/config.json", 0L},
			{"vocoder/model.safetensors", 221079092L},
		};
	}

	//6.4 GB peak measured on Apple Silicon in fp32 for the base model
	public static final Model AUDIOLDM2 = new Model(SFX, "AudioLDM 2", "cvssp/audioldm2", "audioldm2"
			, "audioldm2_generate.py", "1.1 G", audioLdm2Files(1387964784L), 4*GB, 8*GB, 8*GB, 20, 16000);
	//Its UNet is twice as large (750 M parameters instead of 350 M)
	public static final Model AUDIOLDM2_LARGE = new Model(SFX, "AudioLDM 2 Large", "cvssp/audioldm2-large", "audioldm2-large"
			, "audioldm2_generate.py", "1.5 G", audioLdm2Files(2872468672L), 5*GB, 10*GB, 10*GB, 20, 16000);

	//MOSS-SoundEffect v2.0: its inference code, moss_soundeffect_v2 of github.com/OpenMOSS/MOSS-TTS at this commit
	//(only the files of the inference: no training, no demo)
	static final String MOSS_COMMIT = "934d6826b084c46a0d033402174d5f8ac4ed2519";
	static final Object[][] MOSS_CODE_FILES = {
		{"moss_soundeffect_v2/__init__.py", 0L},
		{"moss_soundeffect_v2/pipeline_moss_soundeffect.py", 0L},
		{"moss_soundeffect_v2/diffsynth/__init__.py", 0L},
		{"moss_soundeffect_v2/diffsynth/models/__init__.py", 0L},
		{"moss_soundeffect_v2/diffsynth/models/utils.py", 0L},
		{"moss_soundeffect_v2/diffsynth/models/dac_vae.py", 0L},
		{"moss_soundeffect_v2/diffsynth/models/qwen3_text_encoder.py", 0L},
		{"moss_soundeffect_v2/diffsynth/models/wan_audio_dit.py", 0L},
		{"moss_soundeffect_v2/diffsynth/models/wan_video_dit.py", 0L},
		{"moss_soundeffect_v2/diffsynth/models/wan_video_camera_controller.py", 0L},
		{"moss_soundeffect_v2/diffsynth/pipelines/__init__.py", 0L},
		{"moss_soundeffect_v2/diffsynth/pipelines/wan_audio.py", 0L},
		{"moss_soundeffect_v2/diffsynth/prompters/__init__.py", 0L},
		{"moss_soundeffect_v2/diffsynth/prompters/base_prompter.py", 0L},
		{"moss_soundeffect_v2/diffsynth/prompters/wan_prompter.py", 0L},
		{"moss_soundeffect_v2/diffsynth/schedulers/__init__.py", 0L},
		{"moss_soundeffect_v2/diffsynth/schedulers/flow_match.py", 0L},
		{"moss_soundeffect_v2/diffsynth/utils/__init__.py", 0L},
	};
	//Versions of its pyproject.toml (transformers 4.57, diffusers 0.37), without the demo (gradio)
	public static final Env MOSS_ENV = new Env("moss-env", "1", new String[] {"torch==2.9.0", "torchaudio==2.9.0"
			, "numpy==1.26.4", "einops==0.8.2", "pillow==12.2.0", "tqdm==4.67.3", "safetensors==0.7.0"
			, "transformers==4.57.1", "diffusers==0.37.1", "ftfy==6.3.1", "regex==2026.4.4", "soundfile==0.13.1"
			, "descript-audiotools==0.7.2", "typing-extensions>=4.10"}, new String[0]);
	//DiT 1.3 B + Qwen3 1.7 B text encoder + DAC VAE; files in fp32 (11.2 GB), run in bf16 on the GPU.
	//Memories estimated from the weights (not measured yet: see the PEAK line in 3De.log)
	public static final Model MOSS = new Model(SFX, "MOSS-SoundEffect v2", "OpenMOSS-Team/MOSS-SoundEffect-v2.0"
			, "MOSS-SoundEffect-v2.0", "moss_soundeffect_generate.py", "1.3 G + 1.7 G", new Object[][] {
		{"model_index.json", 0L},
		{"scheduler/scheduler_config.json", 0L},
		{"text_encoder/config.json", 0L},
		{"text_encoder/generation_config.json", 0L},
		{"text_encoder/model.safetensors.index.json", 0L},
		{"text_encoder/model-00001-of-00002.safetensors", 3441185608L},
		{"text_encoder/model-00002-of-00002.safetensors", 622329984L},
		{"tokenizer/merges.txt", 1671853L},
		{"tokenizer/tokenizer.json", 11422654L},
		{"tokenizer/tokenizer_config.json", 0L},
		{"tokenizer/vocab.json", 2776833L},
		{"transformer/config.json", 0L},
		{"transformer/diffusion_pytorch_model.safetensors", 5664274560L},
		{"vae/config.json", 0L},
		{"vae/vae_128d_48k.pth", 1486465965L},
	}, 8*GB, 12*GB, 16*GB, 30, 48000);
	static {
		MOSS.env = MOSS_ENV;
		MOSS.codeUrl = "https://raw.githubusercontent.com/OpenMOSS/MOSS-TTS/"+MOSS_COMMIT+"/";
		MOSS.codeFiles = MOSS_CODE_FILES;
		MOSS.steps = 100;
	}
	public static final Model[] SFX_MODELS = {AUDIOLDM2, AUDIOLDM2_LARGE, MOSS};

	//Denoising steps of AudioLDM 2 (200 in its examples, little difference above 100)
	public static final int SFX_STEPS = 150;

	public static final double MIN_SECONDS = 1;

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
	 * Model of a kind advised for this computer: the largest that fits in the free GPU memory (NVIDIA; on
	 * Apple Silicon the free + reclaimable memory, see DepthEstimator.bestGpu(), as the memory warning of the
	 * form), the smallest on the CPU
	 */
	public static Model defaultModel(String aKind) {
		Model[] aModels = models(aKind);
		if(!hasGpu()) {
			return aModels[0];
		}
		long[] aBest = DepthEstimator.bestGpu();
		long aMem = aBest == null ? -1 : aBest[1];
		if(aMem < 0) {
			return aModels[0];
		}
		Model aBestM = aModels[0];
		for(Model aM : aModels) {
			if(aM.gpuMemory() <= aMem) {
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
		if(!hasGpu()) {
			return DepthEstimator.CPU_REASON_NO_GPU;
		}
		if(!isMac()) {
			long[] aBest = DepthEstimator.bestGpu();
			if(aBest != null && aBest[1] < aModel.cudaMemory) {
				return DepthEstimator.CPU_REASON_GPU_MEMORY;
			}
		}
		return null;
	}

	Model model;
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
		if(!installEnv() || !downloadModel() || canceled()) {
			return false;
		}
		File aDir = model.getDir(tdeDir);
		File aScript = copyScript(model.script, aDir);
		Vector<String> aCmd = pythonCommand(aScript);
		aCmd.add("--model-dir"); aCmd.add(aDir.getAbsolutePath());
		if(model.codeFiles != null) {
			aCmd.add("--code-dir"); aCmd.add(model.getCodeDir(tdeDir).getAbsolutePath());
		}
		aCmd.add("--prompt-file"); aCmd.add(aPromptFile.getAbsolutePath());
		aCmd.add("--seconds"); aCmd.add(String.format(Locale.ROOT, "%.1f", aSeconds));
		if(model.steps > 0) {
			aCmd.add("--steps"); aCmd.add(""+model.steps);
		}
		else if(SFX.equals(model.kind)) {
			aCmd.add("--steps"); aCmd.add(""+SFX_STEPS);
		}
		aCmd.add("--device"); aCmd.add(cpuReason(model) == null ? "auto" : "cpu");
		aCmd.add("--out"); aCmd.add(aOut.getAbsolutePath());
		String aDone = runScript(aCmd);
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
	 * Model files (resumed file by file)
	 * @return false if canceled
	 */
	boolean downloadModel() throws Exception {
		Vector<String[]> aFiles = new Vector<String[]>();
		for(Object[] aF : model.files) {
			aFiles.add(new String[] {HF+model.repo+"/resolve/main/"+aF[0], (String)aF[0], ""+aF[1]});
		}
		if(!downloadFiles(model.getDir(tdeDir), aFiles, "AI.DownloadingSoundModel")) {
			return false;
		}
		if(model.codeFiles == null) {
			return true;
		}
		aFiles.clear();
		for(Object[] aF : model.codeFiles) {
			aFiles.add(new String[] {model.codeUrl+aF[0], (String)aF[0], ""+aF[1]});
		}
		return downloadFiles(model.getCodeDir(tdeDir), aFiles, "AI.DownloadingSoundModel");
	}
}
