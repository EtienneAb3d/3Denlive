package com.cubaix.TDenlive.ffmpeg;

import java.awt.image.BufferedImage;
import java.io.BufferedInputStream;
import java.io.File;
import java.io.InputStream;
import java.util.ArrayList;

import javax.imageio.ImageIO;

public class FfmpegDecoder extends Ffmpeg {
	static final public String FILESIZE = "";
	static final public int FILEFPS = -1;
	
	static int countDecoders = 0;
	int decoderId = countDecoders++;
			
	public String pathIn = null;
	public long timeOffset = 0;
	public int nbFrames = -1;
	String resizeOpt = null;
	String fpsOpt = null;

	Process p = null;
	StreamReader ffmpegErr = null;
	InputStream is = null;
	public boolean processing = false;
	
	public FfmpegDecoder(String aPathIn,long aTimeOffset,int aNbFrames,String aResize,double aFps) {
		pathIn = aPathIn;
		timeOffset = aTimeOffset;
		nbFrames = aNbFrames;
		resizeOpt = aResize == null || aResize.isEmpty() ? "":" -s "+aResize;
		fpsOpt = aFps <= 0 ? "" : "-r "+aFps;
		start();
	}
	
	public void start() {
		processing = true;
		try {
			String ffmpegPath = getFfmpeg();
			ArrayList<String> aCmdA = new ArrayList<String>();
			aCmdA.add(ffmpegPath);
			aCmdA.add("-ss"); aCmdA.add(timeOffset+"ms");
			aCmdA.add("-i"); aCmdA.add(pathIn);
			if(resizeOpt != null && !resizeOpt.trim().isEmpty()) {
				resizeOpt = resizeOpt.trim().replaceAll(" +", " ");
				aCmdA.add(resizeOpt.split(" ")[0]); aCmdA.add(resizeOpt.split(" ")[1]);//+ " -s "+(2*1280)+"x"+720
			}
			if(fpsOpt != null && !fpsOpt.trim().isEmpty()) {
				fpsOpt = fpsOpt.trim().replaceAll(" +", " ");
				aCmdA.add(fpsOpt.split(" ")[0]); aCmdA.add(fpsOpt.split(" ")[1]);//+ " -r "+10
			}
			if(nbFrames > 0) {
				aCmdA.add("-frames"); aCmdA.add(""+nbFrames);
			}
			aCmdA.add("-vcodec"); aCmdA.add("bmp");
			aCmdA.add("-pix_fmt"); aCmdA.add("bgr24");//rgb24"
			aCmdA.add("-f"); aCmdA.add("image2pipe");
			aCmdA.add("pipe:1");
			String[] aCmd = new String[aCmdA.size()];
			StringBuffer aCmdSB = new StringBuffer();
			for(int c = 0;c < aCmdA.size();c++) {
				aCmd[c] = aCmdA.get(c);
				aCmdSB.append(aCmd[c]+" ");
			}
			System.out.println("cmd: "+aCmdSB.toString());
			p = Runtime.getRuntime().exec(aCmd);
			ffmpegErr = new StreamReader(p.getErrorStream());
			new Thread(ffmpegErr).start();
			is = new BufferedInputStream(p.getInputStream());
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
	
	public BufferedImage getImg() {
		try {
			return (BufferedImage)ImageIO.read(is);
		}
		catch(Throwable t) {
			t.printStackTrace(System.err);
		}
		return null;
	}
	
	public void stop() {
		try {
			processing = false;
			is.close();
			ffmpegErr.inputStream.close();
			p.destroy();
			System.out.println("DONE");
		}
		catch(Throwable t) {
			t.printStackTrace(System.err);
		}
	}
}
