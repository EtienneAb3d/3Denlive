package com.cubaix.TDenlive.processors;

import java.awt.image.BufferedImage;

import com.cubaix.TDenlive.TDenlive;
import com.cubaix.TDenlive.medias.Media;
import com.cubaix.TDenlive.processors.ImageParamProcessor.Planes;

/**
 * Waveform of each view (as the ffmpeg waveform filter): for each column of the image, the
 * distribution of the levels from 0 (bottom) to 100 % (top) of the luminance, or of R, G and B
 * side by side (parade). Lines at 0, 25, 50, 75 and 100 %; the levels out of 0..100 % (HDR)
 * are clamped on the edges
 */
public class Waveform extends ImageMonitor {
	static final int W = 384, H = 128, GAP = 6;

	public Waveform(TDenlive aTDe, Media aTargetMedia) {
		super(aTDe, aTargetMedia);
	}

	@Override
	public String getClassName() {return "Waveform";}

	@Override
	void setDescr() {
		super.setDescr();
		icon = "spiral.gif";
	}

	@Override
	public String[] modes() {return new String[] {"Luma", "Parade"};}

	@Override
	public int drawingHeight() {return 170;}

	@Override
	protected BufferedImage drawScope(Planes aL, Planes aR, int aStep) {
		BufferedImage aBI = black(W, 2*H+GAP);
		int[] aPix = pixels(aBI);
		drawView(aPix, aL, aStep, 0);
		if(aR != null) {
			drawView(aPix, aR, aStep, H+GAP);
		}
		return aBI;
	}

	void drawView(int[] aPix,Planes aP,int aStep,int aY0) {
		boolean aParade = mode == 1;
		int aNbC = aParade ? 3 : 1;
		int aColW = W/aNbC;
		int[][] aCounts = new int[aNbC][aColW*H];
		int aRows = 0;
		for(int y = 0;y < aP.h;y += aStep) {
			aRows++;
			for(int x = 0;x < aP.w;x += aStep) {
				int p = y*aP.w+x;
				int aCol = Math.min(aColW-1, x*aColW/aP.w);
				if(aParade) {
					aCounts[0][level(aP.r[p])*aColW+aCol]++;
					aCounts[1][level(aP.g[p])*aColW+aCol]++;
					aCounts[2][level(aP.b[p])*aColW+aCol]++;
				}
				else {
					aCounts[0][level(0.2126f*aP.r[p]+0.7152f*aP.g[p]+0.0722f*aP.b[p])*aColW+aCol]++;
				}
			}
		}
		//Samples per cell of a uniform image
		double aPerCol = aRows*Math.max(1.0, (aP.w/(double)aStep)/aColW);
		double aK = 4.0*H/aPerCol;
		double[][] aColors = aParade ? new double[][] {{1, 0.25, 0.25}, {0.25, 1, 0.25}, {0.35, 0.45, 1}} : new double[][] {{0.55, 1, 0.6}};
		//Graticule
		for(int l = 0;l <= 4;l++) {
			int y = aY0+(H-1)-(int)Math.round(l*(H-1)/4.0);
			hLine(aPix, W, y, 0, W, l == 0 || l == 4 ? 0xFF505050 : 0xFF303030);
		}
		for(int c = 0;c < aNbC;c++) {
			for(int aL = 0;aL < H;aL++) {
				for(int aCol = 0;aCol < aColW;aCol++) {
					int aN = aCounts[c][aL*aColW+aCol];
					if(aN == 0) {
						continue;
					}
					double aV = 1-Math.exp(-aN*aK);
					add(aPix, (aY0+H-1-aL)*W+c*aColW+aCol, aColors[c][0]*aV, aColors[c][1]*aV, aColors[c][2]*aV);
				}
			}
		}
	}

	static int level(float aV) {
		int aL = (int)(aV*(H-1)+0.5f);
		return aL < 0 ? 0 : (aL >= H ? H-1 : aL);
	}
}
