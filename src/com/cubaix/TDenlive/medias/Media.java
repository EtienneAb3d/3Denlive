package com.cubaix.TDenlive.medias;

import java.awt.image.BufferedImage;
import java.io.BufferedWriter;
import java.io.File;
import java.util.Vector;

import org.eclipse.swt.graphics.Image;

import com.cubaix.TDenlive.TDenlive;
import com.cubaix.TDenlive.GUI.Drawable;
import com.cubaix.TDenlive.ffmpeg.AudioReader;
import com.cubaix.TDenlive.utils.StringUtils;
import com.cubaix.TDenlive.xml.XmlObject;
import com.drew.imaging.ImageMetadataReader;
import com.drew.metadata.Directory;
import com.drew.metadata.Metadata;
import com.drew.metadata.Tag;

public abstract class Media extends Drawable {
	static long lastUID = 0;
	//Unique even when several medias/clips are created in the same millisecond
	static synchronized long nextUID() {
		lastUID = Math.max(lastUID+1, System.currentTimeMillis());
		return lastUID;
	}
	public long UID = nextUID();
	
	Vector<TimeListener> timeListeners = new Vector<TimeListener>();
	Vector<TimeListener> timeListenersRemove = new Vector<TimeListener>();
	
	public String fileName = null;
	public String metaData = "";
	public int origRes = 0;
	public double origRatio = 16.0/9.0;
	public int origWidth = 0;
	public int origHeight = 0;
	public int origStereo = 0;//0=L&R, 1=X, 2= P
	public boolean origHdr = false;
	
	public String anamorphW = "1";
	public String rotateL = "0";
	public String rotateR = "0";

	protected long durationMS = 5*1000;//5 s
	
	protected long timePosMS = 0;//Current processing time
	protected long requestedTimePosMS = 0;//Next processing times
	
	private Image thumbSwt = null;
	private BufferedImage thumbAwt = null;

	public Media(TDenlive aTDe) {
		super(aTDe);
	}
	
	public abstract int openProject(Vector<XmlObject> aOs,int o) throws Exception;
	public abstract void saveProject(StringBuffer aSB) throws Exception;

	public String getAnamorphW() {
		return anamorphW;
	}

	public void setAnamorphW(String anamorphW) {
		this.anamorphW = anamorphW;
		tde.updateFirstMediaRatio();
	}

	public void setRotateL(String aVal) {
		rotateL = aVal;
		rotate();
		thumbAwt = null;
		thumbSwt = null;
		buildThumb();
		tde.updateFirstMediaRatio();
	}

	/**
	 * Source files of the media (to pack the project), none by default
	 */
	public java.util.Vector<String> getFilePaths() {
		return new java.util.Vector<String>();
	}

	/**
	 * Width / height ratio of the media as displayed (left view, after rotation and anamorphosis),
	 * 0 if the media has no image size of its own (not loaded, sound, color background)
	 */
	public double getDisplayRatio() {
		if(origRes <= 0 || origRatio <= 0) {
			return 0;
		}
		double aRatio = origRatio;
		try {
			aRatio *= Double.parseDouble(anamorphW);
		}
		catch(Throwable t) {
			t.printStackTrace(System.err);
		}
		if(rotateL.matches("-?90|270")) {
			aRatio = 1.0/aRatio;
		}
		return aRatio;
	}
	
	public void setRotateR(String aVal) {
		rotateR = aVal;
		rotate();
		thumbAwt = null;
		thumbSwt = null;
		buildThumb();
	}
	
	public long getDurationMS() {
		return durationMS;
	}

	public void setDurationMS(long aDurationMS) {
		durationMS = aDurationMS;
	}

	public String getDurationClock() {
		return StringUtils.time2Clock(durationMS);
	}
	
	public long setDurationClock(String aC) {
		long aDurationMS = StringUtils.clock2Time(aC);
		if(aDurationMS >= 0) {
			setDurationMS(aDurationMS);
		}
		return aDurationMS;
	}
	
	public long getTimePosMS() {
		return timePosMS;
	}

	public void setTimePosMS(long aTimePosMS) {
		if(timePosMS == aTimePosMS) {
			//No change
			return;
		}
		this.timePosMS = aTimePosMS;
		synchronized (timeListeners) {
			for(TimeListener aL : timeListenersRemove) {
				for(int tl = 0;tl < timeListeners.size();tl++) {
					if(timeListeners.elementAt(tl) == aL) {
						timeListeners.remove(tl);
						break;
					}
				}
			}
			for(TimeListener aL : timeListeners) {
				aL.timeChanged(aTimePosMS);
			}
		}
	}
	
	public void addTimeListener(TimeListener aTL) {
		synchronized (timeListeners) {
			timeListeners.add(aTL);
		}
	}
	
	public void removeTimeListener(TimeListener aTL) {
		synchronized (timeListenersRemove) {
			timeListenersRemove.add(aTL);
		}
	}

	public long getRequestedTimePosMS() {
		return requestedTimePosMS;
	}

	public void setRequestedTimePosMS(long requestedTimePosMS) {
		this.requestedTimePosMS = requestedTimePosMS;
	}

	public abstract long getMaxTimePosMS();
	
	public void rotate() {}
	public void buildThumb() {}
	
	public abstract void reBuild(int aProcessingMode,long aTime);
	public abstract void setWorkLeft(BufferedImage aBI);
	public abstract BufferedImage getWorkLeft(int aProcessingMode,long aTime);
	public abstract void setWorkRight(BufferedImage aBI);
	public abstract BufferedImage getWorkRight(int aProcessingMode,long aTime);
	public abstract BufferedImage getFinalLeft(int aProcessingMode,long aTime);
	public abstract BufferedImage getFinalRight(int aProcessingMode,long aTime);
	
	/**
	 * Sound track of the media, null if the media can't have one (still images)
	 */
	public AudioReader getAudioReader() {
		return null;
	}

	/**
	 * Sound track of the left (or single) file, or of the right one if aRight and the media has two files
	 */
	public AudioReader getAudioReader(boolean aRight) {
		return getAudioReader();
	}
	
	public float getAlpha(long aTime) {
		return -1.0f;//not known by default
	}
	
	public void addMetaData(String aPath) {
		if(metaData != null && !metaData.trim().isEmpty()) {
			return;
		}
		StringBuffer aSB = new StringBuffer();
		File aFile = new File(aPath);
		aSB.append(metaData);
		aSB.append(aFile.getName()+"\n");
		try {
			Metadata metadata = ImageMetadataReader.readMetadata(aFile);
			for (Directory directory : metadata.getDirectories()) {
				for (Tag tag : directory.getTags()) {
					aSB.append(tag+"\n");
				}
			}
			aSB.append("\n");
		}
		catch(Throwable t) {
			t.printStackTrace(System.err);
		}
		metaData = aSB.toString();
	}

	public BufferedImage getThumbAwt() {
		if(thumbAwt == null) {
			buildThumb();
		}
		return thumbAwt;
	}
	
	/**
	 * Thumbnail of the media at a time of the media (film strip of the time lines).
	 * Same image whatever the time by default.
	 */
	public BufferedImage getThumbAwtAt(long aTimeMS) {
		return getThumbAwt();
	}
	
	protected boolean hasThumb() {
		return thumbAwt != null;
	}
	
	public void setThumbAwt(BufferedImage thumb) {
		thumbAwt = thumb;
	}
	
	public Image getThumbSwt() {
		if(thumbSwt == null) {
			buildThumb();
		}
		return thumbSwt;
	}

	public void setThumbSwt(Image thumb) {
		this.thumbSwt = thumb;
	}
}
