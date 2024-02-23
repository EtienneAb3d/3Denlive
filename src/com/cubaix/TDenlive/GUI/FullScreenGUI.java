package com.cubaix.TDenlive.GUI;

import org.eclipse.swt.SWT;
import org.eclipse.swt.events.KeyEvent;
import org.eclipse.swt.events.KeyListener;
import org.eclipse.swt.graphics.Rectangle;
import org.eclipse.swt.layout.FillLayout;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Shell;

import com.cubaix.TDenlive.TDConfig;
import com.cubaix.TDenlive.TDenlive;

public class FullScreenGUI {
	TDenlive tde = null;
	Shell shell = null;
	MonitorGUI monitorGUI = null;
	boolean playing = false;
	boolean stopped = false;
	
	public FullScreenGUI(TDenlive aTDe) {
 		tde = aTDe;
		shell = new Shell(tde.gui.display,SWT.NONE);
		shell.setText("3Denlive");
		shell.setSize (800, 600);
		shell.setMaximized(true);
		shell.setFullScreen(true);
		shell.setBackground(tde.gui.colorsSwt.BLACK);
		shell.setImage(tde.gui.imgsSwt.getIcon("3Denlive32.png"));
		
		GridLayout aLayout = new GridLayout(1,false);
		aLayout.marginLeft = aLayout.marginTop = aLayout.marginRight = aLayout.marginBottom = 0;
		aLayout.horizontalSpacing = aLayout.verticalSpacing = 0;
		aLayout.marginHeight = aLayout.marginWidth = 0;
		shell.setLayout(aLayout);

		monitorGUI = new MonitorGUI(tde, shell);
		monitorGUI.setMedia(tde.timeLineStack);
		monitorGUI.canvas.getHorizontalBar().setVisible(false);
		monitorGUI.canvas.getVerticalBar().setVisible(false);
		monitorGUI.processingMode = TDConfig.PROCESSING_MODE_FULLSCREEN;
		monitorGUI.mixer = tde.gui.monitorGUI.mixer;
		monitorGUI.grid = tde.gui.monitorGUI.grid;
		
		monitorGUI.canvas.addKeyListener(new KeyListener() {
			@Override
			public void keyReleased(KeyEvent e) {
			}
			@Override
			public void keyPressed(KeyEvent e) {
				if(e.keyCode == SWT.ESC) {
					if(!playing) {
						tde.gui.monitorGUI.setMedia(tde.timeLineStack);
						tde.gui.monitorGUI.redraw();
						shell.dispose();
						return;
					}
					playing = false;
					Thread aTh = new Thread(new Runnable() {
						@Override
						public void run() {
							while(!stopped) {
								try {
									Thread.sleep(100);
								}
								catch(Throwable t) {}
							}
							tde.gui.display.syncExec(new Runnable() {
								@Override
								public void run() {
									tde.gui.monitorGUI.setMedia(tde.timeLineStack);
									tde.gui.monitorGUI.redraw();
									shell.dispose();
								}
							});
						}
					});
					aTh.start();
				}
				if(e.keyCode == 32) {
					if(!playing) {
						play();
					}
					else {
						playing = false;
					}
				}
			}
		});

		shell.open();
	}
	
	void play() {
		playing = true;
		stopped = false;
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
							if(!monitorGUI.canvas.isDisposed()) {
								monitorGUI.redraw();
							}
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
				//Be sure all is done in the proper order according to main SWT thread
				tde.gui.display.syncExec(new Runnable() {
					@Override
					public void run() {
						tde.gui.timeLinesGUI.redraw();
					}
				});
				tde.gui.display.syncExec(new Runnable() {
					@Override
					public void run() {
						stopped = true;
					}
				});
			}
		});
		aTh.start();
	}
}
