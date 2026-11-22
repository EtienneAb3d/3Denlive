package com.cubaix.TDenlive.processors;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.file.Files;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.Vector;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongFunction;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

import com.cubaix.TDenlive.TDConfig;
import com.cubaix.TDenlive.TDenlive;
import com.cubaix.TDenlive.depth.Inpainter;
import com.cubaix.TDenlive.medias.Clip;
import com.cubaix.TDenlive.medias.StereoMovie;
import com.cubaix.TDenlive.medias.VideoTimeLine;

/**
 * "Fill the holes with AI" option of the depth map conversions (Depthmap, DepthmapStereo): the holes that the
 * interpolation of their edges fills (uncovered behind the foreground) are filled by the inpainting model
 * (depth.Inpainter) instead. The interpolation is done first; whatever the mode, the model runs on the image
 * reduced to the work resolution (workSize()), and its result, enlarged, fills the holes of the image at its size
 * (no margin; a reduced pixel with at least one hole pixel is a hole, so that no hole keeps the interpolation;
 * the interpolation only remains when the model gives nothing: cache miss, failure).
 * The cache, the index and the temporal smoothing are at the reduced size.
 * Results cached by content: key = hash of the source view, its depth map, the parameters at the time and the
 * size; value = the replaced pixels (files of a temporary folder, deleted at the end of the application).
 * Method of the clip per processing level (method[], GUI.InpaintGUI panel; INTERPOLATION until configured):
 * work resolution and full screen preview: INTERPOLATION or PRECOMPUTE (the model, precomputed); final
 * rendering: INTERPOLATION, PRECOMPUTE or AT_RENDER (the model, computed only by the rendering).
 * Plans:
 * - RENDER: computed (or taken from the cache);
 * - monitor level with the model, when an image with the model takes less than SLOW_MS (10 fps): computed
 * directly, else only the cache (miss: the interpolation);
 * - the Precomputer thread computes all the images of the clips in the cache, for each level to precompute, at
 * each change of the parameters (while not playing nor rendering, the level of the monitor and the image of the
 * time cursor first), its progress shown on the monitor ruler and in GUI.InpaintProgressGUI.
 * Outside the rendering the parameters are taken at times rounded to the frames of the montage (snap()), so
 * that a playing finds the images precomputed. The rendering ones are precomputed at the times of the
 * rendering from 0 (RendererGUI: floor(1000 k / fps)).
 */
public class Inpainting {
	public enum Plan {NONE, LIVE, CACHE, PRECOMPUTE}
	//Below 10 images per second: precomputed
	static final double SLOW_MS = 100;

	final Processor proc;
	//Parameters of the processor at a time (text), part of the keys
	final LongFunction<String> params;
	public static final int INTERPOLATION = 0;
	public static final int PRECOMPUTE = 1;
	public static final int AT_RENDER = 2;
	//Method per mode WORK, FULLSCREEN, RENDER (saved by the processor)
	public final int[] method = {INTERPOLATION, INTERPOLATION, INTERPOLATION};
	//The model is used by at least one mode
	public boolean enabled = false;
	//Time of an image when the model runs (ms, both views), per mode, -1 if not measured
	final double[] msPerFrame = {-1, -1, -1, -1};
	//Time spent in the model for the image in progress
	volatile long modelMS = 0;
	//Images done by the precomputation (see Precomputer.signature())
	final Set<String> doneSigs = Collections.synchronizedSet(new HashSet<String>());
	//Last failure of the model
	volatile long failedMS = 0;


	static File cacheDir = null;
	static final Set<String> cached = ConcurrentHashMap.newKeySet();

	Inpainting(Processor aProc,LongFunction<String> aParams) {
		proc = aProc;
		params = aParams;
	}

	/**
	 * Methods of the modes WORK, FULLSCREEN, RENDER
	 */
	public void setMethods(int aWork,int aFull,int aRender) {
		method[0] = aWork;
		method[1] = aFull;
		method[2] = aRender;
		setEnabled(aWork != INTERPOLATION || aFull != INTERPOLATION || aRender != INTERPOLATION);
	}

	void setEnabled(boolean aEnabled) {
		enabled = aEnabled;
		if(aEnabled) {
			Inpainter.resetError();
			failedMS = 0;
			Precomputer.start(proc.tde);
		}
	}

	/**
	 * Methods saved as <inpaintModes>work;full;render</inpaintModes>
	 */
	public String modesText() {
		return method[0]+";"+method[1]+";"+method[2];
	}

	public void setModesText(String aText) {
		try {
			String[] aVs = aText.trim().split(";");
			if(aVs.length >= 3) {
				setMethods(Integer.parseInt(aVs[0].trim()), Integer.parseInt(aVs[1].trim()), Integer.parseInt(aVs[2].trim()));
			}
		}
		catch(Throwable t) {
			t.printStackTrace(System.err);
		}
	}

	/**
	 * <inpaint>true</inpaint> of the projects before the methods: the model on the work preview and the rendering
	 */
	public void setOldOption(boolean aEnabled) {
		if(aEnabled) {
			setMethods(PRECOMPUTE, PRECOMPUTE, AT_RENDER);
		}
	}

	/**
	 * The model fills the holes in this mode
	 */
	public boolean usesAi(int aMode) {
		return aMode >= 0 && aMode < method.length && method[aMode] != INTERPOLATION;
	}

	/**
	 * The images of this mode are precomputed in the background
	 */
	public boolean precomputes(int aMode) {
		return aMode >= 0 && aMode < method.length && method[aMode] == PRECOMPUTE;
	}

	boolean isSlow(int aMode) {
		double aMS = msPerFrame[aMode];
		return aMS < 0 || aMS > SLOW_MS;
	}

	/**
	 * What to do for an image in this mode
	 */
	Plan plan(int aMode) {
		if(!usesAi(aMode) || !Inpainter.isReady(proc.tde.tdeDir) || !Inpainter.capable()) {
			//Model not usable here (LaMa: no GPU): always the interpolation
			return Plan.NONE;
		}
		if(Thread.currentThread() == Precomputer.thread) {
			return Plan.PRECOMPUTE;
		}
		if(aMode == TDConfig.PROCESSING_MODE_RENDER || !isSlow(aMode)) {
			return Plan.LIVE;
		}
		return Plan.CACHE;
	}

	/**
	 * Time of the parameters: outside the rendering, rounded to the frames of the montage (same images
	 * precomputed and played)
	 */
	long snap(long aTime,int aMode) {
		if(!usesAi(aMode) || aMode == TDConfig.PROCESSING_MODE_RENDER || !Inpainter.capable()) {
			return aTime;
		}
		double aFps = proc.tde.config.outFps > 0 ? proc.tde.config.outFps : 30;
		long aKT = proc.keyTime(aTime);
		long aSnapped = Math.round(Math.round(aKT*aFps/1000.0)*1000.0/aFps);
		return aSnapped == aKT ? aTime : proc.keyTimePos(aSnapped);
	}

	/**
	 * Start of an image (process2Awt)
	 */
	void beginFrame() {
		modelMS = 0;
	}

	/**
	 * End of an image that took aMS: measured if the model ran
	 */
	void endFrame(int aMode,long aMS) {
		if(modelMS > 0 && aMode >= 0 && aMode < msPerFrame.length) {
			msPerFrame[aMode] = msPerFrame[aMode] < 0 ? aMS : msPerFrame[aMode]*0.5+aMS*0.5;
		}
	}

	/**
	 * Vote of a hole of a line of the warped image for the side of its foreground: +1 when the pixel on its left
	 * is nearer (aZLeft < aZRight: smaller Z = front, as in Depthmap / DepthmapStereo), -1 when the one on its
	 * right is. The sum over the holes of an image, weighted by their lengths, < 0: the image is given mirrored
	 * to the model (see Inpainter.fill())
	 */
	public static int side(double aZLeft,double aZRight) {
		return aZLeft < aZRight ? 1 : (aZLeft > aZRight ? -1 : 0);
	}

	static long mix(long aH,long aV) {
		aH ^= aV;
		aH *= 0x9E3779B97F4A7C15L;
		return aH ^ (aH >>> 29);
	}

	/**
	 * Key of the result for these inputs
	 */
	String key(int[] aSrc,int[] aDM,int aW,int aH,long aParamsTime,boolean aIsL) {
		long aH1 = 0x1234567L;
		long aH2 = 0x7654321L;
		for(int p = 0;p < aSrc.length;p++) {
			aH1 = mix(aH1, aSrc[p]);
		}
		for(int p = 0;p < aDM.length;p++) {
			aH2 = mix(aH2, aDM[p]);
		}
		return keyOf(aH1, aH2, aW, aH, aParamsTime, aIsL);
	}

	String key(float[] aSrc,float[] aDM,int aW,int aH,long aParamsTime,boolean aIsL) {
		long aH1 = 0x1234567L;
		long aH2 = 0x7654321L;
		for(int p = 0;p < aSrc.length;p++) {
			aH1 = mix(aH1, Float.floatToIntBits(aSrc[p]));
		}
		for(int p = 0;p < aDM.length;p++) {
			aH2 = mix(aH2, Float.floatToIntBits(aDM[p]));
		}
		return keyOf(aH1, aH2, aW, aH, aParamsTime, aIsL)+"f";
	}

	String keyOf(long aH1,long aH2,int aW,int aH,long aParamsTime,boolean aIsL) {
		long aH3 = mix(0x55AA55AAL, params.apply(aParamsTime).hashCode());
		aH3 = mix(aH3, proc.getClassName().hashCode());
		int[] aS = workSize(aW, aH);
		return Long.toHexString(aH1)+Long.toHexString(aH2)+Long.toHexString(aH3)+"_"+aW+"x"+aH+"_"+aS[0]+"x"+aS[1]+(aIsL ? "L" : "R");
	}

	static synchronized File cacheFile(String aKey) throws Exception {
		if(cacheDir == null) {
			cacheDir = Files.createTempDirectory("3De_Inpaint_").toFile();
			File aDir = cacheDir;
			Runtime.getRuntime().addShutdownHook(new Thread(() -> {
				File[] aFs = aDir.listFiles();
				if(aFs != null) {
					for(File aF : aFs) {
						aF.delete();
					}
				}
				aDir.delete();
			}));
		}
		return new File(cacheDir, aKey+".bin");
	}

	/**
	 * An image of a view: its cache key, where it is in the time (index of the neighbors), a small gray image
	 * of its source (similarity of the neighbors), the parameters it was made with
	 */
	public static class Frame {
		String key;
		//Mode, view, size (and HDR): neighbors only of the same
		String id;
		long time;
		float[] thumb;
		int paramsHash;
		int w;
		int h;
		//Foreground on the right of the holes: mirrored for the model
		boolean flip;
		//Smoothed result last found / made for it (Precomputer progress)
		volatile String smoothKey = null;
		//Key of its smoothed result with the neighbors of the index at version targetVersion
		volatile String smoothTarget = null;
		volatile long targetVersion = -1;
	}

	//Side of the gray thumbnails of the sources
	static final int THUMB = 24;
	//Neighbors used by the temporal smoothing: frames of the montage on each side, standard deviation (frames)
	static final int SMOOTH_FRAMES = 3;
	static final double SMOOTH_SIGMA = 1.5;
	//Mean difference of the thumbnails (0..1) under which a neighbor counts fully, above which not at all
	static final double SIMILAR = 0.04;
	static final double DIFFERENT = 0.10;
	//Images kept per time of the index (a video may change of image inside a frame of the montage)
	static final int PER_TIME = 6;
	//Index of the images made: id -> time -> images
	final Map<String,TreeMap<Long,Vector<Frame>>> index = new HashMap<String,TreeMap<Long,Vector<Frame>>>();
	//Changed when an image is added to the index (the neighbors may change)
	volatile long indexVersion = 0;

	/**
	 * The image to fill: source view, its depth map, size, time, view, foreground on the right of the holes
	 * (vote of side()), mode
	 */
	Frame frame(int[] aSrc,int[] aDM,int aW,int aH,long aTime,boolean aIsL,boolean aFlip,int aMode) {
		Frame aF = frameOf(key(aSrc, aDM, aW, aH, aTime, aIsL), aW, aH, aTime, aIsL, aMode, "");
		aF.flip = aFlip;
		aF.thumb = new float[THUMB*THUMB];
		thumb(aW, aH, aF.thumb, p -> {
			int aC = aSrc[p];
			return (0.299f*((aC>>16) & 0xFF)+0.587f*((aC>>8) & 0xFF)+0.114f*(aC & 0xFF))/255f;
		});
		return aF;
	}

	Frame frame(float[] aSrc,float[] aDM,int aW,int aH,long aTime,boolean aIsL,boolean aFlip,int aMode) {
		Frame aF = frameOf(key(aSrc, aDM, aW, aH, aTime, aIsL), aW, aH, aTime, aIsL, aMode, "f");
		aF.flip = aFlip;
		aF.thumb = new float[THUMB*THUMB];
		thumb(aW, aH, aF.thumb, p -> Math.max(0f, Math.min(1f, 0.299f*aSrc[p*4]+0.587f*aSrc[p*4+1]+0.114f*aSrc[p*4+2])));
		return aF;
	}

	Frame frameOf(String aKey,int aW,int aH,long aTime,boolean aIsL,int aMode,String aSuffix) {
		Frame aF = new Frame();
		aF.key = aKey;
		aF.id = aMode+(aIsL ? "L" : "R")+aW+"x"+aH+aSuffix;
		aF.time = aTime;
		//Patch at the work resolution
		int[] aS = workSize(aW, aH);
		aF.w = aS[0];
		aF.h = aS[1];
		aF.paramsHash = params.apply(aTime).hashCode();
		return aF;
	}

	interface Gray {
		float at(int p);
	}

	/**
	 * Gray thumbnail: average of the cells of a THUMB x THUMB grid (sampled)
	 */
	static void thumb(int aW,int aH,float[] aT,Gray aGray) {
		for(int ty = 0;ty < THUMB;ty++) {
			int aY0 = ty*aH/THUMB;
			int aY1 = Math.max(aY0+1, (ty+1)*aH/THUMB);
			for(int tx = 0;tx < THUMB;tx++) {
				int aX0 = tx*aW/THUMB;
				int aX1 = Math.max(aX0+1, (tx+1)*aW/THUMB);
				double aSum = 0;
				int aN = 0;
				int aStepY = Math.max(1, (aY1-aY0)/4);
				int aStepX = Math.max(1, (aX1-aX0)/4);
				for(int y = aY0;y < aY1 && y < aH;y += aStepY) {
					for(int x = aX0;x < aX1 && x < aW;x += aStepX) {
						aSum += aGray.at(y*aW+x);
						aN++;
					}
				}
				aT[ty*THUMB+tx] = aN == 0 ? 0 : (float)(aSum/aN);
			}
		}
	}

	/**
	 * Replaced pixels: indexes (ascending) and RGB values 0..1
	 */
	static class Patch {
		int[] idx;
		float[] vals;
		Patch(int[] aIdx,float[] aVals) {
			idx = aIdx;
			vals = aVals;
		}
	}

	interface RGBSource {
		float[] rgb();
	}

	/**
	 * The patch of an image, as planned: smoothed with its neighbors in the time if possible (not while
	 * precomputing: its neighbors may not be there yet, the Precomputer smooths after), from the cache, else
	 * computed (not in the CACHE plan). Null: the interpolation stays.
	 */
	Patch patch(Plan aPlan,Frame aF,boolean[] aHoles,RGBSource aRGB) throws Exception {
		Patch aRaw = null;
		if(cached.contains(aF.key)) {
			float[] aV = load(aF.key, aF.w, aF.h);
			if(aV != null) {
				aRaw = new Patch(lastIdx.get(), aV);
			}
		}
		if(aRaw == null) {
			if(aPlan == Plan.CACHE) {
				return null;
			}
			float[] aRGBs = aRGB.rgb();
			if(!runModel(aRGBs, aF.w, aF.h, aHoles, aF.flip)) {
				return null;
			}
			int[] aIdx = indexes(aHoles);
			float[] aVals = new float[aIdx.length*3];
			for(int i = 0;i < aIdx.length;i++) {
				System.arraycopy(aRGBs, aIdx[i]*3, aVals, i*3, 3);
			}
			store(aF.key, aF.w, aF.h, aIdx, aVals);
			aRaw = new Patch(aIdx, aVals);
		}
		aF = register(aF);
		if(aPlan == Plan.PRECOMPUTE) {
			return aRaw;
		}
		Patch aS = smoothed(aF, aRaw, aPlan != Plan.CACHE, null);
		return aS != null ? aS : aRaw;
	}

	/**
	 * Size of the image given to the model: the height of the work resolution (never enlarged)
	 */
	int[] workSize(int aW,int aH) {
		int aRes = proc.tde.config.processingResValues[TDConfig.PROCESSING_MODE_WORK];
		if(aRes <= 0 || aH <= aRes) {
			return new int[] {aW, aH};
		}
		return new int[] {Math.max(1, (int)Math.round(aW*aRes/(double)aH)), aRes};
	}

	interface Color {
		//Channel aC (0..2, 0..1) of the pixel aP
		float at(int aP,int aC);
	}

	/**
	 * Reduction of the image to aSW x aSH: a reduced pixel is a hole when one of its pixels at least is
	 * (in aSHoles); its color (aSRGB, if not null) = the average of its non hole pixels
	 */
	static void reduce(int aW,int aH,boolean[] aHoles,Color aColor,int aSW,int aSH,boolean[] aSHoles,float[] aSRGB) {
		for(int sy = 0;sy < aSH;sy++) {
			int aY0 = (int)Math.floor(sy*aH/(double)aSH);
			int aY1 = Math.max(aY0+1, Math.min(aH, (int)Math.floor((sy+1)*aH/(double)aSH)));
			for(int sx = 0;sx < aSW;sx++) {
				int aX0 = (int)Math.floor(sx*aW/(double)aSW);
				int aX1 = Math.max(aX0+1, Math.min(aW, (int)Math.floor((sx+1)*aW/(double)aSW)));
				double aR = 0, aG = 0, aB = 0;
				int aN = 0;
				int aNH = 0;
				for(int y = aY0;y < aY1;y++) {
					for(int x = aX0;x < aX1;x++) {
						int p = y*aW+x;
						if(aHoles[p]) {
							aNH++;
						}
						else {
							if(aSRGB != null) {
								aR += aColor.at(p, 0);
								aG += aColor.at(p, 1);
								aB += aColor.at(p, 2);
							}
							aN++;
						}
					}
				}
				int sp = sy*aSW+sx;
				boolean aHole = aNH > 0 || aN == 0;
				aSHoles[sp] = aHole;
				if(aSRGB != null && !aHole) {
					aSRGB[sp*3] = (float)(aR/aN);
					aSRGB[sp*3+1] = (float)(aG/aN);
					aSRGB[sp*3+2] = (float)(aB/aN);
				}
			}
		}
	}

	interface Writer {
		//Color (0..1) of the hole pixel aP
		void set(int aP,float aR,float aG,float aB);
	}

	/**
	 * The patch of the image reduced to the work resolution, as planned, enlarged to the hole pixels of the
	 * image (aW x aH), without interpolation
	 */
	void fillScaled(Plan aPlan,Frame aF,int aW,int aH,boolean[] aHoles,Color aColor,Writer aOut) throws Exception {
		int aSW = aF.w;
		int aSH = aF.h;
		boolean[] aSHoles = new boolean[aSW*aSH];
		if(aSW == aW && aSH == aH) {
			System.arraycopy(aHoles, 0, aSHoles, 0, aHoles.length);
		}
		else {
			reduce(aW, aH, aHoles, aColor, aSW, aSH, aSHoles, null);
		}
		Patch aP = patch(aPlan, aF, aSHoles, () -> {
			float[] aRGB = new float[aSW*aSH*3];
			if(aSW == aW && aSH == aH) {
				for(int p = 0;p < aW*aH;p++) {
					for(int c = 0;c < 3;c++) {
						aRGB[p*3+c] = aColor.at(p, c);
					}
				}
			}
			else {
				reduce(aW, aH, aHoles, aColor, aSW, aSH, new boolean[aSW*aSH], aRGB);
			}
			return aRGB;
		});
		if(aP == null) {
			return;
		}
		//Reduced image of the patch
		float[] aImg = new float[aSW*aSH*3];
		boolean[] aIn = new boolean[aSW*aSH];
		for(int i = 0;i < aP.idx.length;i++) {
			int sp = aP.idx[i];
			aIn[sp] = true;
			System.arraycopy(aP.vals, i*3, aImg, sp*3, 3);
		}
		float[] aPix = new float[3];
		for(int y = 0;y < aH;y++) {
			double aFY = (y+0.5)*aSH/aH-0.5;
			int sy = Math.min(aSH-1, (int)Math.floor(y*aSH/(double)aH));
			for(int x = 0;x < aW;x++) {
				int p = y*aW+x;
				if(!aHoles[p]) {
					continue;
				}
				int sx = Math.min(aSW-1, (int)Math.floor(x*aSW/(double)aW));
				if(!aIn[sy*aSW+sx]) {
					continue;
				}
				double aFX = (x+0.5)*aSW/aW-0.5;
				//From the pixels of the patch only (the others are not filled by the model)
				Inpainter.maskedBilinear(aImg, aIn, true, aSW, aSH, aFX, aFY, sx, sy, aPix, 0);
				aOut.set(p, aPix[0], aPix[1], aPix[2]);
			}
		}
	}

	/**
	 * Fill the holes of an 8 bits image (aFDB ARGB), as planned (see fillScaled()).
	 * aHoles: the pixels filled by the interpolation
	 */
	void fill(Plan aPlan,Frame aF,int[] aFDB,int aW,int aH,boolean[] aHoles) {
		if(aPlan == Plan.NONE) {
			return;
		}
		try {
			fillScaled(aPlan, aF, aW, aH, aHoles, (p, c) -> ((aFDB[p]>>(16-8*c)) & 0xFF)/255f, (p, r, g, b) -> {
				int aA = aFDB[p] & 0xFF000000;
				if(aA == 0) {
					//Margin of a hole on a transparent pixel: kept
					return;
				}
				int aR = Math.max(0, Math.min(255, Math.round(r*255)));
				int aG = Math.max(0, Math.min(255, Math.round(g*255)));
				int aB = Math.max(0, Math.min(255, Math.round(b*255)));
				aFDB[p] = aA|(aR<<16)|(aG<<8)|aB;
			});
		}
		catch(Throwable t) {
			t.printStackTrace(System.err);
		}
	}

	/**
	 * Same for a HDR image (aFDB RGBA floats)
	 */
	void fill(Plan aPlan,Frame aF,float[] aFDB,int aW,int aH,boolean[] aHoles) {
		if(aPlan == Plan.NONE) {
			return;
		}
		try {
			fillScaled(aPlan, aF, aW, aH, aHoles, (p, c) -> Math.max(0f, Math.min(1f, aFDB[p*4+c])), (p, r, g, b) -> {
				if(aFDB[p*4+3] <= 0) {
					return;
				}
				aFDB[p*4] = r;
				aFDB[p*4+1] = g;
				aFDB[p*4+2] = b;
			});
		}
		catch(Throwable t) {
			t.printStackTrace(System.err);
		}
	}

	/**
	 * The image in the index (the one already there for the same key, updated)
	 */
	Frame register(Frame aF) {
		synchronized(index) {
			TreeMap<Long,Vector<Frame>> aByTime = index.computeIfAbsent(aF.id, k -> new TreeMap<Long,Vector<Frame>>());
			Vector<Frame> aFs = aByTime.computeIfAbsent(aF.time, k -> new Vector<Frame>());
			for(int i = 0;i < aFs.size();i++) {
				Frame aO = aFs.elementAt(i);
				if(aO.key.equals(aF.key)) {
					//Most recent last
					aFs.remove(i);
					if(aO.paramsHash != aF.paramsHash) {
						aO.paramsHash = aF.paramsHash;
						indexVersion++;
					}
					aFs.add(aO);
					return aO;
				}
			}
			aFs.add(aF);
			indexVersion++;
			while(aFs.size() > PER_TIME) {
				aFs.remove(0);
			}
			return aF;
		}
	}

	static class Neighbor {
		Frame frame;
		double weight;
	}

	double frameMS() {
		return 1000.0/(proc.tde.config.outFps > 0 ? proc.tde.config.outFps : 30);
	}

	/**
	 * The images around aF in the time (SMOOTH_FRAMES frames of the montage on each side) made with the
	 * parameters of now at their time, whose patch is in the cache, weighted by their distance in time and the
	 * similarity of their source (a cut, a fast motion: not used). aParams: hashes of the parameters by time
	 * (shared by the calls of a pass), or null
	 */
	Vector<Neighbor> neighbors(Frame aF,Map<Long,Integer> aParams) {
		Vector<Neighbor> aNs = new Vector<Neighbor>();
		double aFrameMS = frameMS();
		long aR = (long)Math.ceil(SMOOTH_FRAMES*aFrameMS);
		Vector<Frame> aCands = new Vector<Frame>();
		synchronized(index) {
			TreeMap<Long,Vector<Frame>> aByTime = index.get(aF.id);
			if(aByTime == null) {
				return aNs;
			}
			for(Vector<Frame> aFs : aByTime.subMap(aF.time-aR, true, aF.time+aR, true).values()) {
				aCands.addAll(aFs);
			}
		}
		for(Frame aO : aCands) {
			if(aO.key.equals(aF.key) || !cached.contains(aO.key)) {
				continue;
			}
			Integer aPH = aParams == null ? null : aParams.get(aO.time);
			if(aPH == null) {
				aPH = params.apply(aO.time).hashCode();
				if(aParams != null) {
					aParams.put(aO.time, aPH);
				}
			}
			if(aPH != aO.paramsHash) {
				//Made before a change of the parameters
				continue;
			}
			double aD = 0;
			for(int t = 0;t < aF.thumb.length;t++) {
				aD += Math.abs(aF.thumb[t]-aO.thumb[t]);
			}
			aD /= aF.thumb.length;
			double aSim = Math.max(0, Math.min(1, (DIFFERENT-aD)/(DIFFERENT-SIMILAR)));
			double aDF = (aO.time-aF.time)/aFrameMS;
			double aW = aSim*Math.exp(-aDF*aDF/(2*SMOOTH_SIGMA*SMOOTH_SIGMA));
			if(aW < 0.01) {
				continue;
			}
			Neighbor aN = new Neighbor();
			aN.frame = aO;
			aN.weight = Math.round(aW*100)/100.0;
			aNs.add(aN);
		}
		//Same order whatever the order of the index: same key of the result
		aNs.sort((a, b) -> a.frame.key.compareTo(b.frame.key));
		return aNs;
	}

	static String smoothKey(Frame aF,Vector<Neighbor> aNs) {
		long aH = 0x5A5A5A5AL;
		for(Neighbor aN : aNs) {
			aH = mix(aH, aN.frame.key.hashCode());
			aH = mix(aH, (long)(aN.weight*100));
		}
		return aF.key+"_s"+Long.toHexString(aH);
	}

	/**
	 * aRaw averaged with the patches of its neighbors in the time, on the pixels they share (null: no
	 * neighbor, or not in the cache and not to compute)
	 */
	Patch smoothed(Frame aF,Patch aRaw,boolean aCompute,Map<Long,Integer> aParams) throws Exception {
		Vector<Neighbor> aNs = neighbors(aF, aParams);
		if(aNs.isEmpty()) {
			aF.smoothKey = aF.key;
			return null;
		}
		String aSK = smoothKey(aF, aNs);
		if(cached.contains(aSK)) {
			float[] aV = load(aSK, aF.w, aF.h);
			if(aV != null) {
				aF.smoothKey = aSK;
				return new Patch(lastIdx.get(), aV);
			}
		}
		if(!aCompute) {
			return null;
		}
		if(aRaw == null) {
			float[] aV = cached.contains(aF.key) ? load(aF.key, aF.w, aF.h) : null;
			if(aV == null) {
				return null;
			}
			aRaw = new Patch(lastIdx.get(), aV);
		}
		int aN = aRaw.idx.length;
		double[] aAcc = new double[aN*3];
		double[] aWs = new double[aN];
		for(int i = 0;i < aN;i++) {
			aAcc[i*3] = aRaw.vals[i*3];
			aAcc[i*3+1] = aRaw.vals[i*3+1];
			aAcc[i*3+2] = aRaw.vals[i*3+2];
			aWs[i] = 1;
		}
		for(Neighbor aNb : aNs) {
			float[] aV = load(aNb.frame.key, aF.w, aF.h);
			if(aV == null) {
				continue;
			}
			int[] aIdx = lastIdx.get();
			//Both index lists ascending: the pixels holes in both
			int i = 0;
			int j = 0;
			while(i < aN && j < aIdx.length) {
				if(aRaw.idx[i] < aIdx[j]) {
					i++;
				}
				else if(aRaw.idx[i] > aIdx[j]) {
					j++;
				}
				else {
					aAcc[i*3] += aNb.weight*aV[j*3];
					aAcc[i*3+1] += aNb.weight*aV[j*3+1];
					aAcc[i*3+2] += aNb.weight*aV[j*3+2];
					aWs[i] += aNb.weight;
					i++;
					j++;
				}
			}
		}
		float[] aVals = new float[aN*3];
		for(int i = 0;i < aN;i++) {
			aVals[i*3] = (float)(aAcc[i*3]/aWs[i]);
			aVals[i*3+1] = (float)(aAcc[i*3+1]/aWs[i]);
			aVals[i*3+2] = (float)(aAcc[i*3+2]/aWs[i]);
		}
		store(aSK, aF.w, aF.h, aRaw.idx, aVals);
		aF.smoothKey = aSK;
		return new Patch(aRaw.idx, aVals);
	}

	/**
	 * Images of the index to smooth for the Precomputer (modes precomputed, made with the parameters of now):
	 * {frame, already smoothed}
	 */
	void smoothTasks(Vector<Frame> aTodo,int[] aCounts,boolean aCheck) {
		Vector<Frame> aAll = new Vector<Frame>();
		synchronized(index) {
			for(Map.Entry<String,TreeMap<Long,Vector<Frame>>> aE : index.entrySet()) {
				int aMode = aE.getKey().charAt(0)-'0';
				if(!precomputes(aMode)) {
					continue;
				}
				for(Vector<Frame> aFs : aE.getValue().values()) {
					aAll.addAll(aFs);
				}
			}
		}
		Map<Long,Integer> aParams = new HashMap<Long,Integer>();
		for(Frame aF : aAll) {
			Integer aPH = aParams.get(aF.time);
			if(aPH == null) {
				aPH = params.apply(aF.time).hashCode();
				aParams.put(aF.time, aPH);
			}
			if(aPH != aF.paramsHash || !cached.contains(aF.key)) {
				continue;
			}
			aCounts[1]++;
			String aDone = aF.smoothKey;
			if(aCheck) {
				//The neighbors may have changed since an image was added
				long aVersion = indexVersion;
				String aSK = aF.smoothTarget;
				if(aSK == null || aF.targetVersion != aVersion) {
					Vector<Neighbor> aNs = neighbors(aF, aParams);
					aSK = aNs.isEmpty() ? aF.key : smoothKey(aF, aNs);
					aF.smoothTarget = aSK;
					aF.targetVersion = aVersion;
				}
				if(aSK.equals(aF.key) || cached.contains(aSK)) {
					aF.smoothKey = aSK;
					aCounts[0]++;
				}
				else {
					aTodo.add(aF);
				}
			}
			else if(aDone != null && (aDone.equals(aF.key) || cached.contains(aDone))) {
				aCounts[0]++;
			}
		}
	}

	boolean runModel(float[] aRGB,int aW,int aH,boolean[] aHoles,boolean aFlip) {
		if(!hasHoles(aHoles)) {
			//Nothing to fill: the model is not loaded
			return true;
		}
		long aStart = System.currentTimeMillis();
		try {
			Inpainter.shared(proc.tde.tdeDir).fill(aRGB, aW, aH, aHoles, aFlip);
			return true;
		}
		catch(Throwable t) {
			failedMS = System.currentTimeMillis();
			System.err.println("Inpainting failed: "+t);
			return false;
		}
		finally {
			modelMS += Math.max(1, System.currentTimeMillis()-aStart);
		}
	}

	/**
	 * At least one hole: the inpainting is only run (model loaded, cache, smoothing) when the warp left holes
	 */
	public static boolean hasHoles(boolean[] aHoles) {
		if(aHoles == null) {
			return false;
		}
		for(boolean aB : aHoles) {
			if(aB) {
				return true;
			}
		}
		return false;
	}

	static int[] indexes(boolean[] aHoles) {
		int aN = 0;
		for(boolean aB : aHoles) {
			if(aB) {
				aN++;
			}
		}
		int[] aIdx = new int[aN];
		int i = 0;
		for(int p = 0;p < aHoles.length;p++) {
			if(aHoles[p]) {
				aIdx[i++] = p;
			}
		}
		return aIdx;
	}

	//Indexes read by the last load() of this thread
	static final ThreadLocal<int[]> lastIdx = new ThreadLocal<int[]>();

	static void store(String aKey,int aW,int aH,int[] aIdx,float[] aPatch) {
		try {
			File aF = cacheFile(aKey);
			File aTmp = new File(aF.getPath()+".tmp"+Thread.currentThread().threadId());
			try(DataOutputStream aOut = new DataOutputStream(new BufferedOutputStream(new GZIPOutputStream(new FileOutputStream(aTmp), 1 << 16)))) {
				aOut.writeInt(aW);
				aOut.writeInt(aH);
				aOut.writeInt(aIdx.length);
				for(int i = 0;i < aIdx.length;i++) {
					aOut.writeInt(aIdx[i]);
				}
				for(float aV : aPatch) {
					aOut.writeFloat(aV);
				}
			}
			if(!aTmp.renameTo(aF)) {
				aTmp.delete();
				return;
			}
			cached.add(aKey);
		}
		catch(Throwable t) {
			t.printStackTrace(System.err);
		}
	}

	/**
	 * The cached pixels (indexes in lastIdx), null if not there
	 */
	static float[] load(String aKey,int aW,int aH) {
		try(DataInputStream aIn = new DataInputStream(new BufferedInputStream(new GZIPInputStream(new FileInputStream(cacheFile(aKey)), 1 << 16)))) {
			if(aIn.readInt() != aW || aIn.readInt() != aH) {
				return null;
			}
			int aN = aIn.readInt();
			int[] aIdx = new int[aN];
			for(int i = 0;i < aN;i++) {
				aIdx[i] = aIn.readInt();
				if(aIdx[i] < 0 || aIdx[i] >= aW*aH) {
					return null;
				}
			}
			float[] aPatch = new float[aN*3];
			for(int i = 0;i < aPatch.length;i++) {
				aPatch[i] = aIn.readFloat();
			}
			lastIdx.set(aIdx);
			return aPatch;
		}
		catch(Throwable t) {
			cached.remove(aKey);
			return null;
		}
	}

	/**
	 * Background precomputation of the images of the clips whose depth processor fills its holes with the model
	 * and is too slow to do it while playing (see Inpainting). One image at a time, the nearest to the time
	 * cursor first, by Clip.reBuild() in the mode of the monitor, under the lock of the monitor (MonitorGUI.processI()
	 * rebuilds everything at each image, so the clip state changed here does not matter). Paused while playing
	 * or rendering.
	 */
	public static class Precomputer {
		static volatile Thread thread = null;
		static TDenlive tde = null;
		//Progress: images done / to do (total 0: nothing to do)
		static volatile int done = 0;
		static volatile int total = 0;
		//Rendering in progress (RendererGUI)
		public static volatile boolean rendering = false;
		//A project is being opened: nothing until the user chose (GUI.InpaintGUI.afterOpen())
		public static volatile boolean held = false;
		//Held while an image is precomputed (never with a syncExec inside: the GUI thread can wait for it)
		static final Object BUSY = new Object();
		static int suspended = 0;

		/**
		 * The montage is rebuilt elsewhere (snapshot...): no precomputation until resume(), the image in
		 * progress ends first
		 */
		public static void suspend() {
			synchronized(Precomputer.class) {
				suspended++;
			}
			synchronized(BUSY) {
			}
		}

		public static void resume() {
			synchronized(Precomputer.class) {
				suspended = Math.max(0, suspended-1);
			}
		}
		static long lastRulerMS = 0;

		static synchronized void start(TDenlive aTDe) {
			tde = aTDe;
			if(thread != null) {
				return;
			}
			thread = new Thread(Precomputer::loop, "Inpainting precomputation");
			thread.setDaemon(true);
			thread.setPriority(Thread.MIN_PRIORITY);
			thread.start();
		}

		/**
		 * {done, total} while images are to be precomputed, else null
		 */
		public static int[] status() {
			int aT = total;
			int aD = done;
			return aT > 0 && aD < aT ? new int[] {aD, aT} : null;
		}

		static boolean paused() {
			synchronized(Precomputer.class) {
				if(suspended > 0) {
					return true;
				}
			}
			if(rendering || held || tde.gui == null || tde.gui.monitorGUI == null) {
				return true;
			}
			return tde.gui.playerControlGUI != null && tde.gui.playerControlGUI.isPlaying();
		}

		static class Task {
			Clip clip;
			Inpainting inp;
			int mode;
			long time;
			String sig;
			//Smoothing of an image made (no rebuild)
			Frame frame;
		}

		/**
		 * What makes the image of aClip at aTime: media, media frame, mode and resolution, processors before
		 * the depth one, parameters at the time
		 */
		static String signature(Clip aClip,String aBefore,Inpainting aInp,long aTime,int aMode) {
			String aMediaTime = aClip.getMedia() instanceof StereoMovie ? ""+aClip.mediaTimeMS(aTime) : "";
			return aClip.getMedia().UID+"|"+aMediaTime+"|"+aMode+"|"+tde.config.processingResValues[aMode]
					+"|"+tde.config.processingHdr[aMode]+"|"+aBefore.hashCode()+"|"+aInp.params.apply(aTime);
		}

		static void loop() {
			while(true) {
				try {
					Task aTask = null;
					if(!paused()) {
						aTask = nextTask();
					}
					else if(total > 0) {
						if(held) {
							//Project being opened: the progress of the previous one is gone
							total = 0;
							done = 0;
							redrawRuler(true);
						}
						else {
							//Progress window: paused while playing
							redrawRuler(false);
						}
					}
					if(aTask == null) {
						Thread.sleep(500);
						continue;
					}
					long aStart = System.currentTimeMillis();
					if(aTask.frame != null) {
						//Temporal smoothing: from the cache only
						Patch aP = null;
						try {
							aP = aTask.inp.smoothed(aTask.frame, null, true, null);
						}
						catch(Throwable t) {
							t.printStackTrace(System.err);
						}
						if(aP == null) {
							//Not possible: the image stays as made (not tried again)
							aTask.frame.smoothTarget = aTask.frame.key;
							aTask.frame.targetVersion = aTask.inp.indexVersion;
						}
						done = Math.min(done+1, total);
						long aCursor = tde.timeLineStack.getRequestedTimePosMS();
						if(Math.abs(aCursor-aTask.frame.time) <= aTask.inp.frameMS()
								&& aTask.frame.id.startsWith(""+tde.gui.monitorGUI.processingMode)) {
							tde.gui.display.asyncExec(() -> tde.gui.monitorGUI.redraw());
						}
						redrawRuler(false);
						continue;
					}
					synchronized(tde.gui.monitorGUI) {
						synchronized(BUSY) {
							if(paused()) {
								continue;
							}
							aTask.clip.reBuild(aTask.mode, aTask.time);
						}
					}
					if(aTask.inp.failedMS >= aStart) {
						//Model failure: tried again later
						Thread.sleep(10000);
						continue;
					}
					aTask.inp.doneSigs.add(aTask.sig);
					done = Math.min(done+1, total);
					long aCursor = tde.timeLineStack.getRequestedTimePosMS();
					if(aTask.mode == tde.gui.monitorGUI.processingMode && aCursor >= aTask.clip.getStartTimeMS() && aCursor < aTask.clip.getStartTimeMS()+aTask.clip.getDurationMS()
							&& Math.abs(aCursor-aTask.time) <= 500.0/(tde.config.outFps > 0 ? tde.config.outFps : 30)) {
						//The image shown is ready
						tde.gui.display.asyncExec(() -> tde.gui.monitorGUI.redraw());
					}
					redrawRuler(false);
				}
				catch(Throwable t) {
					t.printStackTrace(System.err);
					try {
						Thread.sleep(2000);
					}
					catch(Throwable t2) {
					}
				}
			}
		}

		static void redrawRuler(boolean aForce) {
			long aNow = System.currentTimeMillis();
			if(!aForce && aNow-lastRulerMS < 500) {
				return;
			}
			lastRulerMS = aNow;
			if(tde.gui != null && tde.gui.display != null && !tde.gui.display.isDisposed()) {
				tde.gui.display.asyncExec(() -> {
					if(tde.gui.monitorRulerGUI != null) {
						tde.gui.monitorRulerGUI.redraw();
					}
					//Progress window
					com.cubaix.TDenlive.GUI.InpaintProgressGUI.update(tde);
				});
			}
		}

		/**
		 * The hole filling settings of the depth processors of the clips of the video lines
		 */
		public static Vector<Inpainting> all(TDenlive aTDe) {
			Vector<Inpainting> aAll = new Vector<Inpainting>();
			for(VideoTimeLine aTL : new Vector<VideoTimeLine>(aTDe.timeLineStack.videoTimeLineStack)) {
				for(Clip aClip : new Vector<Clip>(aTL.clipList)) {
					for(Processor aP : new Vector<Processor>(aClip.getProcessors())) {
						if(aP instanceof Depthmap) {
							aAll.add(((Depthmap)aP).inpainting);
						}
						else if(aP instanceof DepthmapStereo) {
							aAll.add(((DepthmapStereo)aP).inpainting);
						}
					}
				}
			}
			return aAll;
		}

		static Task task(Clip aClip,Inpainting aInp,int aMode,long aTime,String aSig) {
			Task aT = new Task();
			aT.clip = aClip;
			aT.inp = aInp;
			aT.mode = aMode;
			aT.time = aTime;
			aT.sig = aSig;
			return aT;
		}

		/**
		 * The image to precompute (null if none), with the progress: the mode of the monitor first, then the
		 * other preview, then the rendering; the nearest to the time cursor first
		 */
		static Task nextTask() throws Exception {
			int aMonitorMode = tde.gui.monitorGUI.processingMode;
			int[] aModes = {TDConfig.PROCESSING_MODE_WORK, TDConfig.PROCESSING_MODE_FULLSCREEN, TDConfig.PROCESSING_MODE_RENDER};
			double aFps = tde.config.outFps > 0 ? tde.config.outFps : 30;
			long aCursor = tde.timeLineStack.getRequestedTimePosMS();
			Task aBest = null;
			long aBestDist = Long.MAX_VALUE;
			int aTotal = 0;
			int aDone = 0;
			Vector<VideoTimeLine> aLines = new Vector<VideoTimeLine>(tde.timeLineStack.videoTimeLineStack);
			Vector<Inpainting> aInps = new Vector<Inpainting>();
			for(VideoTimeLine aTL : aLines) {
				if(!aTL.isActive) {
					continue;
				}
				for(Clip aClip : new Vector<Clip>(aTL.clipList)) {
					StringBuffer aBefore = new StringBuffer();
					for(Processor aP : new Vector<Processor>(aClip.getProcessors())) {
						Inpainting aInp = aP instanceof Depthmap ? ((Depthmap)aP).inpainting
								: (aP instanceof DepthmapStereo ? ((DepthmapStereo)aP).inpainting : null);
						if(aInp == null) {
							aBefore.append(aP.isActive);
							aP.saveProject(aBefore);
							continue;
						}
						if(!aP.isActive || !Inpainter.isReady(tde.tdeDir) || !Inpainter.capable()) {
							break;
						}
						if(!aInps.contains(aInp)) {
							aInps.add(aInp);
						}
						if(aInp.doneSigs.size() > 200000) {
							aInp.doneSigs.clear();
						}
						String aBeforeS = aBefore.toString();
						long aStartT = aClip.getStartTimeMS();
						long aEndT = aStartT+aClip.getDurationMS();
						boolean aVideo = aClip.getMedia() instanceof StereoMovie;
						for(int aMode : aModes) {
							if(!aInp.precomputes(aMode)) {
								continue;
							}
							//Order of the levels
							long aTier = (aMode == aMonitorMode ? 0 : (aMode == TDConfig.PROCESSING_MODE_RENDER ? 2 : 1))*(1L << 50);
							Set<String> aSeen = new HashSet<String>();
							if(aMode == TDConfig.PROCESSING_MODE_RENDER) {
								//The times of the rendering
								for(long k = (long)Math.floor(aStartT*aFps/1000.0);;k++) {
									long aT = (long)(1000*k/aFps);
									if(aT >= aEndT) {
										break;
									}
									if(aT < aStartT) {
										continue;
									}
									String aSig = signature(aClip, aBeforeS, aInp, aT, aMode);
									if(!aSeen.add(aSig)) {
										continue;
									}
									aTotal++;
									if(aInp.doneSigs.contains(aSig)) {
										aDone++;
										continue;
									}
									long aDist = aTier+Math.abs(aT-aCursor)*2;
									if(aDist < aBestDist) {
										aBestDist = aDist;
										aBest = task(aClip, aInp, aMode, aT, aSig);
									}
								}
								continue;
							}
							//A video may change of image inside a frame of the montage (work copy at 15 fps...):
							//the start and the end of each frame are precomputed too (same parameters, snap())
							double aHalf = 500.0/aFps;
							double[] aOffsets = aVideo ? new double[] {0, -aHalf+1, aHalf-1} : new double[] {0};
							long aK0 = (long)Math.ceil(aP.keyTime(aStartT)*aFps/1000.0)-1;
							for(long k = aK0;;k++) {
								double aKT = k*1000.0/aFps;
								if(aP.keyTimePos(Math.round(aKT-aHalf)) >= aEndT) {
									break;
								}
								long aG = aP.keyTimePos(Math.round(aKT));
								for(double aOff : aOffsets) {
									long aT = aP.keyTimePos(Math.round(aKT+aOff));
									if(aT < aStartT || aT >= aEndT || aInp.snap(aT, aMode) != aG) {
										continue;
									}
									String aSig = signature(aClip, aBeforeS, aInp, aT, aMode);
									if(!aSeen.add(aSig)) {
										continue;
									}
									aTotal++;
									if(aInp.doneSigs.contains(aSig)) {
										aDone++;
										continue;
									}
									//The time cursor first, then its frame, then the nearest
									long aDist = aTier+Math.abs(aT-aCursor)*2+(aOff == 0 ? 0 : 1);
									if(aDist < aBestDist) {
										aBestDist = aDist;
										aBest = task(aClip, aInp, aMode, aT, aSig);
									}
								}
							}
						}
						break;
					}
				}
			}
			//Temporal smoothing of the images made, once they are all there (their neighbors made)
			int[] aSC = {0, 0};
			for(Inpainting aInp : aInps) {
				Vector<Frame> aTodo = new Vector<Frame>();
				aInp.smoothTasks(aTodo, aSC, aBest == null || aBest.frame != null);
				for(Frame aF : aTodo) {
					long aDist = Math.abs(aF.time-aCursor);
					if(aBest == null || (aBest.frame != null && aDist < aBestDist)) {
						aBestDist = aDist;
						aBest = new Task();
						aBest.inp = aInp;
						aBest.frame = aF;
					}
				}
			}
			aTotal += aSC[1];
			aDone += aSC[0];
			boolean aChanged = aTotal != total || aDone != done;
			total = aTotal;
			done = aDone;
			if(aChanged) {
				redrawRuler(aBest == null);
			}
			return aBest;
		}
	}
}
