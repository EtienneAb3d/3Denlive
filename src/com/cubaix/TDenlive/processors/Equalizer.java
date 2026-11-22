package com.cubaix.TDenlive.processors;

import com.cubaix.TDenlive.TDenlive;
import com.cubaix.TDenlive.audio.Biquad;
import com.cubaix.TDenlive.ffmpeg.AudioReader;
import com.cubaix.TDenlive.medias.Media;

/**
 * Equalizer, as the ffmpeg equalizer / bass / treble / highpass / lowpass filters: a low
 * shelf (80 Hz), three peaking bands (300 Hz, 1 kHz, 3 kHz), a high shelf (10 kHz), each
 * -15..+15 dB, and optional high pass (rumble, wind) and low pass (hiss) filters
 */
public class Equalizer extends AudioParamProcessor {
	static final Param[] PARAMS = {
			Param.range("g80", -15, 0, 15, 10),
			Param.range("g300", -15, 0, 15, 10),
			Param.range("g1k", -15, 0, 15, 10),
			Param.range("g3k", -15, 0, 15, 10),
			Param.range("g10k", -15, 0, 15, 10),
			Param.choice("highPass", 0, "Off", "=40 Hz", "=80 Hz", "=120 Hz", "=200 Hz"),
			Param.choice("lowPass", 0, "Off", "=16 kHz", "=12 kHz", "=8 kHz", "=4 kHz"),
	};
	static final int HIGHPASS = 5, LOWPASS = 6;
	static final double[] HIGHPASS_HZ = {0, 40, 80, 120, 200};
	static final double[] LOWPASS_HZ = {0, 16000, 12000, 8000, 4000};
	static final double[] BANDS_HZ = {80, 300, 1000, 3000, 10000};

	final Biquad[] filters = new Biquad[7];
	double[] filterValues = null;

	public Equalizer(TDenlive aTDe, Media aTargetMedia) {
		super(aTDe, aTargetMedia);
		for(int f = 0;f < filters.length;f++) {
			filters[f] = new Biquad();
		}
	}

	@Override
	public String getClassName() {return "Equalizer";}

	@Override
	void setDescr() {
		super.setDescr();
		icon = "sound.gif";
	}

	@Override
	public Param[] params() {return PARAMS;}

	/** Coefficients of the filters for the values aV (the states are kept) */
	void setFilters(double[] aV) {
		double aRate = AudioReader.RATE;
		filters[0].lowShelf(aRate, BANDS_HZ[0], aV[0]);
		for(int b = 1;b <= 3;b++) {
			filters[b].peaking(aRate, BANDS_HZ[b], 1.0, aV[b]);
		}
		filters[4].highShelf(aRate, BANDS_HZ[4], aV[4]);
		int aHP = (int)aV[HIGHPASS], aLP = (int)aV[LOWPASS];
		if(aHP > 0) {
			filters[5].highPass(aRate, HIGHPASS_HZ[aHP], Math.sqrt(0.5));
		}
		if(aLP > 0) {
			filters[6].lowPass(aRate, LOWPASS_HZ[aLP], Math.sqrt(0.5));
		}
		filterValues = aV;
	}

	@Override
	public synchronized void processAudio(Media aMedia, float[] aBuf, int aNbFrames, long aTimeMS, double aFrameMS) {
		if(aNbFrames <= 0) {
			return;
		}
		boolean aFollows = follows(aTimeMS, aNbFrames);
		double[] aV = valuesAt(aTimeMS+(long)(aNbFrames*aFrameMS/2));
		boolean[] aOn = new boolean[7];
		boolean aAny = false;
		for(int b = 0;b < 5;b++) {
			aOn[b] = Math.abs(aV[b]) > 0.01;
			aAny |= aOn[b];
		}
		aOn[5] = (int)aV[HIGHPASS] > 0;
		aOn[6] = (int)aV[LOWPASS] > 0;
		aAny |= aOn[5] || aOn[6];
		if(!aAny) {
			nextFrame = Long.MIN_VALUE;
			return;
		}
		if(!aFollows) {
			for(Biquad aB : filters) {
				aB.reset();
			}
		}
		if(filterValues == null || !java.util.Arrays.equals(filterValues, aV)) {
			setFilters(aV);
		}
		for(int f = 0;f < aNbFrames;f++) {
			for(int c = 0;c < 2;c++) {
				double aS = aBuf[2*f+c];
				for(int b = 0;b < 7;b++) {
					if(aOn[b]) {
						aS = filters[b].process(aS, c);
					}
				}
				aBuf[2*f+c] = (float)aS;
			}
		}
	}
}
