package com.cubaix.TDenlive.processors;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferFloat;
import java.awt.image.DataBufferInt;
import java.util.Vector;

import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.graphics.Rectangle;

import com.cubaix.TDenlive.TDConfig;
import com.cubaix.TDenlive.TDenlive;
import com.cubaix.TDenlive.medias.Clip;
import com.cubaix.TDenlive.medias.Media;
import com.cubaix.TDenlive.utils.Frame;
import com.cubaix.TDenlive.utils.ImageUtils;
import com.cubaix.TDenlive.utils.Luma;
import com.cubaix.TDenlive.xml.XmlObject;
import com.cubaix.TDenlive.xml.XmlTag;

public class Framing extends Processor {
	class Key{
		double opacity = 1.0;
		Frame frame = null;
		double x = 0.0;
		long time = 0;
	}
	Vector<Key> keys = new Vector<Key>();
	
	public Framing(TDenlive aTDe,Media aTargetMedia) {
		super(aTDe,aTargetMedia);
		isExpended = true;
		keys.add(new Key());
		keys.lastElement().frame = tde.gui.frames.getFrame("BLANK");
	}

	@Override
	public String getClassName() {return "Framing";}
	
	@Override
	public int openProject(Vector<XmlObject> aOs, int o) throws Exception {
		keys.clear();
		for(;o < aOs.size();o++) {
			XmlObject aO = aOs.elementAt(o);
			if(aO instanceof XmlTag) {
				XmlTag aT = (XmlTag)aO;
				if("/Framing".equalsIgnoreCase(aT.tagName)) {
					return o;
				}
				if("opacity".equalsIgnoreCase(aT.tagName)) {
					o++;
					keys.add(new Key());
					keys.lastElement().opacity = Double.parseDouble(aOs.elementAt(o).text);
				}
				if("frame".equalsIgnoreCase(aT.tagName)) {
					o++;
					keys.lastElement().frame = tde.gui.frames.getFrame(aOs.elementAt(o).text.replaceAll("[\\\\\\\\]", "/"));
				}
				if("x".equalsIgnoreCase(aT.tagName)) {
					o++;
					keys.lastElement().x = Double.parseDouble(aOs.elementAt(o).text);
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
		aSB.append("						<Framing>\n");
		for(Key aK : keys) {
			aSB.append("							<opacity>" + aK.opacity + "</opacity>\n");
			aSB.append("							<frame>" + aK.frame.path + "</frame>\n");
			aSB.append("							<x>" + aK.x + "</x>\n");
			aSB.append("							<time>" + aK.time + "</time>\n");
		}
		aSB.append("						</Framing>\n");
	}

	@Override
	void setDescr() {
		name = tde.gui.lngs.get("Processor.Framing");
		icon = "binoculars.png";
	}

	public boolean isZero() {
		return true; 
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
	
	public double getOpacity(int aKey) {
		return keys.elementAt(aKey).opacity;
	}

	public void setOpacity(int aKey,double aOpacity) {
		keys.elementAt(aKey).opacity = aOpacity;
	}

	public double getX(int aKey) {
		return keys.elementAt(aKey).x;
	}

	public void setX(int aKey,double aX) {
		keys.elementAt(aKey).x = aX;
	}

	public Frame getFrame(int aKey) {
		return keys.elementAt(aKey).frame;
	}

	public void setFrame(int aKey,Frame frame) {
		keys.elementAt(aKey).frame = frame;
	}

	public long getTime(int aKey) {
		return keys.elementAt(aKey).time;
	}

	@Override
	public Image process2Swt(Media aMedia,int aProcessingMode,Rectangle aTargetSize,long aTime) {
		// TODO Auto-generated method stub
		return null;
	}

	Key getInterKeyGT(long aTimePos){
		long aTime = aTimePos-((Clip)targetMedia).getStartTimeMS();
		for(int k = keys.size()-1;k >= 0;k--) {
			Key aKey = keys.elementAt(k);
			if(aKey.time <= aTime) {
				if(k == keys.size()-1) {
					Key aInter = new Key();
					aInter.opacity = aKey.opacity;
					aInter.frame = aKey.frame;
					aInter.x = aKey.x;
					aInter.time = aTime;
					return aInter;
				}
				Key aNext = keys.elementAt(k+1);
				Key aInter = new Key();
				double aFact = (aTime-aKey.time)/(double)(aNext.time-aKey.time);
				aInter.opacity = aKey.opacity*(1.0-aFact)+aNext.opacity*aFact;
				aInter.frame = aKey.frame;
				aInter.x = aKey.x*(1.0-aFact)+aNext.x*aFact;
				aInter.time = aTime;
				return aInter;
			}
		}
		//??
		Key aKey = keys.firstElement();
		Key aInter = new Key();
		aInter.opacity = aKey.opacity;
		aInter.frame = aKey.frame;
		aInter.x = aKey.x;
		aInter.time = aTime;
		return aInter;
	}
	
	@Override
	public void process2Awt(Media aMedia,int aProcessingMode,long aTime) {
		try {
			Thread aThL = new Thread(new Runnable() {
				@Override
				public void run() {
					BufferedImage aBIL = aMedia.getWorkLeft(aProcessingMode,aTime);
					BufferedImage aProcessedBIL = 
//							tde.config.processingHdr[aProcessingMode]?
//							processAwtHdr(aBIL,aProcessingMode,true):
								processAwtImage(aBIL,aProcessingMode,aTime,true,tde.config.processingHdr[aProcessingMode]);
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
								processAwtImage(aBIR,aProcessingMode,aTime,false,tde.config.processingHdr[aProcessingMode]);
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

	BufferedImage processAwtImage(BufferedImage aBI,int aProcessingMode,long aTime,boolean aIsL,boolean aIsHdr) {
		int aWidth = aBI.getWidth();
		int aHeight = aBI.getHeight();
		BufferedImage aFBI = aIsHdr?ImageUtils.createHdr(aWidth, aHeight)
				:ImageUtils.createImage(aWidth, aHeight);
		
		//Create mask
		Graphics2D aFG = (Graphics2D)aFBI.getGraphics();
		if(!tde.config.fastMode || aProcessingMode == TDConfig.PROCESSING_MODE_RENDER) {
			aFG.setRenderingHint(RenderingHints.KEY_ANTIALIASING,RenderingHints.VALUE_ANTIALIAS_ON);
			aFG.setRenderingHint(RenderingHints.KEY_RENDERING,RenderingHints.VALUE_RENDER_QUALITY);
		}
		
		Key aKey = getInterKeyGT(aTime);
		if(aKey.frame == null || aKey.frame.bi == null || "BLANK".equals(aKey.frame.path)) {
			Color aC = new Color((float)aKey.opacity,(float)aKey.opacity,(float)aKey.opacity);
			aFG.setColor(aC);
			aFG.fillRect(0, 0, aWidth, aHeight);
		}
		else {
			if(aIsL) {
				aFG.drawImage(aKey.frame.bi
						, (aKey.x>=0?0:(int)(aKey.x*aWidth)), (int)(-Math.abs(aKey.x)*aHeight/2)
						, aWidth+(aKey.x>0?(int)(Math.abs(aKey.x)*aWidth):0), aHeight+(int)(Math.abs(aKey.x)*aHeight)
						, 0, 0, aKey.frame.bi.getWidth(), aKey.frame.bi.getHeight(), null);
			}
			else {
				aFG.drawImage(aKey.frame.bi
						, (aKey.x<=0?0:(int)(-aKey.x*aWidth)), (int)(-Math.abs(aKey.x)*aHeight/2)
						, aWidth+(aKey.x<0?(int)(Math.abs(aKey.x)*aWidth):0), aHeight+(int)(Math.abs(aKey.x)*aHeight)
						, 0, 0, aKey.frame.bi.getWidth(), aKey.frame.bi.getHeight(), null);
			}
		}
		
		//Apply mask
		if(aIsHdr) {
			float[] aDB = ((DataBufferFloat)aBI.getRaster().getDataBuffer()).getData();
			float[] aFDB = ((DataBufferFloat)aFBI.getRaster().getDataBuffer()).getData();
			for(int x = 0;x<aWidth;x++) {
				for(int y = 0;y<aHeight;y++) {
					double aR = aDB[y*aWidth*4+x*4];//Red
					double aG = aDB[y*aWidth*4+x*4+1];//Green
					double aB = aDB[y*aWidth*4+x*4+2];//Blue
					double aAlpha = aKey.opacity
							*aDB[y*aWidth*4+x*4+3]//Alpha
							*aFDB[y*aWidth*4+x*4+3];//Alpha
				    aFDB[y*aWidth*4+x*4]=(float)aR;//Red
				    aFDB[y*aWidth*4+x*4+1]=(float)aG;//Green
				    aFDB[y*aWidth*4+x*4+2]=(float)aB;//Blue
				    aFDB[y*aWidth*4+x*4+3]=(float)aAlpha;//Alpha
				}
			}
		}
		else {
			int[] aDB = ((DataBufferInt)aBI.getRaster().getDataBuffer()).getData();
			int[] aFDB = ((DataBufferInt)aFBI.getRaster().getDataBuffer()).getData();
			for(int p = 0;p < aDB.length;p++) {
				double aAlpha = 255.0
						*aKey.opacity
						*(((aDB[p]>>24) & 0xFF)/255.0) 
						*(((aFDB[p]>>24) & 0xFF)/255.0);//Alpha
				aAlpha = aAlpha < 0 ? 0 : (aAlpha > 255 ? 255 : aAlpha);
				aFDB[p] =
						//					aDB[p];
						//					aFDB[p];
						(((int)aAlpha)&0xFF)<<24
						|(aDB[p]&0xFFFFFF);
			}
		}
			
		return aFBI;
	}
}
