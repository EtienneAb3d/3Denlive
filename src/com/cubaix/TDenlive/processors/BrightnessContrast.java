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

public class BrightnessContrast extends Processor {
	/** Parameters at a time (relative to Clip.getKeysOriginMS()) */
	class Key {
		int applyTo = 2;//0=left, 1=right, 2=both
		int color = 3;//0=red, 1=green, 2=blue, 3=all, 4=cyan, 5=magenta, 6=yellow
		double brightness = 0.0;
		double contrast = 0.0;
		double gamma = 0.0;
		double th = 0.0;
		long time = 0;

		Key copy(long aTime) {
			Key aK = new Key();
			aK.applyTo = applyTo;
			aK.color = color;
			aK.brightness = brightness;
			aK.contrast = contrast;
			aK.gamma = gamma;
			aK.th = th;
			aK.time = aTime;
			return aK;
		}

		/** Interpolated between this key and aNext (the choices: those of this key) */
		Key inter(Key aNext,double aF,long aTime) {
			Key aK = copy(aTime);
			aK.brightness = brightness*(1.0-aF)+aNext.brightness*aF;
			aK.contrast = contrast*(1.0-aF)+aNext.contrast*aF;
			aK.gamma = gamma*(1.0-aF)+aNext.gamma*aF;
			aK.th = th*(1.0-aF)+aNext.th*aF;
			return aK;
		}
	}
	Vector<Key> keys = new Vector<Key>();

	public BrightnessContrast(TDenlive aTDe, Media aTargetMedia) {
		super(aTDe, aTargetMedia);
		isExpended = true;
		keys.add(new Key());
	}

	@Override
	public String getClassName() {return "BrightnessContrast";}

	@Override
	public int openProject(Vector<XmlObject> aOs, int o) throws Exception {
		keys.clear();
		for(;o < aOs.size();o++) {
			XmlObject aO = aOs.elementAt(o);
			if(aO instanceof XmlTag) {
				XmlTag aT = (XmlTag)aO;
				if("/BrightnessContrast".equalsIgnoreCase(aT.tagName)) {
					break;
				}
				//applyTo starts a key (old projects: a single key without time)
				if("applyTo".equalsIgnoreCase(aT.tagName) || keys.isEmpty()) {
					keys.add(new Key());
				}
				Key aK = keys.lastElement();
				if("applyTo".equalsIgnoreCase(aT.tagName)) {
					o++;
					aK.applyTo = Integer.parseInt(aOs.elementAt(o).text);
				}
				else if("color".equalsIgnoreCase(aT.tagName)) {
					o++;
					aK.color = Integer.parseInt(aOs.elementAt(o).text);
				}
				else if("brightness".equalsIgnoreCase(aT.tagName)) {
					o++;
					aK.brightness = Double.parseDouble(aOs.elementAt(o).text);
				}
				else if("contrast".equalsIgnoreCase(aT.tagName)) {
					o++;
					aK.contrast = Double.parseDouble(aOs.elementAt(o).text);
				}
				else if("gamma".equalsIgnoreCase(aT.tagName)) {
					o++;
					aK.gamma = Double.parseDouble(aOs.elementAt(o).text);
				}
				else if("th".equalsIgnoreCase(aT.tagName)
						//Bug in the name
						|| "sh".equalsIgnoreCase(aT.tagName)) {
					o++;
					aK.th = Double.parseDouble(aOs.elementAt(o).text);
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
		aSB.append("						<BrightnessContrast>\n");
		for(Key aK : keys) {
			aSB.append("							<applyTo>" + aK.applyTo + "</applyTo>\n");
			aSB.append("							<color>" + aK.color + "</color>\n");
			aSB.append("							<brightness>" + aK.brightness + "</brightness>\n");
			aSB.append("							<contrast>" + aK.contrast + "</contrast>\n");
			aSB.append("							<gamma>" + aK.gamma + "</gamma>\n");
			aSB.append("							<th>" + aK.th + "</th>\n");
			aSB.append("							<time>" + aK.time + "</time>\n");
		}
		aSB.append("						</BrightnessContrast>\n");
	}

	@Override
	void setDescr() {
		name = tde.gui.lngs.get("Processor.BrightnessContrast");
		icon = "starsColor.gif";
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

	public int getApplyTo(int aKey) {
		return key(aKey).applyTo;
	}

	public void setApplyTo(int aKey,int aApplyTo) {
		key(aKey).applyTo = aApplyTo;
	}

	public int getColor(int aKey) {
		return key(aKey).color;
	}

	public void setColor(int aKey,int aColor) {
		key(aKey).color = aColor;
	}

	public double getBrightness(int aKey) {
		return key(aKey).brightness;
	}

	public void setBrightness(int aKey,double aBrightness) {
		key(aKey).brightness = aBrightness;
	}

	public double getContrast(int aKey) {
		return key(aKey).contrast;
	}

	public void setContrast(int aKey,double aContrast) {
		key(aKey).contrast = aContrast;
	}

	public double getGamma(int aKey) {
		return key(aKey).gamma;
	}

	public void setGamma(int aKey,double aGamma) {
		key(aKey).gamma = aGamma;
	}

	public double getTh(int aKey) {
		return key(aKey).th;
	}

	public void setTh(int aKey,double aTh) {
		key(aKey).th = aTh;
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
			final int applyTo = aKey.applyTo;
			Thread aThL = new Thread(new Runnable() {
				@Override
				public void run() {
					if(applyTo == 0 || applyTo == 2) {
						BufferedImage aBIL = aMedia.getWorkLeft(aProcessingMode,aTime);
						BufferedImage aProcessedBIL = tde.config.processingHdr[aProcessingMode]?
								processAwtHdr(aBIL,true,aKey):processAwtImage(aBIL,aProcessingMode,true,aKey);
								aMedia.setWorkLeft(aProcessedBIL);
					}
				}
			});
			aThL.start();
			Thread aThR = new Thread(new Runnable() {
				@Override
				public void run() {
					if(applyTo == 1 || applyTo == 2) {
						BufferedImage aBIR = aMedia.getWorkRight(aProcessingMode,aTime);
						BufferedImage aProcessedBIR = tde.config.processingHdr[aProcessingMode]?
								processAwtHdr(aBIR,false,aKey):processAwtImage(aBIR,aProcessingMode,false,aKey);
								aMedia.setWorkRight(aProcessedBIR);
					}
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

	static final double atanh(double x) {
		return 0.5*Math.log((1+x)/(1-x));
	}
	
	BufferedImage processAwtImage(BufferedImage aBI,int aProcessingMode,boolean aIsL,Key aK) {
		int color = aK.color;
		double brightness = aK.brightness,contrast = aK.contrast,gamma = aK.gamma,th = aK.th;

		int aWidth = aBI.getWidth();
		int aHeight = aBI.getHeight();
		int[] aDB = ((DataBufferInt)aBI.getRaster().getDataBuffer()).getData();
		BufferedImage aFBI = ImageUtils.createImage(aWidth, aHeight);
		int[] aFDB = ((DataBufferInt)aFBI.getRaster().getDataBuffer()).getData();

	    int aNbVal = 10000;

	    double[] aTh = new double[aNbVal+1];
	    for(int f = 0;f <= aNbVal;f++) {
	    	if(th > 0) {
	    		aTh[f] = (Math.tanh(10*th*(f/(double)aNbVal - 0.5))-Math.tanh(10*th*(-0.5)))
	    				/(Math.tanh(10*th*0.5)-Math.tanh(10*th*(-0.5)));
	    	}
	    	else if(th < 0) {
	    		aTh[f] = (Math.tanh(-10*th*(f/(double)aNbVal - 0.5))-Math.tanh(-10*th*(-0.5)))
	    				/(Math.tanh(-10*th*0.5)-Math.tanh(-10*th*(-0.5)));
	    	}
	    	else {
	    		aTh[f] = f/(double)aNbVal;
	    	}
	    }
	    if(th < 0) {
		    double[] aAth = new double[aNbVal+1];
		    aAth[aNbVal] = 1.0;
		    for(int f = 0;f < aNbVal;f++) {
		    	int aY1 = (int)(aNbVal*aTh[f]);
		    	int aY2 = (int)(aNbVal*aTh[f+1]);
		    	aY1 = aY1 <= 0 ? 0 : (aY1 >= aNbVal ? aNbVal : aY1);
		    	aY2 = aY2 <= 0 ? 0 : (aY2 >= aNbVal ? aNbVal : aY2);
		    	for(int y = aY1;y <= aY2;y++) {
		    		aAth[y] = (f+(y <= (aY2-aY1)/2.0 ? 0 : 1))/(double)aNbVal;
		    	}
		    }
		    aTh = aAth;
	    }
	    double aContrast = (contrast >= 0 ? (1.0+contrast):1.0/(1.0-contrast));
	    double aGamma = (gamma >= 0 ?1.0/(1.0+5*gamma) : (1.0-5*gamma));
	    double[] aF = new double[aNbVal+1];
	    for(int f = 0;f <= aNbVal;f++) {
		    aF[f] = aContrast*(f/(double)aNbVal-0.5)+0.5+brightness;
		    aF[f] = aF[f] > 1.0 ? 1.0:(aF[f] < 0.0 ? 0.0 : aF[f]);
		    aF[f] = Math.pow(aF[f], aGamma);
		    aF[f] = aF[f] > 1.0 ? 1.0:(aF[f] < 0.0 ? 0.0 : aF[f]);
		    aF[f] = aTh[(int)(aNbVal*aF[f])];
		    aF[f] = aF[f] > 1.0 ? 1.0:(aF[f] < 0.0 ? 0.0 : aF[f]);
	    }
	    
		for(int x = 0;x<aWidth;x++) {
			for(int y = 0;y<aHeight;y++) {
				int aARGBL = aDB[y*aWidth+x];
				double aAlpha = ((aARGBL>>24) & 0xFF)/255.0;//Alpha
				double aR = ((aARGBL>>16) & 0xFF)/255.0;//Red
				double aG = ((aARGBL>>8) & 0xFF)/255.0;//Green
				double aB = (aARGBL & 0xFF)/255.0;//Blue

			    double aNewR = aR,aNewG = aG,aNewB = aB;

			    int aX;
			    double aW;

			    if(color == 0 || color == 3 || color == 5 || color == 6) {
			    	aX = (int)Math.floor(aR*aNbVal);
			    	aW = aR*aNbVal - aX;
			    	aNewR = aX >= aNbVal ? aF[aNbVal]:aF[aX]*(1.0-aW)+aF[aX+1]*aW; 
			    }

			    if(color == 1 || color == 3 || color == 4 || color == 6) {
			    	aX = (int)Math.floor(aG*aNbVal);
			    	aW = aG*aNbVal - aX;
			    	aNewG = aX >= aNbVal ? aF[aNbVal]:aF[aX]*(1.0-aW)+aF[aX+1]*aW; 
			    }

			    if(color == 2 || color == 3 || color == 4 || color == 5) {
			    	aX = (int)Math.floor(aB*aNbVal);
			    	aW = aB*aNbVal - aX;
			    	aNewB = aX >= aNbVal ? aF[aNbVal]:aF[aX]*(1.0-aW)+aF[aX+1]*aW; 
			    }
			    
				int aPix = ((int)(aAlpha*255.0)) << 24
						| ((int)(aNewR*255.0)) << 16
						| ((int)(aNewG*255.0)) << 8
						| (int)(aNewB*255.0)
						;
				aFDB[y*aWidth+x] = aPix;
			}
		}
		
		return aFBI;
	}

	BufferedImage processAwtHdr(BufferedImage aBI,boolean aIsL,Key aK) {
		int color = aK.color;
		double brightness = aK.brightness,contrast = aK.contrast,gamma = aK.gamma,th = aK.th;

		int aWidth = aBI.getWidth();
		int aHeight = aBI.getHeight();
		float[] aDB = ((DataBufferFloat)aBI.getRaster().getDataBuffer()).getData();
		BufferedImage aFBI = ImageUtils.createHdr(aWidth, aHeight);
		float[] aFDB = ((DataBufferFloat)aFBI.getRaster().getDataBuffer()).getData();

	    int aNbVal = 10000;

	    double[] aTh = new double[aNbVal+1];
	    for(int f = 0;f <= aNbVal;f++) {
	    	if(th > 0) {
	    		aTh[f] = (Math.tanh(10*th*(f/(double)aNbVal - 0.5))-Math.tanh(10*th*(-0.5)))
	    				/(Math.tanh(10*th*0.5)-Math.tanh(10*th*(-0.5)));
	    	}
	    	else if(th < 0) {
	    		aTh[f] = (Math.tanh(-10*th*(f/(double)aNbVal - 0.5))-Math.tanh(-10*th*(-0.5)))
	    				/(Math.tanh(-10*th*0.5)-Math.tanh(-10*th*(-0.5)));
	    	}
	    	else {
	    		aTh[f] = f/(double)aNbVal;
	    	}
	    }
	    if(th < 0) {
		    double[] aAth = new double[aNbVal+1];
		    aAth[aNbVal] = 1.0;
		    for(int f = 0;f < aNbVal;f++) {
		    	int aY1 = (int)(aNbVal*aTh[f]);
		    	int aY2 = (int)(aNbVal*aTh[f+1]);
		    	for(int y = aY1;y <= aY2;y++) {
		    		aAth[y] = (f+(y <= (aY2-aY1)/2.0 ? 0 : 1))/(double)aNbVal;
		    	}
		    }
		    aTh = aAth;
	    }
	    double aContrast = (contrast >= 0 ? (1.0+contrast):1.0/(1.0-contrast));
	    double aGamma = (gamma >= 0 ?1.0/(1.0+5*gamma) : (1.0-5*gamma));
	    double[] aF = new double[aNbVal+1];
	    for(int f = 0;f <= aNbVal;f++) {
		    aF[f] = aContrast*(f/(double)aNbVal-0.5)+0.5+brightness;
		    aF[f] = aF[f] > 1.0 ? 1.0:(aF[f] < 0.0 ? 0.0 : aF[f]);
		    aF[f] = Math.pow(aF[f], aGamma);
		    aF[f] = aF[f] > 1.0 ? 1.0:(aF[f] < 0.0 ? 0.0 : aF[f]);
		    aF[f] = aTh[(int)(aNbVal*aF[f])];
		    aF[f] = aF[f] > 1.0 ? 1.0:(aF[f] < 0.0 ? 0.0 : aF[f]);
	    }
	    
		for(int x = 0;x<aWidth;x++) {
			for(int y = 0;y<aHeight;y++) {
				float aR = aDB[y*aWidth*4+x*4];//Red
				float aG = aDB[y*aWidth*4+x*4+1];//Green
				float aB = aDB[y*aWidth*4+x*4+2];//Blue
				float aAlpha = aDB[y*aWidth*4+x*4+3];//Alpha

			    double aNewR = aR,aNewG = aG,aNewB = aB;
			    
			    int aX;
			    double aW;

			    if(color == 0 || color == 3 || color == 5 || color == 6) {
			    	aX = (int)Math.floor(aR*aNbVal);
			    	aW = aR*aNbVal - aX;
			    	aNewR = aX >= aNbVal ? aF[aNbVal]:aF[aX]*(1.0-aW)+aF[aX+1]*aW; 
			    }

			    if(color == 1 || color == 3 || color == 4 || color == 6) {
			    	aX = (int)Math.floor(aG*aNbVal);
			    	aW = aG*aNbVal - aX;
			    	aNewG = aX >= aNbVal ? aF[aNbVal]:aF[aX]*(1.0-aW)+aF[aX+1]*aW; 
			    }

			    if(color == 2 || color == 3 || color == 4 || color == 5) {
			    	aX = (int)Math.floor(aB*aNbVal);
			    	aW = aB*aNbVal - aX;
			    	aNewB = aX >= aNbVal ? aF[aNbVal]:aF[aX]*(1.0-aW)+aF[aX+1]*aW; 
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
