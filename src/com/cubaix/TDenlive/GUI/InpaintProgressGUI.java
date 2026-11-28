package com.cubaix.TDenlive.GUI;

import org.eclipse.swt.SWT;
import org.eclipse.swt.events.SelectionAdapter;
import org.eclipse.swt.events.SelectionEvent;
import org.eclipse.swt.graphics.Rectangle;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.ProgressBar;
import org.eclipse.swt.widgets.Shell;

import com.cubaix.TDenlive.TDenlive;
import com.cubaix.TDenlive.processors.Inpainting;

/**
 * Window of the progress of the AI hole filling precomputation (processors.Inpainting.Precomputer): opened when
 * images are to be precomputed, closed when all are done. Hidden by the user: not shown again until the
 * precomputation in progress ends (the next one opens it again). Not modal: the editing goes on.
 */
public class InpaintProgressGUI {
	static Shell shell = null;
	static Label countL = null;
	static Label etaL = null;
	static Label pausedL = null;
	static ProgressBar bar = null;
	static Button stopB = null;
	static Button followB = null;
	//Hidden by the user for the precomputation in progress
	static boolean dismissed = false;
	//Start of the measure of the remaining time
	static long startMS = -1;
	//Last state logged (one line per change: why the window is shown or not)
	static String lastLog = null;
	static long lastFrontMS = 0;

	static void log(String aState) {
		if(!aState.equals(lastLog)) {
			lastLog = aState;
			System.out.println("AI hole filling window: "+aState);
		}
	}
	static int startDone = 0;

	/**
	 * Show the state of the precomputation (GUI thread)
	 */
	public static void update(TDenlive aTDe) {
		if(com.cubaix.TDenlive.processors.InpaintVideos.restoring) {
			showRestoring(aTDe);
			return;
		}
		int[] aStatus = Inpainting.Precomputer.status();
		if(aStatus == null) {
			log("nothing to precompute"+(shell != null && !shell.isDisposed() ? ", closed" : ""));
			dismissed = false;
			Inpainting.Precomputer.follow = true;
			startMS = -1;
			close();
			return;
		}
		if(!Inpainting.Precomputer.stopped && stopB != null && !stopB.isDisposed() && stopB.getData() != null) {
			//Resumed elsewhere (button of the processor)
			dismissed = false;
		}
		if(dismissed || aTDe.gui == null || aTDe.gui.shell == null || aTDe.gui.shell.isDisposed()) {
			log(dismissed ? "closed by the user" : "main window missing");
			return;
		}
		if(shell == null || shell.isDisposed()) {
			create(aTDe);
			Rectangle aB = shell.getBounds();
			log("opened at "+aB.x+","+aB.y+" "+aB.width+"x"+aB.height+" ("+aStatus[0]+" / "+aStatus[1]+")");
		}
		else if(!shell.isVisible()) {
			log("shown again (it was hidden)");
			//Hidden by something else than the user (who closes it: dismissed): shown again
			shell.setVisible(true);
		}
		else if(System.currentTimeMillis()-lastFrontMS > 2000) {
			//Ordered to the front again: a window opened while a modal question of macOS was shown (the resume of the
			//depth maps at the opening of a project) was visible for SWT but never put on the screen
			lastFrontMS = System.currentTimeMillis();
			shell.moveAbove(null);
		}
		int aDone = aStatus[0];
		int aTotal = aStatus[1];
		long aNow = System.currentTimeMillis();
		if(startMS < 0 || aDone < startDone) {
			startMS = aNow;
			startDone = aDone;
		}
		boolean aStopped = Inpainting.Precomputer.stopped;
		boolean aPaused = aStopped || (aTDe.gui.playerControlGUI != null && aTDe.gui.playerControlGUI.isPlaying());
		if(stopB != null && !stopB.isDisposed() && (stopB.getData() != null) != aStopped) {
			stopB.setData(aStopped ? Boolean.TRUE : null);
			stopB.setText(aTDe.gui.lngs.get(aStopped ? "Inpaint.Resume" : "Inpaint.Interrupt"));
			stopB.setImage(aTDe.gui.imgsSwt.getIcon(aStopped ? "play.gif" : "stop.gif"));
			stopB.getParent().layout(true);
		}
		if(followB != null && !followB.isDisposed()) {
			followB.setSelection(Inpainting.Precomputer.follow);
		}
		int aPercent = 100*aDone/Math.max(1, aTotal);
		countL.setText(aTDe.gui.lngs.get("Inpaint.ProgressCount").replace("%DONE%", ""+aDone)
				.replace("%TOTAL%", ""+aTotal).replace("%PERCENT%", ""+aPercent));
		bar.setSelection(aPercent);
		String aEta = " ";
		if(aDone > startDone && !aPaused) {
			long aLeftS = (aNow-startMS)*(aTotal-aDone)/(aDone-startDone)/1000;
			aEta = aTDe.gui.lngs.get("DepthConvert.Remaining")+" "+(aLeftS >= 60 ? (aLeftS/60)+" min " : "")+(aLeftS%60)+" s";
		}
		etaL.setText(aEta);
		pausedL.setText(aStopped ? aTDe.gui.lngs.get("Inpaint.Interrupted") : (aPaused ? aTDe.gui.lngs.get("Inpaint.Paused") : " "));
		if(aPaused) {
			//The measure restarts after the play
			startMS = -1;
		}
	}

	/**
	 * The images precomputed in a previous session being read from the videos of the project (before the
	 * precomputation can tell what is left to do)
	 */
	static void showRestoring(TDenlive aTDe) {
		if(dismissed || aTDe.gui == null || aTDe.gui.shell == null || aTDe.gui.shell.isDisposed()) {
			return;
		}
		if(shell == null || shell.isDisposed()) {
			create(aTDe);
		}
		int aDone = com.cubaix.TDenlive.processors.InpaintVideos.restoreDone;
		int aTotal = Math.max(1, com.cubaix.TDenlive.processors.InpaintVideos.restoreTotal);
		int aPercent = Math.min(100, 100*aDone/aTotal);
		countL.setText(aTDe.gui.lngs.get("Inpaint.Restoring").replace("%DONE%", ""+aDone)
				.replace("%TOTAL%", ""+aTotal).replace("%PERCENT%", ""+aPercent));
		bar.setSelection(aPercent);
		long aElapsed = System.currentTimeMillis()-com.cubaix.TDenlive.processors.InpaintVideos.restoreStartMS;
		String aEta = " ";
		if(aDone > 0 && aDone < aTotal) {
			long aLeftS = aElapsed*(aTotal-aDone)/aDone/1000;
			aEta = aTDe.gui.lngs.get("DepthConvert.Remaining")+" "+(aLeftS >= 60 ? (aLeftS/60)+" min " : "")+(aLeftS%60)+" s";
		}
		etaL.setText(aEta);
		pausedL.setText(aTDe.gui.lngs.get("Inpaint.RestoringNote"));
		shell.layout(true, true);
		//Start of the precomputation measured afterwards
		startMS = -1;
	}

	/**
	 * Shown again after the user closed it (click on the precomputation text of the monitor ruler, or on the state of
	 * the hole filling in the panel of the processor)
	 */
	public static void reopen(TDenlive aTDe) {
		log("asked again");
		dismissed = false;
		update(aTDe);
		if(shell != null && !shell.isDisposed()) {
			place(aTDe);
			//Put on the screen again even if SWT takes it for visible
			shell.setVisible(false);
			shell.setVisible(true);
			shell.moveAbove(null);
			shell.setActive();
		}
	}

	/**
	 * Bottom right corner of the main window, not over the monitor
	 */
	static void place(TDenlive aTDe) {
		Rectangle aP = aTDe.gui.shell.getBounds();
		Rectangle aS = shell.getBounds();
		int aX = aP.x+aP.width-aS.width-20;
		int aY = aP.y+aP.height-aS.height-20;
		//Whole on the screen of the main window
		Rectangle aM = aTDe.gui.shell.getMonitor().getClientArea();
		aX = Math.max(aM.x, Math.min(aX, aM.x+aM.width-aS.width));
		aY = Math.max(aM.y, Math.min(aY, aM.y+aM.height-aS.height));
		shell.setLocation(aX, aY);
	}

	static void close() {
		if(shell != null && !shell.isDisposed()) {
			shell.dispose();
		}
		shell = null;
	}

	static Label addLabel(TDenlive aTDe,String aText) {
		Label aL = new Label(shell, SWT.WRAP);
		aTDe.gui.applyColorFont(aL);
		aL.setText(aText);
		GridData aGD = new GridData(GridData.FILL_HORIZONTAL);
		aGD.widthHint = 380;
		aL.setLayoutData(aGD);
		return aL;
	}

	static void create(TDenlive aTDe) {
		//A tool palette: kept above the windows of the application (an ordinary window went behind the main one at
		//the first click in it, as if it had disappeared), hidden while another application is active
		shell = new Shell(aTDe.gui.shell, SWT.DIALOG_TRIM | SWT.MODELESS | SWT.TOOL);
		shell.setText(aTDe.gui.lngs.get("Inpaint.Title"));
		shell.setLayout(new GridLayout(1, false));
		aTDe.gui.applyColorFont(shell);
		shell.setImage(aTDe.gui.imgsSwt.getIcon("3Denlive32.png"));
		Label aHead = addLabel(aTDe, aTDe.gui.lngs.get("Inpaint.ProgressHead"));
		aHead.setFont(aTDe.gui.fontsSwt.robotoBold);
		aHead.setForeground(aTDe.gui.colorsSwt.VIOLET);
		countL = addLabel(aTDe, " ");
		countL.setForeground(aTDe.gui.colorsSwt.VIOLET);
		bar = new ProgressBar(shell, SWT.SMOOTH | SWT.HORIZONTAL);
		bar.setMinimum(0);
		bar.setMaximum(100);
		bar.setLayoutData(new GridData(GridData.FILL_HORIZONTAL));
		etaL = addLabel(aTDe, " ");
		pausedL = addLabel(aTDe, " ");
		addLabel(aTDe, aTDe.gui.lngs.get("Inpaint.ProgressNote"));
		followB = new Button(shell, SWT.CHECK);
		aTDe.gui.applyColorFont(followB);
		followB.setText(aTDe.gui.lngs.get("Inpaint.Follow"));
		followB.setSelection(Inpainting.Precomputer.follow);
		followB.addSelectionListener(new SelectionAdapter() {
			@Override
			public void widgetSelected(SelectionEvent e) {
				Inpainting.Precomputer.follow = followB.getSelection();
				if(!Inpainting.Precomputer.follow) {
					Inpainting.Precomputer.unshow();
				}
			}
		});
		org.eclipse.swt.widgets.Composite aButtons = new org.eclipse.swt.widgets.Composite(shell, SWT.NONE);
		aButtons.setLayoutData(new GridData(GridData.FILL_HORIZONTAL));
		GridLayout aBL = new GridLayout(2, false);
		aBL.marginWidth = 0;
		aButtons.setLayout(aBL);
		aTDe.gui.applyColorFont(aButtons);
		stopB = new Button(aButtons, SWT.PUSH);
		aTDe.gui.applyColorFont(stopB);
		boolean aStopped = Inpainting.Precomputer.stopped;
		stopB.setData(aStopped ? Boolean.TRUE : null);
		stopB.setText(aTDe.gui.lngs.get(aStopped ? "Inpaint.Resume" : "Inpaint.Interrupt"));
		stopB.setImage(aTDe.gui.imgsSwt.getIcon(aStopped ? "play.gif" : "stop.gif"));
		stopB.setLayoutData(new GridData(GridData.HORIZONTAL_ALIGN_BEGINNING | GridData.GRAB_HORIZONTAL));
		stopB.addSelectionListener(new SelectionAdapter() {
			@Override
			public void widgetSelected(SelectionEvent e) {
				if(Inpainting.Precomputer.stopped) {
					Inpainting.Precomputer.resumeComputing();
				}
				else {
					Inpainting.Precomputer.stopComputing();
				}
				update(aTDe);
			}
		});
		Button aHide = new Button(aButtons, SWT.PUSH);
		aHide.setText(aTDe.gui.lngs.get("Inpaint.Hide"));
		aTDe.gui.applyColorFont(aHide);
		aHide.setLayoutData(new GridData(GridData.HORIZONTAL_ALIGN_END));
		aHide.addSelectionListener(new SelectionAdapter() {
			@Override
			public void widgetSelected(SelectionEvent e) {
				shell.close();
			}
		});
		//Closed by the user: not shown again for this precomputation, nor its images in the monitor
		shell.addListener(SWT.Close, e -> {
			log("closed by the user");
			dismissed = true;
			Inpainting.Precomputer.follow = false;
			Inpainting.Precomputer.unshow();
		});
		shell.pack();
		place(aTDe);
		shell.setVisible(true);
	}
}
