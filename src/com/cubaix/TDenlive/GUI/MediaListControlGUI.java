package com.cubaix.TDenlive.GUI;

import java.util.Vector;

import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.CLabel;
import org.eclipse.swt.events.MouseEvent;
import org.eclipse.swt.events.MouseListener;
import org.eclipse.swt.graphics.RGB;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Composite;

import com.cubaix.TDenlive.TDConfig;
import com.cubaix.TDenlive.TDenlive;
import com.cubaix.TDenlive.shapes.Shape;

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
		aTB.heightHint = tde.gui.h(25);
		toolBar.setLayoutData(aTB);
		tde.gui.applyColorFont(toolBar);
		GridLayout aGL = new GridLayout(5, false);
		tde.gui.applyNoMargin(aGL);
		toolBar.setLayout(aGL);
		
		addMedia = new CLabel(toolBar,SWT.NONE);
		addMedia.setImage(tde.gui.imgsSwt.getIcon("add.gif"));
		addMedia.setAlignment(SWT.CENTER);
		GridData aEdgesGD = new GridData(GridData.HORIZONTAL_ALIGN_CENTER);
		aEdgesGD.widthHint = 32;
		aEdgesGD.heightHint = tde.gui.h(25);
		addMedia.setLayoutData(aEdgesGD);
		tde.gui.applyColorFont(addMedia);
		tde.gui.tip(addMedia, "AddMedia");
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
		aColorGD.heightHint = tde.gui.h(25);
		addColor.setLayoutData(aColorGD);
		tde.gui.applyColorFont(addColor);
		tde.gui.tip(addColor, "AddColor");
		addColor.addMouseListener(new MouseListener() {
			@Override
			public void mouseUp(MouseEvent arg0) {}
			@Override
			public void mouseDown(MouseEvent arg0) {
				RGB aRGB = new ColorBckGUI(tde, lastRGB).open();
				if(aRGB != null) {
					lastRGB = aRGB;
					tde.mediaList.createColorBck(aRGB.red,aRGB.green,aRGB.blue,TDConfig.PROCESSING_MODE_RENDER);
					tde.gui.mediaListGUI.redraw();
				}
			}
			@Override
			public void mouseDoubleClick(MouseEvent arg0) {}
		});
		
		CLabel addShapes = new CLabel(toolBar,SWT.NONE);
		addShapes.setImage(tde.gui.imgsSwt.getIcon("colorObjects.png"));
		addShapes.setAlignment(SWT.CENTER);
		GridData aShapesGD = new GridData(GridData.HORIZONTAL_ALIGN_CENTER);
		aShapesGD.widthHint = 32;
		aShapesGD.heightHint = tde.gui.h(25);
		addShapes.setLayoutData(aShapesGD);
		tde.gui.applyColorFont(addShapes);
		tde.gui.tip(addShapes, "AddShapes");
		addShapes.addMouseListener(new MouseListener() {
			@Override
			public void mouseUp(MouseEvent arg0) {}
			@Override
			public void mouseDown(MouseEvent arg0) {
				Vector<Shape> aShapes = new ShapesClipGUI(tde).open();
				if(aShapes != null) {
					tde.mediaList.createShapes(aShapes,TDConfig.PROCESSING_MODE_RENDER);
					tde.gui.mediaListGUI.redraw();
				}
			}
			@Override
			public void mouseDoubleClick(MouseEvent arg0) {}
		});
		
		//AI clip (Wan2.2-TI2V-5B): a text button, AI / IA
		CLabel addAi = new CLabel(toolBar,SWT.NONE);
		addAi.setText(tde.gui.lngs.get("AI.Button"));
		addAi.setAlignment(SWT.CENTER);
		GridData aAiGD = new GridData(GridData.HORIZONTAL_ALIGN_CENTER);
		aAiGD.widthHint = 32;
		aAiGD.heightHint = tde.gui.h(25);
		addAi.setLayoutData(aAiGD);
		tde.gui.applyColorFont(addAi);
		addAi.setFont(tde.gui.fontsSwt.robotoBold);
		tde.gui.tip(addAi, "AddAI");
		addAi.addMouseListener(new MouseListener() {
			@Override
			public void mouseUp(MouseEvent arg0) {}
			@Override
			public void mouseDown(MouseEvent arg0) {
				AiClipGUI.open(tde);
			}
			@Override
			public void mouseDoubleClick(MouseEvent arg0) {}
		});
		
		Composite aSpace = new Composite(toolBar, SWT.SEPARATOR|SWT.FILL);
		aSpace.setBackground(tde.gui.colorsSwt.WHITE);
		aSpace.setForeground(tde.gui.colorsSwt.CUBAIX_BLUE);
		aSpace.setFont(tde.gui.fontsSwt.roboto);
		GridData aSpaceGD = new GridData(GridData.FILL_HORIZONTAL);
		aSpaceGD.heightHint = tde.gui.h(25);
		aSpace.setLayoutData(aSpaceGD);
	}

}
