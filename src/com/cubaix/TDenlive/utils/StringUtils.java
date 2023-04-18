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
	public static String path2workingcopy(String aPath) {
		return aPath.substring(0, aPath.lastIndexOf("."))+"_3De_WorkCopy."
//				+ "avi";
				+ "mp4";
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
