package com.cubaix.TDenlive.ffmpeg;

import java.awt.image.BufferedImage;
import java.io.File;
import java.util.Comparator;
import java.util.Vector;

import com.cubaix.TDenlive.TDConfig;
import com.cubaix.TDenlive.TDenlive;
import com.cubaix.TDenlive.utils.ImageUtils;
import com.cubaix.TDenlive.utils.StringUtils;

public class VideoReader {
	TDenlive tde = null;
	public String path = null;
	Vector<VideoImage> buffer = new Vector<VideoImage>();
	
	public long durationMS = -1;
	public double origFps = 30;
	public int origWidth = 0;
	public int origHeight = 0;
	public double currentFps = 30;
	public int currentWidth = 0;
	public int currentHeight = 0;
	public String currentResize = ""; 
	
	Thread readerTh = null;
	FfmpegDecoder decoder = null;
	int countFrames = 0;
	
	VideoReader sync = null;
	
	public VideoReader(TDenlive aTDe,String aPath,VideoReader aSync) {
		tde = aTDe;
		path = aPath;
		sync = aSync;
		
		//Get a first image at original size
		read(TDConfig.PROCESSING_MODE_RENDER,0, 1,FfmpegDecoder.FILESIZE,FfmpegDecoder.FILEFPS);
		if(buffer.size() > 0) {
			BufferedImage aBI = buffer.elementAt(0).bi;
			origWidth = aBI.getWidth();
			origHeight = aBI.getHeight();
			
			String aPathWork = StringUtils.path2workingcopy(path);
			if(!new File(aPathWork).exists() && !new File(StringUtils.path2processing(aPathWork)).exists()) {
				int aWorkWidth = origWidth;
				int aWorkHeight = origHeight;
				if(origHeight > TDConfig.RESVALUES[TDConfig.RES_DVD]) {
					aWorkHeight = TDConfig.RESVALUES[TDConfig.RES_DVD];
					aWorkWidth = (int)(aWorkHeight*origWidth/(double)origHeight);
				}
				double aWorkFps = origFps;
				if(aWorkFps > 15) {
					aWorkFps = 15;
				}
				new FfmpegTranscoder().createWorkCopy(path, aPathWork, aWorkWidth, aWorkHeight, aWorkFps);
			}
		}
		//Don't keep the RENDER quality
		stopFeed();
	}
	
	public void read(int aProcessingMode,long aTimeOffset,int aNbFrames,String aResize,double aFps) {
		long aTime = System.currentTimeMillis();
		String aPath = StringUtils.path2workingcopy(path);
		if(aProcessingMode == TDConfig.PROCESSING_MODE_RENDER || !new File(aPath).exists()) {
			aPath = path;
		}
		if(decoder == null) {
			decoder = new FfmpegDecoder(aPath,aTimeOffset,-1,aResize,aFps);
			countFrames = 0;
			currentResize = aResize;
		}
		for(int f = 0;f < aNbFrames;f++) {
			BufferedImage aBI = decoder.getImg();
			currentFps = decoder.fps;
			currentWidth = aBI.getWidth();
			currentHeight = aBI.getHeight();
			synchronized (buffer) {
				buffer.add(new VideoImage(aBI,decoder.timeOffset+(int)(1000*countFrames/(double)currentFps)));
				countFrames++;
				buffer.sort(new Comparator<VideoImage>() {
					@Override
					public int compare(VideoImage arg0, VideoImage arg1) {
						return (int)(arg0.timePos - arg1.timePos);
					}
				});
			}
		}
		long aDur = System.currentTimeMillis()-aTime;
		double aReadFps = (int)(10*1000*aNbFrames/(double)aDur)/10;
		System.out.println("READ: "+aNbFrames+" f "+aDur+" ms "
				+ aReadFps + " fps "
				+aPath);
		if(durationMS < 0) {
			durationMS = decoder.duration;
			origFps = decoder.fps;
			System.out.println("DURATION: "+durationMS+"\n"
					+ "FPS: " +origFps);
		}
		
		startFeed();
	}
	
	void stopFeed() {
		if(decoder != null) {
			decoder.stop();
			decoder = null;
		}
//		if(readerTh != null) {
//			readerTh.stop();
//			readerTh = null;
//		}
	}
	void startFeed() {
		if(readerTh != null && readerTh.isAlive()) {
			return;
		}
		readerTh = new Thread(new Runnable() {
			@Override
			public void run() {
				while(true) {
					try {
						Thread.sleep(10);
					}
					catch(Throwable t) {
//						t.printStackTrace(System.err);
					}
					while(true) {
						synchronized (buffer) {
							if(decoder == null) {
								break;
							}
							if(buffer.size() >= 10) {
								break;
							}
							BufferedImage aBI = decoder.getImg();
							if(aBI == null) {
								//??
								break;
							}
							currentFps = decoder.fps;
							buffer.add(new VideoImage(aBI,decoder.timeOffset+(int)(1000*countFrames/(double)currentFps)));
							countFrames++;
							buffer.sort(new Comparator<VideoImage>() {
								@Override
								public int compare(VideoImage arg0, VideoImage arg1) {
									return (int)(arg0.timePos - arg1.timePos);
								}
							});
						}
					}
				}
			}
		});
		readerTh.start();
	}
	
	String evalResize(int aProcessingMode) {
		if(aProcessingMode == TDConfig.PROCESSING_MODE_RENDER) {
			//Allways in original size when rendering, for best quality
			return FfmpegDecoder.FILESIZE;
		}
		int aRes = tde.config.processingResValues[aProcessingMode];
		if(origHeight < TDConfig.RESVALUES[TDConfig.RES_DVD]) {
			//No need to upscale
			return FfmpegDecoder.FILESIZE;
		}
		if(origHeight > TDConfig.RESVALUES[TDConfig.RES_DVD]) {
			//Can downscale
			int aWidth = (int)(TDConfig.RESVALUES[TDConfig.RES_DVD]*origWidth/(double)origHeight);
			return aWidth+"x"+TDConfig.RESVALUES[TDConfig.RES_DVD];
		}
		return FfmpegDecoder.FILESIZE;
	}
	
	public BufferedImage getImage(int aProcessingMode,long aTimeOffset) {
		BufferedImage aBI = null;
		synchronized (buffer) {
			String aResize = evalResize(aProcessingMode);
			if((currentResize != aResize && !currentResize.equals(aResize)) 
					|| (aProcessingMode == TDConfig.PROCESSING_MODE_RENDER && origHeight != currentHeight)
					|| (sync != null && decoder != null && decoder.timeOffset != sync.decoder.timeOffset)) {
				stopFeed();
				buffer.clear();
				read(aProcessingMode, (sync != null && decoder != null ? sync.decoder.timeOffset : aTimeOffset)
						, 1
//						, FfmpegDecoder.FILESIZE
						,aResize
						, FfmpegDecoder.FILEFPS);
			}
			for(int b = 0; b < buffer.size();b++) {
				VideoImage aVI = buffer.elementAt(b);
				if(aVI.bi != null//Why possibly null ?
						&& aTimeOffset >= aVI.timePos-(500/(double)currentFps)
						&& aTimeOffset < aVI.timePos+(500/(double)currentFps)) {
					//Found, keep it if this range comes again
					aBI = aVI.bi;
				}
				if(aVI.bi == null//Why possibly null ?
						|| aTimeOffset > aVI.timePos + (500/(double)currentFps)
						|| aTimeOffset < aVI.timePos - (30*1000/(double)currentFps)) {
					//Not in the good range
					buffer.remove(b);
					b--;
					continue;
				}
			}
			if(aBI == null) {
				stopFeed();
				buffer.clear();
				read(aProcessingMode,aTimeOffset, 1
//						,FfmpegDecoder.FILESIZE
						,aResize
						,FfmpegDecoder.FILEFPS);
				if(buffer.size() > 0) {
					aBI = buffer.elementAt(0).bi;
				}
			}
		}
		if(aBI == null) {
			aBI = ImageUtils.createImage(origWidth, origHeight);
		}
		return aBI;
	}
	
    public static void main(String[] args) {
    	try {
    		long aMem = Runtime.getRuntime().totalMemory();
    		System.out.println("MEM: "+aMem);
    		VideoReader aVR = new VideoReader(null,"/home/etienne/3Denlive/Rendered/3De_TutoKeyframes_P.mp4",null);
    		aVR.read(TDConfig.PROCESSING_MODE_WORK,0, 60,FfmpegDecoder.FILESIZE,FfmpegDecoder.FILEFPS);
    		long aMemEnd = Runtime.getRuntime().totalMemory();
    		System.out.println("MEM: "+aMemEnd+"\n"
    				+ "DIFF: "+(aMemEnd-aMem)+" = "+((aMemEnd-aMem)/1000000)+" M");
    	}
    	catch(Throwable t) {
    		t.printStackTrace(System.err);
    	}
    }
}
