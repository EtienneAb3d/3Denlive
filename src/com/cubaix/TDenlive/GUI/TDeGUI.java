package com.cubaix.TDenlive.GUI;

import java.io.File;

import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.CLabel;
import org.eclipse.swt.custom.SashForm;
import org.eclipse.swt.events.MouseEvent;
import org.eclipse.swt.events.MouseListener;
import org.eclipse.swt.graphics.Cursor;
import org.eclipse.swt.graphics.GC;
import org.eclipse.swt.layout.FillLayout;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.FileDialog;
import org.eclipse.swt.widgets.MenuItem;
import org.eclipse.swt.widgets.MessageBox;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Text;

import com.cubaix.TDenlive.TDConfig;
import com.cubaix.TDenlive.TDenlive;
import com.cubaix.TDenlive.res.FontsAwt;
import com.cubaix.TDenlive.res.FontsSwt;
import com.cubaix.TDenlive.res.ImgsAwt;
import com.cubaix.TDenlive.res.ImgsSwt;
import com.cubaix.TDenlive.res.Lngs;
import com.cubaix.TDenlive.utils.BrowserControl;
import com.cubaix.TDenlive.utils.Frames;
import com.cubaix.TDenlive.utils.Lumas;

public class TDeGUI {
	static public final int MULTIMONITORPANEL_BROWSER = 0;
	static public final int MULTIMONITORPANEL_MONITOR = 1;
	static public final int MULTIMONITORPANEL_SETTINGS = 2;

	static public final int MULTIPROPSPANEL_PROPS = 0;
	static public final int MULTIPROPSPANEL_PROCESSORS = 1;
	
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
	
	public CompositePanel multiMonitorPanel = null;
	public WebBrowserGUI browserGUI = null;
	public MonitorControlGUI monitorControlGUI = null;
	public MonitorGUI monitorGUI = null;
	public PlayerControlGUI playerControlGUI = null;
	
	public CompositePanel multiPropsPanel = null;
	public MediaPropsGUI propsGUI = null;
	public ProcessorsGUI processorGUI = null;
	
	public ProcessorLibGUI processorLibGUI = null;
	
	public TimeLineStackGUI timeLinesGUI = null;
	
	Cursor waitCursor = null;
	
	public TDeGUI(TDenlive aTDe) {
		tde = aTDe;
		tde.gui = this;
		createGUI();
	}
	
	void openProject() {
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

		setWaitCursor(true);
		Thread aTh = new Thread(new Runnable() {
			@Override
			public void run() {
				tde.openProject(aPath);
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
	 * Ask to save the project file and save it.
	 * Returns false if canceled, or if the saving failed
	 */
	boolean saveProject() {
		FileDialog aFD = new FileDialog (shell, SWT.SAVE);
		String [] filterNames = new String [] {"3De projects"};
		String [] filterExtensions = new String [] {"*.3De"};
		//Current project file (opened or last saved): proposed by default, replaced without confirmation
		File aCurrentFile = tde.projectDir != null && tde.projectName != null ? new File(tde.projectDir, tde.projectName) : null;
		String filterPath = aCurrentFile != null ? tde.projectDir : (tde.lastDir == null ? tde.tdeDir:tde.lastDir);
		//macOS: the save panel confirms the replacement of an existing file itself, and SWT can no longer
		//disable it (setOverwrite(false) relies on a private method gone from recent macOS). So no extension
		//is given to it: it returns the name without .3De, which does not exist, and asks nothing.
		boolean aCocoa = "cocoa".equals(SWT.getPlatform());
		if(!aCocoa) {
			aFD.setFilterNames (filterNames);
			aFD.setFilterExtensions (filterExtensions);
		}
		aFD.setFilterPath (filterPath);
		if(tde.projectName != null) {
			//Without extension (macOS: else the panel adds the filter's one itself, and checks it)
			aFD.setFileName(tde.projectName.replaceAll("(?i)([.]3de)+$", ""));
		}
		//No confirmation from the dialog itself: asked below, only for another file
		aFD.setOverwrite(false);
		String aPath = aFD.open ();
		System.out.println ("Path: " + aPath);
		if(aPath == null) {
			return false;
		}
		//macOS: an existing file chosen with its extension (typed, or clicked in the list) was already confirmed by the panel
		boolean aConfirmedByDialog = aCocoa && new File(aPath).exists();
		//Exactly one .3De extension, even if the dialog or the user already added one (or more)
		aPath = aPath.replaceAll("(?i)([.]3de)+$", "") + ".3De";
		if(new File(aPath).exists() && !aConfirmedByDialog && !isSameFile(new File(aPath), aCurrentFile)) {
			MessageBox aMB = new MessageBox(shell, SWT.ICON_WARNING|SWT.YES|SWT.NO);
			aMB.setText(lngs.get("Generic.SaveAs"));
			aMB.setMessage(new File(aPath).getName()+"\n"+lngs.get("Generic.OverwriteFile"));
			if(aMB.open() != SWT.YES) {
				return false;
			}
		}
		String aOldWorkCacheDir = tde.getWorkCacheDir();
		tde.projectDir = tde.lastDir = new File(aPath).getParent();
		tde.projectName = new File(aPath).getName();

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
		return true;
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
		if(tde.isModified()) {
			MessageBox aMB = new MessageBox(shell, SWT.ICON_WARNING|SWT.YES|SWT.NO|SWT.CANCEL);
			aMB.setText("3Denlive");
			String aName = tde.projectName == null ? lngs.get("Generic.Untitled") : tde.projectName;
			aMB.setMessage(aName+"\n"+lngs.get("Generic.SaveBeforeQuit"));
			int aAnswer = aMB.open();
			if(aAnswer == SWT.CANCEL) {
				return false;
			}
			if(aAnswer == SWT.YES && !saveProject()) {
				return false;
			}
		}
		exitConfirmed = true;
		return true;
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
				
	    TDConfig.RATIONAMES[0] = lngs.get("Generic.1stMedia");
	    TDConfig.RATIOSHORTNAMES[0] = lngs.get("Generic.1stMediaShort");
	    TDConfig.RESNAMES[0] = lngs.get("Generic.1stMedia");
	    TDConfig.RESSHORTNAMES[0] = lngs.get("Generic.1stMediaShort");
		
		shell = new Shell(display,SWT.SHELL_TRIM);
		shell.setText("3Denlive "+tde.TDeVersion);
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
	
	public void setWaitCursor(boolean aWaitOn) {
		setWaitCursor(aWaitOn, shell);
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
		GridLayout aGL = new GridLayout(9, false);
		applyNoMargin(aGL);
		toolBar.setLayout(aGL);
		
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
				new RendererGUI(tde);
			}
			@Override
			public void mouseDoubleClick(MouseEvent arg0) {
			}
		});

		Composite aSpace = new Composite(toolBar, SWT.SEPARATOR|SWT.FILL);
		applyColorFont(aSpace);
		GridData aSpaceGD = new GridData(GridData.FILL_HORIZONTAL);
		aSpaceGD.heightHint = h(25);
		aSpace.setLayoutData(aSpaceGD);

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
						"https://www.3denlive.com/news"+tde.config.lng.toUpperCase()+ ".php"
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
						"https://www.3denlive.com/doc/EN/"
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
		final SashForm aSashFormV = new SashForm(aSashV, SWT.VERTICAL);

		// Change the width of the sashes
		aSashFormV.SASH_WIDTH = tde.config.sashWith;

		// Change the color used to paint the sashes
		aSashFormV.setBackground(colorsSwt.CUBAIX_BLUE);

		Composite aSashH = new Composite(aSashFormV, SWT.NONE);
		aSashH.setLayout(new FillLayout());
		aSashH.setLayoutData(new GridData(GridData.FILL_BOTH));
		final SashForm aSashFormH = new SashForm(aSashH, SWT.HORIZONTAL);

		// Change the width of the sashes
		aSashFormH.SASH_WIDTH = tde.config.sashWith;

		// Change the color used to paint the sashes
		aSashFormH.setBackground(colorsSwt.CUBAIX_BLUE);

		// Create the panels
		mediaListControlGUI = new MediaListControlGUI(tde, aSashFormH);
		mediaListGUI = new MediaListGUI(tde,mediaListControlGUI.getContainer());
		
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
		if(aKind == MULTIMONITORPANEL_BROWSER) {
			if(browserGUI == null) {
				multiMonitorPanel.clean();//Dispose all contents
				browserGUI = new WebBrowserGUI(tde, multiMonitorPanel.getContainer());
				multiMonitorPanel.getContainer().layout(true);
			}
		}
		else if(aKind == MULTIMONITORPANEL_MONITOR) {
			if(monitorControlGUI == null) {
				multiMonitorPanel.clean();//Dispose all contents
				monitorControlGUI = new MonitorControlGUI(tde, multiMonitorPanel.getContainer());
				monitorGUI = new MonitorGUI(tde,monitorControlGUI.container);
				playerControlGUI = new PlayerControlGUI(tde, monitorControlGUI.container);
				multiMonitorPanel.getContainer().layout(true);
			}
		}
	}
	
	public void setMultiPropsPanel(int aKind) {
		if(aKind == MULTIPROPSPANEL_PROPS) {
			if(propsGUI == null || propsGUI.container.isDisposed() || propsGUI.treeView.tree.isDisposed()) {
				processorGUI = null;
				multiPropsPanel.clean();//Dispose all contents
				propsGUI = new MediaPropsGUI(tde, multiPropsPanel.getContainer());
				multiPropsPanel.getContainer().layout(true);
			}
		}
		else if(aKind == MULTIPROPSPANEL_PROCESSORS) {
			if(processorGUI == null || processorGUI.container.isDisposed()) {
				propsGUI = null;
				multiPropsPanel.clean();//Dispose all contents
				processorGUI = new ProcessorsGUI(tde, multiPropsPanel.getContainer());
				multiPropsPanel.getContainer().layout(true);
			}
		}
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
