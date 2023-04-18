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
import com.cubaix.TDenlive.GUI.processors.StereoAlignerGUI;
import com.cubaix.TDenlive.GUI.processors.TimeControllerGUI;
import com.cubaix.TDenlive.GUI.processors.TransparencyGUI;
import com.cubaix.TDenlive.medias.Clip;
import com.cubaix.TDenlive.processors.Processor;
import com.cubaix.TDenlive.xml.XmlMinimalParser;
import com.cubaix.TDenlive.xml.XmlObject;

public class ProcessorsGUI extends CompositePanel {

	public ProcessorsGUI(TDenlive aTDe, Composite aParent) {
		super(aTDe, aParent);
		createContents(aParent);
	}

	void createContents(Composite aParent) {
	}

	public void setProcessors(Vector<Processor> aProcessors) {
		clean();
		
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
		aSC.addControlListener(ControlListener.controlResizedAdapter(e -> {
			Rectangle r = aSC.getClientArea();
			Point aS = aContainer.computeSize(r.width, SWT.DEFAULT);
			aSC.setMinSize(aS);
		}));
		aSC.setAlwaysShowScrollBars(true);
		
		boolean aIsFirst = true;
		for(Processor aP : aProcessors) {
			final Composite aBox = new Composite (aContainer, SWT.NULL);
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
			aTitleC.setLayoutData(new GridData(GridData.FILL_HORIZONTAL));
			GridLayout aLayoutTC = new GridLayout(7,false);
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
			aTitle.addListener(SWT.MouseDown, new Listener() {
				@Override
				public void handleEvent(Event arg0) {
					if(aBox.getData() == null) {
						createProcessorGUI(aBox, aP);
						
						aP.isExpended = true;
						
						aContainer.layout(true);
					}
					else {
						ProcessorGUI aPG = (ProcessorGUI)aBox.getData();
						aPG.container.dispose();
						aBox.setData(null);

						aP.isExpended = false;

						aContainer.layout(true);
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
				aAllUp.addListener(SWT.MouseDown,e -> {
					for(Processor p : aProcessors) {
						p.isExpended = false;
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
				aActCB.setSelection(aP.isActive);
				aActCB.addSelectionListener(new SelectionListener() {
					@Override
					public void widgetSelected(SelectionEvent arg0) {
						aP.isActive = aActCB.getSelection();
						tde.gui.monitorGUI.redraw();
					}
					@Override
					public void widgetDefaultSelected(SelectionEvent arg0) {
						aP.isActive = aActCB.getSelection();
						tde.gui.monitorGUI.redraw();
					}
				});
			}
			else {
				CLabel aSPC = new CLabel(aTitleC,SWT.NONE);
				tde.gui.applyColorFont(aSC);
				aSPC.setBackground(tde.gui.colorsSwt.PINK_L92);
			}

			CLabel aUp = new CLabel(aTitleC, SWT.NONE);
			aUp.setLayoutData(new GridData(GridData.FILL_VERTICAL));
			aUp.setBackground(tde.gui.colorsSwt.PINK_L92);
			aUp.setForeground(aP.isWarning ? tde.gui.colorsSwt.RED : tde.gui.colorsSwt.CUBAIX_BLUE);
			aUp.setFont(tde.gui.fontsSwt.robotoBold);
			aUp.setData(aP);
			if(!aP.isUnmovable) {
				aUp.setImage(tde.gui.imgsSwt.getIcon("arrowUp.png"));
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
				aDel.addListener(SWT.MouseDown,e -> {
					((Clip)aP.targetMedia).removeProcessor(aP);
					aBox.dispose();
					tde.gui.monitorGUI.redraw();
				});
			}
			else {
				aDel.setImage(tde.gui.imgsSwt.getIcon("transparent.png"));
			}

			if(aP.isExpended) {
				createProcessorGUI(aBox, aP);
			}
		}

		container.layout(true);
	}
	
	void createProcessorGUI(Composite aBox,Processor aP) {
		String aClassP = aP.getClassName();
		if("TimeController".equals(aClassP)) {
			aBox.setData(new TimeControllerGUI(tde, aBox,aP));
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


}
