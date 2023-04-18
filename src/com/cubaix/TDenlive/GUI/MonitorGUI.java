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
import com.cubaix.TDenlive.medias.Media;
import com.cubaix.TDenlive.processors.AnaMixer;
import com.cubaix.TDenlive.processors.EdgeDetector;
import com.cubaix.TDenlive.processors.Mixer;
import com.cubaix.TDenlive.processors.SbsMixer;

public class MonitorGUI {
	static final boolean _TRACE_DRAW_TIME = true;
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
	int processing = 0;
	long processingStart = -1;
	Image processedI = null;
	boolean redrawAsked = false;
	int countWaitingRequests = 0;
	Rectangle piBounds = null;

	boolean isMouseDown = false;
	boolean isDrag = false;
	Point clickedMousePos = null;
	double clickedMixerX = 0;
	double clickedMixerY = 0;
	Point lastMousePos = null;

	Vector<MonitorListener> monitorListeners = new Vector<MonitorListener>();
	Vector<MonitorListener> monitorListenersToRemove = new Vector<MonitorListener>();
	
	public MonitorGUI(TDenlive aTDe,Composite aParent) {
		tde = aTDe;
		mixer = new AnaMixer(tde,null,0);
		createContents(aParent);
	}

	void createContents(Composite aParent) {
		canvas = new Canvas (aParent,
//				SWT.BORDER |
				SWT.NO_BACKGROUND 
				| SWT.FILL
				| SWT.V_SCROLL 
				| SWT.H_SCROLL
				);
		canvas.setLayoutData(new GridData(GridData.FILL_BOTH));

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
					int aRGB = processedI.getImageData().getPixel(e.x-piBounds.x, e.y-piBounds.y);
					aML.click((e.x-piBounds.x)/(double)piBounds.width,(e.y-piBounds.y)/(double)piBounds.height,aRGB);
				}
				for(MonitorListener aML : monitorListenersToRemove) {
					monitorListeners.remove(aML);
				}
				monitorListenersToRemove.clear();
			}
			@Override
			public void mouseDoubleClick(MouseEvent arg0) {
			}
		});
		
		canvas.addMouseMoveListener(new MouseMoveListener() {
			@Override
			public void mouseMove(MouseEvent e) {
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
	
	void processI() {
		if(processing != 0) {
			//Already processing something
			return;
		}
		synchronized (this) {
			Media aMedia = media;//Avoid a concurrent change
			processing = 1;//Processing
			aMedia.setTimePosMS(media.getRequestedTimePosMS());
			aMedia.reBuild(processingMode, aMedia.getTimePosMS());
			if(edgeDetector != null) {
				edgeDetector.process2Awt(aMedia, processingMode, aMedia.getTimePosMS());
			}
			Image aI = mixer.process2Swt(aMedia,processingMode,clientR,aMedia.getTimePosMS());
			if(processedI != null) {
				processedI.dispose();
			}
			processedI = aI;
			processing = 2;//Processed
			if(tde.gui.display.isDisposed()) {
				//Closed??
				return;
			}
			tde.gui.display.syncExec(new Runnable() {
				@Override
				public void run() {
					if(!canvas.isDisposed()) {
						redraw();
					}
				}
			});
		}
	}
	
	public void redraw() {
		if(countWaitingRequests > 5) {
			return;
		}
		countWaitingRequests++;
		if(processing == 1) {
			//Wait for the image
			countWaitingRequests--;
			redrawAsked = true;
			return;
		}
		canvas.redraw();
		countWaitingRequests--;
	}

	void draw(GC aGC) {
		Rectangle aClientR = canvas.getClientArea();
		Image aI = new Image(tde.gui.display, aClientR.width, aClientR.height);
		GC aGCDL = new GC(aI);
		drawDbl(aGCDL);
		aGCDL.dispose();
		aGC.drawImage(aI, aClientR.x, aClientR.y);
		aI.dispose();
	}

	void drawDbl(GC aGC) {
		long aStartMS = System.currentTimeMillis();
		tde.gui.monitorControlGUI.status.setImage(tde.gui.imgsSwt.getIcon("gearsBlack.gif"));
		tde.gui.monitorControlGUI.status.layout(true);
		clientR = canvas.getClientArea();
		aGC.setBackground(tde.gui.colorsSwt.BLACK);
		aGC.fillRectangle(clientR);
		if(media == null) {
			tde.gui.monitorControlGUI.status.setImage(tde.gui.imgsSwt.getIcon("tuneButton.gif"));
			tde.gui.monitorControlGUI.status.layout(true);
			drawGrid(aGC,clientR);
			return;
		}
		if(processedI == null || processing == 0) {
			processingStart = aStartMS;
			Thread aTh = new Thread(new Runnable() {
				@Override
				public void run() {
					processI();
				}
			});
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
		aGC.drawImage(processedI,aX, aY);
		aGC.setForeground(tde.gui.colorsSwt.WHITE);
		aGC.drawRectangle(aX, aY, aLeftR.width-1, aLeftR.height-1);
		
		if(processing == 2) {
			long aDuration = System.currentTimeMillis() - processingStart;
			processingStart = -1;
			if(_TRACE_DRAW_TIME) {
				System.out.println("Draw: "+(countDraw++)+" "+aDuration+" ms");
			}
			NumberFormat aNF = NumberFormat.getInstance();
			aNF.setMaximumFractionDigits(3);
			aNF.setMinimumFractionDigits(3);
			aNF.setMaximumIntegerDigits(3);
			aNF.setMinimumIntegerDigits(3);
			tde.gui.monitorControlGUI.status.setText((aNF.format(1000.0/aDuration)+" fps"));
			tde.gui.monitorControlGUI.status.setImage(tde.gui.imgsSwt.getIcon("tuneButton.gif"));
			tde.gui.monitorControlGUI.status.layout(true);
			
			processing = 0;//May process again

			if(redrawAsked) {
				redrawAsked = false;
				redraw();
			}
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
