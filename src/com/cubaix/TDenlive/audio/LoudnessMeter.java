package com.cubaix.TDenlive.audio;

import java.util.ArrayList;

import com.cubaix.TDenlive.ffmpeg.AudioReader;

/**
 * Loudness of a stereo sound at 48 kHz as ITU-R BS.1770-4 / EBU R128: K-weighting, mean
 * squares by blocks of 100 ms; momentary (400 ms), short term (3 s), integrated (blocks of
 * 400 ms overlapping by 75 %, gates at -70 LUFS and at -10 LU below the ungated mean), in LUFS;
 * sample peaks of each channel
 */
public class LoudnessMeter {
	static final int SUB = AudioReader.RATE/10;//Frames of a sub-block of 100 ms
	final Biquad shelf = Biquad.of(1.53512485958697, -2.69169618940638, 1.19839281085285, 1.0, -1.69065929318241, 0.73248077421585);
	final Biquad highPass = Biquad.of(1.0, -2.0, 1.0, 1.0, -1.99004745483398, 0.99007225036621);
	double subSum = 0;
	int subN = 0;
	/** Mean squares (sum of the channels) of the sub-blocks done */
	final ArrayList<Double> subs = new ArrayList<Double>();
	/** Keep only the last sub-blocks (meters); 0: all (integrated) */
	final int keepSubs;
	/** Energies of the 400 ms blocks, for the integrated loudness */
	final ArrayList<Double> blocks = new ArrayList<Double>();
	public final float[] peak = new float[2];

	/** aKeepSubs: sub-blocks kept (30 for the short term), the integrated loudness always counted */
	public LoudnessMeter(int aKeepSubs) {
		keepSubs = aKeepSubs;
	}

	public synchronized void reset() {
		shelf.reset();
		highPass.reset();
		subSum = 0;
		subN = 0;
		subs.clear();
		blocks.clear();
		peak[0] = peak[1] = 0;
	}

	/** Adds aN interleaved stereo frames */
	public synchronized void add(float[] aBuf,int aN) {
		for(int f = 0;f < aN;f++) {
			for(int c = 0;c < 2;c++) {
				float aS = aBuf[2*f+c];
				float aAbs = Math.abs(aS);
				if(aAbs > peak[c]) {
					peak[c] = aAbs;
				}
				double aK = highPass.process(shelf.process(aS, c), c);
				subSum += aK*aK;
			}
			if(++subN >= SUB) {
				subs.add(subSum/SUB);
				subSum = 0;
				subN = 0;
				int aNb = subs.size();
				if(aNb >= 4) {
					blocks.add((subs.get(aNb-1)+subs.get(aNb-2)+subs.get(aNb-3)+subs.get(aNb-4))/4);
				}
				if(keepSubs > 0 && aNb > keepSubs) {
					subs.remove(0);
				}
			}
		}
	}

	public static double lufs(double aEnergy) {
		return aEnergy <= 0 ? Double.NEGATIVE_INFINITY : -0.691+10*Math.log10(aEnergy);
	}

	double meanOfLast(int aNb) {
		int aSize = subs.size();
		if(aSize == 0) {
			return 0;
		}
		double aSum = 0;
		int aN = Math.min(aNb, aSize);
		for(int i = aSize-aN;i < aSize;i++) {
			aSum += subs.get(i);
		}
		return aSum/aN;
	}

	public synchronized double momentary() {
		return lufs(meanOfLast(4));
	}

	public synchronized double shortTerm() {
		return lufs(meanOfLast(30));
	}

	public synchronized double integrated() {
		double aAbs = Math.pow(10, (-70+0.691)/10);
		double aSum = 0;
		int aN = 0;
		for(double aE : blocks) {
			if(aE > aAbs) {
				aSum += aE;
				aN++;
			}
		}
		if(aN == 0) {
			return Double.NEGATIVE_INFINITY;
		}
		double aRel = aSum/aN*Math.pow(10, -10/10.0);
		aSum = 0;
		aN = 0;
		for(double aE : blocks) {
			if(aE > aAbs && aE > aRel) {
				aSum += aE;
				aN++;
			}
		}
		return aN == 0 ? Double.NEGATIVE_INFINITY : lufs(aSum/aN);
	}

	/**
	 * Integrated loudness (LUFS) of the frames [aFrom, aTo[ of the file of aAR (decoded)
	 * @param aStop stops when it returns true (null: never); the result is then NaN
	 */
	public static double measure(AudioReader aAR,long aFrom,long aTo,java.util.function.BooleanSupplier aStop) {
		LoudnessMeter aM = new LoudnessMeter(8);
		int aChunk = AudioReader.RATE;
		float[] aBuf = new float[aChunk*AudioReader.CHANNELS];
		for(long aF = Math.max(0, aFrom);aF < aTo;aF += aChunk) {
			if(aStop != null && aStop.getAsBoolean()) {
				return Double.NaN;
			}
			int aN = (int)Math.min(aChunk, aTo-aF);
			aAR.read(aF, aBuf, aN);
			aM.add(aBuf, aN);
		}
		return aM.integrated();
	}
}
