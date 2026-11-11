package com.cubaix.TDenlive.GUI.processors;

import java.util.Vector;

import org.eclipse.swt.SWT;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Composite;

import com.cubaix.TDenlive.TDenlive;
import com.cubaix.TDenlive.GUI.widgets.LabelCombo;
import com.cubaix.TDenlive.GUI.widgets.LabelKeyFrame;
import com.cubaix.TDenlive.GUI.widgets.LabelScale;
import com.cubaix.TDenlive.medias.Clip;
import com.cubaix.TDenlive.processors.ColorAdapter;
import com.cubaix.TDenlive.processors.Processor;

public class ColorAdapterGUI extends ProcessorGUI {
	ColorAdapter ca = null;
	Composite wgt = null;
	LabelKeyFrame lkf = null;
	LabelCombo lcR = null;
	LabelScale lsR = null;
	LabelCombo lcC = null;
	LabelScale lsC = null;

	public ColorAdapterGUI(TDenlive aTDe, Composite aParent, Processor aP) {
		super(aTDe, aParent, aP);
		ca = (ColorAdapter)aP;
		createContents();
	}

	/** Shows the values of the key aKey */
	void show(int aKey) {
		lcR.select(ca.getPresetR(aKey));
		lsR.setSelection((int)((1.0-ca.gettRH(aKey))*10000));
		lcC.select(ca.getPresetC(aKey));
		lsC.setSelection((int)(ca.gettRL(aKey)*10000));
	}

	void createContents() {
		wgt = new Composite(container, SWT.NONE);
		wgt.setLayoutData(new GridData(GridData.FILL_HORIZONTAL));
		GridLayout aGL = new GridLayout(1, false);
		aGL.marginLeft = aGL.marginTop = aGL.marginRight = aGL.marginBottom = 0;
		aGL.verticalSpacing = aGL.horizontalSpacing = 0;
		aGL.marginWidth = aGL.marginHeight = 0;
		wgt.setLayout(aGL);
		wgt.setBackground(tde.gui.colorsSwt.WHITE);
		wgt.setForeground(tde.gui.colorsSwt.CUBAIX_BLUE);

		int aSelectedKey = ca.getPrevKeyGT(tde.timeLineStack.getTimePosMS());
		if(aSelectedKey < 0) {
			aSelectedKey = 0;
		}

		lkf = new LabelKeyFrame(tde,wgt,ca,"K") {
			@Override
			public void change(int aValue) {
				if(aValue >= 0) {
					show(aValue);
				}
			}
			@Override
			public void move(int aTime) {
				tde.timeLineStack.setRequestedTimePosMS(((Clip)ca.targetMedia).getStartTimeMS()+aTime);
				tde.gui.timeLinesGUI.redraw();
				tde.gui.monitorGUI.redraw();
			}
		};
		lkf.setSelection(aSelectedKey);

		Vector<String[]> aValTextsR = new Vector<String[]>();
		String[] aValsR = ca.getPresetListR();
		for(String aVal : aValsR) {
			aValTextsR.add(new String[] {aVal,tde.gui.lngs.get("ColorAdapter."+aVal)});
		}
		lcR = new LabelCombo(tde,wgt,tde.gui.lngs.get("ColorAdapter.RedAdapt"),aValTextsR,ca.getPresetR(aSelectedKey)) {
			@Override
			public void change(String aValue) {
				lkf.edit(aEK -> ca.setPresetR(aEK, aValue));
				int aShow = lkf.displayKey(lkf.getSelection());
				if(aShow >= 0) {
					lsR.setSelection((int)((1.0-ca.gettRH(aShow))*10000));
				}
				tde.gui.monitorGUI.redraw();
			}
		};

		lsR = new LabelScale(tde,wgt,tde.gui.lngs.get("ColorAdapter.Quantity"),0,0,5000,(int)((1.0-ca.gettRH(aSelectedKey))*10000)) {
			@Override
			public void change(int aValue) {
				if(lkf.getSelection() >= 0) {
					lkf.edit(aEK -> ca.settRH(aEK, 1.0 - aValue/10000.0));
					tde.gui.monitorGUI.redraw();
				}
			}
		}.tip("AdaptRed");

		Vector<String[]> aValTextsC = new Vector<String[]>();
		String[] aValsC = ca.getPresetListC();
		for(String aVal : aValsC) {
			aValTextsC.add(new String[] {aVal,tde.gui.lngs.get("ColorAdapter."+aVal)});
		}
		lcC = new LabelCombo(tde,wgt,tde.gui.lngs.get("ColorAdapter.CyanAdapt"),aValTextsC,ca.getPresetC(aSelectedKey)) {
			@Override
			public void change(String aValue) {
				lkf.edit(aEK -> ca.setPresetC(aEK, aValue));
				int aShow = lkf.displayKey(lkf.getSelection());
				if(aShow >= 0) {
					lsC.setSelection((int)(ca.gettRL(aShow)*10000));
				}
				tde.gui.monitorGUI.redraw();
			}
		};

		lsC = new LabelScale(tde,wgt,tde.gui.lngs.get("ColorAdapter.Quantity"),0,0,5000,(int)(ca.gettRL(aSelectedKey)*10000)) {
			@Override
			public void change(int aValue) {
				if(lkf.getSelection() >= 0) {
					lkf.edit(aEK -> ca.settRL(aEK, aValue/10000.0));
					tde.gui.monitorGUI.redraw();
				}
			}
		}.tip("AdaptCyan");

		lkf.change(lkf.displayKey(lkf.getSelection()));
		tde.timeLineStack.addTimeListener(this);
	}

	@Override
	public void timeChanged(long aTimeNew) {
		super.timeChanged(aTimeNew);
		if(wgt.isDisposed()) {
			tde.timeLineStack.removeTimeListener(this);
			return;
		}
		int aSelectedKey = ca.getPrevKeyGT(tde.timeLineStack.getTimePosMS());
		if(aSelectedKey < 0) {
			aSelectedKey = 0;
		}
		final int aKey = aSelectedKey;
		tde.gui.display.asyncExec(new Runnable() {
			@Override
			public void run() {
				if(wgt.isDisposed()) {
					return;
				}
				lkf.setSelection(aKey);
				show(lkf.displayKey(aKey));
				lkf.checkAddMode(tde.timeLineStack.getTimePosMS());
			}
		});
	}
}
