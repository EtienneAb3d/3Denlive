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
	//Side of the squares of the checkerboard behind the work preview (pixels of the screen)
	static final int CHECKER_CELL = 5;
	
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

	/**
	 * Number of views of the frame side by side in the mixed image
	 */
	public int getNbViews() {
		return 1;
	}

	/**
	 * Eye shown in a view: -1 left, 1 right, 0 both (anaglyph...)
	 */
	public int getViewEye(int aView) {
		return 0;
	}

	/**
	 * New mixer of the same kind and mode (default zoom and position)
	 */
	public abstract Mixer copyMode();

	/**
	 * Mixed image of the left and right images (not HDR), fitted in aTargetSize
	 */
	public abstract Image mix2Swt(BufferedImage aLI,BufferedImage aRI,int aProcessingMode,Rectangle aTargetSize);

	/**
	 * Same as mix2Swt(), as bands converted on all the cores (SwtStrips); by default one band
	 */
	public com.cubaix.TDenlive.utils.SwtStrips mix2SwtStrips(BufferedImage aLI,BufferedImage aRI,int aProcessingMode,Rectangle aTargetSize) {
		Image aI = mix2Swt(aLI, aRI, aProcessingMode, aTargetSize);
		return aI == null ? null : com.cubaix.TDenlive.utils.SwtStrips.of(aI);
	}

	/**
	 * Same as process2Swt(), as bands (SwtStrips): mix2SwtStrips() of the final images, one band in HDR
	 */
	public com.cubaix.TDenlive.utils.SwtStrips process2SwtStrips(Media aMedia,int aProcessingMode,Rectangle aTargetSize,long aTime) {
		if(tde.config.processingHdr[aProcessingMode]) {
			Image aI = process2Swt(aMedia, aProcessingMode, aTargetSize, aTime);
			return aI == null ? null : com.cubaix.TDenlive.utils.SwtStrips.of(aI);
		}
		return mix2SwtStrips(aMedia.getFinalLeft(aProcessingMode,aTime), aMedia.getFinalRight(aProcessingMode,aTime), aProcessingMode, aTargetSize);
	}

	public void resize(BufferedImage aBI,BufferedImage aSizedBI,int aProcessingMode
			,int aW,int aH,int aSizedW,int aSizedH) {
		lastW = aW;
		lastH = aH;
		lastSizedW = aSizedW;
		lastSizedH = aSizedH;
		if(aProcessingMode == TDConfig.PROCESSING_MODE_WORK) {
			//Work preview: the image is drawn over a fixed checkerboard, to see the transparencies
			//not filled (and the missing parts on the margins), black otherwise
			ImageUtils.checkerBck(aSizedBI, CHECKER_CELL);
		}
		Graphics2D aGL2 = (Graphics2D)aSizedBI.getGraphics();
		if(!tde.config.fastMode || aProcessingMode == TDConfig.PROCESSING_MODE_RENDER) {
			aGL2.setRenderingHint(RenderingHints.KEY_ANTIALIASING,RenderingHints.VALUE_ANTIALIAS_ON);
			aGL2.setRenderingHint(RenderingHints.KEY_RENDERING,RenderingHints.VALUE_RENDER_QUALITY);
		}
		//Display: a float image (HDR) clamped (Java2D wraps the values above 1)
		aGL2.drawImage(com.cubaix.TDenlive.utils.FloatDraw.clamped(aBI),0,0,aSizedW,aSizedH
				,(int)(x*aW+aW*0.5*(1.0-1.0/zoom)),(int)(y*aH+aH*0.5*(1.0-1.0/zoom))
				,(int)(x*aW+aW*0.5*(1.0-1.0/zoom)+aW/zoom), (int)(y*aH+aH*0.5*(1.0-1.0/zoom)+aH/zoom),null);
		aGL2.dispose();
	}
}
