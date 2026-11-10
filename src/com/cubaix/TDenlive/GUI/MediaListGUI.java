package com.cubaix.TDenlive.GUI;

import java.io.File;
import java.util.Vector;

import org.eclipse.swt.SWT;
import org.eclipse.swt.dnd.DND;
import org.eclipse.swt.dnd.DragSource;
import org.eclipse.swt.dnd.DragSourceAdapter;
import org.eclipse.swt.dnd.DragSourceEvent;
import org.eclipse.swt.dnd.TextTransfer;
import org.eclipse.swt.dnd.Transfer;
import org.eclipse.swt.graphics.GC;
import org.eclipse.swt.graphics.Rectangle;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.MessageBox;

import com.cubaix.TDenlive.TDenlive;
import com.cubaix.TDenlive.medias.Clip;
import com.cubaix.TDenlive.medias.Media;

public class MediaListGUI extends CanvasPanel {

	public MediaListGUI(TDenlive aTDe,Composite aParent) {
		super(aTDe, aParent);
		createContents(aParent);
	}

	//Data of a media dragged to the time lines: prefix + UID of the media
	public static final String MEDIA_DRAG_PREFIX = "3Denlive-media:";
	//Media being dragged (the drop target only gets the data at the drop)
	static Media draggedMedia = null;

	/**
	 * Drag of a media to a line of the time lines (see TimeLineStackGUI.createDropTarget())
	 */
	void createDragSource() {
		DragSource aDS = new DragSource(canvas, DND.DROP_COPY | DND.DROP_MOVE);
		aDS.setTransfer(new Transfer[] {TextTransfer.getInstance()});
		aDS.addDragListener(new DragSourceAdapter() {
			@Override
			public void dragStart(DragSourceEvent e) {
				draggedMedia = null;
				//The media selected by the mouse down, not its trash button
				if(tde.mediaList.trashAt(e.x, e.y) != null || tde.selected.medias.size() != 1) {
					e.doit = false;
					return;
				}
				draggedMedia = tde.selected.medias.elementAt(0);
			}
			@Override
			public void dragSetData(DragSourceEvent e) {
				if(draggedMedia != null) {
					e.data = MEDIA_DRAG_PREFIX+draggedMedia.UID;
				}
			}
			@Override
			public void dragFinished(DragSourceEvent e) {
				draggedMedia = null;
				tde.timeLineStack.clearDropPreview();
				tde.gui.timeLinesGUI.redraw();
			}
		});
	}

	void createContents(Composite aParent) {
		createDragSource();
		tde.gui.tip(canvas, "MediaList");
		//Trash buttons: hover frame and help
		canvas.addListener(SWT.MouseMove, e -> {
			boolean aWasOn = tde.mediaList.trashAt(tde.mediaList.hoverX, tde.mediaList.hoverY) != null;
			tde.mediaList.hoverX = e.x;
			tde.mediaList.hoverY = e.y;
			boolean aOn = tde.mediaList.trashAt(e.x, e.y) != null;
			if(aOn != aWasOn) {
				tde.gui.tip(canvas, aOn ? "MediaTrash" : "MediaList");
				canvas.redraw();
			}
		});
		canvas.addListener(SWT.MouseExit, e -> {
			boolean aWasOn = tde.mediaList.trashAt(tde.mediaList.hoverX, tde.mediaList.hoverY) != null;
			tde.mediaList.hoverX = tde.mediaList.hoverY = -1;
			if(aWasOn) {
				tde.gui.tip(canvas, "MediaList");
				canvas.redraw();
			}
		});
		canvas.addListener(SWT.MouseDown,e -> {
			Media aTrashed = tde.mediaList.trashAt(e.x, e.y);
			if(aTrashed != null) {
				//Not the second click of a double click (it would remove the next media)
				if(e.count == 1 && e.button == 1) {
					tde.gui.display.asyncExec(() -> trashMedia(aTrashed));
				}
				return;
			}
			tde.mediaList.select(e.x, e.y, true);
			tde.gui.setMultiPropsPanel(TDeGUI.MULTIPROPSPANEL_PROPS);
			tde.gui.setMultiMonitorPanel(TDeGUI.MULTIMONITORPANEL_MONITOR);
			if(tde.selected.medias.size() == 1) {
				//First image of the media (Play then plays it alone)
				tde.selected.medias.elementAt(0).setRequestedTimePosMS(0);
				tde.gui.playerControlGUI.showClock(0);
				tde.gui.monitorGUI.setMedia(tde.selected.medias.elementAt(0));
				tde.gui.propsGUI.setMedia(tde.selected.medias.elementAt(0));
			}
			else {
				tde.gui.monitorGUI.clean();
				tde.gui.propsGUI.clean();
			}
			canvas.redraw();
		});
		
		canvas.addListener(SWT.MouseDoubleClick,e -> {
			if(tde.mediaList.trashAt(e.x, e.y) != null) {
				return;
			}
			tde.mediaList.select(e.x, e.y, true);
			if(tde.selected.medias.size() == 1) {
				Media aSelMedia = tde.selected.medias.elementAt(0);
				tde.timeLineStack.addMedia(aSelMedia);
				
				tde.gui.setMultiPropsPanel(TDeGUI.MULTIPROPSPANEL_PROCESSORS);
				tde.gui.setMultiMonitorPanel(TDeGUI.MULTIMONITORPANEL_MONITOR);
				tde.gui.monitorGUI.setMedia(tde.timeLineStack);
				tde.gui.processorGUI.setProcessors(tde.selected.clips.elementAt(0).getProcessors());//Must be in
				tde.gui.monitorGUI.redraw();
				tde.gui.timeLinesGUI.redraw();
			}
			else {
				//??
			}
			canvas.redraw();
		});

	}
	
	/**
	 * Remove a media from the list, after a confirmation if it is used in the montage
	 * (its clips are removed from the time lines too)
	 */
	void trashMedia(Media aMedia) {
		if(!tde.mediaList.mediaList.contains(aMedia)) {
			return;
		}
		Vector<Clip> aClips = tde.timeLineStack.clipsOf(aMedia);
		Vector<Clip> aDepths = tde.timeLineStack.depthUsesOf(aMedia);
		if(aClips.size() > 0 || aDepths.size() > 0) {
			String aName = aMedia.fileName == null ? "?" : new File(aMedia.fileName).getName();
			String aMsg = tde.gui.lngs.get(aClips.size() > 0 ? "MediaTrash.Used" : "MediaTrash.UsedDepthOnly")
					.replace("%s", aName).replace("%n", ""+aClips.size());
			if(aClips.size() > 0 && aDepths.size() > 0) {
				aMsg += "\n"+tde.gui.lngs.get("MediaTrash.Depth").replace("%n", ""+aDepths.size());
			}
			else if(aDepths.size() > 0) {
				aMsg = aMsg.replace("%d", ""+aDepths.size());
			}
			aMsg += "\n\n"+tde.gui.lngs.get("MediaTrash.Confirm");
			MessageBox aMB = new MessageBox(tde.gui.shell, SWT.ICON_WARNING|SWT.YES|SWT.NO);
			aMB.setText(tde.gui.lngs.get("MediaTrash.Title"));
			aMB.setMessage(aMsg);
			if(aMB.open() != SWT.YES) {
				return;
			}
		}
		//Removed clips with their twins
		Vector<Clip> aRemoved = new Vector<Clip>();
		for(Clip aC : aClips) {
			aRemoved.add(aC);
			aRemoved.addAll(aC.getTwinClips());
		}
		boolean aShownClipRemoved = false;
		ProcessorsGUI aPG = tde.gui.processorGUI;
		for(Clip aC : aRemoved) {
			aShownClipRemoved |= tde.selected.clips.contains(aC)
					|| (aPG != null && aPG.processors != null && aPG.processors == aC.getProcessors());
		}
		try {
			tde.timeLineStack.releaseMedia(aMedia);
			tde.mediaList.removeMedia(aMedia);
		}
		catch(Throwable t) {
			t.printStackTrace(System.err);
		}
		//Only one of the properties and processors panels exists at a time (the other is null)
		Media aMonitored = tde.gui.monitorGUI.getMedia();
		if(aMonitored == aMedia || aRemoved.contains(aMonitored)) {
			tde.gui.monitorGUI.clean();
		}
		MediaPropsGUI aProps = tde.gui.propsGUI;
		if(aProps != null && !aProps.container.isDisposed()
				&& (aProps.media == aMedia || aRemoved.contains(aProps.media))) {
			aProps.clean();
		}
		if(aPG != null && !aPG.container.isDisposed() && (aShownClipRemoved || aDepths.size() > 0)) {
			//The panel of a removed clip, or a depth map combo with the media
			aPG.clean();
		}
		canvas.redraw();
		tde.gui.timeLinesGUI.redraw();
		tde.gui.monitorGUI.redraw();
	}

	@Override
	void draw(GC aGC) {
		Rectangle aClientR = canvas.getClientArea();
		aGC.setBackground(tde.gui.colorsSwt.WHITE);
		aGC.fillRectangle(aClientR);
		Rectangle aR = tde.mediaList.drawSwt(aGC, origin.x, origin.y,aClientR);
		if(aR.width != bounds.width || aR.height != bounds.height) {
			bounds = aR;
			adjustScrollBars();
		}
		else {
			bounds = aR;
		}
	}
}
