package com.cubaix.TDenlive.ai;

import java.io.File;
import java.util.Vector;

import com.cubaix.TDenlive.depth.DepthModel;

/**
 * Creative filling of the holes of the views of a 3D scene (model3d.SmartFill, filling model SDXL) with Stable
 * Diffusion XL Inpainting 0.1 (diffusers/stable-diffusion-xl-1.0-inpainting-0.1 pinned by commit, CreativeML Open
 * RAIL++-M, fp16 weights about 6.9 GB into ~/3Denlive/Models/SDXL-Inpainting/), run by res/ai/sdxl_inpaint.py in the
 * shared Python environment, kept loaded (resident, tag TAG). GPU only (CUDA or Apple Silicon): on the CPU a view
 * takes minutes.
 */
public class FillGenerator extends PythonAI {
	static final long GB = 1024L*1024*1024;
	static final String DIR = "SDXL-Inpainting";
	static final String REPO = "diffusers/stable-diffusion-xl-1.0-inpainting-0.1";
	static final String COMMIT = "115134f363124c53c7d878647567d04daf26e41e";
	static final Object[][] FILES = {
		{"model_index.json", 0L},
		{"scheduler/scheduler_config.json", 0L},
		{"text_encoder/config.json", 0L},
		{"text_encoder/model.fp16.safetensors", 246144867L},
		{"text_encoder_2/config.json", 0L},
		{"text_encoder_2/model.fp16.safetensors", 1389382884L},
		{"tokenizer/merges.txt", 0L},
		{"tokenizer/special_tokens_map.json", 0L},
		{"tokenizer/tokenizer_config.json", 0L},
		{"tokenizer/vocab.json", 0L},
		{"tokenizer_2/merges.txt", 0L},
		{"tokenizer_2/special_tokens_map.json", 0L},
		{"tokenizer_2/tokenizer_config.json", 0L},
		{"tokenizer_2/vocab.json", 0L},
		{"unet/config.json", 0L},
		{"unet/diffusion_pytorch_model.fp16.safetensors", 5135178560L},
		{"vae/config.json", 0L},
		{"vae/diffusion_pytorch_model.fp16.safetensors", 167335338L},
	};
	//Files of the model (bytes)
	public static final long DOWNLOAD_SIZE = 246144867L+1389382884L+5135178560L+167335338L;
	//GPU memory needed (fp16 weights 6.9 GB + the activations of an image of about 1 Mpixel)
	public static final long GPU_MEMORY = 10*GB;
	public static final String TAG = "fill sdxl";
	//Pixels of the image given to the model (its training: 1024 x 1024)
	public static final int PIXELS = 1024*1024;

	static File getDir(String aTdeDir) {
		return new File(DepthModel.getModelsDir(aTdeDir), DIR);
	}

	public static long bytesToDownload(String aTdeDir) {
		return PythonAI.bytesToDownload(getDir(aTdeDir), FILES);
	}

	public static boolean isDownloaded(String aTdeDir) {
		return bytesToDownload(aTdeDir) == 0;
	}

	/**
	 * This machine can run it: Python and a GPU usable by PyTorch (NVIDIA or Apple Silicon)
	 */
	public static boolean capable() {
		return hasPython() && hasGpu();
	}

	/**
	 * Ready to fill now: the environment and the model there
	 */
	public static boolean isReady(String aTdeDir) {
		return capable() && isEnvInstalled(aTdeDir, SHARED) && isDownloaded(aTdeDir);
	}

	public volatile String device = null;

	public FillGenerator(String aTdeDir,Progress aProgress) {
		super(aTdeDir, aProgress);
	}

	@Override
	String stepKey() {
		return "AI.FillComputing";
	}

	@Override
	boolean handleLine(String aL) {
		if(aL.startsWith("DEVICE ")) {
			device = aL.substring(7).trim();
			System.out.println("AI fill device: "+device);
			return true;
		}
		return false;
	}

	/**
	 * Install the environment and download the model if needed
	 * @return false if canceled
	 */
	public boolean install() throws Exception {
		if(!installEnv() || canceled()) {
			return false;
		}
		Vector<String[]> aFiles = new Vector<String[]>();
		for(Object[] aF : FILES) {
			aFiles.add(new String[] {HF+REPO+"/resolve/"+COMMIT+"/"+aF[0], (String)aF[0], ""+aF[1]});
		}
		return downloadFiles(getDir(tdeDir), aFiles, "AI.DownloadingFillModel");
	}

	static final Object RUN = new Object();

	/**
	 * Fill the holes of aIn (mask aMask: white = hole) into aOut (the whole image made by the model), one at a time
	 * @return false if canceled
	 */
	public boolean fill(File aIn,File aMask,File aOut,int aSeed,int aSteps) throws Exception {
		synchronized(RUN) {
			if(!isLoaded(TAG)) {
				beforeLoad(TAG);
			}
			File aScript = copyScript("sdxl_inpaint.py", getDir(tdeDir));
			Vector<String> aCmd = pythonCommand(aScript);
			aCmd.add("--model-dir"); aCmd.add(getDir(tdeDir).getAbsolutePath());
			aCmd.add("--device"); aCmd.add("auto");
			Vector<String> aReq = new Vector<String>();
			aReq.add("--input"); aReq.add(aIn.getAbsolutePath());
			aReq.add("--mask"); aReq.add(aMask.getAbsolutePath());
			aReq.add("--out"); aReq.add(aOut.getAbsolutePath());
			aReq.add("--seed"); aReq.add(""+aSeed);
			aReq.add("--steps"); aReq.add(""+aSteps);
			String aDone = runResident(TAG, GPU_MEMORY, aCmd, aReq);
			if(aDone == null) {
				return false;
			}
			if(!aOut.isFile()) {
				throw new Exception("No image produced (see log/3De.log)");
			}
			return true;
		}
	}
}
