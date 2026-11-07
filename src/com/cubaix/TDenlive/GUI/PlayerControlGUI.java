package com.cubaix.TDenlive.GUI;

import org.eclipse.swt.SWT;
import org.eclipse.swt.events.MouseEvent;
import org.eclipse.swt.events.MouseListener;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Label;

import com.cubaix.TDenlive.TDenlive;
import com.cubaix.TDenlive.audio.AudioPlayer;
import com.cubaix.TDenlive.medias.Clip;
import com.cubaix.TDenlive.medias.Media;
import com.cubaix.TDenlive.medias.TimeListener;
import com.cubaix.TDenlive.utils.StringUtils;

public class PlayerControlGUI extends CompositePanel {
	Composite toolBar = null;
	volatile boolean playing = false;
	//Incremented at each start: a previous playing thread not yet ended stops by itself
	volatile int playGen = 0;
	Label playStop = null;
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
		aTB.heightHint = tde.gui.h(25);
		toolBar.setLayoutData(aTB);
		tde.gui.applyColorFont(toolBar);
		GridLayout aGL = new GridLayout(10, false);
		tde.gui.applyNoMargin(aGL);
		toolBar.setLayout(aGL);

		//Single button: play, and stop while playing
		playStop = new Label(toolBar,SWT.NONE);
		playStop.setAlignment(SWT.CENTER);
		GridData aPlayGD = new GridData();
		aPlayGD.widthHint = 25;
		aPlayGD.heightHint = tde.gui.h(25);
		playStop.setLayoutData(aPlayGD);
		tde.gui.applyColorFont(playStop);
		setPlaying(false);
		playStop.addMouseListener(new MouseListener() {
			@Override
			public void mouseUp(MouseEvent arg0) {
			}
			@Override
			public void mouseDown(MouseEvent arg0) {
				if(playing) {
					setPlaying(false);
					return;
				}
				setPlaying(true);
				final int aGen = ++playGen;
				//A library media shown in the monitor is played alone, otherwise the montage
				Media aShown = tde.gui.monitorGUI.getMedia();
				final boolean aIsLibMedia = aShown != null && tde.mediaList.mediaList.contains(aShown);
				final Media aPlayed = aIsLibMedia ? aShown : tde.timeLineStack;
				//Cursor position at the click, restored at the stop
				final long aTimePosStart = aIsLibMedia ? aPlayed.getRequestedTimePosMS() : aPlayed.getTimePosMS();
				tde.gui.monitorGUI.setMedia(aPlayed);
				Thread aTh = new Thread(new Runnable() {
					@Override
					public void run() {
						long aTimeStart = System.currentTimeMillis();
						aPlayed.setRequestedTimePosMS(aTimePosStart);
						//The sound card gives the time while playing, otherwise the system clock
						AudioPlayer aAudio = aIsLibMedia ? new AudioPlayer(tde, aPlayed) : new AudioPlayer(tde);
						boolean aAudioClock = aAudio.start(aTimePosStart);
						final boolean[] aShownChanged = new boolean[] {false};
						while(playing && aGen == playGen) {
							long aTimeStartRedraw = System.currentTimeMillis();
							tde.gui.display.syncExec(new Runnable() {
								@Override
								public void run() {
									if(tde.gui.monitorGUI.getMedia() != aPlayed) {
										//Another media or the montage clicked: stop playing this one
										aShownChanged[0] = true;
										return;
									}
									tde.gui.monitorGUI.redraw();
									if(aIsLibMedia) {
										showClock(aPlayed.getRequestedTimePosMS());
									}
									else {
										tde.gui.timeLinesGUI.redraw();
									}
								}
							});
							if(aShownChanged[0]) {
								tde.gui.display.syncExec(new Runnable() {
									@Override
									public void run() {
										if(aGen == playGen && !playStop.isDisposed()) {
											setPlaying(false);
										}
									}
								});
								break;
							}
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
								long aTimePosCur = aIsLibMedia ? aPlayed.getRequestedTimePosMS() : aPlayed.getTimePosMS();
								if(aTimePosNew-aTimePosCur > 200) {
									//Dilate time
									aTimePosNew = aTimePosCur+200;
								}
							}
							//Stop at the end of the media, or of the last clip of the montage
							if(aTimePosNew >= aPlayed.getMaxTimePosMS()) {
								tde.gui.display.syncExec(new Runnable() {
									@Override
									public void run() {
										if(aGen == playGen && !playStop.isDisposed()) {
											setPlaying(false);
										}
									}
								});
								break;
							}
							aPlayed.setRequestedTimePosMS(aTimePosNew);
						}
						aAudio.stop();
						//Back to the start position, done here (the loop has ended, it no longer moves the cursor),
						//unless a new play has started in the meantime
						tde.gui.display.syncExec(new Runnable() {
							@Override
							public void run() {
								if(aGen != playGen || playing || playStop.isDisposed()) {
									return;
								}
								aPlayed.setRequestedTimePosMS(aTimePosStart);
								if(tde.gui.monitorGUI.getMedia() == aPlayed) {
									tde.gui.monitorGUI.redraw();
									if(aIsLibMedia) {
										showClock(aTimePosStart);
									}
								}
								if(!aIsLibMedia) {
									tde.gui.timeLinesGUI.redraw();
								}
							}
						});
					}
				});
				aTh.start();
			}
			@Override
			public void mouseDoubleClick(MouseEvent arg0) {
			}
		});
		
		clock = new Label(toolBar,SWT.NONE);
		clock.setText(StringUtils.time2Clock(0));
		clock.setAlignment(SWT.CENTER);
		tde.gui.applyColorFont(clock);
		tde.gui.tip(clock, "Clock");
		GridData aClockGD = new GridData();
		aClockGD.widthHint = Math.max(80, clock.computeSize(SWT.DEFAULT, SWT.DEFAULT).x + 8);
		aClockGD.heightHint = tde.gui.h(12);
		clock.setLayoutData(aClockGD);
		
		Label aStart = new Label(toolBar,SWT.NONE);
		aStart.setLayoutData(new GridData(GridData.FILL_VERTICAL));
		aStart.setImage(tde.gui.imgsSwt.getIcon("arrowLeftWall.gif"));
		aStart.setBackground(tde.gui.colorsSwt.WHITE);
		tde.gui.tip(aStart, "ClipStart");
		aStart.addListener(SWT.MouseDown,e -> goToTime(getStartEnd()[0]));
		Label aLeft = new Label(toolBar,SWT.NONE);
		aLeft.setLayoutData(new GridData(GridData.FILL_VERTICAL));
		aLeft.setImage(tde.gui.imgsSwt.getIcon("arrowLeftSmallCubaixBlue.gif"));
		aLeft.setBackground(tde.gui.colorsSwt.WHITE);
		tde.gui.tip(aLeft, "PrevFrame");
		aLeft.addListener(SWT.MouseDown,e -> {
			tde.timeLineStack.setRequestedTimePosMS(tde.timeLineStack.getTimePosMS()-(int)(1000.0/tde.config.outFps));
			tde.gui.monitorGUI.redraw();
		});
		Label aRight = new Label(toolBar,SWT.NONE);
		aRight.setLayoutData(new GridData(GridData.FILL_VERTICAL));
		aRight.setImage(tde.gui.imgsSwt.getIcon("arrowRightSmallCubaixBlue.gif"));
		aRight.setBackground(tde.gui.colorsSwt.WHITE);
		tde.gui.tip(aRight, "NextFrame");
		aRight.addListener(SWT.MouseDown,e -> {
			tde.timeLineStack.setRequestedTimePosMS(tde.timeLineStack.getTimePosMS()+(int)(1000.0/tde.config.outFps));
			tde.gui.monitorGUI.redraw();
		});
		Label aEnd = new Label(toolBar,SWT.NONE);
		aEnd.setLayoutData(new GridData(GridData.FILL_VERTICAL));
		aEnd.setImage(tde.gui.imgsSwt.getIcon("arrowRightWall.gif"));
		aEnd.setBackground(tde.gui.colorsSwt.WHITE);
		tde.gui.tip(aEnd, "ClipEnd");
		aEnd.addListener(SWT.MouseDown,e -> goToTime(getStartEnd()[1]));

		Label aBlank = new Label(toolBar,SWT.NONE);
		tde.gui.applyColorFont(aBlank);
		aBlank.setLayoutData(new GridData(GridData.FILL_HORIZONTAL));
		
		Label aZoomIn = new Label(toolBar,SWT.NONE);
		aZoomIn.setImage(tde.gui.imgsSwt.getIcon("zoomplus.gif"));
		aZoomIn.setAlignment(SWT.CENTER);
		GridData aZIGD = new GridData();
		aZIGD.widthHint = 25;
		aZIGD.heightHint = tde.gui.h(25);
		aZoomIn.setLayoutData(aZIGD);
		tde.gui.applyColorFont(aZoomIn);
		tde.gui.tip(aZoomIn, "MonitorZoomIn");
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
		aZOGD.heightHint = tde.gui.h(25);
		aZoomOut.setLayoutData(aZOGD);
		tde.gui.applyColorFont(aZoomOut);
		tde.gui.tip(aZoomOut, "MonitorZoomOut");
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
		aZFGD.heightHint = tde.gui.h(25);
		aZoomFit.setLayoutData(aZFGD);
		tde.gui.applyColorFont(aZoomFit);
		tde.gui.tip(aZoomFit, "MonitorZoomFit");
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

	/**
	 * Start and end times of the selected clip, or of the whole montage if no clip is selected.
	 * The end is the last displayed time (1 ms before the end).
	 */
	long[] getStartEnd() {
		if(tde.selected.clips.size() > 0) {
			Clip aClip = tde.selected.clips.lastElement();
			long aStart = aClip.getStartTimeMS();
			return new long[] {aStart, Math.max(aStart, aStart+aClip.getDurationMS()-1)};
		}
		return new long[] {0, Math.max(0, tde.timeLineStack.getMaxTimePosMS()-1)};
	}
	
	void goToTime(long aTime) {
		tde.timeLineStack.setRequestedTimePosMS(aTime);
		tde.gui.monitorGUI.redraw();
		tde.gui.timeLinesGUI.redraw();
	}

	/**
	 * Time shown by the clock (SWT thread)
	 */
	public void showClock(long aTimeMS) {
		if(!clock.isDisposed()) {
			clock.setText(StringUtils.time2Clock(aTimeMS));
		}
	}

	/**
	 * Playing state, with the icon and the help message of the play / stop button (SWT thread)
	 */
	void setPlaying(boolean aPlaying) {
		playing = aPlaying;
		playStop.setImage(tde.gui.imgsSwt.getIcon(aPlaying ? "stop.gif" : "play.gif"));
		tde.gui.tip(playStop, aPlaying ? "Stop" : "Play");
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
