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
 */
public class Objects3D extends Processor {
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

		Key copy() {
			Key aK = new Key();
			aK.time = time;
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

	boolean isIdentityAt(long aTimePos) {
		Key aK = interpolate(keyTime(aTimePos));
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
	public void process2Awt(Media aMedia, int aProcessingMode, long aTime) {
		if(isIdentityAt(aTime)) {
			//The image of the media
			return;
		}
		try {
			BufferedImage aBIL = aMedia.getWorkLeft(aProcessingMode, aTime);
			int aH = tde.config.processingResValues[aProcessingMode];
			int aW = aBIL != null ? aBIL.getWidth() : (int)(tde.config.outRatio*aH);
			if(aBIL != null) {
				aH = aBIL.getHeight();
			}
			Model3D aM = modelAt(aTime);
			long aMediaTime = Math.max(0, getClip().mediaTimeMS(aTime));
			double aSpin = aM.spin*aMediaTime/1000.0;
			Renderer3D.Options aOpt = new Renderer3D.Options();
			aOpt.ss = aW*(long)aH <= 2200000 ? 2 : 1;
			BufferedImage aL = Renderer3D.render(aM, aW, aH, -1, aSpin, aOpt).image;
			BufferedImage aR = Renderer3D.render(aM, aW, aH, 1, aSpin, aOpt).image;
			if(tde.config.processingHdr[aProcessingMode]) {
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
