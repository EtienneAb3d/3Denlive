package com.cubaix.TDenlive.processors;

import java.awt.AlphaComposite;
import java.awt.Color;
import java.awt.Composite;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.Shape;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferFloat;
import java.awt.image.DataBufferInt;
import java.io.BufferedWriter;
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

public class Cropper extends Processor {
	public static class Key{
		public double leftL = 0.0;
		public double leftR = 0.0;
		public double rightL = 1.0;
		public double rightR = 1.0;
		public double top = 0.0;
		public double bottom = 1.0;
		public double angle = 0.0;
		public double shearXL = 0.0;
		public double shearXR = 0.0;
		public double transparency = 1.0;
		public long time = 0;

		Key copy(long aTime) {
			Key aK = new Key();
			aK.leftL = leftL;
			aK.leftR = leftR;
			aK.rightL = rightL;
			aK.rightR = rightR;
			aK.top = top;
			aK.bottom = bottom;
			aK.angle = angle;
			aK.shearXL = shearXL;
			aK.shearXR = shearXR;
			aK.transparency = transparency;
			aK.time = aTime;
			return aK;
		}
	}
	Vector<Key> keys = new Vector<Key>();
	
	public boolean showCutLines = true;
	/**
	 * Sliders of the GUI linked by their check box (not saved): leftL, leftR, rightL, rightR, top, bottom,
	 * shearXL, shearXR (see CropperGUI)
	 */
	public boolean[] linked = new boolean[8];

	public Cropper(TDenlive aTDe, Media aTargetMedia) {
		super(aTDe, aTargetMedia);
		isExpended = true;
		keys.add(new Key());
	}

	@Override
	public String getClassName() {return "Cropper";}

	@Override
	public int openProject(Vector<XmlObject> aOs, int o) throws Exception {
		keys.clear();
		for(;o < aOs.size();o++) {
			XmlObject aO = aOs.elementAt(o);
			if(aO instanceof XmlTag) {
				XmlTag aT = (XmlTag)aO;
				if("/Cropper".equalsIgnoreCase(aT.tagName)) {
					break;
				}
				if("showCutLines".equalsIgnoreCase(aT.tagName)) {
					o++;
					showCutLines = "true".equals(aOs.elementAt(o).text);
					continue;
				}
				if("leftL".equalsIgnoreCase(aT.tagName)) {
					//First tag of a key (projects before the keys have a single key without time)
					keys.add(new Key());
				}
				if(keys.size() <= 0) {
					continue;
				}
				Key aK = keys.lastElement();
				if("leftL".equalsIgnoreCase(aT.tagName)) {
					o++;
					aK.leftL = Double.parseDouble(aOs.elementAt(o).text);
				}
				if("leftR".equalsIgnoreCase(aT.tagName)) {
					o++;
					aK.leftR = Double.parseDouble(aOs.elementAt(o).text);
				}
				if("rightL".equalsIgnoreCase(aT.tagName)) {
					o++;
					aK.rightL = Double.parseDouble(aOs.elementAt(o).text);
				}
				if("rightR".equalsIgnoreCase(aT.tagName)) {
					o++;
					aK.rightR = Double.parseDouble(aOs.elementAt(o).text);
				}
				if("top".equalsIgnoreCase(aT.tagName)) {
					o++;
					aK.top = Double.parseDouble(aOs.elementAt(o).text);
				}
				if("bottom".equalsIgnoreCase(aT.tagName)) {
					o++;
					aK.bottom = Double.parseDouble(aOs.elementAt(o).text);
				}
				if("angle".equalsIgnoreCase(aT.tagName)) {
					o++;
					aK.angle = Double.parseDouble(aOs.elementAt(o).text);
				}
				if("shearXL".equalsIgnoreCase(aT.tagName)) {
					o++;
					aK.shearXL = Double.parseDouble(aOs.elementAt(o).text);
				}
				if("shearXR".equalsIgnoreCase(aT.tagName)) {
					o++;
					aK.shearXR = Double.parseDouble(aOs.elementAt(o).text);
				}
				if("transparency".equalsIgnoreCase(aT.tagName)) {
					o++;
					aK.transparency = Double.parseDouble(aOs.elementAt(o).text);
				}
				if("time".equalsIgnoreCase(aT.tagName)) {
					o++;
					aK.time = Long.parseLong(aOs.elementAt(o).text);
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
		aSB.append("						<Cropper>\n");
		aSB.append("							<showCutLines>" + showCutLines + "</showCutLines>\n");
		for(Key aK : keys) {
			aSB.append("							<leftL>" + aK.leftL + "</leftL>\n");
			aSB.append("							<leftR>" + aK.leftR + "</leftR>\n");
			aSB.append("							<rightL>" + aK.rightL + "</rightL>\n");
			aSB.append("							<rightR>" + aK.rightR + "</rightR>\n");
			aSB.append("							<top>" + aK.top + "</top>\n");
			aSB.append("							<bottom>" + aK.bottom + "</bottom>\n");
			aSB.append("							<angle>" + aK.angle + "</angle>\n");
			aSB.append("							<shearXL>" + aK.shearXL + "</shearXL>\n");
			aSB.append("							<shearXR>" + aK.shearXR + "</shearXR>\n");
			aSB.append("							<transparency>" + aK.transparency + "</transparency>\n");
			aSB.append("							<time>" + aK.time + "</time>\n");
		}
		aSB.append("						</Cropper>\n");
	}

	@Override
	void setDescr() {
		name = tde.gui.lngs.get("Processor.Cropper");
		icon = "rectFrame.gif";
	}

	@Override
	public int getNbKeys() {
		return keys.size();
	}
	
	@Override
	public int addKeyGT(long aTime){
		if(aTime < ((Clip)targetMedia).getStartTimeMS() || aTime <= ((Clip)targetMedia).getKeysOriginMS() || aTime > ((Clip)targetMedia).getStartTimeMS() + ((Clip)targetMedia).getDurationMS()) {
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
	
	@Override
	public void deleteKey(int aKey) {
		//Can't delete first
		if(aKey <= 0 || aKey >= keys.size()) {
			return;
		}
		keys.remove(aKey);
	}
	
	@Override
	public int getPrevKeyGT(long aTime){
		long aT = aTime-((Clip)targetMedia).getKeysOriginMS();
		for(int k = keys.size() - 1;k >= 0;k--) {
			if(keys.elementAt(k).time <= aT) {
				return k;
			}
		}
		//No key before
		return -1;
	}
	
	@Override
	public int getNextKeyGT(long aTime){
		long aT = aTime-((Clip)targetMedia).getKeysOriginMS();
		for(int k = 0;k < keys.size();k++) {
			if(keys.elementAt(k).time > aT) {
				return k;
			}
		}
		//No key after
		return -1;
	}

	@Override
	public long getTime(int aKey) {
		return key(aKey).time;
	}

	public Key getKey(int aKey) {
		return key(aKey);
	}

	/** Values interpolated at the time cursor, for the display (index DISPLAY_KEY, UI thread only) */
	Key displayKey = null;

	@Override
	public boolean setDisplayKeyGT(long aTime) {
		try {
			displayKey = getInterKeyGT(aTime);
		}
		catch(Throwable t) {
			displayKey = null;
		}
		return displayKey != null;
	}

	/** The key aKey, or the display key */
	Key key(int aKey) {
		return aKey == DISPLAY_KEY && displayKey != null ? displayKey : keys.elementAt(aKey);
	}

	Key getInterKeyGT(long aTimePos){
		long aTime = aTimePos-((Clip)targetMedia).getKeysOriginMS();
		for(int k = keys.size()-1;k >= 0;k--) {
			Key aKey = keys.elementAt(k);
			if(aKey.time <= aTime) {
				if(k == keys.size()-1) {
					return aKey.copy(aTime);
				}
				Key aNext = keys.elementAt(k+1);
				double aF = (aTime-aKey.time)/(double)(aNext.time-aKey.time);
				double aG = 1.0-aF;
				Key aInter = new Key();
				aInter.leftL = aKey.leftL*aG+aNext.leftL*aF;
				aInter.leftR = aKey.leftR*aG+aNext.leftR*aF;
				aInter.rightL = aKey.rightL*aG+aNext.rightL*aF;
				aInter.rightR = aKey.rightR*aG+aNext.rightR*aF;
				aInter.top = aKey.top*aG+aNext.top*aF;
				aInter.bottom = aKey.bottom*aG+aNext.bottom*aF;
				aInter.angle = aKey.angle*aG+aNext.angle*aF;
				aInter.shearXL = aKey.shearXL*aG+aNext.shearXL*aF;
				aInter.shearXR = aKey.shearXR*aG+aNext.shearXR*aF;
				aInter.transparency = aKey.transparency*aG+aNext.transparency*aF;
				aInter.time = aTime;
				return aInter;
			}
		}
		//Before the first key
		return keys.firstElement().copy(aTime);
	}

	public boolean isShowCutLines() {
		return showCutLines;
	}

	public void setShowCutLines(boolean showCutLines) {
		this.showCutLines = showCutLines;
	}

	@Override
	public Image process2Swt(Media aMedia, int aProcessingMode, Rectangle aTargetSize, long aTime) {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public void process2Awt(Media aMedia, int aProcessingMode, long aTime) {
		try {
			final Key aK = getInterKeyGT(aTime);
			Thread aThL = new Thread(new Runnable() {
				@Override
				public void run() {
					BufferedImage aBIL = aMedia.getWorkLeft(aProcessingMode,aTime);
					BufferedImage aProcessedBIL = 
//							tde.config.processingHdr[aProcessingMode]?
//							processAwtHdr(aBIL,aProcessingMode,true):
								processAwtImage(aBIL,aK,aProcessingMode,true,tde.config.processingHdr[aProcessingMode]);
					aMedia.setWorkLeft(aProcessedBIL);
				}
			});
			aThL.start();
			Thread aThR = new Thread(new Runnable() {
				@Override
				public void run() {
					BufferedImage aBIR = aMedia.getWorkRight(aProcessingMode,aTime);
					BufferedImage aProcessedBIR = 
//							tde.config.processingHdr[aProcessingMode]?
//							processAwtHdr(aBIR,aProcessingMode,false):
								processAwtImage(aBIR,aK,aProcessingMode,false,tde.config.processingHdr[aProcessingMode]);
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

	BufferedImage processAwtImage(BufferedImage aBI,Key aK,int aProcessingMode,boolean aIsL,boolean aIsHdr) {
		double leftL = aK.leftL, leftR = aK.leftR, rightL = aK.rightL, rightR = aK.rightR;
		double top = aK.top, bottom = aK.bottom, angle = aK.angle;
		double shearXL = aK.shearXL, shearXR = aK.shearXR, transparency = aK.transparency;
		int aWidth = aBI.getWidth();
		int aHeight = aBI.getHeight();
		BufferedImage aFBI = aIsHdr?ImageUtils.createHdr(aWidth, aHeight)
				:ImageUtils.createImage(aWidth, aHeight);

		AffineTransform aRotL = null;
		AffineTransform aRotR = null;
		if(aIsL && (angle != 0 || shearXL != 0)) {
			aRotL = AffineTransform.getTranslateInstance(aWidth/2, aHeight/2);
			aRotL.concatenate(AffineTransform.getRotateInstance(Math.PI*angle/180.0));
			aRotL.concatenate(AffineTransform.getShearInstance(shearXL, 0));
			aRotL.concatenate(AffineTransform.getTranslateInstance(-aWidth/2, -aHeight/2));
		}
		if(!aIsL && (angle != 0 || shearXR != 0)) {
			aRotR = AffineTransform.getTranslateInstance(aWidth/2, aHeight/2);
			aRotR.concatenate(AffineTransform.getRotateInstance(Math.PI*angle/180.0));
			aRotR.concatenate(AffineTransform.getShearInstance(shearXR, 0));
			aRotR.concatenate(AffineTransform.getTranslateInstance(-aWidth/2, -aHeight/2));
		}
		
		//Create mask
		Graphics2D aFG = (Graphics2D)aFBI.getGraphics();
		aFG.setColor(Color.WHITE);
		aFG.fillRect(0, 0, aWidth, aHeight);
		if(!tde.config.fastMode || aProcessingMode == TDConfig.PROCESSING_MODE_RENDER) {
			aFG.setRenderingHint(RenderingHints.KEY_ANTIALIASING,RenderingHints.VALUE_ANTIALIAS_ON);
			aFG.setRenderingHint(RenderingHints.KEY_RENDERING,RenderingHints.VALUE_RENDER_QUALITY);
		}
		
		aFG.setColor(new Color((int)(255-transparency*255)));//Blue mask
		if(aIsL) {
			if(aRotL != null) {
				try {
					aFG.setTransform(aRotL.createInverse());
				}
				catch(Throwable t) {
					t.printStackTrace(System.err);
				}
			}
			if(leftL > 0) {
				aFG.fillRect(-aWidth, -aHeight, (int)(leftL*aWidth)+aWidth, 3*aHeight);
			}
			if(rightL < 1) {
				aFG.fillRect((int)(rightL*aWidth), -aHeight,2*aWidth, 3*aHeight);
			}
		}
		else {
			if(aRotR != null) {
				try {
					aFG.setTransform(aRotR.createInverse());
				}
				catch(Throwable t) {
					t.printStackTrace(System.err);
				}
			}
			if(leftR > 0) {
				aFG.fillRect(-aWidth, -aHeight, (int)(leftR*aWidth)+aWidth, 3*aHeight);
			}
			if(rightR < 1) {
				aFG.fillRect((int)(rightR*aWidth), -aHeight,2*aWidth, 3*aHeight);
			}
		}
		if(top > 0) {
			aFG.fillRect(-aWidth, -aHeight, 3*aWidth, aHeight+(int)(top*aHeight));
		}
		if(bottom < 1) {
			aFG.fillRect(-aWidth, (int)(bottom*aHeight), 3*aWidth, 2*aHeight);
		}
		
		//Apply mask
		if(aIsHdr) {
			float[] aDB = ((DataBufferFloat)aBI.getRaster().getDataBuffer()).getData();
			float[] aFDB = ((DataBufferFloat)aFBI.getRaster().getDataBuffer()).getData();
			for(int x = 0;x<aWidth;x++) {
				for(int y = 0;y<aHeight;y++) {
					float aR = aDB[y*aWidth*4+x*4];//Red
					float aG = aDB[y*aWidth*4+x*4+1];//Green
					float aB = aDB[y*aWidth*4+x*4+2];//Blue
					float aAlpha = aDB[y*aWidth*4+x*4+3]//Alpha
							*aFDB[y*aWidth*4+x*4+2];//Blue
				    aFDB[y*aWidth*4+x*4]=(float)aR;//Red
				    aFDB[y*aWidth*4+x*4+1]=(float)aG;//Green
				    aFDB[y*aWidth*4+x*4+2]=(float)aB;//Blue
				    aFDB[y*aWidth*4+x*4+3]=aAlpha;//Alpha
				}
			}
		}
		else {
			int[] aDB = ((DataBufferInt)aBI.getRaster().getDataBuffer()).getData();
			int[] aFDB = ((DataBufferInt)aFBI.getRaster().getDataBuffer()).getData();
			for(int p = 0;p < aDB.length;p++) {
				double aAlpha = 255.0
						*(((aDB[p]>>24)&0xFF)/255.0) 
						*((aFDB[p] & 0xFF)/255.0);
				aAlpha = aAlpha < 0 ? 0 : (aAlpha > 255 ? 255 : aAlpha);
				aFDB[p] =
						//					aDB[p];
						//					aFDB[p];
						(((int)aAlpha)&0xFF)<<24
						|(aDB[p]&0xFFFFFF);
			}
		}
			
		if(showCutLines && aProcessingMode == TDConfig.PROCESSING_MODE_WORK) {
			aFG = (Graphics2D)aFBI.getGraphics();
			if(!tde.config.fastMode || aProcessingMode == TDConfig.PROCESSING_MODE_RENDER) {
				aFG.setRenderingHint(RenderingHints.KEY_ANTIALIASING,RenderingHints.VALUE_ANTIALIAS_ON);
				aFG.setRenderingHint(RenderingHints.KEY_RENDERING,RenderingHints.VALUE_RENDER_QUALITY);
			}
			aFG.setColor(Color.YELLOW);
			if(aIsL) {
				if(aRotL != null) {
					try {
						aFG.setTransform(aRotL.createInverse());
					}
					catch(Throwable t) {
						t.printStackTrace(System.err);
					}
				}
				if(leftL > 0) {
					aFG.drawLine((int)(leftL*aWidth), 0,(int)(leftL*aWidth), aHeight);
				}
				if(rightL < 1) {
					aFG.drawLine((int)(rightL*aWidth), 0,(int)(rightL*aWidth), aHeight);
				}
			}
			else {
				if(aRotR != null) {
					try {
						aFG.setTransform(aRotR.createInverse());
					}
					catch(Throwable t) {
						t.printStackTrace(System.err);
					}
				}
				if(leftR > 0) {
					aFG.drawLine((int)(leftR*aWidth), 0,(int)(leftR*aWidth), aHeight);
				}
				if(rightR < 1) {
					aFG.drawLine((int)(rightR*aWidth), 0,(int)(rightR*aWidth), aHeight);
				}
			}
			if(top > 0) {
				aFG.drawLine(0,(int)(top*aHeight),aWidth,(int)(top*aHeight));
			}
			if(bottom < 1) {
				aFG.drawLine(0,(int)(bottom*aHeight),aWidth,(int)(bottom*aHeight));
			}
		}
		
		return aFBI;
	}

	
//	BufferedImage processAwtHdr(BufferedImage aBI,int aProcessingMode,boolean aIsL) {
//		int aWidth = aBI.getWidth();
//		int aHeight = aBI.getHeight();
//		BufferedImage aFBI = ImageUtils.createHdr(aWidth, aHeight);
//	float[] aDB = ((DataBufferFloat)aBI.getRaster().getDataBuffer()).getData();
//		float[] aFDB = ((DataBufferFloat)aFBI.getRaster().getDataBuffer()).getData();
//
//		AffineTransform aRotL = null;
//		AffineTransform aRotR = null;
//		if(aIsL && (angle != 0 || shearXL != 0)) {
//			aRotL = AffineTransform.getTranslateInstance(aWidth/2, aHeight/2);
//			aRotL.concatenate(AffineTransform.getRotateInstance(Math.PI*angle/180.0));
//			aRotL.concatenate(AffineTransform.getShearInstance(shearXL, 0));
//			aRotL.concatenate(AffineTransform.getTranslateInstance(-aWidth/2, -aHeight/2));
//		}
//		if(!aIsL && (angle != 0 || shearXR != 0)) {
//			aRotR = AffineTransform.getTranslateInstance(aWidth/2, aHeight/2);
//			aRotR.concatenate(AffineTransform.getRotateInstance(Math.PI*angle/180.0));
//			aRotR.concatenate(AffineTransform.getShearInstance(shearXR, 0));
//			aRotR.concatenate(AffineTransform.getTranslateInstance(-aWidth/2, -aHeight/2));
//		}
//		for(int aX = 0;aX<aWidth;aX++) {
//			for(int aY = 0;aY<aHeight;aY++) {
//				int x = aX;
//				int y = aY;
//				float aR = aDB[y*aWidth*4+x*4];//Red
//				float aG = aDB[y*aWidth*4+x*4+1];//Green
//				float aB = aDB[y*aWidth*4+x*4+2];//Blue
//				float aAlpha = aDB[y*aWidth*4+x*4+3];//Alpha
//
//			    double aNewR = aR,aNewG = aG,aNewB = aB;
//
//			    if(aIsL) {
//					if(aRotL != null) {
//						Shape aS = aRotL.createTransformedShape(new java.awt.Rectangle(aX, aY,aX, aY));
//						double[] aSeg = new double[4];
//						aS.getPathIterator(null).currentSegment(aSeg);
//						x = (int)aSeg[0];
//						y = (int)aSeg[1];
//					}
//			    	if(leftL > 0 && x < leftL*aWidth) {
//			    		aAlpha = 0;
//			    	}
//			    	if(rightL < 1 && x > rightL*aWidth) {
//			    		aAlpha = 0;
//			    	}
//			    }
//			    else {
//					if(aRotR != null) {
//						Shape aS = aRotR.createTransformedShape(new java.awt.Rectangle(aX, aY,aX, aY));
//						double[] aSeg = new double[4];
//						aS.getPathIterator(null).currentSegment(aSeg);
//						x = (int)aSeg[0];
//						y = (int)aSeg[1];
//					}
//			    	if(leftR > 0 && x < leftR*aWidth) {
//			    		aAlpha = 0;
//			    	}
//			    	if(rightR < 1 && x > rightR*aWidth) {
//			    		aAlpha = 0;
//			    	}
//			    }
//		    	if(top > 0 && y < top*aHeight) {
//		    		aAlpha = 0;
//		    	}
//		    	if(bottom < 1 && y > bottom*aHeight) {
//		    		aAlpha = 0;
//		    	}
//			    
//			    aFDB[aY*aWidth*4+aX*4]=(float)aNewR;//Red
//			    aFDB[aY*aWidth*4+aX*4+1]=(float)aNewG;//Green
//			    aFDB[aY*aWidth*4+aX*4+2]=(float)aNewB;//Blue
//			    aFDB[aY*aWidth*4+aX*4+3]=aAlpha;//Alpha
//			}
//		}
//		
//		if(showCutLines && aProcessingMode == TDConfig.PROCESSING_MODE_WORK) {
//			Graphics2D aG = (Graphics2D)aFBI.getGraphics();
//			aG.setColor(Color.YELLOW);
//			if(aIsL) {
//				if(aRotL != null) {
//					try {
//						aG.setTransform(aRotL.createInverse());
//					}
//					catch(Throwable t) {
//						t.printStackTrace(System.err);
//					}
//				}
//				aG.drawLine((int)(leftL*aWidth), 0,(int)(leftL*aWidth), aHeight);
//				aG.drawLine((int)(rightL*aWidth), 0,(int)(rightL*aWidth), aHeight);
//				aG.drawLine(0,(int)(top*aHeight),aWidth,(int)(top*aHeight));
//				aG.drawLine(0,(int)(bottom*aHeight),aWidth,(int)(bottom*aHeight));
//			}
//			else {
//				if(aRotR != null) {
//					try {
//						aG.setTransform(aRotR.createInverse());
//					}
//					catch(Throwable t) {
//						t.printStackTrace(System.err);
//					}
//				}
//				aG.drawLine((int)(leftR*aWidth), 0,(int)(leftR*aWidth), aHeight);
//				aG.drawLine((int)(rightR*aWidth), 0,(int)(rightR*aWidth), aHeight);
//				aG.drawLine(0,(int)(top*aHeight),aWidth,(int)(top*aHeight));
//				aG.drawLine(0,(int)(bottom*aHeight),aWidth,(int)(bottom*aHeight));
//			}
//		}
//
//		return aFBI;
//
//	}
}
