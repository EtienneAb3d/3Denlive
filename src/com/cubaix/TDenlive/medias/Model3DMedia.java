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
	//Played alone from the media list (Play with the model selected): it turns as in the texture editor
	//(Texture3DGUI: 2 degrees every 80 ms), one whole turn
	static final double LIBRARY_SPIN_DEG_PER_S = 25;
	boolean lastLibraryPlay = false;

	/**
	 * True while the model is played alone from the media list (playing or paused)
	 */
	boolean libraryPlay() {
		return tde != null && tde.gui != null && tde.gui.playerControlGUI != null && tde.gui.playerControlGUI.isPlayingAlone(this);
	}

	/**
	 * Time of the image: 0 when it does not change with the time (not animated, not played alone)
	 */
	long imageTime(long aTime) {
		return !model.isAnimated() && !libraryPlay() ? 0 : Math.max(0, aTime);
	}

	@Override
	public long getMaxTimePosMS() {
		//Played alone: one whole turn at least
		return libraryPlay() ? Math.max(durationMS, Math.round(360*1000/LIBRARY_SPIN_DEG_PER_S)) : durationMS;
	}
	//A media texture was missing at the last image / thumbnail (not loaded yet): made again
	boolean missing = false;
	boolean thumbMissing = false;

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
			//Images of the disk used as textures: found back next to the project if moved
			for(com.cubaix.TDenlive.model3d.Object3D aObj : model.objects) {
				aObj.mapTextures(aT -> {
					if(aT == null || !ColorBck.Fill.isFile(aT)) {
						return aT;
					}
					String aF = ColorBck.Fill.FILE + findPath(aT.substring(ColorBck.Fill.FILE.length()));
					ColorBck.Fill.addFile(aF);
					return aF;
				});
			}
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
		//No file, but the images of the disk used as textures
		Vector<String> aPs = new Vector<String>();
		for(com.cubaix.TDenlive.model3d.Object3D aObj : model.objects) {
			for(String aP : aObj.textureFiles()) {
				if(!aPs.contains(aP)) {
					aPs.add(aP);
				}
			}
		}
		return aPs;
	}

	@Override
	public java.util.LinkedHashMap<Long, String> usedMedias() {
		java.util.LinkedHashMap<Long, String> aUs = new java.util.LinkedHashMap<Long, String>();
		for(com.cubaix.TDenlive.model3d.Object3D aO : model.objects) {
			for(String aT : aO.textures()) {
				long aUID = com.cubaix.TDenlive.model3d.Texture3D.mediaUID(aT);
				if(aUID >= 0) {
					aUs.put(aUID, "Texture3D");
				}
			}
		}
		return aUs;
	}

	@Override
	public double getDisplayRatio() {
		//No size of its own: follows the output ratio
		return 0;
	}

	@Override
	public synchronized void reBuild(int aProcessingMode, long aTime) {
		long aT = imageTime(aTime);
		if(thumbMissing) {
			buildThumb();
		}
		if(!missing && workLeft != null && workRight != null
				&& lastProcessedRes == tde.config.processingResValues[aProcessingMode]
						&& lastProcessedHdr == tde.config.processingHdr[aProcessingMode]
								&& lastProcessedRatio == tde.config.outRatio
										&& lastTime == aT
												&& lastLibraryPlay == libraryPlay()) {
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
		lastTime = imageTime(aTime);
		lastLibraryPlay = libraryPlay();
		missing = false;
		//Both eyes at the same time
		long aT = lastTime;
		java.util.List<BufferedImage> aEyes = Renderer3D.both(() -> renderEye(aProcessingMode, aT, -1), () -> renderEye(aProcessingMode, aT, 1));
		loadedLeft = aEyes.get(0);
		loadedRight = aEyes.get(1);
		if(origWidth <= 0) {
			//Size of the full render (the monitor may render smaller: Model3D.renderHeight())
			origHeight = Math.max(1, tde.config.processingResValues[aProcessingMode]);
			origWidth = Math.max(1, (int)(origHeight*tde.config.outRatio));
		}
	}

	BufferedImage renderEye(int aProcessingMode, long aTime, int aEye) {
		int aHeight = Math.max(1, tde.config.processingResValues[aProcessingMode]);
		lastProcessedRatio = tde.config.outRatio;
		int aWidth = Math.max(1, (int)(aHeight*lastProcessedRatio));
		//Textured by images or videos: the monitor at the VHS height, enlarged by getWorkLeft() / getWorkRight()
		int aRH = model.renderHeight(aProcessingMode, aHeight);
		if(aRH != aHeight) {
			aHeight = aRH;
			aWidth = Math.max(1, (int)(aHeight*lastProcessedRatio));
		}
		Renderer3D.Options aOpt = new Renderer3D.Options();
		//Anti-aliasing for the rendering only
		aOpt.ss = Renderer3D.ssFor(aProcessingMode, aWidth, aHeight);
		aOpt.mode = aProcessingMode;
		aOpt.timeMS = aTime;
		//Its own turntable, plus the turn of a play alone from the media list
		double aSpin = model.spin*aTime/1000.0 + (libraryPlay() ? LIBRARY_SPIN_DEG_PER_S*aTime/1000.0 : 0);
		BufferedImage aBI = Renderer3D.render(model, aWidth, aHeight, aEye, aSpin, aOpt).image;
		if(aOpt.missing) {
			missing = true;
		}
		return aBI;
	}

	@Override
	public BufferedImage getWorkLeft(int aProcessingMode, long aTime) {
		if(workLeft == null) {
			workLeft = enlarged(loadedLeft, aProcessingMode);
		}
		return super.getWorkLeft(aProcessingMode, aTime);
	}

	@Override
	public BufferedImage getWorkRight(int aProcessingMode, long aTime) {
		if(workRight == null) {
			workRight = enlarged(loadedRight, aProcessingMode);
		}
		return super.getWorkRight(aProcessingMode, aTime);
	}

	/**
	 * An eye rendered below the height of the mode (Model3D.renderHeight()) enlarged bilinearly to it, else null
	 * (the usual work image)
	 */
	BufferedImage enlarged(BufferedImage aBI, int aProcessingMode) {
		int aH = Math.max(1, tde.config.processingResValues[aProcessingMode]);
		if(aBI == null || aBI.getHeight() >= aH) {
			return null;
		}
		int aW = Math.max(1, (int)Math.round(aBI.getWidth()*aH/(double)aBI.getHeight()));
		return Renderer3D.enlarge(aBI, aW, aH, tde.config.processingHdr[aProcessingMode]);
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
		thumbMissing = aOpt.missing;
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
