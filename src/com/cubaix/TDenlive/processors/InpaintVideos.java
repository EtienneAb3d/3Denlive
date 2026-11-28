package com.cubaix.TDenlive.processors;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.Vector;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

import com.cubaix.TDenlive.TDenlive;
import com.cubaix.TDenlive.ffmpeg.Ffmpeg;

/**
 * The images precomputed by the AI hole filling (Inpainting) kept with the project as videos: in
 * <WorkCache>/Inpaint/, per clip (UID + processor class = owner) one H.264 4:4:4 video per index id (mode, view,
 * size) holding its patches in the order of the time (the holes with their colors, the other pixels a continuation
 * of them, so that the compression does not bleed into the holes), and a manifest <project>_<owner>.3ei (gzip:
 * the signatures of the images done, then per video its frames: key, smoothed key, time, parameters hash, flip,
 * thumbnail, holes as runs). The videos are written first (names with a hash: the previous manifest stays valid
 * until the new one replaces it), the manifest last, then the videos no longer referenced are removed.
 * Written in the background (one worker thread) at each save of the project and at the end of a precomputation,
 * only for the clips changed since the last export; read back at the opening: the frames and signatures at once
 * (load()), the videos decoded into the session cache by the worker meanwhile the Precomputer waits (restoring).
 */
public class InpaintVideos {
	static final String SUB = "Inpaint";
	static final String MANIFEST_EXT = ".3ei";
	static final int MAGIC = 0x33654932;
	static final int VERSION = 1;
	static final int FPS = 25;
	static final String CRF = "10";

	//Export signature per manifest file path (unchanged: not written again)
	static final Map<String,String> exported = new HashMap<String,String>();
	static final LinkedBlockingQueue<Runnable> JOBS = new LinkedBlockingQueue<Runnable>();
	static Thread worker = null;
	//Videos being decoded into the session cache: the Precomputer waits
	public static volatile boolean restoring = false;
	//Progress of that reading (images), shown by GUI.InpaintProgressGUI
	public static volatile int restoreDone = 0;
	static synchronized void restored() {
		restoreDone++;
	}
	public static volatile int restoreTotal = 0;
	public static volatile long restoreStartMS = 0;

	static synchronized void submit(Runnable aJob) {
		JOBS.add(aJob);
		if(worker == null) {
			worker = new Thread(() -> {
				while(true) {
					try {
						JOBS.take().run();
					}
					catch(Throwable t) {
						t.printStackTrace(System.err);
					}
				}
			}, "Inpainting videos");
			worker.setDaemon(true);
			worker.setPriority(Thread.MIN_PRIORITY);
			worker.start();
		}
	}

	static File folder(TDenlive aTDe) {
		return new File(aTDe.getWorkCacheDir(), SUB);
	}

	static String base(TDenlive aTDe) {
		return (aTDe.projectName == null ? "Unsaved" : aTDe.projectName.replaceAll("(?i)[.]3de$", ""))
				.replaceAll("[\\\\/:*?\"<>|]", "_");
	}

	static String safe(String aS) {
		return aS.replaceAll("[^A-Za-z0-9.-]", "_");
	}

	static class Entry {
		Inpainting.Frame frame;
		int[] idx;
	}

	static class Video {
		String file;
		String id;
		int w;
		int h;
		Vector<Entry> entries = new Vector<Entry>();
	}

	/**
	 * Export of the clips of a saved project (background)
	 */
	public static void save(TDenlive aTDe) {
		if(aTDe.projectName == null || aTDe.projectDir == null) {
			return;
		}
		File aDir = folder(aTDe);
		String aBase = base(aTDe);
		Map<String,Inpainting> aOwners = Inpainting.owners(aTDe);
		submit(() -> export(aDir, aBase, aOwners));
	}

	static void export(File aDir,String aBase,Map<String,Inpainting> aOwners) {
		Set<String> aKept = new HashSet<String>();
		for(Map.Entry<String,Inpainting> aE : aOwners.entrySet()) {
			Inpainting aInp = aE.getValue();
			if(!aInp.enabled) {
				continue;
			}
			String aOwner = aBase+"_"+safe(aE.getKey());
			aKept.add(aOwner+MANIFEST_EXT);
			try {
				exportOwner(aDir, aOwner, aInp, aKept);
			}
			catch(Throwable t) {
				t.printStackTrace(System.err);
			}
		}
		//Clips removed, or no longer filled by the model: their files go
		String[] aNames = aDir.list();
		if(aNames != null) {
			for(String aN : aNames) {
				if(aN.startsWith(aBase+"_") && !aKept.contains(aN) && (aN.endsWith(MANIFEST_EXT) || aN.endsWith(".mp4"))) {
					new File(aDir, aN).delete();
				}
			}
		}
	}

	/**
	 * The patch shown for a frame: smoothed if made, else the raw one (null if neither is in the session cache)
	 */
	static String contentKey(Inpainting.Frame aF) {
		Set<String> aC = Inpainting.cached();
		String aSK = aF.smoothKey;
		if(aSK != null && !aSK.equals(aF.key) && aC.contains(aSK)) {
			return aSK;
		}
		return aC.contains(aF.key) ? aF.key : null;
	}

	/**
	 * Signatures of the images done (sorted, in aSigsOut[0]), frames whose patch is in the session cache by id (in
	 * aFramesOut), and the hash of all of them
	 */
	static String contentHash(Inpainting aInp,String[][] aSigsOut,Map<String,Vector<Inpainting.Frame>> aFramesOut) {
		String[] aSigs;
		synchronized(aInp.doneSigs) {
			aSigs = aInp.doneSigs.toArray(new String[0]);
		}
		java.util.Arrays.sort(aSigs);
		aSigsOut[0] = aSigs;
		Map<String,Vector<Inpainting.Frame>> aById = new TreeMap<String,Vector<Inpainting.Frame>>();
		synchronized(aInp.index) {
			for(Map.Entry<String,TreeMap<Long,Vector<Inpainting.Frame>>> aE : aInp.index.entrySet()) {
				Vector<Inpainting.Frame> aFs = new Vector<Inpainting.Frame>();
				for(Vector<Inpainting.Frame> aV : aE.getValue().values()) {
					aFs.addAll(aV);
				}
				aById.put(aE.getKey(), aFs);
			}
		}
		StringBuilder aSig = new StringBuilder();
		aSig.append(String.join(",", aSigs)).append('#');
		for(Map.Entry<String,Vector<Inpainting.Frame>> aE : aById.entrySet()) {
			Vector<Inpainting.Frame> aFs = new Vector<Inpainting.Frame>();
			for(Inpainting.Frame aF : aE.getValue()) {
				String aCK = contentKey(aF);
				if(aCK != null) {
					aFs.add(aF);
					aSig.append(aE.getKey()).append(':').append(aF.time).append(':').append(aF.key).append(':').append(aCK).append(';');
				}
			}
			if(!aFs.isEmpty()) {
				aFramesOut.put(aE.getKey(), aFs);
			}
		}
		try {
			byte[] aD = java.security.MessageDigest.getInstance("SHA-1").digest(aSig.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
			StringBuilder aHex = new StringBuilder();
			for(int i = 0;i < 8;i++) {
				aHex.append(String.format("%02x", aD[i] & 0xFF));
			}
			return aHex.toString();
		}
		catch(Throwable t) {
			return Long.toHexString(aSig.toString().hashCode() & 0xFFFFFFFFL)+Integer.toHexString(aSig.length());
		}
	}

	static void exportOwner(File aDir,String aOwner,Inpainting aInp,Set<String> aKept) throws Exception {
		String[][] aSigsOut = new String[1][];
		Map<String,Vector<Inpainting.Frame>> aKeptFrames = new LinkedHashMap<String,Vector<Inpainting.Frame>>();
		String aHash = contentHash(aInp, aSigsOut, aKeptFrames);
		String[] aSigs = aSigsOut[0];
		File aManifest = new File(aDir, aOwner+MANIFEST_EXT);
		synchronized(exported) {
			if(aHash.equals(exported.get(aManifest.getPath())) && aManifest.isFile()) {
				keepVideosOf(aManifest, aKept);
				return;
			}
		}
		aDir.mkdirs();
		Vector<Video> aVideos = new Vector<Video>();
		for(Map.Entry<String,Vector<Inpainting.Frame>> aE : aKeptFrames.entrySet()) {
			Video aV = new Video();
			aV.id = aE.getKey();
			aV.file = aOwner+"_"+safe(aV.id)+"_"+aHash+".mp4";
			Inpainting.Frame aF0 = aE.getValue().firstElement();
			aV.w = aF0.w;
			aV.h = aF0.h;
			if(!encode(new File(aDir, aV.file), aV, aE.getValue())) {
				return;
			}
			aVideos.add(aV);
			aKept.add(aV.file);
		}
		writeManifest(aManifest, aSigs, aVideos);
		synchronized(exported) {
			exported.put(aManifest.getPath(), aHash);
		}
		System.out.println("Inpainting: "+aManifest.getName()+" saved, "+aVideos.size()+" video(s)");
	}

	static void keepVideosOf(File aManifest,Set<String> aKept) {
		try {
			for(Video aV : readManifest(aManifest, new Vector<String>())) {
				aKept.add(aV.file);
			}
		}
		catch(Throwable t) {
			t.printStackTrace(System.err);
		}
	}

	/**
	 * The patches of the frames (same size) into a video; the entries (holes) filled in aV
	 */
	static boolean encode(File aOut,Video aV,Vector<Inpainting.Frame> aFs) throws Exception {
		int aW = aV.w;
		int aH = aV.h;
		File aTmp = new File(aOut.getPath()+".tmp.mp4");
		ArrayList<String> aCmd = new ArrayList<String>();
		aCmd.add(Ffmpeg.ffmpegPath());
		aCmd.add("-hide_banner");
		aCmd.add("-nostats");
		aCmd.add("-loglevel"); aCmd.add("error");
		aCmd.add("-f"); aCmd.add("rawvideo");
		aCmd.add("-pix_fmt"); aCmd.add("rgb24");
		aCmd.add("-s"); aCmd.add(aW+"x"+aH);
		aCmd.add("-r"); aCmd.add(""+FPS);
		aCmd.add("-i"); aCmd.add("pipe:0");
		aCmd.add("-an");
		aCmd.add("-vf"); aCmd.add("pad=ceil(iw/2)*2:ceil(ih/2)*2");
		aCmd.add("-c:v"); aCmd.add("libx264");
		aCmd.add("-preset"); aCmd.add("medium");
		aCmd.add("-crf"); aCmd.add(CRF);
		aCmd.add("-pix_fmt"); aCmd.add("yuv444p");
		aCmd.add("-y");
		aCmd.add(aTmp.getPath());
		Process aP = new ProcessBuilder(aCmd).redirectOutput(ProcessBuilder.Redirect.DISCARD)
				.redirectError(ProcessBuilder.Redirect.INHERIT).start();
		byte[] aImg = new byte[aW*aH*3];
		try(OutputStream aOS = new BufferedOutputStream(aP.getOutputStream(), 1 << 20)) {
			for(Inpainting.Frame aF : aFs) {
				String aCK = contentKey(aF);
				float[] aVals = aCK == null ? null : Inpainting.load(aCK, aW, aH);
				int[] aIdx = aVals == null ? new int[0] : Inpainting.lastIdx.get();
				if(aVals == null) {
					aVals = new float[0];
				}
				image(aImg, aW, aH, aIdx, aVals);
				aOS.write(aImg);
				Entry aE = new Entry();
				aE.frame = aF;
				aE.idx = aIdx;
				aV.entries.add(aE);
			}
		}
		catch(Throwable t) {
			aP.destroy();
			aTmp.delete();
			throw t;
		}
		int aRC = aP.waitFor();
		if(aRC != 0 || !aTmp.isFile()) {
			System.err.println("Inpainting: ffmpeg exit "+aRC+" for "+aOut);
			aTmp.delete();
			return false;
		}
		Files.move(aTmp.toPath(), aOut.toPath(), StandardCopyOption.REPLACE_EXISTING);
		return true;
	}

	/**
	 * Image of a patch: the holes with their colors, every other pixel the color of the nearest hole pixel of its
	 * line (lines without hole: the nearest line with one), gray without hole
	 */
	static void image(byte[] aImg,int aW,int aH,int[] aIdx,float[] aVals) {
		int aN = aW*aH;
		int[] aRGB = new int[aN];
		boolean[] aIn = new boolean[aN];
		for(int i = 0;i < aIdx.length;i++) {
			int p = aIdx[i];
			int aR = Math.max(0, Math.min(255, Math.round(aVals[i*3]*255)));
			int aG = Math.max(0, Math.min(255, Math.round(aVals[i*3+1]*255)));
			int aB = Math.max(0, Math.min(255, Math.round(aVals[i*3+2]*255)));
			aRGB[p] = (aR<<16)|(aG<<8)|aB;
			aIn[p] = true;
		}
		boolean[] aRowHas = new boolean[aH];
		int[] aDist = new int[aW];
		for(int y = 0;y < aH;y++) {
			int aO = y*aW;
			int aLast = -1;
			for(int x = 0;x < aW;x++) {
				if(aIn[aO+x]) {
					aLast = x;
				}
				aDist[x] = aLast < 0 ? Integer.MAX_VALUE : x-aLast;
				if(aLast >= 0 && !aIn[aO+x]) {
					aRGB[aO+x] = aRGB[aO+aLast];
				}
			}
			aRowHas[y] = aLast >= 0;
			aLast = -1;
			for(int x = aW-1;x >= 0;x--) {
				if(aIn[aO+x]) {
					aLast = x;
				}
				else if(aLast >= 0 && aLast-x < aDist[x]) {
					aRGB[aO+x] = aRGB[aO+aLast];
				}
			}
		}
		int aPrev = -1;
		for(int y = 0;y < aH;y++) {
			if(aRowHas[y]) {
				if(aPrev < 0) {
					//Lines before the first one with holes
					for(int y2 = 0;y2 < y;y2++) {
						System.arraycopy(aRGB, y*aW, aRGB, y2*aW, aW);
					}
				}
				aPrev = y;
			}
			else if(aPrev >= 0) {
				System.arraycopy(aRGB, aPrev*aW, aRGB, y*aW, aW);
			}
		}
		if(aPrev < 0) {
			java.util.Arrays.fill(aRGB, 0x808080);
		}
		for(int p = 0;p < aN;p++) {
			int aC = aRGB[p];
			aImg[p*3] = (byte)(aC>>16);
			aImg[p*3+1] = (byte)(aC>>8);
			aImg[p*3+2] = (byte)aC;
		}
	}

	static void writeManifest(File aManifest,String[] aSigs,Vector<Video> aVideos) throws Exception {
		File aTmp = new File(aManifest.getPath()+".tmp");
		try(DataOutputStream aOut = new DataOutputStream(new BufferedOutputStream(new GZIPOutputStream(new FileOutputStream(aTmp), 1 << 16)))) {
			aOut.writeInt(MAGIC);
			aOut.writeInt(VERSION);
			aOut.writeInt(aSigs.length);
			for(String aS : aSigs) {
				aOut.writeUTF(aS);
			}
			aOut.writeInt(aVideos.size());
			for(Video aV : aVideos) {
				aOut.writeUTF(aV.file);
				aOut.writeUTF(aV.id);
				aOut.writeInt(aV.w);
				aOut.writeInt(aV.h);
				aOut.writeInt(aV.entries.size());
				for(Entry aE : aV.entries) {
					Inpainting.Frame aF = aE.frame;
					aOut.writeUTF(aF.key);
					String aSK = aF.smoothKey;
					aOut.writeUTF(aSK == null ? "" : aSK);
					aOut.writeLong(aF.time);
					aOut.writeInt(aF.paramsHash);
					aOut.writeBoolean(aF.flip);
					aOut.writeInt(aF.thumb.length);
					for(float aT : aF.thumb) {
						aOut.writeFloat(aT);
					}
					//Holes as runs (start, length)
					Vector<int[]> aRuns = new Vector<int[]>();
					for(int i = 0;i < aE.idx.length;) {
						int j = i+1;
						while(j < aE.idx.length && aE.idx[j] == aE.idx[j-1]+1) {
							j++;
						}
						aRuns.add(new int[] {aE.idx[i], j-i});
						i = j;
					}
					aOut.writeInt(aRuns.size());
					for(int[] aR : aRuns) {
						aOut.writeInt(aR[0]);
						aOut.writeInt(aR[1]);
					}
				}
			}
		}
		Files.move(aTmp.toPath(), aManifest.toPath(), StandardCopyOption.REPLACE_EXISTING);
	}

	static Vector<Video> readManifest(File aManifest,Vector<String> aSigs) throws Exception {
		Vector<Video> aVideos = new Vector<Video>();
		try(DataInputStream aIn = new DataInputStream(new BufferedInputStream(new GZIPInputStream(new FileInputStream(aManifest), 1 << 16)))) {
			if(aIn.readInt() != MAGIC || aIn.readInt() != VERSION) {
				return aVideos;
			}
			int aNS = aIn.readInt();
			for(int i = 0;i < aNS;i++) {
				aSigs.add(aIn.readUTF());
			}
			int aNV = aIn.readInt();
			for(int v = 0;v < aNV;v++) {
				Video aV = new Video();
				aV.file = aIn.readUTF();
				aV.id = aIn.readUTF();
				aV.w = aIn.readInt();
				aV.h = aIn.readInt();
				int aNE = aIn.readInt();
				for(int e = 0;e < aNE;e++) {
					Inpainting.Frame aF = new Inpainting.Frame();
					aF.id = aV.id;
					aF.w = aV.w;
					aF.h = aV.h;
					aF.key = aIn.readUTF();
					String aSK = aIn.readUTF();
					aF.smoothKey = aSK.isEmpty() ? null : aSK;
					aF.time = aIn.readLong();
					aF.paramsHash = aIn.readInt();
					aF.flip = aIn.readBoolean();
					aF.thumb = new float[aIn.readInt()];
					for(int t = 0;t < aF.thumb.length;t++) {
						aF.thumb[t] = aIn.readFloat();
					}
					int aNR = aIn.readInt();
					int[][] aRuns = new int[aNR][2];
					int aCount = 0;
					for(int r = 0;r < aNR;r++) {
						aRuns[r][0] = aIn.readInt();
						aRuns[r][1] = aIn.readInt();
						aCount += aRuns[r][1];
					}
					int[] aIdx = new int[aCount];
					int k = 0;
					for(int[] aR : aRuns) {
						for(int i = 0;i < aR[1];i++) {
							aIdx[k++] = aR[0]+i;
						}
					}
					boolean[] aHB = new boolean[aV.w*aV.h];
					for(int i : aIdx) {
						if(i >= 0 && i < aHB.length) {
							aHB[i] = true;
						}
					}
					aF.holeBits = Inpainting.holeGrid(aHB, aV.w, aV.h);
					Entry aE = new Entry();
					aE.frame = aF;
					aE.idx = aIdx;
					aV.entries.add(aE);
				}
				aVideos.add(aV);
			}
		}
		return aVideos;
	}

	/**
	 * At the opening of a project (GUI thread): the frames and the images done of its clips read from their
	 * manifests at once (a clip whose videos are not all there: its frames, without the images done), the
	 * videos decoded into the session cache in the background (restoring)
	 */
	public static void load(TDenlive aTDe) {
		File aDir = folder(aTDe);
		String aBase = base(aTDe);
		Map<String,Inpainting> aOwners = Inpainting.owners(aTDe);
		Vector<Object[]> aToDecode = new Vector<Object[]>();
		for(Map.Entry<String,Inpainting> aE : aOwners.entrySet()) {
			Inpainting aInp = aE.getValue();
			File aManifest = new File(aDir, aBase+"_"+safe(aE.getKey())+MANIFEST_EXT);
			if(!aInp.enabled || !aManifest.isFile()) {
				continue;
			}
			try {
				Vector<String> aSigs = new Vector<String>();
				Vector<Video> aVideos = readManifest(aManifest, aSigs);
				boolean aComplete = true;
				for(Video aV : aVideos) {
					File aFile = new File(aDir, aV.file);
					if(!aFile.isFile()) {
						aComplete = false;
						continue;
					}
					for(Entry aEn : aV.entries) {
						aInp.register(aEn.frame);
					}
					aToDecode.add(new Object[] {aFile, aV, aInp, aManifest});
				}
				if(aComplete) {
					aInp.doneSigs.addAll(aSigs);
				}
			}
			catch(Throwable t) {
				t.printStackTrace(System.err);
			}
		}
		if(aToDecode.isEmpty()) {
			return;
		}
		int aTotal = 0;
		for(Object[] aJ : aToDecode) {
			aTotal += ((Video)aJ[1]).entries.size();
		}
		restoreTotal = aTotal;
		restoreDone = 0;
		restoreStartMS = System.currentTimeMillis();
		restoring = true;
		Inpainting.Precomputer.redrawRuler(true);
		submit(() -> {
			long aStart = System.currentTimeMillis();
			int aN = 0;
			try {
				Set<Inpainting> aDone = new HashSet<Inpainting>();
				for(Object[] aJ : aToDecode) {
					aN += decode((File)aJ[0], (Video)aJ[1]);
				}
				//What was read back is what is saved: not written again at the next save unless it changes
				for(Object[] aJ : aToDecode) {
					Inpainting aInp = (Inpainting)aJ[2];
					if(aDone.add(aInp)) {
						String aHash = contentHash(aInp, new String[1][], new HashMap<String,Vector<Inpainting.Frame>>());
						synchronized(exported) {
							exported.put(((File)aJ[3]).getPath(), aHash);
						}
					}
				}
			}
			catch(Throwable t) {
				t.printStackTrace(System.err);
			}
			finally {
				restoring = false;
				Inpainting.Precomputer.redrawRuler(true);
			}
			System.out.println("Inpainting: "+aN+" image(s) read from "+aToDecode.size()+" video(s) in "+(System.currentTimeMillis()-aStart)+" ms");
			if(aTDe.gui != null && aTDe.gui.display != null && !aTDe.gui.display.isDisposed()) {
				aTDe.gui.display.asyncExec(() -> {
					if(aTDe.gui.monitorGUI != null) {
						aTDe.gui.monitorGUI.redraw();
					}
				});
			}
		});
	}

	/**
	 * A video into the session cache: each frame under its key and its smoothed key
	 */
	static int decode(File aFile,Video aV) throws Exception {
		int aW = aV.w;
		int aH = aV.h;
		ArrayList<String> aCmd = new ArrayList<String>();
		aCmd.add(Ffmpeg.ffmpegPath());
		aCmd.add("-hide_banner");
		aCmd.add("-nostats");
		aCmd.add("-loglevel"); aCmd.add("error");
		aCmd.add("-i"); aCmd.add(aFile.getPath());
		aCmd.add("-vf"); aCmd.add("crop="+aW+":"+aH+":0:0");
		aCmd.add("-f"); aCmd.add("rawvideo");
		aCmd.add("-pix_fmt"); aCmd.add("rgb24");
		aCmd.add("pipe:1");
		Process aP = new ProcessBuilder(aCmd).redirectError(ProcessBuilder.Redirect.INHERIT).start();
		byte[] aImg = new byte[aW*aH*3];
		int aN = 0;
		//The files written on all the cores while ffmpeg decodes the next frames
		int aThreads = Math.max(1, Runtime.getRuntime().availableProcessors()-1);
		java.util.concurrent.ExecutorService aPool = java.util.concurrent.Executors.newFixedThreadPool(aThreads);
		java.util.concurrent.Semaphore aSlots = new java.util.concurrent.Semaphore(2*aThreads);
		try(InputStream aIS = new BufferedInputStream(aP.getInputStream(), 1 << 20)) {
			DataInputStream aDIS = new DataInputStream(aIS);
			for(Entry aE : aV.entries) {
				aDIS.readFully(aImg);
				Inpainting.Frame aF = aE.frame;
				Set<String> aC = Inpainting.cached();
				boolean aNeedRaw = !aC.contains(aF.key);
				boolean aNeedSmooth = aF.smoothKey != null && !aF.smoothKey.equals(aF.key) && !aC.contains(aF.smoothKey);
				if(!aNeedRaw && !aNeedSmooth) {
					restored();
					continue;
				}
				if((aN & 31) == 0) {
					Inpainting.Precomputer.redrawRuler(false);
				}
				float[] aVals = new float[aE.idx.length*3];
				for(int i = 0;i < aE.idx.length;i++) {
					int p = aE.idx[i]*3;
					aVals[i*3] = (aImg[p] & 0xFF)/255f;
					aVals[i*3+1] = (aImg[p+1] & 0xFF)/255f;
					aVals[i*3+2] = (aImg[p+2] & 0xFF)/255f;
				}
				//The raw patch is not kept: the smoothed one stands for it
				aSlots.acquire();
				aPool.execute(() -> {
					try {
						if(aNeedRaw) {
							Inpainting.store(aF.key, aW, aH, aE.idx, aVals);
						}
						if(aNeedSmooth) {
							Inpainting.store(aF.smoothKey, aW, aH, aE.idx, aVals);
						}
					}
					finally {
						aSlots.release();
						restored();
					}
				});
				aN++;
			}
		}
		finally {
			aPool.shutdown();
			aPool.awaitTermination(10, java.util.concurrent.TimeUnit.MINUTES);
			aP.destroy();
			aP.waitFor();
		}
		return aN;
	}
}
