package com.cubaix.TDenlive.ffmpeg;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.util.ArrayList;
import java.util.HashMap;

/**
 * Sound track of a media file (video or audio only), decoded once by ffmpeg into a raw PCM cache file
 * (signed 16 bits little endian, stereo, RATE Hz) in the cache folder of the project. The cache is then read
 * by random access, so any position can be mixed at once (playback, rendering).
 * A peak envelope (one value per PEAK_MS) is kept in memory to draw the waveforms.
 * One reader per file and cache folder, shared by all the medias and clips using it (see get()).
 * The cache folder is given by TDenlive.getWorkCacheDir() (next to the project file).
 */
public class AudioReader extends Ffmpeg {
	public static final int RATE = 48000;
	public static final int CHANNELS = 2;
	public static final int FRAME_BYTES = 2*CHANNELS;
	public static final int PEAK_MS = 10;
	static final int PEAK_FRAMES = RATE*PEAK_MS/1000;

	static final HashMap<String,AudioReader> READERS = new HashMap<String,AudioReader>();
	//Called (from a worker thread) when a sound is fully available, to redraw the waveforms
	public static volatile Runnable decodedListener = null;

	public String path = null;
	String cacheDir = null;
	File cacheFile = null;
	RandomAccessFile raf = null;
	volatile long nbFrames = 0;//Frames available in the cache
	volatile boolean decoded = false;//Cache complete (or no audio)
	volatile boolean failed = false;

	byte[] peaks = new byte[1024];//0..127 per PEAK_MS
	int nbPeaks = 0;
	int curPeak = 0;
	int curPeakFrames = 0;

	public static AudioReader get(String aPath,String aCacheDir) {
		if(aPath == null) {
			return null;
		}
		synchronized (READERS) {
			String aK = aCacheDir+"|"+aPath;
			AudioReader aAR = READERS.get(aK);
			if(aAR == null) {
				aAR = new AudioReader(aPath,aCacheDir);
				READERS.put(aK, aAR);
			}
			return aAR;
		}
	}
	
	/**
	 * Copy the complete caches into a new cache folder (project saved elsewhere),
	 * so that the project does not decode the sounds again when opened
	 */
	public static void copyCachesTo(String aCacheDir) {
		java.util.Vector<AudioReader> aARs;
		synchronized (READERS) {
			aARs = new java.util.Vector<AudioReader>(READERS.values());
		}
		for(AudioReader aAR : aARs) {
			try {
				File aDest = new File(aCacheDir, aAR.cacheFile.getName());
				if(!aAR.decoded || aAR.failed || aDest.exists() || !aAR.cacheFile.isFile()
						|| aDest.getAbsoluteFile().equals(aAR.cacheFile.getAbsoluteFile())) {
					continue;
				}
				new File(aCacheDir).mkdirs();
				java.nio.file.Files.copy(aAR.cacheFile.toPath(), aDest.toPath());
			}
			catch(Throwable t) {
				t.printStackTrace(System.err);
			}
		}
	}

	AudioReader(String aPath,String aCacheDir) {
		path = aPath;
		cacheDir = aCacheDir;
		File aIn = new File(aPath);
		String aName = aIn.getName().replaceAll("[^A-Za-z0-9._-]", "_");
		String aKey = Integer.toHexString((aIn.getAbsolutePath()+"|"+aIn.length()+"|"+aIn.lastModified()).hashCode());
		cacheFile = new File(aCacheDir, aName+"_"+aKey+".pcm");
		Thread aTh = new Thread(new Runnable() {
			@Override
			public void run() {
				if(cacheFile.isFile()) {
					openCache();
				}
				else {
					decode();
				}
			}
		});
		aTh.setDaemon(true);
		aTh.start();
	}

	void openCache() {
		try {
			synchronized (this) {
				raf = new RandomAccessFile(cacheFile, "r");
				nbFrames = raf.length()/FRAME_BYTES;
				duration = nbFrames*1000/RATE;
				hasAudioStream = nbFrames > 0;
				inputParsed = true;
				decoded = true;
			}
			//Rebuild the waveform envelope
			byte[] aBuf = new byte[PEAK_FRAMES*FRAME_BYTES*100];
			long aPos = 0;
			while(true) {
				int aRead;
				synchronized (this) {
					raf.seek(aPos);
					aRead = raf.read(aBuf);
				}
				if(aRead <= 0) {
					break;
				}
				aRead -= aRead % FRAME_BYTES;
				addPeaks(aBuf, aRead);
				aPos += aRead;
			}
			flushPeak();
		}
		catch(Throwable t) {
			t.printStackTrace(System.err);
			failed = true;
			decoded = true;
		}
		notifyDecoded();
	}
	
	void notifyDecoded() {
		Runnable aL = decodedListener;
		if(aL != null) {
			try {
				aL.run();
			}
			catch(Throwable t) {
				t.printStackTrace(System.err);
			}
		}
	}

	void decode() {
		Process p = null;
		File aPart = new File(cacheFile.getPath()+".part");
		try {
			new File(cacheDir).mkdirs();
			String ffmpegPath = getFfmpeg();
			if(ffmpegPath == null) {
				failed = true;
				return;
			}
			ArrayList<String> aCmdA = new ArrayList<String>();
			aCmdA.add(ffmpegPath);
			aCmdA.add("-nostdin");
			aCmdA.add("-hide_banner");
			aCmdA.add("-nostats");
			aCmdA.add("-i"); aCmdA.add(path);
			aCmdA.add("-vn"); aCmdA.add("-sn"); aCmdA.add("-dn");
			aCmdA.add("-map"); aCmdA.add("0:a:0");//First audio stream
			aCmdA.add("-ac"); aCmdA.add(""+CHANNELS);
			aCmdA.add("-ar"); aCmdA.add(""+RATE);
			aCmdA.add("-acodec"); aCmdA.add("pcm_s16le");
			aCmdA.add("-f"); aCmdA.add("s16le");
			aCmdA.add("pipe:1");
			System.out.println("cmd: "+String.join(" ", aCmdA));
			p = new ProcessBuilder(aCmdA).start();
			StreamReader aErr = new StreamReader(p.getErrorStream());
			Thread aErrTh = new Thread(aErr);
			aErrTh.setDaemon(true);
			aErrTh.start();
			synchronized (this) {
				raf = new RandomAccessFile(aPart, "rw");
				raf.setLength(0);
			}
			InputStream aIS = new BufferedInputStream(p.getInputStream());
			byte[] aBuf = new byte[64*1024];
			int aKeep = 0;//Bytes of an incomplete frame kept at the start of aBuf
			int aRead;
			while((aRead = aIS.read(aBuf, aKeep, aBuf.length-aKeep)) > 0) {
				int aTotal = aKeep+aRead;
				int aUsable = aTotal - aTotal % FRAME_BYTES;
				synchronized (this) {
					raf.seek(nbFrames*FRAME_BYTES);
					raf.write(aBuf, 0, aUsable);
					addPeaks(aBuf, aUsable);
					nbFrames += aUsable/FRAME_BYTES;
				}
				aKeep = aTotal - aUsable;
				if(aKeep > 0) {
					System.arraycopy(aBuf, aUsable, aBuf, 0, aKeep);
				}
			}
			int aExit = p.waitFor();
			aErrTh.join(2000);
			synchronized (this) {
				flushPeak();
				raf.close();
				raf = null;
				if(nbFrames > 0 || (inputParsed && !hasAudioStream)) {
					//Complete, or no audio at all (an empty cache avoids decoding again)
					if(nbFrames <= 0) {
						System.out.println("No audio in "+path);
					}
					cacheFile.delete();
					if(!aPart.renameTo(cacheFile)) {
						System.err.println("Can't rename "+aPart+" (exit "+aExit+")");
					}
					raf = new RandomAccessFile(cacheFile.isFile() ? cacheFile : aPart, "r");
				}
				else {
					System.err.println("Audio decoding failed for "+path+" (exit "+aExit+")");
					aPart.delete();
					failed = true;
				}
				if(nbFrames > 0) {
					duration = nbFrames*1000/RATE;
				}
				inputParsed = true;
				decoded = true;
			}
		}
		catch(Throwable t) {
			t.printStackTrace(System.err);
			failed = true;
			inputParsed = true;
			decoded = true;
			if(p != null) {
				p.destroy();
			}
		}
		notifyDecoded();
	}

	void addPeaks(byte[] aBuf,int aLen) {
		synchronized (this) {
			for(int b = 0;b+1 < aLen;b += 2) {
				int aV = Math.abs((short)((aBuf[b] & 0xFF) | (aBuf[b+1] << 8)));
				if(aV > curPeak) {
					curPeak = aV;
				}
				if(((b+2) % FRAME_BYTES) == 0) {
					curPeakFrames++;
					if(curPeakFrames >= PEAK_FRAMES) {
						flushPeak();
					}
				}
			}
		}
	}

	void flushPeak() {
		if(curPeakFrames <= 0) {
			return;
		}
		if(nbPeaks >= peaks.length) {
			byte[] aNew = new byte[peaks.length*2];
			System.arraycopy(peaks, 0, aNew, 0, nbPeaks);
			peaks = aNew;
		}
		peaks[nbPeaks++] = (byte)Math.min(127, curPeak*127/32767);
		curPeak = 0;
		curPeakFrames = 0;
	}

	/**
	 * Max level (0..1) of the sound between the 2 times (ms in the file), -1 if unknown yet
	 */
	public float getPeak(long aFromMS,long aToMS) {
		synchronized (this) {
			int aFrom = (int)Math.max(0, aFromMS/PEAK_MS);
			int aTo = (int)Math.min(nbPeaks, Math.max(aFrom+1, aToMS/PEAK_MS));
			if(aFrom >= nbPeaks) {
				return -1;
			}
			int aMax = 0;
			for(int p = aFrom;p < aTo;p++) {
				if(peaks[p] > aMax) {
					aMax = peaks[p];
				}
			}
			return aMax/127.0f;
		}
	}

	/**
	 * Read aNbFrames from aFrame (position in the file) into aOut (interleaved L R, -1..1).
	 * What is not available (before the start, after the end, not yet decoded) is silence.
	 */
	public void read(long aFrame,float[] aOut,int aNbFrames) {
		java.util.Arrays.fill(aOut, 0, aNbFrames*CHANNELS, 0.0f);
		synchronized (this) {
			if(raf == null) {
				return;
			}
			long aFrom = Math.max(0, aFrame);
			long aTo = Math.min(nbFrames, aFrame+aNbFrames);
			if(aTo <= aFrom) {
				return;
			}
			int aN = (int)(aTo-aFrom);
			byte[] aBuf = new byte[aN*FRAME_BYTES];
			try {
				raf.seek(aFrom*FRAME_BYTES);
				raf.readFully(aBuf);
			}
			catch(Throwable t) {
				t.printStackTrace(System.err);
				return;
			}
			int aOff = (int)(aFrom-aFrame)*CHANNELS;
			for(int s = 0;s < aN*CHANNELS;s++) {
				aOut[aOff+s] = ((short)((aBuf[2*s] & 0xFF) | (aBuf[2*s+1] << 8)))/32768.0f;
			}
		}
	}

	/**
	 * Wait (at most aMaxMS) for ffmpeg to describe the input, to know if there is an audio stream
	 */
	public boolean hasAudio(long aMaxMS) {
		long aStart = System.currentTimeMillis();
		while(!inputParsed && !decoded && System.currentTimeMillis()-aStart < aMaxMS) {
			try {
				Thread.sleep(10);
			}
			catch(Throwable t) {}
		}
		return hasAudioStream && !failed;
	}

	/**
	 * Duration of the sound, waiting (at most aMaxMS) for ffmpeg to give it. -1 if unknown.
	 */
	public long getDurationMS(long aMaxMS) {
		long aStart = System.currentTimeMillis();
		while(duration < 0 && !decoded && System.currentTimeMillis()-aStart < aMaxMS) {
			try {
				Thread.sleep(10);
			}
			catch(Throwable t) {}
		}
		return duration;
	}

	public boolean isDecoded() {
		return decoded;
	}

	/**
	 * Wait for the full decoding (needed before a final rendering)
	 */
	public void waitDecoded() {
		while(!decoded) {
			try {
				Thread.sleep(20);
			}
			catch(Throwable t) {}
		}
	}
}
