package com.cubaix.TDenlive.processors;

import java.awt.Color;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferFloat;
import java.awt.image.DataBufferInt;
import java.util.Vector;

import org.eclipse.swt.graphics.GC;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.graphics.Rectangle;

import com.cubaix.TDenlive.TDConfig;
import com.cubaix.TDenlive.TDenlive;
import com.cubaix.TDenlive.medias.Clip;
import com.cubaix.TDenlive.medias.Media;
import com.cubaix.TDenlive.utils.ImageUtils;
import com.cubaix.TDenlive.xml.XmlObject;

public class AutoDepthmap extends Processor {
	BufferedImage leftIMedia = null;
	BufferedImage rightIMedia = null;
	BufferedImage leftI = null;
	BufferedImage rightI = null;
	
	EdgeDetector edgeDetector = null;
	Blurer blurer50 = null;
	Blurer blurer100 = null;
	int[] leftPixelsEdges = null;
	float leftMaxEdge = 0;
	int[] rightPixelsEdges = null;
	float rightMaxEdge = 0;

    int width;
    int height;

    double smoothWidth;

    double moveLeftDx,moveLeftDy,moveLeftR,moveLeftH;
    
    BufferedImage hMorphL = null;
    BufferedImage vMorphL = null;
    BufferedImage hMorphR = null;
    BufferedImage vMorphR = null;
    BufferedImage zTrg = null;
    BufferedImage morphedTrg = null;

	public AutoDepthmap(TDenlive aTDe,Media aTargetMedia) {
		super(aTDe,aTargetMedia);
		edgeDetector = new EdgeDetector(tde,aTargetMedia);
		blurer50 = new Blurer(tde, aTargetMedia, 1.0);
		blurer100 = new Blurer(tde, aTargetMedia, 8.0);
	}
	
	@Override
	public String getClassName() {return "AutoAligner";}


	@Override
	public int openProject(Vector<XmlObject> aOs, int o) throws Exception {
		return o;
	}

	@Override
	public void saveProject(StringBuffer aSB) throws Exception {}

	@Override
	void setDescr() {
	}

	@Override
	public Image process2Swt(Media aMedia, int aProcessingMode, Rectangle aTargetSize, long aTime) {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public void process2Awt(Media aMedia, int aProcessingMode, long aTime) {
		Media aMonitored = tde.gui.monitorGUI.getMedia();

		//Need images available
		tde.config.processingResValues[TDConfig.PROCESSING_MODE_AUTOALIGN] = 300;
		rebuildImages(aMedia);
		leftI = leftIMedia;
		rightI = rightIMedia;

		tde.gui.monitorGUI.setMedia(new Media(tde) {
			@Override
			public int openProject(Vector<XmlObject> aOs, int o) throws Exception {
				return 0;
			}
			@Override
			public void saveProject(StringBuffer aSB) throws Exception {
			}
			@Override
			public long getMaxTimePosMS() {
				return 0;
			}
			@Override
			public void reBuild(int aProcessingMode, long aTime) {
			}
			@Override
			public void setWorkLeft(BufferedImage aBI) {
			}
			@Override
			public void setWorkRight(BufferedImage aBI) {
			}
			@Override
			public BufferedImage getWorkLeft(int aProcessingMode,long aTime) {
				return null;
			}
			@Override
			public BufferedImage getWorkRight(int aProcessingMode,long aTime) {
				return null;
			}
			@Override
			public BufferedImage getFinalLeft(int aProcessingMode, long aTime) {
				return leftI;
			}
			@Override
			public BufferedImage getFinalRight(int aProcessingMode, long aTime) {
				return rightI;
			}
			@Override
			public Rectangle drawSwt(GC aGC, int aX, int aY, Rectangle aClipR) {
				return null;
			}
			@Override
			public Rectangle drawAwt(Graphics2D aGC, int aX, int aY, Rectangle aClippingR) {
				// TODO Auto-generated method stub
				return null;
			}
			@Override
			public boolean selectWidget(int aX, int aY) {
				return false;
			}
			@Override
			public void select(int aX, int aY, boolean aOutUnselect) {
			}
		});
		
		Thread aTh = new Thread(new Runnable() {
			@Override
			public void run() {
				align(aMedia);
				tde.gui.display.syncExec(new Runnable() {
					@Override
					public void run() {
						tde.gui.monitorGUI.setMedia(aMonitored);
						tde.gui.processorGUI.setProcessors(((Clip)aMedia).getProcessors());
					}
				});
			}
		});
		aTh.start();
	}
	
	void rebuildImages(Media aMedia) {
		try {
			aMedia.reBuild(TDConfig.PROCESSING_MODE_AUTOALIGN, tde.timeLineStack.getTimePosMS());
			leftIMedia = aMedia.getWorkLeft(TDConfig.PROCESSING_MODE_AUTOALIGN, tde.timeLineStack.getTimePosMS());
			rightIMedia = aMedia.getWorkRight(TDConfig.PROCESSING_MODE_AUTOALIGN, tde.timeLineStack.getTimePosMS());
		}
		catch(Throwable t) {
			t.printStackTrace(System.err);
		}
	}
	
	void align(Media aMedia) {
		StereoAligner aSA = ((Clip)aMedia).getFinalAligner();
		aSA.isWarning = false;
		
		int aCurrentRes = tde.config.processingResValues[TDConfig.PROCESSING_MODE_WORK];
		int aStart = 50;
		double aEdgeFact = 0.1;
		int aNbSteps = 10;
		tde.config.processingResValues[TDConfig.PROCESSING_MODE_AUTOALIGN] = aStart;
		double aSteps = (aCurrentRes-aStart)/aNbSteps;
		for(int aStep = 0;aStep<=aNbSteps;aStep++) {
			tde.config.processingResValues[TDConfig.PROCESSING_MODE_AUTOALIGN] = (int)(aStart+aStep*aSteps);

			rebuildImages(aMedia);

			width = leftIMedia.getWidth();
			height = leftIMedia.getHeight();
			int[] leftPixels = ((DataBufferInt)leftIMedia.getRaster().getDataBuffer()).getData();
			int[] rightPixels = ((DataBufferInt)rightIMedia.getRaster().getDataBuffer()).getData();
			leftPixelsEdges = edgeDetector.filterRGBAll(width, height, leftPixels);
			rightPixelsEdges = edgeDetector.filterRGBAll(width, height, rightPixels);
			BufferedImage aLeftMixed = ImageUtils.createImage(width, height);
			BufferedImage aRightMixed = ImageUtils.createImage(width, height);
			int[] leftPixelsMixed = ((DataBufferInt)aLeftMixed.getRaster().getDataBuffer()).getData();
			int[] rightPixelsMixed = ((DataBufferInt)aRightMixed.getRaster().getDataBuffer()).getData();
			for(int p = 0;p<leftPixels.length;p++) {
				int aLeftEdge = leftPixelsEdges[p]&0xFF;
				if(aLeftEdge > leftMaxEdge) {
					leftMaxEdge = aLeftEdge;
				}
				leftPixelsMixed[p] = (((int)((leftPixels[p]>>16&0xFF)*(1.0-aEdgeFact) + (leftPixelsEdges[p]>>16&0xFF)*aEdgeFact))<<16)
						+(((int)((leftPixels[p]>>8&0xFF)*(1.0-aEdgeFact) + (leftPixelsEdges[p]>>8&0xFF)*aEdgeFact))<<8)
						+(((int)((leftPixels[p]&0xFF)*(1.0-aEdgeFact) + (leftPixelsEdges[p]&0xFF)*aEdgeFact)))
						;
				int aRightEdge = rightPixelsEdges[p]&0xFF;
				if(aRightEdge > rightMaxEdge) {
					rightMaxEdge = aRightEdge;
				}
				rightPixelsMixed[p] = (((int)((rightPixels[p]>>16&0xFF)*(1.0-aEdgeFact) + (rightPixelsEdges[p]>>16&0xFF)*aEdgeFact))<<16)
						+(((int)((rightPixels[p]>>8&0xFF)*(1.0-aEdgeFact) + (rightPixelsEdges[p]>>8&0xFF)*aEdgeFact))<<8)
						+(((int)((rightPixels[p]&0xFF)*(1.0-aEdgeFact) + (rightPixelsEdges[p]&0xFF)*aEdgeFact)))
						;
			}
			int[] leftPixelsBlured = blurer50.filterRGBAll(width, height, leftPixelsMixed);
			int[] rightPixelsBlured = blurer50.filterRGBAll(width, height, rightPixelsMixed);
			for(int p = 0;p<leftPixels.length;p++) {
				leftPixelsMixed[p] = leftPixels[p]&0xFF000000 | leftPixelsBlured[p];
				rightPixelsMixed[p] = rightPixels[p]&0xFF000000 | rightPixelsBlured[p];
			}
			
		    BufferedImage aHMorphL = ImageUtils.createHdr(width, height);
		    BufferedImage aVMorphL = ImageUtils.createHdr(width, height);
		    BufferedImage aHMorphR = ImageUtils.createHdr(width, height);
		    BufferedImage aVMorphR = ImageUtils.createHdr(width, height);
		    zTrg = ImageUtils.createHdr(width, height);
			morphedTrg = ImageUtils.createImage(width, height);
		    if(hMorphL == null) {
		    	hMorphL = aHMorphL;
		    	vMorphL = aVMorphL;
		    	Color aColor = new Color(0.5f, 0.5f, 0.5f);
		    	Graphics aG = hMorphL.getGraphics();
		    	aG.setColor(aColor);
		    	aG.fillRect(0, 0, width, height);
		    	aG.dispose();
		    	aG = vMorphL.getGraphics();
		    	aG.setColor(aColor);
		    	aG.fillRect(0, 0, width, height);
		    	aG.dispose();
		    	hMorphR = aHMorphR;
		    	vMorphR = aVMorphR;
		    	aG = hMorphR.getGraphics();
		    	aG.setColor(aColor);
		    	aG.fillRect(0, 0, width, height);
		    	aG.dispose();
		    	aG = vMorphR.getGraphics();
		    	aG.setColor(aColor);
		    	aG.fillRect(0, 0, width, height);
		    	aG.dispose();
		    }
		    else {
		    	Graphics2D aG = (Graphics2D)aHMorphL.getGraphics();
				aG.setRenderingHint(RenderingHints.KEY_ANTIALIASING,RenderingHints.VALUE_ANTIALIAS_ON);
				aG.setRenderingHint(RenderingHints.KEY_RENDERING,RenderingHints.VALUE_RENDER_QUALITY);
		    	aG.drawImage(hMorphL, 0, 0, width, height, null);
		    	aG.dispose();
		    	aG = (Graphics2D)aVMorphL.getGraphics();
				aG.setRenderingHint(RenderingHints.KEY_ANTIALIASING,RenderingHints.VALUE_ANTIALIAS_ON);
				aG.setRenderingHint(RenderingHints.KEY_RENDERING,RenderingHints.VALUE_RENDER_QUALITY);
		    	aG.drawImage(vMorphL, 0, 0, width, height, null);
		    	aG.dispose();
		    	hMorphL = aHMorphL;
		    	vMorphL = aVMorphL;
		    	aG = (Graphics2D)aHMorphR.getGraphics();
				aG.setRenderingHint(RenderingHints.KEY_ANTIALIASING,RenderingHints.VALUE_ANTIALIAS_ON);
				aG.setRenderingHint(RenderingHints.KEY_RENDERING,RenderingHints.VALUE_RENDER_QUALITY);
		    	aG.drawImage(hMorphR, 0, 0, width, height, null);
		    	aG.dispose();
		    	aG = (Graphics2D)aVMorphR.getGraphics();
				aG.setRenderingHint(RenderingHints.KEY_ANTIALIASING,RenderingHints.VALUE_ANTIALIAS_ON);
				aG.setRenderingHint(RenderingHints.KEY_RENDERING,RenderingHints.VALUE_RENDER_QUALITY);
		    	aG.drawImage(vMorphR, 0, 0, width, height, null);
		    	aG.dispose();
		    	hMorphR = aHMorphR;
		    	vMorphR = aVMorphR;
		    }
		    
		    for(int aDiv = 1;
//		    		aDiv <= 5
		    		width/aDiv > 100/(aStep + 1)
		    		;aDiv++) {
		    	buildMaps(aRightMixed,aLeftMixed,false,aDiv,aStep >= 0);
		    	buildMaps(aLeftMixed,aRightMixed,true,aDiv,aStep >= 0);

		    	int aKey = aSA.getPrevKeyGT(tde.timeLineStack.getTimePosMS());
		    	aSA.setXL(aKey, aSA.getXL(aKey) + moveLeftDx);
		    	aSA.setYL(aKey, aSA.getYL(aKey) + moveLeftDy);
		    	if(aStep > 50) {
		    		//				aSA.wL += moveLeftH;
		    		aSA.setHL(aKey, aSA.getHL(aKey) + moveLeftH);
		    		aSA.setRL(aKey, aSA.getRL(aKey) + moveLeftR);
		    	}

		    	int aClockSize = 100;
		    	int aClockBorder = 10;
		    	BufferedImage aClock = ImageUtils.createImage(aClockSize, aClockSize);
		    	Graphics2D aG = (Graphics2D)aClock.getGraphics();
		    	aG.setColor(Color.WHITE);
		    	aG.fillOval(0, 0, aClockSize, aClockSize);
		    	aG.setColor(Color.GRAY);
		    	aG.fillArc(aClockBorder, aClockBorder, aClockSize-2*aClockBorder, aClockSize-2*aClockBorder,90, -(aStep*360)/aNbSteps);

		    	int aWB = 1280;
		    	int aHB = 720;
		    	BufferedImage aBoard = ImageUtils.createImage(aWB, aHB);
		    	Graphics aBG = aBoard.getGraphics();
		    	aBG.drawImage(aLeftMixed, 0, 0, aWB/2, aHB/2, null);
		    	aBG.drawImage(aRightMixed, aWB/2, 0, aWB/2, aHB/2, null);
		    	aBG.drawImage(hMorphL, 0, aHB/2, aWB/2, aHB/2, null);
		    	aBG.drawImage(morphedTrg, aWB/2, aHB/2,aWB/2, aHB/2, null);
		    	aBG.setColor(Color.WHITE);
		    	int aGrid = 10;//aDiv*2;
		    	for(int t = 0;t<=aGrid;t++) {
		    		aBG.drawLine((int)(t*aWB/aGrid), 0, (int)(t*aWB/aGrid), aHB);
		    		aBG.drawLine(0,(int)(t*aHB/aGrid), aWB, (int)(t*aHB/aGrid));
		    	}
		    	aBG.drawImage(aClock, aWB/2-aClockSize/2, aHB/2-aClockSize/2, aClockSize, aClockSize, null);
		    	aBG.dispose();
		    	leftI = rightI = aBoard;
		    	

		    	tde.gui.display.syncExec(new Runnable() {
		    		@Override
		    		public void run() {
		    			tde.gui.monitorGUI.redraw();
		    			//					try{
		    			//						Thread.sleep(1000);
		    			//					}
		    			//					catch(Throwable t) {
		    			//						t.printStackTrace(System.err);
		    			//					}
		    		}
		    	});
		    }
		}
	}

	void buildMaps(BufferedImage aSrcBI,BufferedImage aTrgBI,boolean aSrcIsLeft,int aDiv,boolean aUseV){
		int[] aSPixs = ((DataBufferInt)aSrcBI.getRaster().getDataBuffer()).getData();
		int[] aTPixs = ((DataBufferInt)aTrgBI.getRaster().getDataBuffer()).getData();
		int[] aMPixs = ((DataBufferInt)morphedTrg.getRaster().getDataBuffer()).getData();

		float aDynamicH = 7.0f;
		float aDynamicV = 14.0f;

		float[] aMorphSrcH = ((DataBufferFloat)(aSrcIsLeft?hMorphL:hMorphR).getRaster().getDataBuffer()).getData();
		float[] aMorphSrcV = ((DataBufferFloat)(aSrcIsLeft?vMorphL:vMorphR).getRaster().getDataBuffer()).getData();
		float[] aMorphTrgH = ((DataBufferFloat)(!aSrcIsLeft?hMorphL:hMorphR).getRaster().getDataBuffer()).getData();
		float[] aMorphTrgV = ((DataBufferFloat)(!aSrcIsLeft?vMorphL:vMorphR).getRaster().getDataBuffer()).getData();
		
		float[] aZTrg = ((DataBufferFloat)zTrg.getRaster().getDataBuffer()).getData();
		int[] aBackX = new int[width*height];
		int[] aBackY = new int[width*height];
		//Init Z target
		for(int y = 0;y<height;y++) {
			for(int x = 0;x<width;x++) {
				aZTrg[y*width*4+x*4] = aZTrg[y*width*4+x*4+1] = aZTrg[y*width*4+x*4+2] = aZTrg[y*width*4+x*4+3] = 1.0f;
				aMPixs[y*width+x] = 0x00000000;
				aBackX[y*width+x] = -1;
				aBackY[y*width+x] = -1;
			}
		}
		//Eval Z target
		for(int y = 0;y<height;y++) {
			for(int x = 0;x<width;x++) {
				float aZs = aMorphSrcH[y*width*4+x*4];
				float aDx = (aSrcIsLeft?1:-1)*(aMorphSrcH[y*width*4+x*4]-0.5f)*width/aDynamicH;//Red
				float aDy = (aMorphSrcV[y*width*4+x*4]-0.5f)*height/aDynamicV;//Red
				int aXd = (int)(x+aDx);
				int aYd = (int)(y+aDy);
				if(aXd < 0 || aXd >= width 
						|| aYd < 0 || aYd >= height) {
					//Out
					continue;
				}
				float aZt = aZTrg[aYd*width*4+aXd*4];
				if(aZs > aZt) {
					//Hidden
					continue;
				}
				aZTrg[aYd*width*4+aXd*4] = aZTrg[aYd*width*4+aXd*4+1] = aZTrg[aYd*width*4+aXd*4+2] = aZs;
			}
		}
		//Draw morphed
		for(int y = 0;y<height;y++) {
			for(int x = 0;x<width;x++) {
				float aZs = aMorphSrcH[y*width*4+x*4];
				float aDx = (aSrcIsLeft?1:-1)*(aMorphSrcH[y*width*4+x*4]-0.5f)*width/aDynamicH;//Red
				float aDy = (aMorphSrcV[y*width*4+x*4]-0.5f)*height/aDynamicV;//Red
				int aXd = (int)(x+aDx);
				int aYd = (int)(y+aDy);
				if(aXd < 0 || aXd >= width 
						|| aYd < 0 || aYd >= height) {
					//Out
					continue;
				}
				float aZt = aZTrg[aYd*width*4+aXd*4];
				if(aZs > aZt) {
					//Hidden
					continue;
				}
				aMPixs[aYd*width+aXd] = aSPixs[y*width+x];
				aBackX[aYd*width+aXd] = x;
				aBackY[aYd*width+aXd] = y;
			}
		}
		//Fill holes
		int[] aMHole = new int[width*height];
		double aTargetFact = 0.0;
		int aMaxHoleSize = 4;
		for(int x = 0;x<width;x++) {
			int aLastY = -1;
			for(int y = 0;y<height;y++) {
				if(aTPixs[y*width+x] == 0) {
					//Clip
					aMHole[y*width+x] = 0;
//					aMPixs[y*width+x] = 0xFFFFFF00;
					aLastY = -1;
				}
				else if(aMPixs[y*width+x] == 0) {
//					aMPixs[y*width+x] = 0xFFFFFF00;
				}
				else {
					aMHole[y*width+x] = aMPixs[y*width+x];
					if(aLastY > 0 && y - aLastY > 1 && y - aLastY <= aMaxHoleSize+1) {
						int aBegPix = aMHole[aLastY*width+x];
						int aBegAlpha =  (aBegPix>>24)&0xFF;
						int aBegR = (aBegPix>>16)&0xFF;
						int aBegG = (aBegPix>>8)&0xFF;
						int aBegB = (aBegPix)&0xFF;

						int aEndPix = aMHole[y*width+x];
						int aEndAlpha =  (aEndPix>>24)&0xFF;
						int aEndR = (aEndPix>>16)&0xFF;
						int aEndG = (aEndPix>>8)&0xFF;
						int aEndB = (aEndPix)&0xFF;
						for(int iy = aLastY+1;iy < y;iy++) {
							int aTPix = aTPixs[iy*width+x];
							int aTAlpha =  (aTPix>>24)&0xFF;
							int aTR = (aTPix>>16)&0xFF;
							int aTG = (aTPix>>8)&0xFF;
							int aTB = (aTPix)&0xFF;

							int aAlphaN = (int)((aBegAlpha*(y - iy)+aEndAlpha*(iy - aLastY))/((double)(y - aLastY)));
							int aRN = (int)((aBegR*(y - iy)+aEndR*(iy - aLastY))/((double)(y - aLastY)));
							int aGN = (int)((aBegG*(y - iy)+aEndG*(iy - aLastY))/((double)(y - aLastY)));
							int aBN = (int)((aBegB*(y - iy)+aEndB*(iy - aLastY))/((double)(y - aLastY)));

							aAlphaN = (int)(aAlphaN*(1.0-aTargetFact)+aTAlpha*aTargetFact);
							aRN = (int)(aRN*(1.0-aTargetFact)+aTR*aTargetFact);
							aGN = (int)(aGN*(1.0-aTargetFact)+aTG*aTargetFact);
							aBN = (int)(aBN*(1.0-aTargetFact)+aTB*aTargetFact);

							aMPixs[iy*width+x] = aMHole[iy*width+x] = (aAlphaN<<24)|(aRN<<16)|(aGN<<8)|aBN;
						}
					}
					aLastY = y;
				}
			}
		}
		aMaxHoleSize = 1000;
		for(int y = 0;y<height;y++) {
			int aLastX = -1;
			for(int x = 0;x<width;x++) {
				if(aTPixs[y*width+x] == 0) {
					//Clip
					aMHole[y*width+x] = 0;
					aMPixs[y*width+x] = 0xFFFFFF00;
					aLastX = -1;
				}
				else if(aMPixs[y*width+x] == 0) {
					aMPixs[y*width+x] = 0xFFFFFF00;
				}
				else {
					aMHole[y*width+x] = aMPixs[y*width+x];
					if(aLastX > 0 && x - aLastX > 1 && x - aLastX <= aMaxHoleSize+1) {
						int aBegPix = aMHole[y*width+aLastX];
						int aBegAlpha =  (aBegPix>>24)&0xFF;
						int aBegR = (aBegPix>>16)&0xFF;
						int aBegG = (aBegPix>>8)&0xFF;
						int aBegB = (aBegPix)&0xFF;

						int aEndPix = aMHole[y*width+x];
						int aEndAlpha =  (aEndPix>>24)&0xFF;
						int aEndR = (aEndPix>>16)&0xFF;
						int aEndG = (aEndPix>>8)&0xFF;
						int aEndB = (aEndPix)&0xFF;
						for(int ix = aLastX+1;ix < x;ix++) {
							int aTPix = aTPixs[y*width+ix];
							int aTAlpha =  (aTPix>>24)&0xFF;
							int aTR = (aTPix>>16)&0xFF;
							int aTG = (aTPix>>8)&0xFF;
							int aTB = (aTPix)&0xFF;

							int aAlphaN = (int)((aBegAlpha*(x - ix)+aEndAlpha*(ix - aLastX))/((double)(x - aLastX)));
							int aRN = (int)((aBegR*(x - ix)+aEndR*(ix - aLastX))/((double)(x - aLastX)));
							int aGN = (int)((aBegG*(x - ix)+aEndG*(ix - aLastX))/((double)(x - aLastX)));
							int aBN = (int)((aBegB*(x - ix)+aEndB*(ix - aLastX))/((double)(x - aLastX)));

							aAlphaN = (int)(aAlphaN*(1.0-aTargetFact)+aTAlpha*aTargetFact);
							aRN = (int)(aRN*(1.0-aTargetFact)+aTR*aTargetFact);
							aGN = (int)(aGN*(1.0-aTargetFact)+aTG*aTargetFact);
							aBN = (int)(aBN*(1.0-aTargetFact)+aTB*aTargetFact);

							aMPixs[y*width+ix] = aMHole[y*width+ix] = (aAlphaN<<24)|(aRN<<16)|(aGN<<8)|aBN; 
						}
					}
					aLastX = x;
				}
			}
		}
		//Profiles
		double[][] aProfMH = new double[aDiv][width];
		double[][] aProfTH = new double[aDiv][width];
		double[][] aProfMV = new double[aDiv][height];
		double[][] aProfTV = new double[aDiv][height];
		for(int aDivY = 0;aDivY<aDiv;aDivY++) {
			for(int x = 0;x<width;x++) {
				aProfMH[aDivY][x] = 0;
				aProfTH[aDivY][x] = 0;
			}
			if(aUseV) {
				for(int y = 0;y<height;y++) {
					aProfMV[aDivY][y] = 0;
					aProfTV[aDivY][y] = 0;
				}
			}
		}
		for(int y = 0;y<height;y++) {
			int aDivY = (int)(y/(height/(double)aDiv));
			for(int x = 0;x<width;x++) {
				int aDivX = (int)(x/(width/(double)aDiv));
				int aPixM = aMHole[y*width+x];
				int aPixT = aTPixs[y*width+x];
				aProfMH[aDivY][x] += (((aPixM>>16)&0xFF)+((aPixM>>8)&0xFF)+((aPixM)&0xFF))/(3.0*255);
				aProfTH[aDivY][x] += (((aPixT>>16)&0xFF)+((aPixT>>8)&0xFF)+((aPixT)&0xFF))/(3.0*255);
				if(aUseV) {
					aProfMV[aDivX][y] += (((aPixM>>16)&0xFF)+((aPixM>>8)&0xFF)+((aPixM)&0xFF))/(3.0*255);
					aProfTV[aDivX][y] += (((aPixT>>16)&0xFF)+((aPixT>>8)&0xFF)+((aPixT)&0xFF))/(3.0*255);
				}
			}
		}
		//Smooth profiles
		double[][] aProfMH2 = new double[aDiv][width];
		double[][] aProfTH2 = new double[aDiv][width];
		double[][] aProfMV2 = new double[aDiv][height];
		double[][] aProfTV2 = new double[aDiv][height];
		for(int aDivXY = 0;aDivXY<aDiv;aDivXY++) {
			aProfMH2[aDivXY][0] = aProfMH[aDivXY][0];
			aProfMH2[aDivXY][width-1] = aProfMH[aDivXY][width-1];
			aProfTH2[aDivXY][0] = aProfTH[aDivXY][0];
			aProfTH2[aDivXY][width-1] = aProfTH[aDivXY][width-1];
			if(aUseV) {
				aProfMV2[aDivXY][0] = aProfMV[aDivXY][0];
				aProfMV2[aDivXY][height-1] = aProfMH[aDivXY][height-1];
				aProfTV2[aDivXY][0] = aProfTV[aDivXY][0];
				aProfTV2[aDivXY][height-1] = aProfTH[aDivXY][height-1];
			}
			for(int aD = -1;aD <= 1;aD++) {
				for(int s = 1;s < width - 1;s++) {
					aProfMH2[aDivXY][s] += aProfMH[aDivXY][s+aD]/3.0;
					aProfTH2[aDivXY][s] += aProfTH[aDivXY][s+aD]/3.0;
				}
				if(aUseV) {
					for(int s = 1;s < height - 1;s++) {
						aProfMV2[aDivXY][s] += aProfMV[aDivXY][s+aD]/3.0;
						aProfTV2[aDivXY][s] += aProfTV[aDivXY][s+aD]/3.0;
					}
				}
			}			
		}
		//Deriv
		double[][] aProfMH3 = new double[aDiv][width];
		double[][] aProfTH3 = new double[aDiv][width];
		double[][] aProfMV3 = new double[aDiv][height];
		double[][] aProfTV3 = new double[aDiv][height];
		for(int aDivXY = 0;aDivXY<aDiv;aDivXY++) {
			aProfMH3[aDivXY][width-1] = 0;
			aProfTH3[aDivXY][width-1] = 0;
			if(aUseV) {
				aProfMV3[aDivXY][height-1] = 0;
				aProfTV3[aDivXY][height-1] = 0;
			}
			for(int s = 0;s < width - 1;s++) {
				aProfMH3[aDivXY][s] = aProfMH2[aDivXY][s+1]-aProfMH2[aDivXY][s];
				aProfTH3[aDivXY][s] = aProfTH2[aDivXY][s+1]-aProfTH2[aDivXY][s];
			}
			if(aUseV) {
				for(int s = 0;s < height - 1;s++) {
					aProfMV3[aDivXY][s] = aProfMV2[aDivXY][s+1]-aProfMV2[aDivXY][s];
					aProfTV3[aDivXY][s] = aProfTV2[aDivXY][s+1]-aProfTV2[aDivXY][s];
				}
			}			
		}
		//Combine
		double aDerivFact = 0.7;
		for(int aDivXY = 0;aDivXY<aDiv;aDivXY++) {
			for(int s = 0;s < width;s++) {
				aProfMH3[aDivXY][s] = aProfMH3[aDivXY][s]*aDerivFact+aProfMH2[aDivXY][s]*(1.0-aDerivFact);
				aProfTH3[aDivXY][s] = aProfTH3[aDivXY][s]*aDerivFact+aProfTH2[aDivXY][s]*(1.0-aDerivFact);
			}
			if(aUseV) {
				for(int s = 0;s < height;s++) {
					aProfMV3[aDivXY][s] = aProfMV3[aDivXY][s]*aDerivFact+aProfMV2[aDivXY][s]*(1.0-aDerivFact);
					aProfTV3[aDivXY][s] = aProfTV3[aDivXY][s]*aDerivFact+aProfTV2[aDivXY][s]*(1.0-aDerivFact);
				}
			}			
		}
		//Eval moves
		float aSpeedH = 100.0f;
		float aSpeedV = 30.0f;
		float aDirectFact = 0.3f;
//		Graphics aMorphedTrgG = morphedTrg.getGraphics();
		for(int aDivY = 0;aDivY<aDiv;aDivY++) {
			int aBoxY1 = (int)(aDivY*(height/(double)aDiv));
			int aBoxY2 = (int)((aDivY+1)*(height/(double)aDiv));
			for(int aDivX = 0;aDivX<aDiv;aDivX++) {
				int aBoxX1 = (int)(aDivX*(width/(double)aDiv));
				int aBoxX2 = (int)((aDivX+1)*(width/(double)aDiv));
				double aMoveH = evalMove(aProfMH3[aDivY], aProfTH3[aDivY], 
						aBoxX1, aBoxX2, width);
				double aMoveV = aUseV ? evalMove(aProfMV3[aDivX], aProfTV3[aDivX], 
						aBoxY1, aBoxY2, height)
						:0;
				//Morph
				for(int y = aBoxY1;y < aBoxY2;y++) {
					for(int x = aBoxX1;x < aBoxX2;x++) {
						float aMTrgH = (float)(aDirectFact*aSpeedH*(aSrcIsLeft?1:-1)*aMoveH/(float)width); 
						float aMTrgHNew = aMorphTrgH[y*width*4+x*4] + aMTrgH*aDynamicH;
						aMTrgHNew = (aMTrgHNew < 0 ? 0 : (aMTrgHNew > 1 ? 1 : aMTrgHNew));
						aMorphTrgH[y*width*4+x*4] = 
								aMorphTrgH[y*width*4+x*4+1] = 
										aMorphTrgH[y*width*4+x*4+2] = aMTrgHNew;
						
						if(aUseV) {
							float aMTrgV = -(float)(aDirectFact*aSpeedV*aMoveV/(float)height); 
							float aMTrgVNew = aMorphTrgV[y*width*4+x*4] + aMTrgV*aDynamicV;
							aMTrgVNew = (aMTrgVNew < 0 ? 0 : (aMTrgVNew > 1 ? 1 : aMTrgVNew));
							aMorphTrgV[y*width*4+x*4] = 
									aMorphTrgV[y*width*4+x*4+1] = 
											aMorphTrgV[y*width*4+x*4+2] = aMTrgVNew;
						}

						if(aBackX[y*width+x] < 0 || aBackY[y*width+x] < 0) {
							continue;
						}
//						aMorphedTrgG.drawString(""+aMoveH, aBoxX1+10, aBoxY1+10);
						float aMSrcH = (float)(aSpeedH*(aSrcIsLeft?1:-1)*aMoveH/(float)width); 
						float aMSrcHNew = aMorphSrcH[aBackY[y*width+x]*width*4+aBackX[y*width+x]*4] + aMSrcH*aDynamicH;
						aMSrcHNew = (aMSrcHNew < 0 ? 0 : (aMSrcHNew > 1 ? 1 : aMSrcHNew));
						aMorphSrcH[aBackY[y*width+x]*width*4+aBackX[y*width+x]*4] = 
								aMorphSrcH[aBackY[y*width+x]*width*4+aBackX[y*width+x]*4+1] = 
								aMorphSrcH[aBackY[y*width+x]*width*4+aBackX[y*width+x]*4+2] = aMSrcHNew;
						
						if(aUseV) {
							float aMSrcV = (float)(aSpeedV*aMoveV/(float)height); 
							float aMSrcVNew = aMorphSrcV[aBackY[y*width+x]*width*4+aBackX[y*width+x]*4] + aMSrcV*aDynamicV;
							aMSrcVNew = (aMSrcVNew < 0 ? 0 : (aMSrcVNew > 1 ? 1 : aMSrcVNew));
							aMorphSrcV[aBackY[y*width+x]*width*4+aBackX[y*width+x]*4] =
									aMorphSrcV[aBackY[y*width+x]*width*4+aBackX[y*width+x]*4+1] = 
									aMorphSrcV[aBackY[y*width+x]*width*4+aBackX[y*width+x]*4+2] = aMSrcVNew;
						}
					}
				}
			}
		}
		//Draw curves
//		for(int aDivY = 0;aDivY<aDiv;aDivY++) {
//			for(int x = 0;x<width;x++) {
//				int aY1 = ((int)(aDivY*(height/(double)aDiv)+aProfMH3[aDivY][x]));
//				int aY2 = ((int)(aDivY*(height/(double)aDiv)+aProfTH3[aDivY][x]));
//				if(aY1 < 0 || aY2 < 0 || aY1 >= height || aY2 >= height) {
//					continue;
//				}
//				aMPixs[aY1*width+x] = 0xFF000000;
//				aMPixs[aY2*width+x] = 0xFF000000;
//				aMPixs[aY1*width+x] |= 0xFFFF0000;
//				aMPixs[aY2*width+x] |= 0xFF00FFFF;
//			}
//		}
		
		//Blur
    	float[] aMSHBlured = blurer50.filterRGBAllHdr(width, height, aMorphSrcH);
    	float[] aMSVBlured = blurer50.filterRGBAllHdr(width, height, aMorphSrcV);
    	float[] aMTHBlured = blurer50.filterRGBAllHdr(width, height, aMorphTrgH);
    	float[] aMTVBlured = blurer50.filterRGBAllHdr(width, height, aMorphTrgV);
    	float aBlurSpeedSrc = 0.2f;
    	float aBlurSpeedTrg = 0.1f;
		for(int y = 0;y<height;y++) {
			for(int x = 0;x<width;x++) {
				float aBlurSrc = 
						1f;
//						1.0f-((aSrcIsLeft?(leftPixelsEdges[y*width+x]&0xFF)/leftMaxEdge:(rightPixelsEdges[y*width+x]&0xFF)/rightMaxEdge));
				aBlurSrc *= aBlurSpeedSrc;
				aMorphSrcH[y*width*4+x*4] = aMSHBlured[y*width*4+x*4]*aBlurSrc+aMorphSrcH[y*width*4+x*4]*(1.0f-aBlurSrc);
				aMorphSrcH[y*width*4+x*4+1] = aMSHBlured[y*width*4+x*4+1]*aBlurSrc+aMorphSrcH[y*width*4+x*4+1]*(1.0f-aBlurSrc);
				aMorphSrcH[y*width*4+x*4+2] = aMSHBlured[y*width*4+x*4+2]*aBlurSrc+aMorphSrcH[y*width*4+x*4+2]*(1.0f-aBlurSrc);
				aMorphSrcV[y*width*4+x*4] = aMSVBlured[y*width*4+x*4]*aBlurSrc+aMorphSrcV[y*width*4+x*4]*(1.0f-aBlurSrc);
				aMorphSrcV[y*width*4+x*4+1] = aMSVBlured[y*width*4+x*4+1]*aBlurSrc+aMorphSrcV[y*width*4+x*4+1]*(1.0f-aBlurSrc);
				aMorphSrcV[y*width*4+x*4+2] = aMSVBlured[y*width*4+x*4+2]*aBlurSrc+aMorphSrcV[y*width*4+x*4+2]*(1.0f-aBlurSrc);
				float aBlurTrg = 
						1f;
//						1.0f-((!aSrcIsLeft?(leftPixelsEdges[y*width+x]&0xFF)/leftMaxEdge:(rightPixelsEdges[y*width+x]&0xFF)/rightMaxEdge));
				aBlurTrg *= aBlurSpeedTrg;
				aMorphTrgH[y*width*4+x*4] = aMTHBlured[y*width*4+x*4]*aBlurTrg+aMorphTrgH[y*width*4+x*4]*(1.0f-aBlurTrg);
				aMorphTrgH[y*width*4+x*4+1] = aMTHBlured[y*width*4+x*4+1]*aBlurTrg+aMorphTrgH[y*width*4+x*4+1]*(1.0f-aBlurTrg);
				aMorphTrgH[y*width*4+x*4+2] = aMTHBlured[y*width*4+x*4+2]*aBlurTrg+aMorphTrgH[y*width*4+x*4+2]*(1.0f-aBlurTrg);
				aMorphTrgV[y*width*4+x*4] = aMTVBlured[y*width*4+x*4]*aBlurTrg+aMorphTrgV[y*width*4+x*4]*(1.0f-aBlurTrg);
				aMorphTrgV[y*width*4+x*4+1] = aMTVBlured[y*width*4+x*4+1]*aBlurTrg+aMorphTrgV[y*width*4+x*4+1]*(1.0f-aBlurTrg);
				aMorphTrgV[y*width*4+x*4+2] = aMTVBlured[y*width*4+x*4+2]*aBlurTrg+aMorphTrgV[y*width*4+x*4+2]*(1.0f-aBlurTrg);
			}
    	}

		//Clip variations
//		float aClipTol = 0.05f;
//		for(int y = 0;y<height;y++) {
//			for(int x = 1;x<width;x++) {
//				float aEdgeSrc = ((aSrcIsLeft?(leftPixelsEdges[y*width+x]&0xFF)/leftMaxEdge:(rightPixelsEdges[y*width+x]&0xFF)/rightMaxEdge));
//				aEdgeSrc += aClipTol;
//				if(aMorphSrcH[y*width*4+x*4] > aMorphSrcH[y*width*4+(x-1)*4] + aEdgeSrc) {
//					aMorphSrcH[y*width*4+x*4] = aMorphSrcH[y*width*4+(x-1)*4] + aEdgeSrc;
//				}
//				if(aMorphSrcH[y*width*4+x*4] < aMorphSrcH[y*width*4+(x-1)*4] - aEdgeSrc) {
//					aMorphSrcH[y*width*4+x*4] = aMorphSrcH[y*width*4+(x-1)*4] - aEdgeSrc;
//				}
//				aMorphSrcH[y*width*4+x*4+1] = aMorphSrcH[y*width*4+x*4+2] = aMorphSrcH[y*width*4+x*4];
//				if(aMorphSrcV[y*width*4+x*4] > aMorphSrcV[y*width*4+(x-1)*4] + aEdgeSrc) {
//					aMorphSrcV[y*width*4+x*4] = aMorphSrcV[y*width*4+(x-1)*4] + aEdgeSrc;
//				}
//				if(aMorphSrcV[y*width*4+x*4] < aMorphSrcV[y*width*4+(x-1)*4] - aEdgeSrc) {
//					aMorphSrcV[y*width*4+x*4] = aMorphSrcV[y*width*4+(x-1)*4] - aEdgeSrc;
//				}
//				aMorphSrcV[y*width*4+x*4+1] = aMorphSrcV[y*width*4+x*4+2] = aMorphSrcH[y*width*4+x*4];
//				float aEdgeTrg = ((!aSrcIsLeft?(leftPixelsEdges[y*width+x]&0xFF)/leftMaxEdge:(rightPixelsEdges[y*width+x]&0xFF)/rightMaxEdge));
//				aEdgeTrg += aClipTol;
//				if(aMorphTrgH[y*width*4+x*4] > aMorphTrgH[y*width*4+(x-1)*4] + aEdgeTrg) {
//					aMorphTrgH[y*width*4+x*4] = aMorphTrgH[y*width*4+(x-1)*4] + aEdgeTrg;
//				}
//				if(aMorphTrgH[y*width*4+x*4] < aMorphTrgH[y*width*4+(x-1)*4] - aEdgeTrg) {
//					aMorphTrgH[y*width*4+x*4] = aMorphTrgH[y*width*4+(x-1)*4] - aEdgeTrg;
//				}
//				aMorphTrgH[y*width*4+x*4+1] = aMorphTrgH[y*width*4+x*4+2] = aMorphTrgH[y*width*4+x*4];
//				if(aMorphTrgV[y*width*4+x*4] > aMorphTrgV[y*width*4+(x-1)*4] + aEdgeTrg) {
//					aMorphTrgV[y*width*4+x*4] = aMorphTrgV[y*width*4+(x-1)*4] + aEdgeTrg;
//				}
//				if(aMorphTrgV[y*width*4+x*4] < aMorphTrgV[y*width*4+(x-1)*4] - aEdgeTrg) {
//					aMorphTrgV[y*width*4+x*4] = aMorphTrgV[y*width*4+(x-1)*4] - aEdgeTrg;
//				}
//				aMorphTrgV[y*width*4+x*4+1] = aMorphTrgV[y*width*4+x*4+2] = aMorphTrgH[y*width*4+x*4];
//			}
//		}
//		for(int x = 0;x<width;x++) {
//			for(int y = 1;y<height;y++) {
//				float aEdgeSrc = ((aSrcIsLeft?(leftPixelsEdges[y*width+x]&0xFF)/leftMaxEdge:(rightPixelsEdges[y*width+x]&0xFF)/rightMaxEdge));
//				aEdgeSrc += aClipTol;
//				if(aMorphSrcH[y*width*4+x*4] > aMorphSrcH[(y-1)*width*4+x*4] + aEdgeSrc) {
//					aMorphSrcH[y*width*4+x*4] = aMorphSrcH[(y-1)*width*4+x*4] + aEdgeSrc;
//				}
//				if(aMorphSrcH[y*width*4+x*4] < aMorphSrcH[(y-1)*width*4+x*4] - aEdgeSrc) {
//					aMorphSrcH[y*width*4+x*4] = aMorphSrcH[(y-1)*width*4+x*4] - aEdgeSrc;
//				}
//				aMorphSrcH[y*width*4+x*4+1] = aMorphSrcH[(y-1)*width*4+x*4+2] = aMorphSrcH[y*width*4+x*4];
//				if(aMorphSrcV[y*width*4+x*4] > aMorphSrcV[(y-1)*width*4+x*4] + aEdgeSrc) {
//					aMorphSrcV[y*width*4+x*4] = aMorphSrcV[(y-1)*width*4+x*4] + aEdgeSrc;
//				}
//				if(aMorphSrcV[y*width*4+x*4] < aMorphSrcV[(y-1)*width*4+x*4] - aEdgeSrc) {
//					aMorphSrcV[y*width*4+x*4] = aMorphSrcV[(y-1)*width*4+x*4] - aEdgeSrc;
//				}
//				aMorphSrcV[y*width*4+x*4+1] = aMorphSrcV[y*width*4+x*4+2] = aMorphSrcH[y*width*4+x*4];
//				float aEdgeTrg = ((!aSrcIsLeft?(leftPixelsEdges[y*width+x]&0xFF)/leftMaxEdge:(rightPixelsEdges[y*width+x]&0xFF)/rightMaxEdge));
//				aEdgeTrg += aClipTol;
//				if(aMorphTrgH[y*width*4+x*4] > aMorphTrgH[(y-1)*width*4+x*4] + aEdgeTrg) {
//					aMorphTrgH[y*width*4+x*4] = aMorphTrgH[(y-1)*width*4+x*4] + aEdgeTrg;
//				}
//				if(aMorphTrgH[y*width*4+x*4] < aMorphTrgH[(y-1)*width*4+x*4] - aEdgeTrg) {
//					aMorphTrgH[y*width*4+x*4] = aMorphTrgH[(y-1)*width*4+x*4] - aEdgeTrg;
//				}
//				aMorphTrgH[y*width*4+x*4+1] = aMorphTrgH[(y-1)*width*4+x*4+2] = aMorphTrgH[y*width*4+x*4];
//				if(aMorphTrgV[y*width*4+x*4] > aMorphTrgV[(y-1)*width*4+x*4] + aEdgeTrg) {
//					aMorphTrgV[y*width*4+x*4] = aMorphTrgV[(y-1)*width*4+x*4] + aEdgeTrg;
//				}
//				if(aMorphTrgV[y*width*4+x*4] < aMorphTrgV[(y-1)*width*4+x*4] - aEdgeTrg) {
//					aMorphTrgV[y*width*4+x*4] = aMorphTrgV[(y-1)*width*4+x*4] - aEdgeTrg;
//				}
//				aMorphTrgV[y*width*4+x*4+1] = aMorphTrgV[y*width*4+x*4+2] = aMorphTrgH[y*width*4+x*4];
//			}
//		}
//		for(int y = 0;y<height;y++) {
//			for(int x = width-2;x>=0;x--) {
//				float aEdgeSrc = ((aSrcIsLeft?(leftPixelsEdges[y*width+x]&0xFF)/leftMaxEdge:(rightPixelsEdges[y*width+x]&0xFF)/rightMaxEdge));
//				aEdgeSrc += 0.01f;
//				if(aMorphSrcH[y*width*4+x*4] > aMorphSrcH[y*width*4+(x+1)*4] + aEdgeSrc) {
//					aMorphSrcH[y*width*4+x*4] = aMorphSrcH[y*width*4+(x+1)*4] + aEdgeSrc;
//				}
//				if(aMorphSrcH[y*width*4+x*4] < aMorphSrcH[y*width*4+(x+1)*4] - aEdgeSrc) {
//					aMorphSrcH[y*width*4+x*4] = aMorphSrcH[y*width*4+(x+1)*4] - aEdgeSrc;
//				}
//				aMorphSrcH[y*width*4+x*4+1] = aMorphSrcH[y*width*4+x*4+2] = aMorphSrcH[y*width*4+x*4];
//				if(aMorphSrcV[y*width*4+x*4] > aMorphSrcV[y*width*4+(x+1)*4] + aEdgeSrc) {
//					aMorphSrcV[y*width*4+x*4] = aMorphSrcV[y*width*4+(x+1)*4] + aEdgeSrc;
//				}
//				if(aMorphSrcV[y*width*4+x*4] < aMorphSrcV[y*width*4+(x+1)*4] - aEdgeSrc) {
//					aMorphSrcV[y*width*4+x*4] = aMorphSrcV[y*width*4+(x+1)*4] - aEdgeSrc;
//				}
//				aMorphSrcV[y*width*4+x*4+1] = aMorphSrcV[y*width*4+x*4+2] = aMorphSrcH[y*width*4+x*4];
//				float aEdgeTrg = ((!aSrcIsLeft?(leftPixelsEdges[y*width+x]&0xFF)/leftMaxEdge:(rightPixelsEdges[y*width+x]&0xFF)/rightMaxEdge));
//				aEdgeTrg += 0.01f;
//				if(aMorphTrgH[y*width*4+x*4] > aMorphTrgH[y*width*4+(x+1)*4] + aEdgeTrg) {
//					aMorphTrgH[y*width*4+x*4] = aMorphTrgH[y*width*4+(x+1)*4] + aEdgeTrg;
//				}
//				if(aMorphTrgH[y*width*4+x*4] < aMorphTrgH[y*width*4+(x+1)*4] - aEdgeTrg) {
//					aMorphTrgH[y*width*4+x*4] = aMorphTrgH[y*width*4+(x+1)*4] - aEdgeTrg;
//				}
//				aMorphTrgH[y*width*4+x*4+1] = aMorphTrgH[y*width*4+x*4+2] = aMorphTrgH[y*width*4+x*4];
//				if(aMorphTrgV[y*width*4+x*4] > aMorphTrgV[y*width*4+(x+1)*4] + aEdgeTrg) {
//					aMorphTrgV[y*width*4+x*4] = aMorphTrgV[y*width*4+(x+1)*4] + aEdgeTrg;
//				}
//				if(aMorphTrgV[y*width*4+x*4] < aMorphTrgV[y*width*4+(x+1)*4] - aEdgeTrg) {
//					aMorphTrgV[y*width*4+x*4] = aMorphTrgV[y*width*4+(x+1)*4] - aEdgeTrg;
//				}
//				aMorphTrgV[y*width*4+x*4+1] = aMorphTrgV[y*width*4+x*4+2] = aMorphTrgH[y*width*4+x*4];
//			}
//		}
//		for(int x = 0;x<width;x++) {
//			for(int y = height-2;y>=0;y--) {
//				float aEdgeSrc = ((aSrcIsLeft?(leftPixelsEdges[y*width+x]&0xFF)/leftMaxEdge:(rightPixelsEdges[y*width+x]&0xFF)/rightMaxEdge));
//				aEdgeSrc += 0.01f;
//				if(aMorphSrcH[y*width*4+x*4] > aMorphSrcH[(y+1)*width*4+x*4] + aEdgeSrc) {
//					aMorphSrcH[y*width*4+x*4] = aMorphSrcH[(y+1)*width*4+x*4] + aEdgeSrc;
//				}
//				if(aMorphSrcH[y*width*4+x*4] < aMorphSrcH[(y+1)*width*4+x*4] - aEdgeSrc) {
//					aMorphSrcH[y*width*4+x*4] = aMorphSrcH[(y+1)*width*4+x*4] - aEdgeSrc;
//				}
//				aMorphSrcH[y*width*4+x*4+1] = aMorphSrcH[(y+1)*width*4+x*4+2] = aMorphSrcH[y*width*4+x*4];
//				if(aMorphSrcV[y*width*4+x*4] > aMorphSrcV[(y+1)*width*4+x*4] + aEdgeSrc) {
//					aMorphSrcV[y*width*4+x*4] = aMorphSrcV[(y+1)*width*4+x*4] + aEdgeSrc;
//				}
//				if(aMorphSrcV[y*width*4+x*4] < aMorphSrcV[(y+1)*width*4+x*4] - aEdgeSrc) {
//					aMorphSrcV[y*width*4+x*4] = aMorphSrcV[(y+1)*width*4+x*4] - aEdgeSrc;
//				}
//				aMorphSrcV[y*width*4+x*4+1] = aMorphSrcV[y*width*4+x*4+2] = aMorphSrcH[y*width*4+x*4];
//				float aEdgeTrg = ((!aSrcIsLeft?(leftPixelsEdges[y*width+x]&0xFF)/leftMaxEdge:(rightPixelsEdges[y*width+x]&0xFF)/rightMaxEdge));
//				aEdgeTrg += 0.01f;
//				if(aMorphTrgH[y*width*4+x*4] > aMorphTrgH[(y+1)*width*4+x*4] + aEdgeTrg) {
//					aMorphTrgH[y*width*4+x*4] = aMorphTrgH[(y+1)*width*4+x*4] + aEdgeTrg;
//				}
//				if(aMorphTrgH[y*width*4+x*4] < aMorphTrgH[(y+1)*width*4+x*4] - aEdgeTrg) {
//					aMorphTrgH[y*width*4+x*4] = aMorphTrgH[(y+1)*width*4+x*4] - aEdgeTrg;
//				}
//				aMorphTrgH[y*width*4+x*4+1] = aMorphTrgH[(y+1)*width*4+x*4+2] = aMorphTrgH[y*width*4+x*4];
//				if(aMorphTrgV[y*width*4+x*4] > aMorphTrgV[(y+1)*width*4+x*4] + aEdgeTrg) {
//					aMorphTrgV[y*width*4+x*4] = aMorphTrgV[(y+1)*width*4+x*4] + aEdgeTrg;
//				}
//				if(aMorphTrgV[y*width*4+x*4] < aMorphTrgV[(y+1)*width*4+x*4] - aEdgeTrg) {
//					aMorphTrgV[y*width*4+x*4] = aMorphTrgV[(y+1)*width*4+x*4] - aEdgeTrg;
//				}
//				aMorphTrgV[y*width*4+x*4+1] = aMorphTrgV[y*width*4+x*4+2] = aMorphTrgH[y*width*4+x*4];
//			}
//		}
	}
	
	double evalMove(double[] aProfM,double[] aProfT,int aBeg,int aEnd,int aMax) {
		double aDiff = -1;
		int aDec = 0;
		for(int aD = -1;aD <= 1;aD++) {
			double aDiffD = 0;
			for(int p = aBeg+1;p<aEnd-1;p++) {
				int pp = p + aD;
				aDiffD += Math.abs(aProfM[p]-aProfT[pp]);
			}
			if(aDiff < 0 || aDiffD < aDiff || (aDiffD == aDiff && aD == 0)) {
				aDiff = aDiffD;
				aDec = aD;
			}
		}
		return aDec*aDiff/((aEnd-aBeg)*255);
	}
}
