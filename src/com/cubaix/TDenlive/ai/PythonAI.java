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

/**
 * Base of the generators run by a Python script of res/ai/ (WanGenerator, SoundGenerator).
 * They share one Python environment in ~/3Denlive/Models/Python/: at the first use, uv (Astral) is downloaded,
 * it installs a Python 3.12 and PyTorch (CUDA build when there is an NVIDIA GPU) with diffusers and transformers.
 * The scripts write their messages on stdout, one per line:
 * STATUS key (AI.* language key), STEP done total, PEAK stage bytes, DONE path, ERROR code;
 * the other lines go to handleLine() of the subclass, else to the log.
 */
public abstract class PythonAI {
	static final String HF = "https://huggingface.co/";
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
	 * A Python environment of Models/Python/: its folder, version (changed when the packages change: installed
	 * again) and packages. SHARED is that of most generators; a model whose code needs other versions of the
	 * packages has its own (SoundGenerator.Model.env).
	 */
	public static class Env {
		final String name;
		final String version;
		final String[] packages;
		//Added on an NVIDIA GPU
		final String[] cudaPackages;

		Env(String aName,String aVersion,String[] aPackages,String[] aCudaPackages) {
			name = aName;
			version = aVersion;
			packages = aPackages;
			cudaPackages = aCudaPackages;
		}
	}

	//Name of the first use (Wan), kept: the environment is shared by the generators. bitsandbytes: 8 bits text
	//encoder of Wan on the NVIDIA GPU
	public static final Env SHARED = new Env("wan-env", ENV_VERSION, PACKAGES, new String[] {"bitsandbytes"});

	public interface Progress {
		/** Step (AI.* language key), with a detail (may be null) and a progress aDone/aTotal (aTotal <= 0: none) */
		void status(String aKey,String aDetail,long aDone,long aTotal);
		/** New preview: latent frames side by side in a PNG */
		default void preview(String aPng,int aFrames) {
		}
		boolean isCanceled();
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

	String tdeDir;
	Progress progress;
	volatile Process process = null;
	//Peaks of GPU memory measured by the script (log)
	public String peaks = "";

	PythonAI(String aTdeDir,Progress aProgress) {
		tdeDir = aTdeDir;
		progress = aProgress;
	}

	static String os() {
		return System.getProperty("os.name").toLowerCase();
	}

	static boolean isMac() {
		return os().contains("mac");
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
	 * A usable GPU for PyTorch: NVIDIA (nvidia-smi found) on Windows / Linux x64, or Apple Silicon
	 */
	public static boolean hasGpu() {
		if(isMac()) {
			return isArm64();
		}
		return DepthEstimator.useGpuRuntime();
	}

	/**
	 * uv and recent PyTorch builds exist: Windows x64, Linux x64 / arm64, macOS Apple Silicon
	 * (no PyTorch for recent Python on Intel Macs)
	 */
	public static boolean hasPython() {
		if(isMac()) {
			return isArm64();
		}
		if(isWindows()) {
			return isX64();
		}
		return os().contains("linux") && (isX64() || isArm64());
	}

	static File getPythonDir(String aTdeDir) {
		return new File(DepthModel.getModelsDir(aTdeDir), "Python");
	}

	static File getEnvDir(String aTdeDir,Env aEnv) {
		return new File(getPythonDir(aTdeDir), aEnv.name);
	}

	static File getEnvMarker(String aTdeDir,Env aEnv) {
		return new File(getEnvDir(aTdeDir, aEnv), "3Denlive-env.txt");
	}

	static File getEnvPython(String aTdeDir,Env aEnv) {
		File aDir = getEnvDir(aTdeDir, aEnv);
		return isWindows() ? new File(aDir, "Scripts\\python.exe") : new File(new File(aDir, "bin"), "python");
	}

	public static boolean isEnvInstalled(String aTdeDir,Env aEnv) {
		try {
			File aM = getEnvMarker(aTdeDir, aEnv);
			return aM.isFile() && getEnvPython(aTdeDir, aEnv).isFile()
					&& aEnv.version.equals(new String(Files.readAllBytes(aM.toPath()), StandardCharsets.UTF_8).trim());
		}
		catch(Throwable t) {
			return false;
		}
	}

	/**
	 * Disk size of the Python environment to install (0 if installed)
	 */
	public static long envBytesToInstall(String aTdeDir,Env aEnv) {
		if(isEnvInstalled(aTdeDir, aEnv)) {
			return 0;
		}
		return DepthEstimator.useGpuRuntime() ? ENV_SIZE_CUDA : ENV_SIZE;
	}

	public static long envBytesToInstall(String aTdeDir) {
		return envBytesToInstall(aTdeDir, SHARED);
	}

	/**
	 * Environment of this generator (SHARED by default)
	 */
	Env env() {
		return SHARED;
	}

	static boolean isComplete(File aF,long aSize) {
		return aF.isFile() && (aSize <= 0 || aF.length() == aSize);
	}

	/**
	 * Bytes still to download among aFiles (path relative to aDir, size; 0: any) in aDir
	 */
	static long bytesToDownload(File aDir,Object[][] aFiles) {
		long aBytes = 0;
		for(Object[] aF : aFiles) {
			long aSize = (Long)aF[1];
			if(!isComplete(new File(aDir, ((String)aF[0]).replace('/', File.separatorChar)), aSize)) {
				aBytes += aSize;
			}
		}
		return aBytes;
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
	 * Copy the script aName of res/ai/ into aDir (at each run: the version of this release)
	 */
	static File copyScript(String aName,File aDir) throws Exception {
		aDir.mkdirs();
		File aScript = new File(aDir, aName);
		try(InputStream aIS = PythonAI.class.getResourceAsStream("/com/cubaix/TDenlive/res/ai/"+aName)) {
			Files.copy(aIS, aScript.toPath(), StandardCopyOption.REPLACE_EXISTING);
		}
		return aScript;
	}

	/**
	 * Command starting a script with the Python of the environment
	 */
	Vector<String> pythonCommand(File aScript) {
		Vector<String> aCmd = new Vector<String>();
		aCmd.add(getEnvPython(tdeDir, env()).getAbsolutePath());
		aCmd.add("-u");
		aCmd.add("-s");
		aCmd.add(aScript.getAbsolutePath());
		return aCmd;
	}

	/**
	 * Key of the STEP messages (AI.* language key)
	 */
	abstract String stepKey();

	/**
	 * Message of the script not handled by runScript()
	 * @return false to log it
	 */
	boolean handleLine(String aL) {
		return false;
	}

	/**
	 * Run a script with the common protocol (offline, on the NVIDIA GPU with the most free memory)
	 * @return the path of the DONE message, null if canceled
	 */
	String runScript(Vector<String> aCmd) throws Exception {
		ProcessBuilder aPB = scriptProcess(aCmd);
		String[] aResult = {null, null};
		runProcess(aPB, aL -> {
			if(aL.startsWith("DONE ")) {
				aResult[0] = aL.substring(5).trim();
			}
			else if(aL.startsWith("ERROR ")) {
				aResult[1] = aL.substring(6).trim();
			}
			else {
				commonLine(aL);
			}
		});
		if(canceled()) {
			return null;
		}
		if(aResult[1] != null) {
			throw new GenerationException(aResult[1]);
		}
		if(aResult[0] == null) {
			throw new Exception("Nothing produced (see log/3De.log)");
		}
		return aResult[0];
	}

	/**
	 * Process of a script: offline, on the NVIDIA GPU with the most free memory
	 */
	ProcessBuilder scriptProcess(Vector<String> aCmd) {
		ProcessBuilder aPB = new ProcessBuilder(aCmd);
		Map<String,String> aEnv = aPB.environment();
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
		return aPB;
	}

	/**
	 * STATUS, STEP, PEAK and the other lines (handleLine(), else the log)
	 */
	void commonLine(String aL) {
		if(aL.startsWith("STATUS ")) {
			status("AI."+aL.substring(7).trim(), null, 0, 0);
		}
		else if(aL.startsWith("STEP ")) {
			String[] aS = aL.split(" ");
			status(stepKey(), null, Long.parseLong(aS[1]), Long.parseLong(aS[2]));
		}
		else if(aL.startsWith("PEAK ")) {
			String[] aS = aL.split(" ");
			String aPeak = aS[1]+" "+DepthModel.gb(Long.parseLong(aS[2]))+" GB";
			peaks += (peaks.isEmpty() ? "" : ", ")+aPeak;
			System.out.println("AI GPU memory peak: "+aPeak);
		}
		else if(!handleLine(aL)) {
			System.out.println("AI: "+aL);
		}
	}

	/**
	 * Run a process: stdout lines to aHandler, stderr to the log; killed when canceled
	 * @return the exit code
	 */
	int runProcess(ProcessBuilder aPB,LineHandler aHandler) throws Exception {
		Process aP = process = aPB.start();
		//The application quits: the script (and its models in memory) stops too
		Thread aHook = new Thread(() -> kill(aP));
		Runtime.getRuntime().addShutdownHook(aHook);
		try {
			return runProcess(aP, aHandler);
		}
		finally {
			try {
				Runtime.getRuntime().removeShutdownHook(aHook);
			}
			catch(Throwable t) {
				//Already quitting
			}
		}
	}

	int runProcess(Process aP,LineHandler aHandler) throws Exception {
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
		if(!aP.isAlive()) {
			return;
		}
		long aT0 = System.currentTimeMillis();
		java.util.List<ProcessHandle> aKids = aP.descendants().collect(java.util.stream.Collectors.toList());
		aKids.forEach(ProcessHandle::destroyForcibly);
		aP.destroyForcibly();
		System.out.println("AI: process "+aP.pid()+" killed ("+aKids.size()+" child processes)");
		//Its real end, logged (a process killed during a long GPU operation ends with it)
		Thread aW = new Thread(() -> {
			try {
				boolean aEnded = aP.waitFor(60, java.util.concurrent.TimeUnit.SECONDS);
				for(ProcessHandle aK : aKids) {
					aK.onExit().get(5, java.util.concurrent.TimeUnit.SECONDS);
				}
				System.out.println("AI: process "+aP.pid()+(aEnded ? " ended "+(System.currentTimeMillis()-aT0)+" ms after the kill" : " still running 60 s after the kill"));
			}
			catch(Throwable t) {
				System.out.println("AI: process "+aP.pid()+" end not confirmed: "+t);
			}
		}, "AI kill watch");
		aW.setDaemon(true);
		aW.start();
	}

	/**
	 * Its script is running (or still ending after a kill)
	 */
	public boolean isRunning() {
		Process aP = process;
		return aP != null && aP.isAlive();
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
		Env aE = env();
		if(isEnvInstalled(tdeDir, aE)) {
			return true;
		}
		File aPyDir = getPythonDir(tdeDir);
		File aUv = installUv(aPyDir);
		if(aUv == null) {
			return false;
		}
		File aEnv = getEnvDir(tdeDir, aE);
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
		aCmd.add(getEnvPython(tdeDir, aE).getAbsolutePath());
		//PyTorch built for the CUDA version of the NVIDIA driver, else the standard one (MPS on macOS, CPU)
		aCmd.add("--torch-backend=auto");
		for(String aPkg : aE.packages) {
			aCmd.add(aPkg);
		}
		if(DepthEstimator.useGpuRuntime()) {
			for(String aPkg : aE.cudaPackages) {
				aCmd.add(aPkg);
			}
		}
		aPB = new ProcessBuilder(aCmd);
		uvEnv(aPB, aPyDir);
		if(runInstallStep(aPB) != 0 && !canceled()) {
			throw new Exception("Installation of PyTorch / diffusers failed (see log/3De.log)");
		}
		if(canceled()) {
			return false;
		}
		Files.write(getEnvMarker(tdeDir, aE).toPath(), aE.version.getBytes(StandardCharsets.UTF_8));
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
		if(isMac()) {
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
	 * Download the missing files (resumed file by file) into aDir.
	 * aFiles: {url, path relative to aDir, size (0: any)}
	 * @return false if canceled
	 */
	boolean downloadFiles(File aDir,Vector<String[]> aFiles,String aKey) throws Exception {
		long aTotal = 0;
		for(String[] aF : aFiles) {
			long aSize = Long.parseLong(aF[2]);
			if(!isComplete(new File(aDir, aF[1].replace('/', File.separatorChar)), aSize)) {
				aTotal += aSize;
			}
		}
		final long aAll = aTotal;
		long[] aBefore = {0};
		for(String[] aF : aFiles) {
			File aFile = new File(aDir, aF[1].replace('/', File.separatorChar));
			long aSize = Long.parseLong(aF[2]);
			if(isComplete(aFile, aSize)) {
				continue;
			}
			boolean aOk = Downloader.download(aF[0], aFile, aSize, new Downloader.Progress() {
				@Override
				public void downloaded(long aDone, long aFileTotal) {
					status(aKey, aF[1], aBefore[0]+aDone, Math.max(aAll, aBefore[0]+aFileTotal));
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
