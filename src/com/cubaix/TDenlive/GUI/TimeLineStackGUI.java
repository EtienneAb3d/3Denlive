package com.cubaix.TDenlive.GUI;

import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.util.Vector;

import org.eclipse.swt.dnd.DND;
import org.eclipse.swt.dnd.DropTarget;
import org.eclipse.swt.dnd.DropTargetAdapter;
import org.eclipse.swt.dnd.DropTargetEvent;
import org.eclipse.swt.dnd.TextTransfer;
import org.eclipse.swt.dnd.Transfer;
import org.eclipse.swt.events.MouseEvent;
import org.eclipse.swt.events.MouseListener;
import org.eclipse.swt.events.MouseMoveListener;
import org.eclipse.swt.graphics.Cursor;
import org.eclipse.swt.graphics.GC;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.graphics.Rectangle;
import org.eclipse.swt.SWT;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Menu;
import org.eclipse.swt.widgets.MenuItem;
import org.eclipse.swt.widgets.MessageBox;
import org.eclipse.swt.widgets.Scale;

import com.cubaix.TDenlive.TDenlive;
import com.cubaix.TDenlive.medias.AudioTimeLine;
import com.cubaix.TDenlive.medias.Clip;
import com.cubaix.TDenlive.medias.Media;
import com.cubaix.TDenlive.medias.TimeLineStack;
import com.cubaix.TDenlive.medias.VideoTimeLine;
import com.cubaix.TDenlive.utils.ImageUtils;

public class TimeLineStackGUI extends CanvasPanel {
	boolean isMouseDown = false;
	boolean isDrag = false;
	//What a drag on a clip does: move it, or move its start / end (see clipDragModeAt())
	static final int CLIP_DRAG_MOVE = 0;
	static final int CLIP_DRAG_START = 1;
	static final int CLIP_DRAG_END = 2;
	//Width of the grip zone at each side of a clip (pixels)
	static final int CLIP_EDGE_W = 6;
	//Distance (pixels) at which a dragged edge sticks to the start / end of the other clips
	static final int CLIP_SNAP_PX = 8;
	int clipDragMode = CLIP_DRAG_MOVE;
	Clip draggedClip = null;
	long draggedClipStartMS = 0;
	long draggedClipEndMS = 0;
	//Move of the selection (see TimeLineStack.moveClips()): the clips, their starts and lines at the click
	Vector<Clip> movedClips = new Vector<Clip>();
	long[] movedStartsMS = new long[0];
	VideoTimeLine[] movedLines = new VideoTimeLine[0];
	VideoTimeLine draggedClipLine = null;
	int movedLineDelta = 0;
	//Click on a clip already selected: kept selected for a move of the whole selection; at the mouse up
	//without a move, unselected (shift) or the only one selected
	Clip pendingClickClip = null;
	boolean pendingUnselect = false;
	//Mouse moves of less than this from the click (pixels) are not a drag of the clips
	static final int CLICK_MOVE_PX = 3;
	Cursor resizeCursor = null;
	//Rectangle selection (burger menu): armed by the menu, the next drag draws the rectangle (from rectStart)
	boolean rectMode = false;
	Point rectStart = null;
	//Time range selection (burger menu): armed by the menu, the next drag gives the range from the time of
	//the click; or the drag of an edge of the range, from the other edge: rangeAnchorMS, the fixed time (-1: none)
	boolean rangeMode = false;
	long rangeAnchorMS = -1;
	//Drag of an edge: not moved by a click without a real move
	boolean rangeEdgeHeld = false;
	Cursor crossCursor = null;
	Menu linesMenu = null;
	Point clickedMousePos = null;
	Point lastMousePos = null;
	Thread drawTh = null;
	volatile boolean drawRequested = false;
	//Next paint only shows the image just rendered (no new rendering request)
	boolean selfRepaint = false;
	volatile Rectangle drawBounds = null;
	Image lastDbl = null;
	//Drawn over lastDbl at each paint (not in it): the horizontal origin and time scale lastDbl was drawn with,
	//the vertical extent of the cursor, the cursor position last painted (UI thread)
	int lastDblOriginX = 0;
	double lastDblPixPerMS = -1;
	int lastCursorTopY = 0;
	int lastCursorBottomY = 0;
	int shownCursorX = Integer.MIN_VALUE;
	//Origin for which a new drawing has been asked by redrawCursor() (the time lines slide)
	int slideOriginX = Integer.MIN_VALUE;

	//Time zoom: pixPerMS = DEFAULT_PIX_PER_MS * 2^(zoom/ZOOM_STEPS_PER_X2), zoom in [-ZOOM_MAX, ZOOM_MAX]
	static final int ZOOM_MAX = 50;
	static final int ZOOM_STEPS_PER_X2 = 10;
	static final int ZOOM_BUTTON_STEP = 5;//x1.41
	int zoom = 0;
	Scale zoomScale = null;
	long lastFollowedTimeMS = -1;

	public TimeLineStackGUI(TDenlive aTDe, Composite aParent) {
		//Own horizontal bar, to put the zoom tools on its right
		super(aTDe, aParent, true);
		createContents(aParent);
		createZoomTools();
	}
	
	void createZoomTools() {
		GridLayout aBL = (GridLayout)bottomRow.getLayout();
		aBL.numColumns = 6;
		//Tools end where the vertical scroll bar starts (not under the window border)
		aBL.marginRight = canvas.getVerticalBar() != null ? canvas.getVerticalBar().getSize().x+4 : 16;
		zoomButton("zoomminus.gif", "TimeLine.ZoomOut", () -> setZoom(zoom-ZOOM_BUTTON_STEP));
		zoomScale = new Scale(bottomRow, SWT.HORIZONTAL);
		GridData aSGD = new GridData();
		aSGD.widthHint = 100;
		zoomScale.setLayoutData(aSGD);
		zoomScale.setMinimum(0);
		zoomScale.setMaximum(2*ZOOM_MAX);
		zoomScale.setIncrement(1);
		zoomScale.setPageIncrement(ZOOM_STEPS_PER_X2);
		zoomScale.setSelection(ZOOM_MAX);
		//Time scale kept by the montage (window built again)
		showZoom();
		zoomScale.setBackground(tde.gui.colorsSwt.WHITE);
		zoomScale.setToolTipText(tde.gui.lngs.get("TimeLine.Zoom"));
		zoomScale.addListener(SWT.Selection, e -> setZoom(zoomScale.getSelection()-ZOOM_MAX));
		zoomButton("zoomplus.gif", "TimeLine.ZoomIn", () -> setZoom(zoom+ZOOM_BUTTON_STEP));
		zoomButton("HorizontalZoom.png", "TimeLine.ZoomReset", () -> setZoom(0));
		zoomButton("collapseall.png", "TimeLine.ZoomFit", () -> zoomFit());
		
		//Horizontal scrolling of a touchpad / tilt wheel, now that the canvas has no horizontal bar
		canvas.addListener(SWT.MouseHorizontalWheel, e -> {
			origin.x = Math.min(0, origin.x+e.count*10);
			adjustScrollBars();
			redraw();
		});
	}
	
	void zoomButton(String aIcon,String aTip,Runnable aAction) {
		Label aL = new Label(bottomRow, SWT.NONE);
		aL.setImage(tde.gui.imgsSwt.getIcon(aIcon));
		aL.setBackground(tde.gui.colorsSwt.WHITE);
		aL.setToolTipText(tde.gui.lngs.get(aTip));
		GridData aGD = new GridData();
		aGD.widthHint = 22;
		aL.setAlignment(SWT.CENTER);
		aL.setLayoutData(aGD);
		aL.addListener(SWT.MouseDown, e -> aAction.run());
	}
	
	/**
	 * When the time cursor has moved out of the visible part, scroll to put it at 1/4 of the width.
	 * Only when the cursor moves: scrolling by hand with a still cursor is kept.
	 */
	void followCursor(int aClientW) {
		TimeLineStack aTLS = tde.timeLineStack;
		if(aTLS == null) {
			return;
		}
		long aTime = aTLS.getRequestedTimePosMS();
		if(aTime == lastFollowedTimeMS) {
			return;
		}
		lastFollowedTimeMS = aTime;
		int aVisibleW = aClientW-tde.config.lineHeadW();
		if(aVisibleW <= 0) {
			return;
		}
		double aX = origin.x+aTime*aTLS.pixPerMS;//From the left of the visible part
		if(aX < 0 || aX > aVisibleW) {
			origin.x = (int)Math.min(0, aVisibleW/4.0-aTime*aTLS.pixPerMS);
			//The slider follows after the drawing (adjustScrollBars)
		}
	}
	
	/**
	 * Time scale showing exactly the total duration of the montage on the visible width
	 */
	void zoomFit() {
		TimeLineStack aTLS = tde.timeLineStack;
		long aDuration = aTLS.getMaxTimePosMS();
		int aVisibleW = canvas.getClientArea().width-tde.config.lineHeadW();
		if(aDuration <= 0 || aVisibleW <= 0) {
			return;
		}
		aTLS.pixPerMS = aVisibleW/(double)aDuration;
		showZoom();
		origin.x = 0;
		adjustScrollBars();
		redraw();
	}
	
	/**
	 * Zoom and scale from the time scale of the montage.
	 * The scale shows the nearest step, the time scale itself is exact
	 */
	void showZoom() {
		TimeLineStack aTLS = tde.timeLineStack;
		if(aTLS == null || aTLS.pixPerMS <= 0) {
			return;
		}
		zoom = (int)Math.round(ZOOM_STEPS_PER_X2*Math.log(aTLS.pixPerMS/TimeLineStack.DEFAULT_PIX_PER_MS)/Math.log(2.0));
		zoom = Math.max(-ZOOM_MAX, Math.min(ZOOM_MAX, zoom));
		zoomScale.setSelection(zoom+ZOOM_MAX);
	}

	/**
	 * Change the time scale, keeping the time cursor at the same place on screen
	 * (or the left side if the cursor is not visible)
	 */
	void setZoom(int aZoom) {
		aZoom = Math.max(-ZOOM_MAX, Math.min(ZOOM_MAX, aZoom));
		zoom = aZoom;
		if(zoomScale.getSelection() != zoom+ZOOM_MAX) {
			zoomScale.setSelection(zoom+ZOOM_MAX);
		}
		TimeLineStack aTLS = tde.timeLineStack;
		double aOldPix = aTLS.pixPerMS;
		double aNewPix = TimeLineStack.DEFAULT_PIX_PER_MS*Math.pow(2.0, zoom/(double)ZOOM_STEPS_PER_X2);
		if(aNewPix == aOldPix) {
			return;
		}
		int aClientW = canvas.getClientArea().width;
		long aAnchorTime = aTLS.getRequestedTimePosMS();
		double aAnchorX = origin.x+tde.config.lineHeadW()+aAnchorTime*aOldPix;//On screen
		if(aAnchorX < tde.config.lineHeadW() || aAnchorX > aClientW) {
			aAnchorX = tde.config.lineHeadW();
			aAnchorTime = (long)(-origin.x/aOldPix);
		}
		aTLS.pixPerMS = aNewPix;
		origin.x = (int)Math.min(0, aAnchorX-tde.config.lineHeadW()-aAnchorTime*aNewPix);
		adjustScrollBars();
		redraw();
	}

	/**
	 * Scrolls the time lines vertically so that the line aTL is visible (below the fixed ruler); nothing when
	 * it already is (MediaPropsGUI: occurrences of a media)
	 */
	public void showLine(VideoTimeLine aTL) {
		if(aTL == null || aTL.bounds == null || canvas == null || canvas.isDisposed()) {
			return;
		}
		Rectangle aClient = canvas.getClientArea();
		TimeLineStack aTLS = tde.timeLineStack;
		int aTop = aTLS.rulerBounds != null ? aTLS.rulerBounds.height : 0;
		int aY = aTL.bounds.y, aH = aTL.bounds.height;
		int aShift = 0;
		if(aY < aTop) {
			aShift = aTop - aY;
		}
		else if(aY + aH > aClient.height) {
			//Its bottom shown, its top first when it is taller than the view
			aShift = Math.max(aClient.height - (aY + aH), aTop - aY);
		}
		if(aShift == 0) {
			return;
		}
		int aMaxScroll = Math.max(0, (bounds != null ? bounds.height : 0) - aClient.height);
		origin.y = Math.max(-aMaxScroll, Math.min(0, origin.y + aShift));
		org.eclipse.swt.widgets.ScrollBar aVBar = canvas.getVerticalBar();
		if(aVBar != null) {
			aVBar.setSelection(-origin.y);
		}
		redraw();
	}

	@Override
	public void redraw() {
		//Content changed: always render again, even if the paint is merged with a self repaint
		drawRequested = true;
		super.redraw();
		//Time relative to the selected clip (selection or clip moved)
		if(tde.gui.playerControlGUI != null) {
			tde.gui.playerControlGUI.updateClipClock();
		}
		//Duration of the montage on the ruler of the monitor
		if(tde.gui.monitorRulerGUI != null) {
			tde.gui.monitorRulerGUI.redraw();
		}
	}

	/**
	 * Drag mode of a click on this clip: near its left / right side, its start / end; elsewhere, a move
	 */
	int clipDragModeAt(Clip aClip,int aX) {
		if(aClip == null || aClip.bounds == null) {
			return CLIP_DRAG_MOVE;
		}
		//On a short clip, keep at least the middle half to move it
		int aEdgeW = Math.min(CLIP_EDGE_W, aClip.bounds.width/4);
		if(aX < aClip.bounds.x+aEdgeW) {
			return CLIP_DRAG_START;
		}
		if(aX >= aClip.bounds.x+aClip.bounds.width-aEdgeW) {
			return CLIP_DRAG_END;
		}
		return CLIP_DRAG_MOVE;
	}

	/**
	 * Burger menu of the time lines, below its icon aB (panel coordinates)
	 */
	public void showLinesMenu(Rectangle aB) {
		if(canvas.isDisposed()) {
			return;
		}
		//The menu takes the mouse: its release may never come to the canvas
		isMouseDown = false;
		isDrag = false;
		draggedClip = null;
		pendingClickClip = null;
		clickedMousePos = lastMousePos = null;
		if(linesMenu == null || linesMenu.isDisposed()) {
			linesMenu = new Menu(canvas);
		}
		for(MenuItem aMI : linesMenu.getItems()) {
			aMI.dispose();
		}
		menuItem("selectRight.png", "TimeLine.SelectAfterCursor", "TimeLine.KeysAfterClick", true, () -> {
			tde.timeLineStack.selectClipsAfterCursor();
			selectionChanged();
		});
		menuItem("selectRect.png", "TimeLine.SelectRect", "TimeLine.KeysRect", true, () -> {
			rangeMode = false;
			rectMode = true;
			canvas.setCursor(crossCursor);
			canvas.setFocus();
		});
		menuItem("clockRange.gif", "TimeLine.SelectTimeRange", "TimeLine.KeysRange", true, () -> {
			rectMode = false;
			rangeMode = true;
			canvas.setCursor(crossCursor);
			canvas.setFocus();
		});
		menuItem("cut.gif", "TimeLine.CutSelected", null, tde.selected.clips.size() > 0, () -> {
			if(tde.timeLineStack.cutSelectedClips() > 0) {
				selectionChanged();
			}
		});
		//Heavy clips computed apart: they go into a new montage, whose rendering takes their place
		menuItem("plusSquared.gif", "TimeLine.ToNewMontage", null, tde.selected.clips.size() > 0 && !MontageRenderGUI.isRunning(), () -> {
			tde.gui.display.asyncExec(() -> tde.gui.montageListGUI.extractSelectedClips());
		});
		linesMenu.setLocation(canvas.toDisplay(aB.x, aB.y+aB.height));
		linesMenu.setVisible(true);
	}

	/**
	 * Item of the burger menu, with its mouse shortcut (aKeys, null if none: %MOD% = Cmd on macOS, else Ctrl)
	 */
	void menuItem(String aIcon,String aText,String aKeys,boolean aEnabled,Runnable aAction) {
		MenuItem aMI = new MenuItem(linesMenu, SWT.PUSH);
		aMI.setImage(tde.gui.imgsSwt.getIcon(aIcon));
		String aLabel = tde.gui.lngs.get(aText);
		if(aKeys != null) {
			aLabel += "   ("+tde.gui.lngs.get(aKeys).replace("%MOD%", "cocoa".equals(SWT.getPlatform()) ? "Cmd" : "Ctrl")+")";
		}
		aMI.setText(aLabel);
		aMI.setEnabled(aEnabled);
		aMI.addListener(SWT.Selection, e -> aAction.run());
	}

	//Shortcuts of the mouse (see shortcutAt())
	static final int SHORTCUT_NONE = 0;
	static final int SHORTCUT_RIGHT = 1;//Ctrl (Cmd on macOS) + Shift + click: the clips right of the click
	static final int SHORTCUT_RANGE = 2;//Shift + drag on the ruler: a time range
	static final int SHORTCUT_RECT = 3;//Ctrl (Cmd on macOS) + drag on the lines: a rectangle selection
	//Mouse over the canvas (null: outside), for the cursor of the modifier keys
	Point hoverPos = null;
	Cursor rectCursor = null;
	Cursor rightCursor = null;
	Cursor rangeCursor = null;

	/**
	 * Shortcut that a click at this place with these modifier keys (SWT state mask) does
	 */
	int shortcutAt(int aX,int aY,int aState) {
		TimeLineStack aTLS = tde.timeLineStack;
		if(aTLS == null || aTLS.rulerBounds == null || aX < tde.config.lineHeadW()
				|| (aTLS.rangeEraseBounds != null && aTLS.rangeEraseBounds.contains(aX, aY))) {
			return SHORTCUT_NONE;
		}
		boolean aShift = (aState & SWT.SHIFT) != 0;
		//MOD1: Cmd on macOS (Ctrl there is a right click), Ctrl elsewhere
		boolean aMod = (aState & SWT.MOD1) != 0;
		if(aShift && aMod) {
			return SHORTCUT_RIGHT;
		}
		if(aShift && aTLS.rulerBounds.contains(aX, aY)) {
			return SHORTCUT_RANGE;
		}
		if(aMod && aY >= aTLS.rulerBounds.height) {
			return SHORTCUT_RECT;
		}
		return SHORTCUT_NONE;
	}

	/**
	 * Cursor of the mouse over the canvas (hoverPos) with these modifier keys: the action a click does
	 */
	void updateCursor(int aState) {
		if(canvas.isDisposed() || hoverPos == null || isMouseDown) {
			return;
		}
		Cursor aCursor;
		if(rectMode || rangeMode) {
			aCursor = crossCursor;
		}
		else {
			switch(shortcutAt(hoverPos.x, hoverPos.y, aState)) {
			case SHORTCUT_RIGHT: aCursor = rightCursor; break;
			case SHORTCUT_RANGE: aCursor = rangeCursor; break;
			case SHORTCUT_RECT: aCursor = rectCursor; break;
			default:
				//Resize cursor over the edges of the time range and the sides of a clip
				Clip aHovered = tde.timeLineStack.getClipAt(hoverPos.x, hoverPos.y);
				aCursor = tde.timeLineStack.rangeEdgeAt(hoverPos.x, hoverPos.y) != 0 ? resizeCursor
						: aHovered != null && clipDragModeAt(aHovered, hoverPos.x) != CLIP_DRAG_MOVE ? resizeCursor : null;
			}
		}
		if(canvas.getCursor() != aCursor) {
			canvas.setCursor(aCursor);
		}
	}

	/**
	 * Modifier keys pressed / released (whatever the focus): the cursor follows while the mouse is over the canvas
	 */
	void installModifierCursor() {
		org.eclipse.swt.widgets.Listener aL = e -> {
			if(hoverPos == null || canvas.isDisposed() || (e.keyCode & SWT.MODIFIER_MASK) == 0) {
				return;
			}
			//The state mask of the event does not include the key itself yet / any more
			int aState = e.type == SWT.KeyDown ? e.stateMask | e.keyCode : e.stateMask & ~e.keyCode;
			updateCursor(aState);
		};
		tde.gui.display.addFilter(SWT.KeyDown, aL);
		tde.gui.display.addFilter(SWT.KeyUp, aL);
		canvas.addListener(SWT.MouseExit, e -> hoverPos = null);
		canvas.addListener(SWT.Dispose, e -> {
			tde.gui.display.removeFilter(SWT.KeyDown, aL);
			tde.gui.display.removeFilter(SWT.KeyUp, aL);
		});
	}

	Cursor iconCursor(String aIcon,int aHotX,int aHotY) {
		return new Cursor(tde.gui.display, tde.gui.imgsSwt.getIcon(aIcon).getImageData(), aHotX, aHotY);
	}

	/**
	 * End (or cancel) of the rectangle selection
	 */
	void endRectMode() {
		rectMode = false;
		rectStart = null;
		rangeMode = false;
		rangeAnchorMS = -1;
		rangeEdgeHeld = false;
		tde.timeLineStack.selectionRect = null;
		if(!canvas.isDisposed()) {
			canvas.setCursor(null);
		}
	}

	/**
	 * Rectangle from the start of the rectangle selection to this point (positive sizes)
	 */
	Rectangle rectTo(int aX,int aY) {
		return new Rectangle(Math.min(rectStart.x, aX), Math.min(rectStart.y, aY)
				, Math.abs(aX-rectStart.x), Math.abs(aY-rectStart.y));
	}

	/**
	 * Click on a clip: its media shown selected in the media list, scrolled to it (nothing when the clip was
	 * unselected by the click)
	 */
	void revealClipMedia(Clip aClip) {
		try {
			if(aClip != null && aClip.selState > 0 && tde.gui.mediaListGUI != null
					//The rendering of a montage is not shown in the media list (see Montage)
					&& com.cubaix.TDenlive.medias.Montage.of(tde, aClip.getMedia()) == null) {
				tde.gui.mediaListGUI.revealMedia(aClip.getMedia());
			}
		}
		catch(Throwable t) {
			t.printStackTrace(System.err);
		}
	}

	/**
	 * Views refreshed after a change of the selection of clips made here (menu, rectangle)
	 */
	void selectionChanged() {
		tde.gui.setMultiPropsPanel(TDeGUI.MULTIPROPSPANEL_PROCESSORS);
		tde.gui.setMultiMonitorPanel(TDeGUI.MULTIMONITORPANEL_MONITOR);
		tde.gui.playerControlGUI.showClock(tde.timeLineStack.getTimePosMS());
		tde.gui.monitorGUI.setMedia(tde.timeLineStack);
		if(tde.selected.clips.size() > 0) {
			tde.gui.processorGUI.setProcessors(tde.selected.clips.lastElement().getProcessors());
		}
		else {
			tde.gui.processorGUI.clean();
		}
		redraw();
		tde.gui.monitorGUI.redraw();
	}

	/**
	 * Remove the selected clips (and their twins) from the time lines
	 */
	void trashSelectedClip() {
		if(tde.selected.clips.size() <= 0) {
			return;
		}
		for(Clip aC : new Vector<Clip>(tde.selected.clips)) {
			tde.timeLineStack.trashClip(aC);
		}
		tde.gui.processorGUI.clean();
		redraw();
		tde.gui.monitorGUI.redraw();
	}

	/**
	 * Trash of a line: removed, after a confirmation when it holds clips
	 */
	public void askDeleteLine(VideoTimeLine aTL) {
		int aN = aTL.clipList.size();
		if(aN > 0) {
			MessageBox aMB = new MessageBox(tde.gui.shell, SWT.ICON_WARNING | SWT.YES | SWT.NO);
			aMB.setText(tde.gui.lngs.get("TimeLine.DeleteLineTitle"));
			aMB.setMessage(tde.gui.lngs.get("TimeLine.DeleteLineNotEmpty").replace("%NAME%", aTL.getName()).replace("%N%", ""+aN));
			if(aMB.open() != SWT.YES) {
				return;
			}
		}
		tde.timeLineStack.deleteLine(aTL);
		tde.gui.processorGUI.clean();
		redraw();
		tde.gui.monitorGUI.redraw();
		if(tde.gui.monitorRulerGUI != null) {
			tde.gui.monitorRulerGUI.redraw();
		}
	}

	/**
	 * Media dragged from the media list (see MediaListGUI.MEDIA_DRAG_PREFIX), null if not one
	 */
	Media draggedMedia(Object aData) {
		if(!(aData instanceof String) || !((String)aData).startsWith(MediaListGUI.MEDIA_DRAG_PREFIX)) {
			return MediaListGUI.draggedMedia;
		}
		try {
			return tde.mediaList.getMediaByUID(Long.parseLong(((String)aData).substring(MediaListGUI.MEDIA_DRAG_PREFIX.length())));
		}
		catch(Throwable t) {
			return null;
		}
	}

	/**
	 * Drop of a media of the media list on a line: a clip at the time of the mouse
	 */
	void createDropTarget() {
		DropTarget aDT = new DropTarget(canvas, DND.DROP_COPY | DND.DROP_MOVE | DND.DROP_DEFAULT);
		aDT.setTransfer(new Transfer[] {TextTransfer.getInstance()});
		aDT.addDropListener(new DropTargetAdapter() {
			void preview(DropTargetEvent e) {
				//The data is only known at the drop: the media of the current drag
				Media aMedia = MediaListGUI.draggedMedia;
				Point aP = canvas.toControl(e.x, e.y);
				if(aMedia == null && MontageListGUI.draggedMontage != null) {
					//A montage: its rendering, when made, shows where it goes; else it is made at the drop
					aMedia = MontageListGUI.draggedMontage.renderMedia(tde);
					if(aMedia == null) {
						tde.timeLineStack.clearDropPreview();
						e.detail = tde.timeLineStack.getLineAtY(aP.y) != null ? DND.DROP_COPY : DND.DROP_NONE;
						redraw();
						return;
					}
				}
				if(aMedia != null && tde.timeLineStack.setDropPreview(aMedia, aP.x, aP.y)) {
					e.detail = DND.DROP_COPY;
				}
				else {
					e.detail = DND.DROP_NONE;
				}
				redraw();
			}
			@Override
			public void dragEnter(DropTargetEvent e) {
				preview(e);
			}
			@Override
			public void dragOver(DropTargetEvent e) {
				e.feedback = DND.FEEDBACK_SCROLL;
				preview(e);
			}
			@Override
			public void dragOperationChanged(DropTargetEvent e) {
				preview(e);
			}
			@Override
			public void dragLeave(DropTargetEvent e) {
				tde.timeLineStack.clearDropPreview();
				redraw();
			}
			@Override
			public void drop(DropTargetEvent e) {
				tde.timeLineStack.clearDropPreview();
				if(e.data instanceof String && ((String)e.data).startsWith(MontageListGUI.MONTAGE_DRAG_PREFIX)) {
					//A montage of the montage list: its rendering (made first if needed), after the drop
					Point aP = canvas.toControl(e.x, e.y);
					com.cubaix.TDenlive.medias.Montage aM = null;
					try {
						aM = tde.montageByUID(Long.parseLong(((String)e.data).substring(MontageListGUI.MONTAGE_DRAG_PREFIX.length())));
					}
					catch(Throwable t) {
						t.printStackTrace(System.err);
					}
					final com.cubaix.TDenlive.medias.Montage aDropped = aM;
					if(aDropped != null) {
						tde.gui.display.asyncExec(() -> tde.gui.montageListGUI.insert(aDropped, aP.x, aP.y));
					}
					redraw();
					return;
				}
				Media aMedia = draggedMedia(e.data);
				Point aP = canvas.toControl(e.x, e.y);
				Clip aClip = aMedia == null ? null : tde.timeLineStack.dropMedia(aMedia, aP.x, aP.y);
				if(aClip == null) {
					e.detail = DND.DROP_NONE;
					redraw();
					return;
				}
				//Like a double click in the media list
				canvas.setFocus();
				tde.gui.setMultiPropsPanel(TDeGUI.MULTIPROPSPANEL_PROCESSORS);
				tde.gui.setMultiMonitorPanel(TDeGUI.MULTIMONITORPANEL_MONITOR);
				tde.gui.playerControlGUI.showClock(tde.timeLineStack.getTimePosMS());
				tde.gui.monitorGUI.setMedia(tde.timeLineStack);
				tde.gui.processorGUI.setProcessors(aClip.getProcessors());
				tde.gui.mediaListGUI.redraw();
				redraw();
				tde.gui.monitorGUI.redraw();
			}
		});
	}

	void createContents(Composite aParent) {
		createDropTarget();
		resizeCursor = new Cursor(tde.gui.display, SWT.CURSOR_SIZEWE);
		crossCursor = new Cursor(tde.gui.display, SWT.CURSOR_CROSS);
		rectCursor = iconCursor("cursorRect.png", 0, 0);
		rightCursor = iconCursor("cursorRight.png", 1, 8);
		rangeCursor = iconCursor("cursorRange.png", 8, 8);
		canvas.addListener(SWT.Dispose, e -> {
			resizeCursor.dispose();
			crossCursor.dispose();
			rectCursor.dispose();
			rightCursor.dispose();
			rangeCursor.dispose();
		});
		installModifierCursor();
		//SUPPR / DEL (and the delete key of the Mac keyboards, which is a backspace)
		canvas.addListener(SWT.KeyDown, e -> {
			if(e.keyCode == SWT.ESC && (rectMode || rangeMode)) {
				endRectMode();
				redraw();
				return;
			}
			if(e.keyCode == SWT.DEL || e.keyCode == SWT.BS) {
				trashSelectedClip();
			}
		});
		canvas.addMouseListener(new MouseListener() {
			@Override
			public void mouseUp(MouseEvent e) {
				isMouseDown = false;
				if(rangeAnchorMS >= 0) {
					//End of the time range selection / of the move of one of its edges
					TimeLineStack aTLS = tde.timeLineStack;
					if(!rangeEdgeHeld) {
						aTLS.setTimeRange(rangeAnchorMS, aTLS.getTimeAtX(e.x), Math.round(CLIP_SNAP_PX/aTLS.pixPerMS));
					}
					endRectMode();
					clickedMousePos = lastMousePos = null;
					redraw();
					return;
				}
				if(rectStart != null) {
					//End of the rectangle selection: the clips it crosses
					Rectangle aR = rectTo(e.x, e.y);
					endRectMode();
					tde.timeLineStack.selectClipsInRect(aR, (e.stateMask & SWT.SHIFT) != 0);
					clickedMousePos = lastMousePos = null;
					selectionChanged();
					return;
				}
				if(pendingClickClip != null && clickedMousePos != null
						&& Math.abs(e.x-clickedMousePos.x) < CLICK_MOVE_PX && Math.abs(e.y-clickedMousePos.y) < CLICK_MOVE_PX) {
					//A click without a move on a clip already selected
					if(pendingUnselect) {
						tde.timeLineStack.unselectClip(pendingClickClip);
					}
					else {
						tde.timeLineStack.selectClip(pendingClickClip, false);
					}
					if(tde.selected.clips.size() > 0) {
						tde.gui.processorGUI.setProcessors(tde.selected.clips.lastElement().getProcessors());
						revealClipMedia(pendingClickClip);
					}
					else {
						tde.gui.processorGUI.clean();
					}
					redraw();
					tde.gui.monitorGUI.redraw();
				}
				pendingClickClip = null;
				//Refresh in case of a move
				if(isDrag && tde.selected.clips.size() > 0) {
					tde.gui.processorGUI.setProcessors(tde.selected.clips.lastElement().getProcessors());
				}
				isDrag = false;
				draggedClip = null;
				movedClips = new Vector<Clip>();
				clickedMousePos = lastMousePos = null;
			}
			@Override
			public void mouseDown(MouseEvent e) {
				if(isMouseDown) {
					//Multi events !?
					return;
				}
				isMouseDown = true;
				//To receive SUPPR / DEL
				canvas.setFocus();
				clickedMousePos = lastMousePos = new Point(e.x,e.y);
				boolean aShift = (e.stateMask & SWT.SHIFT) != 0;
				TimeLineStack aTLS = tde.timeLineStack;
				if(rangeMode) {
					if(e.button == 1 && e.x >= tde.config.lineHeadW() && aTLS.rulerBounds != null) {
						//Start of the time range (on the ruler too)
						rangeAnchorMS = aTLS.snapTimeMS(aTLS.getTimeAtX(e.x), (Clip)null, Math.round(CLIP_SNAP_PX/aTLS.pixPerMS));
						aTLS.setTimeRange(rangeAnchorMS, rangeAnchorMS, 0);
						redraw();
						return;
					}
					endRectMode();
				}
				int aEdge = rectMode || e.button != 1 ? 0 : aTLS.rangeEdgeAt(e.x, e.y);
				if(aEdge != 0) {
					//Move of an edge of the time range: the other one stays
					rangeAnchorMS = aEdge == 1 ? aTLS.rangeEndMS : aTLS.rangeStartMS;
					rangeEdgeHeld = true;
					return;
				}
				//Shortcuts without the menu (see shortcutAt(), which gives their cursor)
				int aShortcut = rectMode ? SHORTCUT_NONE : shortcutAt(e.x, e.y, e.stateMask);
				if(aShortcut == SHORTCUT_RIGHT) {
					//Ctrl (Cmd on macOS) + Shift + click: the clips that start at the time clicked or after it
					aTLS.selectClipsFrom(aTLS.getTimeAtX(e.x));
					selectionChanged();
					return;
				}
				if(aShortcut == SHORTCUT_RANGE) {
					//Shift + drag on the ruler: a time range (a click without a move changes nothing)
					rangeAnchorMS = aTLS.snapTimeMS(aTLS.getTimeAtX(e.x), (Clip)null, Math.round(CLIP_SNAP_PX/aTLS.pixPerMS));
					rangeEdgeHeld = true;
					return;
				}
				if(aShortcut == SHORTCUT_RECT) {
					//Ctrl (Cmd on macOS) + drag on the lines (on a clip too): a rectangle selection
					rectStart = new Point(e.x, e.y);
					aTLS.selectionRect = rectTo(e.x, e.y);
					redraw();
					return;
				}
				if(rectMode) {
					if(e.button == 1 && e.x >= tde.config.lineHeadW() && aTLS.rulerBounds != null && e.y >= aTLS.rulerBounds.height) {
						//Start of the rectangle selection
						rectStart = new Point(e.x, e.y);
						aTLS.selectionRect = rectTo(e.x, e.y);
						redraw();
						return;
					}
					//Elsewhere (ruler, heads of the lines...): the rectangle selection is canceled
					endRectMode();
				}
				pendingClickClip = null;
				pendingUnselect = false;
				draggedClip = null;
				Clip aHit = null;
				VideoTimeLine aHeadHit = null;
				if(!aTLS.selectWidget(e.x, e.y)) {
					VideoTimeLine aHead = aTLS.getLineHeadAt(e.x, e.y);
					aHeadHit = aHead;
					aHit = aHead == null ? aTLS.getClipAt(e.x, e.y) : null;
					if(aHead != null) {
						//Head of a line (V1, A1...): all its clips; shift: lines added to the selection
						aTLS.selectLine(aHead, aShift);
					}
					else if(aHit != null) {
						if(aHit.selState > 0 && (aShift || tde.selected.clips.size() > 1)) {
							//Kept with the others, to move them together
							aTLS.selectClip(aHit, true);
							pendingClickClip = aHit;
							pendingUnselect = aShift;
						}
						else {
							aTLS.selectClip(aHit, aShift);
						}
					}
					else if(!aShift) {
						//Empty place: selects the line
						aTLS.select(e.x, e.y, true);
					}
				}
				if(aHit != null && aHit.selState > 0) {
					draggedClip = aHit;
					clipDragMode = clipDragModeAt(aHit, e.x);
					draggedClipStartMS = aHit.getStartTimeMS();
					draggedClipEndMS = aHit.getMaxTimePosMS();
					//A move takes all the selected clips along
					movedClips = new Vector<Clip>();
					for(Clip aC : tde.selected.clips) {
						if(aTLS.getLineOf(aC) != null) {
							movedClips.add(aC);
						}
					}
					movedStartsMS = new long[movedClips.size()];
					movedLines = new VideoTimeLine[movedClips.size()];
					for(int c = 0;c < movedClips.size();c++) {
						movedStartsMS[c] = movedClips.elementAt(c).getStartTimeMS();
						movedLines[c] = aTLS.getLineOf(movedClips.elementAt(c));
					}
					draggedClipLine = aTLS.getLineOf(aHit);
					movedLineDelta = 0;
				}
				if(aHit != null) {
					revealClipMedia(aHit);
				}
				tde.gui.setMultiPropsPanel(TDeGUI.MULTIPROPSPANEL_PROCESSORS);
				tde.gui.setMultiMonitorPanel(TDeGUI.MULTIMONITORPANEL_MONITOR);
				//Back to the montage (in the monitor and on the clock)
				tde.gui.playerControlGUI.showClock(tde.timeLineStack.getTimePosMS());
				if(tde.selected.clips.size() > 0) {
					tde.gui.monitorGUI.setMedia(tde.timeLineStack);
					tde.gui.processorGUI.setProcessors(tde.selected.clips.lastElement().getProcessors());
				}
				else {
					tde.gui.monitorGUI.setMedia(tde.timeLineStack);
					tde.gui.processorGUI.clean();
				}
				if(aHeadHit != null) {
					//Head of a line: its properties (title, clips)
					tde.gui.showLineProps(aHeadHit);
				}
				redraw();
				tde.gui.monitorGUI.redraw();
			}
			@Override
			public void mouseDoubleClick(MouseEvent e) {
			}
		});
		canvas.addMouseMoveListener(new MouseMoveListener() {
			@Override
			public void mouseMove(MouseEvent e) {
				if(isMouseDown && rangeAnchorMS >= 0) {
					if(rangeEdgeHeld && clickedMousePos != null && Math.abs(e.x-clickedMousePos.x) < CLICK_MOVE_PX) {
						return;
					}
					rangeEdgeHeld = false;
					TimeLineStack aTLS = tde.timeLineStack;
					aTLS.setTimeRange(rangeAnchorMS, aTLS.getTimeAtX(e.x), Math.round(CLIP_SNAP_PX/aTLS.pixPerMS));
					redraw();
				}
				else if(isMouseDown && rectStart != null) {
					tde.timeLineStack.selectionRect = rectTo(e.x, e.y);
					redraw();
				}
				else if(isMouseDown) {
					if(tde.timeLineStack.rulerBounds.contains(clickedMousePos.x,clickedMousePos.y)){
						isDrag = true;
						long aTimePos = (int)((e.x - tde.timeLineStack.rulerBounds.x)/tde.timeLineStack.pixPerMS);
						if(aTimePos < 0) {
							aTimePos = 0;
						}
						tde.timeLineStack.setRequestedTimePosMS(aTimePos);
						redraw();
						tde.gui.monitorGUI.redraw();
					}
					else if(draggedClip != null && (isDrag || Math.abs(e.x-clickedMousePos.x) >= CLICK_MOVE_PX
							|| Math.abs(e.y-clickedMousePos.y) >= CLICK_MOVE_PX)) {
						//(a click on a clip of the selection, without a real move, does not move it)
						isDrag = true;
						//From the position at the click: no drift of the rounding
						long aDeltaMS = Math.round((e.x-clickedMousePos.x)/tde.timeLineStack.pixPerMS);
						//Sticky edges of the other clips: a window of a few pixels, whatever the zoom
						long aSnapMS = Math.round(CLIP_SNAP_PX/tde.timeLineStack.pixPerMS);
						if(clipDragMode == CLIP_DRAG_START) {
							draggedClip.trimStart(tde.timeLineStack.snapTimeMS(draggedClipStartMS+aDeltaMS, draggedClip, aSnapMS));
						}
						else if(clipDragMode == CLIP_DRAG_END) {
							draggedClip.trimEnd(tde.timeLineStack.snapTimeMS(draggedClipEndMS+aDeltaMS, draggedClip, aSnapMS));
						}
						else {
							TimeLineStack aTLS = tde.timeLineStack;
							//The start or the end of one of the clips sticks, the nearest
							aDeltaMS = aTLS.snapGroupMoveMS(movedClips, movedStartsMS, aDeltaMS, aSnapMS);
							//To the line under the mouse (of the same kind: video / audio), the others follow
							VideoTimeLine aUnder = aTLS.getLineAtY(e.y);
							if(aUnder != null && draggedClipLine != null
									&& (aUnder instanceof AudioTimeLine) == (draggedClipLine instanceof AudioTimeLine)) {
								movedLineDelta = aTLS.stackIndexOf(aUnder)-aTLS.stackIndexOf(draggedClipLine);
							}
							aTLS.moveClips(movedClips, movedStartsMS, movedLines, aDeltaMS, draggedClipLine, movedLineDelta);
						}
						lastMousePos = new Point(e.x,e.y);
						redraw();
						tde.gui.monitorGUI.redraw();
					}
				}
				else {
					hoverPos = new Point(e.x, e.y);
					updateCursor(e.stateMask);
					//Help message of the icon under the mouse (add a line, line on/off, line sound...)
					String aKey = tde.timeLineStack.getWidgetTip(e.x, e.y);
					String aTip = aKey == null ? null : tde.gui.lngs.get("Tip."+aKey);
					if(aTip == null ? canvas.getToolTipText() != null : !aTip.equals(canvas.getToolTipText())) {
						canvas.setToolTipText(aTip);
					}
				}
			}
		});
	}

	@Override
	void draw(GC aGC) {
		drawBounds = canvas.getClientArea();
		if(selfRepaint) {
			//Paint asked after a rendering, only to show it
			selfRepaint = false;
		}
		else {
			drawRequested = true;
		}
		synchronized (this) {
			if(drawTh == null) {
				drawTh = new Thread(new Runnable() {
					@Override
					public void run() {
						//Ends with its canvas (window built again)
						while(!tde.gui.display.isDisposed() && !canvas.isDisposed()) {
							if(!drawRequested) {
								try {
									Thread.sleep(10);
								}
								catch(Throwable t) {}
								continue;
							}
							drawRequested = false;
							try {
								Rectangle aDB = drawBounds;
								if(aDB.width <= 0 || aDB.height <= 0) {
									//Not yet laid out
									continue;
								}
								followCursor(aDB.width);
								BufferedImage aBI = ImageUtils.createImage(aDB.width, aDB.height);
								Graphics2D aG = (Graphics2D)aBI.getGraphics();
								TimeLineStack aTLS = tde.timeLineStack;
								final int aOX = origin.x;
								final double aPPM = aTLS != null ? aTLS.pixPerMS : -1;
								Rectangle aR = drawDbl(aG,new Rectangle(0, 0, aDB.width, aDB.height),aOX);
								aG.dispose();
								final int aTop = aTLS != null ? aTLS.cursorTopY : 0;
								final int aBottom = aTLS != null ? aTLS.cursorBottomY : 0;
								//Converted here, not in the UI thread (its pixel conversion by SWT is slow, and the UI
								//thread is on the path of each image of the monitor while playing)
								Image aSI = ImageUtils.imageToSwt(tde.gui.display, aBI);
								tde.gui.display.asyncExec(new Runnable() {
									@Override
									public void run() {
										if(canvas.isDisposed()) {
											aSI.dispose();
											return;
										}
										if(lastDbl != null) {
											lastDbl.dispose();
										}
										lastDbl = aSI;
										lastDblOriginX = aOX;
										lastDblPixPerMS = aPPM;
										lastCursorTopY = aTop;
										lastCursorBottomY = aBottom;
										if(aR != null) {
											//Always: the zoom may change the origin with the same size
											bounds = aR;
											adjustScrollBars();
										}
										//Drawing on the canvas outside of a paint event does nothing on macOS:
										//ask for a paint, which will only show lastDbl
										selfRepaint = true;
										canvas.redraw();
									}
								});
							}
							catch(Throwable t) {
								t.printStackTrace(System.err);
							}
						}
					}
				});
				drawTh.setDaemon(true);
				drawTh.start();
			}
		}
		if(lastDbl != null) {
			aGC.drawImage(lastDbl, 0, 0);
			drawTimeCursor(aGC);
		}
	}

	Rectangle drawDbl(Graphics2D aG,Rectangle aClientR,int aOriginX) {
		aG.setColor(tde.gui.colorsAwt.WHITE);
		aG.fillRect(aClientR.x,aClientR.y,aClientR.width,aClientR.height);
		TimeLineStack aTLS = tde.timeLineStack;
		if(aTLS == null) {
			return null;
		}
		//The time cursor is drawn over the image at each paint (drawTimeCursor())
		aTLS.cursorInImage = false;
		return aTLS.drawAwt(aG, aOriginX, origin.y,aClientR);
	}

	/**
	 * X of the time cursor on lastDbl (UI thread), Integer.MIN_VALUE if none
	 */
	int cursorX() {
		TimeLineStack aTLS = tde.timeLineStack;
		if(aTLS == null || lastDbl == null) {
			return Integer.MIN_VALUE;
		}
		return lastDblOriginX+tde.config.lineHeadW()+(int)(aTLS.getRequestedTimePosMS()*lastDblPixPerMS);
	}

	/**
	 * Time cursor over lastDbl (as TimeLineStack.drawAwt() would draw it): the pink line from the ruler down to
	 * the last line, its triangle on the ruler; not over the heads of the lines
	 */
	void drawTimeCursor(GC aGC) {
		int aX = cursorX();
		shownCursorX = aX;
		if(aX == Integer.MIN_VALUE) {
			return;
		}
		Rectangle aCA = canvas.getClientArea();
		int aTop = lastCursorTopY;
		aGC.setClipping(tde.config.lineHeadW()+1, 0, Math.max(0, aCA.width-tde.config.lineHeadW()-1), aCA.height);
		aGC.setForeground(tde.gui.colorsSwt.CUBAIX_PINK);
		aGC.setLineWidth(1);
		aGC.setAntialias(SWT.ON);
		aGC.drawLine(aX, aTop, aX, lastCursorBottomY);
		aGC.drawLine(aX, aTop, aX-5, aTop-10);
		aGC.drawLine(aX, aTop, aX+5, aTop-10);
		aGC.drawLine(aX-5, aTop-10, aX+5, aTop-10);
		aGC.setClipping((Rectangle)null);
	}

	/**
	 * The time moved (play): only the cursor is painted again over the image of the time lines, which is drawn
	 * again only when they slide to follow the cursor (or the time scale changed)
	 */
	public void redrawCursor() {
		if(canvas == null || canvas.isDisposed()) {
			return;
		}
		//Time relative to the selected clip, cursor of the ruler of the monitor (as redraw())
		if(tde.gui.playerControlGUI != null) {
			tde.gui.playerControlGUI.updateClipClock();
		}
		if(tde.gui.monitorRulerGUI != null) {
			tde.gui.monitorRulerGUI.redraw();
		}
		TimeLineStack aTLS = tde.timeLineStack;
		if(lastDbl == null || aTLS == null || aTLS.pixPerMS != lastDblPixPerMS) {
			redraw();
			return;
		}
		Rectangle aCA = canvas.getClientArea();
		followCursor(aCA.width);
		if(origin.x != lastDblOriginX && origin.x != slideOriginX) {
			//Slides: drawn again (meanwhile the cursor moves on the image shown)
			slideOriginX = origin.x;
			redraw();
			return;
		}
		int aNewX = cursorX();
		int aOldX = shownCursorX;
		if(aNewX == aOldX) {
			return;
		}
		//Strip of the old and new cursors (its triangle is 11 pixels wide)
		int aL = aOldX == Integer.MIN_VALUE ? aNewX-6 : Math.min(aOldX, aNewX)-6;
		int aR = aOldX == Integer.MIN_VALUE ? aNewX+7 : Math.max(aOldX, aNewX)+7;
		selfRepaint = true;
		canvas.redraw(aL, 0, aR-aL, aCA.height, false);
	}
}
