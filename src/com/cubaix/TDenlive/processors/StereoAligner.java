package com.cubaix.TDenlive.processors;

import java.awt.AlphaComposite;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.Shape;
import java.awt.geom.AffineTransform;
import java.awt.geom.PathIterator;
import java.awt.image.BufferedImage;
import java.util.Vector;

import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.graphics.Rectangle;

import com.cubaix.TDenlive.TDConfig;
import com.cubaix.TDenlive.TDenlive;
import com.cubaix.TDenlive.medias.Clip;
import com.cubaix.TDenlive.medias.Media;
import com.cubaix.TDenlive.utils.ImageUtils;
import com.cubaix.TDenlive.xml.XmlObject;
import com.cubaix.TDenlive.xml.XmlTag;

public class StereoAligner extends Processor {
	boolean secureCropLeft = false;
	boolean secureCropRight = false;
	boolean secureCropTop = true;
	boolean secureCropBottom = true;
	boolean secureResize = true;
	boolean showSecureCropBox = true;

	class Key{
		double xL = 0;
		double yL = 0;
		double xR = 0;
		double yR = 0;

		double wL = 1.0;
		double hL = 1.0;
		double wR = 1.0;
		double hR = 1.0;

		double rL = 0;
		double rR = 0;

		long time = 0;
	}
	Vector<Key> keys = new Vector<Key>();
	
	public StereoAligner(TDenlive aTDe,Media aTargetMedia) {
		super(aTDe,aTargetMedia);
		isExpended = true;
		keys.add(new Key());
	}

	@Override
	public String getClassName() {return "StereoAligner";}

	@Override
	public int openProject(Vector<XmlObject> aOs, int o) throws Exception {
		keys.clear();
		for(;o < aOs.size();o++) {
			XmlObject aO = aOs.elementAt(o);
			if(aO instanceof XmlTag) {
				XmlTag aT = (XmlTag)aO;
				if("/StereoAligner".equalsIgnoreCase(aT.tagName)) {
					return o;
				}
				if("secureCropLeft".equalsIgnoreCase(aT.tagName)) {
					o++;
					secureCropLeft = "true".equals(aOs.elementAt(o).text);
				}
				if("secureCropRight".equalsIgnoreCase(aT.tagName)) {
					o++;
					secureCropRight = "true".equals(aOs.elementAt(o).text);
				}
				if("secureCropTop".equalsIgnoreCase(aT.tagName)) {
					o++;
					secureCropTop = "true".equals(aOs.elementAt(o).text);
				}
				if("secureCropBottom".equalsIgnoreCase(aT.tagName)) {
					o++;
					secureCropBottom = "true".equals(aOs.elementAt(o).text);
				}
				if("secureResize".equalsIgnoreCase(aT.tagName)) {
					o++;
					secureResize = "true".equals(aOs.elementAt(o).text);
				}
				if("showSecureCropBox".equalsIgnoreCase(aT.tagName)) {
					o++;
					showSecureCropBox = "true".equals(aOs.elementAt(o).text);
				}
				if("xL".equalsIgnoreCase(aT.tagName)) {
					o++;
					keys.add(new Key());
					keys.lastElement().xL = Double.parseDouble(aOs.elementAt(o).text);
				}
				if("yL".equalsIgnoreCase(aT.tagName)) {
					o++;
					keys.lastElement().yL = Double.parseDouble(aOs.elementAt(o).text);
				}
				if("xR".equalsIgnoreCase(aT.tagName)) {
					o++;
					keys.lastElement().xR = Double.parseDouble(aOs.elementAt(o).text);
				}
				if("yR".equalsIgnoreCase(aT.tagName)) {
					o++;
					keys.lastElement().yR = Double.parseDouble(aOs.elementAt(o).text);
				}
				if("wL".equalsIgnoreCase(aT.tagName)) {
					o++;
					keys.lastElement().wL = Double.parseDouble(aOs.elementAt(o).text);
				}
				if("hL".equalsIgnoreCase(aT.tagName)) {
					o++;
					keys.lastElement().hL = Double.parseDouble(aOs.elementAt(o).text);
				}
				if("wR".equalsIgnoreCase(aT.tagName)) {
					o++;
					keys.lastElement().wR = Double.parseDouble(aOs.elementAt(o).text);
				}
				if("hR".equalsIgnoreCase(aT.tagName)) {
					o++;
					keys.lastElement().hR = Double.parseDouble(aOs.elementAt(o).text);
				}
				if("rL".equalsIgnoreCase(aT.tagName)) {
					o++;
					keys.lastElement().rL = Double.parseDouble(aOs.elementAt(o).text);
				}
				if("rR".equalsIgnoreCase(aT.tagName)) {
					o++;
					keys.lastElement().rR = Double.parseDouble(aOs.elementAt(o).text);
				}
				if("time".equalsIgnoreCase(aT.tagName)) {
					o++;
					keys.lastElement().time = Long.parseLong(aOs.elementAt(o).text);
				}
			}
		}
		if(keys.size() <= 0) {
			keys.add(new Key());
		}
		return o;
	}

	@Override
	public void saveProject(StringBuffer aSB) throws Exception {
		aSB.append("						<StereoAligner>\n");
		aSB.append("							<secureCropLeft>" + secureCropLeft + "</secureCropLeft>\n");
		aSB.append("							<secureCropRight>" + secureCropRight + "</secureCropRight>\n");
		aSB.append("							<secureCropTop>" + secureCropTop + "</secureCropTop>\n");
		aSB.append("							<secureCropBottom>" + secureCropBottom + "</secureCropBottom>\n");
		aSB.append("							<secureResize>" + secureResize + "</secureResize>\n");
		aSB.append("							<showSecureCropBox>" + showSecureCropBox + "</showSecureCropBox>\n");
		for(Key aK : keys) {
			aSB.append("							<xL>" + aK.xL + "</xL>\n");
			aSB.append("							<yL>" + aK.yL + "</yL>\n");
			aSB.append("							<xR>" + aK.xR + "</xR>\n");
			aSB.append("							<yR>" + aK.yR + "</yR>\n");
			aSB.append("							<wL>" + aK.wL + "</wL>\n");
			aSB.append("							<hL>" + aK.hL + "</hL>\n");
			aSB.append("							<wR>" + aK.wR + "</wR>\n");
			aSB.append("							<hR>" + aK.hR + "</hR>\n");
			aSB.append("							<rL>" + aK.rL + "</rL>\n");
			aSB.append("							<rR>" + aK.rR + "</rR>\n");
			aSB.append("							<time>" + aK.time + "</time>\n");
		}
		aSB.append("						</StereoAligner>\n");
	}

	@Override
	void setDescr() {
		name = tde.gui.lngs.get("Processor.Align");
		icon = "moveRC.png";
	}
	
	public boolean isSecureCropLeft() {
		return secureCropLeft;
	}

	public void setSecureCropLeft(boolean secureCropLeft) {
		this.secureCropLeft = secureCropLeft;
	}

	public boolean isSecureCropRight() {
		return secureCropRight;
	}

	public void setSecureCropRight(boolean secureCropRight) {
		this.secureCropRight = secureCropRight;
	}

	public boolean isSecureCropTop() {
		return secureCropTop;
	}

	public void setSecureCropTop(boolean secureCropTop) {
		this.secureCropTop = secureCropTop;
	}

	public boolean isSecureCropBottom() {
		return secureCropBottom;
	}

	public void setSecureCropBottom(boolean secureCropBottom) {
		this.secureCropBottom = secureCropBottom;
	}

	public boolean isSecureResize() {
		return secureResize;
	}

	public void setSecureResize(boolean secureResize) {
		this.secureResize = secureResize;
	}

	public boolean isShowSecureCropBox() {
		return showSecureCropBox;
	}

	public void setShowSecureCropBox(boolean drawCropRect) {
		this.showSecureCropBox = drawCropRect;
	}

	public int getNbKeys() {
		return keys.size();
	}
	
	public int addKeyGT(long aTime){
		if(aTime <= ((Clip)targetMedia).getStartTimeMS() || aTime > ((Clip)targetMedia).getStartTimeMS() + ((Clip)targetMedia).getDurationMS()) {
			//Can't insert outside
			return -1;
		}
		Key aKey = getInterKeyGT(aTime);
		for(int k = keys.size() - 1;k >= 0;k--) {
			Key aK = keys.elementAt(k);
			if(aK.time <= aKey.time) {
				keys.add(k+1, aKey);
				return k+1;
			}
		}
		//??
		keys.add(aKey);
		return 0;
	}
	
	public void deleteKey(int aKey) {
		//Can't delete first
		if(aKey <= 0 || aKey >= keys.size()) {
			return;
		}
		keys.remove(aKey);
	}
	
	public int getPrevKeyGT(long aTime){
		long aT = aTime - ((Clip)targetMedia).getStartTimeMS();
		for(int k = keys.size() - 1;k >= 0;k--) {
			Key aK = keys.elementAt(k);
			if(aK.time <= aT) {
				return k;
			}
		}
		//No key before
		return -1;
	}
	
	public int getNextKeyGT(long aTime){
		long aT = aTime - ((Clip)targetMedia).getStartTimeMS();
		for(int k = 0;k < keys.size();k++) {
			Key aK = keys.elementAt(k);
			if(aK.time > aT) {
				return k;
			}
		}
		//No key after
		return -1;
	}
	
	public double getXL(int aKey) {
		return keys.elementAt(aKey).xL;
	}

	public double getYL(int aKey) {
		return keys.elementAt(aKey).yL;
	}

	public double getXR(int aKey) {
		return keys.elementAt(aKey).xR;
	}

	public double getYR(int aKey) {
		return keys.elementAt(aKey).yR;
	}

	public double getWL(int aKey) {
		return keys.elementAt(aKey).wL;
	}

	public double getHL(int aKey) {
		return keys.elementAt(aKey).hL;
	}

	public double getWR(int aKey) {
		return keys.elementAt(aKey).wR;
	}

	public double getHR(int aKey) {
		return keys.elementAt(aKey).hR;
	}

	public double getRL(int aKey) {
		return keys.elementAt(aKey).rL;
	}

	public double getRR(int aKey) {
		return keys.elementAt(aKey).rR;
	}

	public void setXL(int aKey,double aV) {
		keys.elementAt(aKey).xL = aV;
	}

	public void setYL(int aKey,double aV) {
		keys.elementAt(aKey).yL = aV;
	}

	public void setXR(int aKey,double aV) {
		keys.elementAt(aKey).xR = aV;
	}

	public void setYR(int aKey,double aV) {
		keys.elementAt(aKey).yR = aV;
	}

	public void setWL(int aKey,double aV) {
		keys.elementAt(aKey).wL = aV;
	}

	public void setHL(int aKey,double aV) {
		keys.elementAt(aKey).hL = aV;
	}

	public void setWR(int aKey,double aV) {
		keys.elementAt(aKey).wR = aV;
	}

	public void setHR(int aKey,double aV) {
		keys.elementAt(aKey).hR = aV;
	}

	public void setRL(int aKey,double aV) {
		keys.elementAt(aKey).rL = aV;
	}

	public void setRR(int aKey,double aV) {
		keys.elementAt(aKey).rR = aV;
	}

	public long getTime(int aKey) {
		return keys.elementAt(aKey).time;
	}

	Key getInterKeyGT(long aTimePos){
		long aTime = aTimePos-((Clip)targetMedia).getStartTimeMS();
		for(int k = keys.size()-1;k >= 0;k--) {
			Key aKey = keys.elementAt(k);
			if(aKey.time <= aTime) {
				if(k == keys.size()-1) {
					Key aInter = new Key();
					aInter.xL = aKey.xL;
					aInter.yL = aKey.yL;
					aInter.xR = aKey.xR;
					aInter.yR = aKey.yR;
					aInter.wL = aKey.wL;
					aInter.hL = aKey.hL;
					aInter.wR = aKey.wR;
					aInter.hR = aKey.hR;
					aInter.rL = aKey.rL;
					aInter.rR = aKey.rR;
					aInter.time = aTime;
					return aInter;
				}
				Key aNext = keys.elementAt(k+1);
				Key aInter = new Key();
				double aFact = (aTime-aKey.time)/(double)(aNext.time-aKey.time);
				aInter.xL = aKey.xL*(1.0-aFact)+aNext.xL*aFact;
				aInter.yL = aKey.yL*(1.0-aFact)+aNext.yL*aFact;
				aInter.xR = aKey.xR*(1.0-aFact)+aNext.xR*aFact;
				aInter.yR = aKey.yR*(1.0-aFact)+aNext.yR*aFact;
				aInter.wL = aKey.wL*(1.0-aFact)+aNext.wL*aFact;
				aInter.hL = aKey.hL*(1.0-aFact)+aNext.hL*aFact;
				aInter.rL = aKey.rL*(1.0-aFact)+aNext.rL*aFact;
				aInter.rR = aKey.rR*(1.0-aFact)+aNext.rR*aFact;
				aInter.time = aTime;
				return aInter;
			}
		}
		//??
		Key aKey = keys.firstElement();
		Key aInter = new Key();
		aInter.xL = aKey.xL;
		aInter.yL = aKey.yL;
		aInter.xR = aKey.xR;
		aInter.yR = aKey.yR;
		aInter.wL = aKey.wL;
		aInter.hL = aKey.hL;
		aInter.wR = aKey.wR;
		aInter.hR = aKey.hR;
		aInter.rL = aKey.rL;
		aInter.rR = aKey.rR;
		aInter.time = aTime;
		return aInter;
	}

	@Override
	public Image process2Swt(Media aMedia,int aProcessingMode,Rectangle aTargetSize,long aTime) {
		return null;
	}

	@Override
	public void process2Awt(Media aMedia,int aProcessingMode,long aTime) {
		try {
			BufferedImage aBIL = aMedia.getWorkLeft(aProcessingMode,aTime);
			BufferedImage aBIR = aMedia.getWorkRight(aProcessingMode,aTime);
			int aWidth = aBIL.getWidth();
			int aHeight = aBIL.getHeight();
			
			java.awt.Rectangle aSrcRect = new java.awt.Rectangle(0,0,aWidth,aHeight);
			java.awt.Rectangle aOptimalRect = new java.awt.Rectangle(0,0,aWidth,aHeight);

			Key aKey = getInterKeyGT(aTime);

			AffineTransform aATL = new AffineTransform();
			aATL.translate(aWidth/2, aHeight/2);
			aATL.scale(aKey.wL, aKey.hL);
			aATL.translate(aKey.xL*aWidth, aKey.yL*aHeight);
			aATL.rotate(aKey.rL);
			aATL.translate(-aWidth/2, -aHeight/2);
			
			Shape aTargetL = aATL.createTransformedShape(aSrcRect);
			double[] aSegL = new double[6];
			PathIterator aPIL = aTargetL.getPathIterator(null);
			aPIL.next();//Top right
			aPIL.currentSegment(aSegL);
			if(aOptimalRect.x+aOptimalRect.width > aSegL[0]) {
				aOptimalRect.width = (int)aSegL[0]-aOptimalRect.x;
			}
			if(aOptimalRect.y < aSegL[1]) {
				aOptimalRect.height -= (int)aSegL[1]-aOptimalRect.y;
				aOptimalRect.y = (int)aSegL[1];
			}
			aPIL.next();//Bottom right
			aPIL.currentSegment(aSegL);
			if(aOptimalRect.x+aOptimalRect.width > aSegL[0]) {
				aOptimalRect.width = (int)aSegL[0]-aOptimalRect.x;
			}
			if(aOptimalRect.y+aOptimalRect.height > aSegL[1]) {
				aOptimalRect.height = (int)aSegL[1] - aOptimalRect.y;
			}
			aPIL.next();//Bottom left
			aPIL.currentSegment(aSegL);
			if(aOptimalRect.x < aSegL[0]) {
				aOptimalRect.width -= (int)aSegL[0]-aOptimalRect.x;
				aOptimalRect.x = (int)aSegL[0];
			}
			if(aOptimalRect.y+aOptimalRect.height > aSegL[1]) {
				aOptimalRect.height = (int)aSegL[1] - aOptimalRect.y;
			}
			aPIL.next();//Top left
			aPIL.currentSegment(aSegL);
			if(aOptimalRect.x < aSegL[0]) {
				aOptimalRect.width -= (int)aSegL[0]-aOptimalRect.x;
				aOptimalRect.x = (int)aSegL[0];
			}
			if(aOptimalRect.y < aSegL[1]) {
				aOptimalRect.height -= (int)aSegL[1]-aOptimalRect.y;
				aOptimalRect.y = (int)aSegL[1];
			}

			AffineTransform aATR = new AffineTransform();
			aATR.translate(aWidth/2, aHeight/2);
			aATR.scale(aKey.wR, aKey.hR);
			aATR.translate(aKey.xR*aWidth, aKey.yR*aHeight);
			aATR.rotate(aKey.rR);
			aATR.translate(-aWidth/2, -aHeight/2);

			Shape aTargetR = aATR.createTransformedShape(aSrcRect);
			double[] aSegR = new double[6];
			PathIterator aPIR = aTargetR.getPathIterator(null);
			aPIR.next();//Top right
			aPIR.currentSegment(aSegR);
			if(aOptimalRect.x+aOptimalRect.width > aSegR[0]) {
				aOptimalRect.width = (int)aSegR[0]-aOptimalRect.x;
			}
			if(aOptimalRect.y < aSegR[1]) {
				aOptimalRect.height -= (int)aSegR[1]-aOptimalRect.y;
				aOptimalRect.y = (int)aSegR[1];
			}
			aPIR.next();//Bottom right
			aPIR.currentSegment(aSegR);
			if(aOptimalRect.x+aOptimalRect.width > aSegR[0]) {
				aOptimalRect.width = (int)aSegR[0]-aOptimalRect.x;
			}
			if(aOptimalRect.y+aOptimalRect.height > aSegR[1]) {
				aOptimalRect.height = (int)aSegR[1] - aOptimalRect.y;
			}
			aPIR.next();//Bottom left
			aPIR.currentSegment(aSegR);
			if(aOptimalRect.x < aSegR[0]) {
				aOptimalRect.width -= (int)aSegR[0]-aOptimalRect.x;
				aOptimalRect.x = (int)aSegR[0];
			}
			if(aOptimalRect.y+aOptimalRect.height > aSegR[1]) {
				aOptimalRect.height = (int)aSegR[1] - aOptimalRect.y;
				aOptimalRect.y = (int)aSegR[1];
			}
			aPIR.next();//Top left
			aPIR.currentSegment(aSegR);
			if(aOptimalRect.x < aSegR[0]) {
				aOptimalRect.width -= (int)aSegR[0]-aOptimalRect.x;
				aOptimalRect.x = (int)aSegR[0];
			}
			if(aOptimalRect.y < aSegR[1]) {
				aOptimalRect.height -= (int)aSegR[1]-aOptimalRect.y;
				aOptimalRect.y = (int)aSegR[1];
			}
			
			AffineTransform aResizeL = null;
			AffineTransform aResizeR = null;
			if(secureResize
					//always with auto align
					|| aProcessingMode == 3) {
				aResizeL = new AffineTransform();
				aResizeR = new AffineTransform();
				double aFact = Math.max(aWidth/(double)aOptimalRect.width, aHeight/(double)aOptimalRect.height);
				
				aResizeL.translate(aWidth/2,aHeight/2);
				aResizeL.scale(aFact, aFact);
				aResizeL.translate(-aOptimalRect.width/2-aOptimalRect.x, -aOptimalRect.height/2-aOptimalRect.y);
				
				aResizeR.translate(aWidth/2,aHeight/2);
				aResizeR.scale(aFact, aFact);
				aResizeR.translate(-aOptimalRect.width/2-aOptimalRect.x, -aOptimalRect.height/2-aOptimalRect.y);
				
				aOptimalRect = aResizeL.createTransformedShape(aOptimalRect).getBounds();
				
				aResizeL.concatenate(aATL);
				aResizeR.concatenate(aATR);
			}

			int aWidthOut = (int)(tde.config.outRatio*tde.config.processingResValues[aProcessingMode]);
			int aHeightOut = tde.config.processingResValues[aProcessingMode];

			final AffineTransform aFinalL = new AffineTransform();
			aFinalL.translate((aWidthOut - aWidth)/2, (aHeightOut - aHeight)/2);
			aFinalL.concatenate(secureResize
					//always with auto align
					|| aProcessingMode == 3?aResizeL:aATL);
			final AffineTransform aFinalR = new AffineTransform();
			aFinalR.translate((aWidthOut - aWidth)/2, (aHeightOut - aHeight)/2);
			aFinalR.concatenate(secureResize
					//always with auto align
					|| aProcessingMode == TDConfig.PROCESSING_MODE_AUTOALIGN?aResizeR:aATR);
			
			aOptimalRect.x += (aWidthOut - aWidth)/2;
			aOptimalRect.y += (aHeightOut - aHeight)/2;
			
			final java.awt.Rectangle aCropRect = aOptimalRect;

			Thread aThL = new Thread(new Runnable() {
				@Override
				public void run() {
					BufferedImage aProcessedBIL = ImageUtils.createImage(aWidthOut,aHeightOut,tde.config.processingHdr[aProcessingMode]);
					Graphics2D aGL = (Graphics2D)aProcessedBIL.getGraphics();
					if(!tde.config.fastMode || aProcessingMode == TDConfig.PROCESSING_MODE_RENDER) {
						aGL.setRenderingHint(RenderingHints.KEY_ANTIALIASING,RenderingHints.VALUE_ANTIALIAS_ON);
						aGL.setRenderingHint(RenderingHints.KEY_RENDERING,RenderingHints.VALUE_RENDER_QUALITY);
					}
					aGL.drawImage(aBIL,aFinalL,null);
					aGL.setComposite(AlphaComposite.Clear);
					if(secureCropLeft
							//always with auto align
							|| aProcessingMode == TDConfig.PROCESSING_MODE_AUTOALIGN) {
						aGL.fillRect(0, 0, aCropRect.x, aHeightOut);//Left
					}
					if(secureCropRight
							//always with auto align
							|| aProcessingMode == TDConfig.PROCESSING_MODE_AUTOALIGN) {
						aGL.fillRect(aCropRect.x+aCropRect.width, 0, aWidthOut-(aCropRect.x+aCropRect.width), aHeightOut);//Right
					}
					if(secureCropTop
							//always with auto align
							|| aProcessingMode == TDConfig.PROCESSING_MODE_AUTOALIGN) {
						aGL.fillRect(0, 0, aWidthOut, aCropRect.y);//Top
					}
					if(secureCropBottom
							//always with auto align
							|| aProcessingMode == TDConfig.PROCESSING_MODE_AUTOALIGN) {
						aGL.fillRect(0, aCropRect.y+aCropRect.height, aWidthOut, aHeightOut-(aCropRect.y+aCropRect.height));//Bottom
					}
					aGL.setComposite(AlphaComposite.SrcOver);
					if(aMedia.selState > 0 && showSecureCropBox
							//Only in processing mode
							&& aProcessingMode == TDConfig.PROCESSING_MODE_WORK) {
						aGL.setColor(Color.GRAY);
						aGL.drawRect(aCropRect.x,aCropRect.y,aCropRect.width,aCropRect.height);
						aGL.setColor(Color.WHITE);
					}
					aGL.dispose();
					aMedia.setWorkLeft(aProcessedBIL);
				}
			});
			aThL.start();
			Thread aThR = new Thread(new Runnable() {
				@Override
				public void run() {
					BufferedImage aProcessedBIR = ImageUtils.createImage(aWidthOut,aHeightOut,tde.config.processingHdr[aProcessingMode]);
					Graphics2D aGR = (Graphics2D)aProcessedBIR.getGraphics();
					if(!tde.config.fastMode || aProcessingMode == TDConfig.PROCESSING_MODE_RENDER) {
						aGR.setRenderingHint(RenderingHints.KEY_ANTIALIASING,RenderingHints.VALUE_ANTIALIAS_ON);
						aGR.setRenderingHint(RenderingHints.KEY_RENDERING,RenderingHints.VALUE_RENDER_QUALITY);
					}
					aGR.drawImage(aBIR,aFinalR,null);
					aGR.setComposite(AlphaComposite.Clear);
					if(secureCropLeft
							//always with auto align
							|| aProcessingMode == TDConfig.PROCESSING_MODE_AUTOALIGN) {
						aGR.fillRect(0, 0, aCropRect.x, aHeightOut);//Left
					}
					if(secureCropRight
							//always with auto align
							|| aProcessingMode == TDConfig.PROCESSING_MODE_AUTOALIGN) {
						aGR.fillRect(aCropRect.x+aCropRect.width, 0, aWidthOut-(aCropRect.x+aCropRect.width), aHeightOut);//Right
					}
					if(secureCropTop
							//always with auto align
							|| aProcessingMode == TDConfig.PROCESSING_MODE_AUTOALIGN) {
						aGR.fillRect(0, 0, aWidthOut, aCropRect.y);//Top
					}
					if(secureCropBottom
							//always with auto align
							|| aProcessingMode == TDConfig.PROCESSING_MODE_AUTOALIGN) {
						aGR.fillRect(0, aCropRect.y+aCropRect.height, aWidthOut, aHeightOut-(aCropRect.y+aCropRect.height));//Bottom
					}
					aGR.setComposite(AlphaComposite.SrcOver);
					if(aMedia.selState > 0 && showSecureCropBox
							//Only in processing mode
							&& aProcessingMode == TDConfig.PROCESSING_MODE_WORK) {
						aGR.setColor(Color.LIGHT_GRAY);
						aGR.drawRect(aCropRect.x,aCropRect.y,aCropRect.width,aCropRect.height);
						aGR.setColor(Color.WHITE);
					}
					aGR.dispose();
					aMedia.setWorkRight(aProcessedBIR);

				}
			});
			aThR.start();
			aThL.join();
			aThR.join();
		}
		catch(Throwable t) {
			t.printStackTrace(System.err);
		}
	}

}
