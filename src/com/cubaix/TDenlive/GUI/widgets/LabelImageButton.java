package com.cubaix.TDenlive.GUI.widgets;

import java.util.Vector;

import org.eclipse.swt.SWT;
import org.eclipse.swt.events.MouseEvent;
import org.eclipse.swt.events.MouseListener;
import org.eclipse.swt.events.SelectionEvent;
import org.eclipse.swt.events.SelectionListener;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Combo;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Label;

import com.cubaix.TDenlive.TDenlive;

public abstract class LabelImageButton extends Widget {
	String label = "";
	String iconName = "";
	Vector<String[]> valTexts = new Vector<String[]>();
	String init = "";

	public LabelImageButton(TDenlive aTDe, Composite aParent,String aLabel,String aIconName) {
		super(aTDe, aParent);
		label = aLabel;
		iconName = aIconName;
		createContents();
	}

	@Override
	void createContents() {
		GridLayout aGL = new GridLayout(4,false);
		aGL.marginLeft = aGL.marginTop = aGL.marginRight = aGL.marginBottom = 0;
		aGL.verticalSpacing = aGL.horizontalSpacing = 0;
		aGL.marginWidth = aGL.marginHeight = 0;
		container.setLayout(aGL);
		container.setBackground(tde.gui.colorsSwt.WHITE);
		container.setForeground(tde.gui.colorsSwt.CUBAIX_BLUE);

		Composite aSpace = new Composite(container, SWT.SEPARATOR);
		aSpace.setBackground(tde.gui.colorsSwt.WHITE);
		aSpace.setForeground(tde.gui.colorsSwt.CUBAIX_BLUE);
		GridData aSGD = new GridData(GridData.HORIZONTAL_ALIGN_CENTER | GridData.VERTICAL_ALIGN_CENTER); 
		aSGD.widthHint = 3;
		aSGD.heightHint = 20;//First element set the real height of the Widget
		aSpace.setLayoutData(aSGD);

		Label aB = new Label(container,SWT.NONE);
		aB.setBackground(tde.gui.colorsSwt.WHITE);
		aB.setForeground(tde.gui.colorsSwt.CUBAIX_BLUE);
		GridData aBGB = new GridData(GridData.FILL_VERTICAL);
		aBGB.widthHint = 16;
		aBGB.heightHint = 16;
		aB.setLayoutData(aBGB);
		aB.setImage(tde.gui.imgsSwt.getIcon(iconName));
		aB.addListener(SWT.MouseDown,e -> {
			clicked();
		});

		Composite aSpace2 = new Composite(container, SWT.SEPARATOR);
		aSpace2.setBackground(tde.gui.colorsSwt.WHITE);
		aSpace2.setForeground(tde.gui.colorsSwt.CUBAIX_BLUE);
		GridData aSGD2 = new GridData(GridData.HORIZONTAL_ALIGN_CENTER | GridData.VERTICAL_ALIGN_CENTER); 
		aSGD2.widthHint = 3;
		aSGD2.heightHint = 16;
		aSpace2.setLayoutData(aSGD2);
		
		Label aL = new Label(container, SWT.NONE);
		aL.setBackground(tde.gui.colorsSwt.WHITE);
		aL.setForeground(tde.gui.colorsSwt.CUBAIX_BLUE);
		aL.setFont(tde.gui.fontsSwt.roboto);
		GridData aLGD = new GridData(GridData.FILL_HORIZONTAL|GridData.VERTICAL_ALIGN_CENTER);
		aLGD.heightHint = 12;
		aL.setLayoutData(aLGD);
		aL.setText(label);
		aL.setFont(tde.gui.fontsSwt.roboto);
		aL.addListener(SWT.MouseDown,e -> {
			clicked();
		});
	}

	public abstract void clicked();

}
