package com.cubaix.TDenlive.processors;

import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.util.Vector;

import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.graphics.Rectangle;

import com.cubaix.TDenlive.TDConfig;
import com.cubaix.TDenlive.TDenlive;
import com.cubaix.TDenlive.medias.Clip;
import com.cubaix.TDenlive.medias.Media;
import com.cubaix.TDenlive.medias.ShapesMedia;
import com.cubaix.TDenlive.shapes.Shape;
import com.cubaix.TDenlive.shapes.ShapeRenderer;
import com.cubaix.TDenlive.shapes.ShapesEditor;
import com.cubaix.TDenlive.utils.ImageUtils;
import com.cubaix.TDenlive.xml.XmlObject;
import com.cubaix.TDenlive.xml.XmlTag;

/**
 * Mandatory processor of the clips of a ShapesMedia: draws its shapes, with keys.
 * Every key holds all the shapes of the clip (same ids, same order), the deleted
 * ones are only hidden. The image of the media is replaced by the drawn shapes.
 */
public class Shapes extends Processor {
	class Key {
		long time = 0;
		Vector<Shape> shapes = new Vector<Shape>();
	}
	Vector<Key> keys = new Vector<Key>();
	ShapesEditor editor = null;

	public Shapes(TDenlive aTDe,Media aTargetMedia) {
		super(aTDe,aTargetMedia);
		isExpended = true;
		isUndeletable = true;
		isUnmovable = true;
		isDeactivable = false;
		Key aKey = new Key();
		if(aTargetMedia instanceof Clip && ((Clip)aTargetMedia).getMedia() instanceof ShapesMedia) {
			aKey.shapes = Shape.copyAll(((ShapesMedia)((Clip)aTargetMedia).getMedia()).getShapes());
		}
		keys.add(aKey);
	}

	@Override
	public String getClassName() {return "Shapes";}

	@Override
	void setDescr() {
		name = tde.gui.lngs.get("Processor.Shapes");
		icon = "colorObjects.png";
	}

	@Override
	public int openProject(Vector<XmlObject> aOs, int o) throws Exception {
		Vector<Key> aKeys = new Vector<Key>();
		for(;o < aOs.size();o++) {
			XmlObject aO = aOs.elementAt(o);
			if(aO instanceof XmlTag) {
				XmlTag aT = (XmlTag)aO;
				if("/Shapes".equalsIgnoreCase(aT.tagName)) {
					break;
				}
				if("Key".equalsIgnoreCase(aT.tagName)) {
					aKeys.add(new Key());
				}
				if("time".equalsIgnoreCase(aT.tagName) && aKeys.size() > 0) {
					o++;
					aKeys.lastElement().time = Long.parseLong(aOs.elementAt(o).text.trim());
				}
				if("Shape".equalsIgnoreCase(aT.tagName) && aKeys.size() > 0) {
					Shape aS = new Shape();
					o = aS.openProject(aOs, o);
					aKeys.lastElement().shapes.add(aS);
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
		aSB.append("						<Shapes>\n");
		synchronized (keys) {
			for(Key aK : keys) {
				aSB.append("							<Key>\n");
				aSB.append("								<time>" + aK.time + "</time>\n");
				for(Shape aS : aK.shapes) {
					aS.saveProject(aSB, "								");
				}
				aSB.append("							</Key>\n");
			}
		}
		aSB.append("						</Shapes>\n");
	}

	Clip getClip() {
		return (Clip)targetMedia;
	}

	/**
	 * Highest id of the shapes of the keys, -1 if none
	 */
	public int maxShapeId() {
		int aMax = -1;
		synchronized (keys) {
			for(Key aK : keys) {
				for(Shape aS : aK.shapes) {
					aMax = Math.max(aMax, aS.id);
				}
			}
		}
		return aMax;
	}

	/**
	 * The shapes of the media were edited (aOld → aNew): in every key, a removed shape is removed,
	 * a new one added, and a changed one replaced where the key still has it as in the media
	 * (an animated shape keeps its keys)
	 */
	public void mediaShapesChanged(Vector<Shape> aOld, Vector<Shape> aNew) {
		synchronized (keys) {
			for(Key aK : keys) {
				for(Shape aO : aOld) {
					if(Shape.findById(aNew, aO.id) == null) {
						Shape aS = Shape.findById(aK.shapes, aO.id);
						if(aS != null) {
							aK.shapes.remove(aS);
						}
					}
				}
				for(Shape aN : aNew) {
					Shape aO = Shape.findById(aOld, aN.id);
					Shape aS = Shape.findById(aK.shapes, aN.id);
					if(aO == null) {
						if(aS == null) {
							aK.shapes.add(aN.copy());
						}
						continue;
					}
					if(aS == null || shapeXml(aO).equals(shapeXml(aN))) {
						continue;
					}
					Shape aCmp = aS.copy();
					aCmp.hidden = aO.hidden;
					if(shapeXml(aCmp).equals(shapeXml(aO))) {
						Shape aR = aN.copy();
						aR.hidden = aS.hidden;
						aK.shapes.set(aK.shapes.indexOf(aS), aR);
					}
				}
			}
		}
	}

	@Override
	public int getNbKeys() {
		return keys.size();
	}

	@Override
	public long getTime(int aKey) {
		return keys.elementAt(aKey).time;
	}

	@Override
	public int addKeyGT(long aTime) {
		if(aTime < getClip().getStartTimeMS() || aTime <= getClip().getKeysOriginMS() || aTime > getClip().getStartTimeMS() + getClip().getDurationMS()) {
			//Can't insert outside
			return -1;
		}
		synchronized (keys) {
			long aT = aTime-getClip().getKeysOriginMS();
			int aExact = keyAt(aT);
			if(aExact >= 0) {
				return aExact;
			}
			Key aKey = new Key();
			aKey.time = aT;
			aKey.shapes = interpolate(aT);
			for(int k = keys.size() - 1;k >= 0;k--) {
				if(keys.elementAt(k).time <= aT) {
					keys.add(k+1, aKey);
					return k+1;
				}
			}
			keys.add(aKey);
			return keys.size()-1;
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
		long aT = aTime - getClip().getKeysOriginMS();
		for(int k = keys.size() - 1;k >= 0;k--) {
			if(keys.elementAt(k).time <= aT) {
				return k;
			}
		}
		return -1;
	}

	@Override
	public int getNextKeyGT(long aTime) {
		long aT = aTime - getClip().getKeysOriginMS();
		for(int k = 0;k < keys.size();k++) {
			if(keys.elementAt(k).time > aT) {
				return k;
			}
		}
		return -1;
	}

	/**
	 * Index of the key at aT (time in the clip), -1 if none
	 */
	int keyAt(long aT) {
		for(int k = 0;k < keys.size();k++) {
			if(keys.elementAt(k).time == aT) {
				return k;
			}
		}
		return -1;
	}

	/**
	 * Shapes at aT (time in the clip), interpolated between the keys (new objects)
	 */
	Vector<Shape> interpolate(long aT) {
		synchronized (keys) {
			for(int k = keys.size()-1;k >= 0;k--) {
				Key aKey = keys.elementAt(k);
				if(aKey.time <= aT || k == 0) {
					if(k == keys.size()-1 || aKey.time >= aT) {
						return Shape.copyAll(aKey.shapes);
					}
					Key aNext = keys.elementAt(k+1);
					double aF = (aT-aKey.time)/(double)(aNext.time-aKey.time);
					Vector<Shape> aShapes = new Vector<Shape>();
					for(Shape aS : aKey.shapes) {
						aShapes.add(Shape.lerp(aS, Shape.findById(aNext.shapes, aS.id), aF, tde.config.outRatio));
					}
					return aShapes;
				}
			}
		}
		return new Vector<Shape>();
	}

	public Vector<Shape> getShapesAt(long aTimePos) {
		return interpolate(aTimePos-getClip().getKeysOriginMS());
	}

	@Override
	public Image process2Swt(Media aMedia,int aProcessingMode,Rectangle aTargetSize,long aTime) {
		return null;
	}

	@Override
	public void process2Awt(Media aMedia,int aProcessingMode,long aTime) {
		try {
			BufferedImage aBIL = aMedia.getWorkLeft(aProcessingMode,aTime);
			int aW = aBIL != null ? aBIL.getWidth() : (int)(tde.config.outRatio*tde.config.processingResValues[aProcessingMode]);
			int aH = aBIL != null ? aBIL.getHeight() : tde.config.processingResValues[aProcessingMode];
			Vector<Shape> aShapes = getShapesAt(aTime);
			boolean aHdr = tde.config.processingHdr[aProcessingMode];
			boolean aFast = tde.config.fastMode && aProcessingMode != TDConfig.PROCESSING_MODE_RENDER;
			BufferedImage aL = ShapeRenderer.render(aShapes, aW, aH, -1);
			BufferedImage aR = ShapeRenderer.render(aShapes, aW, aH, 1);
			if(aHdr) {
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

	//
	// Edition (on the monitor and in the processor panel)
	//

	/**
	 * Geometry of the active processors after this one (StereoAligner, Placer...) at aTimePos,
	 * for the eye aEye (-1 left, 1 right, 0 between both): frame coordinates of the shapes (0..1)
	 * to frame coordinates of the montage (0..1), where the clip image is drawn
	 */
	public AffineTransform getChainTransform(int aEye,long aTimePos) {
		if(aEye == 0) {
			double[] aL = new double[6];
			double[] aR = new double[6];
			getChainTransform(-1, aTimePos).getMatrix(aL);
			getChainTransform(1, aTimePos).getMatrix(aR);
			for(int m = 0;m < 6;m++) {
				aL[m] = (aL[m]+aR[m])/2;
			}
			return new AffineTransform(aL);
		}
		int aMode = TDConfig.PROCESSING_MODE_WORK;
		int aW0 = Math.max(1, (int)(tde.config.outRatio*tde.config.processingResValues[aMode]));
		int aH0 = Math.max(1, tde.config.processingResValues[aMode]);
		AffineTransform aT = AffineTransform.getScaleInstance(aW0, aH0);
		int[] aSize = {aW0,aH0};
		boolean aAfter = false;
		for(Processor aP : new Vector<Processor>(getClip().getProcessors())) {
			if(aP == this) {
				aAfter = true;
				continue;
			}
			if(!aAfter || !aP.isActive) {
				continue;
			}
			try {
				int[] aOut = new int[2];
				AffineTransform aG = aP.getGeometry(aEye, aTimePos, aMode, aSize[0], aSize[1], aOut);
				aT.preConcatenate(aG);
				aSize = aOut;
			}
			catch(Throwable t) {
				t.printStackTrace(System.err);
			}
		}
		//The clip image is drawn at the top left corner of the montage
		aT.preConcatenate(AffineTransform.getScaleInstance(1.0/aW0, 1.0/aH0));
		return aT;
	}

	/**
	 * Current time of the montage in the time of the keys, -1 if out of the clip
	 */
	long currentClipTime() {
		long aTimePos = tde.timeLineStack.getTimePosMS();
		long aT = aTimePos-getClip().getStartTimeMS();
		if(aT < 0 || aT >= getClip().getDurationMS()) {
			return -1;
		}
		return aTimePos-getClip().getKeysOriginMS();
	}

	/**
	 * With selected keys: the main selected key, edited and shown instead of the interpolated shapes.
	 * -1 out of the clip
	 */
	int manualKey() {
		if(currentClipTime() < 0) {
			return -1;
		}
		return Math.max(0, getMainSelectedKey());
	}

	//Main selected key and copy of its shapes at the last synchronization (see syncSelectedKeys())
	Key syncKey = null;
	Vector<Shape> syncShapes = null;

	@Override
	protected void keySelectionChanged() {
		syncSelectedKeys();
	}

	static String shapeXml(Shape aS) {
		StringBuffer aSB = new StringBuffer();
		aS.saveProject(aSB, "");
		return aSB.toString();
	}

	/**
	 * After a change of the shapes of the main selected key: the shapes changed since the last call
	 * are copied into the other selected keys (same value in all of them)
	 */
	void syncSelectedKeys() {
		Vector<Integer> aSel = getSelectedKeys();
		synchronized (keys) {
			if(aSel.isEmpty()) {
				syncKey = null;
				syncShapes = null;
				return;
			}
			int aMain = aSel.lastElement();
			Key aMK = keys.elementAt(aMain);
			if(aMK == syncKey && syncShapes != null && aSel.size() > 1) {
				for(Shape aS : aMK.shapes) {
					Shape aOld = Shape.findById(syncShapes, aS.id);
					if(aOld != null && shapeXml(aOld).equals(shapeXml(aS))) {
						continue;
					}
					for(int aK : aSel) {
						if(aK == aMain || aK >= keys.size()) {
							continue;
						}
						Vector<Shape> aShapes = keys.elementAt(aK).shapes;
						Shape aT = Shape.findById(aShapes, aS.id);
						if(aT != null) {
							aShapes.set(aShapes.indexOf(aT), aS.copy());
						}
					}
				}
			}
			syncKey = aMK;
			syncShapes = Shape.copyAll(aMK.shapes);
		}
	}

	/** Editor listener: the changes of the main selected key go to the other selected keys */
	ShapesEditor.Listener syncListener = new ShapesEditor.Listener() {
		@Override
		public void shapesChanged() {
			syncSelectedKeys();
		}
		@Override
		public void selectionChanged() {
		}
	};

	/**
	 * Key at the current time, created if needed, -1 out of the clip
	 */
	public int ensureCurrentKey() {
		long aT = currentClipTime();
		if(aT < 0) {
			return -1;
		}
		if(!isAutoKey()) {
			return manualKey();
		}
		synchronized (keys) {
			int aK = keyAt(aT);
			if(aK >= 0) {
				return aK;
			}
			return addKeyGT(aT+getClip().getKeysOriginMS());
		}
	}

	public ShapesEditor getEditor() {
		if(editor != null) {
			return editor;
		}
		editor = new ShapesEditor(new ShapesEditor.Source() {
			@Override
			public Vector<Shape> getShapes() {
				long aT = currentClipTime();
				if(aT < 0) {
					return null;
				}
				synchronized (keys) {
					int aK = isAutoKey() ? keyAt(aT) : manualKey();
					if(aK >= 0) {
						return keys.elementAt(aK).shapes;
					}
				}
				return interpolate(aT);
			}
			@Override
			public Vector<Shape> getDisplayedShapes() {
				//As drawn in the image: the key at the time, else interpolated
				long aT = currentClipTime();
				if(aT < 0) {
					return null;
				}
				synchronized (keys) {
					int aK = keyAt(aT);
					if(aK >= 0) {
						return keys.elementAt(aK).shapes;
					}
				}
				return interpolate(aT);
			}
			@Override
			public Vector<Shape> getShapesForEdit() {
				int aK = ensureCurrentKey();
				return aK < 0 ? null : keys.elementAt(aK).shapes;
			}
			@Override
			public void addShape(Shape aS) {
				int aK = ensureCurrentKey();
				if(aK < 0) {
					return;
				}
				synchronized (keys) {
					int aId = 0;
					for(Key aKey : keys) {
						for(Shape aS2 : aKey.shapes) {
							aId = Math.max(aId, aS2.id+1);
						}
					}
					aS.id = aId;
					aS.hidden = false;
					//In the clip from this key on, hidden before
					for(int k = 0;k < keys.size();k++) {
						Shape aCopy = k == aK ? aS : aS.copy();
						aCopy.hidden = k < aK;
						keys.elementAt(k).shapes.add(aCopy);
					}
				}
			}
			@Override
			public void deleteShape(int aId) {
				setHidden(aId, true);
			}
			@Override
			public void setHidden(int aId, boolean aHidden) {
				int aK = ensureCurrentKey();
				if(aK < 0) {
					return;
				}
				//From this key on
				synchronized (keys) {
					for(int k = aK;k < keys.size();k++) {
						Shape aS = Shape.findById(keys.elementAt(k).shapes, aId);
						if(aS != null) {
							aS.hidden = aHidden;
						}
					}
				}
			}
			@Override
			public void reorder(int aId, boolean aToFront) {
				//Same order in all the keys
				synchronized (keys) {
					for(Key aKey : keys) {
						Shape aS = Shape.findById(aKey.shapes, aId);
						if(aS != null) {
							aKey.shapes.remove(aS);
							if(aToFront) {
								aKey.shapes.add(aS);
							}
							else {
								aKey.shapes.add(0, aS);
							}
						}
					}
				}
			}
			@Override
			public boolean showGhosts() {
				return true;
			}
			@Override
			public Vector<Vector<Shape>> getFollowingShapes() {
				Vector<Vector<Shape>> aFollowing = new Vector<Vector<Shape>>();
				long aT = currentClipTime();
				if(aT < 0) {
					return aFollowing;
				}
				synchronized (keys) {
					for(Key aKey : keys) {
						if(aKey.time > aT) {
							aFollowing.add(aKey.shapes);
						}
					}
				}
				return aFollowing;
			}
		});
		editor.addListener(syncListener);
		return editor;
	}
}
