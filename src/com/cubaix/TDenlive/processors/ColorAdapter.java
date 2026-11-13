package com.cubaix.TDenlive.processors;

import java.awt.image.BufferedImage;
import java.awt.image.DataBufferFloat;
import java.awt.image.DataBufferInt;
import java.io.BufferedWriter;
import java.util.Vector;

import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.graphics.Rectangle;

import com.cubaix.TDenlive.TDenlive;
import com.cubaix.TDenlive.medias.Clip;
import com.cubaix.TDenlive.medias.Media;
import com.cubaix.TDenlive.utils.ImageUtils;
import com.cubaix.TDenlive.xml.XmlObject;
import com.cubaix.TDenlive.xml.XmlTag;

public class ColorAdapter extends Processor {
	/** Parameters at a time (relative to Clip.getKeysOriginMS()) */
	class Key {
		double wR = 2.0
				,tRH = 0.6
				,rToGB = 0.5,gFromR = 0.25,bFromR = 0.25
				,tRL = 0.4
				,rFromGB = 0.5,gToR = 0.25,bToR = 0.25;
		String presetR = "Half";
		String presetC = "Half";
		long time = 0;

		Key copy(long aTime) {
			Key aK = new Key();
			aK.wR = wR;
			aK.tRH = tRH;
			aK.rToGB = rToGB;
			aK.gFromR = gFromR;
			aK.bFromR = bFromR;
			aK.tRL = tRL;
			aK.rFromGB = rFromGB;
			aK.gToR = gToR;
			aK.bToR = bToR;
			aK.presetR = presetR;
			aK.presetC = presetC;
			aK.time = aTime;
			return aK;
		}

		/** Interpolated between this key and aNext (presets: those of this key) */
		Key inter(Key aNext,double aF,long aTime) {
			Key aK = copy(aTime);
			aK.wR = wR*(1.0-aF)+aNext.wR*aF;
			aK.tRH = tRH*(1.0-aF)+aNext.tRH*aF;
			aK.rToGB = rToGB*(1.0-aF)+aNext.rToGB*aF;
			aK.gFromR = gFromR*(1.0-aF)+aNext.gFromR*aF;
			aK.bFromR = bFromR*(1.0-aF)+aNext.bFromR*aF;
			aK.tRL = tRL*(1.0-aF)+aNext.tRL*aF;
			aK.rFromGB = rFromGB*(1.0-aF)+aNext.rFromGB*aF;
			aK.gToR = gToR*(1.0-aF)+aNext.gToR*aF;
			aK.bToR = bToR*(1.0-aF)+aNext.bToR*aF;
			return aK;
		}
	}
	Vector<Key> keys = new Vector<Key>();

	public ColorAdapter(TDenlive aTDe,Media aTargetMedia) {
		super(aTDe,aTargetMedia);
		keys.add(new Key());
		setPresetR(0,"NoEffect");
		setPresetC(0,"NoEffect");
		isExpended = true;
	}
	
	@Override
	public String getClassName() {return "ColorAdapter";}

	@Override
	public int openProject(Vector<XmlObject> aOs, int o) throws Exception {
		keys.clear();
		for(;o < aOs.size();o++) {
			XmlObject aO = aOs.elementAt(o);
			if(aO instanceof XmlTag) {
				XmlTag aT = (XmlTag)aO;
				if("/ColorAdapter".equalsIgnoreCase(aT.tagName)) {
					break;
				}
				//presetR starts a key (old projects: a single key without time)
				if("presetR".equalsIgnoreCase(aT.tagName)) {
					o++;
					keys.add(new Key());
					keys.lastElement().presetR = aOs.elementAt(o).text;
					continue;
				}
				if(keys.isEmpty()) {
					keys.add(new Key());
				}
				Key aK = keys.lastElement();
				if("presetC".equalsIgnoreCase(aT.tagName)) {
					o++;
					aK.presetC = aOs.elementAt(o).text;
				}
				else if("wR".equalsIgnoreCase(aT.tagName)) {
					o++;
					aK.wR = Double.parseDouble(aOs.elementAt(o).text);
				}
				else if("tRH".equalsIgnoreCase(aT.tagName)) {
					o++;
					aK.tRH = Double.parseDouble(aOs.elementAt(o).text);
				}
				else if("rToGB".equalsIgnoreCase(aT.tagName)) {
					o++;
					aK.rToGB = Double.parseDouble(aOs.elementAt(o).text);
				}
				else if("gFromR".equalsIgnoreCase(aT.tagName)) {
					o++;
					aK.gFromR = Double.parseDouble(aOs.elementAt(o).text);
				}
				else if("bFromR".equalsIgnoreCase(aT.tagName)) {
					o++;
					aK.bFromR = Double.parseDouble(aOs.elementAt(o).text);
				}
				else if("tRL".equalsIgnoreCase(aT.tagName)) {
					o++;
					aK.tRL = Double.parseDouble(aOs.elementAt(o).text);
				}
				else if("rFromGB".equalsIgnoreCase(aT.tagName)) {
					o++;
					aK.rFromGB = Double.parseDouble(aOs.elementAt(o).text);
				}
				else if("gToR".equalsIgnoreCase(aT.tagName)) {
					o++;
					aK.gToR = Double.parseDouble(aOs.elementAt(o).text);
				}
				else if("bToR".equalsIgnoreCase(aT.tagName)) {
					o++;
					aK.bToR = Double.parseDouble(aOs.elementAt(o).text);
				}
				else if("time".equalsIgnoreCase(aT.tagName)) {
					o++;
					aK.time = Long.parseLong(aOs.elementAt(o).text);
				}
			}
		}
		if(keys.isEmpty()) {
			keys.add(new Key());
		}
		return o;
	}

	@Override
	public void saveProject(StringBuffer aSB) throws Exception {
		aSB.append("						<ColorAdapter>\n");
		for(Key aK : keys) {
			aSB.append("							<presetR>" + aK.presetR + "</presetR>\n");
			aSB.append("							<presetC>" + aK.presetC + "</presetC>\n");
			aSB.append("							<wR>" + aK.wR + "</wR>\n");
			aSB.append("							<tRH>" + aK.tRH + "</tRH>\n");
			aSB.append("							<rToGB>" + aK.rToGB + "</rToGB>\n");
			aSB.append("							<gFromR>" + aK.gFromR + "</gFromR>\n");
			aSB.append("							<bFromR>" + aK.bFromR + "</bFromR>\n");
			aSB.append("							<tRL>" + aK.tRL + "</tRL>\n");
			aSB.append("							<rFromGB>" + aK.rFromGB + "</rFromGB>\n");
			aSB.append("							<gToR>" + aK.gToR + "</gToR>\n");
			aSB.append("							<bToR>" + aK.bToR + "</bToR>\n");
			aSB.append("							<time>" + aK.time + "</time>\n");
		}
		aSB.append("						</ColorAdapter>\n");
	}

	@Override
	void setDescr() {
		name = tde.gui.lngs.get("Processor.AdaptColor");
		icon = "colourPalette2.gif";
	}

	@Override
	public int getNbKeys() {
		return keys.size();
	}

	@Override
	public int addKeyGT(long aTime){
		if(aTime < ((Clip)targetMedia).getStartTimeMS() || keyTime(aTime) <= 0 || aTime > ((Clip)targetMedia).getStartTimeMS() + ((Clip)targetMedia).getDurationMS()) {
			//Can't insert outside
			return -1;
		}
		Key aKey = getInterKeyGT(aTime);
		for(int k = keys.size() - 1;k >= 0;k--) {
			if(keys.elementAt(k).time <= aKey.time) {
				keys.add(k+1, aKey);
				return k+1;
			}
		}
		keys.add(0, aKey);
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
		long aT = keyTime(aTime);
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
		long aT = keyTime(aTime);
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
		long aTime = keyTime(aTimePos);
		for(int k = keys.size()-1;k >= 0;k--) {
			Key aKey = keys.elementAt(k);
			if(aKey.time <= aTime) {
				if(k == keys.size()-1) {
					return aKey.copy(aTime);
				}
				Key aNext = keys.elementAt(k+1);
				double aFact = (aTime-aKey.time)/(double)(aNext.time-aKey.time);
				return aKey.inter(aNext, aFact, aTime);
			}
		}
		return keys.firstElement().copy(aTime);
	}

	public double gettRH(int aKey) {
		return key(aKey).tRH;
	}

	public void settRH(int aKey,double tRH) {
		key(aKey).tRH = tRH;
	}

	public double gettRL(int aKey) {
		return key(aKey).tRL;
	}

	public void settRL(int aKey,double tRL) {
		key(aKey).tRL = tRL;
	}

	public String getPresetR(int aKey) {
		return key(aKey).presetR;
	}

	public String[] getPresetListR() {
		return new String[] {
				"NoEffect"
				,"Half"
				,"Brown"
				,"Orange"
				,"Yellow"
				,"Green"
				,"Purple"
				,"Magenta"
				,"Blue"
				,"White"
				,"LightGray"
				,"Gray"
				,"DarkGray"
		};
	}
	
	public String[] getPresetListC() {
		return new String[] {
				"NoEffect"
				,"Half"
				,"Blue"
				,"Purple"
				,"Magenta"
				,"Green"
				,"Yellow"
				,"Orange"
				,"White"
				,"LightGray"
				,"Gray"
				,"DarkGray"
		};
	}
	
	/** Sets the preset of the reds of the key aKey, with its parameters */
	public void setPresetR(int aKey,String aPreset) {
		Key aK = key(aKey);
		aK.presetR = aPreset;
		double[] aV = null;//wR, tRH, rToGB, gFromR, bFromR
		if("NoEffect".equals(aPreset)) aV = new double[] {2.0,1.0,0,0,0};
		else if("Half".equals(aPreset)) aV = new double[] {2.0,0.6,0.5,0.25,0.25};
		else if("Brown".equals(aPreset)) aV = new double[] {2.0,0.6,0.5,0.4,0.0};
		else if("Orange".equals(aPreset)) aV = new double[] {2.0,0.6,0.0,0.5,0.0};
		else if("Yellow".equals(aPreset)) aV = new double[] {2.0,0.6,0.0,1,0.0};
		else if("Green".equals(aPreset)) aV = new double[] {2.0,0.6,0.5,1,0.0};
		else if("Purple".equals(aPreset)) aV = new double[] {2.0,0.6,0.5,0,0.5};
		else if("Magenta".equals(aPreset)) aV = new double[] {2.0,0.6,0.0,0,1.0};
		else if("Blue".equals(aPreset)) aV = new double[] {2.0,0.6,0.5,0,1.0};
		else if("White".equals(aPreset)) aV = new double[] {2.0,0.5,0.0,1.0,1.0};
		else if("LightGray".equals(aPreset)) aV = new double[] {2.0,0.5,0.2,0.8,0.8};
		else if("Gray".equals(aPreset)) aV = new double[] {2.0,0.5,0.5,0.5,0.5};
		else if("DarkGray".equals(aPreset)) aV = new double[] {2.0,0.5,0.7,0.3,0.3};
		if(aV != null) {
			aK.wR = aV[0];
			aK.tRH = aV[1];
			aK.rToGB = aV[2];
			aK.gFromR = aV[3];
			aK.bFromR = aV[4];
		}
	}

	public String getPresetC(int aKey) {
		return key(aKey).presetC;
	}

	/** Sets the preset of the cyans of the key aKey, with its parameters */
	public void setPresetC(int aKey,String aPreset) {
		Key aK = key(aKey);
		aK.presetC = aPreset;
		double[] aV = null;//wR, tRL, rFromGB, gToR, bToR
		if("NoEffect".equals(aPreset)) aV = new double[] {2.0,0.0,0,0,0};
		else if("Half".equals(aPreset)) aV = new double[] {2.0,0.4,0.5,0.25,0.25};
		else if("Blue".equals(aPreset)) aV = new double[] {2.0,0.4,0.5,1.0,0.0};
		else if("Violet".equals(aPreset)) aV = new double[] {2.0,0.4,1.0,1.0,0.0};
		else if("Magenta".equals(aPreset)) aV = new double[] {2.0,0.4,1.0,1.0,0.5};
		else if("Green".equals(aPreset)) aV = new double[] {2.0,0.4,0.5,0.0,1.0};
		else if("Yellow".equals(aPreset)) aV = new double[] {2.0,0.4,1.0,0.0,1.0};
		else if("Orange".equals(aPreset)) aV = new double[] {2.0,0.4,1.0,0.5,1.0};
		else if("White".equals(aPreset)) aV = new double[] {2.0,0.5,1.0,0,0};
		else if("LightGray".equals(aPreset)) aV = new double[] {2.0,0.5,0.7,0.3,0.3};
		else if("Gray".equals(aPreset)) aV = new double[] {2.0,0.5,0.5,0.5,0.5};
		else if("DarkGray".equals(aPreset)) aV = new double[] {2.0,0.5,0.3,0.7,0.7};
		if(aV != null) {
			aK.wR = aV[0];
			aK.tRL = aV[1];
			aK.rFromGB = aV[2];
			aK.gToR = aV[3];
			aK.bToR = aV[4];
		}
	}

	@Override
	public Image process2Swt(Media aMedia, int aProcessingMode, Rectangle aTargetSize, long aTime) {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public void process2Awt(Media aMedia, int aProcessingMode, long aTime) {
		try {
			final Key aKey = getInterKeyGT(aTime);
			Thread aThL = new Thread(new Runnable() {
				@Override
				public void run() {
					BufferedImage aBIL = aMedia.getWorkLeft(aProcessingMode,aTime);
					BufferedImage aProcessedBIL = tde.config.processingHdr[aProcessingMode]?
							processAwtHdr(aBIL,aKey):processAwtImage(aBIL,aKey);
					aMedia.setWorkLeft(aProcessedBIL);
				}
			});
			aThL.start();
			Thread aThR = new Thread(new Runnable() {
				@Override
				public void run() {
					BufferedImage aBIR = aMedia.getWorkRight(aProcessingMode,aTime);
					BufferedImage aProcessedBIR = tde.config.processingHdr[aProcessingMode]?
							processAwtHdr(aBIR,aKey):processAwtImage(aBIR,aKey);
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
	
	BufferedImage processAwtImage(BufferedImage aBI,Key aK) {
		double wR = aK.wR,tRH = aK.tRH,rToGB = aK.rToGB,gFromR = aK.gFromR,bFromR = aK.bFromR
				,tRL = aK.tRL,rFromGB = aK.rFromGB,gToR = aK.gToR,bToR = aK.bToR;
		int aWidth = aBI.getWidth();
		int aHeight = aBI.getHeight();
		int[] aDB = ((DataBufferInt)aBI.getRaster().getDataBuffer()).getData();
		BufferedImage aFBI = ImageUtils.createImage(aWidth, aHeight);
		int[] aFDB = ((DataBufferInt)aFBI.getRaster().getDataBuffer()).getData();

		for(int x = 0;x<aWidth;x++) {
			for(int y = 0;y<aHeight;y++) {
				int aARGBL = aDB[y*aWidth+x];
				int aAlpha = (aARGBL>>24) & 0xFF;//Alpha
				int aR = (int)((aARGBL>>16) & 0xFF);//Red
				int aG = (int)((aARGBL>>8) & 0xFF);//Green
				int aB = (int)(aARGBL & 0xFF);//Blue

				double aLum = wR * aR + aG + aB;

			    double aNewR = aR,aNewG = aG,aNewB = aB;
			    if(wR * aR > tRH * aLum){
			    	double aDelta = wR * aR - tRH * aLum;
			    	aNewR = aR - rToGB * aDelta;
			    	aNewG = aG + gFromR * aDelta;
			    	aNewB = aB + bFromR * aDelta;
			    	aNewR = (aNewR < 0) ? 0 : ((aNewR > 255) ? 255 : aNewR);
			    	aNewG = (aNewG < 0) ? 0 : ((aNewG > 255) ? 255 : aNewG);
			    	aNewB = (aNewB < 0) ? 0 : ((aNewB > 255) ? 255 : aNewB);
			    }
			    else if(wR * aR < tRL * aLum){
			    	double aDelta = tRL * aLum - wR * aR;
			    	aNewR = aR + rFromGB * aDelta;
			    	aNewG = aG - gToR * aDelta;
			    	aNewB = aB - bToR * aDelta;
			    	aNewR = (aNewR < 0) ? 0 : ((aNewR > 255) ? 255 : aNewR);
			    	aNewG = (aNewG < 0) ? 0 : ((aNewG > 255) ? 255 : aNewG);
			    	aNewB = (aNewB < 0) ? 0 : ((aNewB > 255) ? 255 : aNewB);
			    }

				int aPix = ((int)aAlpha) << 24
						| ((int)aNewR) << 16
						| ((int)aNewG) << 8
						| (int)aNewB
						;
				aFDB[y*aWidth+x] = aPix;
			}
		}
		return aFBI;

	}

	BufferedImage processAwtHdr(BufferedImage aBI,Key aK) {
		double wR = aK.wR,tRH = aK.tRH,rToGB = aK.rToGB,gFromR = aK.gFromR,bFromR = aK.bFromR
				,tRL = aK.tRL,rFromGB = aK.rFromGB,gToR = aK.gToR,bToR = aK.bToR;
		int aWidth = aBI.getWidth();
		int aHeight = aBI.getHeight();
		float[] aDB = ((DataBufferFloat)aBI.getRaster().getDataBuffer()).getData();
		BufferedImage aFBI = ImageUtils.createHdr(aWidth, aHeight);
		float[] aFDB = ((DataBufferFloat)aFBI.getRaster().getDataBuffer()).getData();

		for(int x = 0;x<aWidth;x++) {
			for(int y = 0;y<aHeight;y++) {
				float aR = aDB[y*aWidth*4+x*4];//Red
				float aG = aDB[y*aWidth*4+x*4+1];//Green
				float aB = aDB[y*aWidth*4+x*4+2];//Blue
				float aAlpha = aDB[y*aWidth*4+x*4+3];//Alpha

				double aLum = wR * aR + aG + aB;

			    double aNewR = aR,aNewG = aG,aNewB = aB;
			    if(wR * aR > tRH * aLum){
			    	double aDelta = wR * aR - tRH * aLum;
			    	aNewR = aR - rToGB * aDelta;
			    	aNewG = aG + gFromR * aDelta;
			    	aNewB = aB + bFromR * aDelta;
			    	aNewR = (aNewR < 0) ? 0 : ((aNewR > 255) ? 255 : aNewR);
			    	aNewG = (aNewG < 0) ? 0 : ((aNewG > 255) ? 255 : aNewG);
			    	aNewB = (aNewB < 0) ? 0 : ((aNewB > 255) ? 255 : aNewB);
			    }
			    else if(wR * aR < tRL * aLum){
			    	double aDelta = tRL * aLum - wR * aR;
			    	aNewR = aR + rFromGB * aDelta;
			    	aNewG = aG - gToR * aDelta;
			    	aNewB = aB - bToR * aDelta;
			    	aNewR = (aNewR < 0) ? 0 : ((aNewR > 255) ? 255 : aNewR);
			    	aNewG = (aNewG < 0) ? 0 : ((aNewG > 255) ? 255 : aNewG);
			    	aNewB = (aNewB < 0) ? 0 : ((aNewB > 255) ? 255 : aNewB);
			    }

			    aFDB[y*aWidth*4+x*4]=(float)aNewR;//Red
			    aFDB[y*aWidth*4+x*4+1]=(float)aNewG;//Green
			    aFDB[y*aWidth*4+x*4+2]=(float)aNewB;//Blue
			    aFDB[y*aWidth*4+x*4+3]=aAlpha;//Alpha
			}
		}
		return aFBI;

	}

}
