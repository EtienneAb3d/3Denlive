package com.cubaix.TDenlive.depth;

import java.awt.image.BufferedImage;
import java.awt.image.DataBufferFloat;
import java.awt.image.DataBufferInt;
import java.util.Arrays;
import java.util.Vector;

import com.cubaix.TDenlive.utils.ImageUtils;
import com.cubaix.TDenlive.utils.Par;

/**
 * Recolorization of a stereo pair whose views do not hold the same color layers (an anaglyph imported: red in one
 * view, green + blue in the other...): each view takes the layers it lacks from the other view, at the pixel that
 * corresponds to it. The correspondence comes from the depth maps: the parallax between the views is a line of the
 * depth (x' = x + a + b z, plus a vertical offset), fitted on block matches between the views (zero mean normalized
 * cross correlation of their luminances, which works between different color layers), then each pixel goes to the
 * other view by its own depth, checked by the way back (the depth of the other view gives the parallax back). A pixel
 * without homologue (seen by one view only, or a homologue in a hole of the other view) is erased (alpha 0, a
 * hole of the conversion: maskDepth()), a homologue being looked for by the depth map of each view. A measure matching less than KEPT_MIN of the pixels is not used: the
 * previous one if it does here, else the image is not recolorized. The fit of an image is mixed with that
 * of the previous one only for a still or nearly still shot: images close in time whose holes (pixels without
 * match) differ by less than HOLES_DIFF_MAX of their union; a fast motion or a cut takes its own measure (the
 * previous one only when an image gives too few matches).
 */
public class Recolorizer {
	//Patch radius and step of the matches (half size)
	static final int PATCH = 4;
	static final int STEP = 5;
	//Minimum correlation of a match and gap to the second best
	static final double MIN_NCC = 0.5;
	static final double UNIQUE_GAP = 0.04;
	//Minimum standard deviation of a patch (luminance 0..1)
	static final double MIN_STD = 0.015;
	static final int MIN_MATCHES = 30;
	//A layer is in a view when more than this part of its pixels is above 4/255
	static final double PRESENT_PART = 0.01;

	//Last fits: {a, b, dy} of left -> right and right -> left, their time
	double[] fitLR = null;
	double[] fitRL = null;
	long fitTime = Long.MIN_VALUE;
	//Holes of the last image (pixels without match in the other view with its own measure), to compare
	boolean[] prevHoles = null;
	//Propagation of the fit only when the holes of two images differ less than this (part of their union), and
	//both images close in time
	static final double HOLES_DIFF_MAX = 0.25;
	static final long PROPAGATE_MS = 500;
	//Difference of the holes of the last image with those of the previous one (-1: not compared), propagated or not
	public volatile double lastHolesDiff = -1;
	public volatile boolean lastPropagated = false;
	//A measure is good when at least this part of the pixels has a match in the other view (measured on an
	//anaglyph video: all the images recolorized badly were below 0.86, all those above 0.91 were good)
	static final double KEPT_MIN = 0.9;
	//The last measure was rejected (bad quality)
	public volatile boolean lastRejected = false;
	//Number of block matches of the last measure (fit[3]: part kept by the line fit, fit[4]: median distance)
	public volatile int lastMatches = 0;
	//Part of the pixels with a match (not holes) of the last measure
	public volatile double lastKept = -1;
	//Raw fits of the last measure (before the propagation)
	public volatile double[][] lastRaw = null;

	/** Planes r, g, b, a of an image (0..1, float images as they are) */
	static float[][] planes(BufferedImage aBI) {
		int aW = aBI.getWidth(), aH = aBI.getHeight(), n = aW*aH;
		float[][] aP = new float[4][n];
		if(aBI.getRaster().getDataBuffer() instanceof DataBufferInt) {
			int[] aD = ((DataBufferInt)aBI.getRaster().getDataBuffer()).getData();
			boolean aAlpha = aBI.getColorModel().hasAlpha();
			for(int i = 0;i < n;i++) {
				int p = aD[i];
				aP[0][i] = ((p>>16) & 0xFF)/255f;
				aP[1][i] = ((p>>8) & 0xFF)/255f;
				aP[2][i] = (p & 0xFF)/255f;
				aP[3][i] = aAlpha ? ((p>>>24) & 0xFF)/255f : 1f;
			}
			return aP;
		}
		if(aBI.getRaster().getDataBuffer() instanceof DataBufferFloat && aBI.getRaster().getNumBands() == 4) {
			float[] aD = ((DataBufferFloat)aBI.getRaster().getDataBuffer()).getData();
			for(int i = 0;i < n;i++) {
				aP[0][i] = aD[4*i];
				aP[1][i] = aD[4*i+1];
				aP[2][i] = aD[4*i+2];
				aP[3][i] = aD[4*i+3];
			}
			return aP;
		}
		//Any other: through the 8 bits colors
		for(int y = 0;y < aH;y++) {
			for(int x = 0;x < aW;x++) {
				int p = aBI.getRGB(x, y);
				int i = y*aW+x;
				aP[0][i] = ((p>>16) & 0xFF)/255f;
				aP[1][i] = ((p>>8) & 0xFF)/255f;
				aP[2][i] = (p & 0xFF)/255f;
				aP[3][i] = ((p>>>24) & 0xFF)/255f;
			}
		}
		return aP;
	}

	/** The image of the planes aP, as aLike (8 bits or float) */
	static BufferedImage image(float[][] aP,int aW,int aH,BufferedImage aLike) {
		int n = aW*aH;
		if(aLike.getRaster().getDataBuffer() instanceof DataBufferFloat) {
			BufferedImage aBI = ImageUtils.createHdr(aW, aH);
			float[] aD = ((DataBufferFloat)aBI.getRaster().getDataBuffer()).getData();
			for(int i = 0;i < n;i++) {
				aD[4*i] = aP[0][i];
				aD[4*i+1] = aP[1][i];
				aD[4*i+2] = aP[2][i];
				aD[4*i+3] = aP[3][i];
			}
			return aBI;
		}
		BufferedImage aBI = ImageUtils.createImage(aW, aH);
		int[] aD = ((DataBufferInt)aBI.getRaster().getDataBuffer()).getData();
		for(int i = 0;i < n;i++) {
			aD[i] = (b8(aP[3][i])<<24)|(b8(aP[0][i])<<16)|(b8(aP[1][i])<<8)|b8(aP[2][i]);
		}
		return aBI;
	}

	static int b8(float v) {
		int c = Math.round(v*255);
		return c < 0 ? 0 : (c > 255 ? 255 : c);
	}

	/** Depth map as 0..1 (mean of its colors, stretched between its min and max) */
	static float[] depth(BufferedImage aDM,int aW,int aH) {
		float[][] aP = planes(aDM.getWidth() == aW && aDM.getHeight() == aH ? aDM : ImageUtils.fitTo(aDM, ImageUtils.createImage(aW, aH)));
		int n = aW*aH;
		float[] aZ = new float[n];
		float aMin = Float.MAX_VALUE, aMax = -Float.MAX_VALUE;
		for(int i = 0;i < n;i++) {
			aZ[i] = (aP[0][i]+aP[1][i]+aP[2][i])/3f;
			aMin = Math.min(aMin, aZ[i]);
			aMax = Math.max(aMax, aZ[i]);
		}
		float aR = aMax-aMin > 1e-6f ? aMax-aMin : 1f;
		for(int i = 0;i < n;i++) {
			aZ[i] = (aZ[i]-aMin)/aR;
		}
		return aZ;
	}

	/** Layers present in the view */
	static boolean[] present(float[][] aP) {
		int n = aP[0].length;
		boolean[] aRes = new boolean[3];
		for(int c = 0;c < 3;c++) {
			int k = 0;
			for(int i = 0;i < n;i++) {
				if(aP[c][i] > 4/255f) {
					k++;
				}
			}
			aRes[c] = k > PRESENT_PART*n;
		}
		return aRes;
	}

	/**
	 * The pair recolorized {left, right}, or null when both views hold the same layers (nothing to take from the
	 * other view) or when the depth maps give no correspondence
	 */
	public synchronized BufferedImage[] recolor(BufferedImage aL,BufferedImage aR,BufferedImage aDML,BufferedImage aDMR,boolean aBlackIsFront,long aTime) {
		int aW = aL.getWidth(), aH = aL.getHeight();
		if(aR.getWidth() != aW || aR.getHeight() != aH || aW < 32 || aH < 32) {
			return null;
		}
		float[][] aPL = planes(aL);
		float[][] aPR = planes(aR);
		boolean[] aInL = present(aPL);
		boolean[] aInR = present(aPR);
		boolean[] aMissL = new boolean[3], aMissR = new boolean[3];
		boolean aAny = false;
		for(int c = 0;c < 3;c++) {
			aMissL[c] = !aInL[c] && aInR[c];
			aMissR[c] = !aInR[c] && aInL[c];
			aAny |= aMissL[c] || aMissR[c];
		}
		if(!aAny) {
			return null;
		}
		float[] aZL = depth(aDML, aW, aH);
		float[] aZR = depth(aDMR, aW, aH);
		float[] aLumL = lum(aPL, aInL);
		float[] aLumR = lum(aPR, aInR);
		double[][] aRaw = calibrate(aLumL, aLumR, aZL, aZR, aW, aH);
		boolean[] aHoles = aRaw == null ? null : holes(aZL, aZR, aRaw[0], aRaw[1], aW, aH);
		lastKept = aHoles == null ? -1 : keptPart(aHoles);
		lastRaw = aRaw == null ? null : new double[][] {aRaw[0].clone(), aRaw[1].clone()};
		lastHolesDiff = -1;
		lastPropagated = false;
		lastRejected = false;
		if(aRaw == null || lastKept < KEPT_MIN) {
			//Measure missing or of bad quality: the measure of the previous image, if close in time and good here
			lastRejected = aRaw != null;
			boolean[] aPrevHoles = fitLR == null || Math.abs(aTime-fitTime) > PROPAGATE_MS ? null : holes(aZL, aZR, fitLR, fitRL, aW, aH);
			double aPrevKept = aPrevHoles == null ? -1 : keptPart(aPrevHoles);
			if(aPrevKept < KEPT_MIN) {
				System.out.println("Recolor: no good measure of the parallax at "+aTime+" ms ("+(aRaw == null ? "too few matches"
						: Math.round(lastKept*100)+" % of the pixels matched")+"): not recolorized");
				return null;
			}
			System.out.println("Recolor: measure at "+aTime+" ms "+(aRaw == null ? "with too few matches" : "of bad quality ("+Math.round(lastKept*100)+" % matched)")
					+", that of the previous image ("+Math.round(aPrevKept*100)+" %)");
			fitTime = aTime;
			prevHoles = aPrevHoles;
		}
		else {
			//Propagation of the previous fit only for a still or nearly still shot: holes nearly the same
			double aDiff = prevHoles != null && prevHoles.length == aHoles.length ? holesDiff(prevHoles, aHoles) : -1;
			boolean aProp = fitLR != null && aDiff >= 0 && aDiff <= HOLES_DIFF_MAX && Math.abs(aTime-fitTime) <= PROPAGATE_MS;
			if(aProp) {
				for(int i = 0;i < 3;i++) {
					aRaw[0][i] = 0.6*fitLR[i]+0.4*aRaw[0][i];
					aRaw[1][i] = 0.6*fitRL[i]+0.4*aRaw[1][i];
				}
			}
			fitLR = aRaw[0];
			fitRL = aRaw[1];
			fitTime = aTime;
			prevHoles = aHoles;
			lastHolesDiff = aDiff;
			lastPropagated = aProp;
		}
		double[] aLR = fitLR, aRL = fitRL;
		//Holes of each view (pixels seen by it only): never given to the other view
		boolean[] aHolesL = holes(aZL, aZR, aLR, aRL, aW, aH);
		boolean[] aHolesR = holes(aZR, aZL, aRL, aLR, aW, aH);
		float[][] aOutL = fill(aPL, aLumL, aZL, aPR, aLumR, aZR, aHolesR, aMissL, aLR, aRL, aBlackIsFront, aW, aH);
		float[][] aOutR = fill(aPR, aLumR, aZR, aPL, aLumL, aZL, aHolesL, aMissR, aRL, aLR, aBlackIsFront, aW, aH);
		return new BufferedImage[] {image(aOutL, aW, aH, aL), image(aOutR, aW, aH, aR)};
	}

	/** Color layers present in the image (see present()) */
	public static boolean[] layers(BufferedImage aBI) {
		return present(planes(aBI));
	}

	/** Luminance of the layers of the view */
	static float[] lum(float[][] aP,boolean[] aIn) {
		int n = aP[0].length;
		float[] aRes = new float[n];
		int k = 0;
		for(int c = 0;c < 3;c++) {
			if(aIn[c]) {
				k++;
				for(int i = 0;i < n;i++) {
					aRes[i] += aP[c][i];
				}
			}
		}
		if(k > 1) {
			for(int i = 0;i < n;i++) {
				aRes[i] /= k;
			}
		}
		return aRes;
	}

	/**
	 * The layers aMiss of the view aP taken from its homologue in the other view aO, which must not be one of the
	 * holes aOHoles of that view (seen by it only: the foreground of an edge gave a fringe of the other layers along
	 * the occlusions). Two chances, one per depth map: by the depth of this view, x' = x + a + b z, y' = y + dy, kept
	 * when the way back by the depth of the other view comes back near x (a hole neighbor of the bilinear sample left
	 * out, the nearest pixel taken alone); else by the depth of the other view, a pixel of it (not a hole) landing on
	 * x (the nearest wins). A pixel without homologue is erased (alpha 0): the depth map masked by maskDepth(), it is
	 * a hole of the conversion, filled as the others (a pixel kept with only its own layers stayed a red or cyan dot)
	 */
	static float[][] fill(float[][] aP,float[] aLum,float[] aZ,float[][] aO,float[] aOLum,float[] aOZ,boolean[] aOHoles,boolean[] aMiss
			,double[] aFit,double[] aBack,boolean aBlackIsFront,int aW,int aH) {
		float[][] aRes = new float[][] {aP[0].clone(), aP[1].clone(), aP[2].clone(), aP[3].clone()};
		boolean aAny = aMiss[0] || aMiss[1] || aMiss[2];
		if(!aAny) {
			return aRes;
		}
		int aDy = (int)Math.round(aFit[2]);
		double aTol = Math.max(1.5, aW/500.0);
		Par.rows(aH, (aY0, aY1) -> {
			boolean[] aOk = new boolean[aW];
			int[] aFrom = new int[aW];
			float[] aFromZ = new float[aW];
			for(int y = aY0;y < aY1;y++) {
				int yo = y+aDy;
				Arrays.fill(aOk, false);
				if(yo < 0 || yo >= aH) {
					for(int x = 0;x < aW;x++) {
						aRes[3][y*aW+x] = 0f;
					}
					continue;
				}
				//Chance 1: by the depth of this view
				for(int x = 0;x < aW;x++) {
					int i = y*aW+x;
					double xo = x+aFit[0]+aFit[1]*aZ[i];
					if(xo < 0 || xo > aW-1) {
						continue;
					}
					int xi = (int)xo;
					double fx = xo-xi;
					int xn = Math.min(aW-1, xi+1);
					int io = yo*aW+(fx < 0.5 ? xi : xn);
					//A hole of the other view (seen by it only) is not given to this one
					if(aOHoles != null && aOHoles[io]) {
						continue;
					}
					//Way back by the depth of the other view
					double xb = xo+aBack[0]+aBack[1]*aOZ[io];
					if(Math.abs(xb-x) > aTol) {
						continue;
					}
					aOk[x] = true;
					int i0 = yo*aW+xi, i1 = yo*aW+xn;
					//The other neighbor of the sample in a hole: the nearest pixel alone
					if(aOHoles != null && aOHoles[i0] != aOHoles[i1]) {
						fx = aOHoles[i0] ? 1 : 0;
					}
					for(int c = 0;c < 3;c++) {
						if(aMiss[c]) {
							aRes[c][i] = (float)(aO[c][i0]*(1-fx)+aO[c][i1]*fx);
						}
					}
				}
				//Chance 2: the pixels of the other view (not holes) landing here by their own depth, the nearest wins
				Arrays.fill(aFrom, -1);
				for(int xj = 0;xj < aW;xj++) {
					int j = yo*aW+xj;
					if(aOHoles != null && aOHoles[j]) {
						continue;
					}
					int x = (int)Math.round(xj+aBack[0]+aBack[1]*aOZ[j]);
					if(x < 0 || x >= aW || aOk[x]) {
						continue;
					}
					//Nearness: white is near, unless black is front
					float aN = aBlackIsFront ? -aOZ[j] : aOZ[j];
					if(aFrom[x] < 0 || aN > aFromZ[x]) {
						aFrom[x] = j;
						aFromZ[x] = aN;
					}
				}
				for(int x = 0;x < aW;x++) {
					if(aOk[x]) {
						continue;
					}
					int i = y*aW+x;
					int j = aFrom[x];
					if(j < 0) {
						//No homologue: erased, a hole of the conversion
						aRes[3][i] = 0f;
						continue;
					}
					for(int c = 0;c < 3;c++) {
						if(aMiss[c]) {
							aRes[c][i] = aO[c][j];
						}
					}
				}
			}
		});
		cleanFringes(aRes, aLum, aMiss, aW, aH);
		return aRes;
	}

	/**
	 * Copy of the depth map aDM (size of the view) whose pixels erased in the recolorized view aRec (alpha 0) are
	 * transparent: the conversion skips them, so they are holes, filled as the others
	 */
	public static BufferedImage maskDepth(BufferedImage aDM,BufferedImage aRec) {
		int aW = aDM.getWidth(), aH = aDM.getHeight();
		if(aRec.getWidth() != aW || aRec.getHeight() != aH) {
			return aDM;
		}
		float[] aA = planes(aRec)[3];
		if(aDM.getRaster().getDataBuffer() instanceof DataBufferInt) {
			BufferedImage aRes = ImageUtils.createImage(aW, aH);
			int[] aS = ((DataBufferInt)aDM.getRaster().getDataBuffer()).getData();
			int[] aD = ((DataBufferInt)aRes.getRaster().getDataBuffer()).getData();
			for(int i = 0;i < aD.length;i++) {
				aD[i] = aA[i] <= 0f ? aS[i] & 0x00FFFFFF : aS[i];
			}
			return aRes;
		}
		if(aDM.getRaster().getDataBuffer() instanceof DataBufferFloat && aDM.getRaster().getNumBands() == 4) {
			BufferedImage aRes = ImageUtils.createHdr(aW, aH);
			float[] aS = ((DataBufferFloat)aDM.getRaster().getDataBuffer()).getData();
			float[] aD = ((DataBufferFloat)aRes.getRaster().getDataBuffer()).getData();
			System.arraycopy(aS, 0, aD, 0, aD.length);
			for(int i = 0;i < aA.length;i++) {
				if(aA[i] <= 0f) {
					aD[4*i+3] = 0f;
				}
			}
			return aRes;
		}
		return aDM;
	}

	//Fringe cleaning: radius of the window (part of the height, at least FRINGE_MIN pixels), samples per side, own
	//luminance similarity (absolute + part of the luminance) of the samples taken
	static final double FRINGE_PART = 1/120.0;
	static final int FRINGE_MIN = 3;
	static final int FRINGE_SAMPLES = 7;
	static final float FRINGE_TOL = 0.06f;
	static final float FRINGE_TOL_PART = 0.15f;

	/**
	 * Fringes along the edges: the depth maps are softer than the images, so a pixel of the background near an edge
	 * may carry the depth of the foreground, find the foreground in the other view (the way back passing, the depth
	 * of the other view as soft) and take its layers (a red edge along a cyan object, or the reverse); a sample
	 * across an edge mixes them too. The difference of each layer taken to the own luminance (the color of the scene,
	 * smooth inside a surface) is replaced by its median over the pixels around of similar own luminance (the same
	 * surface: the edge is kept), a few pixels wrong along an edge being a minority there
	 */
	static void cleanFringes(float[][] aRes,float[] aLum,boolean[] aMiss,int aW,int aH) {
		int n = aW*aH;
		int aR = Math.max(FRINGE_MIN, (int)Math.round(aH*FRINGE_PART));
		int aStep = Math.max(1, Math.round(2*aR/(float)(FRINGE_SAMPLES-1)));
		for(int c = 0;c < 3;c++) {
			if(!aMiss[c]) {
				continue;
			}
			float[] aD = new float[n];
			float[] aC = aRes[c];
			//Erased pixels (no homologue): neither cleaned nor samples
			float[] aAl = aRes[3];
			for(int i = 0;i < n;i++) {
				aD[i] = aC[i]-aLum[i];
			}
			Par.rows(aH, (aY0, aY1) -> {
				int aMax = (2*aR/aStep+1)*(2*aR/aStep+1)+1;
				float[] aSim = new float[aMax];
				float[] aAll = new float[aMax];
				for(int y = aY0;y < aY1;y++) {
					for(int x = 0;x < aW;x++) {
						int i = y*aW+x;
						if(aAl[i] <= 0f) {
							continue;
						}
						float aL = aLum[i];
						float aTol = FRINGE_TOL+FRINGE_TOL_PART*Math.abs(aL);
						int ns = 0, na = 0;
						for(int dy = -aR;dy <= aR;dy += aStep) {
							int yy = y+dy;
							if(yy < 0 || yy >= aH) {
								continue;
							}
							for(int dx = -aR;dx <= aR;dx += aStep) {
								int xx = x+dx;
								if(xx < 0 || xx >= aW) {
									continue;
								}
								int j = yy*aW+xx;
								if(aAl[j] <= 0f) {
									continue;
								}
								aAll[na++] = aD[j];
								if(Math.abs(aLum[j]-aL) <= aTol) {
									aSim[ns++] = aD[j];
								}
							}
						}
						if(na == 0) {
							continue;
						}
						float aM = ns >= 5 ? median(aSim, ns) : median(aAll, na);
						aC[i] = Math.max(0f, aL+aM);
					}
				}
			});
		}
	}

	/** Median of the first n values (reordered) */
	static float median(float[] aV,int n) {
		for(int i = 1;i < n;i++) {
			float v = aV[i];
			int j = i-1;
			while(j >= 0 && aV[j] > v) {
				aV[j+1] = aV[j];
				j--;
			}
			aV[j+1] = v;
		}
		return (n & 1) == 1 ? aV[n/2] : 0.5f*(aV[n/2-1]+aV[n/2]);
	}

	/** Half size (2 x 2 box) */
	static float[] half(float[] aV,int aW,int aH) {
		int aW2 = aW/2, aH2 = aH/2;
		float[] aRes = new float[aW2*aH2];
		for(int y = 0;y < aH2;y++) {
			for(int x = 0;x < aW2;x++) {
				int i = 2*y*aW+2*x;
				aRes[y*aW2+x] = (aV[i]+aV[i+1]+aV[i+aW]+aV[i+aW+1])/4f;
			}
		}
		return aRes;
	}

	/** Fits of the parallax against the depth on block matches between the views {left -> right, right -> left}, null if too few matches */
	double[][] calibrate(float[] aLumL,float[] aLumR,float[] aZL,float[] aZR,int aW,int aH) {
		int aW2 = aW/2, aH2 = aH/2;
		float[] aGL = half(aLumL, aW, aH);
		float[] aGR = half(aLumR, aW, aH);
		int aMaxD = Math.max(8, aW2/6);
		int r = PATCH;
		int aNbRows = (aH2-2*r-2)/STEP;
		Vector<double[]> aM = new Vector<double[]>();
		Par.rows(Math.max(0, aNbRows), (aR0, aR1) -> {
			Vector<double[]> aLoc = new Vector<double[]>();
			int aN = (2*r+1)*(2*r+1);
			double[] aScores = new double[2*aMaxD+1];
			for(int k = aR0;k < aR1;k++) {
				int y = r+1+k*STEP;
				for(int x = r;x < aW2-r;x += STEP) {
					double aS = 0, aS2 = 0;
					for(int j = -r;j <= r;j++) {
						for(int i = -r;i <= r;i++) {
							double g = aGL[(y+j)*aW2+x+i];
							aS += g;
							aS2 += g*g;
						}
					}
					double aMean = aS/aN;
					double aVar = aS2/aN-aMean*aMean;
					if(aVar < MIN_STD*MIN_STD) {
						continue;
					}
					double aStd = Math.sqrt(aVar);
					double aBest = -2, aSecond = -2;
					int aBD = 0, aBY = 0;
					for(int dy = -1;dy <= 1;dy++) {
						for(int d = -aMaxD;d <= aMaxD;d++) {
							int xr = x+d;
							double aSc = -2;
							if(xr-r >= 0 && xr+r < aW2) {
								double aSR = 0, aSR2 = 0, aSLR = 0;
								for(int j = -r;j <= r;j++) {
									int aRowL = (y+j)*aW2+x, aRowR = (y+dy+j)*aW2+xr;
									for(int i = -r;i <= r;i++) {
										double gl = aGL[aRowL+i], gr = aGR[aRowR+i];
										aSR += gr;
										aSR2 += gr*gr;
										aSLR += gl*gr;
									}
								}
								double aMR = aSR/aN;
								double aVR = aSR2/aN-aMR*aMR;
								if(aVR > 1e-8) {
									aSc = (aSLR/aN-aMean*aMR)/(aStd*Math.sqrt(aVR));
								}
							}
							if(dy == 0) {
								aScores[d+aMaxD] = aSc;
							}
							if(aSc > aBest) {
								if(Math.abs(d-aBD) > 2 || dy != aBY) {
									aSecond = aBest;
								}
								aBest = aSc;
								aBD = d;
								aBY = dy;
							}
							else if(aSc > aSecond && (Math.abs(d-aBD) > 2 || dy != aBY)) {
								aSecond = aSc;
							}
						}
					}
					if(aBest < MIN_NCC || aBest-aSecond < UNIQUE_GAP) {
						continue;
					}
					double aSub = aBD;
					if(aBY == 0 && aBD > -aMaxD && aBD < aMaxD) {
						double c0 = aScores[aBD-1+aMaxD], c1 = aScores[aBD+aMaxD], c2 = aScores[aBD+1+aMaxD];
						double aDen = c0-2*c1+c2;
						if(c0 > -2 && c2 > -2 && aDen < 0) {
							aSub = aBD+0.5*(c0-c2)/aDen;
						}
					}
					int xl = 2*x, yl = 2*y;
					int xr = (int)Math.round(2*(x+aSub)), yr = 2*(y+aBY);
					if(xr < 0 || xr >= aW || yr < 0 || yr >= aH) {
						continue;
					}
					aLoc.add(new double[] {2*aSub, 2*aBY, aZL[yl*aW+xl], aZR[yr*aW+xr]});
				}
			}
			synchronized(aM) {
				aM.addAll(aLoc);
			}
		});
		if(aM.size() < MIN_MATCHES) {
			return null;
		}
		double[] aLR = fit(aM, 2, 1);
		double[] aRL = fit(aM, 3, -1);
		double[] aDys = new double[aM.size()];
		for(int i = 0;i < aDys.length;i++) {
			aDys[i] = aM.get(i)[1];
		}
		Arrays.sort(aDys);
		aLR[2] = aDys[aDys.length/2];
		aRL[2] = -aLR[2];
		lastMatches = aM.size();
		return new double[][] {aLR, aRL};
	}

	/**
	 * Pixels of the left view without match in the right one with these fits (the check of fill())
	 */
	static boolean[] holes(float[] aZ,float[] aOZ,double[] aFit,double[] aBack,int aW,int aH) {
		boolean[] aRes = new boolean[aW*aH];
		int aDy = (int)Math.round(aFit[2]);
		double aTol = Math.max(1.5, aW/500.0);
		Par.rows(aH, (aY0, aY1) -> {
			for(int y = aY0;y < aY1;y++) {
				int yo = y+aDy;
				for(int x = 0;x < aW;x++) {
					int i = y*aW+x;
					boolean aOk = false;
					if(yo >= 0 && yo < aH) {
						double xo = x+aFit[0]+aFit[1]*aZ[i];
						if(xo >= 0 && xo <= aW-1) {
							int xi = (int)xo;
							int io = yo*aW+(xo-xi < 0.5 ? xi : Math.min(aW-1, xi+1));
							aOk = Math.abs(xo+aBack[0]+aBack[1]*aOZ[io]-x) <= aTol;
						}
					}
					aRes[i] = !aOk;
				}
			}
		});
		return aRes;
	}

	/** Part of the pixels that are not holes */
	static double keptPart(boolean[] aHoles) {
		int aN = 0;
		for(boolean aB : aHoles) {
			if(aB) {
				aN++;
			}
		}
		return 1-aN/(double)aHoles.length;
	}

	/**
	 * Difference of the holes of two images: pixels hole in one only, part of the pixels hole in either (0 when
	 * neither has holes worth counting)
	 */
	static double holesDiff(boolean[] aA,boolean[] aB) {
		long aXor = 0, aUnion = 0;
		for(int i = 0;i < aA.length;i++) {
			if(aA[i] || aB[i]) {
				aUnion++;
				if(aA[i] != aB[i]) {
					aXor++;
				}
			}
		}
		return aUnion < aA.length/1000 ? 0 : aXor/(double)aUnion;
	}

	/** Robust line fit of aSign * the parallax (column 0) against the depth (column aZ): {a, b, 0} */
	static double[] fit(Vector<double[]> aM,int aZ,int aSign) {
		int n = aM.size();
		boolean[] aIn = new boolean[n];
		Arrays.fill(aIn, true);
		double a = 0, b = 0;
		for(int it = 0;it < 5;it++) {
			double sz = 0, sd = 0, szz = 0, szd = 0;
			int c = 0;
			for(int i = 0;i < n;i++) {
				if(!aIn[i]) {
					continue;
				}
				double z = aM.get(i)[aZ], d = aSign*aM.get(i)[0];
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
			double[] aRes = new double[c];
			int k = 0;
			for(int i = 0;i < n;i++) {
				if(aIn[i]) {
					aRes[k++] = Math.abs(aSign*aM.get(i)[0]-a-b*aM.get(i)[aZ]);
				}
			}
			Arrays.sort(aRes);
			double aTh = Math.max(1, 2.5*aRes[c/2]);
			for(int i = 0;i < n;i++) {
				aIn[i] = Math.abs(aSign*aM.get(i)[0]-a-b*aM.get(i)[aZ]) <= aTh;
			}
		}
		//Quality: part of the matches kept by the fit, median distance to the line (pixels)
		int aKept = 0;
		double[] aAll = new double[n];
		for(int i = 0;i < n;i++) {
			aAll[i] = Math.abs(aSign*aM.get(i)[0]-a-b*aM.get(i)[aZ]);
			if(aIn[i]) {
				aKept++;
			}
		}
		Arrays.sort(aAll);
		return new double[] {a, b, 0, n == 0 ? 0 : aKept/(double)n, n == 0 ? 0 : aAll[n/2]};
	}
}
