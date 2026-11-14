package com.cubaix.TDenlive.GUI;

import org.eclipse.swt.SWT;
import org.eclipse.swt.events.SelectionAdapter;
import org.eclipse.swt.events.SelectionEvent;
import org.eclipse.swt.graphics.Rectangle;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.MessageBox;
import org.eclipse.swt.widgets.ProgressBar;
import org.eclipse.swt.widgets.Shell;

import com.cubaix.TDenlive.TDenlive;
import com.cubaix.TDenlive.depth.ComputedDepthmap;
import com.cubaix.TDenlive.depth.DepthConverter;
import com.cubaix.TDenlive.depth.DepthEstimator;
import com.cubaix.TDenlive.depth.DepthModel;
import com.cubaix.TDenlive.medias.Clip;
import com.cubaix.TDenlive.medias.StereoImage;
import com.cubaix.TDenlive.medias.StereoMovie;
import com.cubaix.TDenlive.processors.Depthmap;
import com.cubaix.TDenlive.processors.DepthmapStereo;
import com.cubaix.TDenlive.processors.Processor;

/**
 * Computes the depth maps of a clip for the Depthmap / DepthmapStereo processors (see depth.DepthConverter):
 * proposed when such a processor is added to a clip without depth map, or from the button of the processor.
 * The user chooses the model (download size, licence, CPU / GPU), the conversion runs in background
 * with a progress window. The depth map files are written next to the media (<name>_3eDepthMap), then given
 * to the processor (ComputedDepthmap), associated with the clip, not in the media list. Files already there
 * for the media are used without computing when the processor is added.
 * - Depthmap: the depth map of the left view;
 * - DepthmapStereo: one depth map per view, used by the keys without depth map of their own.
 * White is front (Black is front unchecked).
 */
public class DepthmapConvertGUI {
	//Model chosen last (default of the next choice)
	static DepthModel lastModel = DepthModel.SMALL;
	//One conversion at a time
	static boolean running = false;

	TDenlive tde;
	Clip clip;
	Processor proc;
	DepthModel model = null;
	Shell shell = null;
	Label statusL = null;
	Label etaL = null;
	Label deviceL = null;
	DepthConverter converter = null;
	ProgressBar bar = null;
	volatile boolean canceled = false;
	long computeStartMS = -1;
	long computeStartDone = 0;
	long lastUpdateMS = 0;
	boolean force = false;

	DepthmapConvertGUI(TDenlive aTDe,Clip aClip,Processor aP) {
		tde = aTDe;
		clip = aClip;
		proc = aP;
	}

	/**
	 * After the processor aP was added to aClip: if it is a depth map processor and the clip
	 * has no depth map, propose to compute it
	 */
	public static void checkNewProcessor(TDenlive aTDe,Clip aClip,Processor aP) {
		if(!(aP instanceof Depthmap || aP instanceof DepthmapStereo)
				|| !DepthConverter.isConvertible(aClip.getMedia())) {
			return;
		}
		//Depth maps already computed for this media (files next to it): used as they are
		DepthmapConvertGUI aGUI = new DepthmapConvertGUI(aTDe, aClip, aP);
		if(aGUI.attach()) {
			return;
		}
		if(running || !DepthEstimator.isPlatformSupported()) {
			return;
		}
		if(aP instanceof Depthmap && (((Depthmap)aP).computed != null || DepthConverter.hasDepthmap((StereoImage)aClip.getMedia()))) {
			return;
		}
		MessageBox aMB = new MessageBox(aTDe.gui.shell, SWT.ICON_QUESTION|SWT.YES|SWT.NO);
		aMB.setText(aTDe.gui.lngs.get("DepthConvert.Title"));
		aMB.setMessage(aTDe.gui.lngs.get(aP instanceof Depthmap ? "DepthConvert.Ask2D" : "DepthConvert.AskStereo"));
		if(aMB.open() != SWT.YES) {
			return;
		}
		convert(aTDe, aClip, aP, false);
	}

	/**
	 * Choose the model and compute the depth maps of the clip for the processor
	 * (aForce: computed again even if the files exist, e.g. with another model)
	 */
	public static void convert(TDenlive aTDe,Clip aClip,Processor aP,boolean aForce) {
		if(running) {
			message(aTDe, SWT.ICON_INFORMATION, aTDe.gui.lngs.get("DepthConvert.Running"));
			return;
		}
		if(!DepthEstimator.isPlatformSupported()) {
			message(aTDe, SWT.ICON_INFORMATION, aTDe.gui.lngs.get("DepthConvert.Unsupported"));
			return;
		}
		if(!DepthConverter.isConvertible(aClip.getMedia())) {
			message(aTDe, SWT.ICON_INFORMATION, aTDe.gui.lngs.get("DepthConvert.NotConvertible"));
			return;
		}
		DepthmapConvertGUI aGUI = new DepthmapConvertGUI(aTDe, aClip, aP);
		aGUI.force = aForce;
		aGUI.model = aGUI.chooseModel();
		if(aGUI.model == null) {
			return;
		}
		lastModel = aGUI.model;
		aGUI.start();
	}

	static void message(TDenlive aTDe,int aIcon,String aText) {
		MessageBox aMB = new MessageBox(aTDe.gui.shell, aIcon|SWT.OK);
		aMB.setText(aTDe.gui.lngs.get("DepthConvert.Title"));
		aMB.setMessage(aText);
		aMB.open();
	}

	String lng(String aKey) {
		return tde.gui.lngs.get(aKey);
	}

	Label addLabel(Composite aParent,String aText,int aIndent) {
		Label aL = new Label(aParent, SWT.WRAP);
		tde.gui.applyColorFont(aL);
		aL.setText(aText);
		GridData aGD = new GridData(GridData.FILL_HORIZONTAL);
		aGD.horizontalIndent = aIndent;
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
	 * Model choice: size to download, licence, CPU / GPU
	 * @return null if canceled
	 */
	DepthModel chooseModel() {
		final DepthModel[] aResult = {null};
		final DepthModel[] aChoice = {lastModel};
		Shell aShell = new Shell(tde.gui.shell, SWT.DIALOG_TRIM | SWT.APPLICATION_MODAL);
		aShell.setText(lng("DepthConvert.Title"));
		aShell.setLayout(new GridLayout(1, false));
		tde.gui.applyColorFont(aShell);
		aShell.setImage(tde.gui.imgsSwt.getIcon("3Denlive32.png"));

		boolean aIsVideo = clip.getMedia() instanceof StereoMovie;
		//The AI model kept loaded is unloaded before the depth maps: its memory counts as free
		long aGpuFree = com.cubaix.TDenlive.ai.PythonAI.availableGpuMemory();
		addLabel(aShell, lng("DepthConvert.Intro"), 0);
		for(DepthModel aM : DepthModel.MODELS) {
			Button aRadio = new Button(aShell, SWT.RADIO);
			tde.gui.applyColorFont(aRadio);
			aRadio.setFont(tde.gui.fontsSwt.robotoBold);
			aRadio.setText("Depth Anything V2 "+aM.id);
			GridData aRGD = new GridData(GridData.FILL_HORIZONTAL);
			aRGD.verticalIndent = 8;
			aRadio.setLayoutData(aRGD);
			aRadio.setSelection(aM == aChoice[0]);
			aRadio.addSelectionListener(new SelectionAdapter() {
				@Override
				public void widgetSelected(SelectionEvent e) {
					if(aRadio.getSelection()) {
						aChoice[0] = aM;
					}
				}
			});
			String aSize = DepthModel.mb(aM.size);
			addLabel(aShell, aM.isDownloaded(tde.tdeDir)
					? lng("DepthConvert.Downloaded").replace("%MB%", aSize)
					: lng("DepthConvert.Download").replace("%MB%", aSize), 20);
			addLabel(aShell, lng(aM.isCommercial ? "DepthConvert.LicenceFree" : "DepthConvert.LicenceNonCommercial"), 20);
			addLabel(aShell, lng("DepthConvert.Gpu"+aM.id).replace("%GB%", DepthModel.gb(aM.gpuMemory)), 20);
			if(aGpuFree >= 0 && aGpuFree < aM.gpuMemory) {
				addLabel(aShell, lng("DepthConvert.WillCpu"), 20);
			}
		}
		Label aSep = new Label(aShell, SWT.SEPARATOR | SWT.HORIZONTAL);
		GridData aSGD = new GridData(GridData.FILL_HORIZONTAL);
		aSGD.verticalIndent = 8;
		aSep.setLayoutData(aSGD);
		if(!DepthEstimator.isOrtDownloaded(tde.tdeDir)) {
			addLabel(aShell, lng("DepthConvert.Runtime").replace("%MB%", DepthModel.mb(DepthEstimator.ortSize())), 0);
		}
		if(DepthEstimator.useGpuRuntime()) {
			addLabel(aShell, lng("DepthConvert.NvidiaNote"), 0);
		}
		addLabel(aShell, aGpuFree >= 0
				? lng("DepthConvert.GpuFree").replace("%GB%", DepthModel.gb(aGpuFree))
				: lng("DepthConvert.GpuFreeUnknown"), 0);
		addLabel(aShell, lng("DepthConvert.Cache"), 0);
		if(force && ComputedDepthmap.find(tde, (StereoImage)clip.getMedia()) != null) {
			addLabel(aShell, lng("DepthConvert.Replace"), 0);
		}
		if(aIsVideo) {
			addLabel(aShell, lng("DepthConvert.VideoNote"), 0);
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
		aCancel.setText(lng("DepthConvert.Cancel"));
		tde.gui.applyColorFont(aCancel);
		aCancel.setLayoutData(new GridData(GridData.FILL_HORIZONTAL));
		aCancel.addSelectionListener(new SelectionAdapter() {
			@Override
			public void widgetSelected(SelectionEvent e) {
				aShell.close();
			}
		});
		Button aOk = new Button(aButtons, SWT.PUSH);
		aOk.setText(lng("DepthConvert.Convert"));
		aOk.setImage(tde.gui.imgsSwt.getIcon("checked.gif"));
		tde.gui.applyColorFont(aOk);
		aOk.setLayoutData(new GridData(GridData.FILL_HORIZONTAL));
		aOk.addSelectionListener(new SelectionAdapter() {
			@Override
			public void widgetSelected(SelectionEvent e) {
				aResult[0] = aChoice[0];
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
		return aResult[0];
	}

	/**
	 * Progress window and conversion thread
	 */
	void start() {
		running = true;
		shell = new Shell(tde.gui.shell, SWT.DIALOG_TRIM);
		shell.setText(lng("DepthConvert.Title")+" - "+clip.fileName);
		shell.setLayout(new GridLayout(1, false));
		tde.gui.applyColorFont(shell);
		shell.setImage(tde.gui.imgsSwt.getIcon("3Denlive32.png"));
		statusL = addLabel(shell, lng("DepthConvert.Starting"), 0);
		bar = new ProgressBar(shell, SWT.SMOOTH | SWT.HORIZONTAL);
		bar.setMinimum(0);
		bar.setMaximum(1000);
		GridData aPGD = new GridData(GridData.FILL_HORIZONTAL);
		aPGD.widthHint = 480;
		bar.setLayoutData(aPGD);
		etaL = addLabel(shell, " ", 0);
		deviceL = addLabel(shell, " ", 0);
		Button aCancel = new Button(shell, SWT.PUSH);
		aCancel.setText(lng("DepthConvert.Cancel"));
		aCancel.setImage(tde.gui.imgsSwt.getIcon("stop.gif"));
		tde.gui.applyColorFont(aCancel);
		aCancel.setLayoutData(new GridData(GridData.HORIZONTAL_ALIGN_END));
		aCancel.addSelectionListener(new SelectionAdapter() {
			@Override
			public void widgetSelected(SelectionEvent e) {
				canceled = true;
				statusL.setText(lng("DepthConvert.Canceling"));
			}
		});
		//Closing the window cancels
		shell.addListener(SWT.Close, e -> canceled = true);
		center(shell);
		shell.open();

		StereoImage aSI = (StereoImage)clip.getMedia();
		String aWorkDir = tde.getWorkCacheDir();
		Thread aTh = new Thread(new Runnable() {
			@Override
			public void run() {
				String aError = null;
				String[] aResult = null;
				try {
					DepthConverter aDC = converter = new DepthConverter(tde.tdeDir, aWorkDir, model, new DepthConverter.Progress() {
						@Override
						public void status(String aKey, long aDone, long aTotal) {
							showStatus(aKey, aDone, aTotal);
						}
						@Override
						public boolean isCanceled() {
							return canceled;
						}
					});
					String[] aPaths = aDC.convert(aSI, force);
					if(aPaths != null && !canceled) {
						//Loaded here (videos: first frames, work copies), then given to the clip in the GUI thread
						showStatus("DepthConvert.Loading", 0, 0);
						ComputedDepthmap.loadMedia(tde, aPaths, aSI.origStereo);
						aResult = aPaths;
					}
				}
				catch(Throwable t) {
					t.printStackTrace(System.err);
					aError = t.getMessage() == null ? t.toString() : t.getMessage();
				}
				final String aErr = aError;
				final String[] aPaths = aResult;
				tde.gui.display.asyncExec(new Runnable() {
					@Override
					public void run() {
						running = false;
						if(!shell.isDisposed()) {
							shell.dispose();
						}
						if(aErr != null) {
							message(tde, SWT.ICON_ERROR, lng("DepthConvert.Error")+"\n"+aErr);
							return;
						}
						if(aPaths == null || canceled) {
							return;
						}
						if(!attach()) {
							message(tde, SWT.ICON_ERROR, lng("DepthConvert.Error"));
						}
					}
				});
			}
		});
		aTh.start();
	}

	void showStatus(String aKey,long aDone,long aTotal) {
		long aNow = System.currentTimeMillis();
		boolean aComputing = "DepthConvert.Computing".equals(aKey);
		if(aComputing && computeStartMS < 0) {
			computeStartMS = aNow;
			computeStartDone = aDone;
		}
		//Not too many GUI updates (one per video frame...)
		if(aNow-lastUpdateMS < 200 && aDone > 0 && aDone < aTotal) {
			return;
		}
		lastUpdateMS = aNow;
		String aText = lng(aKey).replace("%MODEL%", model.id);
		String aEta = " ";
		int aBar = 0;
		if(aKey.startsWith("DepthConvert.Downloading")) {
			aText += " "+DepthModel.mb(aDone)+" / "+DepthModel.mb(aTotal)+" MB";
		}
		else if(aComputing && aTotal > 0) {
			aText += " "+(aDone+1)+" / "+aTotal;
			long aDoneHere = aDone-computeStartDone;
			if(aDoneHere > 0) {
				long aLeftS = (aNow-computeStartMS)*(aTotal-aDone)/aDoneHere/1000;
				aEta = lng("DepthConvert.Remaining")+" "+(aLeftS >= 60 ? (aLeftS/60)+" min " : "")+(aLeftS%60)+" s";
			}
		}
		if(aTotal > 0) {
			aBar = (int)Math.min(1000, 1000*aDone/aTotal);
		}
		final String aT = aText;
		final String aE = aEta;
		final String aD = deviceText();
		final int aB = aBar;
		tde.gui.display.asyncExec(new Runnable() {
			@Override
			public void run() {
				if(shell == null || shell.isDisposed() || canceled) {
					return;
				}
				statusL.setText(aT);
				etaL.setText(aE);
				deviceL.setText(aD);
				bar.setSelection(aB);
			}
		});
	}

	/**
	 * Where the model runs, and why on the CPU
	 */
	String deviceText() {
		if(converter == null || converter.provider == null) {
			return " ";
		}
		if(converter.cpuReason == null) {
			//Not the provider name: "WebGPU" could suggest that data leaves the computer
			return lng("DepthConvert.OnGpu");
		}
		if(DepthEstimator.CPU_REASON_GPU_MEMORY.equals(converter.cpuReason)) {
			return lng("DepthConvert.OnCpuMemory")
					.replace("%FREE%", DepthModel.gb(converter.gpuFree))
					.replace("%GB%", DepthModel.gb(model.gpuMemory));
		}
		if(DepthEstimator.CPU_REASON_CUDA.equals(converter.cpuReason)) {
			return lng("DepthConvert.OnCpuCuda");
		}
		return lng(DepthEstimator.CPU_REASON_GPU_ERROR.equals(converter.cpuReason) ? "DepthConvert.OnCpuError" : "DepthConvert.OnCpuNoGpu");
	}

	/**
	 * Give the depth map files of the clip media to the processor (GUI thread)
	 * @return false if there are none
	 */
	boolean attach() {
		if(!clip.getProcessors().contains(proc)) {
			//Processor removed meanwhile: the files stay next to the media for a next time
			return true;
		}
		try {
			ComputedDepthmap aCD = ComputedDepthmap.attach(tde, clip);
			if(aCD == null) {
				return false;
			}
			if(proc instanceof Depthmap) {
				Depthmap aP = (Depthmap)proc;
				aP.setComputed(aCD);
				for(int k = 0;k < aP.getNbKeys();k++) {
					aP.setBlackIsFront(k, false);
				}
			}
			else {
				DepthmapStereo aP = (DepthmapStereo)proc;
				aP.setComputed(aCD);
				//Used by the keys without depth map of their own
				for(int k = 0;k < aP.getNbKeys();k++) {
					if(aP.getDepthmapClipMedia(k) == null) {
						aP.setBlackIsFront(k, false);
					}
				}
			}
		}
		catch(Throwable t) {
			t.printStackTrace(System.err);
			message(tde, SWT.ICON_ERROR, lng("DepthConvert.Error")+"\n"+t);
			return true;
		}
		if(tde.selected.clips.size() > 0 && tde.selected.clips.lastElement() == clip) {
			tde.gui.processorGUI.setProcessors(clip.getProcessors());
		}
		tde.gui.timeLinesGUI.redraw();
		tde.gui.monitorGUI.redraw();
		return true;
	}
}
