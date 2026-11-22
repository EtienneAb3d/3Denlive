package com.cubaix.TDenlive.processors;

import java.awt.image.BufferedImage;

import com.cubaix.TDenlive.TDenlive;
import com.cubaix.TDenlive.medias.Media;
import com.cubaix.TDenlive.processors.ImageParamProcessor.Planes;

/**
 * Histogram of each view (as the ffmpeg histogram filter): R, G, B superposed (additive
 * colors), or the luminance; heights relative to the highest bin, the extreme bins (clipped
 * blacks and whites) excepted, which are marked in red at the bottom when they hold more
 * than 1 % of the pixels
 */
public class Histogram extends ImageMonitor {
	static final int BINS = 256, H = 100, GAP = 6;

	public Histogram(TDenlive aTDe, Media aTargetMedia) {
		super(aTDe, aTargetMedia);
	}

	@Override
	public String getClassName() {return "Histogram";}

	@Override
	void setDescr() {
		super.setDescr();
		icon = "colourPalette2.gif";
	}

	@Override
	public String[] modes() {return new String[] {"RGB", "Luma"};}

	@Override
	public int drawingHeight() {return 150;}

	@Override
	protected BufferedImage drawScope(Planes aL, Planes aR, int aStep) {
		BufferedImage aBI = black(BINS, 2*H+GAP);
		int[] aPix = pixels(aBI);
		drawView(aPix, aL, aStep, 0);
		if(aR != null) {
			drawView(aPix, aR, aStep, H+GAP);
		}
		for(int x = 0;x < BINS;x++) {
			aPix[(H+GAP/2)*BINS+x] = 0xFF404040;
		}
		return aBI;
	}

	void drawView(int[] aPix,Planes aP,int aStep,int aY0) {
		boolean aLuma = mode == 1;
		int[][] aCounts = new int[aLuma ? 1 : 3][BINS];
		int aTotal = 0;
		for(int y = 0;y < aP.h;y += aStep) {
			for(int x = 0;x < aP.w;x += aStep) {
				int p = y*aP.w+x;
				if(aLuma) {
					aCounts[0][bin(0.2126f*aP.r[p]+0.7152f*aP.g[p]+0.0722f*aP.b[p])]++;
				}
				else {
					aCounts[0][bin(aP.r[p])]++;
					aCounts[1][bin(aP.g[p])]++;
					aCounts[2][bin(aP.b[p])]++;
				}
				aTotal++;
			}
		}
		int aMax = 1;
		for(int[] aC : aCounts) {
			for(int b = 1;b < BINS-1;b++) {
				aMax = Math.max(aMax, aC[b]);
			}
		}
		double[][] aColors = aLuma ? new double[][] {{0.85, 0.85, 0.85}} : new double[][] {{0.8, 0, 0}, {0, 0.8, 0}, {0, 0, 0.9}};
		for(int c = 0;c < aCounts.length;c++) {
			for(int b = 0;b < BINS;b++) {
				int aBarH = (int)Math.min(H, Math.round(aCounts[c][b]*(double)H/aMax));
				for(int y = 0;y < aBarH;y++) {
					add(aPix, (aY0+H-1-y)*BINS+b, aColors[c][0], aColors[c][1], aColors[c][2]);
				}
			}
			//Clipped blacks / whites
			for(int b : new int[] {0, BINS-1}) {
				if(aCounts[c][b] > aTotal/100) {
					for(int d = -2;d <= 2;d++) {
						int x = Math.max(0, Math.min(BINS-1, b+d));
						aPix[(aY0+H-1)*BINS+x] = 0xFFFF2020;
						aPix[(aY0+H-2)*BINS+x] = 0xFFFF2020;
					}
				}
			}
		}
	}

	static int bin(float aV) {
		int aB = (int)(aV*255+0.5f);
		return aB < 0 ? 0 : (aB > 255 ? 255 : aB);
	}
}
