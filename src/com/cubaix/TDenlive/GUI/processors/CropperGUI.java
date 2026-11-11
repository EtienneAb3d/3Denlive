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


	//Linkable sliders, in the order of Cropper.linked
	static final int LINK_LEFT_L = 0, LINK_LEFT_R = 1, LINK_RIGHT_L = 2, LINK_RIGHT_R = 3;
	static final int LINK_TOP = 4, LINK_BOTTOM = 5, LINK_SHEAR_L = 6, LINK_SHEAR_R = 7;
	//Group of each slider: borders (left / right / top / bottom), shears
	static final int[] LINK_GROUP = {0,0,0,0,0,0,1,1};
	//Sliders whose value is reversed (1-value) against the others of their group:
	//right borders and top (their 0 is at the max of the bar). So top and bottom are reversed,
	//top takes the value of the right borders, bottom that of the left borders
	static final boolean[] LINK_REVERSED = {false,false,true,true,true,false,false,false};

	LabelScale linkLS(int aId) {
		switch(aId) {
		case LINK_LEFT_L: return leftLLS;
		case LINK_LEFT_R: return leftRLS;
		case LINK_RIGHT_L: return rightLLS;
		case LINK_RIGHT_R: return rightRLS;
		case LINK_TOP: return topLS;
		case LINK_BOTTOM: return bottomLS;
		case LINK_SHEAR_L: return shearLLS;
		default: return shearRLS;
		}
	}

	/** Sets the parameter of the slider aId to the bar value aValue (in the keys being edited) */
	void setParam(int aId,int aValue) {
		switch(aId) {
		case LINK_LEFT_L: lkf.edit(aEK -> crp.getKey(aEK).leftL = aValue/10000.0); break;
		case LINK_LEFT_R: lkf.edit(aEK -> crp.getKey(aEK).leftR = aValue/10000.0); break;
		case LINK_RIGHT_L: lkf.edit(aEK -> crp.getKey(aEK).rightL = aValue/10000.0); break;
		case LINK_RIGHT_R: lkf.edit(aEK -> crp.getKey(aEK).rightR = aValue/10000.0); break;
		case LINK_TOP: lkf.edit(aEK -> crp.getKey(aEK).top = 1-(aValue/10000.0)); break;
		case LINK_BOTTOM: lkf.edit(aEK -> crp.getKey(aEK).bottom = 1.0-(aValue/10000.0)); break;
		case LINK_SHEAR_L: lkf.edit(aEK -> crp.getKey(aEK).shearXL = aValue/50000.0); break;
		default: lkf.edit(aEK -> crp.getKey(aEK).shearXR = aValue/50000.0); break;
		}
	}

	/**
	 * Change of the slider aId: if it is linked, the other linked sliders of its group take the same
	 * value (reversed, 10000-value, between a left / bottom slider and a right / top one)
	 */
	void changeLinked(int aId,int aValue) {
		setParam(aId, aValue);
		if(crp.linked[aId]) {
			for(int aO = 0;aO < crp.linked.length;aO++) {
				if(aO == aId || !crp.linked[aO] || LINK_GROUP[aO] != LINK_GROUP[aId]) {
					continue;
				}
				int aV = LINK_REVERSED[aO] == LINK_REVERSED[aId] ? aValue : 10000-aValue;
				linkLS(aO).setSelection(aV);
				setParam(aO, aV);
			}
		}
		tde.gui.monitorGUI.redraw();
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
				changeLinked(0, aValue);
				tde.gui.monitorGUI.redraw();
			}
		}.tip("CropLeftL");
		leftRLS = new LabelScale(tde,aWGT,tde.gui.lngs.get("Cropper.LeftR"),0,0,10000,(int)(aK.leftR*10000)) {
			@Override
			public void change(int aValue) {
				changeLinked(1, aValue);
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
				changeLinked(2, aValue);
				tde.gui.monitorGUI.redraw();
			}
		}.tip("CropRightL");
		rightRLS = new LabelScale(tde,aWGT,tde.gui.lngs.get("Cropper.RightR"),0,10000,10000,(int)(aK.rightR*10000)) {
			@Override
			public void change(int aValue) {
				changeLinked(3, aValue);
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
				changeLinked(4, aValue);
				tde.gui.monitorGUI.redraw();
			}
		}.tip("CropTop");
		bottomLS = new LabelScale(tde,aWGT,tde.gui.lngs.get("Cropper.Bottom"),0,0,10000,(int)((1-aK.bottom)*10000)) {
			@Override
			public void change(int aValue) {
				changeLinked(5, aValue);
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
				lkf.edit(aEK -> crp.getKey(aEK).angle = aValue/1000.0);
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
				changeLinked(6, aValue);
				tde.gui.monitorGUI.redraw();
			}
		}.tip("CropShearL");
		shearRLS = new LabelScale(tde,aWGT,tde.gui.lngs.get("Cropper.ShearXR"),-10000,0,10000,(int)(aK.shearXR*50000)) {
			@Override
			public void change(int aValue) {
				changeLinked(7, aValue);
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
				lkf.edit(aEK -> crp.getKey(aEK).transparency = aValue/10000.0);
				tde.gui.monitorGUI.redraw();
			}
		}.tip("CropTransparency");

		for(int aId = 0;aId < crp.linked.length;aId++) {
			final int aFId = aId;
			Button aLinkB = linkLS(aId).addLinkCheck(crp.linked[aId], "CropLink");
			aLinkB.addListener(SWT.Selection, e -> crp.linked[aFId] = aLinkB.getSelection());
		}

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
