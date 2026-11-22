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
 * Processor whose keys are a list of numeric parameters described by params() (sliders and
 * choices): keys, interpolation, display key and persistence are done here, the panel is the
 * generic GUI/processors/ParamProcessorGUI. Saved as
 * <ClassName><time>t</time><tag1>v</tag1>...</ClassName>, a <time> starting each key (a
 * missing tag keeps its default value). The values of the choices are not interpolated (those
 * of the key before)
 */
public abstract class ParamProcessor extends Processor {
	/** A parameter: a slider (value = slider / div) or a choice (value = index of the choice) */
	public static class Param {
		/** XML tag (must not be the name of the processor in another case), key of its language entries */
		public final String tag;
		public final double min, def, max;
		/** Slider value = value x div */
		public final int div;
		/** Choices: language keys <ClassName>.<tag>_<choice>, or a text shown as it is if it starts with '=' */
		public final String[] choices;
		/** Language key of the line above the slider (in the section of the processor), null: none */
		public String header;
		/** Full language key of the letter of the slider, null: <ClassName>.<tag>Letter */
		public String letterKey = null;
		/** Tip key (Tip.<key>), null: <ClassName>_<tag> */
		public String tipKey = null;

		Param(String aTag,double aMin,double aDef,double aMax,int aDiv,String[] aChoices) {
			tag = aTag;
			min = aMin;
			def = aDef;
			max = aMax;
			div = aDiv;
			choices = aChoices;
			header = aChoices == null ? aTag : null;
		}

		public static Param range(String aTag,double aMin,double aDef,double aMax,int aDiv) {
			return new Param(aTag, aMin, aDef, aMax, aDiv, null);
		}

		public static Param choice(String aTag,int aDef,String... aChoices) {
			return new Param(aTag, 0, aDef, aChoices.length-1, 1, aChoices);
		}

		public boolean isChoice() {
			return choices != null;
		}

		public Param header(String aKey) {
			header = aKey;
			return this;
		}

		public Param letter(String aFullKey) {
			letterKey = aFullKey;
			return this;
		}

		public Param tip(String aKey) {
			tipKey = aKey;
			return this;
		}

		public double clamp(double aV) {
			return Math.max(min, Math.min(max, aV));
		}
	}

	/** Values of the parameters at a time (relative to Clip.getKeysOriginMS()) */
	public static class Key {
		public double[] v;
		public long time = 0;

		Key(double[] aV,long aTime) {
			v = aV;
			time = aTime;
		}
	}
	protected final Vector<Key> keys = new Vector<Key>();

	public ParamProcessor(TDenlive aTDe,Media aTargetMedia) {
		super(aTDe, aTargetMedia);
		isExpended = true;
		keys.add(new Key(defaults(), 0));
	}

	/** The parameters (same array at each call) */
	public abstract Param[] params();

	double[] defaults() {
		Param[] aPs = params();
		double[] aV = new double[aPs.length];
		for(int p = 0;p < aPs.length;p++) {
			aV[p] = aPs[p].def;
		}
		return aV;
	}

	@Override
	void setDescr() {
		name = tde.gui.lngs.get("Processor."+getClassName());
	}

	@Override
	public int openProject(Vector<XmlObject> aOs, int o) throws Exception {
		Param[] aPs = params();
		synchronized (keys) {
			keys.clear();
			for(;o < aOs.size();o++) {
				XmlObject aO = aOs.elementAt(o);
				if(!(aO instanceof XmlTag)) {
					continue;
				}
				XmlTag aT = (XmlTag)aO;
				if(("/"+getClassName()).equalsIgnoreCase(aT.tagName)) {
					break;
				}
				if("time".equalsIgnoreCase(aT.tagName)) {
					o++;
					keys.add(new Key(defaults(), Long.parseLong(aOs.elementAt(o).text.trim())));
					continue;
				}
				for(int p = 0;p < aPs.length;p++) {
					if(aPs[p].tag.equalsIgnoreCase(aT.tagName)) {
						o++;
						if(keys.isEmpty()) {
							keys.add(new Key(defaults(), 0));
						}
						keys.lastElement().v[p] = aPs[p].clamp(Double.parseDouble(aOs.elementAt(o).text.trim()));
						break;
					}
				}
			}
			if(keys.isEmpty()) {
				keys.add(new Key(defaults(), 0));
			}
		}
		return o;
	}

	@Override
	public void saveProject(StringBuffer aSB) throws Exception {
		Param[] aPs = params();
		aSB.append("						<"+getClassName()+">\n");
		synchronized (keys) {
			for(Key aK : keys) {
				aSB.append("							<time>" + aK.time + "</time>\n");
				for(int p = 0;p < aPs.length;p++) {
					aSB.append("							<"+aPs[p].tag+">" + (aPs[p].isChoice() ? ""+(int)aK.v[p] : ""+aK.v[p]) + "</"+aPs[p].tag+">\n");
				}
			}
		}
		aSB.append("						</"+getClassName()+">\n");
	}

	@Override
	public int getNbKeys() {
		return keys.size();
	}

	@Override
	public int addKeyGT(long aTime){
		Clip aClip = (Clip)targetMedia;
		if(aTime < aClip.getStartTimeMS() || keyTime(aTime) <= 0 || aTime > aClip.getStartTimeMS() + aClip.getDurationMS()) {
			//Can't insert outside
			return -1;
		}
		Key aKey = new Key(valuesAt(aTime), keyTime(aTime));
		synchronized (keys) {
			for(int k = keys.size() - 1;k >= 0;k--) {
				if(keys.elementAt(k).time <= aKey.time) {
					keys.add(k+1, aKey);
					return k+1;
				}
			}
			keys.add(0, aKey);
		}
		return 0;
	}

	@Override
	public void deleteKey(int aKey) {
		//Can't delete first
		synchronized (keys) {
			if(aKey <= 0 || aKey >= keys.size()) {
				return;
			}
			keys.remove(aKey);
		}
	}

	@Override
	public int getPrevKeyGT(long aTime){
		long aT = keyTime(aTime);
		synchronized (keys) {
			for(int k = keys.size() - 1;k >= 0;k--) {
				if(keys.elementAt(k).time <= aT) {
					return k;
				}
			}
		}
		//No key before
		return -1;
	}

	@Override
	public int getNextKeyGT(long aTime){
		long aT = keyTime(aTime);
		synchronized (keys) {
			for(int k = 0;k < keys.size();k++) {
				if(keys.elementAt(k).time > aT) {
					return k;
				}
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
			displayKey = new Key(valuesAt(aTime), keyTime(aTime));
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

	public double get(int aKey,int aParam) {
		return key(aKey).v[aParam];
	}

	public void set(int aKey,int aParam,double aValue) {
		key(aKey).v[aParam] = params()[aParam].clamp(aValue);
	}

	/** Values of the parameters at aTimePos (time line), interpolated between the keys */
	public double[] valuesAt(long aTimePos) {
		long aTime = keyTime(aTimePos);
		Param[] aPs = params();
		synchronized (keys) {
			for(int k = keys.size()-1;k >= 0;k--) {
				Key aKey = keys.elementAt(k);
				if(aKey.time <= aTime) {
					if(k == keys.size()-1) {
						return aKey.v.clone();
					}
					Key aNext = keys.elementAt(k+1);
					double aF = aNext.time == aKey.time ? 1.0 : (aTime-aKey.time)/(double)(aNext.time-aKey.time);
					double[] aV = aKey.v.clone();
					for(int p = 0;p < aV.length;p++) {
						if(!aPs[p].isChoice()) {
							aV[p] = aKey.v[p]*(1.0-aF)+aNext.v[p]*aF;
						}
					}
					return aV;
				}
			}
			return keys.firstElement().v.clone();
		}
	}

	@Override
	public Image process2Swt(Media aMedia, int aProcessingMode, Rectangle aTargetSize, long aTime) {
		return null;
	}
}
