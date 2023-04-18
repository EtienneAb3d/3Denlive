package com.cubaix.TDenlive.processors;

import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.AffineTransform;
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

public class Placer extends Processor {
	class Key{
		double x = 0;
		double y = 0;
		double scale = 1.0;
		long time = 0;
	}
	Vector<Key> keys = new Vector<Key>();
	
	public Placer(TDenlive aTDe,Media aTargetMedia) {
		super(aTDe,aTargetMedia);
		isExpended = true;
		keys.add(new Key());
	}

	@Override
	public String getClassName() {return "Placer";}
	
	@Override
	public int openProject(Vector<XmlObject> aOs, int o) throws Exception {
		keys.clear();
		for(;o < aOs.size();o++) {
			XmlObject aO = aOs.elementAt(o);
			if(aO instanceof XmlTag) {
				XmlTag aT = (XmlTag)aO;
				if("/Placer".equalsIgnoreCase(aT.tagName)) {
					return o;
				}
				if("x".equalsIgnoreCase(aT.tagName)) {
					o++;
					keys.add(new Key());
					keys.lastElement().x = Double.parseDouble(aOs.elementAt(o).text);
				}
				if("y".equalsIgnoreCase(aT.tagName)) {
					o++;
					keys.lastElement().y = Double.parseDouble(aOs.elementAt(o).text);
				}
				if("scale".equalsIgnoreCase(aT.tagName)) {
					o++;
					keys.lastElement().scale = Double.parseDouble(aOs.elementAt(o).text);
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
		aSB.append("						<Placer>\n");
		for(Key aK : keys) {
			aSB.append("							<x>" + aK.x + "</x>\n");
			aSB.append("							<y>" + aK.y + "</y>\n");
			aSB.append("							<scale>" + aK.scale + "</scale>\n");
			aSB.append("							<time>" + aK.time + "</time>\n");
		}
		aSB.append("						</Placer>\n");
	}

	@Override
	void setDescr() {
		name = tde.gui.lngs.get("Processor.Placer");
		icon = "move.gif";
	}

	public boolean isZero() {
		for(Key aK : keys) {
			if(!(aK.x == 0 && aK.y == 0 && aK.scale == 1.0)) {
				return false;
			}
		}
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
	
	public double getX(int aKey) {
		return keys.elementAt(aKey).x;
	}

	public void setX(int aKey,double x) {
		warnNextAlignments();
		keys.elementAt(aKey).x = x;
	}

	public double getY(int aKey) {
		return keys.elementAt(aKey).y;
	}

	public void setY(int aKey,double y) {
		warnNextAlignments();
		keys.elementAt(aKey).y = y;
	}

	public double getScale(int aKey) {
		return keys.elementAt(aKey).scale;
	}

	public void setScale(int aKey,double scale) {
		warnNextAlignments();
		keys.elementAt(aKey).scale = scale;
	}
	
	public long getTime(int aKey) {
		return keys.elementAt(aKey).time;
	}

	public void warnNextAlignments() {
		//Changing the placement will alter next alignments !
		boolean aSawMe = false;
		for(Processor aP : ((Clip)targetMedia).getProcessors()) {
			if(this == aP) {
				aSawMe = true;
			}
			if(aSawMe && 
					(aP instanceof StereoAligner
							|| aP instanceof Cropper)
					&& !aP.isWarning) {
				aP.isWarning = true;
				tde.gui.display.asyncExec(new Runnable() {
					@Override
					public void run() {
						//Force refresh
						tde.gui.processorGUI.setProcessors(((Clip)targetMedia).getProcessors());
					}
				});
			}
		}
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
					aInter.x = aKey.x;
					aInter.y = aKey.y;
					aInter.scale = aKey.scale;
					aInter.time = aTime;
					return aInter;
				}
				Key aNext = keys.elementAt(k+1);
				Key aInter = new Key();
				double aFact = (aTime-aKey.time)/(double)(aNext.time-aKey.time);
				aInter.x = aKey.x*(1.0-aFact)+aNext.x*aFact;
				aInter.y = aKey.y*(1.0-aFact)+aNext.y*aFact;
				aInter.scale = aKey.scale*(1.0-aFact)+aNext.scale*aFact;
				aInter.time = aTime;
				return aInter;
			}
		}
		//??
		Key aKey = keys.firstElement();
		Key aInter = new Key();
		aInter.x = aKey.x;
		aInter.y = aKey.y;
		aInter.scale = aKey.scale;
		aInter.time = aTime;
		return aInter;
	}
	
	@Override
	public void process2Awt(Media aMedia,int aProcessingMode,long aTime) {
		if(isZero()) {
			return;
		}
		try {
			Thread aThL = new Thread(new Runnable() {
				@Override
				public void run() {
					BufferedImage aBIL = aMedia.getWorkLeft(aProcessingMode,aTime);
					int aWidth = aBIL.getWidth();
					int aHeight = aBIL.getHeight();
					int aWidthOut = (int)(tde.config.outRatio*tde.config.processingResValues[aProcessingMode]);
					int aHeightOut = tde.config.processingResValues[aProcessingMode];
					BufferedImage aProcessedBIL = ImageUtils.createImage(aWidthOut,aHeightOut,tde.config.processingHdr[aProcessingMode]);
					Graphics2D aGL2 = (Graphics2D)aProcessedBIL.getGraphics();
					Key aKey = getInterKeyGT(aTime);
					AffineTransform aATL = new AffineTransform();
					aATL.translate(aKey.x * (aWidthOut+aWidth*aKey.scale)/2,aKey.y * (aHeightOut+aHeight*aKey.scale)/2);
					aATL.translate(aWidth/2.0, aHeight/2.0);
					aATL.scale(aKey.scale, aKey.scale);
					aATL.translate(-aWidth/2.0, -aHeight/2.0);
					if(!tde.config.fastMode || aProcessingMode == TDConfig.PROCESSING_MODE_RENDER) {
						aGL2.setRenderingHint(RenderingHints.KEY_ANTIALIASING,RenderingHints.VALUE_ANTIALIAS_ON);
						aGL2.setRenderingHint(RenderingHints.KEY_RENDERING,RenderingHints.VALUE_RENDER_QUALITY);
					}
					aGL2.drawImage(aBIL,aATL,null);
					aGL2.dispose();
					aMedia.setWorkLeft(aProcessedBIL);
				}
			});
			aThL.start();
			Thread aThR = new Thread(new Runnable() {
				@Override
				public void run() {
					BufferedImage aBIR = aMedia.getWorkRight(aProcessingMode,aTime);
					int aWidth = aBIR.getWidth();
					int aHeight = aBIR.getHeight();
					int aWidthOut = (int)(tde.config.outRatio*tde.config.processingResValues[aProcessingMode]);
					int aHeightOut = tde.config.processingResValues[aProcessingMode];
					BufferedImage aProcessedBIR = ImageUtils.createImage(aWidthOut,aHeightOut,tde.config.processingHdr[aProcessingMode]);
					Graphics2D aGR2 = (Graphics2D)aProcessedBIR.getGraphics();
					AffineTransform aATR = new AffineTransform();
					Key aKey = getInterKeyGT(aTime);
					aATR.translate(aKey.x * (aWidthOut+aWidth*aKey.scale)/2,aKey.y * (aHeightOut+aHeight*aKey.scale)/2);
					aATR.translate(aWidth/2.0, aHeight/2.0);
					aATR.scale(aKey.scale, aKey.scale);
					aATR.translate(-aWidth/2.0, -aHeight/2.0);
					if(!tde.config.fastMode || aProcessingMode == TDConfig.PROCESSING_MODE_RENDER) {
						aGR2.setRenderingHint(RenderingHints.KEY_ANTIALIASING,RenderingHints.VALUE_ANTIALIAS_ON);
						aGR2.setRenderingHint(RenderingHints.KEY_RENDERING,RenderingHints.VALUE_RENDER_QUALITY);
					}
					aGR2.drawImage(aBIR,aATR,null);
					aGR2.dispose();
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
