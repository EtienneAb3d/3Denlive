package com.cubaix.TDenlive;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.Vector;

import org.eclipse.swt.graphics.Cursor;

import com.cubaix.TDenlive.GUI.TDeGUI;
import com.cubaix.TDenlive.ffmpeg.AudioReader;
import com.cubaix.TDenlive.ffmpeg.VideoThumbnailer;
import com.cubaix.TDenlive.medias.Media;
import com.cubaix.TDenlive.medias.MediaList;
import com.cubaix.TDenlive.medias.Selected;
import com.cubaix.TDenlive.medias.StereoMovie;
import com.cubaix.TDenlive.medias.TimeLineStack;
import com.cubaix.TDenlive.xml.XmlMinimalParser;
import com.cubaix.TDenlive.xml.XmlObject;
import com.cubaix.TDenlive.xml.XmlTag;

public class TDenlive {
	static final public String TDeVersion = readVersion();

	//Version is kept in VERSION.txt, next to this class (src/ in dev, the jar in delivery)
	static String readVersion() {
		try(InputStream aIs = TDenlive.class.getResourceAsStream("VERSION.txt")) {
			return new String(aIs.readAllBytes(), StandardCharsets.UTF_8).trim();
		}
		catch(Throwable t) {
			t.printStackTrace(System.err);
			return "?";
		}
	}
	
	public String tdeDir = System.getProperty("user.home")+File.separatorChar+"3Denlive";
	{
		if(!new File(tdeDir).exists()) {
			new File(tdeDir).mkdirs();
		}
	}
	public String configPath = tdeDir+File.separatorChar+"3De.conf";
	public String renderDir = tdeDir+File.separatorChar+"Rendered";
	
	public String lastDir = null;
	public String projectDir = null;
	public String projectName = null;
 
	public TDConfig config = null;
	public TDeGUI gui = null;
	
	public MediaList mediaList = null;
	public TimeLineStack timeLineStack = null;
	
	public Selected selected = new Selected();

	//Project file content at the last opening or saving: the montage is modified if it differs
	String savedXml = null;

	/**
	 * Keep the current state of the montage as the saved one (after opening or saving the project)
	 */
	public void markSaved() {
		try {
			savedXml = projectXml();
		}
		catch(Throwable t) {
			t.printStackTrace(System.err);
			savedXml = null;
		}
	}

	/**
	 * True if the montage changed since the last opening or saving of the project
	 */
	public boolean isModified() {
		try {
			return !projectXml().equals(savedXml);
		}
		catch(Throwable t) {
			t.printStackTrace(System.err);
			return true;
		}
	}

	/**
	 * Work folder of the project: the WorkCache sub folder of the project folder, so that moving or
	 * deleting the project folder takes everything. It holds the decoded sounds (see ffmpeg.AudioReader)
	 * and the work copies of the videos (see StringUtils.path2workingcopy()), named from their source:
	 * the projects of the same folder can share it. A project not yet saved uses ~/3Denlive/WorkCache.
	 * The files of the former audio cache (<project>_3De_AudioCache, 1.0.15 and before) are moved into it.
	 */
	public String getWorkCacheDir() {
		String aProjectDir = projectDir == null || projectName == null ? tdeDir : projectDir;
		File aDir = new File(aProjectDir, "WorkCache");
		String aBase = projectName == null ? null : projectName.replaceAll("(?i)[.]3de$", "");
		for(String aOldName : new String[] {aBase == null ? null : aBase+"_3De_AudioCache", projectDir == null || projectName == null ? "Unsaved_3De_AudioCache" : null}) {
			if(aOldName != null) {
				moveCacheFiles(new File(aProjectDir, aOldName), aDir);
			}
		}
		return aDir.getPath();
	}

	/**
	 * Move the files of a former cache folder into the work folder, then remove it
	 */
	static void moveCacheFiles(File aOld,File aDir) {
		if(!aOld.isDirectory()) {
			return;
		}
		try {
			if(!aDir.exists() && aOld.renameTo(aDir)) {
				return;
			}
			aDir.mkdirs();
			File[] aFs = aOld.listFiles();
			if(aFs != null) {
				for(File aF : aFs) {
					File aTo = new File(aDir, aF.getName());
					if(aF.isFile() && !aTo.exists()) {
						java.nio.file.Files.move(aF.toPath(), aTo.toPath());
					}
				}
			}
			//Only if all moved (a file already in the work folder is kept in the old one)
			aOld.delete();
		}
		catch(Throwable t) {
			t.printStackTrace(System.err);
		}
	}
	
	/**
	 * Copy the video work copies from a previous work folder of the project to the current one
	 * (save under another name; the sounds are copied by saveProject())
	 */
	public void copyWorkCacheFrom(String aOldWorkCacheDir) {
		String aWorkCacheDir = getWorkCacheDir();
		if(aOldWorkCacheDir == null || new File(aOldWorkCacheDir).getAbsoluteFile().equals(new File(aWorkCacheDir).getAbsoluteFile())) {
			return;
		}
		for(Media aM : mediaList.mediaList) {
			if(aM instanceof StereoMovie) {
				((StereoMovie)aM).copyWorkCopies(aOldWorkCacheDir, aWorkCacheDir);
			}
		}
	}
	
	/**
	 * True if this output ratio name is the "1st media" one (in any language, as saved in the projects)
	 */
	public static boolean isFirstMediaRatioName(String aName) {
		return aName != null && (aName.equals(TDConfig.RATIONAMES[TDConfig.RATIO_FIRSTMEDIA])
				|| aName.equals(TDConfig.RATIOSHORTNAMES[TDConfig.RATIO_FIRSTMEDIA])
				|| aName.matches("1M|1er|1st|1er media|1st media"));
	}

	/**
	 * Ratio of the first media of the list having an image size of its own (16:9 if none),
	 * applied to the output if the "1st media" ratio is selected
	 */
	public void updateFirstMediaRatio() {
		double aRatio = TDConfig.RATIOVALUES[TDConfig.RATIO_16_9];
		for(Media aM : mediaList.mediaList) {
			double aR = aM.getDisplayRatio();
			if(aR > 0) {
				aRatio = aR;
				break;
			}
		}
		config.origRatio = aRatio;
		if(isFirstMediaRatioName(config.outRatioName)) {
			config.outRatio = aRatio;
		}
	}

	public void resetViews() {
		gui.display.syncExec(new Runnable() {
			@Override
			public void run() {
				mediaList.mediaList.clear();
				updateFirstMediaRatio();
				timeLineStack.videoTimeLineStack.clear();
				timeLineStack.audioTimeLineStack.clear();
				gui.setMultiPropsPanel(gui.MULTIPROPSPANEL_PROPS);
				gui.setMultiMonitorPanel(gui.MULTIMONITORPANEL_MONITOR);
				gui.monitorGUI.setMedia(null);
				timeLineStack.setRequestedTimePosMS(0);
			}
		});
	}
	
	public void openProject(String aPath) {
		try {
			resetViews();
			
			XmlMinimalParser aXMP = new XmlMinimalParser();
			Vector<XmlObject> aOs = aXMP.parse(aPath);
			for(int o = 0;o < aOs.size();o++) {
				XmlObject aO = aOs.elementAt(o);
				if(aO instanceof XmlTag) {
					XmlTag aT = (XmlTag)aO;
					if("outRatio".equalsIgnoreCase(aT.tagName)) {
						o++;
						config.outRatio = Double.parseDouble(aOs.elementAt(o).text);
						continue;
					}
					if("outRatioName".equalsIgnoreCase(aT.tagName)) {
						o++;
						config.outRatioName = aOs.elementAt(o).text;
						continue;
					}
					if("MediaList".equalsIgnoreCase(aT.tagName)) {
						o = mediaList.openProject(aOs, o);
					}
					if("TimeLineList".equalsIgnoreCase(aT.tagName)) {
						o = timeLineStack.openProject(aOs, o);
					}
				}
			}
			//The saved ratio of a "1st media" project may be outdated
			updateFirstMediaRatio();
			if(gui.monitorControlGUI != null) {
				gui.monitorControlGUI.setRatio(config.outRatioName);
			}
			gui.mediaListGUI.redraw();
			gui.timeLinesGUI.redraw();
			//Show the montage in the monitor (as when clicking the timelines), instead of a black preview
			gui.display.syncExec(new Runnable() {
				@Override
				public void run() {
					gui.monitorGUI.setMedia(timeLineStack);
					markSaved();
				}
			});
		}
		catch(Throwable t) {
			t.printStackTrace(System.err);
		}
	}
	
	/**
	 * The project file content (current state of the montage)
	 */
	String projectXml() throws Exception {
		StringBuffer aSB = new StringBuffer();
		aSB.append("<3De>\n");
		aSB.append("	<outRatio>"+config.outRatio+"</outRatio>\n");
		aSB.append("	<outRatioName>"+config.outRatioName+"</outRatioName>\n");
		aSB.append("	<MediaList>\n");
		mediaList.saveProject(aSB);
		aSB.append("	</MediaList>\n");
		aSB.append("	<TimeLineList>\n");
		timeLineStack.saveProject(aSB);
		aSB.append("	</TimeLineList>\n");
		aSB.append("</3De>\n");
		return aSB.toString();
	}

	/**
	 * Zip the project (current state) and its media files into Packed/<project>.zip (<project>_2.zip... if it
	 * exists). Everything is in a <project>/ root folder, the .3De naming the medias by their file name only:
	 * opened from there, they are found next to it (see findPath()). Files not found are added to aMissing.
	 */
	public File packProject(Vector<String> aMissing) throws Exception {
		String aName = projectName == null ? "3Denlive" : projectName.replaceAll("(?i)([.]3de)+$", "");
		File aPackedDir = new File(tdeDir, "Packed");
		aPackedDir.mkdirs();
		File aZip = new File(aPackedDir, aName+".zip");
		for(int v = 2;aZip.exists();v++) {
			aZip = new File(aPackedDir, aName+"_"+v+".zip");
		}
		//Media files, with a unique name in the zip
		java.util.LinkedHashMap<String,String> aFiles = new java.util.LinkedHashMap<String,String>();
		java.util.HashSet<String> aNames = new java.util.HashSet<String>();
		aNames.add((aName+".3De").toLowerCase());
		for(Media aM : mediaList.mediaList) {
			for(String aP : aM.getFilePaths()) {
				if(aFiles.containsKey(aP)) {
					continue;
				}
				if(!new File(aP).isFile()) {
					aMissing.add(aP);
					continue;
				}
				String aFileName = new File(aP).getName();
				String aBase = aFileName.replaceAll("[.][^.]*$", "");
				String aExt = aFileName.substring(aBase.length());
				for(int n = 2;aNames.contains(aFileName.toLowerCase());n++) {
					aFileName = aBase+"_"+n+aExt;
				}
				aNames.add(aFileName.toLowerCase());
				aFiles.put(aP, aFileName);
			}
		}
		String aXml = projectXml();
		for(java.util.Map.Entry<String,String> aE : aFiles.entrySet()) {
			aXml = aXml.replace(">"+aE.getKey()+"<", ">"+aE.getValue()+"<");
		}
		File aTmp = new File(aPackedDir, aZip.getName()+".tmp");
		try(java.util.zip.ZipOutputStream aZOS = new java.util.zip.ZipOutputStream(
				new java.io.BufferedOutputStream(new FileOutputStream(aTmp)), StandardCharsets.UTF_8)) {
			aZOS.putNextEntry(new java.util.zip.ZipEntry(aName+"/"));
			aZOS.closeEntry();
			aZOS.putNextEntry(new java.util.zip.ZipEntry(aName+"/"+aName+".3De"));
			aZOS.write(aXml.getBytes(StandardCharsets.UTF_8));
			aZOS.closeEntry();
			for(java.util.Map.Entry<String,String> aE : aFiles.entrySet()) {
				java.util.zip.ZipEntry aZE = new java.util.zip.ZipEntry(aName+"/"+aE.getValue());
				aZE.setTime(new File(aE.getKey()).lastModified());
				aZOS.putNextEntry(aZE);
				java.nio.file.Files.copy(new File(aE.getKey()).toPath(), aZOS);
				aZOS.closeEntry();
			}
		}
		catch(Throwable t) {
			aTmp.delete();
			throw t;
		}
		//Complete only: a stopped or failed packing leaves no partial zip
		if(!aTmp.renameTo(aZip)) {
			aTmp.delete();
			throw new java.io.IOException("Cannot rename "+aTmp+" to "+aZip);
		}
		return aZip;
	}

	public boolean saveProject(String aPath) {
		try {
			String aXml = projectXml();
			BufferedWriter aBW = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(aPath), "UTF-8"));
			aBW.write(aXml);
			aBW.flush();
			aBW.close();
			//The decoded sounds go with the project
			AudioReader.copyCachesTo(getWorkCacheDir());
			return true;
		}
		catch(Throwable t) {
			t.printStackTrace(System.err);
			return false;
		}
	}
	
	/**
	 * Create the DemoLogo project (~/3Denlive/DemoLogo) from demo/DemoLogo.zip (in the jar, or src/ in dev)
	 * if its .3De does not exist. The files already there are kept.
	 */
	void installDemo() {
		File aRoot = new File(tdeDir).getAbsoluteFile();
		if(new File(aRoot, "DemoLogo"+File.separatorChar+"DemoLogo.3De").exists()) {
			return;
		}
		try(InputStream aIs = TDenlive.class.getResourceAsStream("/demo/DemoLogo.zip")) {
			if(aIs == null) {
				System.err.println("No demo/DemoLogo.zip in the classpath");
				return;
			}
			java.util.zip.ZipInputStream aZIS = new java.util.zip.ZipInputStream(aIs);
			java.util.zip.ZipEntry aZE;
			while((aZE = aZIS.getNextEntry()) != null) {
				File aF = new File(aRoot, aZE.getName()).getCanonicalFile();
				if(!aF.toPath().startsWith(aRoot.getCanonicalFile().toPath())) {
					//Never outside ~/3Denlive
					continue;
				}
				if(aZE.isDirectory()) {
					aF.mkdirs();
					continue;
				}
				if(aF.exists()) {
					continue;
				}
				aF.getParentFile().mkdirs();
				java.nio.file.Files.copy(aZIS, aF.toPath());
				if(aZE.getTime() > 0) {
					aF.setLastModified(aZE.getTime());
				}
			}
			System.out.println("Demo project installed: "+new File(aRoot, "DemoLogo"));
		}
		catch(Throwable t) {
			t.printStackTrace(System.err);
		}
	}

	public void start(String aProjectPath) throws Exception {
		config = new TDConfig();
		installDemo();

		mediaList = new MediaList(this);
	    gui = new TDeGUI(this);
	    timeLineStack = new TimeLineStack(this);
	    //Waveforms and film strips appear when the sounds are decoded / the images extracted
	    Runnable aRedrawTimeLines = () -> {
	    	if(gui.display.isDisposed()) {
	    		return;
	    	}
	    	gui.display.asyncExec(() -> {
	    		if(gui.timeLinesGUI != null) {//redraw() checks the disposal
	    			gui.timeLinesGUI.redraw();
	    		}
	    	});
	    };
	    AudioReader.decodedListener = aRedrawTimeLines;
	    VideoThumbnailer.readyListener = aRedrawTimeLines;

	    //An empty montage has nothing to save
	    markSaved();
	    if(aProjectPath != null) {
	    	gui.openProject(aProjectPath);
	    }
	    gui.run();
	}

	//Optional argument: a project file to open
	public static void main(String[] args) {
		//AWT is only used for off-screen images (BufferedImage, fonts). Headless, it never calls the
		//native window system: on macOS its initialization from a worker thread otherwise needs the
		//main thread, which SWT holds, and both threads deadlock
		System.setProperty("java.awt.headless", "true");
		try {
			new TDenlive().start(args.length > 0 ? args[0] : null);
			System.exit(0);//Force exit, because of possible threads keeping it alive
		}
		catch(Throwable t) {
			t.printStackTrace(System.err);
		}
	}

}
