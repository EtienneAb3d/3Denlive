package com.cubaix.TDenlive.model3d;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import com.cubaix.TDenlive.depth.Downloader;

/**
 * Library of the textured objects of the 3D modeler (type "Objects" of its add panel, Object3D.ASSET):
 * res/objects3d/objects.txt lists the packs and the objects by category (the order of the file is that of
 * the tree), res/objects3d/objects_<LNG>.txt translates the categories and the names (English otherwise).
 * The files are not in the application: downloaded at the first use into ~/3Denlive/Models/Objects3D/
 * (a zip pack unpacked into <pack>/; a Poly Haven object = its 1k glTF and its files, from the API of
 * Poly Haven, into <pack>/<asset>/; kind "files": the glTF / GLB and the files it references, from a base
 * URL, into <pack>/), then read by Gltf (meshes kept by soft references).
 */
public class ObjectCatalog {
	public static class Pack {
		public String id, kind, url, licence, author, page;
		public long size;
	}

	public static class Item {
		public String category;//"Animals/Farm"
		public String id;
		public String name;//English
		public Pack pack;
		public String path;
		public int tris;
		public long bytes;
	}

	static ArrayList<Item> items = null;
	static HashMap<String,Item> byId = new HashMap<String,Item>();
	static HashMap<String,Pack> packs = new HashMap<String,Pack>();
	static String trLng = null;
	static HashMap<String,String> trCat = new HashMap<String,String>();
	static HashMap<String,String> trObj = new HashMap<String,String>();
	static final Map<String, java.lang.ref.SoftReference<Mesh3D>> MESHES = new HashMap<String, java.lang.ref.SoftReference<Mesh3D>>();
	//Objects whose file failed to load: not read again at each image
	static final java.util.Set<String> FAILED = java.util.Collections.synchronizedSet(new java.util.HashSet<String>());

	public static File dir() {
		return new File(System.getProperty("user.home")+File.separator+"3Denlive"+File.separator+"Models", "Objects3D");
	}

	static BufferedReader resource(String aName) {
		InputStream aIS = ObjectCatalog.class.getResourceAsStream("/com/cubaix/TDenlive/res/objects3d/"+aName);
		return aIS == null ? null : new BufferedReader(new InputStreamReader(aIS, StandardCharsets.UTF_8));
	}

	static synchronized void load() {
		if(items != null) {
			return;
		}
		items = new ArrayList<Item>();
		try(BufferedReader aBR = resource("objects.txt")) {
			if(aBR == null) {
				return;
			}
			String aLine;
			while((aLine = aBR.readLine()) != null) {
				aLine = aLine.trim();
				if(aLine.isEmpty() || aLine.startsWith("#")) {
					continue;
				}
				String[] aF = aLine.split("\\|", -1);
				if("pack".equals(aF[0]) && aF.length >= 8) {
					Pack aP = new Pack();
					aP.id = aF[1];
					aP.kind = aF[2];
					aP.url = aF[3];
					aP.size = parseLong(aF[4]);
					aP.licence = aF[5];
					aP.author = aF[6];
					aP.page = aF[7];
					packs.put(aP.id, aP);
				}
				else if("obj".equals(aF[0]) && aF.length >= 8) {
					Item aI = new Item();
					aI.category = aF[1];
					aI.id = aF[2];
					aI.name = aF[3];
					aI.pack = packs.get(aF[4]);
					aI.path = aF[5];
					aI.tris = (int)parseLong(aF[6]);
					aI.bytes = parseLong(aF[7]);
					if(aI.pack == null || byId.containsKey(aI.id)) {
						System.err.println("Objects3D: bad line "+aLine);
						continue;
					}
					items.add(aI);
					byId.put(aI.id, aI);
				}
			}
		}
		catch(Throwable t) {
			t.printStackTrace(System.err);
		}
	}

	static long parseLong(String aS) {
		try {
			return Long.parseLong(aS.trim());
		}
		catch(Throwable t) {
			return 0;
		}
	}

	public static synchronized List<Item> items() {
		load();
		return items;
	}

	public static synchronized Item get(String aId) {
		load();
		return aId == null ? null : byId.get(aId);
	}

	static synchronized void translations(String aLng) {
		if(aLng == null) {
			aLng = "EN";
		}
		if(aLng.equals(trLng)) {
			return;
		}
		trLng = aLng;
		trCat.clear();
		trObj.clear();
		if("EN".equals(aLng)) {
			return;
		}
		try(BufferedReader aBR = resource("objects_"+aLng+".txt")) {
			if(aBR == null) {
				return;
			}
			String aLine;
			while((aLine = aBR.readLine()) != null) {
				String[] aF = aLine.split("\\|", -1);
				if(aF.length < 3) {
					continue;
				}
				if("cat".equals(aF[0])) {
					trCat.put(aF[1].trim(), aF[2].trim());
				}
				else if("obj".equals(aF[0])) {
					trObj.put(aF[1].trim(), aF[2].trim());
				}
			}
		}
		catch(Throwable t) {
			t.printStackTrace(System.err);
		}
	}

	/**
	 * Name of an element of a category path in the language aLng
	 */
	public static synchronized String categoryName(String aCat, String aLng) {
		translations(aLng);
		String aT = trCat.get(aCat);
		return aT == null || aT.isEmpty() ? aCat : aT;
	}

	public static synchronized String name(Item aI, String aLng) {
		translations(aLng);
		String aT = trObj.get(aI.id);
		return aT == null || aT.isEmpty() ? aI.name : aT;
	}

	/**
	 * Categories in the order of the file, each with its objects
	 */
	public static synchronized LinkedHashMap<String, List<Item>> byCategory() {
		load();
		LinkedHashMap<String, List<Item>> aM = new LinkedHashMap<String, List<Item>>();
		for(Item aI : items) {
			aM.computeIfAbsent(aI.category, k -> new ArrayList<Item>()).add(aI);
		}
		return aM;
	}

	//== Files ==

	/**
	 * Objects delivered with the application: objects3d/ of the working directory (distrib/), filled by
	 * Install-Dev.sh (main() below) and copied into the deliveries; read only
	 */
	public static File bundledDir() {
		return new File("objects3d").getAbsoluteFile();
	}

	//Folder of the downloads of install(): the Models folder of the user, or the folder of main()
	static File installRoot = null;

	static File installRoot() {
		return installRoot != null ? installRoot : dir();
	}

	static File packDir(Pack aP, File aRoot) {
		return new File(aRoot, aP.id);
	}

	static File packDir(Pack aP) {
		return packDir(aP, installRoot());
	}

	/**
	 * Folder where the object is (delivered with the application, else downloaded), else that of the
	 * downloads
	 */
	static File rootOf(Item aI) {
		File aB = bundledDir();
		if(installRoot == null && isInstalledIn(aI, aB)) {
			return aB;
		}
		return installRoot();
	}

	/**
	 * The .glb / .gltf of an object (it may not exist yet)
	 */
	public static File file(Item aI) {
		return file(aI, rootOf(aI));
	}

	static File file(Item aI, File aRoot) {
		if("polyhaven".equals(aI.pack.kind)) {
			File aD = new File(packDir(aI.pack, aRoot), aI.path);
			File aMark = new File(aD, ".main");
			if(aMark.isFile()) {
				try {
					return new File(aD, Files.readString(aMark.toPath()).trim());
				}
				catch(Throwable t) {
					t.printStackTrace(System.err);
				}
			}
			return new File(aD, aI.path+".gltf");
		}
		return new File(packDir(aI.pack, aRoot), aI.path);
	}

	public static boolean isInstalled(Item aI) {
		return (installRoot == null && isInstalledIn(aI, bundledDir())) || isInstalledIn(aI, installRoot());
	}

	static boolean isInstalledIn(Item aI, File aRoot) {
		if("zip".equals(aI.pack.kind)) {
			return new File(packDir(aI.pack, aRoot), ".unpacked").isFile() && file(aI, aRoot).isFile();
		}
		if("polyhaven".equals(aI.pack.kind)) {
			return new File(new File(packDir(aI.pack, aRoot), aI.path), ".complete").isFile();
		}
		return file(aI, aRoot).isFile() && new File(file(aI, aRoot).getPath()+".complete").isFile();
	}

	/**
	 * Thumbnail of an object (THUMB pixels square, gray background): the one delivered with the
	 * application, else the one made at its download; null if none
	 */
	public static File thumb(Item aI) {
		for(File aRoot : new File[] {bundledDir(), installRoot()}) {
			File aT = new File(new File(aRoot, "thumbs"), aI.id+".png");
			if(aT.isFile()) {
				return aT;
			}
		}
		return null;
	}

	public static final int THUMB = 96;

	/**
	 * Makes the thumbnail of an installed object into <root>/thumbs/ (the folder of the downloads)
	 */
	public static File makeThumb(Item aI) throws Exception {
		File aT = new File(new File(installRoot(), "thumbs"), aI.id+".png");
		aT.getParentFile().mkdirs();
		Model3D aM = new Model3D();
		Object3D aO = new Object3D(Object3D.ASSET);
		aO.asset = aI.id;
		aO.id = 0;
		aM.objects.add(aO);
		aM.lastId = 0;
		aM.pitch = 20;
		aM.yaw = 30;
		aM.frame(null, 1.0);
		Renderer3D.Options aOpt = new Renderer3D.Options();
		aOpt.bck = 0xFF505050;
		aOpt.ss = 3;
		java.awt.image.BufferedImage aBI = Renderer3D.render(aM, THUMB, THUMB, 0, 0, aOpt).image;
		File aPart = new File(aT.getPath()+".part.png");
		javax.imageio.ImageIO.write(aBI, "png", aPart);
		Files.move(aPart.toPath(), aT.toPath(), StandardCopyOption.REPLACE_EXISTING);
		return aT;
	}

	/**
	 * Downloads every object of the catalog into a folder (distrib/objects3d for the deliveries) and makes
	 * their thumbnails: java -Djava.awt.headless=true -cp <classes>:<src> ...ObjectCatalog <folder> [-f]
	 * (-f: thumbnails made again). Exit code 1 if some objects failed.
	 */
	public static void main(String[] aArgs) throws Exception {
		if(aArgs.length < 1) {
			System.err.println("Usage: ObjectCatalog <folder> [-f]");
			System.exit(2);
		}
		installRoot = new File(aArgs[0]).getAbsoluteFile();
		boolean aForce = aArgs.length > 1 && "-f".equals(aArgs[1]);
		installRoot.mkdirs();
		int aDone = 0, aFailed = 0;
		java.util.Vector<String> aFails = new java.util.Vector<String>();
		long aT0 = System.currentTimeMillis();
		for(Item aI : items()) {
			try {
				if(!isInstalledIn(aI, installRoot)) {
					install(aI, null);
				}
				File aT = new File(new File(installRoot, "thumbs"), aI.id+".png");
				if(aForce || !aT.isFile()) {
					if(mesh(aI.id).nbTris == 0) {
						throw new Exception("empty mesh");
					}
					makeThumb(aI);
				}
				aDone++;
				System.out.println("["+(aDone+aFailed)+"/"+items().size()+"] "+aI.id);
			}
			catch(Throwable t) {
				aFailed++;
				aFails.add(aI.id+": "+t);
				System.out.println("["+(aDone+aFailed)+"/"+items().size()+"] FAILED "+aI.id+": "+t);
			}
		}
		System.out.println(aDone+" objects ready, "+aFailed+" failed, in "+(System.currentTimeMillis()-aT0)/1000+" s");
		for(String aF : aFails) {
			System.out.println("  "+aF);
		}
		System.exit(aFailed > 0 ? 1 : 0);
	}

	/**
	 * Bytes to download for an object not installed (the whole pack for a zip)
	 */
	public static long downloadSize(Item aI) {
		if(isInstalled(aI)) {
			return 0;
		}
		return "zip".equals(aI.pack.kind) ? aI.pack.size : aI.bytes;
	}

	/**
	 * Objects installed with the same download (the other objects of a zip pack)
	 */
	public static int packCount(Item aI) {
		if(!"zip".equals(aI.pack.kind)) {
			return 1;
		}
		int aN = 0;
		for(Item aO : items()) {
			if(aO.pack == aI.pack) {
				aN++;
			}
		}
		return aN;
	}

	/**
	 * Downloads the files of an object (and of its pack) if needed
	 */
	static final Object INSTALL_LOCK = new Object();

	public static void install(Item aI, Downloader.Progress aProgress) throws Exception {
		//One download at a time (two objects of the same pack: the second finds it installed)
		synchronized(INSTALL_LOCK) {
			doInstall(aI, aProgress);
		}
	}

	static void doInstall(Item aI, Downloader.Progress aProgress) throws Exception {
		if(isInstalled(aI)) {
			return;
		}
		File aDir = packDir(aI.pack);
		aDir.mkdirs();
		switch(aI.pack.kind) {
		case "zip": {
			File aZip = new File(dir(), aI.pack.id+".zip");
			//Size of the catalog only shown: a pack updated on its site stays usable
			boolean aOK = Downloader.download(aI.pack.url, aZip, 0, aProgress);
			if(aProgress != null && aProgress.isCanceled()) {
				return;
			}
			if(!aOK) {
				throw new Exception("Download failed: "+aI.pack.url);
			}
			unzip(aZip, aDir);
			Files.writeString(new File(aDir, ".unpacked").toPath(), aI.pack.url);
			aZip.delete();
			break;
		}
		case "polyhaven": {
			File aD = new File(aDir, aI.path);
			aD.mkdirs();
			String aJson = httpText("https://api.polyhaven.com/files/"+aI.path);
			Map<String,Object> aRes = Gltf.obj(Gltf.obj(Gltf.obj(Gltf.Json.parse(aJson)).get("gltf")).get("1k"));
			Map<String,Object> aMain = Gltf.obj(aRes.get("gltf"));
			String aUrl = Gltf.str(aMain.get("url"), null);
			if(aUrl == null) {
				throw new Exception("No 1k glTF for "+aI.path);
			}
			ArrayList<String[]> aFiles = new ArrayList<String[]>();
			String aMainName = aUrl.substring(aUrl.lastIndexOf('/')+1);
			aFiles.add(new String[] {aUrl, aMainName, ""+(long)Gltf.num(aMain.get("size"), 0)});
			for(Map.Entry<String,Object> aE : Gltf.obj(aMain.get("include")).entrySet()) {
				Map<String,Object> aF = Gltf.obj(aE.getValue());
				aFiles.add(new String[] {Gltf.str(aF.get("url"), ""), aE.getKey(), ""+(long)Gltf.num(aF.get("size"), 0)});
			}
			downloadAll(aFiles, aD, aProgress);
			if(aProgress != null && aProgress.isCanceled()) {
				return;
			}
			Files.writeString(new File(aD, ".main").toPath(), aMainName);
			Files.writeString(new File(aD, ".complete").toPath(), aUrl);
			break;
		}
		default: {
			//"files": the glTF / GLB, then the buffers and images it references
			String aBase = aI.pack.url.endsWith("/") ? aI.pack.url : aI.pack.url+"/";
			File aMain = file(aI);
			ArrayList<String[]> aFiles = new ArrayList<String[]>();
			aFiles.add(new String[] {aBase+encodePath(aI.path), aI.path, "0"});
			downloadAll(aFiles, aDir, aProgress);
			if(aProgress != null && aProgress.isCanceled()) {
				return;
			}
			if(aMain.getName().toLowerCase().endsWith(".gltf")) {
				Map<String,Object> aJ = Gltf.obj(Gltf.Json.parse(Files.readString(aMain.toPath())));
				String aRel = aI.path.contains("/") ? aI.path.substring(0, aI.path.lastIndexOf('/')+1) : "";
				aFiles.clear();
				for(String aKey : new String[] {"buffers", "images"}) {
					for(Object aO : Gltf.list(aJ.get(aKey))) {
						String aUri = Gltf.str(Gltf.obj(aO).get("uri"), null);
						if(aUri == null || aUri.startsWith("data:")) {
							continue;
						}
						String aDec = java.net.URLDecoder.decode(aUri.replace("+", "%2B"), StandardCharsets.UTF_8);
						if(aDec.contains("..") || aDec.startsWith("/") || aDec.contains(":")) {
							throw new Exception("Bad glTF URI: "+aUri);
						}
						aFiles.add(new String[] {aBase+encodePath(aRel)+aUri, aRel+aDec, "0"});
					}
				}
				downloadAll(aFiles, aDir, aProgress);
				if(aProgress != null && aProgress.isCanceled()) {
					return;
				}
			}
			Files.writeString(new File(aMain.getPath()+".complete").toPath(), aBase+aI.path);
			break;
		}
		}
		synchronized(MESHES) {
			MESHES.remove(aI.id);
		}
		FAILED.remove(aI.id);
	}

	static String encodePath(String aPath) {
		StringBuilder aSB = new StringBuilder();
		for(String aPart : aPath.split("/", -1)) {
			if(aSB.length() > 0) {
				aSB.append('/');
			}
			aSB.append(java.net.URLEncoder.encode(aPart, StandardCharsets.UTF_8).replace("+", "%20"));
		}
		return aSB.toString();
	}

	/**
	 * {url, relative path, size} into aDir, with one progress for all
	 */
	static void downloadAll(List<String[]> aFiles, File aDir, Downloader.Progress aProgress) throws Exception {
		long aTotal = 0;
		for(String[] aF : aFiles) {
			aTotal += parseLong(aF[2]);
		}
		final long[] aDone = {0};
		final long aAll = aTotal;
		for(String[] aF : aFiles) {
			File aOut = new File(aDir, aF[1]);
			if(!aOut.getCanonicalPath().startsWith(aDir.getCanonicalPath()+File.separator)) {
				throw new Exception("Bad path: "+aF[1]);
			}
			long aSize = parseLong(aF[2]);
			Downloader.download(aF[0], aOut, aSize, aProgress == null ? null : new Downloader.Progress() {
				@Override
				public void downloaded(long aD, long aT) {
					aProgress.downloaded(aDone[0]+aD, Math.max(aAll, aDone[0]+aT));
				}

				@Override
				public boolean isCanceled() {
					return aProgress.isCanceled();
				}
			});
			if(aProgress != null && aProgress.isCanceled()) {
				return;
			}
			aDone[0] += aOut.length();
		}
	}

	static String httpText(String aUrl) throws Exception {
		java.net.http.HttpClient aC = java.net.http.HttpClient.newBuilder().followRedirects(java.net.http.HttpClient.Redirect.NORMAL)
				.connectTimeout(java.time.Duration.ofSeconds(30)).build();
		java.net.http.HttpResponse<String> aR = aC.send(java.net.http.HttpRequest.newBuilder(URI.create(aUrl))
				.header("User-Agent", "3Denlive").GET().build(), java.net.http.HttpResponse.BodyHandlers.ofString());
		if(aR.statusCode() != 200) {
			throw new Exception("HTTP "+aR.statusCode()+": "+aUrl);
		}
		return aR.body();
	}

	static void unzip(File aZip, File aDir) throws Exception {
		String aRoot = aDir.getCanonicalPath()+File.separator;
		try(ZipInputStream aZ = new ZipInputStream(Files.newInputStream(aZip.toPath()))) {
			ZipEntry aE;
			while((aE = aZ.getNextEntry()) != null) {
				File aOut = new File(aDir, aE.getName());
				if(!aOut.getCanonicalPath().startsWith(aRoot)) {
					continue;
				}
				if(aE.isDirectory()) {
					aOut.mkdirs();
					continue;
				}
				aOut.getParentFile().mkdirs();
				Files.copy(aZ, aOut.toPath(), StandardCopyOption.REPLACE_EXISTING);
			}
		}
	}

	static final java.util.Set<String> INSTALLING = java.util.Collections.synchronizedSet(new java.util.HashSet<String>());
	//Called when an object of the library has been installed in the background
	public static volatile Runnable onInstalled = null;

	/**
	 * Object of a project opened where its files are not there yet: downloaded in the background (once
	 * per session), shown once there
	 */
	static void installLater(Item aI) {
		if(!INSTALLING.add(aI.id)) {
			return;
		}
		Thread aTh = new Thread(() -> {
			try {
				System.out.println("Objects3D: download of "+aI.id+" ("+downloadSize(aI)+" bytes) for a project");
				install(aI, null);
				if(thumb(aI) == null) {
					makeThumb(aI);
				}
				Runnable aR = onInstalled;
				if(aR != null) {
					aR.run();
				}
			}
			catch(Throwable t) {
				t.printStackTrace(System.err);
				FAILED.add(aI.id);
			}
		}, "Objects3D install");
		aTh.setDaemon(true);
		aTh.start();
	}

	//== Meshes ==

	/**
	 * Mesh of an object of the library, normalized (largest side 1); Mesh3D.EMPTY when it is not
	 * installed or can't be read
	 */
	public static Mesh3D mesh(String aId) {
		synchronized(MESHES) {
			java.lang.ref.SoftReference<Mesh3D> aRef = MESHES.get(aId);
			Mesh3D aM = aRef != null ? aRef.get() : null;
			if(aM != null) {
				return aM;
			}
		}
		Item aI = get(aId);
		if(aI == null || FAILED.contains(aId)) {
			return Mesh3D.EMPTY;
		}
		if(!isInstalled(aI)) {
			installLater(aI);
			return Mesh3D.EMPTY;
		}
		Mesh3D aM;
		try {
			long aT0 = System.currentTimeMillis();
			aM = Gltf.load(file(aI));
			System.out.println("Objects3D: "+aId+" read, "+aM.nbTris+" triangles in "+(System.currentTimeMillis()-aT0)+" ms");
		}
		catch(Throwable t) {
			System.err.println("Objects3D: can't read "+file(aI));
			t.printStackTrace(System.err);
			FAILED.add(aId);
			return Mesh3D.EMPTY;
		}
		synchronized(MESHES) {
			MESHES.put(aId, new java.lang.ref.SoftReference<Mesh3D>(aM));
		}
		return aM;
	}
}
