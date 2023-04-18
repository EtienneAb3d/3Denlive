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

public class AnaMixer extends Mixer {
	int mode = 0;//0=RC, 1=YB, 2=GM, 3=Dubois, 4=ZhangAllister, 5=InterlacedH1, 6=InterlacedH2,7=InterlacedV1,8=InterlacedV2,9=Left,10=Right
	
	public AnaMixer(TDenlive aTDe,Media aTargetMedia,int aMode) {
		super(aTDe,aTargetMedia);
		mode = aMode;
	}

	@Override
	public String getClassName() {return "AnaMixer";}

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
		double aFact = Math.min(aTargetSize.width/(double)aW, aTargetSize.height / (double) aH);
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
		BufferedImage aBI = ImageUtils.createImage(aWidth, aHeight);
		int[] aDB = ((DataBufferInt)aBI.getRaster().getDataBuffer()).getData();
		for(int x = 0;x<aWidth;x++) {
			for(int y = 0;y<aHeight;y++) {
				int aARGBL = aDBFL[y*aWidth+x];
				int aARGBR = aDBFR[y*aWidth+x];
				double aAL = ((aARGBL>>24) & 0xFF)/255.0;//Alpha
				double aAR = ((aARGBR>>24) & 0xFF)/255.0;//Alpha

				double aRL = (((aARGBL>>16) & 0xFF)*aAL);
				double aGL = (((aARGBL>>8) & 0xFF)*aAL);
				double aBL = (((aARGBL) & 0xFF)*aAL);
				double aRR = (((aARGBR>>16) & 0xFF)*aAR);
				double aGR = (((aARGBR>>8) & 0xFF)*aAR);
				double aBR = (((aARGBR) & 0xFF)*aAR);
				int aR = 0;//Red
				int aG = 0;//Green
				int aB = 0;//Blue
				int aPix = 0;
				switch(mode) {
				case 0://RC
					aR = (int)(aRL*aAL);//Red
					aG = (int)(aGR*aAR);//Green
					aB = (int)(aBR*aAR);//Blue
					aPix = 0xFF << 24
							| (aR & 0xFF) << 16
							| (aG & 0xFF) << 8
							| aB & 0xFF
							;
					aDB[y*aWidth+x] = aPix;
					break;
				case 1://YB
					aR = (int)(aRL*aAL);//Red
					aG = (int)(aGL*aAL);//Green
					aB = (int)(aBR*aAR);//Blue
					aPix = 0xFF << 24
							| (aR & 0xFF) << 16
							| (aG & 0xFF) << 8
							| aB & 0xFF
							;
					aDB[y*aWidth+x] = aPix;
					break;
				case 2://GM
					aR = (int)(aRR*aAR);//Red
					aG = (int)(aGL*aAL);//Green
					aB = (int)(aBR*aAR);//Blue
					aPix = 0xFF << 24
							| (aR & 0xFF) << 16
							| (aG & 0xFF) << 8
							| aB & 0xFF
							;
					aDB[y*aWidth+x] = aPix;
					break;
				case 3://Dubois
					aR = (int)(aRL*(0.4561) + aGL*(0.500484) + aBL*(0.176381) + aRR*(-0.0434706) + aGR*(-0.0879388) + aBR*(-0.00155529));
					aG = (int)(aRL*(-0.0400822) + aGL*(-0.0378246) + aBL*(-0.0157589) + aRR*(0.378476) + aGR*(0.73364) + aBR*(-0.0184503));
					aB = (int)(aRL*(-0.0152161) + aGL*(-0.0205971) + aBL*(-0.00546856) + aRR*(-0.0721527) + aGR*(-0.112961) + aBR*(1.2264));
					aR = aR>=255?255:(aR<=0?0:aR);
					aG = aG>=255?255:(aG<=0?0:aG);
					aB = aB>=255?255:(aB<=0?0:aB);
					aPix = 0xFF << 24
							| (aR & 0xFF) << 16
							| (aG & 0xFF) << 8
							| aB & 0xFF
							;
					aDB[y*aWidth+x] = aPix;
					break;
				case 4://ZhangAllister
					aR = (int)(aRL*(0.4154) + aGL*(0.4710) + aBL*(0.1669) + aRR*(-0.0109) + aGR*(-0.0364) + aBR*(-0.0060));
					aG = (int)(aRL*(-0.0458) + aGL*(-0.0484) + aBL*(-0.0257) + aRR*(0.3756) + aGR*(0.7333) + aBR*(-0.0111));
					aB = (int)(aRL*(-0.0547) + aGL*(-0.0615) + aBL*(-0.0128) + aRR*(-0.0651) + aGR*(-0.1287) + aBR*(1.2971));
					aR = aR>=255?255:(aR<=0?0:aR);
					aG = aG>=255?255:(aG<=0?0:aG);
					aB = aB>=255?255:(aB<=0?0:aB);
					aPix = 0xFF << 24
							| (aR & 0xFF) << 16
							| (aG & 0xFF) << 8
							| aB & 0xFF
							;
					aDB[y*aWidth+x] = aPix;
					break;
				case 5://Interlaced H1
				case 6://Interlaced H2
					if(((y+mode)%2)==0) {
						aR = (int)(aRL*aAL);//Red
						aG = (int)(aGL*aAL);//Green
						aB = (int)(aBL*aAL);//Blue
					}
					else {
						aR = (int)(aRR*aAR);//Red
						aG = (int)(aGR*aAR);//Green
						aB = (int)(aBR*aAR);//Blue
					}
					aPix = 0xFF << 24
							| (aR & 0xFF) << 16
							| (aG & 0xFF) << 8
							| aB & 0xFF
							;
					aDB[y*aWidth+x] = aPix;
					break;
				case 7://Interlaced H1
				case 8://Interlaced H2
					if(((x+mode)%2)==0) {
						aR = (int)(aRL*aAL);//Red
						aG = (int)(aGL*aAL);//Green
						aB = (int)(aBL*aAL);//Blue
					}
					else {
						aR = (int)(aRR*aAR);//Red
						aG = (int)(aGR*aAR);//Green
						aB = (int)(aBR*aAR);//Blue
					}
					aPix = 0xFF << 24
							| (aR & 0xFF) << 16
							| (aG & 0xFF) << 8
							| aB & 0xFF
							;
					aDB[y*aWidth+x] = aPix;
					break;
				case 9://Left
					aR = (int)(aRL*aAL);//Red
					aG = (int)(aGL*aAL);//Green
					aB = (int)(aBL*aAL);//Blue
					aPix = 0xFF << 24
							| (aR & 0xFF) << 16
							| (aG & 0xFF) << 8
							| aB & 0xFF
							;
					aDB[y*aWidth+x] = aPix;
					break;
				case 10://Right
					aR = (int)(aRR*aAR);//Red
					aG = (int)(aGR*aAR);//Green
					aB = (int)(aBR*aAR);//Blue
					aPix = 0xFF << 24
							| (aR & 0xFF) << 16
							| (aG & 0xFF) << 8
							| aB & 0xFF
							;
					aDB[y*aWidth+x] = aPix;
					break;
				}
			}
		}
		
		return ImageUtils.imageToSwt(tde.gui.display, aBI);
	}
	
	public Image process2SwtHdr(Media aMedia,int aProcessingMode,Rectangle aTargetSize,long aTime) {
		final BufferedImage aLI = aMedia.getFinalLeft(aProcessingMode,aTime);
		final BufferedImage aRI = aMedia.getFinalRight(aProcessingMode,aTime);
		final int aW = aLI.getWidth();
		final int aH = aLI.getHeight();
		double aFact = Math.min(aTargetSize.width/(double)aW, aTargetSize.height / (double) aH);
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
		BufferedImage aBI = ImageUtils.createImage(aWidth, aHeight);
		int[] aDB = ((DataBufferInt)aBI.getRaster().getDataBuffer()).getData();
		for(int x = 0;x<aWidth;x++) {
			for(int y = 0;y<aHeight;y++) {
				float aAL = aDBFL[0][y*aWidth*4+x*4+3];//Alpha
				float aAR = aDBFR[0][y*aWidth*4+x*4+3];//Alpha
				double aRL = aDBFL[0][y*aWidth*4+x*4]*aAL;//Red
				double aGL = aDBFL[0][y*aWidth*4+x*4+1]*aAL;//Green
				double aBL = aDBFL[0][y*aWidth*4+x*4+2]*aAL;//Blue
				double aRR = aDBFR[0][y*aWidth*4+x*4]*aAR;//Red
				double aGR = aDBFR[0][y*aWidth*4+x*4+1]*aAR;//Green
				double aBR = aDBFR[0][y*aWidth*4+x*4+2]*aAR;//Blue
				int aR = 0;//Red
				int aG = 0;//Green
				int aB = 0;//Blue
				int aPix = 0;
				switch(mode) {
				case 0://RC
					aR = (int)(255*aRL*aAL);//Red
					aG = (int)(255*aGR*aAR);//Green
					aB = (int)(255*aBR*aAR);//Blue
					aPix = 0xFF << 24
							| (aR & 0xFF) << 16
							| (aG & 0xFF) << 8
							| aB & 0xFF
							;
					aDB[y*aWidth+x] = aPix;
					break;
				case 1://YB
					aR = (int)(255*aRL*aAL);//Red
					aG = (int)(255*aGL*aAL);//Green
					aB = (int)(255*aBR*aAR);//Blue
					aPix = 0xFF << 24
							| (aR & 0xFF) << 16
							| (aG & 0xFF) << 8
							| aB & 0xFF
							;
					aDB[y*aWidth+x] = aPix;
					break;
				case 2://GM
					aR = (int)(255*aRR*aAR);//Red
					aG = (int)(255*aGL*aAL);//Green
					aB = (int)(255*aBR*aAR);//Blue
					aPix = 0xFF << 24
							| (aR & 0xFF) << 16
							| (aG & 0xFF) << 8
							| aB & 0xFF
							;
					aDB[y*aWidth+x] = aPix;
					break;
				case 3://Dubois
					aR = (int)(255*(aRL*(0.4561) + aGL*(0.500484) + aBL*(0.176381) + aRR*(-0.0434706) + aGR*(-0.0879388) + aBR*(-0.00155529)));
					aG = (int)(255*(aRL*(-0.0400822) + aGL*(-0.0378246) + aBL*(-0.0157589) + aRR*(0.378476) + aGR*(0.73364) + aBR*(-0.0184503)));
					aB = (int)(255*(aRL*(-0.0152161) + aGL*(-0.0205971) + aBL*(-0.00546856) + aRR*(-0.0721527) + aGR*(-0.112961) + aBR*(1.2264)));
					aR = aR>=255?255:(aR<=0?0:aR);
					aG = aG>=255?255:(aG<=0?0:aG);
					aB = aB>=255?255:(aB<=0?0:aB);
					aPix = 0xFF << 24
							| (aR & 0xFF) << 16
							| (aG & 0xFF) << 8
							| aB & 0xFF
							;
					aDB[y*aWidth+x] = aPix;
					break;
				case 4://ZhangAllister
					aR = (int)(255*(aRL*(0.4154) + aGL*(0.4710) + aBL*(0.1669) + aRR*(-0.0109) + aGR*(-0.0364) + aBR*(-0.0060)));
					aG = (int)(255*(aRL*(-0.0458) + aGL*(-0.0484) + aBL*(-0.0257) + aRR*(0.3756) + aGR*(0.7333) + aBR*(-0.0111)));
					aB = (int)(255*(aRL*(-0.0547) + aGL*(-0.0615) + aBL*(-0.0128) + aRR*(-0.0651) + aGR*(-0.1287) + aBR*(1.2971)));
					aR = aR>=255?255:(aR<=0?0:aR);
					aG = aG>=255?255:(aG<=0?0:aG);
					aB = aB>=255?255:(aB<=0?0:aB);
					aPix = 0xFF << 24
							| (aR & 0xFF) << 16
							| (aG & 0xFF) << 8
							| aB & 0xFF
							;
					aDB[y*aWidth+x] = aPix;
					break;
				case 5://Interlaced H1
				case 6://Interlaced H2
					if(((y+mode)%2)==0) {
						aR = (int)(255*aRL*aAL);//Red
						aG = (int)(255*aGL*aAL);//Green
						aB = (int)(255*aBL*aAL);//Blue
					}
					else {
						aR = (int)(255*aRR*aAR);//Red
						aG = (int)(255*aGR*aAR);//Green
						aB = (int)(255*aBR*aAR);//Blue
					}
					aPix = 0xFF << 24
							| (aR & 0xFF) << 16
							| (aG & 0xFF) << 8
							| aB & 0xFF
							;
					aDB[y*aWidth+x] = aPix;
					break;
				case 7://Interlaced H1
				case 8://Interlaced H2
					if(((x+mode)%2)==0) {
						aR = (int)(255*aRL*aAL);//Red
						aG = (int)(255*aGL*aAL);//Green
						aB = (int)(255*aBL*aAL);//Blue
					}
					else {
						aR = (int)(255*aRR*aAR);//Red
						aG = (int)(255*aGR*aAR);//Green
						aB = (int)(255*aBR*aAR);//Blue
					}
					aPix = 0xFF << 24
							| (aR & 0xFF) << 16
							| (aG & 0xFF) << 8
							| aB & 0xFF
							;
					aDB[y*aWidth+x] = aPix;
					break;
				case 9://Left
					aR = (int)(255*aRL*aAL);//Red
					aG = (int)(255*aGL*aAL);//Green
					aB = (int)(255*aBL*aAL);//Blue
					aPix = 0xFF << 24
							| (aR & 0xFF) << 16
							| (aG & 0xFF) << 8
							| aB & 0xFF
							;
					aDB[y*aWidth+x] = aPix;
					break;
				case 10://Right
					aR = (int)(255*aRR*aAR);//Red
					aG = (int)(255*aGR*aAR);//Green
					aB = (int)(255*aBR*aAR);//Blue
					aPix = 0xFF << 24
							| (aR & 0xFF) << 16
							| (aG & 0xFF) << 8
							| aB & 0xFF
							;
					aDB[y*aWidth+x] = aPix;
					break;
				}
			}
		}
		return ImageUtils.imageToSwt(tde.gui.display, aBI);
	}

	@Override
	public void process2Awt(Media aMedia,int aProcessingMode,long aTime) {
	}
}
