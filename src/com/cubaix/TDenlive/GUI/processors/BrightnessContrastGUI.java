package com.cubaix.TDenlive.GUI.processors;

import java.util.Vector;

import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.CLabel;
import org.eclipse.swt.events.SelectionEvent;
import org.eclipse.swt.events.SelectionListener;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;

import com.cubaix.TDenlive.TDenlive;
import com.cubaix.TDenlive.GUI.widgets.LabelCombo;
import com.cubaix.TDenlive.GUI.widgets.LabelScale;
import com.cubaix.TDenlive.processors.BrightnessContrast;
import com.cubaix.TDenlive.processors.Processor;

public class BrightnessContrastGUI extends ProcessorGUI {
	BrightnessContrast bc = null;

	public BrightnessContrastGUI(TDenlive aTDe, Composite aParent,Processor aP) {
		super(aTDe, aParent,aP);
		bc = (BrightnessContrast)proc;
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
		new LabelCombo(tde,aWGT,tde.gui.lngs.get("BrightnessContrast.ApplyTo"),aAppliesR,""+bc.getApplyTo()) {
			@Override
			public void change(String aValue) {
				bc.setApplyTo(Integer.parseInt(aValue));
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
		new LabelCombo(tde,aWGT,tde.gui.lngs.get("BrightnessContrast.Color"),aColorsR,""+bc.getColor()) {
			@Override
			public void change(String aValue) {
				bc.setColor(Integer.parseInt(aValue));
				tde.gui.monitorGUI.redraw();
			}
		};
		
		CLabel aBL = new CLabel(aWGT, SWT.NONE);
		tde.gui.applyColorFont(aBL);
		aBL.setText(tde.gui.lngs.get("BrightnessContrast.Brightness"));
		new LabelScale(tde,aWGT,tde.gui.lngs.get("BrightnessContrast.BrightnessLetter"),-10000,0,10000,(int)(bc.getBrightness()*10000)) {
			@Override
			public void change(int aValue) {
				bc.setBrightness(aValue/10000.0);
				tde.gui.monitorGUI.redraw();
			}
		};
		CLabel aCL = new CLabel(aWGT, SWT.NONE);
		tde.gui.applyColorFont(aCL);
		aCL.setText(tde.gui.lngs.get("BrightnessContrast.Contrast"));
		new LabelScale(tde,aWGT,tde.gui.lngs.get("BrightnessContrast.ContrastLetter"),-10000,0,10000,(int)(bc.getContrast()*1000)) {
			@Override
			public void change(int aValue) {
				bc.setContrast(aValue/1000.0);
				tde.gui.monitorGUI.redraw();
			}
		};
		CLabel aGL = new CLabel(aWGT, SWT.NONE);
		tde.gui.applyColorFont(aGL);
		aGL.setText(tde.gui.lngs.get("BrightnessContrast.Gamma"));
		new LabelScale(tde,aWGT,tde.gui.lngs.get("BrightnessContrast.GammaLetter"),-10000,0,10000,(int)(bc.getGamma()*10000)) {
			@Override
			public void change(int aValue) {
				bc.setGamma(aValue/10000.0);
				tde.gui.monitorGUI.redraw();
			}
		};
		CLabel aTL = new CLabel(aWGT, SWT.NONE);
		tde.gui.applyColorFont(aTL);
		aTL.setText(tde.gui.lngs.get("BrightnessContrast.Th"));
		new LabelScale(tde,aWGT,tde.gui.lngs.get("BrightnessContrast.ThLetter"),-10000,0,10000,(int)(bc.getTh()*10000)) {
			@Override
			public void change(int aValue) {
				bc.setTh(aValue/10000.0);
				tde.gui.monitorGUI.redraw();
			}
		};
	}
}
