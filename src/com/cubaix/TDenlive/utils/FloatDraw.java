package com.cubaix.TDenlive.utils;

import java.awt.Graphics2D;
import java.awt.geom.AffineTransform;
import java.awt.geom.NoninvertibleTransformException;
import java.awt.geom.Point2D;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferFloat;
import java.awt.image.PixelInterleavedSampleModel;
import java.awt.image.Raster;
import java.awt.image.WritableRaster;

/**
 * drawImage() for the floating point images (ImageUtils.createHdr(), HDR processing): Java2D draws them through 8-bit
 * ARGB (precision lost, values above 1 wrapped), so that the HDR highlights of a video (HDR rendering end to end)
 * would not survive a copy, a rotation, a resize or a composition. Here: the source (float or integer) transformed by
 * an affine transform, sampled bilinearly (halved by 2 x 2 boxes first while reduced more than twice), composed
 * SRC_OVER with an extra opacity on the float RGBA (not premultiplied) destination, lines on all the cores. Values
 * above 1 kept.
 */
public class FloatDraw {
	/** aBI holds floating point samples */
	public static boolean isFloat(BufferedImage aBI) {
		return aBI != null && aBI.getRaster().getDataBuffer() instanceof DataBufferFloat;
	}

	/**
	 * aSrc drawn on aDst through aAT (null: identity) with the opacity aAlpha, as aG.drawImage(aSrc, aAT, null) with
	 * an AlphaComposite SRC_OVER aAlpha; done by Java2D (aG) when aDst is not a float image
	 */
	public static void draw(Graphics2D aG, BufferedImage aDst, BufferedImage aSrc, AffineTransform aAT, float aAlpha) {
		if(aSrc == null) {
			return;
		}
		if(isFloat(aDst)) {
			draw(aDst, aSrc, aAT, aAlpha);
			return;
		}
		//Float source on an 8-bit image: clamped first (Java2D wraps the values above 1)
		aSrc = clamped(aSrc);
		java.awt.Composite aOld = null;
		if(aAlpha < 1) {
			aOld = aG.getComposite();
			aG.setComposite(java.awt.AlphaComposite.getInstance(java.awt.AlphaComposite.SRC_OVER, aAlpha));
		}
		aG.drawImage(aSrc, aAT == null ? new AffineTransform() : aAT, null);
		if(aOld != null) {
			aG.setComposite(aOld);
		}
	}

	/** aSrc at aX, aY scaled to aW x aH (aW <= 0: its size) */
	public static void draw(Graphics2D aG, BufferedImage aDst, BufferedImage aSrc, double aX, double aY, double aW, double aH) {
		if(aSrc == null) {
			return;
		}
		AffineTransform aAT = AffineTransform.getTranslateInstance(aX, aY);
		if(aW > 0 && aH > 0) {
			aAT.scale(aW/aSrc.getWidth(), aH/aSrc.getHeight());
		}
		draw(aG, aDst, aSrc, aAT, 1f);
	}

	/**
	 * aBI in integer ARGB with its values clamped to 0..1 when it is a float image (Java2D wraps the values above
	 * 1: 2.5 gives 0.49), else aBI itself
	 */
	public static BufferedImage clamped(BufferedImage aBI) {
		if(!isFloat(aBI)) {
			return aBI;
		}
		final int aW = aBI.getWidth(), aH = aBI.getHeight();
		BufferedImage aOut = new BufferedImage(aW, aH, BufferedImage.TYPE_INT_ARGB);
		final int[] aD = ((java.awt.image.DataBufferInt)aOut.getRaster().getDataBuffer()).getData();
		final Raster aR = aBI.getRaster();
		final int aBands = aR.getNumBands();
		final boolean aAlpha = aBI.getColorModel().hasAlpha() && aBands > 3;
		Par.rows(aH, (aY0, aY1) -> {
			float[] aRow = new float[aW*aBands];
			for(int y = aY0;y < aY1;y++) {
				aR.getPixels(0, y, aW, 1, aRow);
				for(int x = 0;x < aW;x++) {
					int i = x*aBands;
					int r = to8(aRow[i]), g = to8(aRow[i+Math.min(1, aBands-1)]), b = to8(aRow[i+Math.min(2, aBands-1)]);
					int a = aAlpha ? to8(aRow[i+3]) : 255;
					aD[y*aW+x] = a << 24 | r << 16 | g << 8 | b;
				}
			}
		});
		return aOut;
	}

	public static int to8(float aV) {
		return aV <= 0 ? 0 : aV >= 1 ? 255 : (int)(aV*255+0.5f);
	}

	/** Source as premultiplied float RGBA (0..1 for 8 bits, values above 1 kept for float) */
	static final class Src {
		float[] p;
		int w, h;
	}

	static Src read(BufferedImage aSrc) {
		Src aS = new Src();
		aS.w = aSrc.getWidth();
		aS.h = aSrc.getHeight();
		aS.p = new float[aS.w*aS.h*4];
		final Raster aR = aSrc.getRaster();
		final int aBands = aR.getNumBands();
		final boolean aFloat = isFloat(aSrc);
		final float aNorm = aFloat ? 1f : 1f/255f;
		final boolean aAlpha = aSrc.getColorModel().hasAlpha() && aBands > 3;
		final boolean aGray = aBands < 3;
		final boolean aPre = aSrc.isAlphaPremultiplied();
		final float[] aP = aS.p;
		final int aW = aS.w;
		Par.rows(aS.h, (aY0, aY1) -> {
			float[] aRow = new float[aW*aBands];
			for(int y = aY0;y < aY1;y++) {
				aR.getPixels(0, y, aW, 1, aRow);
				int o = y*aW*4;
				for(int x = 0;x < aW;x++) {
					int i = x*aBands;
					float r, g, b, a;
					if(aGray) {
						r = g = b = aRow[i]*aNorm;
						a = aBands > 1 ? aRow[i+1]*aNorm : 1f;
					}
					else {
						r = aRow[i]*aNorm;
						g = aRow[i+1]*aNorm;
						b = aRow[i+2]*aNorm;
						a = aAlpha ? aRow[i+3]*aNorm : 1f;
					}
					if(!aPre) {
						r *= a;
						g *= a;
						b *= a;
					}
					aP[o++] = r;
					aP[o++] = g;
					aP[o++] = b;
					aP[o++] = a;
				}
			}
		});
		return aS;
	}

	/** Half size by 2 x 2 boxes */
	static Src halve(Src aS) {
		Src aH = new Src();
		aH.w = Math.max(1, aS.w/2);
		aH.h = Math.max(1, aS.h/2);
		aH.p = new float[aH.w*aH.h*4];
		final Src aIn = aS;
		final Src aOut = aH;
		Par.rows(aH.h, (aY0, aY1) -> {
			for(int y = aY0;y < aY1;y++) {
				int y0 = Math.min(aIn.h-1, 2*y), y1 = Math.min(aIn.h-1, 2*y+1);
				for(int x = 0;x < aOut.w;x++) {
					int x0 = Math.min(aIn.w-1, 2*x), x1 = Math.min(aIn.w-1, 2*x+1);
					int a = (y0*aIn.w+x0)*4, b = (y0*aIn.w+x1)*4, c = (y1*aIn.w+x0)*4, d = (y1*aIn.w+x1)*4;
					int o = (y*aOut.w+x)*4;
					for(int k = 0;k < 4;k++) {
						aOut.p[o+k] = 0.25f*(aIn.p[a+k]+aIn.p[b+k]+aIn.p[c+k]+aIn.p[d+k]);
					}
				}
			}
		});
		return aH;
	}

	/** aSrc composed over the float image aDst through aAT with the opacity aAlpha */
	public static void draw(BufferedImage aDst, BufferedImage aSrc, AffineTransform aAT, float aAlpha) {
		if(aSrc == null || aDst == null || aAlpha <= 0) {
			return;
		}
		AffineTransform aT = aAT == null ? new AffineTransform() : new AffineTransform(aAT);
		Src aS = read(aSrc);
		//Reduced more than twice: halved first (bilinear alone would skip pixels)
		double aScale = Math.sqrt(Math.abs(aT.getDeterminant()));
		while(aScale < 0.5 && aS.w > 1 && aS.h > 1) {
			double aKx = aS.w/(double)Math.max(1, aS.w/2), aKy = aS.h/(double)Math.max(1, aS.h/2);
			aS = halve(aS);
			aT.scale(aKx, aKy);
			aScale *= Math.sqrt(aKx*aKy);
		}
		AffineTransform aInv;
		try {
			aInv = aT.createInverse();
		}
		catch(NoninvertibleTransformException e) {
			return;
		}
		//Bounds of the source in the destination
		double aMinX = Double.MAX_VALUE, aMinY = Double.MAX_VALUE, aMaxX = -Double.MAX_VALUE, aMaxY = -Double.MAX_VALUE;
		double[] aCorners = {0, 0, aS.w, 0, 0, aS.h, aS.w, aS.h};
		aT.transform(aCorners, 0, aCorners, 0, 4);
		for(int c = 0;c < 4;c++) {
			aMinX = Math.min(aMinX, aCorners[2*c]);
			aMaxX = Math.max(aMaxX, aCorners[2*c]);
			aMinY = Math.min(aMinY, aCorners[2*c+1]);
			aMaxY = Math.max(aMaxY, aCorners[2*c+1]);
		}
		final int aDW = aDst.getWidth(), aDH = aDst.getHeight();
		final int aX0 = Math.max(0, (int)Math.floor(aMinX)), aX1 = Math.min(aDW, (int)Math.ceil(aMaxX));
		final int aY0 = Math.max(0, (int)Math.floor(aMinY)), aY1 = Math.min(aDH, (int)Math.ceil(aMaxY));
		if(aX0 >= aX1 || aY0 >= aY1) {
			return;
		}
		final WritableRaster aWR = aDst.getRaster();
		final int aDBands = aWR.getNumBands();
		//Direct access to the samples of ImageUtils.createHdr() (RGBA interleaved)
		final float[] aBank = aWR.getSampleModel() instanceof PixelInterleavedSampleModel && aDBands == 4
				&& aWR.getParent() == null && aWR.getSampleModelTranslateX() == 0 && aWR.getSampleModelTranslateY() == 0
				&& ((PixelInterleavedSampleModel)aWR.getSampleModel()).getScanlineStride() == aDW*4
				&& isRgbaOffsets(((PixelInterleavedSampleModel)aWR.getSampleModel()).getBandOffsets())
				? ((DataBufferFloat)aWR.getDataBuffer()).getData() : null;
		final Src aIn = aS;
		final float aOp = Math.min(1f, aAlpha);
		final Point2D aO = aInv.transform(new Point2D.Double(0, 0), null);
		final Point2D aDx = aInv.deltaTransform(new Point2D.Double(1, 0), null);
		final Point2D aDy = aInv.deltaTransform(new Point2D.Double(0, 1), null);
		final boolean aExact = aInv.getType() == AffineTransform.TYPE_IDENTITY
				|| (aInv.getType() == AffineTransform.TYPE_TRANSLATION && aInv.getTranslateX() == Math.rint(aInv.getTranslateX())
						&& aInv.getTranslateY() == Math.rint(aInv.getTranslateY()));
		Par.rows(aY1-aY0, (aR0, aR1) -> {
			float[] aRow = aBank == null ? new float[(aX1-aX0)*aDBands] : null;
			float[] aC = new float[4];
			for(int y = aY0+aR0;y < aY0+aR1;y++) {
				if(aRow != null) {
					aWR.getPixels(aX0, y, aX1-aX0, 1, aRow);
				}
				for(int x = aX0;x < aX1;x++) {
					//Source position of the center of this pixel, in pixel centers
					double aU = aO.getX()+(x+0.5)*aDx.getX()+(y+0.5)*aDy.getX()-0.5;
					double aV = aO.getY()+(x+0.5)*aDx.getY()+(y+0.5)*aDy.getY()-0.5;
					if(aU < -0.5 || aV < -0.5 || aU > aIn.w-0.5 || aV > aIn.h-0.5) {
						continue;
					}
					if(aExact) {
						int i = ((int)Math.round(aV)*aIn.w+(int)Math.round(aU))*4;
						aC[0] = aIn.p[i]; aC[1] = aIn.p[i+1]; aC[2] = aIn.p[i+2]; aC[3] = aIn.p[i+3];
					}
					else {
						sample(aIn, aU, aV, aC);
					}
					float aSa = aC[3]*aOp;
					if(aSa <= 0) {
						continue;
					}
					int o = aBank != null ? (y*aDW+x)*4 : (x-aX0)*aDBands;
					float[] aD = aBank != null ? aBank : aRow;
					float aDa = aDBands > 3 ? aD[o+3] : 1f;
					float aOa = aSa+aDa*(1-aSa);
					for(int c = 0;c < 3;c++) {
						//Premultiplied source over the straight destination, back to straight
						float aV2 = aC[c]*aOp+aD[o+c]*aDa*(1-aSa);
						aD[o+c] = aOa > 0 ? aV2/aOa : 0;
					}
					if(aDBands > 3) {
						aD[o+3] = aOa;
					}
				}
				if(aRow != null) {
					aWR.setPixels(aX0, y, aX1-aX0, 1, aRow);
				}
			}
		});
	}

	static boolean isRgbaOffsets(int[] aOffsets) {
		return aOffsets.length == 4 && aOffsets[0] == 0 && aOffsets[1] == 1 && aOffsets[2] == 2 && aOffsets[3] == 3;
	}

	/** Bilinear sample (premultiplied) at aU, aV (pixel centers), edges clamped */
	static void sample(Src aS, double aU, double aV, float[] aOut) {
		double aUc = Math.max(0, Math.min(aS.w-1, aU)), aVc = Math.max(0, Math.min(aS.h-1, aV));
		int x0 = (int)aUc, y0 = (int)aVc;
		int x1 = Math.min(aS.w-1, x0+1), y1 = Math.min(aS.h-1, y0+1);
		float fx = (float)(aUc-x0), fy = (float)(aVc-y0);
		int a = (y0*aS.w+x0)*4, b = (y0*aS.w+x1)*4, c = (y1*aS.w+x0)*4, d = (y1*aS.w+x1)*4;
		float[] p = aS.p;
		for(int k = 0;k < 4;k++) {
			aOut[k] = (p[a+k]*(1-fx)+p[b+k]*fx)*(1-fy)+(p[c+k]*(1-fx)+p[d+k]*fx)*fy;
		}
	}
}
