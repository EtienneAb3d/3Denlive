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
	//Position given to -ss (ms, may be fractional), and time of the first frame given (ms): see setGrid()
	public double seekMS = 0;
	public double firstMS = 0;
	public int nbFrames = -1;
	String resizeOpt = null;
	String fpsOpt = null;

	Process p = null;
	StreamReader ffmpegErr = null;
	InputStream is = null;
	public boolean processing = false;
	//HDR video given as float images (HDR rendering end to end, see VideoReader.wantsHdr()): PFM of linear light,
	//instead of 8-bit BMP tone mapped to SDR
	public final boolean hdrOut;
	
	public FfmpegDecoder(String aPathIn,long aTimeOffset,int aNbFrames,String aResize,double aFps) {
		this(aPathIn, aTimeOffset, aNbFrames, aResize, aFps, -1);
	}

	/**
	 * aFileFps > 0: the frames of the file are on its grid k / aFileFps, so the position aTimeOffset is moved to
	 * the frame nearest to it, k: -ss half a frame before it (ffmpeg gives the first frame at or after the position)
	 * and firstMS = its exact time. Without it, the first frame given is the one at or after aTimeOffset, while the
	 * reader took it for aTimeOffset: up to a frame late, a different lag for each video (a mask video then was not
	 * on the frames of its original)
	 */
	public FfmpegDecoder(String aPathIn,long aTimeOffset,int aNbFrames,String aResize,double aFps,double aFileFps) {
		this(aPathIn, aTimeOffset, aNbFrames, aResize, aFps, aFileFps, false);
	}

	/** aHdrOut: an HDR video read as float images with its highlights (getImg()), see hdrOut */
	public FfmpegDecoder(String aPathIn,long aTimeOffset,int aNbFrames,String aResize,double aFps,double aFileFps,boolean aHdrOut) {
		hdrOut = aHdrOut;
		pathIn = aPathIn;
		timeOffset = aTimeOffset;
		seekMS = firstMS = aTimeOffset;
		if(aFileFps > 0 && aTimeOffset > 0) {
			long aK = Math.round(aTimeOffset*aFileFps/1000.0);
			firstMS = aK*1000.0/aFileFps;
			seekMS = Math.max(0, (aK-0.5)*1000.0/aFileFps);
		}
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
			aCmdA.add("-hide_banner");
			aCmdA.add("-nostats");//No progress line every 0.5 s while waiting for the reader
			aCmdA.add("-ss"); aCmdA.add(String.format(java.util.Locale.ROOT, "%.6f", seekMS/1000.0));
			aCmdA.add("-i"); aCmdA.add(pathIn);
			//HDR video: kept HDR in linear light (float images), or tone mapped to SDR (8-bit images)
			String aSdr = hdrOut ? hdrLinearFilter() : sdrFilter(pathIn);
			if(aSdr != null) {
				aCmdA.add("-vf"); aCmdA.add(aSdr);
			}
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
			if(hdrOut) {
				aCmdA.add("-vcodec"); aCmdA.add("pfm");
				aCmdA.add("-pix_fmt"); aCmdA.add("gbrpf32le");
			}
			else {
				aCmdA.add("-vcodec"); aCmdA.add("bmp");
				aCmdA.add("-pix_fmt"); aCmdA.add("bgr24");//rgb24"
			}
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
			return hdrOut ? readPfm(is) : readBmp(is);
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
