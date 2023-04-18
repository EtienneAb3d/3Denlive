package com.cubaix.TDenlive.GUI.processors;

import java.util.Vector;

import org.eclipse.swt.SWT;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Composite;

import com.cubaix.TDenlive.TDenlive;
import com.cubaix.TDenlive.GUI.widgets.LabelScale;
import com.cubaix.TDenlive.GUI.widgets.LabelCombo;
import com.cubaix.TDenlive.processors.ColorAdapter;
import com.cubaix.TDenlive.processors.Processor;

public class ColorAdapterGUI extends ProcessorGUI {
	ColorAdapter ca = null;
	LabelScale lsR = null;
	LabelScale lsC = null;

	public ColorAdapterGUI(TDenlive aTDe, Composite aParent, Processor aP) {
		super(aTDe, aParent, aP);
		ca = (ColorAdapter)aP;
		createContents();
	}

	void createContents() {
		Composite aWGT = new Composite(container, SWT.NONE);
		aWGT.setLayoutData(new GridData(GridData.FILL_HORIZONTAL));
		GridLayout aGL = new GridLayout(1, false);
		aGL.marginLeft = aGL.marginTop = aGL.marginRight = aGL.marginBottom = 0;
		aGL.verticalSpacing = aGL.horizontalSpacing = 0;
		aGL.marginWidth = aGL.marginHeight = 0;
		aWGT.setLayout(aGL);
		aWGT.setBackground(tde.gui.colorsSwt.WHITE);
		aWGT.setForeground(tde.gui.colorsSwt.CUBAIX_BLUE);

		Vector<String[]> aValTextsR = new Vector<String[]>();
		String[] aValsR = ca.getPresetListR();
		for(String aVal : aValsR) {
			aValTextsR.add(new String[] {aVal,tde.gui.lngs.get("ColorAdapter."+aVal)});
		}
		new LabelCombo(tde,aWGT,tde.gui.lngs.get("ColorAdapter.RedAdapt"),aValTextsR,ca.getPresetR()) {
			@Override
			public void change(String aValue) {
				ca.setPresetR(aValue);
				lsR.setSelection((int)((1.0-ca.gettRH())*10000));
				tde.gui.monitorGUI.redraw();
			}
		};
		
		lsR = new LabelScale(tde,aWGT,tde.gui.lngs.get("ColorAdapter.Quantity"),0,0,5000,(int)((1.0-ca.gettRH())*10000)) {
			@Override
			public void change(int aValue) {
				ca.settRH(1.0 - aValue/10000.0);
				tde.gui.monitorGUI.redraw();
			}
		};
		
		Vector<String[]> aValTextsC = new Vector<String[]>();
		String[] aValsC = ca.getPresetListC();
		for(String aVal : aValsC) {
			aValTextsC.add(new String[] {aVal,tde.gui.lngs.get("ColorAdapter."+aVal)});
		}
		new LabelCombo(tde,aWGT,tde.gui.lngs.get("ColorAdapter.CyanAdapt"),aValTextsC,ca.getPresetC()) {
			@Override
			public void change(String aValue) {
				ca.setPresetC(aValue);
				lsC.setSelection((int)(ca.gettRL()*10000));
				tde.gui.monitorGUI.redraw();
			}
		};

		lsC = new LabelScale(tde,aWGT,tde.gui.lngs.get("ColorAdapter.Quantity"),0,0,5000,(int)(ca.gettRL()*10000)) {
			@Override
			public void change(int aValue) {
				ca.settRL(aValue/10000.0);
				tde.gui.monitorGUI.redraw();
			}
		};
	}
}
