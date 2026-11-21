package com.cubaix.TDenlive.depth;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import com.cubaix.TDenlive.TDenlive;
import com.cubaix.TDenlive.medias.Media;
import com.cubaix.TDenlive.medias.StereoImage;
import com.cubaix.TDenlive.medias.StereoMovie;
import com.cubaix.TDenlive.model3d.GaussianScene;

/**
 * 3D scenes of Gaussians computed by a model (ai.SceneGenerator.Model: Flash3D, Depth Pro) for the processor Scene3D
 * (see GUI.SceneConvertGUI), from the left view of the media as it is shown (rotation included), next to its file
 * (else in the work folder of the project when that folder can't be written), like the depth maps
 * (ComputedDepthmap); the id of the model is in the name, so that the scenes of each model are kept:
 * - an image: <name>_3eScene_<model>[_r<rotation>].ply (detail Fine, view dependent colors);
 * - a video: the folder <name>_3eScene_<model>[_r<rotation>]/ with one compact scene per frame (000000.3gs..., detail
 * Standard, GaussianScene.COMPACT_MAGIC) at the rate of INFO ("fps <rate>" and "frames <count>" lines, written
 * before the frames: a frame not computed yet shows the 2D image).
 * The scenes of Flash3D made before the model was in the name (<name>_3eScene[_r<rotation>]) are still found
 * (LEGACY_MODEL).
 */
public class ComputedScenes {
	public static final String SUFFIX = "_3eScene";
	public static final String INFO = "scene.txt";
	//Model of the scenes named without their model
	public static final String LEGACY_MODEL = "Flash3D";

	public boolean video;
	//The PLY (image) or the folder of the frames (video)
	public File file;
	public double fps = 15;
	public int frames = 0;

	/**
	 * Media whose scenes can be computed: an image or a video of a file (not made in the application)
	 */
	public static boolean isConvertible(Media aM) {
		return DepthConverter.isConvertible(aM);
	}

	static String rotation(StereoImage aSI) {
		String aR = aSI.rotateL == null ? "0" : aSI.rotateL.trim();
		return aR.equals("0") || aR.isEmpty() ? "" : "_r"+aR;
	}

	/**
	 * File (image) or folder (video) of the scenes of aSI by the model aModel (id; null: the name without model) in
	 * aDir (null: next to its file)
	 */
	public static File path(StereoImage aSI,String aModel,String aDir) {
		File aIn = new File(aSI.getPathL());
		String aBase = aIn.getName().replaceAll("[.][^.]*$", "")+SUFFIX+(aModel == null ? "" : "_"+aModel)+rotation(aSI);
		File aDirF = aDir == null ? aIn.getAbsoluteFile().getParentFile() : new File(aDir);
		return new File(aDirF, aBase+(aSI instanceof StereoMovie ? "" : ".ply"));
	}

	/**
	 * Where the scenes of aSI are written: next to its file if its folder can be written, else the work folder
	 */
	public static File writePath(TDenlive aTDe,StereoImage aSI,String aModel) {
		File aDir = new File(aSI.getPathL()).getAbsoluteFile().getParentFile();
		return path(aSI, aModel, aDir != null && aDir.canWrite() ? null : aTDe.getWorkCacheDir());
	}

	/**
	 * The scenes of a media by the model aModel (id; all or some frames for a video), null if none
	 */
	public static ComputedScenes find(TDenlive aTDe,Media aM,String aModel) {
		if(!isConvertible(aM)) {
			return null;
		}
		StereoImage aSI = (StereoImage)aM;
		boolean aVideo = aSI instanceof StereoMovie;
		for(String aName : LEGACY_MODEL.equals(aModel) ? new String[] {aModel, null} : new String[] {aModel}) {
			for(String aDir : new String[] {null, aTDe.getWorkCacheDir()}) {
				File aF = path(aSI, aName, aDir);
				if(!aVideo && aF.isFile()) {
					ComputedScenes aCS = new ComputedScenes();
					aCS.file = aF;
					return aCS;
				}
				if(aVideo && new File(aF, INFO).isFile()) {
					ComputedScenes aCS = new ComputedScenes();
					aCS.video = true;
					aCS.file = aF;
					aCS.readInfo();
					return aCS;
				}
			}
		}
		return null;
	}

	void readInfo() {
		try {
			for(String aL : new String(Files.readAllBytes(new File(file, INFO).toPath()), StandardCharsets.UTF_8).split("\\R")) {
				String[] aT = aL.trim().split("\\s+");
				if(aT.length == 2 && aT[0].equals("fps")) {
					fps = Double.parseDouble(aT[1]);
				}
				if(aT.length == 2 && aT[0].equals("frames")) {
					frames = Integer.parseInt(aT[1]);
				}
			}
		}
		catch(Throwable t) {
			t.printStackTrace(System.err);
		}
	}

	public static void writeInfo(File aDir,double aFps,int aFrames) throws Exception {
		aDir.mkdirs();
		Files.write(new File(aDir, INFO).toPath(), ("fps "+aFps+"\nframes "+aFrames+"\n").getBytes(StandardCharsets.UTF_8));
	}

	public static File frameFile(File aDir,int aFrame) {
		return new File(aDir, String.format("%06d", aFrame)+GaussianScene.COMPACT_EXT);
	}

	/**
	 * Frames already computed (video)
	 */
	public int framesDone() {
		if(!video) {
			return file.isFile() ? 1 : 0;
		}
		int aN = 0;
		for(int f = 0;f < frames;f++) {
			if(frameFile(file, f).isFile()) {
				aN++;
			}
		}
		return aN;
	}

	/**
	 * Scene at a media time (ms), null if not computed (yet)
	 */
	public GaussianScene sceneAt(long aMediaMS) {
		if(!video) {
			return GaussianScene.cached(file);
		}
		if(frames <= 0) {
			return null;
		}
		int aF = (int)Math.max(0, Math.min(frames-1, Math.round(aMediaMS*fps/1000.0)));
		File aFF = frameFile(file, aF);
		return aFF.isFile() ? GaussianScene.cached(aFF) : null;
	}
}
