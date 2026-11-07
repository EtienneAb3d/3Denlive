package com.cubaix.TDenlive.ffmpeg;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;

import com.cubaix.TDenlive.utils.StringUtils;

public class Ffmpeg {
	volatile long duration = -1;
	volatile double fps = -1;
	//Set by the stderr reader: the input has an audio stream / the input description is fully read
	volatile boolean hasAudioStream = false;
	volatile boolean inputParsed = false;

	public Ffmpeg() {
	}
	
	//Folder holding the bundled binaries, relative to the working directory (like lumas/ and frames/)
	//Filled in dev by Install-Dev.sh, shipped next to the launch script in distributions
	static final String FFMPEG_DIR = "ffmpeg";
	static String ffmpegPath = null;

	String getFfmpeg() {
		if(ffmpegPath == null) {
			ffmpegPath = findFfmpeg();
			System.err.println("ffmpeg: " + ffmpegPath);
		}
		return ffmpegPath;
	}

	static String findFfmpeg() {
		if(new File("/usr/bin/ffmpeg").exists()) {
			return "/usr/bin/ffmpeg";
		}

		String os = System.getProperty("os.name").toLowerCase();
		boolean isWindows = os.contains("windows");
		boolean isMac = os.contains("mac");

		//Bundled binary: ffmpeg-amd64(.exe), ffmpeg-x86_64-osx, ffmpeg-aarch64-osx, ...
		String suffix = isWindows ? ".exe" : (isMac ? "-osx" : "");
		String arch = System.getProperty("os.arch");
		File aBundled = new File(FFMPEG_DIR, "ffmpeg-" + arch + suffix);
		if(aBundled.isFile()) {
			if(!isWindows && !aBundled.canExecute()) {
				aBundled.setExecutable(true);
			}
			return aBundled.getAbsolutePath();
		}

		//Installed ffmpeg (PATH, then usual package manager locations not always in the PATH of a GUI app)
		String aExe = isWindows ? "ffmpeg.exe" : "ffmpeg";
		String aPath = System.getenv("PATH");
		if(aPath != null) {
			for(String aDir : aPath.split(File.pathSeparator)) {
				File aF = new File(aDir, aExe);
				if(aF.isFile() && aF.canExecute()) {
					return aF.getAbsolutePath();
				}
			}
		}
		for(String aCandidate : new String[] {"/opt/homebrew/bin/ffmpeg", "/usr/local/bin/ffmpeg"}) {
			if(new File(aCandidate).canExecute()) {
				return aCandidate;
			}
		}

		System.err.println("ffmpeg not found: run Install-Dev.sh, or put " + aBundled.getAbsolutePath() + " in place, or install ffmpeg");
		return null;
	}
	
	/**
	 * Read the next BMP image of a stream of concatenated BMPs (ffmpeg image2pipe), null at the end.
	 * Each file is first cut with the size of its header: ImageIO alone may leave the padding
	 * of the last row (width*3 not multiple of 4) in the stream, and then misread all the next images.
	 */
	public static java.awt.image.BufferedImage readBmp(InputStream aIS) throws IOException {
		java.io.DataInputStream aDIS = new java.io.DataInputStream(aIS);
		byte[] aHeader = new byte[14];
		try {
			aDIS.readFully(aHeader);
		}
		catch(java.io.EOFException e) {
			return null;
		}
		if(aHeader[0] != 'B' || aHeader[1] != 'M') {
			throw new IOException("Not a BMP stream");
		}
		int aSize = (aHeader[2] & 0xFF) | (aHeader[3] & 0xFF) << 8 | (aHeader[4] & 0xFF) << 16 | (aHeader[5] & 0xFF) << 24;
		byte[] aFile = new byte[aSize];
		System.arraycopy(aHeader, 0, aFile, 0, 14);
		aDIS.readFully(aFile, 14, aSize-14);
		return javax.imageio.ImageIO.read(new java.io.ByteArrayInputStream(aFile));
	}
	
	class StreamReader implements Runnable {

	    protected final InputStream inputStream;

	    StreamReader(InputStream inputStream) {
	        this.inputStream = inputStream;
	    }

	    private BufferedReader getBufferedReader(InputStream is) {
	        return new BufferedReader(new InputStreamReader(is));
	    }

	    @Override
	    public void run() {
	        BufferedReader aBr = getBufferedReader(inputStream);
	        String aLine = "";
	        try {
	            while ((aLine = aBr.readLine()) != null) {
	                System.out.println(aLine);
	            	aLine = aLine.trim();
	            	if(aLine.matches("Duration: [0-9][0-9]:[0-9][0-9]:[0-9][0-9][.][0-9][0-9],.*")) {
	            		String aDuration = aLine.substring("Duration: ".length());
	            		aDuration = aDuration.substring(0, aDuration.indexOf(","));
	            		duration = StringUtils.ffmpeg2Time(aDuration);
	            	}
	            	if(aLine.matches(".*, [0-9.]+ fps,.*")) {
	            		String aFps = aLine.substring(0, aLine.indexOf("fps,"));
	            		aFps = aFps.substring(aFps.lastIndexOf(",")+1).trim();
	            		fps = Double.parseDouble(aFps);
	            	}
	            	if(aLine.matches("Stream #.*: Audio: .*")) {
	            		hasAudioStream = true;
	            	}
	            	if(aLine.startsWith("Output #") || aLine.startsWith("Stream mapping:")
	            			|| aLine.matches(".*matches no streams.*")) {
	            		inputParsed = true;
	            	}
	            }
	        } catch (IOException e) {
//	            e.printStackTrace();
	        }
	    }
	}
}
