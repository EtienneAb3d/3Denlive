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
	//Readers of the texture copies (VHS height), for the 3D models textured by this video (textureViews()): one
	//pair {left, right} per stream (loop range of a face), the most recently used last, TEX_STREAMS at most
	final java.util.LinkedHashMap<String, VideoReader[]> texReaders = new java.util.LinkedHashMap<String, VideoReader[]>(8, 0.75f, true);
	static final int TEX_STREAMS = 4;
	
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
	 * Both views {left, right} of the video at the media time aTime for the 3D models that have it as texture,
	 * outside the RENDER mode: read from its texture copies at the VHS height in the WorkCache (made in the
	 * background, the original reduced to that size meanwhile), by readers of their own (the clips of the video
	 * keep theirs and the state of the media is not touched), rotation and anamorphosis included; null if missing
	 */
	public BufferedImage[] textureViews(long aTime) {
		return textureViews(aTime, "");
	}

	/**
	 * Same for the stream aStream (loop range of a face): its own readers, so that two faces showing this video
	 * at different times each read it forward, without seeking at each image
	 */
	public BufferedImage[] textureViews(long aTime, String aStream) {
		if(pathL == null) {
			return null;
		}
		VideoReader[] aRs;
		synchronized(texReaders) {
			aRs = texReaders.get(aStream);
			if(aRs == null) {
				aRs = new VideoReader[2];
				texReaders.put(aStream, aRs);
				//The stream used the longest ago closed
				while(texReaders.size() > TEX_STREAMS) {
					java.util.Map.Entry<String, VideoReader[]> aOld = texReaders.entrySet().iterator().next();
					texReaders.remove(aOld.getKey());
					for(VideoReader aVR : aOld.getValue()) {
						if(aVR != null) {
							aVR.close();
						}
					}
				}
			}
		}
		synchronized(aRs) {
			try {
				if(aRs[0] == null) {
					aRs[0] = new VideoReader(tde, pathL, null, true);
				}
				VideoReader texLeftReader = aRs[0];
				int aMode = com.cubaix.TDenlive.TDConfig.PROCESSING_MODE_WORK;
				BufferedImage aOrig = texLeftReader.getImage(aMode, aTime);
				if(aOrig == null) {
					return null;
				}
				BufferedImage aL, aR;
				if(origStereo == 1 || origStereo == 2) {
					//Side by side in one file: crossed (right view on the left) or parallel
					int aW = Math.max(1, aOrig.getWidth()/2), aH = aOrig.getHeight();
					BufferedImage aFirst = half(aOrig, 0, aW, aH);
					BufferedImage aSecond = half(aOrig, aW, aW, aH);
					aL = origStereo == 2 ? aFirst : aSecond;
					aR = origStereo == 2 ? aSecond : aFirst;
				}
				else {
					aL = aOrig;
					aR = aOrig;
					if(pathR != null && !pathR.equals(pathL)) {
						if(aRs[1] == null) {
							aRs[1] = new VideoReader(tde, pathR, texLeftReader, true);
						}
						BufferedImage aOR = aRs[1].getImage(aMode, aTime);
						if(aOR != null) {
							aR = aOR;
						}
					}
				}
				BufferedImage aOL = orient(aL, rotateL);
				BufferedImage aORt = aR == aL && rotateR.equals(rotateL) ? aOL : orient(aR, rotateR);
				return new BufferedImage[] {aOL, aORt};
			}
			catch(Throwable t) {
				t.printStackTrace(System.err);
				return null;
			}
		}
	}

	/** Copy of the half of aBI starting at aX (a sub image shares the whole buffer of aBI) */
	static BufferedImage half(BufferedImage aBI, int aX, int aW, int aH) {
		BufferedImage aOut = ImageUtils.createImage(aW, aH);
		Graphics2D aG = (Graphics2D)aOut.getGraphics();
		aG.drawImage(aBI, -aX, 0, null);
		aG.dispose();
		return aOut;
	}

	/**
	 * Same rotation and anamorphosis as the left view (see StereoImage.rotate())
	 */
	public BufferedImage orient(BufferedImage aBI) {
		return orient(aBI, rotateL);
	}

	/**
	 * Rotation aRotate (degrees) and the anamorphosis of the media
	 */
	BufferedImage orient(BufferedImage aBI, String aRotate) {
		if(aRotate.equals("0") && anamorphW.equals("1")) {
			return aBI;
		}
		int aWidth = aBI.getWidth();
		int aHeight = aBI.getHeight();
		double aAnamorph = Double.parseDouble(anamorphW);
		BufferedImage aOut;
		AffineTransform aAT;
		if(aRotate.equals("180") || aRotate.equals("0")) {
			aOut = ImageUtils.createImage((int)(aAnamorph*aWidth),aHeight);
			aAT = AffineTransform.getTranslateInstance(aWidth/2,aHeight/2);
		}
		else {
			aOut = ImageUtils.createImage(aHeight,(int)(aAnamorph*aWidth));
			aAT = AffineTransform.getTranslateInstance(aHeight/2,aWidth/2);
		}
		aAT.concatenate(AffineTransform.getRotateInstance(Math.PI*Double.parseDouble(aRotate)/180.0));
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
			//The work copy and the texture copy (3D models)
			for(boolean aTex : new boolean[] {false, true}) {
				try {
					File aFrom = new File(aTex ? StringUtils.path2texturecopy(aPath, aFromDir) : StringUtils.path2workingcopy(aPath, aFromDir));
					File aTo = new File(aTex ? StringUtils.path2texturecopy(aPath, aToDir) : StringUtils.path2workingcopy(aPath, aToDir));
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
}
