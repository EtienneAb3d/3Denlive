package com.cubaix.TDenlive.GUI.processors;

import java.text.NumberFormat;

import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.CLabel;
import org.eclipse.swt.events.ModifyEvent;
import org.eclipse.swt.events.ModifyListener;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Text;

import com.cubaix.TDenlive.TDenlive;
import com.cubaix.TDenlive.GUI.widgets.LabelKeyFrame;
import com.cubaix.TDenlive.GUI.widgets.LabelScale;
import com.cubaix.TDenlive.GUI.widgets.LabelText;
import com.cubaix.TDenlive.medias.Clip;
import com.cubaix.TDenlive.processors.LensCorrection;
import com.cubaix.TDenlive.processors.Processor;

public class LensCorrectionGUI extends ProcessorGUI {
	LensCorrection lc = null;
	Composite wgt = null;
	LabelKeyFrame lkf = null;
	LabelScale k1LS = null;
	LabelText k1LT = null;
	LabelScale k2LS = null;
	LabelText k2LT = null;
	LabelScale cxLS = null;
	LabelText cxLT = null;
	LabelScale cyLS = null;
	LabelText cyLT = null;
	LabelScale k1LRLS = null;
	LabelText k1LRLT = null;
	LabelScale k2LRLS = null;
	LabelText k2LRLT = null;
	LabelScale cxLRLS = null;
	LabelText cxLRLT = null;
	LabelScale cyLRLS = null;
	LabelText cyLRLT = null;

	public LensCorrectionGUI(TDenlive aTDe, Composite aParent,Processor aP) {
		super(aTDe, aParent,aP);
		lc = (LensCorrection)proc;
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

		int aSelectedKey = lc.getPrevKeyGT(tde.timeLineStack.getTimePosMS());
		if(aSelectedKey < 0) {
			aSelectedKey = 0;
		}

		lkf = new LabelKeyFrame(tde,wgt,lc,"K") {
			@Override
			public void change(int aValue) {
				if(aValue >= 0) {
					k1LS.setSelection((int)(lc.getK1(aValue)*10000));
					k1LT.setValue(""+lc.getK1(aValue));
					k2LS.setSelection((int)(lc.getK2(aValue)*10000));
					k2LT.setValue(""+lc.getK2(aValue));
					cxLS.setSelection((int)(lc.getCX(aValue)*10000));
					cxLT.setValue(""+lc.getCX(aValue));
					cyLS.setSelection((int)(lc.getCY(aValue)*10000));
					cyLT.setValue(""+lc.getCY(aValue));
					k1LRLS.setSelection((int)(lc.getK1LR(aValue)*10000));
					k1LRLT.setValue(""+lc.getK1LR(aValue));
					k2LRLS.setSelection((int)(lc.getK2LR(aValue)*10000));
					k2LRLT.setValue(""+lc.getK2LR(aValue));
					cxLRLS.setSelection((int)(lc.getCXLR(aValue)*10000));
					cxLRLT.setValue(""+lc.getCXLR(aValue));
					cyLRLS.setSelection((int)(lc.getCYLR(aValue)*10000));
					cyLRLT.setValue(""+lc.getCYLR(aValue));
					tde.gui.monitorGUI.redraw();
				}
			}
			@Override
			public void move(int aTime) {
				tde.timeLineStack.setRequestedTimePosMS(((Clip)lc.targetMedia).getStartTimeMS()+aTime);
				tde.gui.timeLinesGUI.redraw();
				tde.gui.monitorGUI.redraw();
			}
		};
		lkf.setSelection(aSelectedKey);

		k1LS = new LabelScale(tde,wgt,"K1",-10000,0,10000,(int)(lc.getK1(aSelectedKey)*10000)) {
			@Override
			public void change(int aValue) {
				if(lkf.getSelection() >= 0) {
					lc.setK1(lkf.getSelection(),aValue/10000.0);
					k1LT.setValue(""+aValue/10000.0);
					tde.gui.monitorGUI.redraw();
				}
			}
		};

		k2LS = new LabelScale(tde,wgt,"K2",-10000,0,10000,(int)(lc.getK2(aSelectedKey)*10000)) {
			@Override
			public void change(int aValue) {
				if(lkf.getSelection() >= 0) {
					lc.setK2(lkf.getSelection(),aValue/10000.0);
					k2LT.setValue(""+aValue/10000.0);
					tde.gui.monitorGUI.redraw();
				}
			}
		};
		
		cxLS = new LabelScale(tde,wgt,"CX",0,5000,10000,(int)(lc.getCX(aSelectedKey)*10000)) {
			@Override
			public void change(int aValue) {
				if(lkf.getSelection() >= 0) {
					lc.setCX(lkf.getSelection(),aValue/10000.0);
					cxLT.setValue(""+aValue/10000.0);
					tde.gui.monitorGUI.redraw();
				}
			}
		};

		cyLS = new LabelScale(tde,wgt,"CY",0,5000,10000,(int)(lc.getCY(aSelectedKey)*10000)) {
			@Override
			public void change(int aValue) {
				if(lkf.getSelection() >= 0) {
					lc.setCY(lkf.getSelection(),aValue/10000.0);
					cyLT.setValue(""+aValue/10000.0);
					tde.gui.monitorGUI.redraw();
				}
			}
		};

		Composite aTKs = new Composite(wgt, SWT.NONE);
		tde.gui.applyColorFont(aTKs);
		GridData aTksGD = new GridData(GridData.FILL_HORIZONTAL);
		aTKs.setLayoutData(aTksGD);
		GridLayout aTKsGL = new GridLayout(4,true);
		tde.gui.applyNoMargin(aTKsGL);
		aTKs.setLayout(aTKsGL);
		
		k1LT = new LabelText(tde, aTKs,"K1=",""+lc.getK1(aSelectedKey),SWT.LEFT) {
			@Override
			public void change(String aValue) {
				if(lkf.getSelection() < 0) {
					return;
				}
				String aTxt = ""+lc.getK1(lkf.getSelection());
				if(aValue.equals(aTxt)) {
					//No real change
					return;
				}
				double aV = -10;//Force invalide value
				try {
					aV = Double.parseDouble(aValue);
				}
				catch(Throwable t) {
					//??
				}
				if(aV < -1.0 || aV > 1) {
					k1LT.setWarning(true);
				}
				else {
					k1LT.setWarning(false);
					lc.setK1(lkf.getSelection(),aV);
					k1LS.setSelection((int)(aV*10000));
					tde.gui.monitorGUI.redraw();
				}
			}
		};
		
		k2LT = new LabelText(tde, aTKs,"K2=",""+lc.getK2(aSelectedKey),SWT.LEFT) {
			
			@Override
			public void change(String aValue) {
				if(lkf.getSelection() < 0) {
					return;
				}
				String aTxt = ""+lc.getK2(lkf.getSelection());
				if(aValue.equals(aTxt)) {
					//No real change
					return;
				}
				double aV = -10;//Force invalide value
				try {
					aV = Double.parseDouble(aValue);
				}
				catch(Throwable t) {
					//??
				}
				if(aV < -1.0 || aV > 1) {
					k2LT.setWarning(true);
				}
				else {
					k2LT.setWarning(false);
					lc.setK2(lkf.getSelection(),aV);
					k2LS.setSelection((int)(aV*10000));
					tde.gui.monitorGUI.redraw();
				}
			}
		};
		
		cxLT = new LabelText(tde, aTKs,"CX=",""+lc.getCX(aSelectedKey),SWT.LEFT) {
			@Override
			public void change(String aValue) {
				if(lkf.getSelection() < 0) {
					return;
				}
				String aTxt = ""+lc.getCX(lkf.getSelection());
				if(aValue.equals(aTxt)) {
					//No real change
					return;
				}
				double aV = -10;//Force invalide value
				try {
					aV = Double.parseDouble(aValue);
				}
				catch(Throwable t) {
					//??
				}
				if(aV < 0.0 || aV > 1) {
					cxLT.setWarning(true);
				}
				else {
					cxLT.setWarning(false);
					lc.setCX(lkf.getSelection(),aV);
					cxLS.setSelection((int)(aV*10000));
					tde.gui.monitorGUI.redraw();
				}
			}
		};
		
		cyLT = new LabelText(tde, aTKs,"CY=",""+lc.getCY(aSelectedKey),SWT.LEFT) {
			@Override
			public void change(String aValue) {
				if(lkf.getSelection() < 0) {
					return;
				}
				String aTxt = ""+lc.getCY(lkf.getSelection());
				if(aValue.equals(aTxt)) {
					//No real change
					return;
				}
				double aV = -10;//Force invalide value
				try {
					aV = Double.parseDouble(aValue);
				}
				catch(Throwable t) {
					//??
				}
				if(aV < 0.0 || aV > 1) {
					cyLT.setWarning(true);
				}
				else {
					cyLT.setWarning(false);
					lc.setCY(lkf.getSelection(),aV);
					cyLS.setSelection((int)(aV*10000));
					tde.gui.monitorGUI.redraw();
				}
			}
		};
		
		CLabel aLRL = new CLabel(wgt, SWT.NONE);
		tde.gui.applyColorFont(aLRL);
		aLRL.setLayoutData(new GridData(GridData.FILL_HORIZONTAL));
		aLRL.setText(tde.gui.lngs.get("LensCorrection.LeftToRight"));

		k1LRLS = new LabelScale(tde,wgt,"K1",-10000,0,10000,(int)(lc.getK1LR(aSelectedKey)*10000)) {
			@Override
			public void change(int aValue) {
				if(lkf.getSelection() >= 0) {
					lc.setK1LR(lkf.getSelection(),aValue/10000.0);
					k1LRLT.setValue(""+aValue/10000.0);
					tde.gui.monitorGUI.redraw();
				}
			}
		};

		k2LRLS = new LabelScale(tde,wgt,"K2",-10000,0,10000,(int)(lc.getK2LR(aSelectedKey)*10000)) {
			@Override
			public void change(int aValue) {
				if(lkf.getSelection() >= 0) {
					lc.setK2LR(lkf.getSelection(),aValue/10000.0);
					k2LRLT.setValue(""+aValue/10000.0);
					tde.gui.monitorGUI.redraw();
				}
			}
		};
		
		cxLRLS = new LabelScale(tde,wgt,"CX",-10000,0,10000,(int)(lc.getCXLR(aSelectedKey)*10000)) {
			@Override
			public void change(int aValue) {
				if(lkf.getSelection() >= 0) {
					lc.setCXLR(lkf.getSelection(),aValue/10000.0);
					cxLRLT.setValue(""+aValue/10000.0);
					tde.gui.monitorGUI.redraw();
				}
			}
		};

		cyLRLS = new LabelScale(tde,wgt,"CY",-10000,0,10000,(int)(lc.getCYLR(aSelectedKey)*10000)) {
			@Override
			public void change(int aValue) {
				if(lkf.getSelection() >= 0) {
					lc.setCYLR(lkf.getSelection(),aValue/10000.0);
					cyLRLT.setValue(""+aValue/10000.0);
					tde.gui.monitorGUI.redraw();
				}
			}
		};

		Composite aTKLRs = new Composite(wgt, SWT.NONE);
		tde.gui.applyColorFont(aTKLRs);
		GridData aTkLRsGD = new GridData(GridData.FILL_HORIZONTAL);
		aTKLRs.setLayoutData(aTkLRsGD);
		GridLayout aTKLRsGL = new GridLayout(4,true);
		tde.gui.applyNoMargin(aTKLRsGL);
		aTKLRs.setLayout(aTKLRsGL);
		
		k1LRLT = new LabelText(tde, aTKLRs,"K1=",""+lc.getK1LR(aSelectedKey),SWT.LEFT) {
			@Override
			public void change(String aValue) {
				if(lkf.getSelection() < 0) {
					return;
				}
				String aTxt = ""+lc.getK1LR(lkf.getSelection());
				if(aValue.equals(aTxt)) {
					//No real change
					return;
				}
				double aV = -10;//Force invalide value
				try {
					aV = Double.parseDouble(aValue);
				}
				catch(Throwable t) {
					//??
				}
				if(aV < -1.0 || aV > 1) {
					k1LRLT.setWarning(true);
				}
				else {
					k1LRLT.setWarning(false);
					lc.setK1LR(lkf.getSelection(),aV);
					k1LRLS.setSelection((int)(aV*10000));
					tde.gui.monitorGUI.redraw();
				}
			}
		};
		
		k2LRLT = new LabelText(tde, aTKLRs,"K2=",""+lc.getK2LR(aSelectedKey),SWT.LEFT) {
			
			@Override
			public void change(String aValue) {
				if(lkf.getSelection() < 0) {
					return;
				}
				String aTxt = ""+lc.getK2LR(lkf.getSelection());
				if(aValue.equals(aTxt)) {
					//No real change
					return;
				}
				double aV = -10;//Force invalide value
				try {
					aV = Double.parseDouble(aValue);
				}
				catch(Throwable t) {
					//??
				}
				if(aV < -1.0 || aV > 1) {
					k2LRLT.setWarning(true);
				}
				else {
					k2LRLT.setWarning(false);
					lc.setK2LR(lkf.getSelection(),aV);
					k2LRLS.setSelection((int)(aV*10000));
					tde.gui.monitorGUI.redraw();
				}
			}
		};
		
		cxLRLT = new LabelText(tde, aTKLRs,"CX=",""+lc.getCXLR(aSelectedKey),SWT.LEFT) {
			@Override
			public void change(String aValue) {
				if(lkf.getSelection() < 0) {
					return;
				}
				String aTxt = ""+lc.getCXLR(lkf.getSelection());
				if(aValue.equals(aTxt)) {
					//No real change
					return;
				}
				double aV = -10;//Force invalide value
				try {
					aV = Double.parseDouble(aValue);
				}
				catch(Throwable t) {
					//??
				}
				if(aV < -1.0 || aV > 1) {
					cxLRLT.setWarning(true);
				}
				else {
					cxLRLT.setWarning(false);
					lc.setCXLR(lkf.getSelection(),aV);
					cxLRLS.setSelection((int)(aV*10000));
					tde.gui.monitorGUI.redraw();
				}
			}
		};
		
		cyLRLT = new LabelText(tde, aTKLRs,"CY=",""+lc.getCYLR(aSelectedKey),SWT.LEFT) {
			@Override
			public void change(String aValue) {
				if(lkf.getSelection() < 0) {
					return;
				}
				String aTxt = ""+lc.getCYLR(lkf.getSelection());
				if(aValue.equals(aTxt)) {
					//No real change
					return;
				}
				double aV = -10;//Force invalide value
				try {
					aV = Double.parseDouble(aValue);
				}
				catch(Throwable t) {
					//??
				}
				if(aV < -1.0 || aV > 1) {
					cyLRLT.setWarning(true);
				}
				else {
					cyLRLT.setWarning(false);
					lc.setCYLR(lkf.getSelection(),aV);
					cyLRLS.setSelection((int)(aV*10000));
					tde.gui.monitorGUI.redraw();
				}
			}
		};
		
		tde.timeLineStack.addTimeListener(this);
	}
	
	@Override
	public void timeChanged(long aTimeNew) {
		super.timeChanged(aTimeNew);
		if(wgt.isDisposed()) {
			tde.timeLineStack.removeTimeListener(this);
			return;
		}
		int aSelectedKey = lc.getPrevKeyGT(tde.timeLineStack.getTimePosMS());
		if(aSelectedKey < 0) {
			aSelectedKey = 0;
		}
		final int aKey = aSelectedKey;
		tde.gui.display.asyncExec(new Runnable() {
			@Override
			public void run() {
				lkf.setSelection(aKey);
				lkf.checkAddMode(tde.timeLineStack.getTimePosMS());
				k1LS.setSelection((int)(lc.getK1(aKey)*10000));
				k1LT.setValue(""+lc.getK1(aKey));
				k2LS.setSelection((int)(lc.getK2(aKey)*10000));
				k2LT.setValue(""+lc.getK2(aKey));
				cxLS.setSelection((int)(lc.getCX(aKey)*10000));
				cxLT.setValue(""+lc.getCX(aKey));
				cyLS.setSelection((int)(lc.getCY(aKey)*10000));
				cyLT.setValue(""+lc.getCY(aKey));
				k1LRLS.setSelection((int)(lc.getK1LR(aKey)*10000));
				k1LRLT.setValue(""+lc.getK1LR(aKey));
				k2LRLS.setSelection((int)(lc.getK2LR(aKey)*10000));
				k2LRLT.setValue(""+lc.getK2LR(aKey));
				cxLRLS.setSelection((int)(lc.getCXLR(aKey)*10000));
				cxLRLT.setValue(""+lc.getCXLR(aKey));
				cyLRLS.setSelection((int)(lc.getCYLR(aKey)*10000));
				cyLRLT.setValue(""+lc.getCYLR(aKey));
			}
		});
	}
}
