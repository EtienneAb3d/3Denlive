package com.cubaix.TDenlive.processors;

import com.cubaix.TDenlive.TDenlive;
import com.cubaix.TDenlive.medias.Media;

/**
 * Tone curves: a preset of the ffmpeg curves filter (same points, natural cubic splines),
 * then a master curve through the user's shadows / midtones / highlights points
 * (outputs at 0.25 / 0.5 / 0.75), mixed with the image by the intensity
 */
public class Curves extends ImageParamProcessor {
	static final Param[] PARAMS = {
			Param.choice("preset", 0, "None", "Vintage", "CrossProcess", "ColorNegative", "Negative", "Darker", "Lighter"
					, "IncreaseContrast", "LinearContrast", "MediumContrast", "StrongContrast"),
			Param.range("amount", 0, 1, 1, 100),
			Param.range("shadows", -0.25, 0, 0.25, 400),
			Param.range("midtones", -0.25, 0, 0.25, 400),
			Param.range("highlights", -0.25, 0, 0.25, 400),
	};
	static final int PRESET = 0, AMOUNT = 1, SHADOWS = 2, MIDTONES = 3, HIGHLIGHTS = 4;

	/** Points of the presets of ffmpeg (vf_curves.c): {red, green, blue, master}, null = identity */
	static final String[][] PRESETS = {
			{null, null, null, null},
			{"0/0.11 0.42/0.51 1/0.95", "0/0 0.50/0.48 1/1", "0/0.22 0.49/0.44 1/0.8", null},
			{"0/0 0.25/0.156 0.501/0.501 0.686/0.745 1/1", "0/0 0.25/0.188 0.38/0.501 0.745/0.815 1/0.815", "0/0 0.231/0.094 0.709/0.874 1/1", null},
			{"0.129/1 0.466/0.498 0.725/0", "0.109/1 0.301/0.498 0.517/0", "0.098/1 0.235/0.498 0.423/0", null},
			{null, null, null, "0/1 1/0"},
			{null, null, null, "0/0 0.5/0.4 1/1"},
			{null, null, null, "0/0 0.4/0.5 1/1"},
			{null, null, null, "0/0 0.149/0.066 0.831/0.905 0.905/0.98 1/1"},
			{null, null, null, "0/0 0.305/0.286 0.694/0.713 1/1"},
			{null, null, null, "0/0 0.286/0.219 0.639/0.643 1/1"},
			{null, null, null, "0/0 0.301/0.196 0.592/0.6 0.686/0.737 1/1"},
	};
	static final int LUT = 1024;

	public Curves(TDenlive aTDe, Media aTargetMedia) {
		super(aTDe, aTargetMedia);
	}

	@Override
	public String getClassName() {return "Curves";}

	@Override
	void setDescr() {
		super.setDescr();
		icon = "colourPalette2.gif";
	}

	@Override
	public Param[] params() {return PARAMS;}

	@Override
	protected boolean isNeutral(double[] aV) {
		return aV[AMOUNT] <= 0 || ((int)aV[PRESET] == 0 && aV[SHADOWS] == 0 && aV[MIDTONES] == 0 && aV[HIGHLIGHTS] == 0);
	}

	/** Curve of LUT + 1 values on 0..1 through the points "x/y x/y..." (null: identity) */
	static float[] curve(String aPoints) {
		if(aPoints == null) {
			return null;
		}
		String[] aPs = aPoints.trim().split("\\s+");
		double[] aX = new double[aPs.length], aY = new double[aPs.length];
		for(int p = 0;p < aPs.length;p++) {
			String[] aXY = aPs[p].split("/");
			aX[p] = Double.parseDouble(aXY[0]);
			aY[p] = Double.parseDouble(aXY[1]);
		}
		return spline(aX, aY);
	}

	/** Natural cubic spline through the points (x increasing), sampled on 0..1, clamped to 0..1 */
	static float[] spline(double[] aX,double[] aY) {
		int aN = aX.length;
		double[] aM = new double[aN];//Second derivatives
		if(aN > 2) {
			double[] aA = new double[aN], aB = new double[aN], aC = new double[aN], aD = new double[aN];
			for(int i = 1;i < aN-1;i++) {
				double aH0 = aX[i]-aX[i-1], aH1 = aX[i+1]-aX[i];
				aA[i] = aH0;
				aB[i] = 2*(aH0+aH1);
				aC[i] = aH1;
				aD[i] = 6*((aY[i+1]-aY[i])/aH1-(aY[i]-aY[i-1])/aH0);
			}
			//Thomas algorithm on 1..n-2 (natural: m0 = mn-1 = 0)
			for(int i = 2;i < aN-1;i++) {
				double aW = aA[i]/aB[i-1];
				aB[i] -= aW*aC[i-1];
				aD[i] -= aW*aD[i-1];
			}
			for(int i = aN-2;i >= 1;i--) {
				aM[i] = (aD[i]-(i < aN-2 ? aC[i]*aM[i+1] : 0))/aB[i];
			}
		}
		float[] aOut = new float[LUT+1];
		int aSeg = 0;
		for(int s = 0;s <= LUT;s++) {
			double aT = s/(double)LUT;
			double aV;
			if(aT <= aX[0]) {
				aV = aY[0];
			}
			else if(aT >= aX[aN-1]) {
				aV = aY[aN-1];
			}
			else {
				while(aSeg < aN-2 && aT > aX[aSeg+1]) {
					aSeg++;
				}
				double aH = aX[aSeg+1]-aX[aSeg];
				double aA = (aX[aSeg+1]-aT)/aH, aB = (aT-aX[aSeg])/aH;
				aV = aA*aY[aSeg]+aB*aY[aSeg+1]+((aA*aA*aA-aA)*aM[aSeg]+(aB*aB*aB-aB)*aM[aSeg+1])*aH*aH/6.0;
			}
			aOut[s] = (float)Math.max(0, Math.min(1, aV));
		}
		return aOut;
	}

	/** Value of the curve aC at aV (0..1 interpolated, above 1: shifted) */
	static float apply(float[] aC,float aV) {
		if(aC == null) {
			return aV;
		}
		if(aV >= 1) {
			return aC[LUT]+(aV-1);
		}
		if(aV <= 0) {
			return aC[0];
		}
		float aF = aV*LUT;
		int aI = (int)aF;
		float aR = aF-aI;
		return aC[aI]*(1-aR)+aC[aI+1]*aR;
	}

	@Override
	protected void process(Planes aP, double[] aV, boolean aLeft, double aScale, int aProcessingMode, long aTime) {
		String[] aPreset = PRESETS[Math.max(0, Math.min(PRESETS.length-1, (int)aV[PRESET]))];
		float[] aRC = curve(aPreset[0]), aGC = curve(aPreset[1]), aBC = curve(aPreset[2]), aMC = curve(aPreset[3]);
		float[] aUser = null;
		if(aV[SHADOWS] != 0 || aV[MIDTONES] != 0 || aV[HIGHLIGHTS] != 0) {
			aUser = spline(new double[] {0, 0.25, 0.5, 0.75, 1}
					, new double[] {0, 0.25+aV[SHADOWS], 0.5+aV[MIDTONES], 0.75+aV[HIGHLIGHTS], 1});
		}
		float aMix = (float)aV[AMOUNT];
		for(int p = 0;p < aP.w*aP.h;p++) {
			float r = aP.r[p], g = aP.g[p], b = aP.b[p];
			//As ffmpeg: the master curve after the curve of the channel
			float nr = apply(aUser, apply(aMC, apply(aRC, r)));
			float ng = apply(aUser, apply(aMC, apply(aGC, g)));
			float nb = apply(aUser, apply(aMC, apply(aBC, b)));
			aP.r[p] = r+(nr-r)*aMix;
			aP.g[p] = g+(ng-g)*aMix;
			aP.b[p] = b+(nb-b)*aMix;
		}
	}
}
