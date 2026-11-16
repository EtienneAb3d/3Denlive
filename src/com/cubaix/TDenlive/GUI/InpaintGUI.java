package com.cubaix.TDenlive.GUI;

import org.eclipse.swt.SWT;
import org.eclipse.swt.events.SelectionAdapter;
import org.eclipse.swt.events.SelectionEvent;
import org.eclipse.swt.graphics.Rectangle;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Group;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.MessageBox;
import org.eclipse.swt.widgets.ProgressBar;
import org.eclipse.swt.widgets.Shell;

import com.cubaix.TDenlive.TDConfig;
import com.cubaix.TDenlive.TDenlive;
import com.cubaix.TDenlive.depth.DepthEstimator;
import com.cubaix.TDenlive.depth.DepthModel;
import com.cubaix.TDenlive.depth.Downloader;
import com.cubaix.TDenlive.depth.Inpainter;
import com.cubaix.TDenlive.processors.Inpainting;

/**
 * Hole filling of the depth map processors (see processors.Inpainting): a button of the processor GUI opens a panel
 * that chooses the method of the clip per processing level (resolution shown): work resolution and full screen
 * preview: interpolation or the AI precomputed; final rendering: interpolation, the AI precomputed, or the AI only
 * at the rendering. Interpolation everywhere until configured. "Validate" downloads the model and runtime when
 * needed (progress), saves the methods and starts the precomputation (with its progress window).
 */
public class InpaintGUI {
	TDenlive tde;
	volatile boolean canceled = false;

	InpaintGUI(TDenlive aTDe) {
		tde = aTDe;
	}

	String lng(String aKey) {
		return tde.gui.lngs.get(aKey);
	}

	/**
	 * Text of the button: the methods of the clip
	 */
	static String buttonText(TDenlive aTDe,Inpainting aInp) {
		return aTDe.gui.lngs.get("Inpaint.Button").replace("%METHOD%"
				, aTDe.gui.lngs.get(aInp.enabled && Inpainter.capable() ? "Inpaint.ButtonAi" : "Inpaint.ButtonInterp"));
	}

	/**
	 * "Hole filling..." button of a depth map processor GUI
	 */
	public static Button addButton(TDenlive aTDe,Composite aParent,Inpainting aInp) {
		final Button aB = new Button(aParent, SWT.PUSH);
		aTDe.gui.applyColorFont(aB);
		aTDe.gui.tip(aB, Inpainter.ENGINE == Inpainter.Engine.LAMA ? "Inpaint" : "InpaintLight");
		aB.setText(buttonText(aTDe, aInp));
		GridData aGD = new GridData(GridData.FILL_HORIZONTAL);
		aGD.heightHint = aTDe.gui.h(24);
		aB.setLayoutData(aGD);
		aB.addSelectionListener(new SelectionAdapter() {
			@Override
			public void widgetSelected(SelectionEvent e) {
				if(new InpaintGUI(aTDe).configure(aInp) && !aB.isDisposed()) {
					aB.setText(buttonText(aTDe, aInp));
					aB.getParent().layout(true);
				}
			}
		});
		return aB;
	}

	/**
	 * After the opening of a project (GUI thread): the images precomputed by the model are not there (cache of
	 * the session): the user starts their precomputation, or chooses other methods, one for all the clips
	 * concerned. Without a GPU able to run the model: told that the holes stay interpolated.
	 */
	public static void afterOpen(TDenlive aTDe) {
		try {
			java.util.Vector<Inpainting> aAi = new java.util.Vector<Inpainting>();
			boolean aPrecompute = false;
			for(Inpainting aI : Inpainting.Precomputer.all(aTDe)) {
				if(aI.enabled) {
					aAi.add(aI);
					for(int m = 0;m < aI.method.length;m++) {
						aPrecompute |= aI.precomputes(m);
					}
				}
			}
			if(aAi.isEmpty()) {
				return;
			}
			InpaintGUI aGUI = new InpaintGUI(aTDe);
			if(!DepthEstimator.isPlatformSupported() || !Inpainter.capable()) {
				aGUI.message(SWT.ICON_WARNING, aGUI.lng(DepthEstimator.isPlatformSupported() ? "Inpaint.OpenNoGpu" : "Inpaint.OpenUnsupported")
						.replace("%N%", ""+aAi.size()).replace("%GB%", DepthModel.gb(Inpainter.ENGINE.gpuMemory)));
				return;
			}
			if(!aPrecompute && Inpainter.isReady(aTDe.tdeDir)) {
				//Only at the rendering: nothing to precompute
				return;
			}
			Inpainting[] aInps = aAi.toArray(new Inpainting[0]);
			while(true) {
				MessageBox aMB = new MessageBox(aTDe.gui.shell, SWT.ICON_QUESTION | SWT.YES | SWT.NO);
				aMB.setText(aGUI.lng("Inpaint.Title"));
				aMB.setMessage(aGUI.lng("Inpaint.OpenMissing").replace("%N%", ""+aInps.length));
				java.util.HashMap<Integer,String> aLabels = new java.util.HashMap<Integer,String>();
				aLabels.put(SWT.YES, aGUI.lng("Inpaint.OpenStart"));
				aLabels.put(SWT.NO, aGUI.lng("Inpaint.OpenConfigure"));
				aMB.setButtonLabels(aLabels);
				if(aMB.open() == SWT.YES) {
					if(!Inpainter.isReady(aTDe.tdeDir) && !aGUI.download()) {
						//Not downloaded: the holes stay interpolated until configured again
						return;
					}
					InpaintProgressGUI.dismissed = false;
					break;
				}
				if(aGUI.configure(aInps)) {
					break;
				}
				//Panel canceled: asked again
			}
			aTDe.gui.monitorGUI.redraw();
			if(aTDe.gui.monitorRulerGUI != null) {
				aTDe.gui.monitorRulerGUI.redraw();
			}
		}
		catch(Throwable t) {
			t.printStackTrace(System.err);
		}
		finally {
			Inpainting.Precomputer.held = false;
		}
	}

	/**
	 * Name of the resolution of a mode, e.g. "720 (HD)"
	 */
	String resName(int aMode) {
		String aShort = tde.config.processingResNames[aMode];
		for(int r = 0;r < TDConfig.RESSHORTNAMES.length;r++) {
			if(TDConfig.RESSHORTNAMES[r].equals(aShort)) {
				return TDConfig.RESNAMES[r];
			}
		}
		return aShort;
	}

	/**
	 * Group of the choices of a level, the current one selected
	 */
	Button[] level(Composite aParent,String aKey,int aMode,int[] aMethods,int aCurrent,boolean aAi) {
		Group aG = new Group(aParent, SWT.NONE);
		tde.gui.applyColorFont(aG);
		aG.setText(lng(aKey).replace("%RES%", resName(aMode)));
		aG.setLayoutData(new GridData(GridData.FILL_HORIZONTAL));
		aG.setLayout(new GridLayout(aMethods.length, false));
		Button[] aBs = new Button[aMethods.length];
		for(int i = 0;i < aMethods.length;i++) {
			aBs[i] = new Button(aG, SWT.RADIO);
			tde.gui.applyColorFont(aBs[i]);
			aBs[i].setText(lng(aMethods[i] == Inpainting.INTERPOLATION ? "Inpaint.Interpolation"
					: (aMethods[i] == Inpainting.PRECOMPUTE ? "Inpaint.PrecomputeAi" : "Inpaint.AtRender")));
			aBs[i].setData(aMethods[i]);
			aBs[i].setSelection(aMethods[i] == aCurrent || (!aAi && aMethods[i] == Inpainting.INTERPOLATION));
			aBs[i].setEnabled(aAi || aMethods[i] == Inpainting.INTERPOLATION);
		}
		return aBs;
	}

	static int chosen(Button[] aBs) {
		for(Button aB : aBs) {
			if(aB.getSelection()) {
				return (Integer)aB.getData();
			}
		}
		return Inpainting.INTERPOLATION;
	}

	/**
	 * Panel of the methods of the clip (several: the same methods for all, those of the first shown)
	 * @return true if validated (methods changed)
	 */
	boolean configure(Inpainting... aInps) {
		final boolean[] aResult = {false};
		Inpainting aInp = aInps[0];
		boolean aSupported = DepthEstimator.isPlatformSupported();
		//The model where it can run (LaMa: only on a GPU), else always the interpolation
		boolean aAi = aSupported && Inpainter.capable();
		Shell aShell = new Shell(tde.gui.shell, SWT.DIALOG_TRIM | SWT.APPLICATION_MODAL);
		aShell.setText(lng("Inpaint.Title"));
		aShell.setLayout(new GridLayout(1, false));
		tde.gui.applyColorFont(aShell);
		aShell.setImage(tde.gui.imgsSwt.getIcon("3Denlive32.png"));
		addLabel(aShell, lng(Inpainter.ENGINE == Inpainter.Engine.LAMA ? "Inpaint.Intro" : "Inpaint.IntroLight"));
		int[] aMonitor = {Inpainting.INTERPOLATION, Inpainting.PRECOMPUTE};
		Button[] aWork = level(aShell, "Inpaint.LevelWork", TDConfig.PROCESSING_MODE_WORK, aMonitor
				, aInp.method[TDConfig.PROCESSING_MODE_WORK], aAi);
		Button[] aFull = level(aShell, "Inpaint.LevelFull", TDConfig.PROCESSING_MODE_FULLSCREEN, aMonitor
				, aInp.method[TDConfig.PROCESSING_MODE_FULLSCREEN], aAi);
		Button[] aRender = level(aShell, "Inpaint.LevelRender", TDConfig.PROCESSING_MODE_RENDER
				, new int[] {Inpainting.INTERPOLATION, Inpainting.PRECOMPUTE, Inpainting.AT_RENDER}
				, aInp.method[TDConfig.PROCESSING_MODE_RENDER], aAi);
		if(aInps.length > 1) {
			addLabel(aShell, lng("Inpaint.AllClips").replace("%N%", ""+aInps.length));
		}
		if(!aAi) {
			Label aL = addLabel(aShell, lng(aSupported ? "Inpaint.NoGpu" : "Inpaint.Unsupported")
					.replace("%GB%", DepthModel.gb(Inpainter.ENGINE.gpuMemory)));
			aL.setForeground(tde.gui.colorsSwt.VIOLET);
		}
		else {
			long aSize = 0;
			//An embedded model is not downloaded
			if(Inpainter.ENGINE.url != null && !Inpainter.isDownloaded(tde.tdeDir)) {
				aSize += Inpainter.ENGINE.size;
			}
			if(!DepthEstimator.isOrtDownloaded(tde.tdeDir)) {
				aSize += DepthEstimator.ortSize();
			}
			if(aSize > 0) {
				addLabel(aShell, lng("Inpaint.Download").replace("%MB%", DepthModel.mb(aSize)));
			}
			addLabel(aShell, lng(Inpainter.ENGINE.gpuOnly ? "Inpaint.OnGpu" : "Inpaint.OnGpuOrCpu"));
			addLabel(aShell, lng("Inpaint.Precompute"));
		}

		Composite aButtons = new Composite(aShell, SWT.NONE);
		GridData aBGD = new GridData(GridData.HORIZONTAL_ALIGN_END);
		aBGD.verticalIndent = 8;
		aButtons.setLayoutData(aBGD);
		tde.gui.applyColorFont(aButtons);
		GridLayout aBGL = new GridLayout(2, true);
		tde.gui.applyNoMargin(aBGL);
		aButtons.setLayout(aBGL);
		Button aCancel = new Button(aButtons, SWT.PUSH);
		aCancel.setText(lng("Inpaint.Cancel"));
		tde.gui.applyColorFont(aCancel);
		aCancel.setLayoutData(new GridData(GridData.FILL_HORIZONTAL));
		aCancel.addSelectionListener(new SelectionAdapter() {
			@Override
			public void widgetSelected(SelectionEvent e) {
				aShell.close();
			}
		});
		Button aOk = new Button(aButtons, SWT.PUSH);
		aOk.setText(lng("Inpaint.Validate"));
		aOk.setImage(tde.gui.imgsSwt.getIcon("checked.gif"));
		tde.gui.applyColorFont(aOk);
		aOk.setLayoutData(new GridData(GridData.FILL_HORIZONTAL));
		aOk.addSelectionListener(new SelectionAdapter() {
			@Override
			public void widgetSelected(SelectionEvent e) {
				int aW = chosen(aWork);
				int aF = chosen(aFull);
				int aR = chosen(aRender);
				boolean aNeedsAi = aW != Inpainting.INTERPOLATION || aF != Inpainting.INTERPOLATION || aR != Inpainting.INTERPOLATION;
				if(aNeedsAi && !Inpainter.isReady(tde.tdeDir)) {
					//Download first, the panel stays open if it fails or is canceled
					aShell.setVisible(false);
					boolean aReady = download();
					if(aShell.isDisposed()) {
						return;
					}
					if(!aReady) {
						aShell.setVisible(true);
						return;
					}
				}
				for(Inpainting aI : aInps) {
					aI.setMethods(aW, aF, aR);
				}
				//Progress window shown again for this precomputation
				InpaintProgressGUI.dismissed = false;
				aResult[0] = true;
				aShell.close();
			}
		});
		aShell.setDefaultButton(aOk);
		center(aShell);
		aShell.open();
		while(!aShell.isDisposed()) {
			if(!tde.gui.display.readAndDispatch()) {
				tde.gui.display.sleep();
			}
		}
		if(aResult[0]) {
			tde.gui.monitorGUI.redraw();
			if(tde.gui.monitorRulerGUI != null) {
				tde.gui.monitorRulerGUI.redraw();
			}
		}
		return aResult[0];
	}

	void message(int aIcon,String aText) {
		MessageBox aMB = new MessageBox(tde.gui.shell, aIcon|SWT.OK);
		aMB.setText(lng("Inpaint.Title"));
		aMB.setMessage(aText);
		aMB.open();
	}

	Label addLabel(Composite aParent,String aText) {
		Label aL = new Label(aParent, SWT.WRAP);
		tde.gui.applyColorFont(aL);
		aL.setText(aText);
		GridData aGD = new GridData(GridData.FILL_HORIZONTAL);
		aGD.widthHint = 480;
		aL.setLayoutData(aGD);
		return aL;
	}

	void center(Shell aShell) {
		aShell.pack();
		Rectangle aP = tde.gui.shell.getBounds();
		Rectangle aS = aShell.getBounds();
		aShell.setLocation(aP.x + (aP.width - aS.width) / 2, aP.y + (aP.height - aS.height) / 2);
	}

	/**
	 * Download of the runtime and of the model, in a modal progress window
	 * @return true when done
	 */
	boolean download() {
		final boolean[] aOk = {false};
		final String[] aErr = {null};
		Shell aShell = new Shell(tde.gui.shell, SWT.DIALOG_TRIM | SWT.APPLICATION_MODAL);
		aShell.setText(lng("Inpaint.Title"));
		aShell.setLayout(new GridLayout(1, false));
		tde.gui.applyColorFont(aShell);
		aShell.setImage(tde.gui.imgsSwt.getIcon("3Denlive32.png"));
		Label aStatus = addLabel(aShell, lng("DepthConvert.Starting"));
		aStatus.setForeground(tde.gui.colorsSwt.VIOLET);
		ProgressBar aBar = new ProgressBar(aShell, SWT.SMOOTH | SWT.HORIZONTAL);
		aBar.setMinimum(0);
		aBar.setMaximum(1000);
		GridData aPGD = new GridData(GridData.FILL_HORIZONTAL);
		aPGD.widthHint = 480;
		aBar.setLayoutData(aPGD);
		Button aCancel = new Button(aShell, SWT.PUSH);
		aCancel.setText(lng("Inpaint.Cancel"));
		aCancel.setImage(tde.gui.imgsSwt.getIcon("stop.gif"));
		tde.gui.applyColorFont(aCancel);
		aCancel.setLayoutData(new GridData(GridData.HORIZONTAL_ALIGN_END));
		aCancel.addSelectionListener(new SelectionAdapter() {
			@Override
			public void widgetSelected(SelectionEvent e) {
				canceled = true;
				aStatus.setText(lng("DepthConvert.Canceling"));
			}
		});
		aShell.addListener(SWT.Close, e -> canceled = true);
		center(aShell);
		aShell.open();
		Thread aTh = new Thread(() -> {
			try {
				aOk[0] = Inpainter.download(tde.tdeDir, progress(aShell, aStatus, aBar, "DepthConvert.DownloadingRuntime")
						, progress(aShell, aStatus, aBar, "Inpaint.Downloading"));
			}
			catch(Throwable t) {
				t.printStackTrace(System.err);
				aErr[0] = t.getMessage() == null ? t.toString() : t.getMessage();
			}
			tde.gui.display.asyncExec(() -> {
				if(!aShell.isDisposed()) {
					aShell.dispose();
				}
			});
		});
		aTh.start();
		while(!aShell.isDisposed()) {
			if(!tde.gui.display.readAndDispatch()) {
				tde.gui.display.sleep();
			}
		}
		try {
			aTh.join();
		}
		catch(Throwable t) {
		}
		if(aErr[0] != null) {
			message(SWT.ICON_ERROR, lng("Inpaint.Error")+"\n"+aErr[0]);
			return false;
		}
		return aOk[0] && !canceled && Inpainter.isReady(tde.tdeDir);
	}

	Downloader.Progress progress(Shell aShell,Label aStatus,ProgressBar aBar,String aKey) {
		return new Downloader.Progress() {
			@Override
			public void downloaded(long aDone, long aTotal) {
				tde.gui.display.asyncExec(() -> {
					if(aShell.isDisposed() || canceled) {
						return;
					}
					aStatus.setText(lng(aKey).replace("%MODEL%", Inpainter.ENGINE.label)+" "+DepthModel.mb(aDone)+" / "+DepthModel.mb(aTotal)+" MB");
					aBar.setSelection(aTotal > 0 ? (int)Math.min(1000, 1000*aDone/aTotal) : 0);
				});
			}
			@Override
			public boolean isCanceled() {
				return canceled;
			}
		};
	}
}
