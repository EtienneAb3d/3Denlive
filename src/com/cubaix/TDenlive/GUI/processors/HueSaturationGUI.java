package com.cubaix.TDenlive.GUI.processors;

import java.util.Vector;

import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.CLabel;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Composite;

import com.cubaix.TDenlive.TDenlive;
import com.cubaix.TDenlive.GUI.widgets.LabelCombo;
import com.cubaix.TDenlive.GUI.widgets.LabelKeyFrame;
import com.cubaix.TDenlive.GUI.widgets.LabelScale;
import com.cubaix.TDenlive.medias.Clip;
import com.cubaix.TDenlive.processors.HueSaturation;
import com.cubaix.TDenlive.processors.Processor;

public class HueSaturationGUI extends ProcessorGUI {
	HueSaturation hs = null;
	Composite wgt = null;
	LabelKeyFrame lkf = null;
	LabelCombo applyLC = null;
	LabelCombo colorLC = null;
	LabelScale hueLS = null;
	LabelScale saturationLS = null;
	LabelScale brightnessLS = null;
	LabelScale thresholdLS = null;
	LabelScale rangeLS = null;

	public HueSaturationGUI(TDenlive aTDe, Composite aParent,Processor aP) {
		super(aTDe, aParent,aP);
		hs = (HueSaturation)proc;
		createContents();
	}

	/** Shows the values of the key aKey */
	void show(int aKey) {
		applyLC.select(""+hs.getApplyTo(aKey));
		colorLC.select(""+hs.getColor(aKey));
		hueLS.setSelection((int)(hs.getHue(aKey)*100));
		saturationLS.setSelection(satToScale(hs.getSaturation(aKey)));
		brightnessLS.setSelection(brtToScale(hs.getBrightness(aKey)));
		thresholdLS.setSelection((int)(hs.getThreshold(aKey)*10000));
		rangeLS.setSelection((int)(hs.getRange(aKey)*100));
	}

	static int satToScale(double aSat) {
		return (int)(aSat >= 1.0 ? (aSat-1.0)*1000:(aSat-1.0)*10000);
	}

	static int brtToScale(double aBrt) {
		return (int)((aBrt >= 1.0?(aBrt-1.0):1.0-1.0/aBrt)*4000);
	}

	void createContents() {
		wgt = new Composite(container, SWT.NONE);
		wgt.setLayoutData(new GridData(GridData.FILL_HORIZONTAL));
		GridLayout aGridL = new GridLayout(1, false);
		aGridL.marginLeft = aGridL.marginTop = aGridL.marginRight = aGridL.marginBottom = 0;
		aGridL.verticalSpacing = aGridL.horizontalSpacing = 0;
		aGridL.marginWidth = aGridL.marginHeight = 0;
		wgt.setLayout(aGridL);
		wgt.setBackground(tde.gui.colorsSwt.WHITE);
		wgt.setForeground(tde.gui.colorsSwt.CUBAIX_BLUE);

		int aSelectedKey = hs.getPrevKeyGT(tde.timeLineStack.getTimePosMS());
		if(aSelectedKey < 0) {
			aSelectedKey = 0;
		}

		lkf = new LabelKeyFrame(tde,wgt,hs,"K") {
			@Override
			public void change(int aValue) {
				if(aValue >= 0) {
					show(aValue);
				}
			}
			@Override
			public void move(int aTime) {
				tde.timeLineStack.setRequestedTimePosMS(((Clip)hs.targetMedia).getStartTimeMS()+aTime);
				tde.gui.timeLinesGUI.redraw();
				tde.gui.monitorGUI.redraw();
			}
		};
		lkf.setSelection(aSelectedKey);

		Vector<String[]> aAppliesR = new Vector<String[]>();
		aAppliesR.add(new String[] {"0",tde.gui.lngs.get("Generic.LeftLetter")});
		aAppliesR.add(new String[] {"1",tde.gui.lngs.get("Generic.RightLetter")});
		aAppliesR.add(new String[] {"2",tde.gui.lngs.get("Generic.LeftLetter")+" & "+tde.gui.lngs.get("Generic.RightLetter")});
		applyLC = new LabelCombo(tde,wgt,tde.gui.lngs.get("HueSaturation.ApplyTo"),aAppliesR,""+hs.getApplyTo(aSelectedKey)) {
			@Override
			public void change(String aValue) {
				lkf.edit(aEK -> hs.setApplyTo(aEK, Integer.parseInt(aValue)));
				tde.gui.monitorGUI.redraw();
			}
		};
		
		String aR = tde.gui.lngs.get("Generic.RedLetter");
		String aG = tde.gui.lngs.get("Generic.GreenLetter");
		String aB = tde.gui.lngs.get("Generic.BlueLetter");
		String aC = tde.gui.lngs.get("Generic.CyanLetter");
		String aM = tde.gui.lngs.get("Generic.MagentaLetter");
		String aY = tde.gui.lngs.get("Generic.YellowLetter");
		Vector<String[]> aColorsR = new Vector<String[]>();
		aColorsR.add(new String[] {"0",aR});
		aColorsR.add(new String[] {"1",aG});
		aColorsR.add(new String[] {"2",aB});
		aColorsR.add(new String[] {"4",aC+" = "+aG+" & "+aB});
		aColorsR.add(new String[] {"5",aM+" = "+aR+" & "+aB});
		aColorsR.add(new String[] {"6",aY+" = "+aR+" & "+aG});
		aColorsR.add(new String[] {"3",aR+" & "+aG+" & "+aB});
		colorLC = new LabelCombo(tde,wgt,tde.gui.lngs.get("HueSaturation.Color"),aColorsR,""+hs.getColor(aSelectedKey)) {
			@Override
			public void change(String aValue) {
				lkf.edit(aEK -> hs.setColor(aEK, Integer.parseInt(aValue)));
				tde.gui.monitorGUI.redraw();
			}
		};
		CLabel ahueLSL = new CLabel(wgt, SWT.NONE);
		tde.gui.applyColorFont(ahueLSL);
		ahueLSL.setText(tde.gui.lngs.get("HueSaturation.Hue"));
		hueLS = new LabelScale(tde,wgt,tde.gui.lngs.get("HueSaturation.HueLetter"),-18000,0,18000,(int)(hs.getHue(aSelectedKey)*100)) {
			@Override
			public void change(int aValue) {
				if(lkf.getSelection() >= 0) {
					lkf.edit(aEK -> hs.setHue(aEK, aValue/100.0));
					tde.gui.monitorGUI.redraw();
				}
			}
		}.tip("Hue");
		CLabel asaturationLSL = new CLabel(wgt, SWT.NONE);
		tde.gui.applyColorFont(asaturationLSL);
		asaturationLSL.setText(tde.gui.lngs.get("HueSaturation.Saturation"));
		saturationLS = new LabelScale(tde,wgt,tde.gui.lngs.get("HueSaturation.SaturationLetter"),-10000,0,10000,satToScale(hs.getSaturation(aSelectedKey))) {
			@Override
			public void change(int aValue) {
				if(lkf.getSelection() >= 0) {
					lkf.edit(aEK -> hs.setSaturation(aEK, aValue >= 0 ? 1.0+aValue/1000.0 : 1.0+aValue/10000.0));
					tde.gui.monitorGUI.redraw();
				}
			}
		}.tip("Saturation");
		CLabel abrightnessLSL = new CLabel(wgt, SWT.NONE);
		tde.gui.applyColorFont(abrightnessLSL);
		abrightnessLSL.setText(tde.gui.lngs.get("HueSaturation.Brightness"));
		brightnessLS = new LabelScale(tde,wgt,tde.gui.lngs.get("HueSaturation.BrightnessLetter"),-10000,0,10000,brtToScale(hs.getBrightness(aSelectedKey))) {
			@Override
			public void change(int aValue) {
				if(lkf.getSelection() >= 0) {
					lkf.edit(aEK -> hs.setBrightness(aEK, aValue >= 0 ? 1.0+aValue/4000.0:1.0/(1.0-aValue/4000.0)));
					tde.gui.monitorGUI.redraw();
				}
			}
		}.tip("HSBrightness");
		CLabel athresholdLSL = new CLabel(wgt, SWT.NONE);
		tde.gui.applyColorFont(athresholdLSL);
		athresholdLSL.setText(tde.gui.lngs.get("HueSaturation.Threshold"));
		thresholdLS = new LabelScale(tde,wgt,tde.gui.lngs.get("HueSaturation.ThresholdLetter"),-10000,0,20000,(int)(hs.getThreshold(aSelectedKey)*10000)) {
			@Override
			public void change(int aValue) {
				if(lkf.getSelection() >= 0) {
					lkf.edit(aEK -> hs.setThreshold(aEK, aValue / 10000.0));
					tde.gui.monitorGUI.redraw();
				}
			}
		}.tip("Threshold");
		CLabel arangeLSL = new CLabel(wgt, SWT.NONE);
		tde.gui.applyColorFont(arangeLSL);
		arangeLSL.setText(tde.gui.lngs.get("HueSaturation.Range"));
		rangeLS = new LabelScale(tde,wgt,tde.gui.lngs.get("HueSaturation.RangeLetter"),0,3000,36000,(int)(hs.getRange(aSelectedKey)*100)) {
			@Override
			public void change(int aValue) {
				if(lkf.getSelection() >= 0) {
					lkf.edit(aEK -> hs.setRange(aEK, aValue / 100.0));
					tde.gui.monitorGUI.redraw();
				}
			}
		}.tip("Range");

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
		int aSelectedKey = hs.getPrevKeyGT(tde.timeLineStack.getTimePosMS());
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
