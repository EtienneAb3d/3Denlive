package com.cubaix.TDenlive.GUI;

import org.eclipse.swt.SWT;
import org.eclipse.swt.events.MouseEvent;
import org.eclipse.swt.events.MouseListener;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Label;

import com.cubaix.TDenlive.TDenlive;
import com.cubaix.TDenlive.medias.TimeListener;
import com.cubaix.TDenlive.utils.StringUtils;

public class PlayerControlGUI extends CompositePanel {
	Composite toolBar = null;
	boolean playing = false;
	Label clock = null;

	public PlayerControlGUI(TDenlive aTDe, Composite aParent) {
		super(aTDe, aParent);
		createContents();
	}
	void createContents() {
		GridData aCB = new GridData(GridData.FILL_HORIZONTAL);
		container.setLayoutData(aCB);
		
		toolBar = new Composite(container, SWT.NULL);
		GridData aTB = new GridData(GridData.FILL_HORIZONTAL);
		aTB.heightHint = 25;
		toolBar.setLayoutData(aTB);
		tde.gui.applyColorFont(toolBar);
		GridLayout aGL = new GridLayout(9, false);
		tde.gui.applyNoMargin(aGL);
		toolBar.setLayout(aGL);

		Label play = new Label(toolBar,SWT.NONE);
		play.setImage(tde.gui.imgsSwt.getIcon("play.gif"));
		play.setAlignment(SWT.CENTER);
		GridData aPlayGD = new GridData();
		aPlayGD.widthHint = 25;
		aPlayGD.heightHint = 25;
		play.setLayoutData(aPlayGD);
		tde.gui.applyColorFont(play);
		play.addMouseListener(new MouseListener() {
			@Override
			public void mouseUp(MouseEvent arg0) {
			}
			@Override
			public void mouseDown(MouseEvent arg0) {
				if(playing) {
					//Not twice
					return;
				}
				playing = true;
				tde.gui.monitorGUI.setMedia(tde.timeLineStack);
				Thread aTh = new Thread(new Runnable() {
					@Override
					public void run() {
						long aTimeStart = System.currentTimeMillis();
						long aTimePosStart = tde.timeLineStack.getTimePosMS();
						tde.timeLineStack.setRequestedTimePosMS(aTimePosStart);
						while(playing) {
							long aTimeStartRedraw = System.currentTimeMillis();
							tde.gui.display.syncExec(new Runnable() {
								@Override
								public void run() {
									tde.gui.monitorGUI.redraw();
									tde.gui.timeLinesGUI.redraw();
								}
							});
							if(System.currentTimeMillis()-aTimeStartRedraw < 5) {
								//No more than 200 fps at screen, CPU respect (certainly waiting for an image calculation)
								try{
									Thread.sleep(5);
								}
								catch(Throwable t) {
									t.printStackTrace(System.err);
								}
							}
							long aTimePosNew = aTimePosStart+System.currentTimeMillis()-aTimeStart;
							if(aTimePosNew-tde.timeLineStack.getTimePosMS() > 200) {
								//Dilate time
								aTimePosNew = tde.timeLineStack.getTimePosMS()+200;
							}
							tde.timeLineStack.setRequestedTimePosMS(aTimePosNew);
						}
					}
				});
				aTh.start();
			}
			@Override
			public void mouseDoubleClick(MouseEvent arg0) {
			}
		});
		
		Label stop = new Label(toolBar,SWT.NONE);
		stop.setImage(tde.gui.imgsSwt.getIcon("stop.gif"));
		stop.setAlignment(SWT.CENTER);
		GridData aStopGD = new GridData();
		aStopGD.widthHint = 25;
		aStopGD.heightHint = 25;
		stop.setLayoutData(aStopGD);
		tde.gui.applyColorFont(stop);
		stop.addMouseListener(new MouseListener() {
			@Override
			public void mouseUp(MouseEvent arg0) {
			}
			@Override
			public void mouseDown(MouseEvent arg0) {
				playing = false;
			}
			@Override
			public void mouseDoubleClick(MouseEvent arg0) {
			}
		});
		
		clock = new Label(toolBar,SWT.NONE);
		clock.setText(StringUtils.time2Clock(0));
		clock.setAlignment(SWT.CENTER);
		GridData aClockGD = new GridData();
		aClockGD.widthHint = 80;
		aClockGD.heightHint = 12;
		clock.setLayoutData(aClockGD);
		tde.gui.applyColorFont(clock);
		
		Label aLeft = new Label(toolBar,SWT.NONE);
		aLeft.setLayoutData(new GridData(GridData.FILL_VERTICAL));
		aLeft.setImage(tde.gui.imgsSwt.getIcon("arrowLeftSmallCubaixBlue.gif"));
		aLeft.setBackground(tde.gui.colorsSwt.WHITE);
		aLeft.addListener(SWT.MouseDown,e -> {
			tde.timeLineStack.setRequestedTimePosMS(tde.timeLineStack.getTimePosMS()-(int)(1000.0/tde.config.outFps));
			tde.gui.monitorGUI.redraw();
		});
		Label aRight = new Label(toolBar,SWT.NONE);
		aRight.setLayoutData(new GridData(GridData.FILL_VERTICAL));
		aRight.setImage(tde.gui.imgsSwt.getIcon("arrowRightSmallCubaixBlue.gif"));
		aRight.setBackground(tde.gui.colorsSwt.WHITE);
		aRight.addListener(SWT.MouseDown,e -> {
			tde.timeLineStack.setRequestedTimePosMS(tde.timeLineStack.getTimePosMS()+(int)(1000.0/tde.config.outFps));
			tde.gui.monitorGUI.redraw();
		});

		Label aBlank = new Label(toolBar,SWT.NONE);
		tde.gui.applyColorFont(aBlank);
		aBlank.setLayoutData(new GridData(GridData.FILL_HORIZONTAL));
		
		Label aZoomIn = new Label(toolBar,SWT.NONE);
		aZoomIn.setImage(tde.gui.imgsSwt.getIcon("zoomplus.gif"));
		aZoomIn.setAlignment(SWT.CENTER);
		GridData aZIGD = new GridData();
		aZIGD.widthHint = 25;
		aZIGD.heightHint = 25;
		aZoomIn.setLayoutData(aZIGD);
		tde.gui.applyColorFont(aZoomIn);
		aZoomIn.addMouseListener(new MouseListener() {
			@Override
			public void mouseUp(MouseEvent arg0) {
			}
			@Override
			public void mouseDown(MouseEvent arg0) {
				tde.gui.monitorGUI.zoomIn();
			}
			@Override
			public void mouseDoubleClick(MouseEvent arg0) {
			}
		});

		Label aZoomOut = new Label(toolBar,SWT.NONE);
		aZoomOut.setImage(tde.gui.imgsSwt.getIcon("zoomminus.gif"));
		aZoomOut.setAlignment(SWT.CENTER);
		GridData aZOGD = new GridData();
		aZOGD.widthHint = 25;
		aZOGD.heightHint = 25;
		aZoomOut.setLayoutData(aZOGD);
		tde.gui.applyColorFont(aZoomOut);
		aZoomOut.addMouseListener(new MouseListener() {
			@Override
			public void mouseUp(MouseEvent arg0) {
			}
			@Override
			public void mouseDown(MouseEvent arg0) {
				tde.gui.monitorGUI.zoomOut();
			}
			@Override
			public void mouseDoubleClick(MouseEvent arg0) {
			}
		});

		Label aZoomFit = new Label(toolBar,SWT.NONE);
		aZoomFit.setImage(tde.gui.imgsSwt.getIcon("collapseall.png"));
		aZoomFit.setAlignment(SWT.CENTER);
		GridData aZFGD = new GridData();
		aZFGD.widthHint = 25;
		aZFGD.heightHint = 25;
		aZoomFit.setLayoutData(aZFGD);
		tde.gui.applyColorFont(aZoomFit);
		aZoomFit.addMouseListener(new MouseListener() {
			@Override
			public void mouseUp(MouseEvent arg0) {
			}
			@Override
			public void mouseDown(MouseEvent arg0) {
				tde.gui.monitorGUI.zoomFit();
			}
			@Override
			public void mouseDoubleClick(MouseEvent arg0) {
			}
		});
		
		tde.timeLineStack.addTimeListener(this);
	}

	@Override
	public void timeChanged(long aTimeNew) {
		if(clock.isDisposed()) {
			tde.timeLineStack.removeTimeListener(this);
			return;
		}
		tde.gui.display.asyncExec(new Runnable() {
			@Override
			public void run() {
				clock.setText(StringUtils.time2Clock(tde.timeLineStack.getTimePosMS()));
			}
		});
	}
}
