package com.cubaix.TDenlive.GUI.processors;

import org.eclipse.swt.SWT;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Label;

import com.cubaix.TDenlive.TDenlive;
import com.cubaix.TDenlive.GUI.widgets.LabelKeyFrame;
import com.cubaix.TDenlive.GUI.widgets.LabelScale;
import com.cubaix.TDenlive.medias.Clip;
import com.cubaix.TDenlive.processors.Processor;
import com.cubaix.TDenlive.processors.Volume;

public class VolumeGUI extends ProcessorGUI {
	static final int GAIN_SCALE = 10000;//Slider value for a gain of 1.0 (100%)
	Volume vol = null;
	Composite wgt = null;
	LabelKeyFrame lkf = null;
	LabelScale gainLS = null;
	LabelScale panLS = null;

	public VolumeGUI(TDenlive aTDe, Composite aParent,Processor aP) {
		super(aTDe, aParent,aP);
		vol = (Volume)proc;
		createContents();
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

		int aSelectedKey = vol.getPrevKeyGT(tde.timeLineStack.getTimePosMS());
		if(aSelectedKey < 0) {
			aSelectedKey = 0;
		}

		lkf = new LabelKeyFrame(tde,wgt,vol,"K") {
			@Override
			public void change(int aValue) {
				if(aValue >= 0) {
					gainLS.setSelection((int)(vol.getGain(aValue)*GAIN_SCALE));
					panLS.setSelection((int)(vol.getPan(aValue)*GAIN_SCALE));
				}
			}
			@Override
			public void move(int aTime) {
				tde.timeLineStack.setRequestedTimePosMS(((Clip)vol.targetMedia).getStartTimeMS()+aTime);
				tde.gui.timeLinesGUI.redraw();
				tde.gui.monitorGUI.redraw();
			}
		};
		lkf.setSelection(aSelectedKey);

		Label aGLb = new Label(wgt,SWT.NONE);
		tde.gui.applyColorFont(aGLb);
		aGLb.setLayoutData(new GridData(GridData.FILL_HORIZONTAL));
		aGLb.setText(tde.gui.lngs.get("Volume.Gain"));

		gainLS = new LabelScale(tde,wgt,tde.gui.lngs.get("Volume.GainLetter")
				,0,GAIN_SCALE,(int)(Volume.GAIN_MAX*GAIN_SCALE),(int)(vol.getGain(aSelectedKey)*GAIN_SCALE)) {
			@Override
			public void change(int aValue) {
				if(lkf.getSelection() >= 0) {
					vol.setGain(lkf.editKey(),aValue/(double)GAIN_SCALE);
				}
			}
		}.tip("Gain");

		Label aPL = new Label(wgt,SWT.NONE);
		tde.gui.applyColorFont(aPL);
		aPL.setLayoutData(new GridData(GridData.FILL_HORIZONTAL));
		aPL.setText(tde.gui.lngs.get("Volume.Pan"));

		panLS = new LabelScale(tde,wgt,tde.gui.lngs.get("Volume.PanLetter")
				,-GAIN_SCALE,0,GAIN_SCALE,(int)(vol.getPan(aSelectedKey)*GAIN_SCALE)) {
			@Override
			public void change(int aValue) {
				if(lkf.getSelection() >= 0) {
					vol.setPan(lkf.editKey(),aValue/(double)GAIN_SCALE);
				}
			}
		}.tip("Pan");

		tde.timeLineStack.addTimeListener(this);
	}

	@Override
	public void timeChanged(long aTimeNew) {
		super.timeChanged(aTimeNew);
		if(wgt.isDisposed()) {
			tde.timeLineStack.removeTimeListener(this);
			return;
		}
		int aSelectedKey = vol.getPrevKeyGT(tde.timeLineStack.getTimePosMS());
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
				gainLS.setSelection((int)(vol.getGain(aKey)*GAIN_SCALE));
				panLS.setSelection((int)(vol.getPan(aKey)*GAIN_SCALE));
				lkf.checkAddMode(tde.timeLineStack.getTimePosMS());
			}
		});
	}
}
