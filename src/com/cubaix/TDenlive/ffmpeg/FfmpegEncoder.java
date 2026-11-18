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
	/**
	 * Output formats, all made from the parallel stream in the same pass (ffmpeg filter graph): suffix of the
	 * file name (before its extension), ffmpeg filter (null: the stream as is), layout for the stereo player.
	 */
	public static final String[] FORMAT_SUFFIXES = {"_P", "_X", "_Left", "_Right", "_A", "_Dubois"};
	public static final String[] FORMAT_LABELS = {"Renderer.FormatP", "Renderer.FormatX", "Renderer.FormatLeft"
			, "Renderer.FormatRight", "Renderer.FormatA", "Renderer.FormatDubois"};
	static final String[] FORMAT_FILTERS = {null, "stereo3d=sbsl:sbsr", "stereo3d=sbsl:ml", "stereo3d=sbsl:mr"
			, "format=rgb24,stereo3d=sbsl:arcc", "format=rgb24,stereo3d=sbsl:arcd"};
	public static final String[] FORMAT_LAYOUTS = {"sbs", "crossed", "mono", "mono", "anaglyph", "anaglyph"};
	public static final int FORMAT_P = 0;
	public static final int FORMAT_X = 1;
	public static boolean isAnaglyph(int aFormat) {
		return FORMAT_LAYOUTS[aFormat].equals("anaglyph");
	}
	/** Formats by preference for the stereo player page: parallel, crossed (recomposed in every mode), then the
	 * anaglyphs (3D, but shown as they are), then the single views */
	public static final int[] VIEWER_ORDER = {0, 1, 5, 4, 2, 3};

	TDenlive tde = null;
	Process p = null;
	String ffmpegVideoOpts = "";
	String pathOut = null;
	String[] pathsOut = null;//One per format
	int[] formats = null;
	//Colors at full resolution (H.264 High 4:4:4): for the anaglyphs, whose eyes are in the colors
	boolean full444 = false;
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
		this(aTDe, aWidth, aHeight, aFps, aFfmpegVideoOpts, new String[] {aPathOut}, new int[] {FORMAT_P}, aAudioPathIn);
	}

	/** Several outputs from the same parallel images: aPathsOut[i] in the format aFormats[i] (FORMAT_SUFFIXES) */
	public FfmpegEncoder(TDenlive aTDe
			,int aWidth,int aHeight
			,double aFps,String aFfmpegVideoOpts
			,String[] aPathsOut,int[] aFormats,String aAudioPathIn) {
		this(aTDe, aWidth, aHeight, aFps, aFfmpegVideoOpts, aPathsOut, aFormats, aAudioPathIn, false);
	}

	/** aFull444: H.264 in 4:4:4 (colors not subsampled) instead of 4:2:0 */
	public FfmpegEncoder(TDenlive aTDe
			,int aWidth,int aHeight
			,double aFps,String aFfmpegVideoOpts
			,String[] aPathsOut,int[] aFormats,String aAudioPathIn,boolean aFull444) {
		tde = aTDe;
		full444 = aFull444;
		pathsOut = aPathsOut;
		formats = aFormats;
		pathOut = aPathsOut[0];
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
			if(audioPathIn != null) {
				aCmdA.add("-i"); aCmdA.add(audioPathIn);
			}
			//Parallel stream split into the formats asked
			int aN = pathsOut.length;
			StringBuffer aGraph = new StringBuffer("[0:v]");
			if(aN > 1) {
				aGraph.append("split=").append(aN);
				for(int o = 0;o < aN;o++) {
					aGraph.append("[s").append(o).append("]");
				}
			}
			else {
				aGraph.append("null[s0]");
			}
			for(int o = 0;o < aN;o++) {
				String aF = FORMAT_FILTERS[formats[o]];
				aGraph.append(";[s").append(o).append("]")
					//A single view (or an anaglyph) is half the width: even sizes for the 4:2:0 encoding
					.append(aF == null ? "null" : aF+",crop=trunc(iw/2)*2:trunc(ih/2)*2")
					.append("[o").append(o).append("]");
			}
			aCmdA.add("-filter_complex"); aCmdA.add(aGraph.toString());
			String aOpts = ffmpegVideoOpts == null ? "" : ffmpegVideoOpts.replaceAll("[ \n\r\t]+", " ").trim();
			for(int o = 0;o < aN;o++) {
				//Output options are given again for each output
				String aPath = pathsOut[o];
				boolean aWithAudio = audioPathIn != null && canHaveAudio(aPath);
				aCmdA.add("-map"); aCmdA.add("[o"+o+"]");
				if(aWithAudio) {
					aCmdA.add("-map"); aCmdA.add("1:a:0");
				}
				aCmdA.add("-r"); aCmdA.add(""+fps);
				aCmdA.add("-y"); //Overwrite the output file (if it exists)
				if(aPath.toLowerCase().endsWith(".mp4")) {
					aCmdA.add("-c:v"); aCmdA.add("libx264");
					if(formats[o] == FORMAT_P) {
						//Side by side, left first (no flag for right first: none for the crossed one)
						aCmdA.add("-x264opts"); aCmdA.add("frame-packing=3");
					}
					aCmdA.add("-pix_fmt"); aCmdA.add(full444 ? "yuv444p" : "yuv420p");
					if(aWithAudio) {
						aCmdA.add("-c:a"); aCmdA.add("aac");
						aCmdA.add("-b:a"); aCmdA.add("192k");
					}
				}
				if(!aOpts.isEmpty()) {
					for(String aC : aOpts.split(" ")) {
						aCmdA.add(aC);
					}
				}
				aCmdA.add(aPath);
			}
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

	/** An image already written as BMP (the same bytes for several encoders) */
	public void addBmp(byte[] aBmp) {
		try {
			os.write(aBmp);
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
