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
	/**
	 * Status of the computed depth maps of a clip, right of the compute button (violet, as all the statuses of the
	 * processor panels): computed, partly computed (interrupted video, with its part done), or not computed (aOwn:
	 * what is used then). Polled every 2 s while shown (a computation ends, a resume).
	 */
	public static Label addState(TDenlive aTDe,Composite aRow,java.util.function.Supplier<ComputedDepthmap> aComputed,Clip aClip,String aOwnKey) {
		final Label aL = new Label(aRow, SWT.WRAP);
		aTDe.gui.applyColorFont(aL);
		aL.setForeground(aTDe.gui.colorsSwt.VIOLET);
		GridData aGD = new GridData(GridData.FILL_HORIZONTAL | GridData.VERTICAL_ALIGN_CENTER);
		aGD.widthHint = 100;
		aL.setLayoutData(aGD);
		Runnable[] aTick = {null};
		aTick[0] = () -> {
			if(aL.isDisposed()) {
				return;
			}
			String aText;
			try {
				StereoImage aSI = aClip != null && aClip.getMedia() instanceof StereoImage ? (StereoImage)aClip.getMedia() : null;
				ComputedDepthmap aCD = aComputed.get();
				if(aSI != null && ComputedDepthmap.partialModel(aTDe, aSI) != null) {
					double aF = ComputedDepthmap.partialFraction(aTDe, aSI);
					aText = aTDe.gui.lngs.get("DepthConvert.StatePartial").replace("%P%", aF < 0 ? "?" : ""+(int)Math.floor(aF*100));
				}
				else if(aCD != null) {
					aText = aTDe.gui.lngs.get("DepthConvert.StateDone");
				}
				else {
					aText = aTDe.gui.lngs.get(aOwnKey);
				}
			}
			catch(Throwable t) {
				t.printStackTrace(System.err);
				aText = "";
			}
			if(!aText.equals(aL.getText())) {
				aL.setText(aText);
				aL.getParent().layout(true);
			}
			aTDe.gui.display.timerExec(2000, aTick[0]);
		};
		aTick[0].run();
		return aL;
	}

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
	//Step without a counter (end of the encoding, opening of the result): its text and start, an elapsed time
	//shown every second (the window is not frozen)
	volatile String phaseText = null;
	volatile long phaseStartMS = 0;
	boolean phaseTimer = false;
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

	/**
	 * Resume the interrupted computation of the depth maps of the clip (same model, from the last frame done)
	 */
	public static void resume(TDenlive aTDe,Clip aClip,Processor aP) {
		if(running) {
			message(aTDe, SWT.ICON_INFORMATION, aTDe.gui.lngs.get("DepthConvert.Running"));
			return;
		}
		if(!DepthEstimator.isPlatformSupported() || !(aClip.getMedia() instanceof StereoImage)) {
			return;
		}
		DepthmapConvertGUI aGUI = new DepthmapConvertGUI(aTDe, aClip, aP);
		String aId = ComputedDepthmap.partialModel(aTDe, (StereoImage)aClip.getMedia());
		aGUI.model = aId == null || aId.isEmpty() ? null : DepthModel.byId(aId);
		if(aGUI.model == null) {
			aGUI.model = aGUI.chooseModel();
			if(aGUI.model == null) {
				return;
			}
		}
		lastModel = aGUI.model;
		aGUI.start();
	}

	/**
	 * A clip whose depth map computation was interrupted and that has no depth map yet: the part computed given to its
	 * processor (shown and used meanwhile; interruptions made before the partial maps were not noted in the project)
	 */
	public static void attachPartial(TDenlive aTDe,Clip aClip,Processor aP) {
		try {
			if(!(aClip.getMedia() instanceof StereoImage)) {
				return;
			}
			ComputedDepthmap aHas = aP instanceof Depthmap ? ((Depthmap)aP).computed
					: (aP instanceof DepthmapStereo ? ((DepthmapStereo)aP).computed : null);
			if(aHas != null || ComputedDepthmap.partialModel(aTDe, (StereoImage)aClip.getMedia()) == null) {
				return;
			}
			boolean aOk = new DepthmapConvertGUI(aTDe, aClip, aP).attach();
			System.out.println("Depth maps: part computed of "+aClip.getMedia().displayName()+(aOk ? " given to the clip" : " not found"));
		}
		catch(Throwable t) {
			t.printStackTrace(System.err);
		}
	}

	//Clips whose interrupted depth maps are resumed one after the other (offerResume())
	static final java.util.Vector<Object[]> RESUME_QUEUE = new java.util.Vector<Object[]>();

	/**
	 * After the opening of a project (GUI thread): the depth maps whose computation was interrupted, resumed
	 * now (one after the other) or later (button "Resume the computation" of the processor)
	 */
	public static void offerResume(TDenlive aTDe) {
		try {
			if(!DepthEstimator.isPlatformSupported()) {
				return;
			}
			java.util.Vector<Object[]> aTodo = new java.util.Vector<Object[]>();
			java.util.HashSet<Long> aMedias = new java.util.HashSet<Long>();
			for(com.cubaix.TDenlive.medias.TimeLineStack aTLS : aTDe.allStacks()) {
				for(com.cubaix.TDenlive.medias.VideoTimeLine aTL : aTLS.videoTimeLineStack) {
					for(Clip aC : aTL.clipList) {
						Processor aP = aC.getDepthProcessor();
						if(!(aP instanceof Depthmap || aP instanceof DepthmapStereo) || !(aC.getMedia() instanceof StereoImage)) {
							continue;
						}
						if(aMedias.contains(aC.getMedia().UID)) {
							attachPartial(aTDe, aC, aP);
							continue;
						}
						if(ComputedDepthmap.partialModel(aTDe, (StereoImage)aC.getMedia()) != null) {
							attachPartial(aTDe, aC, aP);
							aMedias.add(aC.getMedia().UID);
							aTodo.add(new Object[] {aC, aP});
						}
					}
				}
			}
			System.out.println("Depth maps: "+aTodo.size()+" media(s) with an interrupted computation");
			if(aTodo.isEmpty()) {
				return;
			}
			StringBuilder aList = new StringBuilder();
			for(Object[] aT : aTodo) {
				Clip aC = (Clip)aT[0];
				int aPct = (int)Math.round(100*Math.max(0, ComputedDepthmap.partialFraction(aTDe, (StereoImage)aC.getMedia())));
				aList.append("\n- ").append(aC.getMedia().displayName()).append(" (").append(aPct).append(" %)");
			}
			MessageBox aMB = new MessageBox(aTDe.gui.shell, SWT.ICON_QUESTION | SWT.YES | SWT.NO);
			aMB.setText(aTDe.gui.lngs.get("DepthConvert.Title"));
			aMB.setMessage(aTDe.gui.lngs.get("DepthConvert.OfferResume").replace("%N%", ""+aTodo.size()).replace("%LIST%", aList.toString()));
			java.util.HashMap<Integer,String> aLabels = new java.util.HashMap<Integer,String>();
			aLabels.put(SWT.YES, aTDe.gui.lngs.get("DepthConvert.Resume"));
			aLabels.put(SWT.NO, aTDe.gui.lngs.get("Inpaint.Later"));
			aMB.setButtonLabels(aLabels);
			if(aMB.open() != SWT.YES) {
				return;
			}
			RESUME_QUEUE.clear();
			RESUME_QUEUE.addAll(aTodo);
			resumeNext(aTDe);
		}
		catch(Throwable t) {
			t.printStackTrace(System.err);
		}
	}

	static void resumeNext(TDenlive aTDe) {
		if(RESUME_QUEUE.isEmpty() || running) {
			return;
		}
		Object[] aT = RESUME_QUEUE.remove(0);
		resume(aTDe, (Clip)aT[0], (Processor)aT[1]);
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
		//A video: the frames done are kept, resumed later
		aCancel.setText(lng(clip.getMedia() instanceof StereoMovie ? "DepthConvert.Interrupt" : "DepthConvert.Cancel"));
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
		//The depth maps under construction in the monitor (not over a rendering)
		if(tde.gui.monitorGUI != null && !tde.gui.monitorGUI.isRendering()) {
			tde.gui.monitorGUI.setRendering(true, this);
		}

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
						@Override
						public void frame(java.awt.image.BufferedImage aL,java.awt.image.BufferedImage aR,double aMediaMS) {
							showFrame(aL, aR, aMediaMS);
						}
					});
					String[] aPaths = aDC.convert(aSI, force);
					if(aPaths != null && !canceled) {
						//Loaded here (videos: first frames, work copies), then given to the clip in the GUI thread
						showStatus("DepthConvert.Loading", 0, 0);
						ComputedDepthmap.loadMedia(tde, aPaths, ComputedDepthmap.layoutOf(aSI));
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
						if(tde.gui.monitorGUI != null) {
							tde.gui.monitorGUI.releaseRendering(DepthmapConvertGUI.this);
						}
						if(aErr != null) {
							RESUME_QUEUE.clear();
							message(tde, SWT.ICON_ERROR, lng("DepthConvert.Error")+"\n"+aErr);
							return;
						}
						if(aPaths == null || canceled) {
							RESUME_QUEUE.clear();
							if(canceled && converter != null && converter.partDone >= 0) {
								//Interrupted: the frames done are kept
								//The part computed: shown and used meanwhile
								attach();
								tde.gui.monitorGUI.redraw();
								message(tde, SWT.ICON_INFORMATION, lng("DepthConvert.Interrupted")
										.replace("%P%", ""+(int)Math.round(100*converter.partDone)));
								refreshPanel();
							}
							return;
						}
						if(!attach()) {
							message(tde, SWT.ICON_ERROR, lng("DepthConvert.Error"));
						}
						resumeNext(tde);
					}
				});
			}
		});
		aTh.start();
	}

	/**
	 * The processors of the clip shown again (button "Resume the computation"), if it is the one shown
	 */
	void refreshPanel() {
		if(tde.gui.processorGUI != null && !tde.selected.clips.isEmpty() && tde.selected.clips.lastElement() == clip) {
			tde.gui.processorGUI.setProcessors(clip.getProcessors());
		}
	}

	long lastCursorMS = 0;

	/**
	 * A depth map just computed: in the monitor, the time cursor on its image of the clip (any thread)
	 */
	void showFrame(java.awt.image.BufferedImage aL,java.awt.image.BufferedImage aR,double aMediaMS) {
		if(tde.gui.monitorGUI == null || canceled) {
			return;
		}
		tde.gui.monitorGUI.showBuilding(this, aL, aR);
		long aNow = System.currentTimeMillis();
		if(aMediaMS < 0 || aNow-lastCursorMS < 100) {
			return;
		}
		lastCursorMS = aNow;
		tde.gui.display.asyncExec(() -> {
			if(!tde.gui.monitorGUI.isShownBy(this)) {
				return;
			}
			long aT = clip.timeForMediaMS(aMediaMS);
			if(aT < clip.getStartTimeMS() || aT >= clip.getStartTimeMS()+clip.getDurationMS()) {
				return;
			}
			tde.timeLineStack.setRequestedTimePosMS(aT);
			tde.gui.timeLinesGUI.redraw();
			if(tde.gui.monitorRulerGUI != null) {
				tde.gui.monitorRulerGUI.redraw();
			}
		});
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
		if(aTotal <= 0 && !aKey.startsWith("DepthConvert.Downloading")) {
			if(!aText.equals(phaseText)) {
				phaseText = aText;
				phaseStartMS = aNow;
			}
		}
		else {
			phaseText = null;
		}
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
				tickPhase();
			}
		});
	}

	/**
	 * Elapsed time of a step without a counter, every second (GUI thread)
	 */
	void tickPhase() {
		if(phaseTimer || shell == null || shell.isDisposed()) {
			return;
		}
		String aP = phaseText;
		if(aP == null) {
			return;
		}
		long aS = (System.currentTimeMillis()-phaseStartMS)/1000;
		if(aS >= 1) {
			statusL.setText(aP+" ("+lng("DepthConvert.Elapsed").replace("%S%", (aS >= 60 ? (aS/60)+" min " : "")+(aS%60)+" s")+")");
		}
		phaseTimer = true;
		tde.gui.display.timerExec(1000, () -> {
			phaseTimer = false;
			tickPhase();
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
