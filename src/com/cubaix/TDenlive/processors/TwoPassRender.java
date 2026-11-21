package com.cubaix.TDenlive.processors;

import java.awt.image.BufferedImage;
import java.io.File;
import java.util.ArrayList;
import java.util.function.BooleanSupplier;

import com.cubaix.TDenlive.TDConfig;
import com.cubaix.TDenlive.TDenlive;
import com.cubaix.TDenlive.model3d.SmartFill;

/**
 * Rendering in two passes (rendering window, clip of a time range), only when a 3D scene is filled by SDXL with the
 * F/F+1 carry-over (carriesFilling(), else the output alone): first the keyframes, KEY_FPS images per second from
 * the start, of which only the memory of the filling of the 3D scenes is kept (SmartFill.savePrevSince(), in a
 * temporary folder of the WorkCache); then the images at the output rate, all computed: at the first image whose time
 * is not before that of a keyframe, the memory of that keyframe is made the latest one (SmartFill.restorePrev()) just
 * before it is computed, so that the F/F+1 carry-over of the filling starts from the keyframes (no drift accumulated
 * from one output image to the next).
 */
public class TwoPassRender {
	public static final double KEY_FPS = 10;

	/** KEY_FPS for the texts (%FPS% of Renderer.KeyPass) */
	public static String keyFpsText() {
		return KEY_FPS == Math.rint(KEY_FPS) ? ""+(long)KEY_FPS : ""+KEY_FPS;
	}

	/** Progress of the rendering (rendering thread) */
	public interface Listener {
		/**
		 * An image made: aPass 1 (keyframes) or 2 (output), aIndex 0.. of aTotal of that pass, at aTimeMS; aKey: a
		 * keyframe given again (pass 2)
		 */
		void frame(int aPass,int aIndex,int aTotal,long aTimeMS,BufferedImage aL,BufferedImage aR,boolean aKey);
	}

	/** Time of the image aF of a rendering at aFps from aStartMS (that of the renderings) */
	public static long timeOf(long aStartMS,double aFps,int aF) {
		return aStartMS+(long)(1000*aF/aFps);
	}

	/** Number of images of a rendering at aFps from aStartMS up to aEndMS (at least one) */
	public static int nbFrames(long aStartMS,long aEndMS,double aFps) {
		int n = 1;
		while(timeOf(aStartMS, aFps, n) < aEndMS) {
			n++;
		}
		return n;
	}

	/**
	 * Render from aStartMS to aEndMS at aFps into aRenderer, while aGoOn
	 */
	public static void run(TDenlive aTDe,Renderer aRenderer,long aStartMS,long aEndMS,double aFps,BooleanSupplier aGoOn,Listener aListener) {
		//Without filling of 3D scenes carried over from image to image, the output only
		int aNbKeys = carriesFilling(aTDe, aStartMS, aEndMS) ? nbFrames(aStartMS, aEndMS, KEY_FPS) : 0;
		int aNb = nbFrames(aStartMS, aEndMS, aFps);
		File aDir = new File(aTDe.getWorkCacheDir(), "RenderKeys_"+System.currentTimeMillis());
		aDir.mkdirs();
		ArrayList<File> aKeys = new ArrayList<File>();
		try {
			//== Pass 1: the keyframes, the memory of their filling kept ==
			for(int k = 0;k < aNbKeys && aGoOn.getAsBoolean();k++) {
				long aT = timeOf(aStartMS, KEY_FPS, k);
				Object aMark = SmartFill.prevMark();
				BufferedImage[] aPair = make(aTDe, aT);
				File aF = new File(aDir, k+"_fill.bin");
				try {
					if(SmartFill.savePrevSince(aMark, aF) <= 0) {
						aF.delete();
					}
				}
				catch(Throwable t) {
					t.printStackTrace(System.err);
					aF.delete();
				}
				aKeys.add(aF);
				aListener.frame(1, k, aNbKeys, aT, aPair[0], aPair[1], false);
			}
			//== Pass 2: the output, the filling carried over from the keyframes ==
			int aNext = 0;
			for(int f = 0;f < aNb && aGoOn.getAsBoolean();f++) {
				long aT = timeOf(aStartMS, aFps, f);
				//The latest keyframe not after this image, not used yet
				int aKey = -1;
				while(aNext < aKeys.size() && timeOf(aStartMS, KEY_FPS, aNext) <= aT) {
					aKey = aNext++;
				}
				boolean aRestored = false;
				if(aKey >= 0 && aKeys.get(aKey).exists()) {
					try {
						SmartFill.restorePrev(aKeys.get(aKey));
						aRestored = true;
					}
					catch(Throwable t) {
						t.printStackTrace(System.err);
					}
					aKeys.get(aKey).delete();
				}
				BufferedImage[] aPair = make(aTDe, aT);
				aRenderer.addPair(aPair[0], aPair[1]);
				aListener.frame(2, f, aNb, aT, aPair[0], aPair[1], aRestored);
			}
		}
		finally {
			File[] aFs = aDir.listFiles();
			if(aFs != null) {
				for(File aF : aFs) {
					aF.delete();
				}
			}
			aDir.delete();
		}
	}

	/**
	 * Whether a clip rendered between aStartMS and aEndMS (active line, the solo clips only when set) has an active
	 * processor of 3D scene filling its holes with the F/F+1 carry-over (SceneCamKeys: smartFill and prevCarry): the
	 * only case where the keyframes change the result
	 */
	public static boolean carriesFilling(TDenlive aTDe,long aStartMS,long aEndMS) {
		java.util.Set<com.cubaix.TDenlive.medias.Clip> aSolo = aTDe.timeLineStack.soloClips;
		for(com.cubaix.TDenlive.medias.VideoTimeLine aTL : new java.util.Vector<com.cubaix.TDenlive.medias.VideoTimeLine>(aTDe.timeLineStack.videoTimeLineStack)) {
			if(!aTL.isActive) {
				continue;
			}
			for(com.cubaix.TDenlive.medias.Clip aC : new java.util.Vector<com.cubaix.TDenlive.medias.Clip>(aTL.clipList)) {
				if((aSolo != null && !aSolo.contains(aC)) || aC.getStartTimeMS() >= aEndMS
						|| aC.getStartTimeMS()+aC.getDurationMS() <= aStartMS) {
					continue;
				}
				for(Processor aP : aC.getProcessors()) {
					if(aP.isActive && aP instanceof SceneCamKeys && ((SceneCamKeys)aP).smartFill && ((SceneCamKeys)aP).prevCarry
							&& SmartFill.modelById(((SceneCamKeys)aP).fillModel) == SmartFill.Model.SDXL) {
						return true;
					}
				}
			}
		}
		return false;
	}

	/** The image at aT rendered */
	static BufferedImage[] make(TDenlive aTDe,long aT) {
		aTDe.timeLineStack.setTimePosMS(aT);
		aTDe.timeLineStack.setRequestedTimePosMS(aT);
		aTDe.timeLineStack.reBuild(TDConfig.PROCESSING_MODE_RENDER, aT);
		return new BufferedImage[] {aTDe.timeLineStack.getFinalLeft(TDConfig.PROCESSING_MODE_RENDER, aT)
				, aTDe.timeLineStack.getFinalRight(TDConfig.PROCESSING_MODE_RENDER, aT)};
	}
}
