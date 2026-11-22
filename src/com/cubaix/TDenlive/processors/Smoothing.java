package com.cubaix.TDenlive.processors;

import com.cubaix.TDenlive.TDenlive;
import com.cubaix.TDenlive.medias.Media;

/**
 * Edge-preserving smoothing (skin, flat areas) by a guided filter (He et al.) guided by the
 * luminance: the variations smaller than the smoothness are flattened over the radius, the
 * edges (larger variations) are kept. Same role as the ffmpeg bilateral filter, in O(1) per
 * pixel (box blurs)
 */
public class Smoothing extends ImageParamProcessor {
	static final Param[] PARAMS = {
			Param.range("radius", 1, 8, 50, 1),
			Param.range("smooth", 0.01, 0.4, 1, 100),
			Param.range("mix", 0, 1, 1, 100),
	};
	static final int RADIUS = 0, SMOOTH = 1, MIX = 2;

	public Smoothing(TDenlive aTDe, Media aTargetMedia) {
		super(aTDe, aTargetMedia);
	}

	@Override
	public String getClassName() {return "Smoothing";}

	@Override
	void setDescr() {
		super.setDescr();
		icon = "spiral.gif";
	}

	@Override
	public Param[] params() {return PARAMS;}

	@Override
	protected boolean isNeutral(double[] aV) {
		return aV[MIX] <= 0;
	}

	@Override
	protected void process(Planes aP, double[] aV, boolean aLeft, double aScale, int aProcessingMode, long aTime) {
		int aW = aP.w, aH = aP.h, aN = aW*aH;
		int aR = Math.max(1, (int)Math.round(aV[RADIUS]*aScale));
		float aEps = (float)(aV[SMOOTH]*aV[SMOOTH]*0.01);
		float aMix = (float)aV[MIX];
		float[] aI = aP.luma();
		float[] aII = new float[aN];
		for(int p = 0;p < aN;p++) {
			aII[p] = aI[p]*aI[p];
		}
		float[] aMeanI = boxBlur(aI, aW, aH, aR);
		float[] aVarI = boxBlur(aII, aW, aH, aR);
		for(int p = 0;p < aN;p++) {
			aVarI[p] -= aMeanI[p]*aMeanI[p];
		}
		float[][] aChannels = {aP.r, aP.g, aP.b};
		float[] aIP = new float[aN];
		float[] aA = new float[aN], aB = new float[aN];
		for(float[] aC : aChannels) {
			for(int p = 0;p < aN;p++) {
				aIP[p] = aI[p]*aC[p];
			}
			float[] aMeanP = boxBlur(aC, aW, aH, aR);
			float[] aMeanIP = boxBlur(aIP, aW, aH, aR);
			for(int p = 0;p < aN;p++) {
				float aCov = aMeanIP[p]-aMeanI[p]*aMeanP[p];
				aA[p] = aCov/(aVarI[p]+aEps);
				aB[p] = aMeanP[p]-aA[p]*aMeanI[p];
			}
			float[] aMeanA = boxBlur(aA, aW, aH, aR);
			float[] aMeanB = boxBlur(aB, aW, aH, aR);
			for(int p = 0;p < aN;p++) {
				float aQ = aMeanA[p]*aI[p]+aMeanB[p];
				aC[p] = Math.max(0f, aC[p]+(aQ-aC[p])*aMix);
			}
		}
	}
}
