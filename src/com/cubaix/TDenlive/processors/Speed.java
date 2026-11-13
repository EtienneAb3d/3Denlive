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
 * Play speed of a video or sound clip, with key frames (1 = normal, 0.5 = slow motion, 2 = fast).
 * The speed is linear between the keys; the time in the media is its integral from the start
 * of the clip (see Clip.mediaTimeD()), so a speed ramp gives a smooth acceleration.
 * The sound follows (pitch changed, as a tape played faster or slower).
 */
public class Speed extends Processor {
	public static final double SPEED_MIN = 0.1;
	public static final double SPEED_MAX = 8.0;

	class Key{
		double speed = 1.0;
		long time = 0;
	}
	Vector<Key> keys = new Vector<Key>();
	//Sound: same pitch at any speed (time stretching, see audio.TimeStretch), else the pitch follows the speed
	boolean keepPitch = false;

	public Speed(TDenlive aTDe,Media aTargetMedia) {
		super(aTDe,aTargetMedia);
		isExpended = true;
		//First after the TimeController (Clip.placeSpeedProcessor()): all the others are below it
		isUnmovable = true;
		keys.add(new Key());
	}

	@Override
	public String getClassName() {return "Speed";}

	@Override
	public int openProject(Vector<XmlObject> aOs, int o) throws Exception {
		keys.clear();
		//The parser lowers the tag names: <Speed> and its <speed> values have the same name.
		//Skip the opening tag, and consume the closing tag of each value: the only "/speed"
		//seen by the loop is then the end of the processor
		for(o++;o < aOs.size();o++) {
			XmlObject aO = aOs.elementAt(o);
			if(aO instanceof XmlTag) {
				XmlTag aT = (XmlTag)aO;
				if("/speed".equals(aT.tagName)) {
					break;
				}
				if("speed".equals(aT.tagName)) {
					o++;
					keys.add(new Key());
					keys.lastElement().speed = clamp(Double.parseDouble(aOs.elementAt(o).text));
					if(o+1 < aOs.size() && aOs.elementAt(o+1) instanceof XmlTag
							&& "/speed".equals(((XmlTag)aOs.elementAt(o+1)).tagName)) {
						o++;
					}
				}
				if("keepPitch".equalsIgnoreCase(aT.tagName)) {
					o++;
					keepPitch = "true".equals(aOs.elementAt(o).text.trim());
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
		aSB.append("						<Speed>\n");
		aSB.append("							<keepPitch>" + keepPitch + "</keepPitch>\n");
		for(Key aK : keys) {
			aSB.append("							<speed>" + aK.speed + "</speed>\n");
			aSB.append("							<time>" + aK.time + "</time>\n");
		}
		aSB.append("						</Speed>\n");
	}

	@Override
	void setDescr() {
		name = tde.gui.lngs.get("Processor.Speed");
		icon = "run.gif";
	}

	public boolean isKeepPitch() {
		return keepPitch;
	}

	public void setKeepPitch(boolean aKeep) {
		keepPitch = aKeep;
	}

	public static double clamp(double aSpeed) {
		return aSpeed < SPEED_MIN ? SPEED_MIN : (aSpeed > SPEED_MAX ? SPEED_MAX : aSpeed);
	}

	Clip clip() {
		return (Clip)targetMedia;
	}

	public int getNbKeys() {
		return keys.size();
	}

	public int addKeyGT(long aTime){
		if(aTime < clip().getStartTimeMS() || keyTime(aTime) <= 0 || aTime > clip().getStartTimeMS() + clip().getDurationMS()) {
			//Can't insert outside
			return -1;
		}
		Key aKey = getInterKeyGT(aTime);
		synchronized (keys) {
			for(int k = keys.size() - 1;k >= 0;k--) {
				if(keys.elementAt(k).time <= aKey.time) {
					keys.add(k+1, aKey);
					return k+1;
				}
			}
			keys.add(aKey);
		}
		return 0;
	}

	public void deleteKey(int aKey) {
		//Can't delete first
		if(aKey <= 0 || aKey >= keys.size()) {
			return;
		}
		synchronized (keys) {
			keys.remove(aKey);
		}
	}

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

	public long getTime(int aKey) {
		return key(aKey).time;
	}

	public double getSpeed(int aKey) {
		return key(aKey).speed;
	}

	public void setSpeed(int aKey,double aSpeed) {
		key(aKey).speed = clamp(aSpeed);
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
		Key aInter = new Key();
		aInter.time = keyTime(aTimePos);
		aInter.speed = speedAtKeyTime(aInter.time);
		return aInter;
	}

	/**
	 * Speed at aT (key time: media time, as the keys of the other processors, see Clip.keyTimeAt()):
	 * linear between the keys,
	 * the first / last key's before / after them
	 */
	double speedAtKeyTime(double aT) {
		synchronized (keys) {
			for(int k = keys.size()-1;k >= 0;k--) {
				Key aKey = keys.elementAt(k);
				if(aKey.time <= aT) {
					if(k == keys.size()-1) {
						return aKey.speed;
					}
					Key aNext = keys.elementAt(k+1);
					double aFact = aNext.time == aKey.time ? 1.0 : (aT-aKey.time)/(aNext.time-aKey.time);
					return aKey.speed*(1.0-aFact)+aNext.speed*aFact;
				}
			}
			return keys.firstElement().speed;
		}
	}

	/**
	 * Time line duration (ms) to play the media from the key time aA to aB (signed): integral of
	 * 1/speed, exact on each linear piece between the keys (log)
	 */
	public double timeSpan(double aA,double aB) {
		if(aB < aA) {
			return -timeSpan(aB, aA);
		}
		double aSum = 0;
		synchronized (keys) {
			double aX = aA;
			for(Key aK : keys) {
				if(aK.time <= aX) {
					continue;
				}
				if(aK.time >= aB) {
					break;
				}
				aSum += pieceSpan(aX, aK.time);
				aX = aK.time;
			}
			aSum += pieceSpan(aX, aB);
		}
		return aSum;
	}

	/** timeSpan() between aX < aY with no key strictly between them (speed linear) */
	double pieceSpan(double aX,double aY) {
		double aSX = speedAtKeyTime(aX);
		double aSY = speedAtKeyTime(aY);
		if(Math.abs(aSY-aSX) < 1e-9) {
			return (aY-aX)/aSX;
		}
		return (aY-aX)*Math.log(aSY/aSX)/(aSY-aSX);
	}

	/**
	 * Key time (media) reached after playing aDt ms of time line (signed) from the key time aA
	 * (inverse of timeSpan(): exponential on each linear piece)
	 */
	public double advance(double aA,double aDt) {
		synchronized (keys) {
			double aX = aA;
			double aRem = aDt;
			while(true) {
				//Bound of the piece in the direction of the move
				double aY = aRem >= 0 ? Double.POSITIVE_INFINITY : Double.NEGATIVE_INFINITY;
				for(Key aK : keys) {
					if(aRem >= 0 && aK.time > aX && aK.time < aY) {
						aY = aK.time;
					}
					if(aRem < 0 && aK.time < aX && aK.time > aY) {
						aY = aK.time;
					}
				}
				double aSX = speedAtKeyTime(aX);
				if(!Double.isInfinite(aY)) {
					double aSpan = pieceSpan(Math.min(aX, aY), Math.max(aX, aY));
					if(aSpan < Math.abs(aRem)) {
						aRem -= Math.signum(aRem)*aSpan;
						aX = aY;
						continue;
					}
					double aK = (speedAtKeyTime(aY)-aSX)/(aY-aX);
					if(Math.abs(aK) > 1e-12) {
						return aX+aSX*(Math.exp(aK*aRem)-1.0)/aK;
					}
				}
				//Constant speed (before the first key, after the last one)
				return aX+aSX*aRem;
			}
		}
	}

	@Override
	public Image process2Swt(Media aMedia, int aProcessingMode, Rectangle aTargetSize, long aTime) {
		return null;
	}

	@Override
	public void process2Awt(Media aMedia, int aProcessingMode, long aTime) {
		//Acts on the time in the media (Clip.mediaTimeD()), not on the images
	}
}
