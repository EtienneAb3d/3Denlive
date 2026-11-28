package com.cubaix.TDenlive.GUI;

import java.util.Vector;

import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.CLabel;
import org.eclipse.swt.dnd.DND;
import org.eclipse.swt.dnd.DragSource;
import org.eclipse.swt.dnd.DragSourceAdapter;
import org.eclipse.swt.dnd.DragSourceEvent;
import org.eclipse.swt.dnd.TextTransfer;
import org.eclipse.swt.dnd.Transfer;
import org.eclipse.swt.graphics.GC;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.graphics.Rectangle;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.MessageBox;

import com.cubaix.TDenlive.TDenlive;
import com.cubaix.TDenlive.medias.Clip;
import com.cubaix.TDenlive.medias.Media;
import com.cubaix.TDenlive.medias.Montage;
import com.cubaix.TDenlive.medias.StereoMovie;
import com.cubaix.TDenlive.medias.TimeLineStack;
import com.cubaix.TDenlive.utils.StringUtils;

/**
 * Library of the montages, below the media list (the separation is dragged): the main montage (the whole project,
 * "Master" by default) first, then the others. They behave as the medias of the media list: a click selects a montage
 * (its rendering in the monitor, played alone, and its properties MontagePropsGUI), a double click or a drag to a line
 * of the time lines includes its final rendering in the montage edited, never the main one, never itself, never one
 * making a loop. The pencil of a row opens that montage in the time lines (edited); a click on the main montage, which
 * is never included, opens it directly. "+" adds a montage. Each row: thumbnail, name, duration, state of its
 * rendering; pencil and trash (not the main one).
 */
public class MontageListGUI extends CanvasPanel {
	//Data of a montage dragged to the time lines: prefix + UID of the montage
	public static final String MONTAGE_DRAG_PREFIX = "3Denlive-montage:";
	//Montage being dragged (the drop target only gets the data at the drop)
	static Montage draggedMontage = null;
	static final int MARGIN = 4;
	static final int REFRESH_MS = 2000;

	Composite panel;
	Composite toolBar;
	//Montage selected in the list (as a media of the media list), not always the one edited (tde.montage)
	public Montage selected = null;
	int hoverX = -1, hoverY = -1;

	public MontageListGUI(TDenlive aTDe, Composite aParent) {
		super(aTDe, makePanel(aTDe, aParent));
		panel = canvas.getParent();
		createToolBar();
		createContents();
	}

	static Composite makePanel(TDenlive aTDe, Composite aParent) {
		Composite aP = new Composite(aParent, SWT.NONE);
		aP.setBackground(aTDe.gui.colorsSwt.WHITE);
		GridLayout aGL = new GridLayout(1, false);
		aTDe.gui.applyNoMargin(aGL);
		aGL.verticalSpacing = 0;
		aP.setLayout(aGL);
		return aP;
	}

	void createToolBar() {
		toolBar = new Composite(panel, SWT.NONE);
		GridData aTB = new GridData(GridData.FILL_HORIZONTAL);
		aTB.heightHint = tde.gui.h(25);
		toolBar.setLayoutData(aTB);
		tde.gui.applyColorFont(toolBar);
		GridLayout aGL = new GridLayout(2, false);
		tde.gui.applyNoMargin(aGL);
		toolBar.setLayout(aGL);
		toolBar.moveAbove(canvas);

		Label aTitle = new Label(toolBar, SWT.NONE);
		tde.gui.applyColorFont(aTitle);
		aTitle.setFont(tde.gui.fontsSwt.robotoBold);
		aTitle.setText(" "+tde.gui.lngs.get("Montage.Title"));
		aTitle.setLayoutData(new GridData(GridData.FILL_HORIZONTAL | GridData.VERTICAL_ALIGN_CENTER));

		CLabel aAdd = new CLabel(toolBar, SWT.NONE);
		aAdd.setImage(tde.gui.imgsSwt.getIcon("add.gif"));
		aAdd.setAlignment(SWT.CENTER);
		GridData aGD = new GridData(GridData.HORIZONTAL_ALIGN_END);
		aGD.widthHint = 32;
		aGD.heightHint = tde.gui.h(25);
		aAdd.setLayoutData(aGD);
		tde.gui.applyColorFont(aAdd);
		tde.gui.tip(aAdd, "MontageAdd");
		aAdd.addListener(SWT.MouseDown, e -> {
			if(e.button == 1) {
				tde.gui.display.asyncExec(() -> addMontage());
			}
		});
	}

	//Order changed by a drag in the list (see reorderOver())
	int reorderY = -1;
	Montage reorderBefore = null;

	/** Drop target of the list: a montage of it dragged to change its place (the main one stays first) */
	void createReorderTarget() {
		org.eclipse.swt.dnd.DropTarget aDT = new org.eclipse.swt.dnd.DropTarget(canvas, DND.DROP_MOVE | DND.DROP_COPY | DND.DROP_DEFAULT);
		aDT.setTransfer(new Transfer[] {TextTransfer.getInstance()});
		aDT.addDropListener(new org.eclipse.swt.dnd.DropTargetAdapter() {
			@Override
			public void dragEnter(org.eclipse.swt.dnd.DropTargetEvent e) {
				over(e);
			}
			@Override
			public void dragOperationChanged(org.eclipse.swt.dnd.DropTargetEvent e) {
				over(e);
			}
			@Override
			public void dragOver(org.eclipse.swt.dnd.DropTargetEvent e) {
				over(e);
			}
			void over(org.eclipse.swt.dnd.DropTargetEvent e) {
				org.eclipse.swt.graphics.Point aP = canvas.toControl(e.x, e.y);
				e.detail = reorderOver(aP.x, aP.y) ? DND.DROP_MOVE : DND.DROP_NONE;
			}
			@Override
			public void dragLeave(org.eclipse.swt.dnd.DropTargetEvent e) {
				reorderClear();
			}
			@Override
			public void drop(org.eclipse.swt.dnd.DropTargetEvent e) {
				//The place computed again from the drop (dragLeave comes just before it)
				org.eclipse.swt.graphics.Point aP = canvas.toControl(e.x, e.y);
				Montage aM = null;
				if(e.data instanceof String && ((String)e.data).startsWith(MONTAGE_DRAG_PREFIX)) {
					try {
						aM = tde.montageByUID(Long.parseLong(((String)e.data).substring(MONTAGE_DRAG_PREFIX.length())));
					}
					catch(Throwable t) {
						t.printStackTrace(System.err);
					}
				}
				reorderDrop(aM, aP.x, aP.y);
			}
		});
	}

	/** The montage dragged over the list at aX, aY (canvas): its future place shown; false if none dragged */
	boolean reorderOver(int aX, int aY) {
		Montage aDragged = draggedMontage;
		if(aDragged == null) {
			reorderClear();
			return false;
		}
		Montage aBefore = null;
		int aLineY = -1;
		for(Montage aM : new Vector<Montage>(tde.montages)) {
			Rectangle aB = aM.bounds;
			if(aB == null) {
				continue;
			}
			//Never before the main montage
			if(!aM.isMain() && aY < aB.y+aB.height/2) {
				aBefore = aM;
				aLineY = aB.y-2;
				break;
			}
			aLineY = aB.y+aB.height+1;
		}
		if(aLineY != reorderY || aBefore != reorderBefore) {
			reorderY = aLineY;
			reorderBefore = aBefore;
			canvas.redraw();
		}
		return true;
	}

	void reorderDrop(Montage aDragged, int aX, int aY) {
		System.out.println("Montage list reorder: "+(aDragged == null ? "null" : aDragged.displayName(tde))+" at y="+aY);
		reorderClear();
		if(aDragged == null || aDragged.isMain()) {
			return;
		}
		//The montage before which it goes (null: at the end), as reorderOver()
		Montage aBefore = null;
		for(Montage aM : new Vector<Montage>(tde.montages)) {
			Rectangle aB = aM.bounds;
			if(aB != null && !aM.isMain() && aY < aB.y+aB.height/2) {
				aBefore = aM;
				break;
			}
		}
		if(aBefore == aDragged) {
			return;
		}
		if(!tde.montages.remove(aDragged)) {
			return;
		}
		int aIdx = aBefore == null ? tde.montages.size() : tde.montages.indexOf(aBefore);
		tde.montages.add(Math.max(1, aIdx < 0 ? tde.montages.size() : aIdx), aDragged);
		redraw();
	}

	void reorderClear() {
		if(reorderY >= 0 || reorderBefore != null) {
			reorderY = -1;
			reorderBefore = null;
			if(!canvas.isDisposed()) {
				canvas.redraw();
			}
		}
	}

	void createContents() {
		tde.gui.tip(canvas, "MontageList");
		createDragSource();
		createReorderTarget();
		canvas.addListener(SWT.MouseMove, e -> {
			int aWas = buttonAt(hoverX, hoverY);
			hoverX = e.x;
			hoverY = e.y;
			int aOn = buttonAt(e.x, e.y);
			if(aOn != aWas) {
				tde.gui.tip(canvas, aOn == 1 ? "MontageTrash" : (aOn == 2 ? "MontageEdit" : "MontageList"));
				canvas.redraw();
			}
		});
		canvas.addListener(SWT.MouseExit, e -> {
			boolean aWas = buttonAt(hoverX, hoverY) != 0;
			hoverX = hoverY = -1;
			if(aWas) {
				tde.gui.tip(canvas, "MontageList");
				canvas.redraw();
			}
		});
		canvas.addListener(SWT.MouseDown, e -> {
			if(e.button != 1 || e.count != 1) {
				return;
			}
			Montage aM = montageAt(e.x, e.y);
			if(aM == null || MontageRenderGUI.isRunning()) {
				return;
			}
			if(aM.trashRect != null && aM.trashRect.contains(e.x, e.y)) {
				tde.gui.display.asyncExec(() -> deleteMontage(aM));
				return;
			}
			if(aM.isMain() || (aM.editRect != null && aM.editRect.contains(e.x, e.y))) {
				//Opened in the time lines
				tde.gui.display.asyncExec(() -> {
					if(tde.montages.contains(aM) && !MontageRenderGUI.isRunning()) {
						tde.gui.showMontage(aM, true);
					}
				});
				return;
			}
			selectMontage(aM);
		});
		canvas.addListener(SWT.MouseDoubleClick, e -> {
			if(e.button != 1 || buttonAt(e.x, e.y) != 0) {
				return;
			}
			Montage aM = montageAt(e.x, e.y);
			//Included in the montage edited, as a media of the media list added to the time lines
			if(aM != null && !aM.isMain()) {
				tde.gui.display.asyncExec(() -> insert(aM, -1, -1));
			}
		});
		//Out of date renderings appear without an action of the user (a change of a clip of another montage)
		Runnable[] aTick = new Runnable[1];
		aTick[0] = () -> {
			if(canvas.isDisposed()) {
				return;
			}
			try {
				boolean aPlaying = tde.gui.playerControlGUI != null && (tde.gui.playerControlGUI.playing);
				if(!aPlaying && !MontageRenderGUI.isRunning() && tde.montages.size() > 1 && canvas.isVisible()
						&& Montage.refreshStates(tde)) {
					canvas.redraw();
					if(tde.gui.montagePropsGUI != null) {
						tde.gui.montagePropsGUI.refresh();
					}
				}
			}
			catch(Throwable t) {
				t.printStackTrace(System.err);
			}
			tde.gui.display.timerExec(REFRESH_MS, aTick[0]);
		};
		tde.gui.display.timerExec(REFRESH_MS, aTick[0]);
	}

	/**
	 * Drag of a montage (not the main one, not the one shown) to a line of the time lines (see
	 * TimeLineStackGUI.createDropTarget())
	 */
	void createDragSource() {
		DragSource aDS = new DragSource(canvas, DND.DROP_COPY | DND.DROP_MOVE);
		aDS.setTransfer(new Transfer[] {TextTransfer.getInstance()});
		aDS.addDragListener(new DragSourceAdapter() {
			@Override
			public void dragStart(DragSourceEvent e) {
				draggedMontage = null;
				Montage aM = montageAt(e.x, e.y);
				if(aM == null || buttonAt(e.x, e.y) != 0 || aM.isMain() || MontageRenderGUI.isRunning()) {
					e.doit = false;
					return;
				}
				draggedMontage = aM;
			}
			@Override
			public void dragSetData(DragSourceEvent e) {
				if(draggedMontage != null) {
					e.data = MONTAGE_DRAG_PREFIX+draggedMontage.UID;
				}
			}
			@Override
			public void dragFinished(DragSourceEvent e) {
				draggedMontage = null;
				reorderClear();
				tde.timeLineStack.clearDropPreview();
				tde.gui.timeLinesGUI.redraw();
			}
		});
	}

	Montage montageAt(int aX, int aY) {
		for(Montage aM : new Vector<Montage>(tde.montages)) {
			if(aM.bounds != null && aM.bounds.contains(aX, aY)) {
				return aM;
			}
		}
		return null;
	}

	/** Button of a row under the mouse: 1 trash, 2 edit, 0 none */
	int buttonAt(int aX, int aY) {
		for(Montage aM : new Vector<Montage>(tde.montages)) {
			if(aM.trashRect != null && aM.trashRect.contains(aX, aY)) {
				return 1;
			}
			if(aM.editRect != null && aM.editRect.contains(aX, aY)) {
				return 2;
			}
		}
		return 0;
	}

	/**
	 * Selects aM in the list, as a click on a media of the media list: the media list unselected, its rendering in the
	 * monitor (Play plays it alone; none: the monitor empty), its properties in the panel. The montage edited (time
	 * lines) does not change
	 */
	public void selectMontage(Montage aM) {
		if(aM == null || !tde.montages.contains(aM)) {
			return;
		}
		selected = aM;
		tde.selected.medias.clear();
		for(Media aMd : tde.mediaList.mediaList) {
			aMd.selState = Drawable.THUMBSELSTATE_NONE;
		}
		tde.gui.mediaListGUI.redraw();
		tde.gui.setMultiMonitorPanel(TDeGUI.MULTIMONITORPANEL_MONITOR);
		StereoMovie aR = aM.isMain() ? null : aM.renderMedia(tde);
		if(aR != null) {
			aR.setRequestedTimePosMS(0);
			tde.gui.playerControlGUI.showClock(0);
			tde.gui.monitorGUI.setMedia(aR);
		}
		else {
			tde.gui.monitorGUI.clean();
		}
		tde.gui.setMultiPropsPanel(TDeGUI.MULTIPROPSPANEL_MONTAGE);
		if(tde.gui.montagePropsGUI != null) {
			tde.gui.montagePropsGUI.setMontage(aM);
		}
		tde.gui.monitorRulerGUI.redraw();
		showRow(aM);
	}

	/** No montage selected in the list (a media of the media list was clicked) */
	public void unselect() {
		if(selected != null) {
			selected = null;
			redraw();
		}
	}

	/** A name not taken: "New montage", "New montage 2"... */
	String freeName() {
		String aBase = tde.gui.lngs.get("Montage.NewName");
		for(int n = 1;;n++) {
			String aName = n == 1 ? aBase : aBase+" "+n;
			boolean aTaken = false;
			for(Montage aM : tde.montages) {
				aTaken |= !aM.isMain() && aName.equalsIgnoreCase(aM.displayName(tde));
			}
			if(!aTaken) {
				return aName;
			}
		}
	}

	void addMontage() {
		if(MontageRenderGUI.isRunning()) {
			return;
		}
		Montage aM = new Montage(Montage.nextUID(), freeName(), new TimeLineStack(tde));
		tde.montages.add(aM);
		tde.gui.showMontage(aM, false);
		showRow(aM);
	}

	void warn(String aMsg) {
		MessageBox aMB = new MessageBox(tde.gui.shell, SWT.ICON_WARNING|SWT.OK);
		aMB.setText(tde.gui.lngs.get("Montage.Title"));
		aMB.setMessage(aMsg);
		aMB.open();
	}

	/**
	 * Removes a montage (not the main one), after a confirmation. The montages that include it keep its last
	 * rendering, which becomes an ordinary video of the media list
	 */
	void deleteMontage(Montage aM) {
		if(aM.isMain() || !tde.montages.contains(aM) || MontageRenderGUI.isRunning()) {
			return;
		}
		Vector<Montage> aUsers = aM.includers(tde);
		String aMsg = tde.gui.lngs.get("Montage.DeleteConfirm").replace("%s", aM.displayName(tde));
		if(aUsers.size() > 0) {
			aMsg += "\n\n"+tde.gui.lngs.get("Montage.DeleteUsed").replace("%l", names(aUsers));
		}
		MessageBox aMB = new MessageBox(tde.gui.shell, SWT.ICON_WARNING|SWT.YES|SWT.NO);
		aMB.setText(tde.gui.lngs.get("Montage.Title"));
		aMB.setMessage(aMsg);
		if(aMB.open() != SWT.YES) {
			return;
		}
		if(aM == tde.montage) {
			tde.gui.showMontage(tde.mainMontage(), false);
		}
		StereoMovie aR = aM.renderMedia(tde);
		if(aR != null) {
			if(aUsers.size() > 0) {
				//An ordinary video from now on, shown in the media list
				aR.montageUID = 0;
				aR.montageSig = null;
				aR.montageStale = false;
				aR.libName = aM.displayName(tde);
			}
			else {
				for(TimeLineStack aS : tde.allStacks()) {
					aS.releaseMedia(aR);
				}
				tde.mediaList.removeMedia(aR);
			}
		}
		tde.montages.remove(aM);
		redraw();
		tde.gui.mediaListGUI.redraw();
		tde.gui.timeLinesGUI.redraw();
		if(tde.gui.montagePropsGUI != null) {
			tde.gui.montagePropsGUI.refresh();
		}
	}

	static String names(java.util.Collection<Montage> aMs, TDenlive aTDe) {
		StringBuilder aSB = new StringBuilder();
		for(Montage aM : aMs) {
			aSB.append(aSB.length() > 0 ? ", " : "").append("« ").append(aM.displayName(aTDe)).append(" »");
		}
		return aSB.toString();
	}

	String names(java.util.Collection<Montage> aMs) {
		return names(aMs, tde);
	}

	/**
	 * Whether aM may be included in the montage shown (a message says why not): not the main one, not itself, not
	 * one that includes it (a loop: each update would ask for another one, endlessly), not an empty one
	 */
	boolean canInclude(Montage aM) {
		Montage aTarget = tde.montage;
		if(aM.isMain()) {
			warn(tde.gui.lngs.get("Montage.MainNotIncluded"));
			return false;
		}
		if(aM == aTarget) {
			warn(tde.gui.lngs.get("Montage.NotItself"));
			return false;
		}
		if(aM.includes(tde, aTarget)) {
			warn(tde.gui.lngs.get("Montage.Loop").replace("%s", aM.displayName(tde)).replace("%t", aTarget.displayName(tde)));
			return false;
		}
		if(aM.stack.getMaxTimePosMS() <= 0) {
			warn(tde.gui.lngs.get("Montage.Empty").replace("%s", aM.displayName(tde)));
			return false;
		}
		return true;
	}

	/**
	 * Includes aM in the montage shown: a clip of its final rendering, made first when missing or (asked) out of
	 * date; at the drop place (aX, aY of the time lines), else after the selected clip (aX < 0)
	 */
	public void insert(Montage aM, int aX, int aY) {
		if(MontageRenderGUI.isRunning() || !tde.montages.contains(aM) || !canInclude(aM)) {
			return;
		}
		Montage aTarget = tde.montage;
		Montage.refreshStates(tde);
		Vector<Montage> aChain = new Vector<Montage>();
		Montage.needs(tde, aM, aChain, new java.util.HashMap<Montage,Boolean>(), new java.util.HashSet<Montage>());
		StereoMovie aR = aM.renderMedia(tde);
		if(!aChain.isEmpty()) {
			if(aR == null) {
				//Compute now, or later: a still image "Montage not computed" meanwhile
				MessageBox aMB = new MessageBox(tde.gui.shell, SWT.ICON_QUESTION|SWT.YES|SWT.NO|SWT.CANCEL);
				aMB.setText(tde.gui.lngs.get("Montage.Title"));
				aMB.setMessage(tde.gui.lngs.get("Montage.WillRender").replace("%s", aM.displayName(tde))
						+(aChain.size() > 1 ? "\n"+tde.gui.lngs.get("Montage.AlsoRender").replace("%l", names(aChain.subList(0, aChain.size()-1))) : ""));
				java.util.Map<Integer, String> aLabels = new java.util.HashMap<Integer, String>();
				aLabels.put(SWT.YES, tde.gui.lngs.get("Montage.ComputeNow"));
				aLabels.put(SWT.NO, tde.gui.lngs.get("Montage.ComputeLater"));
				aMB.setButtonLabels(aLabels);
				int aAns = aMB.open();
				if(aAns == SWT.CANCEL) {
					return;
				}
				if(aAns == SWT.NO) {
					withPlaceholder(aTarget, aM, "Montage.PlaceholderInfo", aP -> placeAt(aTarget, aP, aX, aY));
					return;
				}
			}
			else {
				MessageBox aMB = new MessageBox(tde.gui.shell, SWT.ICON_QUESTION|SWT.YES|SWT.NO|SWT.CANCEL);
				aMB.setText(tde.gui.lngs.get("Montage.Title"));
				aMB.setMessage(tde.gui.lngs.get("Montage.InsertStale").replace("%l", names(aChain)));
				int aAns = aMB.open();
				if(aAns == SWT.CANCEL) {
					return;
				}
				if(aAns == SWT.NO) {
					aChain.clear();
				}
			}
		}
		Runnable aPlace = () -> placeAt(aTarget, aM.renderMedia(tde), aX, aY);
		if(aChain.isEmpty()) {
			aPlace.run();
		}
		else {
			MontageRenderGUI.render(tde, aChain, aOk -> {
				if(aOk || aM.renderMedia(tde) != null) {
					aPlace.run();
				}
				else if(aR == null) {
					//Its first rendering canceled or failed: the still image "Montage not computed" in its place
					withPlaceholder(aTarget, aM, "Montage.PlaceholderInfo", aP -> placeAt(aTarget, aP, aX, aY));
				}
			});
		}
	}

	/**
	 * A clip of the rendering aRM in the montage edited aTarget: at aX, aY of the time lines (aX < 0: after the
	 * selected clip)
	 */
	void placeAt(Montage aTarget, StereoMovie aRM, int aX, int aY) {
		if(aRM == null || tde.montage != aTarget) {
			return;
		}
		Clip aClip = null;
		if(aX >= 0) {
			aClip = tde.timeLineStack.dropMedia(aRM, aX, aY);
		}
		if(aClip == null) {
			tde.timeLineStack.addMedia(aRM);
			aClip = tde.selected.clips.isEmpty() ? null : tde.selected.clips.lastElement();
		}
		showClip(aClip);
	}

	/** The processors of aClip, the montage in the monitor (as after a double click in the media list) */
	void showClip(Clip aClip) {
		TDeGUI aGui = tde.gui;
		aGui.setMultiMonitorPanel(TDeGUI.MULTIMONITORPANEL_MONITOR);
		aGui.monitorGUI.setMedia(tde.timeLineStack);
		aGui.playerControlGUI.showClock(tde.timeLineStack.getTimePosMS());
		if(aClip != null) {
			aGui.setMultiPropsPanel(TDeGUI.MULTIPROPSPANEL_PROCESSORS);
			aGui.processorGUI.setProcessors(aClip.getProcessors());
		}
		aGui.monitorGUI.redraw();
		aGui.timeLinesGUI.redraw();
		aGui.monitorRulerGUI.redraw();
		redraw();
	}

	/**
	 * The selected clips of the montage shown (with their twins) go into a new montage, at the same lines, the
	 * earliest at 0; its final rendering is made and takes their place (heavy clips computed apart)
	 */
	public void extractSelectedClips() {
		extractClips(new Vector<Clip>(tde.selected.clips));
	}

	/** Same for the clips aClips of the montage shown (button of the clip panel: the clip of the panel alone) */
	public void extractClips(Vector<Clip> aClips) {
		if(MontageRenderGUI.isRunning() || aClips == null || aClips.isEmpty()) {
			return;
		}
		Montage aSrc = tde.montage;
		MessageBox aAsk = new MessageBox(tde.gui.shell, SWT.ICON_QUESTION|SWT.OK|SWT.CANCEL);
		aAsk.setText(tde.gui.lngs.get("Montage.Title"));
		aAsk.setMessage(aClips.size() == 1 ? tde.gui.lngs.get("Montage.ExtractAskOne")
				: tde.gui.lngs.get("Montage.ExtractAsk").replace("%n", ""+aClips.size()));
		if(aAsk.open() != SWT.OK) {
			return;
		}
		if(tde.gui.playerControlGUI != null && (tde.gui.playerControlGUI.playing || tde.gui.playerControlGUI.paused)) {
			tde.gui.playerControlGUI.setPlaying(false);
		}
		Montage aNew = new Montage(Montage.nextUID(), freeName(), new TimeLineStack(tde));
		long[] aWhere = aNew.moveClipsFrom(aSrc.stack, aClips);
		aSrc.stack.clearSelection();
		tde.montages.add(aNew);
		redraw();
		tde.gui.timeLinesGUI.redraw();
		if(tde.gui.processorGUI != null) {
			tde.gui.processorGUI.clean();
		}
		Vector<Montage> aChain = new Vector<Montage>();
		Montage.needs(tde, aNew, aChain, new java.util.HashMap<Montage,Boolean>(), new java.util.HashSet<Montage>());
		MontageRenderGUI.render(tde, aChain, aOk -> {
			StereoMovie aR = aNew.renderMedia(tde);
			if(aOk && aR != null && tde.montages.contains(aSrc)) {
				Clip aC = Montage.placeClip(aSrc.stack, (int)aWhere[1], aR, aWhere[0]);
				if(tde.montage == aSrc) {
					showClip(aC);
				}
				return;
			}
			//Canceled or failed: the clips keep their place, shown by a still image "Montage not computed" (an out
			//of date rendering, made again by the next rendering)
			placeholderAt(aSrc, aNew, (int)aWhere[1], aWhere[0]);
		});
	}

	/**
	 * The provisional rendering of aNew (MontageRenderGUI.placeholder()) placed on the
	 * video line aIdx of aSrc at aStartMS
	 */
	void placeholderAt(Montage aSrc, Montage aNew, int aIdx, long aStartMS) {
		withPlaceholder(aSrc, aNew, "Montage.ExtractPlaceholder", aP -> {
			Clip aC = Montage.placeClip(aSrc.stack, aIdx, aP, aStartMS);
			if(tde.montage == aSrc) {
				showClip(aC);
			}
		});
	}

	/**
	 * The provisional rendering of aM (a still image "Montage not computed" drawn in memory,
	 * MontageRenderGUI.placeholder()), added to the media list then given to aPlace (UI thread), with the message aMsgKey
	 */
	void withPlaceholder(Montage aIn, Montage aM, String aMsgKey, java.util.function.Consumer<StereoMovie> aPlace) {
		if(!tde.montages.contains(aIn) || !tde.montages.contains(aM)) {
			return;
		}
		//Drawn in memory: immediate
		StereoMovie aP = MontageRenderGUI.placeholder(tde, aM);
		synchronized(tde.mediaList.mediaList) {
			tde.mediaList.mediaList.add(aP);
		}
		aPlace.accept(aP);
		redraw();
		warn(tde.gui.lngs.get(aMsgKey).replace("%s", aM.displayName(tde)));
	}

	/** Scrolls so that the row of aM is visible */
	void showRow(Montage aM) {
		if(aM.bounds == null || canvas.isDisposed()) {
			canvas.redraw();
			return;
		}
		Rectangle aClient = canvas.getClientArea();
		int aShift = 0;
		if(aM.bounds.y < 0) {
			aShift = -aM.bounds.y;
		}
		else if(aM.bounds.y+aM.bounds.height > aClient.height) {
			aShift = Math.max(aClient.height-(aM.bounds.y+aM.bounds.height), -aM.bounds.y);
		}
		if(aShift != 0) {
			int aMax = Math.max(0, bounds.height-aClient.height);
			origin.y = Math.max(-aMax, Math.min(0, origin.y+aShift));
			canvas.getVerticalBar().setSelection(-origin.y);
		}
		canvas.redraw();
	}

	/** Thumbnail of a montage: that of its rendering, else of the media of its first clip */
	Image thumbOf(Montage aM) {
		StereoMovie aR = aM.isMain() ? null : aM.renderMedia(tde);
		if(aR != null && aR.getThumbSwt() != null) {
			return aR.getThumbSwt();
		}
		Clip aFirst = null;
		for(Clip aC : aM.clips()) {
			if(aC.getMedia() != null && aC.getMedia().getThumbSwt() != null && (aFirst == null || aC.getStartTimeMS() < aFirst.getStartTimeMS())) {
				aFirst = aC;
			}
		}
		return aFirst == null ? null : aFirst.getMedia().getThumbSwt();
	}

	@Override
	void draw(GC aGC) {
		Rectangle aClient = canvas.getClientArea();
		aGC.setBackground(tde.gui.colorsSwt.WHITE);
		aGC.fillRectangle(aClient);
		Image aTrash = tde.gui.imgsSwt.getIcon("trash.png");
		Image aPencil = tde.gui.imgsSwt.getIcon("pencil.png");
		Rectangle aTB = aTrash.getBounds();
		int aX = origin.x;
		int aY = origin.y;
		int aH = 0;
		int aMaxW = 0;
		aGC.setFont(tde.gui.fontsSwt.robotoBold);
		int aLineBold = aGC.getFontMetrics().getHeight();
		aGC.setFont(tde.gui.fontsSwt.roboto);
		int aLine = aGC.getFontMetrics().getHeight();
		int aRowH = Math.max(tde.config.thumbH, aLineBold+2*aLine);
		for(Montage aM : new Vector<Montage>(tde.montages)) {
			int aRowY = aY+aH;
			//Selected: violet background, as the medias of the media list and the lines (the montage of the time lines
			//is told by Montage.Editing after its duration)
			boolean aSel = aM == selected;
			boolean aEdited = aM == tde.montage;
			aGC.setBackground(aSel ? tde.gui.colorsSwt.BLUE_L60 : tde.gui.colorsSwt.WHITE);
			aGC.fillRectangle(aClient.x, aRowY, aClient.width, aRowH);
			Image aThumb = thumbOf(aM);
			if(aThumb != null) {
				Rectangle aIB = aThumb.getBounds();
				aGC.drawImage(aThumb, 0, 0, aIB.width, aIB.height, aX, aRowY, tde.config.thumbW, tde.config.thumbH);
			}
			else {
				aGC.setBackground(tde.gui.colorsSwt.LIGHTGRAY);
				aGC.fillRectangle(aX, aRowY, tde.config.thumbW, tde.config.thumbH);
				aGC.setBackground(aSel ? tde.gui.colorsSwt.BLUE_L60 : tde.gui.colorsSwt.WHITE);
			}
			int aTX = aX+tde.config.thumbW+3;
			aGC.setForeground(aSel ? tde.gui.colorsSwt.WHITE : tde.gui.colorsSwt.CUBAIX_BLUE);
			aGC.setFont(tde.gui.fontsSwt.robotoBold);
			String aName = aM.displayName(tde);
			aGC.drawText(aName, aTX, aRowY, true);
			int aW = aGC.stringExtent(aName).x;
			aGC.setFont(tde.gui.fontsSwt.roboto);
			String aClock = StringUtils.time2Clock(aM.stack.getMaxTimePosMS())
					+(aEdited ? "   "+tde.gui.lngs.get("Montage.Editing") : "");
			aGC.drawText(aClock, aTX, aRowY+aLineBold, true);
			String aState;
			if(aM.isMain()) {
				aState = tde.gui.lngs.get("Montage.MainState");
			}
			else {
				StereoMovie aR = aM.renderMedia(tde);
				aState = tde.gui.lngs.get(aR == null ? "Montage.StateNone" : (aR.montageStale ? "Montage.StateStale" : "Montage.StateOk"));
				if(!aSel) {
					aGC.setForeground(aR == null ? tde.gui.colorsSwt.GRAY : (aR.montageStale ? tde.gui.colorsSwt.RED : tde.gui.display.getSystemColor(SWT.COLOR_DARK_GREEN)));
				}
			}
			aGC.drawText(aState, aTX, aRowY+aLineBold+aLine, true);
			aW = Math.max(aW, Math.max(aGC.stringExtent(aClock).x, aGC.stringExtent(aState).x));
			aMaxW = Math.max(aMaxW, aTX-aX+aW);
			aM.bounds = new Rectangle(aClient.x, aRowY, aClient.width, aRowH);
			aM.trashRect = null;
			aM.editRect = null;
			if(!aM.isMain()) {
				Rectangle aTR = new Rectangle(aClient.x+aClient.width-aTB.width-MARGIN, aRowY+MARGIN, aTB.width, aTB.height);
				aGC.drawImage(aTrash, aTR.x, aTR.y);
				aM.trashRect = frame(aGC, aTR);
				//Pencil: opens it in the time lines (not the one already edited)
				if(!aEdited) {
					Rectangle aPB = aPencil.getBounds();
					Rectangle aPR = new Rectangle(aTR.x-MARGIN-aPB.width, aRowY+MARGIN, aPB.width, aPB.height);
					aGC.drawImage(aPencil, aPR.x, aPR.y);
					aM.editRect = frame(aGC, aPR);
				}
			}
			aH += aRowH;
			aGC.setBackground(tde.gui.colorsSwt.WHITE);
			aGC.fillRectangle(0, aY+aH, aClient.width, 3);
			aGC.setBackground(tde.gui.colorsSwt.GRAY);
			aGC.fillRectangle(3, aY+aH+1, aClient.width-6, 1);
			aH += 3;
		}
		if(reorderY >= 0) {
			//Place of the montage dragged in the list
			aGC.setBackground(tde.gui.colorsSwt.CUBAIX_BLUE);
			aGC.fillRectangle(aClient.x+2, reorderY-1, aClient.width-4, 3);
		}
		Rectangle aR = new Rectangle(aX, aY, aMaxW, aH);
		if(aR.width != bounds.width || aR.height != bounds.height) {
			bounds = aR;
			adjustScrollBars();
		}
		else {
			bounds = aR;
		}
	}

	/** The clickable zone of a button, framed while the mouse is over it */
	Rectangle frame(GC aGC, Rectangle aR) {
		Rectangle aF = new Rectangle(aR.x-MARGIN/2, aR.y-MARGIN/2, aR.width+MARGIN, aR.height+MARGIN);
		if(aF.contains(hoverX, hoverY)) {
			aGC.setForeground(tde.gui.colorsSwt.LIGHTGRAY);
			aGC.setLineWidth(1);
			aGC.drawRectangle(aF.x, aF.y, aF.width-1, aF.height-1);
		}
		return aF;
	}
}
