package com.cubaix.TDenlive.processors;

import java.awt.image.BufferedImage;

import com.cubaix.TDenlive.TDenlive;
import com.cubaix.TDenlive.medias.Media;
import com.cubaix.TDenlive.processors.ImageParamProcessor.Planes;

/**
 * Vectorscope of each view (as the ffmpeg vectorscope filter): the chrominance (Cb, Cr of
 * Rec. 709) of the pixels, angle = hue, distance to the center = saturation, each point in
 * the average color of its pixels. Circle of the full saturation, targets of the colors at
 * 75 % (R, Mg, B, Cy, G, Yl) and line of the skin tones; zoom x2 for little saturated images
 */
public class Vectorscope extends ImageMonitor {
	static final int S = 200, GAP = 6;
	/** A skin tone (direction of the skin line) */
	static final double[] SKIN = {0.87, 0.67, 0.55};

	public Vectorscope(TDenlive aTDe, Media aTargetMedia) {
		super(aTDe, aTargetMedia);
	}

	@Override
	public String getClassName() {return "Vectorscope";}

	@Override
	void setDescr() {
		super.setDescr();
		icon = "starsColor.gif";
	}

	@Override
	public String[] modes() {return new String[] {"Zoom1", "Zoom2"};}

	@Override
	public int drawingHeight() {return 160;}

	@Override
	protected boolean sideBySide() {return true;}

	@Override
	protected BufferedImage drawScope(Planes aL, Planes aR, int aStep) {
		int aW = 2*S+GAP;
		BufferedImage aBI = black(aW, S);
		int[] aPix = pixels(aBI);
		drawView(aPix, aW, aL, aStep, 0);
		if(aR != null) {
			drawView(aPix, aW, aR, aStep, S+GAP);
		}
		return aBI;
	}

	static double cb(double r,double g,double b) {
		return (b-(0.2126*r+0.7152*g+0.0722*b))/1.8556;
	}

	static double cr(double r,double g,double b) {
		return (r-(0.2126*r+0.7152*g+0.0722*b))/1.5748;
	}

	void drawView(int[] aPix,int aW,Planes aP,int aStep,int aX0) {
		double aZoom = mode == 1 ? 2 : 1;
		double aRad = (S/2-2)/0.5*aZoom;//Pixels per unit of Cb / Cr (0.5 = edge)
		int aC = S/2;
		int[] aCounts = new int[S*S];
		float[] aSR = new float[S*S], aSG = new float[S*S], aSB = new float[S*S];
		int aTotal = 0;
		for(int y = 0;y < aP.h;y += aStep) {
			for(int x = 0;x < aP.w;x += aStep) {
				int p = y*aP.w+x;
				float r = Math.min(1f, aP.r[p]), g = Math.min(1f, aP.g[p]), b = Math.min(1f, aP.b[p]);
				int aU = aC+(int)Math.round(cb(r, g, b)*aRad);
				int aV = aC-(int)Math.round(cr(r, g, b)*aRad);
				if(aU < 0 || aU >= S || aV < 0 || aV >= S) {
					continue;
				}
				int q = aV*S+aU;
				aCounts[q]++;
				aSR[q] += r;
				aSG[q] += g;
				aSB[q] += b;
				aTotal++;
			}
		}
		//Graticule: circle of the full saturation (in zoom 1), axes, skin line
		int aGrid = 0xFF383838;
		for(int i = 0;i < S;i++) {
			aPix[aC*aW+aX0+i] = aGrid;
			aPix[i*aW+aX0+aC] = aGrid;
		}
		double aCircle = 0.5*aRad/aZoom*Math.min(1, aZoom);
		for(int a = 0;a < 720;a++) {
			double aA = a*Math.PI/360;
			plot(aPix, aW, aX0, aC+(int)Math.round(Math.cos(aA)*aCircle), aC+(int)Math.round(Math.sin(aA)*aCircle), 0xFF505050);
		}
		double aSkinU = cb(SKIN[0], SKIN[1], SKIN[2]), aSkinV = cr(SKIN[0], SKIN[1], SKIN[2]);
		double aSkinN = Math.hypot(aSkinU, aSkinV);
		for(int d = 0;d < S/2;d++) {
			plot(aPix, aW, aX0, aC+(int)Math.round(aSkinU/aSkinN*d), aC-(int)Math.round(aSkinV/aSkinN*d), 0xFF5A4A3A);
		}
		//Targets of the colors at 75 %
		double[][] aTargets = {{0.75, 0, 0}, {0.75, 0, 0.75}, {0, 0, 0.75}, {0, 0.75, 0.75}, {0, 0.75, 0}, {0.75, 0.75, 0}};
		for(double[] aT : aTargets) {
			int aU = aC+(int)Math.round(cb(aT[0], aT[1], aT[2])*aRad);
			int aV = aC-(int)Math.round(cr(aT[0], aT[1], aT[2])*aRad);
			int aCol = rgb(aT[0]/0.75*0.6, aT[1]/0.75*0.6, aT[2]/0.75*0.6);
			for(int d = -4;d <= 4;d++) {
				plot(aPix, aW, aX0, aU+d, aV-4, aCol);
				plot(aPix, aW, aX0, aU+d, aV+4, aCol);
				plot(aPix, aW, aX0, aU-4, aV+d, aCol);
				plot(aPix, aW, aX0, aU+4, aV+d, aCol);
			}
		}
		//Points: brightness = density of the pixels (logarithmic, relative to the densest cells:
		//the 99th percentile of the cells reached, so that a few full cells don't darken all)
		int aNbCells = 0;
		for(int q = 0;q < S*S;q++) {
			if(aCounts[q] > 0) {
				aNbCells++;
			}
		}
		if(aNbCells == 0) {
			return;
		}
		int[] aSorted = new int[aNbCells];
		for(int q = 0, i = 0;q < S*S;q++) {
			if(aCounts[q] > 0) {
				aSorted[i++] = aCounts[q];
			}
		}
		java.util.Arrays.sort(aSorted);
		double aRef = Math.log1p(Math.max(2, aSorted[Math.min(aNbCells-1, (int)(aNbCells*0.99))]));
		for(int q = 0;q < S*S;q++) {
			int aN = aCounts[q];
			if(aN == 0) {
				continue;
			}
			double aI = 0.12+0.88*Math.min(1, Math.log1p(aN)/aRef);
			//Color of the pixels of the cell, whitened in the densest cells (as a phosphor)
			double r = aSR[q]/aN, g = aSG[q]/aN, b = aSB[q]/aN;
			double aM = Math.max(0.05, Math.max(r, Math.max(g, b)));
			double aWhite = Math.max(0, aI-0.75)*1.6;
			aPix[(q/S)*aW+aX0+q%S] = rgb((r/aM*(1-aWhite)+aWhite)*aI, (g/aM*(1-aWhite)+aWhite)*aI, (b/aM*(1-aWhite)+aWhite)*aI);
		}
	}

	static void plot(int[] aPix,int aW,int aX0,int aX,int aY,int aColor) {
		if(aX < 0 || aX >= S || aY < 0 || aY >= S) {
			return;
		}
		aPix[aY*aW+aX0+aX] = aColor;
	}
}
