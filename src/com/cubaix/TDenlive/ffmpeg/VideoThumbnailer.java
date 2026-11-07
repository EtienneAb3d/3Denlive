package com.cubaix.TDenlive.ffmpeg;

import java.awt.image.BufferedImage;
import java.io.BufferedInputStream;
import java.io.File;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Locale;
import java.util.Vector;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import javax.imageio.ImageIO;

import com.cubaix.TDenlive.utils.StringUtils;

/**
 * Small images of a video at regular times, for the clips of the time lines (film strip).
 * Extracted in background by a single ffmpeg run (one video at a time), from the working copy
 * when it exists (smaller and faster to decode).
 * Image k is the frame at k*intervalMS in the video.
 */
public class VideoThumbnailer extends Ffmpeg {
	static final int MAX_THUMBS = 600;
	static final long MIN_INTERVAL_MS = 500;
	static final ExecutorService POOL = Executors.newSingleThreadExecutor(r -> {
		Thread aTh = new Thread(r, "VideoThumbnailer");
		aTh.setDaemon(true);
		return aTh;
	});
	//Called (from a worker thread) when new images are available, to redraw the time lines
	public static volatile Runnable readyListener = null;

	String path = null;
	String workCacheDir = null;//To use the work copy of the video if there is one
	String cropFilter = null;
	int height = 45;
	public long intervalMS = 1000;
	Vector<BufferedImage> thumbs = new Vector<BufferedImage>();
	volatile boolean done = false;

	/**
	 * aCropFilter: ffmpeg crop to keep the left view of a side by side video, null for the whole frame
	 */
	public VideoThumbnailer(String aPath,String aWorkCacheDir,String aCropFilter,int aHeight,long aDurationMS) {
		path = aPath;
		workCacheDir = aWorkCacheDir;
		cropFilter = aCropFilter;
		height = aHeight;
		intervalMS = Math.max(MIN_INTERVAL_MS, aDurationMS/MAX_THUMBS);
		POOL.submit(this::extract);
	}

	void extract() {
		Process p = null;
		try {
			String ffmpegPath = getFfmpeg();
			if(ffmpegPath == null) {
				return;
			}
			String aIn = path;
			String aWork = StringUtils.path2workingcopy(path, workCacheDir);
			if(new File(aWork).isFile()) {
				aIn = aWork;
			}
			StringBuffer aVF = new StringBuffer();
			if(cropFilter != null) {
				aVF.append(cropFilter).append(",");
			}
			aVF.append(String.format(Locale.ROOT, "fps=%.6f", 1000.0/intervalMS));
			aVF.append(",scale=-2:").append(height);
			ArrayList<String> aCmdA = new ArrayList<String>();
			aCmdA.add(ffmpegPath);
			aCmdA.add("-nostdin");
			aCmdA.add("-hide_banner");
			aCmdA.add("-nostats");
			aCmdA.add("-i"); aCmdA.add(aIn);
			aCmdA.add("-an"); aCmdA.add("-sn"); aCmdA.add("-dn");
			aCmdA.add("-vf"); aCmdA.add(aVF.toString());
			aCmdA.add("-vcodec"); aCmdA.add("bmp");
			aCmdA.add("-pix_fmt"); aCmdA.add("bgr24");
			aCmdA.add("-f"); aCmdA.add("image2pipe");
			aCmdA.add("pipe:1");
			System.out.println("cmd: "+String.join(" ", aCmdA));
			p = new ProcessBuilder(aCmdA).redirectError(ProcessBuilder.Redirect.DISCARD).start();
			InputStream aIS = new BufferedInputStream(p.getInputStream());
			BufferedImage aBI;
			while((aBI = readBmp(aIS)) != null) {
				thumbs.add(aBI);
				if(thumbs.size() % 20 == 0) {
					notifyReady();
				}
			}
			p.waitFor();
		}
		catch(Throwable t) {
			t.printStackTrace(System.err);
			if(p != null) {
				p.destroy();
			}
		}
		done = true;
		notifyReady();
	}

	void notifyReady() {
		Runnable aL = readyListener;
		if(aL != null) {
			try {
				aL.run();
			}
			catch(Throwable t) {
				t.printStackTrace(System.err);
			}
		}
	}

	/**
	 * Index of the image for a time in the video, -1 if not (yet) available
	 */
	public int getIndex(long aTimeMS) {
		int aSize = thumbs.size();
		if(aSize <= 0) {
			return -1;
		}
		int aIdx = (int)Math.round(Math.max(0, aTimeMS)/(double)intervalMS);
		if(aIdx >= aSize) {
			//Not yet extracted (or after the end): the last one only when all is done
			return done ? aSize-1 : -1;
		}
		return aIdx;
	}

	public BufferedImage get(int aIdx) {
		return aIdx < 0 || aIdx >= thumbs.size() ? null : thumbs.elementAt(aIdx);
	}
}
