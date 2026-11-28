package com.cubaix.TDenlive.GUI;

import java.awt.image.BufferedImage;
import java.text.NumberFormat;
import java.util.Vector;

import org.eclipse.swt.SWT;
import org.eclipse.swt.events.MouseEvent;
import org.eclipse.swt.events.MouseListener;
import org.eclipse.swt.events.MouseMoveListener;
import org.eclipse.swt.events.SelectionEvent;
import org.eclipse.swt.events.SelectionListener;
import org.eclipse.swt.graphics.GC;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.graphics.RGB;
import org.eclipse.swt.graphics.Rectangle;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.widgets.Canvas;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Event;
import org.eclipse.swt.widgets.Listener;
import org.eclipse.swt.widgets.ScrollBar;

import com.cubaix.TDenlive.TDConfig;
import com.cubaix.TDenlive.TDenlive;
import com.cubaix.TDenlive.medias.Clip;
import com.cubaix.TDenlive.medias.Media;
import com.cubaix.TDenlive.medias.TimeLineStack;
import com.cubaix.TDenlive.processors.AnaMixer;
import com.cubaix.TDenlive.processors.EdgeDetector;
import com.cubaix.TDenlive.processors.Mixer;
import com.cubaix.TDenlive.processors.Processor;
import com.cubaix.TDenlive.processors.SbsMixer;
import com.cubaix.TDenlive.processors.Shapes;
import com.cubaix.TDenlive.shapes.ShapesEditor;

public class MonitorGUI {
	static final boolean _TRACE_DRAW_TIME = false;
	static int countDraw = 0;
	
	public int processingMode = TDConfig.PROCESSING_MODE_WORK;
	
	TDenlive tde = null;
	Canvas canvas = null;
	ScrollBar hSC = null;
	ScrollBar vSC = null;
	
	Media media = null;
	
	Rectangle clientR = null;
	
	int grid = 0;
	EdgeDetector edgeDetector = null;
	
	Mixer mixer = null;
	//0: idle, 1: the computing thread runs (processI()); changed under procLock with redrawAsked / computeAsked
	int processing = 0;
	final Object procLock = new Object();
	//Shown image (UI thread only: set and disposed by showProcessed())
	com.cubaix.TDenlive.utils.SwtStrips processedI = null;
	//redraw() while computing: the computing thread goes on with the next image
	boolean redrawAsked = false;
	//redraw() while idle: the next paint computes, even if merged with a paint only showing an image
	boolean computeAsked = false;
	//Next paint asked by showProcessed(): only shows the image
	boolean selfRepaint = false;
	Rectangle piBounds = null;

	//Rendering in progress (RendererGUI): the last pair produced, mixed in the mode of the monitor, shown instead of
	//its media; one mix at a time, the latest pair waiting wins
	volatile boolean rendering = false;
	Image renderedI = null;
	BufferedImage[] renderPending = null;
	boolean renderMixing = false;

	boolean isMouseDown = false;
	boolean isDrag = false;
	Point clickedMousePos = null;
	double clickedMixerX = 0;
	double clickedMixerY = 0;
	Point lastMousePos = null;

	//Shapes of the selected clip edited on the image: editor being dragged, views of the frame
	ShapesEditor shapesDragged = null;
	Vector<ShapesEditor.View> shapesViews = null;
	ShapesEditor.Listener shapesListener = new ShapesEditor.Listener() {
		@Override
		public void shapesChanged() {
			redraw();
		}
		@Override
		public void selectionChanged() {
			redraw();
		}
	};

	Vector<MonitorListener> monitorListeners = new Vector<MonitorListener>();
	Vector<MonitorListener> monitorListenersToRemove = new Vector<MonitorListener>();
	
	public MonitorGUI(TDenlive aTDe,Composite aParent) {
		tde = aTDe;
		mixer = new AnaMixer(tde,null,0);
		createContents(aParent);
	}

	//The main window has the wait cursor because SDXL computes (SmartFill.onComputing)
	static boolean waitCursorSet = false;

	void createContents(Composite aParent) {
		canvas = new Canvas (aParent,
//				SWT.BORDER |
				SWT.NO_BACKGROUND
				//Buffered by SWT / the system (draw() paints on the GC of the event: no image of the canvas size,
				//which GC.drawImage() would read back pixel by pixel)
				| SWT.DOUBLE_BUFFERED
				| SWT.FILL
				| SWT.V_SCROLL 
				| SWT.H_SCROLL
				);
		canvas.setLayoutData(new GridData(GridData.FILL_BOTH));
		//Hourglass while SDXL fills the holes of a 3D scene (the image of the monitor is computed meanwhile)
		com.cubaix.TDenlive.model3d.SmartFill.onComputing = () -> tde.gui.display.asyncExec(() -> {
			//The cursor of the main window too, back to normal at the end
			if(tde.gui.shell != null && !tde.gui.shell.isDisposed()) {
				boolean aOn = com.cubaix.TDenlive.model3d.SmartFill.isComputing();
				if(aOn != waitCursorSet) {
					waitCursorSet = aOn;
					tde.gui.shell.setCursor(aOn ? tde.gui.waitCursor : null);
				}
			}
			if(!canvas.isDisposed()) {
				canvas.redraw();
			}
		});

		vSC = canvas.getVerticalBar();
		vSC.setValues(1000,0,3000,1000,10,100);
		vSC.addSelectionListener(new SelectionListener() {
			@Override
			public void widgetSelected(SelectionEvent arg0) {
				mixer.setY((vSC.getSelection()-1000)/1000.0);
				redraw();
			}
			@Override
			public void widgetDefaultSelected(SelectionEvent arg0) {
				mixer.setY((vSC.getSelection()-1000)/1000.0);
				redraw();
			}
		});
		hSC = canvas.getHorizontalBar();
		hSC.setValues(1000,0,3000,1000,10,100);
		hSC.addSelectionListener(new SelectionListener() {
			@Override
			public void widgetSelected(SelectionEvent arg0) {
				mixer.setX((hSC.getSelection()-1000)/1000.0);
				redraw();
			}
			@Override
			public void widgetDefaultSelected(SelectionEvent arg0) {
				mixer.setX((hSC.getSelection()-1000)/1000.0);
				redraw();
			}
		});
		
		canvas.addListener(SWT.MouseWheel, new Listener() {
			@Override
			public void handleEvent(Event e) {
				mixer.setZoom(e.count > 0 ? mixer.getZoom()*(1.0+e.count/10.0):mixer.getZoom()/(1.0-e.count/10.0));
				redraw();
				e.doit = false;
			}
		});
		
		canvas.addMouseListener(new MouseListener() {
			@Override
			public void mouseUp(MouseEvent arg0) {
				isMouseDown = false;
				if(shapesDragged != null) {
					ShapesEditor aE = shapesDragged;
					shapesDragged = null;
					aE.mouseUp(arg0.x, arg0.y);
				}
				//Refresh in case of a move
				if(isDrag) {
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
				clickedMixerX = mixer.getX();
				clickedMixerY = mixer.getY();
				
				for(MonitorListener aML : monitorListenersToRemove) {
					monitorListeners.remove(aML);
				}
				monitorListenersToRemove.clear();
				for(MonitorListener aML : monitorListeners) {
					int aRGB = processedI.getPixel(e.x-piBounds.x, e.y-piBounds.y);
					aML.click((e.x-piBounds.x)/(double)piBounds.width,(e.y-piBounds.y)/(double)piBounds.height,aRGB);
				}
				for(MonitorListener aML : monitorListenersToRemove) {
					monitorListeners.remove(aML);
				}
				monitorListenersToRemove.clear();
				
				//Shapes of the selected clip: edited on the image (else the image is moved)
				Shapes aSh = getEditedShapes();
				if(aSh != null && shapesViews != null && e.button == 1) {
					canvas.setFocus();
					ShapesEditor aE = aSh.getEditor();
					aE.addListener(shapesListener);
					aE.setViews(shapesViews);
					if(aE.mouseDown(e.x, e.y)) {
						shapesDragged = aE;
						//Edited on the image: that processor has the focus
						if(aSh.targetMedia instanceof Clip && ((Clip)aSh.targetMedia).getFocused() != aSh && tde.gui.processorGUI != null) {
							tde.gui.processorGUI.focus(aSh);
						}
					}
				}
			}
			@Override
			public void mouseDoubleClick(MouseEvent arg0) {
			}
		});
		
		canvas.addMouseMoveListener(new MouseMoveListener() {
			@Override
			public void mouseMove(MouseEvent e) {
				if(shapesDragged != null) {
					shapesDragged.mouseMove(e.x, e.y);
					return;
				}
				if(!isMouseDown) {
					//Cursor over the shapes of the selected clip
					Shapes aSh = getEditedShapes();
					int aC = -1;
					if(aSh != null && shapesViews != null) {
						aSh.getEditor().setViews(shapesViews);
						aC = aSh.getEditor().getCursorAt(e.x, e.y);
					}
					canvas.setCursor(aC < 0 ? null : tde.gui.display.getSystemCursor(aC));
				}
				if(isMouseDown) {
					isDrag = true;
					double aDx = -(e.x - clickedMousePos.x)/(mixer.getZoom()*mixer.getLastSizedW());
					double aDy = -(e.y - clickedMousePos.y)/(mixer.getZoom()*mixer.getLastSizedH());
					mixer.setX(clickedMixerX+aDx);
					mixer.setY(clickedMixerY+aDy);
					lastMousePos = new Point(e.x,e.y);
					redraw();
				}
			}
		});
		
		canvas.addListener (SWT.Paint, e -> {
			draw(e.gc);
		});
		
		canvas.addListener(SWT.KeyDown, e -> {
			Shapes aSh = getEditedShapes();
			if(aSh == null) {
				return;
			}
			if(e.keyCode == SWT.DEL || e.keyCode == SWT.BS) {
				aSh.getEditor().addListener(shapesListener);
				aSh.getEditor().deleteSelected();
			}
			else if(e.keyCode == SWT.ESC) {
				aSh.getEditor().setTool(-1);
				aSh.getEditor().select(-1);
			}
		});
	}
	
	/**
	 * Shapes processor of the selected clip when it can be edited on the image
	 * (main monitor, work mode, montage, current time in the clip), else null
	 */
	public Shapes getEditedShapes() {
		if(tde.gui == null || tde.gui.monitorGUI != this || media != tde.timeLineStack
				|| processingMode != TDConfig.PROCESSING_MODE_WORK || tde.selected.clips.size() <= 0) {
			return null;
		}
		Clip aClip = tde.selected.clips.lastElement();
		long aTime = tde.timeLineStack.getTimePosMS();
		if(aTime < aClip.getStartTimeMS() || aTime >= aClip.getStartTimeMS()+aClip.getDurationMS()) {
			return null;
		}
		//The one having the focus when there are several (subtitles...)
		return aClip.getEditedShapes();
	}
	
	/**
	 * Frames of the montage on the screen (one per view of the mixer): {x, y, width, height, eye}
	 */
	Vector<ShapesEditor.View> computeShapesViews(Shapes aSh,Rectangle aPI) {
		Vector<ShapesEditor.View> aVs = new Vector<ShapesEditor.View>();
		long aTime = tde.timeLineStack.getTimePosMS();
		int aN = Math.max(1, mixer.getNbViews());
		double aZ = mixer.getZoom();
		double aSW = aPI.width/(double)aN;
		double aFW = aZ*aSW;
		double aFH = aZ*aPI.height;
		for(int v = 0;v < aN;v++) {
			double aFX = aPI.x+v*aSW-(mixer.getX()+0.5*(1.0-1.0/aZ))*aFW;
			double aFY = aPI.y-(mixer.getY()+0.5*(1.0-1.0/aZ))*aFH;
			int aEye = mixer.getViewEye(v);
			//Both eyes on the same image (anaglyph...): a view per eye, the handles of a shape
			//shifted in depth are doubled
			int[] aEyes = aEye == 0 ? new int[] {-1,1} : new int[] {aEye};
			for(int aE : aEyes) {
				ShapesEditor.View aV = new ShapesEditor.View(aFX, aFY, aFW, aFH, aE);
				aV.group = v;
				//Where the processors after the shapes put them
				aV.setChain(aSh.getChainTransform(aE, aTime));
				aVs.add(aV);
			}
		}
		return aVs;
	}
	
	/**
	 * Hidden shapes, selection and handles of the shapes of the selected clip
	 */
	void drawShapesOverlay(GC aGC,Rectangle aPI) {
		Shapes aSh = getEditedShapes();
		if(aSh == null) {
			shapesViews = null;
			return;
		}
		ShapesEditor aE = aSh.getEditor();
		aE.addListener(shapesListener);
		shapesViews = computeShapesViews(aSh, aPI);
		aE.setViews(shapesViews);
		aE.drawOverlay(aGC, aPI, Math.max(1, mixer.getNbViews()), canvas.getClientArea().height);
	}
	
	public void addMonitorListener(MonitorListener aML) {
		monitorListeners.add(aML);
	}

	public void removeMonitorListener(MonitorListener aML) {
		monitorListenersToRemove.add(aML);
	}

	public void zoomIn() {
		mixer.setZoom(mixer.getZoom()*1.2);
		redraw();
	}
	
	public void zoomOut() {
		mixer.setZoom(mixer.getZoom()/1.2);
		redraw();
	}
	
	public void zoomFit() {
		mixer.setZoom(1.0);
		mixer.setX(0.0);
		mixer.setY(0.0);
		vSC.setSelection(1000);
		hSC.setSelection(1000);
		redraw();
	}
	
	public void clean() {
		media = null;
		canvas.redraw();
	}
	
	public Media getMedia() {
		return media;
	}

	public void setMedia(Media aMedia) {
		media = aMedia;
		canvas.redraw();
		redrawRuler();
	}

	/**
	 * Time ruler of the main monitor (the shown media or its time may have changed)
	 */
	public boolean isRendering() {
		return rendering;
	}

	//Who shows its images (null: a rendering; else a computation that shows its images under construction:
	//inpainting precomputation, depth map), so that it ends only its own display
	volatile Object renderOwner = null;

	/**
	 * Start (true) or end (false) the display of the images of a rendering
	 */
	public void setRendering(boolean aOn) {
		setRendering(aOn, null);
	}

	/**
	 * Same for the images under construction of a computation (aOwner), GUI thread
	 */
	public void setRendering(boolean aOn,Object aOwner) {
		synchronized(this) {
			rendering = aOn;
			renderOwner = aOn ? aOwner : null;
			renderPending = null;
		}
		if(!aOn) {
			Image aOld = renderedI;
			renderedI = null;
			if(aOld != null && !aOld.isDisposed()) {
				aOld.dispose();
			}
		}
		if(!canvas.isDisposed()) {
			canvas.redraw();
		}
	}

	/**
	 * End of the display of the images of aOwner, if they are still the ones shown (GUI thread)
	 */
	public void releaseRendering(Object aOwner) {
		if(aOwner != null && rendering && renderOwner == aOwner) {
			setRendering(false, null);
			redraw();
		}
	}

	/**
	 * A rendering shows its images (not the images under construction of a computation)
	 */
	public boolean isRenderingOutput() {
		return rendering && renderOwner == null;
	}

	/**
	 * The monitor shows the images of aOwner
	 */
	public boolean isShownBy(Object aOwner) {
		return rendering && aOwner != null && renderOwner == aOwner;
	}

	/**
	 * Images under construction of aOwner (any thread): shown unless a rendering shows its own
	 */
	public void showBuilding(Object aOwner,BufferedImage aL,BufferedImage aR) {
		if(renderOwner == aOwner && rendering) {
			showRendered(aL, aR);
		}
	}

	/**
	 * A pair just produced by the rendering (any thread): mixed in a thread of its own at the size of the monitor,
	 * then shown; a pair coming while the previous one is mixed replaces the one waiting (the rendering never waits)
	 */
	public void showRendered(BufferedImage aL,BufferedImage aR) {
		if(aL == null || aR == null) {
			return;
		}
		synchronized(this) {
			if(!rendering) {
				return;
			}
			renderPending = new BufferedImage[] {aL, aR};
			if(renderMixing) {
				return;
			}
			renderMixing = true;
		}
		Thread aTh = new Thread(() -> {
			while(true) {
				BufferedImage[] aPair;
				Rectangle aR0;
				synchronized(MonitorGUI.this) {
					aPair = renderPending;
					renderPending = null;
					aR0 = clientR;
					if(aPair == null || !rendering) {
						renderMixing = false;
						return;
					}
				}
				if(aR0 == null || aR0.width <= 0 || aR0.height <= 0) {
					continue;
				}
				try {
					Image aI = mixer.mix2Swt(ldr(aPair[0]), ldr(aPair[1]), TDConfig.PROCESSING_MODE_FULLSCREEN, aR0);
					tde.gui.display.asyncExec(() -> {
						if(!rendering || canvas.isDisposed()) {
							aI.dispose();
							return;
						}
						Image aOld = renderedI;
						renderedI = aI;
						if(aOld != null && !aOld.isDisposed()) {
							aOld.dispose();
						}
						canvas.redraw();
					});
				}
				catch(Throwable t) {
					t.printStackTrace(System.err);
				}
			}
		}, "Monitor of the rendering");
		aTh.setDaemon(true);
		aTh.start();
	}

	/**
	 * An 8 bits image (the mixers don't take the HDR images of the rendering)
	 */
	static BufferedImage ldr(BufferedImage aBI) {
		int aT = aBI.getType();
		if(aT == BufferedImage.TYPE_INT_ARGB || aT == BufferedImage.TYPE_INT_RGB) {
			return aBI;
		}
		BufferedImage aO = com.cubaix.TDenlive.utils.ImageUtils.createImage(aBI.getWidth(), aBI.getHeight());
		java.awt.Graphics2D aG = aO.createGraphics();
		aG.drawImage(aBI, 0, 0, null);
		aG.dispose();
		return aO;
	}

	void redrawRuler() {
		if(tde.gui != null && tde.gui.monitorGUI == this && tde.gui.monitorRulerGUI != null) {
			tde.gui.monitorRulerGUI.redraw();
		}
	}
	
	public void setEdgeDetector(boolean aActive) {
		if(aActive) {
			edgeDetector = new EdgeDetector(tde,null);
		}
		else {
			edgeDetector = null;
		}
		canvas.redraw();
	}

	public void setProcessor(String aProcName) {
		if("AnaMixerRC".equals(aProcName)) {
			mixer = new AnaMixer(tde,null,0);
		}
		if("AnaMixerRC_Dubois".equals(aProcName)) {
			mixer = new AnaMixer(tde,null,3);
		}
		if("AnaMixerRC_ZhangAllister".equals(aProcName)) {
			mixer = new AnaMixer(tde,null,4);
		}
		if("AnaMixerYB".equals(aProcName)) {
			mixer = new AnaMixer(tde,null,1);
		}
		if("AnaMixerGM".equals(aProcName)) {
			mixer = new AnaMixer(tde,null,2);
		}
		if("SbsMixerX".equals(aProcName)) {
			mixer = new SbsMixer(tde,null,1);
		}
		if("SbsMixerP".equals(aProcName)) {
			mixer = new SbsMixer(tde,null,0);
		}
		if("InterlacedH1".equals(aProcName)) {
			mixer = new AnaMixer(tde,null,5);
		}
		if("InterlacedH2".equals(aProcName)) {
			mixer = new AnaMixer(tde,null,6);
		}
		if("InterlacedV1".equals(aProcName)) {
			mixer = new AnaMixer(tde,null,7);
		}
		if("InterlacedV2".equals(aProcName)) {
			mixer = new AnaMixer(tde,null,8);
		}
		if("Left".equals(aProcName)) {
			mixer = new AnaMixer(tde,null,9);
		}
		if("Right".equals(aProcName)) {
			mixer = new AnaMixer(tde,null,10);
		}
	}
	
	/**
	 * Computing thread (processing = 1, set by the paint which started it): computes the image, gives it to the UI
	 * thread (showProcessed()) and goes on at once with the next one if a redraw was asked meanwhile (play), without
	 * waiting for the paints; one image computed at a time (the images of a frame are kept in the medias)
	 */
	void processI() {
		//Time of the last image computed while playing (the next ones never before: videos are read forward)
		long aLastPlayedT = Long.MIN_VALUE;
		while(true) {
			long aStartMS = System.currentTimeMillis();
			boolean aAhead = false;
			Media aMedia = media;//Avoid a concurrent change
			try {
				synchronized (this) {
					if(aMedia == null || canvas.isDisposed() || tde.gui.display.isDisposed()) {
						break;
					}
					long aT = aMedia.getRequestedTimePosMS();
					if(isPlaying()) {
						//Shown about one image duration later (the fps measure): the image of that time, so that it
						//is on time with the clock of the play (the sound); the clock itself is never moved
						aT = Math.max(aLastPlayedT, Math.min(aT+Math.round(frameMS), Math.max(aT, playEndMS(aMedia, aT)-1)));
						aLastPlayedT = aT;
						aAhead = aT != aMedia.getRequestedTimePosMS();
					}
					aMedia.setTimePosMS(aT);
					aMedia.reBuild(processingMode, aT);
					if(edgeDetector != null) {
						edgeDetector.process2Awt(aMedia, processingMode, aT);
					}
					//In bands converted on all the cores
					com.cubaix.TDenlive.utils.SwtStrips aI = mixer.process2SwtStrips(aMedia,processingMode,clientR,aT);
					long aDurationMS = System.currentTimeMillis()-aStartMS;
					//Duration of an image (shown as fps), smoothed: the time computed ahead while playing
					frameMS = frameMS <= 0 ? aDurationMS : Math.min(MAX_AHEAD_MS, 0.7*frameMS+0.3*aDurationMS);
					showProcessed(aI, aDurationMS);
				}
			}
			catch(Throwable t) {
				t.printStackTrace(System.err);
			}
			synchronized (procLock) {
				if(!redrawAsked) {
					if(aAhead && !isPlaying() && aMedia == media) {
						//Paused / stopped after an image computed ahead: the image of the time of the cursor
						continue;
					}
					processing = 0;//May process again
					return;
				}
				//Asked while computing: the next image now
				redrawAsked = false;
			}
		}
		synchronized (procLock) {
			processing = 0;
			redrawAsked = false;
		}
	}

	//Smoothed duration of an image (ms), see processI(); bounded
	volatile double frameMS = 0;
	static final double MAX_AHEAD_MS = 500;
	//Set by the full screen window showing this monitor (its own play)
	FullScreenGUI fullScreen = null;

	/**
	 * True while the montage or a media is played in this monitor
	 */
	boolean isPlaying() {
		if(fullScreen != null) {
			return fullScreen.playing;
		}
		return tde.gui.playerControlGUI != null && tde.gui.playerControlGUI.playing;
	}

	/**
	 * Time where a play from aT stops: the end of the media, or of the selected time range of the montage when
	 * played from before its end (as PlayerControlGUI.rangeEndFor())
	 */
	long playEndMS(Media aMedia,long aT) {
		long aEnd = aMedia.getMaxTimePosMS();
		if(aMedia == tde.timeLineStack) {
			TimeLineStack aTLS = tde.timeLineStack;
			if(aTLS.hasTimeRange() && aT < aTLS.rangeEndMS) {
				aEnd = Math.min(aEnd, aTLS.rangeEndMS);
			}
		}
		return aEnd;
	}

	/**
	 * Image just computed (computing thread): shown by the UI thread, which disposes the previous one (never while
	 * it is painted)
	 */
	void showProcessed(com.cubaix.TDenlive.utils.SwtStrips aI,long aDurationMS) {
		if(aI == null) {
			return;
		}
		try {
			tde.gui.display.asyncExec(() -> {
				if(canvas.isDisposed()) {
					aI.dispose();
					return;
				}
				com.cubaix.TDenlive.utils.SwtStrips aOld = processedI;
				processedI = aI;
				if(aOld != null && !aOld.isDisposed()) {
					aOld.dispose();
				}
				if(_TRACE_DRAW_TIME) {
					System.out.println("Draw: "+(countDraw++)+" "+aDurationMS+" ms");
				}
				NumberFormat aNF = NumberFormat.getInstance();
				aNF.setMaximumFractionDigits(3);
				aNF.setMinimumFractionDigits(3);
				aNF.setMaximumIntegerDigits(3);
				aNF.setMinimumIntegerDigits(3);
				tde.gui.monitorControlGUI.status.setText((aNF.format(1000.0/Math.max(1, aDurationMS))+" fps"));
				tde.gui.monitorControlGUI.status.setImage(tde.gui.imgsSwt.getIcon("tuneButton.gif"));
				tde.gui.monitorControlGUI.status.layout(true);
				//Paint only showing it (no new computation for this paint)
				selfRepaint = true;
				canvas.redraw();
			});
		}
		catch(Throwable t) {
			//Display disposed meanwhile
			aI.dispose();
		}
	}

	public void redraw() {
		redrawRuler();
		synchronized (procLock) {
			if(processing == 1) {
				//Computed after the image in progress (by the same thread)
				redrawAsked = true;
				return;
			}
			computeAsked = true;
		}
		canvas.redraw();
	}

	void draw(GC aGC) {
		Rectangle aClientR = canvas.getClientArea();
		//Zoom shown in percent under the monitor (wheel, buttons, change of mixer)
		if(tde.gui.playerControlGUI != null) {
			tde.gui.playerControlGUI.showZoom(mixer.getZoom());
		}
		drawDbl(aGC);
		int aY = drawWorkCopies(aGC, aClientR);
		drawComputing(aGC, aClientR, aY);
	}

	/**
	 * Message over the top left corner while work copies of videos are made (VideoReader): the images shown come
	 * meanwhile from the original videos, reduced
	 */
	boolean workCopiesTimer = false;

	int drawWorkCopies(GC aGC,Rectangle aR) {
		int aN = com.cubaix.TDenlive.ffmpeg.VideoReader.workCopiesInProgress();
		if(aN <= 0) {
			return aR.y;
		}
		String aMsg = tde.gui.lngs.get("Monitor.WorkCopies").replace("%N%", ""+aN);
		//Its progress and the time left, redrawn every second meanwhile
		long[] aP = com.cubaix.TDenlive.ffmpeg.FfmpegTranscoder.workCopyProgress();
		if(aP != null) {
			String aLeft = "";
			if(aP[0] > 0 && aP[2] > 1000) {
				long aLeftS = aP[2]*(aP[1]-aP[0])/aP[0]/1000;
				aLeft = tde.gui.lngs.get("Monitor.WorkCopiesLeft").replace("%S%", (aLeftS >= 60 ? (aLeftS/60)+" min " : "")+(aLeftS%60)+" s");
			}
			aMsg += " "+(100*aP[0]/aP[1])+" %"+aLeft;
		}
		if(!workCopiesTimer) {
			workCopiesTimer = true;
			tde.gui.display.timerExec(1000, () -> {
				workCopiesTimer = false;
				if(canvas != null && !canvas.isDisposed() && com.cubaix.TDenlive.ffmpeg.VideoReader.workCopiesInProgress() > 0) {
					redraw();
				}
			});
		}
		org.eclipse.swt.graphics.Point aE = aGC.textExtent(aMsg);
		int aPad = 6;
		aGC.setAlpha(170);
		aGC.setBackground(tde.gui.colorsSwt.BLACK);
		aGC.fillRoundRectangle(aR.x+8, aR.y+8, aE.x+2*aPad, aE.y+2*aPad, 8, 8);
		aGC.setAlpha(255);
		aGC.setForeground(tde.gui.colorsSwt.WHITE);
		aGC.drawText(aMsg, aR.x+8+aPad, aR.y+8+aPad, true);
		return aR.y+8+aE.y+2*aPad;
	}

	/**
	 * Hourglass and elapsed time over the top left corner (below aY) while SDXL fills the holes of a 3D scene
	 * (model3d.SmartFill); redrawn every second meanwhile
	 */
	void drawComputing(GC aGC,Rectangle aR,int aY) {
		if(!com.cubaix.TDenlive.model3d.SmartFill.isComputing()) {
			return;
		}
		long aS = Math.max(0, (System.currentTimeMillis()-com.cubaix.TDenlive.model3d.SmartFill.computingSinceMS())/1000);
		String aMsg = tde.gui.lngs.get("Monitor.Filling").replace("%S%", ""+aS)
				.replace("%MODEL%", tde.gui.lngs.get(com.cubaix.TDenlive.model3d.SmartFill.computingModel().key()));
		org.eclipse.swt.graphics.Image aIcon = tde.gui.imgsSwt.getIcon("wait.png");
		Rectangle aIB = aIcon != null ? aIcon.getBounds() : new Rectangle(0, 0, 0, 0);
		org.eclipse.swt.graphics.Point aE = aGC.textExtent(aMsg);
		int aPad = 6;
		int aH = Math.max(aE.y, aIB.height);
		int aX0 = aR.x+8, aY0 = aY+8;
		aGC.setAlpha(170);
		aGC.setBackground(tde.gui.colorsSwt.BLACK);
		aGC.fillRoundRectangle(aX0, aY0, aIB.width+(aIB.width > 0 ? aPad : 0)+aE.x+2*aPad, aH+2*aPad, 8, 8);
		aGC.setAlpha(255);
		if(aIcon != null) {
			aGC.drawImage(aIcon, aX0+aPad, aY0+aPad+(aH-aIB.height)/2);
		}
		aGC.setForeground(tde.gui.colorsSwt.WHITE);
		aGC.drawText(aMsg, aX0+aPad+aIB.width+(aIB.width > 0 ? aPad : 0), aY0+aPad+(aH-aE.y)/2, true);
		tde.gui.display.timerExec(1000, () -> {
			if(!canvas.isDisposed()) {
				canvas.redraw();
			}
		});
	}

	void drawDbl(GC aGC) {
		long aStartMS = System.currentTimeMillis();
		tde.gui.monitorControlGUI.status.setImage(tde.gui.imgsSwt.getIcon("gearsBlack.gif"));
		tde.gui.monitorControlGUI.status.layout(true);
		clientR = canvas.getClientArea();
		aGC.setBackground(tde.gui.colorsSwt.BLACK);
		aGC.fillRectangle(clientR);
		if(rendering) {
			//Images of the rendering in progress
			tde.gui.monitorControlGUI.status.setImage(tde.gui.imgsSwt.getIcon("tuneButton.gif"));
			tde.gui.monitorControlGUI.status.layout(true);
			Image aRI = renderedI;
			if(aRI == null || aRI.isDisposed()) {
				drawGrid(aGC,clientR);
				return;
			}
			Rectangle aB = aRI.getBounds();
			int aX = (clientR.width-aB.width)/2;
			int aY = (clientR.height-aB.height)/2;
			aGC.drawImage(aRI, aX, aY);
			piBounds = new Rectangle(aX, aY, aB.width, aB.height);
			drawGrid(aGC,piBounds);
			return;
		}
		if(media == null) {
			tde.gui.monitorControlGUI.status.setImage(tde.gui.imgsSwt.getIcon("tuneButton.gif"));
			tde.gui.monitorControlGUI.status.layout(true);
			drawGrid(aGC,clientR);
			return;
		}
		//A paint asked by showProcessed() only shows the image; any other one (redraw(), expose, resize...) computes
		boolean aSelf = selfRepaint;
		selfRepaint = false;
		boolean aStart = false;
		synchronized (procLock) {
			if(processing == 0 && (processedI == null || !aSelf || computeAsked)) {
				processing = 1;//Processing
				computeAsked = false;
				aStart = true;
			}
		}
		if(aStart) {
			Thread aTh = new Thread(this::processI, "Monitor computation");
			aTh.setDaemon(true);
			aTh.start();
		}
		if(processedI == null) {
			drawGrid(aGC,clientR);
			return;
		}
		Rectangle aLeftR = processedI.getBounds();
		int aX = (int)((clientR.width - aLeftR.width)/2.0);
		int aY = (int)((clientR.height - aLeftR.height)/2.0);
		aGC.setBackground(tde.gui.colorsSwt.WHITE);//DARK_GRAY);
		aGC.fillRectangle(clientR);
		processedI.draw(aGC, aX, aY);
		aGC.setForeground(tde.gui.colorsSwt.WHITE);
		aGC.drawRectangle(aX, aY, aLeftR.width-1, aLeftR.height-1);
		piBounds = new Rectangle(aX, aY, aLeftR.width, aLeftR.height);
		drawShapesOverlay(aGC, piBounds);
		if(!aStart && processing == 0) {
			tde.gui.monitorControlGUI.status.setImage(tde.gui.imgsSwt.getIcon("tuneButton.gif"));
			tde.gui.monitorControlGUI.status.layout(true);
		}
		drawGrid(aGC,piBounds = new Rectangle(aX, aY, aLeftR.width, aLeftR.height));
	}
	
	void drawGrid(GC aGC,Rectangle aR) {
		if(grid == 0) {
			return;
		}
		aGC.setForeground(tde.gui.colorsSwt.WHITE);
		for(int g = 1;g<=grid;g++) {
			aGC.drawLine(aR.x, aR.y+(g*aR.height)/(grid+1), aR.x+aR.width, aR.y+(g*aR.height)/(grid+1));
			aGC.drawLine(aR.x+(g*aR.width)/(grid+1),aR.y,aR.x+(g*aR.width)/(grid+1),aR.y+aR.height);
		}
	}
}
