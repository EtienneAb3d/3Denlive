package com.cubaix.TDenlive.processors;

import com.cubaix.TDenlive.TDenlive;
import com.cubaix.TDenlive.audio.Biquad;
import com.cubaix.TDenlive.ffmpeg.AudioReader;
import com.cubaix.TDenlive.medias.Media;

/**
 * De-esser (role of the ffmpeg deesser filter): the sound is split at the frequency into a
 * high band (high pass) and the rest (sound - high band: the sum gives back the sound exactly);
 * when the level of the high band (envelope: attack 1 ms, release 60 ms) goes over the
 * threshold, the high band alone is reduced (ratio 4:1, at most the max reduction). "Sibilants
 * only" plays the reduced high band alone, to set the frequency and the threshold
 */
public class DeEsser extends AudioParamProcessor {
	static final Param[] PARAMS = {
			Param.range("frequency", 3000, 6000, 10000, 1),
			Param.range("threshold", -60, -30, 0, 1),
			Param.range("reduction", 0, 12, 24, 1),
			Param.choice("listen", 0, "Normal", "Sibilants"),
	};
	static final int FREQUENCY = 0, THRESHOLD = 1, REDUCTION = 2, LISTEN = 3;
	static final double RATIO = 4;

	final Biquad highPass = new Biquad();
	double filterFreq = -1;
	double env = 0;

	public DeEsser(TDenlive aTDe, Media aTargetMedia) {
		super(aTDe, aTargetMedia);
	}

	@Override
	public String getClassName() {return "DeEsser";}

	@Override
	void setDescr() {
		super.setDescr();
		icon = "sound.gif";
	}

	@Override
	public Param[] params() {return PARAMS;}

	@Override
	public synchronized void processAudio(Media aMedia, float[] aBuf, int aNbFrames, long aTimeMS, double aFrameMS) {
		if(aNbFrames <= 0) {
			return;
		}
		double aRate = AudioReader.RATE;
		if(!follows(aTimeMS, aNbFrames)) {
			highPass.reset();
			env = 0;
		}
		double[] aV = valuesAt(aTimeMS+(long)(aNbFrames*aFrameMS/2));
		if(aV[FREQUENCY] != filterFreq) {
			highPass.highPass(aRate, aV[FREQUENCY], Math.sqrt(0.5));
			filterFreq = aV[FREQUENCY];
		}
		double aAtt = Math.exp(-1/(0.001*aRate)), aRel = Math.exp(-1/(0.060*aRate));
		double aTh = aV[THRESHOLD], aMax = aV[REDUCTION];
		boolean aListen = (int)aV[LISTEN] == 1;
		for(int f = 0;f < aNbFrames;f++) {
			double aHL = highPass.process(aBuf[2*f], 0);
			double aHR = highPass.process(aBuf[2*f+1], 1);
			double aLevel = Math.max(Math.abs(aHL), Math.abs(aHR));
			env = aLevel > env ? aAtt*env+(1-aAtt)*aLevel : aRel*env+(1-aRel)*aLevel;
			double aG = 1;
			if(env > 1e-6) {
				double aOver = 20*Math.log10(env)-aTh;
				if(aOver > 0) {
					aG = Math.pow(10, -Math.min(aMax, aOver*(1-1/RATIO))/20);
				}
			}
			if(aListen) {
				aBuf[2*f] = (float)(aHL*aG);
				aBuf[2*f+1] = (float)(aHR*aG);
			}
			else {
				aBuf[2*f] = (float)(aBuf[2*f]-aHL+aHL*aG);
				aBuf[2*f+1] = (float)(aBuf[2*f+1]-aHR+aHR*aG);
			}
		}
	}
}
