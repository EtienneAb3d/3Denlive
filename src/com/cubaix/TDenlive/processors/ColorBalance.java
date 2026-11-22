package com.cubaix.TDenlive.processors;

import com.cubaix.TDenlive.TDenlive;
import com.cubaix.TDenlive.medias.Media;

/**
 * Color balance of the shadows, midtones and highlights, as the ffmpeg colorbalance filter
 * (-1..1 per channel and tone range; same results), optional preservation of the lightness
 * (HSL lightness of the source)
 */
public class ColorBalance extends ImageParamProcessor {
	static final Param[] PARAMS = {
			Param.range("rs", -1, 0, 1, 100).header("Shadows").letter("Generic.RedLetter").tip("ColorBalance_Shadows"),
			Param.range("gs", -1, 0, 1, 100).header(null).letter("Generic.GreenLetter").tip("ColorBalance_Shadows"),
			Param.range("bs", -1, 0, 1, 100).header(null).letter("Generic.BlueLetter").tip("ColorBalance_Shadows"),
			Param.range("rm", -1, 0, 1, 100).header("Midtones").letter("Generic.RedLetter").tip("ColorBalance_Midtones"),
			Param.range("gm", -1, 0, 1, 100).header(null).letter("Generic.GreenLetter").tip("ColorBalance_Midtones"),
			Param.range("bm", -1, 0, 1, 100).header(null).letter("Generic.BlueLetter").tip("ColorBalance_Midtones"),
			Param.range("rh", -1, 0, 1, 100).header("Highlights").letter("Generic.RedLetter").tip("ColorBalance_Highlights"),
			Param.range("gh", -1, 0, 1, 100).header(null).letter("Generic.GreenLetter").tip("ColorBalance_Highlights"),
			Param.range("bh", -1, 0, 1, 100).header(null).letter("Generic.BlueLetter").tip("ColorBalance_Highlights"),
			Param.choice("preserve", 1, "Yes", "No"),
	};
	static final int PRESERVE = 9;

	public ColorBalance(TDenlive aTDe, Media aTargetMedia) {
		super(aTDe, aTargetMedia);
	}

	@Override
	public String getClassName() {return "ColorBalance";}

	@Override
	void setDescr() {
		super.setDescr();
		icon = "starsColor.gif";
	}

	@Override
	public Param[] params() {return PARAMS;}

	@Override
	protected boolean isNeutral(double[] aV) {
		for(int p = 0;p < 9;p++) {
			if(aV[p] != 0) {
				return false;
			}
		}
		return true;
	}

	static float component(float aV,float aL,float aS,float aM,float aH,float aTop) {
		final float a = 4.f, b = 0.333f, scale = 0.7f;
		aS *= clip((b-aL)*a+0.5f, 0, 1)*scale;
		aM *= clip((aL-b)*a+0.5f, 0, 1)*clip((1.0f-aL-b)*a+0.5f, 0, 1)*scale;
		aH *= clip((aL+b-1)*a+0.5f, 0, 1)*scale;
		return clip(aV+aS+aM+aH, 0, aTop);
	}

	static float clip(float aV,float aMin,float aMax) {
		return aV < aMin ? aMin : (aV > aMax ? aMax : aV);
	}

	static float hfun(float aN,float aH,float aS,float aL) {
		float aA = aS*Math.min(aL, 1.f-aL);
		float aK = (aN+aH/30.f) % 12.f;
		return clip(aL-aA*Math.max(Math.min(Math.min(aK-3.f, 9.f-aK), 1), -1.f), 0, 1);
	}

	@Override
	protected void process(Planes aP, double[] aV, boolean aLeft, double aScale, int aProcessingMode, long aTime) {
		float rs = (float)aV[0], gs = (float)aV[1], bs = (float)aV[2];
		float rm = (float)aV[3], gm = (float)aV[4], bm = (float)aV[5];
		float rh = (float)aV[6], gh = (float)aV[7], bh = (float)aV[8];
		boolean aPreserve = (int)aV[PRESERVE] == 0;
		float aTop = aP.top();
		for(int p = 0;p < aP.w*aP.h;p++) {
			float r = aP.r[p], g = aP.g[p], b = aP.b[p];
			float aL = Math.max(r, Math.max(g, b))+Math.min(r, Math.min(g, b));
			r = component(r, aL, rs, rm, rh, aTop);
			g = component(g, aL, gs, gm, gh, aTop);
			b = component(b, aL, bs, bm, bh, aTop);
			if(aPreserve && aL <= 2) {
				//Hue and saturation (HSL) of the result, lightness of the source. Not as ffmpeg
				//preservel(), which makes the pure colors (a channel at 0 or 1) gray
				float aMax = Math.min(1f, Math.max(r, Math.max(g, b))), aMin = Math.min(r, Math.min(g, b));
				float aH = 0, aS = 0;
				float aL1 = (aMax+aMin)*0.5f;
				float aD = aMax-aMin;
				if(aD > 1e-6f) {
					if(aMax == r) {
						aH = 60.f*((g-b)/aD);
					}
					else if(aMax == g) {
						aH = 60.f*(2.f+(b-r)/aD);
					}
					else {
						aH = 60.f*(4.f+(r-g)/aD);
					}
					float aDen = 1.f-Math.abs(2*aL1-1);
					aS = aDen <= 1e-6f ? 0 : Math.min(1f, aD/aDen);
				}
				if(aH < 0) {
					aH += 360.f;
				}
				float aHalfL = aL*0.5f;
				r = hfun(0, aH, aS, aHalfL);
				g = hfun(8, aH, aS, aHalfL);
				b = hfun(4, aH, aS, aHalfL);
			}
			aP.r[p] = r;
			aP.g[p] = g;
			aP.b[p] = b;
		}
	}
}
