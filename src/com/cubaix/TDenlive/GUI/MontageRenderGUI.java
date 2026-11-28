package com.cubaix.TDenlive.GUI;

import java.io.File;
import java.text.NumberFormat;
import java.util.Vector;
import java.util.function.Consumer;

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
import com.cubaix.TDenlive.medias.Montage;
import com.cubaix.TDenlive.medias.StereoMovie;
import com.cubaix.TDenlive.processors.Inpainting;
import com.cubaix.TDenlive.processors.Renderer;
import com.cubaix.TDenlive.processors.TwoPassRender;

/**
 * Final renderings of montages (see Montage), one after the other in the order given (the included ones first): each
 * montage is shown while it is rendered (time lines, monitor), whole, in the final quality, both views side by side
 * with the sound, at the default rendering resolution and the fps of the montage, into Montages/ next to the project
 * (~/3Denlive/Montages before the first save). The new video replaces the previous rendering in the clips of all
 * the montages (Montage.replaceMedia()); the previous file is kept (the undo may need it). Modal window, cancel.
 * The montage shown before is shown again at the end.
 * Also the questions before a rendering (beforeRender()) or when a montage is shown (offerUpdates()) when the
 * renderings of the montages it includes are out of date.
 */
public class MontageRenderGUI {
	static MontageRenderGUI running = null;

	TDenlive tde;
	Vector<Montage> order;
	Consumer<Boolean> done;
	Montage shownBefore;
	int index = 0;
	Shell shell;
	Label status;
	ProgressBar bar;
	Button cancel;
	volatile boolean playing = false;
	volatile boolean canceled = false;

	public static boolean isRunning() {
		return running != null;
	}

	/**
	 * Renders the montages of aOrder, then aDone(true) on the UI thread, or aDone(false) if canceled / failed
	 */
	public static void render(TDenlive aTDe, Vector<Montage> aOrder, Consumer<Boolean> aDone) {
		if(running != null) {
			if(running.shell != null && !running.shell.isDisposed()) {
				running.shell.setActive();
			}
			return;
		}
		if(aOrder == null || aOrder.isEmpty()) {
			aDone.accept(true);
			return;
		}
		running = new MontageRenderGUI(aTDe, aOrder, aDone);
	}

	/**
	 * Before rendering the montage shown (rendering window, clip of a time range): the renderings of the montages it
	 * includes that are out of date are made again (Yes), or kept as they are (No), or nothing (Cancel)
	 */
	public static void beforeRender(TDenlive aTDe, Runnable aGo) {
		if(running != null) {
			return;
		}
		Vector<Montage> aChain = Montage.staleChain(aTDe, aTDe.montage);
		if(aChain.isEmpty()) {
			aGo.run();
			return;
		}
		MessageBox aMB = new MessageBox(aTDe.gui.shell, SWT.ICON_WARNING|SWT.YES|SWT.NO|SWT.CANCEL);
		aMB.setText(aTDe.gui.lngs.get("Montage.Title"));
		aMB.setMessage(aTDe.gui.lngs.get("Montage.RenderStale").replace("%l", MontageListGUI.names(aChain, aTDe)));
		int aAns = aMB.open();
		if(aAns == SWT.NO) {
			aGo.run();
		}
		else if(aAns == SWT.YES) {
			render(aTDe, aChain, aOk -> {
				if(aOk) {
					aGo.run();
				}
			});
		}
	}

	/**
	 * aTarget just shown: proposes to make again the renderings of the montages it includes that are out of date
	 */
	public static void offerUpdates(TDenlive aTDe, Montage aTarget) {
		if(running != null || aTarget != aTDe.montage) {
			return;
		}
		Vector<Montage> aChain = Montage.staleChain(aTDe, aTarget);
		if(aTDe.gui.montageListGUI != null) {
			aTDe.gui.montageListGUI.redraw();
		}
		if(aChain.isEmpty()) {
			return;
		}
		MessageBox aMB = new MessageBox(aTDe.gui.shell, SWT.ICON_QUESTION|SWT.YES|SWT.NO);
		aMB.setText(aTDe.gui.lngs.get("Montage.Title"));
		aMB.setMessage(aTDe.gui.lngs.get("Montage.UpdateAsk").replace("%t", aTarget.displayName(aTDe))
				.replace("%l", MontageListGUI.names(aChain, aTDe)));
		if(aMB.open() == SWT.YES) {
			render(aTDe, aChain, aOk -> {});
		}
	}

	MontageRenderGUI(TDenlive aTDe, Vector<Montage> aOrder, Consumer<Boolean> aDone) {
		tde = aTDe;
		order = new Vector<Montage>(aOrder);
		done = aDone;
		shownBefore = tde.montage;
		PlayerControlGUI.pauseForEdit(tde);
		createContents();
		shell.open();
		next();
	}

	void createContents() {
		TDeGUI aGui = tde.gui;
		shell = new Shell(aGui.shell, SWT.TITLE|SWT.BORDER|SWT.APPLICATION_MODAL);
		shell.setText(aGui.lngs.get("Montage.RenderTitle"));
		shell.setImage(aGui.imgsSwt.getIcon("videoCamera.png"));
		shell.setLayout(new GridLayout(1, false));
		aGui.applyColorFont(shell);
		status = new Label(shell, SWT.WRAP);
		GridData aSGD = new GridData(GridData.FILL_HORIZONTAL);
		aSGD.widthHint = 460;
		status.setLayoutData(aSGD);
		aGui.applyColorFont(status);
		status.setText(aGui.lngs.get("Renderer.MixingAudio"));
		bar = new ProgressBar(shell, SWT.HORIZONTAL);
		bar.setMinimum(0);
		bar.setMaximum(1);
		bar.setLayoutData(new GridData(GridData.FILL_HORIZONTAL));
		cancel = new Button(shell, SWT.PUSH);
		cancel.setText(aGui.lngs.get("Monitor.RangeClipCancel"));
		cancel.setLayoutData(new GridData(GridData.HORIZONTAL_ALIGN_END));
		aGui.applyColorFont(cancel);
		cancel.addListener(SWT.Selection, e -> stop());
		shell.addListener(SWT.Close, e -> {
			e.doit = false;
			stop();
		});
		shell.pack();
		org.eclipse.swt.graphics.Rectangle aP = aGui.shell.getBounds();
		org.eclipse.swt.graphics.Point aS = shell.getSize();
		shell.setLocation(aP.x+(aP.width-aS.x)/2, aP.y+(aP.height-aS.y)/2);
	}

	void stop() {
		canceled = true;
		playing = false;
		if(!cancel.isDisposed()) {
			cancel.setEnabled(false);
		}
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

	/** File of a new rendering of aM: Montages/<name>_NNNN.mp4 next to the project */
	File outFile(Montage aM) {
		return outFile(tde, aM);
	}

	/**
	 * Provisional rendering of aM when its rendering was canceled, failed or asked for later (the clips put into it
	 * by MontageListGUI.extractClips() keep their place): a still image « Montage not computed » drawn in memory, no
	 * file (medias.MontagePlaceholder: it follows the language and the name of the montage), out of date
	 */
	public static StereoMovie placeholder(TDenlive tde, Montage aM) {
		return new com.cubaix.TDenlive.medias.MontagePlaceholder(tde, aM);
	}

	static File outFile(TDenlive tde, Montage aM) {
		File aDir = new File(tde.projectDir == null || tde.projectName == null ? tde.tdeDir : tde.projectDir, Montage.DIR);
		aDir.mkdirs();
		NumberFormat aNF = NumberFormat.getInstance();
		aNF.setMinimumIntegerDigits(4);
		aNF.setGroupingUsed(false);
		String aBase = aM.displayName(tde).replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", "_").trim();
		if(aBase.isEmpty()) {
			aBase = "Montage";
		}
		File aF = null;
		for(int i = 1;i < 100000;i++) {
			aF = new File(aDir, aBase+"_"+aNF.format(i)+".mp4");
			if(!aF.exists()) {
				break;
			}
		}
		return aF;
	}

	/** Renders the next montage, or ends */
	void next() {
		if(canceled) {
			finish(false, null);
			return;
		}
		if(index >= order.size()) {
			finish(true, null);
			return;
		}
		Montage aM = order.elementAt(index);
		long aEndMS = aM.stack.getMaxTimePosMS();
		if(!tde.montages.contains(aM) || aEndMS <= 0) {
			index++;
			next();
			return;
		}
		//Shown while rendered: the rendering works on the active montage
		tde.gui.showMontage(aM, false);
		String aSig = aM.signature(tde);
		File aOut = outFile(aM);
		double aFps = tde.config.outFps > 0 ? tde.config.outFps : 30;
		int aHeight = TDConfig.RESVALUES[tde.config.renderedDefaultRes];
		int aWidth = (int)(tde.config.outRatio * aHeight);
		long aCursorMS = tde.timeLineStack.getTimePosMS();
		String aName = aM.displayName(tde);
		String aCount = order.size() > 1 ? " ("+(index+1)+"/"+order.size()+")" : "";
		status.setText(tde.gui.lngs.get("Montage.Rendering").replace("%s", aName)+aCount);
		bar.setSelection(0);
		playing = true;
		if(tde.gui.monitorGUI != null) {
			tde.gui.monitorGUI.setMedia(null);
			tde.gui.monitorGUI.setRendering(true);
		}
		Thread aTh = new Thread(() -> {
			String aError = null;
			StereoMovie aNew = null;
			File aWavF = null;
			Inpainting.Precomputer.rendering = true;
			tde.timeLineStack.soloClips = null;
			try {
				if(tde.gui.monitorGUI != null) {
					synchronized(tde.gui.monitorGUI) {
					}
				}
				try {
					File aWav = File.createTempFile("3De_audio_", ".wav");
					aWav.deleteOnExit();
					if(new AudioMixer(tde).renderWav(0, aEndMS, aWav)) {
						aWavF = aWav;
					}
					else {
						aWav.delete();
					}
				}
				catch(Throwable t) {
					t.printStackTrace(System.err);
				}
				if(playing) {
					Renderer aRenderer = new Renderer(tde, aWidth, aHeight, aFps, "", new String[] {aOut.getAbsolutePath()}
							, new int[] {FfmpegEncoder.FORMAT_P}, aWavF == null ? null : aWavF.getAbsolutePath(), false);
					TwoPassRender.run(tde, aRenderer, 0, aEndMS, aFps, () -> playing
							, (aPass, aIndex, aTotal, aTimeMS, aL, aR, aKey) -> {
						if(tde.gui.monitorGUI != null) {
							tde.gui.monitorGUI.showRendered(aL, aR);
						}
						final int aDone = aIndex+1;
						ui(() -> {
							tde.gui.timeLinesGUI.redraw();
							if(tde.gui.monitorRulerGUI != null) {
								tde.gui.monitorRulerGUI.redraw();
							}
							bar.setMaximum(aTotal);
							bar.setSelection(aDone);
							status.setText(tde.gui.lngs.get(aPass == 1 ? "Renderer.KeyPass" : "Montage.RenderProgress")
									.replace("%FPS%", TwoPassRender.keyFpsText()).replace("%s", aName)
									.replace("%DONE%", ""+aDone).replace("%TOTAL%", ""+aTotal)+aCount);
						});
					});
					aRenderer.stop();
				}
				if(!canceled) {
					if(!aOut.exists() || aOut.length() <= 0) {
						aError = aOut.getAbsolutePath();
					}
					else {
						ui(() -> status.setText(tde.gui.lngs.get("Monitor.RangeClipLoading")));
						aNew = new StereoMovie(tde);
						aNew.montageUID = aM.UID;
						aNew.montageSig = aSig;
						aNew.montageStale = false;
						aNew.libName = aName;
						aNew.loadImageP(aOut.getAbsolutePath(), TDConfig.PROCESSING_MODE_RENDER, 0);
					}
				}
			}
			catch(Throwable t) {
				t.printStackTrace(System.err);
				aError = t.getMessage() == null ? t.toString() : t.getMessage();
				aNew = null;
			}
			finally {
				playing = false;
				Inpainting.Precomputer.rendering = false;
				if(aWavF != null) {
					aWavF.delete();
				}
				tde.timeLineStack.setTimePosMS(aCursorMS);
				tde.timeLineStack.setRequestedTimePosMS(aCursorMS);
			}
			if(canceled || aError != null) {
				aOut.delete();
			}
			final String aErr = aError;
			final StereoMovie aMedia = aNew;
			tde.gui.display.asyncExec(() -> {
				if(tde.gui.monitorGUI != null) {
					tde.gui.monitorGUI.setRendering(false);
				}
				if(aErr != null) {
					finish(false, aErr);
					return;
				}
				if(canceled || aMedia == null) {
					finish(false, null);
					return;
				}
				install(aM, aMedia);
				index++;
				next();
			});
		}, "3De-MontageRender");
		aTh.setDaemon(true);
		aTh.start();
	}

	/** aNew becomes the rendering of aM, in place of the previous one in the media list and the clips */
	void install(Montage aM, StereoMovie aNew) {
		StereoMovie aOld = aM.renderMedia(tde);
		synchronized(tde.mediaList.mediaList) {
			int aIdx = aOld == null ? -1 : tde.mediaList.mediaList.indexOf(aOld);
			if(aIdx >= 0) {
				tde.mediaList.mediaList.add(aIdx, aNew);
			}
			else {
				tde.mediaList.mediaList.add(aNew);
			}
		}
		if(aOld != null) {
			Montage.replaceMedia(tde, aOld, aNew);
			tde.mediaList.removeMedia(aOld);
		}
		tde.updateFirstMediaRatio();
		//The montages including it now include a new rendering: theirs are out of date
		Montage.refreshStates(tde);
	}

	void finish(boolean aOk, String aError) {
		running = null;
		if(shell != null && !shell.isDisposed()) {
			shell.dispose();
		}
		if(tde.gui.monitorGUI != null) {
			tde.gui.monitorGUI.setRendering(false);
		}
		if(shownBefore != null && tde.montages.contains(shownBefore)) {
			tde.gui.showMontage(shownBefore, false);
		}
		else {
			tde.gui.showMontage(tde.mainMontage(), false);
		}
		Montage.refreshStates(tde);
		if(tde.gui.mediaListGUI != null) {
			tde.gui.mediaListGUI.redraw();
		}
		if(tde.gui.montageListGUI != null) {
			tde.gui.montageListGUI.redraw();
		}
		if(tde.gui.montagePropsGUI != null) {
			tde.gui.montagePropsGUI.refresh();
		}
		if(aError != null) {
			MessageBox aMB = new MessageBox(tde.gui.shell, SWT.ICON_ERROR|SWT.OK);
			aMB.setMessage(tde.gui.lngs.get("Montage.RenderError")+"\n"+aError);
			aMB.open();
		}
		try {
			done.accept(aOk);
		}
		catch(Throwable t) {
			t.printStackTrace(System.err);
		}
	}
}
