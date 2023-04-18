package com.cubaix.TDenlive.processors;

import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferFloat;
import java.awt.image.DataBufferInt;
import java.io.BufferedWriter;
import java.util.Vector;

import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.graphics.ImageData;
import org.eclipse.swt.graphics.PaletteData;
import org.eclipse.swt.graphics.Rectangle;

import com.cubaix.TDenlive.TDConfig;
import com.cubaix.TDenlive.TDenlive;
import com.cubaix.TDenlive.medias.Media;
import com.cubaix.TDenlive.utils.ImageUtils;
import com.cubaix.TDenlive.xml.XmlObject;

public class SbsMixer extends Mixer {
	int mode = 0;//0=P, 1=X
	public SbsMixer(TDenlive aTDe,Media aTargetMedia,int aMode) {
		super(aTDe,aTargetMedia);
		mode = aMode;
	}

	@Override
	public String getClassName() {return "SbsMixer";}

	@Override
	public int openProject(Vector<XmlObject> aOs, int o) throws Exception {
		return o;
	}

	@Override
	public void saveProject(StringBuffer aSB) throws Exception {}

	@Override
	void setDescr() {
		name = tde.gui.lngs.get("Processor.AnaMixer");
		icon = "glassesAll.png";
	}
	
	@Override
	public Image process2Swt(Media aMedia,int aProcessingMode,Rectangle aTargetSize,long aTime) {
		if(tde.config.processingHdr[aProcessingMode]) {
			return process2SwtHdr(aMedia,aProcessingMode,aTargetSize,aTime);
		}
		return process2SwtImage(aMedia,aProcessingMode,aTargetSize,aTime);
	}

	public Image process2SwtImage(Media aMedia,int aProcessingMode,Rectangle aTargetSize,long aTime) {
		final BufferedImage aLI = aMedia.getFinalLeft(aProcessingMode,aTime);
		final BufferedImage aRI = aMedia.getFinalRight(aProcessingMode,aTime);
		final int aW = aLI.getWidth();
		final int aH = aLI.getHeight();
		double aFact = Math.min(aTargetSize.width/(double)(2*aW), aTargetSize.height / (double) aH);
		final int aWidth = (int)(aW * aFact);
		final int aHeight = (int)(aH * aFact);
		final BufferedImage[] aResized = new BufferedImage[2];
		try {
			Thread aThL = new Thread(new Runnable() {
				@Override
				public void run() {
					aResized[0] = ImageUtils.createImage(aWidth, aHeight);
					resize(aLI, aResized[0],aProcessingMode, aW, aH, aWidth, aHeight);
				}
			});
			aThL.start();
			Thread aThR = new Thread(new Runnable() {
				@Override
				public void run() {
					aResized[1] = ImageUtils.createImage(aWidth, aHeight);
					resize(aRI,aResized[1], aProcessingMode, aW, aH, aWidth, aHeight);
				}
			});
			aThR.start();
			aThL.join();
			aThR.join();
		}
		catch(Throwable t) {
			t.printStackTrace(System.err);
		}
	
		int[] aDBFL = ((DataBufferInt)aResized[0].getRaster().getDataBuffer()).getData();
		int[] aDBFR = ((DataBufferInt)aResized[1].getRaster().getDataBuffer()).getData();
		ImageData aID = new ImageData(aWidth*2,aHeight,32,new PaletteData(0xFF0000, 0xFF00, 0xFF));
		for(int x = 0;x<aWidth;x++) {
			for(int y = 0;y<aHeight;y++) {
				int aARGBL = aDBFL[y*aWidth+x];
				double aAL = ((aARGBL>>24) & 0xFF)/255.0;//Alpha
				if(mode == 0) {//P
					aID.setPixel(x, y, aARGBL);
					aID.setAlpha(x, y, (int)(aAL*255));//Need to set it explicitly 
				}
				else if(mode == 1) {//X
					aID.setPixel(x+aWidth, y, aARGBL);
					aID.setAlpha(x+aWidth, y, (int)(aAL*255));//Need to set it explicitly 
				}
				int aARGBR = aDBFR[y*aWidth+x];
				double aAR = ((aARGBR>>24) & 0xFF)/255.0;//Alpha
				if(mode == 0) {//P
					aID.setPixel(x+aWidth, y, aARGBR);
					aID.setAlpha(x+aWidth, y, (int)(aAR*255));//Need to set it explicitly
				}
				else if(mode == 1) {//X
					aID.setPixel(x, y, aARGBR);
					aID.setAlpha(x, y, (int)(aAR*255));//Need to set it explicitly
				} 
			}
		}
		return new Image(tde.gui.display,aID);
	}
	
	public Image process2SwtHdr(Media aMedia,int aProcessingMode,Rectangle aTargetSize,long aTime) {
		final BufferedImage aLI = aMedia.getFinalLeft(aProcessingMode,aTime);
		final BufferedImage aRI = aMedia.getFinalRight(aProcessingMode,aTime);
		final int aW = aLI.getWidth();
		final int aH = aLI.getHeight();
		double aFact = Math.min(aTargetSize.width/(double)(2*aW), aTargetSize.height / (double) aH);
		final int aWidth = (int)(aW * aFact);
		final int aHeight = (int)(aH * aFact);
		final BufferedImage[] aResized = new BufferedImage[2];
		try {
			Thread aThL = new Thread(new Runnable() {
				@Override
				public void run() {
					aResized[0] = ImageUtils.createHdr(aWidth, aHeight);
					resize(aLI, aResized[0],aProcessingMode, aW, aH, aWidth, aHeight);
				}
			});
			aThL.start();
			Thread aThR = new Thread(new Runnable() {
				@Override
				public void run() {
					aResized[1] = ImageUtils.createHdr(aWidth, aHeight);
					resize(aRI,aResized[1], aProcessingMode, aW, aH, aWidth, aHeight);
				}
			});
			aThR.start();
			aThL.join();
			aThR.join();
		}
		catch(Throwable t) {
			t.printStackTrace(System.err);
		}
	
		float[][] aDBFL = ((DataBufferFloat)aResized[0].getRaster().getDataBuffer()).getBankData();
		float[][] aDBFR = ((DataBufferFloat)aResized[1].getRaster().getDataBuffer()).getBankData();
		ImageData aID = new ImageData(aWidth*2,aHeight,32,new PaletteData(0xFF0000, 0xFF00, 0xFF));
		for(int x = 0;x<aWidth;x++) {
			for(int y = 0;y<aHeight;y++) {
				float aR = aDBFL[0][y*aWidth*4+x*4];//Red
				float aG = aDBFL[0][y*aWidth*4+x*4+1];//Green
				float aB = aDBFL[0][y*aWidth*4+x*4+2];//Blue
				float aA = aDBFL[0][y*aWidth*4+x*4+3];//Alpha
				int aPix = (((int)(aA*255)) & 0xFF) << 24
						| (((int)(aR*255)) & 0xFF) << 16
						| (((int)(aG*255)) & 0xFF) << 8
						| (((int)(aB*255)) & 0xFF)
						;
				if(mode == 0) {//P
					aID.setPixel(x, y, aPix);
					aID.setAlpha(x, y, ((int)(aA*255)) & 0xFF);//Need to set it explicitly
				}
				else if(mode == 1) {//X
					aID.setPixel(x+aWidth, y, aPix);
					aID.setAlpha(x+aWidth, y, ((int)(aA*255)) & 0xFF);//Need to set it explicitly
				}
				
				aR = aDBFR[0][y*aWidth*4+x*4];//Red
				aG = aDBFR[0][y*aWidth*4+x*4+1];//Green
				aB = aDBFR[0][y*aWidth*4+x*4+2];//Blue
				aA = aDBFR[0][y*aWidth*4+x*4+3];//Alpha
				aPix = (((int)(aA*255)) & 0xFF) << 24
						| (((int)(aR*255)) & 0xFF) << 16
						| (((int)(aG*255)) & 0xFF) << 8
						| (((int)(aB*255)) & 0xFF)
						;
				if(mode == 0) {//P
					aID.setPixel(x+aWidth, y, aPix);
					aID.setAlpha(x+aWidth, y, ((int)(aA*255)) & 0xFF);//Need to set it explicitly
				}
				else if(mode == 1) {//X
					aID.setPixel(x, y, aPix);
					aID.setAlpha(x, y, ((int)(aA*255)) & 0xFF);//Need to set it explicitly
				}
			}
		}
		return new Image(tde.gui.display,aID);
	}

	@Override
	public void process2Awt(Media aMedia,int aProcessingMode,long aTime) {
	}
}
