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
	//Image of a video that can't be decoded (missing file)
	static final int MISSING_W = 640;
	static final int MISSING_H = 360;
	public int origHeight = 0;
	public double currentFps = 30;
	public int currentWidth = 0;
	public int currentHeight = 0;
	public String currentResize = ""; 
	//The decoder gives float HDR images (wantsHdr() when it was started)
	boolean currentHdr = false;
	
	Thread readerTh = null;
	FfmpegDecoder decoder = null;
	int countFrames = 0;
	
	VideoReader sync = null;
	
	//Last image given, and its processing mode: shown again past the last frame of the video
	BufferedImage lastImage = null;
	int lastImageMode = -1;
	
	//Texture mode: reads, outside the RENDER mode, its texture copy at the VHS height (StringUtils.path2texturecopy())
	//instead of the work copy (the 3D models textured by the video, Texture3D.MediaSource)
	public final boolean texture;
	//Height of the texture copies
	public static final int TEXTURE_H = TDConfig.RESVALUES[TDConfig.RES_VHS];

	public VideoReader(TDenlive aTDe,String aPath,VideoReader aSync) {
		this(aTDe, aPath, aSync, false);
	}

	public VideoReader(TDenlive aTDe,String aPath,VideoReader aSync,boolean aTexture) {
		tde = aTDe;
		path = aPath;
		sync = aSync;
		texture = aTexture;
		
		//Get a first image at original size
		read(TDConfig.PROCESSING_MODE_RENDER,0, 1,FfmpegDecoder.FILESIZE,FfmpegDecoder.FILEFPS);
		if(buffer.size() > 0) {
			BufferedImage aBI = buffer.elementAt(0).bi;
			origWidth = aBI.getWidth();
			origHeight = aBI.getHeight();
			
			if(new File(workPath()).length() > 0) {
				//Its size checked in the background (one image decoded), not to slow down the loading
				Thread aTh = new Thread(this::checkWorkCopy, "3De-WorkCopyCheck");
				aTh.setDaemon(true);
				aTh.start();
			}
			else {
				checkWorkCopy();
			}
		}
		//Don't keep the RENDER quality
		stopFeed();
		synchronized(READERS) {
			READERS.add(this);
		}
	}

	//Readers of the videos opened (work copies checked again when the work resolution changes)
	static final java.util.Set<VideoReader> READERS = java.util.Collections.newSetFromMap(new java.util.WeakHashMap<VideoReader, Boolean>());
	//Work copies being made in this session (the end checks them again)
	static final java.util.Set<String> MAKING = new java.util.HashSet<String>();

	/** Number of work copies being made */
	public static int workCopiesInProgress() {
		synchronized(MAKING) {
			return MAKING.size();
		}
	}

	String workPath() {
		String aDir = tde == null ? null : tde.getWorkCacheDir();
		return texture ? StringUtils.path2texturecopy(path, aDir) : StringUtils.path2workingcopy(path, aDir);
	}

	int workRes() {
		if(texture) {
			return TEXTURE_H;
		}
		return tde == null ? TDConfig.RESVALUES[TDConfig.RES_DVD] : tde.config.processingResValues[TDConfig.PROCESSING_MODE_WORK];
	}

	/**
	 * Work resolution of the monitor at least that of the original: the work copy keeps the size of the original
	 * (only its frame rate reduced to 15 fps), read as it is (no upscale)
	 */
	boolean useOriginal() {
		return workRes() >= origHeight;
	}

	/**
	 * Size of the work copy: the height of the work resolution of the monitor (processingResValues[WORK]),
	 * never enlarged, even sides (libx264 refuses odd sizes: 1280x704 -> 1047x576)
	 */
	int[] workSize() {
		int aH = Math.min(origHeight, workRes());
		int aW = (int)Math.round(aH*origWidth/(double)origHeight);
		return new int[] {Math.max(2, aW / 2 * 2), Math.max(2, aH / 2 * 2)};
	}

	/**
	 * Makes the work copy again when it is missing or not at the work size (workSize()), else nothing. Always at
	 * 15 fps at most, whatever the work resolution (at the size of the original when it is not above).
	 * Meanwhile the original is read, resized (see read() and evalResize()).
	 */
	void checkWorkCopy() {
		if(origHeight <= 0 || origWidth <= 0) {
			return;
		}
		String aPathWork = workPath();
		int[] aSize = workSize();
		synchronized(MAKING) {
			if(MAKING.contains(aPathWork)) {
				//Checked again at its end
				return;
			}
			File aW = new File(aPathWork);
			if(aW.length() > 0 && videoHeight(aPathWork) == aSize[1]) {
				return;
			}
			MAKING.add(aPathWork);
		}
		//Shown over the monitor while copies are made (MonitorGUI.drawWorkCopies())
		redrawMonitor();
		//The readers of that video stop reading the old copy, deleted
		for(VideoReader aVR : readersOf(path)) {
			aVR.restartFeed();
		}
		new File(aPathWork).delete();
		new File(StringUtils.path2processing(aPathWork)).delete();//Left by a session stopped while making it
		if(tde != null && !texture) {
			new File(StringUtils.oldWorkingCopy(path, tde.getWorkCacheDir())).delete();
		}
		double aWorkFps = Math.min(15, origFps > 0 ? origFps : 15);
		System.out.println((texture ? "Texture copy " : "Work copy ")+aSize[0]+"x"+aSize[1]+": "+path);
		new FfmpegTranscoder().createWorkCopy(path, aPathWork, aSize[0], aSize[1], aWorkFps, () -> {
			synchronized(MAKING) {
				MAKING.remove(aPathWork);
			}
			//The readers take the new copy; checked again if the resolution changed meanwhile
			for(VideoReader aVR : readersOf(path)) {
				aVR.restartFeed();
			}
			if(new File(aPathWork).length() > 0) {
				checkWorkCopy();
			}
			redrawMonitor();
		});
	}

	/** Height of the first image of a video, -1 if none */
	static int videoHeight(String aPath) {
		FfmpegDecoder aD = null;
		try {
			aD = new FfmpegDecoder(aPath, 0, 1, FfmpegDecoder.FILESIZE, FfmpegDecoder.FILEFPS);
			BufferedImage aBI = aD.getImg();
			return aBI == null ? -1 : aBI.getHeight();
		}
		catch(Throwable t) {
			t.printStackTrace(System.err);
			return -1;
		}
		finally {
			if(aD != null) {
				aD.stop();
			}
		}
	}

	static java.util.List<VideoReader> readersOf(String aPath) {
		java.util.ArrayList<VideoReader> aL = new java.util.ArrayList<VideoReader>();
		synchronized(READERS) {
			for(VideoReader aVR : READERS) {
				if(aVR.path != null && aVR.path.equals(aPath)) {
					aL.add(aVR);
				}
			}
		}
		return aL;
	}

	/** Next image decoded again from the file now in place (work copy made or removed) */
	void restartFeed() {
		synchronized(buffer) {
			stopFeed();
			buffer.clear();
		}
	}

	void redrawMonitor() {
		if(tde == null || tde.gui == null || tde.gui.display == null || tde.gui.display.isDisposed()) {
			return;
		}
		tde.gui.display.asyncExec(() -> {
			if(tde.gui.monitorGUI != null) {
				tde.gui.monitorGUI.redraw();
			}
		});
	}

	/**
	 * The work resolution of the monitor changed: the work copies of the videos opened that are not at the
	 * new size are made again (in the background)
	 */
	public static void workResChanged() {
		java.util.ArrayList<VideoReader> aL;
		synchronized(READERS) {
			aL = new java.util.ArrayList<VideoReader>(READERS);
		}
		Thread aTh = new Thread(() -> {
			java.util.HashSet<String> aDone = new java.util.HashSet<String>();
			for(VideoReader aVR : aL) {
				if(aVR.path != null && aDone.add(aVR.texture+"|"+aVR.path)) {
					aVR.checkWorkCopy();
				}
			}
			for(VideoReader aVR : aL) {
				aVR.redrawMonitor();
				break;
			}
		}, "3De-WorkCopies");
		aTh.setDaemon(true);
		aTh.start();
	}
	
	public void read(int aProcessingMode,long aTimeOffset,int aNbFrames,String aResize,double aFps) {
		long aTime = System.currentTimeMillis();
		String aPath = workPath();
		if(aProcessingMode == TDConfig.PROCESSING_MODE_RENDER || new File(aPath).length() == 0) {
			aPath = path;
		}
		if(decoder == null) {
			//Frame grid of the file read: the work copy at 15 fps at most, else the original (known after the first
			//reading, at 0)
			double aFileFps = durationMS < 0 || origFps <= 0 ? -1 : aPath.equals(path) ? origFps : Math.min(15, origFps);
			currentHdr = wantsHdr(aProcessingMode) && aPath.equals(path);
			decoder = new FfmpegDecoder(aPath,aTimeOffset,-1,aResize,aFps,aFileFps,currentHdr);
			countFrames = 0;
			currentResize = aResize;
		}
		for(int f = 0;f < aNbFrames;f++) {
			BufferedImage aBI = decoder.getImg();
			if(aBI == null) {
				//No frame at this time (past the last frame of the video) or decoding error
				break;
			}
			currentFps = decoder.fps;
			currentWidth = aBI.getWidth();
			currentHeight = aBI.getHeight();
			synchronized (buffer) {
				buffer.add(new VideoImage(aBI,Math.round(decoder.firstMS+1000*countFrames/(double)currentFps)));
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
	//Closed: no more reading, its feeding thread ends
	volatile boolean closed = false;

	/**
	 * No longer used (a stream of texture of StereoMovie.textureViews() replaced): the decoder stopped, the
	 * feeding thread ended
	 */
	public void close() {
		closed = true;
		synchronized(buffer) {
			stopFeed();
			buffer.clear();
		}
		synchronized(READERS) {
			READERS.remove(this);
		}
	}

	void startFeed() {
		if(closed || (readerTh != null && readerTh.isAlive())) {
			return;
		}
		readerTh = new Thread(new Runnable() {
			@Override
			public void run() {
				while(!closed) {
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
							buffer.add(new VideoImage(aBI,Math.round(decoder.firstMS+1000*countFrames/(double)currentFps)));
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
	
	/**
	 * HDR end to end: in the RENDER mode of an HDR rendering (Ffmpeg.hdrRendering: HLG / PQ output, HDR processing),
	 * an HDR video is decoded as float images with its highlights instead of being tone mapped to SDR
	 */
	boolean wantsHdr(int aProcessingMode) {
		//Not an anaglyph: its layers kept apart in SDR (Ffmpeg.anaglyphFilter())
		return aProcessingMode == TDConfig.PROCESSING_MODE_RENDER && Ffmpeg.hdrRendering && !texture && !Ffmpeg.isAnaglyph(path)
				&& Ffmpeg.hdrTransfer(path) != null && Ffmpeg.hasZscale();
	}

	String evalResize(int aProcessingMode) {
		if(aProcessingMode == TDConfig.PROCESSING_MODE_RENDER) {
			//Allways in original size when rendering, for best quality
			return FfmpegDecoder.FILESIZE;
		}
		//At the size of the work copy (the original resized while it is made)
		if(useOriginal()) {
			//No need to upscale: the original as it is
			return FfmpegDecoder.FILESIZE;
		}
		int[] aSize = workSize();
		return aSize[0]+"x"+aSize[1];
	}
	
	public BufferedImage getImage(int aProcessingMode,long aTimeOffset) {
		BufferedImage aBI = null;
		synchronized (buffer) {
			String aResize = evalResize(aProcessingMode);
			if((currentResize != aResize && !currentResize.equals(aResize)) 
					|| (decoder != null && currentHdr != wantsHdr(aProcessingMode))
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
		if(aBI != null) {
			lastImage = aBI;
			lastImageMode = aProcessingMode;
		}
		else if(lastImage != null && lastImageMode == aProcessingMode) {
			//Past the last frame (the clip can last a few ms more than the frames): keep the last one
			aBI = lastImage;
		}
		else if(origWidth > 0 && origHeight > 0) {
			aBI = ImageUtils.createImage(origWidth, origHeight);
		}
		else {
			//Nothing decoded (file missing or unreadable): a black image instead of an error, the project opens
			System.err.println("Video not readable (missing?): "+path);
			aBI = ImageUtils.createImage(MISSING_W, MISSING_H);
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
