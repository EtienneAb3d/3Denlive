package com.cubaix.TDenlive.processors;

import java.awt.image.BufferedImage;

import com.cubaix.TDenlive.TDenlive;
import com.cubaix.TDenlive.depth.ComputedScenes;
import com.cubaix.TDenlive.medias.Media;
import com.cubaix.TDenlive.model3d.GaussianScene;

/**
 * 2D => 3D scene ("Stereo" folder of the library): the image, or each image of a video, turned into a 3D scene of
 * Gaussians by Depth Pro or Flash3D (model of the processor: sceneModel, precomputed, see depth.ComputedScenes and GUI.SceneConvertGUI), seen by a pair of stereo
 * cameras moved with keys in each image (SceneCamKeys: relative to the camera of the image, the first key at its
 * viewpoint; spacing and screen plane automatic until set). One depth processor per clip (Clip.isDepthProcessor():
 * first after the head processors, unmovable). An image whose scene is not computed (yet) stays 2D.
 */
public class Scene3D extends SceneCamKeys {
	//Scenes found for the media (looked for again after a computation, or every few seconds while missing)
	ComputedScenes computed = null;
	long lastLookMS = 0;

	//Model computing the scenes (ai.SceneGenerator.Model id), that of the AI panel when the processor is made,
	//<sceneModel>; the scenes of each model are kept (ComputedScenes: its id in their name)
	public String sceneModel;

	public Scene3D(TDenlive aTDe, Media aTargetMedia) {
		super(aTDe, aTargetMedia);
		isUnmovable = true;
		sceneModel = com.cubaix.TDenlive.ai.SceneGenerator.modelById(aTDe.config.aiSceneModel).id;
		fillModel = com.cubaix.TDenlive.model3d.SmartFill.forProcessor(aTDe.config.aiSceneFill).id;
	}

	@Override
	int openProjectSub(java.util.Vector<com.cubaix.TDenlive.xml.XmlObject> aOs, int o, com.cubaix.TDenlive.xml.XmlTag aT) {
		if("sceneModel".equalsIgnoreCase(aT.tagName)) {
			sceneModel = com.cubaix.TDenlive.ai.SceneGenerator.modelById(aOs.elementAt(++o).text.trim()).id;
		}
		return o;
	}

	@Override
	void saveProjectSub(StringBuffer aSB) {
		aSB.append("							<sceneModel>" + sceneModel + "</sceneModel>\n");
	}

	@Override
	public String getClassName() {return "Scene3D";}

	@Override
	void setDescr() {
		name = tde.gui.lngs.get("Processor.Scene3D");
		icon = "rhino.gif";
	}

	/**
	 * The scenes of the media of the clip, null if none
	 */
	public synchronized ComputedScenes computed() {
		long aNow = System.currentTimeMillis();
		if(computed == null && aNow-lastLookMS > 2000) {
			lastLookMS = aNow;
			computed = ComputedScenes.find(tde, getClip().getMedia(), sceneModel);
		}
		return computed;
	}

	/**
	 * Scenes computed again (or in progress): looked for at once
	 */
	public synchronized void scenesChanged() {
		computed = null;
		lastLookMS = 0;
	}

	@Override
	public GaussianScene sceneAt(long aTimePos) {
		ComputedScenes aCS = computed();
		if(aCS == null) {
			return null;
		}
		return smooth(aCS.sceneAt(Math.max(0, getClip().mediaTimeMS(aTimePos))));
	}

	/**
	 * Spacing and screen plane of the first key set from the scene when they are automatic (the same for all the
	 * images of a video, so that its depth does not change from one image to the next)
	 */
	public void fixAutoCam() {
		GaussianScene aS = sceneAt(getClip().getStartTimeMS());
		if(aS == null) {
			return;
		}
		synchronized (keys) {
			for(Key aK : keys) {
				if(aK.cam.screen <= 0) {
					aK.cam.screen = aK.cam.screenFor(aS);
				}
				if(aK.cam.base < 0) {
					aK.cam.base = aK.cam.baseFor(aS);
				}
			}
		}
	}

	@Override
	public void process2Awt(Media aMedia, int aProcessingMode, long aTime) {
		try {
			BufferedImage aBIL = aMedia.getWorkLeft(aProcessingMode, aTime);
			if(aBIL == null) {
				return;
			}
			renderInto(aMedia, aProcessingMode, aTime, aBIL.getWidth(), aBIL.getHeight());
		}
		catch(Throwable t) {
			t.printStackTrace(System.err);
		}
	}
}
