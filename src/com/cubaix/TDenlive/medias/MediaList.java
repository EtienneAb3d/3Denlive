package com.cubaix.TDenlive.medias;

import java.awt.Graphics2D;
import java.util.Vector;

import org.eclipse.swt.graphics.GC;
import org.eclipse.swt.graphics.RGB;
import org.eclipse.swt.graphics.Rectangle;

import com.cubaix.TDenlive.TDConfig;
import com.cubaix.TDenlive.TDenlive;
import com.cubaix.TDenlive.GUI.Drawable;
import com.cubaix.TDenlive.xml.XmlObject;
import com.cubaix.TDenlive.xml.XmlTag;

public class MediaList extends Drawable {
	static final String VIDEO_FILE_RE = ".*[.](mpg|mpeg|mp4|avi|mov|mkv|flv|vob|ogv|ts|m2s|m2ts|divx|dvx|wmv)";
	static final String SVG_FILE_RE = ".*[.]svg";
	public Vector<Media> mediaList = new Vector<Media>();
	
	public MediaList(TDenlive aTDe) {
		super(aTDe);
	}
	
	public int openProject(Vector<XmlObject> aOs,int o) throws Exception{
		for(;o < aOs.size();o++) {
			XmlObject aO = aOs.elementAt(o);
			if(aO instanceof XmlTag) {
				XmlTag aT = (XmlTag)aO;
				if("/MediaList".equalsIgnoreCase(aT.tagName)) {
					return o;
				}
				if("ColorBck".equalsIgnoreCase(aT.tagName)) {
					ColorBck aCB = new ColorBck(tde);
					mediaList.add(aCB);
					o = aCB.openProject(aOs, o);
					try {
						aCB.reloadOrig(TDConfig.PROCESSING_MODE_RENDER, -1);
					}
					catch(Throwable t) {
						t.printStackTrace(System.err);
					}
				}
				if("StereoImage".equalsIgnoreCase(aT.tagName)) {
					StereoImage aSI = new StereoImage(tde);
					mediaList.add(aSI);
					o = aSI.openProject(aOs, o);
				}
				if("StereoMovie".equalsIgnoreCase(aT.tagName)) {
					StereoMovie aSM = new StereoMovie(tde);
					mediaList.add(aSM);
					o = aSM.openProject(aOs, o);
				}
				if("StereoSvg".equalsIgnoreCase(aT.tagName)) {
					StereoSvg aSG = new StereoSvg(tde);
					mediaList.add(aSG);
					o = aSG.openProject(aOs, o);
				}
			}
		}
		return o;
	}
	
	public void saveProject(StringBuffer aSB) throws Exception {
		for(Media aM : mediaList) {
			aM.saveProject(aSB);
		}
	}

	public Media getMediaByUID(long aUID) {
		for(Media aM : mediaList) {
			if(aM.UID == aUID) {
				return aM;
			}
		}
		return null;
	}
	
	public Media getSelectedMedias(boolean aOrFirst) {
		Media aSelected = aOrFirst && mediaList.size() > 0 ? mediaList.elementAt(0) : null;
		for(Media aM : mediaList) {
			if(aM.selState > 0) {
				return aM;
			}
		}
		return aSelected;
	}
	
	public void loadImageLR(String aPathL,String aPathR,int aProcessingMode,long aTime) throws Exception {
		StereoImage aSI = null;
		if(aPathL.toLowerCase().matches(VIDEO_FILE_RE)) {
			aSI = new StereoMovie(tde);
		}
		else if(aPathL.toLowerCase().matches(SVG_FILE_RE)) {
			aSI = new StereoSvg(tde);
		}
		else {
			aSI = new StereoImage(tde);
		}
		aSI.loadImageLR(aPathL,aPathR,aProcessingMode,aTime);
		mediaList.add(aSI);
	}

	public void loadImageX(String aPath,int aProcessingMode,long aTime) throws Exception {
		StereoImage aSI = null;
		if(aPath.toLowerCase().matches(VIDEO_FILE_RE)) {
			aSI = new StereoMovie(tde);
		}
		else if(aPath.toLowerCase().matches(SVG_FILE_RE)) {
			aSI = new StereoSvg(tde);
		}
		else {
			aSI = new StereoImage(tde);
		}
		aSI.loadImageX(aPath,aProcessingMode,aTime);
		mediaList.add(aSI);
	}

	public void loadImageP(String aPath,int aProcessingMode,long aTime) throws Exception {
		StereoImage aSI = null;
		if(aPath.toLowerCase().matches(VIDEO_FILE_RE)) {
			aSI = new StereoMovie(tde);
		}
		else if(aPath.toLowerCase().matches(SVG_FILE_RE)) {
			aSI = new StereoSvg(tde);
		}
		else {
			aSI = new StereoImage(tde);
		}
		aSI.loadImageP(aPath,aProcessingMode,aTime);
		mediaList.add(aSI);
	}

	public void loadImageMPO(String aPath) throws Exception {
		StereoImage aSI = null;
		if(aPath.toLowerCase().matches(VIDEO_FILE_RE)) {
			aSI = new StereoMovie(tde);
		}
		else {
			aSI = new StereoImage(tde);
		}
		aSI.loadImageMPO(aPath);
		mediaList.add(aSI);
	}
	
	public void createColorBck(int aR,int aG, int aB,int aProcessingMode) {
		ColorBck aCB = new ColorBck(tde);
		aCB.setColor(aR,aG,aB);
		try {
			aCB.reloadOrig(aProcessingMode, -1);
		}
		catch(Throwable t) {
			t.printStackTrace(System.err);
		}
		mediaList.add(aCB);
	}

	public Rectangle drawSwt(GC aGC,int aX, int aY,Rectangle aClippingR) {
		int aW = 0;
		int aH = 0;
		
		synchronized (mediaList) {
			for(Media aM : mediaList) {
				Rectangle aR = aM.drawSwt(aGC, aX, aY+aH,aClippingR);
				aH += aR.height;
				if(aW < aR.width) {
					aW = aR.width;
				}
				aGC.setBackground(tde.gui.colorsSwt.WHITE);
				aGC.fillRectangle(0,aY+aH,aClippingR.width,3);
				aGC.setBackground(tde.gui.colorsSwt.GRAY);
				aGC.fillRectangle(3,aY+aH+1,aClippingR.width-6,1);
				aH += 3;
			}
		}
		return bounds = new Rectangle(aX, aY, aW, aH);
	}

	@Override
	public Rectangle drawAwt(Graphics2D aGC, int aX, int aY, Rectangle aClippingR) {
		return null;
	}

	@Override
	public boolean selectWidget(int aX, int aY) {
		return false;
	}

	@Override
	public void select(int aX, int aY,boolean aOutUnselect) {
		tde.selected.medias.clear();
		for(Media aM : mediaList) {
			aM.select(aX, aY,aOutUnselect);
		}
	}
}
