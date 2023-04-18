package com.cubaix.TDenlive.medias;

import java.awt.Color;
import java.awt.Graphics;
import java.awt.image.BufferedImage;
import java.util.Vector;

import com.cubaix.TDenlive.TDenlive;
import com.cubaix.TDenlive.utils.ImageUtils;
import com.cubaix.TDenlive.xml.XmlObject;
import com.cubaix.TDenlive.xml.XmlTag;

public class ColorBck extends StereoImage {
	Color color = null;
	
	public ColorBck(TDenlive aTDe) {
		super(aTDe);
		color = tde.gui.colorsAwt.BLACK;
		isSizeAuto = true;
	}

	@Override
	public int openProject(Vector<XmlObject> aOs, int o) throws Exception {
		return super.openProject(aOs, o,"ColorBck");
	}

	@Override
	public int openProjectSub(Vector<XmlObject> aOs, int o) throws Exception {
		XmlObject aO = aOs.elementAt(o);
		if(aO instanceof XmlTag) {
			XmlTag aT = (XmlTag)aO;
			if("color".equalsIgnoreCase(aT.tagName)) {
				o++;
				color= new Color(Integer.parseInt(aOs.elementAt(o).text));
			}
		}
		return o;
	}

	@Override
	public void saveProject(StringBuffer aSB) throws Exception {
		super.saveProject(aSB,"ColorBck");
	}

	@Override
	void saveProjectSub(StringBuffer aSB) {
		aSB.append("			<color>" + color.getRGB() + "</color>\n");
	}

	public void setColor(int aR,int aG, int aB) {
		color = new Color(aR, aG, aB);
	}
	
	@Override
	public void reBuild(int aProcessingMode,long aTime) {
		if(workLeft != null && workRight != null
				&& lastProcessedRes == tde.config.processingResValues[aProcessingMode]
						&& lastProcessedHdr == tde.config.processingHdr[aProcessingMode]) {
			return;
		}
		workLeft = workRight = null;
		try {
			reloadOrig(aProcessingMode,aTime);
		}
		catch(Throwable t) {
			t.printStackTrace(System.err);
		}
		getWorkLeft(aProcessingMode,aTime);
		getWorkRight(aProcessingMode,aTime);
		lastProcessedRes = tde.config.processingResValues[aProcessingMode];
		lastProcessedHdr = tde.config.processingHdr[aProcessingMode];
	}

	@Override
	BufferedImage loadImage(String aPath, int aProcessingMode, long aTime, boolean aIsLeft) throws Exception {
		int aHeight = tde.config.processingResValues[aProcessingMode];
		int aWidth = (int)(aHeight*tde.config.origRatio);
		BufferedImage aBI = ImageUtils.createImage(aWidth, aHeight);
		Graphics aG = aBI.getGraphics();
		aG.setColor(color);
		aG.fillRect(0, 0, aWidth, aHeight);
		if(origWidth <= 0) {
			origWidth = aBI.getWidth();
			origHeight = aBI.getHeight();
		}
		return aBI;
	}

	@Override
	void reloadOrig(int aProcessingMode, long aTime) throws Exception {
		loadedLeft = loadedRight = loadImage(null, aProcessingMode, aTime, true);
		fileName = "Color";
		buildThumb();
	}

	@Override
	public void loadImageLR(String aPathL, String aPathR, int aProcessingMode, long aTime) throws Exception {
		reloadOrig(aProcessingMode, aTime);
	}

	@Override
	public void loadImageX(String aPath, int aProcessingMode, long aTime) throws Exception {
		reloadOrig(aProcessingMode, aTime);
	}

	@Override
	public void loadImageP(String aPath, int aProcessingMode, long aTime) throws Exception {
		reloadOrig(aProcessingMode, aTime);
	}
}
