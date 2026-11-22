package com.cubaix.TDenlive.processors;

import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.Color;
import org.eclipse.swt.graphics.GC;
import org.eclipse.swt.graphics.Rectangle;

import com.cubaix.TDenlive.TDenlive;
import com.cubaix.TDenlive.ffmpeg.AudioReader;
import com.cubaix.TDenlive.medias.Media;

/**
 * Spectrum of the sound of the clip at this place of its stack (as the ffmpeg showfreqs
 * filter): FFT of the last N samples (L + R, Hann window), levels in dB on a logarithmic
 * frequency axis 20 Hz..20 kHz, falling slowly (FALL_DB per second), peaks held
 */
public class Spectrum extends MonitorProcessor {
	static final int N = 4096, BANDS = 160;
	static final double F_MIN = 20, F_MAX = 20000, DB_MIN = -90, FALL_DB = 40;
	final float[] ring = new float[N];
	int ringPos = 0;
	final double[] levels = new double[BANDS], peaks = new double[BANDS];
	long lastMS = 0;

	public Spectrum(TDenlive aTDe, Media aTargetMedia) {
		super(aTDe, aTargetMedia);
		java.util.Arrays.fill(levels, DB_MIN);
		java.util.Arrays.fill(peaks, DB_MIN);
	}

	@Override
	public String getClassName() {return "Spectrum";}

	@Override
	void setDescr() {
		super.setDescr();
		icon = "sound.gif";
	}

	@Override
	public boolean isAudio() {return true;}

	@Override
	public int drawingHeight() {return 120;}

	@Override
	public String resetKey() {return "Reset";}

	@Override
	public synchronized void reset() {
		java.util.Arrays.fill(levels, DB_MIN);
		java.util.Arrays.fill(peaks, DB_MIN);
		updated();
	}

	@Override
	public void processAudio(Media aMedia, float[] aBuf, int aNbFrames, long aTimeMS, double aFrameMS) {
		if(!isWatched() || aNbFrames <= 0) {
			return;
		}
		synchronized (this) {
			for(int f = 0;f < aNbFrames;f++) {
				ring[ringPos] = (aBuf[2*f]+aBuf[2*f+1])*0.5f;
				ringPos = (ringPos+1) % N;
			}
			double[] aRe = new double[N], aIm = new double[N];
			double aWinSum = 0;
			for(int i = 0;i < N;i++) {
				double aWin = 0.5-0.5*Math.cos(2*Math.PI*i/(N-1));
				aRe[i] = ring[(ringPos+i) % N]*aWin;
				aWinSum += aWin;
			}
			fft(aRe, aIm);
			long aNow = System.currentTimeMillis();
			double aFall = lastMS == 0 ? 1000 : FALL_DB*(aNow-lastMS)/1000.0;
			lastMS = aNow;
			double aBinHz = AudioReader.RATE/(double)N;
			for(int b = 0;b < BANDS;b++) {
				double aF0 = F_MIN*Math.pow(F_MAX/F_MIN, b/(double)BANDS);
				double aF1 = F_MIN*Math.pow(F_MAX/F_MIN, (b+1)/(double)BANDS);
				int aK0 = (int)Math.floor(aF0/aBinHz), aK1 = Math.max(aK0, (int)Math.ceil(aF1/aBinHz)-1);
				double aMax = 0;
				for(int k = Math.max(1, aK0);k <= aK1 && k < N/2;k++) {
					aMax = Math.max(aMax, Math.hypot(aRe[k], aIm[k]));
				}
				//Full scale sine = 0 dB
				double aDb = aMax <= 0 ? DB_MIN : Math.max(DB_MIN, 20*Math.log10(2*aMax/aWinSum));
				levels[b] = Math.max(aDb, levels[b]-aFall);
				peaks[b] = Math.max(aDb, peaks[b]-aFall/4);
			}
		}
		updated();
	}

	/** In place radix-2 FFT (length power of 2) */
	static void fft(double[] aRe,double[] aIm) {
		int aN = aRe.length;
		for(int i = 1, j = 0;i < aN;i++) {
			int aBit = aN >> 1;
			for(;(j & aBit) != 0;aBit >>= 1) {
				j ^= aBit;
			}
			j ^= aBit;
			if(i < j) {
				double aT = aRe[i];
				aRe[i] = aRe[j];
				aRe[j] = aT;
				aT = aIm[i];
				aIm[i] = aIm[j];
				aIm[j] = aT;
			}
		}
		for(int aLen = 2;aLen <= aN;aLen <<= 1) {
			double aAng = -2*Math.PI/aLen;
			double aWR = Math.cos(aAng), aWI = Math.sin(aAng);
			for(int i = 0;i < aN;i += aLen) {
				double aCR = 1, aCI = 0;
				for(int j = 0;j < aLen/2;j++) {
					int a = i+j, b = i+j+aLen/2;
					double aUR = aRe[a], aUI = aIm[a];
					double aVR = aRe[b]*aCR-aIm[b]*aCI, aVI = aRe[b]*aCI+aIm[b]*aCR;
					aRe[a] = aUR+aVR;
					aIm[a] = aUI+aVI;
					aRe[b] = aUR-aVR;
					aIm[b] = aUI-aVI;
					double aNR = aCR*aWR-aCI*aWI;
					aCI = aCR*aWI+aCI*aWR;
					aCR = aNR;
				}
			}
		}
	}

	@Override
	public void paint(GC aGC, Rectangle aR) {
		double[] aLevels, aPeaks;
		synchronized (this) {
			aLevels = levels.clone();
			aPeaks = peaks.clone();
		}
		aGC.setBackground(tde.gui.display.getSystemColor(SWT.COLOR_BLACK));
		aGC.fillRectangle(aR);
		aGC.setFont(tde.gui.fontsSwt.roboto);
		int aTextH = aGC.getFontMetrics().getHeight();
		int aX0 = aR.x+30, aW = aR.width-34, aY0 = aR.y+4, aH = aR.height-aTextH-8;
		Color aGrid = new Color(tde.gui.display, 50, 50, 50), aBar = new Color(tde.gui.display, 60, 170, 230);
		Color aPeak = new Color(tde.gui.display, 230, 230, 230);
		try {
			aGC.setForeground(aGrid);
			for(int aDb = 0;aDb >= DB_MIN;aDb -= 30) {
				int aY = aY0+(int)((aDb/DB_MIN)*aH);
				aGC.drawLine(aX0, aY, aX0+aW, aY);
				aGC.setForeground(tde.gui.display.getSystemColor(SWT.COLOR_GRAY));
				aGC.drawString(""+aDb, aR.x+2, Math.max(aR.y, aY-aTextH/2), true);
				aGC.setForeground(aGrid);
			}
			for(double aF : new double[] {50, 100, 200, 500, 1000, 2000, 5000, 10000}) {
				int aX = aX0+(int)(Math.log(aF/F_MIN)/Math.log(F_MAX/F_MIN)*aW);
				aGC.setForeground(aGrid);
				aGC.drawLine(aX, aY0, aX, aY0+aH);
				String aT = aF >= 1000 ? (int)(aF/1000)+"k" : ""+(int)aF;
				aGC.setForeground(tde.gui.display.getSystemColor(SWT.COLOR_GRAY));
				aGC.drawString(aT, aX-aGC.textExtent(aT).x/2, aY0+aH+2, true);
			}
			for(int b = 0;b < BANDS;b++) {
				int aXa = aX0+b*aW/BANDS, aXb = aX0+(b+1)*aW/BANDS;
				int aBarH = (int)((1-aLevels[b]/DB_MIN)*aH);
				aGC.setBackground(aBar);
				aGC.fillRectangle(aXa, aY0+aH-aBarH, Math.max(1, aXb-aXa-1), aBarH);
				int aPY = aY0+aH-(int)((1-aPeaks[b]/DB_MIN)*aH);
				aGC.setBackground(aPeak);
				aGC.fillRectangle(aXa, aPY, Math.max(1, aXb-aXa-1), 1);
			}
		}
		finally {
			aGrid.dispose();
			aBar.dispose();
			aPeak.dispose();
		}
	}
}
