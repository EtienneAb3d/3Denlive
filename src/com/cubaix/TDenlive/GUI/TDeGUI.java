package com.cubaix.TDenlive.GUI;

import java.awt.Graphics;
import java.awt.image.BufferedImage;
import java.io.File;
import java.text.NumberFormat;
import java.util.Vector;

import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.CLabel;
import org.eclipse.swt.custom.SashForm;
import org.eclipse.swt.events.MouseEvent;
import org.eclipse.swt.events.MouseListener;
import org.eclipse.swt.graphics.Cursor;
import org.eclipse.swt.graphics.Color;
import org.eclipse.swt.graphics.GC;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.layout.FillLayout;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.FileDialog;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.MenuItem;
import org.eclipse.swt.widgets.MessageBox;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Text;

import com.cubaix.TDenlive.TDConfig;
import com.cubaix.TDenlive.TDenlive;
import com.cubaix.TDenlive.utils.ProjectFiles;
import com.cubaix.TDenlive.medias.Clip;
import com.cubaix.TDenlive.medias.Media;
import com.cubaix.TDenlive.medias.VideoTimeLine;
import com.cubaix.TDenlive.processors.Processor;
import com.cubaix.TDenlive.res.FontsAwt;
import com.cubaix.TDenlive.res.FontsSwt;
import com.cubaix.TDenlive.res.ImgsAwt;
import com.cubaix.TDenlive.res.ImgsSwt;
import com.cubaix.TDenlive.res.Lngs;
import com.cubaix.TDenlive.utils.BrowserControl;
import com.cubaix.TDenlive.utils.Frames;
import com.cubaix.TDenlive.utils.HtmlViewer;
import com.cubaix.TDenlive.utils.ImageUtils;
import com.cubaix.TDenlive.utils.Lumas;

public class TDeGUI {
	static public final int MULTIMONITORPANEL_BROWSER = 0;
	static public final int MULTIMONITORPANEL_MONITOR = 1;
	static public final int MULTIMONITORPANEL_SETTINGS = 2;

	static public final int MULTIPROPSPANEL_PROPS = 0;
	static public final int MULTIPROPSPANEL_PROCESSORS = 1;
	//Properties of the active montage (name, rendering, inclusions)
	static public final int MULTIPROPSPANEL_MONTAGE = 2;
	//Properties of a time line (click on its head)
	static public final int MULTIPROPSPANEL_LINE = 3;
	
	TDenlive tde = null;
	
	public Display display = null;
	public ColorsSwt colorsSwt = null;
	public ColorsAwt colorsAwt = null;
	public FontsSwt fontsSwt = null;
	public FontsAwt fontsAwt = null;
	public ImgsSwt imgsSwt = null;
	public ImgsAwt imgsAwt = null;
	public Lngs lngs = null;
	public Lumas lumas = null;
	public Frames frames = null;
	
	public Shell shell = null;
	
	public Composite toolBar = null;	
	public MediaListControlGUI mediaListControlGUI = null;
	public MediaListGUI mediaListGUI = null;
	//Library of the montages, below the media list
	public MontageListGUI montageListGUI = null;
	
	public CompositePanel multiMonitorPanel = null;
	public WebBrowserGUI browserGUI = null;
	public MonitorControlGUI monitorControlGUI = null;
	public MonitorGUI monitorGUI = null;
	public MonitorRulerGUI monitorRulerGUI = null;
	public PlayerControlGUI playerControlGUI = null;
	
	public CompositePanel multiPropsPanel = null;
	public MediaPropsGUI propsGUI = null;
	public ProcessorsGUI processorGUI = null;
	public MontagePropsGUI montagePropsGUI = null;
	public LinePropsGUI linePropsGUI = null;
	
	public ProcessorLibGUI processorLibGUI = null;
	
	public TimeLineStackGUI timeLinesGUI = null;
	
	//Main panels, horizontal (lists, monitor, properties) and vertical (top, timelines)
	SashForm sashFormH = null;
	SashForm sashFormV = null;
	//Left column: media list (top), montages (bottom)
	SashForm sashFormLeft = null;
	
	Cursor waitCursor = null;

	//Undo / redo of the changes of the montage
	public History history = null;
	
	public TDeGUI(TDenlive aTDe) {
		tde = aTDe;
		tde.gui = this;
		createGUI();
	}
	
	void openProject() {
		//The project in progress first
		if(!confirmSave("Generic.SaveBeforeOpen")) {
			return;
		}
		FileDialog aFD = new FileDialog (shell, SWT.OPEN);
		String [] filterNames = new String [] {"3De projects"};
		String [] filterExtensions = new String [] {"*.3De"};
		String filterPath = tde.lastDir == null ? tde.tdeDir:tde.lastDir;
		aFD.setFilterNames (filterNames);
		aFD.setFilterExtensions (filterExtensions);
		aFD.setFilterPath (filterPath);
		String aPath = aFD.open ();
		System.out.println ("Path: " + aPath);
		if(aPath == null) {
			return;
		}
		openProject(aPath);
	}

	//Opens a project in a background thread
	public void openProject(String aPath) {
		tde.projectDir = tde.lastDir = new File(aPath).getAbsoluteFile().getParent();
		tde.projectName = new File(aPath).getName();
		updateTitle();

		setWaitCursor(true);
		//Nothing recorded while opening: the project opened is the first state of the history
		history.loading = true;
		//No precomputation of the hole filling before the user chose (InpaintGUI.afterOpen())
		com.cubaix.TDenlive.processors.Inpainting.Precomputer.held = true;
		Thread aTh = new Thread(new Runnable() {
			@Override
			public void run() {
				try {
					tde.openProject(aPath);
				}
				finally {
					display.syncExec(() -> history.reset());
					display.asyncExec(() -> {
						InpaintGUI.afterOpen(tde);
						//The depth maps whose computation was interrupted
						DepthmapConvertGUI.offerResume(tde);
						//After its question (modal): the 3D scenes not computed here
						SceneConvertGUI.offerMissing(tde);
					});
				}
				setWaitCursor(false);
			}
		});
		aTh.start();
	}

	static boolean isSameFile(File aF1,File aF2) {
		if(aF1 == null || aF2 == null) {
			return false;
		}
		try {
			return aF1.getCanonicalFile().equals(aF2.getCanonicalFile());
		}
		catch(Throwable t) {
			return aF1.getAbsoluteFile().equals(aF2.getAbsoluteFile());
		}
	}

	/**
	 * Zip the project and its medias into the Packed folder (in the background: the videos can be big)
	 */
	void packProject() {
		setWaitCursor(true);
		Thread aTh = new Thread(new Runnable() {
			@Override
			public void run() {
				java.util.Vector<String> aMissing = new java.util.Vector<String>();
				String aMsg;
				int aIcon = SWT.ICON_INFORMATION;
				try {
					aMsg = lngs.get("Generic.PackDone")+"\n"+tde.packProject(aMissing).getPath();
					if(aMissing.size() > 0) {
						aIcon = SWT.ICON_WARNING;
						aMsg += "\n\n"+lngs.get("Generic.PackMissing")+"\n"+String.join("\n", aMissing);
					}
				}
				catch(Throwable t) {
					t.printStackTrace(System.err);
					aIcon = SWT.ICON_ERROR;
					aMsg = lngs.get("Generic.PackError")+"\n"+t.getMessage();
				}
				setWaitCursor(false);
				final String aFinalMsg = aMsg;
				final int aFinalIcon = aIcon;
				display.asyncExec(() -> {
					if(shell.isDisposed()) {
						return;
					}
					MessageBox aMB = new MessageBox(shell, aFinalIcon|SWT.OK);
					aMB.setMessage(aFinalMsg);
					aMB.open();
				});
			}
		});
		aTh.start();
	}

	/**
	 * Ask to save the project file and save it (with the proposal to gather the medias in the project folder).
	 * Returns false if canceled, or if the saving failed
	 */
	boolean saveProject() {
		return saveProject(true);
	}

	/**
	 * Save panel (SaveProjectGUI: name, folder, new folder for the project), then save.
	 * aOfferGather: when the project folder changes and medias are outside it, propose to move or copy them
	 * into it (not when quitting: done in background, then the project is opened again).
	 * Returns false if canceled, or if the saving failed
	 */
	boolean saveProject(boolean aOfferGather) {
		//Current project file (opened or last saved): replaced without confirmation
		File aCurrentFile = tde.projectDir != null && tde.projectName != null ? new File(tde.projectDir, tde.projectName) : null;
		String aPath = new SaveProjectGUI(tde).open();
		System.out.println ("Path: " + aPath);
		if(aPath == null) {
			return false;
		}
		if(new File(aPath).exists() && !isSameFile(new File(aPath), aCurrentFile)) {
			MessageBox aMB = new MessageBox(shell, SWT.ICON_WARNING|SWT.YES|SWT.NO);
			aMB.setText(lngs.get("Generic.SaveAs"));
			aMB.setMessage(new File(aPath).getName()+"\n"+lngs.get("Generic.OverwriteFile"));
			if(aMB.open() != SWT.YES) {
				return false;
			}
		}
		String aOldDir = aCurrentFile == null ? null : aCurrentFile.getAbsoluteFile().getParent();
		String aOldWorkCacheDir = tde.getWorkCacheDir();
		tde.projectDir = tde.lastDir = new File(aPath).getParent();
		tde.projectName = new File(aPath).getName();
		updateTitle();

		if(!tde.saveProject(aPath)) {
			MessageBox aMB = new MessageBox(shell, SWT.ICON_ERROR|SWT.OK);
			aMB.setText(lngs.get("Generic.Save"));
			aMB.setMessage(lngs.get("Generic.SaveError")+"\n"+aPath);
			aMB.open();
			return false;
		}
		tde.markSaved();
		//The video work copies go with the project
		tde.copyWorkCacheFrom(aOldWorkCacheDir);
		if(aOfferGather && (aOldDir == null || !isSameFile(new File(aOldDir), new File(tde.projectDir)))) {
			offerGather(aPath);
		}
		return true;
	}

	/**
	 * Medias outside the project folder (just saved into a new folder): move them into it, copy them, or leave them.
	 * Done in background, then the project is saved with the new paths and opened again.
	 */
	void offerGather(String aPath) {
		java.util.Vector<String> aOutside = ProjectFiles.outsideMedias(tde);
		if(aOutside.isEmpty()) {
			return;
		}
		MessageBox aMB = new MessageBox(shell, SWT.ICON_QUESTION|SWT.YES|SWT.NO|SWT.CANCEL);
		aMB.setText(lngs.get("SaveProject.Title"));
		String aList = aOutside.size() <= 10 ? String.join("\n", aOutside)
				: String.join("\n", aOutside.subList(0, 10))+"\n...";
		aMB.setMessage(lngs.get("SaveProject.Gather").replace("%N%", ""+aOutside.size()).replace("%DIR%", tde.projectDir)+"\n\n"+aList);
		java.util.HashMap<Integer,String> aLabels = new java.util.HashMap<Integer,String>();
		aLabels.put(SWT.YES, lngs.get("SaveProject.Move"));
		aLabels.put(SWT.NO, lngs.get("SaveProject.Copy"));
		aLabels.put(SWT.CANCEL, lngs.get("SaveProject.Leave"));
		aMB.setButtonLabels(aLabels);
		int aAnswer = aMB.open();
		if(aAnswer != SWT.YES && aAnswer != SWT.NO) {
			return;
		}
		boolean aMove = aAnswer == SWT.YES;
		setWaitCursor(true);
		Thread aTh = new Thread(new Runnable() {
			@Override
			public void run() {
				java.util.Vector<String> aFailed = new java.util.Vector<String>();
				java.util.Vector<String> aCopied = new java.util.Vector<String>();
				String aError = null;
				try {
					String aXml = ProjectFiles.relocate(tde, aMove, aFailed, aCopied);
					java.nio.file.Files.write(new File(aPath).toPath(), aXml.getBytes(java.nio.charset.StandardCharsets.UTF_8));
				}
				catch(Throwable t) {
					t.printStackTrace(System.err);
					aError = t.getMessage() == null ? t.toString() : t.getMessage();
				}
				final String aErr = aError;
				display.asyncExec(() -> {
					setWaitCursor(false);
					if(shell.isDisposed()) {
						return;
					}
					if(aErr == null) {
						//Opened again with the new paths (medias, work copies and sounds renamed with them)
						openProject(aPath);
					}
					String aMsg = aErr != null ? lngs.get("SaveProject.GatherError")+"\n"+aErr : null;
					if(aErr == null && !aFailed.isEmpty()) {
						aMsg = lngs.get("SaveProject.GatherFailed")+"\n"+String.join("\n", aFailed);
					}
					if(aErr == null && !aCopied.isEmpty()) {
						aMsg = (aMsg == null ? "" : aMsg+"\n\n")+lngs.get("SaveProject.GatherCopied")+"\n"+String.join("\n", aCopied);
					}
					if(aMsg != null) {
						MessageBox aR = new MessageBox(shell, SWT.ICON_WARNING|SWT.OK);
						aR.setText(lngs.get("SaveProject.Title"));
						aR.setMessage(aMsg);
						aR.open();
					}
				});
			}
		});
		aTh.start();
	}

	//Set once the quitting is accepted: not asked again by the following close events
	boolean exitConfirmed = false;

	/**
	 * Before quitting: if the montage was modified since its last saving, propose to save it.
	 * Returns false if the user cancels the quitting (or the saving)
	 */
	boolean confirmExit() {
		if(exitConfirmed) {
			return true;
		}
		if(!confirmSave("Generic.SaveBeforeQuit")) {
			return false;
		}
		exitConfirmed = true;
		return true;
	}

	/**
	 * If the project was modified since its last opening or saving, asks (message aMsgKey) to save it first: false
	 * if the user cancels (or the saving fails)
	 */
	public boolean confirmSave(String aMsgKey) {
		if(!tde.isModified()) {
			return true;
		}
		MessageBox aMB = new MessageBox(shell, SWT.ICON_WARNING|SWT.YES|SWT.NO|SWT.CANCEL);
		aMB.setText("3Denlive");
		String aName = tde.projectName == null ? lngs.get("Generic.Untitled") : tde.projectName;
		aMB.setMessage(aName+"\n"+lngs.get(aMsgKey));
		int aAnswer = aMB.open();
		if(aAnswer == SWT.CANCEL) {
			return false;
		}
		return aAnswer != SWT.YES || saveProject(false);
	}

	private void createGUI() {
		display = new Display();
		colorsSwt = new ColorsSwt(tde);
		colorsAwt = new ColorsAwt(tde);
		fontsSwt = new FontsSwt(tde);
		textH = measureTextH();
		fontsAwt = new FontsAwt(tde);
		imgsSwt = new ImgsSwt(tde);
		imgsAwt = new ImgsAwt(tde);
		lngs = new Lngs(tde);
		lumas = new Lumas(tde);
		frames = new Frames(tde);
		history = new History(tde);
		installHoverFrames();
		installDialogsInFront();
				
		shell = new Shell(display,SWT.SHELL_TRIM);
		updateTitle();
		shell.setSize (800, 600);
		shell.setMaximized(true);
		shell.setBackground(colorsSwt.WHITE);
		shell.setImage(imgsSwt.getIcon("3Denlive32.png"));
		//Closing the window, or quitting the application (macOS: Cmd+Q, application menu)
		shell.addListener(SWT.Close, e -> e.doit = confirmExit());
		display.addListener(SWT.Close, e -> e.doit = confirmExit());
		
		if(!new File(tde.configPath).exists()) {
			new LngChooser(tde);
			tde.config.saveGeneral(tde.configPath);
		}
		else {
			tde.config.loadGeneral(tde.configPath);
		}
		//After the loading of the config: in its language
		applyLngNames();

		if(tde.config.renderDir != null && !tde.config.renderDir.trim().isEmpty()) {
			tde.renderDir = tde.config.renderDir;
		}
		if(!new File(tde.renderDir).exists()) {
			new File(tde.renderDir).mkdirs();
		}

		createContents();
		
		waitCursor = new Cursor( shell.getDisplay(), SWT.CURSOR_WAIT );
		
		shell.open();
	}
	
	/**
	 * Names of the config lists given by the language
	 */
	void applyLngNames() {
	    TDConfig.RATIONAMES[0] = lngs.get("Generic.1stMedia");
	    TDConfig.RATIOSHORTNAMES[0] = lngs.get("Generic.1stMediaShort");
	    TDConfig.RESNAMES[0] = lngs.get("Generic.1stMedia");
	    TDConfig.RESSHORTNAMES[0] = lngs.get("Generic.1stMediaShort");
	}

	/**
	 * Change the interface language (saved in the config), and build the main window contents again in it
	 */
	//Title of the main window: the application, then [the project name], or [New project] before the first save
	public void updateTitle() {
		if(shell == null || shell.isDisposed()) {
			return;
		}
		String aName = tde.projectName == null ? lngs.get("Generic.Untitled") : tde.projectName.replaceAll("(?i)([.]3de)+$", "");
		shell.setText("3Denlive "+tde.TDeVersion+" ["+aName+"]");
		//The main montage is named after the project
		if(montageListGUI != null) {
			montageListGUI.redraw();
		}
		if(montagePropsGUI != null) {
			montagePropsGUI.refresh();
		}
	}

	public void setLanguage(String aLng) {
		String aOldLng = tde.config.lng;
		if(aLng == null || aLng.equalsIgnoreCase(aOldLng)) {
			return;
		}
		String aOldRatioShort = TDConfig.RATIOSHORTNAMES[0];
		String aOldResShort = TDConfig.RESSHORTNAMES[0];
		tde.config.lng = aLng;
		applyLngNames();
		//"1st media" names already chosen, in the old language
		if(aOldRatioShort.equals(tde.config.outRatioName)) {
			tde.config.outRatioName = TDConfig.RATIOSHORTNAMES[0];
		}
		for(int m = 0;m < tde.config.processingResNames.length;m++) {
			if(aOldResShort.equals(tde.config.processingResNames[m])) {
				tde.config.processingResNames[m] = TDConfig.RESSHORTNAMES[0];
			}
		}
		//Names of the processors of the clips, got at their creation
		Vector<VideoTimeLine> aTLs = new Vector<VideoTimeLine>(tde.timeLineStack.videoTimeLineStack);
		aTLs.addAll(tde.timeLineStack.audioTimeLineStack);
		for(VideoTimeLine aTL : aTLs) {
			for(Clip aClip : new Vector<Clip>(aTL.clipList)) {
				for(Processor aP : new Vector<Processor>(aClip.getProcessors())) {
					aP.name = lngs.translate(aP.name, aOldLng);
				}
			}
		}
		tde.config.saveGeneral(tde.configPath);
		rebuildContents();
		updateTitle();
	}

	/**
	 * Build the main window contents again (after a language change), keeping the views:
	 * monitor (shown media, glasses, grid, edges, zoom), properties or processors panel, scrolling and
	 * time zoom of the lists, sizes of the panels
	 */
	void rebuildContents() {
		//Stop what works on the old widgets
		if(playerControlGUI != null) {
			playerControlGUI.playing = false;
			playerControlGUI.playGen++;
		}
		if(monitorControlGUI != null && monitorControlGUI.wobble > 0) {
			monitorControlGUI.wobble = -10;
		}
		int[] aWeightsH = sashFormH.getWeights();
		int[] aWeightsV = sashFormV.getWeights();
		int[] aWeightsLeft = sashFormLeft != null && !sashFormLeft.isDisposed() ? sashFormLeft.getWeights() : null;
		boolean aMontagePanel = montagePropsGUI != null && !montagePropsGUI.container.isDisposed();
		com.cubaix.TDenlive.medias.Montage aShownMontage = aMontagePanel ? montagePropsGUI.shown : null;
		com.cubaix.TDenlive.medias.Montage aSelMontage = montageListGUI != null ? montageListGUI.selected : null;
		Point aMontageListOrigin = montageListGUI != null ? new Point(montageListGUI.origin.x, montageListGUI.origin.y) : null;
		MonitorGUI aOldMonitor = monitorGUI;
		MonitorControlGUI aOldMonitorControl = monitorControlGUI;
		//Left or right eye only: colored glasses button
		Color aGlassesBck = monitorControlGUI != null && monitorControlGUI.wobble <= 0 ? monitorControlGUI.glasses.getBackground() : null;
		Vector<Processor> aOldProcessors = processorGUI != null && !processorGUI.container.isDisposed() ? processorGUI.processors : null;
		Media aOldPropsMedia = processorGUI == null && propsGUI != null ? propsGUI.media : null;
		Point aTimeLinesOrigin = new Point(timeLinesGUI.origin.x, timeLinesGUI.origin.y);
		Point aMediaListOrigin = new Point(mediaListGUI.origin.x, mediaListGUI.origin.y);

		shell.setRedraw(false);
		try {
			for(Control aC : shell.getChildren()) {
				aC.dispose();
			}
			toolBar = null;
			mediaListControlGUI = null;
			mediaListGUI = null;
			montageListGUI = null;
			montagePropsGUI = null;
			multiMonitorPanel = null;
			browserGUI = null;
			monitorControlGUI = null;
			monitorGUI = null;
			monitorRulerGUI = null;
			playerControlGUI = null;
			multiPropsPanel = null;
			propsGUI = null;
			processorGUI = null;
			processorLibGUI = null;
			timeLinesGUI = null;
			createContents();
			sashFormH.setWeights(aWeightsH);
			sashFormV.setWeights(aWeightsV);
			if(aWeightsLeft != null) {
				sashFormLeft.setWeights(aWeightsLeft);
			}

			if(aOldMonitor != null) {
				setMultiMonitorPanel(MULTIMONITORPANEL_MONITOR);
				monitorGUI.mixer = aOldMonitor.mixer;
				monitorGUI.grid = aOldMonitor.grid;
				monitorGUI.edgeDetector = aOldMonitor.edgeDetector;
				monitorGUI.processingMode = aOldMonitor.processingMode;
				monitorControlGUI.setEdgesViewOn(aOldMonitorControl.edgesViewOn);
				if(aGlassesBck != null) {
					monitorControlGUI.glasses.setBackground(aGlassesBck);
				}
				Media aShown = aOldMonitor.getMedia();
				if(aShown != null) {
					playerControlGUI.showClock(aShown == tde.timeLineStack ? aShown.getTimePosMS() : aShown.getRequestedTimePosMS());
				}
				monitorGUI.setMedia(aShown);
			}
			if(aOldProcessors != null) {
				setMultiPropsPanel(MULTIPROPSPANEL_PROCESSORS);
				processorGUI.setProcessors(aOldProcessors);
			}
			else if(aMontagePanel) {
				setMultiPropsPanel(MULTIPROPSPANEL_MONTAGE);
				montagePropsGUI.setMontage(aShownMontage);
			}
			else if(aOldPropsMedia != null) {
				propsGUI.setMedia(aOldPropsMedia);
			}
			montageListGUI.selected = aSelMontage;
			if(aMontageListOrigin != null) {
				montageListGUI.origin.x = aMontageListOrigin.x;
				montageListGUI.origin.y = aMontageListOrigin.y;
			}
			timeLinesGUI.origin.x = aTimeLinesOrigin.x;
			timeLinesGUI.origin.y = aTimeLinesOrigin.y;
			mediaListGUI.origin.x = aMediaListOrigin.x;
			mediaListGUI.origin.y = aMediaListOrigin.y;
			shell.layout(true, true);
		}
		finally {
			shell.setRedraw(true);
		}
		mediaListGUI.redraw();
		timeLinesGUI.redraw();
		if(monitorGUI != null) {
			monitorGUI.redraw();
		}
	}
	
	public void setWaitCursor(boolean aWaitOn) {
		setWaitCursor(aWaitOn, shell);
	}

	/**
	 * Shows aM: its time lines, in the monitor, and its properties panel. aOffer: proposes to make again the
	 * renderings of the montages it includes that are out of date (MontageRenderGUI.offerUpdates())
	 */
	/** The montages, the one edited first */
	public static java.util.List<com.cubaix.TDenlive.medias.Montage> montagesEditedFirst(TDenlive aTDe) {
		java.util.List<com.cubaix.TDenlive.medias.Montage> aMs = new java.util.ArrayList<com.cubaix.TDenlive.medias.Montage>();
		if(aTDe.montage != null) {
			aMs.add(aTDe.montage);
		}
		for(com.cubaix.TDenlive.medias.Montage aM : aTDe.montages) {
			if(aM != aTDe.montage) {
				aMs.add(aM);
			}
		}
		return aMs;
	}

	/**
	 * Clips of aMedia in the time lines of all the montages: {montage, clip}, the montage edited first, then by
	 * montage, by start
	 */
	public static java.util.List<Object[]> occurrencesOf(TDenlive aTDe, com.cubaix.TDenlive.medias.Media aMedia) {
		java.util.List<Object[]> aL = new java.util.ArrayList<Object[]>();
		if(aMedia == null) {
			return aL;
		}
		for(com.cubaix.TDenlive.medias.Montage aM : montagesEditedFirst(aTDe)) {
			java.util.Vector<com.cubaix.TDenlive.medias.Clip> aClips = aM.stack.clipsOf(aMedia);
			aClips.sort((aA, aB) -> Long.compare(aA.getStartTimeMS(), aB.getStartTimeMS()));
			for(com.cubaix.TDenlive.medias.Clip aC : aClips) {
				aL.add(new Object[] {aM, aC});
			}
		}
		return aL;
	}

	/**
	 * The montage aM (null: the one edited) edited in the time lines if not yet (its panel shown), then the time
	 * cursor at the start of aClip, the montage in the monitor, its line scrolled into view
	 */
	public void goToClip(com.cubaix.TDenlive.medias.Montage aM, com.cubaix.TDenlive.medias.Clip aClip) {
		if(aM != null && aM != tde.montage) {
			if(MontageRenderGUI.isRunning()) {
				return;
			}
			showMontage(aM, false);
		}
		com.cubaix.TDenlive.medias.TimeLineStack aTLS = tde.timeLineStack;
		if(aTLS == null) {
			return;
		}
		long aT = aClip.getStartTimeMS();
		//A play in progress paused first (its thread would set the time again)
		PlayerControlGUI.pauseForEdit(tde);
		aTLS.setRequestedTimePosMS(aT);
		if(monitorGUI != null) {
			monitorGUI.setMedia(aTLS);
		}
		if(playerControlGUI != null) {
			playerControlGUI.showClock(aT);
		}
		timeLinesGUI.showLine(aTLS.getLineOf(aClip));
		timeLinesGUI.redraw();
		if(monitorGUI != null) {
			monitorGUI.redraw();
		}
		if(monitorRulerGUI != null) {
			monitorRulerGUI.redraw();
		}
	}

	public void showMontage(com.cubaix.TDenlive.medias.Montage aM, boolean aOffer) {
		if(aM == null || !tde.montages.contains(aM)) {
			return;
		}
		if(aM != tde.montage) {
			if(playerControlGUI != null && (playerControlGUI.playing || playerControlGUI.paused)) {
				playerControlGUI.setPlaying(false);
			}
			if(timeLinesGUI != null && tde.montage != null) {
				tde.montage.timeLinesOrigin = new Point(timeLinesGUI.origin.x, timeLinesGUI.origin.y);
			}
			tde.timeLineStack.clearDropPreview();
			tde.timeLineStack.clearSelection();
			tde.setActiveMontage(aM);
			tde.timeLineStack.clearSelection();
			if(timeLinesGUI != null) {
				timeLinesGUI.origin.x = aM.timeLinesOrigin != null ? aM.timeLinesOrigin.x : 0;
				timeLinesGUI.origin.y = aM.timeLinesOrigin != null ? aM.timeLinesOrigin.y : 0;
				timeLinesGUI.showZoom();
				timeLinesGUI.lastFollowedTimeMS = tde.timeLineStack.getRequestedTimePosMS();
			}
		}
		setMultiMonitorPanel(MULTIMONITORPANEL_MONITOR);
		setMultiPropsPanel(MULTIPROPSPANEL_MONTAGE);
		if(montageListGUI != null) {
			montageListGUI.selected = aM;
		}
		if(montagePropsGUI != null) {
			montagePropsGUI.setMontage(aM);
		}
		if(monitorGUI != null) {
			monitorGUI.setMedia(tde.timeLineStack);
			playerControlGUI.showClock(tde.timeLineStack.getTimePosMS());
			monitorGUI.redraw();
		}
		if(monitorRulerGUI != null) {
			monitorRulerGUI.redraw();
		}
		if(timeLinesGUI != null) {
			timeLinesGUI.redraw();
		}
		if(montageListGUI != null) {
			montageListGUI.redraw();
		}
		if(aOffer) {
			display.asyncExec(() -> MontageRenderGUI.offerUpdates(tde, aM));
		}
	}
	public void setWaitCursor(boolean aWaitOn,Shell aShell) {
		//Force main thread to update the cursor
		display.syncExec(new Runnable() {
			@Override
			public void run() {
				if(aWaitOn) {
					if(aShell != shell) {
						aShell.setCursor(waitCursor);
					}
					shell.setCursor(waitCursor);
				}
				else {
					if(aShell != shell) {
						aShell.setCursor(null);
					}
					shell.setCursor(null);
				}
			}
		});
	}


	/**
	 * Creates the main window's contents
	 * 
	 * @param parent the parent window
	 */
	/**
	 * Help message shown when the mouse is over a control: the "Tip.<aKey>" language entry
	 */
	public void tip(Control aC,String aKey) {
		aC.setToolTipText(lngs.get("Tip."+aKey));
	}
	public void tip(MenuItem aMI,String aKey) {
		aMI.setToolTipText(lngs.get("Tip."+aKey));
	}

	private void createContents() {
		GridLayout aLayout = new GridLayout(1, false);
		aLayout.marginHeight = aLayout.marginWidth = 0;
		shell.setLayout(aLayout);

		// Create the row of buttons
		toolBar = new Composite(shell, SWT.NULL);
		GridData aTB = new GridData(GridData.FILL_HORIZONTAL);
		aTB.heightHint = h(25);
		toolBar.setLayoutData(aTB);
		applyColorFont(toolBar);
		GridLayout aGL = new GridLayout(16, false);
		applyNoMargin(aGL);
		toolBar.setLayout(aGL);

		//Demos page of the web site in the central panel (its "Project" links open the demo projects)
		CLabel aDemos = new CLabel(toolBar,SWT.NONE);
		aDemos.setImage(tde.gui.imgsSwt.getIcon("demos.png"));
		aDemos.setAlignment(SWT.CENTER);
		GridData aDemosGD = new GridData(GridData.HORIZONTAL_ALIGN_CENTER);
		aDemosGD.widthHint = 32;
		aDemosGD.heightHint = h(25);
		aDemos.setLayoutData(aDemosGD);
		applyColorFont(aDemos);
		tip(aDemos, "Demos");
		aDemos.addListener(SWT.MouseDown, e -> showDemos());
		
		CLabel aOpenProject = new CLabel(toolBar,SWT.NONE);
		aOpenProject.setImage(tde.gui.imgsSwt.getIcon("openProject.png"));
		aOpenProject.setAlignment(SWT.CENTER);
		GridData aOpenGD = new GridData(GridData.HORIZONTAL_ALIGN_CENTER);
		aOpenGD.widthHint = 32;
		aOpenGD.heightHint = h(25);
		aOpenProject.setLayoutData(aOpenGD);
		applyColorFont(aOpenProject);
		tip(aOpenProject, "OpenProject");
		aOpenProject.addMouseListener(new MouseListener() {
			@Override
			public void mouseUp(MouseEvent arg0) {
			}
			@Override
			public void mouseDown(MouseEvent arg0) {
				openProject();
			}
			@Override
			public void mouseDoubleClick(MouseEvent arg0) {
			}
		});

		CLabel aSaveProject = new CLabel(toolBar,SWT.NONE);
		aSaveProject.setImage(tde.gui.imgsSwt.getIcon("saveProject.png"));
		aSaveProject.setAlignment(SWT.CENTER);
		GridData aSaveGD = new GridData(GridData.HORIZONTAL_ALIGN_CENTER);
		aSaveGD.widthHint = 32;
		aSaveGD.heightHint = h(25);
		aSaveProject.setLayoutData(aSaveGD);
		applyColorFont(aSaveProject);
		tip(aSaveProject, "SaveProject");
		aSaveProject.addMouseListener(new MouseListener() {
			@Override
			public void mouseUp(MouseEvent arg0) {
			}
			@Override
			public void mouseDown(MouseEvent arg0) {
				saveProject();
			}
			@Override
			public void mouseDoubleClick(MouseEvent arg0) {
			}
		});

		CLabel aPackProject = new CLabel(toolBar,SWT.NONE);
		aPackProject.setImage(tde.gui.imgsSwt.getIcon("packProject.png"));
		aPackProject.setAlignment(SWT.CENTER);
		GridData aPackGD = new GridData(GridData.HORIZONTAL_ALIGN_CENTER);
		aPackGD.widthHint = 32;
		aPackGD.heightHint = h(25);
		aPackProject.setLayoutData(aPackGD);
		applyColorFont(aPackProject);
		tip(aPackProject, "PackProject");
		aPackProject.addListener(SWT.MouseDown, e -> packProject());

		CLabel aSettings = new CLabel(toolBar,SWT.NONE);
		aSettings.setImage(tde.gui.imgsSwt.getIcon("gear.gif"));
		aSettings.setAlignment(SWT.CENTER);
		GridData aSettingsGD = new GridData(GridData.HORIZONTAL_ALIGN_CENTER);
		aSettingsGD.widthHint = 32;
		aSettingsGD.heightHint = h(25);
		aSettings.setLayoutData(aSettingsGD);
		applyColorFont(aSettings);
		tip(aSettings, "Settings");
		aSettings.addMouseListener(new MouseListener() {
			@Override
			public void mouseUp(MouseEvent arg0) {
			}
			@Override
			public void mouseDown(MouseEvent arg0) {
				new SettingsGUI(tde);
			}
			@Override
			public void mouseDoubleClick(MouseEvent arg0) {
			}
		});

		//What the AI can do on this machine (GPU / CPU of each model)
		CLabel aMachine = new CLabel(toolBar,SWT.NONE);
		aMachine.setImage(tde.gui.imgsSwt.getIcon("machine.png"));
		aMachine.setAlignment(SWT.CENTER);
		GridData aMachineGD = new GridData(GridData.HORIZONTAL_ALIGN_CENTER);
		aMachineGD.widthHint = 32;
		aMachineGD.heightHint = h(25);
		aMachine.setLayoutData(aMachineGD);
		applyColorFont(aMachine);
		tip(aMachine, "Machine");
		aMachine.addListener(SWT.MouseDown, e -> new MachineEvalGUI(tde));

		//Current image of the monitor, saved in the render folder
		CLabel aSaveView = new CLabel(toolBar,SWT.NONE);
		aSaveView.setImage(tde.gui.imgsSwt.getIcon("saveView.png"));
		aSaveView.setAlignment(SWT.CENTER);
		GridData aSaveViewGD = new GridData(GridData.HORIZONTAL_ALIGN_CENTER);
		aSaveViewGD.widthHint = 32;
		aSaveViewGD.heightHint = h(25);
		aSaveView.setLayoutData(aSaveViewGD);
		applyColorFont(aSaveView);
		tip(aSaveView, "SaveView");
		aSaveView.addListener(SWT.MouseDown, e -> saveCurrentImage());

		CLabel aRender = new CLabel(toolBar,SWT.NONE);
		aRender.setImage(tde.gui.imgsSwt.getIcon("record.gif"));
		aRender.setAlignment(SWT.CENTER);
		GridData aRenderGD = new GridData(GridData.HORIZONTAL_ALIGN_CENTER);
		aRenderGD.widthHint = 32;
		aRenderGD.heightHint = h(25);
		aRender.setLayoutData(aRenderGD);
		applyColorFont(aRender);
		tip(aRender, "Render");
		aRender.addMouseListener(new MouseListener() {
			@Override
			public void mouseUp(MouseEvent arg0) {
			}
			@Override
			public void mouseDown(MouseEvent arg0) {
				//The renderings of the montages it includes made again first if out of date (or kept, asked)
				MontageRenderGUI.beforeRender(tde, () -> new RendererGUI(tde));
			}
			@Override
			public void mouseDoubleClick(MouseEvent arg0) {
			}
		});

		//Empty space of a button, then undo / redo
		Composite aUndoSpace = new Composite(toolBar, SWT.NONE);
		applyColorFont(aUndoSpace);
		GridData aUndoSpaceGD = new GridData(GridData.HORIZONTAL_ALIGN_CENTER);
		aUndoSpaceGD.widthHint = 32;
		aUndoSpaceGD.heightHint = h(25);
		aUndoSpace.setLayoutData(aUndoSpaceGD);
		CLabel aUndo = new CLabel(toolBar,SWT.NONE);
		CLabel aRedo = new CLabel(toolBar,SWT.NONE);
		for(CLabel aL : new CLabel[] {aUndo, aRedo}) {
			aL.setAlignment(SWT.CENTER);
			GridData aGD = new GridData(GridData.HORIZONTAL_ALIGN_CENTER);
			aGD.widthHint = 32;
			aGD.heightHint = h(25);
			aL.setLayoutData(aGD);
			applyColorFont(aL);
		}
		history.setButtons(aUndo, aRedo);

		Composite aSpace = new Composite(toolBar, SWT.SEPARATOR|SWT.FILL);
		applyColorFont(aSpace);
		GridData aSpaceGD = new GridData(GridData.FILL_HORIZONTAL);
		aSpaceGD.heightHint = h(25);
		aSpace.setLayoutData(aSpaceGD);

		//Illustrations: the monitor, the window or the screen copied to the clipboard (menu)
		CLabel aCopy = new CLabel(toolBar,SWT.NONE);
		aCopy.setImage(tde.gui.imgsSwt.getIcon("copy.png"));
		aCopy.setAlignment(SWT.CENTER);
		GridData aCopyGD = new GridData(GridData.HORIZONTAL_ALIGN_CENTER);
		aCopyGD.widthHint = 32;
		aCopyGD.heightHint = h(25);
		aCopy.setLayoutData(aCopyGD);
		applyColorFont(aCopy);
		tip(aCopy, "CopyImage");
		aCopy.addListener(SWT.MouseDown, e -> {
			if(e.button == 1) {
				ScreenCopyGUI.show(tde, aCopy);
			}
		});

		CLabel aNewsSite = new CLabel(toolBar,SWT.NONE);
		aNewsSite.setImage(tde.gui.imgsSwt.getIcon("lamp.png"));
		aNewsSite.setAlignment(SWT.CENTER);
		GridData aNewsGD = new GridData(GridData.HORIZONTAL_ALIGN_CENTER);
		aNewsGD.widthHint = 32;
		aNewsGD.heightHint = h(25);
		aNewsSite.setLayoutData(aNewsGD);
		applyColorFont(aNewsSite);
		tip(aNewsSite, "News");
		aNewsSite.addMouseListener(new MouseListener() {
			@Override
			public void mouseUp(MouseEvent arg0) {
			}
			@Override
			public void mouseDown(MouseEvent arg0) {
				BrowserControl.displayURL(
						WebBrowserGUI.newsURL(tde)
						);
			}
			@Override
			public void mouseDoubleClick(MouseEvent arg0) {
			}
		});

		CLabel aGuideSite = new CLabel(toolBar,SWT.NONE);
		aGuideSite.setImage(tde.gui.imgsSwt.getIcon("book.png"));
		aGuideSite.setAlignment(SWT.CENTER);
		GridData aBookGD = new GridData(GridData.HORIZONTAL_ALIGN_CENTER);
		aBookGD.widthHint = 32;
		aBookGD.heightHint = h(25);
		aGuideSite.setLayoutData(aBookGD);
		applyColorFont(aGuideSite);
		tip(aGuideSite, "Guide");
		aGuideSite.addMouseListener(new MouseListener() {
			@Override
			public void mouseUp(MouseEvent arg0) {
			}
			@Override
			public void mouseDown(MouseEvent arg0) {
				BrowserControl.displayURL(
						WebBrowserGUI.guideURL(tde)
						);
			}
			@Override
			public void mouseDoubleClick(MouseEvent arg0) {
			}
		});

		CLabel aWebSite = new CLabel(toolBar,SWT.NONE);
		aWebSite.setImage(tde.gui.imgsSwt.getIcon("3Denlive16.png"));
		aWebSite.setAlignment(SWT.CENTER);
		GridData aWebSiteGD = new GridData(GridData.HORIZONTAL_ALIGN_CENTER);
		aWebSiteGD.widthHint = 32;
		aWebSiteGD.heightHint = h(25);
		aWebSite.setLayoutData(aWebSiteGD);
		applyColorFont(aWebSite);
		tip(aWebSite, "WebSite");
		aWebSite.addMouseListener(new MouseListener() {
			@Override
			public void mouseUp(MouseEvent arg0) {
			}
			@Override
			public void mouseDown(MouseEvent arg0) {
				BrowserControl.displayURL(
						"https://3denlive.com/"
						);
			}
			@Override
			public void mouseDoubleClick(MouseEvent arg0) {
			}
		});

		// Create the SashForm
		Composite aSashV = new Composite(shell, SWT.NONE);
		aSashV.setLayout(new FillLayout());
		aSashV.setLayoutData(new GridData(GridData.FILL_BOTH));
		final SashForm aSashFormV = sashFormV = new SashForm(aSashV, SWT.VERTICAL);

		// Change the width of the sashes
		aSashFormV.SASH_WIDTH = tde.config.sashWith;

		// Change the color used to paint the sashes
		aSashFormV.setBackground(colorsSwt.CUBAIX_BLUE);

		Composite aSashH = new Composite(aSashFormV, SWT.NONE);
		aSashH.setLayout(new FillLayout());
		aSashH.setLayoutData(new GridData(GridData.FILL_BOTH));
		final SashForm aSashFormH = sashFormH = new SashForm(aSashH, SWT.HORIZONTAL);

		// Change the width of the sashes
		aSashFormH.SASH_WIDTH = tde.config.sashWith;

		// Change the color used to paint the sashes
		aSashFormH.setBackground(colorsSwt.CUBAIX_BLUE);

		// Create the panels
		//Left column: the media list (2/3) above the montages (1/3), the separation dragged with the mouse
		Composite aLeft = new Composite(aSashFormH, SWT.NONE);
		aLeft.setLayout(new FillLayout());
		final SashForm aSashFormLeft = sashFormLeft = new SashForm(aLeft, SWT.VERTICAL);
		aSashFormLeft.SASH_WIDTH = tde.config.sashWith;
		aSashFormLeft.setBackground(colorsSwt.CUBAIX_BLUE);
		mediaListControlGUI = new MediaListControlGUI(tde, aSashFormLeft);
		mediaListGUI = new MediaListGUI(tde,mediaListControlGUI.getContainer());
		montageListGUI = new MontageListGUI(tde, aSashFormLeft);
		aSashFormLeft.setWeights(new int[] {2,1});
		
		multiMonitorPanel = new CompositePanel(tde, aSashFormH);
		setMultiMonitorPanel(MULTIMONITORPANEL_BROWSER);
		
		multiPropsPanel = new CompositePanel(tde, aSashFormH);
		setMultiPropsPanel(MULTIPROPSPANEL_PROPS);
		
		processorLibGUI = new ProcessorLibGUI(tde,aSashFormH);

		aSashFormH.setWeights(new int[] {1,6,2,1});

		timeLinesGUI = new TimeLineStackGUI(tde,aSashFormV);

		// Set the relative weights for the buttons
		aSashFormV.setWeights(new int[] {3,1});
	}
	
	public void setMultiMonitorPanel(int aKind) {
		setMultiMonitorPanel(aKind, null);
	}

	/**
	 * Show the web page (aURL: page to open, null: the one already there, else the news) or the monitor
	 * in the central panel. Both are kept once created, only the shown one is visible: the monitor keeps
	 * its state (media, mixer...) while a page is shown.
	 */
	public void setMultiMonitorPanel(int aKind,String aURL) {
		Composite aShown = null;
		boolean aChanged = false;
		if(aKind == MULTIMONITORPANEL_BROWSER) {
			if(browserGUI == null || browserGUI.container.isDisposed()) {
				aChanged = true;
				browserGUI = new WebBrowserGUI(tde, multiMonitorPanel.getContainer(), aURL);
			}
			else if(aURL != null) {
				browserGUI.setUrl(aURL);
			}
			aShown = browserGUI.container;
		}
		else if(aKind == MULTIMONITORPANEL_MONITOR) {
			if(monitorControlGUI == null || monitorControlGUI.container.isDisposed()) {
				aChanged = true;
				monitorControlGUI = new MonitorControlGUI(tde, multiMonitorPanel.getContainer());
				monitorGUI = new MonitorGUI(tde,monitorControlGUI.container);
				monitorRulerGUI = new MonitorRulerGUI(tde, monitorControlGUI.container);
				playerControlGUI = new PlayerControlGUI(tde, monitorControlGUI.container);
			}
			aShown = monitorControlGUI.container;
		}
		if(aShown == null) {
			return;
		}
		for(Control aC : multiMonitorPanel.getContainer().getChildren()) {
			boolean aVisible = aC == aShown;
			if(aC.getVisible() != aVisible) {
				aChanged = true;
				aC.setVisible(aVisible);
			}
			if(aC.getLayoutData() instanceof GridData) {
				((GridData)aC.getLayoutData()).exclude = !aVisible;
			}
		}
		if(aChanged || aURL != null) {
			multiMonitorPanel.getContainer().layout(true, true);
		}
	}

	/**
	 * Demos page of the web site (language of the interface) in the central panel
	 */
	public void showDemos() {
		setMultiMonitorPanel(MULTIMONITORPANEL_BROWSER, WebBrowserGUI.demosURL(tde));
	}
	
	/** The properties of the time line aLine in the processors area */
	public void showLineProps(com.cubaix.TDenlive.medias.VideoTimeLine aLine) {
		if(aLine == null) {
			return;
		}
		setMultiPropsPanel(MULTIPROPSPANEL_LINE);
		linePropsGUI.setLine(aLine);
	}

	public void setMultiPropsPanel(int aKind) {
		if(aKind == MULTIPROPSPANEL_PROPS) {
			if(propsGUI == null || propsGUI.container.isDisposed() || propsGUI.treeView.tree.isDisposed()) {
				processorGUI = null;
				montagePropsGUI = null;
				multiPropsPanel.clean();//Dispose all contents
				propsGUI = new MediaPropsGUI(tde, multiPropsPanel.getContainer());
				multiPropsPanel.getContainer().layout(true);
			}
		}
		else if(aKind == MULTIPROPSPANEL_PROCESSORS) {
			if(processorGUI == null || processorGUI.container.isDisposed()) {
				propsGUI = null;
				montagePropsGUI = null;
				multiPropsPanel.clean();//Dispose all contents
				processorGUI = new ProcessorsGUI(tde, multiPropsPanel.getContainer());
				multiPropsPanel.getContainer().layout(true);
			}
		}
		else if(aKind == MULTIPROPSPANEL_LINE) {
			if(linePropsGUI == null || linePropsGUI.container.isDisposed()) {
				propsGUI = null;
				processorGUI = null;
				montagePropsGUI = null;
				multiPropsPanel.clean();//Dispose all contents
				linePropsGUI = new LinePropsGUI(tde, multiPropsPanel.getContainer());
				multiPropsPanel.getContainer().layout(true);
			}
		}
		else if(aKind == MULTIPROPSPANEL_MONTAGE) {
			if(montagePropsGUI == null || montagePropsGUI.container.isDisposed()) {
				propsGUI = null;
				processorGUI = null;
				multiPropsPanel.clean();//Dispose all contents
				montagePropsGUI = new MontagePropsGUI(tde, multiPropsPanel.getContainer());
				multiPropsPanel.getContainer().layout(true);
			}
			montagePropsGUI.refresh();
		}
	}
	
	//A control whose hover frame is drawn on another one (e.g. the whole icon + text button)
	public static final String HOVER_TARGET = "3De.hoverTarget";
	//Clickable control under the mouse, framed in light gray
	Control hovered = null;

	/**
	 * Light gray frame on the controls acting as buttons (labels with an action on click) while the
	 * mouse is over them: else nothing shows that they can be clicked
	 */
	/**
	 * The secondary windows (progress, panels, dialogs: shells of the main window, and theirs) always in front of the
	 * main window: on macOS an ordinary child window goes behind the main one at the first click in it, and a window
	 * opened while a modal question of the system is shown may never be put on the screen. After a click in the main
	 * window or its activation, each visible one is ordered above (without taking the focus)
	 */
	void installDialogsInFront() {
		display.addFilter(SWT.MouseDown, e -> {
			if(e.widget instanceof org.eclipse.swt.widgets.Control && !e.widget.isDisposed()
					&& ((org.eclipse.swt.widgets.Control)e.widget).getShell() == shell) {
				dialogsInFront();
			}
		});
		display.addFilter(SWT.Activate, e -> {
			if(e.widget == shell) {
				dialogsInFront();
			}
		});
	}

	boolean dialogsFrontPending = false;

	/**
	 * Every visible secondary window ordered above the main window (once the event is handled)
	 */
	public void dialogsInFront() {
		if(dialogsFrontPending || display == null || display.isDisposed()) {
			return;
		}
		dialogsFrontPending = true;
		display.asyncExec(() -> {
			dialogsFrontPending = false;
			if(shell == null || shell.isDisposed()) {
				return;
			}
			frontOf(shell);
		});
	}

	static void frontOf(Shell aParent) {
		for(Shell aC : aParent.getShells()) {
			if(aC.isDisposed() || !aC.isVisible() || aC.getMinimized()) {
				continue;
			}
			aC.moveAbove(null);
			frontOf(aC);
		}
	}

	void installHoverFrames() {
		display.addFilter(SWT.MouseEnter, e -> {
			if(!(e.widget instanceof Control)) {
				return;
			}
			Control aC = (Control)e.widget;
			Object aTarget = aC.getData(HOVER_TARGET);
			if(aTarget instanceof Control) {
				setHovered((Control)aTarget);
			}
			else if((aC instanceof Label || aC instanceof CLabel) && aC.isListening(SWT.MouseDown)) {
				setHovered(aC);
			}
		});
		display.addFilter(SWT.MouseExit, e -> {
			if(hovered == null || !(e.widget instanceof Control)) {
				return;
			}
			Control aC = (Control)e.widget;
			if(aC == hovered || aC.getData(HOVER_TARGET) == hovered) {
				setHovered(null);
			}
		});
	}

	void setHovered(Control aC) {
		Control aOld = hovered;
		hovered = aC;
		if(aOld == aC) {
			return;
		}
		if(aOld != null && !aOld.isDisposed()) {
			aOld.redraw();
		}
		if(aC == null || aC.isDisposed()) {
			return;
		}
		if(aC.getData("3De.hoverPaint") == null) {
			aC.setData("3De.hoverPaint", Boolean.TRUE);
			aC.addListener(SWT.Paint, e -> {
				if(hovered != aC) {
					return;
				}
				Point aS = aC.getSize();
				e.gc.setForeground(colorsSwt.LIGHTGRAY);
				e.gc.setLineWidth(1);
				e.gc.drawRectangle(0, 0, aS.x-1, aS.y-1);
			});
		}
		aC.redraw();
	}

	/**
	 * Save the current image of the monitor (left, right, parallel and cross views) in the render folder
	 */
	void saveCurrentImage() {
		if(monitorGUI == null || monitorGUI.media == null) {
			return;
		}
		Media aMedia = monitorGUI.media;
		shell.setCursor(waitCursor);
		String[] aPaths = null;
		try {
			BufferedImage aBIL;
			BufferedImage aBIR;
			//Not during an image of the AI hole filling precomputation
			com.cubaix.TDenlive.processors.Inpainting.Precomputer.suspend();
			try {
				tde.timeLineStack.reBuild(TDConfig.PROCESSING_MODE_RENDER, tde.timeLineStack.getTimePosMS());
				aBIL = aMedia.getFinalLeft(TDConfig.PROCESSING_MODE_RENDER, tde.timeLineStack.getTimePosMS());
				aBIR = aMedia.getFinalRight(TDConfig.PROCESSING_MODE_RENDER, tde.timeLineStack.getTimePosMS());
			}
			finally {
				com.cubaix.TDenlive.processors.Inpainting.Precomputer.resume();
			}
			NumberFormat aNF = NumberFormat.getInstance();
			aNF.setMinimumIntegerDigits(4);
			aNF.setGroupingUsed(false);
			//Named after the project once saved, 3De_ before (as the rendering)
			String aPrefix = tde.projectName == null ? "3De_" : tde.projectName.replaceAll("(?i)([.]3de)+$", "")+"_";
			for(int i = 1;i < 10000;i++) {
				String aBase = tde.renderDir+File.separatorChar+aPrefix+aNF.format(i);
				aPaths = new String[] {aBase+"_Left.png", aBase+"_Right.png", aBase+"_X.png", aBase+"_P.png"};
				if(!new File(aPaths[0]).exists() && !new File(aPaths[1]).exists()) {
					break;
				}
			}
			ImageUtils.saveImage(aPaths[0], aBIL);
			ImageUtils.saveImage(aPaths[1], aBIR);
			BufferedImage aBI = ImageUtils.createImage(aBIL.getWidth()*2, aBIL.getHeight(), tde.config.processingHdr[TDConfig.PROCESSING_MODE_RENDER]);
			Graphics aG = aBI.getGraphics();
			aG.drawImage(aBIL, 0, 0, null);
			aG.drawImage(aBIR, aBIL.getWidth(), 0, null);
			ImageUtils.saveImage(aPaths[3], aBI);
			aG.drawImage(aBIR, 0, 0, null);
			aG.drawImage(aBIL, aBIL.getWidth(), 0, null);
			ImageUtils.saveImage(aPaths[2], aBI);
		}
		catch(Throwable t) {
			t.printStackTrace(System.err);
			shell.setCursor(null);
			MessageBox aMB = new MessageBox(shell,SWT.ICON_ERROR|SWT.OK);
			aMB.setMessage("Error: "+t.toString());
			aMB.open();
			return;
		}
		//Page with the stereo player on the parallel image, then the four images
		File aHtml = HtmlViewer.writeImagesPage(tde, aPaths);
		shell.setCursor(null);
		MessageBox aMB = new MessageBox(shell,SWT.ICON_INFORMATION|SWT.OK);
		aMB.setMessage(aPaths[0]+"\n"+aPaths[1]+"\n"+aPaths[2]+"\n"+aPaths[3]
				+(aHtml == null ? "" : "\n"+aHtml.getAbsolutePath()));
		aMB.open();
		if(aHtml != null) {
			BrowserControl.openFile(aHtml);
		}
	}

	/**
	 * Snapshot of the monitor at the time cursor in the final quality (RENDER mode), both views side by side in a
	 * PNG of Snapshots/ next to the project (~/3Denlive/Snapshots before the first save), added to the media list
	 * as a parallel stereo image (camera button left of the full screen one)
	 */
	public void snapshotToLibrary() {
		if(monitorGUI == null || monitorGUI.media == null) {
			return;
		}
		PlayerControlGUI.pauseForEdit(tde);
		Media aMedia = monitorGUI.media;
		shell.setCursor(waitCursor);
		File aOut = null;
		try {
			long aTime = tde.timeLineStack.getTimePosMS();
			BufferedImage aBIL;
			BufferedImage aBIR;
			//Not during an image of the AI hole filling precomputation
			com.cubaix.TDenlive.processors.Inpainting.Precomputer.suspend();
			try {
				tde.timeLineStack.reBuild(TDConfig.PROCESSING_MODE_RENDER, aTime);
				aBIL = aMedia.getFinalLeft(TDConfig.PROCESSING_MODE_RENDER, aTime);
				aBIR = aMedia.getFinalRight(TDConfig.PROCESSING_MODE_RENDER, aTime);
			}
			finally {
				com.cubaix.TDenlive.processors.Inpainting.Precomputer.resume();
			}
			if(aBIL == null || aBIR == null) {
				throw new Exception(lngs.get("Monitor.SnapshotEmpty"));
			}
			File aDir = new File(tde.projectDir == null || tde.projectName == null ? tde.tdeDir : tde.projectDir, "Snapshots");
			aDir.mkdirs();
			NumberFormat aNF = NumberFormat.getInstance();
			aNF.setMinimumIntegerDigits(4);
			aNF.setGroupingUsed(false);
			String aPrefix = tde.projectName == null ? "3De_" : tde.projectName.replaceAll("(?i)([.]3de)+$", "")+"_";
			for(int i = 1;i < 100000;i++) {
				aOut = new File(aDir, aPrefix+"Snapshot_"+aNF.format(i)+".png");
				if(!aOut.exists()) {
					break;
				}
			}
			//8 bits per channel (also from an HDR rendering), the alpha of the montage kept
			BufferedImage aBI = ImageUtils.createImage(aBIL.getWidth()+aBIR.getWidth(), Math.max(aBIL.getHeight(), aBIR.getHeight()));
			Graphics aG = aBI.getGraphics();
			aG.drawImage(aBIL, 0, 0, null);
			aG.drawImage(aBIR, aBIL.getWidth(), 0, null);
			aG.dispose();
			ImageUtils.saveImage(aOut.getAbsolutePath(), aBI);
			tde.mediaList.loadImageP(aOut.getAbsolutePath(), TDConfig.PROCESSING_MODE_RENDER, 0);
			if(mediaListGUI != null) {
				mediaListGUI.redraw();
			}
		}
		catch(Throwable t) {
			t.printStackTrace(System.err);
			shell.setCursor(null);
			MessageBox aMB = new MessageBox(shell,SWT.ICON_ERROR|SWT.OK);
			aMB.setMessage(lngs.get("Monitor.SnapshotError")+"\n"+(t.getMessage() == null ? t.toString() : t.getMessage()));
			aMB.open();
			return;
		}
		shell.setCursor(null);
	}

	/**
	 * New mixer in the view mode of the monitor (anaglyph, side by side...), its default mode
	 * when the monitor is not created yet (web page shown in its place)
	 */
	public com.cubaix.TDenlive.processors.Mixer copyMonitorMixer() {
		if(monitorGUI != null && monitorGUI.mixer != null) {
			return monitorGUI.mixer.copyMode();
		}
		return new com.cubaix.TDenlive.processors.AnaMixer(tde, null, 0);
	}

	public void applyColorFont(Control aC) {
		aC.setBackground(colorsSwt.WHITE);
		aC.setForeground(colorsSwt.CUBAIX_BLUE);
		aC.setFont(fontsSwt.roboto);
	}
	
	//Text line height the hard-coded widget heights were designed for (8pt font, ~10-12px)
	static final int DESIGN_TEXT_H = 12;
	//Real text line height of the SWT fonts, set by createGUI()
	public int textH = DESIGN_TEXT_H;

	//Adapts a hard-coded widget height (designed for DESIGN_TEXT_H) to the real font height
	public int h(int aDesignH) {
		return aDesignH + Math.max(0, textH - DESIGN_TEXT_H);
	}

	//Width hint of a combo that shows its longest item whole (the drop down list of macOS is as wide as the combo):
	//text of the widest item in the combo's font + room for the arrow and the margins, at least aMinW
	public int comboW(org.eclipse.swt.widgets.Combo aCombo, int aMinW) {
		int aTextW = 0;
		GC aGC = new GC(aCombo);
		try {
			aGC.setFont(aCombo.getFont());
			for(String aItem : aCombo.getItems()) {
				aTextW = Math.max(aTextW, aGC.textExtent(aItem).x);
			}
		}
		finally {
			aGC.dispose();
		}
		int aW = aTextW + 40;
		try {
			aW = Math.max(aW, aCombo.computeSize(SWT.DEFAULT, SWT.DEFAULT).x);
		}
		catch(Throwable t) {
			t.printStackTrace(System.err);
		}
		return Math.max(aMinW, aW);
	}

	int measureTextH() {
		GC aGC = new GC(display);
		try {
			aGC.setFont(fontsSwt.roboto);
			int aH = aGC.getFontMetrics().getHeight();
			aGC.setFont(fontsSwt.robotoBold);
			return Math.max(aH, aGC.getFontMetrics().getHeight());
		}
		finally {
			aGC.dispose();
		}
	}

	/**
	 * Text field with a light gray 1 px frame (SWT.BORDER is not drawn everywhere, e.g. on macOS with a white
	 * background). The frame is the parent of the field: give the layout data to text.getParent().
	 */
	public Text framedText(Composite aParent,int aStyle) {
		Composite aFrame = new Composite(aParent, SWT.NONE);
		aFrame.setBackground(colorsSwt.LIGHTGRAY);
		GridLayout aGL = new GridLayout(1, false);
		applyNoMargin(aGL);
		aGL.marginWidth = aGL.marginHeight = 1;
		aFrame.setLayout(aGL);
		Text aText = new Text(aFrame, aStyle & ~SWT.BORDER);
		aText.setLayoutData(new GridData(GridData.FILL_BOTH));
		applyColorFont(aText);
		return aText;
	}

	public void applyNoMargin(GridLayout aGL) {
		aGL.marginLeft = aGL.marginTop = aGL.marginRight = aGL.marginBottom = 0;
		aGL.verticalSpacing = aGL.horizontalSpacing = 0;
		aGL.marginHeight = aGL.marginWidth = 0;
	}
	
	public void run() {
		//Force timelines redraw (possibly not already created at the first shell viewing)
		timeLinesGUI.redraw();
		while (!shell.isDisposed()) {
			//Never crash
			try {
				if (!display.readAndDispatch()) {
					display.sleep();
				}
			}
			catch(Throwable t) {
				t.printStackTrace(System.err);
			}
		}
		tde.config.saveGeneral(tde.configPath);
		tde.saveProject(tde.tdeDir+File.separatorChar+"LastProject.3De");
		display.dispose();
	}
}
