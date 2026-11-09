package com.cubaix.TDenlive.medias;

import java.awt.Graphics2D;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.io.BufferedWriter;
import java.io.File;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.Vector;

import org.eclipse.swt.graphics.GC;
import org.eclipse.swt.graphics.Rectangle;

import com.cubaix.TDenlive.TDenlive;
import com.cubaix.TDenlive.ffmpeg.AudioReader;
import com.cubaix.TDenlive.ffmpeg.VideoReader;
import com.cubaix.TDenlive.ffmpeg.VideoThumbnailer;
import com.cubaix.TDenlive.utils.ImageUtils;
import com.cubaix.TDenlive.utils.StringUtils;
import com.cubaix.TDenlive.xml.XmlObject;

public class StereoMovie extends StereoImage {
	VideoReader leftReader = null;
	VideoReader rightReader = null;
	String thumbKey = null;//Orientation of the current thumbnail
	VideoThumbnailer thumbnailer = null;
	HashMap<Integer,BufferedImage> stripCache = new HashMap<Integer,BufferedImage>();
	String stripKey = null;
	//No sound track used (e.g. a depth map video: the sound is the one of the clip)
	public boolean noSound = false;
	
	public StereoMovie(TDenlive aTDe) {
		super(aTDe);
	}

	@Override
	public int openProject(Vector<XmlObject> aOs, int o) throws Exception {
		return super.openProject(aOs, o,"StereoMovie");
	}

	@Override
	public void saveProject(StringBuffer aSB) throws Exception {
		super.saveProject(aSB,"StereoMovie");
	}

	@Override
	public AudioReader getAudioReader() {
		if(noSound) {
			return null;
		}
		//The sound comes from the left (or single) file, never from the right one of two files
		return AudioReader.get(pathL, tde.getWorkCacheDir());
	}

	@Override
	public AudioReader getAudioReader(boolean aRight) {
		if(aRight && !noSound && origStereo == 0 && pathR != null && !pathR.equals(pathL)) {
			return AudioReader.get(pathR, tde.getWorkCacheDir());
		}
		return getAudioReader();
	}

	@Override
	BufferedImage loadImage(String aPath,int aProcessingMode,long aTime,boolean aIsLeft) throws Exception {
		BufferedImage aBI = null;
		if(aIsLeft) {
			if(leftReader == null) {
				leftReader = new VideoReader(tde,aPath,null);
				durationMS = leftReader.durationMS;
				//Start decoding the sound track in background
				getAudioReader();
			}
			aBI = leftReader.getImage(aProcessingMode,aTime);
		}
		else if(!aIsLeft) {
			if(rightReader == null) {
				rightReader = new VideoReader(tde,aPath,leftReader);
			}
			aBI = rightReader.getImage(aProcessingMode,aTime);
		}
		if(origWidth <= 0) {
			origWidth = aBI.getWidth();
			origHeight = aBI.getHeight();
		}
		return aBI;
	}
	
	@Override
	void reloadOrig(int aProcessingMode,long aTime) throws Exception {
		synchronized (this) {
			switch(origStereo) {
			case 0:
				loadImageLR(pathL,pathR,aProcessingMode,aTime);
				break;
			case 1:
				loadImageX(pathL,aProcessingMode,aTime);
				break;
			case 2:
				loadImageP(pathL,aProcessingMode,aTime);
				break;
			}
		}
	}
	
	@Override
	public void reBuild(int aProcessingMode,long aTime) {
		synchronized (this) {
			//Always rebuild from file
			try {
				loadedLeft = loadedRight = workLeft = workRight = null;
				reloadOrig(aProcessingMode,aTime);
				rotate();
			}
			catch(Throwable t) {
				t.printStackTrace(System.err);
			}
			getWorkLeft(aProcessingMode,aTime);
			getWorkRight(aProcessingMode,aTime);
			lastProcessedRes = tde.config.processingResValues[aProcessingMode];
			lastProcessedHdr = tde.config.processingHdr[aProcessingMode];
		}
	}
	
	@Override
	public BufferedImage getWorkLeft(int aProcessingMode,long aTime) {
		synchronized (this) {
			return super.getWorkLeft(aProcessingMode,aTime);
		}
	}

	@Override
	public BufferedImage getWorkRight(int aProcessingMode,long aTime) {
		synchronized (this) {
			return super.getWorkRight(aProcessingMode,aTime);
		}
	}


	@Override
	public void loadImageLR(String aPathL, String aPathR,int aProcessingMode, long aTime) throws Exception {
		synchronized (this) {
			super.loadImageLR(aPathL, aPathR,aProcessingMode, aTime);
		}
	}

	@Override
	public void loadImageX(String aPath,int aProcessingMode, long aTime) throws Exception {
		synchronized (this) {
			super.loadImageX(aPath,aProcessingMode,aTime);
		}
	}

	@Override
	public void loadImageP(String aPath,int aProcessingMode, long aTime) throws Exception {
		synchronized (this) {
			super.loadImageP(aPath,aProcessingMode, aTime);
		}
	}

	@Override
	public void loadImageMPO(String aPath) throws Exception {
		synchronized (this) {
			super.loadImageMPO(aPath);
		}
	}

	@Override
	public void rotate() {
		synchronized (this) {
			super.rotate();
		}
	}

	@Override
	public void setWorkLeft(BufferedImage aBI) {
		synchronized (this) {
			super.setWorkLeft(aBI);
		}
	}

	@Override
	public void setWorkRight(BufferedImage aBI) {
		synchronized (this) {
			super.setWorkRight(aBI);
		}
	}

	@Override
	public BufferedImage getFinalLeft(int aProcessingMode, long aTime) {
		synchronized (this) {
			return super.getFinalLeft(aProcessingMode, aTime);
		}
	}

	@Override
	public BufferedImage getFinalRight(int aProcessingMode, long aTime) {
		synchronized (this) {
			return super.getFinalRight(aProcessingMode, aTime);
		}
	}

	@Override
	public void buildThumb() {
		synchronized (this) {
			//The frame is reloaded at each time change: keep the first thumbnail,
			//unless the orientation changed (setRotateL/R clears it)
			String aKey = rotateL+"|"+anamorphW;
			if(hasThumb() && aKey.equals(thumbKey)) {
				return;
			}
			super.buildThumb();
			thumbKey = aKey;
		}
	}
	
	@Override
	public BufferedImage getThumbAwtAt(long aTimeMS) {
		VideoThumbnailer aVT = getThumbnailer();
		int aIdx = aVT == null ? -1 : aVT.getIndex(aTimeMS);
		if(aIdx < 0) {
			//Not yet available
			return getThumbAwt();
		}
		String aKey = rotateL+"|"+anamorphW;
		synchronized (stripCache) {
			if(!aKey.equals(stripKey)) {
				stripCache.clear();
				stripKey = aKey;
			}
			BufferedImage aBI = stripCache.get(aIdx);
			if(aBI == null) {
				aBI = ImageUtils.toRes(orient(aVT.get(aIdx)), tde.config.thumbH, false, true, true);
				stripCache.put(aIdx, aBI);
			}
			return aBI;
		}
	}
	
	VideoThumbnailer getThumbnailer() {
		if(thumbnailer == null && pathL != null && durationMS > 0) {
			synchronized (stripCache) {
				if(thumbnailer == null) {
					//Left view only (see loadImageX / loadImageP)
					String aCrop = origStereo == 1 ? "crop=iw/2:ih:iw/2:0"
							: (origStereo == 2 ? "crop=iw/2:ih:0:0" : null);
					thumbnailer = new VideoThumbnailer(pathL, tde.getWorkCacheDir(), aCrop, tde.config.thumbH, durationMS);
				}
			}
		}
		return thumbnailer;
	}
	
	/**
	 * Same rotation and anamorphosis as the left view (see StereoImage.rotate())
	 */
	BufferedImage orient(BufferedImage aBI) {
		if(rotateL.equals("0") && anamorphW.equals("1")) {
			return aBI;
		}
		int aWidth = aBI.getWidth();
		int aHeight = aBI.getHeight();
		double aAnamorph = Double.parseDouble(anamorphW);
		BufferedImage aOut;
		AffineTransform aAT;
		if(rotateL.equals("180") || rotateL.equals("0")) {
			aOut = ImageUtils.createImage((int)(aAnamorph*aWidth),aHeight);
			aAT = AffineTransform.getTranslateInstance(aWidth/2,aHeight/2);
		}
		else {
			aOut = ImageUtils.createImage(aHeight,(int)(aAnamorph*aWidth));
			aAT = AffineTransform.getTranslateInstance(aHeight/2,aWidth/2);
		}
		aAT.concatenate(AffineTransform.getRotateInstance(Math.PI*Double.parseDouble(rotateL)/180.0));
		aAT.concatenate(AffineTransform.getTranslateInstance(-aWidth/2,-aHeight/2));
		aAT.concatenate(AffineTransform.getScaleInstance(aAnamorph, 1.0));
		Graphics2D aG = (Graphics2D)aOut.getGraphics();
		aG.drawImage(aBI, aAT, null);
		aG.dispose();
		return aOut;
	}

	@Override
	public Rectangle drawSwt(GC aGC, int aX, int aY, Rectangle aClippingR) {
		synchronized (this) {
			return super.drawSwt(aGC, aX, aY, aClippingR);
		}
	}

	/**
	 * Copy the finished work copies of the views from a previous work folder of the project to the current one
	 */
	public void copyWorkCopies(String aFromDir,String aToDir) {
		for(String aPath : new String[] {pathL, pathR}) {
			if(aPath == null || (aPath == pathR && pathR.equals(pathL))) {
				continue;
			}
			try {
				File aFrom = new File(StringUtils.path2workingcopy(aPath, aFromDir));
				File aTo = new File(StringUtils.path2workingcopy(aPath, aToDir));
				//Not a legacy one next to the video (still found from the new folder), not one being made
				if(!aFrom.isFile() || aTo.exists() || !aFrom.getAbsoluteFile().getParentFile().equals(new File(aFromDir).getAbsoluteFile())) {
					continue;
				}
				aTo.getAbsoluteFile().getParentFile().mkdirs();
				Files.copy(aFrom.toPath(), aTo.toPath());
			}
			catch(Throwable t) {
				t.printStackTrace(System.err);
			}
		}
	}
}
