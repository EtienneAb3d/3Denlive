package com.cubaix.TDenlive.model3d;

import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;

import com.cubaix.TDenlive.medias.ColorBck;

/**
 * Texture of the objects of the 3D models: an image of the texture library or of the disk
 * (ColorBck.Fill.bitmap()), with its mipmaps (halved levels, box filtered), sampled bilinearly
 * and repeated. The procedural textures are not sampled here: ColorBck.Fill.mix() gives them
 * between the two colors of the object.
 */
public class Texture3D {
	int[][] levels;
	int[] lw, lh;

	static final java.util.HashMap<String, java.lang.ref.SoftReference<Texture3D>> CACHE = new java.util.HashMap<String, java.lang.ref.SoftReference<Texture3D>>();

	//Prefix of a media of the media list as texture: "media:<UID>" (still image or video, 2D or stereo:
	//each eye takes its own view, the center eye the left one; a video at the media time of the model, looped)
	public static final String MEDIA = "media:";
	//Largest side of a texture made from a media (halved down to it)
	static final int MEDIA_MAX_SIDE = 2048;
	//Same for the monitor (all the modes but RENDER): rendered at the VHS height (Model3D.renderHeight())
	static final int MEDIA_PREVIEW_MAX_SIDE = 512;

	/**
	 * Images of the medias of the media list (set by the application)
	 */
	public interface MediaSource {
		/**
		 * Both views {left, right} of the media aUID at the media time aTimeMS in the processing mode aMode, null if
		 * missing (not loaded yet, removed)
		 */
		BufferedImage[] views(long aUID, int aMode, long aTimeMS);

		/**
		 * Same for the stream aStream: the images of one sequence of times (a loop range of a face), read by a
		 * reader of their own outside RENDER, so that two faces showing the same video at different times don't
		 * make one reader seek back and forth at each image
		 */
		default BufferedImage[] views(long aUID, int aMode, long aTimeMS, String aStream) {
			return views(aUID, aMode, aTimeMS);
		}

		/**
		 * True if its image changes with the time (a video)
		 */
		boolean isMoving(long aUID);

		/**
		 * Duration of the media in ms, 0 for a still image or a missing media
		 */
		long durationMS(long aUID);

		/**
		 * Name shown, null if missing
		 */
		String name(long aUID);
	}

	public static MediaSource medias = null;

	/**
	 * Textures of the medias: last frame per media, mode and stream (loop range: two faces of one video at
	 * different times keep their own frame)
	 */
	static class MediaFrame {
		long time;
		BufferedImage srcL, srcR;
		Texture3D left, right;
	}

	static final java.util.HashMap<String, MediaFrame> FRAMES = new java.util.HashMap<String, MediaFrame>();

	/**
	 * An image or a video of the user (media of the media list, image of the disk): fitted whole with its ratio,
	 * centered, not repeated over the face (Renderer3D.TexCtx.picture); the images of the library are tiles
	 */
	public static boolean isPicture(String aTexture) {
		return isMedia(aTexture) || (aTexture != null && ColorBck.Fill.isFile(aTexture));
	}

	public static boolean isMedia(String aTexture) {
		return aTexture != null && aTexture.startsWith(MEDIA);
	}

	/**
	 * UID of a media texture, -1 if not one
	 */
	public static long mediaUID(String aTexture) {
		if(!isMedia(aTexture)) {
			return -1;
		}
		try {
			return Long.parseLong(aTexture.substring(MEDIA.length()).trim());
		}
		catch(Throwable t) {
			return -1;
		}
	}

	/**
	 * True if aTexture is a media whose image changes with the time
	 */
	public static boolean isMoving(String aTexture) {
		long aUID = mediaUID(aTexture);
		return aUID >= 0 && medias != null && medias.isMoving(aUID);
	}

	/**
	 * Duration of the media of a media texture in ms, 0 for a still image or a missing media
	 */
	public static long durationMS(String aTexture) {
		long aUID = mediaUID(aTexture);
		return aUID >= 0 && medias != null && medias.isMoving(aUID) ? Math.max(0, medias.durationMS(aUID)) : 0;
	}

	/**
	 * Media time of a video looped over [aStartMS, aEndMS[ (aEndMS < 0 = its end) at the time aTimeMS of the model,
	 * aDurMS its duration (0 unknown: the time as it is, looped by the media source)
	 */
	public static long loopTime(long aTimeMS, long aStartMS, long aEndMS, long aDurMS) {
		long aT = Math.max(0, aTimeMS);
		if(aDurMS <= 0) {
			return aStartMS > 0 ? aStartMS + aT : aT;
		}
		long aS = Math.max(0, Math.min(aStartMS, aDurMS-1));
		long aE = aEndMS < 0 ? aDurMS : Math.max(aS+1, Math.min(aEndMS, aDurMS));
		return aS + aT % (aE - aS);
	}

	/**
	 * Texture of a media for the eye aEye (-1 left, 0 center = left, 1 right) at the media time aTimeMS, null if
	 * missing
	 */
	public static Texture3D ofMedia(String aTexture, int aEye, int aMode, long aTimeMS) {
		return ofMedia(aTexture, aEye, aMode, aTimeMS, 0, -2);
	}

	/**
	 * Same, a video looped over its range [aStartMS, aEndMS[ (aEndMS < 0 = its end)
	 */
	public static Texture3D ofMedia(String aTexture, int aEye, int aMode, long aTimeMS, long aStartMS, long aEndMS) {
		long aUID = mediaUID(aTexture);
		if(aUID < 0 || medias == null) {
			return null;
		}
		boolean aMoving = medias.isMoving(aUID);
		long aT = aMoving ? loopTime(aTimeMS, aStartMS, aEndMS, medias.durationMS(aUID)) : 0;
		//One stream per loop range (the time of the preview of a range: its own one)
		String aStream = aStartMS + ";" + aEndMS;
		String aKey = aUID + "|" + aMode + "|" + aStream;
		MediaFrame aF;
		synchronized(FRAMES) {
			aF = FRAMES.get(aKey);
			if(aF == null) {
				aF = new MediaFrame();
				aF.time = Long.MIN_VALUE;
				FRAMES.put(aKey, aF);
			}
		}
		synchronized(aF) {
			if(!aMoving || aF.time != aT || aF.left == null) {
				BufferedImage[] aVs = medias.views(aUID, aMode, aT, aStream);
				if(aVs == null || aVs[0] == null) {
					return null;
				}
				BufferedImage aR = aVs.length > 1 && aVs[1] != null ? aVs[1] : aVs[0];
				//A still image: the same images, the same textures
				int aMax = aMode == com.cubaix.TDenlive.TDConfig.PROCESSING_MODE_RENDER ? MEDIA_MAX_SIDE : MEDIA_PREVIEW_MAX_SIDE;
				if(aVs[0] != aF.srcL || aF.left == null) {
					aF.left = fromView(aVs[0], aMax);
				}
				if(aR != aF.srcR || aF.right == null) {
					aF.right = aR == aVs[0] ? aF.left : fromView(aR, aMax);
				}
				aF.srcL = aVs[0];
				aF.srcR = aR;
				aF.time = aT;
			}
			return aEye > 0 ? aF.right : aF.left;
		}
	}

	/**
	 * Opaque texture of a view of a media (8 bits or HDR), halved down to aMaxSide at most
	 */
	static Texture3D fromView(BufferedImage aBI, int aMaxSide) {
		int aW = aBI.getWidth(), aH = aBI.getHeight();
		int[] aPix = new int[aW*aH];
		java.awt.image.DataBuffer aDB = aBI.getRaster().getDataBuffer();
		if(aDB instanceof java.awt.image.DataBufferFloat) {
			//HDR: RGBA floats 0..1 (ImageUtils.createHdr())
			float[] aF = ((java.awt.image.DataBufferFloat)aDB).getData();
			for(int p = 0;p < aPix.length;p++) {
				int aR = Math.max(0, Math.min(255, (int)(aF[p*4]*255 + 0.5)));
				int aG = Math.max(0, Math.min(255, (int)(aF[p*4+1]*255 + 0.5)));
				int aB = Math.max(0, Math.min(255, (int)(aF[p*4+2]*255 + 0.5)));
				aPix[p] = 0xFF000000 | (aR<<16) | (aG<<8) | aB;
			}
		}
		else {
			aBI.getRGB(0, 0, aW, aH, aPix, 0, aW);
			for(int p = 0;p < aPix.length;p++) {
				aPix[p] |= 0xFF000000;
			}
		}
		//Halved down to the largest side
		while(aW > aMaxSide || aH > aMaxSide) {
			int aNW = Math.max(1, aW/2), aNH = Math.max(1, aH/2);
			int[] aN = new int[aNW*aNH];
			for(int y = 0;y < aNH;y++) {
				for(int x = 0;x < aNW;x++) {
					int aA = aPix[(y*2)*aW+x*2], aB = aPix[(y*2)*aW+Math.min(aW-1, x*2+1)];
					int aC = aPix[Math.min(aH-1, y*2+1)*aW+x*2], aD = aPix[Math.min(aH-1, y*2+1)*aW+Math.min(aW-1, x*2+1)];
					int aR = (((aA>>16)&0xFF)+((aB>>16)&0xFF)+((aC>>16)&0xFF)+((aD>>16)&0xFF)+2)>>2;
					int aG = (((aA>>8)&0xFF)+((aB>>8)&0xFF)+((aC>>8)&0xFF)+((aD>>8)&0xFF)+2)>>2;
					int aBl = ((aA&0xFF)+(aB&0xFF)+(aC&0xFF)+(aD&0xFF)+2)>>2;
					aN[y*aNW+x] = 0xFF000000|(aR<<16)|(aG<<8)|aBl;
				}
			}
			aPix = aN;
			aW = aNW;
			aH = aNH;
		}
		BufferedImage aOut = new BufferedImage(aW, aH, BufferedImage.TYPE_INT_ARGB);
		int[] aD = ((DataBufferInt)aOut.getRaster().getDataBuffer()).getData();
		System.arraycopy(aPix, 0, aD, 0, aPix.length);
		return new Texture3D(aOut);
	}

	/**
	 * Texture of an image id ("bmp:..." / "file:..."), shared; null if it can't be read
	 */
	public static synchronized Texture3D get(String aTexture) {
		java.lang.ref.SoftReference<Texture3D> aRef = CACHE.get(aTexture);
		Texture3D aT = aRef != null ? aRef.get() : null;
		if(aT == null) {
			BufferedImage aBI = ColorBck.Fill.bitmap(aTexture);
			if(aBI == null) {
				return null;
			}
			aT = new Texture3D(aBI);
			CACHE.put(aTexture, new java.lang.ref.SoftReference<Texture3D>(aT));
		}
		return aT;
	}

	/**
	 * Texture of an image of an object of the library, alpha kept (opaque image: alpha 255), not shared
	 */
	public static Texture3D of(BufferedImage aBI) {
		int aW = aBI.getWidth(), aH = aBI.getHeight();
		BufferedImage aARGB = new BufferedImage(aW, aH, BufferedImage.TYPE_INT_ARGB);
		java.awt.Graphics2D aG = aARGB.createGraphics();
		aG.setComposite(java.awt.AlphaComposite.Src);
		aG.drawImage(aBI, 0, 0, null);
		aG.dispose();
		return new Texture3D(aARGB);
	}

	Texture3D(BufferedImage aBI) {
		//TYPE_INT_RGB from ColorBck.Fill.bitmap() (alpha 0, unused), TYPE_INT_ARGB from of()
		int aW = aBI.getWidth(), aH = aBI.getHeight();
		int[] aSrc = ((DataBufferInt)aBI.getRaster().getDataBuffer()).getData();
		java.util.Vector<int[]> aLs = new java.util.Vector<int[]>();
		java.util.Vector<int[]> aSizes = new java.util.Vector<int[]>();
		aLs.add(aSrc);
		aSizes.add(new int[] {aW, aH});
		while(aW > 1 || aH > 1) {
			int aNW = Math.max(1, aW/2), aNH = Math.max(1, aH/2);
			int[] aPrev = aLs.lastElement();
			int[] aNext = new int[aNW*aNH];
			for(int y = 0;y < aNH;y++) {
				int aY0 = Math.min(aH-1, y*2), aY1 = Math.min(aH-1, y*2+1);
				for(int x = 0;x < aNW;x++) {
					int aX0 = Math.min(aW-1, x*2), aX1 = Math.min(aW-1, x*2+1);
					int aA = aPrev[aY0*aW+aX0], aB = aPrev[aY0*aW+aX1], aC = aPrev[aY1*aW+aX0], aD = aPrev[aY1*aW+aX1];
					int aR = (((aA>>16)&0xFF)+((aB>>16)&0xFF)+((aC>>16)&0xFF)+((aD>>16)&0xFF)+2)>>2;
					int aG = (((aA>>8)&0xFF)+((aB>>8)&0xFF)+((aC>>8)&0xFF)+((aD>>8)&0xFF)+2)>>2;
					int aBl = ((aA&0xFF)+(aB&0xFF)+(aC&0xFF)+(aD&0xFF)+2)>>2;
					int aAl = ((aA>>>24)+(aB>>>24)+(aC>>>24)+(aD>>>24)+2)>>2;
					aNext[y*aNW+x] = (aAl<<24)|(aR<<16)|(aG<<8)|aBl;
				}
			}
			aLs.add(aNext);
			aSizes.add(new int[] {aNW, aNH});
			aW = aNW;
			aH = aNH;
		}
		levels = aLs.toArray(new int[0][]);
		lw = new int[levels.length];
		lh = new int[levels.length];
		for(int l = 0;l < levels.length;l++) {
			lw[l] = aSizes.elementAt(l)[0];
			lh[l] = aSizes.elementAt(l)[1];
		}
	}

	public int width() {
		return lw[0];
	}

	public int height() {
		return lh[0];
	}

	/**
	 * Level of detail for aTexelsPerPixel texels of the full image per pixel (along a side)
	 */
	public int level(double aTexelsPerPixel) {
		if(!(aTexelsPerPixel > 1)) {
			return 0;
		}
		int aL = (int)Math.floor(Math.log(aTexelsPerPixel)/Math.log(2));
		return Math.max(0, Math.min(levels.length-1, aL));
	}

	/**
	 * RGB at (aU, aV) in 0..1 of the level aL, not repeated (the edges extended), bilinear
	 */
	public int sampleClamp(double aU, double aV, int aL) {
		int aW = lw[aL], aH = lh[aL];
		int[] aP = levels[aL];
		double aX = Math.max(0, Math.min(aW-1, aU*aW - 0.5));
		double aY = Math.max(0, Math.min(aH-1, (1 - aV)*aH - 0.5));
		int aX0 = (int)aX, aY0 = (int)aY;
		double aFx = aX-aX0, aFy = aY-aY0;
		int aX1 = Math.min(aW-1, aX0+1), aY1 = Math.min(aH-1, aY0+1);
		int aA = aP[aY0*aW+aX0], aB = aP[aY0*aW+aX1], aC = aP[aY1*aW+aX0], aD = aP[aY1*aW+aX1];
		double aW00 = (1-aFx)*(1-aFy), aW10 = aFx*(1-aFy), aW01 = (1-aFx)*aFy, aW11 = aFx*aFy;
		int aR = (int)(((aA>>16)&0xFF)*aW00 + ((aB>>16)&0xFF)*aW10 + ((aC>>16)&0xFF)*aW01 + ((aD>>16)&0xFF)*aW11 + 0.5);
		int aG = (int)(((aA>>8)&0xFF)*aW00 + ((aB>>8)&0xFF)*aW10 + ((aC>>8)&0xFF)*aW01 + ((aD>>8)&0xFF)*aW11 + 0.5);
		int aBl = (int)((aA&0xFF)*aW00 + (aB&0xFF)*aW10 + (aC&0xFF)*aW01 + (aD&0xFF)*aW11 + 0.5);
		return 0xFF000000|(aR<<16)|(aG<<8)|aBl;
	}

	/**
	 * RGB at (aU, aV) of the level aL, repeated (v up: v = 0 is the bottom of the image), bilinear
	 */
	public int sample(double aU, double aV, int aL) {
		int aW = lw[aL], aH = lh[aL];
		int[] aP = levels[aL];
		double aX = (aU - Math.floor(aU))*aW - 0.5;
		double aY = (1 - (aV - Math.floor(aV)))*aH - 0.5;
		int aX0 = (int)Math.floor(aX), aY0 = (int)Math.floor(aY);
		double aFx = aX-aX0, aFy = aY-aY0;
		aX0 = ((aX0 % aW) + aW) % aW;
		aY0 = ((aY0 % aH) + aH) % aH;
		int aX1 = aX0+1 == aW ? 0 : aX0+1;
		int aY1 = aY0+1 == aH ? 0 : aY0+1;
		int aA = aP[aY0*aW+aX0], aB = aP[aY0*aW+aX1], aC = aP[aY1*aW+aX0], aD = aP[aY1*aW+aX1];
		double aW00 = (1-aFx)*(1-aFy), aW10 = aFx*(1-aFy), aW01 = (1-aFx)*aFy, aW11 = aFx*aFy;
		int aR = (int)(((aA>>16)&0xFF)*aW00 + ((aB>>16)&0xFF)*aW10 + ((aC>>16)&0xFF)*aW01 + ((aD>>16)&0xFF)*aW11 + 0.5);
		int aG = (int)(((aA>>8)&0xFF)*aW00 + ((aB>>8)&0xFF)*aW10 + ((aC>>8)&0xFF)*aW01 + ((aD>>8)&0xFF)*aW11 + 0.5);
		int aBl = (int)((aA&0xFF)*aW00 + (aB&0xFF)*aW10 + (aC&0xFF)*aW01 + (aD&0xFF)*aW11 + 0.5);
		int aAl = (int)((aA>>>24)*aW00 + (aB>>>24)*aW10 + (aC>>>24)*aW01 + (aD>>>24)*aW11 + 0.5);
		return (aAl<<24)|(aR<<16)|(aG<<8)|aBl;
	}
}
