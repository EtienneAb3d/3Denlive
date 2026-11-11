package com.cubaix.TDenlive.medias;

import java.awt.image.BufferedImage;
import java.util.Vector;

import com.cubaix.TDenlive.TDenlive;
import com.cubaix.TDenlive.shapes.Shape;
import com.cubaix.TDenlive.shapes.ShapeRenderer;
import com.cubaix.TDenlive.xml.XmlObject;
import com.cubaix.TDenlive.xml.XmlTag;

/**
 * Vector shapes on a transparent frame (follows the output ratio).
 * These are the shapes drawn at the creation: each clip of this media
 * gets a copy of them in its Shapes processor, which animates them.
 */
public class ShapesMedia extends StereoImage {
	Vector<Shape> shapes = new Vector<Shape>();
	//Output ratio used for the last image
	double lastProcessedRatio = 0;

	public ShapesMedia(TDenlive aTDe) {
		super(aTDe);
		isSizeAuto = true;
	}

	public Vector<Shape> getShapes() {
		return shapes;
	}

	public void setShapes(Vector<Shape> aShapes) {
		shapes = Shape.copyAll(aShapes);
		workLeft = workRight = null;
	}

	/**
	 * Images and thumbnail again after a change of the shapes
	 */
	public void reloadShapes() {
		workLeft = workRight = null;
		lastProcessedRes = -1;
		try {
			reloadOrig(com.cubaix.TDenlive.TDConfig.PROCESSING_MODE_WORK, -1);
		}
		catch(Throwable t) {
			t.printStackTrace(System.err);
		}
	}

	@Override
	public int openProject(Vector<XmlObject> aOs, int o) throws Exception {
		shapes.clear();
		return super.openProject(aOs, o,"ShapesMedia");
	}

	@Override
	public int openProjectSub(Vector<XmlObject> aOs, int o) throws Exception {
		XmlObject aO = aOs.elementAt(o);
		if(aO instanceof XmlTag) {
			XmlTag aT = (XmlTag)aO;
			if("Shape".equalsIgnoreCase(aT.tagName)) {
				Shape aS = new Shape();
				o = aS.openProject(aOs, o);
				shapes.add(aS);
			}
		}
		return o;
	}

	@Override
	public void saveProject(StringBuffer aSB) throws Exception {
		super.saveProject(aSB,"ShapesMedia");
	}

	@Override
	void saveProjectSub(StringBuffer aSB) {
		for(Shape aS : shapes) {
			aS.saveProject(aSB, "			");
		}
	}

	@Override
	public Vector<String> getFilePaths() {
		//No file
		return new Vector<String>();
	}

	@Override
	public double getDisplayRatio() {
		//No size of its own: follows the output ratio
		return 0;
	}

	@Override
	public void reBuild(int aProcessingMode,long aTime) {
		if(workLeft != null && workRight != null
				&& lastProcessedRes == tde.config.processingResValues[aProcessingMode]
						&& lastProcessedHdr == tde.config.processingHdr[aProcessingMode]
								&& lastProcessedRatio == tde.config.outRatio) {
			return;
		}
		workLeft = workRight = null;
		try {
			reloadOrig(aProcessingMode,aTime);
		}
		catch(Throwable t) {
			t.printStackTrace(System.err);
		}
		getWorkLeft(aProcessingMode,aTime);
		getWorkRight(aProcessingMode,aTime);
		lastProcessedRes = tde.config.processingResValues[aProcessingMode];
		lastProcessedHdr = tde.config.processingHdr[aProcessingMode];
	}

	@Override
	BufferedImage loadImage(String aPath, int aProcessingMode, long aTime, boolean aIsLeft) throws Exception {
		int aHeight = tde.config.processingResValues[aProcessingMode];
		lastProcessedRatio = tde.config.outRatio;
		int aWidth = Math.max(1, (int)(aHeight*lastProcessedRatio));
		BufferedImage aBI = ShapeRenderer.render(shapes, aWidth, aHeight, aIsLeft ? -1 : 1);
		if(origWidth <= 0) {
			origWidth = aBI.getWidth();
			origHeight = aBI.getHeight();
		}
		return aBI;
	}

	@Override
	void reloadOrig(int aProcessingMode, long aTime) throws Exception {
		loadedLeft = loadImage(null, aProcessingMode, aTime, true);
		loadedRight = loadImage(null, aProcessingMode, aTime, false);
		fileName = tde.gui.lngs.get("Shapes.MediaName");
		buildThumb();
	}

	@Override
	public void buildThumb() {
		if(loadedLeft == null) {
			return;
		}
		//Shapes over a checkerboard: the transparent parts are visible
		int aH = tde.config.thumbH;
		int aW = Math.max(1, (int)(loadedLeft.getWidth()*aH/(double)loadedLeft.getHeight()));
		BufferedImage aThumb = com.cubaix.TDenlive.utils.ImageUtils.createImage(aW, aH);
		com.cubaix.TDenlive.utils.ImageUtils.checkerBck(aThumb, 4);
		java.awt.Graphics2D aG = (java.awt.Graphics2D)aThumb.getGraphics();
		ShapeRenderer.draw(aG, shapes, 0, 0, aW, aH, 0);
		aG.dispose();
		setThumbAwt(aThumb);
		setThumbSwt(com.cubaix.TDenlive.utils.ImageUtils.imageToSwt(tde.gui.display,aThumb,false));
	}

	@Override
	public void loadImageLR(String aPathL, String aPathR, int aProcessingMode, long aTime) throws Exception {
		reloadOrig(aProcessingMode, aTime);
	}

	@Override
	public void loadImageX(String aPath, int aProcessingMode, long aTime) throws Exception {
		reloadOrig(aProcessingMode, aTime);
	}

	@Override
	public void loadImageP(String aPath, int aProcessingMode, long aTime) throws Exception {
		reloadOrig(aProcessingMode, aTime);
	}
}
