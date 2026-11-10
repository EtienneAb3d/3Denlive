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
import com.cubaix.TDenlive.GUI.widgets.LabelKeyFrame;
import com.cubaix.TDenlive.GUI.widgets.LabelScale;
import com.cubaix.TDenlive.medias.Clip;
import com.cubaix.TDenlive.processors.Cropper;
import com.cubaix.TDenlive.processors.Processor;

public class CropperGUI extends ProcessorGUI {
	Cropper crp = null;
	Composite wgt = null;
	LabelKeyFrame lkf = null;
	LabelScale leftLLS = null;
	LabelScale leftRLS = null;
	LabelScale rightLLS = null;
	LabelScale rightRLS = null;
	LabelScale topLS = null;
	LabelScale bottomLS = null;
	LabelScale angleLS = null;
	LabelScale shearLLS = null;
	LabelScale shearRLS = null;
	LabelScale transpLS = null;

	public CropperGUI(TDenlive aTDe, Composite aParent,Processor aP) {
		super(aTDe, aParent,aP);
		crp = (Cropper)proc;
		createContents();
	}

	/** Key to modify at the time cursor (see LabelKeyFrame.editKey()) */
	Cropper.Key editKey() {
		return crp.getKey(lkf.editKey());
	}

	void showKey(int aKey) {
		Cropper.Key aK = crp.getKey(aKey);
		leftLLS.setSelection((int)(aK.leftL*10000));
		leftRLS.setSelection((int)(aK.leftR*10000));
		rightLLS.setSelection((int)(aK.rightL*10000));
		rightRLS.setSelection((int)(aK.rightR*10000));
		topLS.setSelection((int)((1.0-aK.top)*10000));
		bottomLS.setSelection((int)((1.0-aK.bottom)*10000));
		angleLS.setSelection((int)(aK.angle*1000));
		shearLLS.setSelection((int)(aK.shearXL*50000));
		shearRLS.setSelection((int)(aK.shearXR*50000));
		transpLS.setSelection((int)(aK.transparency*10000));
	}

	void createContents() {
		wgt = new Composite(container, SWT.NONE);
		Composite aWGT = wgt;
		aWGT.setLayoutData(new GridData(GridData.FILL_HORIZONTAL));
		GridLayout aGL = new GridLayout(1, false);
		aGL.marginLeft = aGL.marginTop = aGL.marginRight = aGL.marginBottom = 0;
		aGL.verticalSpacing = aGL.horizontalSpacing = 0;
		aGL.marginWidth = aGL.marginHeight = 0;
		aWGT.setLayout(aGL);
		aWGT.setBackground(tde.gui.colorsSwt.WHITE);
		aWGT.setForeground(tde.gui.colorsSwt.CUBAIX_BLUE);

		int aSelectedKey = crp.getPrevKeyGT(tde.timeLineStack.getTimePosMS());
		if(aSelectedKey < 0) {
			aSelectedKey = 0;
		}
		Cropper.Key aK = crp.getKey(aSelectedKey);

		final Button aDrawCropRectS = new Button(aWGT, SWT.CHECK);
		tde.gui.applyColorFont(aDrawCropRectS);
		tde.gui.tip(aDrawCropRectS, "ShowCutLines");
		GridData aDCSGD = new GridData(GridData.FILL_HORIZONTAL);
		aDCSGD.heightHint = tde.gui.h(20);
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

		lkf = new LabelKeyFrame(tde,aWGT,crp,"K") {
			@Override
			public void change(int aValue) {
				if(aValue >= 0) {
					showKey(aValue);
				}
			}
			@Override
			public void move(int aTime) {
				tde.timeLineStack.setRequestedTimePosMS(((Clip)crp.targetMedia).getStartTimeMS()+aTime);
				tde.gui.timeLinesGUI.redraw();
				tde.gui.monitorGUI.redraw();
			}
		};
		lkf.setSelection(aSelectedKey);

		CLabel aLeft = new CLabel(aWGT, SWT.NONE);
		tde.gui.applyColorFont(aLeft);
		aLeft.setLayoutData(new GridData(GridData.FILL_HORIZONTAL));
		aLeft.setText(tde.gui.lngs.get("Cropper.LeftBorder"));
		
		leftLLS = new LabelScale(tde,aWGT,tde.gui.lngs.get("Cropper.LeftL"),0,0,10000,(int)(aK.leftL*10000)) {
			@Override
			public void change(int aValue) {
				editKey().leftL = aValue/10000.0;
				tde.gui.monitorGUI.redraw();
			}
		}.tip("CropLeftL");
		leftRLS = new LabelScale(tde,aWGT,tde.gui.lngs.get("Cropper.LeftR"),0,0,10000,(int)(aK.leftR*10000)) {
			@Override
			public void change(int aValue) {
				editKey().leftR = aValue/10000.0;
				tde.gui.monitorGUI.redraw();
			}
		}.tip("CropLeftR");

		CLabel aRight = new CLabel(aWGT, SWT.NONE);
		tde.gui.applyColorFont(aRight);
		aRight.setLayoutData(new GridData(GridData.FILL_HORIZONTAL));
		aRight.setText(tde.gui.lngs.get("Cropper.RightBorder"));

		rightLLS = new LabelScale(tde,aWGT,tde.gui.lngs.get("Cropper.RightL"),0,10000,10000,(int)(aK.rightL*10000)) {
			@Override
			public void change(int aValue) {
				editKey().rightL = aValue/10000.0;
				tde.gui.monitorGUI.redraw();
			}
		}.tip("CropRightL");
		rightRLS = new LabelScale(tde,aWGT,tde.gui.lngs.get("Cropper.RightR"),0,10000,10000,(int)(aK.rightR*10000)) {
			@Override
			public void change(int aValue) {
				editKey().rightR = aValue/10000.0;
				tde.gui.monitorGUI.redraw();
			}
		}.tip("CropRightR");
		
		CLabel aTopBottom = new CLabel(aWGT, SWT.NONE);
		tde.gui.applyColorFont(aTopBottom);
		aTopBottom.setLayoutData(new GridData(GridData.FILL_HORIZONTAL));
		aTopBottom.setText(tde.gui.lngs.get("Cropper.TopBottomBorder"));

		topLS = new LabelScale(tde,aWGT,tde.gui.lngs.get("Cropper.Top"),0,10000,10000,(int)((1.0-aK.top)*10000)) {
			@Override
			public void change(int aValue) {
				editKey().top = 1-(aValue/10000.0);
				tde.gui.monitorGUI.redraw();
			}
		}.tip("CropTop");
		bottomLS = new LabelScale(tde,aWGT,tde.gui.lngs.get("Cropper.Bottom"),0,0,10000,(int)((1-aK.bottom)*10000)) {
			@Override
			public void change(int aValue) {
				editKey().bottom = 1.0-(aValue/10000.0);
				tde.gui.monitorGUI.redraw();
			}
		}.tip("CropBottom");

		CLabel aRot = new CLabel(aWGT, SWT.NONE);
		tde.gui.applyColorFont(aRot);
		aRot.setLayoutData(new GridData(GridData.FILL_HORIZONTAL));
		aRot.setText(tde.gui.lngs.get("Cropper.RotationBorder"));

		angleLS = new LabelScale(tde,aWGT,tde.gui.lngs.get("Cropper.Rotation"),-90000,0,90000,(int)(aK.angle*1000)) {
			@Override
			public void change(int aValue) {
				editKey().angle = aValue/1000.0;
				tde.gui.monitorGUI.redraw();
			}
		}.tip("CropRotation");

		CLabel aShear = new CLabel(aWGT, SWT.NONE);
		tde.gui.applyColorFont(aShear);
		aShear.setLayoutData(new GridData(GridData.FILL_HORIZONTAL));
		aShear.setText(tde.gui.lngs.get("Cropper.ShearX"));

		shearLLS = new LabelScale(tde,aWGT,tde.gui.lngs.get("Cropper.ShearXL"),-10000,0,10000,(int)(aK.shearXL*50000)) {
			@Override
			public void change(int aValue) {
				editKey().shearXL = aValue/50000.0;
				tde.gui.monitorGUI.redraw();
			}
		}.tip("CropShearL");
		shearRLS = new LabelScale(tde,aWGT,tde.gui.lngs.get("Cropper.ShearXR"),-10000,0,10000,(int)(aK.shearXR*50000)) {
			@Override
			public void change(int aValue) {
				editKey().shearXR = aValue/50000.0;
				tde.gui.monitorGUI.redraw();
			}
		}.tip("CropShearR");

		CLabel aTransp = new CLabel(aWGT, SWT.NONE);
		tde.gui.applyColorFont(aTransp);
		aTransp.setLayoutData(new GridData(GridData.FILL_HORIZONTAL));
		aTransp.setText(tde.gui.lngs.get("Cropper.Transparency"));

		transpLS = new LabelScale(tde,aWGT,tde.gui.lngs.get("Cropper.TransparencyLetter"),0,10000,10000,(int)(aK.transparency*10000)) {
			@Override
			public void change(int aValue) {
				editKey().transparency = aValue/10000.0;
				tde.gui.monitorGUI.redraw();
			}
		}.tip("CropTransparency");

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
		int aSelectedKey = crp.getPrevKeyGT(tde.timeLineStack.getTimePosMS());
		if(aSelectedKey < 0) {
			aSelectedKey = 0;
		}
		final int aKey = aSelectedKey;
		tde.gui.display.asyncExec(new Runnable() {
			@Override
			public void run() {
				if(wgt.isDisposed() || aKey >= crp.getNbKeys()) {
					return;
				}
				lkf.setSelection(aKey);
				int aShow = lkf.displayKey(aKey);
				showKey(aShow);
				lkf.checkAddMode(tde.timeLineStack.getTimePosMS());
			}
		});
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
