package com.cubaix.TDenlive.GUI.processors;

import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.CLabel;
import org.eclipse.swt.events.SelectionEvent;
import org.eclipse.swt.events.SelectionListener;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;

import com.cubaix.TDenlive.TDenlive;
import com.cubaix.TDenlive.GUI.widgets.LabelScale;
import com.cubaix.TDenlive.processors.Cropper;
import com.cubaix.TDenlive.processors.Processor;

public class CropperGUI extends ProcessorGUI {
	Cropper crp = null;

	public CropperGUI(TDenlive aTDe, Composite aParent,Processor aP) {
		super(aTDe, aParent,aP);
		crp = (Cropper)proc;
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

		final Button aDrawCropRectS = new Button(aWGT, SWT.CHECK);
		tde.gui.applyColorFont(aDrawCropRectS);
		GridData aDCSGD = new GridData(GridData.FILL_HORIZONTAL);
		aDCSGD.heightHint = 20;
		aDrawCropRectS.setLayoutData(aDCSGD);
		aDrawCropRectS.setSelection(crp.isShowCutLines());
		aDrawCropRectS.addSelectionListener(new SelectionListener() {
			@Override
			public void widgetSelected(SelectionEvent arg0) {
				crp.setShowCutLines(aDrawCropRectS.getSelection());
				tde.gui.monitorGUI.redraw();
			}
			@Override
			public void widgetDefaultSelected(SelectionEvent arg0) {
				crp.setShowCutLines(aDrawCropRectS.getSelection());
				tde.gui.monitorGUI.redraw();
			}
		});
		aDrawCropRectS.setText(tde.gui.lngs.get("Cropper.ShowCutLines"));
		aDrawCropRectS.setBackground(tde.gui.colorsSwt.WHITE);

		CLabel aLeft = new CLabel(aWGT, SWT.NONE);
		tde.gui.applyColorFont(aLeft);
		aLeft.setLayoutData(new GridData(GridData.FILL_HORIZONTAL));
		aLeft.setText(tde.gui.lngs.get("Cropper.LeftBorder"));
		
		new LabelScale(tde,aWGT,tde.gui.lngs.get("Cropper.LeftL"),0,0,10000,(int)(crp.getLeftL()*10000)) {
			@Override
			public void change(int aValue) {
				crp.setLeftL(aValue/10000.0);
				tde.gui.monitorGUI.redraw();
			}
		};
		new LabelScale(tde,aWGT,tde.gui.lngs.get("Cropper.LeftR"),0,0,10000,(int)(crp.getLeftR()*10000)) {
			@Override
			public void change(int aValue) {
				crp.setLeftR(aValue/10000.0);
				tde.gui.monitorGUI.redraw();
			}
		};

		CLabel aRight = new CLabel(aWGT, SWT.NONE);
		tde.gui.applyColorFont(aRight);
		aRight.setLayoutData(new GridData(GridData.FILL_HORIZONTAL));
		aRight.setText(tde.gui.lngs.get("Cropper.RightBorder"));

		new LabelScale(tde,aWGT,tde.gui.lngs.get("Cropper.RightL"),0,10000,10000,(int)(crp.getRightL()*10000)) {
			@Override
			public void change(int aValue) {
				crp.setRightL(aValue/10000.0);
				tde.gui.monitorGUI.redraw();
			}
		};
		new LabelScale(tde,aWGT,tde.gui.lngs.get("Cropper.RightR"),0,10000,10000,(int)(crp.getRightR()*10000)) {
			@Override
			public void change(int aValue) {
				crp.setRightR(aValue/10000.0);
				tde.gui.monitorGUI.redraw();
			}
		};
		
		CLabel aTopBottom = new CLabel(aWGT, SWT.NONE);
		tde.gui.applyColorFont(aTopBottom);
		aTopBottom.setLayoutData(new GridData(GridData.FILL_HORIZONTAL));
		aTopBottom.setText(tde.gui.lngs.get("Cropper.TopBottomBorder"));

		new LabelScale(tde,aWGT,tde.gui.lngs.get("Cropper.Top"),0,10000,10000,(int)((1.0-crp.getTop())*10000)) {
			@Override
			public void change(int aValue) {
				crp.setTop(1-(aValue/10000.0));
				tde.gui.monitorGUI.redraw();
			}
		};
		new LabelScale(tde,aWGT,tde.gui.lngs.get("Cropper.Bottom"),0,0,10000,(int)((1-crp.getBottom())*10000)) {
			@Override
			public void change(int aValue) {
				crp.setBottom(1.0-(aValue/10000.0));
				tde.gui.monitorGUI.redraw();
			}
		};

		CLabel aRot = new CLabel(aWGT, SWT.NONE);
		tde.gui.applyColorFont(aRot);
		aRot.setLayoutData(new GridData(GridData.FILL_HORIZONTAL));
		aRot.setText(tde.gui.lngs.get("Cropper.RotationBorder"));

		new LabelScale(tde,aWGT,tde.gui.lngs.get("Cropper.Rotation"),-90000,0,90000,(int)(crp.getAngle()*1000)) {
			@Override
			public void change(int aValue) {
				crp.setAngle(aValue/1000.0);
				tde.gui.monitorGUI.redraw();
			}
		};

		CLabel aShear = new CLabel(aWGT, SWT.NONE);
		tde.gui.applyColorFont(aShear);
		aShear.setLayoutData(new GridData(GridData.FILL_HORIZONTAL));
		aShear.setText(tde.gui.lngs.get("Cropper.ShearX"));

		new LabelScale(tde,aWGT,tde.gui.lngs.get("Cropper.ShearXL"),-10000,0,10000,(int)(crp.getShearX()*50000)) {
			@Override
			public void change(int aValue) {
				crp.setShearX(aValue/50000.0);
				tde.gui.monitorGUI.redraw();
			}
		};
		new LabelScale(tde,aWGT,tde.gui.lngs.get("Cropper.ShearXR"),-10000,0,10000,(int)(crp.getShearY()*50000)) {
			@Override
			public void change(int aValue) {
				crp.setShearY(aValue/50000.0);
				tde.gui.monitorGUI.redraw();
			}
		};

		CLabel aTransp = new CLabel(aWGT, SWT.NONE);
		tde.gui.applyColorFont(aTransp);
		aTransp.setLayoutData(new GridData(GridData.FILL_HORIZONTAL));
		aTransp.setText(tde.gui.lngs.get("Cropper.Transparency"));

		new LabelScale(tde,aWGT,tde.gui.lngs.get("Cropper.TransparencyLetter"),0,10000,10000,(int)(crp.getTransparency()*10000)) {
			@Override
			public void change(int aValue) {
				crp.setTransparency(aValue/10000.0);
				tde.gui.monitorGUI.redraw();
			}
		};
	}
	
	int scale2bar(double aValue) {
		if(aValue <= 1) {
			return (int)((Math.pow(aValue,1/2.0)-1.0)*10000);
		}
		return (int)((Math.pow(((aValue-1.0)*10)+1.0,1/10.0)-1.0)*10000);
	}
	
	double bar2Scale(int aValue) {
		if(aValue <= 0) {
			return Math.pow(((aValue/10000.0)+1.0),2.0);
		}
		return 1.0+(Math.pow(((aValue/10000.0)+1.0),10.0)-1.0)/10;
	}
}
