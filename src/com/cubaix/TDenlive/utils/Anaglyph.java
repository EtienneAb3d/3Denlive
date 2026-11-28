package com.cubaix.TDenlive.utils;

import java.awt.image.BufferedImage;
import java.awt.image.WritableRaster;

/**
 * Anaglyph images / videos imported as stereo: each view keeps the color layers of its filter as they are, the others
 * at 0 (red / cyan: the left view = the red layer, the right one = the green and blue layers), alpha kept; 8 bits or
 * HDR as the image given. The anaglyph mode of the monitor / rendering of the same kind (AnaMixer: red from the left
 * view, green and blue from the right one...) gives back the original image.
 */
public class Anaglyph {
	/** Ids of the kinds (saved in the projects, language keys "Anaglyph.Type_<id>"), the left filter first */
	public static final String[] TYPES = {"redCyan", "cyanRed", "greenMagenta", "magentaGreen", "amberBlue", "redBlue", "redGreen"};
	//Layers R, G, B kept (1) for the left view, then for the right one, per kind
	static final float[][][] MASKS = {
		{{1, 0, 0}, {0, 1, 1}},
		{{0, 1, 1}, {1, 0, 0}},
		{{0, 1, 0}, {1, 0, 1}},
		{{1, 0, 1}, {0, 1, 0}},
		{{1, 1, 0}, {0, 0, 1}},
		{{1, 0, 0}, {0, 0, 1}},
		{{1, 0, 0}, {0, 1, 0}},
	};

	public static int indexOf(String aType) {
		for(int t = 0;t < TYPES.length;t++) {
			if(TYPES[t].equalsIgnoreCase(aType)) {
				return t;
			}
		}
		return -1;
	}

	public static boolean isAnaglyph(String aType) {
		return aType != null && indexOf(aType) >= 0;
	}

	/**
	 * The left (aLeft) or right view of the anaglyph aSrc of the kind aType: a new image of the same kind (aSrc is
	 * not changed); aSrc itself for an unknown kind
	 */
	public static BufferedImage view(BufferedImage aSrc, String aType, boolean aLeft) {
		int aT = indexOf(aType);
		if(aSrc == null || aT < 0) {
			return aSrc;
		}
		float[] aW = MASKS[aT][aLeft ? 0 : 1];
		int aWd = aSrc.getWidth(), aH = aSrc.getHeight();
		boolean aHdr = aSrc.getRaster().getDataBuffer() instanceof java.awt.image.DataBufferFloat;
		BufferedImage aOut;
		if(aHdr || aSrc.getType() == BufferedImage.TYPE_INT_ARGB || aSrc.getType() == BufferedImage.TYPE_INT_RGB) {
			aOut = ImageUtils.createImage(aWd, aH, aHdr);
		}
		else {
			//Another kind (ImageIO): read as integer ARGB
			aOut = ImageUtils.createImage(aWd, aH);
			java.awt.Graphics2D aG = aOut.createGraphics();
			aG.drawImage(aSrc, 0, 0, null);
			aG.dispose();
			aSrc = aOut;
			aOut = ImageUtils.createImage(aWd, aH);
		}
		final BufferedImage aIn = aSrc;
		final WritableRaster aOR = aOut.getRaster();
		final int aInBands = aIn.getRaster().getNumBands();
		final int aOutBands = aOR.getNumBands();
		Par.rows(aH, (aY0, aY1) -> {
			float[] aRow = new float[aWd*aInBands];
			float[] aDst = new float[aWd*aOutBands];
			for(int y = aY0;y < aY1;y++) {
				aIn.getRaster().getPixels(0, y, aWd, 1, aRow);
				for(int x = 0;x < aWd;x++) {
					int i = x*aInBands, o = x*aOutBands;
					aDst[o] = aW[0]*aRow[i];
					aDst[o+1] = aW[1]*aRow[i+1];
					aDst[o+2] = aW[2]*aRow[i+2];
					if(aOutBands > 3) {
						aDst[o+3] = aInBands > 3 ? aRow[i+3] : (aHdr ? 1f : 255f);
					}
				}
				aOR.setPixels(0, y, aWd, 1, aDst);
			}
		});
		return aOut;
	}

	/**
	 * The anaglyph image again from its two views (view()): the sum of their color layers (each keeps only those of
	 * its filter), alpha of the left one; 8-bit ARGB (thumbnails), values clamped
	 */
	public static BufferedImage combine(BufferedImage aL, BufferedImage aR) {
		if(aL == null || aR == null) {
			return aL != null ? aL : aR;
		}
		aL = FloatDraw.clamped(aL);
		aR = FloatDraw.clamped(aR);
		int aW = Math.min(aL.getWidth(), aR.getWidth()), aH = Math.min(aL.getHeight(), aR.getHeight());
		BufferedImage aOut = new BufferedImage(aW, aH, BufferedImage.TYPE_INT_ARGB);
		for(int y = 0;y < aH;y++) {
			for(int x = 0;x < aW;x++) {
				int l = aL.getRGB(x, y), r = aR.getRGB(x, y);
				int aR8 = Math.min(255, ((l >> 16) & 255)+((r >> 16) & 255));
				int aG8 = Math.min(255, ((l >> 8) & 255)+((r >> 8) & 255));
				int aB8 = Math.min(255, (l & 255)+(r & 255));
				aOut.setRGB(x, y, (l & 0xFF000000) | aR8 << 16 | aG8 << 8 | aB8);
			}
		}
		return aOut;
	}
}
