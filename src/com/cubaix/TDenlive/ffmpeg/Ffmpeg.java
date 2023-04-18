package com.cubaix.TDenlive.ffmpeg;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;

import com.cubaix.TDenlive.utils.StringUtils;

public class Ffmpeg {
	long duration = -1;
	double fps = -1;

	public Ffmpeg() {
	}
	
	String getFfmpeg() {
		if(new File("/usr/bin/ffmpeg").exists()) {
			return "/usr/bin/ffmpeg";
		}

		String os = System.getProperty("os.name").toLowerCase();
		boolean isWindows = os.contains("windows");
		boolean isMac = os.contains("mac");

		File dirFolder = new File(System.getProperty("java.io.tmpdir"), "3De/");
		if (!dirFolder.exists()) {
			dirFolder.mkdirs();
		}

		String suffix = isWindows ? ".exe" : (isMac ? "-osx" : "");
		String arch = System.getProperty("os.arch");

		File ffmpegFile = new File(dirFolder, "ffmpeg-" + arch + suffix);

		if (ffmpegFile.exists()) {
			return ffmpegFile.getAbsolutePath();
		}

		copyFile("ffmpeg-" + arch + suffix, ffmpegFile);

		if (!isWindows) {
			try {
				Runtime.getRuntime().exec(new String[] {"/bin/chmod", "755", ffmpegFile.getAbsolutePath()});
			} 
			catch (Throwable t) {
				t.printStackTrace(System.err);
			}
		}

		if (ffmpegFile.exists()){
			return ffmpegFile.getAbsolutePath();
		}

		return null;
	}
	
	private void copyFile(String path, File dest) {
		String resourceName = "bin/" + path;
		try {
			InputStream is = FfmpegDecoder.class.getResourceAsStream(resourceName);
			Files.copy(is, Paths.get(dest.getAbsolutePath()), StandardCopyOption.REPLACE_EXISTING);
			is.close();
		}
		catch (Throwable t) {
			t.printStackTrace(System.err);
		}
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
	            }
	        } catch (IOException e) {
//	            e.printStackTrace();
	        }
	    }
	}
}
