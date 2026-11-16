package com.cubaix.TDenlive.depth;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.PrintStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Vector;

import com.cubaix.TDenlive.ai.PythonAI;

/**
 * Filling of the holes uncovered by the 2D/stereo + depth map conversions (Depthmap, DepthmapStereo) with an
 * inpainting model (ENGINE), run by ONNX Runtime like DepthEstimator (same jar, same GPU choice: CUDA, WebGPU,
 * else the CPU), in a child JVM (main() of this class):
 * - LIGHT (default): light_inpaint_v1 of iw3 (nunif of nagadomi, MIT; ONNX export embedded in the jar,
 * res/ai/iw3_light_inpaint_v1.onnx, copied into the Models folder at the first use: inputs "image" 1x3xHxW RGB 0..1
 * and "mask" 1x1xHxW, 1 = hole, H and W multiples of 64 (the child pads the tiles by repeating their edges),
 * output 1x3xHxW RGB 0..1). Trained on the holes of the right view of a depth warp (foreground on the left of
 * the hole, background on its right): an image whose holes have their foreground on the right is mirrored
 * (fill(..., aFlip)). Small (2.3 M parameters), it runs on the CPU too: about 0.7 s per million pixels on
 * Apple Silicon (ONNX Runtime CPU), peak 0.5 GB per tile of 512;
 * - LAMA (previous engine, kept to go back to it: ENGINE): LaMa (big-lama, Apache-2.0; ONNX export of
 * Carve/LaMa-ONNX, downloaded: inputs 1x3x512x512 RGB 0..1 and 1x1x512x512, output RGB 0..255), only on a GPU
 * (about 0.6 s per tile on Apple Silicon with WebGPU, peak 1.5 GB; 2 s per tile on the CPU: too slow).
 * The image (already reduced to the work resolution by processors.Inpainting) is cut into tiles of TILE (only
 * those with holes are computed, the tiles overlap and are blended; LaMa: each tile resampled to 512 x 512,
 * LIGHT: at its size), and only the hole pixels take the result.
 * One instance is shared by all the processors (shared()), kept loaded like the AI models of the application
 * (PythonAI.Resident: unloaded when another model has to be loaded).
 */
public class Inpainter implements PythonAI.Resident, AutoCloseable {
	public enum Engine {
		//Model file (Models folder), download URL (null: embedded in res/ai), size, GPU memory needed (LIGHT:
		//peak 0.5 GB measured on the CPU for a 512 tile, with a margin; LaMa: 1.5 GB measured with WebGPU, with a
		//margin), name shown, GPU only, each tile resampled to TILE x TILE, scale of the output
		LIGHT("iw3_light_inpaint_v1.onnx", null, 9552707L, 1024L*1024*1024, "light_inpaint_v1 (iw3)", false, false, 1f),
		LAMA("lama-inpainting-fp32.onnx", "https://huggingface.co/Carve/LaMa-ONNX/resolve/main/lama_fp32.onnx"
				, 208044816L, 2048L*1024*1024, "LaMa", true, true, 1f/255);

		public final String fileName;
		public final String url;
		public final long size;
		public final long gpuMemory;
		public final String label;
		public final boolean gpuOnly;
		final boolean resamples;
		final float outScale;

		Engine(String aFileName,String aUrl,long aSize,long aGpuMemory,String aLabel,boolean aGpuOnly,boolean aResamples,float aOutScale) {
			fileName = aFileName;
			url = aUrl;
			size = aSize;
			gpuMemory = aGpuMemory;
			label = aLabel;
			gpuOnly = aGpuOnly;
			resamples = aResamples;
			outScale = aOutScale;
		}
	}
	//Engine used (LAMA: the previous one)
	public static final Engine ENGINE = Engine.LIGHT;
	//Side of the tiles (LaMa: its input)
	static final int TILE = 512;
	//Minimum overlap of the tiles (blended)
	static final int OVERLAP = 64;
	//LIGHT: sides of its input multiple of
	static final int MOD = 64;
	static final String KEY = "inpaint "+ENGINE.name().toLowerCase();
	public static final String TAG = "inpaint";

	static final Object LOCK = new Object();
	static Inpainter shared = null;
	//Last failure to start (not tried again at each image)
	static String lastError = null;
	static long lastErrorMS = 0;
	static final long RETRY_MS = 60000;

	//Child process (parent side)
	Process worker = null;
	DataInputStream workerIn = null;
	DataOutputStream workerOut = null;
	Thread workerHook = null;
	//Where the model runs (state sent by the child)
	public String provider = "CPU";
	public String cpuReason = null;
	public long gpuFree = -1;

	//Session (child side)
	String tdeDir = null;
	Class<?> envC = null;
	Object env = null;
	Object session = null;

	public static File getFile(String aTdeDir) {
		return new File(DepthModel.getModelsDir(aTdeDir), ENGINE.fileName);
	}

	/**
	 * The model file is there (embedded model: copied from the jar if missing)
	 */
	public static boolean isDownloaded(String aTdeDir) {
		File aF = getFile(aTdeDir);
		if(aF.isFile() && aF.length() == ENGINE.size) {
			return true;
		}
		return ENGINE.url == null && extract(aTdeDir);
	}

	/**
	 * Copy of the embedded model into the Models folder
	 * @return true if done
	 */
	static synchronized boolean extract(String aTdeDir) {
		File aF = getFile(aTdeDir);
		if(aF.isFile() && aF.length() == ENGINE.size) {
			return true;
		}
		File aTmp = new File(aF.getParentFile(), aF.getName()+".part");
		try(java.io.InputStream aIS = Inpainter.class.getResourceAsStream("/com/cubaix/TDenlive/res/ai/"+ENGINE.fileName)) {
			if(aIS == null) {
				System.err.println("Inpainting: embedded model missing: "+ENGINE.fileName);
				return false;
			}
			aF.getParentFile().mkdirs();
			java.nio.file.Files.copy(aIS, aTmp.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
			if(aTmp.length() != ENGINE.size) {
				System.err.println("Inpainting: embedded model of an unexpected size: "+aTmp.length());
				aTmp.delete();
				return false;
			}
			java.nio.file.Files.move(aTmp.toPath(), aF.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
			System.out.println("Inpainting model copied to "+aF);
			return true;
		}
		catch(Throwable t) {
			t.printStackTrace(System.err);
			aTmp.delete();
			return false;
		}
	}

	/**
	 * The model and ONNX Runtime are there
	 */
	public static boolean isReady(String aTdeDir) {
		return isDownloaded(aTdeDir) && DepthEstimator.isOrtDownloaded(aTdeDir);
	}

	/**
	 * The model will run on the GPU: a GPU usable by ONNX Runtime (Apple Silicon, NVIDIA) with enough memory
	 */
	public static boolean willUseGpu() {
		String aOs = System.getProperty("os.name").toLowerCase();
		boolean aProvider = aOs.contains("mac") || DepthEstimator.useGpuRuntime();
		long aFree = PythonAI.availableGpuMemory();
		return aProvider && (aFree < 0 || aFree >= ENGINE.gpuMemory);
	}

	/**
	 * This machine can run the model (else the holes stay interpolated): LIGHT on any platform of ONNX Runtime
	 * (GPU or CPU), LaMa only on a GPU (gpuCapable())
	 */
	public static boolean capable() {
		if(!ENGINE.gpuOnly) {
			return DepthEstimator.isPlatformSupported();
		}
		return gpuCapable();
	}

	//GPU able to run the model (computed once, false if the model ended on the CPU), null if not known yet
	static volatile Boolean gpuCapable = null;

	/**
	 * This machine runs the model on its GPU (platform of ONNX Runtime, Apple Silicon or NVIDIA GPU with
	 * the GPU memory of the engine free at the first check, and not fallen back to the CPU)
	 */
	public static boolean gpuCapable() {
		Boolean aC = gpuCapable;
		if(aC == null) {
			aC = DepthEstimator.isPlatformSupported() && willUseGpu();
			gpuCapable = aC;
			System.out.println("Inpainting on the GPU: "+(aC ? "possible" : "not possible (interpolation)"));
		}
		return aC;
	}

	/**
	 * Download of ONNX Runtime and of the model (if missing; embedded model: copied from the jar)
	 * @return false if canceled
	 */
	public static boolean download(String aTdeDir,Downloader.Progress aRuntime,Downloader.Progress aModel) throws Exception {
		if(!DepthEstimator.isOrtDownloaded(aTdeDir)) {
			if(!Downloader.download(DepthEstimator.ortUrl(), DepthEstimator.getOrtJar(aTdeDir), DepthEstimator.ortSize(), aRuntime)) {
				return false;
			}
		}
		if(ENGINE.url == null) {
			if(!extract(aTdeDir)) {
				throw new Exception("Inpainting model not available: "+ENGINE.fileName);
			}
			return true;
		}
		return Downloader.download(ENGINE.url, getFile(aTdeDir), ENGINE.size, aModel);
	}

	/**
	 * The shared inpainter, started if needed (the model kept loaded before, if another one, is unloaded)
	 */
	public static Inpainter shared(String aTdeDir) throws Exception {
		synchronized(LOCK) {
			if(shared != null && shared.residentAlive()) {
				return shared;
			}
			shared = null;
			if(lastError != null && System.currentTimeMillis()-lastErrorMS < RETRY_MS) {
				throw new Exception(lastError);
			}
			if(!isReady(aTdeDir)) {
				throw new Exception("Inpainting model not downloaded");
			}
			try {
				PythonAI.beforeLoad(KEY);
				Inpainter aI = new Inpainter(aTdeDir);
				PythonAI.setResident(aI);
				shared = aI;
				lastError = null;
				return aI;
			}
			catch(Exception e) {
				lastError = e.getMessage() == null ? e.toString() : e.getMessage();
				lastErrorMS = System.currentTimeMillis();
				throw e;
			}
		}
	}

	/**
	 * A new try is allowed at once (e.g. the option checked again)
	 */
	public static void resetError() {
		synchronized(LOCK) {
			lastError = null;
		}
	}

	/**
	 * Start the child JVM and load the model in it
	 */
	Inpainter(String aTdeDir) throws Exception {
		String aJava = ProcessHandle.current().info().command().orElse("java");
		Vector<String> aCmd = new Vector<String>();
		aCmd.add(aJava);
		aCmd.add("-Xmx1G");
		aCmd.add("-Djava.awt.headless=true");
		aCmd.add("--enable-native-access=ALL-UNNAMED");
		aCmd.add("-cp");
		aCmd.add(System.getProperty("java.class.path"));
		aCmd.add(Inpainter.class.getName());
		aCmd.add(aTdeDir);
		ProcessBuilder aPB = new ProcessBuilder(aCmd);
		aPB.redirectError(ProcessBuilder.Redirect.INHERIT);
		System.out.println("Inpainting worker: "+String.join(" ", aCmd));
		worker = aPB.start();
		Process aWorker = worker;
		workerHook = new Thread(() -> aWorker.destroyForcibly());
		Runtime.getRuntime().addShutdownHook(workerHook);
		workerOut = new DataOutputStream(new BufferedOutputStream(worker.getOutputStream(), 1 << 20));
		workerIn = new DataInputStream(new BufferedInputStream(worker.getInputStream(), 1 << 20));
		try {
			readAnswer();
			readState();
		}
		catch(Throwable t) {
			close();
			throw t instanceof Exception ? (Exception)t : new Exception(t);
		}
		System.out.println("Inpainting model "+ENGINE.label+" loaded ("+provider+(cpuReason != null ? ", "+cpuReason : "")+")");
		if(!isGpu() && ENGINE.gpuOnly) {
			//On the CPU: too slow, the holes stay interpolated
			gpuCapable = false;
		}
	}

	/**
	 * Child JVM: arg tdeDir (the model of ENGINE). Protocol on stdin / stdout (DataStream), logs on stderr:
	 * - after the loading: answer, state;
	 * - request: number of tiles (-1: end), width and height of the tiles, per tile w*h*3 bytes RGB + w*h bytes
	 * mask (1 = hole); answer: answer, per tile w*h*3 bytes RGB; state.
	 * answer = int 0, or 1 + UTF message; state = UTF provider, UTF cpu reason ("": none), long free GPU memory.
	 */
	public static void main(String[] aArgs) {
		PrintStream aStdout = System.out;
		System.setOut(System.err);
		System.setProperty("java.awt.headless", "true");
		DataOutputStream aOut = new DataOutputStream(new BufferedOutputStream(aStdout, 1 << 20));
		Inpainter aI = new Inpainter();
		try {
			try {
				aI.load(aArgs[0]);
			}
			catch(Throwable t) {
				t.printStackTrace(System.err);
				DepthEstimator.writeError(aOut, t);
				aOut.flush();
				return;
			}
			aOut.writeInt(0);
			aI.writeState(aOut);
			aOut.flush();
			DataInputStream aIn = new DataInputStream(new BufferedInputStream(System.in, 1 << 20));
			while(true) {
				int aN = aIn.readInt();
				if(aN < 0) {
					break;
				}
				int aTW = aIn.readInt();
				int aTH = aIn.readInt();
				int aPlane = aTW*aTH;
				byte[][] aRGB = new byte[aN][3*aPlane];
				byte[][] aMask = new byte[aN][aPlane];
				for(int t = 0;t < aN;t++) {
					aIn.readFully(aRGB[t]);
					aIn.readFully(aMask[t]);
				}
				try {
					byte[][] aRes = new byte[aN][];
					for(int t = 0;t < aN;t++) {
						aRes[t] = aI.run(aRGB[t], aMask[t], aTW, aTH);
					}
					aOut.writeInt(0);
					for(int t = 0;t < aN;t++) {
						aOut.write(aRes[t]);
					}
				}
				catch(Throwable t) {
					t.printStackTrace(System.err);
					DepthEstimator.writeError(aOut, t);
				}
				aI.writeState(aOut);
				aOut.flush();
			}
		}
		catch(Throwable t) {
			//stdin closed: the application stopped the worker or quit
		}
		finally {
			DepthEstimator.closeQuietly(aI.session);
		}
		System.exit(0);
	}

	/** Child side */
	Inpainter() {
	}

	/**
	 * Load the model in this process (child): GPU when there is one with enough free memory, else CPU
	 */
	void load(String aTdeDir) throws Exception {
		tdeDir = aTdeDir;
		ClassLoader aCL = DepthEstimator.getOrtLoader(aTdeDir);
		envC = aCL.loadClass("ai.onnxruntime.OrtEnvironment");
		Class<?> aOptsC = aCL.loadClass("ai.onnxruntime.OrtSession$SessionOptions");
		env = DepthEstimator.invoke(envC.getMethod("getEnvironment"), null);
		String aPath = getFile(aTdeDir).getAbsolutePath();
		long[] aBest = DepthEstimator.bestGpu();
		gpuFree = aBest == null ? -1 : aBest[1];
		int aDevice = aBest == null ? 0 : (int)aBest[0];
		boolean aCudaFailed = false;
		if(gpuFree >= 0 && gpuFree < ENGINE.gpuMemory) {
			cpuReason = DepthEstimator.CPU_REASON_GPU_MEMORY;
		}
		else {
			Collection<?> aAvailable = (Collection<?>)DepthEstimator.invoke(envC.getMethod("getAvailableProviders"), null);
			String aAvailableS = ";";
			for(Object aP : aAvailable) {
				aAvailableS += aP+";";
			}
			for(String aP : DepthEstimator.gpuProviders) {
				if(aAvailableS.indexOf(";"+aP+";") < 0) {
					continue;
				}
				Object aOpts = aOptsC.getConstructor().newInstance();
				try {
					if("CUDA".equals(aP)) {
						DepthEstimator.invoke(aOptsC.getMethod("addCUDA", int.class), aOpts, aDevice);
					}
					else {
						DepthEstimator.invoke(aOptsC.getMethod("addWebGPU", Map.class), aOpts, new HashMap<String,String>());
					}
					session = DepthEstimator.invoke(envC.getMethod("createSession", String.class, aOptsC), env, aPath, aOpts);
					provider = "CUDA".equals(aP) ? "CUDA" : "WebGPU";
					break;
				}
				catch(Throwable t) {
					System.err.println("Inpainting: "+aP+" not usable, next");
					t.printStackTrace(System.err);
					DepthEstimator.closeQuietly(aOpts);
					if("CUDA".equals(aP)) {
						aCudaFailed = true;
					}
				}
			}
			if(session == null) {
				cpuReason = aCudaFailed ? DepthEstimator.CPU_REASON_CUDA : DepthEstimator.CPU_REASON_NO_GPU;
			}
		}
		if(session == null) {
			createCpuSession(aTdeDir);
		}
	}

	void createCpuSession(String aTdeDir) throws Exception {
		Class<?> aOptsC = envC.getClassLoader().loadClass("ai.onnxruntime.OrtSession$SessionOptions");
		Object aOpts = aOptsC.getConstructor().newInstance();
		session = DepthEstimator.invoke(envC.getMethod("createSession", String.class, aOptsC), env, getFile(aTdeDir).getAbsolutePath(), aOpts);
		provider = "CPU";
	}

	/**
	 * One tile of aW x aH (child side): RGB bytes + mask bytes -> RGB bytes. A GPU failure goes on with the CPU.
	 */
	byte[] run(byte[] aRGB,byte[] aMask,int aW,int aH) throws Exception {
		try {
			return runOnce(aRGB, aMask, aW, aH);
		}
		catch(Exception e) {
			if("CPU".equals(provider)) {
				throw e;
			}
			System.err.println("Inpainting: "+provider+" failed, CPU used");
			e.printStackTrace(System.err);
			DepthEstimator.closeQuietly(session);
			session = null;
			createCpuSession(tdeDir);
			cpuReason = DepthEstimator.CPU_REASON_GPU_ERROR;
			return runOnce(aRGB, aMask, aW, aH);
		}
	}

	/**
	 * The tile, padded to sides multiple of MOD by repeating its last row and column (LIGHT; LaMa: always
	 * TILE x TILE), through the model
	 */
	byte[] runOnce(byte[] aRGB,byte[] aMask,int aW,int aH) throws Exception {
		int aPW = ENGINE.resamples ? aW : (aW+MOD-1)/MOD*MOD;
		int aPH = ENGINE.resamples ? aH : (aH+MOD-1)/MOD*MOD;
		int aPPlane = aPW*aPH;
		FloatBuffer aIB = ByteBuffer.allocateDirect(3*aPPlane*4).order(ByteOrder.nativeOrder()).asFloatBuffer();
		for(int c = 0;c < 3;c++) {
			for(int y = 0;y < aPH;y++) {
				int aRow = Math.min(y, aH-1)*aW;
				for(int x = 0;x < aPW;x++) {
					aIB.put((aRGB[(aRow+Math.min(x, aW-1))*3+c] & 0xFF)/255f);
				}
			}
		}
		aIB.rewind();
		FloatBuffer aMB = ByteBuffer.allocateDirect(aPPlane*4).order(ByteOrder.nativeOrder()).asFloatBuffer();
		for(int y = 0;y < aPH;y++) {
			int aRow = Math.min(y, aH-1)*aW;
			for(int x = 0;x < aPW;x++) {
				aMB.put(aMask[aRow+Math.min(x, aW-1)] != 0 ? 1f : 0f);
			}
		}
		aMB.rewind();
		Class<?> aTensorC = envC.getClassLoader().loadClass("ai.onnxruntime.OnnxTensor");
		Object aImage = DepthEstimator.invoke(aTensorC.getMethod("createTensor", envC, FloatBuffer.class, long[].class)
				, null, env, aIB, new long[] {1, 3, aPH, aPW});
		Object aMaskT = DepthEstimator.invoke(aTensorC.getMethod("createTensor", envC, FloatBuffer.class, long[].class)
				, null, env, aMB, new long[] {1, 1, aPH, aPW});
		Object aResult = null;
		try {
			Map<String,Object> aInputs = new HashMap<String,Object>();
			aInputs.put("image", aImage);
			aInputs.put("mask", aMaskT);
			aResult = DepthEstimator.invoke(session.getClass().getMethod("run", Map.class), session, aInputs);
			Object aOutT = DepthEstimator.invoke(aResult.getClass().getMethod("get", int.class), aResult, 0);
			FloatBuffer aOFB = (FloatBuffer)DepthEstimator.invoke(aTensorC.getMethod("getFloatBuffer"), aOutT);
			float[] aO = new float[3*aPPlane];
			aOFB.get(aO);
			//Output scaled to 0..1 then to bytes, cropped to the tile
			float aScale = ENGINE.outScale*255;
			byte[] aRes = new byte[3*aW*aH];
			for(int c = 0;c < 3;c++) {
				for(int y = 0;y < aH;y++) {
					for(int x = 0;x < aW;x++) {
						aRes[(y*aW+x)*3+c] = (byte)Math.max(0, Math.min(255, Math.round(aO[c*aPPlane+y*aPW+x]*aScale)));
					}
				}
			}
			return aRes;
		}
		finally {
			DepthEstimator.closeQuietly(aResult);
			DepthEstimator.closeQuietly(aImage);
			DepthEstimator.closeQuietly(aMaskT);
		}
	}

	void writeState(DataOutputStream aOut) throws Exception {
		aOut.writeUTF(provider == null ? "CPU" : provider);
		aOut.writeUTF(cpuReason == null ? "" : cpuReason);
		aOut.writeLong(gpuFree);
	}

	void readAnswer() throws Exception {
		if(workerIn.readInt() != 0) {
			throw new Exception(workerIn.readUTF());
		}
	}

	void readState() throws Exception {
		provider = workerIn.readUTF();
		String aR = workerIn.readUTF();
		cpuReason = aR.isEmpty() ? null : aR;
		gpuFree = workerIn.readLong();
	}

	/**
	 * Tiles of aTW x aTH computed by the child (parent side)
	 */
	synchronized byte[][] runTiles(byte[][] aRGB,byte[][] aMask,int aTW,int aTH) throws Exception {
		if(workerOut == null) {
			throw new Exception("Inpainting worker stopped");
		}
		workerOut.writeInt(aRGB.length);
		workerOut.writeInt(aTW);
		workerOut.writeInt(aTH);
		for(int t = 0;t < aRGB.length;t++) {
			workerOut.write(aRGB[t]);
			workerOut.write(aMask[t]);
		}
		workerOut.flush();
		Exception aErr = null;
		byte[][] aRes = new byte[aRGB.length][3*aTW*aTH];
		try {
			readAnswer();
			for(int t = 0;t < aRes.length;t++) {
				workerIn.readFully(aRes[t]);
			}
		}
		catch(Exception e) {
			aErr = e;
		}
		if(aErr == null || (worker != null && worker.isAlive())) {
			readState();
		}
		if(aErr != null) {
			throw aErr;
		}
		return aRes;
	}

	/**
	 * Fill the holes of an image.
	 * @param aRGB colors 0..1, 3 per pixel, row by row: the hole pixels are replaced
	 * @param aHoles the holes
	 * @param aFlip the foreground is on the right of the holes (LIGHT: the image is given mirrored to the model,
	 * trained with the foreground on their left)
	 */
	public void fill(float[] aRGB,int aW,int aH,boolean[] aHoles,boolean aFlip) throws Exception {
		boolean aAny = false;
		for(boolean aB : aHoles) {
			if(aB) {
				aAny = true;
				break;
			}
		}
		if(!aAny) {
			return;
		}
		if(!aFlip || ENGINE.resamples) {
			fillTiles(aRGB, aW, aH, aHoles);
			return;
		}
		float[] aMRGB = new float[aRGB.length];
		boolean[] aMHoles = new boolean[aHoles.length];
		mirror(aRGB, aHoles, aMRGB, aMHoles, aW, aH);
		fillTiles(aMRGB, aW, aH, aMHoles);
		for(int y = 0;y < aH;y++) {
			for(int x = 0;x < aW;x++) {
				int p = y*aW+x;
				if(aHoles[p]) {
					System.arraycopy(aMRGB, (y*aW+aW-1-x)*3, aRGB, p*3, 3);
				}
			}
		}
	}

	/**
	 * Horizontal mirror of an image and its holes
	 */
	static void mirror(float[] aRGB,boolean[] aHoles,float[] aMRGB,boolean[] aMHoles,int aW,int aH) {
		for(int y = 0;y < aH;y++) {
			for(int x = 0;x < aW;x++) {
				int p = y*aW+x;
				int mp = y*aW+aW-1-x;
				aMHoles[mp] = aHoles[p];
				System.arraycopy(aRGB, p*3, aMRGB, mp*3, 3);
			}
		}
	}

	/**
	 * Fill the holes by tiles of TILE at most (only those with holes, overlapping, blended)
	 */
	void fillTiles(float[] aRGB,int aW,int aH,boolean[] aHoles) throws Exception {
		float[] aSRGB = aRGB;
		boolean[] aSHoles = aHoles;
		int aSW = aW;
		int aSH = aH;
		//Tiles over the image (overlapping), only those with holes
		int aTW = Math.min(TILE, aSW);
		int aTH = Math.min(TILE, aSH);
		//Size given to the model: LaMa TILE x TILE (the tile resampled), else the tile itself
		int aMW = ENGINE.resamples ? TILE : aTW;
		int aMH = ENGINE.resamples ? TILE : aTH;
		int[] aXs = tilePositions(aSW, aTW);
		int[] aYs = tilePositions(aSH, aTH);
		Vector<int[]> aTiles = new Vector<int[]>();
		for(int aTY : aYs) {
			for(int aTX : aXs) {
				boolean aHas = false;
				for(int y = aTY;y < aTY+aTH && !aHas;y++) {
					for(int x = aTX;x < aTX+aTW;x++) {
						if(aSHoles[y*aSW+x]) {
							aHas = true;
							break;
						}
					}
				}
				if(aHas) {
					aTiles.add(new int[] {aTX, aTY});
				}
			}
		}
		byte[][] aInRGB = new byte[aTiles.size()][3*aMW*aMH];
		byte[][] aInMask = new byte[aTiles.size()][aMW*aMH];
		float[] aPix = new float[3];
		for(int t = 0;t < aTiles.size();t++) {
			int aTX = aTiles.elementAt(t)[0];
			int aTY = aTiles.elementAt(t)[1];
			for(int ty = 0;ty < aMH;ty++) {
				double aFY = (ty+0.5)*aTH/aMH-0.5;
				for(int tx = 0;tx < aMW;tx++) {
					double aFX = (tx+0.5)*aTW/aMW-0.5;
					//Hole if the nearest reduced pixel is one (no enlargement)
					int aNX = clamp((int)Math.round(aFX), aTW);
					int aNY = clamp((int)Math.round(aFY), aTH);
					boolean aHole = aSHoles[(aTY+aNY)*aSW+aTX+aNX];
					int tp = ty*aMW+tx;
					aInMask[t][tp] = (byte)(aHole ? 1 : 0);
					if(!aHole) {
						//Over the non hole pixels only (the holes are black here)
						maskedBilinear(aSRGB, aSHoles, false, aSW, aSH, aTX+aFX, aTY+aFY, aTX+aNX, aTY+aNY, aPix, 0);
						for(int c = 0;c < 3;c++) {
							aInRGB[t][tp*3+c] = (byte)Math.max(0, Math.min(255, Math.round(aPix[c]*255)));
						}
					}
				}
			}
		}
		byte[][] aOut = runTiles(aInRGB, aInMask, aMW, aMH);
		//Holes: tiles blended, weight growing with the distance to the tile edges
		float[] aAcc = new float[aSW*aSH*3];
		float[] aWAcc = new float[aSW*aSH];
		for(int t = 0;t < aTiles.size();t++) {
			int aTX = aTiles.elementAt(t)[0];
			int aTY = aTiles.elementAt(t)[1];
			for(int y = 0;y < aTH;y++) {
				for(int x = 0;x < aTW;x++) {
					int sp = (aTY+y)*aSW+aTX+x;
					if(!aSHoles[sp]) {
						continue;
					}
					float aWt = 1+Math.min(Math.min(x, aTW-1-x), Math.min(y, aTH-1-y));
					double aFX = (x+0.5)*aMW/aTW-0.5;
					double aFY = (y+0.5)*aMH/aTH-0.5;
					for(int c = 0;c < 3;c++) {
						aAcc[sp*3+c] += aWt*bilinearBytes(aOut[t], aMW, aMH, aFX, aFY, c)/255f;
					}
					aWAcc[sp] += aWt;
				}
			}
		}
		for(int sp = 0;sp < aSW*aSH;sp++) {
			if(aSHoles[sp] && aWAcc[sp] > 0) {
				for(int c = 0;c < 3;c++) {
					aSRGB[sp*3+c] = aAcc[sp*3+c]/aWAcc[sp];
				}
			}
		}
	}

	static int clamp(int aV,int aN) {
		return aV < 0 ? 0 : (aV >= aN ? aN-1 : aV);
	}

	/**
	 * Positions of tiles of aT over aN, overlapping by OVERLAP at least
	 */
	static int[] tilePositions(int aN,int aT) {
		if(aN <= aT) {
			return new int[] {0};
		}
		int aCount = (int)Math.ceil((aN-aT)/(double)(aT-OVERLAP))+1;
		int[] aPos = new int[aCount];
		for(int i = 0;i < aCount;i++) {
			aPos[i] = (int)Math.round(i*(aN-aT)/(double)(aCount-1));
		}
		return aPos;
	}

	/**
	 * Bilinear interpolation of the reduced image at (aX, aY) over its pixels whose hole state is aHole only
	 * (holes: filled by the model; others: the image), into aOut at aP; the reduced pixel (aSX, aSY), of that
	 * state, if none of the 4 nearest is
	 */
	public static void maskedBilinear(float[] aRGB,boolean[] aHoles,boolean aHole,int aW,int aH,double aX,double aY,int aSX,int aSY,float[] aOut,int aP) {
		int aX0 = (int)Math.floor(aX);
		int aY0 = (int)Math.floor(aY);
		double aFX = aX-aX0;
		double aFY = aY-aY0;
		int[] aXs = {clamp(aX0, aW), clamp(aX0+1, aW)};
		int[] aYs = {clamp(aY0, aH), clamp(aY0+1, aH)};
		double[] aWXs = {1-aFX, aFX};
		double[] aWYs = {1-aFY, aFY};
		double aR = 0, aG = 0, aB = 0, aWt = 0;
		for(int j = 0;j < 2;j++) {
			for(int i = 0;i < 2;i++) {
				int sp = aYs[j]*aW+aXs[i];
				if(aHoles[sp] != aHole) {
					continue;
				}
				double aK = aWXs[i]*aWYs[j];
				aR += aK*aRGB[sp*3];
				aG += aK*aRGB[sp*3+1];
				aB += aK*aRGB[sp*3+2];
				aWt += aK;
			}
		}
		if(aWt <= 1e-9) {
			int sp = aSY*aW+aSX;
			aOut[aP*3] = aRGB[sp*3];
			aOut[aP*3+1] = aRGB[sp*3+1];
			aOut[aP*3+2] = aRGB[sp*3+2];
			return;
		}
		aOut[aP*3] = (float)(aR/aWt);
		aOut[aP*3+1] = (float)(aG/aWt);
		aOut[aP*3+2] = (float)(aB/aWt);
	}

	/**
	 * Bilinear interpolation of channel aC of an RGB bytes image of aW x aH at (aX, aY)
	 */
	static float bilinearBytes(byte[] aRGB,int aW,int aH,double aX,double aY,int aC) {
		int aX0 = (int)Math.floor(aX);
		int aY0 = (int)Math.floor(aY);
		double aFX = aX-aX0;
		double aFY = aY-aY0;
		int aXa = clamp(aX0, aW), aXb = clamp(aX0+1, aW);
		int aYa = clamp(aY0, aH), aYb = clamp(aY0+1, aH);
		double aV = (aRGB[(aYa*aW+aXa)*3+aC] & 0xFF)*(1-aFX)*(1-aFY) + (aRGB[(aYa*aW+aXb)*3+aC] & 0xFF)*aFX*(1-aFY)
				+ (aRGB[(aYb*aW+aXa)*3+aC] & 0xFF)*(1-aFX)*aFY + (aRGB[(aYb*aW+aXb)*3+aC] & 0xFF)*aFX*aFY;
		return (float)aV;
	}

	public boolean isGpu() {
		return !"CPU".equals(provider);
	}

	//PythonAI.Resident
	@Override
	public String residentKey() {
		return KEY;
	}

	@Override
	public String residentTag() {
		return TAG;
	}

	@Override
	public String residentDevice() {
		return isGpu() ? "auto" : "cpu";
	}

	@Override
	public boolean residentAlive() {
		Process aP = worker;
		return aP != null && aP.isAlive() && workerOut != null;
	}

	@Override
	public long residentMemory() {
		return isGpu() ? ENGINE.gpuMemory : 0;
	}

	@Override
	public void unload() {
		close();
	}

	@Override
	public synchronized void close() {
		Process aP = worker;
		if(aP == null) {
			return;
		}
		worker = null;
		try {
			workerOut.writeInt(-1);
			workerOut.flush();
			workerOut.close();
		}
		catch(Throwable t) {
		}
		workerOut = null;
		try {
			if(!aP.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)) {
				aP.destroyForcibly();
			}
		}
		catch(Throwable t) {
			aP.destroyForcibly();
		}
		try {
			Runtime.getRuntime().removeShutdownHook(workerHook);
		}
		catch(Throwable t) {
			//Already quitting
		}
		PythonAI.residentGone(this);
		System.out.println("Inpainting worker stopped");
	}
}
