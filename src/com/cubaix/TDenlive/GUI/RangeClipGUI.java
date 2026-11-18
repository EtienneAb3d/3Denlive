package com.cubaix.TDenlive.GUI;

import java.awt.image.BufferedImage;
import java.io.File;
import java.text.NumberFormat;

import org.eclipse.swt.SWT;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.MessageBox;
import org.eclipse.swt.widgets.ProgressBar;
import org.eclipse.swt.widgets.Shell;

import com.cubaix.TDenlive.TDConfig;
import com.cubaix.TDenlive.TDenlive;
import com.cubaix.TDenlive.audio.AudioMixer;
import com.cubaix.TDenlive.ffmpeg.FfmpegEncoder;
import com.cubaix.TDenlive.medias.Clip;
import com.cubaix.TDenlive.processors.Inpainting;
import com.cubaix.TDenlive.processors.Renderer;

/**
 * Video camera button of the monitor toolbar (right of the snapshot one): renders the selected time range of the
 * time lines (TimeLineStack.rangeStartMS / rangeEndMS), else the selected clips alone (TimeLineStack.soloClips,
 * with their twins: from the earliest start to the latest end, the other clips left out) in the final quality, both views side by side with the
 * sound, at the default rendering resolution and the fps of the montage, into Clips/ next to the project
 * (~/3Denlive/Clips before the first save), and adds it to the media list as a parallel stereo video.
 */
public class RangeClipGUI {
	static RangeClipGUI running = null;

	TDenlive tde;
	Shell shell;
	Label status;
	ProgressBar bar;
	Button cancel;
	volatile boolean playing = false;
	volatile boolean canceled = false;
	Renderer renderer = null;
	File out = null;
	File audioWav = null;
	long startMS, endMS, cursorMS;
	double fps;
	int nbFrames;
	//Selected clips rendered alone (no time range); null: all the clips of the range
	java.util.Set<Clip> solo = null;

	public static void start(TDenlive aTDe) {
		TDeGUI aGui = aTDe.gui;
		if(running != null && running.shell != null && !running.shell.isDisposed()) {
			running.shell.setActive();
			return;
		}
		if(!aTDe.timeLineStack.hasTimeRange() && aTDe.selected.clips.isEmpty()) {
			MessageBox aMB = new MessageBox(aGui.shell, SWT.ICON_WARNING|SWT.OK);
			aMB.setMessage(aGui.lngs.get("Monitor.RangeClipNoRange"));
			aMB.open();
			return;
		}
		running = new RangeClipGUI(aTDe);
	}

	RangeClipGUI(TDenlive aTDe) {
		tde = aTDe;
		PlayerControlGUI.pauseForEdit(tde);
		cursorMS = tde.timeLineStack.getTimePosMS();
		if(tde.timeLineStack.hasTimeRange()) {
			startMS = tde.timeLineStack.rangeStartMS;
			endMS = Math.min(tde.timeLineStack.rangeEndMS, tde.timeLineStack.getMaxTimePosMS());
		}
		else {
			solo = new java.util.HashSet<Clip>();
			startMS = Long.MAX_VALUE;
			endMS = 0;
			for(Clip aC : new java.util.Vector<Clip>(tde.selected.clips)) {
				solo.add(aC);
				solo.addAll(aC.getTwinClips());
				startMS = Math.min(startMS, aC.getStartTimeMS());
				endMS = Math.max(endMS, aC.getMaxTimePosMS());
			}
		}
		fps = tde.config.outFps > 0 ? tde.config.outFps : 30;
		nbFrames = Math.max(1, (int)Math.ceil((endMS-startMS)*fps/1000.0));

		File aDir = new File(tde.projectDir == null || tde.projectName == null ? tde.tdeDir : tde.projectDir, "Clips");
		aDir.mkdirs();
		NumberFormat aNF = NumberFormat.getInstance();
		aNF.setMinimumIntegerDigits(4);
		aNF.setGroupingUsed(false);
		String aPrefix = tde.projectName == null ? "3De_" : tde.projectName.replaceAll("(?i)([.]3de)+$", "")+"_";
		for(int i = 1;i < 100000;i++) {
			out = new File(aDir, aPrefix+"Clip_"+aNF.format(i)+".mp4");
			if(!out.exists()) {
				break;
			}
		}

		createContents();
		shell.open();
		render();
	}

	void createContents() {
		TDeGUI aGui = tde.gui;
		shell = new Shell(aGui.shell, SWT.TITLE|SWT.BORDER);
		shell.setText(aGui.lngs.get("Monitor.RangeClipTitle"));
		shell.setImage(aGui.imgsSwt.getIcon("videoCamera.png"));
		shell.setLayout(new GridLayout(1, false));
		aGui.applyColorFont(shell);

		status = new Label(shell, SWT.NONE);
		GridData aSGD = new GridData(GridData.FILL_HORIZONTAL);
		aSGD.widthHint = 420;
		status.setLayoutData(aSGD);
		aGui.applyColorFont(status);
		status.setText(aGui.lngs.get("Renderer.MixingAudio"));

		bar = new ProgressBar(shell, SWT.HORIZONTAL);
		bar.setMinimum(0);
		bar.setMaximum(nbFrames);
		bar.setLayoutData(new GridData(GridData.FILL_HORIZONTAL));

		cancel = new Button(shell, SWT.PUSH);
		cancel.setText(aGui.lngs.get("Monitor.RangeClipCancel"));
		cancel.setLayoutData(new GridData(GridData.HORIZONTAL_ALIGN_END));
		aGui.applyColorFont(cancel);
		cancel.addListener(SWT.Selection, e -> {
			canceled = true;
			playing = false;
			cancel.setEnabled(false);
		});
		//Closed by the end of the rendering only
		shell.addListener(SWT.Close, e -> {
			if(playing) {
				e.doit = false;
				canceled = true;
				playing = false;
				cancel.setEnabled(false);
			}
		});
		shell.pack();
		org.eclipse.swt.graphics.Rectangle aP = aGui.shell.getBounds();
		org.eclipse.swt.graphics.Point aS = shell.getSize();
		shell.setLocation(aP.x+(aP.width-aS.x)/2, aP.y+(aP.height-aS.y)/2);
	}

	void ui(Runnable aR) {
		if(!tde.gui.display.isDisposed()) {
			tde.gui.display.asyncExec(() -> {
				if(shell != null && !shell.isDisposed()) {
					aR.run();
				}
			});
		}
	}

	void render() {
		playing = true;
		if(tde.gui.monitorGUI != null) {
			tde.gui.monitorGUI.setMedia(null);
		}
		int aHeight = TDConfig.RESVALUES[tde.config.renderedDefaultRes];
		int aWidth = (int)(tde.config.outRatio * aHeight);
		Thread aTh = new Thread(() -> {
			String aError = null;
			Inpainting.Precomputer.rendering = true;
			tde.timeLineStack.soloClips = solo;
			try {
				if(tde.gui.monitorGUI != null) {
					//The image the monitor draws, if any, ends first
					synchronized(tde.gui.monitorGUI) {
					}
				}
				//Sound track first, mixed into a temporary WAV given to ffmpeg with the images
				try {
					File aWav = File.createTempFile("3De_audio_", ".wav");
					aWav.deleteOnExit();
					if(new AudioMixer(tde).renderWav(startMS, endMS, aWav)) {
						audioWav = aWav;
					}
					else {
						aWav.delete();
					}
				}
				catch(Throwable t) {
					t.printStackTrace(System.err);
				}
				if(playing) {
					renderer = new Renderer(tde, aWidth, aHeight, fps, "", new String[] {out.getAbsolutePath()}
							, new int[] {FfmpegEncoder.FORMAT_P}, audioWav == null ? null : audioWav.getAbsolutePath(), false);
					for(int f = 0;f < nbFrames && playing;f++) {
						long aT = startMS+(long)(1000*f/fps);
						tde.timeLineStack.setTimePosMS(aT);
						tde.timeLineStack.setRequestedTimePosMS(aT);
						tde.timeLineStack.reBuild(TDConfig.PROCESSING_MODE_RENDER, aT);
						BufferedImage aL = tde.timeLineStack.getFinalLeft(TDConfig.PROCESSING_MODE_RENDER, aT);
						BufferedImage aR = tde.timeLineStack.getFinalRight(TDConfig.PROCESSING_MODE_RENDER, aT);
						renderer.addPair(aL, aR);
						final int aDone = f+1;
						ui(() -> {
							bar.setSelection(aDone);
							status.setText(tde.gui.lngs.get("Monitor.RangeClipProgress")
									.replace("%DONE%", ""+aDone).replace("%TOTAL%", ""+nbFrames));
						});
					}
					renderer.stop();
				}
			}
			catch(Throwable t) {
				t.printStackTrace(System.err);
				aError = t.getMessage() == null ? t.toString() : t.getMessage();
			}
			finally {
				playing = false;
				tde.timeLineStack.soloClips = null;
				Inpainting.Precomputer.rendering = false;
				if(audioWav != null) {
					audioWav.delete();
					audioWav = null;
				}
				tde.timeLineStack.setTimePosMS(cursorMS);
				tde.timeLineStack.setRequestedTimePosMS(cursorMS);
			}
			if(canceled) {
				out.delete();
			}
			else if(aError == null) {
				if(!out.exists() || out.length() <= 0) {
					aError = out.getAbsolutePath();
				}
				else {
					ui(() -> status.setText(tde.gui.lngs.get("Monitor.RangeClipLoading")));
					try {
						tde.mediaList.loadImageP(out.getAbsolutePath(), TDConfig.PROCESSING_MODE_RENDER, 0);
					}
					catch(Throwable t) {
						t.printStackTrace(System.err);
						aError = t.getMessage() == null ? t.toString() : t.getMessage();
					}
				}
			}
			final String aErr = aError;
			tde.gui.display.asyncExec(() -> finish(aErr));
		}, "3De-RangeClip");
		aTh.setDaemon(true);
		aTh.start();
	}

	void finish(String aError) {
		running = null;
		if(shell != null && !shell.isDisposed()) {
			shell.dispose();
		}
		if(tde.gui.timeLinesGUI != null) {
			tde.gui.timeLinesGUI.redraw();
		}
		if(tde.gui.monitorGUI != null) {
			tde.gui.monitorGUI.setMedia(tde.timeLineStack);
		}
		if(tde.gui.mediaListGUI != null) {
			tde.gui.mediaListGUI.redraw();
		}
		if(aError != null) {
			MessageBox aMB = new MessageBox(tde.gui.shell, SWT.ICON_ERROR|SWT.OK);
			aMB.setMessage(tde.gui.lngs.get("Monitor.RangeClipError")+"\n"+aError);
			aMB.open();
		}
	}
}
