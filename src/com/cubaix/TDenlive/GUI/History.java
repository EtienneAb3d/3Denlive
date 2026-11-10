package com.cubaix.TDenlive.GUI;

import java.io.File;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Vector;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.CLabel;
import org.eclipse.swt.custom.ScrolledComposite;
import org.eclipse.swt.graphics.Color;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.widgets.Control;

import com.cubaix.TDenlive.TDenlive;
import com.cubaix.TDenlive.medias.AudioTimeLine;
import com.cubaix.TDenlive.medias.Clip;
import com.cubaix.TDenlive.medias.Media;
import com.cubaix.TDenlive.medias.MediaList;
import com.cubaix.TDenlive.medias.VideoTimeLine;
import com.cubaix.TDenlive.processors.Mixer;
import com.cubaix.TDenlive.processors.Processor;
import com.cubaix.TDenlive.processors.Shapes;
import com.cubaix.TDenlive.shapes.Shape;
import com.cubaix.TDenlive.shapes.ShapesEditor;
import com.cubaix.TDenlive.xml.XmlMinimalParser;
import com.cubaix.TDenlive.xml.XmlObject;
import com.cubaix.TDenlive.xml.XmlTag;

/**
 * Undo / redo of the changes of the montage (at most MAX_OPERATIONS).
 * A change is found by comparing the project XML (plus the processors on/off, not saved in the
 * project) after each interaction of the user, once it is over (mouse released, no event for
 * IDLE_MS): a drag only gives its start and end states. A state keeps the views (time cursor,
 * selection, panels, shapes editor, monitor mode) before and after its change, restored with it.
 */
public class History {
	static final int MAX_OPERATIONS = 100;
	//Quiet time after an event before looking for a change
	static final int IDLE_MS = 500;
	//Changes made in the background (loading of a media...)
	static final int POLL_MS = 1500;

	/**
	 * What the user sees: restored with the state
	 */
	static class ViewState {
		boolean monitorShown = false;
		boolean montageShown = false;
		long shownMediaUID = -1;
		long timePosMS = 0;
		long mediaTimePosMS = 0;
		boolean processorsShown = false;
		long processorsClipUID = -1;
		boolean[] expanded = null;
		Point processorsScroll = null;
		long propsMediaUID = -1;
		Vector<Long> selClips = new Vector<Long>();
		Vector<Long> selMedias = new Vector<Long>();
		Vector<Integer> selVideoLines = new Vector<Integer>();
		Vector<Integer> selAudioLines = new Vector<Integer>();
		int shapeSelectedId = -1;
		int shapeTool = -1;
		Shape shapeStyle = null;
		Mixer mixer = null;
		int grid = 0;
		boolean edges = false;
		Color glassesBck = null;
		int processingMode = 0;
		Point timeLinesOrigin = null;
		double pixPerMS = 0;
		Point mediaListOrigin = null;
	}

	/**
	 * A state of the montage
	 */
	static class State {
		//Compared to find the changes
		String xml = null;
		//Processors on/off of each clip (UID -> "1011")
		LinkedHashMap<Long,String> actives = new LinkedHashMap<Long,String>();
		//Medias, and their XML: an unchanged media is not loaded again
		Media[] medias = null;
		String[] mediaXmls = null;
		//What the change to this state did (key expression of the buttons help)
		String label = "";
		ViewState viewBefore = null;
		ViewState viewAfter = null;

		String compared() {
			return xml+actives.toString();
		}
	}

	TDenlive tde = null;
	Vector<State> states = new Vector<State>();
	int current = -1;
	//A project is being opened: nothing is recorded until reset()
	public boolean loading = true;
	boolean restoring = false;
	boolean mouseDown = false;
	boolean checkScheduled = false;
	ViewState lastView = null;
	CLabel undoButton = null;
	CLabel redoButton = null;
	Runnable checkRunnable = () -> check();
	//Control of the last mouse down, and what to do if it is disposed before the mouse up
	org.eclipse.swt.widgets.Widget mouseDownWidget = null;
	org.eclipse.swt.widgets.Listener mouseDownDisposed = e -> {
		mouseDownWidget = null;
		mouseDown = false;
		scheduleCheck();
	};
	Runnable pollRunnable = null;

	public History(TDenlive aTDe) {
		tde = aTDe;
		installFilters();
	}

	void installFilters() {
		org.eclipse.swt.widgets.Display aD = tde.gui.display;
		aD.addFilter(SWT.MouseDown, e -> {
			//The change of the previous interaction, before this one starts
			if(checkScheduled) {
				check();
			}
			mouseDown = true;
			//A click that rebuilds its panel disposes the clicked control: its mouse up never comes
			if(mouseDownWidget != null && !mouseDownWidget.isDisposed()) {
				mouseDownWidget.removeListener(SWT.Dispose, mouseDownDisposed);
			}
			mouseDownWidget = e.widget;
			if(mouseDownWidget != null && !mouseDownWidget.isDisposed()) {
				mouseDownWidget.addListener(SWT.Dispose, mouseDownDisposed);
			}
		});
		aD.addFilter(SWT.MouseUp, e -> mouseReleased());
		//Mouse up lost (control disposed, dialog opened by the click): a move without button tells it
		aD.addFilter(SWT.MouseMove, e -> {
			if(mouseDown && (e.stateMask & SWT.BUTTON_MASK) == 0) {
				mouseReleased();
			}
		});
		for(int aType : new int[] {SWT.KeyUp, SWT.Selection, SWT.DefaultSelection, SWT.MouseWheel, SWT.FocusOut}) {
			aD.addFilter(aType, e -> scheduleCheck());
		}
		//Ctrl+Z / Shift+Ctrl+Z (Cmd on macOS), in the main window
		aD.addFilter(SWT.KeyDown, e -> {
			if((e.stateMask & (SWT.MOD1|SWT.CTRL)) == 0 || (e.keyCode != 'z' && e.keyCode != 'Z')) {
				return;
			}
			if(tde.gui.shell == null || aD.getActiveShell() != tde.gui.shell) {
				return;
			}
			e.doit = false;
			e.type = SWT.None;
			if((e.stateMask & SWT.SHIFT) != 0) {
				redo();
			}
			else {
				undo();
			}
		});
		pollRunnable = () -> {
			if(aD.isDisposed()) {
				return;
			}
			if(!mouseDown && !checkScheduled) {
				check();
			}
			aD.timerExec(POLL_MS, pollRunnable);
		};
		aD.timerExec(POLL_MS, pollRunnable);
	}

	void mouseReleased() {
		mouseDown = false;
		if(mouseDownWidget != null && !mouseDownWidget.isDisposed()) {
			mouseDownWidget.removeListener(SWT.Dispose, mouseDownDisposed);
		}
		mouseDownWidget = null;
		scheduleCheck();
	}

	void scheduleCheck() {
		checkScheduled = true;
		tde.gui.display.timerExec(IDLE_MS, checkRunnable);
	}

	/**
	 * Forget the history: the current montage is the first state (project opened)
	 */
	public void reset() {
		states.clear();
		current = -1;
		loading = false;
		try {
			State aS = capture();
			aS.viewAfter = captureView();
			states.add(aS);
			current = 0;
			lastView = aS.viewAfter;
		}
		catch(Throwable t) {
			t.printStackTrace(System.err);
		}
		updateButtons();
	}

	/**
	 * Record the current montage if it changed since the current state
	 */
	void check() {
		checkScheduled = false;
		tde.gui.display.timerExec(-1, checkRunnable);
		if(loading || restoring || mouseDown || current < 0 || tde.timeLineStack == null || tde.mediaList == null) {
			return;
		}
		try {
			State aCur = states.elementAt(current);
			State aNew = capture();
			ViewState aView = captureView();
			if(aNew.compared().equals(aCur.compared())) {
				lastView = aView;
				return;
			}
			aNew.viewBefore = lastView != null ? lastView : aCur.viewAfter;
			aNew.viewAfter = aView;
			aNew.label = describe(aCur, aNew);
			while(states.size() > current+1) {
				states.remove(states.size()-1);
			}
			states.add(aNew);
			while(states.size() > MAX_OPERATIONS+1) {
				states.remove(0);
			}
			current = states.size()-1;
			lastView = aView;
			updateButtons();
		}
		catch(Throwable t) {
			t.printStackTrace(System.err);
		}
	}

	public boolean canUndo() {
		return current > 0;
	}

	public boolean canRedo() {
		return current >= 0 && current < states.size()-1;
	}

	public void undo() {
		check();
		if(!canUndo()) {
			return;
		}
		State aFrom = states.elementAt(current);
		current--;
		restore(states.elementAt(current), aFrom.viewBefore);
	}

	public void redo() {
		check();
		if(!canRedo()) {
			return;
		}
		current++;
		State aTo = states.elementAt(current);
		restore(aTo, aTo.viewAfter);
	}

	//---------------------------------------------------------------- States

	static String mediaXml(Media aM) throws Exception {
		StringBuffer aSB = new StringBuffer();
		aM.saveProject(aSB);
		return aSB.toString();
	}

	Vector<Clip> allClips() {
		Vector<Clip> aClips = new Vector<Clip>();
		for(VideoTimeLine aTL : new Vector<VideoTimeLine>(tde.timeLineStack.videoTimeLineStack)) {
			aClips.addAll(aTL.clipList);
		}
		for(AudioTimeLine aTL : new Vector<AudioTimeLine>(tde.timeLineStack.audioTimeLineStack)) {
			aClips.addAll(aTL.clipList);
		}
		return aClips;
	}

	State capture() throws Exception {
		State aS = new State();
		aS.xml = tde.projectXml();
		Vector<Media> aMs = new Vector<Media>(tde.mediaList.mediaList);
		aS.medias = aMs.toArray(new Media[0]);
		aS.mediaXmls = new String[aS.medias.length];
		for(int m = 0;m < aS.medias.length;m++) {
			aS.mediaXmls[m] = mediaXml(aS.medias[m]);
		}
		for(Clip aC : allClips()) {
			StringBuilder aSB = new StringBuilder();
			for(Processor aP : aC.getProcessors()) {
				aSB.append(aP.isActive ? '1' : '0');
			}
			aS.actives.put(aC.UID, aSB.toString());
		}
		return aS;
	}

	/**
	 * Put the montage back in the state aS, and the views in aView
	 */
	void restore(State aS,ViewState aView) {
		restoring = true;
		TDeGUI aGUI = tde.gui;
		try {
			if(aGUI.playerControlGUI != null && (aGUI.playerControlGUI.playing || aGUI.playerControlGUI.paused)) {
				aGUI.playerControlGUI.setPlaying(false);
			}
			//Medias: the unchanged ones are kept (not loaded again)
			Vector<Media> aMedias = new Vector<Media>();
			for(int m = 0;m < aS.medias.length;m++) {
				Media aM = aS.medias[m];
				if(!mediaXml(aM).equals(aS.mediaXmls[m])) {
					aM = null;
					for(Media aLive : tde.mediaList.mediaList) {
						if(aLive.UID == aS.medias[m].UID && mediaXml(aLive).equals(aS.mediaXmls[m])) {
							aM = aLive;
							break;
						}
					}
				}
				if(aM == null) {
					MediaList aTmp = new MediaList(tde);
					aTmp.openProject(new XmlMinimalParser().parseXml(aS.mediaXmls[m]), 0);
					if(aTmp.mediaList.size() <= 0) {
						continue;
					}
					aM = aTmp.mediaList.firstElement();
				}
				aMedias.add(aM);
			}
			//New vectors: the threads still drawing the old ones are not disturbed
			tde.mediaList.mediaList = aMedias;
			Vector<XmlObject> aOs = new XmlMinimalParser().parseXml(aS.xml);
			tde.timeLineStack.videoTimeLineStack = new Vector<VideoTimeLine>();
			tde.timeLineStack.audioTimeLineStack = new Vector<AudioTimeLine>();
			for(int o = 0;o < aOs.size();o++) {
				if(!(aOs.elementAt(o) instanceof XmlTag)) {
					continue;
				}
				XmlTag aT = (XmlTag)aOs.elementAt(o);
				if("outRatio".equalsIgnoreCase(aT.tagName)) {
					tde.config.outRatio = Double.parseDouble(aOs.elementAt(++o).text);
				}
				else if("outRatioName".equalsIgnoreCase(aT.tagName)) {
					tde.config.outRatioName = aOs.elementAt(++o).text;
				}
				else if("TimeLineList".equalsIgnoreCase(aT.tagName)) {
					o = tde.timeLineStack.openProject(aOs, o);
				}
			}
			for(Clip aC : allClips()) {
				String aFlags = aS.actives.get(aC.UID);
				Vector<Processor> aPs = aC.getProcessors();
				for(int p = 0;aFlags != null && p < aPs.size() && p < aFlags.length();p++) {
					aPs.elementAt(p).isActive = aFlags.charAt(p) == '1';
				}
			}
			tde.updateFirstMediaRatio();
			if(aGUI.monitorControlGUI != null) {
				aGUI.monitorControlGUI.setRatio(tde.config.outRatioName);
			}
			applyView(aView);
			//What the montage gives now (an exact copy, unless the saving is not stable): not a change
			State aNow = capture();
			aS.xml = aNow.xml;
			aS.actives = aNow.actives;
		}
		catch(Throwable t) {
			t.printStackTrace(System.err);
		}
		finally {
			restoring = false;
		}
		lastView = captureView();
		updateButtons();
		aGUI.mediaListGUI.redraw();
		aGUI.timeLinesGUI.redraw();
		if(aGUI.monitorGUI != null) {
			aGUI.monitorGUI.redraw();
		}
	}

	//---------------------------------------------------------------- Views

	Clip shownProcessorsClip() {
		ProcessorsGUI aPG = tde.gui.processorGUI;
		if(aPG == null || aPG.container.isDisposed() || aPG.container.getChildren().length <= 0
				|| aPG.processors == null || aPG.processors.size() <= 0) {
			return null;
		}
		Media aTarget = aPG.processors.firstElement().targetMedia;
		return aTarget instanceof Clip ? (Clip)aTarget : null;
	}

	static Shapes shapesOf(Clip aClip) {
		if(aClip == null) {
			return null;
		}
		for(Processor aP : aClip.getProcessors()) {
			if(aP instanceof Shapes) {
				return (Shapes)aP;
			}
		}
		return null;
	}

	ViewState captureView() {
		ViewState aV = new ViewState();
		TDeGUI aGUI = tde.gui;
		try {
			MonitorGUI aMon = aGUI.monitorGUI;
			if(aMon != null && aMon.canvas != null && !aMon.canvas.isDisposed()) {
				aV.monitorShown = true;
				Media aShown = aMon.getMedia();
				aV.montageShown = aShown == tde.timeLineStack;
				if(aShown != null && !aV.montageShown) {
					aV.shownMediaUID = aShown.UID;
					aV.mediaTimePosMS = aShown.getRequestedTimePosMS();
				}
				aV.mixer = aMon.mixer;
				aV.grid = aMon.grid;
				aV.processingMode = aMon.processingMode;
				MonitorControlGUI aMC = aGUI.monitorControlGUI;
				if(aMC != null && !aMC.glasses.isDisposed()) {
					aV.edges = aMC.edgesViewOn;
					aV.glassesBck = aMC.wobble > 0 ? null : aMC.glasses.getBackground();
				}
			}
			aV.timePosMS = tde.timeLineStack.getRequestedTimePosMS();
			Clip aShownClip = shownProcessorsClip();
			if(aShownClip != null) {
				aV.processorsShown = true;
				aV.processorsClipUID = aShownClip.UID;
				Vector<Processor> aPs = aShownClip.getProcessors();
				aV.expanded = new boolean[aPs.size()];
				for(int p = 0;p < aPs.size();p++) {
					aV.expanded[p] = aPs.elementAt(p).isExpended;
				}
				Control[] aCs = aGUI.processorGUI.container.getChildren();
				if(aCs.length > 0 && aCs[0] instanceof ScrolledComposite) {
					aV.processorsScroll = ((ScrolledComposite)aCs[0]).getOrigin();
				}
			}
			else if(aGUI.propsGUI != null && aGUI.propsGUI.media != null) {
				aV.propsMediaUID = aGUI.propsGUI.media.UID;
			}
			for(Clip aC : tde.selected.clips) {
				aV.selClips.add(aC.UID);
			}
			for(Media aM : tde.selected.medias) {
				aV.selMedias.add(aM.UID);
			}
			for(VideoTimeLine aTL : tde.selected.timeLines) {
				int aI = tde.timeLineStack.videoTimeLineStack.indexOf(aTL);
				if(aI >= 0) {
					aV.selVideoLines.add(aI);
				}
				else {
					aI = tde.timeLineStack.audioTimeLineStack.indexOf(aTL);
					if(aI >= 0) {
						aV.selAudioLines.add(aI);
					}
				}
			}
			Shapes aSh = shapesOf(tde.selected.clips.size() > 0 ? tde.selected.clips.lastElement() : null);
			if(aSh != null) {
				ShapesEditor aE = aSh.getEditor();
				aV.shapeSelectedId = aE.getSelected() != null ? aE.getSelected().id : -1;
				aV.shapeTool = aE.getTool();
				aV.shapeStyle = aE.style.copy();
			}
			if(aGUI.timeLinesGUI != null) {
				aV.timeLinesOrigin = new Point(aGUI.timeLinesGUI.origin.x, aGUI.timeLinesGUI.origin.y);
			}
			aV.pixPerMS = tde.timeLineStack.pixPerMS;
			if(aGUI.mediaListGUI != null) {
				aV.mediaListOrigin = new Point(aGUI.mediaListGUI.origin.x, aGUI.mediaListGUI.origin.y);
			}
		}
		catch(Throwable t) {
			t.printStackTrace(System.err);
		}
		return aV;
	}

	void applyView(ViewState aV) {
		if(aV == null) {
			return;
		}
		TDeGUI aGUI = tde.gui;
		//Selection
		tde.selected.medias.clear();
		for(Media aM : tde.mediaList.mediaList) {
			aM.selState = aV.selMedias.contains(aM.UID) ? Drawable.THUMBSELSTATE_SELECTED : Drawable.THUMBSELSTATE_NONE;
		}
		for(Long aUID : aV.selMedias) {
			Media aM = tde.mediaList.getMediaByUID(aUID);
			if(aM != null) {
				tde.selected.medias.add(aM);
			}
		}
		tde.selected.clips.clear();
		for(Clip aC : allClips()) {
			aC.selState = aV.selClips.contains(aC.UID) ? Drawable.THUMBSELSTATE_SELECTED : Drawable.THUMBSELSTATE_NONE;
		}
		for(Long aUID : aV.selClips) {
			Clip aC = tde.timeLineStack.getClipByUID(aUID);
			if(aC != null) {
				tde.selected.clips.add(aC);
			}
		}
		tde.selected.timeLines.clear();
		Vector<VideoTimeLine> aVTLs = tde.timeLineStack.videoTimeLineStack;
		for(int t = 0;t < aVTLs.size();t++) {
			boolean aSel = aV.selVideoLines.contains(t);
			aVTLs.elementAt(t).selState = aSel ? Drawable.THUMBSELSTATE_SELECTED : Drawable.THUMBSELSTATE_NONE;
			if(aSel) {
				tde.selected.timeLines.add(aVTLs.elementAt(t));
			}
		}
		Vector<AudioTimeLine> aATLs = tde.timeLineStack.audioTimeLineStack;
		for(int t = 0;t < aATLs.size();t++) {
			boolean aSel = aV.selAudioLines.contains(t);
			aATLs.elementAt(t).selState = aSel ? Drawable.THUMBSELSTATE_SELECTED : Drawable.THUMBSELSTATE_NONE;
			if(aSel) {
				tde.selected.timeLines.add(aATLs.elementAt(t));
			}
		}

		//Time cursor
		tde.timeLineStack.setRequestedTimePosMS(aV.timePosMS);

		//Monitor
		if(aV.monitorShown) {
			aGUI.setMultiMonitorPanel(TDeGUI.MULTIMONITORPANEL_MONITOR);
		}
		if(aGUI.monitorGUI != null) {
			MonitorGUI aMon = aGUI.monitorGUI;
			MonitorControlGUI aMC = aGUI.monitorControlGUI;
			if(aV.monitorShown) {
				boolean aWobble = aMC != null && aMC.wobble > 0;
				if(aV.mixer != null && !aWobble) {
					aMon.mixer = aV.mixer;
				}
				aMon.grid = aV.grid;
				aMon.processingMode = aV.processingMode;
				if(aMC != null && !aMC.glasses.isDisposed()) {
					if(aMC.edgesViewOn != aV.edges) {
						aMC.setEdgesViewOn(aV.edges);
						aMon.setEdgeDetector(aV.edges);
					}
					if(aV.glassesBck != null && !aWobble) {
						aMC.glasses.setBackground(aV.glassesBck);
					}
				}
			}
			if(aV.montageShown) {
				aMon.setMedia(tde.timeLineStack);
				aGUI.playerControlGUI.showClock(aV.timePosMS);
			}
			else if(aV.shownMediaUID >= 0 && tde.mediaList.getMediaByUID(aV.shownMediaUID) != null) {
				Media aM = tde.mediaList.getMediaByUID(aV.shownMediaUID);
				aM.setRequestedTimePosMS(aV.mediaTimePosMS);
				aMon.setMedia(aM);
				aGUI.playerControlGUI.showClock(aV.mediaTimePosMS);
			}
			else {
				aMon.clean();
			}
		}

		//Processors or properties panel
		Clip aShownClip = aV.processorsShown ? tde.timeLineStack.getClipByUID(aV.processorsClipUID) : null;
		if(aV.processorsShown) {
			aGUI.setMultiPropsPanel(TDeGUI.MULTIPROPSPANEL_PROCESSORS);
			if(aShownClip != null) {
				Vector<Processor> aPs = aShownClip.getProcessors();
				for(int p = 0;aV.expanded != null && p < aPs.size() && p < aV.expanded.length;p++) {
					aPs.elementAt(p).isExpended = aV.expanded[p];
				}
			}
		}
		//Shapes editor (before building the processors panel, which shows its selection)
		Shapes aSh = shapesOf(tde.selected.clips.size() > 0 ? tde.selected.clips.lastElement() : null);
		if(aSh != null) {
			ShapesEditor aE = aSh.getEditor();
			if(aV.shapeStyle != null) {
				aE.style = aV.shapeStyle.copy();
			}
			aE.setTool(aV.shapeTool);
			if(aV.shapeTool < 0) {
				aE.select(aV.shapeSelectedId);
			}
		}
		if(aV.processorsShown) {
			if(aShownClip != null) {
				aGUI.processorGUI.setProcessors(aShownClip.getProcessors());
				Point aScroll = aV.processorsScroll;
				if(aScroll != null) {
					aGUI.display.asyncExec(() -> {
						if(aGUI.processorGUI == null || aGUI.processorGUI.container.isDisposed()) {
							return;
						}
						Control[] aCs = aGUI.processorGUI.container.getChildren();
						if(aCs.length > 0 && aCs[0] instanceof ScrolledComposite) {
							((ScrolledComposite)aCs[0]).setOrigin(aScroll);
						}
					});
				}
			}
			else {
				aGUI.processorGUI.clean();
			}
		}
		else {
			aGUI.setMultiPropsPanel(TDeGUI.MULTIPROPSPANEL_PROPS);
			Media aM = aV.propsMediaUID >= 0 ? tde.mediaList.getMediaByUID(aV.propsMediaUID) : null;
			if(aM != null) {
				aGUI.propsGUI.setMedia(aM);
			}
			else {
				aGUI.propsGUI.clean();
			}
		}

		//Scrolling and time scale of the lists
		if(aGUI.timeLinesGUI != null && aV.timeLinesOrigin != null) {
			if(aV.pixPerMS > 0) {
				tde.timeLineStack.pixPerMS = aV.pixPerMS;
				aGUI.timeLinesGUI.showZoom();
			}
			aGUI.timeLinesGUI.origin.x = aV.timeLinesOrigin.x;
			aGUI.timeLinesGUI.origin.y = aV.timeLinesOrigin.y;
			//The scrolling is restored with the cursor: not moved to follow it
			aGUI.timeLinesGUI.lastFollowedTimeMS = aV.timePosMS;
		}
		if(aGUI.mediaListGUI != null && aV.mediaListOrigin != null) {
			aGUI.mediaListGUI.origin.x = aV.mediaListOrigin.x;
			aGUI.mediaListGUI.origin.y = aV.mediaListOrigin.y;
		}
	}

	//---------------------------------------------------------------- Buttons

	void setButtons(CLabel aUndo,CLabel aRedo) {
		undoButton = aUndo;
		redoButton = aRedo;
		undoButton.addListener(SWT.MouseDown, e -> undo());
		redoButton.addListener(SWT.MouseDown, e -> redo());
		updateButtons();
	}

	static String shortcut(boolean aRedo) {
		boolean aMac = "cocoa".equals(SWT.getPlatform());
		return (aRedo ? (aMac ? "Shift+Cmd+" : "Shift+Ctrl+") : (aMac ? "Cmd+" : "Ctrl+"))+"Z";
	}

	void updateButtons() {
		if(undoButton == null || undoButton.isDisposed() || redoButton == null || redoButton.isDisposed()) {
			return;
		}
		com.cubaix.TDenlive.res.Lngs aL = tde.gui.lngs;
		boolean aCanUndo = canUndo();
		boolean aCanRedo = canRedo();
		undoButton.setImage(tde.gui.imgsSwt.getIcon(aCanUndo ? "undo.png" : "undoOff.png"));
		redoButton.setImage(tde.gui.imgsSwt.getIcon(aCanRedo ? "redo.png" : "redoOff.png"));
		undoButton.setToolTipText(aL.get("History.UndoTip").replace("%k", shortcut(false))
				.replace("%s", aCanUndo ? states.elementAt(current).label : aL.get("History.NothingToUndo")));
		redoButton.setToolTipText(aL.get("History.RedoTip").replace("%k", shortcut(true))
				.replace("%s", aCanRedo ? states.elementAt(current+1).label : aL.get("History.NothingToRedo")));
	}

	//---------------------------------------------------------------- Description of a change

	/**
	 * A clip in the project XML: its line, its fields and its processors
	 */
	static class ClipXml {
		long uid = -1;
		long mediaUID = -1;
		String line = "";
		String xml = "";
		HashMap<String,String> fields = new HashMap<String,String>();
		Vector<String> procNames = new Vector<String>();
		Vector<String> procXmls = new Vector<String>();
	}

	static final Pattern FIELD_RE = Pattern.compile("^\\s*<([A-Za-z]+)>(.*)</\\1>\\s*$");
	static final Pattern MEDIA_UID_RE = Pattern.compile("<Media UID='(-?\\d+)'>");

	/**
	 * Clips of a project XML (by UID), and the fields of its lines (by line name) in aLines
	 */
	static LinkedHashMap<Long,ClipXml> parseClips(String aXml,LinkedHashMap<String,String> aLines) {
		LinkedHashMap<Long,ClipXml> aClips = new LinkedHashMap<Long,ClipXml>();
		int aV = 0;
		int aA = 0;
		String aLine = "";
		ClipXml aClip = null;
		boolean aInProcs = false;
		StringBuilder aProc = null;
		for(String aL : aXml.split("\n")) {
			String aT = aL.trim();
			if(aClip == null) {
				if(aT.equals("<VideoTimeLine>") || aT.equals("<TimeLine>")) {
					aLine = "V"+(++aV);
					aLines.put(aLine, "");
				}
				else if(aT.equals("<AudioTimeLine>")) {
					aLine = "A"+(++aA);
					aLines.put(aLine, "");
				}
				else if(aT.equals("<Clip>")) {
					aClip = new ClipXml();
					aClip.line = aLine;
				}
				else if(!aLine.isEmpty() && aT.startsWith("<") && !aT.startsWith("</")) {
					aLines.put(aLine, aLines.get(aLine)+aT);
				}
				continue;
			}
			aClip.xml += aL+"\n";
			if(aT.equals("</Clip>")) {
				aClips.put(aClip.uid, aClip);
				aClip = null;
				continue;
			}
			if(aT.equals("<Processors>")) {
				aInProcs = true;
				continue;
			}
			if(aT.equals("</Processors>")) {
				aInProcs = false;
				continue;
			}
			if(aInProcs) {
				//A processor: its tag alone on its line, at the first level of the processors
				if(aProc == null && aT.matches("<[A-Za-z]+>")) {
					aClip.procNames.add(aT.substring(1, aT.length()-1));
					aProc = new StringBuilder();
				}
				else if(aProc != null && aT.equals("</"+aClip.procNames.lastElement()+">")) {
					aClip.procXmls.add(aProc.toString());
					aProc = null;
				}
				else if(aProc != null) {
					aProc.append(aL).append("\n");
				}
				continue;
			}
			Matcher aM = MEDIA_UID_RE.matcher(aT);
			if(aM.find()) {
				aClip.mediaUID = Long.parseLong(aM.group(1));
				continue;
			}
			aM = FIELD_RE.matcher(aL);
			if(aM.matches()) {
				if("UID".equals(aM.group(1)) && aClip.uid < 0) {
					try {
						aClip.uid = Long.parseLong(aM.group(2));
					}
					catch(Throwable t) {}
				}
				else {
					aClip.fields.put(aM.group(1), aM.group(2));
				}
			}
		}
		return aClips;
	}

	static String mediaName(Media aM) {
		if(aM == null) {
			return "";
		}
		if(aM.fileName != null && !aM.fileName.trim().isEmpty()) {
			return new File(aM.fileName).getName();
		}
		return aM.getClass().getSimpleName();
	}

	static Media findMedia(long aUID,State... aStates) {
		for(State aS : aStates) {
			for(Media aM : aS.medias) {
				if(aM.UID == aUID) {
					return aM;
				}
			}
		}
		return null;
	}

	String procName(String aTag) {
		String aKey = aTag;
		if("StereoAligner".equals(aTag)) {
			aKey = "Align";
		}
		else if("ColorAdapter".equals(aTag)) {
			aKey = "AdaptColor";
		}
		else if("TimeController".equals(aTag)) {
			aKey = "TimeControler";
		}
		return tde.gui.lngs.get("Processor."+aKey);
	}

	String text(String aKey,String aS,String aP,String aL) {
		String aText = tde.gui.lngs.get("History."+aKey);
		return aText.replace("%s", aS == null ? "" : aS).replace("%p", aP == null ? "" : aP).replace("%l", aL == null ? "" : aL);
	}

	/**
	 * Key expression of the change from aA to aB
	 */
	String describe(State aA,State aB) {
		try {
			//Medias added / removed
			for(Media aM : aB.medias) {
				if(findMedia(aM.UID, aA) == null) {
					return text("AddMedia", mediaName(aM), null, null);
				}
			}
			for(Media aM : aA.medias) {
				if(findMedia(aM.UID, aB) == null) {
					return text("DeleteMedia", mediaName(aM), null, null);
				}
			}
			LinkedHashMap<String,String> aLinesA = new LinkedHashMap<String,String>();
			LinkedHashMap<String,String> aLinesB = new LinkedHashMap<String,String>();
			LinkedHashMap<Long,ClipXml> aCA = parseClips(aA.xml, aLinesA);
			LinkedHashMap<Long,ClipXml> aCB = parseClips(aB.xml, aLinesB);
			Vector<ClipXml> aAdded = new Vector<ClipXml>();
			Vector<ClipXml> aRemoved = new Vector<ClipXml>();
			for(ClipXml aC : aCB.values()) {
				if(!aCA.containsKey(aC.uid)) {
					aAdded.add(aC);
				}
			}
			for(ClipXml aC : aCA.values()) {
				if(!aCB.containsKey(aC.uid)) {
					aRemoved.add(aC);
				}
			}
			if(aAdded.size() > 0 && aRemoved.size() <= 0) {
				//A cut: the clip cut gets shorter
				for(ClipXml aC : aCB.values()) {
					ClipXml aOld = aCA.get(aC.uid);
					if(aOld != null && aC.fields.get("durationMS") != null && !aC.fields.get("durationMS").equals(aOld.fields.get("durationMS"))) {
						return text("CutClip", mediaName(findMedia(aC.mediaUID, aB, aA)), null, aC.line);
					}
				}
				ClipXml aC = aAdded.firstElement();
				return text("AddClip", mediaName(findMedia(aC.mediaUID, aB, aA)), null, aC.line);
			}
			if(aRemoved.size() > 0 && aAdded.size() <= 0) {
				ClipXml aC = aRemoved.firstElement();
				return text("DeleteClip", mediaName(findMedia(aC.mediaUID, aA, aB)), null, aC.line);
			}
			if(aRemoved.size() > 0) {
				return text("Clips", null, null, null);
			}
			//Lines added / removed / changed
			if(aLinesB.size() > aLinesA.size()) {
				return text("AddLine", null, null, null);
			}
			if(aLinesB.size() < aLinesA.size()) {
				return text("DeleteLine", null, null, null);
			}
			//A clip changed
			for(ClipXml aC : aCB.values()) {
				ClipXml aOld = aCA.get(aC.uid);
				String aActA = aA.actives.get(aC.uid);
				String aActB = aB.actives.get(aC.uid);
				boolean aActChanged = aActA != null && aActB != null && !aActA.equals(aActB);
				if(aC.xml.equals(aOld.xml) && aC.line.equals(aOld.line) && !aActChanged) {
					continue;
				}
				String aName = mediaName(findMedia(aC.mediaUID, aB, aA));
				if(!aC.line.equals(aOld.line)) {
					return text("MoveClipLine", aName, null, aC.line);
				}
				//Processors
				if(aC.procNames.size() > aOld.procNames.size()) {
					return text("AddProc", aName, procName(firstDiff(aC.procNames, aOld.procNames)), aC.line);
				}
				if(aC.procNames.size() < aOld.procNames.size()) {
					return text("DeleteProc", aName, procName(firstDiff(aOld.procNames, aC.procNames)), aC.line);
				}
				if(!aC.procNames.equals(aOld.procNames)) {
					return text("MoveProc", aName, procName(firstDiff(aC.procNames, aOld.procNames)), aC.line);
				}
				for(int p = 0;p < aC.procXmls.size() && p < aOld.procXmls.size();p++) {
					if(!aC.procXmls.elementAt(p).equals(aOld.procXmls.elementAt(p))) {
						String aTag = aC.procNames.elementAt(p);
						if("Shapes".equals(aTag)) {
							return text("EditShapes", aName, procName(aTag), aC.line);
						}
						return text("EditProc", aName, procName(aTag), aC.line);
					}
				}
				//Clip fields
				if(!same(aC, aOld, "durationMS") || !same(aC, aOld, "shiftTimeMS")) {
					return text("TrimClip", aName, null, aC.line);
				}
				if(!same(aC, aOld, "startTimeMS")) {
					return text("MoveClip", aName, null, aC.line);
				}
				if(!same(aC, aOld, "fadeInMS") || !same(aC, aOld, "fadeOutMS")) {
					return text("FadeClip", aName, null, aC.line);
				}
				if(!same(aC, aOld, "soundOn")) {
					return text("ClipSound", aName, null, aC.line);
				}
				if(aActChanged) {
					//One flag per processor of the clip, in its order
					for(int p = 0;p < aActA.length() && p < aActB.length();p++) {
						if(aActA.charAt(p) != aActB.charAt(p)) {
							Clip aLive = tde.timeLineStack.getClipByUID(aC.uid);
							String aPName = aLive != null && p < aLive.getProcessors().size() ? aLive.getProcessors().elementAt(p).name : "";
							return text(aActB.charAt(p) == '1' ? "ProcOn" : "ProcOff", aName, aPName, aC.line);
						}
					}
				}
				return text("EditClip", aName, null, aC.line);
			}
			//Clips order on a line
			if(!new Vector<Long>(aCA.keySet()).equals(new Vector<Long>(aCB.keySet()))) {
				return text("Clips", null, null, null);
			}
			for(String aL : aLinesB.keySet()) {
				if(!aLinesB.get(aL).equals(aLinesA.get(aL))) {
					return text("EditLine", null, null, aL);
				}
			}
			//A media changed (rotation...)
			for(int m = 0;m < aB.medias.length;m++) {
				Media aM = aB.medias[m];
				for(int n = 0;n < aA.medias.length;n++) {
					if(aA.medias[n].UID == aM.UID && !aA.mediaXmls[n].equals(aB.mediaXmls[m])) {
						return text("EditMedia", mediaName(aM), null, null);
					}
				}
			}
			if(!aA.xml.substring(0, Math.max(0, aA.xml.indexOf("<MediaList>"))).equals(aB.xml.substring(0, Math.max(0, aB.xml.indexOf("<MediaList>"))))) {
				return text("Ratio", null, null, null);
			}
			if(aA.medias.length == aB.medias.length) {
				for(int m = 0;m < aA.medias.length;m++) {
					if(aA.medias[m].UID != aB.medias[m].UID) {
						return text("MoveMedia", mediaName(aB.medias[m]), null, null);
					}
				}
			}
		}
		catch(Throwable t) {
			t.printStackTrace(System.err);
		}
		return text("Change", null, null, null);
	}

	static boolean same(ClipXml aA,ClipXml aB,String aField) {
		String aVA = aA.fields.get(aField);
		String aVB = aB.fields.get(aField);
		return aVA == null ? aVB == null : aVA.equals(aVB);
	}

	/**
	 * First name of aLonger not at the same place in aOther
	 */
	static String firstDiff(Vector<String> aLonger,Vector<String> aOther) {
		for(int i = 0;i < aLonger.size();i++) {
			if(i >= aOther.size() || !aLonger.elementAt(i).equals(aOther.elementAt(i))) {
				return aLonger.elementAt(i);
			}
		}
		return aLonger.size() > 0 ? aLonger.lastElement() : "";
	}
}
