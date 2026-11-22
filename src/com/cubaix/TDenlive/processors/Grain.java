package com.cubaix.TDenlive.processors;

import com.cubaix.TDenlive.TDenlive;
import com.cubaix.TDenlive.medias.Media;

/**
 * Film grain: a noise of grains of the given size (pixels of an image of REF_H lines), stronger
 * in the midtones, new at each image of the montage (or fixed). The same grain in both views
 * (different grains would make a binocular rivalry), shifted by the parallax in the right one
 * so that it can be put in front of or behind the screen
 */
public class Grain extends ImageParamProcessor {
	static final Param[] PARAMS = {
			Param.range("strength", 0, 0.25, 1, 100),
			Param.range("size", 1, 1.5, 5, 10),
			Param.range("parallax", -20, 0, 20, 10),
			Param.choice("colored", 0, "Mono", "Color"),
			Param.choice("animated", 0, "Yes", "No"),
	};
	static final int STRENGTH = 0, SIZE = 1, PARALLAX = 2, COLORED = 3, ANIMATED = 4;

	public Grain(TDenlive aTDe, Media aTargetMedia) {
		super(aTDe, aTargetMedia);
	}

	@Override
	public String getClassName() {return "Grain";}

	@Override
	void setDescr() {
		super.setDescr();
		icon = "starsColor.gif";
	}

	@Override
	public Param[] params() {return PARAMS;}

	@Override
	protected boolean isNeutral(double[] aV) {
		return aV[STRENGTH] <= 0;
	}

	/** Noise -1..1 (triangular) of the cell aX, aY of the image aSeed, channel aC */
	static float noise(int aX,int aY,int aSeed,int aC) {
		int aH = aX*374761393+aY*668265263+aSeed*1274126177+aC*1911520717;
		aH = (aH^(aH>>>13))*1274126177;
		aH ^= aH>>>16;
		int aH2 = aH*1664525+1013904223;
		aH2 ^= aH2>>>15;
		return ((aH & 0xFFFF)+(aH2 & 0xFFFF))/65535.0f-1.0f;
	}

	@Override
	protected void process(Planes aP, double[] aV, boolean aLeft, double aScale, int aProcessingMode, long aTime) {
		int aW = aP.w, aH = aP.h;
		double aCell = Math.max(0.5, aV[SIZE]*aScale);
		double aShift = aLeft ? 0 : aV[PARALLAX]*aScale;
		float aAmp = (float)(aV[STRENGTH]*0.25);
		boolean aColor = (int)aV[COLORED] == 1;
		double aFps = tde.config.outFps > 0 ? tde.config.outFps : 30;
		int aSeed = (int)aV[ANIMATED] == 0 ? (int)Math.floor(keyTime(aTime)*aFps/1000.0) : 0;
		float aTop = aP.top();
		float[] aN = new float[3];
		for(int y = 0;y < aH;y++) {
			double aGY = y/aCell;
			int aY0 = (int)Math.floor(aGY);
			float aFY = (float)(aGY-aY0);
			for(int x = 0;x < aW;x++) {
				double aGX = (x+aShift)/aCell;
				int aX0 = (int)Math.floor(aGX);
				float aFX = (float)(aGX-aX0);
				int p = y*aW+x;
				float aL = Math.min(1f, 0.2126f*aP.r[p]+0.7152f*aP.g[p]+0.0722f*aP.b[p]);
				//Stronger in the midtones (as on a film)
				float aWeight = aAmp*(0.35f+2.6f*aL*(1-aL));
				int aNbC = aColor ? 3 : 1;
				for(int c = 0;c < aNbC;c++) {
					float aN00 = noise(aX0, aY0, aSeed, c), aN10 = noise(aX0+1, aY0, aSeed, c);
					float aN01 = noise(aX0, aY0+1, aSeed, c), aN11 = noise(aX0+1, aY0+1, aSeed, c);
					aN[c] = ((aN00*(1-aFX)+aN10*aFX)*(1-aFY)+(aN01*(1-aFX)+aN11*aFX)*aFY)*aWeight;
				}
				if(!aColor) {
					aN[1] = aN[2] = aN[0];
				}
				aP.r[p] = Math.max(0f, Math.min(aTop, aP.r[p]+aN[0]));
				aP.g[p] = Math.max(0f, Math.min(aTop, aP.g[p]+aN[1]));
				aP.b[p] = Math.max(0f, Math.min(aTop, aP.b[p]+aN[2]));
			}
		}
	}
}
