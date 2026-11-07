package com.cubaix.TDenlive.audio;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.SourceDataLine;

import com.cubaix.TDenlive.TDenlive;
import com.cubaix.TDenlive.ffmpeg.AudioReader;
import com.cubaix.TDenlive.medias.Media;

/**
 * Real time playback of the audio time lines on the sound card (javax.sound, works with a headless AWT).
 * While playing, the sound card is the master clock: getPositionMS() gives the time line position
 * actually heard, and the images follow it.
 */
public class AudioPlayer {
	static final int CHUNK_FRAMES = AudioMixer.RATE/50;//20 ms
	static final int LINE_BUFFER_FRAMES = AudioMixer.RATE/8;//125 ms of latency

	TDenlive tde = null;
	AudioMixer mixer = null;
	SourceDataLine line = null;
	Thread playTh = null;
	volatile boolean playing = false;
	long startMS = 0;
	//If set, plays the sound of this library media alone (positions in the media), instead of the time lines
	Media media = null;

	public AudioPlayer(TDenlive aTDe) {
		tde = aTDe;
		mixer = new AudioMixer(tde);
	}

	public AudioPlayer(TDenlive aTDe,Media aMedia) {
		this(aTDe);
		media = aMedia;
	}

	boolean hasSound() {
		if(media == null) {
			return mixer.hasSound();
		}
		AudioReader aAR = media.getAudioReader();
		return aAR != null && aAR.hasAudio(1000);
	}

	void mix(long aFrame,float[] aMix,int aNbFrames) {
		if(media == null) {
			mixer.mix(aFrame, aMix, aNbFrames);
		}
		else {
			media.getAudioReader().read(aFrame, aMix, aNbFrames);
		}
	}

	/**
	 * Start playing from a time line position.
	 * False if there is nothing to hear (the caller keeps its own clock) or no sound output is available.
	 */
	public synchronized boolean start(long aFromMS) {
		stop();
		if(!hasSound()) {
			return false;
		}
		try {
			AudioFormat aFormat = new AudioFormat(AudioMixer.RATE, 16, AudioMixer.CHANNELS, true, false);
			line = AudioSystem.getSourceDataLine(aFormat);
			line.open(aFormat, LINE_BUFFER_FRAMES*AudioReader.FRAME_BYTES);
		}
		catch(Throwable t) {
			System.err.println("No sound output: "+t);
			line = null;
			return false;
		}
		startMS = aFromMS;
		playing = true;
		final SourceDataLine aLine = line;
		playTh = new Thread(new Runnable() {
			@Override
			public void run() {
				float[] aMix = new float[CHUNK_FRAMES*AudioMixer.CHANNELS];
				byte[] aPcm = new byte[CHUNK_FRAMES*AudioReader.FRAME_BYTES];
				long aFrame = AudioMixer.ms2Frame(startMS);
				aLine.start();
				while(playing) {
					try {
						mix(aFrame, aMix, CHUNK_FRAMES);
						AudioMixer.toPcm16(aMix, aPcm, CHUNK_FRAMES);
						aLine.write(aPcm, 0, aPcm.length);//Blocks while the line buffer is full
						aFrame += CHUNK_FRAMES;
					}
					catch(Throwable t) {
						t.printStackTrace(System.err);
						break;
					}
				}
				try {
					aLine.stop();
					aLine.flush();
					aLine.close();
				}
				catch(Throwable t) {
					t.printStackTrace(System.err);
				}
			}
		});
		playTh.setDaemon(true);
		playTh.setPriority(Thread.MAX_PRIORITY);
		playTh.start();
		return true;
	}

	/**
	 * Time line position currently heard
	 */
	public long getPositionMS() {
		SourceDataLine aLine = line;
		if(aLine == null) {
			return startMS;
		}
		return startMS + AudioMixer.frame2MS(aLine.getLongFramePosition());
	}

	public boolean isPlaying() {
		return playing;
	}

	public synchronized void stop() {
		playing = false;
		if(playTh != null) {
			try {
				playTh.join(1000);
			}
			catch(Throwable t) {}
			playTh = null;
		}
		line = null;
	}
}
