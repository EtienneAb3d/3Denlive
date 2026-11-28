package com.cubaix.TDenlive.medias;

import java.io.File;
import java.util.LinkedHashSet;
import java.util.Vector;

import org.eclipse.swt.graphics.Point;

import com.cubaix.TDenlive.TDConfig;
import com.cubaix.TDenlive.TDenlive;
import com.cubaix.TDenlive.processors.Processor;
import com.cubaix.TDenlive.xml.XmlObject;
import com.cubaix.TDenlive.xml.XmlTag;

/**
 * A montage of the project: its own time lines (stack). The first one (MAIN_UID) is the whole project, saved in
 * <TimeLineList> as before; the others in <Montages>. The active one is TDenlive.montage, whose stack is
 * TDenlive.timeLineStack (time lines, monitor, play, rendering).
 * A montage is included in another one by its final rendering: a parallel stereo video with its sound
 * (StereoImage.montageUID = the UID of the montage), a media of the media list that is not shown there (the montage
 * list shows it), used by ordinary clips and packed like any media. One rendering per montage, shared by the montages
 * that include it.
 * Up to date: StereoImage.montageSig = signature() of the montage when it was rendered; montageStale (saved) says it
 * no longer is, so that a project reopened later never gives an outdated result unnoticed (see refreshStates()).
 * The main montage is never included, and an inclusion that would make a loop is refused.
 */
public class Montage {
	public static final long MAIN_UID = 0;
	static long lastUID = 0;

	public long UID;
	public String name;
	public TimeLineStack stack;
	//View of its time lines when another montage is shown (session)
	public Point timeLinesOrigin = null;
	//Bounds of its row and buttons in the montage list (last drawing)
	public org.eclipse.swt.graphics.Rectangle bounds = null;
	public org.eclipse.swt.graphics.Rectangle trashRect = null;
	public org.eclipse.swt.graphics.Rectangle editRect = null;

	//Renderings read from the project file already marked out of date: their saved signature is not trusted (a
	//signature computed by another session may differ for an unchanged montage), they stay out of date until made again
	static final java.util.Set<Long> UNTRUSTED = java.util.Collections.synchronizedSet(new java.util.HashSet<Long>());

	public Montage(long aUID, String aName, TimeLineStack aStack) {
		UID = aUID;
		name = aName;
		stack = aStack;
	}

	public static synchronized long nextUID() {
		lastUID = Math.max(lastUID+1, System.currentTimeMillis());
		return lastUID;
	}

	public boolean isMain() {
		return UID == MAIN_UID;
	}

	/** Default name of the main montage, the same in all the languages */
	public static final String MASTER_NAME = "Master";

	/** Name shown: its name, else "Master" for the main montage, "New montage" for the others */
	public String displayName(TDenlive aTDe) {
		if(name != null && !name.trim().isEmpty()) {
			return name.trim();
		}
		return isMain() ? MASTER_NAME : aTDe.gui.lngs.get("Montage.NewName");
	}

	/** Name of the main montage, saved alone (its stack is the TimeLineList of the project) when not the default one */
	public static void saveMasterName(TDenlive aTDe, StringBuffer aSB) {
		Montage aMain = aTDe.mainMontage();
		if(aMain != null && aMain.name != null && !aMain.name.trim().isEmpty()) {
			aSB.append("	<masterName>" + com.cubaix.TDenlive.shapes.Shape.encode(aMain.name.trim()) + "</masterName>\n");
		}
	}

	/** Reads the tag of saveMasterName() at o (its text at o + 1), returns the new index */
	public static int openMasterName(TDenlive aTDe, Vector<XmlObject> aOs, int o) {
		String aText = o+1 < aOs.size() ? aOs.elementAt(o+1).text : null;
		if(aText != null && !(aOs.elementAt(o+1) instanceof XmlTag)) {
			o++;
			String aName = com.cubaix.TDenlive.shapes.Shape.decode(aText.trim());
			if(aTDe.mainMontage() != null) {
				aTDe.mainMontage().name = aName.isEmpty() ? null : aName;
			}
		}
		return o;
	}

	/** All the clips of its lines */
	public Vector<Clip> clips() {
		Vector<Clip> aClips = new Vector<Clip>();
		for(VideoTimeLine aTL : new Vector<VideoTimeLine>(stack.videoTimeLineStack)) {
			aClips.addAll(aTL.clipList);
		}
		for(AudioTimeLine aTL : new Vector<AudioTimeLine>(stack.audioTimeLineStack)) {
			aClips.addAll(aTL.clipList);
		}
		return aClips;
	}

	/** Its final rendering in the media list, null if not made yet */
	public StereoMovie renderMedia(TDenlive aTDe) {
		StereoMovie aFound = null;
		for(Media aM : new Vector<Media>(aTDe.mediaList.mediaList)) {
			if(aM instanceof StereoMovie && ((StereoMovie)aM).montageUID == UID && !isMain()) {
				aFound = (StereoMovie)aM;
			}
		}
		return aFound;
	}

	/** The montage rendered by aMedia, null if it is not the rendering of a montage of the project */
	public static Montage of(TDenlive aTDe, Media aMedia) {
		if(!(aMedia instanceof StereoImage) || ((StereoImage)aMedia).montageUID <= 0) {
			return null;
		}
		return aTDe.montageByUID(((StereoImage)aMedia).montageUID);
	}

	/** The montages whose rendering one of its clips shows (directly) */
	public LinkedHashSet<Montage> included(TDenlive aTDe) {
		LinkedHashSet<Montage> aMs = new LinkedHashSet<Montage>();
		for(Clip aC : clips()) {
			Montage aM = of(aTDe, aC.getMedia());
			if(aM != null && aM != this) {
				aMs.add(aM);
			}
		}
		return aMs;
	}

	/** Whether aOther is included in this one, directly or through other montages */
	public boolean includes(TDenlive aTDe, Montage aOther) {
		return includes(aTDe, aOther, new java.util.HashSet<Montage>());
	}

	boolean includes(TDenlive aTDe, Montage aOther, java.util.Set<Montage> aSeen) {
		if(!aSeen.add(this)) {
			return false;
		}
		for(Montage aM : included(aTDe)) {
			if(aM == aOther || aM.includes(aTDe, aOther, aSeen)) {
				return true;
			}
		}
		return false;
	}

	/** The montages that include this one directly */
	public Vector<Montage> includers(TDenlive aTDe) {
		Vector<Montage> aMs = new Vector<Montage>();
		for(Montage aM : new Vector<Montage>(aTDe.montages)) {
			if(aM != this && aM.included(aTDe).contains(this)) {
				aMs.add(aM);
			}
		}
		return aMs;
	}

	/**
	 * Media XML without what does not change its image (out of date flag, name in the media list; for the rendering
	 * of a montage its file, moved into the project folder at the save: its signature says what it shows)
	 */
	static String stableXml(Media aM) throws Exception {
		StringBuffer aSB = new StringBuffer();
		aM.saveProject(aSB);
		String aXml = aSB.toString().replaceAll("<montageStale>[^<]*</montageStale>", "").replaceAll("<libName>[^<]*</libName>", "");
		if(aM instanceof StereoImage && ((StereoImage)aM).montageUID > 0) {
			aXml = aXml.replaceAll("<pathL>[^<]*</pathL>", "").replaceAll("<pathR>[^<]*</pathR>", "");
		}
		return aXml;
	}

	/** Sub folder of the project folder holding the renderings of the montages */
	public static final String DIR = "Montages";

	/**
	 * The renderings of the montages stay in the project: those outside <project folder>/Montages (made before the
	 * first save in ~/3Denlive/Montages, at the root of an unpacked project, in the folder of the project before a
	 * "save as") go into it, moved (copied from the folder of another project), with their work copies and sounds;
	 * the media is loaded again from there, with the same UID (UI thread). Returns the number of files done
	 */
	public static int keepRendersInProject(TDenlive aTDe) {
		String aDir = com.cubaix.TDenlive.utils.ProjectFiles.projectDir(aTDe);
		if(aDir == null) {
			return 0;
		}
		File aMDir = new File(aDir, DIR);
		int aDone = 0;
		for(Media aM : new Vector<Media>(aTDe.mediaList.mediaList)) {
			if(!(aM instanceof StereoMovie) || of(aTDe, aM) == null) {
				continue;
			}
			StereoMovie aOld = (StereoMovie)aM;
			String aP = aOld.pathL;
			if(aP == null || !new File(aP).isFile() || com.cubaix.TDenlive.utils.ProjectFiles.isInside(aP, aMDir.getPath())) {
				continue;
			}
			boolean aMove = com.cubaix.TDenlive.utils.ProjectFiles.isInside(aP, aDir)
					|| com.cubaix.TDenlive.utils.ProjectFiles.isInside(aP, new File(aTDe.tdeDir, DIR).getPath());
			String aNew = com.cubaix.TDenlive.utils.ProjectFiles.importFile(aTDe, aP, aMove, null, aMDir);
			if(aNew.equals(aP)) {
				continue;
			}
			try {
				StereoMovie aR = new StereoMovie(aTDe);
				aR.UID = aOld.UID;
				aR.montageUID = aOld.montageUID;
				aR.montageSig = aOld.montageSig;
				aR.montageStale = aOld.montageStale;
				aR.libName = aOld.libName;
				aR.loadImageP(aNew, com.cubaix.TDenlive.TDConfig.PROCESSING_MODE_RENDER, 0);
				synchronized(aTDe.mediaList.mediaList) {
					int aIdx = aTDe.mediaList.mediaList.indexOf(aOld);
					if(aIdx >= 0) {
						aTDe.mediaList.mediaList.set(aIdx, aR);
					}
					else {
						aTDe.mediaList.mediaList.add(aR);
					}
				}
				replaceMedia(aTDe, aOld, aR);
				aDone++;
			}
			catch(Throwable t) {
				t.printStackTrace(System.err);
			}
		}
		return aDone;
	}

	/**
	 * What its rendering depends on: output settings, its lines and clips with their processors (on / off too), the
	 * medias they use (an included montage by its rendering: its signature changes when it is made again)
	 */
	public String signature(TDenlive aTDe) {
		try {
			StringBuffer aSB = new StringBuffer();
			aSB.append(aTDe.config.outRatio).append(';').append(aTDe.config.outFps).append(';')
				.append(TDConfig.RESVALUES[aTDe.config.renderedDefaultRes]).append('\n');
			stack.saveProject(aSB);
			for(Clip aC : clips()) {
				aSB.append(aC.UID).append(':');
				for(Processor aP : aC.getProcessors()) {
					aSB.append(aP.isActive ? '1' : '0');
				}
				aSB.append('\n');
			}
			//The selection of keys does not change the images
			String aStackXml = aSB.toString().replaceAll("<ProcSelKeys>[^<]*</ProcSelKeys>", "");
			aSB = new StringBuffer(aStackXml);
			for(Media aM : new Vector<Media>(aTDe.mediaList.mediaList)) {
				if(aStackXml.contains(""+aM.UID)) {
					aSB.append(stableXml(aM));
				}
			}
			java.security.MessageDigest aMD = java.security.MessageDigest.getInstance("SHA-1");
			byte[] aH = aMD.digest(aSB.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
			StringBuilder aHex = new StringBuilder();
			for(byte aB : aH) {
				aHex.append(String.format("%02x", aB & 0xff));
			}
			return aHex.toString();
		}
		catch(Throwable t) {
			t.printStackTrace(System.err);
			return "?"+System.nanoTime();
		}
	}

	/** Whether its rendering is made and up to date (refreshStates() first) */
	public boolean isRenderUpToDate(TDenlive aTDe) {
		StereoMovie aR = renderMedia(aTDe);
		return aR != null && !aR.montageStale;
	}

	/**
	 * Out of date flags of all the renderings (StereoImage.montageStale), from the signatures of now. Returns
	 * whether one changed
	 */
	public static boolean refreshStates(TDenlive aTDe) {
		boolean aChanged = false;
		if(aTDe.gui != null && aTDe.gui.history != null && aTDe.gui.history.loading) {
			//A project being opened: afterOpen() decides at its end
			return false;
		}
		for(Montage aM : new Vector<Montage>(aTDe.montages)) {
			if(aM.isMain()) {
				continue;
			}
			StereoMovie aR = aM.renderMedia(aTDe);
			if(aR == null) {
				continue;
			}
			boolean aStale = UNTRUSTED.contains(aR.UID) || aR.montageSig == null || !aR.montageSig.equals(aM.signature(aTDe));
			if(aStale != aR.montageStale) {
				aR.montageStale = aStale;
				aChanged = true;
			}
		}
		return aChanged;
	}

	/**
	 * After the opening of a project: a rendering saved up to date gets the signature of its montage computed here
	 * (the saved one may come from another version), one saved out of date stays so until it is made again
	 */
	public static void afterOpen(TDenlive aTDe) {
		UNTRUSTED.clear();
		for(Montage aM : new Vector<Montage>(aTDe.montages)) {
			StereoMovie aR = aM.isMain() ? null : aM.renderMedia(aTDe);
			if(aR == null) {
				continue;
			}
			if(aR.montageStale) {
				UNTRUSTED.add(aR.UID);
			}
			else {
				aR.montageSig = aM.signature(aTDe);
			}
		}
	}

	/**
	 * The montages to render again before aTarget is rendered or shown, deepest first: those it includes (also
	 * through others) whose rendering is out of date, or which include one rendered again
	 */
	public static Vector<Montage> staleChain(TDenlive aTDe, Montage aTarget) {
		refreshStates(aTDe);
		Vector<Montage> aOrder = new Vector<Montage>();
		java.util.Map<Montage,Boolean> aDone = new java.util.HashMap<Montage,Boolean>();
		for(Montage aM : aTarget.included(aTDe)) {
			needs(aTDe, aM, aOrder, aDone, new java.util.HashSet<Montage>());
		}
		return aOrder;
	}

	/** aOrder with the montages to render for aM, then aM itself if it needs it; returns whether it does */
	public static boolean needs(TDenlive aTDe, Montage aM, Vector<Montage> aOrder, java.util.Map<Montage,Boolean> aDone, java.util.Set<Montage> aPath) {
		Boolean aKnown = aDone.get(aM);
		if(aKnown != null) {
			return aKnown;
		}
		if(!aPath.add(aM)) {
			//A loop (refused when made): not followed
			return false;
		}
		boolean aDep = false;
		for(Montage aI : aM.included(aTDe)) {
			aDep |= needs(aTDe, aI, aOrder, aDone, aPath);
		}
		boolean aNeeds = aDep || !aM.isRenderUpToDate(aTDe);
		if(aNeeds) {
			aOrder.add(aM);
		}
		aDone.put(aM, aNeeds);
		return aNeeds;
	}

	/** <Montage> of the project (not for the main one, saved in <TimeLineList>) */
	public void saveProject(StringBuffer aSB) throws Exception {
		aSB.append("	<Montage>\n");
		aSB.append("		<montageUID>" + UID + "</montageUID>\n");
		aSB.append("		<montageName>" + com.cubaix.TDenlive.shapes.Shape.encode(name == null ? "" : name) + "</montageName>\n");
		stack.saveProject(aSB);
		aSB.append("	</Montage>\n");
	}

	/**
	 * Reads a <Montage> from its tag at o, its lines into a new stack (aStack: one to reuse, emptied first), with
	 * aTDe.timeLineStack being it while its clips are read (twin clips); returns the index of </Montage>
	 */
	public static int openProject(TDenlive aTDe, Vector<XmlObject> aOs, int o, Montage[] aOut, java.util.function.LongFunction<Montage> aReuse) throws Exception {
		long aUID = -1;
		String aName = "";
		Montage aM = null;
		TimeLineStack aActive = aTDe.timeLineStack;
		try {
			for(o++;o < aOs.size();o++) {
				XmlObject aO = aOs.elementAt(o);
				if(!(aO instanceof XmlTag)) {
					continue;
				}
				XmlTag aT = (XmlTag)aO;
				if("/Montage".equalsIgnoreCase(aT.tagName)) {
					break;
				}
				if("montageUID".equalsIgnoreCase(aT.tagName)) {
					aUID = Long.parseLong(aOs.elementAt(++o).text.trim());
					synchronized(Montage.class) {
						lastUID = Math.max(lastUID, aUID);
					}
				}
				else if("montageName".equalsIgnoreCase(aT.tagName)) {
					String aText = aOs.elementAt(o+1).text;
					if(aText != null && !(aOs.elementAt(o+1) instanceof XmlTag)) {
						o++;
						aName = com.cubaix.TDenlive.shapes.Shape.decode(aText.trim());
					}
				}
				else if("TimeLineStack".equalsIgnoreCase(aT.tagName)) {
					aM = aReuse != null && aUID > 0 ? aReuse.apply(aUID) : null;
					TimeLineStack aStack = aM != null ? aM.stack : new TimeLineStack(aTDe);
					aStack.videoTimeLineStack = new Vector<VideoTimeLine>();
					aStack.audioTimeLineStack = new Vector<AudioTimeLine>();
					aTDe.timeLineStack = aStack;
					o = aStack.openProject(aOs, o);
					if(aM == null) {
						aM = new Montage(aUID > 0 ? aUID : nextUID(), aName, aStack);
					}
				}
			}
		}
		finally {
			aTDe.timeLineStack = aActive;
		}
		if(aM != null) {
			aM.name = aName;
		}
		aOut[0] = aM;
		return o;
	}
	/**
	 * Moves aClips of aFrom (with their twins) into this montage, on the lines of the same kind and index (created
	 * if needed), shifted so that the earliest starts at 0. Returns {start of the earliest clip, index of the video
	 * line of the earliest video clip (-1: none)}
	 */
	public long[] moveClipsFrom(TimeLineStack aFrom, Vector<Clip> aClips) {
		java.util.LinkedHashSet<Clip> aAll = new java.util.LinkedHashSet<Clip>();
		for(Clip aC : aClips) {
			aAll.add(aC);
			aAll.addAll(aC.getTwinClips());
		}
		long aMin = Long.MAX_VALUE;
		long aMinVideo = Long.MAX_VALUE;
		int aVideoIdx = -1;
		for(Clip aC : aAll) {
			aMin = Math.min(aMin, aC.getStartTimeMS());
		}
		if(aMin == Long.MAX_VALUE) {
			return new long[] {0, -1};
		}
		for(Clip aC : aAll) {
			VideoTimeLine aTL = aFrom.getLineOf(aC);
			if(aTL == null) {
				continue;
			}
			boolean aAudio = aFrom.audioTimeLineStack.contains(aTL);
			int aIdx = aAudio ? aFrom.audioTimeLineStack.indexOf(aTL) : aFrom.videoTimeLineStack.indexOf(aTL);
			VideoTimeLine aTo;
			if(aAudio) {
				while(stack.audioTimeLineStack.size() <= aIdx) {
					stack.addAudioTimeLine();
				}
				aTo = stack.audioTimeLineStack.elementAt(aIdx);
			}
			else {
				while(stack.videoTimeLineStack.size() <= aIdx) {
					stack.addVideoTimeLine();
				}
				aTo = stack.videoTimeLineStack.elementAt(aIdx);
				if(aC.getStartTimeMS() < aMinVideo) {
					aMinVideo = aC.getStartTimeMS();
					aVideoIdx = aIdx;
				}
			}
			aTo.isActive = aTL.isActive;
			aTL.trashClip(aC);
			aC.selState = com.cubaix.TDenlive.GUI.Drawable.THUMBSELSTATE_NONE;
			aC.setStartTimeMS(aC.getStartTimeMS()-aMin);
			aTo.addClip(aC);
		}
		return new long[] {aMin, aVideoIdx};
	}

	/** A clip of aMedia on the video line aIdx of aStack (created if needed) at aStartMS, selected alone */
	public static Clip placeClip(TimeLineStack aStack, int aIdx, Media aMedia, long aStartMS) {
		aIdx = Math.max(0, aIdx);
		while(aStack.videoTimeLineStack.size() <= aIdx) {
			aStack.addVideoTimeLine();
		}
		VideoTimeLine aLine = aStack.videoTimeLineStack.elementAt(aIdx);
		Clip aClip = aLine.newClip(aMedia);
		aClip.setStartTimeMS(aStartMS);
		aLine.addClip(aClip);
		aStack.clearSelection();
		aClip.selState = com.cubaix.TDenlive.GUI.Drawable.THUMBSELSTATE_SELECTED;
		aStack.tde.selected.clips.add(aClip);
		return aClip;
	}

	/**
	 * The clips of all the montages showing aOld show aNew (a rendering made again): a clip that showed the whole of
	 * aOld shows the whole of aNew, the others are shortened if aNew is shorter
	 */
	public static void replaceMedia(TDenlive aTDe, Media aOld, Media aNew) {
		long aOldD = aOld.getDurationMS();
		long aNewD = aNew.getDurationMS();
		for(TimeLineStack aS : aTDe.allStacks()) {
			for(Clip aC : aS.clipsOf(aOld)) {
				boolean aWhole = aC.shiftTimeMS == 0 && Math.abs(aC.durationMS-aOldD) <= 1;
				aC.media = aNew;
				if(aWhole) {
					aC.durationMS = aNewD;
				}
				else if(aC.shiftTimeMS+aC.durationMS > aNewD) {
					aC.durationMS = Math.max(Clip.MIN_DURATION_MS, aNewD-aC.shiftTimeMS);
				}
			}
		}
	}
}
