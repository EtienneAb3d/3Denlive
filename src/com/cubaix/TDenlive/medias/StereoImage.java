package com.cubaix.TDenlive.medias;

import java.awt.Graphics2D;
import java.awt.geom.AffineTransform;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;
import java.io.BufferedWriter;
import java.io.File;
import java.util.Vector;

import org.eclipse.swt.graphics.GC;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.graphics.Rectangle;

import com.cubaix.TDenlive.TDConfig;
import com.cubaix.TDenlive.TDenlive;
import com.cubaix.TDenlive.GUI.Drawable;
import com.cubaix.TDenlive.utils.FloatDraw;
import com.cubaix.TDenlive.utils.ImageUtils;
import com.cubaix.TDenlive.utils.MpoReader;
import com.cubaix.TDenlive.xml.XmlObject;
import com.cubaix.TDenlive.xml.XmlTag;

public class StereoImage extends Media {
	String pathL = null;
	String pathR = null;
	public BufferedImage loadedLeft = null;
	public BufferedImage loadedRight = null;
	public BufferedImage rotatedLeft = null;
	public BufferedImage rotatedRight = null;
	public BufferedImage workLeft = null;
	public BufferedImage workRight = null;
	int lastProcessedRes = -1;
	boolean lastProcessedHdr = false;
	boolean isSizeAuto = false;
	
	public StereoImage(TDenlive aTDe) {
		super(aTDe);
	}

	public String getPathL() {
		return pathL;
	}

	public String getPathR() {
		return pathR;
	}

	@Override
	public int openProject(Vector<XmlObject> aOs,int o) throws Exception{
		return openProject(aOs, o, "StereoImage");
	}
	public int openProject(Vector<XmlObject> aOs,int o,String aType) throws Exception{
		for(;o < aOs.size();o++) {
			XmlObject aO = aOs.elementAt(o);
			if(aO instanceof XmlTag) {
				XmlTag aT = (XmlTag)aO;
				if(("/"+aType).equalsIgnoreCase(aT.tagName)) {
					if(pathL.toLowerCase().endsWith(".mpo")) {
						loadImageMPO(pathL);
					}
					else if(origStereo == 0) {
						loadImageLR(pathL, pathR,TDConfig.PROCESSING_MODE_WORK,0);
					}
					else if(origStereo == 1) {
						loadImageX(pathL,TDConfig.PROCESSING_MODE_WORK,0);
					}
					else if(origStereo == 2) {
						loadImageP(pathL,TDConfig.PROCESSING_MODE_WORK,0);
					}
					
					if(!rotateL.equals("0")||!rotateR.equals("0")) {
						rotate();
						buildThumb();
					}
					return o;
				}
				if("UID".equalsIgnoreCase(aT.tagName)) {
					o++;
					UID=Long.parseLong(aOs.elementAt(o).text);
				}
				if("stereo".equalsIgnoreCase(aT.tagName)) {
					o++;
					origStereo=Integer.parseInt(aOs.elementAt(o).text);
				}
				if("pathL".equalsIgnoreCase(aT.tagName)) {
					o++;
					pathL=aOs.elementAt(o).text;
				}
				if("pathR".equalsIgnoreCase(aT.tagName)) {
					o++;
					pathR=aOs.elementAt(o).text;
				}
				if("rotateL".equalsIgnoreCase(aT.tagName)) {
					o++;
					rotateL=aOs.elementAt(o).text;
				}
				if("rotateR".equalsIgnoreCase(aT.tagName)) {
					o++;
					rotateR=aOs.elementAt(o).text;
				}
				if("durationMS".equalsIgnoreCase(aT.tagName)) {
					o++;
					durationMS=Long.parseLong(aOs.elementAt(o).text);
				}
				if("anamorphW".equalsIgnoreCase(aT.tagName)) {
					o++;
					anamorphW=aOs.elementAt(o).text;
				}
				if("libName".equalsIgnoreCase(aT.tagName)) {
					o++;
					libName=com.cubaix.TDenlive.shapes.Shape.decode(aOs.elementAt(o).text.trim());
					continue;
				}
				if("anaglyph".equalsIgnoreCase(aT.tagName)) {
					anaglyph=aOs.elementAt(++o).text.trim();
					continue;
				}
				if("montageUID".equalsIgnoreCase(aT.tagName)) {
					montageUID=Long.parseLong(aOs.elementAt(++o).text.trim());
					continue;
				}
				if("montageSig".equalsIgnoreCase(aT.tagName)) {
					montageSig=aOs.elementAt(++o).text.trim();
					if(montageSig.equals("-")) {
						montageSig = null;
					}
					continue;
				}
				if("montageStale".equalsIgnoreCase(aT.tagName)) {
					montageStale="true".equalsIgnoreCase(aOs.elementAt(++o).text.trim());
					continue;
				}
				o = openProjectSub(aOs, o);
			}
		}
		return o;
	}
	
	//Final rendering of a montage of the project (Montage): its UID (0: an ordinary media), the signature of the montage
	//when rendered, and whether it is out of date (saved: a project reopened later knows it)
	public long montageUID = 0;
	//Kind of anaglyph of a single file (Anaglyph.TYPES: its views taken from its color layers), null: not an anaglyph
	public String anaglyph = null;
	public String montageSig = null;
	public boolean montageStale = false;

	public int openProjectSub(Vector<XmlObject> aOs,int o) throws Exception{
		//To be defined in sub-classes
		return o;
	}

	@Override
	public void saveProject(StringBuffer aSB) throws Exception {
		saveProject(aSB, "StereoImage");
	}

	@Override
	public java.util.Vector<String> getFilePaths() {
		java.util.Vector<String> aPaths = new java.util.Vector<String>();
		for(String aP : new String[] {pathL, pathR}) {
			if(aP != null && !"null".equals(aP) && !aPaths.contains(aP)) {
				aPaths.add(aP);
			}
		}
		return aPaths;
	}
	public void saveProject(StringBuffer aSB,String aType) throws Exception {
		aSB.append("		<"+aType+">\n");
		aSB.append("			<UID>" + UID + "</UID>\n");
		aSB.append("			<stereo>" + origStereo + "</stereo>\n");
		aSB.append("			<pathL>" + com.cubaix.TDenlive.utils.ProjectFiles.forSave(pathL) + "</pathL>\n");
		aSB.append("			<pathR>" + com.cubaix.TDenlive.utils.ProjectFiles.forSave(pathR) + "</pathR>\n");
		aSB.append("			<rotateL>" + rotateL + "</rotateL>\n");
		aSB.append("			<rotateR>" + rotateR + "</rotateR>\n");
		aSB.append("			<durationMS>" + durationMS + "</durationMS>\n");
		aSB.append("			<anamorphW>" + anamorphW + "</anamorphW>\n");
		saveLibName(aSB);
		if(com.cubaix.TDenlive.utils.Anaglyph.isAnaglyph(anaglyph)) {
			aSB.append("			<anaglyph>" + anaglyph + "</anaglyph>\n");
		}
		if(montageUID > 0) {
			//Final rendering of a montage of the project (see Montage)
			aSB.append("			<montageUID>" + montageUID + "</montageUID>\n");
			aSB.append("			<montageSig>" + (montageSig == null ? "-" : montageSig) + "</montageSig>\n");
			aSB.append("			<montageStale>" + montageStale + "</montageStale>\n");
		}
		saveProjectSub(aSB);
		aSB.append("		</"+aType+">\n");
	}
	
	void saveProjectSub(StringBuffer aSB) {
		//To be defined in sub-classes
	}

	void reloadOrig(int aProcessingMode,long aTime) throws Exception {
		switch(origStereo) {
		case 0:
			loadImageLR(pathL,pathR,aProcessingMode,aTime);
			break;
		case 1:
			loadImageX(pathL,aProcessingMode,aTime);
			break;
		case 2:
			loadImageP(pathL,aProcessingMode,aTime);
			break;
		}
	}
	
	String findPath(String aPath) {
		//As it is, else relative to the project folder or found under it with its sub folders (project folder
		//moved: Montages/<file> of the renderings of the montages, AI/...)
		String aFound = com.cubaix.TDenlive.utils.ProjectFiles.resolve(tde, aPath);
		if(aFound != null) {
			return aFound;
		}
		return tde.lastDir+File.separatorChar+new File(aPath).getName();
	}
	
	BufferedImage loadImage(String aPath,int aProcessingMode,long aTime,boolean aIsLeft) throws Exception {
		BufferedImage aBI = ImageUtils.loadImage(aPath);
		if(origWidth <= 0) {
			origWidth = aBI.getWidth();
			origHeight = aBI.getHeight();
		}
		return aBI;
	}
	
	public void loadImageLR(String aPathL,String aPathR,int aProcessingMode,long aTime) throws Exception {
		origStereo = 0;
		pathL = findPath(aPathL);
		pathR = findPath(aPathR);
		if(com.cubaix.TDenlive.utils.Anaglyph.isAnaglyph(anaglyph)) {
			//Its decodings keep the color layers apart (Ffmpeg.anaglyphFilter())
			com.cubaix.TDenlive.ffmpeg.Ffmpeg.markAnaglyph(pathL);
		}
		origHdr = tde.config.processingHdr[0];
		Graphics2D aG;
		BufferedImage aOrig = loadImage(pathL,aProcessingMode,aTime,true);
		int aWidth = aOrig.getWidth();
		int aHeight = aOrig.getHeight();
		loadedLeft = ImageUtils.createImage(aWidth, aHeight,tde.config.processingHdr[0] || FloatDraw.isFloat(aOrig));
		aG = (Graphics2D)loadedLeft.getGraphics();
		FloatDraw.draw(aG, loadedLeft, aOrig, 0, 0, 0, 0);
		aG.dispose();
		if(com.cubaix.TDenlive.utils.Anaglyph.isAnaglyph(anaglyph)) {
			//One file, both views taken from its color layers (the file read once)
			BufferedImage aAna = loadedLeft;
			loadedLeft = com.cubaix.TDenlive.utils.Anaglyph.view(aAna, anaglyph, true);
			loadedRight = com.cubaix.TDenlive.utils.Anaglyph.view(aAna, anaglyph, false);
		}
		else {
			aOrig = loadImage(pathR,aProcessingMode,aTime,false);
			loadedRight = ImageUtils.createImage(aWidth, aHeight,tde.config.processingHdr[0] || FloatDraw.isFloat(aOrig));
			aG = (Graphics2D)loadedRight.getGraphics();
			FloatDraw.draw(aG, loadedRight, aOrig, 0, 0, 0, 0);
			aG.dispose();
		}

		if(origRes <= 0) {
			origRes = aHeight;
			origRatio = aWidth/(double)aHeight;
		}
		
		fileName = new File(pathL).getName()+" / "+new File(pathR).getName();
		
		buildThumb();
		
		addMetaData(pathL);
		addMetaData(pathR);
	}

	public void loadImageX(String aPath,int aProcessingMode,long aTime) throws Exception {
		origStereo = 1;
		pathL = findPath(aPath);
		pathR = pathL;
		origRatio = 1;
		origHdr = tde.config.processingHdr[0];
		Graphics2D aG;
		BufferedImage aOrig = loadImage(pathL,aProcessingMode,aTime,true);
		int aWidth = aOrig.getWidth()/2;
		int aHeight = origRes = aOrig.getHeight();
		origRatio = aWidth/(double)aHeight;
		loadedLeft = ImageUtils.createImage(aWidth, aHeight,tde.config.processingHdr[0] || FloatDraw.isFloat(aOrig));
		aG = (Graphics2D)loadedLeft.getGraphics();
		FloatDraw.draw(aG, loadedLeft, aOrig, -aWidth, 0, 0, 0);
		aG.dispose();
		loadedRight = ImageUtils.createImage(aWidth, aHeight,tde.config.processingHdr[0] || FloatDraw.isFloat(aOrig));
		aG = (Graphics2D)loadedRight.getGraphics();
		FloatDraw.draw(aG, loadedRight, aOrig, 0, 0, 0, 0);
		aG.dispose();

		fileName = new File(pathL).getName();
		
		buildThumb();

		addMetaData(pathL);
	}

	public void loadImageP(String aPath,int aProcessingMode,long aTime) throws Exception {
		if(aPath.toLowerCase().endsWith(".mpo")) {
			loadImageMPO(aPath);
			return;
		}
		origStereo = 2;
		pathL = findPath(aPath);
		pathR = pathL;
		origRatio = 2;
		origHdr = tde.config.processingHdr[0];
		Graphics2D aG;
		BufferedImage aOrig = loadImage(pathL,aProcessingMode,aTime,true);
		int aWidth = aOrig.getWidth()/2;
		int aHeight = origRes = aOrig.getHeight();
		origRatio = aWidth/(double)aHeight;
		BufferedImage aOrigLeft = ImageUtils.createImage(aWidth, aHeight,tde.config.processingHdr[0] || FloatDraw.isFloat(aOrig));
		aG = (Graphics2D)aOrigLeft.getGraphics();
		FloatDraw.draw(aG, aOrigLeft, aOrig, 0, 0, 0, 0);
		aG.dispose();
		BufferedImage aOrigRight = ImageUtils.createImage(aWidth, aHeight,tde.config.processingHdr[0] || FloatDraw.isFloat(aOrig));
		aG = (Graphics2D)aOrigRight.getGraphics();
		FloatDraw.draw(aG, aOrigRight, aOrig, -aWidth, 0, 0, 0);
		aG.dispose();

		fileName = new File(pathL).getName();
		
		loadedLeft = aOrigLeft;
		loadedRight = aOrigRight;

		buildThumb();

		addMetaData(pathL);
	}

	public void loadImageMPO(String aPath) throws Exception {
		origStereo = 2;
		pathL = findPath(aPath);
		pathR = pathL;
		origHdr = tde.config.processingHdr[0];
		
		MpoReader aMR = new MpoReader();
		BufferedImage[] aIs = aMR.process(pathL);

		Graphics2D aG;
		BufferedImage aOrig = aIs[0];
		int aWidth = origWidth = aOrig.getWidth();
		int aHeight = origHeight = origRes = aOrig.getHeight();
		origRatio = aWidth/(double)aHeight;
		loadedLeft = ImageUtils.createImage(aWidth, aHeight,tde.config.processingHdr[0] || FloatDraw.isFloat(aOrig));
		aG = (Graphics2D)loadedLeft.getGraphics();
		FloatDraw.draw(aG, loadedLeft, aOrig, 0, 0, 0, 0);
		aG.dispose();
		aOrig = aIs[1];
		aWidth = aOrig.getWidth();
		aHeight = aOrig.getHeight();
		loadedRight = ImageUtils.createImage(aWidth, aHeight,tde.config.processingHdr[0] || FloatDraw.isFloat(aOrig));
		aG = (Graphics2D)loadedRight.getGraphics();
		FloatDraw.draw(aG, loadedRight, aOrig, 0, 0, 0, 0);
		aG.dispose();
		
		fileName = new File(pathL).getName();
		
		buildThumb();
		
		addMetaData(pathL);
	}

	@Override
	public void rotate() {
		if(rotateL.equals("0") && anamorphW.equals("1")) {
			rotatedLeft = null;
		}
		else {
			int aWidth = loadedLeft.getWidth();
			int aHeight = loadedLeft.getHeight();
			AffineTransform aAT = null;
			if(rotateL.equals("180") || rotateL.equals("0")) {
				rotatedLeft = ImageUtils.createImage((int)(Double.parseDouble(anamorphW)*aWidth),aHeight,tde.config.processingHdr[0] || FloatDraw.isFloat(loadedLeft));
				aAT = AffineTransform.getTranslateInstance(aWidth/2,aHeight/2);
				aAT.concatenate(AffineTransform.getRotateInstance(Math.PI*Double.parseDouble(rotateL)/180.0));
				aAT.concatenate(AffineTransform.getTranslateInstance(-aWidth/2,-aHeight/2));
				aAT.concatenate(AffineTransform.getScaleInstance(Double.parseDouble(anamorphW), 1.0));
			}
			else {
				rotatedLeft = ImageUtils.createImage(aHeight,(int)(Double.parseDouble(anamorphW)*aWidth),tde.config.processingHdr[0] || FloatDraw.isFloat(loadedLeft));
				aAT = AffineTransform.getTranslateInstance(aHeight/2,aWidth/2);
				aAT.concatenate(AffineTransform.getRotateInstance(Math.PI*Double.parseDouble(rotateL)/180.0));
				aAT.concatenate(AffineTransform.getTranslateInstance(-aWidth/2,-aHeight/2));
				aAT.concatenate(AffineTransform.getScaleInstance(Double.parseDouble(anamorphW), 1.0));
			}
			Graphics2D aG = (Graphics2D)rotatedLeft.getGraphics();
			FloatDraw.draw(aG, rotatedLeft, loadedLeft, aAT, 1f);
			aG.dispose();
		}
		if(rotateR.equals("0") && Double.parseDouble(anamorphW) == 1) {
			rotatedRight = null;
		}
		else {
			int aWidth = loadedRight.getWidth();
			int aHeight = loadedRight.getHeight();
			AffineTransform aAT = null;
			if(rotateR.equals("180") || rotateR.equals("0")) {
				rotatedRight = ImageUtils.createImage((int)(Double.parseDouble(anamorphW)*aWidth),aHeight,tde.config.processingHdr[0] || FloatDraw.isFloat(loadedRight));
				aAT = AffineTransform.getTranslateInstance(aWidth/2,aHeight/2);
				aAT.concatenate(AffineTransform.getRotateInstance(Math.PI*Double.parseDouble(rotateR)/180.0));
				aAT.concatenate(AffineTransform.getTranslateInstance(-aWidth/2,-aHeight/2));
				aAT.concatenate(AffineTransform.getScaleInstance(Double.parseDouble(anamorphW), 1.0));
			}
			else {
				rotatedRight = ImageUtils.createImage(aHeight,(int)(Double.parseDouble(anamorphW)*aWidth),tde.config.processingHdr[0] || FloatDraw.isFloat(loadedRight));
				aAT = AffineTransform.getTranslateInstance(aHeight/2,aWidth/2);
				aAT.concatenate(AffineTransform.getRotateInstance(Math.PI*Double.parseDouble(rotateR)/180.0));
				aAT.concatenate(AffineTransform.getTranslateInstance(-aWidth/2,-aHeight/2));
				aAT.concatenate(AffineTransform.getScaleInstance(Double.parseDouble(anamorphW), 1.0));
			}
			Graphics2D aG = (Graphics2D)rotatedRight.getGraphics();
			FloatDraw.draw(aG, rotatedRight, loadedRight, aAT, 1f);
			aG.dispose();
		}
		buildThumb();
		workLeft = workRight = null;//Enforce rebuild
	}

	@Override
	public long getMaxTimePosMS() {
		return durationMS;
	}

	@Override
	public void reBuild(int aProcessingMode,long aTime) {
		if(workLeft != null && workRight != null
				&& lastProcessedRes == tde.config.processingResValues[aProcessingMode]
				&& lastProcessedHdr == tde.config.processingHdr[aProcessingMode]) {
			return;
		}
		workLeft = workRight = null;
		if(origHdr != tde.config.processingHdr[aProcessingMode]) {
			try {
				reloadOrig(aProcessingMode,aTime);
			}
			catch(Throwable t) {
				t.printStackTrace(System.err);
			}
		}
		getWorkLeft(aProcessingMode,aTime);
		getWorkRight(aProcessingMode,aTime);
		lastProcessedRes = tde.config.processingResValues[aProcessingMode];
		lastProcessedHdr = tde.config.processingHdr[aProcessingMode];
	}

	@Override
	public void setWorkLeft(BufferedImage aBI) {
		workLeft = aBI;
	}

	@Override
	public void setWorkRight(BufferedImage aBI) {
		workRight = aBI;
	}

	@Override
	public BufferedImage getWorkLeft(int aProcessingMode,long aTime) {
		if(workLeft != null) {
			return workLeft;
		}
		
		boolean aFastMode = tde.config.fastMode && aProcessingMode != TDConfig.PROCESSING_MODE_RENDER;
		return workLeft = ImageUtils.toRes(rotatedLeft != null ? rotatedLeft : loadedLeft, tde.config.processingResValues[aProcessingMode],tde.config.processingHdr[aProcessingMode],aFastMode,false);
	}

	@Override
	public BufferedImage getWorkRight(int aProcessingMode,long aTime) {
		if(workRight != null) {
			return workRight;
		}
		boolean aFastMode = tde.config.fastMode && aProcessingMode != TDConfig.PROCESSING_MODE_RENDER;
		return workRight = ImageUtils.toRes(rotatedRight != null ? rotatedRight : loadedRight, tde.config.processingResValues[aProcessingMode],tde.config.processingHdr[aProcessingMode],aFastMode,false);
	}

	@Override
	public BufferedImage getFinalLeft(int aProcessingMode,long aTime) {
		return getWorkLeft(aProcessingMode,aTime);
	}

	@Override
	public BufferedImage getFinalRight(int aProcessingMode,long aTime) {
		return getWorkRight(aProcessingMode,aTime);
	}
	
	@Override
	public void buildThumb() {
		if(com.cubaix.TDenlive.utils.Anaglyph.isAnaglyph(anaglyph) && loadedRight != null) {
			//Imported anaglyph: the whole anaglyph image (both views summed), not the left view alone
			BufferedImage aL = ImageUtils.toRes(rotatedLeft != null ? rotatedLeft : loadedLeft, tde.config.thumbH, false, true, false);
			BufferedImage aR = ImageUtils.toRes(rotatedRight != null ? rotatedRight : loadedRight, tde.config.thumbH, false, true, false);
			BufferedImage aA = com.cubaix.TDenlive.utils.Anaglyph.combine(aL, aR);
			BufferedImage aT = ImageUtils.createImage(aA.getWidth(), aA.getHeight());
			ImageUtils.checkerBck(aT, 5);
			java.awt.Graphics2D aG = aT.createGraphics();
			aG.drawImage(aA, 0, 0, null);
			aG.dispose();
			setThumbAwt(aT);
		}
		else {
			setThumbAwt(ImageUtils.toRes(rotatedLeft != null ? rotatedLeft : loadedLeft, tde.config.thumbH,false,true,true));
		}
		setThumbSwt(ImageUtils.imageToSwt(tde.gui.display,getThumbAwt(),false));
	}
	
	public Rectangle drawSwt(GC aGC,int aX, int aY,Rectangle aClippingR) {
		int aW = 0;
		int aH = 0;
		
		//The 2 or 3 text lines may be higher than the thumbnail with big fonts
		aGC.setFont(tde.gui.fontsSwt.robotoBold);
		int aLinesH = aGC.getFontMetrics().getHeight();
		aGC.setFont(tde.gui.fontsSwt.roboto);
		aLinesH += (isSizeAuto ? 1 : 2) * aGC.getFontMetrics().getHeight();
		int aFullH = Math.max(tde.config.thumbH, aLinesH);

		aGC.setBackground(selState == 0 ? tde.gui.colorsSwt.WHITE : tde.gui.colorsSwt.BLUE_L60);
		aGC.setForeground(selState == 0 ? tde.gui.colorsSwt.CUBAIX_BLUE : tde.gui.colorsSwt.WHITE);
		aGC.fillRectangle(aClippingR.x, aY, aClippingR.width, aFullH);

		if(getThumbSwt() == null || fileName == null) {//While loading, not yet available
			return new Rectangle(aClippingR.x, aY, aClippingR.width, aFullH);
		}

		aGC.drawImage(getThumbSwt(), aX, aY);

		aW += tde.config.thumbW;

		aGC.fillRectangle(aX+aW, aY, aClippingR.width, aFullH);
		
		aW += 3;
		
		aGC.setFont(tde.gui.fontsSwt.robotoBold);
		aGC.drawText(displayName(), aX+aW, aY);
		Point aTPF = aGC.stringExtent(displayName());

		aGC.setFont(tde.gui.fontsSwt.roboto);
		Point aTPS = new Point(0, 0);
		if(!isSizeAuto) {
			String aSize = rotateL.matches("(0|180)") ? origWidth+"x"+origHeight : origHeight+"x"+origWidth;
			aGC.drawText(aSize, aX+aW, aY+aTPF.y);
			aTPS = aGC.stringExtent(aSize);
		}

		String aClock = getDurationClock();
		aGC.drawText(aClock, aX+aW, aY+aTPF.y+aTPS.y);
		Point aTPC = aGC.stringExtent(aClock);

		aW += Math.max(Math.max(aTPF.x,aTPS.x),aTPC.x);
		aH += aTPF.y + aTPS.y + aTPC.y;

		aH = Math.max(tde.config.thumbH, aH);
		aW = Math.max(aW, aClippingR.width - aX);
		
		bounds = new Rectangle(aX, aY, aW, aH);
		
		if(selState > 0) {
			aGC.setForeground(tde.gui.colorsSwt.CUBAIX_BLUE);
			aGC.drawRectangle(aX, aY, aW-1, aH-1);
		}
		
		return bounds;
	}
	
	@Override
	public Rectangle drawAwt(Graphics2D aGC, int aX, int aY, Rectangle aClippingR) {
		int aW = 0;
		int aH = 0;
		
		aGC.setBackground(selState == 0 ? tde.gui.colorsAwt.WHITE : tde.gui.colorsAwt.BLUE_L60);
		aGC.setColor(selState == 0 ? tde.gui.colorsAwt.CUBAIX_BLUE : tde.gui.colorsAwt.WHITE);
		aGC.fillRect(aClippingR.x, aY, aClippingR.width, tde.config.thumbH);
		
		if(getThumbSwt() == null || fileName == null) {//While loading, not yet available
			return new Rectangle(aClippingR.x, aY, aClippingR.width, tde.config.thumbH);
		}

		aGC.drawImage(getThumbAwt(), aX, aY,null);

		aW += tde.config.thumbW;
		
		aGC.fillRect(aX+aW, aY, aClippingR.width, tde.config.thumbH);
		
		aW += 3;
		
		aGC.setFont(tde.gui.fontsAwt.robotoBold);
		aGC.drawString(displayName(), aX+aW, aY);
		Rectangle2D aTRF = aGC.getFontMetrics().getStringBounds(displayName(),aGC);

		aGC.setFont(tde.gui.fontsAwt.roboto);
		Rectangle2D aTRS = new java.awt.Rectangle();
		if(!isSizeAuto) {
			String aSize = rotateL.matches("(0|180)") ? origWidth+"x"+origHeight : origHeight+"x"+origWidth;
			aGC.drawString(aSize, aX+aW, aY+(int)aTRF.getWidth());
			aTRS = aGC.getFontMetrics().getStringBounds(aSize,aGC);
		}
		
		String aClock = getDurationClock();
		aGC.drawString(aClock, aX+aW, aY+(int)aTRF.getWidth()+(int)aTRS.getWidth());
		Rectangle2D aTRC = aGC.getFontMetrics().getStringBounds(aClock,aGC);

		aW += Math.max(Math.max(aTRF.getWidth(),aTRS.getWidth()),aTRC.getWidth());
		aH += aTRF.getHeight() + aTRS.getHeight() + aTRC.getHeight();

		aH = Math.max(tde.config.thumbH, aH);
		aW = Math.max(aW, aClippingR.width - aX);
		
		bounds = new Rectangle(aX, aY, aW, aH);
		
		if(selState > 0) {
			aGC.setColor(tde.gui.colorsAwt.CUBAIX_BLUE);
			aGC.drawRect(aX, aY, aW-1, aH-1);
		}
		
		return bounds;
	}

	@Override
	public boolean selectWidget(int aX, int aY) {
		return false;
	}

	@Override
	public void select(int aX, int aY,boolean aOutUnselect) {
		if(aX > 0 && aY > 0 //Negative values are used to unselect all (can't click outside the panel)
				&& bounds != null && bounds.contains(aX, aY)) {
			selState = Drawable.THUMBSELSTATE_SELECTED;
			tde.selected.medias.add(this);
		}
		else if(aOutUnselect) {
			selState = Drawable.THUMBSELSTATE_NONE;
		}
	}

}
