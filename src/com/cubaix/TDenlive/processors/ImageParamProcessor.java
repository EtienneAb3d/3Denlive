package com.cubaix.TDenlive.processors;

import com.cubaix.TDenlive.utils.Par;

import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferFloat;
import java.awt.image.DataBufferInt;

import com.cubaix.TDenlive.TDenlive;
import com.cubaix.TDenlive.medias.Media;
import com.cubaix.TDenlive.utils.ImageUtils;

/**
 * ParamProcessor working on the images: both views are processed in parallel as float planes
 * (Planes: R, G, B, A, 0..1, above 1 in HDR), the same parameters for both eyes. The sizes
 * in pixels of the parameters are given for an image of REF_H lines (scale())
 */
public abstract class ImageParamProcessor extends ParamProcessor {
	/** Lines of the image for which the pixel sizes of the parameters are given */
	public static final double REF_H = 1080.0;

	/** An image as float planes */
	public static class Planes {
		public final int w, h;
		public final float[] r, g, b, a;
		public final boolean hdr;

		public Planes(int aW,int aH,boolean aHdr) {
			w = aW;
			h = aH;
			hdr = aHdr;
			r = new float[aW*aH];
			g = new float[aW*aH];
			b = new float[aW*aH];
			a = new float[aW*aH];
		}

		/** Planes of aBI (INT_ARGB, or float RGBA for HDR; other types are converted) */
		public static Planes of(BufferedImage aBI) {
			int aW = aBI.getWidth(), aH = aBI.getHeight();
			if(aBI.getRaster().getDataBuffer() instanceof DataBufferFloat) {
				float[] aDB = ((DataBufferFloat)aBI.getRaster().getDataBuffer()).getData();
				Planes aP = new Planes(aW, aH, true);
				for(int p = 0;p < aW*aH;p++) {
					aP.r[p] = aDB[4*p];
					aP.g[p] = aDB[4*p+1];
					aP.b[p] = aDB[4*p+2];
					aP.a[p] = aDB[4*p+3];
				}
				return aP;
			}
			if(aBI.getType() != BufferedImage.TYPE_INT_ARGB) {
				BufferedImage aC = ImageUtils.createImage(aW, aH);
				Graphics2D aG = (Graphics2D)aC.getGraphics();
				aG.drawImage(aBI, 0, 0, null);
				aG.dispose();
				aBI = aC;
			}
			int[] aDB = ((DataBufferInt)aBI.getRaster().getDataBuffer()).getData();
			Planes aP = new Planes(aW, aH, false);
			final float aK = 1.0f/255.0f;
			for(int p = 0;p < aW*aH;p++) {
				int aPix = aDB[p];
				aP.a[p] = ((aPix>>>24) & 0xFF)*aK;
				aP.r[p] = ((aPix>>16) & 0xFF)*aK;
				aP.g[p] = ((aPix>>8) & 0xFF)*aK;
				aP.b[p] = (aPix & 0xFF)*aK;
			}
			return aP;
		}

		/** Image of the planes, of the kind of the source (8 bits values clamped) */
		public BufferedImage toImage() {
			if(hdr) {
				BufferedImage aBI = ImageUtils.createHdr(w, h);
				float[] aDB = ((DataBufferFloat)aBI.getRaster().getDataBuffer()).getData();
				for(int p = 0;p < w*h;p++) {
					aDB[4*p] = Math.max(0f, r[p]);
					aDB[4*p+1] = Math.max(0f, g[p]);
					aDB[4*p+2] = Math.max(0f, b[p]);
					aDB[4*p+3] = Math.max(0f, Math.min(1f, a[p]));
				}
				return aBI;
			}
			BufferedImage aBI = ImageUtils.createImage(w, h);
			int[] aDB = ((DataBufferInt)aBI.getRaster().getDataBuffer()).getData();
			for(int p = 0;p < w*h;p++) {
				aDB[p] = to8(a[p]) << 24 | to8(r[p]) << 16 | to8(g[p]) << 8 | to8(b[p]);
			}
			return aBI;
		}

		static int to8(float aV) {
			int aI = (int)(aV*255.0f+0.5f);
			return aI < 0 ? 0 : (aI > 255 ? 255 : aI);
		}

		/** Upper limit of the values (1 for 8 bits images, none in HDR) */
		public float top() {
			return hdr ? Float.MAX_VALUE : 1.0f;
		}

		/** Luminance (Rec. 709) */
		public float[] luma() {
			float[] aY = new float[w*h];
			for(int p = 0;p < w*h;p++) {
				aY[p] = 0.2126f*r[p]+0.7152f*g[p]+0.0722f*b[p];
			}
			return aY;
		}
	}

	public ImageParamProcessor(TDenlive aTDe, Media aTargetMedia) {
		super(aTDe, aTargetMedia);
	}

	/** True if the values aV leave the image unchanged */
	protected boolean isNeutral(double[] aV) {
		return false;
	}

	/**
	 * Process the view aP in place
	 * @param aLeft true for the left view
	 * @param aScale size of a pixel of the parameters in pixels of the image (lines / REF_H)
	 */
	protected abstract void process(Planes aP,double[] aV,boolean aLeft,double aScale,int aProcessingMode,long aTime);

	@Override
	public void process2Awt(Media aMedia, int aProcessingMode, long aTime) {
		try {
			final double[] aV = valuesAt(aTime);
			if(isNeutral(aV)) {
				return;
			}
			Par.Task aThL = new Par.Task(() -> processView(aMedia, aProcessingMode, aTime, aV, true));
			Par.Task aThR = new Par.Task(() -> processView(aMedia, aProcessingMode, aTime, aV, false));
			aThL.start();
			aThR.start();
			aThL.join();
			aThR.join();
		}
		catch(Throwable t) {
			t.printStackTrace(System.err);
		}
	}

	void processView(Media aMedia,int aProcessingMode,long aTime,double[] aV,boolean aLeft) {
		try {
			BufferedImage aBI = aLeft ? aMedia.getWorkLeft(aProcessingMode,aTime) : aMedia.getWorkRight(aProcessingMode,aTime);
			if(aBI == null) {
				return;
			}
			Planes aP = Planes.of(aBI);
			process(aP, aV, aLeft, aP.h/REF_H, aProcessingMode, aTime);
			if(aLeft) {
				aMedia.setWorkLeft(aP.toImage());
			}
			else {
				aMedia.setWorkRight(aP.toImage());
			}
		}
		catch(Throwable t) {
			t.printStackTrace(System.err);
		}
	}

	//------------------------------------------------------------------ Tools

	/** Box blur of aSrc (w x h) of radius aR (window 2 aR + 1, edges averaged on the pixels inside) */
	public static float[] boxBlur(float[] aSrc,int aW,int aH,int aR) {
		if(aR <= 0) {
			return aSrc.clone();
		}
		float[] aTmp = new float[aW*aH];
		float[] aOut = new float[aW*aH];
		//Horizontal
		for(int y = 0;y < aH;y++) {
			int aRow = y*aW;
			double aSum = 0;
			int aN = 0;
			for(int x = 0;x <= Math.min(aR, aW-1);x++) {
				aSum += aSrc[aRow+x];
				aN++;
			}
			for(int x = 0;x < aW;x++) {
				aTmp[aRow+x] = (float)(aSum/aN);
				int aAdd = x+aR+1, aSub = x-aR;
				if(aAdd < aW) {
					aSum += aSrc[aRow+aAdd];
					aN++;
				}
				if(aSub >= 0) {
					aSum -= aSrc[aRow+aSub];
					aN--;
				}
			}
		}
		//Vertical
		double[] aSums = new double[aW];
		int aN = 0;
		for(int y = 0;y <= Math.min(aR, aH-1);y++) {
			for(int x = 0;x < aW;x++) {
				aSums[x] += aTmp[y*aW+x];
			}
			aN++;
		}
		for(int y = 0;y < aH;y++) {
			for(int x = 0;x < aW;x++) {
				aOut[y*aW+x] = (float)(aSums[x]/aN);
			}
			int aAdd = y+aR+1, aSub = y-aR;
			if(aAdd < aH) {
				for(int x = 0;x < aW;x++) {
					aSums[x] += aTmp[aAdd*aW+x];
				}
				aN++;
			}
			if(aSub >= 0) {
				for(int x = 0;x < aW;x++) {
					aSums[x] -= aTmp[aSub*aW+x];
				}
				aN--;
			}
		}
		return aOut;
	}

	/** Gaussian blur of standard deviation aSigma (pixels), approached by 3 box blurs */
	public static float[] gaussBlur(float[] aSrc,int aW,int aH,double aSigma) {
		if(aSigma < 0.3) {
			return aSrc.clone();
		}
		//Sizes of the 3 boxes (Kovesi)
		double aWIdeal = Math.sqrt(12*aSigma*aSigma/3+1);
		int aWL = (int)Math.floor(aWIdeal);
		if(aWL % 2 == 0) {
			aWL--;
		}
		int aWU = aWL+2;
		double aMIdeal = (12*aSigma*aSigma-3*aWL*aWL-12*aWL-9)/(double)(-4*aWL-4);
		long aM = Math.round(aMIdeal);
		float[] aOut = aSrc;
		for(int i = 0;i < 3;i++) {
			int aSize = i < aM ? aWL : aWU;
			aOut = boxBlur(aOut, aW, aH, Math.max(0, (aSize-1)/2));
		}
		return aOut;
	}

	/** Smooth step from 0 (aV <= aE0) to 1 (aV >= aE1) */
	public static double smoothStep(double aE0,double aE1,double aV) {
		double aT = Math.max(0, Math.min(1, (aV-aE0)/(aE1-aE0)));
		return aT*aT*(3-2*aT);
	}
}
