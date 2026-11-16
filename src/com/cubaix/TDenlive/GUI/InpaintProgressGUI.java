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
	//Hidden by the user for the precomputation in progress
	static boolean dismissed = false;
	//Start of the measure of the remaining time
	static long startMS = -1;
	static int startDone = 0;

	/**
	 * Show the state of the precomputation (GUI thread)
	 */
	public static void update(TDenlive aTDe) {
		int[] aStatus = Inpainting.Precomputer.status();
		if(aStatus == null) {
			dismissed = false;
			startMS = -1;
			close();
			return;
		}
		if(dismissed || aTDe.gui == null || aTDe.gui.shell == null || aTDe.gui.shell.isDisposed()) {
			return;
		}
		if(shell == null || shell.isDisposed()) {
			create(aTDe);
		}
		int aDone = aStatus[0];
		int aTotal = aStatus[1];
		long aNow = System.currentTimeMillis();
		if(startMS < 0 || aDone < startDone) {
			startMS = aNow;
			startDone = aDone;
		}
		boolean aPaused = aTDe.gui.playerControlGUI != null && aTDe.gui.playerControlGUI.isPlaying();
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
		pausedL.setText(aPaused ? aTDe.gui.lngs.get("Inpaint.Paused") : " ");
		if(aPaused) {
			//The measure restarts after the play
			startMS = -1;
		}
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
		shell = new Shell(aTDe.gui.shell, SWT.DIALOG_TRIM | SWT.MODELESS);
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
		Button aHide = new Button(shell, SWT.PUSH);
		aHide.setText(aTDe.gui.lngs.get("Inpaint.Hide"));
		aTDe.gui.applyColorFont(aHide);
		aHide.setLayoutData(new GridData(GridData.HORIZONTAL_ALIGN_END));
		aHide.addSelectionListener(new SelectionAdapter() {
			@Override
			public void widgetSelected(SelectionEvent e) {
				shell.close();
			}
		});
		//Closed by the user: not shown again for this precomputation
		shell.addListener(SWT.Close, e -> dismissed = true);
		shell.pack();
		//Bottom right corner of the main window, not over the monitor
		Rectangle aP = aTDe.gui.shell.getBounds();
		Rectangle aS = shell.getBounds();
		shell.setLocation(aP.x+aP.width-aS.width-20, aP.y+aP.height-aS.height-20);
		shell.setVisible(true);
	}
}
