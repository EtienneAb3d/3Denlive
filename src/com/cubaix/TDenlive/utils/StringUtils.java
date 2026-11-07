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
	 * Low resolution copy of a video used for the work preview, in the work folder of the project
	 * (TDenlive.getWorkCacheDir()): <video>_<key>_3De_WorkCopy.mp4, the key telling apart the videos
	 * of the same name in different folders.
	 * A work copy made by 1.0.15 and before, next to the video, is still used if there is no other.
	 */
	public static String path2workingcopy(String aPath,String aWorkCacheDir) {
		File aIn = new File(aPath);
		String aBase = aIn.getName().replaceAll("[.][^.]*$", "");
		String aLegacy = new File(aIn.getParentFile(), aBase+"_3De_WorkCopy.mp4").getPath();
		if(aWorkCacheDir == null) {
			return aLegacy;
		}
		String aKey = Integer.toHexString(aIn.getAbsolutePath().hashCode());
		String aWork = new File(aWorkCacheDir, aBase.replaceAll("[^A-Za-z0-9._-]", "_")+"_"+aKey+"_3De_WorkCopy.mp4").getPath();
		if(!new File(aWork).exists() && !new File(path2processing(aWork)).exists() && new File(aLegacy).isFile()) {
			return aLegacy;
		}
		return aWork;
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
