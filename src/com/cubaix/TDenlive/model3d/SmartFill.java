package com.cubaix.TDenlive.model3d;

import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;

import com.cubaix.TDenlive.depth.Inpainter;

/**
 * Smart filling of the views of a 3D scene of Gaussians (Flash3D), in two stages:
 * 1. the mesh of the visible Gaussians (GaussianScene.View.meshFill: two triangles per square of pixels of the photo,
 * colors of the vertices interpolated) fills what the photo did not see by stretching between the foreground and the
 * background, in place of the blurred hidden layer of Flash3D; thresholding (View.cosCut): the triangles seen edge on
 * from the camera of the photo (|cos| of their normal with the direction of the depth below the threshold,
 * DEFAULT_COS: the slopes the depth estimate puts at the edges of the objects, long smears seen from aside) are holes;
 * 2. what is left uncovered (those holes, beyond the borders of the photo) is filled by the inpainting model of the
 * depth processors (depth.Inpainter, shared, kept loaded): a pixel more than HOLE uncovered is a hole; the others are
 * given to it with their own color, blurred (blurContext(): the ripples of the Gaussians are not continued), and
 * completed by its colors where they are transparent. The left eye is given mirrored (model trained with the
 * foreground on the left of the holes). The model runs on the image reduced to at most MODEL_MAX_H lines. Far from the
 * known pixels (MODEL_REACH), where it makes a blurred grid, a smooth diffusion of the colors (limitReach()).
 * Filling model (Model, chosen per scene media / processor / viewer, <fillModel>): LIGHT (light_inpaint_v1 of iw3,
 * the default), LAMA (LaMa, GPU only, no MODEL_REACH limit: it was trained on large masks) or SDXL (Stable Diffusion XL
 * Inpainting, ai.FillGenerator, GPU only: invents plausible content in the large holes). SDXL runs on the view rendered
 * at about FillGenerator.PIXELS whatever the size asked (sdxlFill(): the same result for every processing mode),
 * without the blur of the context, with the same seed for both eyes; its image is kept in memory and on the disk
 * (Cache/SmartFill of the 3Denlive folder, by scene file, cameras, eye, options; at most DISK_MAX files), and the holes of
 * the view at its size take its colors enlarged bilinearly. A model not downloaded or not usable here: LIGHT instead;
 * a failure of SDXL: LIGHT for this view (SDXL tried again after RETRY_MS).
 * Without the model (platform of ONNX Runtime missing, its jar downloaded in the background, an error, or while
 * Flash3D computes: held), the holes stay black. The images are cached by scene, cameras, eye, size and threshold.
 */
public class SmartFill {
	/** Filling models of the holes */
	public enum Model {
		LIGHT("Light", Inpainter.Engine.LIGHT),
		LAMA("LaMa", Inpainter.Engine.LAMA),
		SDXL("SDXL", null);

		public final String id;
		public final Inpainter.Engine engine;

		Model(String aId,Inpainter.Engine aEngine) {
			id = aId;
			engine = aEngine;
		}

		/** Language key of its name (+ "Note": its description) */
		public String key() {
			return "AI.SceneFill"+id;
		}

		/** This machine can run it */
		public boolean capable() {
			return engine != null ? Inpainter.capable(engine) : com.cubaix.TDenlive.ai.FillGenerator.capable();
		}

		/** Installed (model, runtime or Python environment) */
		public boolean ready(String aTdeDir) {
			return engine != null ? Inpainter.isReady(aTdeDir, engine) : com.cubaix.TDenlive.ai.FillGenerator.isReady(aTdeDir);
		}

		/** Bytes still to download (the Python environment not counted) */
		public long bytesToDownload(String aTdeDir) {
			if(engine == null) {
				return com.cubaix.TDenlive.ai.FillGenerator.bytesToDownload(aTdeDir);
			}
			long aB = 0;
			if(!com.cubaix.TDenlive.depth.DepthEstimator.isOrtDownloaded(aTdeDir)) {
				aB += com.cubaix.TDenlive.depth.DepthEstimator.ortSize();
			}
			if(engine.url != null && !Inpainter.isDownloaded(aTdeDir, engine)) {
				aB += engine.size;
			}
			return aB;
		}
	}
	public static final Model[] MODELS = {Model.LIGHT, Model.LAMA, Model.SDXL};
	public static final Model DEFAULT_MODEL = Model.LIGHT;
	//Models of the processors and of the scene medias: all of them (SDXL too: the filling of the previous image is
	//carried over, temporal(), SDXL then only for the new large areas)
	public static final Model[] PROCESSOR_MODELS = MODELS;

	/** Model of a processor / scene media */
	public static Model forProcessor(String aId) {
		return modelById(aId);
	}

	/** Model of an id (saved), DEFAULT_MODEL if unknown */
	public static Model modelById(String aId) {
		for(Model aM : MODELS) {
			if(aM.id.equalsIgnoreCase(aId)) {
				return aM;
			}
		}
		return DEFAULT_MODEL;
	}

	static final float HOLE = 0.5f;
	//Thresholding: the triangles whose |cos| of the normal with the direction of the depth is below this are holes
	public static final double DEFAULT_COS = 0.02;
	//Sliders of the threshold (logarithmic): 0 at their start (no hole), then COS_MIN..1, COS_DECADE steps a decade
	public static final double COS_MIN = 1e-5;
	public static final int COS_DECADE = 100;
	public static final int COS_STEPS = 5*COS_DECADE;

	/** Position of a threshold on its slider (0..COS_STEPS) */
	public static int cosToSlider(double aCos) {
		if(aCos <= 0) {
			return 0;
		}
		return Math.max(1, Math.min(COS_STEPS, (int)Math.round(COS_DECADE*Math.log10(aCos/COS_MIN))));
	}

	/** Threshold of a position of its slider */
	public static double sliderToCos(int aPos) {
		return aPos <= 0 ? 0 : Math.min(1, COS_MIN*Math.pow(10, aPos/(double)COS_DECADE));
	}

	/** Threshold shown: 2 significant digits below 0.1 */
	public static String formatCos(double aCos) {
		if(aCos <= 0) {
			return "0";
		}
		int aDec = Math.max(2, (int)Math.ceil(-Math.log10(aCos))+1);
		return String.format("%."+aDec+"f", aCos);
	}
	static final int MODEL_MAX_H = 720;
	static final int CONTEXT_BLUR = 3;
	//The colors of the model are kept up to this distance from the known pixels (at its resolution); farther, where it
	//makes a blurred grid (it was trained on the small holes behind the objects), a smooth diffusion of the colors
	//(pushPull()), blended over the second half of the distance
	static final int MODEL_REACH = 24;
	static final int CACHE_MAX = 8;
	static final java.util.LinkedHashMap<String,java.lang.ref.SoftReference<BufferedImage>> CACHE = new java.util.LinkedHashMap<String,java.lang.ref.SoftReference<BufferedImage>>(16, 0.75f, true);
	static volatile boolean downloading = false;
	//SDXL: steps of the denoising, last failure (not tried again before RETRY_MS), cache on the disk
	static final int SDXL_STEPS = 20;
	static final long RETRY_MS = 60000;
	static volatile long sdxlFailedMS = 0;
	static final int DISK_MAX = 400;
	static final Object SDXL_LOCK = new Object();
	//Model of the last filling started (name shown with the hourglass)
	static volatile Model computingModel = DEFAULT_MODEL;
	//Views being filled now (any model), since (shown by the monitor with an hourglass: MonitorGUI.drawComputing()), and
	//the listener told at each start and end (UI: asyncExec)
	static final java.util.concurrent.atomic.AtomicInteger computing = new java.util.concurrent.atomic.AtomicInteger();
	static volatile long computingSinceMS = 0;
	public static volatile Runnable onComputing = null;

	/** Model of the filling in progress */
	public static Model computingModel() {
		return computingModel;
	}

	/** A model is filling a view now */
	public static boolean isComputing() {
		return computing.get() > 0;
	}

	/** Start of the filling in progress (ms) */
	public static long computingSinceMS() {
		return computingSinceMS;
	}

	static void computingChanged(boolean aStart) {
		if(aStart) {
			if(computing.getAndIncrement() == 0) {
				computingSinceMS = System.currentTimeMillis();
			}
		}
		else {
			computing.decrementAndGet();
		}
		Runnable aR = onComputing;
		if(aR != null) {
			try {
				aR.run();
			}
			catch(Throwable t) {
				t.printStackTrace(System.err);
			}
		}
	}
	static final java.util.LinkedHashMap<String,java.lang.ref.SoftReference<float[]>> SDXL_CACHE = new java.util.LinkedHashMap<String,java.lang.ref.SoftReference<float[]>>(16, 0.75f, true);
	//Another model computing (Flash3D): the inpainter would unload it, the mesh alone then
	public static volatile boolean held = false;

	/**
	 * The model can run now: ONNX Runtime and the model there (else downloaded once in the background)
	 */
	public static boolean available(String aTdeDir) {
		if(held || aTdeDir == null || !Inpainter.capable()) {
			return false;
		}
		if(Inpainter.isReady(aTdeDir)) {
			return true;
		}
		if(!downloading) {
			downloading = true;
			Thread aTh = new Thread(() -> {
				try {
					System.out.println("Smart filling: download of ONNX Runtime");
					Inpainter.download(aTdeDir, null, null);
				}
				catch(Throwable t) {
					t.printStackTrace(System.err);
				}
			}, "Smart filling download");
			aTh.setDaemon(true);
			aTh.start();
		}
		return false;
	}

	/**
	 * View of one eye (-1 left, 0 center, 1 right) of aScene with aCam: the mesh of the visible Gaussians when aInterp,
	 * else the Gaussians; the depths in gray when aDepth; the triangles seen edge on below aCos as holes when aCut or
	 * aFill (mesh only); those holes and the pixels uncovered filled by the inpainting model when aFill
	 */
	public static BufferedImage render(GaussianScene aScene,StereoCam aCam,int aEye,int aW,int aH,boolean aDepth,boolean aInterp,boolean aCut,boolean aFill,double aCos,String aFillModel,String aTdeDir) {
		return render(aScene, aCam, aEye, aW, aH, aDepth, aInterp, aCut, aFill, aCos, aFillModel, aTdeDir, true);
	}

	/**
	 * render(), SDXL computed only when aSdxlCompute (its cost: the final rendering, "Fill this view"); otherwise its
	 * images already made (memory or disk cache, sdxlCached()), else the light model
	 */
	public static BufferedImage render(GaussianScene aScene,StereoCam aCam,int aEye,int aW,int aH,boolean aDepth,boolean aInterp,boolean aCut,boolean aFill,double aCos,String aFillModel,String aTdeDir,boolean aSdxlCompute) {
		return render(aScene, aCam, aEye, aW, aH, aDepth, aInterp, aCut, aFill, aCos, aFillModel, aTdeDir, aSdxlCompute, true, true);
	}

	/**
	 * render(), the filling of the left eye carried over to the right one when aPairCarry (pair(), else each eye filled
	 * alone), the filling of the previous images carried over when aPrevCarry (temporal())
	 */
	public static BufferedImage render(GaussianScene aScene,StereoCam aCam,int aEye,int aW,int aH,boolean aDepth,boolean aInterp,boolean aCut,boolean aFill,double aCos,String aFillModel,String aTdeDir,boolean aSdxlCompute,boolean aPairCarry,boolean aPrevCarry) {
		return render(aScene, aCam, aEye, aW, aH, aDepth, aInterp, aCut, aFill, aCos, aFillModel, aTdeDir, aSdxlCompute, aPairCarry, aPrevCarry, false);
	}

	/**
	 * render(), aCarry: the image asked after carryNext() (next image / step of the player): filled again from the
	 * image shown at that time (its filling carried over first, temporal()), not taken from the memory caches (once per
	 * view, carryDone)
	 */
	public static BufferedImage render(GaussianScene aScene,StereoCam aCam,int aEye,int aW,int aH,boolean aDepth,boolean aInterp,boolean aCut,boolean aFill,double aCos,String aFillModel,String aTdeDir,boolean aSdxlCompute,boolean aPairCarry,boolean aPrevCarry,boolean aCarry) {
		boolean aMesh = aInterp && aScene.hasGrid();
		double aCosCut = aMesh && (aCut || aFill) ? Math.max(0, Math.min(1, aCos)) : 0;
		Model aModel = modelById(aFillModel);
		if(aModel != Model.LIGHT && (held || aTdeDir == null || !aModel.capable() || !aModel.ready(aTdeDir)
				|| (aModel == Model.SDXL && System.currentTimeMillis()-sdxlFailedMS < RETRY_MS))) {
			aModel = Model.LIGHT;
		}
		if(aModel == Model.SDXL && aFill && !aSdxlCompute && !sdxlCached(aScene, aCam, aEye, aW, aH, aMesh, aDepth, aCosCut, aTdeDir, aPairCarry, aPrevCarry)) {
			aModel = Model.LIGHT;
		}
		if(!aFill || (aModel == Model.LIGHT && !available(aTdeDir))) {
			GaussianScene.View aV = aCam.view(aScene, aEye, aW, aH);
			aV.meshFill = aMesh;
			aV.depthView = aDepth;
			aV.cosCut = aCosCut;
			return aScene.render(aV, aW, aH);
		}
		double aThreshold = aCosCut;
		String aKey = System.identityHashCode(aScene)+"|"+aScene.n+"|"+aCam.save()+"|"+aEye+"|"+aW+"x"+aH+"|"+aMesh+"|"+aDepth+"|"+aThreshold+"|"+aModel.id+"|"+aPairCarry+"|"+aPrevCarry;
		//Carried over from the image shown before (once per view): filled again, not taken from the caches
		boolean aForced = aCarry && aPrevCarry && aMesh && takeCarry(aKey);
		if(!aForced) {
			synchronized(CACHE) {
				java.lang.ref.SoftReference<BufferedImage> aR = CACHE.get(aKey);
				BufferedImage aBI = aR == null ? null : aR.get();
				if(aBI != null) {
					markShown(aKey);
					return aBI;
				}
			}
		}
		BufferedImage aBI = new BufferedImage(aW, aH, BufferedImage.TYPE_INT_ARGB);
		int[] aPix = ((DataBufferInt)aBI.getRaster().getDataBuffer()).getData();
		float[] aTrans = new float[aW*aH];
		GaussianScene.View aV = aCam.view(aScene, aEye, aW, aH);
		aV.meshFill = aMesh;
		aV.depthView = aDepth;
		aV.cosCut = aCosCut;
		float[] aZ = aMesh ? new float[aW*aH] : null;
		aV.depthOut = aZ;
		aScene.render(aV, aW, aH, aPix, aTrans);
		if(!hasHoles(aTrans)) {
			//Nothing uncovered in this view: no filling (no model loaded, no SDXL computation, no hourglass); its
			//image is kept for the carry-over of the next images
			if(aPrevCarry && aZ != null && aModel != Model.SDXL) {
				boolean[] aKnown = new boolean[aW*aH];
				for(int p = 0;p < aKnown.length;p++) {
					aKnown[p] = aTrans[p] <= HOLE && aZ[p] > 0;
				}
				keepPrevOf(aKey, remember(stream(aW, aH, aEye, aDepth, aThreshold, aModel), toRGB(aPix), aZ, aTrans, aKnown, aV, aW, aH));
			}
			synchronized(CACHE) {
				CACHE.put(aKey, new java.lang.ref.SoftReference<BufferedImage>(aBI));
				while(CACHE.size() > CACHE_MAX) {
					CACHE.remove(CACHE.keySet().iterator().next());
				}
			}
			markShown(aKey);
			return aBI;
		}
		computingModel = aModel;
		computingChanged(true);
		try {
			float[] aSdxl = null;
			int[] aSdxlSize = null;
			if(aMesh && aEye != 0 && aPairCarry) {
				//Both eyes filled together: the left one, carried over to the right one (pair())
				int[] aPS = aModel == Model.SDXL ? sdxlSize(aW, aH) : new int[] {aW, aH};
				float[][] aPair = null;
				try {
					aPair = pair(aScene, aCam, aPS[0], aPS[1], aDepth, aThreshold, aModel, aTdeDir, aPrevCarry, aForced);
				}
				catch(Throwable t) {
					if(aModel != Model.SDXL) {
						throw t;
					}
					t.printStackTrace(System.err);
					sdxlFailedMS = System.currentTimeMillis();
					aModel = Model.LIGHT;
					computingModel = aModel;
					aPS = new int[] {aW, aH};
					aPair = pair(aScene, aCam, aW, aH, aDepth, aThreshold, aModel, aTdeDir, aPrevCarry, aForced);
				}
				aSdxl = aPair[aEye < 0 ? 0 : 1];
				aSdxlSize = aPS;
				if(aPrevCarry) {
					keepPrevOf(aKey, pairPrev(pairId(aScene, aCam, aPS[0], aPS[1], aDepth, aThreshold, aModel, aPrevCarry), aEye));
				}
			}
			else if(aModel == Model.SDXL) {
				aSdxlSize = sdxlSize(aW, aH);
				try {
					aSdxl = sdxlFill(aScene, aCam, aEye, aSdxlSize[0], aSdxlSize[1], aMesh, aDepth, aThreshold, aTdeDir);
				}
				catch(Throwable t) {
					t.printStackTrace(System.err);
					sdxlFailedMS = System.currentTimeMillis();
				}
				if(aSdxl == null) {
					//Not filled by SDXL: the light model for this view
					if(!available(aTdeDir)) {
						throw new Exception("No filling model");
					}
					aModel = Model.LIGHT;
				}
			}
			boolean aTemp = aPrevCarry && aZ != null && aSdxl == null && aModel != Model.SDXL;
			String aStream = stream(aW, aH, aEye, aDepth, aThreshold, aModel);
			boolean[] aKnown = aTemp ? temporal(aPix, aTrans, aZ, aV, aW, aH, aStream, aForced ? carryPrev(aStream) : null) : null;
			fill(aPix, aTrans, aW, aH, aEye < 0, aModel, aSdxl, aSdxlSize, aTdeDir, aZ);
			if(aTemp) {
				keepPrevOf(aKey, remember(aStream, toRGB(aPix), aZ, aTrans, aKnown, aV, aW, aH));
			}
		}
		catch(Throwable t) {
			//Not filled (the model failed: tried again later)
			t.printStackTrace(System.err);
			GaussianScene.View aV2 = aCam.view(aScene, aEye, aW, aH);
			aV2.meshFill = aMesh;
			aV2.depthView = aDepth;
			aV2.cosCut = aCosCut;
			return aScene.render(aV2, aW, aH);
		}
		finally {
			computingChanged(false);
		}
		synchronized(CACHE) {
			CACHE.put(aKey, new java.lang.ref.SoftReference<BufferedImage>(aBI));
			while(CACHE.size() > CACHE_MAX) {
				CACHE.remove(CACHE.keySet().iterator().next());
			}
		}
		markShown(aKey);
		return aBI;
	}

	/**
	 * The pixels of aPix (rendered over black where uncovered) completed with the colors filled by the model
	 */
	/**
	 * A pixel of the view at least is uncovered (transmittance above HOLE): something to fill
	 */
	static boolean hasHoles(float[] aTrans) {
		for(float t : aTrans) {
			if(t > HOLE) {
				return true;
			}
		}
		return false;
	}

	static void fill(int[] aPix,float[] aTrans,int aW,int aH,boolean aFlip,Model aModel,float[] aSdxl,int[] aSdxlSize,String aTdeDir) throws Exception {
		fill(aPix, aTrans, aW, aH, aFlip, aModel, aSdxl, aSdxlSize, aTdeDir, null);
	}

	/**
	 * Same, with the depths of the view (1/z, 0: none; null: unknown). With them, the filling continues the background,
	 * not the objects in front: the holes are not grown into the nearer pixels (holeInfo(): nearer than the background
	 * of the nearest hole by EDGE), those within FG_MASK of a hole are hidden from the model (given as holes, its colors
	 * not kept there), and the light model gets each hole the right way round (trained with the foreground on the left
	 * of the hole): the image is filled as it is and mirrored when the holes have their foreground on both sides, each
	 * hole pixel taking the result of its side.
	 */
	static void fill(int[] aPix,float[] aTrans,int aW,int aH,boolean aFlip,Model aModel,float[] aSdxl,int[] aSdxlSize,String aTdeDir,float[] aZ) throws Exception {
		int n = aW*aH;
		boolean[] aHoles = new boolean[n];
		float[] aRGB = new float[3*n];
		boolean aAny = false;
		for(int p = 0;p < n;p++) {
			float t = aTrans[p];
			if(t > HOLE) {
				aHoles[p] = true;
				aAny = true;
				continue;
			}
			//Own color, unpremultiplied (the pixel was blended with black)
			float a = Math.max(1e-3f, 1-t);
			int c = aPix[p];
			aRGB[3*p] = Math.min(1f, ((c>>16)&0xFF)/255f/a);
			aRGB[3*p+1] = Math.min(1f, ((c>>8)&0xFF)/255f/a);
			aRGB[3*p+2] = Math.min(1f, (c&0xFF)/255f/a);
		}
		if(!aAny) {
			return;
		}
		//The holes follow the triangles cut (a saw tooth edge, the stretched triangles left along it): grown by
		//aGrow pixels for the model, and its colors faded into the mesh over them
		int aGrow = Math.max(2, Math.round(aH/360f));
		int[] aDist = distance(aHoles, aW, aH);
		boolean[] aCore = aHoles;
		aHoles = new boolean[n];
		float[] aOwn = aRGB.clone();
		//Given to the model as holes (aHoles + the foreground near the holes, hidden from it)
		boolean[] aMask = aHoles;
		byte[] aSide = null;
		int aFgLeft = 0, aFgRight = 0;
		if(aZ != null) {
			int aR = Math.max(FG_MASK, 3*aGrow);
			float[] aBg = new float[n];
			aSide = new byte[n];
			holeInfo(aCore, aZ, aTrans, aW, aH, aR, aBg, aSide);
			aMask = new boolean[n];
			for(int p = 0;p < n;p++) {
				if(aCore[p]) {
					aHoles[p] = aMask[p] = true;
					if(aSide[p] > 0) {
						aFgLeft++;
					}
					else if(aSide[p] < 0) {
						aFgRight++;
					}
					continue;
				}
				if(aBg[p] <= 0) {
					continue;
				}
				boolean aFg = aZ[p] > 0 && aZ[p] > FG_EDGE*aBg[p];
				if(aFg) {
					aMask[p] = aDist[p] <= 3*aR;
				}
				else {
					aHoles[p] = aMask[p] = aDist[p] <= 3*aGrow;
				}
			}
		}
		else {
			for(int p = 0;p < n;p++) {
				aHoles[p] = aDist[p] <= 3*aGrow;
			}
		}
		int f = (aH+MODEL_MAX_H-1)/MODEL_MAX_H;
		if(aSdxl != null) {
			sample(aSdxl, aSdxlSize[0], aSdxlSize[1], aRGB, aHoles, aW, aH);
		}
		else if(f <= 1) {
			boolean aBoth = false;
			if(aSide != null && aModel == Model.LIGHT) {
				//The way of the holes from their depths (aFlip only when none says)
				aBoth = aFgLeft > 0 && aFgRight > 0;
				aFlip = aFgLeft != aFgRight ? aFgRight > aFgLeft : aFlip;
			}
			//First pass: the way of most holes (all the holes given; those of that way, and of no known way, kept)
			float[] aCtx = blurContext(aRGB, aMask, aW, aH, CONTEXT_BLUR);
			Inpainter.shared(aTdeDir, aModel.engine).fill(aCtx, aW, aH, aMask, aFlip);
			if(aModel == Model.LIGHT) {
				limitReach(aCtx, aMask, aW, aH);
			}
			boolean[] aMask2 = aBoth ? aMask.clone() : null;
			for(int p = 0;p < n;p++) {
				if(!aHoles[p]) {
					continue;
				}
				boolean aOther = aBoth && aSide[p] != 0 && (aSide[p] < 0) != aFlip;
				if(!aOther) {
					System.arraycopy(aCtx, 3*p, aRGB, 3*p, 3);
					if(aMask2 != null) {
						aMask2[p] = false;
					}
				}
			}
			if(aBoth) {
				//Second pass, the other way: only its holes (and the foreground hidden), with those filled by the first
				//one as context
				float[] aCtx2 = blurContext(aRGB, aMask2, aW, aH, CONTEXT_BLUR);
				Inpainter.shared(aTdeDir, aModel.engine).fill(aCtx2, aW, aH, aMask2, !aFlip);
				limitReach(aCtx2, aMask2, aW, aH);
				for(int p = 0;p < n;p++) {
					if(aHoles[p] && aMask2[p]) {
						System.arraycopy(aCtx2, 3*p, aRGB, 3*p, 3);
					}
				}
			}
		}
		else {
			fillReduced(aRGB, aMask, aW, aH, f, aFlip, aModel, aTdeDir);
		}
		for(int p = 0;p < n;p++) {
			float t = aTrans[p];
			if(!aHoles[p] && t < 0.004f) {
				continue;
			}
			int c = aPix[p];
			float r = ((c>>16)&0xFF)+t*aRGB[3*p]*255;
			float g = ((c>>8)&0xFF)+t*aRGB[3*p+1]*255;
			float b = (c&0xFF)+t*aRGB[3*p+2]*255;
			if(aHoles[p] && !aCore[p]) {
				//Grown border: from the filled colors (at the hole) to the mesh (aGrow + 1 pixels away)
				float w = 1-(aDist[p]/3f)/(aGrow+1);
				float aOR = aOwn[3*p]*255*(1-t)+t*aRGB[3*p]*255, aOG = aOwn[3*p+1]*255*(1-t)+t*aRGB[3*p+1]*255, aOB = aOwn[3*p+2]*255*(1-t)+t*aRGB[3*p+2]*255;
				r = aOR*(1-w)+aRGB[3*p]*255*w;
				g = aOG*(1-w)+aRGB[3*p+1]*255*w;
				b = aOB*(1-w)+aRGB[3*p+2]*255*w;
			}
			aPix[p] = 0xFF000000|(clamp(r)<<16)|(clamp(g)<<8)|clamp(b);
		}
	}

	//Foreground pixels within this distance of a hole are hidden from the model
	static final int FG_MASK = 8;
	//A pixel nearer than the background of the nearest hole by this ratio (1/z) is foreground
	static float FG_EDGE = 1.02f;

	/**
	 * For the pixels within aR of a hole of aCore (4-neighbor steps): aBg the depth (1/z) of the background of the
	 * nearest hole pixel (backgroundDepth(): the farther side of its line), aSide the side of the foreground of its hole
	 * run on the line (1: on the left, nearer than the right side by EDGE; -1: on the right; 0: not known); 0 farther
	 */
	static void holeInfo(boolean[] aCore,float[] aZ,float[] aTrans,int aW,int aH,int aR,float[] aBg,byte[] aSide) {
		int n = aW*aH;
		float[] aB = backgroundDepth(aZ, aTrans, aW, aH);
		int[] aD = new int[n];
		java.util.Arrays.fill(aD, Integer.MAX_VALUE);
		int[] aQ = new int[n];
		int aQN = 0;
		for(int y = 0;y < aH;y++) {
			int aRow = y*aW;
			int x = 0;
			while(x < aW) {
				if(!aCore[aRow+x]) {
					x++;
					continue;
				}
				int x0 = x;
				while(x < aW && aCore[aRow+x]) {
					x++;
				}
				float zl = x0 > 0 ? aZ[aRow+x0-1] : 0, zr = x < aW ? aZ[aRow+x] : 0;
				byte aS = 0;
				if(zl > 0 && zr > 0) {
					aS = zl > GaussianScene.EDGE*zr ? (byte)1 : zr > GaussianScene.EDGE*zl ? (byte)-1 : 0;
				}
				for(int i = x0;i < x;i++) {
					int p = aRow+i;
					aSide[p] = aS;
					aBg[p] = aB[p];
					aD[p] = 0;
					aQ[aQN++] = p;
				}
			}
		}
		//Spread to the pixels around (breadth first, up to aR)
		for(int i = 0;i < aQN;i++) {
			int p = aQ[i];
			int d = aD[p]+1;
			if(d > aR) {
				continue;
			}
			int x = p%aW, y = p/aW;
			for(int k = 0;k < 4;k++) {
				int q = k == 0 ? (x > 0 ? p-1 : -1) : k == 1 ? (x < aW-1 ? p+1 : -1) : k == 2 ? (y > 0 ? p-aW : -1) : (y < aH-1 ? p+aW : -1);
				if(q >= 0 && aD[q] > d) {
					aD[q] = d;
					aBg[q] = aBg[p];
					aSide[q] = aSide[p];
					aQ[aQN++] = q;
				}
			}
		}
	}

	/**
	 * Chamfer distance (3-4: pixels x 3) of each pixel to the nearest one of aIn (0 there)
	 */
	public static int[] distance(boolean[] aIn,int aW,int aH) {
		int n = aW*aH;
		int[] aD = new int[n];
		final int INF = Integer.MAX_VALUE/4;
		for(int p = 0;p < n;p++) {
			aD[p] = aIn[p] ? 0 : INF;
		}
		for(int y = 0;y < aH;y++) {
			for(int x = 0;x < aW;x++) {
				int p = y*aW+x;
				int d = aD[p];
				if(d == 0) {
					continue;
				}
				if(x > 0) d = Math.min(d, aD[p-1]+3);
				if(y > 0) {
					d = Math.min(d, aD[p-aW]+3);
					if(x > 0) d = Math.min(d, aD[p-aW-1]+4);
					if(x < aW-1) d = Math.min(d, aD[p-aW+1]+4);
				}
				aD[p] = d;
			}
		}
		for(int y = aH-1;y >= 0;y--) {
			for(int x = aW-1;x >= 0;x--) {
				int p = y*aW+x;
				int d = aD[p];
				if(d == 0) {
					continue;
				}
				if(x < aW-1) d = Math.min(d, aD[p+1]+3);
				if(y < aH-1) {
					d = Math.min(d, aD[p+aW]+3);
					if(x < aW-1) d = Math.min(d, aD[p+aW+1]+4);
					if(x > 0) d = Math.min(d, aD[p+aW-1]+4);
				}
				aD[p] = d;
			}
		}
		return aD;
	}

	/**
	 * The model on the image reduced by f (blocks of f x f: the mean of their colors, a hole when most of the block
	 * is), its colors enlarged (bilinear) into the holes of the image at its size
	 */
	static void fillReduced(float[] aRGB,boolean[] aHoles,int aW,int aH,int f,boolean aFlip,Model aModel,String aTdeDir) throws Exception {
		int aRW = Math.max(1, aW/f), aRH = Math.max(1, aH/f);
		float[] aSmall = new float[3*aRW*aRH];
		boolean[] aSHoles = new boolean[aRW*aRH];
		for(int y = 0;y < aRH;y++) {
			for(int x = 0;x < aRW;x++) {
				float r = 0, g = 0, b = 0;
				int aN = 0, aNH = 0;
				for(int dy = 0;dy < f;dy++) {
					int sy = Math.min(aH-1, y*f+dy);
					for(int dx = 0;dx < f;dx++) {
						int p = sy*aW+Math.min(aW-1, x*f+dx);
						if(aHoles[p]) {
							aNH++;
							continue;
						}
						r += aRGB[3*p];
						g += aRGB[3*p+1];
						b += aRGB[3*p+2];
						aN++;
					}
				}
				int q = y*aRW+x;
				aSHoles[q] = aNH*2 > f*f || aN == 0;
				if(aN > 0) {
					aSmall[3*q] = r/aN;
					aSmall[3*q+1] = g/aN;
					aSmall[3*q+2] = b/aN;
				}
			}
		}
		aSmall = blurContext(aSmall, aSHoles, aRW, aRH, CONTEXT_BLUR);
		Inpainter.shared(aTdeDir, aModel.engine).fill(aSmall, aRW, aRH, aSHoles, aFlip);
		if(aModel == Model.LIGHT) {
			limitReach(aSmall, aSHoles, aRW, aRH);
		}
		for(int y = 0;y < aH;y++) {
			double sy = Math.max(0, Math.min(aRH-1, (y+0.5)/f-0.5));
			int y0 = (int)sy, y1 = Math.min(aRH-1, y0+1);
			double fy = sy-y0;
			for(int x = 0;x < aW;x++) {
				int p = y*aW+x;
				if(!aHoles[p]) {
					continue;
				}
				double sx = Math.max(0, Math.min(aRW-1, (x+0.5)/f-0.5));
				int x0 = (int)sx, x1 = Math.min(aRW-1, x0+1);
				double fx = sx-x0;
				for(int c = 0;c < 3;c++) {
					double v = (aSmall[3*(y0*aRW+x0)+c]*(1-fx)+aSmall[3*(y0*aRW+x1)+c]*fx)*(1-fy)
							+(aSmall[3*(y1*aRW+x0)+c]*(1-fx)+aSmall[3*(y1*aRW+x1)+c]*fx)*fy;
					aRGB[3*p+c] = (float)v;
				}
			}
		}
	}

	//Both eyes filled (pair()), by scene, cameras, size, options, model
	static final int PAIR_MAX = 4;
	static final java.util.LinkedHashMap<String,java.lang.ref.SoftReference<float[][]>> PAIR_CACHE = new java.util.LinkedHashMap<String,java.lang.ref.SoftReference<float[][]>>(16, 0.75f, true);
	static final Object PAIR_LOCK = new Object();

	/**
	 * Both eyes of the mesh at aW x aH, filled consistently: the left eye filled by aModel; its holes given the depth of
	 * the background around them (backgroundDepth()); its pixels reprojected into the right eye (warp(): parallel
	 * cameras, a horizontal shift by the depth), which take its holes; what is left (seen by the right eye only)
	 * filled by aModel (SDXL only for large areas, largeHoles()) with the image around as context.
	 * @return {left, right}: whole images, 3 floats per pixel 0..1 (cached in memory, and on the disk for SDXL)
	 */
	static String pairId(GaussianScene aScene,StereoCam aCam,int aW,int aH,boolean aDepth,double aThreshold,Model aModel,boolean aPrevCarry) {
		return (aPrevCarry ? "" : "noprev|")+(aScene.fileKey != null ? aScene.fileKey : "mem"+System.identityHashCode(aScene))+"|"+aScene.n+"|"+aCam.save()+"|pair|"+aW+"x"+aH+"|"+aDepth+"|"+aThreshold+"|"+aModel.id+(aModel == Model.SDXL ? "|"+SDXL_STEPS : "");
	}

	static String sdxlId(GaussianScene aScene,StereoCam aCam,int aEye,int aW,int aH,boolean aMesh,boolean aDepth,double aThreshold) {
		return (aScene.fileKey != null ? aScene.fileKey : "mem"+System.identityHashCode(aScene))+"|"+aScene.n+"|"+aCam.save()+"|"+aEye+"|"+aW+"x"+aH+"|"+aMesh+"|"+aDepth+"|"+aThreshold+"|"+SDXL_STEPS;
	}

	/**
	 * The SDXL filling of this view (at sdxlSize() of aW x aH) is already made: in memory or on the disk
	 */
	public static boolean sdxlCached(GaussianScene aScene,StereoCam aCam,int aEye,int aW,int aH,boolean aMesh,boolean aDepth,double aThreshold,String aTdeDir) {
		return sdxlCached(aScene, aCam, aEye, aW, aH, aMesh, aDepth, aThreshold, aTdeDir, true, true);
	}

	/** sdxlCached(), the pair only when aPairCarry (else the view alone), aPrevCarry: see render() */
	public static boolean sdxlCached(GaussianScene aScene,StereoCam aCam,int aEye,int aW,int aH,boolean aMesh,boolean aDepth,double aThreshold,String aTdeDir,boolean aPairCarry,boolean aPrevCarry) {
		int[] aS = sdxlSize(aW, aH);
		java.io.File aDir = aTdeDir == null ? null : new java.io.File(new java.io.File(aTdeDir, "Cache"), "SmartFill");
		if(aMesh && aEye != 0 && aPairCarry) {
			String aId = pairId(aScene, aCam, aS[0], aS[1], aDepth, aThreshold, Model.SDXL, aPrevCarry);
			synchronized(PAIR_CACHE) {
				java.lang.ref.SoftReference<float[][]> aR = PAIR_CACHE.get(aId);
				if(aR != null && aR.get() != null) {
					return true;
				}
			}
			String aH0 = hash(aId);
			return aDir != null && aScene.fileKey != null && new java.io.File(aDir, aH0+"_L.png").isFile() && new java.io.File(aDir, aH0+"_R.png").isFile();
		}
		String aId = sdxlId(aScene, aCam, aEye, aS[0], aS[1], aMesh, aDepth, aThreshold);
		synchronized(SDXL_CACHE) {
			java.lang.ref.SoftReference<float[]> aR = SDXL_CACHE.get(aId);
			if(aR != null && aR.get() != null) {
				return true;
			}
		}
		return aDir != null && aScene.fileKey != null && new java.io.File(aDir, hash(aId)+".png").isFile();
	}

	static float[][] pair(GaussianScene aScene,StereoCam aCam,int aW,int aH,boolean aDepth,double aThreshold,Model aModel,String aTdeDir,boolean aPrevCarry,boolean aForced) throws Exception {
		String aId = pairId(aScene, aCam, aW, aH, aDepth, aThreshold, aModel, aPrevCarry);
		synchronized(PAIR_LOCK) {
			//Carried over from the image shown before: computed again once (not SDXL: its pair is kept, 45 s per eye)
			boolean aAgain = aForced && aModel != Model.SDXL && takeCarry(aId);
			synchronized(PAIR_CACHE) {
				java.lang.ref.SoftReference<float[][]> aR = PAIR_CACHE.get(aId);
				float[][] aC = aR == null ? null : aR.get();
				if(aC != null && !aAgain) {
					return aC;
				}
			}
			boolean aDisk = aModel == Model.SDXL && aScene.fileKey != null && aTdeDir != null;
			java.io.File aDir = aTdeDir == null ? null : new java.io.File(new java.io.File(aTdeDir, "Cache"), "SmartFill");
			String aH0 = hash(aId);
			java.io.File aFL = aDir == null ? null : new java.io.File(aDir, aH0+"_L.png");
			java.io.File aFR = aDir == null ? null : new java.io.File(aDir, aH0+"_R.png");
			float[][] aRes = null;
			if(aDisk && aFL.isFile() && aFR.isFile()) {
				float[] aL = readRGB(aFL, aW, aH), aRt = readRGB(aFR, aW, aH);
				if(aL != null && aRt != null) {
					aRes = new float[][] {aL, aRt};
					aFL.setLastModified(System.currentTimeMillis());
					aFR.setLastModified(System.currentTimeMillis());
				}
			}
			if(aRes == null) {
				aRes = computePair(aScene, aCam, aW, aH, aDepth, aThreshold, aModel, aTdeDir, aPrevCarry, aId, aAgain);
				if(aDisk) {
					aDir.mkdirs();
					writeRGB(aRes[0], aW, aH, aFL);
					writeRGB(aRes[1], aW, aH, aFR);
					pruneDisk(aDir);
				}
			}
			synchronized(PAIR_CACHE) {
				PAIR_CACHE.put(aId, new java.lang.ref.SoftReference<float[][]>(aRes));
				while(PAIR_CACHE.size() > PAIR_MAX) {
					PAIR_CACHE.remove(PAIR_CACHE.keySet().iterator().next());
				}
			}
			return aRes;
		}
	}

	static float[][] computePair(GaussianScene aScene,StereoCam aCam,int aW,int aH,boolean aDepth,double aThreshold,Model aModel,String aTdeDir,boolean aPrevCarry,String aId,boolean aForced) throws Exception {
		long aT0 = System.currentTimeMillis();
		int n = aW*aH;
		int aSeed = (aScene.fileKey != null ? aScene.fileKey.hashCode() : aScene.n) & 0x7FFFFFFF;
		//Left eye, with its depths
		int[] aPL = new int[n];
		float[] aTL = new float[n];
		float[] aZL = new float[n];
		GaussianScene.View aVL = aCam.view(aScene, -1, aW, aH);
		aVL.meshFill = true;
		aVL.depthView = aDepth;
		aVL.cosCut = aThreshold;
		aVL.depthOut = aZL;
		aScene.render(aVL, aW, aH, aPL, aTL);
		//The filling of the previous image carried over first; SDXL then only for the large areas left (largeHoles()),
		//else the light model finishes
		String aSL = stream(aW, aH, -1, aDepth, aThreshold, aModel), aSR = stream(aW, aH, 1, aDepth, aThreshold, aModel);
		boolean[] aKL = aPrevCarry ? temporal(aPL, aTL, aZL, aVL, aW, aH, aSL, aForced ? carryPrev(aSL) : null) : null;
		Model aModelL = aModel == Model.SDXL && !largeHoles(aTL, aW, aH) && available(aTdeDir) ? Model.LIGHT : aModel;
		float[] aRGBL = fillImage(aPL, aTL, aW, aH, true, aModelL, aSeed, aTdeDir, aZL);
		if(aPrevCarry) {
			keepPairPrev(aId, -1, remember(aSL, aRGBL, aZL, aTL, aKL, aVL, aW, aH));
		}
		//Right eye
		int[] aPR = new int[n];
		float[] aTR = new float[n];
		float[] aZR = new float[n];
		GaussianScene.View aVR = aCam.view(aScene, 1, aW, aH);
		aVR.depthOut = aZR;
		aVR.meshFill = true;
		aVR.depthView = aDepth;
		aVR.cosCut = aThreshold;
		aScene.render(aVR, aW, aH, aPR, aTR);
		boolean[] aKR = new boolean[n];
		for(int p = 0;p < n;p++) {
			aKR[p] = aTR[p] <= HOLE && aZR[p] > 0;
		}
		//The left eye carried over: x right = x left + (cx right - cx left) - focal * base / z, the depth of each pixel
		//of its holes from the mesh not thresholded (the triangles cut there: a hole of the thresholding may cover
		//varied depths, each pixel gets its own parallax)
		double aFB = aVL.focal*aCam.baseFor(aScene);
		float[] aZB = aZL.clone();
		int aSteep = 0, aThin = 0;
		if(aThreshold > 0) {
			float[] aZU = new float[n];
			GaussianScene.View aVU = aCam.view(aScene, -1, aW, aH);
			aVU.meshFill = true;
			aVU.depthView = aDepth;
			aVU.cosCut = 0;
			aVU.depthOut = aZU;
			aScene.render(aVU, aW, aH, new int[n], null);
			for(int p = 0;p < n;p++) {
				if(!(aZL[p] > 0 && aTL[p] <= HOLE)) {
					aZB[p] = aZU[p];
				}
			}
		}
		else {
			for(int p = 0;p < n;p++) {
				if(!(aZL[p] > 0 && aTL[p] <= HOLE)) {
					aZB[p] = 0;
				}
			}
		}
		boolean[] aKnownL = new boolean[n];
		for(int p = 0;p < n;p++) {
			aKnownL[p] = aZL[p] > 0 && aTL[p] <= HOLE;
		}
		//Only the filled pixels of homogeneous depth are cloned: where the parallax changes by more than PAIR_SLOPE
		//pixel per pixel (strong slope), they would be stretched / squeezed: left empty, filled by the model with the
		//disocclusions of the right eye
		float[] aZS = aZB.clone();
		for(int y = 0;y < aH;y++) {
			for(int x = 0;x < aW;x++) {
				int p = y*aW+x;
				if(aKnownL[p] || aZS[p] <= 0) {
					continue;
				}
				float z = aZS[p];
				double aMax = 0;
				if(x > 0 && aZS[p-1] > 0) aMax = Math.max(aMax, Math.abs(z-aZS[p-1]));
				if(x+1 < aW && aZS[p+1] > 0) aMax = Math.max(aMax, Math.abs(z-aZS[p+1]));
				if(y > 0 && aZS[p-aW] > 0) aMax = Math.max(aMax, Math.abs(z-aZS[p-aW]));
				if(y+1 < aH && aZS[p+aW] > 0) aMax = Math.max(aMax, Math.abs(z-aZS[p+aW]));
				if(aFB*aMax > PAIR_SLOPE) {
					aZB[p] = 0;
					aSteep++;
				}
			}
		}
		//The pixels covered by no triangle at all (the band around the frame when the camera moves back...): the depth of
		//the background around them (backgroundDepth()), only in the filled areas of a minimum width: a thin hole (along
		//an edge) is filled mostly from its borders, at a background depth that may be wrong, and carried over it would
		//draw a line elsewhere in the other eye
		float[] aZBg = backgroundDepth(aZL, aTL, aW, aH);
		boolean[] aWide = wideHoles(aKnownL, aW, aH, Math.max(2, aH/PAIR_MIN_HOLE));
		for(int p = 0;p < n;p++) {
			if(aKnownL[p] || aZS[p] > 0 || aZBg[p] <= 0) {
				continue;
			}
			if(aWide[p]) {
				aZB[p] = aZBg[p];
			}
			else {
				aThin++;
			}
		}
		float[] aWarp = new float[3*n];
		float[] aWZ = new float[n];
		warp(aRGBL, aZB, aW, aH, aVR.cx-aVL.cx, aFB, aWarp, aWZ);
		int aHoles = 0, aGiven = 0;
		for(int p = 0;p < n;p++) {
			if(aTR[p] <= HOLE) {
				continue;
			}
			aHoles++;
			if(aWZ[p] > 0) {
				aPR[p] = 0xFF000000|(clamp(aWarp[3*p]*255)<<16)|(clamp(aWarp[3*p+1]*255)<<8)|clamp(aWarp[3*p+2]*255);
				aTR[p] = 0;
				aZR[p] = aWZ[p];
				aGiven++;
			}
		}
		int aLeft = aHoles-aGiven;
		if(aPrevCarry) {
			temporal(aPR, aTR, aZR, aVR, aW, aH, aSR, aForced ? carryPrev(aSR) : null);
		}
		Model aModelR = aModel == Model.SDXL && !largeHoles(aTR, aW, aH) ? Model.LIGHT : aModel;
		if(aModelR == Model.LIGHT && aModel != Model.LIGHT && !available(aTdeDir)) {
			aModelR = aModel;
		}
		float[] aRGBR = fillImage(aPR, aTR, aW, aH, false, aModelR, aSeed, aTdeDir, aZR);
		if(aPrevCarry) {
			keepPairPrev(aId, 1, remember(aSR, aRGBR, aZR, aTR, aKR, aVR, aW, aH));
		}
		System.out.println("Smart filling "+aModel.id+": pair "+aW+"x"+aH+", left eye filled by "+aModelL.id+", right eye holes "+aHoles+" px, "+aGiven+" from the left eye, "
				+aLeft+" filled by "+aModelR.id+" (left eye holes not carried over: steep "+aSteep+" px, thin "+aThin+" px), "+(System.currentTimeMillis()-aT0)+" ms");
		return new float[][] {aRGBL, aRGBR};
	}

	/**
	 * The view aPix / aTrans (not changed) filled by aModel: the whole image, 3 floats per pixel 0..1
	 */
	public static float[] fillImage(int[] aPix,float[] aTrans,int aW,int aH,boolean aFlip,Model aModel,int aSeed,String aTdeDir,float[] aZ) throws Exception {
		int n = aW*aH;
		int[] aP = aPix.clone();
		if(aModel == Model.SDXL) {
			float[] aImg = sdxlImage(aP, aTrans, aW, aH, aSeed, aTdeDir);
			if(aImg != null) {
				fill(aP, aTrans, aW, aH, aFlip, aModel, aImg, new int[] {aW, aH}, aTdeDir, aZ);
			}
		}
		else {
			fill(aP, aTrans, aW, aH, aFlip, aModel, null, null, aTdeDir, aZ);
		}
		float[] aRes = new float[3*n];
		for(int p = 0;p < n;p++) {
			int c = aP[p];
			aRes[3*p] = ((c>>16)&0xFF)/255f;
			aRes[3*p+1] = ((c>>8)&0xFF)/255f;
			aRes[3*p+2] = (c&0xFF)/255f;
		}
		return aRes;
	}

	/**
	 * Any view (not a scene: the AI eraser, ai.EraseFiller) aPix / aTrans (not changed, holes: aTrans > HOLE) filled by
	 * aModel, with its depths aZ (1/z or any nearness > 0, the larger the nearer; null: unknown). SDXL runs on the view
	 * resized to sdxlSize() (its sides multiple of 8), its colors enlarged into the holes; a failure of SDXL: LIGHT.
	 * @return the whole image, 3 floats per pixel 0..1
	 */
	public static float[] fillView(int[] aPix,float[] aTrans,int aW,int aH,boolean aFlip,Model aModel,int aSeed,String aTdeDir,float[] aZ) throws Exception {
		int n = aW*aH;
		if(aModel == Model.SDXL) {
			try {
				int[] aS = sdxlSize(aW, aH);
				int aSW = aS[0], aSH = aS[1];
				int[] aSP = new int[aSW*aSH];
				float[] aST = new float[aSW*aSH];
				for(int y = 0;y < aSH;y++) {
					int sy = Math.min(aH-1, (int)((y+0.5)*aH/aSH));
					for(int x = 0;x < aSW;x++) {
						int sx = Math.min(aW-1, (int)((x+0.5)*aW/aSW));
						aSP[y*aSW+x] = aPix[sy*aW+sx];
						aST[y*aSW+x] = aTrans[sy*aW+sx];
					}
				}
				float[] aImg = sdxlImage(aSP, aST, aSW, aSH, aSeed, aTdeDir);
				int[] aP = aPix.clone();
				if(aImg != null) {
					fill(aP, aTrans, aW, aH, aFlip, aModel, aImg, aS, aTdeDir, aZ);
				}
				return toRGB(aP);
			}
			catch(Throwable t) {
				System.err.println("Smart filling: SDXL failed, the light model instead");
				t.printStackTrace(System.err);
				aModel = Model.LIGHT;
			}
		}
		int[] aP = aPix.clone();
		fill(aP, aTrans, aW, aH, aFlip, aModel, null, null, aTdeDir, aZ);
		float[] aRes = new float[3*n];
		for(int p = 0;p < n;p++) {
			int c = aP[p];
			aRes[3*p] = ((c>>16)&0xFF)/255f;
			aRes[3*p+1] = ((c>>8)&0xFF)/255f;
			aRes[3*p+2] = (c&0xFF)/255f;
		}
		return aRes;
	}

	//== Temporal coherence: the filling of the previous image carried over ==

	/**
	 * An image filled (the whole image, the depths of the background in its holes, its real pixels, its camera), kept to
	 * fill the next images of the same stream (size, eye, options, model)
	 */
	static final class Prev {
		String stream;
		float[] rgb;
		float[] z;
		boolean[] known;
		double[] eye, right, down, fwd;
		double focal, cx, cy;
		int w, h;
		//Its filled pixels in the parts of its holes where a disc of radius wideR fits (wideHoles()), made once
		boolean[] wide;
		int wideR;
		synchronized boolean[] wide(int aR) {
			if(wide == null || wideR != aR) {
				wide = wideHoles(known, w, h, aR);
				wideR = aR;
			}
			return wide;
		}
	}
	//Images kept (all streams), the latest first
	static final java.util.LinkedList<Prev> PREV = new java.util.LinkedList<Prev>();
	static final int PREV_MAX = 6;
	//Per stream, the images tried (the latest first): another clip may have come in between
	static final int PREV_TRY = 2;
	//Comparison of the real pixels of both images: step of the grid of samples, a sample matching when its colors
	//differ by less than MATCH_DIFF (mean of the channels 0..1), enough samples seen in both (MATCH_COVER) and enough
	//of them matching (MATCH_GOOD): else another place (a change of shot), nothing carried over
	static final int MATCH_STEP = 4;
	static final double MATCH_DIFF = 0.08;
	static final double MATCH_COVER = 0.3;
	static final double MATCH_GOOD = 0.6;

	static String stream(int aW,int aH,int aEye,boolean aDepth,double aThreshold,Model aModel) {
		return aW+"x"+aH+"|"+aEye+"|"+aDepth+"|"+aThreshold+"|"+aModel.id;
	}

	/**
	 * Point of the pixel (x, y) at 1/z aIZ of the view aV (pixel centers at + 0.5) in the image of aP: {x, y, 1/z}, null
	 * behind it
	 */
	static double[] reproject(GaussianScene.View aV,int x,int y,float aIZ,Prev aP) {
		double Z = 1/aIZ;
		double X = (x+0.5-aV.cx)*Z/aV.focal, Y = (y+0.5-aV.cy)*Z/aV.focal;
		double dx = aV.eye[0]-aP.eye[0]+X*aV.right[0]+Y*aV.down[0]+Z*aV.fwd[0];
		double dy = aV.eye[1]-aP.eye[1]+X*aV.right[1]+Y*aV.down[1]+Z*aV.fwd[1];
		double dz = aV.eye[2]-aP.eye[2]+X*aV.right[2]+Y*aV.down[2]+Z*aV.fwd[2];
		double Z2 = aP.fwd[0]*dx+aP.fwd[1]*dy+aP.fwd[2]*dz;
		if(Z2 < 1e-4) {
			return null;
		}
		double X2 = aP.right[0]*dx+aP.right[1]*dy+aP.right[2]*dz;
		double Y2 = aP.down[0]*dx+aP.down[1]*dy+aP.down[2]*dz;
		return new double[] {aP.focal*X2/Z2+aP.cx-0.5, aP.focal*Y2/Z2+aP.cy-0.5, 1/Z2};
	}

	/**
	 * Index in the image of aP of the point (nearest pixel) when its depth there agrees (EDGE) with aR, else -1
	 */
	static int matchAt(double[] aR,Prev aP) {
		if(aR == null) {
			return -1;
		}
		int x = (int)Math.round(aR[0]), y = (int)Math.round(aR[1]);
		if(x < 0 || y < 0 || x >= aP.w || y >= aP.h) {
			return -1;
		}
		int q = y*aP.w+x;
		float pz = aP.z[q];
		if(pz <= 0) {
			return -1;
		}
		double iz = aR[2];
		return Math.max(pz, iz) <= GaussianScene.EDGE*Math.min(pz, iz) ? q : -1;
	}

	/**
	 * Before the filling of a view (aPix / aTrans / aZ of the mesh, camera aV) by the model: an image of the same stream
	 * filled before (the previous image of the play) is reprojected into it with the depths and the cameras of both
	 * (each hole pixel at the depth of its background, backgroundDepth(), carried to the other camera and back to its
	 * pixel there: same place, same depth, else nothing), when their real pixels match (MATCH_*: not a change of shot);
	 * its holes then take the colors of that image (they become real pixels at that depth), and the border of the holes
	 * the model would blend into the mesh (the stretched triangles of the cut) is blended with them in the same way. The
	 * model only fills what is left: the same filling from one image to the next.
	 * @return the real pixels of the view (before), for remember()
	 */
	static boolean[] temporal(int[] aPix,float[] aTrans,float[] aZ,GaussianScene.View aV,int aW,int aH,String aStream,Prev aFirst) {
		int n = aW*aH;
		boolean[] aKnown = new boolean[n];
		boolean[] aHoles = new boolean[n];
		int aNH = 0;
		for(int p = 0;p < n;p++) {
			aKnown[p] = aTrans[p] <= HOLE && aZ[p] > 0;
			if(aTrans[p] > HOLE) {
				aHoles[p] = true;
				aNH++;
			}
		}
		if(aNH == 0) {
			return aKnown;
		}
		java.util.Vector<Prev> aCands = new java.util.Vector<Prev>();
		//The image shown before a step of the player first (carryNext())
		if(aFirst != null && aFirst.stream.equals(aStream) && aFirst.w == aW && aFirst.h == aH) {
			aCands.add(aFirst);
		}
		synchronized(PREV) {
			int aN = 0;
			for(Prev aP : PREV) {
				if(aP.stream.equals(aStream) && aN < PREV_TRY) {
					aN++;
					if(aP != aFirst) {
						aCands.add(aP);
					}
				}
			}
		}
		for(Prev aP : aCands) {
			//Real pixels of both seen at the same place: their colors
			int aSampled = 0, aMatched = 0, aGood = 0;
			for(int y = MATCH_STEP/2;y < aH;y += MATCH_STEP) {
				for(int x = MATCH_STEP/2;x < aW;x += MATCH_STEP) {
					int p = y*aW+x;
					if(!aKnown[p] || aTrans[p] > 0.004f) {
						continue;
					}
					aSampled++;
					int q = matchAt(reproject(aV, x, y, aZ[p], aP), aP);
					if(q < 0 || !aP.known[q]) {
						continue;
					}
					aMatched++;
					int c = aPix[p];
					double d = (Math.abs(((c>>16)&0xFF)/255.0-aP.rgb[3*q])+Math.abs(((c>>8)&0xFF)/255.0-aP.rgb[3*q+1])
							+Math.abs((c&0xFF)/255.0-aP.rgb[3*q+2]))/3;
					if(d < MATCH_DIFF) {
						aGood++;
					}
				}
			}
			boolean aOk = aSampled > 0 && aMatched >= MATCH_COVER*aSampled && aGood >= MATCH_GOOD*aMatched;
			if(!aOk) {
				System.out.println("Smart filling: previous image not taken (seen in both "+aMatched+" / "+aSampled
						+", matching "+aGood+")");
				continue;
			}
			//The holes, at the depth of their background; around them, that background (foreground: not blended)
			float[] aZB = backgroundDepthFull(aZ, aTrans, aW, aH);
			int aGrow = Math.max(2, Math.round(aH/360f));
			float[] aBg = new float[n];
			holeInfo(aHoles, aZ, aTrans, aW, aH, aGrow+1, aBg, new byte[n]);
			//Only the parts of the holes of a minimum width (wideHoles(), as between the eyes), taken only from the wide
			//filled parts of that image (or its real pixels): a thin band along an edge carried over from image to image
			//drifts with that edge and fills the hole alone; the model fills them, with the other edge as context
			boolean[] aNotHole = new boolean[n];
			for(int p = 0;p < n;p++) {
				aNotHole[p] = !aHoles[p];
			}
			int aMinR = Math.max(2, aH/PAIR_MIN_HOLE);
			boolean[] aThick = wideHoles(aNotHole, aW, aH, aMinR);
			boolean[] aPWide = aP.wide(aMinR);
			int aGiven = 0;
			for(int y = 0;y < aH;y++) {
				for(int x = 0;x < aW;x++) {
					int p = y*aW+x;
					if(!aThick[p] || aZB[p] <= 0) {
						continue;
					}
					int q = matchAt(reproject(aV, x, y, aZB[p], aP), aP);
					if(q < 0 || !aP.known[q] && !aPWide[q]) {
						continue;
					}
					aPix[p] = 0xFF000000|(clamp(aP.rgb[3*q]*255)<<16)|(clamp(aP.rgb[3*q+1]*255)<<8)|clamp(aP.rgb[3*q+2]*255);
					aTrans[p] = 0;
					aZ[p] = aZB[p];
					aGiven++;
				}
			}
			//Their border in the mesh (grown by fill()): blended toward the previous image as the model's colors were
			int[] aDist = distance(aHoles, aW, aH);
			for(int y = 0;y < aH;y++) {
				for(int x = 0;x < aW;x++) {
					int p = y*aW+x;
					if(aHoles[p] || !aKnown[p] || aDist[p] > 3*aGrow || aBg[p] <= 0 || aZ[p] > FG_EDGE*aBg[p]) {
						continue;
					}
					int q = matchAt(reproject(aV, x, y, aZ[p], aP), aP);
					if(q < 0) {
						continue;
					}
					float w = 1-(aDist[p]/3f)/(aGrow+1);
					int c = aPix[p];
					//The part of the pixel the mesh does not cover (blended with black) completed with that image
					float t = aTrans[p];
					float r = ((c>>16)&0xFF)+t*aP.rgb[3*q]*255, g = ((c>>8)&0xFF)+t*aP.rgb[3*q+1]*255, b = (c&0xFF)+t*aP.rgb[3*q+2]*255;
					aTrans[p] = 0;
					r = r*(1-w)+aP.rgb[3*q]*255*w;
					g = g*(1-w)+aP.rgb[3*q+1]*255*w;
					b = b*(1-w)+aP.rgb[3*q+2]*255*w;
					aPix[p] = 0xFF000000|(clamp(r)<<16)|(clamp(g)<<8)|clamp(b);
				}
			}
			System.out.println("Smart filling: previous image taken (matching "+aGood+" / "+aMatched+"), "+aGiven+" of "+aNH
					+" hole pixels from it");
			break;
		}
		return aKnown;
	}

	/**
	 * Keep the filled image aRGB (whole image 0..1) of the view (aZ / aTrans after temporal(), aKnown its real pixels)
	 * for the next images of aStream
	 */
	static Prev remember(String aStream,float[] aRGB,float[] aZ,float[] aTrans,boolean[] aKnown,GaussianScene.View aV,int aW,int aH) {
		Prev aP = new Prev();
		aP.stream = aStream;
		aP.rgb = aRGB;
		aP.z = backgroundDepthFull(aZ, aTrans, aW, aH);
		aP.known = aKnown;
		aP.eye = aV.eye.clone();
		aP.right = aV.right.clone();
		aP.down = aV.down.clone();
		aP.fwd = aV.fwd.clone();
		aP.focal = aV.focal;
		aP.cx = aV.cx;
		aP.cy = aV.cy;
		aP.w = aW;
		aP.h = aH;
		synchronized(PREV) {
			PREV.addFirst(aP);
			while(PREV.size() > PREV_MAX) {
				PREV.removeLast();
			}
		}
		return aP;
	}

	//== Carry-over from the image shown (next image / step buttons of the player) ==

	//Filling (Prev) of each view computed, by its render() cache key (cache hits included), and of each pair, by
	//pairId() + eye; per stream, the filling of the view last given to the monitor (SHOWN)
	static final int PREV_OF_MAX = 16;
	static final java.util.LinkedHashMap<String,java.lang.ref.SoftReference<Prev>> PREV_OF = new java.util.LinkedHashMap<String,java.lang.ref.SoftReference<Prev>>(16, 0.75f, true);
	static final java.util.HashMap<String,Prev> SHOWN = new java.util.HashMap<String,Prev>();
	//carryNext(): the SHOWN of that time, the time asked, the views already filled again for it
	static final Object CARRY_LOCK = new Object();
	static java.util.HashMap<String,Prev> carryFrom = null;
	static volatile long carryTimeMS = -1;
	static final java.util.HashSet<String> carryDone = new java.util.HashSet<String>();

	static void keepPrevOf(String aId,Prev aP) {
		if(aP == null) {
			return;
		}
		synchronized(PREV_OF) {
			PREV_OF.put(aId, new java.lang.ref.SoftReference<Prev>(aP));
			while(PREV_OF.size() > PREV_OF_MAX) {
				PREV_OF.remove(PREV_OF.keySet().iterator().next());
			}
		}
	}

	static Prev prevOf(String aId) {
		synchronized(PREV_OF) {
			java.lang.ref.SoftReference<Prev> aR = PREV_OF.get(aId);
			return aR == null ? null : aR.get();
		}
	}

	static void keepPairPrev(String aId,int aEye,Prev aP) {
		keepPrevOf(aId+"|pairEye"+aEye, aP);
	}

	static Prev pairPrev(String aId,int aEye) {
		return prevOf(aId+"|pairEye"+(aEye < 0 ? -1 : 1));
	}

	/**
	 * The view of the render() key aId is given (to the monitor): its filling becomes the one shown for its stream
	 */
	static void markShown(String aId) {
		Prev aP = prevOf(aId);
		if(aP != null) {
			synchronized(CARRY_LOCK) {
				SHOWN.put(aP.stream, aP);
			}
		}
	}

	/**
	 * The player goes to aTimeMS (next image, step of x s): the views of that time are filled again, carried over from
	 * the images shown now (each stream: its view last given), instead of being taken from the caches
	 */
	public static void carryNext(long aTimeMS) {
		synchronized(CARRY_LOCK) {
			carryFrom = new java.util.HashMap<String,Prev>(SHOWN);
			carryDone.clear();
			carryTimeMS = aTimeMS;
		}
	}

	/**
	 * The views at aTimeMS are those asked by carryNext()
	 */
	public static boolean isCarryTime(long aTimeMS) {
		return carryTimeMS >= 0 && carryTimeMS == aTimeMS;
	}

	/**
	 * True the first time the view (or pair) aId is asked after carryNext(): it is filled again
	 */
	static boolean takeCarry(String aId) {
		synchronized(CARRY_LOCK) {
			return carryFrom != null && carryDone.add(aId);
		}
	}

	static Prev carryPrev(String aStream) {
		synchronized(CARRY_LOCK) {
			return carryFrom == null ? null : carryFrom.get(aStream);
		}
	}

	//== Keyframes of a rendering in two passes (processors.TwoPassRender) ==

	/**
	 * Latest image kept (identity, null when none): the images kept after it are those of the image rendered since
	 */
	public static Object prevMark() {
		synchronized(PREV) {
			return PREV.isEmpty() ? null : PREV.getFirst();
		}
	}

	/**
	 * Write to aFile the images kept since aMark (prevMark() before the image rendered; all when aMark is no longer
	 * kept), oldest first: colors in 8 bits (they are only used as such), depths, real pixels, camera
	 * @return the number written
	 */
	public static int savePrevSince(Object aMark,java.io.File aFile) throws java.io.IOException {
		java.util.ArrayList<Prev> aList = new java.util.ArrayList<Prev>();
		synchronized(PREV) {
			for(Prev aP : PREV) {
				if(aP == aMark) {
					break;
				}
				aList.add(0, aP);
			}
		}
		try(java.io.DataOutputStream aOut = new java.io.DataOutputStream(new java.io.BufferedOutputStream(
				new java.util.zip.GZIPOutputStream(new java.io.FileOutputStream(aFile), 1<<16) {
					{ def.setLevel(java.util.zip.Deflater.BEST_SPEED); }
				}, 1<<16))) {
			aOut.writeInt(aList.size());
			for(Prev aP : aList) {
				int n = aP.w*aP.h;
				aOut.writeUTF(aP.stream);
				aOut.writeInt(aP.w);
				aOut.writeInt(aP.h);
				for(double[] aA : new double[][] {aP.eye, aP.right, aP.down, aP.fwd}) {
					for(int c = 0;c < 3;c++) {
						aOut.writeDouble(aA[c]);
					}
				}
				aOut.writeDouble(aP.focal);
				aOut.writeDouble(aP.cx);
				aOut.writeDouble(aP.cy);
				byte[] aB = new byte[3*n];
				for(int i = 0;i < 3*n;i++) {
					aB[i] = (byte)clamp(aP.rgb[i]*255);
				}
				aOut.write(aB);
				java.nio.ByteBuffer aZB = java.nio.ByteBuffer.allocate(4*n);
				aZB.asFloatBuffer().put(aP.z, 0, n);
				aOut.write(aZB.array());
				byte[] aK = new byte[n];
				for(int i = 0;i < n;i++) {
					aK[i] = (byte)(aP.known[i] ? 1 : 0);
				}
				aOut.write(aK);
			}
			return aList.size();
		}
	}

	/**
	 * The images of savePrevSince() kept again, as the latest ones: the next images of their streams are filled from
	 * them (temporal())
	 */
	public static void restorePrev(java.io.File aFile) throws java.io.IOException {
		try(java.io.DataInputStream aIn = new java.io.DataInputStream(new java.io.BufferedInputStream(
				new java.util.zip.GZIPInputStream(new java.io.FileInputStream(aFile), 1<<16), 1<<16))) {
			int aNb = aIn.readInt();
			for(int k = 0;k < aNb;k++) {
				Prev aP = new Prev();
				aP.stream = aIn.readUTF();
				aP.w = aIn.readInt();
				aP.h = aIn.readInt();
				int n = aP.w*aP.h;
				double[][] aA = new double[4][3];
				for(int a = 0;a < 4;a++) {
					for(int c = 0;c < 3;c++) {
						aA[a][c] = aIn.readDouble();
					}
				}
				aP.eye = aA[0];
				aP.right = aA[1];
				aP.down = aA[2];
				aP.fwd = aA[3];
				aP.focal = aIn.readDouble();
				aP.cx = aIn.readDouble();
				aP.cy = aIn.readDouble();
				byte[] aB = new byte[3*n];
				aIn.readFully(aB);
				aP.rgb = new float[3*n];
				for(int i = 0;i < 3*n;i++) {
					aP.rgb[i] = (aB[i]&0xFF)/255f;
				}
				byte[] aZB = new byte[4*n];
				aIn.readFully(aZB);
				aP.z = new float[n];
				java.nio.ByteBuffer.wrap(aZB).asFloatBuffer().get(aP.z);
				byte[] aK = new byte[n];
				aIn.readFully(aK);
				aP.known = new boolean[n];
				for(int i = 0;i < n;i++) {
					aP.known[i] = aK[i] != 0;
				}
				synchronized(PREV) {
					PREV.addFirst(aP);
					while(PREV.size() > PREV_MAX) {
						PREV.removeLast();
					}
				}
			}
		}
	}

	//Holes of the left eye carried over to the right one (computePair()), and holes filled from the previous image
	//(temporal(), on both images): only those at least 2 x max(2, height / PAIR_MIN_HOLE) + 1 pixels wide (wideHoles())
	static final int PAIR_MIN_HOLE = 180;
	//Filled pixels of the left eye carried over to the right one (computePair()) only where their parallax changes by at
	//most this many pixels per pixel (homogeneous depth): beyond, a strong slope, left to the model
	static final double PAIR_SLOPE = 0.5;

	/**
	 * The hole pixels (not aKnown) of the parts of the holes where a disc of radius aR fits (opening by that disc, with
	 * chamfer distances): the parts narrower than 2 aR + 1 pixels are left out
	 */
	public static boolean[] wideHoles(boolean[] aKnown,int aW,int aH,int aR) {
		int n = aW*aH;
		int[] aD = distance(aKnown, aW, aH);
		boolean[] aCore = new boolean[n];
		for(int p = 0;p < n;p++) {
			aCore[p] = aD[p] > 3*aR;
		}
		int[] aDC = distance(aCore, aW, aH);
		boolean[] aRes = new boolean[n];
		for(int p = 0;p < n;p++) {
			aRes[p] = !aKnown[p] && aDC[p] <= 3*aR;
		}
		return aRes;
	}

	//SDXL only for large areas: hole pixels farther than height / SDXL_DEEP from the known pixels, more than
	//SDXL_DEEP_PART of the image (not the thin bands, as the one around the frame when the camera moves back)
	static final int SDXL_DEEP = 50;
	static final double SDXL_DEEP_PART = 0.002;

	/**
	 * The holes (aTrans > HOLE) need SDXL: enough of their pixels deep inside them (SDXL_DEEP, SDXL_DEEP_PART)
	 */
	public static boolean largeHoles(float[] aTrans,int aW,int aH) {
		int n = aW*aH;
		boolean[] aKnown = new boolean[n];
		for(int p = 0;p < n;p++) {
			aKnown[p] = aTrans[p] <= HOLE;
		}
		int[] aD = distance(aKnown, aW, aH);
		int aR = 3*Math.max(4, aH/SDXL_DEEP), aN = 0;
		for(int p = 0;p < n;p++) {
			if(aD[p] > aR) {
				aN++;
			}
		}
		return aN > n*SDXL_DEEP_PART;
	}

	/**
	 * backgroundDepth(), the lines without any known pixel (a band along the frame) given the depth of the nearest
	 * pixel of their column that has one (the farther of above and below)
	 */
	public static float[] backgroundDepthFull(float[] aZ,float[] aTrans,int aW,int aH) {
		float[] aRes = backgroundDepth(aZ, aTrans, aW, aH);
		float[] aUp = new float[aH];
		for(int x = 0;x < aW;x++) {
			float aLast = 0;
			for(int y = 0;y < aH;y++) {
				float z = aRes[y*aW+x];
				if(z > 0) {
					aLast = z;
				}
				aUp[y] = aLast;
			}
			aLast = 0;
			for(int y = aH-1;y >= 0;y--) {
				int p = y*aW+x;
				if(aRes[p] > 0) {
					aLast = aRes[p];
					continue;
				}
				float u = aUp[y], d = aLast;
				aRes[p] = u > 0 && d > 0 ? Math.min(u, d) : Math.max(u, d);
			}
		}
		return aRes;
	}

	static float[] toRGB(int[] aPix) {
		float[] aRes = new float[3*aPix.length];
		for(int p = 0;p < aPix.length;p++) {
			int c = aPix[p];
			aRes[3*p] = ((c>>16)&0xFF)/255f;
			aRes[3*p+1] = ((c>>8)&0xFF)/255f;
			aRes[3*p+2] = (c&0xFF)/255f;
		}
		return aRes;
	}

	/**
	 * Depths (1/z) of the view with its holes (aTrans > HOLE, or no depth) given that of the background: on each line,
	 * the farther of the nearest known pixels on the left and on the right (a hole uncovered by a move is what the
	 * nearer object hid); 0 when the line has none
	 */
	public static float[] backgroundDepth(float[] aZ,float[] aTrans,int aW,int aH) {
		float[] aRes = aZ.clone();
		float[] aLeft = new float[aW];
		for(int y = 0;y < aH;y++) {
			int aRow = y*aW;
			float aLast = 0;
			for(int x = 0;x < aW;x++) {
				int p = aRow+x;
				if(aZ[p] > 0 && aTrans[p] <= HOLE) {
					aLast = aZ[p];
				}
				aLeft[x] = aLast;
			}
			aLast = 0;
			for(int x = aW-1;x >= 0;x--) {
				int p = aRow+x;
				if(aZ[p] > 0 && aTrans[p] <= HOLE) {
					aLast = aZ[p];
					continue;
				}
				float l = aLeft[x], r = aLast;
				aRes[p] = l > 0 && r > 0 ? Math.min(l, r) : Math.max(l, r);
			}
		}
		return aRes;
	}

	/**
	 * Forward warp of the image aRGB (depths aZ = 1/z, 0: none) into the other eye of parallel cameras: x' = x + aDc -
	 * aFB * z; two neighbors of close depths (EDGE) cover the span between their places (no crack), the nearest wins.
	 * aOut / aOutZ: colors and 1/z (0: nothing there)
	 */
	static void warp(float[] aRGB,float[] aZ,int aW,int aH,double aDc,double aFB,float[] aOut,float[] aOutZ) {
		for(int y = 0;y < aH;y++) {
			int aRow = y*aW;
			for(int x = 0;x < aW;x++) {
				int p = aRow+x;
				float z = aZ[p];
				if(z <= 0) {
					continue;
				}
				double xa = x+aDc-aFB*z;
				double xb = xa+1;
				if(x+1 < aW) {
					float z2 = aZ[p+1];
					if(z2 > 0 && Math.max(z, z2) <= GaussianScene.EDGE*Math.min(z, z2)) {
						double x2 = x+1+aDc-aFB*z2;
						if(x2 > xa) {
							xb = Math.min(x2, xa+4);
						}
					}
				}
				int i0 = (int)Math.round(xa), i1 = Math.max(i0+1, (int)Math.round(xb));
				for(int i = Math.max(0, i0);i < Math.min(aW, i1);i++) {
					int q = aRow+i;
					if(z > aOutZ[q]) {
						aOutZ[q] = z;
						System.arraycopy(aRGB, 3*p, aOut, 3*q, 3);
					}
				}
			}
		}
	}

	static void writeRGB(float[] aRGB,int aW,int aH,java.io.File aFile) throws Exception {
		BufferedImage aBI = new BufferedImage(aW, aH, BufferedImage.TYPE_INT_RGB);
		int[] aIP = ((DataBufferInt)aBI.getRaster().getDataBuffer()).getData();
		for(int p = 0;p < aW*aH;p++) {
			aIP[p] = (clamp(aRGB[3*p]*255)<<16)|(clamp(aRGB[3*p+1]*255)<<8)|clamp(aRGB[3*p+2]*255);
		}
		java.io.File aTmp = new java.io.File(aFile.getParentFile(), "fill_"+aFile.getName());
		javax.imageio.ImageIO.write(aBI, "png", aTmp);
		java.nio.file.Files.move(aTmp.toPath(), aFile.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
	}

	/**
	 * Size of the view given to SDXL: about FillGenerator.PIXELS at the ratio aW / aH, sides multiple of 8
	 */
	static int[] sdxlSize(int aW,int aH) {
		//From the ratio rounded to the hundredth: the same size (the same result) for every processing mode
		double aR = Math.max(0.1, Math.min(10, Math.round(100.0*aW/aH)/100.0));
		double aH0 = Math.sqrt(com.cubaix.TDenlive.ai.FillGenerator.PIXELS/aR);
		return new int[] {Math.max(64, (int)Math.round(aH0*aR/8)*8), Math.max(64, (int)Math.round(aH0/8)*8)};
	}

	/**
	 * The holes of aRGB (aW x aH) take the colors of the image aSrc (aSW x aSH, 3 floats per pixel) enlarged bilinearly
	 */
	static void sample(float[] aSrc,int aSW,int aSH,float[] aRGB,boolean[] aHoles,int aW,int aH) {
		double aFX = aSW/(double)aW, aFY = aSH/(double)aH;
		for(int y = 0;y < aH;y++) {
			double sy = Math.max(0, Math.min(aSH-1, (y+0.5)*aFY-0.5));
			int y0 = (int)sy, y1 = Math.min(aSH-1, y0+1);
			double fy = sy-y0;
			for(int x = 0;x < aW;x++) {
				int p = y*aW+x;
				if(!aHoles[p]) {
					continue;
				}
				double sx = Math.max(0, Math.min(aSW-1, (x+0.5)*aFX-0.5));
				int x0 = (int)sx, x1 = Math.min(aSW-1, x0+1);
				double fx = sx-x0;
				for(int c = 0;c < 3;c++) {
					aRGB[3*p+c] = (float)((aSrc[3*(y0*aSW+x0)+c]*(1-fx)+aSrc[3*(y0*aSW+x1)+c]*fx)*(1-fy)
							+(aSrc[3*(y1*aSW+x0)+c]*(1-fx)+aSrc[3*(y1*aSW+x1)+c]*fx)*fy);
				}
			}
		}
	}

	/**
	 * The view of one eye at aW x aH (the size given to SDXL) filled by SDXL: the whole image it made (3 floats per
	 * pixel, 0..1), from the memory or disk caches, else computed (one at a time)
	 * @return null when there is no hole
	 */
	static float[] sdxlFill(GaussianScene aScene,StereoCam aCam,int aEye,int aW,int aH,boolean aMesh,boolean aDepth,double aThreshold,String aTdeDir) throws Exception {
		String aId = sdxlId(aScene, aCam, aEye, aW, aH, aMesh, aDepth, aThreshold);
		synchronized(SDXL_LOCK) {
			synchronized(SDXL_CACHE) {
				java.lang.ref.SoftReference<float[]> aR = SDXL_CACHE.get(aId);
				float[] aC = aR == null ? null : aR.get();
				if(aC != null) {
					return aC;
				}
			}
			java.io.File aDir = new java.io.File(new java.io.File(aTdeDir, "Cache"), "SmartFill");
			java.io.File aFile = new java.io.File(aDir, hash(aId)+".png");
			float[] aRes = null;
			if(aScene.fileKey != null && aFile.isFile()) {
				aRes = readRGB(aFile, aW, aH);
				if(aRes != null) {
					aFile.setLastModified(System.currentTimeMillis());
				}
			}
			if(aRes == null) {
				aRes = sdxlCompute(aScene, aCam, aEye, aW, aH, aMesh, aDepth, aThreshold, aTdeDir, aDir, aFile);
				if(aRes == null) {
					return null;
				}
			}
			synchronized(SDXL_CACHE) {
				SDXL_CACHE.put(aId, new java.lang.ref.SoftReference<float[]>(aRes));
				while(SDXL_CACHE.size() > CACHE_MAX) {
					SDXL_CACHE.remove(SDXL_CACHE.keySet().iterator().next());
				}
			}
			return aRes;
		}
	}

	static float[] sdxlCompute(GaussianScene aScene,StereoCam aCam,int aEye,int aW,int aH,boolean aMesh,boolean aDepth,double aThreshold,String aTdeDir,java.io.File aDir,java.io.File aFile) throws Exception {
		int n = aW*aH;
		int[] aPix = new int[n];
		float[] aTrans = new float[n];
		GaussianScene.View aV = aCam.view(aScene, aEye, aW, aH);
		aV.meshFill = aMesh;
		aV.depthView = aDepth;
		aV.cosCut = aThreshold;
		aScene.render(aV, aW, aH, aPix, aTrans);
		//The same seed for both eyes (closer contents), from the scene
		int aSeed = (aScene.fileKey != null ? aScene.fileKey.hashCode() : aScene.n) & 0x7FFFFFFF;
		float[] aRes = sdxlImage(aPix, aTrans, aW, aH, aSeed, aTdeDir);
		if(aRes != null && aScene.fileKey != null) {
			aDir.mkdirs();
			writeRGB(aRes, aW, aH, aFile);
			pruneDisk(aDir);
		}
		return aRes;
	}

	/**
	 * The image made by SDXL from the view aPix / aTrans (its holes grown, pre-filled by a diffusion of the colors)
	 * @return 3 floats per pixel 0..1, null when there is no hole
	 */
	static float[] sdxlImage(int[] aPix,float[] aTrans,int aW,int aH,int aSeed,String aTdeDir) throws Exception {
		int n = aW*aH;
		boolean[] aCore = new boolean[n];
		boolean aAny = false;
		for(int p = 0;p < n;p++) {
			if(aTrans[p] > HOLE) {
				aCore[p] = true;
				aAny = true;
			}
		}
		if(!aAny) {
			return null;
		}
		//The holes grown as in fill() (plus the latents of 8 x 8 pixels of the model)
		int aGrow = Math.max(2, Math.round(aH/360f));
		int[] aDist = distance(aCore, aW, aH);
		boolean[] aHoles = new boolean[n];
		float[] aRGB = new float[3*n];
		float[] aWt = new float[n];
		for(int p = 0;p < n;p++) {
			aHoles[p] = aDist[p] <= 3*(aGrow+4);
			if(aHoles[p]) {
				continue;
			}
			float a = Math.max(1e-3f, 1-aTrans[p]);
			int c = aPix[p];
			aRGB[3*p] = Math.min(1f, ((c>>16)&0xFF)/255f/a);
			aRGB[3*p+1] = Math.min(1f, ((c>>8)&0xFF)/255f/a);
			aRGB[3*p+2] = Math.min(1f, (c&0xFF)/255f/a);
			aWt[p] = 1;
		}
		//The holes pre-filled by a diffusion of the colors around (the model starts from the image noised)
		pushPull(aRGB, aWt, aW, aH);
		java.io.File aDir = new java.io.File(new java.io.File(aTdeDir, "Cache"), "SmartFill");
		aDir.mkdirs();
		java.io.File aIn = java.io.File.createTempFile("fill_in_", ".png", aDir);
		java.io.File aMask = java.io.File.createTempFile("fill_mask_", ".png", aDir);
		java.io.File aOut = java.io.File.createTempFile("fill_out_", ".png", aDir);
		try {
			BufferedImage aBI = new BufferedImage(aW, aH, BufferedImage.TYPE_INT_RGB);
			BufferedImage aBM = new BufferedImage(aW, aH, BufferedImage.TYPE_BYTE_GRAY);
			int[] aIP = ((DataBufferInt)aBI.getRaster().getDataBuffer()).getData();
			byte[] aMP = ((java.awt.image.DataBufferByte)aBM.getRaster().getDataBuffer()).getData();
			for(int p = 0;p < n;p++) {
				aIP[p] = (clamp(aRGB[3*p]*255)<<16)|(clamp(aRGB[3*p+1]*255)<<8)|clamp(aRGB[3*p+2]*255);
				aMP[p] = (byte)(aHoles[p] ? 255 : 0);
			}
			javax.imageio.ImageIO.write(aBI, "png", aIn);
			javax.imageio.ImageIO.write(aBM, "png", aMask);
			long aT0 = System.currentTimeMillis();
			com.cubaix.TDenlive.ai.FillGenerator aG = new com.cubaix.TDenlive.ai.FillGenerator(aTdeDir, null);
			if(!aG.fill(aIn, aMask, aOut, aSeed, SDXL_STEPS)) {
				throw new Exception("SDXL: canceled");
			}
			System.out.println("Smart filling SDXL: "+aW+"x"+aH+" in "+(System.currentTimeMillis()-aT0)+" ms");
			float[] aRes = readRGB(aOut, aW, aH);
			if(aRes == null) {
				throw new Exception("SDXL: unreadable image "+aOut);
			}
			return aRes;
		}
		finally {
			aIn.delete();
			aMask.delete();
			aOut.delete();
		}
	}

	/** Colors of a PNG of aW x aH, 3 floats per pixel (null if unreadable or of another size) */
	static float[] readRGB(java.io.File aFile,int aW,int aH) {
		try {
			BufferedImage aBI = javax.imageio.ImageIO.read(aFile);
			if(aBI == null || aBI.getWidth() != aW || aBI.getHeight() != aH) {
				return null;
			}
			int[] aP = aBI.getRGB(0, 0, aW, aH, null, 0, aW);
			float[] aRes = new float[3*aW*aH];
			for(int p = 0;p < aP.length;p++) {
				int c = aP[p];
				aRes[3*p] = ((c>>16)&0xFF)/255f;
				aRes[3*p+1] = ((c>>8)&0xFF)/255f;
				aRes[3*p+2] = (c&0xFF)/255f;
			}
			return aRes;
		}
		catch(Throwable t) {
			t.printStackTrace(System.err);
			return null;
		}
	}

	/** At most DISK_MAX images in the cache folder: the oldest used removed */
	static void pruneDisk(java.io.File aDir) {
		java.io.File[] aFs = aDir.listFiles((d, aN) -> aN.endsWith(".png") && !aN.startsWith("fill_"));
		if(aFs == null || aFs.length <= DISK_MAX) {
			return;
		}
		java.util.Arrays.sort(aFs, (a, b) -> Long.compare(a.lastModified(), b.lastModified()));
		for(int i = 0;i < aFs.length-DISK_MAX;i++) {
			aFs[i].delete();
		}
	}

	static String hash(String aS) {
		try {
			byte[] aD = java.security.MessageDigest.getInstance("SHA-256").digest(aS.getBytes("UTF-8"));
			StringBuilder aSB = new StringBuilder();
			for(int i = 0;i < 16;i++) {
				aSB.append(String.format("%02x", aD[i]));
			}
			return aSB.toString();
		}
		catch(Exception e) {
			return Integer.toHexString(aS.hashCode());
		}
	}

	/**
	 * The holes of aRGB filled by the model: its colors near the known pixels, a smooth diffusion farther
	 * (MODEL_REACH)
	 */
	static void limitReach(float[] aRGB,boolean[] aHoles,int aW,int aH) {
		int n = aW*aH;
		//Chamfer distance (3-4) to the known pixels, in pixels x 3
		int[] aD = new int[n];
		final int INF = Integer.MAX_VALUE/4;
		boolean aFar = false;
		for(int p = 0;p < n;p++) {
			aD[p] = aHoles[p] ? INF : 0;
		}
		for(int y = 0;y < aH;y++) {
			for(int x = 0;x < aW;x++) {
				int p = y*aW+x;
				int d = aD[p];
				if(d == 0) {
					continue;
				}
				if(x > 0) d = Math.min(d, aD[p-1]+3);
				if(y > 0) {
					d = Math.min(d, aD[p-aW]+3);
					if(x > 0) d = Math.min(d, aD[p-aW-1]+4);
					if(x < aW-1) d = Math.min(d, aD[p-aW+1]+4);
				}
				aD[p] = d;
			}
		}
		for(int y = aH-1;y >= 0;y--) {
			for(int x = aW-1;x >= 0;x--) {
				int p = y*aW+x;
				int d = aD[p];
				if(d == 0) {
					continue;
				}
				if(x < aW-1) d = Math.min(d, aD[p+1]+3);
				if(y < aH-1) {
					d = Math.min(d, aD[p+aW]+3);
					if(x < aW-1) d = Math.min(d, aD[p+aW+1]+4);
					if(x > 0) d = Math.min(d, aD[p+aW-1]+4);
				}
				aD[p] = d;
				if(d > 3*MODEL_REACH/2) {
					aFar = true;
				}
			}
		}
		if(!aFar) {
			return;
		}
		//Diffusion from the known pixels and the colors of the model near them
		float[] aC = aRGB.clone();
		float[] aWt = new float[n];
		for(int p = 0;p < n;p++) {
			aWt[p] = aD[p] <= 3*MODEL_REACH/2 ? 1 : 0;
		}
		pushPull(aC, aWt, aW, aH);
		for(int p = 0;p < n;p++) {
			if(!aHoles[p]) {
				continue;
			}
			float t = (aD[p]/3f-MODEL_REACH/2f)/(MODEL_REACH/2f);
			if(t <= 0) {
				continue;
			}
			t = t >= 1 ? 1 : t*t*(3-2*t);
			for(int c = 0;c < 3;c++) {
				aRGB[3*p+c] = aRGB[3*p+c]*(1-t)+aC[3*p+c]*t;
			}
		}
	}

	/**
	 * Push-pull: the pixels of weight < 1 completed from a pyramid of the weighted colors (halved each level, enlarged
	 * bilinearly): a smooth continuation of the colors around, without any pattern
	 */
	public static void pushPull(float[] aRGB,float[] aWt,int aW,int aH) {
		if(aW <= 1 && aH <= 1) {
			return;
		}
		int aCW = (aW+1)/2, aCH = (aH+1)/2;
		float[] aCRGB = new float[3*aCW*aCH];
		float[] aCWt = new float[aCW*aCH];
		for(int y = 0;y < aH;y++) {
			for(int x = 0;x < aW;x++) {
				int p = y*aW+x, q = (y/2)*aCW+x/2;
				float w = aWt[p];
				if(w <= 0) {
					continue;
				}
				aCRGB[3*q] += aRGB[3*p]*w;
				aCRGB[3*q+1] += aRGB[3*p+1]*w;
				aCRGB[3*q+2] += aRGB[3*p+2]*w;
				aCWt[q] += w;
			}
		}
		for(int q = 0;q < aCW*aCH;q++) {
			float w = aCWt[q];
			if(w > 0) {
				aCRGB[3*q] /= w;
				aCRGB[3*q+1] /= w;
				aCRGB[3*q+2] /= w;
				aCWt[q] = Math.min(1f, w);
			}
		}
		pushPull(aCRGB, aCWt, aCW, aCH);
		for(int y = 0;y < aH;y++) {
			double sy = Math.max(0, Math.min(aCH-1, (y+0.5)/2-0.5));
			int y0 = (int)sy, y1 = Math.min(aCH-1, y0+1);
			double fy = sy-y0;
			for(int x = 0;x < aW;x++) {
				int p = y*aW+x;
				float w = aWt[p];
				if(w >= 1) {
					continue;
				}
				double sx = Math.max(0, Math.min(aCW-1, (x+0.5)/2-0.5));
				int x0 = (int)sx, x1 = Math.min(aCW-1, x0+1);
				double fx = sx-x0;
				for(int c = 0;c < 3;c++) {
					double v = (aCRGB[3*(y0*aCW+x0)+c]*(1-fx)+aCRGB[3*(y0*aCW+x1)+c]*fx)*(1-fy)
							+(aCRGB[3*(y1*aCW+x0)+c]*(1-fx)+aCRGB[3*(y1*aCW+x1)+c]*fx)*fy;
					aRGB[3*p+c] = (float)(aRGB[3*p+c]*w+v*(1-w));
				}
				aWt[p] = 1;
			}
		}
	}

	/**
	 * Copy of aRGB whose known pixels are blurred (box of radius aR, twice, horizontally then vertically, from the known
	 * pixels only), the holes left as they are
	 */
	static float[] blurContext(float[] aRGB,boolean[] aHoles,int aW,int aH,int aR) {
		float[] aCur = aRGB.clone();
		float[] aTmp = new float[aRGB.length];
		for(int aPass = 0;aPass < 2;aPass++) {
			for(int aDir = 0;aDir < 2;aDir++) {
				boolean aH0 = aDir == 0;
				int aLines = aH0 ? aH : aW, aLen = aH0 ? aW : aH;
				//Each line (column) on its own, read from aCur and written into aTmp: on all the cores
				final float[] aIn0 = aCur, aOut0 = aTmp;
				com.cubaix.TDenlive.utils.Par.rows(aLines, (aL0, aL1) -> {
				for(int l = aL0;l < aL1;l++) {
					float r = 0, g = 0, b = 0;
					int aN = 0;
					//Sliding window over [i-aR, i+aR]
					for(int i = -aR;i < aLen+aR;i++) {
						int aIn = i+aR, aOut = i-aR-1;
						if(aIn >= 0 && aIn < aLen) {
							int p = aH0 ? l*aW+aIn : aIn*aW+l;
							if(!aHoles[p]) {
								r += aIn0[3*p]; g += aIn0[3*p+1]; b += aIn0[3*p+2];
								aN++;
							}
						}
						if(aOut >= 0 && aOut < aLen) {
							int p = aH0 ? l*aW+aOut : aOut*aW+l;
							if(!aHoles[p]) {
								r -= aIn0[3*p]; g -= aIn0[3*p+1]; b -= aIn0[3*p+2];
								aN--;
							}
						}
						if(i >= 0 && i < aLen) {
							int p = aH0 ? l*aW+i : i*aW+l;
							if(aHoles[p] || aN <= 0) {
								System.arraycopy(aIn0, 3*p, aOut0, 3*p, 3);
							}
							else {
								aOut0[3*p] = r/aN; aOut0[3*p+1] = g/aN; aOut0[3*p+2] = b/aN;
							}
						}
					}
				}
				});
				float[] aSwap = aCur;
				aCur = aTmp;
				aTmp = aSwap;
			}
		}
		return aCur;
	}

	static int clamp(float aV) {
		int v = Math.round(aV);
		return v < 0 ? 0 : v > 255 ? 255 : v;
	}
}
