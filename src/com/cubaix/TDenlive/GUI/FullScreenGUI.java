package com.cubaix.TDenlive.GUI;

import org.eclipse.swt.SWT;
import org.eclipse.swt.widgets.Listener;
import org.eclipse.swt.graphics.Rectangle;
import org.eclipse.swt.layout.FillLayout;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Shell;

import com.cubaix.TDenlive.TDConfig;
import com.cubaix.TDenlive.TDenlive;
import com.cubaix.TDenlive.audio.AudioPlayer;

public class FullScreenGUI {
	TDenlive tde = null;
	Shell shell = null;
	MonitorGUI monitorGUI = null;
	volatile boolean playing = false;
	//True when no playing thread is running
	volatile boolean stopped = true;
	boolean closing = false;
	
	public FullScreenGUI(TDenlive aTDe) {
 		tde = aTDe;
		//On macOS a shell without title can't become the key window: it would never get the keyboard
		//(the title bar is hidden in full screen anyway)
		shell = new Shell(tde.gui.display,"cocoa".equals(SWT.getPlatform()) ? SWT.TITLE : SWT.NONE);
		shell.setText("3Denlive");
		//Size of the screen of the main window: on macOS, setFullScreen does not resize the content
		shell.setBounds(tde.gui.shell.getMonitor().getBounds());
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
		
		//Keys are caught at the display level: on macOS the canvas does not get the keyboard focus,
		//so a key listener on it never receives ESC or space
		Listener aKeyFilter = e -> {
			if(shell.isDisposed()) {
				return;
			}
			if(e.keyCode == SWT.ESC) {
				e.doit = false;
				close();
			}
			else if(e.keyCode == ' ') {
				e.doit = false;
				if(!playing) {
					play();
				}
				else {
					playing = false;
				}
			}
		};
		tde.gui.display.addFilter(SWT.KeyDown, aKeyFilter);
		shell.addListener(SWT.Dispose, e -> tde.gui.display.removeFilter(SWT.KeyDown, aKeyFilter));
		//Mouse fallback: double-click closes
		monitorGUI.canvas.addListener(SWT.MouseDoubleClick, e -> close());

		shell.open();
		shell.forceActive();
		monitorGUI.canvas.setFocus();
	}

	void close() {
		if(closing) {
			return;
		}
		closing = true;
		playing = false;
		Thread aTh = new Thread(new Runnable() {
			@Override
			public void run() {
				//Wait for the end of the playing thread, if any
				while(!stopped) {
					try {
						Thread.sleep(100);
					}
					catch(Throwable t) {}
				}
				tde.gui.display.syncExec(new Runnable() {
					@Override
					public void run() {
						if(shell.isDisposed()) {
							return;
						}
						//Leave the macOS full screen space before closing
						shell.setFullScreen(false);
						shell.dispose();
						tde.gui.monitorGUI.setMedia(tde.timeLineStack);
						tde.gui.monitorGUI.redraw();
					}
				});
			}
		});
		aTh.setDaemon(true);
		aTh.start();
	}

	void play() {
		playing = true;
		stopped = false;
		//Cursor position at the start, restored at the stop
		final long aTimePosStart = tde.timeLineStack.getTimePosMS();
		Thread aTh = new Thread(new Runnable() {
			@Override
			public void run() {
				long aTimeStart = System.currentTimeMillis();
				tde.timeLineStack.setRequestedTimePosMS(aTimePosStart);
				//The sound card gives the time while playing, otherwise the system clock
				AudioPlayer aAudio = new AudioPlayer(tde);
				boolean aAudioClock = aAudio.start(aTimePosStart);
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
					long aTimePosNew;
					if(aAudioClock) {
						//Images follow the sound (frames are skipped if the processing is too slow)
						aTimePosNew = aAudio.getPositionMS();
					}
					else {
						aTimePosNew = aTimePosStart+System.currentTimeMillis()-aTimeStart;
						if(aTimePosNew-tde.timeLineStack.getTimePosMS() > 200) {
							//Dilate time
							aTimePosNew = tde.timeLineStack.getTimePosMS()+200;
						}
					}
					//Stop at the end of the last clip
					if(aTimePosNew >= tde.timeLineStack.getMaxTimePosMS()) {
						playing = false;
						break;
					}
					tde.timeLineStack.setRequestedTimePosMS(aTimePosNew);
				}
				aAudio.stop();
				//Back to the start position (the loop has ended, it no longer moves the cursor),
				//unless a new play has started in the meantime
				//Be sure all is done in the proper order according to main SWT thread
				tde.gui.display.syncExec(new Runnable() {
					@Override
					public void run() {
						if(!playing) {
							tde.timeLineStack.setRequestedTimePosMS(aTimePosStart);
							if(!monitorGUI.canvas.isDisposed()) {
								monitorGUI.redraw();
							}
							tde.gui.monitorGUI.redraw();
						}
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
