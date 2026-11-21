package com.cubaix.TDenlive.ai;

import java.io.File;
import java.util.Vector;

import java.awt.image.BufferedImage;

import com.cubaix.TDenlive.depth.DepthEstimator;
import com.cubaix.TDenlive.depth.DepthModel;
import com.cubaix.TDenlive.depth.DepthScene;
import com.cubaix.TDenlive.depth.Downloader;
import com.cubaix.TDenlive.utils.ImageUtils;

/**
 * Navigable 3D scene from one photo, by one of these models (Model, MODELS; TDConfig.aiSceneModel):
 * - DEPTH_SMALL / DEPTH_LARGE: the depth map of Depth Anything V2 Small (Apache-2.0) / Large (CC-BY-NC-4.0), computed
 *   in Java by depth.DepthEstimator (ONNX Runtime, no Python), turned into one Gaussian per pixel of the grid of the
 *   photo by depth.DepthScene (relative depth: assumed depth range and field of view), nothing behind.
 * - DEPTH_PRO: Depth Pro of Apple (Bochkovskii et al., ICLR 2025, weights apple/DepthPro-hf, Apple Machine
 *   Learning Research Model licence), native in transformers, in the SHARED environment, res/ai/depthpro_scene.py:
 *   metric depth with sharp edges and the field of view of the photo; one Gaussian per pixel of the grid of the
 *   photo, nothing behind (the holes are made and filled by model3d.SmartFill). fp16 on the GPU; measured on Apple
 *   Silicon: 2.7 s a photo once loaded (any detail: the network always sees 1536 x 1536), about 6 GB.
 * - FLASH3D (default):
 * Flash3D (Szymanowicz, Insafutdinov et al., University of Oxford, 3DV 2025,
 * https://github.com/eldar/flash3d): the metric depth of UniDepth V1 ViT-L and a ResNet-50 predict 2 3D Gaussians
 * per pixel (the second for what the first hides), written as a 3D Gaussian Splatting PLY (model3d.GaussianScene
 * reads and renders it, GUI.AiSceneGUI shows it with a pair of stereo cameras).
 * Run by res/ai/flash3d_scene.py (resident) in its own Python environment FLASH3D_ENV (torchvision, absent from the
 * shared one), on CUDA, MPS or the CPU, fp32. Into ~/3Denlive/Models/Flash3D/ at the first use: the configuration and
 * weights of einsafutdinov/flash3d (MIT), those of UniDepth lpiccinelli/unidepth-v1-vitl14 (CC-BY-NC-4.0) in
 * unidepth-v1-vitl14/, and the inference code of the Space of its authors (folder flash3d/ of
 * spaces/einsafutdinov/flash3d, CODE_FILES at CODE_COMMIT) in code/; nothing else is downloaded by the script.
 * Measured on Apple Silicon (MPS): 7 s for a photo, whatever the detail; peak 5 GB.
 */
public class SceneGenerator extends PythonAI {
	static final long GB = 1024L*1024*1024;
	static final String DIR = "Flash3D";
	static final String MODEL_REPO = "einsafutdinov/flash3d";
	static final String MODEL_COMMIT = "1431139679ff5abce63363f334b2c9e8999d62ed";
	static final String UNIDEPTH_REPO = "lpiccinelli/unidepth-v1-vitl14";
	static final String UNIDEPTH_COMMIT = "1937d33086564a8cbb71adb7aaa546400083f8ea";
	static final String UNIDEPTH_DIR = "unidepth-v1-vitl14";
	static final String CODE_COMMIT = "2d19fdff96157c95a9ce8a53c71402ece6dd7a34";
	static final String CODE_URL = HF+"spaces/einsafutdinov/flash3d/resolve/"+CODE_COMMIT+"/";
	static final Object[][] MODEL_FILES = {
		{"config_re10k_v1.yaml", 1743L},
		{"model_re10k_v1.pth", 731910673L},
	};
	static final Object[][] UNIDEPTH_FILES = {
		{"config.json", 912L},
		{"model.safetensors", 1388090576L},
	};
	//Inference code (flash3d/ of the Space): the networks and UniDepth V1 (no Gradio application)
	static final String[] CODE_FILES = {
		"networks/depth_decoder.py", "networks/gaussian_decoder.py", "networks/gaussian_predictor.py",
		"networks/layers.py", "networks/resnet_encoder.py", "networks/unidepth.py", "networks/unidepth_extension.py",
		"unidepth/layers/__init__.py", "unidepth/layers/activation.py", "unidepth/layers/attention.py",
		"unidepth/layers/convnext.py", "unidepth/layers/drop_path.py", "unidepth/layers/layer_scale.py",
		"unidepth/layers/mlp.py", "unidepth/layers/nystrom_attention.py", "unidepth/layers/positional_encoding.py",
		"unidepth/layers/upsample.py", "unidepth/models/__init__.py", "unidepth/models/backbones/__init__.py",
		"unidepth/models/backbones/convnext.py", "unidepth/models/backbones/convnext2.py",
		"unidepth/models/backbones/dinov2.py", "unidepth/models/backbones/metadinov2/__init__.py",
		"unidepth/models/backbones/metadinov2/attention.py", "unidepth/models/backbones/metadinov2/block.py",
		"unidepth/models/backbones/metadinov2/dino_head.py", "unidepth/models/backbones/metadinov2/drop_path.py",
		"unidepth/models/backbones/metadinov2/layer_scale.py", "unidepth/models/backbones/metadinov2/mlp.py",
		"unidepth/models/backbones/metadinov2/patch_embed.py", "unidepth/models/backbones/metadinov2/swiglu_ffn.py",
		"unidepth/models/encoder.py", "unidepth/models/unidepthv1/__init__.py", "unidepth/models/unidepthv1/decoder.py",
		"unidepth/models/unidepthv1/unidepthv1.py", "unidepth/ops/__init__.py", "unidepth/ops/losses.py",
		"unidepth/ops/scheduler.py", "unidepth/utils/__init__.py", "unidepth/utils/constants.py",
		"unidepth/utils/distributed.py", "unidepth/utils/ema_torch.py", "unidepth/utils/evaluation_depth.py",
		"unidepth/utils/geometric.py", "unidepth/utils/misc.py", "unidepth/utils/positional_embedding.py",
		"unidepth/utils/sht.py", "unidepth/utils/visualization.py",
	};
	//Versions tested (macOS arm64, MPS)
	public static final Env FLASH3D_ENV = new Env("flash3d-env", "1", new String[] {"torch==2.15.0", "torchvision==0.29.2"
			, "einops==0.8.2", "timm==1.0.31", "huggingface-hub==2.4.0", "safetensors==0.8.0", "omegaconf==2.4.0"
			, "numpy", "pillow", "scipy"}, new String[0]);
	//Peak measured on Apple Silicon (fp32: ViT-L of UniDepth, ResNet-50 and its decoders)
	public static final long GPU_MEMORY = 5*GB;
	public static final String TAG = "scene flash3d";

	//Depth Pro (transformers format, pinned)
	static final String DEPTH_PRO_REPO = "apple/DepthPro-hf";
	static final String DEPTH_PRO_COMMIT = "de816c8ce7168afcb231f96d501d72b869d0beda";
	static final Object[][] DEPTH_PRO_FILES = {
		{"config.json", 2916L},
		{"preprocessor_config.json", 280L},
		{"model.safetensors", 1904185492L},
	};

	/**
	 * A model making the scenes: folder in Models/, environment, GPU memory, resident tag, script, Gaussians per
	 * pixel (layers), licence (language key), seconds per image on the GPU once loaded (measured on Apple Silicon)
	 */
	public static class Model {
		public final String id;
		final String dir;
		final Env env;
		public final long gpuMemory;
		final String tag;
		final String script;
		public final int layers;
		public final String licenceKey;
		public final double seconds;
		//Depth Anything V2 model of the scenes made from a depth map (null: a Python model)
		public final DepthModel depthModel;

		Model(String aId,String aDir,Env aEnv,long aGpuMemory,String aTag,String aScript,int aLayers,String aLicenceKey,double aSeconds) {
			this(aId, aDir, aEnv, aGpuMemory, aTag, aScript, aLayers, aLicenceKey, aSeconds, null);
		}

		Model(String aId,String aDir,Env aEnv,long aGpuMemory,String aTag,String aScript,int aLayers,String aLicenceKey,double aSeconds,DepthModel aDepthModel) {
			id = aId;
			dir = aDir;
			env = aEnv;
			gpuMemory = aGpuMemory;
			tag = aTag;
			script = aScript;
			layers = aLayers;
			licenceKey = aLicenceKey;
			seconds = aSeconds;
			depthModel = aDepthModel;
		}

		/** It runs on this platform (Python and PyTorch, or ONNX Runtime for the depth maps) */
		public boolean isSupported() {
			return depthModel != null ? DepthEstimator.isPlatformSupported() : hasPython();
		}

		/** Name shown (AI.SceneModel<id>) */
		public String key() {
			return "AI.SceneModel"+id;
		}
	}

	public static final Model DEPTH_PRO = new Model("DepthPro", "DepthPro-hf", SHARED, 6*GB, "scene depthpro", "depthpro_scene.py", 1, "AI.SceneLicenceDepthPro", 3);
	public static final Model FLASH3D = new Model("Flash3D", DIR, FLASH3D_ENV, GPU_MEMORY, TAG, "flash3d_scene.py", 2, "AI.SceneLicence", 0.6);
	public static final Model DEPTH_SMALL = new Model("DepthMapSmall", null, null, DepthModel.SMALL.gpuMemory, null, null, 1, "AI.SceneLicenceDepthMapSmall", 0.5, DepthModel.SMALL);
	public static final Model DEPTH_LARGE = new Model("DepthMapLarge", null, null, DepthModel.LARGE.gpuMemory, null, null, 1, "AI.SceneLicenceDepthMapLarge", 2, DepthModel.LARGE);
	//The default one first
	public static final Model[] MODELS = {FLASH3D, DEPTH_PRO, DEPTH_SMALL, DEPTH_LARGE};

	/** The model of an id ("" or unknown: the advised one) */
	public static Model modelById(String aId) {
		for(Model aM : MODELS) {
			if(aM.id.equalsIgnoreCase(aId)) {
				return aM;
			}
		}
		return MODELS[0];
	}

	/**
	 * Detail: pixels of the image given to the model (2 Gaussians each)
	 */
	public static class Detail {
		public final String id;
		public final int pixels;

		Detail(String aId,int aPixels) {
			id = aId;
			pixels = aPixels;
		}

		/** Number of Gaussians (about) of the scenes of aModel */
		public int gaussians(Model aModel) {
			return aModel.layers*pixels;
		}
	}

	//The size of its training (256 x 384)
	public static final Detail STANDARD = new Detail("Standard", 256*384);
	//4 times the pixels: sharper, the same shapes (advised)
	public static final Detail FINE = new Detail("Fine", 4*256*384);
	public static final Detail[] DETAILS = {FINE, STANDARD};

	public static Detail detailById(String aId) {
		for(Detail aD : DETAILS) {
			if(aD.id.equalsIgnoreCase(aId)) {
				return aD;
			}
		}
		return null;
	}

	static File getRoot(String aTdeDir) {
		return new File(DepthModel.getModelsDir(aTdeDir), DIR);
	}

	static File getRoot(String aTdeDir,Model aModel) {
		return new File(DepthModel.getModelsDir(aTdeDir), aModel.dir);
	}

	static File getUniDepthDir(String aTdeDir) {
		return new File(getRoot(aTdeDir), UNIDEPTH_DIR);
	}

	static File getCodeDir(String aTdeDir) {
		return new File(getRoot(aTdeDir), "code");
	}

	/**
	 * Model, code and Python environment of aModel still to download / install (bytes)
	 */
	public static long bytesToDownload(String aTdeDir,Model aModel) {
		if(aModel.depthModel != null) {
			return (DepthEstimator.isOrtDownloaded(aTdeDir) ? 0 : DepthEstimator.ortSize())
					+(aModel.depthModel.isDownloaded(aTdeDir) ? 0 : aModel.depthModel.size);
		}
		if(aModel == DEPTH_PRO) {
			return PythonAI.bytesToDownload(getRoot(aTdeDir, aModel), DEPTH_PRO_FILES)+envBytesToInstall(aTdeDir, aModel.env);
		}
		long aB = PythonAI.bytesToDownload(getRoot(aTdeDir), MODEL_FILES)+PythonAI.bytesToDownload(getUniDepthDir(aTdeDir), UNIDEPTH_FILES);
		for(String aF : CODE_FILES) {
			if(!new File(new File(getCodeDir(aTdeDir), "flash3d"), aF.replace('/', File.separatorChar)).isFile()) {
				aB += 16*1024;
			}
		}
		return aB+envBytesToInstall(aTdeDir, FLASH3D_ENV);
	}

	/**
	 * One of the models runs on this platform (Python and PyTorch, or ONNX Runtime; GPU or CPU)
	 */
	public static boolean isPlatformSupported() {
		return hasPython() || DepthEstimator.isPlatformSupported();
	}

	/**
	 * Device: null for the GPU, else the reason of the CPU (DepthEstimator.CPU_REASON_*)
	 */
	public static String cpuReason(Model aModel) {
		if(aModel.depthModel != null) {
			//ONNX Runtime: WebGPU on Apple Silicon, CUDA on NVIDIA (DepthEstimator decides at the loading)
			if(!(isMac() ? isArm64() : DepthEstimator.useGpuRuntime())) {
				return DepthEstimator.CPU_REASON_NO_GPU;
			}
			long aFree = isMac() ? -1 : availableGpuMemory();
			return aFree >= 0 && aFree < aModel.gpuMemory ? DepthEstimator.CPU_REASON_GPU_MEMORY : null;
		}
		if("auto".equals(loadedDevice(aModel.tag)) && hasGpu()) {
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

	//Device used by the script (DEVICE message): cuda, mps or cpu
	public volatile String device = null;

	final Model model;
	//Depth maps: the estimator, loaded at the first image, kept until close(); levels smoothed over the images
	//of the same generator (frames of a video: no jump of depth from one to the next)
	DepthEstimator estimator = null;
	float levelLow = Float.NaN;
	float levelHigh = Float.NaN;

	public SceneGenerator(String aTdeDir,Model aModel,Progress aProgress) {
		super(aTdeDir, aProgress);
		model = aModel;
	}

	@Override
	Env env() {
		return model.env;
	}

	@Override
	String stepKey() {
		return "AI.SceneComputing";
	}

	@Override
	boolean handleLine(String aL) {
		if(aL.startsWith("DEVICE ")) {
			device = aL.substring(7).trim();
			System.out.println("AI 3D scene device: "+device);
			return true;
		}
		return false;
	}

	/**
	 * Install what is missing, then make the scene of the photo aInput into the PLY aOut
	 * @return false if canceled
	 */
	public boolean generate(File aInput,Detail aDetail,File aOut) throws Exception {
		try {
			return generate(new File[] {aInput}, aDetail, new File[] {aOut}, false);
		}
		finally {
			close();
		}
	}

	/**
	 * Stop the depth estimator (scenes from a depth map), if any
	 */
	public void close() {
		DepthEstimator aE = estimator;
		estimator = null;
		if(aE != null) {
			aE.close();
		}
	}

	/**
	 * Install what is missing, then make the scene of each image of aInputs into aOuts (same order): PLY files,
	 * or compact ones (aCompact: GaussianScene.COMPACT_MAGIC, the frames of a video), in one request
	 * @return false if canceled
	 */
	public boolean generate(File[] aInputs,Detail aDetail,File[] aOuts,boolean aCompact) throws Exception {
		if(model.depthModel != null) {
			return generateFromDepth(aInputs, aDetail, aOuts, aCompact);
		}
		if(!installEnv() || !(model == DEPTH_PRO ? downloadDepthPro() : downloadModel()) || canceled()) {
			return false;
		}
		//Another model kept loaded is unloaded first, so that its memory counts as free for the device
		if(!isLoaded(model.tag)) {
			beforeLoad(model.tag);
		}
		File aRoot = getRoot(tdeDir, model);
		File aScript = copyScript(model.script, aRoot);
		//Arguments of the model (the same: the model kept loaded is used again), then of these images
		Vector<String> aCmd = pythonCommand(aScript);
		aCmd.add("--model-dir"); aCmd.add(aRoot.getAbsolutePath());
		if(model == FLASH3D) {
			aCmd.add("--unidepth-dir"); aCmd.add(getUniDepthDir(tdeDir).getAbsolutePath());
			aCmd.add("--code-dir"); aCmd.add(getCodeDir(tdeDir).getAbsolutePath());
		}
		aCmd.add("--device"); aCmd.add(cpuReason(model) == null ? "auto" : "cpu");
		Vector<String> aReq = new Vector<String>();
		for(int i = 0;i < aInputs.length;i++) {
			aReq.add("--input"); aReq.add(aInputs[i].getAbsolutePath());
			aReq.add("--out"); aReq.add(aOuts[i].getAbsolutePath());
		}
		aReq.add("--pixels"); aReq.add(""+aDetail.pixels);
		if(aCompact) {
			aReq.add("--compact");
		}
		String aDone;
		//No smart filling meanwhile: its inpainter would unload the model (one model kept loaded)
		com.cubaix.TDenlive.model3d.SmartFill.held = true;
		try {
			aDone = runResident(model.tag, model.gpuMemory, aCmd, aReq);
		}
		finally {
			com.cubaix.TDenlive.model3d.SmartFill.held = false;
		}
		if(aDone == null || canceled()) {
			for(File aO : aOuts) {
				aO.delete();
			}
			return false;
		}
		for(File aO : aOuts) {
			if(!aO.isFile()) {
				throw new Exception("No scene produced (see log/3De.log)");
			}
		}
		return true;
	}

	/**
	 * Scenes made from the depth maps of Depth Anything V2 (depth.DepthScene): ONNX Runtime and the model downloaded
	 * if needed, the estimator loaded once (child JVM), then each image
	 * @return false if canceled
	 */
	boolean generateFromDepth(File[] aInputs,Detail aDetail,File[] aOuts,boolean aCompact) throws Exception {
		DepthModel aDM = model.depthModel;
		if(!DepthEstimator.isOrtDownloaded(tdeDir)
				&& !Downloader.download(DepthEstimator.ortUrl(), DepthEstimator.getOrtJar(tdeDir), DepthEstimator.ortSize(), downloadProgress())) {
			return false;
		}
		if(!aDM.isDownloaded(tdeDir) && !Downloader.download(aDM.url, aDM.getFile(tdeDir), aDM.size, downloadProgress())) {
			return false;
		}
		if(canceled()) {
			return false;
		}
		if(estimator == null) {
			status("AI.SceneLoading", null, 0, 0);
			estimator = new DepthEstimator(tdeDir, aDM);
			device = estimator.isGpu() ? "gpu" : "cpu";
			System.out.println("AI 3D scene from depth maps ("+aDM.id+"): "+estimator.provider
					+(estimator.cpuReason != null ? " ("+estimator.cpuReason+")" : ""));
		}
		for(int i = 0;i < aInputs.length;i++) {
			if(canceled()) {
				for(File aO : aOuts) {
					aO.delete();
				}
				return false;
			}
			status("AI.SceneComputing", null, i, aInputs.length);
			long aT0 = System.currentTimeMillis();
			BufferedImage aImg = ImageUtils.loadImage(aInputs[i].getAbsolutePath());
			if(aImg == null) {
				throw new Exception("Image not read: "+aInputs[i]);
			}
			Object[] aRes = estimator.estimate(aImg);
			float[] aDepth = (float[])aRes[0];
			float[] aRange = DepthEstimator.range(aDepth);
			if(Float.isNaN(levelLow)) {
				levelLow = aRange[0];
				levelHigh = aRange[1];
			}
			else {
				levelLow = 0.9f*levelLow+0.1f*aRange[0];
				levelHigh = 0.9f*levelHigh+0.1f*aRange[1];
			}
			DepthScene.write(aImg, aDepth, (Integer)aRes[1], (Integer)aRes[2], levelLow, levelHigh, aDetail.pixels
					, aCompact, aInputs[i].getName(), aOuts[i]);
			System.err.println("Depth map scene of "+aInputs[i]+" in "+(System.currentTimeMillis()-aT0)+" ms -> "+aOuts[i]);
			status("AI.SceneComputing", null, i+1, aInputs.length);
		}
		return !canceled();
	}

	Downloader.Progress downloadProgress() {
		return new Downloader.Progress() {
			@Override
			public void downloaded(long aDone, long aTotal) {
				status("AI.DownloadingSceneModel", null, aDone, aTotal);
			}
			@Override
			public boolean isCanceled() {
				return canceled();
			}
		};
	}

	/**
	 * Weights of Depth Pro (resumed file by file)
	 * @return false if canceled
	 */
	boolean downloadDepthPro() throws Exception {
		Vector<String[]> aFiles = new Vector<String[]>();
		for(Object[] aF : DEPTH_PRO_FILES) {
			aFiles.add(new String[] {HF+DEPTH_PRO_REPO+"/resolve/"+DEPTH_PRO_COMMIT+"/"+aF[0], (String)aF[0], ""+aF[1]});
		}
		return downloadFiles(getRoot(tdeDir, DEPTH_PRO), aFiles, "AI.DownloadingSceneModel");
	}

	/**
	 * Weights of Flash3D and UniDepth, code (resumed file by file)
	 * @return false if canceled
	 */
	boolean downloadModel() throws Exception {
		Vector<String[]> aFiles = new Vector<String[]>();
		for(Object[] aF : MODEL_FILES) {
			aFiles.add(new String[] {HF+MODEL_REPO+"/resolve/"+MODEL_COMMIT+"/"+aF[0], (String)aF[0], ""+aF[1]});
		}
		if(!downloadFiles(getRoot(tdeDir), aFiles, "AI.DownloadingSceneModel")) {
			return false;
		}
		aFiles.clear();
		for(Object[] aF : UNIDEPTH_FILES) {
			aFiles.add(new String[] {HF+UNIDEPTH_REPO+"/resolve/"+UNIDEPTH_COMMIT+"/"+aF[0], (String)aF[0], ""+aF[1]});
		}
		if(!downloadFiles(getUniDepthDir(tdeDir), aFiles, "AI.DownloadingSceneModel")) {
			return false;
		}
		aFiles.clear();
		for(String aF : CODE_FILES) {
			aFiles.add(new String[] {CODE_URL+"flash3d/"+aF, "flash3d/"+aF, "0"});
		}
		return downloadFiles(getCodeDir(tdeDir), aFiles, "AI.DownloadingSceneModel");
	}
}
