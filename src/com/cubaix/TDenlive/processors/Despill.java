package com.cubaix.TDenlive.processors;

import com.cubaix.TDenlive.TDenlive;
import com.cubaix.TDenlive.medias.Media;

/**
 * Removes the green (or blue) cast left by a green screen on the edges and reflections of
 * the subject (after Chroma key), as the ffmpeg despill filter: the spill = the excess of the
 * key color over a mix of the two others, taken out of the key color, and added back as
 * brightness by the compensation
 */
public class Despill extends ImageParamProcessor {
	static final Param[] PARAMS = {
			Param.choice("keyColor", 0, "Green", "Blue"),
			Param.range("mix", 0, 0.5, 1, 100),
			Param.range("expand", 0, 0, 1, 100),
			Param.range("brightness", -1, 0, 1, 100),
	};
	static final int KEY = 0, MIX = 1, EXPAND = 2, BRIGHTNESS = 3;

	public Despill(TDenlive aTDe, Media aTargetMedia) {
		super(aTDe, aTargetMedia);
	}

	@Override
	public String getClassName() {return "Despill";}

	@Override
	void setDescr() {
		super.setDescr();
		icon = "colorGreen.gif";
	}

	@Override
	public Param[] params() {return PARAMS;}

	@Override
	protected void process(Planes aP, double[] aV, boolean aLeft, double aScale, int aProcessingMode, long aTime) {
		boolean aBlue = (int)aV[KEY] == 1;
		float aMix = (float)aV[MIX];
		float aFactor = (1-aMix)*(1-(float)aV[EXPAND]);
		float aBright = (float)aV[BRIGHTNESS];
		for(int p = 0;p < aP.w*aP.h;p++) {
			float r = aP.r[p], g = aP.g[p], b = aP.b[p];
			float aSpill = aBlue ? Math.max(b-(r*aMix+g*aFactor), 0f) : Math.max(g-(r*aMix+b*aFactor), 0f);
			if(aSpill <= 0) {
				continue;
			}
			float aAdd = aBright*aSpill;
			aP.r[p] = Math.max(0f, r+aAdd);
			aP.g[p] = Math.max(0f, g+aAdd-(aBlue ? 0 : aSpill));
			aP.b[p] = Math.max(0f, b+aAdd-(aBlue ? aSpill : 0));
		}
	}
}
