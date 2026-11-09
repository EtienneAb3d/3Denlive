package com.cubaix.TDenlive.shapes;

import java.awt.Font;
import java.awt.GraphicsEnvironment;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Fonts of the texts of a project, copied into Fonts/<project>/ next to the .3De file, so that the
 * project shows the same texts on a computer where they are not installed. At the save, the files of
 * the font families used by the texts are copied there (all the styles of the family), and the files
 * no more used are removed. At the opening, the fonts of this folder are registered.
 */
public class ProjectFonts {
	public static final String DIR = "Fonts";
	static final String[] EXTENSIONS = {".ttf",".otf",".ttc"};
	//Always available: embedded in the application, or logical fonts of Java
	static final String[] NOT_COPIED = {ShapeText.DEFAULT_FONT,"Dialog","DialogInput","Serif","SansSerif","Monospaced"};
	static final Pattern FONT_TAG = Pattern.compile("<fontName>([^<]*)</fontName>");
	//Family (lower case) -> font files: registered from projects, and found in the system folders
	static final Map<String,Set<File>> REGISTERED = new HashMap<String,Set<File>>();
	static Map<String,Set<File>> systemIndex = null;

	/**
	 * Fonts folder of the project file aProjectPath
	 */
	public static File dirOf(String aProjectPath) {
		File aP = new File(aProjectPath).getAbsoluteFile();
		String aBase = aP.getName().replaceAll("(?i)([.]3de)+$", "");
		return new File(new File(aP.getParentFile(), DIR), aBase);
	}

	static boolean isFontFile(File aF) {
		String aN = aF.getName().toLowerCase(Locale.ROOT);
		for(String aE : EXTENSIONS) {
			if(aN.endsWith(aE)) {
				return true;
			}
		}
		return false;
	}

	/**
	 * Families of the fonts of a file (lower case, in the default language and in English)
	 */
	static Set<String> familiesOf(Font[] aFonts) {
		Set<String> aFams = new TreeSet<String>();
		for(Font aFt : aFonts) {
			aFams.add(aFt.getFamily().toLowerCase(Locale.ROOT));
			aFams.add(aFt.getFamily(Locale.ENGLISH).toLowerCase(Locale.ROOT));
		}
		return aFams;
	}

	static void index(Map<String,Set<File>> aIndex,File aF,Font[] aFonts) {
		for(String aFam : familiesOf(aFonts)) {
			aIndex.computeIfAbsent(aFam, k -> new LinkedHashSet<File>()).add(aF);
		}
	}

	/**
	 * Register the fonts of the project aProjectPath (those already installed keep the installed version)
	 */
	public static void register(String aProjectPath) {
		File[] aFs = dirOf(aProjectPath).listFiles();
		if(aFs == null) {
			return;
		}
		ShapeText.register();
		GraphicsEnvironment aGE = GraphicsEnvironment.getLocalGraphicsEnvironment();
		boolean aAdded = false;
		for(File aF : aFs) {
			if(!aF.isFile() || !isFontFile(aF)) {
				continue;
			}
			try {
				Font[] aFonts = Font.createFonts(aF);
				for(Font aFt : aFonts) {
					aGE.registerFont(aFt);
				}
				synchronized (REGISTERED) {
					index(REGISTERED, aF, aFonts);
				}
				aAdded = true;
			}
			catch(Throwable t) {
				System.err.println("Font not loaded: "+aF);
				t.printStackTrace(System.err);
			}
		}
		if(aAdded) {
			ShapeText.fontsChanged();
		}
	}

	/**
	 * Font folders of the system (macOS, Linux, Windows) and of the user
	 */
	static String[] systemDirs() {
		String aHome = System.getProperty("user.home");
		String aWin = System.getenv("WINDIR");
		String aLocal = System.getenv("LOCALAPPDATA");
		return new String[] {"/System/Library/Fonts","/Library/Fonts",aHome+"/Library/Fonts"
				,"/usr/share/fonts","/usr/local/share/fonts",aHome+"/.fonts",aHome+"/.local/share/fonts"
				,aWin == null ? "C:\\Windows\\Fonts" : aWin+"\\Fonts"
				,aLocal == null ? null : aLocal+"\\Microsoft\\Windows\\Fonts"};
	}

	static void scan(Map<String,Set<File>> aIndex,File aDir,int aDepth) {
		File[] aFs = aDir.listFiles();
		if(aFs == null || aDepth > 8) {
			return;
		}
		for(File aF : aFs) {
			if(aF.isDirectory()) {
				scan(aIndex, aF, aDepth+1);
			}
			else if(isFontFile(aF)) {
				try {
					index(aIndex, aF, Font.createFonts(aF));
				}
				catch(Throwable t) {
					//Not a font readable by Java: ignored
				}
			}
		}
	}

	/**
	 * Font files of the system by family, built once (some hundreds of files: a fraction of a second)
	 */
	static synchronized Map<String,Set<File>> getSystemIndex() {
		if(systemIndex == null) {
			Map<String,Set<File>> aIndex = new HashMap<String,Set<File>>();
			for(String aD : systemDirs()) {
				if(aD != null) {
					scan(aIndex, new File(aD), 0);
				}
			}
			systemIndex = aIndex;
		}
		return systemIndex;
	}

	/**
	 * Font families of the texts of a project content (except the fonts always available)
	 */
	public static Set<String> usedFamilies(String aXml) {
		Set<String> aFams = new TreeSet<String>(String.CASE_INSENSITIVE_ORDER);
		Matcher aM = FONT_TAG.matcher(aXml);
		while(aM.find()) {
			try {
				String aFam = Shape.decode(aM.group(1).trim()).trim();
				boolean aCopied = !aFam.isEmpty();
				for(String aN : NOT_COPIED) {
					if(aN.equalsIgnoreCase(aFam)) {
						aCopied = false;
					}
				}
				if(aCopied) {
					aFams.add(aFam);
				}
			}
			catch(Throwable t) {
				t.printStackTrace(System.err);
			}
		}
		return aFams;
	}

	/**
	 * Files of the fonts used by a project content: from the project fonts already registered, else
	 * from the system. A family found nowhere is reported on the error output
	 */
	public static Set<File> usedFiles(String aXml) {
		Set<File> aFiles = new LinkedHashSet<File>();
		for(String aFam : usedFamilies(aXml)) {
			String aKey = aFam.toLowerCase(Locale.ROOT);
			Set<File> aFs = null;
			synchronized (REGISTERED) {
				Set<File> aReg = REGISTERED.get(aKey);
				if(aReg != null) {
					aFs = new LinkedHashSet<File>();
					for(File aF : aReg) {
						if(aF.isFile()) {
							aFs.add(aF);
						}
					}
				}
			}
			if(aFs == null || aFs.isEmpty()) {
				aFs = getSystemIndex().get(aKey);
			}
			if(aFs == null || aFs.isEmpty()) {
				System.err.println("Font files not found: "+aFam);
				continue;
			}
			aFiles.addAll(aFs);
		}
		return aFiles;
	}

	/**
	 * After the save of aXml in aProjectPath: copy the fonts it uses into its fonts folder, remove
	 * the others (the folder is removed when empty)
	 */
	public static void save(String aProjectPath,String aXml) {
		File aDir = dirOf(aProjectPath);
		try {
			Set<File> aUsed = usedFiles(aXml);
			Set<String> aNames = new TreeSet<String>(String.CASE_INSENSITIVE_ORDER);
			for(File aF : aUsed) {
				File aTo = new File(aDir, aF.getName());
				//One file per name (a same name from 2 folders: the first one)
				if(!aNames.add(aF.getName())) {
					continue;
				}
				if(aTo.getCanonicalFile().equals(aF.getCanonicalFile())
						|| (aTo.isFile() && aTo.length() == aF.length())) {
					continue;
				}
				aDir.mkdirs();
				Files.copy(aF.toPath(), aTo.toPath(), StandardCopyOption.REPLACE_EXISTING);
				synchronized (REGISTERED) {
					index(REGISTERED, aTo, Font.createFonts(aTo));
				}
			}
			File[] aFs = aDir.listFiles();
			if(aFs != null) {
				for(File aF : aFs) {
					if(aF.isFile() && isFontFile(aF) && !aNames.contains(aF.getName())) {
						aF.delete();
					}
				}
			}
			String[] aLeft = aDir.list();
			if(aLeft != null && aLeft.length == 0) {
				aDir.delete();
				String[] aOthers = aDir.getParentFile().list();
				if(aOthers != null && aOthers.length == 0) {
					aDir.getParentFile().delete();
				}
			}
		}
		catch(Throwable t) {
			t.printStackTrace(System.err);
		}
	}
}
