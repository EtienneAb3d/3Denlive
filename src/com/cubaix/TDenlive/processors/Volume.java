package com.cubaix.TDenlive.processors;

import java.util.Vector;

import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.graphics.Rectangle;

import com.cubaix.TDenlive.TDenlive;
import com.cubaix.TDenlive.medias.Clip;
import com.cubaix.TDenlive.medias.Media;
import com.cubaix.TDenlive.xml.XmlObject;
import com.cubaix.TDenlive.xml.XmlTag;

/**
 * Sound volume and left/right balance of an audio clip, with key frames
 */
public class Volume extends Processor {
	public static final double GAIN_MAX = 4.0;//+12 dB

	class Key{
		double gain = 1.0;//0..GAIN_MAX, linear
		double pan = 0.0;//-1 (left) .. 1 (right)
		long time = 0;
	}
	Vector<Key> keys = new Vector<Key>();

	public Volume(TDenlive aTDe,Media aTargetMedia) {
		super(aTDe,aTargetMedia);
		isExpended = true;
		keys.add(new Key());
	}

	@Override
	public String getClassName() {return "Volume";}

	@Override
	public boolean isAudio() {return true;}

	@Override
	public int openProject(Vector<XmlObject> aOs, int o) throws Exception {
		keys.clear();
		for(;o < aOs.size();o++) {
			XmlObject aO = aOs.elementAt(o);
			if(aO instanceof XmlTag) {
				XmlTag aT = (XmlTag)aO;
				if("/Volume".equalsIgnoreCase(aT.tagName)) {
					break;
				}
				if("gain".equalsIgnoreCase(aT.tagName)) {
					o++;
					keys.add(new Key());
					keys.lastElement().gain = Double.parseDouble(aOs.elementAt(o).text);
				}
				if("pan".equalsIgnoreCase(aT.tagName)) {
					o++;
					keys.lastElement().pan = Double.parseDouble(aOs.elementAt(o).text);
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
		aSB.append("						<Volume>\n");
		for(Key aK : keys) {
			aSB.append("							<gain>" + aK.gain + "</gain>\n");
			aSB.append("							<pan>" + aK.pan + "</pan>\n");
			aSB.append("							<time>" + aK.time + "</time>\n");
		}
		aSB.append("						</Volume>\n");
	}

	@Override
	void setDescr() {
		name = tde.gui.lngs.get("Processor.Volume");
		icon = "sound.gif";
	}

	public int getNbKeys() {
		return keys.size();
	}

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

	public void deleteKey(int aKey) {
		//Can't delete first
		if(aKey <= 0 || aKey >= keys.size()) {
			return;
		}
		keys.remove(aKey);
	}

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

	public long getTime(int aKey) {
		return key(aKey).time;
	}

	public double getGain(int aKey) {
		return key(aKey).gain;
	}

	public void setGain(int aKey,double aGain) {
		key(aKey).gain = aGain;
	}

	public double getPan(int aKey) {
		return key(aKey).pan;
	}

	public void setPan(int aKey,double aPan) {
		key(aKey).pan = aPan;
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
		Key aInter = new Key();
		aInter.time = aTime;
		synchronized (keys) {
			for(int k = keys.size()-1;k >= 0;k--) {
				Key aKey = keys.elementAt(k);
				if(aKey.time <= aTime) {
					if(k == keys.size()-1) {
						aInter.gain = aKey.gain;
						aInter.pan = aKey.pan;
						return aInter;
					}
					Key aNext = keys.elementAt(k+1);
					double aFact = aNext.time == aKey.time ? 1.0 : (aTime-aKey.time)/(double)(aNext.time-aKey.time);
					aInter.gain = aKey.gain*(1.0-aFact)+aNext.gain*aFact;
					aInter.pan = aKey.pan*(1.0-aFact)+aNext.pan*aFact;
					return aInter;
				}
			}
			Key aKey = keys.firstElement();
			aInter.gain = aKey.gain;
			aInter.pan = aKey.pan;
		}
		return aInter;
	}

	@Override
	public void processAudio(Media aMedia, float[] aBuf, int aNbFrames, long aTimeMS, double aFrameMS) {
		if(aNbFrames <= 0) {
			return;
		}
		//Linear ramp between the values at both ends of the buffer (no zipper noise)
		Key aStart = getInterKeyGT(aTimeMS);
		Key aEnd = getInterKeyGT(aTimeMS+(long)(aNbFrames*aFrameMS));
		for(int f = 0;f < aNbFrames;f++) {
			double aFact = f/(double)aNbFrames;
			double aGain = aStart.gain*(1.0-aFact)+aEnd.gain*aFact;
			double aPan = aStart.pan*(1.0-aFact)+aEnd.pan*aFact;
			double aGainL = aGain*Math.min(1.0, 1.0-aPan);
			double aGainR = aGain*Math.min(1.0, 1.0+aPan);
			aBuf[2*f] *= aGainL;
			aBuf[2*f+1] *= aGainR;
		}
	}

	@Override
	public Image process2Swt(Media aMedia, int aProcessingMode, Rectangle aTargetSize, long aTime) {
		return null;
	}

	@Override
	public void process2Awt(Media aMedia, int aProcessingMode, long aTime) {
		//No effect on images
	}
}
