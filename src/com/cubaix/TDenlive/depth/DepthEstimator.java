package com.cubaix.TDenlive.depth;

import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.File;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Vector;

/**
 * Depth estimation with a Depth Anything V2 ONNX model (see DepthModel) and ONNX Runtime.
 * The ONNX Runtime jar (natives for Windows x64, Linux x64/arm64, macOS arm64; its CUDA build when there is an
 * NVIDIA GPU on Windows / Linux x64, see useGpuRuntime()) is downloaded at the first use
 * into ~/3Denlive/Models/ and loaded with its own class loader, so it is neither needed to compile
 * nor shipped with the application: its API is called by reflection.
 * The result is a relative inverse depth: the higher, the nearer (white is front once normalized).
 */
public class DepthEstimator implements AutoCloseable {
	public static final String ORT_VERSION = "1.31.1";
	static final String MAVEN = "https://repo1.maven.org/maven2/com/microsoft/onnxruntime/";
	//Standard jar: CPU everywhere, plus WebGPU (Metal) in its macOS build only
	static final String ORT_JAR = "onnxruntime";
	static final long ORT_SIZE = 50942631L;
	//GPU jar (Windows x64, Linux x64): CPU + CUDA, the CUDA 12 and cuDNN 9 libraries must be installed
	static final String ORT_GPU_JAR = "onnxruntime_gpu";
	static final long ORT_GPU_SIZE = 403444993L;
	//Short side of the image given to the model (size of the training), multiple of PATCH
	static final int BASE_SIZE = 518;
	static final int PATCH = 14;
	//Long side limit (panoramas)
	static final int MAX_SIZE = 3*BASE_SIZE;
	static final float[] MEAN = {0.485f, 0.456f, 0.406f};
	static final float[] STD = {0.229f, 0.224f, 0.225f};

	static ClassLoader ortLoader = null;
	//GPU execution providers, in the order they are tried (when in the jar and usable).
	//Not CoreML: on Apple Silicon, it splits these models in many parts and is much slower than the CPU,
	//whereas WebGPU (Metal) is about 5 times faster than the CPU
	//(DirectML is in none of the Java builds)
	static String[] gpuProviders = {"CUDA", "WEBGPU"};

	//Why the model runs on the CPU (cpuReason)
	public static final String CPU_REASON_NO_GPU = "NoGpu";
	public static final String CPU_REASON_GPU_MEMORY = "GpuMemory";
	public static final String CPU_REASON_GPU_ERROR = "GpuError";
	public static final String CPU_REASON_CUDA = "Cuda";

	Class<?> envC = null;
	Class<?> optsC = null;
	Object env = null;
	Object session = null;
	String inputName = null;
	DepthModel model = null;
	String modelPath = null;
	//Execution provider in use: CPU, CUDA, DirectML, WebGPU
	public String provider = "CPU";
	//Free GPU memory found at the loading (bytes), -1 if unknown
	public long gpuFree = -1;
	//Index of the GPU used (CUDA)
	public int gpuDevice = 0;
	boolean cudaFailed = false;
	//Why the CPU is used (CPU_REASON_*), null on the GPU
	public String cpuReason = null;

	/**
	 * Platforms with ONNX Runtime natives in its jar (not macOS x86_64 nor PPC64le)
	 */
	public static boolean isPlatformSupported() {
		String aOs = System.getProperty("os.name").toLowerCase();
		String aArch = System.getProperty("os.arch").toLowerCase();
		boolean aX64 = aArch.equals("amd64") || aArch.equals("x86_64");
		boolean aArm64 = aArch.equals("aarch64") || aArch.equals("arm64");
		if(aOs.contains("mac")) {
			return aArm64;
		}
		if(aOs.contains("windows")) {
			return aX64;
		}
		if(aOs.contains("linux")) {
			return aX64 || aArm64;
		}
		return false;
	}

	/**
	 * An NVIDIA GPU (nvidia-smi found) on Windows x64 or Linux x64: the CUDA build of ONNX Runtime is used.
	 * The integrated GPUs (Intel, AMD) have no provider in the Java builds of ONNX Runtime: the CPU is used.
	 */
	public static boolean useGpuRuntime() {
		String aOs = System.getProperty("os.name").toLowerCase();
		String aArch = System.getProperty("os.arch").toLowerCase();
		boolean aX64 = aArch.equals("amd64") || aArch.equals("x86_64");
		return aX64 && (aOs.contains("windows") || aOs.contains("linux")) && findNvidiaSmi(aOs) != null;
	}

	static String ortName() {
		return useGpuRuntime() ? ORT_GPU_JAR : ORT_JAR;
	}

	public static long ortSize() {
		return useGpuRuntime() ? ORT_GPU_SIZE : ORT_SIZE;
	}

	public static String ortUrl() {
		return MAVEN+ortName()+"/"+ORT_VERSION+"/"+ortName()+"-"+ORT_VERSION+".jar";
	}

	public static File getOrtJar(String aTdeDir) {
		return new File(DepthModel.getModelsDir(aTdeDir), ortName()+"-"+ORT_VERSION+".jar");
	}

	public static boolean isOrtDownloaded(String aTdeDir) {
		File aF = getOrtJar(aTdeDir);
		return aF.isFile() && aF.length() == ortSize();
	}

	static synchronized ClassLoader getOrtLoader(String aTdeDir) throws Exception {
		if(ortLoader == null) {
			ortLoader = new URLClassLoader(new URL[] {getOrtJar(aTdeDir).toURI().toURL()}, DepthEstimator.class.getClassLoader());
		}
		return ortLoader;
	}

	/**
	 * Free memory usable by the GPU (bytes) of the GPU that has the most, -1 if unknown (see bestGpu())
	 */
	public static long gpuFreeMemory() {
		long[] aBest = bestGpu();
		return aBest == null ? -1 : aBest[1];
	}

	/**
	 * GPU with the most free memory: {device index, free bytes}, null if unknown.
	 * When the NVIDIA GPUs are all full, the CPU is used: an integrated GPU can't be used by the Java builds
	 * of ONNX Runtime on Windows / Linux (and on macOS the GPU is the integrated one).
	 * - NVIDIA (nvidia-smi), one or several GPUs: the index is the nvidia-smi one, given to CUDA
	 * (same order as CUDA with CUDA_DEVICE_ORDER=PCI_BUS_ID, or with identical GPUs);
	 * - macOS (Apple Silicon, one GPU, memory shared with the CPU): free + reclaimable memory (vm_stat),
	 * at most the share of the memory Metal lets the GPU use (about 3/4).
	 */
	public static long[] bestGpu() {
		try {
			String aOs = System.getProperty("os.name").toLowerCase();
			String aSmi = findNvidiaSmi(aOs);
			if(aSmi != null) {
				long[] aBest = null;
				for(String aL : run(aSmi, "--query-gpu=index,memory.free", "--format=csv,noheader,nounits").split("\\R")) {
					String[] aF = aL.split(",");
					if(aF.length == 2 && aF[0].trim().matches("[0-9]+") && aF[1].trim().matches("[0-9]+")) {
						long aFree = Long.parseLong(aF[1].trim())*1024*1024;
						System.out.println("GPU "+aF[0].trim()+": "+aF[1].trim()+" MB free");
						if(aBest == null || aFree > aBest[1]) {
							aBest = new long[] {Long.parseLong(aF[0].trim()), aFree};
						}
					}
				}
				if(aBest != null) {
					return aBest;
				}
			}
			if(aOs.contains("mac")) {
				long aPage = 4096;
				long aPages = 0;
				for(String aL : run("vm_stat").split("\\R")) {
					if(aL.matches(".*page size of [0-9]+ bytes.*")) {
						aPage = Long.parseLong(aL.replaceAll(".*page size of ([0-9]+) bytes.*", "$1"));
					}
					else if(aL.matches("Pages (free|inactive|speculative|purgeable):\\s+[0-9]+\\.?")) {
						aPages += Long.parseLong(aL.replaceAll("[^0-9]", ""));
					}
				}
				long aTotal = Long.parseLong(run("sysctl", "-n", "hw.memsize").trim());
				return new long[] {0, Math.min(aPages*aPage, aTotal*3/4)};
			}
		}
		catch(Throwable t) {
			t.printStackTrace(System.err);
		}
		return null;
	}

	static String findNvidiaSmi(String aOs) {
		String aExe = aOs.contains("windows") ? "nvidia-smi.exe" : "nvidia-smi";
		Vector<String> aDirs = new Vector<String>();
		String aPath = System.getenv("PATH");
		if(aPath != null) {
			for(String aD : aPath.split(File.pathSeparator)) {
				aDirs.add(aD);
			}
		}
		aDirs.add("/usr/bin");
		aDirs.add("C:\\Windows\\System32");
		for(String aD : aDirs) {
			File aF = new File(aD, aExe);
			if(aF.isFile()) {
				return aF.getAbsolutePath();
			}
		}
		return null;
	}

	static String run(String... aCmd) throws Exception {
		Process aP = new ProcessBuilder(aCmd).redirectErrorStream(true).start();
		String aOut = new String(aP.getInputStream().readAllBytes());
		aP.waitFor();
		return aOut;
	}

	/**
	 * Load the model (the model and the ONNX Runtime jar must be downloaded).
	 * It runs on the GPU when there is one with enough free memory for it (see DepthModel.gpuMemory),
	 * with the first usable provider (CUDA, WebGPU), else on the CPU (see cpuReason).
	 */
	public DepthEstimator(String aTdeDir,DepthModel aModel) throws Exception {
		ClassLoader aCL = getOrtLoader(aTdeDir);
		envC = aCL.loadClass("ai.onnxruntime.OrtEnvironment");
		optsC = aCL.loadClass("ai.onnxruntime.OrtSession$SessionOptions");
		env = invoke(envC.getMethod("getEnvironment"), null);
		model = aModel;
		modelPath = aModel.getFile(aTdeDir).getAbsolutePath();
		//Several GPUs: the one with the most free memory, if enough for the model
		long[] aBest = bestGpu();
		gpuFree = aBest == null ? -1 : aBest[1];
		gpuDevice = aBest == null ? 0 : (int)aBest[0];
		System.out.println("GPU "+gpuDevice+" free memory: "+(gpuFree < 0 ? "unknown" : (gpuFree/(1024*1024))+" MB")
				+", needed by "+aModel.id+": "+(aModel.gpuMemory/(1024*1024))+" MB");
		if(gpuFree >= 0 && gpuFree < aModel.gpuMemory) {
			cpuReason = CPU_REASON_GPU_MEMORY;
		}
		else {
			Collection<?> aAvailable = (Collection<?>)invoke(envC.getMethod("getAvailableProviders"), null);
			String aAvailableS = ";";
			for(Object aP : aAvailable) {
				aAvailableS += aP+";";
			}
			System.out.println("ONNX Runtime providers: "+aAvailableS);
			for(String aP : gpuProviders) {
				if(aAvailableS.indexOf(";"+aP+";") < 0) {
					continue;
				}
				Object aOpts = optsC.getConstructor().newInstance();
				try {
					if("CUDA".equals(aP)) {
						invoke(optsC.getMethod("addCUDA", int.class), aOpts, gpuDevice);
					}
					else {
						invoke(optsC.getMethod("addWebGPU", Map.class), aOpts, new HashMap<String,String>());
					}
					session = invoke(envC.getMethod("createSession", String.class, optsC), env, modelPath, aOpts);
					provider = "CUDA".equals(aP) ? "CUDA" : "WebGPU";
					break;
				}
				catch(Throwable t) {
					System.err.println("ONNX Runtime: "+aP+" not usable, next");
					t.printStackTrace(System.err);
					closeQuietly(aOpts);
					if("CUDA".equals(aP)) {
						//Usually the CUDA / cuDNN libraries not installed
						cudaFailed = true;
					}
				}
			}
			if(session == null) {
				cpuReason = cudaFailed ? CPU_REASON_CUDA : CPU_REASON_NO_GPU;
			}
		}
		if(session == null) {
			createCpuSession();
		}
		Collection<?> aNames = (Collection<?>)invoke(session.getClass().getMethod("getInputNames"), session);
		inputName = (String)aNames.iterator().next();
		System.out.println("Depth model "+aModel.id+" loaded ("+provider+"), input: "+inputName);
	}

	void createCpuSession() throws Exception {
		Object aOpts = optsC.getConstructor().newInstance();
		session = invoke(envC.getMethod("createSession", String.class, optsC), env, modelPath, aOpts);
		provider = "CPU";
	}

	public boolean isGpu() {
		return !"CPU".equals(provider);
	}

	static Object invoke(Method aM,Object aObj,Object... aArgs) throws Exception {
		try {
			return aM.invoke(aObj, aArgs);
		}
		catch(InvocationTargetException e) {
			Throwable aT = e.getCause();
			throw aT instanceof Exception ? (Exception)aT : new Exception(aT);
		}
	}

	static void closeQuietly(Object aO) {
		try {
			if(aO instanceof AutoCloseable) {
				((AutoCloseable)aO).close();
			}
		}
		catch(Throwable t) {
			t.printStackTrace(System.err);
		}
	}

	/**
	 * Size of the image given to the model: short side BASE_SIZE, multiples of PATCH, ratio kept
	 */
	static int[] inputSize(int aW,int aH) {
		double aScale = BASE_SIZE/(double)Math.min(aW, aH);
		if(Math.max(aW, aH)*aScale > MAX_SIZE) {
			aScale = MAX_SIZE/(double)Math.max(aW, aH);
		}
		int aIW = Math.max(PATCH, (int)Math.round(aW*aScale/PATCH)*PATCH);
		int aIH = Math.max(PATCH, (int)Math.round(aH*aScale/PATCH)*PATCH);
		return new int[] {aIW, aIH};
	}

	/**
	 * Depth of an image at the size given to the model.
	 * @return {depth values (row by row), width, height}: the higher, the nearer
	 */
	public Object[] estimate(BufferedImage aBI) throws Exception {
		try {
			return estimateOnce(aBI);
		}
		catch(Exception e) {
			if(!isGpu()) {
				throw e;
			}
			//GPU failure (out of memory...): go on with the CPU
			System.err.println("Depth model: "+provider+" failed, CPU used");
			e.printStackTrace(System.err);
			closeQuietly(session);
			session = null;
			createCpuSession();
			cpuReason = CPU_REASON_GPU_ERROR;
			return estimateOnce(aBI);
		}
	}

	Object[] estimateOnce(BufferedImage aBI) throws Exception {
		int[] aSize = inputSize(aBI.getWidth(), aBI.getHeight());
		int aW = aSize[0];
		int aH = aSize[1];
		BufferedImage aRes = new BufferedImage(aW, aH, BufferedImage.TYPE_INT_RGB);
		Graphics2D aG = aRes.createGraphics();
		aG.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
		aG.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
		aG.drawImage(aBI, 0, 0, aW, aH, null);
		aG.dispose();
		int[] aPix = aRes.getRGB(0, 0, aW, aH, null, 0, aW);
		int aPlane = aW*aH;
		FloatBuffer aFB = ByteBuffer.allocateDirect(3*aPlane*4).order(ByteOrder.nativeOrder()).asFloatBuffer();
		for(int c = 0;c < 3;c++) {
			int aShift = 16-8*c;
			for(int p = 0;p < aPlane;p++) {
				aFB.put((((aPix[p]>>aShift) & 0xFF)/255.0f-MEAN[c])/STD[c]);
			}
		}
		aFB.rewind();

		Class<?> aTensorC = envC.getClassLoader().loadClass("ai.onnxruntime.OnnxTensor");
		Object aTensor = invoke(aTensorC.getMethod("createTensor", envC, FloatBuffer.class, long[].class)
				, null, env, aFB, new long[] {1, 3, aH, aW});
		Object aResult = null;
		try {
			Map<String,Object> aInputs = new HashMap<String,Object>();
			aInputs.put(inputName, aTensor);
			aResult = invoke(session.getClass().getMethod("run", Map.class), session, aInputs);
			Object aOut = invoke(aResult.getClass().getMethod("get", int.class), aResult, 0);
			Object aInfo = invoke(aTensorC.getMethod("getInfo"), aOut);
			long[] aShape = (long[])invoke(aInfo.getClass().getMethod("getShape"), aInfo);
			int aOW = (int)aShape[aShape.length-1];
			int aOH = (int)aShape[aShape.length-2];
			FloatBuffer aOFB = (FloatBuffer)invoke(aTensorC.getMethod("getFloatBuffer"), aOut);
			float[] aDepth = new float[aOW*aOH];
			aOFB.get(aDepth);
			return new Object[] {aDepth, aOW, aOH};
		}
		finally {
			closeQuietly(aResult);
			closeQuietly(aTensor);
		}
	}

	/**
	 * Low and high values of a depth map (1st and 99th percentiles: a few extreme pixels don't flatten the rest)
	 */
	public static float[] range(float[] aDepth) {
		float aMin = Float.MAX_VALUE;
		float aMax = -Float.MAX_VALUE;
		for(float aD : aDepth) {
			aMin = Math.min(aMin, aD);
			aMax = Math.max(aMax, aD);
		}
		if(aMax <= aMin) {
			return new float[] {aMin, aMin+1};
		}
		int[] aHist = new int[1024];
		for(float aD : aDepth) {
			aHist[Math.min(1023, (int)((aD-aMin)/(aMax-aMin)*1024))]++;
		}
		int aLow = 0;
		int aHigh = 1023;
		long aCount = 0;
		long aTh = aDepth.length/100;
		for(int b = 0;b < 1024;b++) {
			aCount += aHist[b];
			if(aCount > aTh) {
				aLow = b;
				break;
			}
		}
		aCount = 0;
		for(int b = 1023;b >= 0;b--) {
			aCount += aHist[b];
			if(aCount > aTh) {
				aHigh = b+1;
				break;
			}
		}
		float aL = aMin+(aMax-aMin)*aLow/1024f;
		float aH = aMin+(aMax-aMin)*aHigh/1024f;
		return aH > aL ? new float[] {aL, aH} : new float[] {aMin, aMax};
	}

	/**
	 * Gray opaque image of a depth map (aLow -> black, aHigh -> white), resized to aW x aH
	 */
	public static BufferedImage toImage(float[] aDepth,int aDW,int aDH,float aLow,float aHigh,int aW,int aH) {
		BufferedImage aSmall = new BufferedImage(aDW, aDH, BufferedImage.TYPE_INT_RGB);
		int[] aPix = new int[aDW*aDH];
		float aScale = 255f/(aHigh-aLow);
		for(int p = 0;p < aPix.length;p++) {
			int aV = Math.max(0, Math.min(255, Math.round((aDepth[p]-aLow)*aScale)));
			aPix[p] = 0xFF000000 | aV << 16 | aV << 8 | aV;
		}
		aSmall.setRGB(0, 0, aDW, aDH, aPix, 0, aDW);
		if(aDW == aW && aDH == aH) {
			return aSmall;
		}
		BufferedImage aOut = new BufferedImage(aW, aH, BufferedImage.TYPE_INT_RGB);
		Graphics2D aG = aOut.createGraphics();
		aG.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
		aG.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
		aG.drawImage(aSmall, 0, 0, aW, aH, null);
		aG.dispose();
		return aOut;
	}

	@Override
	public void close() {
		closeQuietly(session);
		session = null;
	}
}
