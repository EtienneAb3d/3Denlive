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

	Texture3D(BufferedImage aBI) {
		//TYPE_INT_RGB from ColorBck.Fill.bitmap()
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
					aNext[y*aNW+x] = (aR<<16)|(aG<<8)|aBl;
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
		return (aR<<16)|(aG<<8)|aBl;
	}
}
