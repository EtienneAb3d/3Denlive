package com.cubaix.TDenlive.processors;

import java.awt.image.BufferedImage;

import com.cubaix.TDenlive.TDenlive;
import com.cubaix.TDenlive.medias.Media;
import com.cubaix.TDenlive.medias.SceneMedia;
import com.cubaix.TDenlive.model3d.GaussianScene;

/**
 * Mandatory processor of the clips of a SceneMedia (undeletable, unmovable, right after the TimeController): moves
 * the pair of stereo cameras in the scene with keys, the first key at the view of the media. When the cameras at
 * the time are those of the media, its images are kept.
 */
public class SceneCamera extends SceneCamKeys {
	public SceneCamera(TDenlive aTDe, Media aTargetMedia) {
		super(aTDe, aTargetMedia);
		isUndeletable = true;
		isUnmovable = true;
		isDeactivable = false;
		SceneMedia aSM = sceneMedia();
		if(aSM != null) {
			setFirstCam(aSM.cam);
			smartFill = aSM.smartFill;
			interp = aSM.interp;
			depthView = aSM.depthView;
			cosCut = aSM.cosCut;
			cutView = aSM.cutView;
			fillModel = aSM.fillModel;
			depthBlur = aSM.depthBlur;
		}
	}

	@Override
	public String getClassName() {return "SceneCamera";}

	@Override
	void setDescr() {
		name = tde.gui.lngs.get("Processor.SceneCamera");
		icon = "cube3d.png";
	}

	SceneMedia sceneMedia() {
		Media aM = targetMedia instanceof com.cubaix.TDenlive.medias.Clip ? getClip().getMedia() : null;
		return aM instanceof SceneMedia ? (SceneMedia)aM : null;
	}

	@Override
	public GaussianScene sceneAt(long aTimePos) {
		SceneMedia aSM = sceneMedia();
		return aSM == null ? null : smooth(aSM.getScene());
	}

	@Override
	public void process2Awt(Media aMedia, int aProcessingMode, long aTime) {
		SceneMedia aSM = sceneMedia();
		if(aSM == null || (camAt(aTime).sameAs(aSM.cam) && smartFill == aSM.smartFill && interp == aSM.interp && depthView == aSM.depthView && cosCut == aSM.cosCut && cutView == aSM.cutView && fillModel.equals(aSM.fillModel) && depthBlur == aSM.depthBlur && pairCarry && prevCarry)) {
			//The images of the media
			return;
		}
		try {
			BufferedImage aBIL = aMedia.getWorkLeft(aProcessingMode, aTime);
			int aH = aBIL != null ? aBIL.getHeight() : tde.config.processingResValues[aProcessingMode];
			int aW = aBIL != null ? aBIL.getWidth() : (int)(aH*16/9.0);
			renderInto(aMedia, aProcessingMode, aTime, aW, aH);
		}
		catch(Throwable t) {
			t.printStackTrace(System.err);
		}
	}
}
