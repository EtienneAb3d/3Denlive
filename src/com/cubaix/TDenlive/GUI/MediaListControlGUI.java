package com.cubaix.TDenlive.GUI;

import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.CLabel;
import org.eclipse.swt.events.MouseEvent;
import org.eclipse.swt.events.MouseListener;
import org.eclipse.swt.graphics.RGB;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.ColorDialog;
import org.eclipse.swt.widgets.Composite;

import com.cubaix.TDenlive.TDConfig;
import com.cubaix.TDenlive.TDenlive;

public class MediaListControlGUI extends CompositePanel {
	Composite toolBar = null;
	CLabel addMedia = null;
	RGB lastRGB = null;

	public MediaListControlGUI(TDenlive aTDe, Composite aParent) {
		super(aTDe, aParent);
		createContents();
	}
	
	void createContents() {
		toolBar = new Composite(container, SWT.NULL);
		GridData aTB = new GridData(GridData.FILL_HORIZONTAL);
		aTB.heightHint = 25;
		toolBar.setLayoutData(aTB);
		tde.gui.applyColorFont(toolBar);
		GridLayout aGL = new GridLayout(3, false);
		tde.gui.applyNoMargin(aGL);
		toolBar.setLayout(aGL);
		
		addMedia = new CLabel(toolBar,SWT.NONE);
		addMedia.setImage(tde.gui.imgsSwt.getIcon("add.gif"));
		addMedia.setAlignment(SWT.CENTER);
		GridData aEdgesGD = new GridData(GridData.HORIZONTAL_ALIGN_CENTER);
		aEdgesGD.widthHint = 32;
		aEdgesGD.heightHint = 25;
		addMedia.setLayoutData(aEdgesGD);
		tde.gui.applyColorFont(addMedia);
		addMedia.addMouseListener(new MouseListener() {
			@Override
			public void mouseUp(MouseEvent arg0) {}
			@Override
			public void mouseDown(MouseEvent arg0) {
				OpenMediaGUI aMOG = new OpenMediaGUI(tde);
			}
			@Override
			public void mouseDoubleClick(MouseEvent arg0) {}
		});
		
		CLabel addColor = new CLabel(toolBar,SWT.NONE);
		addColor.setImage(tde.gui.imgsSwt.getIcon("colourPalette.gif"));
		addColor.setAlignment(SWT.CENTER);
		GridData aColorGD = new GridData(GridData.HORIZONTAL_ALIGN_CENTER);
		aColorGD.widthHint = 32;
		aColorGD.heightHint = 25;
		addColor.setLayoutData(aColorGD);
		tde.gui.applyColorFont(addColor);
		addColor.addMouseListener(new MouseListener() {
			@Override
			public void mouseUp(MouseEvent arg0) {}
			@Override
			public void mouseDown(MouseEvent arg0) {
				ColorDialog aCD = new ColorDialog(tde.gui.shell);
				if(lastRGB != null) {
					aCD.setRGB(lastRGB);
				}
				RGB aRGB = aCD.open();
				if(aRGB != null) {
					lastRGB = aRGB;
					tde.mediaList.createColorBck(aRGB.red,aRGB.green,aRGB.blue,TDConfig.PROCESSING_MODE_RENDER);
					tde.gui.mediaListGUI.redraw();
				}
			}
			@Override
			public void mouseDoubleClick(MouseEvent arg0) {}
		});
		
		Composite aSpace = new Composite(toolBar, SWT.SEPARATOR|SWT.FILL);
		aSpace.setBackground(tde.gui.colorsSwt.WHITE);
		aSpace.setForeground(tde.gui.colorsSwt.CUBAIX_BLUE);
		aSpace.setFont(tde.gui.fontsSwt.roboto);
		GridData aSpaceGD = new GridData(GridData.FILL_HORIZONTAL);
		aSpaceGD.heightHint = 25;
		aSpace.setLayoutData(aSpaceGD);
	}

}
