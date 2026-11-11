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
import com.cubaix.TDenlive.processors.BrightnessContrast;
import com.cubaix.TDenlive.processors.Processor;

public class BrightnessContrastGUI extends ProcessorGUI {
	BrightnessContrast bc = null;
	Composite wgt = null;
	LabelKeyFrame lkf = null;
	LabelCombo applyLC = null;
	LabelCombo colorLC = null;
	LabelScale brightnessLS = null;
	LabelScale contrastLS = null;
	LabelScale gammaLS = null;
	LabelScale thLS = null;

	public BrightnessContrastGUI(TDenlive aTDe, Composite aParent,Processor aP) {
		super(aTDe, aParent,aP);
		bc = (BrightnessContrast)proc;
		createContents();
	}

	/** Shows the values of the key aKey */
	void show(int aKey) {
		applyLC.select(""+bc.getApplyTo(aKey));
		colorLC.select(""+bc.getColor(aKey));
		brightnessLS.setSelection((int)(bc.getBrightness(aKey)*10000));
		contrastLS.setSelection((int)(bc.getContrast(aKey)*1000));
		gammaLS.setSelection((int)(bc.getGamma(aKey)*10000));
		thLS.setSelection((int)(bc.getTh(aKey)*10000));
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

		int aSelectedKey = bc.getPrevKeyGT(tde.timeLineStack.getTimePosMS());
		if(aSelectedKey < 0) {
			aSelectedKey = 0;
		}

		lkf = new LabelKeyFrame(tde,wgt,bc,"K") {
			@Override
			public void change(int aValue) {
				if(aValue >= 0) {
					show(aValue);
				}
			}
			@Override
			public void move(int aTime) {
				tde.timeLineStack.setRequestedTimePosMS(((Clip)bc.targetMedia).getStartTimeMS()+aTime);
				tde.gui.timeLinesGUI.redraw();
				tde.gui.monitorGUI.redraw();
			}
		};
		lkf.setSelection(aSelectedKey);

		Vector<String[]> aAppliesR = new Vector<String[]>();
		aAppliesR.add(new String[] {"0",tde.gui.lngs.get("Generic.LeftLetter")});
		aAppliesR.add(new String[] {"1",tde.gui.lngs.get("Generic.RightLetter")});
		aAppliesR.add(new String[] {"2",tde.gui.lngs.get("Generic.LeftLetter")+" & "+tde.gui.lngs.get("Generic.RightLetter")});
		applyLC = new LabelCombo(tde,wgt,tde.gui.lngs.get("BrightnessContrast.ApplyTo"),aAppliesR,""+bc.getApplyTo(aSelectedKey)) {
			@Override
			public void change(String aValue) {
				lkf.edit(aEK -> bc.setApplyTo(aEK, Integer.parseInt(aValue)));
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
		colorLC = new LabelCombo(tde,wgt,tde.gui.lngs.get("BrightnessContrast.Color"),aColorsR,""+bc.getColor(aSelectedKey)) {
			@Override
			public void change(String aValue) {
				lkf.edit(aEK -> bc.setColor(aEK, Integer.parseInt(aValue)));
				tde.gui.monitorGUI.redraw();
			}
		};
		CLabel abrightnessLSL = new CLabel(wgt, SWT.NONE);
		tde.gui.applyColorFont(abrightnessLSL);
		abrightnessLSL.setText(tde.gui.lngs.get("BrightnessContrast.Brightness"));
		brightnessLS = new LabelScale(tde,wgt,tde.gui.lngs.get("BrightnessContrast.BrightnessLetter"),-10000,0,10000,(int)(bc.getBrightness(aSelectedKey)*10000)) {
			@Override
			public void change(int aValue) {
				if(lkf.getSelection() >= 0) {
					lkf.edit(aEK -> bc.setBrightness(aEK, aValue/10000.0));
					tde.gui.monitorGUI.redraw();
				}
			}
		}.tip("Brightness");
		CLabel acontrastLSL = new CLabel(wgt, SWT.NONE);
		tde.gui.applyColorFont(acontrastLSL);
		acontrastLSL.setText(tde.gui.lngs.get("BrightnessContrast.Contrast"));
		contrastLS = new LabelScale(tde,wgt,tde.gui.lngs.get("BrightnessContrast.ContrastLetter"),-10000,0,10000,(int)(bc.getContrast(aSelectedKey)*1000)) {
			@Override
			public void change(int aValue) {
				if(lkf.getSelection() >= 0) {
					lkf.edit(aEK -> bc.setContrast(aEK, aValue/1000.0));
					tde.gui.monitorGUI.redraw();
				}
			}
		}.tip("Contrast");
		CLabel agammaLSL = new CLabel(wgt, SWT.NONE);
		tde.gui.applyColorFont(agammaLSL);
		agammaLSL.setText(tde.gui.lngs.get("BrightnessContrast.Gamma"));
		gammaLS = new LabelScale(tde,wgt,tde.gui.lngs.get("BrightnessContrast.GammaLetter"),-10000,0,10000,(int)(bc.getGamma(aSelectedKey)*10000)) {
			@Override
			public void change(int aValue) {
				if(lkf.getSelection() >= 0) {
					lkf.edit(aEK -> bc.setGamma(aEK, aValue/10000.0));
					tde.gui.monitorGUI.redraw();
				}
			}
		}.tip("Gamma");
		CLabel athLSL = new CLabel(wgt, SWT.NONE);
		tde.gui.applyColorFont(athLSL);
		athLSL.setText(tde.gui.lngs.get("BrightnessContrast.Th"));
		thLS = new LabelScale(tde,wgt,tde.gui.lngs.get("BrightnessContrast.ThLetter"),-10000,0,10000,(int)(bc.getTh(aSelectedKey)*10000)) {
			@Override
			public void change(int aValue) {
				if(lkf.getSelection() >= 0) {
					lkf.edit(aEK -> bc.setTh(aEK, aValue/10000.0));
					tde.gui.monitorGUI.redraw();
				}
			}
		}.tip("SmoothContrast");

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
		int aSelectedKey = bc.getPrevKeyGT(tde.timeLineStack.getTimePosMS());
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
