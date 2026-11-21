package com.cubaix.TDenlive.medias;

import java.awt.image.BufferedImage;
import java.io.File;
import java.util.Vector;

import com.cubaix.TDenlive.TDConfig;
import com.cubaix.TDenlive.TDenlive;
import com.cubaix.TDenlive.model3d.GaussianScene;
import com.cubaix.TDenlive.model3d.StereoCam;
import com.cubaix.TDenlive.utils.ImageUtils;
import com.cubaix.TDenlive.xml.XmlObject;
import com.cubaix.TDenlive.xml.XmlTag;

/**
 * 3D scene of Gaussians (a .ply of the 3D Gaussian Splatting format, such as those made from a photo by the AI
 * panel, ai.SceneGenerator): rendered for each eye by a pair of stereo cameras (StereoCam), at the ratio of the photo
 * of the scene. Its cameras (cam, <sceneCam>) are the view of the media; its clips move them with keys (mandatory
 * processor processors.SceneCamera, starting from this view). The file is pathL (gathered, packed, renamed as the
 * files of the other medias).
 */
public class SceneMedia extends StereoImage {
	public StereoCam cam = new StereoCam();
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
	//Gaussian blur of the depth of the scene in pixels of an image of GaussianScene.DVD_LINES lines <depthBlur>
	public double depthBlur = GaussianScene.DEFAULT_BLUR;
	GaussianScene scene = null;

	public SceneMedia(TDenlive aTDe) {
		super(aTDe);
	}

	/**
	 * The scene of the file (read once, null if it can't be read)
	 */
	public synchronized GaussianScene getScene() {
		if(scene == null && pathL != null) {
			scene = GaussianScene.cached(new File(pathL));
		}
		return scene;
	}

	@Override
	public int openProject(Vector<XmlObject> aOs, int o) throws Exception {
		return super.openProject(aOs, o, "SceneMedia");
	}

	@Override
	public int openProjectSub(Vector<XmlObject> aOs, int o) throws Exception {
		XmlObject aO = aOs.elementAt(o);
		if(aO instanceof XmlTag && "sceneCam".equalsIgnoreCase(((XmlTag)aO).tagName)) {
			cam = StereoCam.load(aOs.elementAt(++o).text);
		}
		if(aO instanceof XmlTag && "depthView".equalsIgnoreCase(((XmlTag)aO).tagName)) {
			depthView = "true".equalsIgnoreCase(aOs.elementAt(++o).text.trim());
		}
		if(aO instanceof XmlTag && "interp".equalsIgnoreCase(((XmlTag)aO).tagName)) {
			interp = !"false".equalsIgnoreCase(aOs.elementAt(++o).text.trim());
		}
		if(aO instanceof XmlTag && "cutView".equalsIgnoreCase(((XmlTag)aO).tagName)) {
			cutView = "true".equalsIgnoreCase(aOs.elementAt(++o).text.trim());
		}
		if(aO instanceof XmlTag && "cosCut".equalsIgnoreCase(((XmlTag)aO).tagName)) {
			cosCut = Double.parseDouble(aOs.elementAt(++o).text.trim());
		}
		if(aO instanceof XmlTag && "fillModel".equalsIgnoreCase(((XmlTag)aO).tagName)) {
			fillModel = com.cubaix.TDenlive.model3d.SmartFill.forProcessor(aOs.elementAt(++o).text.trim()).id;
		}
		if(aO instanceof XmlTag && "depthBlur".equalsIgnoreCase(((XmlTag)aO).tagName)) {
			depthBlur = Math.max(0, Double.parseDouble(aOs.elementAt(++o).text.trim()));
		}
		if(aO instanceof XmlTag && "smartFill".equalsIgnoreCase(((XmlTag)aO).tagName)) {
			smartFill = !"false".equalsIgnoreCase(aOs.elementAt(++o).text.trim());
		}
		return o;
	}

	@Override
	public void saveProject(StringBuffer aSB) throws Exception {
		super.saveProject(aSB, "SceneMedia");
	}

	@Override
	void saveProjectSub(StringBuffer aSB) {
		aSB.append("			<sceneCam>"+cam.save()+"</sceneCam>\n");
		aSB.append("			<interp>"+interp+"</interp>\n");
		aSB.append("			<depthView>" + depthView + "</depthView>\n");
		aSB.append("			<smartFill>"+smartFill+"</smartFill>\n");
		aSB.append("			<cosCut>"+cosCut+"</cosCut>\n");
		aSB.append("			<cutView>"+cutView+"</cutView>\n");
		aSB.append("			<fillModel>"+fillModel+"</fillModel>\n");
		aSB.append("			<depthBlur>"+depthBlur+"</depthBlur>\n");
	}

	@Override
	public Vector<String> getFilePaths() {
		Vector<String> aPs = new Vector<String>();
		if(pathL != null) {
			aPs.add(pathL);
		}
		return aPs;
	}

	/**
	 * Size of the images at a processing mode: its height, the ratio of the photo of the scene
	 */
	int[] size(int aProcessingMode) {
		GaussianScene aS = getScene();
		int aH = Math.max(1, tde.config.processingResValues[aProcessingMode]);
		double aR = aS != null && aS.width > 0 && aS.height > 0 ? aS.width/(double)aS.height : 16/9.0;
		return new int[] {Math.max(1, (int)Math.round(aH*aR)), aH};
	}

	/**
	 * Image of one eye with the cameras aCam at a processing mode (transparent when the scene can't be read)
	 */
	public BufferedImage renderEye(int aProcessingMode,StereoCam aCam,int aEye) {
		int[] aSz = size(aProcessingMode);
		GaussianScene aS = getScene();
		if(aS == null) {
			return ImageUtils.createImage(aSz[0], aSz[1]);
		}
		aS = aS.smoothedDvd(depthBlur);
		return com.cubaix.TDenlive.model3d.SmartFill.render(aS, aCam, aEye, aSz[0], aSz[1], depthView, interp, cutView, smartFill, cosCut, fillModel, tde.tdeDir);
	}

	//Height of the RENDER resolution the views (loadedLeft / loadedRight) were computed at
	int renderedRes = -1;

	/**
	 * The views are computed once at the resolution of the final rendering (as the original of an image file: the
	 * size shown in the media list), the images of the other modes are reduced from them
	 */
	@Override
	public synchronized void reBuild(int aProcessingMode, long aTime) {
		if(loadedLeft == null || loadedRight == null || renderedRes != tde.config.processingResValues[TDConfig.PROCESSING_MODE_RENDER]) {
			try {
				render();
			}
			catch(Throwable t) {
				t.printStackTrace(System.err);
			}
			workLeft = workRight = null;
			lastProcessedRes = -1;
		}
		if(workLeft != null && workRight != null
				&& lastProcessedRes == tde.config.processingResValues[aProcessingMode]
						&& lastProcessedHdr == tde.config.processingHdr[aProcessingMode]) {
			return;
		}
		workLeft = workRight = null;
		getWorkLeft(aProcessingMode, aTime);
		getWorkRight(aProcessingMode, aTime);
		lastProcessedRes = tde.config.processingResValues[aProcessingMode];
		lastProcessedHdr = tde.config.processingHdr[aProcessingMode];
	}

	/**
	 * Both eyes at the resolution of the final rendering
	 */
	void render() {
		renderedRes = tde.config.processingResValues[TDConfig.PROCESSING_MODE_RENDER];
		loadedLeft = renderEye(TDConfig.PROCESSING_MODE_RENDER, cam, -1);
		loadedRight = renderEye(TDConfig.PROCESSING_MODE_RENDER, cam, 1);
		origWidth = loadedLeft.getWidth();
		origHeight = loadedLeft.getHeight();
		//Ratio of the photo of the scene (display, "1st media" output ratio)
		origRes = origHeight;
		origRatio = origWidth/(double)origHeight;
	}

	/**
	 * New view of the media (its clips start from it)
	 */
	public synchronized void setCam(StereoCam aCam) {
		cam = aCam.copy();
		workLeft = workRight = null;
		lastProcessedRes = -1;
		try {
			reloadOrig(TDConfig.PROCESSING_MODE_WORK, 0);
		}
		catch(Throwable t) {
			t.printStackTrace(System.err);
		}
	}

	@Override
	BufferedImage loadImage(String aPath, int aProcessingMode, long aTime, boolean aIsLeft) throws Exception {
		return renderEye(TDConfig.PROCESSING_MODE_RENDER, cam, aIsLeft ? -1 : 1);
	}

	@Override
	synchronized void reloadOrig(int aProcessingMode, long aTime) throws Exception {
		origStereo = 0;
		pathR = pathL;
		render();
		workLeft = workRight = null;
		lastProcessedRes = -1;
		fileName = new File(pathL).getName();
		buildThumb();
	}

	@Override
	public void buildThumb() {
		int aH = Math.max(1, tde.config.thumbH);
		GaussianScene aS = getScene();
		double aR = aS != null && aS.width > 0 && aS.height > 0 ? aS.width/(double)aS.height : 16/9.0;
		int aW = Math.max(1, (int)(aH*aR));
		BufferedImage aThumb = aS == null ? ImageUtils.createImage(aW, aH) : cam.render(aS.smoothedDvd(depthBlur), 0, aW, aH, interp, depthView);
		setThumbAwt(aThumb);
		setThumbSwt(ImageUtils.imageToSwt(tde.gui.display, aThumb, false));
	}

	@Override
	public void loadImageLR(String aPathL, String aPathR, int aProcessingMode, long aTime) throws Exception {
		pathL = findPath(aPathL);
		scene = null;
		if(getScene() == null) {
			throw new Exception("Not a 3D scene: "+aPathL);
		}
		reloadOrig(aProcessingMode, aTime);
	}

	@Override
	public void loadImageX(String aPath, int aProcessingMode, long aTime) throws Exception {
		loadImageLR(aPath, aPath, aProcessingMode, aTime);
	}

	@Override
	public void loadImageP(String aPath, int aProcessingMode, long aTime) throws Exception {
		loadImageLR(aPath, aPath, aProcessingMode, aTime);
	}
}
