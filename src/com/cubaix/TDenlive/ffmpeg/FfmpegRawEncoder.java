package com.cubaix.TDenlive.ffmpeg;

import java.awt.image.BufferedImage;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;

import com.cubaix.TDenlive.utils.StringUtils;

/**
 * Encodes gray images (depth maps) given one by one into an H.264 mp4, as raw frames piped to ffmpeg.
 * Written into path2processing(aPathOut), renamed at the end: an interrupted encoding leaves no wrong file.
 */
public class FfmpegRawEncoder extends Ffmpeg {
	String pathOut = null;
	int width;
	int height;
	Process p = null;
	OutputStream os = null;
	Thread errTh = null;

	public FfmpegRawEncoder(String aPathOut,int aWidth,int aHeight,double aFps) throws Exception {
		pathOut = aPathOut;
		width = aWidth;
		height = aHeight;
		new File(aPathOut).getAbsoluteFile().getParentFile().mkdirs();
		ArrayList<String> aCmdA = new ArrayList<String>();
		aCmdA.add(getFfmpeg());
		aCmdA.add("-hide_banner");
		aCmdA.add("-nostats");
		aCmdA.add("-loglevel"); aCmdA.add("error");
		aCmdA.add("-f"); aCmdA.add("rawvideo");
		aCmdA.add("-pix_fmt"); aCmdA.add("gray");
		aCmdA.add("-s"); aCmdA.add(aWidth+"x"+aHeight);
		aCmdA.add("-r"); aCmdA.add(""+aFps);
		aCmdA.add("-i"); aCmdA.add("pipe:0");
		aCmdA.add("-an");
		//yuv420p needs even sizes
		aCmdA.add("-vf"); aCmdA.add("scale=trunc(iw/2)*2:trunc(ih/2)*2");
		aCmdA.add("-c:v"); aCmdA.add("libx264");
		aCmdA.add("-crf"); aCmdA.add("16");
		aCmdA.add("-pix_fmt"); aCmdA.add("yuv420p");
		aCmdA.add("-y");
		aCmdA.add(StringUtils.path2processing(aPathOut));
		System.out.println("cmd: "+String.join(" ", aCmdA));
		p = new ProcessBuilder(aCmdA).redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
		errTh = new Thread(new StreamReader(p.getErrorStream()));
		errTh.start();
		os = new BufferedOutputStream(p.getOutputStream(), 1 << 20);
	}

	public void addImg(BufferedImage aBI) throws Exception {
		int[] aPix = aBI.getRGB(0, 0, width, height, null, 0, width);
		byte[] aBytes = new byte[aPix.length];
		for(int p = 0;p < aPix.length;p++) {
			aBytes[p] = (byte)(aPix[p] & 0xFF);
		}
		os.write(aBytes);
	}

	/**
	 * End of the encoding: with aKeep, the file is renamed to its final path, else removed (canceled)
	 */
	public boolean finish(boolean aKeep) {
		String aProcessing = StringUtils.path2processing(pathOut);
		try {
			os.close();
		}
		catch(Throwable t) {
			t.printStackTrace(System.err);
		}
		try {
			if(!aKeep) {
				p.destroy();
			}
			int aRC = p.waitFor();
			if(aKeep && aRC == 0 && new File(aProcessing).isFile()) {
				Files.move(Paths.get(aProcessing), Paths.get(pathOut), StandardCopyOption.REPLACE_EXISTING);
				return true;
			}
			System.err.println("ffmpeg exit "+aRC+": "+pathOut);
		}
		catch(Throwable t) {
			t.printStackTrace(System.err);
		}
		new File(aProcessing).delete();
		return false;
	}
}
