package com.cubaix.TDenlive.processors;

import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;

import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.GC;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.graphics.Rectangle;

import com.cubaix.TDenlive.TDConfig;
import com.cubaix.TDenlive.TDenlive;
import com.cubaix.TDenlive.medias.Media;
import com.cubaix.TDenlive.processors.ImageParamProcessor.Planes;
import com.cubaix.TDenlive.utils.ImageUtils;

/**
 * Image monitor: analyses both views as they are at its place in the stack (work and full
 * screen modes only) into a scope image (drawScope(), INT_ARGB), drawn in its panel with the
 * names of the views
 */
public abstract class ImageMonitor extends MonitorProcessor {
	/** Pixels analysed at most per view (the views are subsampled above) */
	static final int MAX_SAMPLES = 400000;
	/** Last scope image */
	protected volatile BufferedImage scope = null;
	/** Trace of the analyses in the log (at most one line per second and monitor) */
	static final boolean _TRACE = false;
	long lastTraceMS = 0;

	public ImageMonitor(TDenlive aTDe, Media aTargetMedia) {
		super(aTDe, aTargetMedia);
	}

	/** Scope image of both views (aR may be null for a 2D media without right view) */
	protected abstract BufferedImage drawScope(Planes aL,Planes aR,int aStep);

	/** True: the views side by side in the scope image, else one above the other */
	protected boolean sideBySide() {return false;}

	@Override
	public void process2Awt(Media aMedia, int aProcessingMode, long aTime) {
		boolean aWatched = isWatched();
		if(_TRACE && System.currentTimeMillis()-lastTraceMS > 1000) {
			lastTraceMS = System.currentTimeMillis();
			System.out.println("MONITOR "+getClassName()+" t="+aTime+" mode="+aProcessingMode+" watched="+aWatched+" version="+version);
		}
		if(!aWatched || (aProcessingMode != TDConfig.PROCESSING_MODE_WORK && aProcessingMode != TDConfig.PROCESSING_MODE_FULLSCREEN)) {
			return;
		}
		try {
			BufferedImage aBL = aMedia.getWorkLeft(aProcessingMode, aTime);
			BufferedImage aBR = aMedia.getWorkRight(aProcessingMode, aTime);
			if(aBL == null) {
				return;
			}
			Planes aL = Planes.of(aBL);
			Planes aR = aBR == null ? null : Planes.of(aBR);
			int aStep = Math.max(1, (int)Math.ceil(Math.sqrt(aL.w*(double)aL.h/MAX_SAMPLES)));
			scope = drawScope(aL, aR, aStep);
			updated();
		}
		catch(Throwable t) {
			t.printStackTrace(System.err);
		}
	}

	@Override
	public void paint(GC aGC, Rectangle aR) {
		aGC.setBackground(tde.gui.display.getSystemColor(SWT.COLOR_BLACK));
		aGC.fillRectangle(aR);
		BufferedImage aBI = scope;
		if(aBI == null) {
			return;
		}
		Image aImg = ImageUtils.imageToSwt(tde.gui.display, aBI);
		try {
			double aS = Math.min(aR.width/(double)aBI.getWidth(), aR.height/(double)aBI.getHeight());
			int aW = (int)(aBI.getWidth()*aS), aH = (int)(aBI.getHeight()*aS);
			int aX = aR.x+(aR.width-aW)/2, aY = aR.y+(aR.height-aH)/2;
			aGC.setAntialias(SWT.ON);
			aGC.setInterpolation(SWT.HIGH);
			aGC.drawImage(aImg, 0, 0, aBI.getWidth(), aBI.getHeight(), aX, aY, aW, aH);
			aGC.setForeground(tde.gui.display.getSystemColor(SWT.COLOR_GRAY));
			aGC.setFont(tde.gui.fontsSwt.roboto);
			String aLeft = tde.gui.lngs.get("Generic.LeftLetter"), aRight = tde.gui.lngs.get("Generic.RightLetter");
			aGC.drawString(aLeft, aX+3, aY+1, true);
			if(sideBySide()) {
				aGC.drawString(aRight, aX+aW/2+5, aY+1, true);
			}
			else {
				aGC.drawString(aRight, aX+3, aY+aH/2+3, true);
			}
		}
		finally {
			aImg.dispose();
		}
	}

	//------------------------------------------------------------------ Drawing tools

	protected static BufferedImage black(int aW,int aH) {
		BufferedImage aBI = new BufferedImage(aW, aH, BufferedImage.TYPE_INT_ARGB);
		int[] aDB = ((DataBufferInt)aBI.getRaster().getDataBuffer()).getData();
		java.util.Arrays.fill(aDB, 0xFF000000);
		return aBI;
	}

	protected static int[] pixels(BufferedImage aBI) {
		return ((DataBufferInt)aBI.getRaster().getDataBuffer()).getData();
	}

	protected static int rgb(double aR,double aG,double aB) {
		return 0xFF000000 | c8(aR) << 16 | c8(aG) << 8 | c8(aB);
	}

	static int c8(double aV) {
		int aI = (int)(aV*255+0.5);
		return aI < 0 ? 0 : (aI > 255 ? 255 : aI);
	}

	/** Adds (saturating) the color aARGB x aF to the pixel p */
	protected static void add(int[] aPix,int p,double aR,double aG,double aB) {
		int aC = aPix[p];
		int r = Math.min(255, ((aC>>16) & 0xFF)+c8(aR));
		int g = Math.min(255, ((aC>>8) & 0xFF)+c8(aG));
		int b = Math.min(255, (aC & 0xFF)+c8(aB));
		aPix[p] = 0xFF000000 | r << 16 | g << 8 | b;
	}

	protected static void hLine(int[] aPix,int aW,int aY,int aX0,int aX1,int aColor) {
		for(int x = Math.max(0, aX0);x < aX1 && x < aW;x++) {
			aPix[aY*aW+x] = aColor;
		}
	}
}
