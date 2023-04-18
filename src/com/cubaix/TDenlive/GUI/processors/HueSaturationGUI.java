package com.cubaix.TDenlive.GUI.processors;

import java.util.Vector;

import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.CLabel;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Composite;

import com.cubaix.TDenlive.TDenlive;
import com.cubaix.TDenlive.GUI.widgets.LabelCombo;
import com.cubaix.TDenlive.GUI.widgets.LabelScale;
import com.cubaix.TDenlive.processors.BrightnessContrast;
import com.cubaix.TDenlive.processors.HueSaturation;
import com.cubaix.TDenlive.processors.Processor;

public class HueSaturationGUI extends ProcessorGUI {
	HueSaturation hs = null;

	public HueSaturationGUI(TDenlive aTDe, Composite aParent,Processor aP) {
		super(aTDe, aParent,aP);
		hs = (HueSaturation)proc;
		createContents();
	}

	void createContents() {
		Composite aWGT = new Composite(container, SWT.NONE);
		aWGT.setLayoutData(new GridData(GridData.FILL_HORIZONTAL));
		GridLayout aGridL = new GridLayout(1, false);
		aGridL.marginLeft = aGridL.marginTop = aGridL.marginRight = aGridL.marginBottom = 0;
		aGridL.verticalSpacing = aGridL.horizontalSpacing = 0;
		aGridL.marginWidth = aGridL.marginHeight = 0;
		aWGT.setLayout(aGridL);
		aWGT.setBackground(tde.gui.colorsSwt.WHITE);
		aWGT.setForeground(tde.gui.colorsSwt.CUBAIX_BLUE);

		Vector<String[]> aAppliesR = new Vector<String[]>();
		aAppliesR.add(new String[] {"0",tde.gui.lngs.get("Generic.LeftLetter")});
		aAppliesR.add(new String[] {"1",tde.gui.lngs.get("Generic.RightLetter")});
		aAppliesR.add(new String[] {"2",tde.gui.lngs.get("Generic.LeftLetter")+" & "+tde.gui.lngs.get("Generic.RightLetter")});
		new LabelCombo(tde,aWGT,tde.gui.lngs.get("HueSaturation.ApplyTo"),aAppliesR,""+hs.getApplyTo()) {
			@Override
			public void change(String aValue) {
				hs.setApplyTo(Integer.parseInt(aValue));
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
		new LabelCombo(tde,aWGT,tde.gui.lngs.get("HueSaturation.Color"),aColorsR,""+hs.getColor()) {
			@Override
			public void change(String aValue) {
				hs.setColor(Integer.parseInt(aValue));
				tde.gui.monitorGUI.redraw();
			}
		};
		
		CLabel aGL = new CLabel(aWGT, SWT.NONE);
		tde.gui.applyColorFont(aGL);
		aGL.setText(tde.gui.lngs.get("HueSaturation.Hue"));
		new LabelScale(tde,aWGT,tde.gui.lngs.get("HueSaturation.HueLetter"),-18000,0,18000,(int)(hs.getHue()*100)) {
			@Override
			public void change(int aValue) {
				hs.setHue(aValue/100.0);
				tde.gui.monitorGUI.redraw();
			}
		};
		CLabel aCL = new CLabel(aWGT, SWT.NONE);
		tde.gui.applyColorFont(aCL);
		aCL.setText(tde.gui.lngs.get("HueSaturation.Saturation"));
		double aSat = hs.getSaturation();
		new LabelScale(tde,aWGT,tde.gui.lngs.get("HueSaturation.SaturationLetter"),-10000,0,10000
				,(int)(aSat >= 1.0 ? (aSat-1.0)*1000:(aSat-1.0)*10000)) {
			@Override
			public void change(int aValue) {
				hs.setSaturation(aValue >= 0 ? 1.0+aValue/1000.0 : 1.0+aValue/10000.0);
				tde.gui.monitorGUI.redraw();
			}
		};
		CLabel aBL = new CLabel(aWGT, SWT.NONE);
		tde.gui.applyColorFont(aBL);
		aBL.setText(tde.gui.lngs.get("HueSaturation.Brightness"));
		double aBrt = hs.getBrightness();
		new LabelScale(tde,aWGT,tde.gui.lngs.get("HueSaturation.BrightnessLetter"),-10000,0,10000
				,(int)((aBrt >= 1.0?(aBrt-1.0):1.0-1.0/aBrt)*4000)) {
			@Override
			public void change(int aValue) {
				hs.setBrightness(aValue >= 0 ? 1.0+aValue/4000.0:1.0/(1.0-aValue/4000.0));
				tde.gui.monitorGUI.redraw();
			}
		};
		CLabel aTL = new CLabel(aWGT, SWT.NONE);
		tde.gui.applyColorFont(aTL);
		aTL.setText(tde.gui.lngs.get("HueSaturation.Threshold"));
		new LabelScale(tde,aWGT,tde.gui.lngs.get("HueSaturation.ThresholdLetter"),-10000,0,20000
				,(int)(hs.getThreshold()*10000)) {
			@Override
			public void change(int aValue) {
				hs.setThreshold(aValue / 10000.0);
				tde.gui.monitorGUI.redraw();
			}
		};
		CLabel aRL = new CLabel(aWGT, SWT.NONE);
		tde.gui.applyColorFont(aRL);
		aRL.setText(tde.gui.lngs.get("HueSaturation.Range"));
		new LabelScale(tde,aWGT,tde.gui.lngs.get("HueSaturation.RangeLetter"),0,3000,36000
				,(int)(hs.getRange()*100)) {
			@Override
			public void change(int aValue) {
				hs.setRange(aValue / 100.0);
				tde.gui.monitorGUI.redraw();
			}
		};
	}
}
