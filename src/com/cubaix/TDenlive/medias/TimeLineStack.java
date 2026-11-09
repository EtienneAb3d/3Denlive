package com.cubaix.TDenlive.medias;

import java.awt.AlphaComposite;
import java.awt.Color;
import java.awt.Composite;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;
import java.io.BufferedWriter;
import java.util.Vector;

import org.eclipse.swt.graphics.GC;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.graphics.Rectangle;

import com.cubaix.TDenlive.TDConfig;
import com.cubaix.TDenlive.TDenlive;
import com.cubaix.TDenlive.GUI.Drawable;
import com.cubaix.TDenlive.processors.Processor;
import com.cubaix.TDenlive.utils.ImageUtils;
import com.cubaix.TDenlive.utils.StringUtils;
import com.cubaix.TDenlive.xml.XmlObject;
import com.cubaix.TDenlive.xml.XmlTag;

public class TimeLineStack extends Media {
	public Vector<VideoTimeLine> videoTimeLineStack = new Vector<VideoTimeLine>();
	public Vector<AudioTimeLine> audioTimeLineStack = new Vector<AudioTimeLine>();
	public static final double DEFAULT_PIX_PER_MS = 50.0/1000.0;//50 pix per second
	public double pixPerMS = DEFAULT_PIX_PER_MS;//Time zoom (see TimeLineStackGUI.setZoom()) 
	BufferedImage workLeft = null;
	BufferedImage workRight = null;
	BufferedImage finalLeft = null;
	BufferedImage finalRight = null;
	public Rectangle addBounds = null;
	public Rectangle soundBounds = null;
	public Rectangle rulerBounds = null;

	public TimeLineStack(TDenlive aTDe) {
		super(aTDe);
		addVideoTimeLine();
		addVideoTimeLine();
		addAudioTimeLine();
	}

	@Override
	public int openProject(Vector<XmlObject> aOs,int o) throws Exception{
		for(;o < aOs.size();o++) {
			XmlObject aO = aOs.elementAt(o);
			if(aO instanceof XmlTag) {
				XmlTag aT = (XmlTag)aO;
				if("/TimeLineStack".equalsIgnoreCase(aT.tagName)) {
					mergeSoundTwins();
					if(audioTimeLineStack.size() <= 0) {
						addAudioTimeLine();
					}
					return o;
				}
				if("VideoTimeLine".equalsIgnoreCase(aT.tagName)
						//For compatibility with old params
						|| "TimeLine".equalsIgnoreCase(aT.tagName)) {
					VideoTimeLine aTL = new VideoTimeLine(tde, "V"+(videoTimeLineStack.size()+1));
					videoTimeLineStack.add(aTL);
					o = aTL.openProject(aOs, o);
				}
				if("AudioTimeLine".equalsIgnoreCase(aT.tagName)) {
					AudioTimeLine aTL = new AudioTimeLine(tde, "A"+(audioTimeLineStack.size()+1));
					audioTimeLineStack.add(aTL);
					o = aTL.openProject(aOs, o);
				}
			}
		}
		return o;
	}
	
	@Override
	public void saveProject(StringBuffer aSB) throws Exception {
		aSB.append("		<TimeLineStack>\n");
		for(VideoTimeLine aTL : videoTimeLineStack) {
			aTL.saveProject(aSB);
		}
		for(AudioTimeLine aTL : audioTimeLineStack) {
			aTL.saveProject(aSB);
		}
		aSB.append("		</TimeLineStack>\n");
	}

	public void addVideoTimeLine() {
		addVideoTimeLine(videoTimeLineStack.size());
	}
	
	public void addVideoTimeLine(int aIdx) {
		if(aIdx >= videoTimeLineStack.size()) {
			videoTimeLineStack.add(new VideoTimeLine(tde, "V"+(videoTimeLineStack.size()+1)));
			return;
		}
		if(aIdx < 0) {
			videoTimeLineStack.add(0,new VideoTimeLine(tde, "V1"));
			aIdx = 0;
		}
		for(;aIdx < videoTimeLineStack.size();aIdx++) {
			videoTimeLineStack.elementAt(aIdx).setName("V"+(aIdx+1));
		}
	}
	
	public AudioTimeLine addAudioTimeLine() {
		AudioTimeLine aTL = new AudioTimeLine(tde, "A"+(audioTimeLineStack.size()+1));
		audioTimeLineStack.add(aTL);
		return aTL;
	}
	
	/**
	 * Projects of version 1.0.7 put the sound of a video on an audio line, as a twin clip.
	 * The video clip now carries its own sound: the twin is removed, its sound processors
	 * (volume...) go to the video clip.
	 */
	void mergeSoundTwins() {
		for(AudioTimeLine aATL : audioTimeLineStack) {
			for(Clip aAC : new Vector<Clip>(aATL.clipList)) {
				for(Clip aVC : new Vector<Clip>(aAC.getTwinClips())) {
					if(aVC.getMedia() != aAC.getMedia() || isAudioClip(aVC)) {
						continue;
					}
					for(Processor aP : aAC.getProcessors()) {
						if(aP.isAudio()) {
							aP.targetMedia = aVC;
							aVC.getProcessors().add(aP);
						}
					}
					aAC.unlinkTwins();
					aATL.trashClip(aAC);
					break;
				}
			}
		}
		//Remove the audio lines left empty (at least one is added back by the caller)
		for(int t = audioTimeLineStack.size()-1;t >= 0;t--) {
			if(audioTimeLineStack.elementAt(t).clipList.size() <= 0) {
				audioTimeLineStack.remove(t);
			}
		}
		for(int t = 0;t < audioTimeLineStack.size();t++) {
			audioTimeLineStack.elementAt(t).setName("A"+(t+1));
		}
	}
	
	/**
	 * Sticky edges: the nearest start / end of another clip (any line), or the time cursor,
	 * if it is at most aWindowMS away from aTimeMS; aTimeMS otherwise.
	 * aIgnored and its twins are not targets (they move with the dragged edge).
	 */
	public long snapTimeMS(long aTimeMS,Clip aIgnored,long aWindowMS) {
		long aBest = aTimeMS;
		long aBestDist = aWindowMS+1;
		for(long aT : snapTargetsMS(aIgnored)) {
			long aDist = Math.abs(aT-aTimeMS);
			if(aDist < aBestDist) {
				aBestDist = aDist;
				aBest = aT;
			}
		}
		return aBest;
	}
	
	/**
	 * Sticky move of a clip from aStartMS to aEndMS: the start to give it so that its start or its end
	 * (the nearest) sticks to a target of snapTimeMS() at most aWindowMS away; aStartMS otherwise
	 */
	public long snapMoveMS(long aStartMS,long aEndMS,Clip aIgnored,long aWindowMS) {
		long aBestDelta = 0;
		long aBestDist = aWindowMS+1;
		for(long aT : snapTargetsMS(aIgnored)) {
			for(long aEdge : new long[] {aStartMS,aEndMS}) {
				long aDist = Math.abs(aT-aEdge);
				if(aDist < aBestDist) {
					aBestDist = aDist;
					aBestDelta = aT-aEdge;
				}
			}
		}
		return aStartMS+aBestDelta;
	}
	
	/**
	 * Targets of the sticky edges: the time cursor and the start / end of the clips, except aIgnored and its twins
	 */
	Vector<Long> snapTargetsMS(Clip aIgnored) {
		Vector<VideoTimeLine> aTLs = new Vector<VideoTimeLine>(videoTimeLineStack);
		aTLs.addAll(audioTimeLineStack);
		Vector<Long> aTargets = new Vector<Long>();
		aTargets.add(getRequestedTimePosMS());
		for(VideoTimeLine aTL : aTLs) {
			for(Clip aC : aTL.clipList) {
				if(aC == aIgnored || (aIgnored != null && aIgnored.getTwinClips().contains(aC))) {
					continue;
				}
				aTargets.add(aC.getStartTimeMS());
				aTargets.add(aC.getMaxTimePosMS());
			}
		}
		return aTargets;
	}

	/**
	 * Clip drawn at this position of the time line panel, null if none
	 */
	public Clip getClipAt(int aX, int aY) {
		Vector<VideoTimeLine> aTLs = new Vector<VideoTimeLine>(videoTimeLineStack);
		aTLs.addAll(audioTimeLineStack);
		for(VideoTimeLine aTL : aTLs) {
			for(Clip aC : aTL.clipList) {
				if(aC.bounds != null && aC.bounds.contains(aX, aY)) {
					return aC;
				}
			}
		}
		return null;
	}

	public boolean isAudioClip(Clip aClip) {
		for(AudioTimeLine aTL : audioTimeLineStack) {
			if(aTL.containsClip(aClip)) {
				return true;
			}
		}
		return false;
	}
	

	
	public VideoTimeLine getSelectedVideoTimeLine(boolean aOrFirst) {
		VideoTimeLine aSelected = aOrFirst && videoTimeLineStack.size() > 0 ? videoTimeLineStack.elementAt(0) : null;
		for(VideoTimeLine aTL : videoTimeLineStack) {
			if(aTL.selState > 0) {
				return aTL;
			}
		}
		return aSelected;
	}

	public AudioTimeLine getSelectedAudioTimeLine(boolean aOrFirst) {
		AudioTimeLine aSelected = aOrFirst && audioTimeLineStack.size() > 0 ? audioTimeLineStack.elementAt(0) : null;
		for(AudioTimeLine aTL : audioTimeLineStack) {
			if(aTL.selState > 0) {
				return aTL;
			}
		}
		return aSelected;
	}

	public boolean addMedia(Media aMedia) {
		if(aMedia instanceof AudioMedia) {
			//A sound only goes to the audio time lines
			return addAudioMedia(aMedia);
		}
		boolean aAudioSelected = false;
		for(AudioTimeLine aTL : audioTimeLineStack) {
			if(aTL.selState > 0) {
				aAudioSelected = true;
			}
			for(Clip aC : aTL.clipList) {
				if(aC.selState > 0) {
					aAudioSelected = true;
				}
			}
		}
		if(aAudioSelected && aMedia.getAudioReader() != null) {
			//Audio time line explicitly selected: only the sound of the video
			return addAudioMedia(aMedia);
		}
		return addVideoMedia(aMedia);
	}
	
	public boolean addVideoMedia(Media aMedia) {
		if(videoTimeLineStack.size() <= 0) {
			addVideoTimeLine();
		}
		boolean aAdded = false;
		for(VideoTimeLine aTL : videoTimeLineStack) {
			if(aTL.addMedia(aMedia, true)) {
				aAdded = true;
				break;
			}
		}
		if(!aAdded) {
			videoTimeLineStack.elementAt(0).addMedia(aMedia, false);
		}
		return true;
	}
	
	public boolean addAudioMedia(Media aMedia) {
		if(audioTimeLineStack.size() <= 0) {
			addAudioTimeLine();
		}
		for(AudioTimeLine aTL : audioTimeLineStack) {
			if(aTL.addMedia(aMedia, true)) {
				return true;
			}
		}
		audioTimeLineStack.elementAt(0).addMedia(aMedia, false);
		return true;
	}
	
	/**
	 * Audio lines are ordered like the video lines: A1 at the bottom, "up" goes to A(n+1)
	 * (created if needed)
	 */
	public void moveUpAudio(Clip aClip) {
		int aSel = indexOfAudioLine(aClip);
		if(aSel < 0) {
			return;
		}
		audioTimeLineStack.elementAt(aSel).trashClip(aClip);
		aSel++;
		while(aSel >= audioTimeLineStack.size()) {
			addAudioTimeLine();
		}
		audioTimeLineStack.elementAt(aSel).addClip(aClip);
	}
	
	/**
	 * "Down" goes to A(n-1); below A1, a new A1 is inserted
	 */
	public void moveDownAudio(Clip aClip) {
		int aSel = indexOfAudioLine(aClip);
		if(aSel < 0) {
			return;
		}
		audioTimeLineStack.elementAt(aSel).trashClip(aClip);
		aSel--;
		if(aSel < 0) {
			audioTimeLineStack.add(0, new AudioTimeLine(tde, "A1"));
			aSel = 0;
			for(int t = 0;t < audioTimeLineStack.size();t++) {
				audioTimeLineStack.elementAt(t).setName("A"+(t+1));
			}
		}
		audioTimeLineStack.elementAt(aSel).addClip(aClip);
	}
	
	int indexOfAudioLine(Clip aClip) {
		for(int t = 0;t < audioTimeLineStack.size();t++) {
			if(audioTimeLineStack.elementAt(t).containsClip(aClip)) {
				return t;
			}
		}
		return -1;
	}
	
	public void moveUpVideo(Clip aClip) {
		int aSel = 0;
		for(;aSel < videoTimeLineStack.size();aSel++) {
			if(videoTimeLineStack.elementAt(aSel).containsClip(aClip)) {
				videoTimeLineStack.elementAt(aSel).trashClip(aClip);
				break;
			}
		}
		if(aSel < videoTimeLineStack.size()) {
			aSel++;
		}
		while(aSel >= videoTimeLineStack.size()) {
			addVideoTimeLine();
		}
		VideoTimeLine aTL = videoTimeLineStack.elementAt(aSel);
		aTL.addClip(aClip);
	}
	
	public void moveDownVideo(Clip aClip) {
		int aSel = 0;
		for(;aSel < videoTimeLineStack.size();aSel++) {
			if(videoTimeLineStack.elementAt(aSel).containsClip(aClip)) {
				videoTimeLineStack.elementAt(aSel).trashClip(aClip);
				break;
			}
		}
		aSel--;
		while(aSel < 0) {
			addVideoTimeLine(aSel++);
		}
		VideoTimeLine aTL = videoTimeLineStack.elementAt(aSel);
		aTL.addClip(aClip);
	}
	
	public void duplicateUpVideo(Clip aClip) {
		int aSel = 0;
		for(;aSel < videoTimeLineStack.size();aSel++) {
			if(videoTimeLineStack.elementAt(aSel).containsClip(aClip)) {
				break;
			}
		}
		if(aSel < videoTimeLineStack.size()) {
			aSel++;
		}
		while(aSel >= videoTimeLineStack.size()) {
			addVideoTimeLine();
		}
		VideoTimeLine aTL = videoTimeLineStack.elementAt(aSel);
		Clip aCopy = aClip.duplicate();
		//An upper layer of the same images: its sound would be heard twice
		aCopy.soundOn = false;
		aTL.addClip(aCopy);
	}
	
	/**
	 * Cut aClip at aTimeMS: its second part is added to the same time line (see Clip.cutAt()).
	 * Returns the second part, null if nothing was cut.
	 */
	public Clip cutClip(Clip aClip,long aTimeMS) {
		Vector<VideoTimeLine> aTLs = new Vector<VideoTimeLine>(videoTimeLineStack);
		aTLs.addAll(audioTimeLineStack);
		for(VideoTimeLine aTL : aTLs) {
			if(aTL.containsClip(aClip)) {
				Clip aSecond = aClip.cutAt(aTimeMS);
				if(aSecond != null) {
					aTL.addClip(aSecond);
				}
				return aSecond;
			}
		}
		return null;
	}

	public Clip getClipByUID(long aUID) {
		for(VideoTimeLine aTL : videoTimeLineStack) {
			Clip aC = aTL.getClipByUID(aUID);
			if(aC != null) {
				return aC;
			}
		}
		for(AudioTimeLine aTL : audioTimeLineStack) {
			Clip aC = aTL.getClipByUID(aUID);
			if(aC != null) {
				return aC;
			}
		}
		return null;
	}
	
	/**
	 * Remove a clip from all the time lines, with its twins (sound of a video, or video of a sound)
	 */
	public void trashClip(Clip aClip) {
		for(Clip aC : new Vector<Clip>(aClip.getTwinClips())) {
			trashVideoClip(aC);
			trashAudioClip(aC);
		}
		aClip.unlinkTwins();
		trashVideoClip(aClip);
		trashAudioClip(aClip);
		tde.selected.clips.clear();
	}
	
	public void trashVideoClip(Clip aClip) {
		for(VideoTimeLine aTL : videoTimeLineStack) {
			aTL.trashClip(aClip);
		}
	}

	public void trashAudioClip(Clip aClip) {
		for(AudioTimeLine aTL : audioTimeLineStack) {
			aTL.trashClip(aClip);
		}
	}

	@Override
	public Rectangle drawSwt(GC aGC, int aX, int aY, Rectangle aClippingR) {
		aGC.setClipping(aClippingR);
		int aW = 0;
		int aH = 0;

		aGC.setBackground(tde.gui.colorsSwt.WHITE);
		aGC.fillRectangle(aClippingR.x, aY, aClippingR.width, tde.config.thumbH);
		
		aGC.drawImage(tde.gui.imgsSwt.getIcon("add.gif"), 10, 8);
		addBounds = new Rectangle(10,8,16,16);
		aGC.drawImage(tde.gui.imgsSwt.getIcon("sound.gif"), 36, 8);
		soundBounds = new Rectangle(36,8,16,16);

		aW += tde.config.thumbW;
		
		aGC.setForeground(tde.gui.colorsSwt.CUBAIX_BLUE);
		aGC.drawLine(tde.config.thumbW, 0, tde.config.thumbW, tde.config.thumbH);

		aGC.setClipping(new Rectangle(tde.config.thumbW+1,0,aClippingR.width-aClippingR.x-tde.config.thumbW-1,aClippingR.height));//Ruler fixed at the top
		long aTime = 0;
		int aHC = 0;
		int aWC = 0;
		while((aX+aW+aWC < aClippingR.x + aClippingR.width)) {
			String aClock = StringUtils.time2Clock(aTime);
			aGC.setForeground(tde.gui.colorsSwt.CUBAIX_BLUE);
			aGC.drawString(aClock,aX+aW+aWC, 0);
			Point aP = aGC.textExtent(aClock);
			aP.x+=20;
			aHC = aP.y;
			aGC.setForeground(tde.gui.colorsSwt.GRAY);
			aGC.drawLine(aX+aW+aWC, aP.y, aX+aW+aWC, aP.y*2);
			for(int aXC = aP.x/4;aXC < aP.x-aP.x/8;aXC+=aP.x/4) {
				aGC.drawLine(aX+aW+aWC+aXC, aP.y*2, aX+aW+aWC+aXC, (3*aP.y)/2);
			}
			aWC += aP.x;
			aTime += (int)(aP.x/(double)pixPerMS);
		}
		aGC.setClipping(aClippingR);
		aGC.drawLine(aX, aHC*2-1, aClippingR.x + aClippingR.width, aHC*2-1);
		
		aH += aHC*2+1;
		
		rulerBounds = new Rectangle(aX+tde.config.thumbW, 0, aClippingR.width-aX, aHC*2);
		
		aClippingR.y = rulerBounds.height;
		for(int t = videoTimeLineStack.size()-1;t >= 0;t--) {
			Rectangle aR = videoTimeLineStack.elementAt(t).drawSwt(aGC, aX, aY+aH,aClippingR);
			aGC.setClipping(aClippingR);
			aH += aR.height;
			if(aW < aR.width) {
				aW = aR.width;
			}
			aGC.setBackground(tde.gui.colorsSwt.WHITE);
			aGC.fillRectangle(0,aY+aH,aClippingR.x+aClippingR.width,3);
			aGC.setBackground(tde.gui.colorsSwt.GRAY);
			aGC.fillRectangle(3,aY+aH+1,aClippingR.x+aClippingR.width-6,1);
			aH += 3;
		}
		//Like the video lines: the last added on top, A1 at the bottom
		for(int t = audioTimeLineStack.size()-1;t >= 0;t--) {
			Rectangle aR = audioTimeLineStack.elementAt(t).drawSwt(aGC, aX, aY+aH,aClippingR);
			aGC.setClipping(aClippingR);
			aH += aR.height;
			if(aW < aR.width) {
				aW = aR.width;
			}
			aGC.setBackground(tde.gui.colorsSwt.WHITE);
			aGC.fillRectangle(0,aY+aH,aClippingR.x+aClippingR.width,3);
			aGC.setBackground(tde.gui.colorsSwt.GRAY);
			aGC.fillRectangle(3,aY+aH+1,aClippingR.x+aClippingR.width-6,1);
			aH += 3;
		}
		
		//Cursor
		aGC.setClipping(new Rectangle(tde.config.thumbW+1,0
				,aClippingR.width-aClippingR.x-tde.config.thumbW-1,aClippingR.height));
		aGC.setForeground(tde.gui.colorsSwt.CUBAIX_PINK);
		int aTimePosX = aX + tde.config.thumbW+(int)(requestedTimePosMS*(double)pixPerMS);
		aGC.drawLine(aTimePosX, aHC*2, aTimePosX, aY+aH);
		aGC.drawLine(aTimePosX, aHC*2, aTimePosX-5, aHC*2-10);
		aGC.drawLine(aTimePosX, aHC*2, aTimePosX+5, aHC*2-10);
		aGC.drawLine(aTimePosX-5, aHC*2-10, aTimePosX+5, aHC*2-10);
		
		return bounds = new Rectangle(aX, aY, aW, aH);
	}
	
	@Override
	public Rectangle drawAwt(Graphics2D aGC, int aX, int aY, Rectangle aClippingR) {
		aGC.setRenderingHint(RenderingHints.KEY_ANTIALIASING,RenderingHints.VALUE_ANTIALIAS_ON);
		aGC.setRenderingHint(RenderingHints.KEY_RENDERING,RenderingHints.VALUE_RENDER_QUALITY);

		aGC.setClip(aClippingR.x,aClippingR.y,aClippingR.width,aClippingR.height);
		int aW = 0;
		int aH = 0;

		aGC.setColor(tde.gui.colorsAwt.WHITE);
		aGC.fillRect(aClippingR.x, aClippingR.y, aClippingR.width, tde.config.thumbH);
		
		aGC.drawImage(tde.gui.imgsAwt.getIcon("add.gif"), 10, 8,null);
		addBounds = new Rectangle(10,8,16,16);
		aGC.drawImage(tde.gui.imgsAwt.getIcon("sound.gif"), 36, 8,null);
		soundBounds = new Rectangle(36,8,16,16);

		aW += tde.config.thumbW;
		
		aGC.setColor(tde.gui.colorsAwt.CUBAIX_BLUE);
		aGC.drawLine(tde.config.thumbW, 0, tde.config.thumbW, tde.config.thumbH);

		aGC.setClip(tde.config.thumbW+1,0,aClippingR.width-aClippingR.x-tde.config.thumbW-1,aClippingR.height);//Ruler fixed at the top
		long aTime = 0;
		int aHC = 0;
		int aWC = 0;
		while((aX+aW+aWC < aClippingR.x + aClippingR.width)) {
			String aClock = StringUtils.time2Clock(aTime);
			aGC.setColor(tde.gui.colorsAwt.CUBAIX_BLUE);
			aGC.setFont(tde.gui.fontsAwt.roboto);
			Rectangle2D aFR = aGC.getFontMetrics().getStringBounds(aClock,aGC);
			aGC.drawString(aClock,aX+aW+aWC, (int)aFR.getHeight());
			int aWT = (int)aFR.getWidth()+20;
			aHC = (int)aFR.getHeight();
			aGC.setColor(tde.gui.colorsAwt.GRAY);
			aGC.drawLine(aX+aW+aWC, aHC, aX+aW+aWC, aHC*2-1);
			for(int aXC = aWT/4;aXC < aWT-aWT/8;aXC+=aWT/4) {
				aGC.drawLine(aX+aW+aWC+aXC, aHC*2-1, aX+aW+aWC+aXC, (3*aHC)/2);
			}
			aWC += aWT;
			aTime += (int)(aWT/(double)pixPerMS);
		}
		aGC.setClip(aClippingR.x,aClippingR.y,aClippingR.width,aClippingR.height);
		aGC.drawLine(aX, aHC*2-1, aClippingR.x + aClippingR.width, aHC*2-1);
		
		aH += aHC*2+1;
		
		rulerBounds = new Rectangle(aX+tde.config.thumbW, 0, aClippingR.width-aX, aHC*2);
		
		aClippingR.y = rulerBounds.height;
		for(int t = videoTimeLineStack.size()-1;t >= 0;t--) {
			Rectangle aR = videoTimeLineStack.elementAt(t).drawAwt(aGC, aX, aY+aH,aClippingR);
			aGC.setClip(aClippingR.x,aClippingR.y,aClippingR.width,aClippingR.height);
			aH += aR.height;
			if(aW < aR.width) {
				aW = aR.width;
			}
			aGC.setColor(tde.gui.colorsAwt.WHITE);
			aGC.fillRect(0,aY+aH,aClippingR.x+aClippingR.width,3);
			aGC.setColor(tde.gui.colorsAwt.GRAY);
			aGC.fillRect(3,aY+aH+1,aClippingR.x+aClippingR.width-6,1);
			aH += 3;
		}
		//Like the video lines: the last added on top, A1 at the bottom
		for(int t = audioTimeLineStack.size()-1;t >= 0;t--) {
			Rectangle aR = audioTimeLineStack.elementAt(t).drawAwt(aGC, aX, aY+aH,aClippingR);
			aGC.setClip(aClippingR.x,aClippingR.y,aClippingR.width,aClippingR.height);
			aH += aR.height;
			if(aW < aR.width) {
				aW = aR.width;
			}
			aGC.setColor(tde.gui.colorsAwt.WHITE);
			aGC.fillRect(0,aY+aH,aClippingR.x+aClippingR.width,3);
			aGC.setColor(tde.gui.colorsAwt.GRAY);
			aGC.fillRect(3,aY+aH+1,aClippingR.x+aClippingR.width-6,1);
			aH += 3;
		}
		
		//Cursor
		aGC.setClip(tde.config.thumbW+1,0
				,aClippingR.width-aClippingR.x-tde.config.thumbW-1,aClippingR.height);
		aGC.setColor(tde.gui.colorsAwt.CUBAIX_PINK);
		int aTimePosX = aX + tde.config.thumbW+(int)(requestedTimePosMS*(double)pixPerMS);
		aGC.drawLine(aTimePosX, aHC*2, aTimePosX, aY+aH);
		aGC.drawLine(aTimePosX, aHC*2, aTimePosX-5, aHC*2-10);
		aGC.drawLine(aTimePosX, aHC*2, aTimePosX+5, aHC*2-10);
		aGC.drawLine(aTimePosX-5, aHC*2-10, aTimePosX+5, aHC*2-10);
		
		return bounds = new Rectangle(aX, aY, aW, aH);
	}

	@Override
	public boolean selectWidget(int aX, int aY) {
		if(addBounds != null && addBounds.contains(aX,aY)) {
			addVideoTimeLine();
			return true;
		}
		if(soundBounds != null && soundBounds.contains(aX,aY)) {
			addAudioTimeLine();
			return true;
		}
		if(rulerBounds != null && rulerBounds.contains(aX,aY)) {
			requestedTimePosMS = (long)((aX - rulerBounds.x)/(double)pixPerMS);
			return true;
		}
		for(VideoTimeLine aTL : videoTimeLineStack) {
			if(aTL.selectWidget(aX, aY)) {
				return true;
			}
		}
		for(AudioTimeLine aTL : audioTimeLineStack) {
			if(aTL.selectWidget(aX, aY)) {
				return true;
			}
		}
		return false;
	}

	/**
	 * Key (in the "Tip" language section) of the help message of the widget at this place, or null
	 */
	public String getWidgetTip(int aX, int aY) {
		if(addBounds != null && addBounds.contains(aX,aY)) {
			return "AddVideoLine";
		}
		if(soundBounds != null && soundBounds.contains(aX,aY)) {
			return "AddAudioLine";
		}
		if(rulerBounds != null && rulerBounds.contains(aX,aY)) {
			return "Ruler";
		}
		for(VideoTimeLine aTL : videoTimeLineStack) {
			String aKey = aTL.getWidgetTip(aX, aY);
			if(aKey != null) {
				return aKey;
			}
		}
		for(AudioTimeLine aTL : audioTimeLineStack) {
			String aKey = aTL.getWidgetTip(aX, aY);
			if(aKey != null) {
				return aKey;
			}
		}
		return null;
	}

	@Override
	public void select(int aX, int aY, boolean aOutUnselect) {
		if(selectWidget(aX, aY)) {
			return;
		}
		if(aOutUnselect) {
			tde.selected.timeLines.clear();
			tde.selected.clips.clear();
		}
		for(VideoTimeLine aTL : videoTimeLineStack) {
			aTL.select(aX, aY,aOutUnselect);
		}
		for(AudioTimeLine aTL : audioTimeLineStack) {
			aTL.select(aX, aY,aOutUnselect);
		}
	}
	
	@Override
	public long getMaxTimePosMS() {
		long aMax = 0;
		for(VideoTimeLine aTL : videoTimeLineStack) {
			long aMaxT = aTL.getMaxTimePosMS();
			if(aMaxT > aMax) {
				aMax = aMaxT;
			}
		}
		for(AudioTimeLine aTL : audioTimeLineStack) {
			long aMaxT = aTL.getMaxTimePosMS();
			if(aMaxT > aMax) {
				aMax = aMaxT;
			}
		}
		return aMax;
	}

	@Override
	public void reBuild(int aProcessingMode,long aTime) {
		workLeft = workRight = finalLeft = finalRight = null;
		Vector<Thread> aThs = new Vector<Thread>();
		for(VideoTimeLine aTL : videoTimeLineStack) {
			Thread aTh = new Thread(new Runnable() {
				@Override
				public void run() {
					if(aTL.isActive) {
						aTL.reBuild(aProcessingMode, aTime);
					}
				}
			});
			aThs.add(aTh);
			aTh.start();
		}
		for(Thread aTh : aThs) {
			try {
				aTh.join();
			}
			catch(Throwable t) {
				t.printStackTrace(System.err);
			}
		}
		try {
			Thread aThL = new Thread(new Runnable() {
				@Override
				public void run() {
					int aWidthOut = (int)(tde.config.outRatio*tde.config.processingResValues[aProcessingMode]);
					int aHeightOut = tde.config.processingResValues[aProcessingMode];
					BufferedImage aAssembledBIL = ImageUtils.createImage(aWidthOut,aHeightOut,tde.config.processingHdr[aProcessingMode]);
					Graphics2D aGL2 = (Graphics2D)aAssembledBIL.getGraphics();
					//Work preview: left transparent, the mixer shows a checkerboard behind (see Mixer.resize())
					if(aProcessingMode != TDConfig.PROCESSING_MODE_WORK) {
						aGL2.setColor(Color.BLACK);
						aGL2.fillRect(0, 0, aWidthOut, aHeightOut);
					}
					for(VideoTimeLine aTL : videoTimeLineStack) {
						if(!aTL.isActive) {
							continue;
						}
						BufferedImage aBI = aTL.getFinalLeft(aProcessingMode, aTime);
						if(aBI == null) {
							continue;
						}
						
						float aAlpha = aTL.getAlpha(aTime);
						Composite comp = AlphaComposite.getInstance(AlphaComposite.SRC_OVER,aAlpha);
						aGL2.setComposite(comp );
				         
						aGL2.drawImage(aBI,0,0, null);
					}
					workLeft = aAssembledBIL;
					aGL2.dispose();
				}
			});
			aThL.start();
			Thread aThR = new Thread(new Runnable() {
				@Override
				public void run() {

					int aWidthOut = (int)(tde.config.outRatio*tde.config.processingResValues[aProcessingMode]);
					int aHeightOut = tde.config.processingResValues[aProcessingMode];
					BufferedImage aAssembledBIR = ImageUtils.createImage(aWidthOut,aHeightOut,tde.config.processingHdr[aProcessingMode]);
					Graphics2D aGR2 = (Graphics2D)aAssembledBIR.getGraphics();	
					//Work preview: left transparent, the mixer shows a checkerboard behind (see Mixer.resize())
					if(aProcessingMode != TDConfig.PROCESSING_MODE_WORK) {
						aGR2.setColor(Color.BLACK);
						aGR2.fillRect(0, 0, aWidthOut, aHeightOut);
					}
					for(VideoTimeLine aTL : videoTimeLineStack) {
						if(!aTL.isActive) {
							continue;
						}
						BufferedImage aBI = aTL.getFinalRight(aProcessingMode, aTime);
						if(aBI == null) {
							continue;
						}
						float aAlpha = aTL.getAlpha(aTime);
						Composite comp = AlphaComposite.getInstance(AlphaComposite.SRC_OVER,aAlpha);
						aGR2.setComposite(comp );

						aGR2.drawImage(aBI,0,0, null);
					}
					workRight = aAssembledBIR;
					aGR2.dispose();
				}
			});
			aThR.start();
			aThL.join();
			aThR.join();
		}
		catch(Throwable t) {
			t.printStackTrace(System.err);
		}
		finalLeft = workLeft;
		finalRight = workRight;
	}

	@Override
	public void setWorkLeft(BufferedImage aBI) {
		// TODO Auto-generated method stub
		
	}

	@Override
	public void setWorkRight(BufferedImage aBI) {
		// TODO Auto-generated method stub
		
	}

	@Override
	public BufferedImage getWorkLeft(int aProcessingMode,long aTime) {
		return workLeft;
	}

	@Override
	public BufferedImage getWorkRight(int aProcessingMode,long aTime) {
		return workRight;
	}

	@Override
	public BufferedImage getFinalLeft(int aProcessingMode,long aTime) {
		while(finalLeft == null) {
			try {
				Thread.sleep(10);
			}
			catch(Throwable t) {
				t.printStackTrace(System.err);
			}
		}
		return finalLeft;
	}

	@Override
	public BufferedImage getFinalRight(int aProcessingMode,long aTime) {
		while(finalRight == null) {
			try {
				Thread.sleep(10);
			}
			catch(Throwable t) {
				t.printStackTrace(System.err);
			}
		}
		return finalRight;
	}

	
}
