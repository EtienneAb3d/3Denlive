package com.cubaix.TDenlive.GUI;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.util.Vector;

import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.CLabel;
import org.eclipse.swt.custom.ScrolledComposite;
import org.eclipse.swt.events.ControlListener;
import org.eclipse.swt.events.SelectionEvent;
import org.eclipse.swt.events.SelectionListener;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.graphics.Rectangle;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Event;
import org.eclipse.swt.widgets.FileDialog;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Listener;

import com.cubaix.TDenlive.TDenlive;
import com.cubaix.TDenlive.GUI.processors.BrightnessContrastGUI;
import com.cubaix.TDenlive.GUI.processors.ChromakeyGUI;
import com.cubaix.TDenlive.GUI.processors.ColorAdapterGUI;
import com.cubaix.TDenlive.GUI.processors.CropperGUI;
import com.cubaix.TDenlive.GUI.processors.DepthmapGUI;
import com.cubaix.TDenlive.GUI.processors.DepthmapStereoGUI;
import com.cubaix.TDenlive.GUI.processors.FramingGUI;
import com.cubaix.TDenlive.GUI.processors.HueSaturationGUI;
import com.cubaix.TDenlive.GUI.processors.LensCorrectionGUI;
import com.cubaix.TDenlive.GUI.processors.PlacerGUI;
import com.cubaix.TDenlive.GUI.processors.ProcessorGUI;
import com.cubaix.TDenlive.GUI.processors.ScripterGUI;
import com.cubaix.TDenlive.GUI.processors.ShapesGUI;
import com.cubaix.TDenlive.GUI.processors.SubtitlesGUI;
import com.cubaix.TDenlive.GUI.processors.StereoAlignerGUI;
import com.cubaix.TDenlive.GUI.processors.TimeControllerGUI;
import com.cubaix.TDenlive.GUI.processors.TransparencyGUI;
import com.cubaix.TDenlive.GUI.processors.VolumeGUI;
import com.cubaix.TDenlive.GUI.processors.SpeedGUI;
import com.cubaix.TDenlive.medias.Clip;
import com.cubaix.TDenlive.processors.Processor;
import com.cubaix.TDenlive.xml.XmlMinimalParser;
import com.cubaix.TDenlive.xml.XmlObject;

public class ProcessorsGUI extends CompositePanel {

	public ProcessorsGUI(TDenlive aTDe, Composite aParent) {
		super(aTDe, aParent);
		createContents(aParent);
		//A change in the panel of a processor (click, selection, key; not in its title) gives it the focus
		Listener aFilter = e -> focusFromEvent(e);
		org.eclipse.swt.widgets.Display aD = aParent.getDisplay();
		aD.addFilter(SWT.MouseDown, aFilter);
		aD.addFilter(SWT.Selection, aFilter);
		aD.addFilter(SWT.KeyDown, aFilter);
		container.addListener(SWT.Dispose, e -> {
			aD.removeFilter(SWT.MouseDown, aFilter);
			aD.removeFilter(SWT.Selection, aFilter);
			aD.removeFilter(SWT.KeyDown, aFilter);
		});
	}

	//Scrolled list of the processors shown and its content
	ScrolledComposite scroll = null;
	Composite scrollContent = null;
	boolean scrollPending = false;

	/**
	 * Height of the scrolled list from its content (a panel expanded / collapsed / grown): the scroll bar can
	 * reach the last processors
	 */
	public void updateScroll() {
		if(scroll == null || scroll.isDisposed() || scrollContent == null || scrollContent.isDisposed()) {
			return;
		}
		Rectangle r = scroll.getClientArea();
		Point aS = scrollContent.computeSize(r.width, SWT.DEFAULT);
		if(scroll.getMinWidth() != aS.x || scroll.getMinHeight() != aS.y) {
			scroll.setMinSize(aS);
		}
	}

	/**
	 * Scrolls the list of the processors to the panel of aP (after the layout)
	 */
	public void reveal(Processor aP) {
		tde.gui.display.asyncExec(() -> {
			updateScroll();
			if(scroll == null || scroll.isDisposed() || scrollContent == null || scrollContent.isDisposed()) {
				return;
			}
			for(org.eclipse.swt.widgets.Control aC : scrollContent.getChildren()) {
				if(aC.getData(PROC_BOX) == aP) {
					scroll.setOrigin(0, aC.getLocation().y);
					return;
				}
			}
		});
	}

	void scrollLater() {
		if(scrollPending) {
			return;
		}
		scrollPending = true;
		tde.gui.display.asyncExec(() -> {
			scrollPending = false;
			updateScroll();
		});
	}

	//Data of the box of a processor (its processor) and of its title (the focus is not changed by a click there)
	static final String PROC_BOX = "ProcessorsGUI.box";
	static final String PROC_TITLE = "ProcessorsGUI.title";
	//Focus icons of the titles shown, by processor
	final java.util.HashMap<Processor,CLabel> focusIcons = new java.util.HashMap<Processor,CLabel>();

	void focusFromEvent(Event e) {
		if(!(e.widget instanceof Control) || container.isDisposed()) {
			return;
		}
		for(Control aC = (Control)e.widget;aC != null;aC = aC.getParent()) {
			if(aC.getData(PROC_TITLE) != null) {
				return;
			}
			Object aP = aC.getData(PROC_BOX);
			if(aP instanceof Processor) {
				if(((Processor)aP).targetMedia instanceof Clip && ((Clip)((Processor)aP).targetMedia).getFocused() != aP) {
					focus((Processor)aP);
				}
				return;
			}
			if(aC == container) {
				return;
			}
		}
	}

	/**
	 * aP gets the focus of its clip (null: none of aClip), the others lose it; the icons and the monitor follow
	 */
	public void focus(Processor aP) {
		if(aP != null && aP.targetMedia instanceof Clip) {
			((Clip)aP.targetMedia).setFocused(aP);
		}
		showFocus();
		tde.gui.monitorGUI.redraw();
	}

	void showFocus() {
		for(java.util.Map.Entry<Processor,CLabel> aE : focusIcons.entrySet()) {
			CLabel aL = aE.getValue();
			if(aL.isDisposed()) {
				continue;
			}
			Processor aP = aE.getKey();
			boolean aOn = aP.targetMedia instanceof Clip && ((Clip)aP.targetMedia).getFocused() == aP;
			aL.setImage(tde.gui.imgsSwt.getIcon(aOn ? "focusOn.png" : "focusOff.png"));
			tde.gui.tip(aL, aOn ? "ProcFocusOn" : "ProcFocusOff");
		}
	}

	void createContents(Composite aParent) {
	}

	//Processors shown (of the selected clip)
	Vector<Processor> processors = null;

	//Processors expanded by the user during the session, by class: applied to the processors of each clip shown
	//(a class never toggled: collapsed, so the first clip shows its processors collapsed)
	static final java.util.HashMap<Class<?>, Boolean> EXPANDED = new java.util.HashMap<>();
	//Clip whose processors were shown last: shown again (move, add, undo...), their own states are kept
	Object shownClip = null;

	static void rememberExpanded(Processor aP) {
		EXPANDED.put(aP.getClass(), aP.isExpended);
	}

	public void setProcessors(Vector<Processor> aProcessors) {
		processors = aProcessors;
		Object aClip = aProcessors == null || aProcessors.isEmpty() ? null : aProcessors.elementAt(0).targetMedia;
		if(aClip != null && aClip != shownClip) {
			for(Processor aP : aProcessors) {
				Boolean aE = EXPANDED.get(aP.getClass());
				aP.isExpended = aE != null && aE;
			}
		}
		if(aClip != null) {
			shownClip = aClip;
		}
		clean();
		if(tde.selected.clips.size() > 1) {
			//Several clips selected: whose processors they would be is not clear, none are shown
			Label aMsg = new Label(container, SWT.WRAP | SWT.CENTER);
			tde.gui.applyColorFont(aMsg);
			aMsg.setText(tde.gui.lngs.get("Processor.SeveralClips").replace("%N%", ""+tde.selected.clips.size()));
			GridData aGD = new GridData(GridData.FILL_HORIZONTAL);
			aGD.verticalIndent = 20;
			aMsg.setLayoutData(aGD);
			container.layout(true, true);
			return;
		}
		
		ScrolledComposite aSC = new ScrolledComposite(container, SWT.V_SCROLL);
		aSC.setBackground(tde.gui.colorsSwt.WHITE);
		aSC.setLayoutData(new GridData(GridData.FILL_BOTH));
		Composite aContainer = new Composite(aSC, SWT.NONE);
		aContainer.setBackground(tde.gui.colorsSwt.WHITE);
		GridLayout aLayoutC = new GridLayout(1,false);
		aLayoutC.marginHeight = aLayoutC.marginWidth = 0;
		aLayoutC.verticalSpacing = aLayoutC.horizontalSpacing = 0;
		aLayoutC.marginHeight = aLayoutC.marginWidth = 0;
		aContainer.setLayout(aLayoutC);
		aSC.setContent(aContainer);
		aSC.setExpandHorizontal(true);
		aSC.setExpandVertical(true);
		scroll = aSC;
		scrollContent = aContainer;
		aSC.addControlListener(ControlListener.controlResizedAdapter(e -> updateScroll()));
		aSC.setAlwaysShowScrollBars(true);
		
		boolean aIsFirst = true;
		focusIcons.clear();
		for(Processor aP : aProcessors) {
			final Composite aBox = new Composite (aContainer, SWT.NULL);
			aBox.setData(PROC_BOX, aP);
			//A panel opened, closed or changing its size: the scrolled height follows
			aBox.addListener(SWT.Resize, e -> scrollLater());
			aBox.setLayoutData(new GridData(GridData.FILL_HORIZONTAL));
			GridLayout aLayoutB = new GridLayout(1,false);
			aLayoutB.marginLeft = aLayoutB.marginTop = aLayoutB.marginRight = aLayoutB.marginBottom = 0;
			aLayoutB.verticalSpacing = aLayoutB.horizontalSpacing = 0;
			aLayoutB.marginHeight = aLayoutB.marginWidth = 0;
			aBox.setLayout(aLayoutB);
			aBox.setBackground(tde.gui.colorsSwt.CUBAIX_PINK);
			aBox.setFont(tde.gui.fontsSwt.robotoBold);
			aBox.setForeground(tde.gui.colorsSwt.WHITE);
			
			Composite aTitleC = new Composite(aBox,SWT.BORDER);
			aTitleC.setData(PROC_TITLE, Boolean.TRUE);
			aTitleC.setLayoutData(new GridData(GridData.FILL_HORIZONTAL));
			GridLayout aLayoutTC = new GridLayout(8,false);
			tde.gui.applyNoMargin(aLayoutTC);
			aTitleC.setLayout(aLayoutTC);
			tde.gui.applyColorFont(aTitleC);
			aTitleC.setBackground(tde.gui.colorsSwt.PINK_L92);
			
			CLabel aTitle = new CLabel(aTitleC, SWT.NONE);
//			aTitle.setLeftMargin(10);
			aTitle.setLayoutData(new GridData(GridData.FILL_HORIZONTAL));
			tde.gui.applyColorFont(aTitle);
			aTitle.setBackground(tde.gui.colorsSwt.PINK_L92);
			aTitle.setForeground(aP.isWarning ? tde.gui.colorsSwt.RED : tde.gui.colorsSwt.CUBAIX_BLUE);
			aTitle.setText(aP.name);
			aTitle.setData(aP);
			aTitle.setImage(tde.gui.imgsSwt.getIcon(aP.icon));
			tde.gui.tip(aTitle, "ProcTitle");
			aTitle.addListener(SWT.MouseDown, new Listener() {
				@Override
				public void handleEvent(Event arg0) {
					if(aBox.getData() == null) {
						createProcessorGUI(aBox, aP);
						
						aP.isExpended = true;
						rememberExpanded(aP);
						
						aContainer.layout(true);
						updateScroll();
					}
					else {
						ProcessorGUI aPG = (ProcessorGUI)aBox.getData();
						aPG.container.dispose();
						aBox.setData(null);

						aP.isExpended = false;
						rememberExpanded(aP);

						aContainer.layout(true);
						updateScroll();
					}
				}
			});
			
			if(aIsFirst) {
				CLabel aAllUp = new CLabel(aTitleC, SWT.NONE);
				aAllUp.setLayoutData(new GridData(GridData.FILL_VERTICAL));
				aAllUp.setBackground(tde.gui.colorsSwt.PINK_L92);
				aAllUp.setFont(tde.gui.fontsSwt.robotoBold);
				aAllUp.setData(aP);
				aAllUp.setImage(tde.gui.imgsSwt.getIcon("collapseUp.gif"));
				tde.gui.tip(aAllUp, "ProcCollapseAll");
				aAllUp.addListener(SWT.MouseDown,e -> {
					for(Processor p : aProcessors) {
						p.isExpended = false;
						rememberExpanded(p);
					}
					tde.gui.processorGUI.setProcessors(((Clip)aP.targetMedia).getProcessors());
					tde.gui.monitorGUI.redraw();
				});
				aIsFirst = false;
				if(aP.isExpended) {
					createProcessorGUI(aBox, aP);
				}
				continue;
			}
			
			if(aP.isDeactivable) {
				Button aActCB = new Button(aTitleC,SWT.CHECK);
				tde.gui.applyColorFont(aActCB);
				tde.gui.tip(aActCB, "ProcActive");
				aActCB.setSelection(aP.isActive);
				aActCB.addSelectionListener(new SelectionListener() {
					@Override
					public void widgetSelected(SelectionEvent arg0) {
						setActive(aP, aActCB.getSelection());
					}
					@Override
					public void widgetDefaultSelected(SelectionEvent arg0) {
						setActive(aP, aActCB.getSelection());
					}
				});
			}
			else {
				CLabel aSPC = new CLabel(aTitleC,SWT.NONE);
				tde.gui.applyColorFont(aSC);
				aSPC.setBackground(tde.gui.colorsSwt.PINK_L92);
			}

			//Focus: the processor concerned by the changes (several subtitles...); a click gives / removes it
			CLabel aFocus = new CLabel(aTitleC, SWT.NONE);
			aFocus.setLayoutData(new GridData(GridData.FILL_VERTICAL));
			aFocus.setBackground(tde.gui.colorsSwt.PINK_L92);
			aFocus.setData(aP);
			focusIcons.put(aP, aFocus);
			aFocus.addListener(SWT.MouseDown, e -> {
				Clip aOwner = (Clip)aP.targetMedia;
				if(aOwner.getFocused() == aP) {
					aOwner.setFocused(null);
					showFocus();
					tde.gui.monitorGUI.redraw();
				}
				else {
					focus(aP);
				}
			});

			CLabel aUp = new CLabel(aTitleC, SWT.NONE);
			aUp.setLayoutData(new GridData(GridData.FILL_VERTICAL));
			aUp.setBackground(tde.gui.colorsSwt.PINK_L92);
			aUp.setForeground(aP.isWarning ? tde.gui.colorsSwt.RED : tde.gui.colorsSwt.CUBAIX_BLUE);
			aUp.setFont(tde.gui.fontsSwt.robotoBold);
			aUp.setData(aP);
			if(!aP.isUnmovable) {
				aUp.setImage(tde.gui.imgsSwt.getIcon("arrowUp.png"));
				tde.gui.tip(aUp, "ProcUp");
				aUp.addListener(SWT.MouseDown,e -> {
					((Clip)aP.targetMedia).upProcessor(aP);
					tde.gui.processorGUI.setProcessors(((Clip)aP.targetMedia).getProcessors());
					tde.gui.monitorGUI.redraw();
				});
			}
			else {
				aUp.setImage(tde.gui.imgsSwt.getIcon("transparent.png"));
			}

			CLabel aDown = new CLabel(aTitleC, SWT.NONE);
			aDown.setLayoutData(new GridData(GridData.FILL_VERTICAL));
			aDown.setBackground(tde.gui.colorsSwt.PINK_L92);
			aDown.setForeground(aP.isWarning ? tde.gui.colorsSwt.RED : tde.gui.colorsSwt.CUBAIX_BLUE);
			aDown.setFont(tde.gui.fontsSwt.robotoBold);
			aDown.setData(aP);
			if(!aP.isUnmovable) {
				aDown.setImage(tde.gui.imgsSwt.getIcon("arrowDown.png"));
				tde.gui.tip(aDown, "ProcDown");
				aDown.addListener(SWT.MouseDown,e -> {
					((Clip)aP.targetMedia).downProcessor(aP);
					tde.gui.processorGUI.setProcessors(((Clip)aP.targetMedia).getProcessors());
					tde.gui.monitorGUI.redraw();
				});
			}
			else {
				aDown.setImage(tde.gui.imgsSwt.getIcon("transparent.png"));
			}

			CLabel aLoad = new CLabel(aTitleC, SWT.NONE);
			aLoad.setLayoutData(new GridData(GridData.FILL_VERTICAL));
			aLoad.setBackground(tde.gui.colorsSwt.PINK_L92);
			aLoad.setForeground(aP.isWarning ? tde.gui.colorsSwt.RED : tde.gui.colorsSwt.CUBAIX_BLUE);
			aLoad.setFont(tde.gui.fontsSwt.robotoBold);
			aLoad.setData(aP);
			aLoad.setImage(tde.gui.imgsSwt.getIcon("open.gif"));
			tde.gui.tip(aLoad, "ProcLoad");
			aLoad.addListener(SWT.MouseDown,e -> {
				loadProcessor(aP);
				tde.gui.processorGUI.setProcessors(((Clip)aP.targetMedia).getProcessors());
				tde.gui.monitorGUI.redraw();
			});

			CLabel aSave = new CLabel(aTitleC, SWT.NONE);
			aSave.setLayoutData(new GridData(GridData.FILL_VERTICAL));
			aSave.setBackground(tde.gui.colorsSwt.PINK_L92);
			aSave.setForeground(aP.isWarning ? tde.gui.colorsSwt.RED : tde.gui.colorsSwt.CUBAIX_BLUE);
			aSave.setFont(tde.gui.fontsSwt.robotoBold);
			aSave.setData(aP);
			aSave.setImage(tde.gui.imgsSwt.getIcon("save.gif"));
			tde.gui.tip(aSave, "ProcSave");
			aSave.addListener(SWT.MouseDown,e -> {
				saveProcessor(aP);
				tde.gui.processorGUI.setProcessors(((Clip)aP.targetMedia).getProcessors());
				tde.gui.monitorGUI.redraw();
			});

			CLabel aDel = new CLabel(aTitleC, SWT.NONE);
			aDel.setLayoutData(new GridData(GridData.FILL_VERTICAL));
			aDel.setBackground(tde.gui.colorsSwt.PINK_L92);
			aDel.setForeground(aP.isWarning ? tde.gui.colorsSwt.RED : tde.gui.colorsSwt.CUBAIX_BLUE);
			aDel.setFont(tde.gui.fontsSwt.robotoBold);
			aDel.setData(aP);
			if(!aP.isUndeletable) {
				aDel.setImage(tde.gui.imgsSwt.getIcon("trash.png"));
				tde.gui.tip(aDel, "ProcDelete");
				aDel.addListener(SWT.MouseDown,e -> {
					((Clip)aP.targetMedia).removeProcessor(aP);
					aBox.dispose();
					tde.gui.monitorGUI.redraw();
					tde.gui.timeLinesGUI.redraw();
				});
			}
			else {
				aDel.setImage(tde.gui.imgsSwt.getIcon("transparent.png"));
			}

			if(aP.isExpended) {
				createProcessorGUI(aBox, aP);
			}
		}
		showFocus();

		container.layout(true);
		updateScroll();
	}
	
	void createProcessorGUI(Composite aBox,Processor aP) {
		String aClassP = aP.getClassName();
		if("TimeController".equals(aClassP)) {
			aBox.setData(new TimeControllerGUI(tde, aBox,aP));
			return;
		}
		if("Shapes".equals(aClassP)) {
			aBox.setData(new ShapesGUI(tde, aBox,aP));
			return;
		}
		if("Subtitles".equals(aClassP)) {
			aBox.setData(new SubtitlesGUI(tde, aBox,aP));
			return;
		}
		if("SceneCamera".equals(aClassP) || "Scene3D".equals(aClassP)) {
			aBox.setData(new com.cubaix.TDenlive.GUI.processors.SceneCameraGUI(tde, aBox,aP));
			return;
		}
		if("Objects3D".equals(aClassP)) {
			aBox.setData(new com.cubaix.TDenlive.GUI.processors.Objects3DGUI(tde, aBox,aP));
			return;
		}
		if("StereoAligner".equals(aClassP)) {
			aBox.setData(new StereoAlignerGUI(tde, aBox,aP));
			return;
		}
		if("ColorAdapter".equals(aClassP)) {
			aBox.setData(new ColorAdapterGUI(tde, aBox,aP));
			return;
		}
		if("Depthmap".equals(aClassP)) {
			aBox.setData(new DepthmapGUI(tde, aBox,aP));
			return;
		}
		if("DepthmapStereo".equals(aClassP)) {
			aBox.setData(new DepthmapStereoGUI(tde, aBox,aP));
			return;
		}
		if("Placer".equals(aClassP)) {
			aBox.setData(new PlacerGUI(tde, aBox,aP));
			return;
		}
		if("Scripter".equals(aClassP)) {
			aBox.setData(new ScripterGUI(tde, aBox,aP));
			return;
		}
		if("Cropper".equals(aClassP)) {
			aBox.setData(new CropperGUI(tde, aBox,aP));
			return;
		}
		if("Transparency".equals(aClassP)) {
			aBox.setData(new TransparencyGUI(tde, aBox,aP));
			return;
		}
		if("Chromakey".equals(aClassP)) {
			aBox.setData(new ChromakeyGUI(tde, aBox,aP));
			return;
		}
		if("Framing".equals(aClassP)) {
			aBox.setData(new FramingGUI(tde, aBox,aP));
			return;
		}
		if("BrightnessContrast".equals(aClassP)) {
			aBox.setData(new BrightnessContrastGUI(tde, aBox,aP));
			return;
		}
		if("HueSaturation".equals(aClassP)) {
			aBox.setData(new HueSaturationGUI(tde, aBox,aP));
			return;
		}
		if("LensCorrection".equals(aClassP)) {
			aBox.setData(new LensCorrectionGUI(tde, aBox,aP));
			return;
		}
		if("Volume".equals(aClassP)) {
			aBox.setData(new VolumeGUI(tde, aBox,aP));
			return;
		}
		if("Speed".equals(aClassP)) {
			aBox.setData(new SpeedGUI(tde, aBox,aP));
			return;
		}
		if(aP instanceof com.cubaix.TDenlive.processors.ParamProcessor) {
			aBox.setData(new com.cubaix.TDenlive.GUI.processors.ParamProcessorGUI(tde, aBox,aP));
			return;
		}
		if(aP instanceof com.cubaix.TDenlive.processors.MonitorProcessor) {
			aBox.setData(new com.cubaix.TDenlive.GUI.processors.MonitorProcessorGUI(tde, aBox,aP));
			return;
		}
		
		//Simply create a label
		Label aL = new Label(aBox, SWT.NONE);
		aL.setText("-----??-----");
		aL.setAlignment(SWT.CENTER);
		aL.setLayoutData(new GridData(GridData.FILL_BOTH));
	}
	
	void loadProcessor(Processor aP) {
		FileDialog aFD = new FileDialog (tde.gui.shell, SWT.OPEN);
		String [] filterNames = new String [] {"Processor "+aP.getClassName()};
		String [] filterExtensions = new String [] {"*.3Dp"};
		String filterPath = tde.tdeDir;
		aFD.setFilterNames (filterNames);
		aFD.setFilterExtensions (filterExtensions);
		aFD.setFilterPath (filterPath);
		String aPath = aFD.open ();
		System.out.println ("Path: " + aPath);
		if(aPath == null) {
			return;
		}

		try {
			XmlMinimalParser aXMP = new XmlMinimalParser();
			Vector<XmlObject> aOs = aXMP.parse(aPath);
			aP.openProject(aOs, 0);
		}
		catch(Throwable t) {
			t.printStackTrace(System.err);
		}
	}

	void saveProcessor(Processor aP) {
		FileDialog aFD = new FileDialog (tde.gui.shell, SWT.SAVE);
		String [] filterNames = new String [] {"Processor "+aP.getClassName()};
		String [] filterExtensions = new String [] {"*.3Dp"};
		String filterPath = tde.tdeDir;
		aFD.setFilterNames (filterNames);
		aFD.setFilterExtensions (filterExtensions);
		aFD.setFilterPath (filterPath);
		aFD.setFileName("New."+aP.getClassName()+".3Dp");
		String aPath = aFD.open ();
		System.out.println ("Path: " + aPath);
		if(aPath == null) {
			return;
		}
		if(!aPath.toLowerCase().endsWith(".3dp")) {
			aPath += ".3Dp";
		}
		try {
			StringBuffer aSB = new StringBuffer();
			aP.saveProject(aSB);
			BufferedWriter aBW = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(aPath), "UTF-8"));
			aBW.write(aSB.toString());
			aBW.flush();
			aBW.close();
		}
		catch(Throwable t) {
			t.printStackTrace(System.err);
		}
	}



	/**
	 * (De)activate a processor; a speed processor changes the time in the media: the clip keeps the
	 * same end in its media, so its duration on the time line changes
	 */
	void setActive(Processor aP,boolean aActive) {
		if(aP.targetMedia instanceof Clip && aP.getClassName().equals("Speed")) {
			((Clip)aP.targetMedia).keepMediaEnd(() -> aP.isActive = aActive);
			tde.gui.timeLinesGUI.redraw();
		}
		else {
			aP.isActive = aActive;
		}
		tde.gui.monitorGUI.redraw();
	}
}
