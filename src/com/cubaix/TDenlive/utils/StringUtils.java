package com.cubaix.TDenlive.utils;

import java.io.File;
import java.text.NumberFormat;

public class StringUtils {
	
	public static String path2processing(String aPath) {
		try {
			return new File(aPath).getParentFile().getPath()+File.separatorChar
					+"processing_"+new File(aPath).getName();
		}
		catch(Throwable t) {
			t.printStackTrace(System.err);
		}
		return null;
	}
	/**
	 * Copy of a video used for the work preview (VideoReader: at the work resolution of the monitor, 15 fps),
	 * in the work folder of the project (TDenlive.getWorkCacheDir()): <video>_<key>_3De_WorkCopyHQ.mp4, the key
	 * telling apart the videos of the same name in different folders.
	 * The copies of 1.11.6 and before (_3De_WorkCopy.mp4, 576 lines at 300 kbit/s, next to the video before
	 * 1.0.16) are no longer used: see oldWorkingCopy().
	 */
	public static String path2workingcopy(String aPath,String aWorkCacheDir) {
		File aIn = new File(aPath);
		String aBase = aIn.getName().replaceAll("[.][^.]*$", "");
		if(aWorkCacheDir == null) {
			return new File(aIn.getParentFile(), aBase+(com.cubaix.TDenlive.ffmpeg.Ffmpeg.isAnaglyph(aPath) ? "_a" : "")+"_3De_WorkCopyHQ.mp4").getPath();
		}
		//An anaglyph: its own copy (layers kept apart, 4:4:4: see Ffmpeg.anaglyphFilter()), made again from the older ones
		String aKey = Integer.toHexString(aIn.getAbsolutePath().hashCode())+(com.cubaix.TDenlive.ffmpeg.Ffmpeg.isAnaglyph(aPath) ? "a" : "");
		return new File(aWorkCacheDir, aBase.replaceAll("[^A-Za-z0-9._-]", "_")+"_"+aKey+"_3De_WorkCopyHQ.mp4").getPath();
	}

	/**
	 * Texture copy of a video in the work folder: the same as path2workingcopy() at the VHS height, read by the 3D
	 * models that have the video as texture outside the RENDER mode (VideoReader texture mode)
	 */
	public static String path2texturecopy(String aPath,String aWorkCacheDir) {
		return path2workingcopy(aPath, aWorkCacheDir).replaceAll("_3De_WorkCopyHQ[.]mp4$", "_3De_WorkCopyTex.mp4");
	}

	/**
	 * Work copy of the same video made by 1.11.6 and before in the work folder (deleted when a new one is made)
	 */
	public static String oldWorkingCopy(String aPath,String aWorkCacheDir) {
		return path2workingcopy(aPath, aWorkCacheDir).replaceAll("_3De_WorkCopyHQ[.]mp4$", "_3De_WorkCopy.mp4");
	}
	
	public static String time2Ffmpeg(long aTimeMS) {
		int aH = (int)(aTimeMS / 3600000);
		int aM = (int)((aTimeMS % 3600000) / 60000);
		int aS = (int)((aTimeMS % 60000) / 1000);
		int aCS = (int)(aTimeMS % 1000)/10;
		NumberFormat aNF = NumberFormat.getInstance();
		aNF.setMinimumIntegerDigits(2);
		return aNF.format(aH)+":"+aNF.format(aM)+":"+aNF.format(aS)+"."+aNF.format(aCS);
	}
	
	public static long ffmpeg2Time(String aClock) {
		if(!aClock.matches("[0-9][0-9]:[0-6][0-9]:[0-6][0-9][.][0-9][0-9]")) {
			return -1;
		}
		int aH = Integer.parseInt(aClock.substring(0, 2));
		int aM = Integer.parseInt(aClock.substring(3, 5));
		int aS = Integer.parseInt(aClock.substring(6, 8));
		int aCS = Integer.parseInt(aClock.substring(9, 11));
		if(aM >= 60 || aS >= 60) {
			return -1;
		}
		return aH*3600000
				+aM*60000
				+aS*1000
				+aCS*10;
	}

	public static String time2Clock(long aTimeMS) {
		int aH = (int)(aTimeMS / 3600000);
		int aM = (int)((aTimeMS % 3600000) / 60000);
		int aS = (int)((aTimeMS % 60000) / 1000);
		int aCS = (int)(aTimeMS % 1000)/10;
		NumberFormat aNF = NumberFormat.getInstance();
		aNF.setMinimumIntegerDigits(2);
		return aNF.format(aH)+":"+aNF.format(aM)+":"+aNF.format(aS)+":"+aNF.format(aCS);
	}
	
	public static long clock2Time(String aClock) {
		if(!aClock.matches("[0-9][0-9]:[0-6][0-9]:[0-6][0-9]:[0-9][0-9]")) {
			return -1;
		}
		int aH = Integer.parseInt(aClock.substring(0, 2));
		int aM = Integer.parseInt(aClock.substring(3, 5));
		int aS = Integer.parseInt(aClock.substring(6, 8));
		int aCS = Integer.parseInt(aClock.substring(9, 11));
		if(aM >= 60 || aS >= 60) {
			return -1;
		}
		return aH*3600000
				+aM*60000
				+aS*1000
				+aCS*10;
	}
}
