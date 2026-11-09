package com.cubaix.TDenlive.depth;

import java.io.File;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.Vector;

import com.cubaix.TDenlive.TDenlive;
import com.cubaix.TDenlive.medias.Clip;
import com.cubaix.TDenlive.medias.Media;
import com.cubaix.TDenlive.medias.StereoImage;
import com.cubaix.TDenlive.medias.StereoMovie;
import com.cubaix.TDenlive.utils.StringUtils;
import com.cubaix.TDenlive.xml.XmlObject;

/**
 * Depth maps computed for a clip (see DepthConverter), owned by its Depthmap / DepthmapStereo processor.
 * One depth map file per source file, next to it with the same name + SUFFIX (.png for an image, .mp4 without
 * sound for a video), in the same stereo layout (a side by side source gives a side by side depth map); in the
 * work folder of the project when the folder of the source can't be written. The files are found back from the
 * media of the clip: a project only saves <computedDepth>true</computedDepth>, and a packed project gets them
 * next to its medias.
 * They are played by a media not in the media list (a video gets its work copy like any other), given to the clip
 * as a paired clip (Clip.addPairedClip()) with the orientation of the clip media (Clip.followsOrientation).
 */
public class ComputedDepthmap {
	public static final String SUFFIX = "_3eDepthMap";
	//Medias already loaded, by files: an undo or a duplicated clip reuses them
	static HashMap<String,StereoImage> medias = new HashMap<String,StereoImage>();

	//Files of the left and right views (the same one for a single 2D or side by side file)
	public String[] files;
	//Paired clip giving the depth maps (getWorkLeft / getWorkRight)
	public Clip clip;
	Clip target;

	/**
	 * Depth map file of a source file, in aDir (null: next to the source)
	 */
	public static String depthPath(String aSrc,String aDir,boolean aVideo) {
		File aIn = new File(aSrc);
		String aBase = aIn.getName().replaceAll("[.][^.]*$", "");
		File aDirF = aDir == null ? aIn.getAbsoluteFile().getParentFile() : new File(aDir);
		return new File(aDirF, aBase+SUFFIX+(aVideo ? ".mp4" : ".png")).getPath();
	}

	/**
	 * Folder where the depth map of a source is written: its folder if it can be written, else the work folder
	 */
	public static String writeDir(String aWorkDir,String aSrc) {
		File aDir = new File(aSrc).getAbsoluteFile().getParentFile();
		return aDir != null && aDir.canWrite() ? aDir.getPath() : aWorkDir;
	}

	/**
	 * The depth map of a source file: next to it, else in the work folder; null if none
	 */
	static String findDepth(TDenlive aTDe,String aSrc,boolean aVideo) {
		for(String aDir : new String[] {null, aTDe.getWorkCacheDir()}) {
			String aP = depthPath(aSrc, aDir, aVideo);
			if(new File(aP).isFile()) {
				return aP;
			}
		}
		return null;
	}

	/**
	 * Source files of a media: one, or the left and right ones
	 */
	public static String[] sources(StereoImage aSI) {
		if(aSI.origStereo == 0 && aSI.getPathR() != null && !aSI.getPathR().equals(aSI.getPathL())) {
			return new String[] {aSI.getPathL(), aSI.getPathR()};
		}
		return new String[] {aSI.getPathL()};
	}

	/**
	 * Depth map files of the media {left, right}, null if one is missing
	 */
	public static String[] find(TDenlive aTDe,StereoImage aSI) {
		boolean aVideo = aSI instanceof StereoMovie;
		String[] aSrcs = sources(aSI);
		String aL = findDepth(aTDe, aSrcs[0], aVideo);
		String aR = aSrcs.length > 1 ? findDepth(aTDe, aSrcs[1], aVideo) : aL;
		return aL == null || aR == null ? null : new String[] {aL, aR};
	}

	/**
	 * Media of the depth map files, in the stereo layout of the source (loaded once, may be called from a worker thread)
	 */
	public static StereoImage loadMedia(TDenlive aTDe,String[] aFiles,int aStereo) throws Exception {
		String aKey = aFiles[0]+"|"+aFiles[1]+"|"+aStereo;
		synchronized (medias) {
			StereoImage aSI = medias.get(aKey);
			if(aSI == null) {
				if(aFiles[0].toLowerCase().endsWith(".mp4")) {
					StereoMovie aSM = new StereoMovie(aTDe);
					//The sound is the one of the clip
					aSM.noSound = true;
					aSI = aSM;
				}
				else {
					aSI = new StereoImage(aTDe);
				}
				if(aStereo == 1) {
					aSI.loadImageX(aFiles[0], com.cubaix.TDenlive.TDConfig.PROCESSING_MODE_RENDER, 0);
				}
				else if(aStereo == 2) {
					aSI.loadImageP(aFiles[0], com.cubaix.TDenlive.TDConfig.PROCESSING_MODE_RENDER, 0);
				}
				else {
					aSI.loadImageLR(aFiles[0], aFiles[1], com.cubaix.TDenlive.TDConfig.PROCESSING_MODE_RENDER, 0);
				}
				medias.put(aKey, aSI);
			}
			return aSI;
		}
	}

	/**
	 * Files rewritten (computed again): their medias and video work copies are out of date
	 */
	public static void forget(String aWorkDir,String[] aFiles) {
		synchronized (medias) {
			medias.keySet().removeIf(aK -> aK.contains(aFiles[0]) || aK.contains(aFiles[1]));
		}
		for(String aF : aFiles) {
			if(aF.toLowerCase().endsWith(".mp4")) {
				new File(StringUtils.path2workingcopy(aF, aWorkDir)).delete();
			}
		}
	}

	/**
	 * Give the depth maps of the media of aTarget (if computed) to aTarget as a paired clip
	 * @return null if there is no depth map file for its media
	 */
	public static ComputedDepthmap attach(TDenlive aTDe,Clip aTarget) throws Exception {
		Media aM = aTarget.getMedia();
		if(!DepthConverter.isConvertible(aM)) {
			return null;
		}
		StereoImage aSI = (StereoImage)aM;
		String[] aFiles = find(aTDe, aSI);
		if(aFiles == null) {
			return null;
		}
		ComputedDepthmap aCD = new ComputedDepthmap();
		aCD.files = aFiles;
		aCD.target = aTarget;
		aCD.clip = aTarget.addPairedClip(loadMedia(aTDe, aFiles, aSI.origStereo), 1);
		aCD.clip.followsOrientation = true;
		return aCD;
	}

	/**
	 * Remove the paired clip from the clip (processor removed or depth maps replaced)
	 */
	public void detach() {
		if(target != null && clip != null) {
			target.removePairedClip(clip);
		}
		clip = null;
	}

	public void save(StringBuffer aSB,String aIndent) {
		aSB.append(aIndent+"<computedDepth>true</computedDepth>\n");
	}

	/**
	 * Read by the openProject() of the processors: true if the tag at o is ours, aHas[0] = its value
	 */
	public static boolean read(Vector<XmlObject> aOs,int o,String aTagName,boolean[] aHas) {
		if("computedDepth".equalsIgnoreCase(aTagName)) {
			aHas[0] = Boolean.parseBoolean(aOs.elementAt(o+1).text.trim());
			return true;
		}
		return false;
	}

	/**
	 * Copy the files that are in a work folder into another one (save as)
	 */
	public void copyFromWorkDir(String aFromDir,String aToDir) {
		for(String aPath : files) {
			try {
				File aFrom = new File(aPath);
				File aTo = new File(aToDir, aFrom.getName());
				if(aFrom.isFile() && !aTo.exists()
						&& aFrom.getAbsoluteFile().getParentFile().equals(new File(aFromDir).getAbsoluteFile())) {
					aTo.getAbsoluteFile().getParentFile().mkdirs();
					Files.copy(aFrom.toPath(), aTo.toPath());
				}
			}
			catch(Throwable t) {
				t.printStackTrace(System.err);
			}
		}
	}
}
