package com.cubaix.TDenlive.processors;

import com.cubaix.TDenlive.TDenlive;
import com.cubaix.TDenlive.medias.Media;

/**
 * Pixelation of the whole image or of a zone (rectangle or ellipse, center and size in % of
 * the frame, keyed: it can follow a face), as the ffmpeg pixelize filter (average of each
 * block). The zone and its blocks are shifted by the parallax in the right view, to put them
 * at the depth of what they hide
 */
public class Pixelize extends ImageParamProcessor {
	static final Param[] PARAMS = {
			Param.range("blockSize", 2, 24, 200, 1),
			Param.range("x", -0.5, 0, 0.5, 200),
			Param.range("y", -0.5, 0, 0.5, 200),
			Param.range("w", 0.01, 1, 1, 100),
			Param.range("h", 0.01, 1, 1, 100),
			Param.range("parallax", -0.1, 0, 0.1, 1000),
			Param.choice("shape", 0, "Rect", "Ellipse"),
	};
	static final int SIZE = 0, X = 1, Y = 2, W = 3, H = 4, PARALLAX = 5, SHAPE = 6;

	public Pixelize(TDenlive aTDe, Media aTargetMedia) {
		super(aTDe, aTargetMedia);
	}

	@Override
	public String getClassName() {return "Pixelize";}

	@Override
	void setDescr() {
		super.setDescr();
		icon = "colorObjects.png";
	}

	@Override
	public Param[] params() {return PARAMS;}

	@Override
	protected void process(Planes aP, double[] aV, boolean aLeft, double aScale, int aProcessingMode, long aTime) {
		int aW = aP.w, aH = aP.h;
		int aBlock = Math.max(1, (int)Math.round(aV[SIZE]*aScale));
		double aCX = aW*(0.5+aV[X]+(aLeft ? 0 : aV[PARALLAX])), aCY = aH*(0.5+aV[Y]);
		double aHW = aW*aV[W]/2, aHH = aH*aV[H]/2;
		boolean aEllipse = (int)aV[SHAPE] == 1;
		int aX0 = (int)Math.floor(aCX-aHW), aY0 = (int)Math.floor(aCY-aHH);
		int aX1 = (int)Math.ceil(aCX+aHW), aY1 = (int)Math.ceil(aCY+aHH);
		float[][] aCs = {aP.r, aP.g, aP.b, aP.a};
		//Blocks anchored at the corner of the zone
		for(int aBY = aY0;aBY < aY1;aBY += aBlock) {
			int aYS = Math.max(0, aBY), aYE = Math.min(aH, Math.min(aY1, aBY+aBlock));
			if(aYE <= aYS) {
				continue;
			}
			for(int aBX = aX0;aBX < aX1;aBX += aBlock) {
				int aXS = Math.max(0, aBX), aXE = Math.min(aW, Math.min(aX1, aBX+aBlock));
				if(aXE <= aXS) {
					continue;
				}
				double[] aSum = new double[4];
				int aN = 0;
				for(int y = aYS;y < aYE;y++) {
					for(int x = aXS;x < aXE;x++) {
						int p = y*aW+x;
						for(int c = 0;c < 4;c++) {
							aSum[c] += aCs[c][p];
						}
						aN++;
					}
				}
				for(int y = aYS;y < aYE;y++) {
					for(int x = aXS;x < aXE;x++) {
						if(aEllipse) {
							double aDX = (x+0.5-aCX)/aHW, aDY = (y+0.5-aCY)/aHH;
							if(aDX*aDX+aDY*aDY > 1) {
								continue;
							}
						}
						int p = y*aW+x;
						for(int c = 0;c < 4;c++) {
							aCs[c][p] = (float)(aSum[c]/aN);
						}
					}
				}
			}
		}
	}
}
