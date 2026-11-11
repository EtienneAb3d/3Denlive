package com.cubaix.TDenlive.medias;

import java.awt.BasicStroke;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.util.Vector;

import org.eclipse.swt.graphics.GC;
import org.eclipse.swt.graphics.Rectangle;

import com.cubaix.TDenlive.TDenlive;
import com.cubaix.TDenlive.GUI.Drawable;
import com.cubaix.TDenlive.ffmpeg.AudioReader;
import com.cubaix.TDenlive.processors.BrightnessContrast;
import com.cubaix.TDenlive.processors.Chromakey;
import com.cubaix.TDenlive.processors.ColorAdapter;
import com.cubaix.TDenlive.processors.Cropper;
import com.cubaix.TDenlive.processors.Depthmap;
import com.cubaix.TDenlive.processors.DepthmapStereo;
import com.cubaix.TDenlive.processors.Framing;
import com.cubaix.TDenlive.processors.HueSaturation;
import com.cubaix.TDenlive.processors.LensCorrection;
import com.cubaix.TDenlive.processors.Placer;
import com.cubaix.TDenlive.processors.Processor;
import com.cubaix.TDenlive.processors.Scripter;
import com.cubaix.TDenlive.processors.Shapes;
import com.cubaix.TDenlive.processors.Subtitles;
import com.cubaix.TDenlive.processors.StereoAligner;
import com.cubaix.TDenlive.processors.TimeController;
import com.cubaix.TDenlive.processors.Transparency;
import com.cubaix.TDenlive.processors.Volume;
import com.cubaix.TDenlive.utils.StringUtils;
import com.cubaix.TDenlive.xml.XmlMinimalParser;
import com.cubaix.TDenlive.xml.XmlObject;
import com.cubaix.TDenlive.xml.XmlTag;

public class Clip extends Media {
	protected Media media = null;
	protected Vector<Processor> processors = new Vector<Processor>();
	protected BufferedImage workLeft = null;
	protected BufferedImage workRight = null;
	protected BufferedImage finalLeft = null;
	protected BufferedImage finalRight = null;
	long startTimeMS = 0;
	long shiftTimeMS = 0;
	long fadeInMS = 0;
	long fadeOutMS = 0;
	//Time of the keys of the processors at the start of the clip (> 0 for the second part of a cut clip):
	//a key at time t is at startTimeMS-keysShiftMS+t on the time line
	long keysShiftMS = 0;
	Vector<Clip> pairedClips = new Vector<Clip>();
	public int pairedType = -1;//-1 = not a paired, 0 = unknown, 1 = depthmap
	//Paired clip whose media takes the rotation / anamorphosis of the media of its clip (computed depth maps)
	public boolean followsOrientation = false;
	Vector<Clip> twinClips = new Vector<Clip>();
	//Sound of the clip heard (e.g. off for a copy of a clip used as an upper layer)
	public boolean soundOn = true;
	//Sound of the right video of a media of two files, set by the line of the clip (see VideoTimeLine.soundSource)
	public boolean soundRight = false;
	//Shortest clip left by a trim of its start / end on the time line
	public static final long MIN_DURATION_MS = 40;

	public Clip(TDenlive aTDe,Media aMedia) {
		super(aTDe);
		media = aMedia;
		durationMS = media != null ? media.durationMS : 5*1000;
		fileName = media != null ? media.fileName:"??";
		processors.add(new TimeController(tde,this));
		if(media instanceof ShapesMedia) {
			//Mandatory: draws the shapes of the clip
			processors.add(new Shapes(tde,this));
		}
		if(media instanceof Model3DMedia) {
			//Mandatory: moves the objects of the model, with keys
			processors.add(new com.cubaix.TDenlive.processors.Objects3D(tde,this));
		}
		if(!(media instanceof AudioMedia)) {
			processors.add(new StereoAligner(tde,this));
		}
	}
	
	/**
	 * For a clip used as sound: no image processor
	 */
	public void keepOnlyTimeController() {
		for(int p = processors.size()-1;p >= 0;p--) {
			if(!(processors.elementAt(p) instanceof TimeController) && !processors.elementAt(p).isAudio()) {
				processors.remove(p);
			}
		}
	}

	@Override
	public int openProject(Vector<XmlObject> aOs,int o) throws Exception{
		for(;o < aOs.size();o++) {
			XmlObject aO = aOs.elementAt(o);
			if(aO instanceof XmlTag) {
				XmlTag aT = (XmlTag)aO;
				if("/Clip".equalsIgnoreCase(aT.tagName)) {
					return o;
				}
				if("UID".equalsIgnoreCase(aT.tagName)) {
					o++;
					UID=Long.parseLong(aOs.elementAt(o).text);
				}
				if("Media".equalsIgnoreCase(aT.tagName)) {
					media = tde.mediaList.getMediaByUID(Long.parseLong(aT.attrs.get("uid")));
					fileName = media.fileName;
				}
				if("startTimeMS".equalsIgnoreCase(aT.tagName)) {
					o++;
					startTimeMS = Long.parseLong(aOs.elementAt(o).text);
				}
				if("shiftTimeMS".equalsIgnoreCase(aT.tagName)) {
					o++;
					shiftTimeMS = Long.parseLong(aOs.elementAt(o).text);
				}
				if("durationMS".equalsIgnoreCase(aT.tagName)) {
					o++;
					durationMS = Long.parseLong(aOs.elementAt(o).text);
				}
				if("keysShiftMS".equalsIgnoreCase(aT.tagName)) {
					o++;
					keysShiftMS = Long.parseLong(aOs.elementAt(o).text);
				}
				if("fadeInMS".equalsIgnoreCase(aT.tagName)) {
					o++;
					fadeInMS = Long.parseLong(aOs.elementAt(o).text);
				}
				if("fadeOutMS".equalsIgnoreCase(aT.tagName)) {
					o++;
					fadeOutMS = Long.parseLong(aOs.elementAt(o).text);
				}
				if("Processors".equalsIgnoreCase(aT.tagName)) {
					processors.clear();
					processors.add(new TimeController(tde,this));
					o = openProjectProcessors(aOs, o);
				}
				if("soundOn".equalsIgnoreCase(aT.tagName)) {
					o++;
					soundOn = !"false".equalsIgnoreCase(aOs.elementAt(o).text);
				}
				if("TwinClipUID".equalsIgnoreCase(aT.tagName)) {
					o++;
					long aUID = Long.parseLong(aOs.elementAt(o).text);
					Clip aC = tde.timeLineStack.getClipByUID(aUID);
					if(aC != null && aC != this && !twinClips.contains(aC)) {//If not found, will be set when the twin clip will be loaded
						twinClips.add(aC);
						aC.twinClips.add(this);
					}
				}
			}
		}
		return o;
	}

	public int openProjectProcessors(Vector<XmlObject> aOs,int o) throws Exception{
		for(;o < aOs.size();o++) {
			XmlObject aO = aOs.elementAt(o);
			if(aO instanceof XmlTag) {
				XmlTag aT = (XmlTag)aO;
				if("/Processors".equalsIgnoreCase(aT.tagName)) {
					//Projects made before the rule: the depth map processor goes first
					Processor aDP = getDepthProcessor();
					if(aDP != null) {
						placeDepthProcessor(aDP);
					}
					//Clips of 3D models made before the processor of their objects
					if(media instanceof Model3DMedia) {
						boolean aHas = false;
						for(Processor aP : processors) {
							aHas |= aP instanceof com.cubaix.TDenlive.processors.Objects3D;
						}
						if(!aHas) {
							int aPos = 0;
							while(aPos < processors.size() && processors.elementAt(aPos) instanceof TimeController) {
								aPos++;
							}
							processors.add(aPos, new com.cubaix.TDenlive.processors.Objects3D(tde,this));
						}
					}
					return o;
				}
				if("ProcAutoKey".equalsIgnoreCase(aT.tagName)) {
					//Former manual key mode (before the key selection): ignored
					o++;
					continue;
				}
				if("ProcSelKeys".equalsIgnoreCase(aT.tagName)) {
					//Selected keys of the processor just read
					o++;
					if(processors.size() > 0) {
						processors.lastElement().selectedKeysFromString(aOs.elementAt(o).text);
					}
					continue;
				}
				if("StereoAligner".equalsIgnoreCase(aT.tagName)) {
					StereoAligner aSA = new StereoAligner(tde,this);
					processors.add(aSA);
					o = aSA.openProject(aOs, o);
				}
				if("Shapes".equalsIgnoreCase(aT.tagName)) {
					Shapes aP = new Shapes(tde,this);
					processors.add(aP);
					o = aP.openProject(aOs, o);
				}
				if("Objects3D".equalsIgnoreCase(aT.tagName)) {
					com.cubaix.TDenlive.processors.Objects3D aP = new com.cubaix.TDenlive.processors.Objects3D(tde,this);
					processors.add(aP);
					o = aP.openProject(aOs, o);
				}
				if("Subtitles".equalsIgnoreCase(aT.tagName)) {
					Subtitles aP = new Subtitles(tde,this);
					processors.add(aP);
					o = aP.openProject(aOs, o);
				}
				if("ColorAdapter".equalsIgnoreCase(aT.tagName)) {
					ColorAdapter aCA = new ColorAdapter(tde,this);
					processors.add(aCA);
					o = aCA.openProject(aOs, o);
				}
				if("Depthmap".equalsIgnoreCase(aT.tagName)) {
					Depthmap aP = new Depthmap(tde,this);
					processors.add(aP);
					o = aP.openProject(aOs, o);
				}
				if("DepthmapStereo".equalsIgnoreCase(aT.tagName)) {
					DepthmapStereo aP = new DepthmapStereo(tde,this);
					processors.add(aP);
					o = aP.openProject(aOs, o);
				}
				if("Placer".equalsIgnoreCase(aT.tagName)) {
					Placer aP = new Placer(tde,this);
					processors.add(aP);
					o = aP.openProject(aOs, o);
				}
				if("Scripter".equalsIgnoreCase(aT.tagName)) {
					Scripter aP = new Scripter(tde,this);
					processors.add(aP);
					o = aP.openProject(aOs, o);
				}
				if("Cropper".equalsIgnoreCase(aT.tagName)) {
					Cropper aP = new Cropper(tde,this);
					processors.add(aP);
					o = aP.openProject(aOs, o);
				}
				if("Transparency".equalsIgnoreCase(aT.tagName)) {
					Transparency aP = new Transparency(tde,this);
					processors.add(aP);
					o = aP.openProject(aOs, o);
				}
				if("Chromakey".equalsIgnoreCase(aT.tagName)) {
					Chromakey aP = new Chromakey(tde,this);
					processors.add(aP);
					o = aP.openProject(aOs, o);
				}
				if("BrightnessContrast".equalsIgnoreCase(aT.tagName)) {
					BrightnessContrast aP = new BrightnessContrast(tde,this);
					processors.add(aP);
					o = aP.openProject(aOs, o);
				}
				if("HueSaturation".equalsIgnoreCase(aT.tagName)) {
					HueSaturation aP = new HueSaturation(tde,this);
					processors.add(aP);
					o = aP.openProject(aOs, o);
				}
				if("Framing".equalsIgnoreCase(aT.tagName)) {
					Framing aP = new Framing(tde,this);
					processors.add(aP);
					o = aP.openProject(aOs, o);
				}
				if("LensCorrection".equalsIgnoreCase(aT.tagName)) {
					LensCorrection aP = new LensCorrection(tde,this);
					processors.add(aP);
					o = aP.openProject(aOs, o);
				}
				if("Volume".equalsIgnoreCase(aT.tagName)) {
					Volume aP = new Volume(tde,this);
					processors.add(aP);
					o = aP.openProject(aOs, o);
				}
			}
		}
		return o;
	}

	@Override
	public void saveProject(StringBuffer aSB) throws Exception {
		aSB.append("				<Clip>\n");
		aSB.append("					<UID>" + UID + "</UID>\n");
		aSB.append("					<Media UID='" + media.UID + "'>\n");
		aSB.append("						<startTimeMS>" + startTimeMS + "</startTimeMS>\n");
		aSB.append("						<shiftTimeMS>" + shiftTimeMS + "</shiftTimeMS>\n");
		aSB.append("						<durationMS>" + durationMS + "</durationMS>\n");
		aSB.append("						<keysShiftMS>" + keysShiftMS + "</keysShiftMS>\n");
		aSB.append("						<fadeInMS>" + fadeInMS + "</fadeInMS>\n");
		aSB.append("						<fadeOutMS>" + fadeOutMS + "</fadeOutMS>\n");
		aSB.append("					</Media>\n");
		aSB.append("					<Processors>\n");
		for(Processor aP : processors) {
			aP.saveProject(aSB);
			String aSel = aP.selectedKeysToString();
			if(aSel.length() > 0) {
				aSB.append("						<ProcSelKeys>" + aSel + "</ProcSelKeys>\n");
			}
		}
		aSB.append("					</Processors>\n");
		aSB.append("					<soundOn>" + soundOn + "</soundOn>\n");
		for(Clip aC : twinClips) {
			aSB.append("					<TwinClipUID>" + aC.UID + "</TwinClipUID>\n");
		}
		aSB.append("				</Clip>\n");
	}

	public Clip duplicate() {
		Clip aClip = null; 
		try {
			StringBuffer aSB = new StringBuffer();
			saveProject(aSB);
			
			XmlMinimalParser aXMP = new XmlMinimalParser();
			Vector<XmlObject> aOs = aXMP.parse(new ByteArrayInputStream(aSB.toString().getBytes("utf-8")));

			aClip = new Clip(tde, null);
			aClip.openProject(aOs, 0);
			//A new clip: its own UID, and not linked to the twins of this one
			aClip.UID = nextUID();
			aClip.unlinkTwins();
		}
		catch(Throwable t) {
			t.printStackTrace(System.err);
		}
		return aClip;
	}
	
	/**
	 * Cut the clip at aTimeMS (time line): this clip ends there, and a copy of it (returned, not
	 * added to any time line) starts there. Same images and sound: the copy plays the media from
	 * the cut, and its keys are kept as they are, shifted by keysShiftMS.
	 * null if aTimeMS is not strictly inside the clip.
	 */
	public Clip cutAt(long aTimeMS) {
		long aCut = aTimeMS-startTimeMS;
		if(aCut <= 0 || aCut >= durationMS) {
			return null;
		}
		Clip aSecond = duplicate();
		if(aSecond == null) {
			return null;
		}
		aSecond.startTimeMS = aTimeMS;
		aSecond.durationMS = durationMS-aCut;
		if(getMediaLimitMS() >= 0) {
			//Like trimStart(): the media position only moves for the videos and sounds
			aSecond.shiftTimeMS = shiftTimeMS+aCut;
		}
		aSecond.keysShiftMS = keysShiftMS+aCut;
		//The fade in stays on the first part, the fade out goes to the second one
		aSecond.fadeInMS = 0;
		fadeOutMS = 0;
		durationMS = aCut;
		for(Clip aC : twinClips) {
			aC.durationMS = durationMS;
			aC.fadeOutMS = 0;
		}
		return aSecond;
	}
	
	/**
	 * Time line position of the time 0 of the keys of the processors
	 */
	public long getKeysOriginMS() {
		return startTimeMS-keysShiftMS;
	}
	
	public Media getMedia() {
		return media;
	}
	
	public long getShiftTimeMS() {
		return shiftTimeMS;
	}

	public Vector<Clip> getTwinClips() {
		return twinClips;
	}
	
	public void linkTwin(Clip aClip) {
		if(aClip == this || twinClips.contains(aClip)) {
			return;
		}
		twinClips.add(aClip);
		aClip.twinClips.add(this);
	}
	
	public void unlinkTwins() {
		for(Clip aC : new Vector<Clip>(twinClips)) {
			aC.twinClips.remove(this);
		}
		twinClips.clear();
	}

	public Vector<Processor> getProcessors() {
		return processors;
	}

	public void setProcessors(Vector<Processor> processors) {
		this.processors = processors;
	}

	public long getStartTimeMS() {
		return startTimeMS;
	}

	public void setStartTimeMS(long aStartTimeMS) {
		startTimeMS = aStartTimeMS;
		if(startTimeMS < 0) {
			startTimeMS = 0;
		}
		for(Clip aC : twinClips) {
			aC.startTimeMS = startTimeMS;
		}
	}
	
	@Override
	public long getMaxTimePosMS() {
		return startTimeMS+durationMS;
	}

	public String getStartClock() {
		return StringUtils.time2Clock(startTimeMS);
	}
	
	public long setStartClock(String aClock) {
		long aStartTimeMS = StringUtils.clock2Time(aClock);
		if(aStartTimeMS >= 0) {
			setStartTimeMS(aStartTimeMS);
			return aStartTimeMS;
		}
		return -1;
	}

	public String getShiftClock() {
		return StringUtils.time2Clock(shiftTimeMS);
	}
	
	public long setShiftClock(String aClock) {
		long aShiftTimeMS = StringUtils.clock2Time(aClock);
		if(aShiftTimeMS >= 0 && aShiftTimeMS-shiftTimeMS < durationMS) {
			durationMS -= aShiftTimeMS-shiftTimeMS;
			shiftTimeMS = aShiftTimeMS;
			for(Clip aC : twinClips) {
				aC.durationMS = durationMS;
				aC.shiftTimeMS = shiftTimeMS;
			}
			return aShiftTimeMS;
		}
		return -1;
	}

	
	
	@Override
	public void setDurationMS(long aDurationMS) {
		super.setDurationMS(aDurationMS);
		for(Clip aC : twinClips) {
			aC.durationMS = durationMS;
		}
	}

	/**
	 * Duration of the media when it limits the clip (video, sound), -1 when it has no limit (still image, color...)
	 */
	public long getMediaLimitMS() {
		if((media instanceof StereoMovie || media instanceof AudioMedia) && media.getDurationMS() > 0) {
			return media.getDurationMS();
		}
		return -1;
	}

	/**
	 * Move the start of the clip on the time line, keeping its end: the play position in the media
	 * moves with it (not before the beginning of the media)
	 */
	public void trimStart(long aStartTimeMS) {
		long aDelta = aStartTimeMS-startTimeMS;
		boolean aLimited = getMediaLimitMS() >= 0;
		if(aLimited && shiftTimeMS+aDelta < 0) {
			aDelta = -shiftTimeMS;
		}
		if(startTimeMS+aDelta < 0) {
			aDelta = -startTimeMS;
		}
		if(durationMS-aDelta < MIN_DURATION_MS) {
			aDelta = durationMS-MIN_DURATION_MS;
		}
		if(aDelta == 0) {
			return;
		}
		startTimeMS += aDelta;
		durationMS -= aDelta;
		if(aLimited) {
			shiftTimeMS += aDelta;
		}
		for(Clip aC : twinClips) {
			aC.startTimeMS = startTimeMS;
			aC.durationMS = durationMS;
			aC.shiftTimeMS = shiftTimeMS;
		}
	}

	/**
	 * Move the end of the clip on the time line, keeping its start (not after the end of the media)
	 */
	public void trimEnd(long aEndTimeMS) {
		long aDuration = aEndTimeMS-startTimeMS;
		long aLimit = getMediaLimitMS();
		if(aLimit >= 0 && shiftTimeMS+aDuration > aLimit) {
			aDuration = aLimit-shiftTimeMS;
		}
		if(aDuration < MIN_DURATION_MS) {
			aDuration = MIN_DURATION_MS;
		}
		setDurationMS(aDuration);
	}

	public String getEndClock() {
		return StringUtils.time2Clock(startTimeMS+durationMS);
	}
	
	public long setEndClock(String aClock) {
		long aEndTimeMS = StringUtils.clock2Time(aClock);
		if(aEndTimeMS >= 0 && aEndTimeMS > startTimeMS) {
			setDurationMS(aEndTimeMS - startTimeMS);
			return durationMS;
		}
		return -1;
	}

	public String getFadeInClock() {
		return StringUtils.time2Clock(fadeInMS);
	}
	
	public long setFadeInClock(String aClock) {
		long aFadeInTimeMS = StringUtils.clock2Time(aClock);
		if(aFadeInTimeMS >= 0) {
			fadeInMS = aFadeInTimeMS;
			for(Clip aC : twinClips) {
				aC.fadeInMS = fadeInMS;
			}
			return fadeInMS;
		}
		return -1;
	}

	public String getFadeOutClock() {
		return StringUtils.time2Clock(fadeOutMS);
	}
	
	public long setFadeOutClock(String aClock) {
		long aFadeOutTimeMS = StringUtils.clock2Time(aClock);
		if(aFadeOutTimeMS >= 0) {
			fadeOutMS = aFadeOutTimeMS;
			for(Clip aC : twinClips) {
				aC.fadeOutMS = fadeOutMS;
			}
			return fadeOutMS;
		}
		return -1;
	}

	public Clip addPairedClip(Media aMedia,int aPairedType) {
		Clip aC = new Clip(tde, aMedia);
		aC.pairedType = aPairedType;
		pairedClips.add(aC);
		return aC;
	}
	
	public void removePairedClip(Clip aClip) {
		pairedClips.remove(aClip);
	}

	/**
	 * Same rotation and anamorphosis as aSource for the media of this (paired) clip
	 */
	void followOrientation(Media aSource) {
		if(media == null || aSource == null) {
			return;
		}
		if(!media.rotateL.equals(aSource.rotateL) || !media.rotateR.equals(aSource.rotateR) || !media.anamorphW.equals(aSource.anamorphW)) {
			media.rotateL = aSource.rotateL;
			media.rotateR = aSource.rotateR;
			media.anamorphW = aSource.anamorphW;
			media.rotate();
		}
	}

	public void removePairedClip(Media aMedia,int aPairedType) {
		for(Clip aC : pairedClips) {
			if(aC.media == aMedia && aC.pairedType == aPairedType) {
				//Suppose to be only once in the list
				pairedClips.remove(aC);
				return;
			}
		}
	}
	
	public StereoAligner getFinalAligner() {
		for(int p = processors.size()-1;p>=0;p--) {
			Processor aP = processors.elementAt(p);
			if(aP instanceof Placer && !((Placer)aP).isZero()
					|| aP instanceof Scripter && !((Scripter)aP).isZero()) {
				//Need a new aligner
				StereoAligner aSA = new StereoAligner(tde,this);
				processors.add(aSA);
				return aSA;
			}
			if(aP instanceof StereoAligner) {
				return (StereoAligner)aP;
			}
		}
		//Need a new aligner
		StereoAligner aSA = new StereoAligner(tde,this);
		processors.add(aSA);
		return aSA;
	}
	
	@Override
	public void reBuild(int aProcessingMode,long aTime) {
		if(aTime < startTimeMS || aTime >= startTimeMS+durationMS) {
			//Nothing to do
			return;
		}
		workLeft = workRight = finalLeft = finalRight = null;
		media.reBuild(aProcessingMode, aTime-startTimeMS+shiftTimeMS);
		for(Clip aC : pairedClips) {
			if(aC.pairedType == 1) {
				if(aC.followsOrientation) {
					aC.followOrientation(media);
				}
				aC.workLeft = aC.workRight = aC.finalLeft = aC.finalRight = null;
				aC.media.reBuild(aProcessingMode, aTime-startTimeMS+shiftTimeMS);
			}
		}
		for(Processor aP : processors) {
			if(aP.isActive) {
				aP.process2Awt(this,aProcessingMode,aTime);
				//Also process paired clips
				for(Clip aC : pairedClips) {
					if(aC.pairedType == 1) {
						//Depthmap
						if(";Placer;StereoAligner;".indexOf(";"+aP.getClassName()+";") >= 0) {
							aP.process2Awt(aC,aProcessingMode,aTime);
						}
					}
				}
			}
		}
		finalLeft = getWorkLeft(aProcessingMode, aTime);
		finalRight = getWorkRight(aProcessingMode, aTime);
	}

	@Override
	public void setWorkLeft(BufferedImage aBI) {
		workLeft = aBI;
	}

	@Override
	public void setWorkRight(BufferedImage aBI) {
		workRight = aBI;
	}

	@Override
	public BufferedImage getWorkLeft(int aProcessingMode,long aTime) {
		if(workLeft != null) {
			return workLeft;
		}
		return workLeft = media.getWorkLeft(aProcessingMode,aTime);
	}

	@Override
	public BufferedImage getWorkRight(int aProcessingMode,long aTime) {
		if(workRight != null) {
			return workRight;
		}
		return workRight = media.getWorkRight(aProcessingMode,aTime);
	}

	@Override
	public BufferedImage getFinalLeft(int aProcessingMode,long aTime) {
		if(aTime < startTimeMS || aTime >= startTimeMS+durationMS) {
			//Nothing to do
			return null;
		}
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
		if(aTime < startTimeMS || aTime >= startTimeMS+durationMS) {
			//Nothing to do
			return null;
		}
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

	@Override
	public float getAlpha(long aTime) {
		if(aTime < startTimeMS || aTime >= startTimeMS+durationMS) {
			//Not in the scope
			return -1.0f;
		}
		float aFadeIn = 1.0f;
		if(fadeInMS > 0 && aTime-startTimeMS < fadeInMS) {
			aFadeIn = (aTime-startTimeMS)/(float)fadeInMS;
		}
		float aFadeOut = 1.0f;
		if(fadeOutMS > 0 && startTimeMS+durationMS-aTime < fadeOutMS) {
			aFadeOut = (startTimeMS+durationMS-aTime)/(float)fadeOutMS;
		}
		return Math.min(aFadeIn, aFadeOut);
	}

	@Override
	public Rectangle drawSwt(GC aGC, int aX, int aY, Rectangle aClippingR) {
		int aStartPos = (int)(aX+startTimeMS * tde.timeLineStack.pixPerMS);
		int aDurationWidth = (int)(durationMS * tde.timeLineStack.pixPerMS);
		bounds = new Rectangle(aStartPos, aY, aDurationWidth, tde.config.thumbH);
		Rectangle aClippingNew = new Rectangle(Math.max(aClippingR.x, bounds.x), aClippingR.y
				, bounds.width-(Math.max(aClippingR.x, bounds.x) - bounds.x), bounds.height);
		if(aClippingNew.width < 0) {
			aClippingNew.width = 0;
		}
		aGC.setClipping(aClippingNew);

		aGC.setBackground(selState == 0 ? tde.gui.colorsSwt.WHITE : tde.gui.colorsSwt.BLUE_L60);
		aGC.setForeground(selState == 0 ? tde.gui.colorsSwt.CUBAIX_BLUE : tde.gui.colorsSwt.CUBAIX_PINK);
		aGC.fillRectangle(bounds);

		int aW = 0;
		while(aW < aDurationWidth) {
			if(aStartPos+aW < aClippingR.x+aClippingR.width) {
				aGC.drawImage(media.getThumbSwt(), aStartPos+aW, aY);
			}
			aW+=media.getThumbSwt().getBounds().width;
		}
		
		if(fadeInMS > 0) {
			aGC.setForeground(tde.gui.colorsSwt.PINK_L92);
			aGC.setLineWidth(2);
			aGC.drawLine(bounds.x, bounds.y+bounds.height
					, (int)(bounds.x+fadeInMS*tde.timeLineStack.pixPerMS), bounds.y);
		}
		if(fadeOutMS > 0) {
			aGC.setForeground(tde.gui.colorsSwt.PINK_L92);
			aGC.setLineWidth(2);
			aGC.drawLine(bounds.x+bounds.width, bounds.y+bounds.height
					, (int)(bounds.x+bounds.width-fadeOutMS*tde.timeLineStack.pixPerMS), bounds.y);
		}
		
		aGC.setForeground(selState == 0 ? tde.gui.colorsSwt.GRAY : tde.gui.colorsSwt.BLUE_L60);
		aGC.setLineWidth(selState == 0 ? 1 : 3);
		aGC.drawRectangle(bounds.x+(selState == 0 ? 0 : 2)-1,bounds.y+(selState == 0 ? 0 : 2)-1
				,bounds.width-(selState == 0 ? 1 : 4)+1,bounds.height-(selState == 0 ? 1 : 4)+1);
		aGC.setLineWidth(1);

		return bounds;
	}

	@Override
	public Rectangle drawAwt(Graphics2D aGC, int aX, int aY, Rectangle aClippingR) {
		return drawAwt(aGC, aX, aY, aClippingR, 0, false);
	}
	
	/**
	 * Clip of a video time line: film strip, and its sound below (aSoundBarH > 0)
	 */
	public Rectangle drawAwt(Graphics2D aGC, int aX, int aY, Rectangle aClippingR,int aSoundBarH,boolean aLineMuted) {
		int aStartPos = (int)(aX+startTimeMS * tde.timeLineStack.pixPerMS);
		int aDurationWidth = (int)(durationMS * tde.timeLineStack.pixPerMS);
		bounds = new Rectangle(aStartPos, aY, aDurationWidth, tde.config.thumbH+aSoundBarH);
		Rectangle aClippingNew = new Rectangle(Math.max(aClippingR.x, bounds.x)
				, aClippingR.y
				, bounds.width-(Math.max(aClippingR.x, bounds.x) - bounds.x)
				, bounds.height);
		if(aClippingNew.width < 0) {
			aClippingNew.width = 0;
		}
		aGC.setClip(aClippingNew.x,aClippingNew.y,aClippingNew.width,aClippingNew.height);

		aGC.setBackground(selState == 0 ? tde.gui.colorsAwt.WHITE : tde.gui.colorsAwt.BLUE_L60);
		aGC.setColor(selState == 0 ? tde.gui.colorsAwt.CUBAIX_BLUE : tde.gui.colorsAwt.CUBAIX_PINK);
		aGC.fillRect(bounds.x,bounds.y,bounds.width,bounds.height);

		//Film strip: each image shows the frame at its own position
		int aW = 0;
		int aRight = Math.min(aStartPos+aDurationWidth, aClippingR.x+aClippingR.width);
		while(aStartPos+aW < aRight) {
			BufferedImage aTile = media.getThumbAwt();
			int aTileW = aTile == null ? tde.config.thumbW : Math.max(1, aTile.getWidth());
			if(aStartPos+aW+aTileW >= aClippingR.x) {
				//Time in the media at the middle of the tile
				long aMediaTime = shiftTimeMS+(long)((aW+aTileW/2)/tde.timeLineStack.pixPerMS);
				BufferedImage aBI = media.getThumbAwtAt(aMediaTime);
				if(aBI != null) {
					aGC.drawImage(aBI, aStartPos+aW, aY,null);
				}
			}
			aW += aTileW;
		}
		
		if(aSoundBarH > 0) {
			//Sound of the clip, below the images
			Rectangle aBar = new Rectangle(bounds.x, aY+tde.config.thumbH, bounds.width, aSoundBarH);
			aGC.setColor(selState == 0 ? tde.gui.colorsAwt.PINK_L92 : tde.gui.colorsAwt.BLUE_L60);
			aGC.fillRect(aBar.x, aBar.y, aBar.width, aBar.height);
			boolean aHeard = soundOn && !aLineMuted;
			aGC.setColor(aHeard ? (selState == 0 ? tde.gui.colorsAwt.CUBAIX_BLUE : tde.gui.colorsAwt.WHITE) : tde.gui.colorsAwt.GRAY);
			drawWave(aGC, aStartPos, aBar, aClippingR);
		}
		
		if(fadeInMS > 0) {
			aGC.setColor(tde.gui.colorsAwt.PINK_L92);
			aGC.setStroke(new BasicStroke(2));
			aGC.drawLine(bounds.x, bounds.y+bounds.height
					, (int)(bounds.x+fadeInMS*tde.timeLineStack.pixPerMS), bounds.y);
		}
		if(fadeOutMS > 0) {
			aGC.setColor(tde.gui.colorsAwt.PINK_L92);
			aGC.setStroke(new BasicStroke(2));
			aGC.drawLine(bounds.x+bounds.width, bounds.y+bounds.height
					, (int)(bounds.x+bounds.width-fadeOutMS*tde.timeLineStack.pixPerMS), bounds.y);
		}
		
		aGC.setColor(selState == 0 ? tde.gui.colorsAwt.GRAY : tde.gui.colorsAwt.BLUE_L60);
		aGC.setStroke(new BasicStroke(selState == 0 ? 1 : 3));
		aGC.drawRect(bounds.x+(selState == 0 ? 0 : 2)-1,bounds.y+(selState == 0 ? 0 : 2)-1
				,bounds.width-(selState == 0 ? 1 : 4)+1,bounds.height-(selState == 0 ? 1 : 4)+1);
		aGC.setStroke(new BasicStroke(1));

		return bounds;
	}

	@Override
	public boolean selectWidget(int aX, int aY) {
		return false;
	}

	@Override
	public void select(int aX, int aY, boolean aOutUnselect) {
		if(aX > 0 && aY > 0 //Negative values are used to unselect all (can't click outside the panel)
				&& bounds != null && bounds.contains(aX, aY)) {
			selState = Drawable.THUMBSELSTATE_SELECTED;
			tde.selected.clips.add(this);
		}
		else if(aOutUnselect) {
			selState = Drawable.THUMBSELSTATE_NONE;
		}
	}
	
	/**
	 * Fade in/out level (0..1) at a time on the time line, 1 outside the fades
	 */
	double getFade(double aTime) {
		double aFade = 1.0;
		if(fadeInMS > 0 && aTime-startTimeMS < fadeInMS) {
			aFade = Math.max(0.0, (aTime-startTimeMS)/fadeInMS);
		}
		if(fadeOutMS > 0 && startTimeMS+durationMS-aTime < fadeOutMS) {
			aFade = Math.min(aFade, Math.max(0.0, (startTimeMS+durationMS-aTime)/fadeOutMS));
		}
		return aFade;
	}
	
	/**
	 * Add the sound of this clip to aOut (aNbFrames interleaved stereo samples,
	 * the first one at frame aStartFrame of the time line), with its processors and fades
	 */
	public void mixAudio(long aStartFrame,float[] aOut,int aNbFrames) {
		if(media == null) {
			return;
		}
		AudioReader aAR = getSoundReader();
		if(aAR == null) {
			return;
		}
		long aRate = AudioReader.RATE;
		long aClipStartF = startTimeMS*aRate/1000;
		long aClipEndF = (startTimeMS+durationMS)*aRate/1000;
		long aFrom = Math.max(aStartFrame, aClipStartF);
		long aTo = Math.min(aStartFrame+aNbFrames, aClipEndF);
		if(aTo <= aFrom) {
			return;
		}
		int aN = (int)(aTo-aFrom);
		float[] aBuf = new float[aN*AudioReader.CHANNELS];
		aAR.read(aFrom-aClipStartF+shiftTimeMS*aRate/1000, aBuf, aN);
		double aFrameMS = 1000.0/aRate;
		long aTimeMS = aFrom*1000/aRate;
		for(Processor aP : new Vector<Processor>(processors)) {
			if(aP.isActive) {
				aP.processAudio(this, aBuf, aN, aTimeMS, aFrameMS);
			}
		}
		int aOff = (int)(aFrom-aStartFrame)*AudioReader.CHANNELS;
		for(int f = 0;f < aN;f++) {
			float aFade = (float)getFade((aFrom+f)*aFrameMS);
			aOut[aOff+2*f] += aBuf[2*f]*aFade;
			aOut[aOff+2*f+1] += aBuf[2*f+1]*aFade;
		}
	}
	
	/**
	 * Waveform of the sound of the clip in aArea (current color), with the fades
	 */
	void drawWave(Graphics2D aGC,int aStartPos,Rectangle aArea,Rectangle aClippingR) {
		AudioReader aAR = getSoundReader();
		int aMid = aArea.y+aArea.height/2;
		if(aAR != null) {
			int aXFrom = Math.max(aArea.x, aClippingR.x);
			int aXTo = Math.min(aArea.x+aArea.width, aClippingR.x+aClippingR.width);
			for(int x = aXFrom;x < aXTo;x++) {
				double aT0 = startTimeMS+(x-aStartPos)/tde.timeLineStack.pixPerMS;
				double aT1 = startTimeMS+(x+1-aStartPos)/tde.timeLineStack.pixPerMS;
				float aPeak = aAR.getPeak((long)(aT0-startTimeMS+shiftTimeMS), (long)(aT1-startTimeMS+shiftTimeMS));
				if(aPeak <= 0) {
					continue;
				}
				//Square root: quiet sounds stay visible
				aPeak = (float)(Math.sqrt(aPeak)*getFade(aT0));
				int aPH = (int)(aPeak*(aArea.height/2-1));
				aGC.drawLine(x, aMid-aPH, x, aMid+aPH);
			}
		}
		aGC.drawLine(aArea.x, aMid, aArea.x+aArea.width, aMid);
	}
	
	/**
	 * True if the media of the clip has a sound track (as far as known now)
	 */
	public boolean hasSound() {
		AudioReader aAR = getSoundReader();
		return aAR != null && aAR.hasAudio(0);
	}

	/**
	 * Sound of the clip: its media's, from the right video if soundRight (see Media.getAudioReader(boolean))
	 */
	public AudioReader getSoundReader() {
		return media == null ? null : media.getAudioReader(soundRight);
	}
	
	/**
	 * Draw the clip as a sound (waveform), for the audio time lines
	 */
	public Rectangle drawAudioAwt(Graphics2D aGC, int aX, int aY, Rectangle aClippingR) {
		int aStartPos = (int)(aX+startTimeMS * tde.timeLineStack.pixPerMS);
		int aDurationWidth = (int)(durationMS * tde.timeLineStack.pixPerMS);
		bounds = new Rectangle(aStartPos, aY, aDurationWidth, tde.config.thumbH);
		Rectangle aClippingNew = new Rectangle(Math.max(aClippingR.x, bounds.x)
				, aClippingR.y
				, bounds.width-(Math.max(aClippingR.x, bounds.x) - bounds.x)
				, bounds.height);
		if(aClippingNew.width < 0) {
			aClippingNew.width = 0;
		}
		aGC.setClip(aClippingNew.x,aClippingNew.y,aClippingNew.width,aClippingNew.height);

		aGC.setColor(selState == 0 ? tde.gui.colorsAwt.PINK_L92 : tde.gui.colorsAwt.BLUE_L60);
		aGC.fillRect(bounds.x,bounds.y,bounds.width,bounds.height);

		//Waveform
		aGC.setColor(selState == 0 ? tde.gui.colorsAwt.CUBAIX_BLUE : tde.gui.colorsAwt.WHITE);
		drawWave(aGC, aStartPos, bounds, aClippingR);
		
		aGC.setFont(tde.gui.fontsAwt.roboto);
		aGC.drawString(fileName == null ? "" : fileName, bounds.x+3, bounds.y+aGC.getFontMetrics().getAscent());
		
		if(fadeInMS > 0) {
			aGC.setColor(tde.gui.colorsAwt.CUBAIX_PINK);
			aGC.setStroke(new BasicStroke(2));
			aGC.drawLine(bounds.x, bounds.y+bounds.height
					, (int)(bounds.x+fadeInMS*tde.timeLineStack.pixPerMS), bounds.y);
		}
		if(fadeOutMS > 0) {
			aGC.setColor(tde.gui.colorsAwt.CUBAIX_PINK);
			aGC.setStroke(new BasicStroke(2));
			aGC.drawLine(bounds.x+bounds.width, bounds.y+bounds.height
					, (int)(bounds.x+bounds.width-fadeOutMS*tde.timeLineStack.pixPerMS), bounds.y);
		}

		aGC.setColor(selState == 0 ? tde.gui.colorsAwt.GRAY : tde.gui.colorsAwt.BLUE_L60);
		aGC.setStroke(new BasicStroke(selState == 0 ? 1 : 3));
		aGC.drawRect(bounds.x+(selState == 0 ? 0 : 2)-1,bounds.y+(selState == 0 ? 0 : 2)-1
				,bounds.width-(selState == 0 ? 1 : 4)+1,bounds.height-(selState == 0 ? 1 : 4)+1);
		aGC.setStroke(new BasicStroke(1));

		return bounds;
	}
	
	public static boolean isDepthProcessorName(String aName) {
		return "Depthmap".equalsIgnoreCase(aName) || "DepthmapStereo".equalsIgnoreCase(aName);
	}

	public static boolean isDepthProcessor(Processor aP) {
		return aP instanceof Depthmap || aP instanceof DepthmapStereo;
	}

	/**
	 * The depth map processor of the clip (Depthmap or DepthmapStereo), null if none
	 */
	public Processor getDepthProcessor() {
		for(Processor aP : processors) {
			if(isDepthProcessor(aP)) {
				return aP;
			}
		}
		return null;
	}

	/**
	 * The depth map processor builds the views from the media: always the first one after the
	 * mandatory TimeController (and Shapes), and unmovable (see Processor.isUnmovable)
	 */
	void placeDepthProcessor(Processor aP) {
		processors.remove(aP);
		int aPos = 0;
		while(aPos < processors.size()
				&& (processors.elementAt(aPos) instanceof TimeController
						|| processors.elementAt(aPos) instanceof com.cubaix.TDenlive.processors.Objects3D
						|| (processors.elementAt(aPos) instanceof Shapes && !(processors.elementAt(aPos) instanceof Subtitles)))) {
			aPos++;
		}
		processors.add(aPos, aP);
	}

	/**
	 * @return the processor added, null if none (unknown name, or a second depth map processor)
	 */
	public Processor addProcessor(String aProcessorName) {
		boolean aIsDepth = isDepthProcessorName(aProcessorName);
		if(aIsDepth && getDepthProcessor() != null) {
			//Only one, it can't be replaced
			return null;
		}
		int aNb = processors.size();
		if("StereoAligner".equals(aProcessorName)) {
			processors.add(new StereoAligner(tde, this));
		}
		if("ColorAdapter".equals(aProcessorName)) {
			processors.add(new ColorAdapter(tde, this));
		}
		if("HueSaturation".equals(aProcessorName)) {
//			processors.add(new HueSaturation(tde, this));
		}
		if("BrightnessContrast".equals(aProcessorName)) {
			processors.add(new BrightnessContrast(tde, this));
		}
		if("HueSaturation".equals(aProcessorName)) {
			processors.add(new HueSaturation(tde, this));
		}
		if("Depthmap".equalsIgnoreCase(aProcessorName)) {
			processors.add(new Depthmap(tde,this));
		}
		if("DepthmapStereo".equalsIgnoreCase(aProcessorName)) {
			processors.add(new DepthmapStereo(tde,this));
		}
		if("Placer".equals(aProcessorName)) {
			processors.add(new Placer(tde, this));
		}
		if("Scripter".equals(aProcessorName)) {
			processors.add(new Scripter(tde, this));
		}
		if("Cropper".equals(aProcessorName)) {
			processors.add(new Cropper(tde, this));
		}
		if("Transparency".equals(aProcessorName)) {
			processors.add(new Transparency(tde, this));
		}
		if("Chromakey".equals(aProcessorName)) {
			processors.add(new Chromakey(tde, this));
		}
		if("Framing".equals(aProcessorName)) {
			processors.add(new Framing(tde, this));
		}
		if("LensCorrection".equals(aProcessorName)) {
			processors.add(new LensCorrection(tde, this));
		}
		if("Volume".equals(aProcessorName)) {
			processors.add(new Volume(tde, this));
		}
		if("Subtitles".equals(aProcessorName)) {
			processors.add(new Subtitles(tde, this));
		}
		Processor aAdded = processors.size() > aNb ? processors.lastElement() : null;
		if(aAdded != null && aIsDepth) {
			placeDepthProcessor(aAdded);
		}
		tde.gui.processorGUI.setProcessors(processors);
		return aAdded;
	}

	public void upProcessor(Processor aP) {
		for(int p = 0;p < processors.size();p++) {
			if(processors.elementAt(p) == aP) {
				if(p <= 1 || processors.elementAt(p-1).isUnmovable){
					return;
				}
				processors.remove(p);
				processors.add(p-1, aP);
				return;
			}
		}
	}

	public void downProcessor(Processor aP) {
		for(int p = 0;p < processors.size();p++) {
			if(processors.elementAt(p) == aP) {
				if(p >= processors.size()-1) {
					return;
				}
				processors.remove(p);
				processors.add(p+1, aP);
				return;
			}
		}
	}

	public void removeProcessor(Processor aP) {
		for(int p = 0;p < processors.size();p++) {
			if(processors.elementAt(p) == aP) {
				processors.remove(p);
				aP.removed();
				return;
			}
		}
	}
}
