package com.cubaix.TDenlive.audio;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.util.HashSet;
import java.util.Vector;

import com.cubaix.TDenlive.TDenlive;
import com.cubaix.TDenlive.ffmpeg.AudioReader;
import com.cubaix.TDenlive.medias.VideoTimeLine;
import com.cubaix.TDenlive.medias.Clip;

/**
 * Mix of the sounds of all the time lines (videos and audio lines), the sound counterpart of TimeLineStack.reBuild().
 * The lines off, or muted, are not heard.
 */
public class AudioMixer {
	public static final int RATE = AudioReader.RATE;
	public static final int CHANNELS = AudioReader.CHANNELS;

	TDenlive tde = null;

	public AudioMixer(TDenlive aTDe) {
		tde = aTDe;
	}

	public static long ms2Frame(long aMS) {
		return aMS*RATE/1000;
	}

	public static long frame2MS(long aFrame) {
		return aFrame*1000/RATE;
	}

	/**
	 * Mix aNbFrames from aStartFrame (time line position) into aOut (interleaved stereo, -1..1, clipped)
	 */
	public void mix(long aStartFrame,float[] aOut,int aNbFrames) {
		java.util.Arrays.fill(aOut, 0, aNbFrames*CHANNELS, 0.0f);
		for(VideoTimeLine aTL : allLines()) {
			aTL.mixAudio(aStartFrame, aOut, aNbFrames);
		}
		for(int s = 0;s < aNbFrames*CHANNELS;s++) {
			if(aOut[s] > 1.0f) {
				aOut[s] = 1.0f;
			}
			else if(aOut[s] < -1.0f) {
				aOut[s] = -1.0f;
			}
		}
	}

	public static void toPcm16(float[] aIn,byte[] aOut,int aNbFrames) {
		for(int s = 0;s < aNbFrames*CHANNELS;s++) {
			int aV = Math.round(aIn[s]*32767.0f);
			aOut[2*s] = (byte)(aV & 0xFF);
			aOut[2*s+1] = (byte)((aV >> 8) & 0xFF);
		}
	}

	/**
	 * True if at least one active audio time line holds a clip with a sound
	 */
	public boolean hasSound() {
		for(VideoTimeLine aTL : allLines()) {
			if(aTL.hasSound()) {
				return true;
			}
		}
		return false;
	}
	
	Vector<VideoTimeLine> allLines() {
		Vector<VideoTimeLine> aLines = new Vector<VideoTimeLine>(tde.timeLineStack.videoTimeLineStack);
		aLines.addAll(tde.timeLineStack.audioTimeLineStack);
		return aLines;
	}

	/**
	 * Wait for all the sounds used to be fully decoded (before a final rendering)
	 */
	public void waitDecoded() {
		HashSet<AudioReader> aReaders = new HashSet<AudioReader>();
		for(VideoTimeLine aTL : allLines()) {
			for(Clip aC : new Vector<Clip>(aTL.clipList)) {
				if(aC.getMedia() != null && aC.getMedia().getAudioReader() != null) {
					aReaders.add(aC.getMedia().getAudioReader());
				}
			}
		}
		for(AudioReader aAR : aReaders) {
			aAR.waitDecoded();
		}
	}

	/**
	 * Mix [aStartMS, aEndMS[ of the time lines into a WAV file (16 bits stereo), for the final rendering.
	 * Returns false if there is no sound to mix.
	 */
	public boolean renderWav(long aStartMS,long aEndMS,File aWav) {
		if(!hasSound()) {
			return false;
		}
		waitDecoded();
		long aStartF = ms2Frame(aStartMS);
		long aEndF = ms2Frame(aEndMS);
		long aDataBytes = (aEndF-aStartF)*AudioReader.FRAME_BYTES;
		try(OutputStream aOS = new BufferedOutputStream(new FileOutputStream(aWav), 1024*1024)) {
			writeWavHeader(aOS, aDataBytes);
			int aChunk = RATE/10;
			float[] aMix = new float[aChunk*CHANNELS];
			byte[] aPcm = new byte[aChunk*AudioReader.FRAME_BYTES];
			for(long aF = aStartF;aF < aEndF;aF += aChunk) {
				int aN = (int)Math.min(aChunk, aEndF-aF);
				mix(aF, aMix, aN);
				toPcm16(aMix, aPcm, aN);
				aOS.write(aPcm, 0, aN*AudioReader.FRAME_BYTES);
			}
		}
		catch(Throwable t) {
			t.printStackTrace(System.err);
			return false;
		}
		return true;
	}

	static void writeWavHeader(OutputStream aOS,long aDataBytes) throws Exception {
		long aMax = 0xFFFFFFFFL-36;
		if(aDataBytes > aMax) {
			aDataBytes = aMax;//WAV limit (4 GB), more than 6 hours
		}
		int aByteRate = RATE*AudioReader.FRAME_BYTES;
		aOS.write("RIFF".getBytes("US-ASCII"));
		writeInt(aOS, (int)(36+aDataBytes));
		aOS.write("WAVE".getBytes("US-ASCII"));
		aOS.write("fmt ".getBytes("US-ASCII"));
		writeInt(aOS, 16);
		writeShort(aOS, 1);//PCM
		writeShort(aOS, CHANNELS);
		writeInt(aOS, RATE);
		writeInt(aOS, aByteRate);
		writeShort(aOS, AudioReader.FRAME_BYTES);
		writeShort(aOS, 16);
		aOS.write("data".getBytes("US-ASCII"));
		writeInt(aOS, (int)aDataBytes);
	}

	static void writeInt(OutputStream aOS,int aV) throws Exception {
		aOS.write(aV & 0xFF);
		aOS.write((aV >> 8) & 0xFF);
		aOS.write((aV >> 16) & 0xFF);
		aOS.write((aV >> 24) & 0xFF);
	}

	static void writeShort(OutputStream aOS,int aV) throws Exception {
		aOS.write(aV & 0xFF);
		aOS.write((aV >> 8) & 0xFF);
	}
}
