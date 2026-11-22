package com.cubaix.TDenlive.processors;

import com.cubaix.TDenlive.TDenlive;
import com.cubaix.TDenlive.medias.Media;

/**
 * Sharpening by unsharp mask (as the ffmpeg unsharp filter) on the luminance only (no
 * colored fringes): the details (luminance - its Gaussian blur of radius R) are added
 * x amount, those under the threshold (noise) progressively left out
 */
public class Sharpen extends ImageParamProcessor {
	static final Param[] PARAMS = {
			Param.range("amount", 0, 1, 5, 100),
			Param.range("radius", 0.3, 1.0, 10, 10),
			Param.range("threshold", 0, 0, 0.2, 1000),
	};
	static final int AMOUNT = 0, RADIUS = 1, THRESHOLD = 2;

	public Sharpen(TDenlive aTDe, Media aTargetMedia) {
		super(aTDe, aTargetMedia);
	}

	@Override
	public String getClassName() {return "Sharpen";}

	@Override
	void setDescr() {
		super.setDescr();
		icon = "binoculars.png";
	}

	@Override
	public Param[] params() {return PARAMS;}

	@Override
	protected boolean isNeutral(double[] aV) {
		return aV[AMOUNT] <= 0;
	}

	@Override
	protected void process(Planes aP, double[] aV, boolean aLeft, double aScale, int aProcessingMode, long aTime) {
		float[] aY = aP.luma();
		float[] aBlur = gaussBlur(aY, aP.w, aP.h, aV[RADIUS]*aScale);
		float aAmount = (float)aV[AMOUNT];
		double aTh = aV[THRESHOLD];
		float aTop = aP.top();
		for(int p = 0;p < aP.w*aP.h;p++) {
			float aD = aY[p]-aBlur[p];
			if(aTh > 0) {
				aD *= (float)smoothStep(aTh*0.5, aTh, Math.abs(aD));
			}
			float aAdd = aD*aAmount;
			aP.r[p] = Math.max(0f, Math.min(aTop, aP.r[p]+aAdd));
			aP.g[p] = Math.max(0f, Math.min(aTop, aP.g[p]+aAdd));
			aP.b[p] = Math.max(0f, Math.min(aTop, aP.b[p]+aAdd));
		}
	}
}
