package com.cubaix.TDenlive.processors;

import java.awt.Color;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.io.BufferedWriter;
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

public class AutoAligner extends Processor {
	BufferedImage leftIMedia = null;
	BufferedImage rightIMedia = null;
	BufferedImage leftI = null;
	BufferedImage rightI = null;
	
	EdgeDetector edgeDetector = null;
	
    double[] leftHSum;
    double[] rightHSum;
    double[] leftVSum;
    double[] rightVSum;
    double[] leftVSumL;
    double[] leftVSumR;
    double[] rightVSumL;
    double[] rightVSumR;
    int width;
    int height;

    double smoothWidth;

    double moveLeftDx,moveLeftDy,moveLeftR,moveLeftH;

	public AutoAligner(TDenlive aTDe,Media aTargetMedia) {
		super(aTDe,aTargetMedia);
		edgeDetector = new EdgeDetector(tde,aTargetMedia);
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
		int aStart = 300;
		tde.config.processingResValues[TDConfig.PROCESSING_MODE_AUTOALIGN] = aStart;
		double aSteps = (aCurrentRes-aStart)/100.0;
		for(int aStep = 0;aStep<=100;aStep++) {
			tde.config.processingResValues[TDConfig.PROCESSING_MODE_AUTOALIGN] = (int)(aStart+aStep*aSteps);

			rebuildImages(aMedia);

			int aWidth = leftIMedia.getWidth();
			int aHeight = leftIMedia.getHeight();
			int[] leftPixels = ((DataBufferInt)leftIMedia.getRaster().getDataBuffer()).getData();
			int[] rightPixels = ((DataBufferInt)rightIMedia.getRaster().getDataBuffer()).getData();
			int[] leftPixelsEdges = edgeDetector.filterRGBAll(aWidth, aHeight, leftPixels);
			int[] rightPixelsEdges = edgeDetector.filterRGBAll(aWidth, aHeight, rightPixels);
			BufferedImage aLeftNew = ImageUtils.createImage(aWidth, aHeight);
			BufferedImage aRightNew = ImageUtils.createImage(aWidth, aHeight);
			int[] leftPixelsNew = ((DataBufferInt)aLeftNew.getRaster().getDataBuffer()).getData();
			int[] rightPixelsNew = ((DataBufferInt)aRightNew.getRaster().getDataBuffer()).getData();
			for(int p = 0;p<leftPixels.length;p++) {
				leftPixelsNew[p] = leftPixelsEdges[p];
				rightPixelsNew[p] = rightPixelsEdges[p];
			}
			
			buildProfiles(aLeftNew,aRightNew);
			evalMoves();
			
			int aKey = aSA.getPrevKeyGT(tde.timeLineStack.getTimePosMS());
			aSA.setXL(aKey, aSA.getXL(aKey) + moveLeftDx);
			aSA.setYL(aKey, aSA.getYL(aKey) + moveLeftDy);
			if(aStep > 50) {
//				aSA.wL += moveLeftH;
				aSA.setHL(aKey, aSA.getHL(aKey) + moveLeftH);
				aSA.setRL(aKey, aSA.getRL(aKey) + moveLeftR);
			}
			
			int aClockSize = 300;
			int aClockBorder = 10;
			BufferedImage aClock = ImageUtils.createImage(aClockSize, aClockSize);
			Graphics2D aG = (Graphics2D)aClock.getGraphics();
//			aG.setRenderingHint(RenderingHints.KEY_ANTIALIASING,RenderingHints.VALUE_ANTIALIAS_ON);
//			aG.setRenderingHint(RenderingHints.KEY_RENDERING,RenderingHints.VALUE_RENDER_QUALITY);
			aG.setColor(Color.WHITE);
			aG.fillOval(0, 0, aClockSize, aClockSize);
			aG.setColor(Color.GRAY);
			aG.fillArc(aClockBorder, aClockBorder, aClockSize-2*aClockBorder, aClockSize-2*aClockBorder,90, -aStep*360/100);
//			String aProgress = aStep+"%";
//			Rectangle2D aR = aG.getFontMetrics().getStringBounds(aProgress, aG);
//			aG.setColor(Color.BLACK);
//			aG.drawString(aProgress, (int)(aClockSize/2.0-aR.getWidth()/2.0), (int)(aClockSize/2.0+aR.getHeight()));
			int aSize = Math.max(aWidth, aHeight)/10;
			Graphics2D aGL = (Graphics2D)aLeftNew.getGraphics();
			aGL.drawImage(aClock, aWidth-2*aSize, aHeight-2*aSize, aSize, aSize, null);
			Graphics2D aGR = (Graphics2D)aRightNew.getGraphics();
			aGR.drawImage(aClock, aWidth-2*aSize, aHeight-2*aSize, aSize, aSize, null);
			
			
			leftI = aLeftNew;
			rightI = aRightNew;
			
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

	void buildProfiles(BufferedImage aLeftBI,BufferedImage aRightBI){
		width = aLeftBI.getWidth();
		height = aLeftBI.getHeight();

		int[] leftPixels;// = new int[hSumSize * vSumSize];
		int[] rightPixels;// = new int[hSumSize * vSumSize];

		leftPixels = ((DataBufferInt)aLeftBI.getRaster().getDataBuffer()).getData();
		rightPixels = ((DataBufferInt)aRightBI.getRaster().getDataBuffer()).getData();

		int i,j;
		int aRGB;
		int red;
		int green;
		int blue;
		double lum;

		int hBI,vBI,xBI,yBI;
		hBI = aLeftBI.getWidth();
		vBI = aLeftBI.getHeight();
		xBI = 0;
		yBI = 0;

		leftHSum = new double[width];
		leftVSum = new double[height];
		rightHSum = new double[width];
		rightVSum = new double[height];
		leftVSumL = new double[height];
		leftVSumR = new double[height];
		rightVSumL = new double[height];
		rightVSumR = new double[height];

		for(j = 0;j < height;j++){
			leftVSum[j] = 0;
			rightVSum[j] = 0;
			leftVSumL[j] = 0;
			rightVSumL[j] = 0;
			leftVSumR[j] = 0;
			rightVSumR[j] = 0;
		}
		for (i = 0; i < width; i++){
			leftHSum[i]  = 0;
			rightHSum[i] = 0;
		}

		for(j = 0;j < height;j++){
			for (i = 0; i < width; i++){
				aRGB = leftPixels[(i + xBI) + hBI * (j + yBI)];
				red   = (aRGB >> 16) & 0xff;
				green = (aRGB >>  8) & 0xff;
				blue  = (aRGB      ) & 0xff;

				lum = 2 * red + green + blue;

				leftHSum[i] += lum / (double)width;
				leftVSum[j] += lum / (double)height;

				if(i < width / 2){
					leftVSumL[j] += lum / (double)(width / 2);
				}
				else{
					leftVSumR[j] += lum / (double)(width / 2);
				}

				aRGB = rightPixels[(i + xBI) + hBI * (j + yBI)];
				red   = (aRGB >> 16) & 0xff;
				green = (aRGB >>  8) & 0xff;
				blue  = (aRGB      ) & 0xff;

				lum = 2 * red + green + blue;

				rightHSum[i] += lum / (double)width;
				rightVSum[j] += lum / (double)height;

				if(i < width / 2){
					rightVSumL[j] += lum / (double)(width / 2);
				}
				else{
					rightVSumR[j] += lum / (double)(width / 2);
				}
			}
		}
		
		Graphics aGL = aLeftBI.getGraphics();
		Graphics aGR = aRightBI.getGraphics();
		aGL.setColor(Color.WHITE);
		aGR.setColor(Color.WHITE);
		for(int x = 1;x < width;x++) {
			aGL.drawLine(x-1, (int)leftHSum[x-1], x, (int)leftHSum[x]);
			aGR.drawLine(x-1, (int)rightHSum[x-1], x, (int)rightHSum[x]);
		}
		for(int y = 1;y < height;y++) {
			aGL.drawLine((int)leftVSum[y-1],y-1, (int)leftVSum[y],y);
			aGR.drawLine((int)rightVSum[y-1],y-1, (int)rightVSum[y],y);
		}
	}

	void evalMoves() {
		int i,j;
		double hLRDiff,hLRDiffP,hLRDiffM;
		double vLRDiff,vLRDiffP,vLRDiffM;
		double aV = 0.0,aV1 = 0.0,aV2 = 0.0;

		//Translations
		vLRDiff = vLRDiffP = vLRDiffM = 0;
		for(j = 1;j < height - 1;j++){
			aV = rightVSum[j] - leftVSum[j];
			vLRDiff += (aV >= 0) ? aV : -aV;
			aV = rightVSum[j] - leftVSum[j + 1];
			vLRDiffP += (aV >= 0) ? aV : -aV;
			aV = rightVSum[j] - leftVSum[j - 1];
			vLRDiffM += (aV >= 0) ? aV : -aV;
		}

		hLRDiff = hLRDiffP = hLRDiffM = 0;
		for (i = 1; i < width - 1; i++){
			aV = rightHSum[i] - leftHSum[i];
			hLRDiff += (aV >= 0) ? aV : -aV;
			aV = rightHSum[i] - leftHSum[i + 1];
			hLRDiffP += (aV >= 0) ? aV : -aV;
			aV = rightHSum[i] - leftHSum[i - 1];
			hLRDiffM += (aV >= 0) ? aV : -aV;
		}

		moveLeftDy = 0;
		if((vLRDiff > vLRDiffP && vLRDiff > vLRDiffM) || (vLRDiff < vLRDiffP && vLRDiff < vLRDiffM)){
			//Min pos
		}
		else if(vLRDiffM < vLRDiffP){
			moveLeftDy = +1.0 / leftI.getHeight(null);
		}
		else if(vLRDiffM > vLRDiffP){
			moveLeftDy = -1.0 / leftI.getHeight(null);
		}

		moveLeftDx = 0;
		if((hLRDiff > hLRDiffP && hLRDiff > hLRDiffM) || (hLRDiff < hLRDiffP && hLRDiff < hLRDiffM)){
			//Min pos
		}
		else if(hLRDiffM < hLRDiffP){
			moveLeftDx = +1.0 / leftI.getWidth(null);
		}
		else if(hLRDiffM > hLRDiffP){
			moveLeftDx = -1.0 / leftI.getWidth(null);
		}

		//Rotations
		vLRDiff = vLRDiffP = vLRDiffM = 0;
		for(j = 1;j < height - 1;j++){
			aV1 = rightVSumR[j] - leftVSumR[j];
			aV2 = rightVSumL[j] - leftVSumL[j];
			vLRDiff += ((aV1 >= 0) ? aV1 : -aV1) - ((aV2 >= 0) ? aV2 : -aV2);
			aV1 = rightVSumR[j] - leftVSumR[j + 1];
			aV2 = rightVSumL[j] - leftVSumL[j + 1];
			vLRDiffP += ((aV1 >= 0) ? aV1 : -aV1) - ((aV2 >= 0) ? aV2 : -aV2);
			aV1 = rightVSumR[j] - leftVSumR[j - 1];
			aV2 = rightVSumL[j] - leftVSumL[j - 1];
			vLRDiffM += ((aV1 >= 0) ? aV1 : -aV1) - ((aV2 >= 0) ? aV2 : -aV2);
		}

		moveLeftR = 0;
		if((vLRDiff > vLRDiffP && vLRDiff > vLRDiffM) || (vLRDiff < vLRDiffP && vLRDiff < vLRDiffM)){
			//Min pos
		}
		else if(vLRDiffM < vLRDiffP){
			moveLeftR = Math.PI / leftI.getWidth(null);
		}
		else if(vLRDiffM > vLRDiffP){
			moveLeftR = -Math.PI / leftI.getWidth(null);
		}

		//Dilatations
		vLRDiff = vLRDiffP = vLRDiffM = 0;
		for(j = 1;j < (height / 2) - 1;j++){
			aV1 = rightVSum[j] - leftVSum[j];
			aV2 = rightVSum[j + height / 2] - leftVSum[j + height / 2];
			vLRDiff += ((aV1 >= 0) ? aV1 : -aV1) + ((aV2 >= 0) ? aV2 : -aV2);
			aV1 = rightVSum[j] - leftVSum[j - 1];
			aV2 = rightVSum[j + height / 2] - leftVSum[j + 1 + height / 2];
			vLRDiffP += ((aV1 >= 0) ? aV1 : -aV1) + ((aV2 >= 0) ? aV2 : -aV2);
			aV1 = rightVSum[j] - leftVSum[j + 1];
			aV2 = rightVSum[j + height / 2] - leftVSum[j - 1 + height / 2];
			vLRDiffM += ((aV1 >= 0) ? aV1 : -aV1) + ((aV2 >= 0) ? aV2 : -aV2);
		}

		moveLeftH = 0;
		if((vLRDiff > vLRDiffP && vLRDiff > vLRDiffM) || (vLRDiff < vLRDiffP && vLRDiff < vLRDiffM)){
			//Min pos
		}
		else if(vLRDiffM < vLRDiffP){
			moveLeftH = +1.0 / leftI.getWidth(null);
		}
		else if(vLRDiffM > vLRDiffP){
			moveLeftH = -1.0 / leftI.getWidth(null);
		}
	}
}
