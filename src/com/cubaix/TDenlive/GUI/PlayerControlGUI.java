package com.cubaix.TDenlive.GUI;

import org.eclipse.swt.SWT;
import org.eclipse.swt.events.MouseEvent;
import org.eclipse.swt.events.MouseListener;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Text;

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
	//Paused: no playing thread, the cursor stays where it is, the buttons are those of the play
	volatile boolean paused = false;
	//Generation of the last playing thread that has ended (its end has been handled)
	volatile int endedGen = 0;
	//Media played or paused, and cursor position restored at the stop
	Media playedMedia = null;
	boolean playedIsLib = false;
	long returnPosMS = 0;
	Label playStop = null;
	Label pause = null;
	Text clock = null;
	//True while the program sets the clock text (not a user input)
	boolean settingClock = false;
	//Step in seconds of the back / forward arrows
	Text step = null;

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
		GridLayout aGL = new GridLayout(16, false);
		tde.gui.applyNoMargin(aGL);
		toolBar.setLayout(aGL);

		//Play button, which becomes the stop button while playing or paused
		playStop = new Label(toolBar,SWT.NONE);
		playStop.setAlignment(SWT.CENTER);
		GridData aPlayGD = new GridData();
		aPlayGD.widthHint = 25;
		aPlayGD.heightHint = tde.gui.h(25);
		playStop.setLayoutData(aPlayGD);
		tde.gui.applyColorFont(playStop);
		playStop.addListener(SWT.MouseDown, e -> {
			if(playing || paused) {
				stop();
				return;
			}
			//A library media shown in the monitor is played alone, otherwise the montage
			Media aShown = tde.gui.monitorGUI.getMedia();
			boolean aIsLibMedia = aShown != null && tde.mediaList.mediaList.contains(aShown);
			Media aPlayed = aIsLibMedia ? aShown : tde.timeLineStack;
			//Cursor position at the click (the requested one: the shown one may not be rendered yet), restored at the stop
			long aTimePosStart = aPlayed.getRequestedTimePosMS();
			startPlay(aPlayed, aIsLibMedia, aTimePosStart, aTimePosStart);
		});

		//Two-state pause button, shown while playing or paused: pressed = paused
		pause = new Label(toolBar,SWT.NONE);
		pause.setAlignment(SWT.CENTER);
		pause.setImage(tde.gui.imgsSwt.getIcon("pause.png"));
		GridData aPauseGD = new GridData();
		aPauseGD.widthHint = 25;
		aPauseGD.heightHint = tde.gui.h(25);
		pause.setLayoutData(aPauseGD);
		tde.gui.applyColorFont(pause);
		pause.addListener(SWT.MouseDown, e -> {
			if(paused) {
				//Goes on from the current cursor position (it may have been moved during the pause)
				long aFrom = playedMedia.getRequestedTimePosMS();
				startPlay(playedMedia, playedIsLib, aFrom, returnPosMS);
			}
			else if(playing) {
				//The playing thread ends without going back to the start position
				paused = true;
				playing = false;
				updateButtons();
			}
		});
		updateButtons();

		//Editable clock: a valid time jumps there, an invalid one turns the field red
		clock = framedField(SWT.CENTER);
		clock.setText(StringUtils.time2Clock(0));
		tde.gui.tip(clock, "Clock");
		GridData aClockGD = (GridData)clock.getParent().getLayoutData();
		aClockGD.widthHint = Math.max(80, clock.computeSize(SWT.DEFAULT, SWT.DEFAULT).x + 8);
		//Digits and separators only, at most the length of a clock
		clock.addListener(SWT.Verify, e -> {
			String aNew = clock.getText().substring(0, e.start) + e.text + clock.getText().substring(e.end);
			e.doit = aNew.length() <= 11 && aNew.matches("[0-9:]*");
		});
		clock.addListener(SWT.Modify, e -> {
			if(settingClock) {
				return;
			}
			long aTime = StringUtils.clock2Time(clock.getText());
			clock.setBackground(aTime < 0 ? tde.gui.colorsSwt.RED : tde.gui.colorsSwt.WHITE);
			if(aTime >= 0) {
				jumpTo(aTime);
			}
		});
		//Enter: the typed time is taken, the field is left (it then follows the cursor again)
		clock.addListener(SWT.DefaultSelection, e -> releaseClockFocus());
		//Leaving the field with an invalid time: the current time is shown again
		clock.addListener(SWT.FocusOut, e -> {
			if(StringUtils.clock2Time(clock.getText()) < 0) {
				Media aShown = tde.gui.monitorGUI.getMedia();
				showClock(aShown != null && aShown != tde.timeLineStack && tde.mediaList.mediaList.contains(aShown)
						? aShown.getRequestedTimePosMS() : tde.timeLineStack.getRequestedTimePosMS());
			}
		});

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
			tde.timeLineStack.setRequestedTimePosMS(Math.max(0, tde.timeLineStack.getRequestedTimePosMS()-(int)(1000.0/tde.config.outFps)));
			tde.gui.monitorGUI.redraw();
		});
		Label aRight = new Label(toolBar,SWT.NONE);
		aRight.setLayoutData(new GridData(GridData.FILL_VERTICAL));
		aRight.setImage(tde.gui.imgsSwt.getIcon("arrowRightSmallCubaixBlue.gif"));
		aRight.setBackground(tde.gui.colorsSwt.WHITE);
		tde.gui.tip(aRight, "NextFrame");
		aRight.addListener(SWT.MouseDown,e -> {
			tde.timeLineStack.setRequestedTimePosMS(tde.timeLineStack.getRequestedTimePosMS()+(int)(1000.0/tde.config.outFps));
			tde.gui.monitorGUI.redraw();
		});
		Label aEnd = new Label(toolBar,SWT.NONE);
		aEnd.setLayoutData(new GridData(GridData.FILL_VERTICAL));
		aEnd.setImage(tde.gui.imgsSwt.getIcon("arrowRightWall.gif"));
		aEnd.setBackground(tde.gui.colorsSwt.WHITE);
		tde.gui.tip(aEnd, "ClipEnd");
		aEnd.addListener(SWT.MouseDown,e -> goToTime(getStartEnd()[1]));

		//Space of a button, then the step in seconds and its back / forward arrows
		Label aStepSpace = new Label(toolBar,SWT.NONE);
		tde.gui.applyColorFont(aStepSpace);
		GridData aSSGD = new GridData();
		aSSGD.widthHint = 25;
		aStepSpace.setLayoutData(aSSGD);
		step = framedField(SWT.RIGHT);
		step.setText("1");
		tde.gui.tip(step, "StepSeconds");
		((GridData)step.getParent().getLayoutData()).widthHint = 40;
		//Digits and one decimal separator only
		step.addListener(SWT.Verify, e -> {
			String aNew = step.getText().substring(0, e.start) + e.text + step.getText().substring(e.end);
			e.doit = aNew.matches("[0-9]*([.,][0-9]*)?");
		});
		Label aStepUnit = new Label(toolBar,SWT.NONE);
		aStepUnit.setText("s");
		tde.gui.applyColorFont(aStepUnit);
		aStepUnit.setLayoutData(new GridData(SWT.BEGINNING, SWT.CENTER, false, false));
		Label aStepBack = new Label(toolBar,SWT.NONE);
		aStepBack.setLayoutData(new GridData(GridData.FILL_VERTICAL));
		aStepBack.setImage(tde.gui.imgsSwt.getIcon("doubleArrowLeft.png"));
		aStepBack.setBackground(tde.gui.colorsSwt.WHITE);
		tde.gui.tip(aStepBack, "StepBack");
		aStepBack.addListener(SWT.MouseDown,e -> stepBy(-1));
		Label aStepForward = new Label(toolBar,SWT.NONE);
		aStepForward.setLayoutData(new GridData(GridData.FILL_VERTICAL));
		aStepForward.setImage(tde.gui.imgsSwt.getIcon("doubleArrowRight.png"));
		aStepForward.setBackground(tde.gui.colorsSwt.WHITE);
		tde.gui.tip(aStepForward, "StepForward");
		aStepForward.addListener(SWT.MouseDown,e -> stepBy(1));

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
	 * Move the montage time cursor by aDir times the step in seconds (in the montage bounds)
	 */
	void stepBy(int aDir) {
		double aSec;
		try {
			aSec = Double.parseDouble(step.getText().replace(',', '.'));
		}
		catch(Throwable t) {
			return;
		}
		long aTime = tde.timeLineStack.getRequestedTimePosMS() + aDir*Math.round(aSec*1000);
		goToTime(Math.max(0, Math.min(aTime, Math.max(0, tde.timeLineStack.getMaxTimePosMS()-1))));
	}

	/**
	 * Text field with a gray 1 px frame (the native border is hardly visible on macOS)
	 */
	Text framedField(int aStyle) {
		Text aText = tde.gui.framedText(toolBar, aStyle);
		aText.getParent().setBackground(tde.gui.colorsSwt.GRAY);
		aText.getParent().setLayoutData(new GridData(SWT.BEGINNING, SWT.CENTER, false, false));
		return aText;
	}

	/**
	 * Focus out of the clock (to the time lines), so that its text follows the time cursor again
	 */
	void releaseClockFocus() {
		if(!clock.isDisposed() && clock.isFocusControl() && tde.gui.timeLinesGUI != null
				&& !tde.gui.timeLinesGUI.canvas.isDisposed()) {
			tde.gui.timeLinesGUI.canvas.setFocus();
		}
	}

	/**
	 * Time typed in the clock: the library media shown in the monitor (whose time the clock shows),
	 * otherwise the montage, goes there (in its bounds)
	 */
	void jumpTo(long aTime) {
		Media aShown = tde.gui.monitorGUI.getMedia();
		Media aTarget = aShown != null && aShown != tde.timeLineStack && tde.mediaList.mediaList.contains(aShown)
				? aShown : tde.timeLineStack;
		aTime = Math.max(0, Math.min(aTime, Math.max(0, aTarget.getMaxTimePosMS()-1)));
		if(aTarget == tde.timeLineStack) {
			goToTime(aTime);
		}
		else {
			aTarget.setRequestedTimePosMS(aTime);
			tde.gui.monitorGUI.redraw();
		}
	}

	/**
	 * Time shown by the clock (SWT thread). An invalid time being typed is left as it is.
	 */
	public void showClock(long aTimeMS) {
		if(clock.isDisposed()) {
			return;
		}
		if(clock.isFocusControl() && StringUtils.clock2Time(clock.getText()) < 0) {
			return;
		}
		String aText = StringUtils.time2Clock(aTimeMS);
		if(aText.equals(clock.getText())) {
			return;
		}
		settingClock = true;
		try {
			int aCaret = clock.getCaretPosition();
			clock.setText(aText);
			clock.setBackground(tde.gui.colorsSwt.WHITE);
			if(clock.isFocusControl()) {
				clock.setSelection(Math.min(aCaret, aText.length()));
			}
		}
		finally {
			settingClock = false;
		}
	}

	/**
	 * Play aPlayed from aFromPos; the stop goes back to aReturnPos (SWT thread)
	 */
	void startPlay(final Media aPlayed, final boolean aIsLibMedia, final long aFromPos, final long aReturnPos) {
		//A clock being edited is not refreshed by macOS: the playing time must show
		releaseClockFocus();
		playedMedia = aPlayed;
		playedIsLib = aIsLibMedia;
		returnPosMS = aReturnPos;
		paused = false;
		playing = true;
		updateButtons();
		final int aGen = ++playGen;
		tde.gui.monitorGUI.setMedia(aPlayed);
		Thread aTh = new Thread(new Runnable() {
			@Override
			public void run() {
				long aTimeStart = System.currentTimeMillis();
				aPlayed.setRequestedTimePosMS(aFromPos);
				//The sound card gives the time while playing, otherwise the system clock
				AudioPlayer aAudio = aIsLibMedia ? new AudioPlayer(tde, aPlayed) : new AudioPlayer(tde);
				boolean aAudioClock = aAudio.start(aFromPos);
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
					if(!playing || aGen != playGen) {
						//Paused or stopped meanwhile: the cursor is not moved any more
						break;
					}
					long aTimePosNew;
					if(aAudioClock) {
						//Images follow the sound (frames are skipped if the processing is too slow)
						aTimePosNew = aAudio.getPositionMS();
					}
					else {
						aTimePosNew = aFromPos+System.currentTimeMillis()-aTimeStart;
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
				//unless paused or a new play has started in the meantime
				tde.gui.display.syncExec(new Runnable() {
					@Override
					public void run() {
						if(aGen != playGen || playStop.isDisposed()) {
							return;
						}
						endedGen = aGen;
						if(playing || paused) {
							return;
						}
						returnToStart();
					}
				});
			}
		});
		aTh.start();
	}

	/**
	 * Stop playing (or the pause) and go back to the position of the play click (SWT thread)
	 */
	void stop() {
		boolean aWasPaused = paused;
		setPlaying(false);
		if(aWasPaused && endedGen == playGen) {
			//The thread has already ended: no one else goes back to the start
			returnToStart();
		}
	}

	/**
	 * Cursor back to the position of the play click (SWT thread)
	 */
	void returnToStart() {
		if(playedMedia == null) {
			return;
		}
		playedMedia.setRequestedTimePosMS(returnPosMS);
		if(tde.gui.monitorGUI.getMedia() == playedMedia) {
			tde.gui.monitorGUI.redraw();
			if(playedIsLib) {
				showClock(returnPosMS);
			}
		}
		if(!playedIsLib) {
			tde.gui.timeLinesGUI.redraw();
		}
	}

	/**
	 * Playing state (false: stopped, the pause too), with the buttons (SWT thread)
	 */
	void setPlaying(boolean aPlaying) {
		playing = aPlaying;
		paused = false;
		updateButtons();
	}

	/**
	 * Icon and help message of the play / stop button, pause button shown (pressed when paused)
	 * while playing or paused (SWT thread)
	 */
	void updateButtons() {
		boolean aActive = playing || paused;
		playStop.setImage(tde.gui.imgsSwt.getIcon(aActive ? "stop.gif" : "play.gif"));
		tde.gui.tip(playStop, aActive ? "Stop" : "Play");
		tde.gui.tip(pause, paused ? "Resume" : "Pause");
		pause.setBackground(paused ? tde.gui.colorsSwt.LIGHTGRAY : tde.gui.colorsSwt.WHITE);
		if(pause.getVisible() != aActive) {
			pause.setVisible(aActive);
			((GridData)pause.getLayoutData()).exclude = !aActive;
			toolBar.layout(true);
		}
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
				showClock(tde.timeLineStack.getTimePosMS());
			}
		});
	}
}
