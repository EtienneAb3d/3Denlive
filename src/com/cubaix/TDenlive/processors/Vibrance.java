package com.cubaix.TDenlive.processors;

import com.cubaix.TDenlive.TDenlive;
import com.cubaix.TDenlive.medias.Media;

/**
 * Vibrance (documented formula of the ffmpeg vibrance filter, whose output differs: it changes
 * the saturated colors most): the saturation is multiplied by 1 + i x (1 - sign(i) x (max - min)),
 * so that the little saturated colors are increased (i > 0) more than the saturated ones, and
 * that the saturated colors are decreased (i < 0) more than the others
 */
public class Vibrance extends ImageParamProcessor {
	static final Param[] PARAMS = {
			Param.range("intensity", -2, 0, 2, 100),
	};
	static final int INTENSITY = 0;

	public Vibrance(TDenlive aTDe, Media aTargetMedia) {
		super(aTDe, aTargetMedia);
	}

	@Override
	public String getClassName() {return "Vibrance";}

	@Override
	void setDescr() {
		super.setDescr();
		icon = "colorPalette.gif";
	}

	@Override
	public Param[] params() {return PARAMS;}

	@Override
	protected boolean isNeutral(double[] aV) {
		return aV[INTENSITY] == 0;
	}

	@Override
	protected void process(Planes aP, double[] aV, boolean aLeft, double aScale, int aProcessingMode, long aTime) {
		float aI = (float)aV[INTENSITY];
		float aSgn = Math.signum(aI);
		float aTop = aP.top();
		for(int p = 0;p < aP.w*aP.h;p++) {
			float r = aP.r[p], g = aP.g[p], b = aP.b[p];
			float aDiff = Math.min(1f, Math.max(r, Math.max(g, b))-Math.min(r, Math.min(g, b)));
			float aLuma = 0.2126f*r+0.7152f*g+0.0722f*b;
			float aF = 1.f+aI*(1.f-aSgn*aDiff);
			aP.r[p] = Math.max(0f, Math.min(aTop, aLuma+(r-aLuma)*aF));
			aP.g[p] = Math.max(0f, Math.min(aTop, aLuma+(g-aLuma)*aF));
			aP.b[p] = Math.max(0f, Math.min(aTop, aLuma+(b-aLuma)*aF));
		}
	}
}
