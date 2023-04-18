package com.cubaix.TDenlive.GUI;

import java.awt.Graphics2D;
import java.awt.image.BufferedImage;

import org.eclipse.swt.events.MouseEvent;
import org.eclipse.swt.events.MouseListener;
import org.eclipse.swt.events.MouseMoveListener;
import org.eclipse.swt.graphics.GC;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.graphics.Rectangle;
import org.eclipse.swt.widgets.Composite;

import com.cubaix.TDenlive.TDenlive;
import com.cubaix.TDenlive.medias.Clip;
import com.cubaix.TDenlive.utils.ImageUtils;

public class TimeLineStackGUI extends CanvasPanel {
	boolean isMouseDown = false;
	boolean isDrag = false;
	Point clickedMousePos = null;
	Point lastMousePos = null;
	Thread drawTh = null;
	boolean drawRequested = false;
	Rectangle drawBounds = null;
	Image lastDbl = null;
	
	public TimeLineStackGUI(TDenlive aTDe, Composite aParent) {
		super(aTDe, aParent);
		createContents(aParent);
	}

	void createContents(Composite aParent) {
		canvas.addMouseListener(new MouseListener() {
			@Override
			public void mouseUp(MouseEvent e) {
				isMouseDown = false;
				//Refresh in case of a move
				if(isDrag && tde.selected.clips.size() > 0) {
					tde.gui.processorGUI.setProcessors(tde.selected.clips.lastElement().getProcessors());
				}
				isDrag = false;
				clickedMousePos = lastMousePos = null;
			}
			@Override
			public void mouseDown(MouseEvent e) {
				if(isMouseDown) {
					//Multi events !?
					return;
				}
				isMouseDown = true;
				clickedMousePos = lastMousePos = new Point(e.x,e.y);
				tde.timeLineStack.select(e.x, e.y, true);
				tde.gui.setMultiPropsPanel(TDeGUI.MULTIPROPSPANEL_PROCESSORS);
				tde.gui.setMultiMonitorPanel(TDeGUI.MULTIMONITORPANEL_MONITOR);
				if(tde.selected.clips.size() > 0) {
					tde.gui.monitorGUI.setMedia(tde.timeLineStack);
					tde.gui.processorGUI.setProcessors(tde.selected.clips.lastElement().getProcessors());
				}
				else {
					tde.gui.monitorGUI.setMedia(tde.timeLineStack);
					tde.gui.processorGUI.clean();
				}
				canvas.redraw();
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
						canvas.redraw();
						tde.gui.monitorGUI.redraw();
					}
					else if(tde.selected.clips.size() > 0) {
						Clip aClip = tde.selected.clips.lastElement();
						if(aClip != null && lastMousePos != null) {
							isDrag = true;
							aClip.setStartTimeMS((int)(aClip.getStartTimeMS()+(e.x-lastMousePos.x)/tde.timeLineStack.pixPerMS));
							lastMousePos = new Point(e.x,e.y);
							canvas.redraw();
							tde.gui.monitorGUI.redraw();
						}
					}
				}
			}
		});
	}

	@Override
	void draw(GC aGC) {
		drawBounds = canvas.getClientArea();
		drawRequested = true;
		synchronized (this) {
			if(drawTh == null) {
				drawTh = new Thread(new Runnable() {
					@Override
					public void run() {
						while(true) {
							if(!drawRequested) {
								try {
									Thread.sleep(10);
								}
								catch(Throwable t) {}
								continue;
							}
							drawRequested = false;
							BufferedImage aBI = ImageUtils.createImage(drawBounds.width, drawBounds.height);
							Graphics2D aG = (Graphics2D)aBI.getGraphics();
							Rectangle aR = drawDbl(aG,new Rectangle(0, 0, drawBounds.width, drawBounds.height));
							tde.gui.display.asyncExec(new Runnable() {
								@Override
								public void run() {
									if(lastDbl != null) {
										lastDbl.dispose();
									}
									lastDbl = ImageUtils.imageToSwt(tde.gui.display, aBI);
									GC aGC = new GC(canvas);
									aGC.drawImage(lastDbl, 0,0);//aClientR.x, aClientR.y);
									if(aR != null && aR.width != bounds.width || aR.height != bounds.height) {
										bounds = aR;
										adjustScrollBars();
									}
									else {
										bounds = aR;
									}
									aGC.dispose();
								}
							});
						}
					}
				});
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
