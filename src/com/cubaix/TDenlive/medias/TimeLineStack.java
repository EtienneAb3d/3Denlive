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
import com.cubaix.TDenlive.processors.DepthmapStereo;
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
	//Drop of a media dragged from the media list (see setDropPreview()): where its clip would go, drawn
	volatile VideoTimeLine dropLine = null;
	volatile long dropStartMS = 0;
	volatile long dropDurationMS = 0;

	//TEMPORARY: trace of the composition of the lines (left view), to be removed
	static final boolean _TRACE_COMPOSE = true;

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
	
	/**
	 * Moves a whole line (with its clips and settings) up (drawn higher: index + 1) or down in the stack
	 * of its kind (video / audio); the lines are named again by their place (V1.., A1..)
	 */
	public void moveLine(VideoTimeLine aTL,boolean aUp) {
		boolean aAudio = aTL instanceof AudioTimeLine;
		@SuppressWarnings("unchecked")
		Vector<VideoTimeLine> aStack = aAudio ? (Vector<VideoTimeLine>)(Vector<?>)audioTimeLineStack : videoTimeLineStack;
		int aIdx = aStack.indexOf(aTL);
		int aNew = aIdx+(aUp ? 1 : -1);
		if(aIdx < 0 || aNew < 0 || aNew >= aStack.size()) {
			return;
		}
		aStack.set(aIdx, aStack.elementAt(aNew));
		aStack.set(aNew, aTL);
		for(int t = 0;t < aStack.size();t++) {
			aStack.elementAt(t).setName((aAudio ? "A" : "V")+(t+1));
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
		Vector<Clip> aIgnoredV = new Vector<Clip>();
		if(aIgnored != null) {
			aIgnoredV.add(aIgnored);
		}
		return snapTargetsMS(aIgnoredV);
	}

	/**
	 * Targets of the sticky edges: the time cursor and the start / end of the clips, except aIgnored and their twins
	 */
	Vector<Long> snapTargetsMS(Vector<Clip> aIgnored) {
		Vector<VideoTimeLine> aTLs = new Vector<VideoTimeLine>(videoTimeLineStack);
		aTLs.addAll(audioTimeLineStack);
		Vector<Long> aTargets = new Vector<Long>();
		aTargets.add(getRequestedTimePosMS());
		for(VideoTimeLine aTL : aTLs) {
			for(Clip aC : aTL.clipList) {
				boolean aSkip = false;
				for(Clip aI : aIgnored) {
					if(aC == aI || aI.getTwinClips().contains(aC)) {
						aSkip = true;
						break;
					}
				}
				if(aSkip) {
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

	/**
	 * Time line (video or audio) drawn at this height of the time line panel, null if none
	 */
	public VideoTimeLine getLineAtY(int aY) {
		Vector<VideoTimeLine> aTLs = new Vector<VideoTimeLine>(videoTimeLineStack);
		aTLs.addAll(audioTimeLineStack);
		for(VideoTimeLine aTL : aTLs) {
			Rectangle aB = aTL.bounds;
			if(aB != null && aY >= aB.y && aY < aB.y+aB.height) {
				return aTL;
			}
		}
		return null;
	}

	/**
	 * Time of the montage at this abscissa of the time line panel (0 at least)
	 */
	public long getTimeAtX(int aX) {
		if(rulerBounds == null) {
			return 0;
		}
		return Math.max(0, Math.round((aX-rulerBounds.x)/pixPerMS));
	}

	/**
	 * Line which receives aMedia dropped on aLine, null if refused: a sound goes to an audio line only;
	 * on an audio line, a video gives its sound only (if it has one)
	 */
	VideoTimeLine dropLineFor(Media aMedia,VideoTimeLine aLine) {
		if(aMedia == null || aLine == null) {
			return null;
		}
		boolean aAudioLine = aLine instanceof AudioTimeLine;
		if(aMedia instanceof AudioMedia) {
			return aAudioLine ? aLine : null;
		}
		if(aAudioLine && aMedia.getAudioReader() == null) {
			return null;
		}
		return aLine;
	}

	/**
	 * Start of a clip of aMedia dropped at this abscissa: sticky edges, like a clip move
	 */
	long dropStartMS(Media aMedia,int aX) {
		long aStart = getTimeAtX(aX);
		long aSnapMS = Math.round(8/pixPerMS);
		return Math.max(0, snapMoveMS(aStart, aStart+aMedia.durationMS, null, aSnapMS));
	}

	/**
	 * Shows where aMedia dragged at (aX, aY) would be dropped. False (and nothing shown) if it can't be
	 */
	public boolean setDropPreview(Media aMedia,int aX,int aY) {
		VideoTimeLine aLine = dropLineFor(aMedia, getLineAtY(aY));
		if(aLine == null) {
			dropLine = null;
			return false;
		}
		dropStartMS = dropStartMS(aMedia, aX);
		dropDurationMS = aMedia.durationMS;
		dropLine = aLine;
		return true;
	}

	public void clearDropPreview() {
		dropLine = null;
	}

	/**
	 * Adds a clip of aMedia on the line at (aX, aY), starting at the time of aX (sticky edges), and
	 * selects it. Returns the clip, null if the media can't go there
	 */
	public Clip dropMedia(Media aMedia,int aX,int aY) {
		dropLine = null;
		VideoTimeLine aLine = dropLineFor(aMedia, getLineAtY(aY));
		if(aLine == null) {
			return null;
		}
		Clip aClip = aLine.newClip(aMedia);
		aClip.setStartTimeMS(dropStartMS(aMedia, aX));
		aLine.addClip(aClip);
		//The new clip is the only selection
		select(-1, -1, true);
		tde.selected.clips.clear();
		tde.selected.timeLines.clear();
		aClip.selState = Drawable.THUMBSELSTATE_SELECTED;
		tde.selected.clips.add(aClip);
		return aClip;
	}

	/**
	 * Time line which holds aClip, null if none
	 */
	public VideoTimeLine getLineOf(Clip aClip) {
		Vector<VideoTimeLine> aTLs = new Vector<VideoTimeLine>(videoTimeLineStack);
		aTLs.addAll(audioTimeLineStack);
		for(VideoTimeLine aTL : aTLs) {
			if(aTL.containsClip(aClip)) {
				return aTL;
			}
		}
		return null;
	}

	/**
	 * Moves aClip to aLine, of the same kind (video / audio: their clips have different processors).
	 * Returns true if moved
	 */
	public boolean moveClipToLine(Clip aClip,VideoTimeLine aLine) {
		VideoTimeLine aFrom = getLineOf(aClip);
		if(aFrom == null || aLine == null || aFrom == aLine
				|| (aFrom instanceof AudioTimeLine) != (aLine instanceof AudioTimeLine)) {
			return false;
		}
		aFrom.trashClip(aClip);
		aLine.addClip(aClip);
		return true;
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
	 * Clips of the time lines (video and audio) that show or play aMedia
	 */
	public Vector<Clip> clipsOf(Media aMedia) {
		Vector<Clip> aClips = new Vector<Clip>();
		Vector<VideoTimeLine> aTLs = new Vector<VideoTimeLine>(videoTimeLineStack);
		aTLs.addAll(audioTimeLineStack);
		for(VideoTimeLine aTL : aTLs) {
			for(Clip aC : new Vector<Clip>(aTL.clipList)) {
				if(aC.getMedia() == aMedia && !aClips.contains(aC)) {
					aClips.add(aC);
				}
			}
		}
		return aClips;
	}

	/**
	 * Clips whose DepthmapStereo uses aMedia as depth map (in one of its keys)
	 */
	public Vector<Clip> depthUsesOf(Media aMedia) {
		Vector<Clip> aClips = new Vector<Clip>();
		Vector<VideoTimeLine> aTLs = new Vector<VideoTimeLine>(videoTimeLineStack);
		aTLs.addAll(audioTimeLineStack);
		for(VideoTimeLine aTL : aTLs) {
			for(Clip aC : new Vector<Clip>(aTL.clipList)) {
				for(Processor aP : new Vector<Processor>(aC.getProcessors())) {
					if(aP instanceof DepthmapStereo && ((DepthmapStereo)aP).usesDepthMedia(aMedia) && !aClips.contains(aC)) {
						aClips.add(aC);
					}
				}
			}
		}
		return aClips;
	}

	/**
	 * aMedia leaves the media list: its clips are removed from the time lines (with their twins),
	 * and the DepthmapStereo keys that use it as depth map no longer use it
	 */
	public void releaseMedia(Media aMedia) {
		for(Clip aC : depthUsesOf(aMedia)) {
			for(Processor aP : new Vector<Processor>(aC.getProcessors())) {
				if(aP instanceof DepthmapStereo) {
					((DepthmapStereo)aP).releaseDepthMedia(aMedia);
				}
			}
		}
		for(Clip aC : clipsOf(aMedia)) {
			trashClip(aC);
		}
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
			videoTimeLineStack.elementAt(t).canMoveUp = t < videoTimeLineStack.size()-1;
			videoTimeLineStack.elementAt(t).canMoveDown = t > 0;
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
			audioTimeLineStack.elementAt(t).canMoveUp = t < audioTimeLineStack.size()-1;
			audioTimeLineStack.elementAt(t).canMoveDown = t > 0;
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
		//Place of a media dragged from the media list
		VideoTimeLine aDropLine = dropLine;
		if(aDropLine != null && aDropLine.bounds != null) {
			int aDropX = aX+tde.config.thumbW+(int)(dropStartMS*pixPerMS);
			int aDropW = Math.max(4, (int)(dropDurationMS*pixPerMS));
			aGC.setColor(new Color(121,44,125,60));
			aGC.fillRect(aDropX, aDropLine.bounds.y, aDropW, aDropLine.bounds.height);
			aGC.setColor(tde.gui.colorsAwt.CUBAIX_PINK);
			aGC.setStroke(new java.awt.BasicStroke(2));
			aGC.drawRect(aDropX+1, aDropLine.bounds.y+1, aDropW-2, aDropLine.bounds.height-2);
			aGC.setStroke(new java.awt.BasicStroke(1));
		}
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
		Vector<VideoTimeLine> aAllTLs = new Vector<VideoTimeLine>(videoTimeLineStack);
		aAllTLs.addAll(audioTimeLineStack);
		for(VideoTimeLine aTL : aAllTLs) {
			if(aTL.lineUpBounds != null && aTL.lineUpBounds.contains(aX,aY)) {
				moveLine(aTL, true);
				return true;
			}
			if(aTL.lineDownBounds != null && aTL.lineDownBounds.contains(aX,aY)) {
				moveLine(aTL, false);
				return true;
			}
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

	/**
	 * Unselects all the clips and lines
	 */
	public void clearSelection() {
		Vector<VideoTimeLine> aTLs = new Vector<VideoTimeLine>(videoTimeLineStack);
		aTLs.addAll(audioTimeLineStack);
		for(VideoTimeLine aTL : aTLs) {
			aTL.selState = Drawable.THUMBSELSTATE_NONE;
			for(Clip aC : aTL.clipList) {
				aC.selState = Drawable.THUMBSELSTATE_NONE;
			}
		}
		tde.selected.clips.clear();
		tde.selected.timeLines.clear();
	}

	/**
	 * Line whose head (name, checkbox... left of the clips) is at this place, null if none
	 */
	public VideoTimeLine getLineHeadAt(int aX, int aY) {
		if(aX < 0 || aX >= tde.config.thumbW) {
			return null;
		}
		return getLineAtY(aY);
	}

	/**
	 * Selects aClip as the main selected clip (last of tde.selected.clips, shown in the processors panel).
	 * aAdd: added to the selection, else the only one selected
	 */
	public void selectClip(Clip aClip, boolean aAdd) {
		if(!aAdd) {
			clearSelection();
		}
		aClip.selState = Drawable.THUMBSELSTATE_SELECTED;
		tde.selected.clips.remove(aClip);
		tde.selected.clips.add(aClip);
	}

	public void unselectClip(Clip aClip) {
		aClip.selState = Drawable.THUMBSELSTATE_NONE;
		tde.selected.clips.remove(aClip);
	}

	/**
	 * Selects aLine and all its clips. aAdd: added to the selection (a line already selected is then
	 * unselected, with its clips), else the only ones selected
	 */
	public void selectLine(VideoTimeLine aLine, boolean aAdd) {
		if(aAdd && aLine.selState > 0) {
			aLine.selState = Drawable.THUMBSELSTATE_NONE;
			tde.selected.timeLines.remove(aLine);
			for(Clip aC : aLine.clipList) {
				unselectClip(aC);
			}
			return;
		}
		if(!aAdd) {
			clearSelection();
		}
		aLine.selState = Drawable.THUMBSELSTATE_SELECTED;
		if(!tde.selected.timeLines.contains(aLine)) {
			tde.selected.timeLines.add(aLine);
		}
		for(Clip aC : aLine.clipList) {
			selectClip(aC, true);
		}
	}

	/**
	 * Stack (video or audio) of aLine
	 */
	Vector<? extends VideoTimeLine> stackOf(VideoTimeLine aLine) {
		return aLine instanceof AudioTimeLine ? audioTimeLineStack : videoTimeLineStack;
	}

	/**
	 * Index of aLine in its stack (video or audio: V1 / A1 = 0), -1 if none
	 */
	public int stackIndexOf(VideoTimeLine aLine) {
		return aLine == null ? -1 : stackOf(aLine).indexOf(aLine);
	}

	/**
	 * Sticky move of a group of clips by aDeltaMS from their starts aStartsMS: the delta to apply so that
	 * the start or the end of one of them (the nearest) sticks to a target of snapTimeMS() at most aWindowMS away
	 * (the clips of the group and their twins are not targets); aDeltaMS otherwise
	 */
	public long snapGroupMoveMS(Vector<Clip> aClips, long[] aStartsMS, long aDeltaMS, long aWindowMS) {
		long aBestCorr = 0;
		long aBestDist = aWindowMS+1;
		Vector<Long> aTargets = snapTargetsMS(aClips);
		for(int c = 0;c < aClips.size();c++) {
			long aStart = aStartsMS[c]+aDeltaMS;
			long aEnd = aStart+aClips.elementAt(c).getDurationMS();
			for(long aT : aTargets) {
				for(long aEdge : new long[] {aStart,aEnd}) {
					long aDist = Math.abs(aT-aEdge);
					if(aDist < aBestDist) {
						aBestDist = aDist;
						aBestCorr = aT-aEdge;
					}
				}
			}
		}
		return aDeltaMS+aBestCorr;
	}

	/**
	 * Moves a group of clips together: each one starts at aStartsMS + aDeltaMS (the earliest at 0 at least, the
	 * gaps are kept), and the clips of aLines (their lines at the start of the move) of the same kind as aKindLine
	 * (video / audio) go aLineDelta lines up in their stack (limited so that all of them stay in the stack, their
	 * vertical gaps are kept). The clips of the other kind only move in time
	 */
	public void moveClips(Vector<Clip> aClips, long[] aStartsMS, VideoTimeLine[] aLines, long aDeltaMS
			, VideoTimeLine aKindLine, int aLineDelta) {
		long aMinStart = Long.MAX_VALUE;
		for(long aS : aStartsMS) {
			aMinStart = Math.min(aMinStart, aS);
		}
		if(aClips.size() > 0) {
			aDeltaMS = Math.max(aDeltaMS, -aMinStart);
		}
		boolean aAudio = aKindLine instanceof AudioTimeLine;
		Vector<? extends VideoTimeLine> aStack = aAudio ? audioTimeLineStack : videoTimeLineStack;
		for(VideoTimeLine aL : aLines) {
			int aI = aL != null && (aL instanceof AudioTimeLine) == aAudio ? aStack.indexOf(aL) : -1;
			if(aI >= 0) {
				aLineDelta = Math.max(aLineDelta, -aI);
				aLineDelta = Math.min(aLineDelta, aStack.size()-1-aI);
			}
		}
		for(int c = 0;c < aClips.size();c++) {
			Clip aC = aClips.elementAt(c);
			aC.setStartTimeMS(aStartsMS[c]+aDeltaMS);
			VideoTimeLine aL = aLines[c];
			int aI = aL != null && (aL instanceof AudioTimeLine) == aAudio ? aStack.indexOf(aL) : -1;
			if(aI >= 0) {
				moveClipToLine(aC, aStack.elementAt(aI+aLineDelta));
			}
		}
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
						if(_TRACE_COMPOSE) {
							//TEMPORARY trace (gray clip not shown after a drop)
							StringBuilder aTr = new StringBuilder("COMPOSE t="+aTime+" mode="+aProcessingMode+" line="+aTL.getName()+" clips=");
							for(Clip aC : aTL.clipList) {
								aTr.append("[uid="+aC.UID+" "+aC.media.getClass().getSimpleName()+" start="+aC.getStartTimeMS()+" dur="+aC.getDurationMS()
									+" alpha="+aC.getAlpha(aTime)+" procs="+aC.getProcessors().size()+"]");
							}
							if(aBI == null) {
								aTr.append(" img=null");
							}
							else {
								aTr.append(" img="+aBI.getWidth()+"x"+aBI.getHeight()+" type="+aBI.getType()
									+" center=#"+Integer.toHexString(aBI.getRGB(aBI.getWidth()/2, aBI.getHeight()/2))
									+" corner=#"+Integer.toHexString(aBI.getRGB(0, 0)));
							}
							aTr.append(" lineAlpha="+aTL.getAlpha(aTime));
							System.out.println(aTr);
						}
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
