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
import com.cubaix.TDenlive.processors.StereoAligner;
import com.cubaix.TDenlive.processors.TimeController;
import com.cubaix.TDenlive.processors.Transparency;
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
	Vector<Clip> pairedClips = new Vector<Clip>();
	public int pairedType = -1;//-1 = not a paired, 0 = unknown, 1 = depthmap
	Vector<Clip> twinClips = new Vector<Clip>();

	public Clip(TDenlive aTDe,Media aMedia) {
		super(aTDe);
		media = aMedia;
		durationMS = media != null ? media.durationMS : 5*1000;
		fileName = media != null ? media.fileName:"??";
		processors.add(new TimeController(tde,this));
		processors.add(new StereoAligner(tde,this));
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
				if("TwinClipUID".equalsIgnoreCase(aT.tagName)) {
					o++;
					long aUID = Long.parseLong(aOs.elementAt(o).text);
					Clip aC = tde.timeLineStack.getClipByUID(aUID);
					if(aC != null) {//If not found, will be set when the twin clip will be loaded
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
					return o;
				}
				if("StereoAligner".equalsIgnoreCase(aT.tagName)) {
					StereoAligner aSA = new StereoAligner(tde,this);
					processors.add(aSA);
					o = aSA.openProject(aOs, o);
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
		aSB.append("						<fadeInMS>" + fadeInMS + "</fadeInMS>\n");
		aSB.append("						<fadeOutMS>" + fadeOutMS + "</fadeOutMS>\n");
		aSB.append("					</Media>\n");
		aSB.append("					<Processors>\n");
		for(Processor aP : processors) {
			aP.saveProject(aSB);
		}
		aSB.append("					</Processors>\n");
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
		}
		catch(Throwable t) {
			
		}
		return aClip;
	}
	
	public Media getMedia() {
		return media;
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

		aGC.setBackground(selState == 0 ? tde.gui.colorsAwt.WHITE : tde.gui.colorsAwt.BLUE_L60);
		aGC.setColor(selState == 0 ? tde.gui.colorsAwt.CUBAIX_BLUE : tde.gui.colorsAwt.CUBAIX_PINK);
		aGC.fillRect(bounds.x,bounds.y,bounds.width,bounds.height);

		int aW = 0;
		while(aW < aDurationWidth) {
			if(aStartPos+aW < aClippingR.x+aClippingR.width) {
				aGC.drawImage(media.getThumbAwt(), aStartPos+aW, aY,null);
			}
			aW+=media.getThumbSwt().getBounds().width;
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
	
	public void addProcessor(String aProcessorName) {
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
		tde.gui.processorGUI.setProcessors(processors);
	}

	public void upProcessor(Processor aP) {
		for(int p = 0;p < processors.size();p++) {
			if(processors.elementAt(p) == aP) {
				if(p <= 1){
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
				return;
			}
		}
	}
}
