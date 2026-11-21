package com.cubaix.TDenlive.processors;

import java.util.Vector;

import com.cubaix.TDenlive.TDenlive;
import com.cubaix.TDenlive.medias.Clip;
import com.cubaix.TDenlive.medias.Media;
import com.cubaix.TDenlive.model3d.GaussianScene;
import com.cubaix.TDenlive.model3d.StereoCam;
import com.cubaix.TDenlive.xml.XmlObject;
import com.cubaix.TDenlive.xml.XmlTag;

/**
 * Processor moving a pair of stereo cameras (StereoCam) in a 3D scene of Gaussians, with keys (time of the keys:
 * media time, as the others): SceneCamera (clips of a SceneMedia) and Scene3D (2D => 3D scene of the images of a
 * clip). Interpolation: linear (positions, angles, spacing), logarithmic for the screen plane. A key per camera
 * saved as <Key><time>t</time><cam>x;y;z;yaw;pitch;base;screen</cam></Key> inside the tag of the processor.
 * GUI: GUI.processors.SceneCameraGUI.
 */
public abstract class SceneCamKeys extends Processor {
	class Key {
		long time = 0;
		StereoCam cam = new StereoCam();

		Key copy() {
			Key aK = new Key();
			aK.time = time;
			aK.cam = cam.copy();
			return aK;
		}
	}
	Vector<Key> keys = new Vector<Key>();
	//Cameras interpolated at the time cursor, for the display (index DISPLAY_KEY, UI thread only)
	Key displayKey = null;

	public SceneCamKeys(TDenlive aTDe, Media aTargetMedia) {
		super(aTDe, aTargetMedia);
		isExpended = true;
		keys.add(new Key());
	}

	//Pixels not covered filled by the inpainting model (model3d.SmartFill), <smartFill>
	public boolean smartFill = true;
	//Drawn as the mesh of the visible Gaussians (interpolation), else as the Gaussians, <interp>
	public boolean interp = true;
	//The estimated depths in gray instead of the colors, <depthView>
	public boolean depthView = false;
	//Thresholding (model3d.SmartFill): the triangles of the mesh whose |cos| with the direction of the depth is below
	//cosCut are holes <cosCut>; shown (cutView, "Thresholding") or not <cutView>
	public double cosCut = com.cubaix.TDenlive.model3d.SmartFill.DEFAULT_COS;
	public boolean cutView = false;
	//Model filling the holes (model3d.SmartFill.Model id) <fillModel>
	public String fillModel = com.cubaix.TDenlive.model3d.SmartFill.DEFAULT_MODEL.id;
	//Smart filling: the left eye filled first then carried over to the right one ("L/R carry-over") <pairCarry>, the
	//filling of the previous images carried over ("F/F+1 carry-over") <prevCarry>
	public boolean pairCarry = true;
	public boolean prevCarry = true;
	//Gaussian blur of the depth of the scene (slider S), in pixels of an image of GaussianScene.DVD_LINES lines
	//(proportional to the resolution), <depthBlur>; applied when the scene is read (smooth())
	public double depthBlur = GaussianScene.DEFAULT_BLUR;

	/** aScene with its depth smoothed by depthBlur (null: null) */
	GaussianScene smooth(GaussianScene aScene) {
		return aScene == null ? null : aScene.smoothedDvd(depthBlur);
	}

	public Clip getClip() {
		return (Clip)targetMedia;
	}

	/**
	 * Scene seen at aTimePos (time line), null if none (not computed, not readable)
	 */
	public abstract GaussianScene sceneAt(long aTimePos);

	/** Cameras of the first key when the processor is made */
	void setFirstCam(StereoCam aCam) {
		synchronized (keys) {
			keys.elementAt(0).cam = aCam.copy();
		}
	}

	@Override
	public int openProject(Vector<XmlObject> aOs, int o) throws Exception {
		Vector<Key> aKeys = new Vector<Key>();
		String aEnd = "/"+getClassName();
		for(;o < aOs.size();o++) {
			XmlObject aO = aOs.elementAt(o);
			if(aO instanceof XmlTag) {
				XmlTag aT = (XmlTag)aO;
				if(aEnd.equalsIgnoreCase(aT.tagName)) {
					break;
				}
				if("Key".equalsIgnoreCase(aT.tagName)) {
					aKeys.add(new Key());
				}
				else if("time".equalsIgnoreCase(aT.tagName) && aKeys.size() > 0) {
					aKeys.lastElement().time = Long.parseLong(aOs.elementAt(++o).text.trim());
				}
				else if("cam".equalsIgnoreCase(aT.tagName) && aKeys.size() > 0) {
					aKeys.lastElement().cam = StereoCam.load(aOs.elementAt(++o).text);
				}
				else if("depthView".equalsIgnoreCase(aT.tagName)) {
					depthView = "true".equalsIgnoreCase(aOs.elementAt(++o).text.trim());
				}
				else if("interp".equalsIgnoreCase(aT.tagName)) {
					interp = !"false".equalsIgnoreCase(aOs.elementAt(++o).text.trim());
				}
				else if("cutView".equalsIgnoreCase(aT.tagName)) {
					cutView = "true".equalsIgnoreCase(aOs.elementAt(++o).text.trim());
				}
				else if("cosCut".equalsIgnoreCase(aT.tagName)) {
					cosCut = Double.parseDouble(aOs.elementAt(++o).text.trim());
				}
				else if("fillModel".equalsIgnoreCase(aT.tagName)) {
					fillModel = com.cubaix.TDenlive.model3d.SmartFill.forProcessor(aOs.elementAt(++o).text.trim()).id;
				}
				else if("depthBlur".equalsIgnoreCase(aT.tagName)) {
					depthBlur = Math.max(0, Double.parseDouble(aOs.elementAt(++o).text.trim()));
				}
				else if("pairCarry".equalsIgnoreCase(aT.tagName)) {
					pairCarry = !"false".equalsIgnoreCase(aOs.elementAt(++o).text.trim());
				}
				else if("prevCarry".equalsIgnoreCase(aT.tagName)) {
					prevCarry = !"false".equalsIgnoreCase(aOs.elementAt(++o).text.trim());
				}
				else if("smartFill".equalsIgnoreCase(aT.tagName)) {
					smartFill = !"false".equalsIgnoreCase(aOs.elementAt(++o).text.trim());
				}
				else {
					o = openProjectSub(aOs, o, aT);
				}
			}
		}
		if(aKeys.size() <= 0) {
			aKeys.add(new Key());
		}
		synchronized (keys) {
			keys.clear();
			keys.addAll(aKeys);
		}
		return o;
	}

	/** Other tags of the subclass (o: the tag aT), returns the new index */
	int openProjectSub(Vector<XmlObject> aOs, int o, XmlTag aT) {
		return o;
	}

	@Override
	public void saveProject(StringBuffer aSB) throws Exception {
		aSB.append("						<"+getClassName()+">\n");
		aSB.append("							<interp>" + interp + "</interp>\n");
		aSB.append("							<depthView>" + depthView + "</depthView>\n");
		aSB.append("							<smartFill>" + smartFill + "</smartFill>\n");
		aSB.append("							<cosCut>" + cosCut + "</cosCut>\n");
		aSB.append("							<cutView>" + cutView + "</cutView>\n");
		aSB.append("							<fillModel>" + fillModel + "</fillModel>\n");
		aSB.append("							<pairCarry>" + pairCarry + "</pairCarry>\n");
		aSB.append("							<depthBlur>" + depthBlur + "</depthBlur>\n");
		aSB.append("							<prevCarry>" + prevCarry + "</prevCarry>\n");
		saveProjectSub(aSB);
		synchronized (keys) {
			for(Key aK : keys) {
				aSB.append("							<Key>\n");
				aSB.append("								<time>" + aK.time + "</time>\n");
				aSB.append("								<cam>" + aK.cam.save() + "</cam>\n");
				aSB.append("							</Key>\n");
			}
		}
		aSB.append("						</"+getClassName()+">\n");
	}

	void saveProjectSub(StringBuffer aSB) {
	}

	//== Keys ==

	@Override
	public int getNbKeys() {
		return keys.size();
	}

	@Override
	public long getTime(int aKey) {
		return key(aKey).time;
	}

	Key key(int aKey) {
		return aKey == DISPLAY_KEY && displayKey != null ? displayKey : keys.elementAt(aKey);
	}

	int keyAt(long aT) {
		for(int k = 0;k < keys.size();k++) {
			if(keys.elementAt(k).time == aT) {
				return k;
			}
		}
		return -1;
	}

	@Override
	public int addKeyGT(long aTime) {
		if(aTime < getClip().getStartTimeMS() || keyTime(aTime) <= 0 || aTime > getClip().getStartTimeMS() + getClip().getDurationMS()) {
			//Can't insert outside
			return -1;
		}
		synchronized (keys) {
			long aT = keyTime(aTime);
			int aExact = keyAt(aT);
			if(aExact >= 0) {
				return aExact;
			}
			Key aKey = interpolate(aT);
			aKey.time = aT;
			for(int k = keys.size() - 1;k >= 0;k--) {
				if(keys.elementAt(k).time <= aT) {
					keys.add(k+1, aKey);
					return k+1;
				}
			}
			keys.add(0, aKey);
			return 0;
		}
	}

	@Override
	public void deleteKey(int aKey) {
		//Can't delete the first
		if(aKey <= 0 || aKey >= keys.size()) {
			return;
		}
		synchronized (keys) {
			keys.remove(aKey);
		}
	}

	@Override
	public int getPrevKeyGT(long aTime) {
		long aT = keyTime(aTime);
		for(int k = keys.size() - 1;k >= 0;k--) {
			if(keys.elementAt(k).time <= aT) {
				return k;
			}
		}
		return -1;
	}

	@Override
	public int getNextKeyGT(long aTime) {
		long aT = keyTime(aTime);
		for(int k = 0;k < keys.size();k++) {
			if(keys.elementAt(k).time > aT) {
				return k;
			}
		}
		return -1;
	}

	@Override
	public boolean setDisplayKeyGT(long aTime) {
		try {
			displayKey = interpolate(keyTime(aTime));
		}
		catch(Throwable t) {
			displayKey = null;
		}
		return displayKey != null;
	}

	/**
	 * Cameras at aT (time of the keys), interpolated
	 */
	Key interpolate(long aT) {
		synchronized (keys) {
			for(int k = keys.size()-1;k >= 0;k--) {
				Key aKey = keys.elementAt(k);
				if(aKey.time <= aT || k == 0) {
					if(k == keys.size()-1 || aKey.time >= aT) {
						Key aCopy = aKey.copy();
						aCopy.time = aT;
						return aCopy;
					}
					Key aNext = keys.elementAt(k+1);
					double aF = (aT-aKey.time)/(double)(aNext.time-aKey.time);
					Key aInter = new Key();
					aInter.time = aT;
					aInter.cam = StereoCam.lerp(aKey.cam, aNext.cam, aF);
					return aInter;
				}
			}
		}
		return new Key();
	}

	/**
	 * Cameras of a key (or of the display key), a copy
	 */
	public StereoCam camForKey(int aKey) {
		synchronized (keys) {
			if(aKey == DISPLAY_KEY) {
				return displayKey != null ? displayKey.cam.copy() : keys.elementAt(0).cam.copy();
			}
			return aKey >= 0 && aKey < keys.size() ? keys.elementAt(aKey).cam.copy() : new StereoCam();
		}
	}

	/**
	 * Cameras at aTimePos (time of the montage)
	 */
	public StereoCam camAt(long aTimePos) {
		return interpolate(keyTime(aTimePos)).cam;
	}

	public void setCam(int aKey,StereoCam aCam) {
		if(aKey < 0 || aKey >= keys.size()) {
			return;
		}
		synchronized (keys) {
			keys.elementAt(aKey).cam = aCam.copy();
		}
	}

	/**
	 * Both eyes of the scene at aTimePos into the work images of aMedia, at their size
	 * @return false if there is no scene there (the images are left as they are)
	 */
	//"Fill this view" (SceneCameraGUI): the next image of the monitor computes SDXL (not saved)
	public volatile boolean fillNow = false;

	boolean renderInto(Media aMedia,int aProcessingMode,long aTime,int aW,int aH) {
		GaussianScene aS = sceneAt(aTime);
		if(aS == null) {
			return false;
		}
		StereoCam aCam = camAt(aTime);
		//SDXL (about 40 s per view) only for the final rendering or after "Fill this view": the monitor takes its
		//images already made, else the light model
		boolean aSdxl = aProcessingMode == com.cubaix.TDenlive.TDConfig.PROCESSING_MODE_RENDER || fillNow;
		//Next image / step of the player (SmartFill.carryNext()): filled again from the image shown before
		boolean aCarry = aProcessingMode != com.cubaix.TDenlive.TDConfig.PROCESSING_MODE_RENDER && com.cubaix.TDenlive.model3d.SmartFill.isCarryTime(aTime);
		java.awt.image.BufferedImage aL = com.cubaix.TDenlive.model3d.SmartFill.render(aS, aCam, -1, aW, aH, depthView, interp, cutView, smartFill, cosCut, fillModel, tde.tdeDir, aSdxl, pairCarry, prevCarry, aCarry);
		java.awt.image.BufferedImage aR = com.cubaix.TDenlive.model3d.SmartFill.render(aS, aCam, 1, aW, aH, depthView, interp, cutView, smartFill, cosCut, fillModel, tde.tdeDir, aSdxl, pairCarry, prevCarry, aCarry);
		if(aProcessingMode != com.cubaix.TDenlive.TDConfig.PROCESSING_MODE_RENDER) {
			fillNow = false;
		}
		if(tde.config.processingHdr[aProcessingMode]) {
			boolean aFast = tde.config.fastMode && aProcessingMode != com.cubaix.TDenlive.TDConfig.PROCESSING_MODE_RENDER;
			aL = com.cubaix.TDenlive.utils.ImageUtils.toRes(aL, aH, true, aFast, false);
			aR = com.cubaix.TDenlive.utils.ImageUtils.toRes(aR, aH, true, aFast, false);
		}
		aMedia.setWorkLeft(aL);
		aMedia.setWorkRight(aR);
		return true;
	}

	@Override
	public org.eclipse.swt.graphics.Image process2Swt(Media aMedia, int aProcessingMode, org.eclipse.swt.graphics.Rectangle aTargetSize, long aTime) {
		return null;
	}
}
