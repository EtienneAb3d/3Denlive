package com.cubaix.TDenlive.GUI;

import java.awt.Graphics2D;
import java.awt.image.BufferedImage;

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
import org.eclipse.swt.widgets.Scale;

import com.cubaix.TDenlive.TDenlive;
import com.cubaix.TDenlive.medias.Clip;
import com.cubaix.TDenlive.medias.TimeLineStack;
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
	Cursor resizeCursor = null;
	Point clickedMousePos = null;
	Point lastMousePos = null;
	Thread drawTh = null;
	volatile boolean drawRequested = false;
	//Next paint only shows the image just rendered (no new rendering request)
	boolean selfRepaint = false;
	volatile Rectangle drawBounds = null;
	Image lastDbl = null;

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
		int aVisibleW = aClientW-tde.config.thumbW;
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
		int aVisibleW = canvas.getClientArea().width-tde.config.thumbW;
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
		double aAnchorX = origin.x+tde.config.thumbW+aAnchorTime*aOldPix;//On screen
		if(aAnchorX < tde.config.thumbW || aAnchorX > aClientW) {
			aAnchorX = tde.config.thumbW;
			aAnchorTime = (long)(-origin.x/aOldPix);
		}
		aTLS.pixPerMS = aNewPix;
		origin.x = (int)Math.min(0, aAnchorX-tde.config.thumbW-aAnchorTime*aNewPix);
		adjustScrollBars();
		redraw();
	}

	@Override
	public void redraw() {
		//Content changed: always render again, even if the paint is merged with a self repaint
		drawRequested = true;
		super.redraw();
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
	 * Remove the selected clip (and its twins) from the time lines
	 */
	void trashSelectedClip() {
		if(tde.selected.clips.size() <= 0) {
			return;
		}
		tde.timeLineStack.trashClip(tde.selected.clips.lastElement());
		tde.gui.processorGUI.clean();
		redraw();
		tde.gui.monitorGUI.redraw();
	}

	void createContents(Composite aParent) {
		resizeCursor = new Cursor(tde.gui.display, SWT.CURSOR_SIZEWE);
		canvas.addListener(SWT.Dispose, e -> resizeCursor.dispose());
		//SUPPR / DEL (and the delete key of the Mac keyboards, which is a backspace)
		canvas.addListener(SWT.KeyDown, e -> {
			if(e.keyCode == SWT.DEL || e.keyCode == SWT.BS) {
				trashSelectedClip();
			}
		});
		canvas.addMouseListener(new MouseListener() {
			@Override
			public void mouseUp(MouseEvent e) {
				isMouseDown = false;
				//Refresh in case of a move
				if(isDrag && tde.selected.clips.size() > 0) {
					tde.gui.processorGUI.setProcessors(tde.selected.clips.lastElement().getProcessors());
				}
				isDrag = false;
				draggedClip = null;
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
				tde.timeLineStack.select(e.x, e.y, true);
				draggedClip = null;
				if(tde.selected.clips.size() > 0) {
					Clip aClip = tde.selected.clips.lastElement();
					if(aClip.bounds != null && aClip.bounds.contains(e.x, e.y)) {
						draggedClip = aClip;
						clipDragMode = clipDragModeAt(aClip, e.x);
						draggedClipStartMS = aClip.getStartTimeMS();
						draggedClipEndMS = aClip.getMaxTimePosMS();
					}
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
				if(isMouseDown) {
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
					else if(draggedClip != null) {
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
							//Its start or its end sticks, the nearest
							draggedClip.setStartTimeMS(tde.timeLineStack.snapMoveMS(draggedClipStartMS+aDeltaMS
									, draggedClipEndMS+aDeltaMS, draggedClip, aSnapMS));
						}
						lastMousePos = new Point(e.x,e.y);
						redraw();
						tde.gui.monitorGUI.redraw();
					}
				}
				else {
					//Resize cursor over the sides of a clip
					Clip aHovered = tde.timeLineStack.getClipAt(e.x, e.y);
					Cursor aCursor = aHovered != null && clipDragModeAt(aHovered, e.x) != CLIP_DRAG_MOVE ? resizeCursor : null;
					if(canvas.getCursor() != aCursor) {
						canvas.setCursor(aCursor);
					}
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
								Rectangle aR = drawDbl(aG,new Rectangle(0, 0, aDB.width, aDB.height));
								tde.gui.display.asyncExec(new Runnable() {
									@Override
									public void run() {
										if(canvas.isDisposed()) {
											return;
										}
										if(lastDbl != null) {
											lastDbl.dispose();
										}
										lastDbl = ImageUtils.imageToSwt(tde.gui.display, aBI);
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
		}
	}

	Rectangle drawDbl(Graphics2D aG,Rectangle aClientR) {
		aG.setColor(tde.gui.colorsAwt.WHITE);
		aG.fillRect(aClientR.x,aClientR.y,aClientR.width,aClientR.height);
		if(tde.timeLineStack == null) {
			return null;
		}
		return tde.timeLineStack.drawAwt(aG, origin.x, origin.y,aClientR);
	}
}
