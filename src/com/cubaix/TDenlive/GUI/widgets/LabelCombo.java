package com.cubaix.TDenlive.GUI.widgets;

import java.util.Vector;

import org.eclipse.swt.SWT;
import org.eclipse.swt.events.SelectionEvent;
import org.eclipse.swt.events.SelectionListener;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Combo;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Label;

import com.cubaix.TDenlive.TDenlive;

public abstract class LabelCombo extends Widget {
	String label = "";
	Vector<String[]> valTexts = new Vector<String[]>();
	String init = "";
	Combo combo = null;

	public LabelCombo(TDenlive aTDe, Composite aParent,String aLabel,Vector<String[]> aValTexts,String aInit) {
		super(aTDe, aParent);
		label = aLabel;
		valTexts = aValTexts;
		init = aInit;
		createContents();
	}
	
	@Override
	void createContents() {
		GridLayout aGL = new GridLayout(3,false);
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

		Label aL = new Label(container, SWT.NONE);
		aL.setBackground(tde.gui.colorsSwt.WHITE);
		aL.setForeground(tde.gui.colorsSwt.CUBAIX_BLUE);
		GridData aLGD = new GridData(GridData.FILL_HORIZONTAL);
		aLGD.heightHint = 16;
		aL.setLayoutData(aLGD);
		aL.setText(label);
		aL.setFont(tde.gui.fontsSwt.roboto);

		combo = new Combo(container, SWT.DROP_DOWN|SWT.READ_ONLY);
		GridData aCGD = new GridData(GridData.HORIZONTAL_ALIGN_END|GridData.VERTICAL_ALIGN_CENTER);
		aCGD.heightHint = 25;
		combo.setLayoutData(aCGD);
		combo.setBackground(tde.gui.colorsSwt.WHITE);
		combo.setForeground(tde.gui.colorsSwt.CUBAIX_BLUE);
		combo.setFont(tde.gui.fontsSwt.roboto);
		int aSelected = -1;
		String[] aVals = new String[valTexts.size()];
		for(int v = 0;v < aVals.length;v++) {
			aVals[v] = valTexts.elementAt(v)[1];
			if(valTexts.elementAt(v)[0].equals(init)) {
				aSelected = v;
			}
		}
		combo.setItems(aVals);
		if(aSelected >= 0) {
			combo.select(aSelected);
		}
		combo.addSelectionListener(new SelectionListener() {
			@Override
			public void widgetSelected(SelectionEvent e) {
				change(valTexts.elementAt(combo.getSelectionIndex())[0]);
			}
			@Override
			public void widgetDefaultSelected(SelectionEvent e) {
				change(valTexts.elementAt(combo.getSelectionIndex())[0]);
			}
		});
	}

	public void select(String aVal) {
		for(int v = 0;v < valTexts.size();v++) {
			if(valTexts.elementAt(v)[0].equals(aVal)) {
				combo.select(v);
				return;
			}
		}
	}
	
	public abstract void change(String aValue);

}
