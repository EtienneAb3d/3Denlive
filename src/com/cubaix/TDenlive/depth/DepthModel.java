package com.cubaix.TDenlive.depth;

import java.io.File;

/**
 * Depth Anything V2 models (ONNX conversions of https://huggingface.co/depth-anything),
 * downloaded at the first use into ~/3Denlive/Models/.
 * Small: Apache-2.0 (commercial use allowed). Large: CC-BY-NC-4.0 (non commercial use only).
 * Both run on the GPU when there is one with enough free memory (gpuMemory), else on the CPU
 * (Small: under a second per image, Large: several seconds).
 */
public class DepthModel {
	public static final DepthModel SMALL = new DepthModel("Small"
			,"https://huggingface.co/onnx-community/depth-anything-v2-small/resolve/main/onnx/model.onnx"
			,99060839L,true,1536L*1024*1024);
	public static final DepthModel LARGE = new DepthModel("Large"
			,"https://huggingface.co/onnx-community/depth-anything-v2-large/resolve/main/onnx/model.onnx"
			,1336922232L,false,5120L*1024*1024);
	public static final DepthModel[] MODELS = {SMALL, LARGE};

	public final String id;
	public final String url;
	public final long size;
	//Licence allowing a commercial use (else non commercial only)
	public final boolean isCommercial;
	//GPU memory needed to run it (bytes): peak footprint measured with WebGPU on Apple Silicon
	//(Small 1.4 GB, Large 4.9 GB with the JVM) for a 924x518 input, with a margin
	public final long gpuMemory;

	DepthModel(String aId,String aUrl,long aSize,boolean aIsCommercial,long aGpuMemory) {
		id = aId;
		url = aUrl;
		size = aSize;
		isCommercial = aIsCommercial;
		gpuMemory = aGpuMemory;
	}

	public static File getModelsDir(String aTdeDir) {
		return new File(aTdeDir, "Models");
	}

	public File getFile(String aTdeDir) {
		return new File(getModelsDir(aTdeDir), "depth-anything-v2-"+id.toLowerCase()+".onnx");
	}

	public boolean isDownloaded(String aTdeDir) {
		File aF = getFile(aTdeDir);
		return aF.isFile() && aF.length() == size;
	}

	public static DepthModel byId(String aId) {
		for(DepthModel aM : MODELS) {
			if(aM.id.equalsIgnoreCase(aId)) {
				return aM;
			}
		}
		return SMALL;
	}

	/**
	 * Size in GB with one decimal, for the user
	 */
	public static String gb(long aBytes) {
		return String.format("%.1f", aBytes/(1024.0*1024.0*1024.0));
	}

	/**
	 * Size in MB, for the user
	 */
	public static String mb(long aBytes) {
		return Math.round(aBytes/(1024.0*1024.0))+"";
	}
}
