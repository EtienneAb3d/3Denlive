package com.cubaix.TDenlive.ai;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.Map;
import java.util.Vector;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import com.cubaix.TDenlive.depth.DepthEstimator;
import com.cubaix.TDenlive.depth.DepthModel;
import com.cubaix.TDenlive.depth.Downloader;
import com.cubaix.TDenlive.ffmpeg.Ffmpeg;

/**
 * Text to video with Wan2.2-TI2V-5B (https://huggingface.co/Wan-AI/Wan2.2-TI2V-5B, Apache-2.0),
 * run by the Python script res/ai/wan_generate.py (diffusers, PyTorch).
 * At the first use, everything is downloaded into ~/3Denlive/Models/:
 * - uv (Astral), which installs a Python 3.12 and PyTorch (CUDA build when there is an NVIDIA GPU)
 * with diffusers into Models/Python/;
 * - the model files into Models/Wan2.2-TI2V-5B/: text encoder, tokenizer, VAE and configs of the diffusers
 * repository, and the transformer quantized in GGUF Q8_0 (QuantStack/Wan2.2-TI2V-5B-GGUF) instead of its bf16 weights.
 * The script loads the models one at a time on the GPU (text encoder in 8 bits, then transformer, then VAE):
 * see GPU_MEMORY. A GPU is required: NVIDIA (CUDA) on Windows / Linux x64, or Apple Silicon (MPS).
 */
public class WanGenerator {
	public static final String MODEL_NAME = "Wan2.2-TI2V-5B";
	static final String HF = "https://huggingface.co/";
	static final String REPO = "Wan-AI/Wan2.2-TI2V-5B-Diffusers";
	static final String GGUF_REPO = "QuantStack/Wan2.2-TI2V-5B-GGUF";
	static final String GGUF_FILE = "Wan2.2-TI2V-5B-Q8_0.gguf";
	static final long GGUF_SIZE = 5400179040L;
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
	//Python environment: uv release, Python version, packages
	static final String UV_VERSION = "0.13.7";
	static final String PYTHON_VERSION = "3.12";
	//Changed when the packages change: the environment is installed again
	static final String ENV_VERSION = "1";
	static final String[] PACKAGES = {"torch", "diffusers>=0.35.0", "transformers", "accelerate", "gguf"
			, "sentencepiece", "protobuf", "ftfy", "pillow", "numpy"};
	//Size of the Python environment once installed (PyTorch CUDA with its libraries, else without), for the user
	static final long ENV_SIZE_CUDA = 6L*1024*1024*1024;
	static final long ENV_SIZE = 1536L*1024*1024;

	/**
	 * GPU memory needed (bytes). Peak of each stage, one model at a time on the GPU (1280x704, 121 frames):
	 * - text encoder UMT5-XXL in 8 bits: about 6.5 GB (its 1 B parameters embedding stays in bf16);
	 * - transformer GGUF Q8_0: 5.4 GB of weights + about 2 GB of activations (27280 tokens, attention without
	 * the full matrix, conditional and unconditional passes one after the other);
	 * - VAE fp32 (2.8 GB) with tiled decoding: about 6 GB;
	 * plus the CUDA context (0.5 GB) and a margin. The script logs the real peaks (PEAK lines in 3De.log).
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

	public interface Progress {
		/** Step (AI.* language key), with a detail (may be null) and a progress aDone/aTotal (aTotal <= 0: none) */
		void status(String aKey,String aDetail,long aDone,long aTotal);
		/** New preview: latent frames side by side in a PNG */
		void preview(String aPng,int aFrames);
		boolean isCanceled();
	}

	String tdeDir;
	Progress progress;
	volatile Process process = null;
	//Peaks of GPU memory measured by the script (log)
	public String peaks = "";

	public WanGenerator(String aTdeDir,Progress aProgress) {
		tdeDir = aTdeDir;
		progress = aProgress;
	}

	static String os() {
		return System.getProperty("os.name").toLowerCase();
	}

	static boolean isArm64() {
		String aArch = System.getProperty("os.arch").toLowerCase();
		return aArch.equals("aarch64") || aArch.equals("arm64");
	}

	static boolean isX64() {
		String aArch = System.getProperty("os.arch").toLowerCase();
		return aArch.equals("amd64") || aArch.equals("x86_64");
	}

	static boolean isWindows() {
		return os().contains("windows");
	}

	/**
	 * A usable GPU: NVIDIA (nvidia-smi found) on Windows / Linux x64, or Apple Silicon
	 */
	public static boolean isPlatformSupported() {
		if(os().contains("mac")) {
			return isArm64();
		}
		return DepthEstimator.useGpuRuntime();
	}

	public static File getModelDir(String aTdeDir) {
		return new File(DepthModel.getModelsDir(aTdeDir), MODEL_NAME);
	}

	static File getPythonDir(String aTdeDir) {
		return new File(DepthModel.getModelsDir(aTdeDir), "Python");
	}

	static File getEnvDir(String aTdeDir) {
		return new File(getPythonDir(aTdeDir), "wan-env");
	}

	static File getEnvMarker(String aTdeDir) {
		return new File(getEnvDir(aTdeDir), "3Denlive-env.txt");
	}

	static File getEnvPython(String aTdeDir) {
		File aEnv = getEnvDir(aTdeDir);
		return isWindows() ? new File(aEnv, "Scripts\\python.exe") : new File(new File(aEnv, "bin"), "python");
	}

	public static boolean isEnvInstalled(String aTdeDir) {
		try {
			File aM = getEnvMarker(aTdeDir);
			return aM.isFile() && getEnvPython(aTdeDir).isFile()
					&& ENV_VERSION.equals(new String(Files.readAllBytes(aM.toPath()), StandardCharsets.UTF_8).trim());
		}
		catch(Throwable t) {
			return false;
		}
	}

	static boolean isComplete(File aF,long aSize) {
		return aF.isFile() && (aSize <= 0 || aF.length() == aSize);
	}

	/**
	 * Bytes of model files still to download
	 */
	public static long modelBytesToDownload(String aTdeDir) {
		File aDir = getModelDir(aTdeDir);
		long aBytes = 0;
		for(Object[] aF : REPO_FILES) {
			long aSize = (Long)aF[1];
			if(!isComplete(new File(aDir, (String)aF[0]), aSize)) {
				aBytes += aSize;
			}
		}
		if(!isComplete(new File(aDir, GGUF_FILE), GGUF_SIZE)) {
			aBytes += GGUF_SIZE;
		}
		return aBytes;
	}

	/**
	 * Disk size of the Python environment to install (0 if installed)
	 */
	public static long envBytesToInstall(String aTdeDir) {
		if(isEnvInstalled(aTdeDir)) {
			return 0;
		}
		return DepthEstimator.useGpuRuntime() ? ENV_SIZE_CUDA : ENV_SIZE;
	}

	/**
	 * Number of frames for a duration: 4n+1, between 4*FPS*MIN_SECONDS/4+1 and MAX_FRAMES
	 */
	public static int frames(double aSeconds) {
		int aN = (int)Math.round(aSeconds*FPS/4.0);
		return Math.max(1, Math.min((MAX_FRAMES-1)/4, aN))*4+1;
	}

	boolean canceled() {
		return progress != null && progress.isCanceled();
	}

	void status(String aKey,String aDetail,long aDone,long aTotal) {
		if(progress != null) {
			progress.status(aKey, aDetail, aDone, aTotal);
		}
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
		File aScript = new File(aDir, "wan_generate.py");
		//Copied at each run: the version of this release
		try(InputStream aIS = WanGenerator.class.getResourceAsStream("/com/cubaix/TDenlive/res/ai/wan_generate.py")) {
			Files.copy(aIS, aScript.toPath(), StandardCopyOption.REPLACE_EXISTING);
		}
		File aPreview = new File(aOut.getAbsoluteFile().getParentFile(), aOut.getName().replaceAll("[.]mp4$", "")+"_preview.png");
		Vector<String> aCmd = new Vector<String>();
		aCmd.add(getEnvPython(tdeDir).getAbsolutePath());
		aCmd.add("-u");
		aCmd.add("-s");
		aCmd.add(aScript.getAbsolutePath());
		aCmd.add("--model-dir"); aCmd.add(aDir.getAbsolutePath());
		aCmd.add("--gguf"); aCmd.add(new File(aDir, GGUF_FILE).getAbsolutePath());
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
		ProcessBuilder aPB = new ProcessBuilder(aCmd);
		Map<String,String> aEnv = aPB.environment();
		//The NVIDIA GPU with the most free memory
		long[] aBest = DepthEstimator.bestGpu();
		if(DepthEstimator.useGpuRuntime() && aBest != null) {
			aEnv.put("CUDA_DEVICE_ORDER", "PCI_BUS_ID");
			aEnv.put("CUDA_VISIBLE_DEVICES", ""+aBest[0]);
		}
		aEnv.put("HF_HOME", new File(getPythonDir(tdeDir), "hf").getAbsolutePath());
		aEnv.put("HF_HUB_OFFLINE", "1");
		aEnv.put("PYTHONIOENCODING", "utf-8");
		//Apple Silicon: operations missing on MPS run on the CPU
		aEnv.put("PYTORCH_ENABLE_MPS_FALLBACK", "1");
		System.out.println("AI: "+String.join(" ", aCmd));
		String[] aResult = {null, null};
		try {
			runProcess(aPB, aL -> {
				if(aL.startsWith("STATUS ")) {
					status("AI."+aL.substring(7).trim(), null, 0, 0);
				}
				else if(aL.startsWith("STEP ")) {
					String[] aS = aL.split(" ");
					status("AI.Generating", null, Long.parseLong(aS[1]), Long.parseLong(aS[2]));
				}
				else if(aL.startsWith("PREVIEW ")) {
					int aSp = aL.lastIndexOf(' ');
					if(progress != null) {
						progress.preview(aL.substring(8, aSp), Integer.parseInt(aL.substring(aSp+1)));
					}
				}
				else if(aL.startsWith("PEAK ")) {
					String[] aS = aL.split(" ");
					String aPeak = aS[1]+" "+DepthModel.gb(Long.parseLong(aS[2]))+" GB";
					peaks += (peaks.isEmpty() ? "" : ", ")+aPeak;
					System.out.println("AI GPU memory peak: "+aPeak);
				}
				else if(aL.startsWith("DONE ")) {
					aResult[0] = aL.substring(5).trim();
				}
				else if(aL.startsWith("ERROR ")) {
					aResult[1] = aL.substring(6).trim();
				}
				else {
					System.out.println("AI: "+aL);
				}
			});
		}
		finally {
			aPreview.delete();
			new File(aPreview.getPath()+".tmp.png").delete();
		}
		if(canceled()) {
			aOut.delete();
			return false;
		}
		if(aResult[1] != null) {
			throw new GenerationException(aResult[1]);
		}
		if(aResult[0] == null || !aOut.isFile()) {
			throw new Exception("No video produced (see log/3De.log)");
		}
		return true;
	}

	/**
	 * Error reported by the script: OOM (GPU memory), NOGPU, or a message
	 */
	public static class GenerationException extends Exception {
		private static final long serialVersionUID = 1L;
		public final String code;
		GenerationException(String aCode) {
			super(aCode);
			code = aCode;
		}
	}

	interface LineHandler {
		void line(String aL) throws Exception;
	}

	/**
	 * Run a process: stdout lines to aHandler, stderr to the log; killed when canceled
	 * @return the exit code
	 */
	int runProcess(ProcessBuilder aPB,LineHandler aHandler) throws Exception {
		Process aP = process = aPB.start();
		Thread aErr = new Thread(() -> {
			try(BufferedReader aR = new BufferedReader(new InputStreamReader(aP.getErrorStream(), StandardCharsets.UTF_8))) {
				String aL;
				while((aL = aR.readLine()) != null) {
					System.err.println("[AI] "+aL);
				}
			}
			catch(Throwable t) {
			}
		});
		aErr.setDaemon(true);
		aErr.start();
		Thread aWatch = new Thread(() -> {
			while(aP.isAlive()) {
				if(canceled()) {
					kill(aP);
					return;
				}
				try {
					Thread.sleep(300);
				}
				catch(InterruptedException e) {
					return;
				}
			}
		});
		aWatch.setDaemon(true);
		aWatch.start();
		try(BufferedReader aR = new BufferedReader(new InputStreamReader(aP.getInputStream(), StandardCharsets.UTF_8))) {
			String aL;
			while((aL = aR.readLine()) != null) {
				try {
					aHandler.line(aL);
				}
				catch(Throwable t) {
					t.printStackTrace(System.err);
				}
			}
		}
		int aCode = aP.waitFor();
		aErr.join(2000);
		process = null;
		return aCode;
	}

	static void kill(Process aP) {
		aP.descendants().forEach(ProcessHandle::destroyForcibly);
		aP.destroyForcibly();
	}

	/**
	 * Stop a running generation (the process and its children)
	 */
	public void cancel() {
		Process aP = process;
		if(aP != null) {
			kill(aP);
		}
	}

	/**
	 * uv, then Python and the packages, in Models/Python/
	 * @return false if canceled
	 */
	boolean installEnv() throws Exception {
		if(isEnvInstalled(tdeDir)) {
			return true;
		}
		File aPyDir = getPythonDir(tdeDir);
		File aUv = installUv(aPyDir);
		if(aUv == null) {
			return false;
		}
		File aEnv = getEnvDir(tdeDir);
		status("AI.InstallingPython", null, 0, 0);
		ProcessBuilder aPB = new ProcessBuilder(aUv.getAbsolutePath(), "venv", "--clear", "--python", PYTHON_VERSION, aEnv.getAbsolutePath());
		uvEnv(aPB, aPyDir);
		if(runInstallStep(aPB) != 0 && !canceled()) {
			throw new Exception("Python installation failed (see log/3De.log)");
		}
		if(canceled()) {
			return false;
		}
		status("AI.InstallingPackages", null, 0, 0);
		Vector<String> aCmd = new Vector<String>();
		aCmd.add(aUv.getAbsolutePath());
		aCmd.add("pip");
		aCmd.add("install");
		aCmd.add("--python");
		aCmd.add(getEnvPython(tdeDir).getAbsolutePath());
		//PyTorch built for the CUDA version of the NVIDIA driver, else the standard one (MPS on macOS)
		aCmd.add("--torch-backend=auto");
		for(String aPkg : PACKAGES) {
			aCmd.add(aPkg);
		}
		if(DepthEstimator.useGpuRuntime()) {
			//8 bits text encoder on the NVIDIA GPU
			aCmd.add("bitsandbytes");
		}
		aPB = new ProcessBuilder(aCmd);
		uvEnv(aPB, aPyDir);
		if(runInstallStep(aPB) != 0 && !canceled()) {
			throw new Exception("Installation of PyTorch / diffusers failed (see log/3De.log)");
		}
		if(canceled()) {
			return false;
		}
		Files.write(getEnvMarker(tdeDir).toPath(), ENV_VERSION.getBytes(StandardCharsets.UTF_8));
		return true;
	}

	static void uvEnv(ProcessBuilder aPB,File aPyDir) {
		Map<String,String> aEnv = aPB.environment();
		aEnv.put("UV_PYTHON_INSTALL_DIR", new File(aPyDir, "python").getAbsolutePath());
		aEnv.put("UV_CACHE_DIR", new File(aPyDir, "uv-cache").getAbsolutePath());
		aEnv.put("UV_NO_PROGRESS", "1");
		aEnv.put("UV_PYTHON_PREFERENCE", "only-managed");
		aEnv.put("UV_LINK_MODE", "copy");
	}

	/**
	 * Install command: its output shown as detail, stderr merged
	 */
	int runInstallStep(ProcessBuilder aPB) throws Exception {
		aPB.redirectErrorStream(true);
		String aKey = aPB.command().contains("venv") ? "AI.InstallingPython" : "AI.InstallingPackages";
		System.out.println("AI: "+String.join(" ", aPB.command()));
		return runProcess(aPB, aL -> {
			System.out.println("[uv] "+aL);
			String aT = aL.trim();
			if(!aT.isEmpty()) {
				status(aKey, aT.length() > 80 ? aT.substring(0, 80)+"..." : aT, 0, 0);
			}
		});
	}

	/**
	 * Download and unpack uv (single executable)
	 * @return the executable, null if canceled
	 */
	File installUv(File aPyDir) throws Exception {
		File aUvDir = new File(aPyDir, "uv-"+UV_VERSION);
		File aExe = new File(aUvDir, isWindows() ? "uv.exe" : "uv");
		if(aExe.isFile()) {
			return aExe;
		}
		String aTarget;
		if(os().contains("mac")) {
			aTarget = isArm64() ? "aarch64-apple-darwin" : "x86_64-apple-darwin";
		}
		else if(isWindows()) {
			aTarget = "x86_64-pc-windows-msvc";
		}
		else {
			aTarget = (isArm64() ? "aarch64" : "x86_64")+"-unknown-linux-gnu";
		}
		String aName = "uv-"+aTarget+(isWindows() ? ".zip" : ".tar.gz");
		File aArchive = new File(aPyDir, aName);
		boolean aOk = Downloader.download("https://github.com/astral-sh/uv/releases/download/"+UV_VERSION+"/"+aName, aArchive, 0, new Downloader.Progress() {
			@Override
			public void downloaded(long aDone, long aTotal) {
				status("AI.DownloadingUv", null, aDone, aTotal);
			}
			@Override
			public boolean isCanceled() {
				return canceled();
			}
		});
		if(!aOk) {
			return null;
		}
		File aTmp = new File(aPyDir, "uv-unpack");
		deleteTree(aTmp);
		aTmp.mkdirs();
		if(isWindows()) {
			try(ZipInputStream aZ = new ZipInputStream(Files.newInputStream(aArchive.toPath()))) {
				ZipEntry aE;
				while((aE = aZ.getNextEntry()) != null) {
					File aF = new File(aTmp, aE.getName()).getCanonicalFile();
					if(!aF.getPath().startsWith(aTmp.getCanonicalPath()) || aE.isDirectory()) {
						continue;
					}
					aF.getParentFile().mkdirs();
					Files.copy(aZ, aF.toPath(), StandardCopyOption.REPLACE_EXISTING);
				}
			}
		}
		else {
			Process aP = new ProcessBuilder("tar", "-xzf", aArchive.getAbsolutePath(), "-C", aTmp.getAbsolutePath())
					.redirectErrorStream(true).start();
			System.out.println(new String(aP.getInputStream().readAllBytes()));
			if(aP.waitFor() != 0) {
				throw new Exception("uv: unpacking failed");
			}
		}
		File aFound = find(aTmp, aExe.getName());
		if(aFound == null) {
			throw new Exception("uv: executable not found in "+aName);
		}
		aUvDir.mkdirs();
		Files.move(aFound.toPath(), aExe.toPath(), StandardCopyOption.REPLACE_EXISTING);
		aExe.setExecutable(true);
		deleteTree(aTmp);
		aArchive.delete();
		return aExe;
	}

	static File find(File aDir,String aName) {
		File[] aFs = aDir.listFiles();
		if(aFs == null) {
			return null;
		}
		for(File aF : aFs) {
			if(aF.isFile() && aF.getName().equals(aName)) {
				return aF;
			}
		}
		for(File aF : aFs) {
			if(aF.isDirectory()) {
				File aR = find(aF, aName);
				if(aR != null) {
					return aR;
				}
			}
		}
		return null;
	}

	static void deleteTree(File aF) {
		File[] aFs = aF.listFiles();
		if(aFs != null) {
			for(File aC : aFs) {
				deleteTree(aC);
			}
		}
		aF.delete();
	}

	/**
	 * Model files (resumed file by file)
	 * @return false if canceled
	 */
	boolean downloadModel() throws Exception {
		File aDir = getModelDir(tdeDir);
		long aTotal = modelBytesToDownload(tdeDir);
		long[] aBefore = {0};
		Vector<String[]> aFiles = new Vector<String[]>();
		for(Object[] aF : REPO_FILES) {
			aFiles.add(new String[] {HF+REPO+"/resolve/main/"+aF[0], (String)aF[0], ""+aF[1]});
		}
		aFiles.add(new String[] {HF+GGUF_REPO+"/resolve/main/"+GGUF_FILE, GGUF_FILE, ""+GGUF_SIZE});
		for(String[] aF : aFiles) {
			File aFile = new File(aDir, aF[1].replace('/', File.separatorChar));
			long aSize = Long.parseLong(aF[2]);
			if(isComplete(aFile, aSize)) {
				continue;
			}
			boolean aOk = Downloader.download(aF[0], aFile, aSize, new Downloader.Progress() {
				@Override
				public void downloaded(long aDone, long aFileTotal) {
					status("AI.DownloadingModel", aF[1], aBefore[0]+aDone, Math.max(aTotal, aBefore[0]+aFileTotal));
				}
				@Override
				public boolean isCanceled() {
					return canceled();
				}
			});
			if(!aOk) {
				return false;
			}
			aBefore[0] += aSize;
		}
		return true;
	}
}
