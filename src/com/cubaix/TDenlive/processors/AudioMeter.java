package com.cubaix.TDenlive.processors;

import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.Color;
import org.eclipse.swt.graphics.GC;
import org.eclipse.swt.graphics.Rectangle;

import com.cubaix.TDenlive.TDenlive;
import com.cubaix.TDenlive.audio.LoudnessMeter;
import com.cubaix.TDenlive.ffmpeg.AudioReader;
import com.cubaix.TDenlive.medias.Media;

/**
 * Sound meters of the clip at this place of its stack (as the ffmpeg showvolume and ebur128
 * filters): peak and RMS level of each channel in dBFS (bars, peak held 1.5 s), and the
 * loudness EBU R128 (LoudnessMeter): momentary, short term, integrated since the start of the
 * play (or the reset button), maximum sample peak
 */
public class AudioMeter extends MonitorProcessor {
	static final double FLOOR_DB = -60;
	static final long HOLD_MS = 1500;
	final LoudnessMeter meter = new LoudnessMeter(30);
	long nextFrame = Long.MIN_VALUE;
	final double[] peakDb = {FLOOR_DB, FLOOR_DB}, rmsDb = {FLOOR_DB, FLOOR_DB}, holdDb = {FLOOR_DB, FLOOR_DB};
	final long[] holdAt = new long[2];
	double maxPeakDb = Double.NEGATIVE_INFINITY;

	public AudioMeter(TDenlive aTDe, Media aTargetMedia) {
		super(aTDe, aTargetMedia);
	}

	@Override
	public String getClassName() {return "AudioMeter";}

	@Override
	void setDescr() {
		super.setDescr();
		icon = "sound.gif";
	}

	@Override
	public boolean isAudio() {return true;}

	@Override
	public int drawingHeight() {return 70;}

	@Override
	public String resetKey() {return "Reset";}

	@Override
	public synchronized void reset() {
		meter.reset();
		maxPeakDb = Double.NEGATIVE_INFINITY;
		for(int c = 0;c < 2;c++) {
			peakDb[c] = rmsDb[c] = holdDb[c] = FLOOR_DB;
		}
		updated();
	}

	static double db(double aV) {
		return aV <= 0 ? Double.NEGATIVE_INFINITY : 20*Math.log10(aV);
	}

	@Override
	public void processAudio(Media aMedia, float[] aBuf, int aNbFrames, long aTimeMS, double aFrameMS) {
		if(!isWatched() || aNbFrames <= 0) {
			return;
		}
		synchronized (this) {
			long aF = aTimeMS*AudioReader.RATE/1000;
			if(Math.abs(aF-nextFrame) > 2*AudioReader.RATE/1000+1) {
				//New play (or jump): the integrated loudness starts again
				meter.reset();
				maxPeakDb = Double.NEGATIVE_INFINITY;
			}
			nextFrame = aF+aNbFrames;
			meter.add(aBuf, aNbFrames);
			long aNow = System.currentTimeMillis();
			for(int c = 0;c < 2;c++) {
				double aPeak = 0, aSum = 0;
				for(int f = 0;f < aNbFrames;f++) {
					double aS = aBuf[2*f+c];
					aPeak = Math.max(aPeak, Math.abs(aS));
					aSum += aS*aS;
				}
				peakDb[c] = Math.max(FLOOR_DB, db(aPeak));
				rmsDb[c] = Math.max(FLOOR_DB, db(Math.sqrt(aSum/aNbFrames)));
				if(peakDb[c] >= holdDb[c] || aNow-holdAt[c] > HOLD_MS) {
					holdDb[c] = peakDb[c];
					holdAt[c] = aNow;
				}
				maxPeakDb = Math.max(maxPeakDb, db(aPeak));
			}
		}
		updated();
	}

	static String fmt(double aV) {
		return Double.isInfinite(aV) || Double.isNaN(aV) ? "-inf" : String.format("%.1f", aV);
	}

	@Override
	public void paint(GC aGC, Rectangle aR) {
		double[] aPeak, aRms, aHold;
		double aM, aS, aI, aMax;
		synchronized (this) {
			aPeak = peakDb.clone();
			aRms = rmsDb.clone();
			aHold = holdDb.clone();
			aM = meter.momentary();
			aS = meter.shortTerm();
			aI = meter.integrated();
			aMax = maxPeakDb;
		}
		Color aBlack = tde.gui.display.getSystemColor(SWT.COLOR_BLACK);
		aGC.setBackground(aBlack);
		aGC.fillRectangle(aR);
		aGC.setFont(tde.gui.fontsSwt.roboto);
		int aTextH = aGC.getFontMetrics().getHeight();
		int aLabelW = aGC.textExtent("W ").x;
		int aBarX = aR.x+aLabelW+4, aBarW = aR.width-aLabelW-10;
		int aBarH = Math.max(6, (aR.height-aTextH*2-12)/2);
		Color aGreen = new Color(tde.gui.display, 40, 200, 70), aYellow = new Color(tde.gui.display, 230, 200, 40);
		Color aRed = new Color(tde.gui.display, 230, 50, 40), aDark = new Color(tde.gui.display, 60, 60, 60);
		Color aDim = new Color(tde.gui.display, 30, 110, 50);
		try {
			String[] aNames = {tde.gui.lngs.get("Generic.LeftLetter"), tde.gui.lngs.get("Generic.RightLetter")};
			for(int c = 0;c < 2;c++) {
				int aY = aR.y+4+c*(aBarH+4);
				aGC.setForeground(tde.gui.display.getSystemColor(SWT.COLOR_GRAY));
				aGC.drawString(aNames[c], aR.x+4, aY+(aBarH-aTextH)/2, true);
				aGC.setBackground(aDark);
				aGC.fillRectangle(aBarX, aY, aBarW, aBarH);
				//Peak: colored by zones (green < -18, yellow < -6, red), RMS darker inside
				int aPW = xOf(aPeak[c], aBarW);
				int aG18 = xOf(-18, aBarW), aG6 = xOf(-6, aBarW);
				aGC.setBackground(aGreen);
				aGC.fillRectangle(aBarX, aY, Math.min(aPW, aG18), aBarH);
				if(aPW > aG18) {
					aGC.setBackground(aYellow);
					aGC.fillRectangle(aBarX+aG18, aY, Math.min(aPW, aG6)-aG18, aBarH);
				}
				if(aPW > aG6) {
					aGC.setBackground(aRed);
					aGC.fillRectangle(aBarX+aG6, aY, aPW-aG6, aBarH);
				}
				aGC.setBackground(aDim);
				aGC.fillRectangle(aBarX, aY+aBarH/3, xOf(aRms[c], aBarW), aBarH-2*(aBarH/3));
				int aHX = aBarX+xOf(aHold[c], aBarW);
				aGC.setBackground(aHold[c] > -1 ? aRed : tde.gui.display.getSystemColor(SWT.COLOR_WHITE));
				aGC.fillRectangle(Math.min(aHX, aBarX+aBarW-2), aY, 2, aBarH);
			}
			//Scale
			int aScaleY = aR.y+4+2*(aBarH+4);
			aGC.setForeground(tde.gui.display.getSystemColor(SWT.COLOR_GRAY));
			for(int aDb : new int[] {-60, -40, -30, -20, -12, -6, -3, 0}) {
				int aX = aBarX+xOf(aDb, aBarW);
				String aT = ""+aDb;
				int aTW = aGC.textExtent(aT).x;
				aGC.drawLine(aX, aScaleY-3, aX, aScaleY);
				aGC.drawString(aT, Math.max(aBarX, Math.min(aBarX+aBarW-aTW, aX-aTW/2)), aScaleY, true);
			}
			aGC.setForeground(tde.gui.display.getSystemColor(SWT.COLOR_WHITE));
			aGC.drawString("M "+fmt(aM)+"   S "+fmt(aS)+"   I "+fmt(aI)+" LUFS   "
					+tde.gui.lngs.get("AudioMeter.MaxPeak")+" "+fmt(aMax)+" dBFS", aR.x+4, aScaleY+aTextH+2, true);
		}
		finally {
			aGreen.dispose();
			aYellow.dispose();
			aRed.dispose();
			aDark.dispose();
			aDim.dispose();
		}
	}

	static int xOf(double aDb,int aW) {
		double aF = (Math.max(FLOOR_DB, Math.min(0, aDb))-FLOOR_DB)/-FLOOR_DB;
		return (int)Math.round(aF*aW);
	}
}
