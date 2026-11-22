package com.cubaix.TDenlive.processors;

import com.cubaix.TDenlive.TDenlive;
import com.cubaix.TDenlive.medias.Media;

/**
 * Vignetting, as the ffmpeg vignette filter: the image is darkened by cos(angle x d)^4, d =
 * distance to the center / distance of the corners (a circle, or an ellipse of the shape of
 * the frame). Same place in both views: the vignette is on the plane of the screen
 */
public class Vignette extends ImageParamProcessor {
	static final Param[] PARAMS = {
			Param.range("angle", 0, 36, 90, 1),
			Param.range("x", -0.5, 0, 0.5, 200),
			Param.range("y", -0.5, 0, 0.5, 200),
			Param.choice("shape", 0, "Circle", "Frame"),
	};
	static final int ANGLE = 0, X = 1, Y = 2, SHAPE = 3;

	public Vignette(TDenlive aTDe, Media aTargetMedia) {
		super(aTDe, aTargetMedia);
	}

	@Override
	public String getClassName() {return "Vignette";}

	@Override
	void setDescr() {
		super.setDescr();
		icon = "rectFrame.gif";
	}

	@Override
	public Param[] params() {return PARAMS;}

	@Override
	protected boolean isNeutral(double[] aV) {
		return aV[ANGLE] <= 0;
	}

	@Override
	protected void process(Planes aP, double[] aV, boolean aLeft, double aScale, int aProcessingMode, long aTime) {
		int aW = aP.w, aH = aP.h;
		double aAngle = Math.toRadians(aV[ANGLE]);
		double aX0 = aW*(0.5+aV[X]), aY0 = aH*(0.5+aV[Y]);
		boolean aFrame = (int)aV[SHAPE] == 1;
		//Circle: as ffmpeg (distance of the corners); frame: x and y normalized by the half sizes
		double aDMax = Math.hypot(aW/2.0, aH/2.0);
		double aSX = aFrame ? aDMax/(aW/2.0) : 1, aSY = aFrame ? aDMax/(aH/2.0) : 1;
		for(int y = 0;y < aH;y++) {
			double aDY = (y+0.5-aY0)*aSY;
			for(int x = 0;x < aW;x++) {
				double aDX = (x+0.5-aX0)*aSX;
				double aA = Math.min(Math.PI/2, aAngle*Math.sqrt(aDX*aDX+aDY*aDY)/aDMax);
				double aC = Math.cos(aA);
				float aF = (float)(aC*aC*aC*aC);
				int p = y*aW+x;
				aP.r[p] *= aF;
				aP.g[p] *= aF;
				aP.b[p] *= aF;
			}
		}
	}
}
