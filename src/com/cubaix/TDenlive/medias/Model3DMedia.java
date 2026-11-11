package com.cubaix.TDenlive.medias;

import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.util.Vector;

import com.cubaix.TDenlive.TDenlive;
import com.cubaix.TDenlive.model3d.Model3D;
import com.cubaix.TDenlive.model3d.Renderer3D;
import com.cubaix.TDenlive.utils.ImageUtils;
import com.cubaix.TDenlive.xml.XmlObject;
import com.cubaix.TDenlive.xml.XmlTag;

/**
 * 3D model drawn by the 3D modeler (Model3DGUI): rendered for each eye by its stereo camera,
 * on a transparent frame of the output ratio. With a turntable speed, the image depends on the time.
 */
public class Model3DMedia extends StereoImage {
	Model3D model = new Model3D();
	//Output ratio and media time of the last image
	double lastProcessedRatio = 0;
	long lastTime = Long.MIN_VALUE;

	public Model3DMedia(TDenlive aTDe) {
		super(aTDe);
		isSizeAuto = true;
	}

	public Model3D getModel() {
		return model;
	}

	public synchronized void setModel(Model3D aModel) {
		model = aModel.copy();
		workLeft = workRight = null;
		lastProcessedRes = -1;
		try {
			reloadOrig(com.cubaix.TDenlive.TDConfig.PROCESSING_MODE_WORK, 0);
		}
		catch(Throwable t) {
			t.printStackTrace(System.err);
		}
	}

	@Override
	public int openProject(Vector<XmlObject> aOs, int o) throws Exception {
		return super.openProject(aOs, o, "Model3DMedia");
	}

	@Override
	public int openProjectSub(Vector<XmlObject> aOs, int o) throws Exception {
		XmlObject aO = aOs.elementAt(o);
		if(aO instanceof XmlTag && "Model3D".equalsIgnoreCase(((XmlTag)aO).tagName)) {
			model = new Model3D();
			o = model.openProject(aOs, o);
		}
		return o;
	}

	@Override
	public void saveProject(StringBuffer aSB) throws Exception {
		super.saveProject(aSB, "Model3DMedia");
	}

	@Override
	void saveProjectSub(StringBuffer aSB) {
		model.saveProject(aSB, "			");
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
	public synchronized void reBuild(int aProcessingMode, long aTime) {
		long aT = model.spin == 0 ? 0 : Math.max(0, aTime);
		if(workLeft != null && workRight != null
				&& lastProcessedRes == tde.config.processingResValues[aProcessingMode]
						&& lastProcessedHdr == tde.config.processingHdr[aProcessingMode]
								&& lastProcessedRatio == tde.config.outRatio
										&& lastTime == aT) {
			return;
		}
		workLeft = workRight = null;
		try {
			render(aProcessingMode, aT);
		}
		catch(Throwable t) {
			t.printStackTrace(System.err);
		}
		getWorkLeft(aProcessingMode, aT);
		getWorkRight(aProcessingMode, aT);
		lastProcessedRes = tde.config.processingResValues[aProcessingMode];
		lastProcessedHdr = tde.config.processingHdr[aProcessingMode];
	}

	/**
	 * Both eyes at a media time
	 */
	void render(int aProcessingMode, long aTime) {
		lastTime = model.spin == 0 ? 0 : Math.max(0, aTime);
		loadedLeft = renderEye(aProcessingMode, lastTime, -1);
		loadedRight = renderEye(aProcessingMode, lastTime, 1);
		if(origWidth <= 0) {
			origWidth = loadedLeft.getWidth();
			origHeight = loadedLeft.getHeight();
		}
	}

	BufferedImage renderEye(int aProcessingMode, long aTime, int aEye) {
		int aHeight = Math.max(1, tde.config.processingResValues[aProcessingMode]);
		lastProcessedRatio = tde.config.outRatio;
		int aWidth = Math.max(1, (int)(aHeight*lastProcessedRatio));
		Renderer3D.Options aOpt = new Renderer3D.Options();
		//Anti-aliasing up to the full HD, the memory of the samples grows fast beyond
		aOpt.ss = aWidth*(long)aHeight <= 2200000 ? 2 : 1;
		return Renderer3D.render(model, aWidth, aHeight, aEye, model.spin*aTime/1000.0, aOpt).image;
	}

	@Override
	BufferedImage loadImage(String aPath, int aProcessingMode, long aTime, boolean aIsLeft) throws Exception {
		return renderEye(aProcessingMode, aTime, aIsLeft ? -1 : 1);
	}

	@Override
	synchronized void reloadOrig(int aProcessingMode, long aTime) throws Exception {
		render(aProcessingMode, aTime);
		fileName = model.name != null && !model.name.trim().isEmpty() ? model.name.trim() : tde.gui.lngs.get("Model3D.MediaName");
		buildThumb();
	}

	@Override
	public void buildThumb() {
		//The model over a checkerboard: the transparent parts are visible
		int aH = Math.max(1, tde.config.thumbH);
		int aW = Math.max(1, (int)(aH*Math.max(0.2, tde.config.outRatio)));
		BufferedImage aThumb = ImageUtils.createImage(aW, aH);
		ImageUtils.checkerBck(aThumb, 4);
		Renderer3D.Options aOpt = new Renderer3D.Options();
		BufferedImage aModel = Renderer3D.render(model, aW, aH, 0, 0, aOpt).image;
		Graphics2D aG = (Graphics2D)aThumb.getGraphics();
		aG.drawImage(aModel, 0, 0, null);
		aG.dispose();
		setThumbAwt(aThumb);
		setThumbSwt(ImageUtils.imageToSwt(tde.gui.display, aThumb, false));
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
