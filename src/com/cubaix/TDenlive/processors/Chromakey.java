package com.cubaix.TDenlive.processors;

import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferFloat;
import java.awt.image.DataBufferInt;
import java.util.Vector;

import org.eclipse.swt.graphics.Color;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.graphics.RGB;
import org.eclipse.swt.graphics.Rectangle;

import com.cubaix.TDenlive.TDConfig;
import com.cubaix.TDenlive.TDenlive;
import com.cubaix.TDenlive.GUI.processors.ChromakeyGUI;
import com.cubaix.TDenlive.medias.Clip;
import com.cubaix.TDenlive.medias.Media;
import com.cubaix.TDenlive.utils.ImageUtils;
import com.cubaix.TDenlive.xml.XmlObject;
import com.cubaix.TDenlive.xml.XmlTag;

public class Chromakey extends Processor {
	class Key{
		double tolerance = 0.1;
		int color = 0xFF5fe578;
		double bevel = 0.0;
		long time = 0;
	}
	Vector<Key> keys = new Vector<Key>();
	
	double pickedX = -1;
	double pickedY = -1;
	ChromakeyGUI gui = null;
	
	public Chromakey(TDenlive aTDe,Media aTargetMedia) {
		super(aTDe,aTargetMedia);
		isExpended = true;
		keys.add(new Key());
	}

	@Override
	public String getClassName() {return "Chromakey";}
	
	@Override
	public int openProject(Vector<XmlObject> aOs, int o) throws Exception {
		keys.clear();
		for(;o < aOs.size();o++) {
			XmlObject aO = aOs.elementAt(o);
			if(aO instanceof XmlTag) {
				XmlTag aT = (XmlTag)aO;
				if("/Chromakey".equalsIgnoreCase(aT.tagName)) {
					return o;
				}
				if("tolerance".equalsIgnoreCase(aT.tagName)) {
					o++;
					keys.add(new Key());
					keys.lastElement().tolerance = Double.parseDouble(aOs.elementAt(o).text);
				}
				if("color".equalsIgnoreCase(aT.tagName)) {
					o++;
					keys.lastElement().color =Integer.parseInt(aOs.elementAt(o).text);
				}
				if("bevel".equalsIgnoreCase(aT.tagName)) {
					o++;
					keys.lastElement().bevel = Double.parseDouble(aOs.elementAt(o).text);
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
		aSB.append("						<Chromakey>\n");
		for(Key aK : keys) {
			aSB.append("							<tolerance>" + aK.tolerance + "</tolerance>\n");
			aSB.append("							<color>" + aK.color + "</color>\n");
			aSB.append("							<bevel>" + aK.bevel + "</bevel>\n");
			aSB.append("							<time>" + aK.time + "</time>\n");
		}
		aSB.append("						</Chromakey>\n");
	}

	@Override
	void setDescr() {
		name = tde.gui.lngs.get("Processor.Chromakey");
		icon = "colorGreen.gif";
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
	
	public double getTolerance(int aKey) {
		return keys.elementAt(aKey).tolerance;
	}

	public void setTolerance(int aKey,double aTolerance) {
		keys.elementAt(aKey).tolerance = aTolerance;
	}

	public double getBevel(int aKey) {
		return keys.elementAt(aKey).bevel;
	}

	public void setBevel(int aKey,double aBevel) {
		keys.elementAt(aKey).bevel = aBevel;
	}

	public int getColor(int aKey) {
		return keys.elementAt(aKey).color;
	}

	public void setColor(int aKey,int aColor) {
		keys.elementAt(aKey).color = aColor;
	}

	public long getTime(int aKey) {
		return keys.elementAt(aKey).time;
	}
	
	public void setPicked(ChromakeyGUI aGui,double aPIX,double aPIY) {
		pickedX = aPIX;
		pickedY = aPIY;
		gui = aGui;
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
					aInter.tolerance = aKey.tolerance;
					aInter.color = aKey.color;
					aInter.bevel = aKey.bevel;
					aInter.time = aTime;
					return aInter;
				}
				Key aNext = keys.elementAt(k+1);
				Key aInter = new Key();
				double aFact = (aTime-aKey.time)/(double)(aNext.time-aKey.time);
				aInter.tolerance = aKey.tolerance*(1.0-aFact)+aNext.tolerance*aFact;
				aInter.color = aKey.color;
				aInter.bevel = aKey.bevel*(1.0-aFact)+aNext.bevel*aFact;
				aInter.time = aTime;
				return aInter;
			}
		}
		//??
		Key aKey = keys.firstElement();
		Key aInter = new Key();
		aInter.tolerance = aKey.tolerance;
		aInter.color = aKey.color;
		aInter.bevel = aKey.bevel;
		aInter.time = aTime;
		return aInter;
	}
	
	@Override
	public void process2Awt(Media aMedia,int aProcessingMode,long aTime) {
		try {
			if(pickedX >= 0 && pickedY >= 0
					&& pickedX < 1.0 && pickedY < 1.0) {
				BufferedImage aBIL = aMedia.getWorkLeft(aProcessingMode,aTime);
				Key aKey = keys.elementAt(getPrevKeyGT(aTime));
				aKey.color = aBIL.getRGB((int)(pickedX*aBIL.getWidth()), (int)(pickedY*aBIL.getHeight()));
				pickedX = pickedY = -1;
				tde.gui.display.syncExec(new Runnable() {
					@Override
					public void run() {
						RGB aRGB = new RGB(aKey.color>>16&0xFF,aKey.color>>8&0xFF,aKey.color&0xFF);
						gui.colorL.setBackground(new Color(aRGB));
						gui = null;
					}
				});
			}

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

	static final double[] rgb2hsv(int aRGB) {
		int aColor = aRGB;
		double r = ((aColor>>16) & 0xFF)/255.0;//Red
		double g = ((aColor>>8) & 0xFF)/255.0;//Green
		double b = (aColor & 0xFF)/255.0;//Blue
		return rgb2hsv(r, g, b);
	}
	static final double[] rgb2hsv(double r, double g, double b) {

	    //https://github.com/openjdk-mirror/jdk7u-jdk/blob/master/src/share/classes/java/awt/Color.java
		double hue, saturation, brightness;
		double cmax = (r > g) ? r : g;
        if (b > cmax) cmax = b;
        double cmin = (r < g) ? r : g;
        if (b < cmin) cmin = b;

        brightness = ((float) cmax) / 255.0f;
        if (cmax != 0)
            saturation = ((float) (cmax - cmin)) / ((float) cmax);
        else
            saturation = 0;
        if (saturation == 0)
            hue = 0;
        else {
            float redc = ((float) (cmax - r)) / ((float) (cmax - cmin));
            float greenc = ((float) (cmax - g)) / ((float) (cmax - cmin));
            float bluec = ((float) (cmax - b)) / ((float) (cmax - cmin));
            if (r == cmax)
                hue = bluec - greenc;
            else if (g == cmax)
                hue = 2.0f + redc - bluec;
            else
                hue = 4.0f + greenc - redc;
            hue = hue / 6.0f;
            if (hue < 0)
                hue = hue + 1.0f;
        }

//        System.out.println("H="+(hue*360)+"\n"
//        		+ "S="+(saturation*100)+"\n"
//        		+ "V="+(brightness*100)+"\n");
        return new double[] {hue,saturation,brightness};
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
		aFG.drawImage(aBI,0,0,null);

		double aBevel = aKey.bevel <= 0.0 ? 0.001/255.0:aKey.bevel;
		
		double[] aHsvC = rgb2hsv(aKey.color);
		
		if(aIsHdr) {
			float[] aFDB = ((DataBufferFloat)aFBI.getRaster().getDataBuffer()).getData();
			for(int x = 0;x<aWidth;x++) {
				for(int y = 0;y<aHeight;y++) {
					double aR = aFDB[y*aWidth*4+x*4];
					double aG = aFDB[y*aWidth*4+x*4+1];
					double aB = aFDB[y*aWidth*4+x*4+2];
					
					double[] aHsv = rgb2hsv(aR,aG,aB);
					
					double aHA = Math.abs(aHsv[0]-aHsvC[0]);
					aHA = (aHA - aKey.tolerance)/aBevel;
					aHA = aHA <= 0 ? 0.0 : (aHA >= 1.0 ? 1.0 : aHA);
					double aSA = Math.abs(aHsv[1]-aHsvC[1]);
					aSA = (aSA - 0.6)/aBevel;
					aSA = aSA <= 0 ? 0.0 : (aSA >= 1.0 ? 1.0 : aSA);
					double aVA = Math.abs(aHsv[2]-aHsvC[2]);
					aVA = (aVA - 1.0)/aBevel;
					aVA = aVA <= 0 ? 0.0 : (aVA >= 1.0 ? 1.0 : aVA);
					double aMA = Math.max(aHA,Math.max(aSA,aVA));

					aFDB[y*aWidth*4+x*4]=(float)aMA;//Red
					aFDB[y*aWidth*4+x*4+1]=(float)aMA;//Green
					aFDB[y*aWidth*4+x*4+2]=(float)aMA;//Blue
				}
			}
		}
		else {
			int[] aFDB = ((DataBufferInt)aFBI.getRaster().getDataBuffer()).getData();
			for(int x = 0;x<aWidth;x++) {
				for(int y = 0;y<aHeight;y++) {
					double[] aHsv = rgb2hsv(aFDB[x+y*aWidth]);
					
					double aHA = Math.abs(aHsv[0]-aHsvC[0]);
//					aHA = (aHA - aKey.tolerance)/aBevel;
//					aHA = aHA <= 0 ? 0.0 : (aHA >= 1.0 ? 1.0 : aHA);
					double aSA = Math.abs(aHsv[1]-aHsvC[1]);
//					aSA = (aSA - 0.5)/aBevel;
//					aSA = aSA <= 0 ? 0.0 : (aSA >= 1.0 ? 1.0 : aSA);
					double aVA = Math.abs(aHsv[2]-aHsvC[2]);
//					aVA = (aVA - 0.1)/aBevel;
//					aVA = aVA <= 0 ? 0.0 : (aVA >= 1.0 ? 1.0 : aVA);
					double aMA = Math.sqrt(aHA*aHA+aSA*aSA/3.0+aVA*aVA/10.0);
					aMA = (aMA - aKey.tolerance)/aBevel;
					aMA = aMA <= 0 ? 0.0 : (aMA >= 1.0 ? 1.0 : aMA);
					aFDB[x+y*aWidth]= ((int)(aMA*255))&0xFF
							| (((int)(aMA*255))&0xFF)<<8
							| (((int)(aMA*255))&0xFF)<<16;
				}
			}
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
						*((aFDB[p] & 0xFF)/255.0);//Blue
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
