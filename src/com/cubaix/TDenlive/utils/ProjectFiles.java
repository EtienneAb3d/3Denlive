package com.cubaix.TDenlive.utils;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Vector;

import com.cubaix.TDenlive.TDenlive;
import com.cubaix.TDenlive.depth.ComputedDepthmap;
import com.cubaix.TDenlive.ffmpeg.AudioReader;
import com.cubaix.TDenlive.medias.Media;

/**
 * Media files and the project folder:
 * - a media added from outside the project folder can be copied into it (importFile());
 * - when the project is saved into another folder, the media files outside it can be moved or copied
 * into it (relocate()).
 * A file goes with what is named after it: its computed depth maps (next to it), its video work copy
 * and its decoded sound (in the WorkCache, named from the path), so that nothing is computed again.
 * The files go to the root of the project folder (a name already taken gets _2, _3...).
 */
public class ProjectFiles {
	//Folder of the project file being written (TDenlive.projectXml(String)): the paths of the files inside it are
	//written relative to it (forSave()); null otherwise (history, signatures, packing: absolute paths)
	static final ThreadLocal<String> SAVE_DIR = new ThreadLocal<String>();

	/**
	 * A path as written in the project file: relative to its folder, with '/', when the file is inside it (the
	 * project folder can be moved or renamed), else as it is (absolute); unchanged outside the writing of a project
	 * file. Read back by resolve()
	 */
	public static String forSave(String aPath) {
		String aDir = SAVE_DIR.get();
		if(aDir == null || aPath == null || aPath.isEmpty() || "null".equals(aPath) || !new File(aPath).isAbsolute()) {
			return aPath;
		}
		try {
			java.nio.file.Path aD = new File(aDir).getAbsoluteFile().toPath().normalize();
			java.nio.file.Path aP = new File(aPath).getAbsoluteFile().toPath().normalize();
			if(aP.startsWith(aD) && !aP.equals(aD)) {
				return aD.relativize(aP).toString().replace(File.separatorChar, '/');
			}
		}
		catch(Throwable t) {
			t.printStackTrace(System.err);
		}
		return aPath;
	}

	/** Runs aXml with the paths inside aDir written relative to it (forSave()) */
	public static <T> T withSaveDir(String aDir, java.util.concurrent.Callable<T> aXml) throws Exception {
		String aOld = SAVE_DIR.get();
		SAVE_DIR.set(aDir);
		try {
			return aXml.call();
		}
		finally {
			if(aOld == null) {
				SAVE_DIR.remove();
			}
			else {
				SAVE_DIR.set(aOld);
			}
		}
	}

	//Levels of sub folders tried by resolve() (Montages/, AI/, Snapshots/, Clips/... and deeper)
	static final int RESOLVE_LEVELS = 4;

	/**
	 * A media path of a project file, found again after the project folder was moved or renamed: aPath itself when
	 * it exists, else relative to the project folder, else its last 1 to RESOLVE_LEVELS components under the project
	 * folder then under the last folder used (an absolute path .../OldProject/Montages/x.mp4 found as
	 * <project folder>/Montages/x.mp4, the file name alone at the root first); null when not found
	 */
	public static String resolve(TDenlive aTDe, String aPath) {
		if(aPath == null || aPath.isEmpty() || "null".equals(aPath)) {
			return null;
		}
		File aF = new File(aPath);
		//Relative (written so when inside the project folder): from the project folder, never from the working
		//directory of the application
		if(!aF.isAbsolute() && aTDe.projectDir != null && new File(aTDe.projectDir, aPath).exists()) {
			return new File(aTDe.projectDir, aPath).getPath();
		}
		if(aF.isAbsolute() && aF.exists()) {
			return aPath;
		}
		String[] aDirs = {aTDe.projectDir, aTDe.lastDir};
		//Components of the path, the file name last
		java.util.ArrayList<String> aParts = new java.util.ArrayList<String>();
		for(File aP = aF;aP != null && !aP.getName().isEmpty();aP = aP.getParentFile()) {
			aParts.add(0, aP.getName());
		}
		for(String aDir : aDirs) {
			if(aDir == null) {
				continue;
			}
			for(int n = 1;n <= Math.min(RESOLVE_LEVELS, aParts.size());n++) {
				File aC = new File(aDir);
				for(int k = aParts.size()-n;k < aParts.size();k++) {
					aC = new File(aC, aParts.get(k));
				}
				if(aC.exists()) {
					return aC.getPath();
				}
			}
		}
		return null;
	}


	/**
	 * Folder of the saved project, null if the project was never saved
	 */
	public static String projectDir(TDenlive aTDe) {
		return aTDe.projectDir == null || aTDe.projectName == null ? null : aTDe.projectDir;
	}

	/**
	 * True if aPath is in aDir or one of its sub folders
	 */
	public static boolean isInside(String aPath,String aDir) {
		if(aPath == null || aDir == null) {
			return false;
		}
		try {
			return new File(aPath).getCanonicalFile().toPath().startsWith(new File(aDir).getCanonicalFile().toPath());
		}
		catch(Throwable t) {
			return new File(aPath).getAbsoluteFile().toPath().startsWith(new File(aDir).getAbsoluteFile().toPath());
		}
	}

	/**
	 * Existing files among aPaths that are not in the project folder (none if the project is not saved)
	 */
	public static Vector<String> outside(TDenlive aTDe,String... aPaths) {
		Vector<String> aOut = new Vector<String>();
		String aDir = projectDir(aTDe);
		if(aDir == null) {
			return aOut;
		}
		for(String aP : aPaths) {
			if(aP != null && new File(aP).isFile() && !isInside(aP, aDir) && !aOut.contains(aP)) {
				aOut.add(aP);
			}
		}
		return aOut;
	}

	/**
	 * Media files of the project that are not in its folder
	 */
	public static Vector<String> outsideMedias(TDenlive aTDe) {
		Vector<String> aAll = new Vector<String>();
		for(Media aM : aTDe.mediaList.mediaList) {
			aAll.addAll(aM.getFilePaths());
		}
		return outside(aTDe, aAll.toArray(new String[0]));
	}

	/**
	 * Free file name in aDir for aName (aName, else name_2.ext...)
	 */
	static File freeFile(File aDir,String aName) {
		File aF = new File(aDir, aName);
		String aBase = aName.replaceAll("[.][^.]*$", "");
		String aExt = aName.substring(aBase.length());
		for(int n = 2;aF.exists();n++) {
			aF = new File(aDir, aBase+"_"+n+aExt);
		}
		return aF;
	}

	/**
	 * Move (or copy) a file, keeping its date. A move that fails (file in use on Windows...) becomes a copy.
	 * @return true if moved, false if copied
	 */
	static boolean transfer(File aSrc,File aDest,boolean aMove) throws Exception {
		aDest.getAbsoluteFile().getParentFile().mkdirs();
		if(aMove) {
			try {
				Files.move(aSrc.toPath(), aDest.toPath());
				return true;
			}
			catch(Throwable t) {
				System.err.println("Move failed, copied: "+aSrc+" -> "+aDest);
				t.printStackTrace(System.err);
			}
		}
		Files.copy(aSrc.toPath(), aDest.toPath(), StandardCopyOption.COPY_ATTRIBUTES);
		return false;
	}

	/**
	 * Move or copy a file of the work folder named after the media path (aOld -> aNew), if it exists
	 */
	static void transferWork(File aOld,File aNew,boolean aMove) {
		try {
			if(aOld.isFile() && !aNew.exists() && !aOld.getAbsoluteFile().equals(aNew.getAbsoluteFile())) {
				transfer(aOld, aNew, aMove);
			}
		}
		catch(Throwable t) {
			t.printStackTrace(System.err);
		}
	}

	/**
	 * Move or copy a media file into the project folder, with its depth maps, work copy and sound cache.
	 * aMoved (may be null) is set to false when a move had to be a copy.
	 * @return its new path, aPath itself if not done (no project folder, already in it, error)
	 */
	public static String importFile(TDenlive aTDe,String aPath,boolean aMove,boolean[] aMoved) {
		String aDir = projectDir(aTDe);
		return aDir == null ? aPath : importFile(aTDe, aPath, aMove, aMoved, new File(aDir));
	}

	/**
	 * Same into aDestDir (a sub folder of the project folder: renderings of the montages, see Montage)
	 */
	public static String importFile(TDenlive aTDe,String aPath,boolean aMove,boolean[] aMoved,File aDestDir) {
		String aDir = aDestDir.getPath();
		if(projectDir(aTDe) == null || aPath == null || !new File(aPath).isFile() || isInside(aPath, aDir)) {
			return aPath;
		}
		try {
			String aWork = aTDe.getWorkCacheDir();
			File aSrc = new File(aPath);
			//Already copied there (same name, size and date): that copy is used
			File aSame = new File(aDir, aSrc.getName());
			if(!aMove && aSame.isFile() && aSame.length() == aSrc.length() && aSame.lastModified() == aSrc.lastModified()) {
				return aSame.getPath();
			}
			File aDest = freeFile(new File(aDir), aSrc.getName());
			//Named from the path (and the date for the sound): known before the move
			File aOldWorkCopy = new File(StringUtils.path2workingcopy(aPath, aWork));
			File aOldTexCopy = new File(StringUtils.path2texturecopy(aPath, aWork));
			File aOldSound = AudioReader.cacheFileFor(aPath, aWork);
			Vector<String[]> aDepths = new Vector<String[]>();
			for(boolean aVideo : new boolean[] {false, true}) {
				String aD = ComputedDepthmap.depthPath(aPath, null, aVideo);
				if(new File(aD).isFile()) {
					aDepths.add(new String[] {aD, ComputedDepthmap.depthPath(aDest.getPath(), null, aVideo, com.cubaix.TDenlive.ffmpeg.Ffmpeg.isAnaglyph(aPath))});
				}
			}
			boolean aDone = transfer(aSrc, aDest, aMove);
			if(aMoved != null && aMove && !aDone) {
				aMoved[0] = false;
			}
			String aNew = aDest.getPath();
			//Work copy only if it is in the work folder (not an old one next to the media)
			if(aOldWorkCopy.getAbsoluteFile().getParentFile().equals(new File(aWork).getAbsoluteFile())) {
				transferWork(aOldWorkCopy, new File(StringUtils.path2workingcopy(aNew, aWork)), aMove);
			}
			if(aOldTexCopy.isFile()) {
				transferWork(aOldTexCopy, new File(StringUtils.path2texturecopy(aNew, aWork)), aMove);
			}
			transferWork(aOldSound, AudioReader.cacheFileFor(aNew, aWork), aMove);
			//The other audio streams decoded (multi-track mixer)
			for(int k = 1;k < 64;k++) {
				File aOldK = AudioReader.cacheFileFor(aPath, aWork, k);
				if(aOldK.isFile()) {
					transferWork(aOldK, AudioReader.cacheFileFor(aNew, aWork, k), aMove);
				}
			}
			for(String[] aD : aDepths) {
				File aT = new File(aD[1]);
				if(!aT.exists()) {
					transfer(new File(aD[0]), aT, aMove);
				}
			}
			System.out.println((aMove ? "Moved: " : "Copied: ")+aPath+" -> "+aNew);
			return aNew;
		}
		catch(Throwable t) {
			t.printStackTrace(System.err);
			return aPath;
		}
	}

	/**
	 * Move or copy all the media files outside the project folder into it.
	 * aFailed gets the files not done, aCopied the files copied instead of moved.
	 * @return the project XML with the new paths
	 */
	public static String relocate(TDenlive aTDe,boolean aMove,Vector<String> aFailed,Vector<String> aCopied) throws Exception {
		Map<String,String> aNews = new LinkedHashMap<String,String>();
		for(String aP : outsideMedias(aTDe)) {
			boolean[] aMoved = {true};
			String aNew = importFile(aTDe, aP, aMove, aMoved);
			if(aNew.equals(aP)) {
				aFailed.add(aP);
			}
			else {
				aNews.put(aP, aNew);
				if(aMove && !aMoved[0]) {
					aCopied.add(aP);
				}
			}
		}
		//Written in the project folder: the files inside it relative (those moved / copied there too)
		String aDir = aTDe.projectDir;
		String aXml = aTDe.projectXml(aDir);
		for(Map.Entry<String,String> aE : aNews.entrySet()) {
			String aNew = withSaveDir(aDir, () -> forSave(aE.getValue()));
			aXml = aXml.replace(">"+aE.getKey()+"<", ">"+aNew+"<");
		}
		return aXml;
	}
}
