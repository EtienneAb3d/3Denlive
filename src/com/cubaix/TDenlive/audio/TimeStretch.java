package com.cubaix.TDenlive.audio;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.DoubleUnaryOperator;

import com.cubaix.TDenlive.ffmpeg.AudioReader;

/**
 * Changes the speed of a sound without changing its pitch (WSOLA: waveform similarity overlap-add).
 * The output is made of grains of GRAIN frames, one every HOP = GRAIN/2 (Hann windows, whose sum is 1).
 * Grain k starts at the output frame k*HOP (from the start of the clip) and is read in the source at the
 * position given by the speed (aMap, any speed curve), moved by at most SEEK frames so that it continues
 * the previous grain as well as possible (cross-correlation with the source following the previous grain).
 * A grain only depends on its index and on the shift of the previous one (cached), so any buffer of the
 * time line can be computed alone: playing, seeking and rendering give the same sound.
 */
public class TimeStretch {
	static final int GRAIN = 1920;//40 ms at 48 kHz
	static final int HOP = GRAIN/2;
	static final int SEEK = 480;//±10 ms
	static final int CORR_STEP = 2;//Candidate shifts tested every 2 frames, then refined
	static final int CORR_LEN = HOP;//Compared length: the overlap with the previous grain
	static final double[] WINDOW = new double[GRAIN];
	static {
		for(int n = 0;n < GRAIN;n++) {
			//Periodic Hann: two windows HOP apart sum to 1
			WINDOW[n] = 0.5-0.5*Math.cos(2*Math.PI*n/GRAIN);
		}
	}

	//Shift (frames) of the grains already computed, by index (the last ones used)
	final Map<Long,Integer> shifts = new LinkedHashMap<Long,Integer>(256, 0.75f, true) {
		private static final long serialVersionUID = 1L;
		@Override
		protected boolean removeEldestEntry(Map.Entry<Long,Integer> aE) {
			return size() > 8192;
		}
	};

	/** Forget the shifts (new source, or speed curve changed a lot) */
	public void reset() {
		synchronized (shifts) {
			shifts.clear();
		}
	}

	/**
	 * Fills aBuf (aN interleaved stereo frames) with the output frames aOutFrom.. (from the start of the clip).
	 * aMap gives the source frame (in the file) played at an output frame (from the start of the clip).
	 */
	public void render(AudioReader aAR,DoubleUnaryOperator aMap,long aOutFrom,float[] aBuf,int aN) {
		java.util.Arrays.fill(aBuf, 0, aN*AudioReader.CHANNELS, 0.0f);
		long aKFrom = Math.floorDiv(aOutFrom-GRAIN+1, (long)HOP);
		long aKTo = Math.floorDiv(aOutFrom+aN-1, (long)HOP);
		float[] aGrain = new float[GRAIN*AudioReader.CHANNELS];
		for(long k = aKFrom;k <= aKTo;k++) {
			long aSrc = grainSource(aAR, aMap, k);
			aAR.read(aSrc, aGrain, GRAIN);
			long aOut = k*HOP;
			int aN0 = (int)Math.max(0, aOutFrom-aOut);
			int aN1 = (int)Math.min(GRAIN, aOutFrom+aN-aOut);
			for(int n = aN0;n < aN1;n++) {
				int aB = (int)(aOut+n-aOutFrom)*AudioReader.CHANNELS;
				for(int c = 0;c < AudioReader.CHANNELS;c++) {
					aBuf[aB+c] += (float)(aGrain[n*AudioReader.CHANNELS+c]*WINDOW[n]);
				}
			}
		}
	}

	/** Position of grain k without its shift: its middle on the speed curve */
	static long nominal(DoubleUnaryOperator aMap,long k) {
		return Math.round(aMap.applyAsDouble(k*HOP+GRAIN/2.0))-GRAIN/2;
	}

	/** Start (source frame) of grain k: nominal position + shift */
	long grainSource(AudioReader aAR,DoubleUnaryOperator aMap,long k) {
		return nominal(aMap, k)+shift(aAR, aMap, k);
	}

	/**
	 * Shift of grain k: the one whose start best matches what follows the previous grain in the source
	 * (the previous grain is taken with its cached shift, else without shift)
	 */
	int shift(AudioReader aAR,DoubleUnaryOperator aMap,long k) {
		synchronized (shifts) {
			Integer aS = shifts.get(k);
			if(aS != null) {
				return aS;
			}
		}
		Integer aPrevShift;
		synchronized (shifts) {
			aPrevShift = shifts.get(k-1);
		}
		long aPrev = nominal(aMap, k-1)+(aPrevShift != null ? aPrevShift : 0);
		//Natural continuation of the previous grain: its second half goes on in the source
		float[] aRef = mono(aAR, aPrev+HOP, CORR_LEN);
		long aNom = nominal(aMap, k);
		float[] aCand = mono(aAR, aNom-SEEK, CORR_LEN+2*SEEK);
		int aBest = bestShift(aRef, aCand, -SEEK, SEEK, CORR_STEP);
		//Refine around the best coarse shift
		aBest = bestShift(aRef, aCand, Math.max(-SEEK, aBest-CORR_STEP+1), Math.min(SEEK, aBest+CORR_STEP-1), 1);
		synchronized (shifts) {
			shifts.put(k, aBest);
		}
		return aBest;
	}

	/** Shift in [aFrom, aTo] (every aStep) of the best normalized correlation of aRef in aCand (aCand[0] = shift -SEEK) */
	static int bestShift(float[] aRef,float[] aCand,int aFrom,int aTo,int aStep) {
		double aRefE = 0;
		for(float aV : aRef) {
			aRefE += aV*aV;
		}
		if(aRefE < 1e-9) {
			//Silence: no preference
			return 0;
		}
		int aBest = 0;
		double aBestScore = Double.NEGATIVE_INFINITY;
		for(int d = aFrom;d <= aTo;d += aStep) {
			int aOff = d+SEEK;
			double aDot = 0;
			double aE = 0;
			for(int n = 0;n < aRef.length;n++) {
				float aC = aCand[aOff+n];
				aDot += aRef[n]*aC;
				aE += aC*aC;
			}
			double aScore = aE < 1e-9 ? 0 : aDot/Math.sqrt(aE);
			//Small preference for no shift (stable at speed 1)
			aScore -= Math.abs(d)*1e-9;
			if(aScore > aBestScore) {
				aBestScore = aScore;
				aBest = d;
			}
		}
		return aBest;
	}

	/** aN frames from aFrom, left + right */
	static float[] mono(AudioReader aAR,long aFrom,int aN) {
		float[] aSt = new float[aN*AudioReader.CHANNELS];
		aAR.read(aFrom, aSt, aN);
		float[] aM = new float[aN];
		for(int n = 0;n < aN;n++) {
			aM[n] = aSt[2*n]+aSt[2*n+1];
		}
		return aM;
	}
}
