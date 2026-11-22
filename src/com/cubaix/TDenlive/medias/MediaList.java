package com.cubaix.TDenlive.medias;

import java.awt.Graphics2D;
import java.util.Vector;

import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.GC;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.graphics.RGB;
import org.eclipse.swt.graphics.Rectangle;

import com.cubaix.TDenlive.TDConfig;
import com.cubaix.TDenlive.TDenlive;
import com.cubaix.TDenlive.GUI.Drawable;
import com.cubaix.TDenlive.xml.XmlObject;
import com.cubaix.TDenlive.xml.XmlTag;

public class MediaList extends Drawable {
	static final String VIDEO_FILE_RE = ".*[.](mpg|mpeg|mp4|m4v|avi|mov|mkv|webm|flv|vob|ogv|ts|mts|m2s|m2ts|dv|divx|dvx|wmv)";
	static final String SVG_FILE_RE = ".*[.]svg";
	//3D scenes of Gaussians (3D Gaussian Splatting PLY), see SceneMedia
	public static final String SCENE_FILE_RE = ".*[.]ply";
	public static final String AUDIO_FILE_RE = ".*[.](mp3|wav|wave|ogg|oga|opus|flac|aac|m4a|wma|aif|aiff|ac3|mka)";
	public Vector<Media> mediaList = new Vector<Media>();
	//Trash buttons of the medias, where they were drawn last (control coordinates)
	java.util.Map<Media,Rectangle> trashRects = new java.util.IdentityHashMap<Media,Rectangle>();
	static final int TRASH_MARGIN = 4;
	//Edit buttons (pencil, left of the trash) of the medias made in an editor of the application
	java.util.Map<Media,Rectangle> editRects = new java.util.IdentityHashMap<Media,Rectangle>();
	//Mouse position over the list (hover frame of the trash buttons), -1 outside
	public int hoverX = -1;
	public int hoverY = -1;
	
	public MediaList(TDenlive aTDe) {
		super(aTDe);
	}
	
	public int openProject(Vector<XmlObject> aOs,int o) throws Exception{
		for(;o < aOs.size();o++) {
			XmlObject aO = aOs.elementAt(o);
			if(aO instanceof XmlTag) {
				XmlTag aT = (XmlTag)aO;
				if("/MediaList".equalsIgnoreCase(aT.tagName)) {
					return o;
				}
				if("ColorBck".equalsIgnoreCase(aT.tagName)) {
					ColorBck aCB = new ColorBck(tde);
					mediaList.add(aCB);
					o = aCB.openProject(aOs, o);
					try {
						aCB.reloadOrig(TDConfig.PROCESSING_MODE_RENDER, -1);
					}
					catch(Throwable t) {
						t.printStackTrace(System.err);
					}
				}
				if("ShapesMedia".equalsIgnoreCase(aT.tagName)) {
					ShapesMedia aSM = new ShapesMedia(tde);
					mediaList.add(aSM);
					o = aSM.openProject(aOs, o);
					try {
						aSM.reloadOrig(TDConfig.PROCESSING_MODE_RENDER, -1);
					}
					catch(Throwable t) {
						t.printStackTrace(System.err);
					}
				}
				if("Model3DMedia".equalsIgnoreCase(aT.tagName)) {
					Model3DMedia aMM = new Model3DMedia(tde);
					mediaList.add(aMM);
					o = aMM.openProject(aOs, o);
				}
				if("SceneMedia".equalsIgnoreCase(aT.tagName)) {
					SceneMedia aSM = new SceneMedia(tde);
					mediaList.add(aSM);
					o = aSM.openProject(aOs, o);
				}
				if("StereoImage".equalsIgnoreCase(aT.tagName)) {
					StereoImage aSI = new StereoImage(tde);
					mediaList.add(aSI);
					o = aSI.openProject(aOs, o);
				}
				if("StereoMovie".equalsIgnoreCase(aT.tagName)) {
					StereoMovie aSM = new StereoMovie(tde);
					mediaList.add(aSM);
					o = aSM.openProject(aOs, o);
				}
				if("AudioMedia".equalsIgnoreCase(aT.tagName)) {
					AudioMedia aAM = new AudioMedia(tde);
					mediaList.add(aAM);
					o = aAM.openProject(aOs, o);
				}
				if("StereoSvg".equalsIgnoreCase(aT.tagName)) {
					StereoSvg aSG = new StereoSvg(tde);
					mediaList.add(aSG);
					o = aSG.openProject(aOs, o);
				}
			}
		}
		return o;
	}
	
	public void saveProject(StringBuffer aSB) throws Exception {
		for(Media aM : mediaList) {
			aM.saveProject(aSB);
		}
	}

	public Media getMediaByUID(long aUID) {
		for(Media aM : mediaList) {
			if(aM.UID == aUID) {
				return aM;
			}
		}
		return null;
	}
	
	public Media getSelectedMedias(boolean aOrFirst) {
		Media aSelected = aOrFirst && mediaList.size() > 0 ? mediaList.elementAt(0) : null;
		for(Media aM : mediaList) {
			if(aM.selState > 0) {
				return aM;
			}
		}
		return aSelected;
	}
	
	public void loadImageLR(String aPathL,String aPathR,int aProcessingMode,long aTime) throws Exception {
		if(aPathL.toLowerCase().matches(SCENE_FILE_RE)) {
			loadScene(aPathL, null);
			return;
		}
		if(aPathL.toLowerCase().matches(AUDIO_FILE_RE)) {
			//A sound file, whatever the stereo format asked
			loadAudio(aPathL);
			return;
		}
		StereoImage aSI = null;
		if(aPathL.toLowerCase().matches(VIDEO_FILE_RE)) {
			aSI = new StereoMovie(tde);
		}
		else if(aPathL.toLowerCase().matches(SVG_FILE_RE)) {
			aSI = new StereoSvg(tde);
		}
		else {
			aSI = new StereoImage(tde);
		}
		aSI.loadImageLR(aPathL,aPathR,aProcessingMode,aTime);
		mediaList.add(aSI);
		tde.updateFirstMediaRatio();
	}

	public void loadImageX(String aPath,int aProcessingMode,long aTime) throws Exception {
		if(aPath.toLowerCase().matches(SCENE_FILE_RE)) {
			//A 3D scene, whatever the stereo format asked
			loadScene(aPath, null);
			return;
		}
		if(aPath.toLowerCase().matches(AUDIO_FILE_RE)) {
			//A sound file, whatever the stereo format asked
			loadAudio(aPath);
			return;
		}
		StereoImage aSI = null;
		if(aPath.toLowerCase().matches(VIDEO_FILE_RE)) {
			aSI = new StereoMovie(tde);
		}
		else if(aPath.toLowerCase().matches(SVG_FILE_RE)) {
			aSI = new StereoSvg(tde);
		}
		else {
			aSI = new StereoImage(tde);
		}
		aSI.loadImageX(aPath,aProcessingMode,aTime);
		mediaList.add(aSI);
		tde.updateFirstMediaRatio();
	}

	public void loadImageP(String aPath,int aProcessingMode,long aTime) throws Exception {
		if(aPath.toLowerCase().matches(SCENE_FILE_RE)) {
			//A 3D scene, whatever the stereo format asked
			loadScene(aPath, null);
			return;
		}
		if(aPath.toLowerCase().matches(AUDIO_FILE_RE)) {
			//A sound file, whatever the stereo format asked
			loadAudio(aPath);
			return;
		}
		StereoImage aSI = null;
		if(aPath.toLowerCase().matches(VIDEO_FILE_RE)) {
			aSI = new StereoMovie(tde);
		}
		else if(aPath.toLowerCase().matches(SVG_FILE_RE)) {
			aSI = new StereoSvg(tde);
		}
		else {
			aSI = new StereoImage(tde);
		}
		aSI.loadImageP(aPath,aProcessingMode,aTime);
		mediaList.add(aSI);
		tde.updateFirstMediaRatio();
	}

	public void loadImageMPO(String aPath) throws Exception {
		if(aPath.toLowerCase().matches(SCENE_FILE_RE)) {
			//A 3D scene, whatever the stereo format asked
			loadScene(aPath, null);
			return;
		}
		if(aPath.toLowerCase().matches(AUDIO_FILE_RE)) {
			//A sound file, whatever the stereo format asked
			loadAudio(aPath);
			return;
		}
		StereoImage aSI = null;
		if(aPath.toLowerCase().matches(VIDEO_FILE_RE)) {
			aSI = new StereoMovie(tde);
		}
		else {
			aSI = new StereoImage(tde);
		}
		aSI.loadImageMPO(aPath);
		mediaList.add(aSI);
		tde.updateFirstMediaRatio();
	}
	
	public void loadAudio(String aPath) {
		AudioMedia aAM = new AudioMedia(tde);
		aAM.load(aPath);
		mediaList.add(aAM);
		tde.updateFirstMediaRatio();
	}
	
	public void createColorBck(ColorBck.Fill aFill,int aProcessingMode) {
		ColorBck aCB = new ColorBck(tde);
		aCB.setFill(aFill);
		try {
			aCB.reloadOrig(aProcessingMode, -1);
		}
		catch(Throwable t) {
			t.printStackTrace(System.err);
		}
		mediaList.add(aCB);
		tde.updateFirstMediaRatio();
	}

	public void createShapes(Vector<com.cubaix.TDenlive.shapes.Shape> aShapes,int aProcessingMode) {
		ShapesMedia aSM = new ShapesMedia(tde);
		aSM.setShapes(aShapes);
		try {
			aSM.reloadOrig(aProcessingMode, -1);
		}
		catch(Throwable t) {
			t.printStackTrace(System.err);
		}
		mediaList.add(aSM);
		tde.updateFirstMediaRatio();
	}

	/**
	 * Add a 3D scene (PLY of Gaussians) with the view aCam (null: the viewpoint of its photo)
	 * @return the media
	 */
	public SceneMedia loadScene(String aPath,com.cubaix.TDenlive.model3d.StereoCam aCam) throws Exception {
		return loadScene(aPath, aCam, true);
	}

	/**
	 * Same, with the smart filling of the pixels not covered or not (model3d.SmartFill)
	 */
	public SceneMedia loadScene(String aPath,com.cubaix.TDenlive.model3d.StereoCam aCam,boolean aSmartFill) throws Exception {
		return loadScene(aPath, aCam, aSmartFill, com.cubaix.TDenlive.model3d.SmartFill.DEFAULT_COS);
	}

	/**
	 * Same, with the thresholding of the smart filling (|cos| of the triangles)
	 */
	public SceneMedia loadScene(String aPath,com.cubaix.TDenlive.model3d.StereoCam aCam,boolean aSmartFill,double aCos) throws Exception {
		SceneMedia aSM = new SceneMedia(tde);
		aSM.smartFill = aSmartFill;
		aSM.cosCut = aCos;
		aSM.cutView = tde.config.aiSceneCut;
		aSM.fillModel = com.cubaix.TDenlive.model3d.SmartFill.forProcessor(tde.config.aiSceneFill).id;
		aSM.interp = tde.config.aiSceneInterp;
		aSM.depthView = tde.config.aiSceneDepth;
		if(aCam != null) {
			aSM.cam = aCam.copy();
		}
		aSM.loadImageLR(aPath, aPath, TDConfig.PROCESSING_MODE_WORK, 0);
		mediaList.add(aSM);
		tde.updateFirstMediaRatio();
		return aSM;
	}

	public void createModel3D(com.cubaix.TDenlive.model3d.Model3D aModel) {
		Model3DMedia aMM = new Model3DMedia(tde);
		aMM.setModel(aModel);
		mediaList.add(aMM);
		tde.updateFirstMediaRatio();
	}

	/**
	 * Remove a media from the list (its clips must be removed from the time lines before, see
	 * TimeLineStack.releaseMedia())
	 */
	public void removeMedia(Media aMedia) {
		synchronized (mediaList) {
			int aIdx = mediaList.indexOf(aMedia);
			if(aIdx >= 0) {
				mediaList.remove(aIdx);
			}
		}
		synchronized (trashRects) {
			trashRects.clear();
			editRects.clear();
		}
		tde.selected.medias.remove(aMedia);
		tde.updateFirstMediaRatio();
	}

	/**
	 * Media whose trash button is at (aX,aY) (control coordinates), null if none
	 */
	public Media trashAt(int aX,int aY) {
		synchronized (trashRects) {
			for(java.util.Map.Entry<Media,Rectangle> aE : trashRects.entrySet()) {
				if(aE.getValue().contains(aX, aY) && mediaList.contains(aE.getKey())) {
					return aE.getKey();
				}
			}
		}
		return null;
	}

	/**
	 * Media whose edit button is at (aX,aY) (control coordinates), null if none
	 */
	public Media editAt(int aX,int aY) {
		synchronized (trashRects) {
			for(java.util.Map.Entry<Media,Rectangle> aE : editRects.entrySet()) {
				if(aE.getValue().contains(aX, aY) && mediaList.contains(aE.getKey())) {
					return aE.getKey();
				}
			}
		}
		return null;
	}

	/**
	 * Made in an editor of the application (shapes, 3D model): an edit button opens it again
	 */
	public static boolean isEditable(Media aM) {
		return aM instanceof ShapesMedia || aM instanceof Model3DMedia;
	}

	//Side of the badges drawn on the thumbnails
	static final int BADGE = 16;

	/**
	 * Kind of media: 0 image, 1 video, 2 sound, 3 shapes, 4 3D model
	 */
	static int kindOf(Media aM) {
		if(aM instanceof AudioMedia) {
			return 2;
		}
		if(aM instanceof ShapesMedia) {
			return 3;
		}
		if(aM instanceof Model3DMedia || aM instanceof SceneMedia) {
			return 4;
		}
		return aM instanceof StereoMovie ? 1 : 0;
	}

	/**
	 * A stereo media (two views), else a plain image / video (the same file for both views)
	 */
	static boolean isStereo(Media aM) {
		if(aM instanceof ShapesMedia || aM instanceof Model3DMedia || aM instanceof SceneMedia) {
			//Made in the application with depths
			return true;
		}
		if(aM instanceof ColorBck) {
			return false;
		}
		if(aM instanceof StereoImage) {
			StereoImage aSI = (StereoImage)aM;
			return aSI.origStereo != 0 || (aSI.pathR != null && !aSI.pathR.equals(aSI.pathL));
		}
		return false;
	}

	/**
	 * Badges at the bottom left of the thumbnail: the kind (image, video, sound, shapes and 3D model with the
	 * icons of their toolbar buttons), then, for the images and
	 * videos, 2D (plain) or 3D (stereo, anaglyph colors)
	 */
	void drawBadges(GC aGC,Media aM,int aX,int aRowY) {
		org.eclipse.swt.graphics.Image aThumb = aM.getThumbSwt();
		if(aThumb == null || aThumb.isDisposed()) {
			return;
		}
		Rectangle aTB = aThumb.getBounds();
		int aY = aRowY + Math.min(aTB.height, tde.config.thumbH) - BADGE - 2;
		int aBX = aX + 2;
		int aOldAA = aGC.getAntialias();
		int aOldAlpha = aGC.getAlpha();
		int aOldLW = aGC.getLineWidth();
		org.eclipse.swt.graphics.Font aOldFont = aGC.getFont();
		try {
			aGC.setAntialias(SWT.ON);
			badgeBack(aGC, aBX, aY, BADGE);
			aGC.setForeground(tde.gui.colorsSwt.WHITE);
			aGC.setBackground(tde.gui.colorsSwt.WHITE);
			aGC.setLineWidth(1);
			switch(kindOf(aM)) {
			case 3:
				//Icons of the buttons of the media list toolbar (MediaListControlGUI)
				aGC.drawImage(tde.gui.imgsSwt.getIcon("colorObjects.png"), aBX, aY);
				break;
			case 4:
				aGC.drawImage(tde.gui.imgsSwt.getIcon("cube3d.png"), aBX, aY);
				break;
			case 1:
				//Video camera: body and lens cone
				aGC.fillRoundRectangle(aBX+2, aY+5, 8, 7, 2, 2);
				aGC.fillPolygon(new int[] {aBX+10, aY+8, aBX+14, aY+5, aBX+14, aY+12, aBX+10, aY+9});
				break;
			case 2:
				//Two eighth notes joined by a beam
				aGC.fillOval(aBX+2, aY+10, 4, 3);
				aGC.fillOval(aBX+9, aY+9, 4, 3);
				aGC.setLineWidth(1);
				aGC.drawLine(aBX+5, aY+11, aBX+5, aY+3);
				aGC.drawLine(aBX+12, aY+10, aBX+12, aY+2);
				aGC.fillPolygon(new int[] {aBX+5, aY+3, aBX+12, aY+2, aBX+12, aY+4, aBX+5, aY+5});
				break;
			default:
				//Picture: frame, mountains and sun
				aGC.drawRectangle(aBX+2, aY+3, 11, 10);
				aGC.fillPolygon(new int[] {aBX+3, aY+13, aBX+7, aY+7, aBX+10, aY+10, aBX+11, aY+9, aBX+13, aY+13});
				aGC.fillOval(aBX+9, aY+5, 3, 3);
				break;
			}
			if(kindOf(aM) == 2) {
				return;
			}
			//2D / 3D
			boolean aStereo = isStereo(aM);
			String aText = aStereo ? "3D" : "2D";
			aGC.setFont(tde.gui.fontsSwt.robotoBold);
			Point aTP = aGC.stringExtent(aText);
			int aW = Math.max(BADGE, aTP.x+6);
			int aSX = aBX + BADGE + 2;
			badgeBack(aGC, aSX, aY, aW);
			int aTX = aSX + (aW-aTP.x)/2;
			int aTY = aY + (BADGE-aTP.y)/2;
			if(aStereo) {
				//Anaglyph fringes
				aGC.setForeground(tde.gui.colorsSwt.RED);
				aGC.drawString(aText, aTX-1, aTY, true);
				aGC.setForeground(tde.gui.colorsSwt.CYAN);
				aGC.drawString(aText, aTX+1, aTY, true);
			}
			aGC.setForeground(tde.gui.colorsSwt.WHITE);
			aGC.drawString(aText, aTX, aTY, true);
		}
		finally {
			aGC.setAlpha(aOldAlpha);
			aGC.setLineWidth(aOldLW);
			aGC.setAntialias(aOldAA);
			aGC.setFont(aOldFont);
		}
	}

	/**
	 * Dark translucent back of a badge
	 */
	void badgeBack(GC aGC,int aX,int aY,int aW) {
		aGC.setAlpha(170);
		aGC.setBackground(tde.gui.colorsSwt.BLACK);
		aGC.fillRoundRectangle(aX, aY, aW, BADGE, 5, 5);
		aGC.setAlpha(255);
	}

	public Rectangle drawSwt(GC aGC,int aX, int aY,Rectangle aClippingR) {
		int aW = 0;
		int aH = 0;
		org.eclipse.swt.graphics.Image aTrash = tde.gui.imgsSwt.getIcon("trash.png");
		Rectangle aTB = aTrash.getBounds();
		
		org.eclipse.swt.graphics.Image aPencil = tde.gui.imgsSwt.getIcon("pencil.png");
		synchronized (trashRects) {
			trashRects.clear();
			editRects.clear();
		}
		synchronized (mediaList) {
			for(Media aM : mediaList) {
				int aRowY = aY+aH;
				Rectangle aR = aM.drawSwt(aGC, aX, aY+aH,aClippingR);
				drawBadges(aGC, aM, aX, aRowY);
				//Trash button at the right of the visible part of the row
				Rectangle aTR = new Rectangle(aClippingR.x+aClippingR.width-aTB.width-TRASH_MARGIN, aRowY+TRASH_MARGIN, aTB.width, aTB.height);
				aGC.drawImage(aTrash, aTR.x, aTR.y);
				aTR = new Rectangle(aTR.x-TRASH_MARGIN/2, aTR.y-TRASH_MARGIN/2, aTR.width+TRASH_MARGIN, aTR.height+TRASH_MARGIN);
				if(aTR.contains(hoverX, hoverY)) {
					//Hover frame of the button
					aGC.setForeground(tde.gui.colorsSwt.LIGHTGRAY);
					aGC.setLineWidth(1);
					aGC.drawRectangle(aTR.x, aTR.y, aTR.width-1, aTR.height-1);
				}
				synchronized (trashRects) {
					trashRects.put(aM, aTR);
				}
				if(isEditable(aM)) {
					//Pencil left of the trash
					Rectangle aER = new Rectangle(aTR.x-TRASH_MARGIN/2-aTB.width, aRowY+TRASH_MARGIN, aTB.width, aTB.height);
					aGC.drawImage(aPencil, aER.x, aER.y);
					aER = new Rectangle(aER.x-TRASH_MARGIN/2, aER.y-TRASH_MARGIN/2, aER.width+TRASH_MARGIN, aER.height+TRASH_MARGIN);
					if(aER.contains(hoverX, hoverY)) {
						aGC.setForeground(tde.gui.colorsSwt.LIGHTGRAY);
						aGC.setLineWidth(1);
						aGC.drawRectangle(aER.x, aER.y, aER.width-1, aER.height-1);
					}
					synchronized (trashRects) {
						editRects.put(aM, aER);
					}
				}
				aH += aR.height;
				if(aW < aR.width) {
					aW = aR.width;
				}
				aGC.setBackground(tde.gui.colorsSwt.WHITE);
				aGC.fillRectangle(0,aY+aH,aClippingR.width,3);
				aGC.setBackground(tde.gui.colorsSwt.GRAY);
				aGC.fillRectangle(3,aY+aH+1,aClippingR.width-6,1);
				aH += 3;
			}
		}
		return bounds = new Rectangle(aX, aY, aW, aH);
	}

	@Override
	public Rectangle drawAwt(Graphics2D aGC, int aX, int aY, Rectangle aClippingR) {
		return null;
	}

	@Override
	public boolean selectWidget(int aX, int aY) {
		return false;
	}

	@Override
	public void select(int aX, int aY,boolean aOutUnselect) {
		tde.selected.medias.clear();
		for(Media aM : mediaList) {
			aM.select(aX, aY,aOutUnselect);
		}
	}
}
