package com.cubaix.TDenlive.ai;

import java.io.File;
import java.util.Vector;

import com.cubaix.TDenlive.depth.DepthModel;
import com.cubaix.TDenlive.ffmpeg.Ffmpeg;

/**
 * Text to video with Wan2.2-TI2V-5B (https://huggingface.co/Wan-AI/Wan2.2-TI2V-5B, Apache-2.0),
 * run by the Python script res/ai/wan_generate.py (diffusers, PyTorch).
 * At the first use, the Python environment is installed (see PythonAI) and the model files are downloaded
 * into ~/3Denlive/Models/Wan2.2-TI2V-5B/: text encoder, tokenizer, VAE and configs of the diffusers
 * repository, and the transformer quantized in GGUF (QuantStack/Wan2.2-TI2V-5B-GGUF, Variant: Q8_0, Q6_K, Q4_K_M)
 * instead of its bf16 weights.
 * The script loads the models one at a time on the GPU (text encoder in 8 bits, then transformer, then VAE):
 * see GPU_MEMORY. A GPU is required: NVIDIA (CUDA) on Windows / Linux x64, or Apple Silicon (MPS).
 */
public class WanGenerator extends PythonAI {
	public static final String MODEL_NAME = "Wan2.2-TI2V-5B";
	static final String REPO = "Wan-AI/Wan2.2-TI2V-5B-Diffusers";
	static final String GGUF_REPO = "QuantStack/Wan2.2-TI2V-5B-GGUF";

	/**
	 * Quantization of the transformer (GGUF of QuantStack/Wan2.2-TI2V-5B-GGUF); the text encoder and the VAE are
	 * shared. The GPU memory needed goes down with the transformer stage only: the text encoder (8 bits, about
	 * 6.5 GB) and the VAE (about 6 GB) stages are the same for all of them.
	 */
	public static class Variant {
		public final String id;
		final String file;
		public final long size;
		public final long gpuMemory;

		Variant(String aId,long aSize,long aGpu) {
			id = aId;
			file = "Wan2.2-TI2V-5B-"+aId+".gguf";
			size = aSize;
			gpuMemory = aGpu;
		}

		public boolean isDownloaded(String aTdeDir) {
			return isComplete(new File(getModelDir(aTdeDir), file), size);
		}
	}

	public static final Variant Q8_0 = new Variant("Q8_0", 5400179040L, 10L*1024*1024*1024);
	public static final Variant Q6_K = new Variant("Q6_K", 4211683680L, 9L*1024*1024*1024);
	public static final Variant Q4_K_M = new Variant("Q4_K_M", 3433116000L, 8L*1024*1024*1024);
	public static final Variant[] VARIANTS = {Q8_0, Q6_K, Q4_K_M};

	public static Variant variantById(String aId) {
		for(Variant aV : VARIANTS) {
			if(aV.id.equalsIgnoreCase(aId)) {
				return aV;
			}
		}
		return null;
	}

	/**
	 * Variant advised for this computer: the best one that fits in the free GPU memory, else the smallest
	 */
	public static Variant defaultVariant() {
		//The model kept loaded is unloaded before Wan: its memory counts as free
		long aAvail = availableGpuMemory();
		if(aAvail < 0) {
			return Q8_0;
		}
		for(Variant aV : VARIANTS) {
			if(aV.gpuMemory <= aAvail) {
				return aV;
			}
		}
		return Q4_K_M;
	}
	//Files of the diffusers repository (not the bf16 transformer weights, replaced by the GGUF): path, size (0: any)
	static final Object[][] REPO_FILES = {
		{"model_index.json", 0L},
		{"scheduler/scheduler_config.json", 0L},
		{"text_encoder/config.json", 0L},
		{"text_encoder/model.safetensors.index.json", 0L},
		{"text_encoder/model-00001-of-00003.safetensors", 4935812536L},
		{"text_encoder/model-00002-of-00003.safetensors", 4983103192L},
		{"text_encoder/model-00003-of-00003.safetensors", 1442935480L},
		{"tokenizer/special_tokens_map.json", 0L},
		{"tokenizer/spiece.model", 4548313L},
		{"tokenizer/tokenizer.json", 16837459L},
		{"tokenizer/tokenizer_config.json", 0L},
		{"transformer/config.json", 0L},
		{"vae/config.json", 0L},
		{"vae/diffusion_pytorch_model.safetensors", 2818777808L},
	};
	/**
	 * GPU memory needed (bytes). Peak of each stage, one model at a time on the GPU (1280x704, 121 frames):
	 * - text encoder UMT5-XXL in 8 bits: about 6.5 GB (its 1 B parameters embedding stays in bf16);
	 * - transformer GGUF Q8_0: 5.4 GB of weights + about 2 GB of activations (27280 tokens, attention without
	 * the full matrix, conditional and unconditional passes one after the other);
	 * - VAE fp32 (2.8 GB) with tiled decoding: about 6 GB;
	 * plus the CUDA context (0.5 GB) and a margin. The script logs the real peaks (PEAK lines in 3De.log).
	 * Need of the Q8_0 variant (see Variant.gpuMemory).
	 */
	public static final long GPU_MEMORY = 10L*1024*1024*1024;
	public static final int FPS = 24;
	public static final int WIDTH = 1280;
	public static final int HEIGHT = 704;
	//Number of frames: 4n+1, at most 121 (5 s at 24 fps, the length the model was trained for)
	public static final int MAX_FRAMES = 121;
	public static final double MIN_SECONDS = 1;
	public static final double MAX_SECONDS = (MAX_FRAMES-1)/(double)FPS;
	public static final int STEPS = 50;

	Variant variant;

	public WanGenerator(String aTdeDir,Variant aVariant,Progress aProgress) {
		super(aTdeDir, aProgress);
		variant = aVariant;
	}

	/**
	 * A usable GPU: NVIDIA (nvidia-smi found) on Windows / Linux x64, or Apple Silicon
	 */
	public static boolean isPlatformSupported() {
		return hasGpu();
	}

	public static File getModelDir(String aTdeDir) {
		return new File(DepthModel.getModelsDir(aTdeDir), MODEL_NAME);
	}

	/**
	 * Bytes of model files still to download for aVariant (the shared files the first time)
	 */
	public static long modelBytesToDownload(String aTdeDir,Variant aVariant) {
		File aDir = getModelDir(aTdeDir);
		return bytesToDownload(aDir, REPO_FILES)+bytesToDownload(aDir, new Object[][] {{aVariant.file, aVariant.size}});
	}

	/**
	 * Size of the shared files (text encoder, VAE...)
	 */
	public static long sharedSize() {
		long aS = 0;
		for(Object[] aF : REPO_FILES) {
			aS += (Long)aF[1];
		}
		return aS;
	}

	/**
	 * Number of frames for a duration: 4n+1, between 4*FPS*MIN_SECONDS/4+1 and MAX_FRAMES
	 */
	public static int frames(double aSeconds) {
		int aN = (int)Math.round(aSeconds*FPS/4.0);
		return Math.max(1, Math.min((MAX_FRAMES-1)/4, aN))*4+1;
	}

	@Override
	String stepKey() {
		return "AI.Generating";
	}

	@Override
	boolean handleLine(String aL) {
		if(aL.startsWith("PREVIEW ")) {
			int aSp = aL.lastIndexOf(' ');
			if(progress != null) {
				progress.preview(aL.substring(8, aSp), Integer.parseInt(aL.substring(aSp+1)));
			}
			return true;
		}
		return false;
	}

	/**
	 * Install what is missing, then generate the video aOut (mp4, H.264, no sound).
	 * aPromptFile: the prompt (UTF-8). aImage (may be null): reference image, first frame of the video,
	 * animated according to the prompt (cropped to the ratio of aW x aH).
	 * @return false if canceled
	 */
	public boolean generate(File aPromptFile,File aImage,int aFrames,int aW,int aH,File aOut) throws Exception {
		if(!installEnv() || !downloadModel() || canceled()) {
			return false;
		}
		String aFfmpeg = Ffmpeg.ffmpegPath();
		if(aFfmpeg == null) {
			throw new Exception("ffmpeg not found");
		}
		File aDir = getModelDir(tdeDir);
		File aScript = copyScript("wan_generate.py", aDir);
		File aPreview = new File(aOut.getAbsoluteFile().getParentFile(), aOut.getName().replaceAll("[.]mp4$", "")+"_preview.png");
		Vector<String> aCmd = pythonCommand(aScript);
		aCmd.add("--model-dir"); aCmd.add(aDir.getAbsolutePath());
		aCmd.add("--gguf"); aCmd.add(new File(aDir, variant.file).getAbsolutePath());
		aCmd.add("--prompt-file"); aCmd.add(aPromptFile.getAbsolutePath());
		aCmd.add("--frames"); aCmd.add(""+aFrames);
		aCmd.add("--width"); aCmd.add(""+aW);
		aCmd.add("--height"); aCmd.add(""+aH);
		aCmd.add("--steps"); aCmd.add(""+STEPS);
		aCmd.add("--fps"); aCmd.add(""+FPS);
		if(aImage != null) {
			aCmd.add("--image"); aCmd.add(aImage.getAbsolutePath());
		}
		aCmd.add("--preview"); aCmd.add(aPreview.getAbsolutePath());
		aCmd.add("--ffmpeg"); aCmd.add(aFfmpeg);
		aCmd.add("--out"); aCmd.add(aOut.getAbsolutePath());
		String aDone;
		try {
			aDone = runScript(aCmd);
		}
		finally {
			aPreview.delete();
			new File(aPreview.getPath()+".tmp.png").delete();
		}
		if(aDone == null || canceled()) {
			aOut.delete();
			return false;
		}
		if(!aOut.isFile()) {
			throw new Exception("No video produced (see log/3De.log)");
		}
		return true;
	}

	/**
	 * Model files (resumed file by file)
	 * @return false if canceled
	 */
	boolean downloadModel() throws Exception {
		Vector<String[]> aFiles = new Vector<String[]>();
		for(Object[] aF : REPO_FILES) {
			aFiles.add(new String[] {HF+REPO+"/resolve/main/"+aF[0], (String)aF[0], ""+aF[1]});
		}
		aFiles.add(new String[] {HF+GGUF_REPO+"/resolve/main/"+variant.file, variant.file, ""+variant.size});
		return downloadFiles(getModelDir(tdeDir), aFiles, "AI.DownloadingModel");
	}
}
