package com.cubaix.TDenlive.processors;

import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.util.Vector;

import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.graphics.Rectangle;

import com.cubaix.TDenlive.TDConfig;
import com.cubaix.TDenlive.TDenlive;
import com.cubaix.TDenlive.medias.Media;
import com.cubaix.TDenlive.utils.ImageUtils;
import com.cubaix.TDenlive.xml.XmlObject;

public abstract class Mixer extends Processor {
	double zoom = 1.0;
	double x = 0.0;
	double y = 0.0;
	double lastW = 16;
	double lastH = 9;
	double lastSizedW = 16;
	double lastSizedH = 9;
	
	public Mixer(TDenlive aTDe, Media aTargetMedia) {
		super(aTDe, aTargetMedia);
	}

	public double getZoom() {
		return zoom;
	}

	public void setZoom(double zoom) {
		this.zoom = zoom;
	}

	public double getX() {
		return x;
	}

	public void setX(double x) {
		this.x = x;
	}

	public double getY() {
		return y;
	}

	public void setY(double y) {
		this.y = y;
	}

	public double getLastW() {
		return lastW;
	}

	public double getLastH() {
		return lastH;
	}

	public double getLastSizedW() {
		return lastSizedW;
	}

	public double getLastSizedH() {
		return lastSizedH;
	}

	public void resize(BufferedImage aBI,BufferedImage aSizedBI,int aProcessingMode
			,int aW,int aH,int aSizedW,int aSizedH) {
		lastW = aW;
		lastH = aH;
		lastSizedW = aSizedW;
		lastSizedH = aSizedH;
		Graphics2D aGL2 = (Graphics2D)aSizedBI.getGraphics();
		if(!tde.config.fastMode || aProcessingMode == TDConfig.PROCESSING_MODE_RENDER) {
			aGL2.setRenderingHint(RenderingHints.KEY_ANTIALIASING,RenderingHints.VALUE_ANTIALIAS_ON);
			aGL2.setRenderingHint(RenderingHints.KEY_RENDERING,RenderingHints.VALUE_RENDER_QUALITY);
		}
		aGL2.drawImage(aBI,0,0,aSizedW,aSizedH
				,(int)(x*aW+aW*0.5*(1.0-1.0/zoom)),(int)(y*aH+aH*0.5*(1.0-1.0/zoom))
				,(int)(x*aW+aW*0.5*(1.0-1.0/zoom)+aW/zoom), (int)(y*aH+aH*0.5*(1.0-1.0/zoom)+aH/zoom),null);
		aGL2.dispose();
	}
}
