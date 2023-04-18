package com.cubaix.TDenlive.processors;

import java.io.BufferedWriter;
import java.util.Vector;

import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.graphics.Rectangle;

import com.cubaix.TDenlive.TDenlive;
import com.cubaix.TDenlive.medias.Clip;
import com.cubaix.TDenlive.medias.Media;
import com.cubaix.TDenlive.utils.StringUtils;
import com.cubaix.TDenlive.xml.XmlObject;

public class TimeController extends Processor {

	public TimeController(TDenlive aTDe, Media aTargetMedia) {
		super(aTDe, aTargetMedia);
		isExpended = true;
		isUndeletable = true;
		isUnmovable = true;
		isDeactivable = false;
	}

	@Override
	public String getClassName() {return "TimeController";}

	@Override
	public int openProject(Vector<XmlObject> aOs, int o) throws Exception {
		return o;
	}

	@Override
	public void saveProject(StringBuffer aSB) throws Exception {}

	@Override
	void setDescr() {
		name = tde.gui.lngs.get("Processor.TimeControler");
		icon = "clockRange.gif";
	}
	
	public String getStartClock() {
		return ((Clip)targetMedia).getStartClock();
	}
	
	public long setStartClock(String aC) {
		return ((Clip)targetMedia).setStartClock(aC);
	}

	public String getShiftClock() {
		return ((Clip)targetMedia).getShiftClock();
	}
	
	public long setShiftClock(String aC) {
		return ((Clip)targetMedia).setShiftClock(aC);
	}

	public String getDurationClock() {
		return targetMedia.getDurationClock();
	}

	public long setDurationClock(String aC) {
		return targetMedia.setDurationClock(aC);
	}

	public String getEndClock() {
		return ((Clip)targetMedia).getEndClock();
	}
	
	public long setEndClock(String aC) {
		return ((Clip)targetMedia).setEndClock(aC);
	}

	public String getFadeInClock() {
		return ((Clip)targetMedia).getFadeInClock();
	}
	
	public long setFadeInClock(String aC) {
		return ((Clip)targetMedia).setFadeInClock(aC);
	}

	public String getFadeOutClock() {
		return ((Clip)targetMedia).getFadeOutClock();
	}
	
	public long setFadeOutClock(String aC) {
		return ((Clip)targetMedia).setFadeOutClock(aC);
	}

	@Override
	public Image process2Swt(Media aMedia, int aProcessingMode, Rectangle aTargetSize, long aTime) {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public void process2Awt(Media aMedia, int aProcessingMode, long aTime) {
		// TODO Auto-generated method stub

	}

}
