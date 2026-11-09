package com.cubaix.TDenlive.depth;

import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;

import javax.imageio.ImageIO;

import com.cubaix.TDenlive.TDConfig;
import com.cubaix.TDenlive.ffmpeg.FfmpegDecoder;
import com.cubaix.TDenlive.ffmpeg.FfmpegRawEncoder;
import com.cubaix.TDenlive.medias.Media;
import com.cubaix.TDenlive.medias.StereoImage;
import com.cubaix.TDenlive.medias.StereoMovie;
import com.cubaix.TDenlive.utils.StringUtils;

/**
 * Builds the depth maps of an image or a video media with a DepthEstimator: one file per source file,
 * next to it (see ComputedDepthmap.depthPath(): same name + _3eDepthMap, .png or .mp4 without sound),
 * in the same stereo layout (a side by side source gives a side by side map), white = front.
 * The views of the source all get their depth map, whatever the processor: Depthmap uses the left one,
 * DepthmapStereo both.
 */
public class DepthConverter {
	public interface Progress {
		/** Current step (language key, see DepthConvert.* entries), aDone of aTotal (aTotal <= 0: unknown) */
		void status(String aKey,long aDone,long aTotal);
		boolean isCanceled();
	}

	String tdeDir;
	String workDir;
	DepthModel model;
	Progress progress;
	DepthEstimator estimator = null;
	//Provider used to compute (CPU, WebGPU...), null if all was in the cache (kept after the end)
	public String provider = null;
	//Why the CPU is used (DepthEstimator.CPU_REASON_*), null on the GPU
	public String cpuReason = null;
	//Free GPU memory found (bytes), -1 if unknown
	public long gpuFree = -1;

	public DepthConverter(String aTdeDir,String aWorkDir,DepthModel aModel,Progress aProgress) {
		tdeDir = aTdeDir;
		workDir = aWorkDir;
		model = aModel;
		progress = aProgress;
	}

	/**
	 * Media that can be converted: still image or video files (not colors, shapes, SVG...)
	 */
	public static boolean isConvertible(Media aM) {
		return aM != null && (aM.getClass() == StereoImage.class || aM.getClass() == StereoMovie.class)
				&& ((StereoImage)aM).getPathL() != null;
	}

	/**
	 * The media is an image (or video) with its depth map, for the Depthmap processor (2D + depth map):
	 * not a single 2D file, and its right view is gray while its left one is not
	 * (a black and white stereo pair counts as an image with its depth map).
	 */
	public static boolean hasDepthmap(StereoImage aSI) {
		String aPL = aSI.getPathL();
		String aPR = aSI.getPathR();
		if(aSI.origStereo == 0 && aPL != null && aPL.equals(aPR)) {
			return false;
		}
		if(aPR != null && aPR.contains(ComputedDepthmap.SUFFIX)) {
			return true;
		}
		BufferedImage aL;
		BufferedImage aR;
		synchronized (aSI) {
			aL = aSI.loadedLeft;
			aR = aSI.loadedRight;
		}
		if(aL == null || aR == null) {
			aL = aSI.getWorkLeft(TDConfig.PROCESSING_MODE_WORK, aSI.getTimePosMS());
			aR = aSI.getWorkRight(TDConfig.PROCESSING_MODE_WORK, aSI.getTimePosMS());
		}
		if(aL == null || aR == null) {
			return true;//Unknown, don't ask
		}
		return isGray(aR) && !isGray(aL);
	}

	/**
	 * At least 97% of the pixels are gray (sampled)
	 */
	static boolean isGray(BufferedImage aBI) {
		int aW = aBI.getWidth();
		int aH = aBI.getHeight();
		int aCount = 0;
		int aGray = 0;
		for(int y = 0;y < 64;y++) {
			for(int x = 0;x < 64;x++) {
				int aP = aBI.getRGB((int)((x+0.5)*aW/64), (int)((y+0.5)*aH/64));
				int aRed = (aP >> 16) & 0xFF;
				int aGreen = (aP >> 8) & 0xFF;
				int aBlue = aP & 0xFF;
				aCount++;
				if(Math.max(Math.abs(aRed-aGreen), Math.max(Math.abs(aGreen-aBlue), Math.abs(aRed-aBlue))) <= 8) {
					aGray++;
				}
			}
		}
		return aGray >= 0.97*aCount;
	}

	boolean canceled() {
		return progress != null && progress.isCanceled();
	}

	void status(String aKey,long aDone,long aTotal) {
		if(estimator != null) {
			//May change during the conversion (GPU failure)
			provider = estimator.provider;
			cpuReason = estimator.cpuReason;
			gpuFree = estimator.gpuFree;
		}
		if(progress != null) {
			progress.status(aKey, aDone, aTotal);
		}
	}

	/**
	 * Download the ONNX Runtime jar and the model if needed, then load the model
	 * @return false if canceled
	 */
	boolean prepare() throws Exception {
		if(estimator != null) {
			return true;
		}
		if(!DepthEstimator.isOrtDownloaded(tdeDir)) {
			if(!Downloader.download(DepthEstimator.ortUrl(), DepthEstimator.getOrtJar(tdeDir), DepthEstimator.ortSize(), downloadProgress("DepthConvert.DownloadingRuntime"))) {
				return false;
			}
		}
		if(!model.isDownloaded(tdeDir)) {
			if(!Downloader.download(model.url, model.getFile(tdeDir), model.size, downloadProgress("DepthConvert.DownloadingModel"))) {
				return false;
			}
		}
		status("DepthConvert.LoadingModel", 0, 0);
		estimator = new DepthEstimator(tdeDir, model);
		return !canceled();
	}

	Downloader.Progress downloadProgress(String aKey) {
		return new Downloader.Progress() {
			@Override
			public void downloaded(long aDone, long aTotal) {
				status(aKey, aDone, aTotal);
			}
			@Override
			public boolean isCanceled() {
				return canceled();
			}
		};
	}

	/**
	 * Builds the depth map files of the media (aForce: even if they exist, e.g. with another model)
	 * @return {left, right} depth map files (see ComputedDepthmap.find()), null if canceled
	 */
	public String[] convert(StereoImage aSI,boolean aForce) throws Exception {
		try {
			String[] aSrcs = ComputedDepthmap.sources(aSI);
			boolean aVideo = aSI instanceof StereoMovie;
			String[] aOuts = new String[aSrcs.length];
			boolean aAllThere = true;
			for(int i = 0;i < aSrcs.length;i++) {
				aOuts[i] = ComputedDepthmap.depthPath(aSrcs[i], ComputedDepthmap.writeDir(workDir, aSrcs[i]), aVideo);
				aAllThere &= new File(aOuts[i]).isFile();
			}
			String[] aResult = {aOuts[0], aOuts[aOuts.length-1]};
			if(aAllThere && !aForce) {
				return aResult;
			}
			boolean aDone = aVideo ? convertVideo(aSI, aSrcs, aOuts) : convertImage(aSI, aOuts);
			//Medias and work copies of the previous files are out of date
			ComputedDepthmap.forget(workDir, aResult);
			return aDone ? aResult : null;
		}
		finally {
			if(estimator != null) {
				estimator.close();
				estimator = null;
			}
		}
	}

	static BufferedImage toRgb(BufferedImage aBI) {
		BufferedImage aOut = new BufferedImage(aBI.getWidth(), aBI.getHeight(), BufferedImage.TYPE_INT_RGB);
		Graphics2D aG = aOut.createGraphics();
		aG.drawImage(aBI, 0, 0, null);
		aG.dispose();
		return aOut;
	}

	BufferedImage depthImage(BufferedImage aBI) throws Exception {
		Object[] aRes = estimator.estimate(aBI);
		float[] aDepth = (float[])aRes[0];
		float[] aRange = DepthEstimator.range(aDepth);
		return DepthEstimator.toImage(aDepth, (Integer)aRes[1], (Integer)aRes[2], aRange[0], aRange[1], aBI.getWidth(), aBI.getHeight());
	}

	/**
	 * PNG written beside then renamed: never a partial file under the final name
	 */
	static void writePng(BufferedImage aBI,String aPath) throws Exception {
		File aTmp = new File(StringUtils.path2processing(aPath));
		ImageIO.write(aBI, "png", aTmp);
		Files.move(aTmp.toPath(), new File(aPath).toPath(), StandardCopyOption.REPLACE_EXISTING);
	}

	boolean convertImage(StereoImage aSI,String[] aOuts) throws Exception {
		//Views as loaded (split side by side, MPO), before rotation: the depth maps follow the rotation of the media
		BufferedImage aL;
		BufferedImage aR;
		synchronized (aSI) {
			aL = toRgb(aSI.loadedLeft);
			aR = aSI.loadedRight == null ? aL : toRgb(aSI.loadedRight);
		}
		if(!prepare()) {
			return false;
		}
		if(aOuts.length == 2) {
			//Two files: one map each
			status("DepthConvert.Computing", 0, 2);
			writePng(depthImage(aL), aOuts[0]);
			if(canceled()) {
				return false;
			}
			status("DepthConvert.Computing", 1, 2);
			writePng(depthImage(aR), aOuts[1]);
			return !canceled();
		}
		if(aSI.origStereo == 0) {
			//A single 2D image
			status("DepthConvert.Computing", 0, 1);
			writePng(depthImage(aL), aOuts[0]);
			return !canceled();
		}
		//Side by side (or MPO): both maps in the layout of the source, X (1) = right|left, P (2) = left|right
		status("DepthConvert.Computing", 0, 2);
		BufferedImage aDL = depthImage(aL);
		if(canceled()) {
			return false;
		}
		status("DepthConvert.Computing", 1, 2);
		BufferedImage aDR = depthImage(aR);
		int aW = aDL.getWidth();
		BufferedImage aSbs = new BufferedImage(2*aW, aDL.getHeight(), BufferedImage.TYPE_INT_RGB);
		Graphics2D aG = aSbs.createGraphics();
		aG.drawImage(aSI.origStereo == 1 ? aDR : aDL, 0, 0, null);
		aG.drawImage(aSI.origStereo == 1 ? aDL : aDR, aW, 0, aW, aDL.getHeight(), null);
		aG.dispose();
		writePng(aSbs, aOuts[0]);
		return !canceled();
	}

	/**
	 * Depth range smoothed along the frames of a video (no flicker of the levels)
	 */
	static class Levels {
		float low = Float.NaN;
		float high = Float.NaN;

		void add(float[] aRange) {
			if(Float.isNaN(low)) {
				low = aRange[0];
				high = aRange[1];
			}
			else {
				low = 0.9f*low+0.1f*aRange[0];
				high = 0.9f*high+0.1f*aRange[1];
			}
		}
	}

	boolean convertVideo(StereoImage aSI,String[] aSrcs,String[] aOuts) throws Exception {
		if(!prepare()) {
			return false;
		}
		//Side by side: the two halves of each frame, in the same place in the map
		boolean aSplit = aSI.origStereo != 0;
		for(int i = 0;i < aSrcs.length;i++) {
			if(!videoPass(aSrcs[i], aOuts[i], aSplit, aSI.getDurationMS(), i, aSrcs.length)) {
				return false;
			}
		}
		return true;
	}

	BufferedImage depthOf(BufferedImage aView,Levels aLevels) throws Exception {
		Object[] aRes = estimator.estimate(aView);
		float[] aDepth = (float[])aRes[0];
		aLevels.add(DepthEstimator.range(aDepth));
		return DepthEstimator.toImage(aDepth, (Integer)aRes[1], (Integer)aRes[2], aLevels.low, aLevels.high, aView.getWidth(), aView.getHeight());
	}

	boolean videoPass(String aPath,String aOut,boolean aSplit,long aDurationMS,int aPass,int aNbPasses) throws Exception {
		FfmpegDecoder aDec = new FfmpegDecoder(aPath, 0, -1, FfmpegDecoder.FILESIZE, FfmpegDecoder.FILEFPS);
		FfmpegRawEncoder aEnc = null;
		Levels[] aLevels = {new Levels(), new Levels()};
		boolean aOk = false;
		long aFrame = 0;
		double aFps = -1;
		try {
			while(!canceled()) {
				BufferedImage aBI = aDec.getImg();
				if(aBI == null) {
					aOk = aFrame > 0;
					break;
				}
				if(aFrame == 0) {
					//Read by ffmpeg before the first image
					aFps = aDec.getFps();
					if(aFps <= 0) {
						aFps = 25;
						System.err.println("Unknown frame rate, 25 used: "+aPath);
					}
				}
				long aTotal = Math.max(1, (long)(aDurationMS*aFps/1000));
				status("DepthConvert.Computing", aPass*aTotal+aFrame, aNbPasses*aTotal);
				BufferedImage aDepth;
				if(aSplit) {
					int aW = aBI.getWidth()/2;
					int aH = aBI.getHeight();
					aDepth = new BufferedImage(2*aW, aH, BufferedImage.TYPE_INT_RGB);
					Graphics2D aG = aDepth.createGraphics();
					aG.drawImage(depthOf(aBI.getSubimage(0, 0, aW, aH), aLevels[0]), 0, 0, null);
					aG.drawImage(depthOf(aBI.getSubimage(aW, 0, aW, aH), aLevels[1]), aW, 0, null);
					aG.dispose();
				}
				else {
					aDepth = depthOf(aBI, aLevels[0]);
				}
				if(aEnc == null) {
					aEnc = new FfmpegRawEncoder(aOut, aDepth.getWidth(), aDepth.getHeight(), aFps);
				}
				aEnc.addImg(aDepth);
				aFrame++;
			}
		}
		finally {
			aDec.stop();
			if(aEnc != null && !aEnc.finish(aOk && !canceled())) {
				aOk = false;
			}
		}
		return aOk && !canceled();
	}
}
