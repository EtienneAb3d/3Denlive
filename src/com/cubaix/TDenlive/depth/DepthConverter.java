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
		/** Depth maps just computed {left, right} (shown under construction), at aMediaMS in the media (-1: an image) */
		default void frame(BufferedImage aL,BufferedImage aR,double aMediaMS) {
		}
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
	//Part of a video done when its computation was interrupted (kept, see videoPass()), -1 if not
	public double partDone = -1;
	boolean force = false;

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
			force = aForce;
			if(aForce && aVideo) {
				//Computed again: an interrupted computation is not resumed
				for(String aO : aOuts) {
					deleteParts(aO);
				}
			}
			boolean aDone = aVideo ? convertVideo(aSI, aSrcs, aOuts) : convertImage(aSI, aOuts);
			//Medias and work copies of the previous files are out of date (the partial ones too)
			ComputedDepthmap.forget(workDir, aResult);
			if(aVideo) {
				ComputedDepthmap.forget(workDir, new String[] {ComputedDepthmap.partialPath(aResult[0]), ComputedDepthmap.partialPath(aResult[1])});
			}
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
			BufferedImage aDL = depthImage(aL);
			writePng(aDL, aOuts[0]);
			if(canceled()) {
				return false;
			}
			status("DepthConvert.Computing", 1, 2);
			BufferedImage aDR = depthImage(aR);
			writePng(aDR, aOuts[1]);
			showFrame(aDL, aDR, -1);
			return !canceled();
		}
		boolean aAna = com.cubaix.TDenlive.utils.Anaglyph.isAnaglyph(aSI.anaglyph);
		if(aSI.origStereo == 0 && !aAna) {
			//A single 2D image
			status("DepthConvert.Computing", 0, 1);
			BufferedImage aD = depthImage(aL);
			writePng(aD, aOuts[0]);
			showFrame(aD, aD, -1);
			return !canceled();
		}
		//Side by side (or MPO): both maps in the layout of the source, X (1) = right|left, P (2) = left|right;
		//an anaglyph: one map per view (each in gray from its own color layers), left|right
		BufferedImage aDL, aDR;
		status("DepthConvert.Computing", 0, 2);
		if(aAna) {
			BufferedImage[] aDs = depthPair(gray(aL), gray(aR), null);
			aDL = aDs[0];
			aDR = aDs[1];
		}
		else {
			aDL = depthImage(aL);
			if(canceled()) {
				return false;
			}
			status("DepthConvert.Computing", 1, 2);
			aDR = depthImage(aR);
		}
		int aW = aDL.getWidth();
		BufferedImage aSbs = new BufferedImage(2*aW, aDL.getHeight(), BufferedImage.TYPE_INT_RGB);
		Graphics2D aG = aSbs.createGraphics();
		aG.drawImage(aSI.origStereo == 1 ? aDR : aDL, 0, 0, null);
		aG.drawImage(aSI.origStereo == 1 ? aDL : aDR, aW, 0, aW, aDL.getHeight(), null);
		aG.dispose();
		writePng(aSbs, aOuts[0]);
		showFrame(aDL, aDR, -1);
		return !canceled();
	}

	/**
	 * A view of an anaglyph in gray: the mean of its color layers that hold something (the others are 0), the same
	 * value on R, G, B (the depth model was trained on images of all the layers, a red-only view is far from them)
	 */
	static BufferedImage gray(BufferedImage aView) {
		int aW = aView.getWidth(), aH = aView.getHeight();
		int[] aPix = aView.getRGB(0, 0, aW, aH, null, 0, aW);
		long[] aSum = new long[3];
		for(int p : aPix) {
			aSum[0] += (p>>16) & 0xFF;
			aSum[1] += (p>>8) & 0xFF;
			aSum[2] += p & 0xFF;
		}
		boolean[] aIn = new boolean[3];
		int aN = 0;
		for(int c = 0;c < 3;c++) {
			aIn[c] = aSum[c] > 4L*aPix.length/255+aPix.length/100;
			if(aIn[c]) {
				aN++;
			}
		}
		if(aN == 0) {
			aIn[0] = aIn[1] = aIn[2] = true;
			aN = 3;
		}
		BufferedImage aOut = new BufferedImage(aW, aH, BufferedImage.TYPE_INT_RGB);
		int[] aG = new int[aPix.length];
		for(int i = 0;i < aPix.length;i++) {
			int p = aPix[i];
			int v = ((aIn[0] ? (p>>16) & 0xFF : 0)+(aIn[1] ? (p>>8) & 0xFF : 0)+(aIn[2] ? p & 0xFF : 0))/aN;
			aG[i] = (v<<16)|(v<<8)|v;
		}
		aOut.setRGB(0, 0, aW, aH, aG, 0, aW);
		return aOut;
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
			if(!force && new File(aOuts[i]).isFile()) {
				//Done before an interruption
				continue;
			}
			if(!videoPass(aSrcs[i], aOuts[i], aSplit, aSI.getDurationMS(), i, aSrcs.length, aSrcs.length == 1 && aSI.origStereo == 1,
					com.cubaix.TDenlive.utils.Anaglyph.isAnaglyph(aSI.anaglyph) ? aSI.anaglyph : null)) {
				return false;
			}
		}
		return true;
	}

	/**
	 * The depth maps of the two views of a pair with the same levels (the range of both, smoothed along the frames
	 * when aLevels is shared), so that both eyes have the same depth scale
	 */
	BufferedImage[] depthPair(BufferedImage aL,BufferedImage aR,Levels aLevels) throws Exception {
		Object[] aEL = estimator.estimate(aL);
		Object[] aER = estimator.estimate(aR);
		float[] aRL = DepthEstimator.range((float[])aEL[0]);
		float[] aRR = DepthEstimator.range((float[])aER[0]);
		float[] aRange = {Math.min(aRL[0], aRR[0]), Math.max(aRL[1], aRR[1])};
		float aLow, aHigh;
		if(aLevels != null) {
			aLevels.add(aRange);
			aLow = aLevels.low;
			aHigh = aLevels.high;
		}
		else {
			aLow = aRange[0];
			aHigh = aRange[1];
		}
		return new BufferedImage[] {
				DepthEstimator.toImage((float[])aEL[0], (Integer)aEL[1], (Integer)aEL[2], aLow, aHigh, aL.getWidth(), aL.getHeight()),
				DepthEstimator.toImage((float[])aER[0], (Integer)aER[1], (Integer)aER[2], aLow, aHigh, aR.getWidth(), aR.getHeight())};
	}

	BufferedImage depthOf(BufferedImage aView,Levels aLevels) throws Exception {
		Object[] aRes = estimator.estimate(aView);
		float[] aDepth = (float[])aRes[0];
		aLevels.add(DepthEstimator.range(aDepth));
		return DepthEstimator.toImage(aDepth, (Integer)aRes[1], (Integer)aRes[2], aLevels.low, aLevels.high, aView.getWidth(), aView.getHeight());
	}

	void showFrame(BufferedImage aL,BufferedImage aR,double aMediaMS) {
		if(progress != null) {
			try {
				progress.frame(aL, aR, aMediaMS);
			}
			catch(Throwable t) {
				t.printStackTrace(System.err);
			}
		}
	}

	/**
	 * Interrupted computation of a depth video aOut: aOut + PARTS (properties: model, fps, frames done, segments,
	 * levels), segments aOut.part<k>.mp4 (frames 0.. in order), joined into aOut at the end
	 */
	public static final String PARTS = ".parts";

	static String segment(String aOut,int k) {
		return aOut+".part"+k+".mp4";
	}

	static java.util.Properties readParts(String aOut) {
		File aF = new File(aOut+PARTS);
		if(!aF.isFile()) {
			return null;
		}
		java.util.Properties aP = new java.util.Properties();
		try(java.io.InputStream aIn = new java.io.FileInputStream(aF)) {
			aP.load(aIn);
			return aP;
		}
		catch(Throwable t) {
			t.printStackTrace(System.err);
			return null;
		}
	}

	public static void deleteParts(String aOut) {
		java.util.Properties aP = readParts(aOut);
		int aSegs = aP == null ? 0 : Integer.parseInt(aP.getProperty("segments", "0"));
		for(int k = 1;k <= Math.max(aSegs, 50);k++) {
			File aS = new File(segment(aOut, k));
			if(!aS.delete() && k > aSegs) {
				break;
			}
		}
		new File(aOut+PARTS).delete();
	}

	/**
	 * The partial file of an interrupted computation of aOut from its segments, if it has some
	 */
	static synchronized void makePartial(String aOut) {
		java.util.Properties aP = readParts(aOut);
		if(aP == null) {
			return;
		}
		try {
			int aSegs = Integer.parseInt(aP.getProperty("segments", "0"));
			if(aSegs > 0 && new File(segment(aOut, aSegs)).isFile()) {
				joinSegments(aOut, aSegs, ComputedDepthmap.partialPath(aOut));
			}
		}
		catch(Throwable t) {
			t.printStackTrace(System.err);
		}
	}

	/**
	 * Model id of the interrupted computation of a depth video aOut, null if none
	 */
	public static String partModel(String aOut) {
		java.util.Properties aP = readParts(aOut);
		return aP == null ? null : aP.getProperty("model");
	}

	/**
	 * Part done of the interrupted computation of a depth video aOut (0..1), -1 if unknown
	 */
	public static double partFraction(String aOut,long aDurationMS) {
		java.util.Properties aP = readParts(aOut);
		if(aP == null || aDurationMS <= 0) {
			return -1;
		}
		double aFps = Double.parseDouble(aP.getProperty("fps", "25"));
		long aFrames = Long.parseLong(aP.getProperty("frames", "0"));
		return Math.min(1, aFrames/(aDurationMS*aFps/1000));
	}

	void writeParts(String aOut,double aFps,long aFrames,int aSegs,Levels[] aLevels) throws Exception {
		java.util.Properties aP = new java.util.Properties();
		aP.setProperty("model", model.id);
		aP.setProperty("fps", ""+aFps);
		aP.setProperty("frames", ""+aFrames);
		aP.setProperty("segments", ""+aSegs);
		for(int l = 0;l < aLevels.length;l++) {
			if(!Float.isNaN(aLevels[l].low)) {
				aP.setProperty("low"+l, ""+aLevels[l].low);
				aP.setProperty("high"+l, ""+aLevels[l].high);
			}
		}
		File aF = new File(aOut+PARTS);
		File aTmp = new File(aF.getPath()+".tmp");
		try(java.io.OutputStream aO = new java.io.FileOutputStream(aTmp)) {
			aP.store(aO, "3Denlive interrupted depth map");
		}
		Files.move(aTmp.toPath(), aF.toPath(), StandardCopyOption.REPLACE_EXISTING);
	}

	/**
	 * The segments into the depth video (ffmpeg concat, no new encoding)
	 */
	static boolean joinSegments(String aOut,int aSegs) throws Exception {
		return joinSegments(aOut, aSegs, aOut);
	}

	/**
	 * The segments of aOut into aDest (aOut: moved / joined; another file, e.g. the partial one: the segments kept)
	 */
	static boolean joinSegments(String aOut,int aSegs,String aDest) throws Exception {
		boolean aKeep = !aDest.equals(aOut);
		if(aSegs == 1) {
			if(aKeep) {
				Files.copy(new File(segment(aOut, 1)).toPath(), new File(aDest).toPath(), StandardCopyOption.REPLACE_EXISTING);
			}
			else {
				Files.move(new File(segment(aOut, 1)).toPath(), new File(aOut).toPath(), StandardCopyOption.REPLACE_EXISTING);
			}
			return true;
		}
		File aList = new File(aOut+".parts.txt");
		StringBuilder aSB = new StringBuilder();
		for(int k = 1;k <= aSegs;k++) {
			aSB.append("file '").append(new File(segment(aOut, k)).getAbsolutePath().replace("'", "'\\''")).append("'\n");
		}
		Files.writeString(aList.toPath(), aSB.toString());
		String aTmp = StringUtils.path2processing(aDest);
		java.util.ArrayList<String> aCmd = new java.util.ArrayList<String>();
		aCmd.add(com.cubaix.TDenlive.ffmpeg.Ffmpeg.ffmpegPath());
		aCmd.add("-hide_banner");
		aCmd.add("-loglevel"); aCmd.add("error");
		aCmd.add("-f"); aCmd.add("concat");
		aCmd.add("-safe"); aCmd.add("0");
		aCmd.add("-i"); aCmd.add(aList.getPath());
		aCmd.add("-c"); aCmd.add("copy");
		aCmd.add("-y");
		aCmd.add(aTmp);
		System.out.println("cmd: "+String.join(" ", aCmd));
		Process aP = new ProcessBuilder(aCmd).redirectOutput(ProcessBuilder.Redirect.DISCARD)
				.redirectError(ProcessBuilder.Redirect.INHERIT).start();
		int aRC = aP.waitFor();
		aList.delete();
		if(aRC != 0 || !new File(aTmp).isFile()) {
			System.err.println("ffmpeg concat exit "+aRC+": "+aDest);
			new File(aTmp).delete();
			return false;
		}
		Files.move(new File(aTmp).toPath(), new File(aDest).toPath(), StandardCopyOption.REPLACE_EXISTING);
		return true;
	}

	/**
	 * One source file into its depth video. Resumes an interrupted computation of the same model (aOut + PARTS:
	 * from its next frame, the depth levels as they were); interrupted: the frames done are kept as a segment
	 */
	boolean videoPass(String aPath,String aOut,boolean aSplit,long aDurationMS,int aPass,int aNbPasses,boolean aCrossed,String aAnaglyph) throws Exception {
		java.util.Properties aParts = readParts(aOut);
		if(aParts != null && !model.id.equals(aParts.getProperty("model"))) {
			deleteParts(aOut);
			aParts = null;
		}
		Levels[] aLevels = {new Levels(), new Levels()};
		long aStartFrame = 0;
		int aSegs = 0;
		double aFps = -1;
		if(aParts != null) {
			aStartFrame = Long.parseLong(aParts.getProperty("frames", "0"));
			aSegs = Integer.parseInt(aParts.getProperty("segments", "0"));
			aFps = Double.parseDouble(aParts.getProperty("fps", "-1"));
			for(int l = 0;l < aLevels.length;l++) {
				String aLow = aParts.getProperty("low"+l);
				if(aLow != null) {
					aLevels[l].low = Float.parseFloat(aLow);
					aLevels[l].high = Float.parseFloat(aParts.getProperty("high"+l));
				}
			}
			if(aFps <= 0 || aSegs <= 0) {
				deleteParts(aOut);
				aStartFrame = 0;
				aSegs = 0;
				aFps = -1;
			}
			else {
				System.out.println("Depth map resumed at frame "+aStartFrame+": "+aOut);
			}
		}
		FfmpegDecoder aDec = aStartFrame > 0
				? new FfmpegDecoder(aPath, Math.round(aStartFrame*1000.0/aFps), -1, FfmpegDecoder.FILESIZE, FfmpegDecoder.FILEFPS, aFps)
				: new FfmpegDecoder(aPath, 0, -1, FfmpegDecoder.FILESIZE, FfmpegDecoder.FILEFPS);
		FfmpegRawEncoder aEnc = null;
		boolean aOk = false;
		long aFrame = aStartFrame;
		try {
			while(!canceled()) {
				BufferedImage aBI = aDec.getImg();
				if(aBI == null) {
					aOk = aFrame > 0;
					break;
				}
				if(aFps <= 0) {
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
				if(aAnaglyph != null) {
					//An anaglyph: its views from its color layers, each in gray, their maps left|right
					BufferedImage[] aDs = depthPair(gray(com.cubaix.TDenlive.utils.Anaglyph.view(toRgb(aBI), aAnaglyph, true)),
							gray(com.cubaix.TDenlive.utils.Anaglyph.view(toRgb(aBI), aAnaglyph, false)), aLevels[0]);
					BufferedImage aD0 = aDs[0], aD1 = aDs[1];
					int aW = aD0.getWidth(), aH = aD0.getHeight();
					aDepth = new BufferedImage(2*aW, aH, BufferedImage.TYPE_INT_RGB);
					Graphics2D aG = aDepth.createGraphics();
					aG.drawImage(aD0, 0, 0, null);
					aG.drawImage(aD1, aW, 0, null);
					aG.dispose();
					showFrame(aD0, aD1, aFrame*1000.0/aFps);
				}
				else if(aSplit) {
					int aW = aBI.getWidth()/2;
					int aH = aBI.getHeight();
					aDepth = new BufferedImage(2*aW, aH, BufferedImage.TYPE_INT_RGB);
					Graphics2D aG = aDepth.createGraphics();
					BufferedImage aD0 = depthOf(aBI.getSubimage(0, 0, aW, aH), aLevels[0]);
					BufferedImage aD1 = depthOf(aBI.getSubimage(aW, 0, aW, aH), aLevels[1]);
					aG.drawImage(aD0, 0, 0, null);
					aG.drawImage(aD1, aW, 0, null);
					aG.dispose();
					//Crossed: the left half is the right view
					showFrame(aCrossed ? aD1 : aD0, aCrossed ? aD0 : aD1, aFrame*1000.0/aFps);
				}
				else {
					aDepth = depthOf(aBI, aLevels[0]);
					showFrame(aDepth, aDepth, aFrame*1000.0/aFps);
				}
				if(aEnc == null) {
					aEnc = new FfmpegRawEncoder(segment(aOut, aSegs+1), aDepth.getWidth(), aDepth.getHeight(), aFps);
				}
				aEnc.addImg(aDepth);
				aFrame++;
			}
		}
		finally {
			aDec.stop();
			if(aEnc != null) {
				if(aOk && !canceled()) {
					//The end of the encoding of the depth video (no counter: elapsed time shown)
					status("DepthConvert.Finishing", 0, 0);
				}
				//Interrupted too: the frames done are kept
				if(aEnc.finish(true)) {
					aSegs++;
					try {
						writeParts(aOut, aFps, aFrame, aSegs, aLevels);
					}
					catch(Throwable t) {
						t.printStackTrace(System.err);
					}
				}
				else {
					aOk = false;
				}
			}
		}
		if(!aOk || canceled()) {
			if(canceled() && aSegs > 0) {
				double aT = Math.max(1, aDurationMS*aFps/1000.0);
				partDone = Math.min(1, (aPass*aT+aFrame)/(aNbPasses*aT));
				//The frames done, joined: shown and used until the computation is resumed (the segments kept)
				try {
					joinSegments(aOut, aSegs, ComputedDepthmap.partialPath(aOut));
				}
				catch(Throwable t) {
					t.printStackTrace(System.err);
				}
			}
			return false;
		}
		if(aSegs == 0 || !joinSegments(aOut, aSegs)) {
			return false;
		}
		deleteParts(aOut);
		new File(ComputedDepthmap.partialPath(aOut)).delete();
		return true;
	}
}
