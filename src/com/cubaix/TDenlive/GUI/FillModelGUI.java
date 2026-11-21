package com.cubaix.TDenlive.GUI;

import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.Rectangle;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.MessageBox;
import org.eclipse.swt.widgets.ProgressBar;
import org.eclipse.swt.widgets.Shell;

import com.cubaix.TDenlive.TDenlive;
import com.cubaix.TDenlive.ai.FillGenerator;
import com.cubaix.TDenlive.ai.PythonAI;
import com.cubaix.TDenlive.depth.DepthModel;
import com.cubaix.TDenlive.depth.Downloader;
import com.cubaix.TDenlive.depth.Inpainter;
import com.cubaix.TDenlive.model3d.SmartFill;

/**
 * Choice of the model filling the holes of the 3D scenes (model3d.SmartFill.Model): ensure() checks that this machine
 * can run it, asks for its download when missing (ONNX Runtime + LaMa, or the Python environment + SDXL Inpainting)
 * and downloads it in a modal window (progress, cancel).
 */
public class FillModelGUI {
	/**
	 * The model aM can be used now (installed, after the question and the download if needed)
	 * @return false if not possible here, refused, canceled or failed
	 */
	public static boolean ensure(TDenlive aTDe,Shell aParent,SmartFill.Model aM) {
		if(aM.ready(aTDe.tdeDir)) {
			return true;
		}
		String aName = aTDe.gui.lngs.get(aM.key());
		if(!aM.capable()) {
			AiClipGUI.message(aTDe, SWT.ICON_WARNING, aTDe.gui.lngs.get("AI.SceneFillUnsupported").replace("%MODEL%", aName));
			return false;
		}
		long aBytes = aM.bytesToDownload(aTDe.tdeDir);
		if(aM == SmartFill.Model.SDXL) {
			aBytes += PythonAI.envBytesToInstall(aTDe.tdeDir);
		}
		MessageBox aMB = new MessageBox(aParent, SWT.ICON_QUESTION|SWT.YES|SWT.NO);
		aMB.setText(aTDe.gui.lngs.get("AI.Title"));
		aMB.setMessage(aTDe.gui.lngs.get("AI.SceneFillAsk").replace("%MODEL%", aName).replace("%GB%", DepthModel.gb(aBytes)));
		if(aMB.open() != SWT.YES) {
			return false;
		}
		return download(aTDe, aParent, aM, aName);
	}

	static boolean download(TDenlive aTDe,Shell aParent,SmartFill.Model aM,String aName) {
		Shell aD = new Shell(aParent, SWT.DIALOG_TRIM | SWT.APPLICATION_MODAL);
		aD.setText(aTDe.gui.lngs.get("AI.Title"));
		aTDe.gui.applyColorFont(aD);
		aD.setLayout(new GridLayout(1, false));
		Label aTitle = new Label(aD, SWT.WRAP);
		aTDe.gui.applyColorFont(aTitle);
		aTitle.setText(aTDe.gui.lngs.get("AI.DownloadingFillModel")+" "+aName);
		aTitle.setFont(aTDe.gui.fontsSwt.robotoBold);
		GridData aTGD = new GridData(GridData.FILL_HORIZONTAL);
		aTGD.widthHint = 460;
		aTitle.setLayoutData(aTGD);
		Label aStep = new Label(aD, SWT.WRAP);
		aTDe.gui.applyColorFont(aStep);
		aStep.setText(aTDe.gui.lngs.get("AI.Starting"));
		aStep.setForeground(aTDe.gui.colorsSwt.VIOLET);
		GridData aSGD = new GridData(GridData.FILL_HORIZONTAL);
		aSGD.widthHint = 460;
		aStep.setLayoutData(aSGD);
		ProgressBar aBar = new ProgressBar(aD, SWT.HORIZONTAL);
		aBar.setMaximum(1000);
		aBar.setLayoutData(new GridData(GridData.FILL_HORIZONTAL));
		Button aCancel = new Button(aD, SWT.PUSH);
		aTDe.gui.applyColorFont(aCancel);
		aCancel.setText(aTDe.gui.lngs.get("AI.Cancel"));
		aCancel.setLayoutData(new GridData(SWT.END, SWT.CENTER, false, false));
		final boolean[] aOk = {false};
		final boolean[] aCanceled = {false};
		final String[] aErr = {null};
		PythonAI.Progress aP = new PythonAI.Progress() {
			@Override
			public void status(String aKey, String aDetail, long aDone, long aTotal) {
				String aT = aTDe.gui.lngs.get(aKey)+(aKey.startsWith("AI.Downloading") && aTotal > 0
						? " "+DepthModel.gb(aDone)+" / "+DepthModel.gb(aTotal)+" GB" : "");
				int aV = aTotal > 0 ? (int)Math.min(1000, 1000*aDone/aTotal) : -1;
				aTDe.gui.display.asyncExec(() -> {
					if(aD.isDisposed()) {
						return;
					}
					aStep.setText(aT);
					if(aV >= 0) {
						aBar.setSelection(aV);
					}
				});
			}
			@Override
			public boolean isCanceled() {
				return aCanceled[0];
			}
		};
		Downloader.Progress aDP = new Downloader.Progress() {
			@Override
			public void downloaded(long aDone, long aTotal) {
				aP.status("AI.DownloadingFillModel", null, aDone, aTotal);
			}
			@Override
			public boolean isCanceled() {
				return aCanceled[0];
			}
		};
		Thread aT = new Thread(() -> {
			try {
				if(aM.engine != null) {
					aOk[0] = Inpainter.download(aTDe.tdeDir, aM.engine, aDP, aDP) && Inpainter.isReady(aTDe.tdeDir, aM.engine);
				}
				else {
					aOk[0] = new FillGenerator(aTDe.tdeDir, aP).install() && FillGenerator.isReady(aTDe.tdeDir);
				}
			}
			catch(Throwable t) {
				t.printStackTrace(System.err);
				aErr[0] = t.getMessage() == null ? t.toString() : t.getMessage();
			}
			aTDe.gui.display.asyncExec(() -> {
				if(!aD.isDisposed()) {
					aD.dispose();
				}
			});
		}, "Fill model download");
		aT.setDaemon(true);
		aCancel.addListener(SWT.Selection, e -> {
			aCanceled[0] = true;
			aD.dispose();
		});
		aD.addListener(SWT.Close, e -> aCanceled[0] = true);
		aD.pack();
		Rectangle aPB = aParent.getBounds();
		Rectangle aS = aD.getBounds();
		aD.setLocation(aPB.x + (aPB.width - aS.width) / 2, aPB.y + (aPB.height - aS.height) / 2);
		aD.open();
		aT.start();
		while(!aD.isDisposed()) {
			if(!aTDe.gui.display.readAndDispatch()) {
				aTDe.gui.display.sleep();
			}
		}
		if(aErr[0] != null && !aCanceled[0]) {
			AiClipGUI.message(aTDe, SWT.ICON_ERROR, aTDe.gui.lngs.get("AI.SceneFillError")+" "+aErr[0]);
		}
		return aOk[0] && !aCanceled[0];
	}
}
