package com.cubaix.TDenlive.processors;

import java.awt.image.BufferedImage;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Vector;

import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.graphics.Rectangle;

import com.cubaix.TDenlive.TDConfig;
import com.cubaix.TDenlive.TDenlive;
import com.cubaix.TDenlive.medias.Clip;
import com.cubaix.TDenlive.medias.Media;
import com.cubaix.TDenlive.medias.Model3DMedia;
import com.cubaix.TDenlive.model3d.Math3D;
import com.cubaix.TDenlive.model3d.Model3D;
import com.cubaix.TDenlive.model3d.Object3D;
import com.cubaix.TDenlive.model3d.Renderer3D;
import com.cubaix.TDenlive.utils.ImageUtils;
import com.cubaix.TDenlive.xml.XmlObject;
import com.cubaix.TDenlive.xml.XmlTag;

/**
 * Mandatory processor of the clips of a Model3DMedia: moves, turns and scales its objects and groups,
 * with keys. A key holds a pose per node, relative to the model of the media (so that a change of the
 * model in the modeler still applies): position + d, rotation q * R (quaternion, in the space of the
 * group), scale * k. A node without pose in a key: no change. Interpolation: linear, rotations by slerp.
 * A key may also hold the camera (Cam: free, or on the X / Y / Z axis of its target); none = the camera of the model.
 * And the lamp (model3d.Lamp: tied to the camera, or placed in the scene); none = the lamp of the model.
 */
public class Objects3D extends Processor {
	//Camera modes: free (orbit), or looking along an axis (camera on +X, on top, on +Z of the target)
	public static final int CAM_FREE = 0;
	public static final int CAM_X = 1;
	public static final int CAM_Y = 2;
	public static final int CAM_Z = 3;

	/**
	 * Camera of a key: target, angles and distance of the orbit camera (the field of view stays that of the model)
	 */
	public static class Cam {
		public int mode = CAM_FREE;
		public double[] target = {0, 0.5, 0};
		public double yaw = 35, pitch = 25, distance = 4;

		public Cam copy() {
			Cam aC = new Cam();
			aC.mode = mode;
			aC.target = target.clone();
			aC.yaw = yaw;
			aC.pitch = pitch;
			aC.distance = distance;
			return aC;
		}

		public static Cam of(Model3D aM) {
			Cam aC = new Cam();
			aC.target = aM.getTarget();
			aC.yaw = aM.yaw;
			aC.pitch = aM.pitch;
			aC.distance = aM.distance;
			return aC;
		}

		public void applyTo(Model3D aM) {
			aM.setTarget(target.clone());
			aM.yaw = yaw;
			aM.pitch = pitch;
			aM.distance = distance;
		}

		/**
		 * The direction of its mode (an axis: the angles of that axis; free from an axis: the standard view)
		 */
		public void setMode(int aMode) {
			int aOld = mode;
			mode = aMode;
			switch(aMode) {
			case CAM_FREE:
				//Back from an axis: the standard view between the three axes
				if(aOld != CAM_FREE) {
					yaw = 35;
					pitch = 25;
				}
				break;
			case CAM_X: yaw = 90; pitch = 0; break;
			case CAM_Y: yaw = 0; pitch = Model3D.MAX_PITCH; break;
			case CAM_Z: yaw = 0; pitch = 0; break;
			}
		}

		boolean same(Cam aC) {
			return Math.abs(target[0]-aC.target[0]) < 1e-9 && Math.abs(target[1]-aC.target[1]) < 1e-9
					&& Math.abs(target[2]-aC.target[2]) < 1e-9 && Math.abs(distance-aC.distance) < 1e-9
					&& Math.abs(pitch-aC.pitch) < 1e-9 && Math.abs(((yaw-aC.yaw)%360+540)%360-180) < 1e-9;
		}

		/**
		 * Linear, the yaw by the shortest way; the mode of aA
		 */
		static Cam lerp(Cam aA, Cam aB, double aF) {
			Cam aC = new Cam();
			aC.mode = aA.mode;
			for(int c = 0;c < 3;c++) {
				aC.target[c] = aA.target[c]*(1-aF)+aB.target[c]*aF;
			}
			double aDYaw = ((aB.yaw-aA.yaw)%360+540)%360-180;
			aC.yaw = aA.yaw+aDYaw*aF;
			aC.pitch = aA.pitch*(1-aF)+aB.pitch*aF;
			//Logarithmic: the same zoom speed whatever the distance
			aC.distance = aA.distance > 0 && aB.distance > 0 ? Math.exp(Math.log(aA.distance)*(1-aF)+Math.log(aB.distance)*aF)
					: aA.distance*(1-aF)+aB.distance*aF;
			return aC;
		}

		String save() {
			return mode+";"+target[0]+";"+target[1]+";"+target[2]+";"+yaw+";"+pitch+";"+distance;
		}

		static Cam load(String aText) {
			String[] aVs = aText.trim().split(";");
			Cam aC = new Cam();
			aC.mode = Integer.parseInt(aVs[0]);
			for(int c = 0;c < 3;c++) {
				aC.target[c] = Double.parseDouble(aVs[1+c]);
			}
			aC.yaw = Double.parseDouble(aVs[4]);
			aC.pitch = Double.parseDouble(aVs[5]);
			aC.distance = Double.parseDouble(aVs[6]);
			return aC;
		}
	}

	public static class Pose {
		public double[] d = {0, 0, 0};
		public double[] q = {1, 0, 0, 0};
		public double[] k = {1, 1, 1};

		public Pose copy() {
			Pose aP = new Pose();
			aP.d = d.clone();
			aP.q = q.clone();
			aP.k = k.clone();
			return aP;
		}

		public boolean isIdentity() {
			return Math.abs(d[0]) < 1e-9 && Math.abs(d[1]) < 1e-9 && Math.abs(d[2]) < 1e-9
					&& Math.abs(Math.abs(q[0])-1) < 1e-9
					&& Math.abs(k[0]-1) < 1e-9 && Math.abs(k[1]-1) < 1e-9 && Math.abs(k[2]-1) < 1e-9;
		}

		static Pose lerp(Pose aA, Pose aB, double aF) {
			Pose aP = new Pose();
			for(int c = 0;c < 3;c++) {
				aP.d[c] = aA.d[c]*(1-aF)+aB.d[c]*aF;
				aP.k[c] = aA.k[c]*(1-aF)+aB.k[c]*aF;
			}
			aP.q = Math3D.slerp(aA.q, aB.q, aF);
			return aP;
		}

		/**
		 * The node aBase of the model with this pose, into aOut
		 */
		void apply(Object3D aBase, Object3D aOut) {
			aOut.setPosition(Math3D.add(aBase.getPosition(), d));
			aOut.setRotation(Math3D.mulM(Math3D.fromQuat(q), aBase.getRotation()));
			double[] aS = aBase.getScale();
			aOut.setScale(new double[] {aS[0]*k[0], aS[1]*k[1], aS[2]*k[2]});
		}

		/**
		 * Pose that gives aNow from aBase (local transforms)
		 */
		public static Pose between(Object3D aBase, Object3D aNow) {
			Pose aP = new Pose();
			aP.d = Math3D.sub(aNow.getPosition(), aBase.getPosition());
			aP.q = Math3D.toQuat(Math3D.mulM(aNow.getRotation(), Math3D.transpose(aBase.getRotation())));
			double[] aS0 = aBase.getScale(), aS1 = aNow.getScale();
			for(int c = 0;c < 3;c++) {
				aP.k[c] = Math.abs(aS0[c]) < 1e-12 ? 1 : aS1[c]/aS0[c];
			}
			return aP;
		}

		String save(int aId) {
			return aId+";"+d[0]+";"+d[1]+";"+d[2]+";"+q[0]+";"+q[1]+";"+q[2]+";"+q[3]+";"+k[0]+";"+k[1]+";"+k[2];
		}

		static int load(String aText, Pose aP) {
			String[] aVs = aText.trim().split(";");
			for(int c = 0;c < 3;c++) {
				aP.d[c] = Double.parseDouble(aVs[1+c]);
				aP.k[c] = Double.parseDouble(aVs[8+c]);
			}
			for(int c = 0;c < 4;c++) {
				aP.q[c] = Double.parseDouble(aVs[4+c]);
			}
			return Integer.parseInt(aVs[0]);
		}
	}

	class Key {
		long time = 0;
		HashMap<Integer,Pose> poses = new HashMap<Integer,Pose>();
		//Camera of the key, null = that of the model of the media
		Cam cam = null;
		//Lamp of the key, null = that of the model of the media
		com.cubaix.TDenlive.model3d.Lamp lamp = null;

		Key copy() {
			Key aK = new Key();
			aK.time = time;
			aK.cam = cam == null ? null : cam.copy();
			aK.lamp = lamp == null ? null : lamp.copy();
			for(Map.Entry<Integer,Pose> aE : poses.entrySet()) {
				aK.poses.put(aE.getKey(), aE.getValue().copy());
			}
			return aK;
		}
	}
	Vector<Key> keys = new Vector<Key>();
	//Poses interpolated at the time cursor, for the display (index DISPLAY_KEY, UI thread only)
	Key displayKey = null;

	public Objects3D(TDenlive aTDe, Media aTargetMedia) {
		super(aTDe, aTargetMedia);
		isExpended = true;
		isUndeletable = true;
		isUnmovable = true;
		isDeactivable = false;
		keys.add(new Key());
	}

	@Override
	public String getClassName() {return "Objects3D";}

	@Override
	void setDescr() {
		name = tde.gui.lngs.get("Processor.Objects3D");
		icon = "cube3d.png";
	}

	@Override
	public int openProject(Vector<XmlObject> aOs, int o) throws Exception {
		Vector<Key> aKeys = new Vector<Key>();
		for(;o < aOs.size();o++) {
			XmlObject aO = aOs.elementAt(o);
			if(aO instanceof XmlTag) {
				XmlTag aT = (XmlTag)aO;
				if("/Objects3D".equalsIgnoreCase(aT.tagName)) {
					break;
				}
				if("Key".equalsIgnoreCase(aT.tagName)) {
					aKeys.add(new Key());
				}
				if("time".equalsIgnoreCase(aT.tagName) && aKeys.size() > 0) {
					aKeys.lastElement().time = Long.parseLong(aOs.elementAt(++o).text.trim());
				}
				if("pose".equalsIgnoreCase(aT.tagName) && aKeys.size() > 0) {
					Pose aP = new Pose();
					int aId = Pose.load(aOs.elementAt(++o).text, aP);
					aKeys.lastElement().poses.put(aId, aP);
				}
				if("cam".equalsIgnoreCase(aT.tagName) && aKeys.size() > 0) {
					aKeys.lastElement().cam = Cam.load(aOs.elementAt(++o).text);
				}
				if("lamp".equalsIgnoreCase(aT.tagName) && aKeys.size() > 0) {
					aKeys.lastElement().lamp = com.cubaix.TDenlive.model3d.Lamp.load(aOs.elementAt(++o).text);
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

	@Override
	public void saveProject(StringBuffer aSB) throws Exception {
		aSB.append("						<Objects3D>\n");
		synchronized (keys) {
			for(Key aK : keys) {
				aSB.append("							<Key>\n");
				aSB.append("								<time>" + aK.time + "</time>\n");
				if(aK.cam != null) {
					aSB.append("								<cam>" + aK.cam.save() + "</cam>\n");
				}
				if(aK.lamp != null) {
					aSB.append("								<lamp>" + aK.lamp.save() + "</lamp>\n");
				}
				//Same order at each save (the history compares the texts)
				for(Integer aId : new java.util.TreeSet<Integer>(aK.poses.keySet())) {
					Pose aP = aK.poses.get(aId);
					if(!aP.isIdentity()) {
						aSB.append("								<pose>" + aP.save(aId) + "</pose>\n");
					}
				}
				aSB.append("							</Key>\n");
			}
		}
		aSB.append("						</Objects3D>\n");
	}

	Clip getClip() {
		return (Clip)targetMedia;
	}

	/**
	 * Model of the media (not animated)
	 */
	public Model3D getBaseModel() {
		Media aM = getClip().getMedia();
		return aM instanceof Model3DMedia ? ((Model3DMedia)aM).getModel() : new Model3D();
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
	 * Poses at aT (time of the keys), interpolated (new objects)
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
					HashSet<Integer> aIds = new HashSet<Integer>(aKey.poses.keySet());
					aIds.addAll(aNext.poses.keySet());
					Pose aId = new Pose();
					for(Integer aI : aIds) {
						aInter.poses.put(aI, Pose.lerp(aKey.poses.getOrDefault(aI, aId), aNext.poses.getOrDefault(aI, aId), aF));
					}
					if(aKey.cam != null || aNext.cam != null) {
						Cam aBase = Cam.of(getBaseModel());
						aInter.cam = Cam.lerp(aKey.cam != null ? aKey.cam : aBase, aNext.cam != null ? aNext.cam : aBase, aF);
					}
					if(aKey.lamp != null || aNext.lamp != null) {
						com.cubaix.TDenlive.model3d.Lamp aBase = com.cubaix.TDenlive.model3d.Lamp.of(getBaseModel());
						aInter.lamp = com.cubaix.TDenlive.model3d.Lamp.lerp(aKey.lamp != null ? aKey.lamp : aBase, aNext.lamp != null ? aNext.lamp : aBase, aF);
					}
					return aInter;
				}
			}
		}
		return new Key();
	}

	/**
	 * Model of the media with the poses of a key (or of the display key)
	 */
	public Model3D modelForKey(int aKey) {
		Key aK;
		synchronized (keys) {
			aK = aKey == DISPLAY_KEY ? displayKey : (aKey >= 0 && aKey < keys.size() ? keys.elementAt(aKey) : null);
		}
		return withPoses(aK);
	}

	/**
	 * Model of the media at aTimePos (time of the montage)
	 */
	public Model3D modelAt(long aTimePos) {
		return withPoses(interpolate(keyTime(aTimePos)));
	}

	Model3D withPoses(Key aK) {
		Model3D aM = getBaseModel().copy();
		if(aK == null) {
			return aM;
		}
		if(aK.cam != null) {
			aK.cam.applyTo(aM);
		}
		if(aK.lamp != null) {
			aK.lamp.applyTo(aM);
		}
		for(Object3D aO : aM.objects) {
			Pose aP = aK.poses.get(aO.id);
			if(aP != null) {
				aP.apply(aO.copy(), aO);
			}
		}
		return aM;
	}

	/**
	 * Sets the pose of a node in a key so that it has the local transform of aNow
	 */
	public void setPose(int aKey, Object3D aNow) {
		Object3D aBase = getBaseModel().findById(aNow.id);
		if(aBase == null || aKey < 0 || aKey >= keys.size()) {
			return;
		}
		synchronized (keys) {
			keys.elementAt(aKey).poses.put(aNow.id, Pose.between(aBase, aNow));
		}
	}

	/**
	 * Camera of a key (or of the display key): its own, else that of the model of the media (mode free)
	 */
	public Cam camForKey(int aKey) {
		Key aK;
		synchronized (keys) {
			aK = aKey == DISPLAY_KEY ? displayKey : (aKey >= 0 && aKey < keys.size() ? keys.elementAt(aKey) : null);
		}
		return aK != null && aK.cam != null ? aK.cam.copy() : Cam.of(getBaseModel());
	}

	/**
	 * Camera at aTimePos (time of the montage)
	 */
	public Cam camAt(long aTimePos) {
		Key aK = interpolate(keyTime(aTimePos));
		return aK.cam != null ? aK.cam : Cam.of(getBaseModel());
	}

	/**
	 * Sets the camera of a key: the view of aM, in the mode aMode (an axis: the angles of that axis)
	 */
	public void setCamera(int aKey, Model3D aM, int aMode) {
		if(aKey < 0 || aKey >= keys.size()) {
			return;
		}
		Cam aC = Cam.of(aM);
		aC.setMode(aMode);
		synchronized (keys) {
			keys.elementAt(aKey).cam = aC;
		}
	}

	/**
	 * Changes the mode of the camera of a key (its target and distance kept)
	 */
	public void setCameraMode(int aKey, int aMode) {
		if(aKey < 0 || aKey >= keys.size()) {
			return;
		}
		Cam aC = camForKey(aKey);
		aC.setMode(aMode);
		synchronized (keys) {
			keys.elementAt(aKey).cam = aC;
		}
	}

	/**
	 * Lamp of a key (or of the display key): its own, else that of the model of the media
	 */
	public com.cubaix.TDenlive.model3d.Lamp lampForKey(int aKey) {
		Key aK;
		synchronized (keys) {
			aK = aKey == DISPLAY_KEY ? displayKey : (aKey >= 0 && aKey < keys.size() ? keys.elementAt(aKey) : null);
		}
		return aK != null && aK.lamp != null ? aK.lamp.copy() : com.cubaix.TDenlive.model3d.Lamp.of(getBaseModel());
	}

	/**
	 * Lamp at aTimePos (time of the montage)
	 */
	public com.cubaix.TDenlive.model3d.Lamp lampAt(long aTimePos) {
		Key aK = interpolate(keyTime(aTimePos));
		return aK.lamp != null ? aK.lamp : com.cubaix.TDenlive.model3d.Lamp.of(getBaseModel());
	}

	/**
	 * Sets the lamp of a key
	 */
	public void setLamp(int aKey, com.cubaix.TDenlive.model3d.Lamp aL) {
		if(aKey < 0 || aKey >= keys.size()) {
			return;
		}
		synchronized (keys) {
			keys.elementAt(aKey).lamp = aL.copy();
		}
	}

	/**
	 * Back to the model of the media for a node in a key
	 */
	public void resetPose(int aKey, int aId) {
		if(aKey < 0 || aKey >= keys.size()) {
			return;
		}
		synchronized (keys) {
			keys.elementAt(aKey).poses.remove(aId);
		}
	}

	/**
	 * True if this processor renders the model itself at aTimePos (active, poses or camera not those of the
	 * media): the image of the media is then not needed (Clip.reBuild() skips its rendering)
	 */
	public boolean rendersAt(long aTimePos) {
		return isActive && !isIdentityAt(aTimePos);
	}

	boolean isIdentityAt(long aTimePos) {
		Key aK = interpolate(keyTime(aTimePos));
		if(aK.cam != null && !aK.cam.same(Cam.of(getBaseModel()))) {
			return false;
		}
		if(aK.lamp != null && !aK.lamp.same(com.cubaix.TDenlive.model3d.Lamp.of(getBaseModel()))) {
			return false;
		}
		for(Pose aP : aK.poses.values()) {
			if(!aP.isIdentity()) {
				return false;
			}
		}
		return true;
	}

	@Override
	public Image process2Swt(Media aMedia, int aProcessingMode, Rectangle aTargetSize, long aTime) {
		return null;
	}

	@Override
	public String stateAt(int aProcessingMode,long aTime) {
		//The image of the media when no node moves (its own images say if it changed), else rendered
		return isIdentityAt(aTime) ? "" : null;
	}

	@Override
	public void process2Awt(Media aMedia, int aProcessingMode, long aTime) {
		if(isIdentityAt(aTime)) {
			//The image of the media
			return;
		}
		try {
			//The size of the image of the media (Model3DMedia.renderEye()), without rendering it (Clip.reBuild())
			int aH = Math.max(1, tde.config.processingResValues[aProcessingMode]);
			int aW = Math.max(1, (int)(aH*tde.config.outRatio));
			Model3D aM = modelAt(aTime);
			long aMediaTime = Math.max(0, getClip().mediaTimeMS(aTime));
			double aSpin = aM.spin*aMediaTime/1000.0;
			//Textured by images or videos: the monitor at the VHS height, enlarged after
			int aRH = aM.renderHeight(aProcessingMode, aH);
			int aRW = aRH == aH ? aW : Math.max(1, (int)Math.round(aW*aRH/(double)aH));
			Renderer3D.Options aOpt = new Renderer3D.Options();
			//Anti-aliasing for the rendering only
			aOpt.ss = Renderer3D.ssFor(aProcessingMode, aRW, aRH);
			//Videos as textures: at the media time of the clip
			aOpt.mode = aProcessingMode;
			aOpt.timeMS = aMediaTime;
			//Both eyes at the same time
			Renderer3D.Result[] aRs = Renderer3D.renderPair(aM, aRW, aRH, aSpin, aOpt);
			BufferedImage aL = aRs[0].image;
			BufferedImage aR = aRs[1].image;
			if(aRH != aH) {
				boolean aHdr = tde.config.processingHdr[aProcessingMode];
				aL = Renderer3D.enlarge(aL, aW, aH, aHdr);
				aR = Renderer3D.enlarge(aR, aW, aH, aHdr);
			}
			else if(tde.config.processingHdr[aProcessingMode]) {
				boolean aFast = tde.config.fastMode && aProcessingMode != TDConfig.PROCESSING_MODE_RENDER;
				aL = ImageUtils.toRes(aL, aH, true, aFast, false);
				aR = ImageUtils.toRes(aR, aH, true, aFast, false);
			}
			aMedia.setWorkLeft(aL);
			aMedia.setWorkRight(aR);
		}
		catch(Throwable t) {
			t.printStackTrace(System.err);
		}
	}
}
