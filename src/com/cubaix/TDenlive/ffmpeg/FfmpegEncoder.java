package com.cubaix.TDenlive.ffmpeg;

import java.awt.image.BufferedImage;
import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;

import javax.imageio.ImageIO;

import com.cubaix.TDenlive.TDenlive;

public class FfmpegEncoder extends Ffmpeg {
	TDenlive tde = null;
	Process p = null;
	String ffmpegVideoOpts = "";
	String pathOut = null;
	String audioPathIn = null;//Mixed sound track (WAV), null if none
	public int width = 1280;
	public int height = 720;
	StreamReader ffmpegOut = null;
	StreamReader ffmpegErr = null;
	OutputStream os = null;
	public boolean processing = false;
	
	public FfmpegEncoder(TDenlive aTDe
			,int aWidth,int aHeight
			,double aFps,String aFfmpegVideoOpts
			,String aPathOut,String aAudioPathIn) {
		tde = aTDe;
		pathOut = aPathOut;
		audioPathIn = aAudioPathIn;
		width = aWidth;
		height = aHeight;
		ffmpegVideoOpts = aFfmpegVideoOpts;
		fps = aFps;
		start();
	}
	
	public void start() {
		processing = true;
		try {
			String ffmpegPath = getFfmpeg();
			ArrayList<String> aCmdA = new ArrayList<String>();
			aCmdA.add(ffmpegPath);
			aCmdA.add("-pix_fmt"); aCmdA.add("bgr24");
			aCmdA.add("-r"); aCmdA.add(""+fps);
//			aCmdA.add("-c:a"); aCmdA.add("copy");
			aCmdA.add("-c:v"); aCmdA.add("bmp");
			aCmdA.add("-i"); aCmdA.add("pipe:0");
			boolean aWithAudio = audioPathIn != null && canHaveAudio(pathOut);
			if(aWithAudio) {
				aCmdA.add("-i"); aCmdA.add(audioPathIn);
				aCmdA.add("-map"); aCmdA.add("0:v:0");
				aCmdA.add("-map"); aCmdA.add("1:a:0");
			}
			aCmdA.add("-s"); aCmdA.add((width*2)+"x"+height);
			aCmdA.add("-r"); aCmdA.add(""+fps);
			aCmdA.add("-y"); //Overwrite the output file (if it exists)
//			aCmdA.add("-threads"); aCmdA.add("auto");
			if(pathOut.endsWith(".mp4")) {
				aCmdA.add("-c:v"); aCmdA.add("libx264");
				aCmdA.add("-x264opts"); aCmdA.add("frame-packing=3");
				aCmdA.add("-pix_fmt"); aCmdA.add("yuva420p");
				if(aWithAudio) {
					aCmdA.add("-c:a"); aCmdA.add("aac");
					aCmdA.add("-b:a"); aCmdA.add("192k");
				}
			}
			if(ffmpegVideoOpts != null && !ffmpegVideoOpts.trim().isEmpty()) {
				ffmpegVideoOpts = ffmpegVideoOpts.replaceAll("[ \n\r\t]+", " ").trim();
				for(String aC : ffmpegVideoOpts.split(" ")) {
					aCmdA.add(aC);
				}
			}
			aCmdA.add(pathOut);
			String[] aCmd = new String[aCmdA.size()];
			StringBuffer aCmdSB = new StringBuffer();
			for(int c = 0;c < aCmdA.size();c++) {
				aCmd[c] = aCmdA.get(c);
				aCmdSB.append(aCmd[c]+" ");
			}
			System.out.println("cmd: "+aCmdSB.toString());
			p = Runtime.getRuntime().exec(aCmd);
			ffmpegOut = new StreamReader(p.getInputStream());
			ffmpegErr = new StreamReader(p.getErrorStream());
			new Thread(ffmpegOut).start();
			new Thread(ffmpegErr).start();
			os = p.getOutputStream();
		}
		catch(Throwable t) {
			t.printStackTrace(System.err);
		}
		Thread aTh = new Thread(new Runnable() {
			@Override
			public void run() {
				try {
					p.waitFor();
					processing = false;
				}
				catch(Throwable t) {
					t.printStackTrace(System.err);
				}
			}
		});
		aTh.start();
	}
	
	//Image sequences (3De_%04d_P.png...) can't hold a sound track
	public static boolean canHaveAudio(String aPathOut) {
		String aP = aPathOut.toLowerCase();
		return !aP.contains("%") && !aP.matches(".*[.](png|jpg|jpeg|bmp|tif|tiff|gif|webp)");
	}
	
	public void addImg(BufferedImage aBI) {
		try {
			ImageIO.write(aBI, "bmp", os);
			os.flush();
		}
		catch(Throwable t) {
			t.printStackTrace(System.err);
		}
	}
	
	public void stop() {
		try {
			os.flush();
			os.close();
			p.waitFor();
			System.out.println("DONE");
		}
		catch(Throwable t) {
			t.printStackTrace(System.err);
		}
	}
}
