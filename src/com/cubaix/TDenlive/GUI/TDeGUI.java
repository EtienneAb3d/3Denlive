package com.cubaix.TDenlive.GUI;

import java.io.File;

import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.CLabel;
import org.eclipse.swt.custom.SashForm;
import org.eclipse.swt.events.MouseEvent;
import org.eclipse.swt.events.MouseListener;
import org.eclipse.swt.graphics.Cursor;
import org.eclipse.swt.layout.FillLayout;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.FileDialog;
import org.eclipse.swt.widgets.Shell;

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
		tde.projectDir = tde.lastDir = new File(aPath).getParent();
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

	void saveProject() {
		FileDialog aFD = new FileDialog (shell, SWT.SAVE);
		String [] filterNames = new String [] {"3De projects"};
		String [] filterExtensions = new String [] {"*.3De"};
		String filterPath = tde.lastDir == null ? tde.tdeDir:tde.lastDir;
		aFD.setFilterNames (filterNames);
		aFD.setFilterExtensions (filterExtensions);
		aFD.setFilterPath (filterPath);
		if(tde.projectName != null) {
			aFD.setFileName(tde.projectName);
		}
		String aPath = aFD.open ();
		System.out.println ("Path: " + aPath);
		if(aPath == null) {
			return;
		}
		tde.projectDir = tde.lastDir = new File(aPath).getParent();
		tde.projectName = new File(aPath).getName();

		if(!aPath.toLowerCase().endsWith(".3de")) {
			aPath += ".3De";
		}
		tde.saveProject(aPath);
	}

	private void createGUI() {
		display = new Display();
		colorsSwt = new ColorsSwt(tde);
		colorsAwt = new ColorsAwt(tde);
		fontsSwt = new FontsSwt(tde);
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
	private void createContents() {
		GridLayout aLayout = new GridLayout(1, false);
		aLayout.marginHeight = aLayout.marginWidth = 0;
		shell.setLayout(aLayout);

		// Create the row of buttons
		toolBar = new Composite(shell, SWT.NULL);
		GridData aTB = new GridData(GridData.FILL_HORIZONTAL);
		aTB.heightHint = 25;
		toolBar.setLayoutData(aTB);
		applyColorFont(toolBar);
		GridLayout aGL = new GridLayout(8, false);
		applyNoMargin(aGL);
		toolBar.setLayout(aGL);
		
		CLabel aOpenProject = new CLabel(toolBar,SWT.NONE);
		aOpenProject.setImage(tde.gui.imgsSwt.getIcon("openProject.png"));
		aOpenProject.setAlignment(SWT.CENTER);
		GridData aOpenGD = new GridData(GridData.HORIZONTAL_ALIGN_CENTER);
		aOpenGD.widthHint = 32;
		aOpenGD.heightHint = 25;
		aOpenProject.setLayoutData(aOpenGD);
		applyColorFont(aOpenProject);
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
		aSaveGD.heightHint = 25;
		aSaveProject.setLayoutData(aSaveGD);
		applyColorFont(aSaveProject);
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

		CLabel aSettings = new CLabel(toolBar,SWT.NONE);
		aSettings.setImage(tde.gui.imgsSwt.getIcon("gear.gif"));
		aSettings.setAlignment(SWT.CENTER);
		GridData aSettingsGD = new GridData(GridData.HORIZONTAL_ALIGN_CENTER);
		aSettingsGD.widthHint = 32;
		aSettingsGD.heightHint = 25;
		aSettings.setLayoutData(aSettingsGD);
		applyColorFont(aSettings);
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
		aRenderGD.heightHint = 25;
		aRender.setLayoutData(aRenderGD);
		applyColorFont(aRender);
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
		aSpaceGD.heightHint = 25;
		aSpace.setLayoutData(aSpaceGD);

		CLabel aNewsSite = new CLabel(toolBar,SWT.NONE);
		aNewsSite.setImage(tde.gui.imgsSwt.getIcon("lamp.png"));
		aNewsSite.setAlignment(SWT.CENTER);
		GridData aNewsGD = new GridData(GridData.HORIZONTAL_ALIGN_CENTER);
		aNewsGD.widthHint = 32;
		aNewsGD.heightHint = 25;
		aNewsSite.setLayoutData(aNewsGD);
		applyColorFont(aNewsSite);
		aNewsSite.addMouseListener(new MouseListener() {
			@Override
			public void mouseUp(MouseEvent arg0) {
			}
			@Override
			public void mouseDown(MouseEvent arg0) {
				BrowserControl.displayURL(
						"http://www.3denlive.com/news"+tde.config.lng.toUpperCase()+ ".php"
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
		aBookGD.heightHint = 25;
		aGuideSite.setLayoutData(aBookGD);
		applyColorFont(aGuideSite);
		aGuideSite.addMouseListener(new MouseListener() {
			@Override
			public void mouseUp(MouseEvent arg0) {
			}
			@Override
			public void mouseDown(MouseEvent arg0) {
				BrowserControl.displayURL(
						"http://www.3denlive.com/doc/EN/"
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
		aWebSiteGD.heightHint = 25;
		aWebSite.setLayoutData(aWebSiteGD);
		applyColorFont(aWebSite);
		aWebSite.addMouseListener(new MouseListener() {
			@Override
			public void mouseUp(MouseEvent arg0) {
			}
			@Override
			public void mouseDown(MouseEvent arg0) {
				BrowserControl.displayURL(
						"http://3denlive.com/"
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
