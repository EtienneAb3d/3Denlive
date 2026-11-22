package com.cubaix.TDenlive.processors;

import java.util.HashMap;
import java.util.HashSet;

import com.cubaix.TDenlive.TDenlive;
import com.cubaix.TDenlive.audio.LoudnessMeter;
import com.cubaix.TDenlive.ffmpeg.AudioReader;
import com.cubaix.TDenlive.medias.Clip;
import com.cubaix.TDenlive.medias.Media;

/**
 * Loudness normalization (EBU R128, as the ffmpeg loudnorm filter in its linear mode): the
 * integrated loudness of the part of the media used by the clip (its sound before the
 * processors) is measured once, in the background (LoudnessMeter), and the gain that brings it
 * to the target is applied, at most +MAX_GAIN_DB. An optional soft limiter keeps the peaks under
 * -1 dBFS. Until measured, the sound is unchanged (a final rendering waits for the decoding:
 * the measure is then done at once)
 */
public class Loudness extends AudioParamProcessor {
	static final Param[] PARAMS = {
			Param.range("target", -30, -16, -6, 1),
			Param.choice("limiter", 0, "Yes", "No"),
	};
	static final int TARGET = 0, LIMITER = 1;
	public static final double MAX_GAIN_DB = 24;
	/** Measures by "file|from ms|to ms" (LUFS, NaN: failed) */
	static final HashMap<String, Double> MEASURED = new HashMap<String, Double>();
	static final HashSet<String> RUNNING = new HashSet<String>();
	/** Measure of the clip when last known (for the panel) */
	public volatile double lastLufs = Double.NaN;

	public Loudness(TDenlive aTDe, Media aTargetMedia) {
		super(aTDe, aTargetMedia);
	}

	@Override
	public String getClassName() {return "Loudness";}

	@Override
	void setDescr() {
		super.setDescr();
		icon = "sound.gif";
	}

	@Override
	public Param[] params() {return PARAMS;}

	/** Key of the measure of the clip now, null if it has no sound */
	String measureKey(AudioReader[] aReader,long[] aRange) {
		Clip aClip = (Clip)targetMedia;
		AudioReader aAR = aClip.getSoundReader();
		if(aAR == null) {
			return null;
		}
		long aFrom = aClip.mediaTimeMS(aClip.getStartTimeMS());
		long aTo = aClip.mediaTimeMS(aClip.getStartTimeMS()+aClip.getDurationMS());
		aReader[0] = aAR;
		aRange[0] = aFrom;
		aRange[1] = aTo;
		return aAR.path+"|"+aFrom+"|"+aTo;
	}

	/**
	 * Integrated loudness of the clip, null if not known yet (the measure is started in the
	 * background; done at once if aWait and the sound is decoded)
	 */
	public Double measure(boolean aWait) {
		AudioReader[] aAR = new AudioReader[1];
		long[] aRange = new long[2];
		String aKey = measureKey(aAR, aRange);
		if(aKey == null) {
			return null;
		}
		boolean aNow = aWait && aAR[0].isDecoded();
		synchronized (MEASURED) {
			Double aL = MEASURED.get(aKey);
			if(aL != null) {
				lastLufs = aL;
				return aL;
			}
			if(!RUNNING.contains(aKey)) {
				RUNNING.add(aKey);
				if(!aNow) {
					Thread aTh = new Thread(() -> run(aKey, aAR[0], aRange), "Loudness measure");
					aTh.setDaemon(true);
					aTh.start();
					return null;
				}
			}
			else if(aNow) {
				//Measured by another thread: waited for
				while(RUNNING.contains(aKey)) {
					try {
						MEASURED.wait(50);
					}
					catch(InterruptedException e) {
						return null;
					}
				}
				return MEASURED.get(aKey);
			}
			else {
				return null;
			}
		}
		run(aKey, aAR[0], aRange);
		synchronized (MEASURED) {
			return MEASURED.get(aKey);
		}
	}

	void run(String aKey,AudioReader aAR,long[] aRange) {
		double aL = Double.NaN;
		try {
			aAR.waitDecoded();
			long aRate = AudioReader.RATE;
			aL = LoudnessMeter.measure(aAR, aRange[0]*aRate/1000, aRange[1]*aRate/1000, null);
			System.out.println("Loudness of "+aKey+": "+aL+" LUFS");
		}
		catch(Throwable t) {
			t.printStackTrace(System.err);
		}
		synchronized (MEASURED) {
			MEASURED.put(aKey, aL);
			RUNNING.remove(aKey);
			MEASURED.notifyAll();
		}
		lastLufs = aL;
	}

	/** Gain (linear) for the target aTarget, 1 if unknown */
	static double gainFor(Double aLufs,double aTarget) {
		if(aLufs == null || aLufs.isNaN() || aLufs.isInfinite()) {
			return 1;
		}
		return Math.pow(10, Math.min(MAX_GAIN_DB, aTarget-aLufs)/20);
	}

	@Override
	public void processAudio(Media aMedia, float[] aBuf, int aNbFrames, long aTimeMS, double aFrameMS) {
		if(aNbFrames <= 0) {
			return;
		}
		Double aLufs = measure(true);
		double[] aStart = valuesAt(aTimeMS);
		double[] aEnd = valuesAt(aTimeMS+(long)(aNbFrames*aFrameMS));
		double aG0 = gainFor(aLufs, aStart[TARGET]), aG1 = gainFor(aLufs, aEnd[TARGET]);
		boolean aLimit = (int)aStart[LIMITER] == 0;
		final float aT = 0.891f;//-1 dBFS
		for(int f = 0;f < aNbFrames;f++) {
			float aG = (float)(aG0+(aG1-aG0)*f/(double)aNbFrames);
			for(int c = 0;c < 2;c++) {
				float aS = aBuf[2*f+c]*aG;
				if(aLimit) {
					float aAbs = Math.abs(aS);
					if(aAbs > aT) {
						aS = Math.signum(aS)*(aT+(1-aT)*(float)Math.tanh((aAbs-aT)/(1-aT)));
					}
				}
				aBuf[2*f+c] = aS;
			}
		}
	}
}
