package com.cubaix.TDenlive.ai;

import java.io.File;
import java.util.Vector;

import com.cubaix.TDenlive.depth.DepthEstimator;
import com.cubaix.TDenlive.depth.DepthModel;

/**
 * Colorization of black and white photos with DDColor (Kang et al., ICCV 2023, https://github.com/piddnad/DDColor,
 * Apache-2.0), run by res/ai/ddcolor_colorize.py in the shared Python environment (see PythonAI), on CUDA, MPS or
 * the CPU. Unlike a video generator, the photo is kept as it is: only its colors (Lab a / b channels predicted at
 * 512 x 512) are added to its own lightness, at its size.
 * The weights (piddnad/ddcolor_* on Hugging Face) go into ~/3Denlive/Models/DDColor/<model>/, the inference code
 * of its authors (CODE_FILES, at CODE_COMMIT) into ~/3Denlive/Models/DDColor/code/, at the first use.
 */
public class ColorGenerator extends PythonAI {
	static final long GB = 1024L*1024*1024;
	static final long MB = 1024L*1024;
	static final String DIR = "DDColor";
	//Inference code of DDColor at this commit: the model and the modules it imports (no training code)
	static final String CODE_COMMIT = "2adb63f2656ac41cbdf7b894cddd94121a3faf13";
	static final String CODE_URL = "https://raw.githubusercontent.com/piddnad/DDColor/"+CODE_COMMIT+"/";
	static final Object[][] CODE_FILES = {
		{"ddcolor/model.py", 0L},
		{"basicsr/archs/ddcolor_arch_utils/__init__.py", 0L},
		{"basicsr/archs/ddcolor_arch_utils/unet.py", 0L},
		{"basicsr/archs/ddcolor_arch_utils/convnext.py", 0L},
		{"basicsr/archs/ddcolor_arch_utils/transformer_utils.py", 0L},
		{"basicsr/archs/ddcolor_arch_utils/position_encoding.py", 0L},
	};

	public static class Model {
		public final String id;
		final String repo;
		final String dir;
		//Parameters (for the user)
		public final String params;
		final long size;
		//Memory needed (estimates: fp32 weights + the activations at 512 x 512)
		public final long gpuMemory;

		Model(String aId,String aRepo,String aDir,String aParams,long aSize,long aGpu) {
			id = aId;
			repo = aRepo;
			dir = aDir;
			params = aParams;
			size = aSize;
			gpuMemory = aGpu;
		}

		Object[][] files() {
			return new Object[][] {{"config.json", 0L}, {"pytorch_model.bin", size}};
		}

		public File getDir(String aTdeDir) {
			return new File(getRoot(aTdeDir), dir);
		}

		public long downloadSize() {
			return size;
		}

		public long bytesToDownload(String aTdeDir) {
			long aB = PythonAI.bytesToDownload(getDir(aTdeDir), files());
			for(Object[] aF : CODE_FILES) {
				if(!new File(getCodeDir(aTdeDir), ((String)aF[0]).replace('/', File.separatorChar)).isFile()) {
					//The code files are small
					aB += 64*1024;
				}
			}
			return aB;
		}

		public boolean isDownloaded(String aTdeDir) {
			return bytesToDownload(aTdeDir) == 0;
		}
	}

	//Trained on ImageNet + the data of ModelScope: the most natural colors (advised)
	public static final Model MODELSCOPE = new Model("Standard", "piddnad/ddcolor_modelscope", "modelscope", "228 M", 911914869L, 2*GB);
	//Trained for more vivid, saturated colors
	public static final Model ARTISTIC = new Model("Artistic", "piddnad/ddcolor_artistic", "artistic", "228 M", 911914869L, 2*GB);
	//ConvNeXt-T encoder: 4 times smaller and faster, less accurate
	public static final Model TINY = new Model("Tiny", "piddnad/ddcolor_paper_tiny", "tiny", "55 M", 220372845L, 1*GB);
	public static final Model[] MODELS = {MODELSCOPE, ARTISTIC, TINY};

	public static Model byId(String aId) {
		for(Model aM : MODELS) {
			if(aM.id.equalsIgnoreCase(aId)) {
				return aM;
			}
		}
		return null;
	}

	static File getRoot(String aTdeDir) {
		return new File(DepthModel.getModelsDir(aTdeDir), DIR);
	}

	static File getCodeDir(String aTdeDir) {
		return new File(getRoot(aTdeDir), "code");
	}

	/**
	 * Python and PyTorch exist for this platform (GPU or CPU)
	 */
	public static boolean isPlatformSupported() {
		return hasPython();
	}

	/**
	 * Device: null for the GPU, else the reason of the CPU (DepthEstimator.CPU_REASON_*)
	 */
	public static String cpuReason(Model aModel) {
		if(!hasGpu()) {
			return DepthEstimator.CPU_REASON_NO_GPU;
		}
		if(!isMac()) {
			long[] aBest = DepthEstimator.bestGpu();
			if(aBest != null && aBest[1] < aModel.gpuMemory) {
				return DepthEstimator.CPU_REASON_GPU_MEMORY;
			}
		}
		return null;
	}

	Model model;
	//Device used by the script (DEVICE message): cuda, mps or cpu
	public volatile String device = null;

	public ColorGenerator(String aTdeDir,Model aModel,Progress aProgress) {
		super(aTdeDir, aProgress);
		model = aModel;
	}

	@Override
	String stepKey() {
		return "AI.Colorizing";
	}

	@Override
	boolean handleLine(String aL) {
		if(aL.startsWith("DEVICE ")) {
			device = aL.substring(7).trim();
			System.out.println("AI colorization device: "+device);
			return true;
		}
		return false;
	}

	/**
	 * Install what is missing, then colorize each photo of aInputs into the PNG of aOuts (same order)
	 * @return false if canceled
	 */
	public boolean colorize(File[] aInputs,File[] aOuts) throws Exception {
		if(!installEnv() || !downloadModel() || canceled()) {
			return false;
		}
		File aDir = model.getDir(tdeDir);
		File aScript = copyScript("ddcolor_colorize.py", getRoot(tdeDir));
		Vector<String> aCmd = pythonCommand(aScript);
		aCmd.add("--model-dir"); aCmd.add(aDir.getAbsolutePath());
		aCmd.add("--code-dir"); aCmd.add(getCodeDir(tdeDir).getAbsolutePath());
		for(int i = 0;i < aInputs.length;i++) {
			aCmd.add("--input"); aCmd.add(aInputs[i].getAbsolutePath());
			aCmd.add("--out"); aCmd.add(aOuts[i].getAbsolutePath());
		}
		aCmd.add("--device"); aCmd.add(cpuReason(model) == null ? "auto" : "cpu");
		String aDone = runScript(aCmd);
		if(aDone == null || canceled()) {
			for(File aO : aOuts) {
				aO.delete();
			}
			return false;
		}
		for(File aO : aOuts) {
			if(!aO.isFile()) {
				throw new Exception("No image produced (see log/3De.log)");
			}
		}
		return true;
	}

	/**
	 * Weights and code (resumed file by file)
	 * @return false if canceled
	 */
	boolean downloadModel() throws Exception {
		Vector<String[]> aFiles = new Vector<String[]>();
		for(Object[] aF : model.files()) {
			aFiles.add(new String[] {HF+model.repo+"/resolve/main/"+aF[0], (String)aF[0], ""+aF[1]});
		}
		if(!downloadFiles(model.getDir(tdeDir), aFiles, "AI.DownloadingColorModel")) {
			return false;
		}
		aFiles.clear();
		for(Object[] aF : CODE_FILES) {
			aFiles.add(new String[] {CODE_URL+aF[0], (String)aF[0], ""+aF[1]});
		}
		return downloadFiles(getCodeDir(tdeDir), aFiles, "AI.DownloadingColorModel");
	}
}
