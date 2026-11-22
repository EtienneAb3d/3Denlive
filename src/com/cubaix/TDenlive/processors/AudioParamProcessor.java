package com.cubaix.TDenlive.processors;

import com.cubaix.TDenlive.TDenlive;
import com.cubaix.TDenlive.ffmpeg.AudioReader;
import com.cubaix.TDenlive.medias.Media;

/**
 * ParamProcessor working on the sound (no effect on the images). follows() tells whether a
 * buffer continues the previous one (the state of the filters is reset at a jump: seek, new play)
 */
public abstract class AudioParamProcessor extends ParamProcessor {
	long nextFrame = Long.MIN_VALUE;

	public AudioParamProcessor(TDenlive aTDe, Media aTargetMedia) {
		super(aTDe, aTargetMedia);
	}

	@Override
	public boolean isAudio() {return true;}

	@Override
	public void process2Awt(Media aMedia, int aProcessingMode, long aTime) {
		//No effect on images
	}

	/**
	 * True if the buffer of aN frames at aTimeMS (time line) follows the previous one
	 * (aTimeMS is rounded to the ms: a tolerance of a few ms)
	 */
	protected boolean follows(long aTimeMS,int aN) {
		long aF = aTimeMS*AudioReader.RATE/1000;
		boolean aFollows = Math.abs(aF-nextFrame) <= 2*AudioReader.RATE/1000+1;
		nextFrame = aF+aN;
		return aFollows;
	}
}
