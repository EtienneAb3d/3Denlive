package com.cubaix.TDenlive.ai;

import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.io.File;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Vector;

import javax.imageio.ImageIO;

import com.cubaix.TDenlive.depth.DepthEstimator;
import com.cubaix.TDenlive.depth.DepthModel;
import com.cubaix.TDenlive.depth.Downloader;
import com.cubaix.TDenlive.model3d.SmartFill;

/**
 * Filling of the zones erased by the AI eraser (AI window, "Eraser" panel, GUI/AiMaskGUI in eraser mode): the objects
 * delimited and tracked by SAM 2.1 (labels of the mask editor) are holes, filled frame after frame at the work size:
 * - a depth map per view (Depth Anything V2 Small, child JVM, levels smoothed over the frames), its values in the holes
 *   replaced by those of the background around them (SmartFill.backgroundDepthFull(): the erased object is in front);
 * - stereo: the parallax between the views as a function of the depth (x other = x + a + b z), fitted on block matches
 *   of the pixels outside the holes; the real pixels of the right view carried into the holes of the left one, then the
 *   filled left view carried into the holes of the right one (both eyes show the same content);
 * - video: the real pixels of the neighbor frames (TEMPORAL_RANGE on each side, nearest first: what the object hid
 *   before or after) and the filling of the previous frame (its wide parts: the same invented content from one frame
 *   to the next), each frame aligned on the ring of pixels around the holes (translation, checked);
 * - what is left filled by the model chosen (SmartFill.fillView(): iw3 light, LaMa or SDXL, SDXL only for large areas).
 * The filled frames (views side by side, PNG) go into outDir, with the same numbers as the work frames.
 */
public class EraseFiller {
	public interface Progress {
		void status(String aKey,String aDetail,long aDone,long aTotal);
		boolean isCanceled();
		//A frame filled (written into outDir)
		void filled(int aFrame);
	}

	//Neighbor frames tried on each side for their real pixels
	static final int TEMPORAL_RANGE = 6;
	//Ring around the holes on which two frames are aligned (pixels)
	static final int RING = 24;
	//Alignment accepted: MATCH_GOOD of the ring samples closer than MATCH_DIFF (gray 0..255), MATCH_COVER of them seen
	static final double MATCH_DIFF = 20;
	static final double MATCH_GOOD = 0.6;
	static final double MATCH_COVER = 0.5;
	static final int MIN_SAMPLES = 150;
	static final int MAX_SAMPLES = 3000;
	//Stereo matches: grid step and patch radius (half size), uniqueness
	static final int STEREO_STEP = 8;
	static final int STEREO_PATCH = 3;
	static final double STEREO_UNIQUE = 0.8;
	static final int STEREO_MIN_MATCHES = 30;
	//Filled pixels of a view carried to the other only where the parallax changes by at most this per pixel
	static final double PAIR_SLOPE = 0.5;
	//Frames kept in memory
	static final int CACHE = 2*TEMPORAL_RANGE+3;

	final String tdeDir;
	final File framesDir;
	final File labelsDir;
	final File outDir;
	final int count;
	final int nViews;
	final int viewW;
	final int viewH;
	//Margin of the holes around the objects (work pixels), objects erased (bits of the labels)
	final int margin;
	final int keep;
	final SmartFill.Model model;
	final Progress progress;
	final int seed;
	//Frames processed (a range of a video, all by default): the others are kept as they are
	int from = 0;
	int to = -1;

	DepthEstimator estimator = null;
	float levelLow = Float.NaN;
	float levelHigh = Float.NaN;
	//Stereo: {a, b, dy} of x right = x left + a + b zL (left to right) and x left = x right + a + b zR, null: unknown
	double[] dispLR = null;
	double[] dispRL = null;
	//Filling of the previous frame per view
	int[][] prevOut = null;
	int prevFrame = -1;
	//Counters (log)
	long carriedView = 0, carriedTime = 0, carriedPrev = 0, byModel = 0, holesTotal = 0;

	/** A work frame: its views, holes, gray levels */
	static class Frame {
		int[][] pix;
		boolean[][] holes;
		int[][] gray;
		//Labels the holes come from (date, size): read again when they changed (a filler kept between the steps)
		long labStamp;
		//Depth estimate of each view as given by the model ({float[], w, h}), kept
		Object[][] rawDepth;
		//Views completed by the real pixels of the other view (complete()): pixels, holes left, gray; null: not yet
		int[][] cpix;
		boolean[][] choles;
		int[][] cgray;
	}
	final Map<Integer,Frame> cache = new LinkedHashMap<Integer,Frame>(16, 0.75f, true) {
		private static final long serialVersionUID = 1L;
		@Override
		protected boolean removeEldestEntry(Map.Entry<Integer,Frame> aE) {
			return size() > CACHE;
		}
	};

	public EraseFiller(String aTdeDir,File aFrames,File aLabels,File aOut,int aCount,int aViews,int aViewW,int aViewH
			,int aMargin,int aKeep,SmartFill.Model aModel,int aSeed,Progress aProgress) {
		tdeDir = aTdeDir;
		framesDir = aFrames;
		labelsDir = aLabels;
		outDir = aOut;
		count = aCount;
		nViews = aViews;
		viewW = aViewW;
		viewH = aViewH;
		margin = aMargin;
		keep = aKeep;
		model = aModel;
		seed = aSeed & 0x7FFFFFFF;
		progress = aProgress;
	}

	/**
	 * Only the frames aFrom..aTo (included) processed; their neighbors outside are not used either (their objects are
	 * not erased)
	 */
	public void setRange(int aFrom,int aTo) {
		from = Math.max(0, aFrom);
		to = Math.min(count-1, aTo);
	}

	int last() {
		return to < 0 ? count-1 : to;
	}

	//Frames usable as neighbors (their objects tracked), by default the frames processed
	int nFrom = -1, nTo = -1;

	/**
	 * Neighbors taken in aFrom..aTo instead of the frames processed (one frame filled at a step of the editor:
	 * the frames before it, already tracked and filled, not those after, not tracked yet); the filling of the
	 * frame before is read back from its file
	 */
	public void setNeighbors(int aFrom,int aTo) {
		nFrom = Math.max(0, aFrom);
		nTo = Math.min(count-1, aTo);
	}

	int nbFrom() {
		return nFrom >= 0 ? nFrom : from;
	}

	int nbTo() {
		return nTo >= 0 ? nTo : last();
	}

	/**
	 * The filling of frame aF read back from its file (the views side by side), if it is there
	 */
	void loadPrev(int aF) {
		File aFile = outFile(outDir, aF);
		if(aF < nbFrom() || aF > nbTo() || !aFile.isFile()) {
			return;
		}
		try {
			BufferedImage aBI = ImageIO.read(aFile);
			if(aBI == null || aBI.getWidth() != viewW*nViews || aBI.getHeight() != viewH) {
				return;
			}
			int[][] aViews = new int[nViews][];
			for(int v = 0;v < nViews;v++) {
				aViews[v] = aBI.getRGB(v*viewW, 0, viewW, viewH, null, 0, viewW);
				for(int p = 0;p < aViews[v].length;p++) {
					aViews[v][p] &= 0xFFFFFF;
				}
			}
			prevOut = aViews;
			prevFrame = aF;
		}
		catch(Throwable t) {
			t.printStackTrace(System.err);
		}
	}

	boolean canceled() {
		return progress != null && progress.isCanceled();
	}

	void status(String aKey,String aDetail,long aDone,long aTotal) {
		if(progress != null) {
			progress.status(aKey, aDetail, aDone, aTotal);
		}
	}

	public static File frameFile(File aFrames,int aF) {
		File aJ = new File(aFrames, String.format("%06d.jpg", aF));
		return aJ.isFile() ? aJ : new File(aFrames, String.format("%06d.png", aF));
	}

	public static File outFile(File aOut,int aF) {
		return new File(aOut, String.format("%06d.png", aF));
	}

	/**
	 * Holes of each view of a frame: the objects of aKeep in its labels, grown by aMargin pixels (null: no labels)
	 */
	public static boolean[][] holes(File aLabels,int aF,int aViews,int aViewW,int aViewH,int aMargin,int aKeep) {
		File aLF = new File(aLabels, String.format("%06d.png", aF));
		boolean[][] aRes = new boolean[aViews][aViewW*aViewH];
		if(!aLF.isFile()) {
			return aRes;
		}
		try {
			BufferedImage aLab = ImageIO.read(aLF);
			int aW = aViewW*aViews;
			if(aLab == null || aLab.getWidth() != aW || aLab.getHeight() != aViewH) {
				return aRes;
			}
			int[] aL = aLab.getRaster().getPixels(0, 0, aW, aViewH, (int[])null);
			for(int v = 0;v < aViews;v++) {
				boolean[] aH = aRes[v];
				boolean aAny = false;
				for(int y = 0;y < aViewH;y++) {
					for(int x = 0;x < aViewW;x++) {
						if((aL[y*aW+v*aViewW+x] & aKeep) != 0) {
							aH[y*aViewW+x] = true;
							aAny = true;
						}
					}
				}
				if(aAny && aMargin > 0) {
					int[] aD = SmartFill.distance(aH, aViewW, aViewH);
					for(int p = 0;p < aH.length;p++) {
						aH[p] = aD[p] <= 3*aMargin;
					}
				}
			}
		}
		catch(Throwable t) {
			t.printStackTrace(System.err);
		}
		return aRes;
	}

	long labStamp(int aF) {
		File aLF = new File(labelsDir, String.format("%06d.png", aF));
		return aLF.isFile() ? aLF.lastModified()*31+aLF.length() : -1;
	}

	Frame frame(int aF) throws Exception {
		Frame aFr = cache.get(aF);
		if(aFr != null) {
			long aStamp = labStamp(aF);
			if(aStamp != aFr.labStamp) {
				aFr.holes = holes(labelsDir, aF, nViews, viewW, viewH, margin, keep);
				aFr.labStamp = aStamp;
				aFr.cpix = null;
				aFr.choles = null;
				aFr.cgray = null;
			}
			return aFr;
		}
		BufferedImage aBI = ImageIO.read(frameFile(framesDir, aF));
		if(aBI == null) {
			throw new Exception("Unreadable frame "+aF);
		}
		int aW = viewW*nViews;
		BufferedImage aRGB = new BufferedImage(aW, viewH, BufferedImage.TYPE_INT_RGB);
		java.awt.Graphics2D aG = aRGB.createGraphics();
		aG.drawImage(aBI, 0, 0, aW, viewH, null);
		aG.dispose();
		int[] aAll = ((DataBufferInt)aRGB.getRaster().getDataBuffer()).getData();
		aFr = new Frame();
		aFr.pix = new int[nViews][viewW*viewH];
		aFr.gray = new int[nViews][viewW*viewH];
		for(int v = 0;v < nViews;v++) {
			for(int y = 0;y < viewH;y++) {
				System.arraycopy(aAll, y*aW+v*viewW, aFr.pix[v], y*viewW, viewW);
			}
			for(int p = 0;p < viewW*viewH;p++) {
				int c = aFr.pix[v][p];
				aFr.gray[v][p] = (((c>>16)&0xFF)*77+((c>>8)&0xFF)*150+(c&0xFF)*29)>>8;
			}
		}
		aFr.labStamp = labStamp(aF);
		aFr.holes = holes(labelsDir, aF, nViews, viewW, viewH, margin, keep);
		cache.put(aF, aFr);
		return aFr;
	}

	static boolean any(boolean[] aB) {
		for(boolean b : aB) {
			if(b) {
				return true;
			}
		}
		return false;
	}

	/**
	 * All the frames filled into outDir
	 * @return false if canceled
	 */
	public boolean run() throws Exception {
		outDir.mkdirs();
		long aT0 = System.currentTimeMillis();
		carriedView = carriedTime = carriedPrev = byModel = holesTotal = 0;
		//The filling before the first frame read back from its file (made again since, maybe by another filler)
		prevFrame = -1;
		prevOut = null;
		try {
			int aN = last()-from+1;
			for(int f = from;f <= last();f++) {
				if(canceled()) {
					return false;
				}
				status("AI.EraserFilling", null, f-from, aN);
				if(skip != null && skip.test(f)) {
					//Already filled with these settings (the frames shown in the editor)
					continue;
				}
				if(prevFrame != f-1) {
					loadPrev(f-1);
				}
				fillFrame(f);
				if(filledKey != null) {
					filledKey.accept(f);
				}
				if(progress != null) {
					progress.filled(f);
				}
			}
			status("AI.EraserFilling", null, aN, aN);
			System.out.println("AI eraser: frames "+(from+1)+".."+(last()+1)+" of "+count+"  ("+nViews+" views "+viewW+"x"+viewH+", model "+model.id+") in "
					+(System.currentTimeMillis()-aT0)+" ms; hole pixels "+holesTotal+": "+carriedView+" from the other view, "
					+carriedTime+" from other frames, "+carriedPrev+" from the previous filling, "+byModel+" by the model");
			return true;
		}
		finally {
			if(estimator != null && !keepOpen) {
				estimator.close();
				estimator = null;
			}
		}
	}

	/**
	 * Depth estimator (downloaded and loaded at the first frame with holes)
	 */
	//Frames already filled (kept as they are, read back as the previous filling of the next one), frames filled
	public java.util.function.IntPredicate skip = null;
	public java.util.function.IntConsumer filledKey = null;

	//Steps of the editor: the depth estimator kept loaded between the runs (close() at the end)
	public boolean keepOpen = false;

	public void close() {
		if(estimator != null) {
			estimator.close();
			estimator = null;
		}
	}

	DepthEstimator estimator() throws Exception {
		if(estimator != null) {
			return estimator;
		}
		DepthModel aDM = DepthModel.SMALL;
		Downloader.Progress aDP = new Downloader.Progress() {
			@Override
			public void downloaded(long aDone,long aTotal) {
				status("AI.DownloadingEraserDepth", null, aDone, aTotal);
			}
			@Override
			public boolean isCanceled() {
				return canceled();
			}
		};
		if(!DepthEstimator.isOrtDownloaded(tdeDir)
				&& !Downloader.download(DepthEstimator.ortUrl(), DepthEstimator.getOrtJar(tdeDir), DepthEstimator.ortSize(), aDP)) {
			throw new Exception("Download of ONNX Runtime canceled or failed");
		}
		if(!aDM.isDownloaded(tdeDir) && !Downloader.download(aDM.url, aDM.getFile(tdeDir), aDM.size, aDP)) {
			throw new Exception("Download of the depth model canceled or failed");
		}
		status("AI.EraserDepthLoading", null, 0, 0);
		estimator = new DepthEstimator(tdeDir, aDM);
		System.out.println("AI eraser depth maps: "+estimator.provider+(estimator.cpuReason != null ? " ("+estimator.cpuReason+")" : ""));
		return estimator;
	}

	/**
	 * Nearness of each pixel of a view (0.1 far .. 1 near), from its depth map at the view size
	 */
	/**
	 * Nearness of each view of a frame (0.1 far .. 1 near), the background depth in its holes (the erased objects
	 * were in front); the estimate of the model kept in the frame. aUpdate: the levels follow this frame (the frame
	 * filled; not its neighbors, taken with the levels of now)
	 */
	float[][] depths(Frame aFr,boolean aUpdate) throws Exception {
		int n = viewW*viewH;
		if(aFr.rawDepth == null) {
			aFr.rawDepth = new Object[nViews][];
		}
		float[][] aZ = new float[nViews][];
		for(int v = 0;v < nViews;v++) {
			if(aFr.rawDepth[v] == null) {
				BufferedImage aBI = new BufferedImage(viewW, viewH, BufferedImage.TYPE_INT_RGB);
				System.arraycopy(aFr.pix[v], 0, ((DataBufferInt)aBI.getRaster().getDataBuffer()).getData(), 0, n);
				aFr.rawDepth[v] = estimator().estimate(aBI);
			}
			aZ[v] = nearness(aFr.rawDepth[v], aUpdate || Float.isNaN(levelLow));
			if(any(aFr.holes[v])) {
				float[] aTrans0 = new float[n];
				for(int p = 0;p < n;p++) {
					aTrans0[p] = aFr.holes[v][p] ? 1 : 0;
				}
				float[] aBg = SmartFill.backgroundDepthFull(aZ[v], aTrans0, viewW, viewH);
				for(int p = 0;p < n;p++) {
					if(aFr.holes[v][p] && aBg[p] > 0) {
						aZ[v][p] = aBg[p];
					}
				}
			}
		}
		return aZ;
	}

	/**
	 * The views of a frame completed by the real pixels of the other view (outside its holes), both ways (the
	 * stereo calibration of now): pixels, holes left and gray levels kept in the frame
	 * @return pixels given
	 */
	int complete(Frame aFr,float[][] aZ) throws Exception {
		int n = viewW*viewH;
		aFr.cpix = new int[nViews][];
		aFr.choles = new boolean[nViews][];
		aFr.cgray = new int[nViews][];
		for(int v = 0;v < nViews;v++) {
			aFr.cpix[v] = aFr.pix[v].clone();
			aFr.choles[v] = aFr.holes[v].clone();
			for(int p = 0;p < n;p++) {
				if(aFr.choles[v][p]) {
					aFr.cpix[v][p] = 0;
				}
			}
		}
		int aGiven = 0;
		if(nViews == 2 && (any(aFr.holes[0]) || any(aFr.holes[1]))) {
			if(aZ == null) {
				aZ = depths(aFr, false);
			}
			if(dispRL != null && any(aFr.choles[0])) {
				aGiven += carryView(aFr.pix[1], aZ[1], aFr.holes[1], null, dispRL, aFr.cpix[0], aFr.choles[0]);
			}
			if(dispLR != null && any(aFr.choles[1])) {
				aGiven += carryView(aFr.pix[0], aZ[0], aFr.holes[0], null, dispLR, aFr.cpix[1], aFr.choles[1]);
			}
		}
		for(int v = 0;v < nViews;v++) {
			aFr.cgray[v] = new int[n];
			for(int p = 0;p < n;p++) {
				int c = aFr.cpix[v][p];
				aFr.cgray[v][p] = (((c>>16)&0xFF)*77+((c>>8)&0xFF)*150+(c&0xFF)*29)>>8;
			}
		}
		return aGiven;
	}

	/** A neighbor frame completed by its other view (once per frame and labels) */
	Frame completed(int aF) throws Exception {
		Frame aFr = frame(aF);
		if(aFr.cpix == null) {
			complete(aFr, null);
		}
		return aFr;
	}

	/**
	 * Nearness (0.1 far .. 1 near) at the view size from an estimate of the model; aUpdate: the levels follow it
	 */
	float[] nearness(Object[] aRes,boolean aUpdate) {
		float[] aD = (float[])aRes[0];
		int aDW = (Integer)aRes[1];
		int aDH = (Integer)aRes[2];
		float[] aR = DepthEstimator.range(aD);
		if(aUpdate && Float.isNaN(levelLow)) {
			levelLow = aR[0];
			levelHigh = aR[1];
		}
		else if(aUpdate) {
			levelLow = 0.9f*levelLow+0.1f*aR[0];
			levelHigh = 0.9f*levelHigh+0.1f*aR[1];
		}
		float aSpan = Math.max(1e-6f, levelHigh-levelLow);
		float[] aZ = new float[viewW*viewH];
		for(int y = 0;y < viewH;y++) {
			double sy = Math.max(0, Math.min(aDH-1, (y+0.5)*aDH/viewH-0.5));
			int y0 = (int)sy, y1 = Math.min(aDH-1, y0+1);
			double fy = sy-y0;
			for(int x = 0;x < viewW;x++) {
				double sx = Math.max(0, Math.min(aDW-1, (x+0.5)*aDW/viewW-0.5));
				int x0 = (int)sx, x1 = Math.min(aDW-1, x0+1);
				double fx = sx-x0;
				double d = (aD[y0*aDW+x0]*(1-fx)+aD[y0*aDW+x1]*fx)*(1-fy)+(aD[y1*aDW+x0]*(1-fx)+aD[y1*aDW+x1]*fx)*fy;
				double t = Math.max(0, Math.min(1, (d-levelLow)/aSpan));
				aZ[y*viewW+x] = (float)(0.1+0.9*t);
			}
		}
		return aZ;
	}

	/**
	 * One frame: each view filled (left first), written into outDir
	 */
	void fillFrame(int f) throws Exception {
		Frame aFr = frame(f);
		int n = viewW*viewH;
		int[][] aOut = new int[nViews][];
		boolean aAnyHole = false;
		for(int v = 0;v < nViews;v++) {
			aAnyHole |= any(aFr.holes[v]);
		}
		if(!aAnyHole) {
			for(int v = 0;v < nViews;v++) {
				aOut[v] = aFr.pix[v];
			}
			write(f, aOut);
			prevOut = aOut;
			prevFrame = f;
			return;
		}
		//Depths of the views, the background in the holes (the erased objects were in front)
		float[][] aZ = depths(aFr, true);
		if(nViews == 2) {
			calibrate(aFr, aZ);
		}
		for(int v = 0;v < nViews;v++) {
			for(boolean b : aFr.holes[v]) {
				if(b) {
					holesTotal++;
				}
			}
		}
		//First the real pixels of the other view (outside its holes), both ways: what one eye sees behind the
		//objects, the other often sees it. The views so completed (trusted pixels) are those the motion of the
		//surroundings of the holes is measured on, here and in the neighbor frames
		carriedView += complete(aFr, aZ);
		int[][] aPixs = new int[nViews][];
		boolean[][] aLefts = new boolean[nViews][];
		for(int v = 0;v < nViews;v++) {
			aPixs[v] = aFr.cpix[v].clone();
			aLefts[v] = aFr.choles[v].clone();
		}
		//Alignments on the neighbor frames: {dx, dy} per view and frame, null if refused
		for(int v = 0;v < nViews;v++) {
			if(canceled()) {
				return;
			}
			boolean[] aHoles = aFr.holes[v];
			int[] aPix = aPixs[v];
			//Still a hole
			boolean[] aLeft = aLefts[v];
			if(!any(aHoles)) {
				aOut[v] = aFr.pix[v];
				continue;
			}
			if(nViews == 2 && v == 1 && dispLR != null && any(aLeft)) {
				//The filled left view (its holes at the depth of their background) carried into the right one
				boolean[] aKnownL = new boolean[n];
				for(int p = 0;p < n;p++) {
					aKnownL[p] = !aFr.holes[0][p];
				}
				carriedView += carryView(aOut[0], aZ[0], null, aKnownL, dispLR, aPix, aLeft);
			}
			//Real pixels of the neighbor frames (completed by their other view), nearest first, each hole moved by
			//the motion of its surroundings: the pixels known after the carry between the views, those still holes
			//here or there not used
			double[][] aShiftPrev = null;
			int[] aCGray = aFr.cgray[v];
			boolean[] aCHoles = aFr.choles[v];
			if(count > 1 && any(aCHoles)) {
				Groups aGr = groups(aCHoles);
				double[][][] aLast = new double[2][][];
				int[] aFails = new int[2];
				for(int k = 1;k <= TEMPORAL_RANGE && remaining(aLeft) > 0;k++) {
					for(int s = 0;s < 2;s++) {
						int g = s == 0 ? f-k : f+k;
						if(g < nbFrom() || g > nbTo() || aFails[s] >= 2 || canceled()) {
							continue;
						}
						//Not tracked yet (no labels): its objects would be taken as background
						if(!new File(labelsDir, String.format("%06d.png", g)).isFile()) {
							continue;
						}
						Frame aG = completed(g);
						int aRange = aLast[s] == null ? Math.max(16, viewW/16) : Math.max(8, viewW/64);
						double[][] aM = motions(aCGray, aCHoles, aGr, aG.cgray[v], aG.choles[v], aLast[s], k, aRange);
						if(aM == null) {
							aFails[s]++;
							continue;
						}
						//The motion of a group not found this time: its last one kept as the guess of the next frame
						if(aLast[s] != null) {
							for(int i = 0;i < aM.length;i++) {
								if(aM[i] == null && aLast[s][i] != null) {
									aLast[s][i] = new double[] {aLast[s][i][0]*k/(k-1.0), aLast[s][i][1]*k/(k-1.0)};
								}
								else {
									aLast[s][i] = aM[i];
								}
							}
						}
						else {
							aLast[s] = aM.clone();
						}
						if(g == f-1) {
							aShiftPrev = aM;
						}
						carriedTime += carryMotion(aG.cpix[v], aG.choles[v], aGr, aM, aPix, aLeft, null);
					}
				}
				//The filling of the previous frame, in the wide parts of the holes left (the same content kept)
				if(remaining(aLeft) > 0 && prevOut != null && prevFrame == f-1) {
					if(aShiftPrev == null) {
						//The holes of the frame before not used either: their content moved on its own
						Frame aG = completed(f-1);
						aShiftPrev = motions(aCGray, aCHoles, aGr, aG.cgray[v], aG.choles[v], null, 1, Math.max(16, viewW/16));
					}
					if(aShiftPrev != null) {
						boolean[] aKnown = new boolean[n];
						for(int p = 0;p < n;p++) {
							aKnown[p] = !aLeft[p];
						}
						boolean[] aWide = SmartFill.wideHoles(aKnown, viewW, viewH, Math.max(2, viewH/180));
						//Rounded: carried from frame to frame, a sub pixel error would add up (drift, blur)
						double[][] aRound = new double[aShiftPrev.length][];
						for(int i = 0;i < aRound.length;i++) {
							if(aShiftPrev[i] != null) {
								aRound[i] = new double[] {Math.round(aShiftPrev[i][0]), Math.round(aShiftPrev[i][1])};
							}
						}
						carriedPrev += carryMotion(prevOut[v], null, aGr, aRound, aPix, aLeft, aWide);
					}
				}
			}
			//The model for the rest
			int aRest = remaining(aLeft);
			if(aRest > 0 && !canceled()) {
				float[] aTrans = new float[n];
				for(int p = 0;p < n;p++) {
					aTrans[p] = aLeft[p] ? 1 : 0;
				}
				SmartFill.Model aM = model == SmartFill.Model.SDXL && !SmartFill.largeHoles(aTrans, viewW, viewH) ? SmartFill.Model.LIGHT : model;
				status(aM == SmartFill.Model.SDXL ? "AI.EraserSdxl" : "AI.EraserFilling", null, f-from, last()-from+1);
				float[] aRGB = SmartFill.fillView(aPix, aTrans, viewW, viewH, v == 0, aM, seed, tdeDir, aZ[v]);
				for(int p = 0;p < n;p++) {
					if(aLeft[p]) {
						aPix[p] = (clamp(aRGB[3*p]*255)<<16)|(clamp(aRGB[3*p+1]*255)<<8)|clamp(aRGB[3*p+2]*255);
					}
				}
				byModel += aRest;
			}
			aOut[v] = aPix;
		}
		write(f, aOut);
		prevOut = aOut;
		prevFrame = f;
	}

	static int remaining(boolean[] aLeft) {
		int c = 0;
		for(boolean b : aLeft) {
			if(b) {
				c++;
			}
		}
		return c;
	}

	static int clamp(float aV) {
		return aV < 0 ? 0 : aV > 255 ? 255 : (int)aV;
	}

	void write(int f,int[][] aViews) throws Exception {
		BufferedImage aBI = new BufferedImage(viewW*nViews, viewH, BufferedImage.TYPE_INT_RGB);
		int[] aD = ((DataBufferInt)aBI.getRaster().getDataBuffer()).getData();
		int aW = viewW*nViews;
		for(int v = 0;v < nViews;v++) {
			for(int y = 0;y < viewH;y++) {
				System.arraycopy(aViews[v], y*viewW, aD, y*aW+v*viewW, viewW);
			}
		}
		File aTmp = new File(outDir, "tmp_"+String.format("%06d.png", f));
		ImageIO.write(aBI, "png", aTmp);
		File aDst = outFile(outDir, f);
		java.nio.file.Files.move(aTmp.toPath(), aDst.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
	}

	//== Between the views ==

	/**
	 * Parallax between the views as a function of the depth, fitted on block matches of the pixels outside the holes
	 * (half size); kept from the previous frame when this one gives too few matches, smoothed over the frames
	 */
	void calibrate(Frame aFr,float[][] aZ) {
		int aW2 = viewW/2, aH2 = viewH/2;
		if(aW2 < 16 || aH2 < 16) {
			return;
		}
		int[] aGL = half(aFr.gray[0]);
		int[] aGR = half(aFr.gray[1]);
		boolean[] aHL = halfHoles(aFr.holes[0]);
		boolean[] aHR = halfHoles(aFr.holes[1]);
		int aMaxD = Math.max(8, aW2/8);
		int r = STEREO_PATCH;
		Vector<double[]> aM = new Vector<double[]>();
		for(int y = r+1;y < aH2-r-1;y += STEREO_STEP) {
			for(int x = r;x < aW2-r;x += STEREO_STEP) {
				if(aHL[y*aW2+x]) {
					continue;
				}
				//Texture of the patch
				double aS = 0, aS2 = 0;
				for(int j = -r;j <= r;j++) {
					for(int i = -r;i <= r;i++) {
						int g = aGL[(y+j)*aW2+x+i];
						aS += g;
						aS2 += g*g;
					}
				}
				int aN = (2*r+1)*(2*r+1);
				if(aS2/aN-(aS/aN)*(aS/aN) < 30) {
					continue;
				}
				double aBest = Double.MAX_VALUE, aSecond = Double.MAX_VALUE;
				int aBD = 0, aBY = 0;
				double[] aCosts = new double[2*aMaxD+1];
				for(int dy = -1;dy <= 1;dy++) {
					for(int d = -aMaxD;d <= aMaxD;d++) {
						int xr = x+d;
						if(xr-r < 0 || xr+r >= aW2 || aHR[(y+dy)*aW2+xr]) {
							continue;
						}
						double c = 0;
						for(int j = -r;j <= r && c < aSecond;j++) {
							int aRowL = (y+j)*aW2+x, aRowR = (y+dy+j)*aW2+xr;
							for(int i = -r;i <= r;i++) {
								c += Math.abs(aGL[aRowL+i]-aGR[aRowR+i]);
							}
						}
						if(dy == 0) {
							aCosts[d+aMaxD] = c;
						}
						if(c < aBest) {
							if(Math.abs(d-aBD) > 2 || dy != aBY) {
								aSecond = aBest;
							}
							aBest = c;
							aBD = d;
							aBY = dy;
						}
						else if(c < aSecond && (Math.abs(d-aBD) > 2 || dy != aBY)) {
							aSecond = c;
						}
					}
				}
				if(aBest == Double.MAX_VALUE || aSecond == Double.MAX_VALUE || aBest > STEREO_UNIQUE*aSecond) {
					continue;
				}
				//Sub pixel (parabola on the line of the best)
				double aSub = aBD;
				if(aBY == 0 && aBD > -aMaxD && aBD < aMaxD) {
					double c0 = aCosts[aBD-1+aMaxD], c1 = aCosts[aBD+aMaxD], c2 = aCosts[aBD+1+aMaxD];
					double aDen = c0-2*c1+c2;
					if(c0 > 0 && c2 > 0 && aDen > 0) {
						aSub = aBD+0.5*(c0-c2)/aDen;
					}
				}
				int xl = 2*x, yl = 2*y;
				int xr = (int)Math.round(2*(x+aSub)), yr = 2*(y+aBY);
				if(xr < 0 || xr >= viewW || yr < 0 || yr >= viewH) {
					continue;
				}
				aM.add(new double[] {2*aSub, 2*aBY, aZ[0][yl*viewW+xl], aZ[1][yr*viewW+xr]});
			}
		}
		if(aM.size() < STEREO_MIN_MATCHES) {
			System.out.println("AI eraser: "+aM.size()+" stereo matches, parallax "+(dispLR == null ? "unknown" : "of the previous frame"));
			return;
		}
		double[] aLR = fit(aM, 0, 2, 1);
		double[] aRL = fit(aM, 0, 3, -1);
		double aDy = 0;
		double[] aDys = new double[aM.size()];
		for(int i = 0;i < aDys.length;i++) {
			aDys[i] = aM.get(i)[1];
		}
		Arrays.sort(aDys);
		aDy = aDys[aDys.length/2];
		aLR[2] = aDy;
		aRL[2] = -aDy;
		dispLR = smooth(dispLR, aLR);
		dispRL = smooth(dispRL, aRL);
	}

	static double[] smooth(double[] aOld,double[] aNew) {
		if(aOld == null) {
			return aNew;
		}
		double[] aRes = new double[3];
		for(int i = 0;i < 3;i++) {
			aRes[i] = 0.7*aOld[i]+0.3*aNew[i];
		}
		return aRes;
	}

	/**
	 * Robust line fit of aSign * the parallax (column aD) against the depth (column aZ): {a, b, 0}; a constant (the
	 * median) when the depth does not explain it
	 */
	static double[] fit(Vector<double[]> aM,int aD,int aZ,int aSign) {
		int n = aM.size();
		boolean[] aIn = new boolean[n];
		Arrays.fill(aIn, true);
		double a = 0, b = 0;
		for(int it = 0;it < 4;it++) {
			double sz = 0, sd = 0, szz = 0, szd = 0;
			int c = 0;
			for(int i = 0;i < n;i++) {
				if(!aIn[i]) {
					continue;
				}
				double z = aM.get(i)[aZ], d = aSign*aM.get(i)[aD];
				sz += z;
				sd += d;
				szz += z*z;
				szd += z*d;
				c++;
			}
			if(c < 3) {
				break;
			}
			double aDen = c*szz-sz*sz;
			if(Math.abs(aDen) < 1e-9) {
				b = 0;
				a = sd/c;
			}
			else {
				b = (c*szd-sz*sd)/aDen;
				a = (sd-b*sz)/c;
			}
			//Outliers: beyond 2.5 the median of the residuals (at least 1 pixel)
			double[] aRes = new double[c];
			int k = 0;
			for(int i = 0;i < n;i++) {
				if(aIn[i]) {
					aRes[k++] = Math.abs(aSign*aM.get(i)[aD]-a-b*aM.get(i)[aZ]);
				}
			}
			Arrays.sort(aRes);
			double aTh = Math.max(1, 2.5*aRes[c/2]);
			for(int i = 0;i < n;i++) {
				aIn[i] = Math.abs(aSign*aM.get(i)[aD]-a-b*aM.get(i)[aZ]) <= aTh;
			}
		}
		return new double[] {a, b, 0};
	}

	int[] half(int[] aG) {
		int aW2 = viewW/2, aH2 = viewH/2;
		int[] aRes = new int[aW2*aH2];
		for(int y = 0;y < aH2;y++) {
			for(int x = 0;x < aW2;x++) {
				int p = 2*y*viewW+2*x;
				aRes[y*aW2+x] = (aG[p]+aG[p+1]+aG[p+viewW]+aG[p+viewW+1])/4;
			}
		}
		return aRes;
	}

	boolean[] halfHoles(boolean[] aH) {
		int aW2 = viewW/2, aH2 = viewH/2;
		boolean[] aRes = new boolean[aW2*aH2];
		for(int y = 0;y < aH2;y++) {
			for(int x = 0;x < aW2;x++) {
				int p = 2*y*viewW+2*x;
				aRes[y*aW2+x] = aH[p] || aH[p+1] || aH[p+viewW] || aH[p+viewW+1];
			}
		}
		return aRes;
	}

	/**
	 * The pixels of the other view aSrc (known: not in aSrcHoles, or aSrcKnown; null: all) forward warped by the
	 * parallax aDisp ({a, b, dy}: x' = x + a + b z, y' = y + dy) into the holes aLeft of aPix (the nearest wins; two
	 * neighbors of close depths cover the span between them). The unknown pixels of aSrc are carried at the depth of
	 * their background only where the parallax changes slowly (PAIR_SLOPE) and in the wide parts of the holes.
	 * @return pixels given
	 */
	int carryView(int[] aSrc,float[] aZ,boolean[] aSrcHoles,boolean[] aSrcKnown,double[] aDisp,int[] aPix,boolean[] aLeft) {
		int n = viewW*viewH;
		float[] aZB = new float[n];
		boolean[] aKnown = new boolean[n];
		for(int p = 0;p < n;p++) {
			aKnown[p] = aSrcHoles != null ? !aSrcHoles[p] : aSrcKnown == null || aSrcKnown[p];
			aZB[p] = aKnown[p] ? aZ[p] : 0;
		}
		if(aSrcHoles == null) {
			//The filled pixels of the source: at the depth of their background, where it changes slowly, wide parts
			boolean[] aWide = SmartFill.wideHoles(aKnown, viewW, viewH, Math.max(2, viewH/180));
			for(int y = 0;y < viewH;y++) {
				for(int x = 0;x < viewW;x++) {
					int p = y*viewW+x;
					if(aKnown[p] || !aWide[p]) {
						continue;
					}
					float z = aZ[p];
					double aMax = 0;
					if(x > 0) aMax = Math.max(aMax, Math.abs(z-aZ[p-1]));
					if(x+1 < viewW) aMax = Math.max(aMax, Math.abs(z-aZ[p+1]));
					if(y > 0) aMax = Math.max(aMax, Math.abs(z-aZ[p-viewW]));
					if(y+1 < viewH) aMax = Math.max(aMax, Math.abs(z-aZ[p+viewW]));
					if(Math.abs(aDisp[1])*aMax <= PAIR_SLOPE) {
						aZB[p] = z;
					}
				}
			}
		}
		int[] aOut = new int[n];
		float[] aOutZ = new float[n];
		int aDy = (int)Math.round(aDisp[2]);
		for(int y = 0;y < viewH;y++) {
			int yt = y+aDy;
			if(yt < 0 || yt >= viewH) {
				continue;
			}
			int aRow = y*viewW;
			int aTRow = yt*viewW;
			for(int x = 0;x < viewW;x++) {
				int p = aRow+x;
				float z = aZB[p];
				if(z <= 0) {
					continue;
				}
				double xa = x+aDisp[0]+aDisp[1]*z;
				double xb = xa+1;
				if(x+1 < viewW) {
					float z2 = aZB[p+1];
					if(z2 > 0 && Math.max(z, z2) <= 1.1f*Math.min(z, z2)) {
						double x2 = x+1+aDisp[0]+aDisp[1]*z2;
						if(x2 > xa) {
							xb = Math.min(x2, xa+4);
						}
					}
				}
				int i0 = (int)Math.round(xa), i1 = Math.max(i0+1, (int)Math.round(xb));
				for(int i = Math.max(0, i0);i < Math.min(viewW, i1);i++) {
					int q = aTRow+i;
					if(z > aOutZ[q]) {
						aOutZ[q] = z;
						aOut[q] = aSrc[p];
					}
				}
			}
		}
		int aGiven = 0;
		for(int p = 0;p < n;p++) {
			if(aLeft[p] && aOutZ[p] > 0) {
				aPix[p] = aOut[p];
				aLeft[p] = false;
				aGiven++;
			}
		}
		return aGiven;
	}

	//== Between the frames ==

	/**
	 * Mean gray difference of the samples moved by (dx, dy) (every aSub-th sample); aQ: {part seen, part close}
	 */
	double cost(int[] aG,int[] aOG,boolean[] aOHoles,int[] aS,int aNS,int dx,int dy,int aSub,double[] aQ) {
		double aSum = 0;
		int aSeen = 0, aClose = 0, aTried = 0;
		for(int i = 0;i < aNS;i += aSub) {
			int p = aS[i];
			int x = p%viewW+dx, y = p/viewW+dy;
			aTried++;
			if(x < 0 || y < 0 || x >= viewW || y >= viewH) {
				continue;
			}
			int q = y*viewW+x;
			if(aOHoles != null && aOHoles[q]) {
				continue;
			}
			int d = Math.abs(aG[p]-aOG[q]);
			aSum += d;
			aSeen++;
			if(d < MATCH_DIFF) {
				aClose++;
			}
		}
		if(aQ != null) {
			aQ[0] = aTried == 0 ? 0 : aSeen/(double)aTried;
			aQ[1] = aSeen == 0 ? 0 : aClose/(double)aSeen;
		}
		if(aSeen < Math.max(10, aTried*MATCH_COVER)) {
			return Double.MAX_VALUE;
		}
		return aSum/aSeen;
	}

	//== Motion of the surroundings of each hole ==

	//Patches of the motion of the surroundings: grid step, radius, texture needed (gray variance), search radius
	//around the translation found, vectors kept within MOTION_KEEP pixels of their median
	static final int PATCH_STEP = 8;
	static final int PATCH_R = 3;
	static final double PATCH_VAR = 25;
	static final int PATCH_SEARCH = 2;
	static final double MOTION_KEEP = 1.0;
	static final int MOTION_MIN = 5;

	/**
	 * Holes grouped by their surroundings: connected parts of the holes grown by the ring (holes close to each
	 * other share their ring); per group, its ring of known pixels (sampled)
	 */
	static class Groups {
		//Group of each pixel (-1: none), only the holes matter for the carry
		int[] label;
		//Per group: samples of its ring (pixel indexes), and all its ring pixels (patches)
		int[][] samples;
		int[][] ring;
		int count() {
			return samples.length;
		}
	}

	Groups groups(boolean[] aHoles) {
		int n = viewW*viewH;
		int[] aD = SmartFill.distance(aHoles, viewW, viewH);
		int aLim = 3*RING;
		Groups aGr = new Groups();
		aGr.label = new int[n];
		java.util.Arrays.fill(aGr.label, -1);
		Vector<int[]> aSamples = new Vector<int[]>();
		Vector<int[]> aRings = new Vector<int[]>();
		int[] aStack = new int[n];
		for(int p0 = 0;p0 < n;p0++) {
			if(!aHoles[p0] || aGr.label[p0] >= 0) {
				continue;
			}
			int aId = aSamples.size();
			int aTop = 0;
			aStack[aTop++] = p0;
			aGr.label[p0] = aId;
			java.util.ArrayList<Integer> aRing = new java.util.ArrayList<Integer>();
			while(aTop > 0) {
				int p = aStack[--aTop];
				if(!aHoles[p]) {
					aRing.add(p);
				}
				int x = p%viewW, y = p/viewW;
				for(int d = 0;d < 4;d++) {
					int xn = x+(d == 0 ? 1 : d == 1 ? -1 : 0), yn = y+(d == 2 ? 1 : d == 3 ? -1 : 0);
					if(xn < 0 || yn < 0 || xn >= viewW || yn >= viewH) {
						continue;
					}
					int q = yn*viewW+xn;
					if(aGr.label[q] < 0 && aD[q] <= aLim) {
						aGr.label[q] = aId;
						aStack[aTop++] = q;
					}
				}
			}
			int[] aR = new int[aRing.size()];
			for(int i = 0;i < aR.length;i++) {
				aR[i] = aRing.get(i);
			}
			java.util.Arrays.sort(aR);
			int aStride = Math.max(1, aR.length/MAX_SAMPLES);
			int[] aS = new int[(aR.length+aStride-1)/aStride];
			for(int i = 0;i < aS.length;i++) {
				aS[i] = aR[i*aStride];
			}
			aSamples.add(aS);
			aRings.add(aR);
		}
		aGr.samples = aSamples.toArray(new int[0][]);
		aGr.ring = aRings.toArray(new int[0][]);
		return aGr;
	}

	/**
	 * Mean motion {dx, dy} (sub pixel) of the surroundings of each group of holes from this frame (aG, aHoles) to
	 * the other one (aOG, aOHoles): the pixels of the holes are never used, neither those of this frame nor those
	 * of the other (their content, the objects erased, moves on its own). aLast: the motions found on the frame
	 * before on this side (guess × aK / (aK-1)), null: none.
	 * @return per group its motion (null: not found), null when none was found
	 */
	double[][] motions(int[] aG,boolean[] aHoles,Groups aGr,int[] aOG,boolean[] aOHoles,double[][] aLast,int aK,int aRange) {
		double[][] aRes = new double[aGr.count()][];
		boolean aAny = false;
		for(int i = 0;i < aGr.count();i++) {
			int[] aGuess = {0, 0};
			if(aLast != null && aLast[i] != null) {
				double aF = aK > 1 ? aK/(aK-1.0) : 1;
				aGuess = new int[] {(int)Math.round(aLast[i][0]*aF), (int)Math.round(aLast[i][1]*aF)};
			}
			//The ring known in both frames at the same place: an object that moves covers in the other frame a large
			//part of the ring of this one (63 % for a person walking 20 px per frame), and those pixels, compared
			//with the object, only favored the motions that take the ring out of its hole
			int[] aS = knownIn(aGr.samples[i], aOHoles);
			if(aS.length < MIN_SAMPLES) {
				continue;
			}
			//The translation of the ring as a whole (integer), then the mean of the local motions around it
			int[] aT = search(aG, aOG, aOHoles, aS, aGuess, aRange);
			if(aT == null) {
				continue;
			}
			aRes[i] = meanMotion(aG, aHoles, knownIn(aGr.ring[i], aOHoles), aOG, aOHoles, aT);
			aAny = true;
		}
		return aAny ? aRes : null;
	}

	/** The pixels of aP not in the holes aOHoles (all if null) */
	static int[] knownIn(int[] aP,boolean[] aOHoles) {
		if(aOHoles == null) {
			return aP;
		}
		int n = 0;
		for(int p : aP) {
			if(!aOHoles[p]) {
				n++;
			}
		}
		int[] aRes = new int[n];
		n = 0;
		for(int p : aP) {
			if(!aOHoles[p]) {
				aRes[n++] = p;
			}
		}
		return aRes;
	}

	/**
	 * Integer translation of the samples aS onto the other frame (coarse by 4 then fine), null if it does not match
	 */
	int[] search(int[] aG,int[] aOG,boolean[] aOHoles,int[] aS,int[] aGuess,int aRange) {
		int aNS = aS.length;
		int[] aBest = {aGuess[0], aGuess[1]};
		double aBestC = Double.MAX_VALUE;
		int aStep = aRange > 8 ? 4 : 1;
		//A ring without texture (a uniform zone): any position matches it, the expected one is kept
		double aSum = 0, aSum2 = 0;
		for(int p : aS) {
			aSum += aG[p];
			aSum2 += (double)aG[p]*aG[p];
		}
		double aMean = aSum/Math.max(1, aNS);
		double[] aQ0 = new double[2];
		double aGuessC0 = cost(aG, aOG, aOHoles, aS, aNS, aGuess[0], aGuess[1], 1, aQ0);
		if(Math.sqrt(Math.max(0, aSum2/Math.max(1, aNS)-aMean*aMean)) < RING_TEXTURE) {
			return aGuessC0 < Double.MAX_VALUE && aQ0[1] >= MATCH_GOOD ? aBest : null;
		}
		//The positions that lose much of the ring (out of the frame, into the holes of the other frame) are not
		//compared: the few pixels left would match better only because they are fewer and more uniform
		double aMinCover = aGuessC0 < Double.MAX_VALUE ? COVER_KEEP*aQ0[0] : 0;
		double[] aQ = new double[2];
		for(int dy = aGuess[1]-aRange;dy <= aGuess[1]+aRange;dy += aStep) {
			for(int dx = aGuess[0]-aRange;dx <= aGuess[0]+aRange;dx += aStep) {
				double c = cost(aG, aOG, aOHoles, aS, aNS, dx, dy, aStep > 1 ? 4 : 1, aQ);
				if(aQ[0] < aMinCover) {
					continue;
				}
				if(c < aBestC) {
					aBestC = c;
					aBest = new int[] {dx, dy};
				}
			}
		}
		if(aStep > 1) {
			int[] aC = aBest;
			for(int dy = aC[1]-3;dy <= aC[1]+3;dy++) {
				for(int dx = aC[0]-3;dx <= aC[0]+3;dx++) {
					double c = cost(aG, aOG, aOHoles, aS, aNS, dx, dy, 1, aQ);
					if(aQ[0] < aMinCover) {
						continue;
					}
					if(c < aBestC) {
						aBestC = c;
						aBest = new int[] {dx, dy};
					}
				}
			}
		}
		if(aBestC == Double.MAX_VALUE) {
			return null;
		}
		//Another position kept only when it matches clearly better than the expected one (the guess, else no
		//motion): on a surface without texture (a wall, the sky), all the positions look alike and the best one
		//is a matter of noise
		double aGuessC = cost(aG, aOG, aOHoles, aS, aNS, aGuess[0], aGuess[1], 1, null);
		double aBestC1 = cost(aG, aOG, aOHoles, aS, aNS, aBest[0], aBest[1], 1, null);
		if(aGuessC < Double.MAX_VALUE && !(aBestC1 < aGuessC*(1-SEARCH_GAIN) && aGuessC-aBestC1 > SEARCH_MIN_GAIN)) {
			aBest = new int[] {aGuess[0], aGuess[1]};
		}
		cost(aG, aOG, aOHoles, aS, aNS, aBest[0], aBest[1], 1, aQ);
		return aQ[0] >= MATCH_COVER && aQ[1] >= MATCH_GOOD ? aBest : null;
	}

	//A translation other than the expected one: its mean difference lower by this part and these gray levels
	static final double SEARCH_GAIN = 0.15;
	static final double SEARCH_MIN_GAIN = 1.5;
	//Ring without texture below this gray standard deviation; part of the ring seen at the expected position that
	//another position must keep
	static final double RING_TEXTURE = 6;
	static final double COVER_KEEP = 0.9;

	/**
	 * Mean motion of the pixels of a ring: patches on a grid of the ring (textured ones only, their pixels outside
	 * the holes of both frames), each matched within PATCH_SEARCH of aT with a sub pixel refinement (parabolas);
	 * the vectors within MOTION_KEEP of their median averaged. Too few vectors: aT.
	 */
	double[] meanMotion(int[] aG,boolean[] aHoles,int[] aRing,int[] aOG,boolean[] aOHoles,int[] aT) {
		int aW = 2*PATCH_SEARCH+1;
		Vector<double[]> aV = new Vector<double[]>();
		int aPatches = 0;
		double[] aC = new double[aW*aW];
		for(int p : aRing) {
			int x0 = p%viewW, y0 = p/viewW;
			if(x0%PATCH_STEP != 0 || y0%PATCH_STEP != 0) {
				continue;
			}
			//Texture of the patch (its known pixels)
			double aSum = 0, aSum2 = 0;
			int aN = 0;
			for(int y = y0-PATCH_R;y <= y0+PATCH_R;y++) {
				for(int x = x0-PATCH_R;x <= x0+PATCH_R;x++) {
					if(x < 0 || y < 0 || x >= viewW || y >= viewH || aHoles[y*viewW+x]) {
						continue;
					}
					int g = aG[y*viewW+x];
					aSum += g;
					aSum2 += g*g;
					aN++;
				}
			}
			if(aN < (2*PATCH_R+1)*(2*PATCH_R+1)/2) {
				continue;
			}
			double aMean = aSum/aN;
			if(aSum2/aN-aMean*aMean < PATCH_VAR) {
				continue;
			}
			aPatches++;
			//Mean difference at each position of the search window
			int aBest = -1;
			for(int j = 0;j < aW*aW;j++) {
				int dx = aT[0]+j%aW-PATCH_SEARCH, dy = aT[1]+j/aW-PATCH_SEARCH;
				double aD = 0;
				int aM = 0;
				for(int y = y0-PATCH_R;y <= y0+PATCH_R;y++) {
					for(int x = x0-PATCH_R;x <= x0+PATCH_R;x++) {
						if(x < 0 || y < 0 || x >= viewW || y >= viewH || aHoles[y*viewW+x]) {
							continue;
						}
						int xo = x+dx, yo = y+dy;
						if(xo < 0 || yo < 0 || xo >= viewW || yo >= viewH) {
							continue;
						}
						int q = yo*viewW+xo;
						if(aOHoles != null && aOHoles[q]) {
							continue;
						}
						//Squared: the parabola of the sub pixel refinement fits it (not the absolute differences)
						double d = aG[y*viewW+x]-aOG[q];
						aD += d*d;
						aM++;
					}
				}
				aC[j] = aM*10 < aN*7 ? Double.MAX_VALUE : aD/aM;
				if(aC[j] < Double.MAX_VALUE && (aBest < 0 || aC[j] < aC[aBest])) {
					aBest = j;
				}
			}
			if(aBest < 0 || aC[aBest] >= MATCH_DIFF*MATCH_DIFF) {
				continue;
			}
			int bx = aBest%aW, by = aBest/aW;
			//Minimum on the edge of the window: the patch moves otherwise (an object), not used
			if(bx == 0 || by == 0 || bx == aW-1 || by == aW-1) {
				continue;
			}
			double[] aSub = refine(aG, aHoles, aOG, aOHoles, x0, y0, aT[0]+bx-PATCH_SEARCH, aT[1]+by-PATCH_SEARCH);
			if(aSub != null) {
				aV.add(aSub);
			}
		}
		if(aV.size() < MOTION_MIN || aV.size()*5 < aPatches*2) {
			return new double[] {aT[0], aT[1]};
		}
		double[] aXs = new double[aV.size()], aYs = new double[aV.size()];
		for(int i = 0;i < aXs.length;i++) {
			aXs[i] = aV.get(i)[0];
			aYs[i] = aV.get(i)[1];
		}
		java.util.Arrays.sort(aXs);
		java.util.Arrays.sort(aYs);
		double aMX = aXs[aXs.length/2], aMY = aYs[aYs.length/2];
		double aSX = 0, aSY = 0;
		int aKept = 0;
		for(double[] aM : aV) {
			if(Math.abs(aM[0]-aMX) <= MOTION_KEEP && Math.abs(aM[1]-aMY) <= MOTION_KEEP) {
				aSX += aM[0];
				aSY += aM[1];
				aKept++;
			}
		}
		if(aKept < MOTION_MIN) {
			return new double[] {aT[0], aT[1]};
		}
		return new double[] {aSX/aKept, aSY/aKept};
	}

	/**
	 * Sub pixel motion of the patch at (x0, y0) from the integer one (aDX, aDY): Lucas-Kanade iterations on the
	 * other frame sampled bilinearly (its holes excluded, those of this frame too); null if it does not converge
	 * within a pixel
	 */
	double[] refine(int[] aG,boolean[] aHoles,int[] aOG,boolean[] aOHoles,int x0,int y0,int aDX,int aDY) {
		double dx = aDX, dy = aDY;
		for(int it = 0;it < 4;it++) {
			double aXX = 0, aXY = 0, aYY = 0, aBX = 0, aBY = 0;
			for(int y = y0-PATCH_R;y <= y0+PATCH_R;y++) {
				for(int x = x0-PATCH_R;x <= x0+PATCH_R;x++) {
					if(x < 0 || y < 0 || x >= viewW || y >= viewH || aHoles[y*viewW+x]) {
						continue;
					}
					double sx = x+dx, sy = y+dy;
					int xi = (int)Math.floor(sx), yi = (int)Math.floor(sy);
					if(xi < 1 || yi < 1 || xi+2 >= viewW || yi+2 >= viewH) {
						continue;
					}
					int q = yi*viewW+xi;
					if(aOHoles != null && (aOHoles[q] || aOHoles[q+1] || aOHoles[q+viewW] || aOHoles[q+viewW+1])) {
						continue;
					}
					double fx = sx-xi, fy = sy-yi;
					double aI = grayAt(aOG, q, fx, fy);
					double gx = 0.5*(grayAt(aOG, q+1, fx, fy)-grayAt(aOG, q-1, fx, fy));
					double gy = 0.5*(grayAt(aOG, q+viewW, fx, fy)-grayAt(aOG, q-viewW, fx, fy));
					double e = aG[y*viewW+x]-aI;
					aXX += gx*gx;
					aXY += gx*gy;
					aYY += gy*gy;
					aBX += gx*e;
					aBY += gy*e;
				}
			}
			double aDet = aXX*aYY-aXY*aXY;
			if(aDet <= 1e-6*(aXX+aYY)*(aXX+aYY) || aDet <= 0) {
				return null;
			}
			double ux = (aYY*aBX-aXY*aBY)/aDet;
			double uy = (aXX*aBY-aXY*aBX)/aDet;
			dx += ux;
			dy += uy;
			if(Math.abs(dx-aDX) > 1 || Math.abs(dy-aDY) > 1) {
				return null;
			}
			if(Math.abs(ux) < 0.01 && Math.abs(uy) < 0.01) {
				break;
			}
		}
		return new double[] {dx, dy};
	}

	/** Gray level of aOG bilinearly between q, q+1, q+w, q+w+1 */
	double grayAt(int[] aOG,int q,double fx,double fy) {
		return (aOG[q]*(1-fx)+aOG[q+1]*fx)*(1-fy)+(aOG[q+viewW]*(1-fx)+aOG[q+viewW+1]*fx)*fy;
	}

	/**
	 * The holes aLeft (restricted to aOnly if not null) take the pixels of the other frame aSrc moved by the motion
	 * of their group (bilinear when its four pixels are known, else the nearest one when known, not in aSrcHoles)
	 * @return pixels given
	 */
	int carryMotion(int[] aSrc,boolean[] aSrcHoles,Groups aGr,double[][] aM,int[] aPix,boolean[] aLeft,boolean[] aOnly) {
		int aGiven = 0;
		for(int y = 0;y < viewH;y++) {
			for(int x = 0;x < viewW;x++) {
				int p = y*viewW+x;
				if(!aLeft[p] || (aOnly != null && !aOnly[p])) {
					continue;
				}
				int aL = aGr.label[p];
				if(aL < 0 || aM[aL] == null) {
					continue;
				}
				double sx = x+aM[aL][0], sy = y+aM[aL][1];
				int x0 = (int)Math.floor(sx), y0 = (int)Math.floor(sy);
				double fx = sx-x0, fy = sy-y0;
				int c = -1;
				if(x0 >= 0 && y0 >= 0 && x0+1 < viewW && y0+1 < viewH) {
					int q = y0*viewW+x0;
					if(aSrcHoles == null || !(aSrcHoles[q] || aSrcHoles[q+1] || aSrcHoles[q+viewW] || aSrcHoles[q+viewW+1])) {
						c = bilinear(aSrc[q], aSrc[q+1], aSrc[q+viewW], aSrc[q+viewW+1], fx, fy);
					}
				}
				if(c < 0) {
					int xn = (int)Math.round(sx), yn = (int)Math.round(sy);
					if(xn < 0 || yn < 0 || xn >= viewW || yn >= viewH) {
						continue;
					}
					int q = yn*viewW+xn;
					if(aSrcHoles != null && aSrcHoles[q]) {
						continue;
					}
					c = aSrc[q] & 0xFFFFFF;
				}
				aPix[p] = c;
				aLeft[p] = false;
				aGiven++;
			}
		}
		return aGiven;
	}

	static int bilinear(int a,int b,int c,int d,double fx,double fy) {
		int aRes = 0;
		for(int sh = 16;sh >= 0;sh -= 8) {
			double v = ((a>>sh)&0xFF)*(1-fx)*(1-fy)+((b>>sh)&0xFF)*fx*(1-fy)+((c>>sh)&0xFF)*(1-fx)*fy+((d>>sh)&0xFF)*fx*fy;
			aRes |= clamp((float)v)<<sh;
		}
		return aRes;
	}
}
