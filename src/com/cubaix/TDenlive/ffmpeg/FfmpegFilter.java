package com.cubaix.TDenlive.ffmpeg;

import java.awt.image.BufferedImage;
import java.io.BufferedInputStream;
import java.io.File;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;

import javax.imageio.ImageIO;

import com.cubaix.TDenlive.ffmpeg.Ffmpeg.StreamReader;

public class FfmpegFilter extends Ffmpeg {
	static final public String FILESIZE = "";
	static final public int FILEFPS = -1;
	
	static int countDecoders = 0;
	int decoderId = countDecoders++;
			
	public String filterOpt = null;
	public String filterVal = null;

	Process p = null;
	public int width = 1280;
	public int height = 720;
	StreamReader ffmpegErr = null;
	OutputStream os = null;
	InputStream is = null;
	public boolean processing = false;
	
	public FfmpegFilter(String aFilterOpt,int aWidth,int aHeight,String aFilterVal) {
		width = aWidth;
		height = aHeight;
		filterOpt = aFilterOpt;
		filterVal = aFilterVal;
		start();
	}
	
	public void start() {
		processing = true;
		try {
			String ffmpegPath = getFfmpeg();

			ArrayList<String> aCmdA = new ArrayList<String>();
			aCmdA.add(ffmpegPath);
			aCmdA.add("-pix_fmt"); aCmdA.add("bgr24");
//			aCmdA.add("-f"); aCmdA.add("rawvideo");
//			aCmdA.add("-vcodec"); aCmdA.add("rawvideo");
//			aCmdA.add("-r"); aCmdA.add("30");//Mandatory to avoid blocking read
			aCmdA.add("-c:v"); aCmdA.add("bmp");
//			aCmdA.add("-an"); //No audio
			aCmdA.add("-s"); aCmdA.add(width+"x"+height);
			aCmdA.add("-i"); aCmdA.add("pipe:0");
			aCmdA.add(filterOpt); aCmdA.add(filterVal);
//			aCmdA.add("-an"); //No audio
//			aCmdA.add("-r"); aCmdA.add("30");//Mandatory to avoid blocking read
			aCmdA.add("-vcodec"); aCmdA.add("bmp");
			aCmdA.add("-pix_fmt"); aCmdA.add("bgr24");//rgb24"
			aCmdA.add("-f"); aCmdA.add("image2pipe");
			aCmdA.add("-s"); aCmdA.add(width+"x"+height);
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
			os = p.getOutputStream();
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
	
	public BufferedImage filterImg(BufferedImage aBI) {
		try {
			ImageIO.write(aBI, "bmp", os);
			os.flush();
			os.close();//Must close to enforce processing (no more image to add in the buffer) 
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
			os.flush();
			os.close();
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
