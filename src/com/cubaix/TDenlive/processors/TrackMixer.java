package com.cubaix.TDenlive.processors;

import com.cubaix.TDenlive.TDenlive;
import com.cubaix.TDenlive.ffmpeg.AudioReader;
import com.cubaix.TDenlive.ffmpeg.AudioTracks;
import com.cubaix.TDenlive.medias.Clip;
import com.cubaix.TDenlive.medias.Media;

/**
 * Multi-track mixer: the volume of each audio stream of the media of the clip (an MP4 of the AI remix: its mix
 * then its separated tracks; a film with several languages...), with keys. Added at once to a clip whose media
 * has several audio streams (addIfMultiTrack(), from VideoTimeLine / AudioTimeLine.newClip()). Its gains start
 * from those of the remix (AudioTracks.gains), else the first stream at 100 % and the others muted.
 * The sound of the clip is then the sum of the streams at their volumes (Clip.mixAudio() → mix()), read at the
 * same media positions (speed included), before the other sound processors. Parameters track<k> (0 .. 400 %),
 * one per stream, named after the stream in the panel (Param.header "=" + label).
 */
public class TrackMixer extends AudioParamProcessor {
	public static final double GAIN_MAX = 4.0;
	//Not final, without initializer: set by the first params(), called by the constructor of ParamProcessor
	Param[] ps;

	public TrackMixer(TDenlive aTDe, Media aTargetMedia) {
		super(aTDe, aTargetMedia);
	}

	@Override
	public String getClassName() {return "TrackMixer";}

	@Override
	void setDescr() {
		super.setDescr();
		icon = "sound.gif";
	}

	/**
	 * Streams of the media of the clip (its sound, from the left or right file)
	 */
	AudioTracks tracks() {
		Clip aC = (Clip)targetMedia;
		AudioReader aAR = aC == null ? null : aC.getSoundReader();
		return AudioTracks.of(aAR == null ? null : aAR.path);
	}

	@Override
	public Param[] params() {
		if(ps == null) {
			AudioTracks aT = tracks();
			int aN = Math.max(1, aT.size());
			Param[] aPs = new Param[aN];
			String aLng = tde.config.lng;
			String aNoName = tde.gui.lngs.get("TrackMixer.Track");
			for(int k = 0;k < aN;k++) {
				aPs[k] = Param.range("track"+k, 0, aT.defaultGain(k), GAIN_MAX, 100)
						.header("="+aT.label(k, aLng, aNoName, aTitle -> showTitle(tde, aTitle))).letter("TrackMixer.GainLetter").tip("TrackMixer_track");
			}
			ps = aPs;
		}
		return ps;
	}

	/**
	 * Name of a stream shown: a stream of the AI remix ("[3De] Drums", names in English in the file) in the
	 * language of the interface (AI.RemixOriginal, AI.RemixStem_<source>), another name as it is
	 */
	public static String showTitle(TDenlive aTDe,String aTitle) {
		if(aTitle == null || !aTitle.startsWith(AudioTracks.REMIX_PREFIX)) {
			return aTitle;
		}
		String aName = aTitle.substring(AudioTracks.REMIX_PREFIX.length()).trim();
		String aKey = "Original".equalsIgnoreCase(aName) ? "AI.RemixOriginal" : "AI.RemixStem_"+aName.toLowerCase();
		String aT = aTDe.gui.lngs.get(aKey);
		if(aT == null || aT.startsWith("(??)")) {
			return aTitle;
		}
		return AudioTracks.REMIX_PREFIX+" "+aT;
	}

	public int nbTracks() {
		return params().length;
	}

	/**
	 * Sound of the clip: the streams at their volumes, ramped from the start to the end of the buffer.
	 * aRead reads a stream of the file (positions of the clip, speed) into a buffer.
	 */
	public void mix(AudioReader aBase,long aTimeMS,int aN,double aFrameMS,float[] aBuf,TrackRead aRead) {
		java.util.Arrays.fill(aBuf, 0, aN*AudioReader.CHANNELS, 0f);
		double[] aStart = valuesAt(aTimeMS);
		double[] aEnd = valuesAt(aTimeMS+(long)(aN*aFrameMS));
		float[] aTmp = null;
		for(int k = 0;k < aStart.length;k++) {
			if(aStart[k] <= 0 && aEnd[k] <= 0) {
				continue;
			}
			if(aTmp == null) {
				aTmp = new float[aN*AudioReader.CHANNELS];
			}
			aRead.read(aBase.track(k), k, aTmp);
			for(int f = 0;f < aN;f++) {
				double aFact = f/(double)aN;
				float aG = (float)(aStart[k]*(1.0-aFact)+aEnd[k]*aFact);
				aBuf[2*f] += aTmp[2*f]*aG;
				aBuf[2*f+1] += aTmp[2*f+1]*aG;
			}
		}
	}

	public interface TrackRead {
		void read(AudioReader aAR,int aTrack,float[] aOut);
	}

	/**
	 * Readers of the streams heard (to wait for their decoding before a rendering)
	 */
	public java.util.Vector<AudioReader> readers(AudioReader aBase) {
		java.util.Vector<AudioReader> aRs = new java.util.Vector<AudioReader>();
		for(int k = 0;k < nbTracks();k++) {
			aRs.add(aBase.track(k));
		}
		return aRs;
	}

	@Override
	public void processAudio(Media aMedia, float[] aBuf, int aNbFrames, long aTimeMS, double aFrameMS) {
		//Mixed when the sound of the clip is read (Clip.mixAudio())
	}

	/**
	 * A clip whose media has several audio streams gets its mixer (a new clip on a time line)
	 */
	public static void addIfMultiTrack(Clip aClip) {
		try {
			if(aClip == null || aClip.getMedia() == null || aClip.getTrackMixer(false) != null) {
				return;
			}
			AudioReader aAR = aClip.getSoundReader();
			if(aAR == null || AudioTracks.of(aAR.path).size() < 2) {
				return;
			}
			aClip.getProcessors().add(new TrackMixer(aClip.tde, aClip));
		}
		catch(Throwable t) {
			t.printStackTrace(System.err);
		}
	}
}
