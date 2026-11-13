package com.cubaix.TDenlive.processors;

import java.awt.geom.AffineTransform;
import java.io.BufferedWriter;
import java.util.Vector;

import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.graphics.Rectangle;

import com.cubaix.TDenlive.TDenlive;
import com.cubaix.TDenlive.medias.Media;
import com.cubaix.TDenlive.xml.XmlObject;

public abstract class Processor {
	public Media targetMedia = null;
	public String name = null;
	public String icon = null;
	public boolean isDeactivable = true;
	public boolean isActive = true;
	public boolean isExpended = false;
	public boolean isWarning = false;
	public boolean isUndeletable = false;
	public boolean isUnmovable = false;

	public TDenlive tde = null;

	public Processor(TDenlive aTDe,Media aTargetMedia) {
		tde = aTDe;
		targetMedia = aTargetMedia;
		setDescr();
	}

	/**
	 * Called when the processor is removed from its clip (to release what it gave the clip)
	 */
	public void removed() {}
	
	public abstract String getClassName();
	public abstract int openProject(Vector<XmlObject> aOs,int o) throws Exception;
	public abstract void saveProject(StringBuffer aSB) throws Exception;
	abstract void setDescr();
	public int getNbKeys() {return -1;}
	public int addKeyGT(long aTime){return -1;}
	public void deleteKey(int aKey) {}
	public int getPrevKeyGT(long aTime){return -1;}
	public int getNextKeyGT(long aTime){return -1;}
	public long getTime(int aKey) {return -1;}

	/**
	 * Time of the keys at aTimePos (time line). Below a speed processor, the keys are in media time:
	 * they stay on the same images whatever the speed (see Clip.keyTimeAt())
	 */
	public long keyTime(long aTimePos) {
		return targetMedia instanceof com.cubaix.TDenlive.medias.Clip
				? ((com.cubaix.TDenlive.medias.Clip)targetMedia).keyTimeAt(aTimePos) : aTimePos;
	}

	/** Time line position of the key time aKeyTime (inverse of keyTime()) */
	public long keyTimePos(long aKeyTime) {
		return targetMedia instanceof com.cubaix.TDenlive.medias.Clip
				? ((com.cubaix.TDenlive.medias.Clip)targetMedia).keyTimePos(aKeyTime) : aKeyTime;
	}

	/**
	 * True if the key aKey is at aTimePos (time line). With a speed, several time line times can give
	 * the same key time (slow motion) and a key time lands on one of them (fast motion): both count
	 */
	public boolean isKeyAt(int aKey,long aTimePos) {
		if(aKey < 0 || aKey >= getNbKeys()) {
			return false;
		}
		long aT = getTime(aKey);
		return aT == keyTime(aTimePos) || keyTimePos(aT) == aTimePos;
	}
	/** Key index of the values interpolated at the time cursor, for the display only (see setDisplayKeyGT()) */
	public static final int DISPLAY_KEY = Integer.MAX_VALUE;
	/**
	 * Selected keys of this processor ("Select" button of the K row), by their time (relative to
	 * Clip.getKeysOriginMS()), in the order of selection: the last one is the main selected key,
	 * whose values the widgets show. A parameter change gives the same value to all of them.
	 * None: automatic mode, a parameter change adds a key at the time cursor, the widgets show
	 * the interpolated values. Saved by the clip (<ProcSelKeys> after the processor, if any)
	 */
	Vector<Long> selectedKeyTimes = new Vector<Long>();

	/** True without selected key: a change adds a key at the time cursor */
	public boolean isAutoKey() {
		return getMainSelectedKey() < 0;
	}

	/** Index of the key at aT (time of the keys), -1 if none */
	int keyIndexOfTime(long aT) {
		int aNb = getNbKeys();
		for(int k = 0;k < aNb;k++) {
			if(getTime(k) == aT) {
				return k;
			}
		}
		return -1;
	}

	/** Indexes of the selected keys (in the order of selection), the deleted ones are forgotten */
	public Vector<Integer> getSelectedKeys() {
		Vector<Integer> aKeys = new Vector<Integer>();
		synchronized (selectedKeyTimes) {
			for(int i = 0;i < selectedKeyTimes.size();i++) {
				int aK = keyIndexOfTime(selectedKeyTimes.elementAt(i));
				if(aK < 0) {
					selectedKeyTimes.remove(i--);
					continue;
				}
				aKeys.add(aK);
			}
		}
		return aKeys;
	}

	/** Main selected key (the last selected), -1 if none */
	public int getMainSelectedKey() {
		Vector<Integer> aKeys = getSelectedKeys();
		return aKeys.isEmpty() ? -1 : aKeys.lastElement();
	}

	public boolean isKeySelected(int aKey) {
		return aKey >= 0 && aKey < getNbKeys() && selectedKeyTimes.contains(getTime(aKey));
	}

	/**
	 * Adds to the selection the key at aTimePos (time of the montage), else the nearest one on its
	 * left (the first key if none), as the main selected key.
	 * @return its index, -1 if the processor has no key
	 */
	public int selectKeyGT(long aTimePos) {
		int aK = getPrevKeyGT(aTimePos);
		if(aK < 0) {
			if(getNbKeys() <= 0) {
				return -1;
			}
			aK = 0;
		}
		synchronized (selectedKeyTimes) {
			Long aT = getTime(aK);
			selectedKeyTimes.remove(aT);
			selectedKeyTimes.add(aT);
		}
		keySelectionChanged();
		return aK;
	}

	/** Back to the automatic mode */
	public void unselectKeys() {
		synchronized (selectedKeyTimes) {
			selectedKeyTimes.clear();
		}
		keySelectionChanged();
	}

	/** Selection for the project file: times separated by ';', "" if none */
	public String selectedKeysToString() {
		StringBuffer aSB = new StringBuffer();
		synchronized (selectedKeyTimes) {
			for(Long aT : selectedKeyTimes) {
				if(aSB.length() > 0) {
					aSB.append(';');
				}
				aSB.append(aT);
			}
		}
		return aSB.toString();
	}

	public void selectedKeysFromString(String aS) {
		synchronized (selectedKeyTimes) {
			selectedKeyTimes.clear();
			for(String aT : aS.split(";")) {
				try {
					if(!aT.isBlank()) {
						selectedKeyTimes.add(Long.parseLong(aT.trim()));
					}
				}
				catch(Throwable t) {
					t.printStackTrace(System.err);
				}
			}
		}
		keySelectionChanged();
	}

	/** Called when the selected keys change */
	protected void keySelectionChanged() {}

	/**
	 * Computes the display key: the values interpolated at aTime, read with the index DISPLAY_KEY
	 * by the getters (never by the setters). The keys of the processor are not changed.
	 * @return false if the processor has no display key
	 */
	public boolean setDisplayKeyGT(long aTime) {return false;}
	public abstract Image process2Swt(Media aMedia,int aProcessingMode,Rectangle aTargetSize,long aTime);
	public abstract void process2Awt(Media aMedia,int aProcessingMode,long aTime);
	/**
	 * Sound processing, for the processors of the audio clips.
	 * aBuf holds aNbFrames interleaved stereo samples (L R, -1..1) of the clip,
	 * the first one at aTimeMS on the time line. Default: no effect.
	 */
	public void processAudio(Media aMedia,float[] aBuf,int aNbFrames,long aTimeMS,double aFrameMS) {}
	/**
	 * True for the processors working on the sound (only for the audio clips)
	 */
	public boolean isAudio() {return false;}
	/**
	 * Geometry of the image made by this processor (to show and edit the shapes of the clip
	 * where they are displayed): transform from the pixels of its input image (aInW x aInH)
	 * to the pixels of its output image, whose size is put in aOutSize, for the eye aEye
	 * (-1 left, 1 right) at aTime. By default the image is not moved (colors, masks,
	 * deformations that are not affine).
	 */
	public AffineTransform getGeometry(int aEye,long aTime,int aProcessingMode,int aInW,int aInH,int[] aOutSize) {
		aOutSize[0] = aInW;
		aOutSize[1] = aInH;
		return new AffineTransform();
	}

}
