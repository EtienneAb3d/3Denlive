package com.cubaix.TDenlive.processors;

import com.cubaix.TDenlive.TDenlive;
import com.cubaix.TDenlive.medias.Media;

/**
 * Color temperature, as the ffmpeg colortemperature filter: the image is multiplied by the
 * color of a black body at that temperature (lower = warmer, higher = cooler), divided by
 * that of 6500 K so that 6500 K leaves the image unchanged; a tint (green / magenta) and the
 * preservation of the lightness (max + min of R G B kept)
 */
public class ColorTemperature extends ImageParamProcessor {
	static final Param[] PARAMS = {
			Param.range("kelvin", 1000, 6500, 15000, 1),
			Param.range("tint", -1, 0, 1, 100),
			Param.choice("preserve", 0, "Yes", "No"),
	};
	static final int KELVIN = 0, TINT = 1, PRESERVE = 2;

	public ColorTemperature(TDenlive aTDe, Media aTargetMedia) {
		super(aTDe, aTargetMedia);
	}

	@Override
	public String getClassName() {return "ColorTemperature";}

	@Override
	void setDescr() {
		super.setDescr();
		icon = "colorPalette.gif";
	}

	@Override
	public Param[] params() {return PARAMS;}

	@Override
	protected boolean isNeutral(double[] aV) {
		return Math.abs(aV[KELVIN]-6500) < 0.5 && aV[TINT] == 0;
	}

	/** Color of a black body (Tanner Helland's fit, as ffmpeg) */
	static double[] kelvin2rgb(double aK) {
		double aKelvin = aK/100.0;
		double[] aRGB = new double[3];
		if(aKelvin <= 66.0) {
			aRGB[0] = 1.0;
			aRGB[1] = 0.39008157876901960784*Math.log(aKelvin)-0.63184144378862745098;
		}
		else {
			double aT = Math.max(aKelvin-60.0, 0.0);
			aRGB[0] = 1.29293618606274509804*Math.pow(aT, -0.1332047592);
			aRGB[1] = 1.12989086089529411765*Math.pow(aT, -0.0755148492);
		}
		if(aKelvin >= 66.0) {
			aRGB[2] = 1.0;
		}
		else if(aKelvin <= 19.0) {
			aRGB[2] = 0.0;
		}
		else {
			aRGB[2] = 0.54320678911019607843*Math.log(aKelvin-10.0)-1.19625408914;
		}
		for(int c = 0;c < 3;c++) {
			aRGB[c] = Math.max(0, Math.min(1, aRGB[c]));
		}
		return aRGB;
	}

	@Override
	protected void process(Planes aP, double[] aV, boolean aLeft, double aScale, int aProcessingMode, long aTime) {
		double[] aC = kelvin2rgb(aV[KELVIN]);
		double[] aRef = kelvin2rgb(6500);
		double aTint = aV[TINT];
		float aMR = (float)(aC[0]/aRef[0]*(1+0.15*aTint));
		float aMG = (float)(aC[1]/aRef[1]*(1-0.3*aTint));
		float aMB = (float)(aC[2]/aRef[2]*(1+0.15*aTint));
		boolean aPreserve = (int)aV[PRESERVE] == 0;
		for(int p = 0;p < aP.w*aP.h;p++) {
			float r = aP.r[p], g = aP.g[p], b = aP.b[p];
			float nr = r*aMR, ng = g*aMG, nb = b*aMB;
			if(aPreserve) {
				float aL0 = Math.max(r, Math.max(g, b))+Math.min(r, Math.min(g, b));
				float aL1 = Math.max(nr, Math.max(ng, nb))+Math.min(nr, Math.min(ng, nb));
				if(aL1 > 0) {
					float aL = aL0/aL1;
					nr *= aL;
					ng *= aL;
					nb *= aL;
				}
			}
			float aTop = aP.top();
			aP.r[p] = Math.min(aTop, nr);
			aP.g[p] = Math.min(aTop, ng);
			aP.b[p] = Math.min(aTop, nb);
		}
	}
}
