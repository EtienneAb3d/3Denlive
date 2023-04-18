package com.cubaix.TDenlive.GUI.processors;

import org.eclipse.swt.SWT;
import org.eclipse.swt.events.SelectionEvent;
import org.eclipse.swt.events.SelectionListener;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;

import com.cubaix.TDenlive.TDenlive;
import com.cubaix.TDenlive.GUI.widgets.LabelImageButton;
import com.cubaix.TDenlive.GUI.widgets.LabelKeyFrame;
import com.cubaix.TDenlive.GUI.widgets.LabelScale;
import com.cubaix.TDenlive.medias.Clip;
import com.cubaix.TDenlive.medias.Media;
import com.cubaix.TDenlive.processors.AutoAligner;
import com.cubaix.TDenlive.processors.AutoDepthmap;
import com.cubaix.TDenlive.processors.Processor;
import com.cubaix.TDenlive.processors.StereoAligner;

public class StereoAlignerGUI extends ProcessorGUI {
	StereoAligner sa = null;
	Composite wgt = null;
	LabelKeyFrame lkf = null;
	LabelScale xLLS = null;
	LabelScale yLLS = null;
	LabelScale wLLS = null;
	LabelScale hLLS = null;
	LabelScale rLLS = null;
	public StereoAlignerGUI(TDenlive aTDe, Composite aParent,Processor aP) {
		super(aTDe, aParent,aP);
		sa = (StereoAligner)proc;
		createContents();
	}

	void createContents() {
		wgt = new Composite(container, SWT.NONE);
		wgt.setLayoutData(new GridData(GridData.FILL_HORIZONTAL));
		GridLayout aGL = new GridLayout(1, false);
		tde.gui.applyNoMargin(aGL);
		wgt.setLayout(aGL);
		tde.gui.applyColorFont(wgt);

		Composite aCropContainer = new Composite(wgt, SWT.NONE);
		aCropContainer.setLayoutData(new GridData(GridData.FILL_HORIZONTAL));
		GridLayout aGLC = new GridLayout(2, false);
		tde.gui.applyNoMargin(aGLC);
		aCropContainer.setLayout(aGLC);
		tde.gui.applyColorFont(aCropContainer);
		
		Button aCropL = new Button(aCropContainer, SWT.CHECK);
		tde.gui.applyColorFont(aCropL);
		GridData aCLGD = new GridData(GridData.FILL_HORIZONTAL);
		aCLGD.heightHint = 20;
		aCropL.setLayoutData(aCLGD);
		aCropL.setSelection(sa.isSecureCropLeft());
		aCropL.addSelectionListener(new SelectionListener() {
			@Override
			public void widgetSelected(SelectionEvent arg0) {
				sa.setSecureCropLeft(aCropL.getSelection());
				tde.gui.monitorGUI.redraw();
			}
			@Override
			public void widgetDefaultSelected(SelectionEvent arg0) {
				sa.setSecureCropLeft(aCropL.getSelection());
				tde.gui.monitorGUI.redraw();
			}
		});
		aCropL.setText(tde.gui.lngs.get("StereoAligner.SecureCropLeft"));
		
		final Button aCropR = new Button(aCropContainer, SWT.CHECK);
		tde.gui.applyColorFont(aCropR);
		GridData aCRGD = new GridData(GridData.FILL_HORIZONTAL);
		aCRGD.heightHint = 20;
		aCropR.setLayoutData(aCRGD);
		aCropR.setSelection(sa.isSecureCropRight());
		aCropR.addSelectionListener(new SelectionListener() {
			@Override
			public void widgetSelected(SelectionEvent arg0) {
				sa.setSecureCropRight(aCropR.getSelection());
				tde.gui.monitorGUI.redraw();
			}
			@Override
			public void widgetDefaultSelected(SelectionEvent arg0) {
				sa.setSecureCropRight(aCropR.getSelection());
				tde.gui.monitorGUI.redraw();
			}
		});
		aCropR.setText(tde.gui.lngs.get("StereoAligner.SecureCropRight"));
		
		final Button aCropT = new Button(aCropContainer, SWT.CHECK);
		tde.gui.applyColorFont(aCropT);
		GridData aCTGD = new GridData(GridData.FILL_HORIZONTAL);
		aCTGD.heightHint = 20;
		aCropT.setLayoutData(aCTGD);
		aCropT.setSelection(sa.isSecureCropTop());
		aCropT.addSelectionListener(new SelectionListener() {
			@Override
			public void widgetSelected(SelectionEvent arg0) {
				sa.setSecureCropTop(aCropT.getSelection());
				tde.gui.monitorGUI.redraw();
			}
			@Override
			public void widgetDefaultSelected(SelectionEvent arg0) {
				sa.setSecureCropTop(aCropT.getSelection());
				tde.gui.monitorGUI.redraw();
			}
		});
		aCropT.setText(tde.gui.lngs.get("StereoAligner.SecureCropTop"));
		
		final Button aCropB = new Button(aCropContainer, SWT.CHECK);
		tde.gui.applyColorFont(aCropB);
		GridData aCBGD = new GridData(GridData.FILL_HORIZONTAL);
		aCBGD.heightHint = 20;
		aCropB.setLayoutData(aCBGD);
		aCropB.setSelection(sa.isSecureCropBottom());
		aCropB.addSelectionListener(new SelectionListener() {
			@Override
			public void widgetSelected(SelectionEvent arg0) {
				sa.setSecureCropBottom(aCropB.getSelection());
				tde.gui.monitorGUI.redraw();
			}
			@Override
			public void widgetDefaultSelected(SelectionEvent arg0) {
				sa.setSecureCropBottom(aCropB.getSelection());
				tde.gui.monitorGUI.redraw();
			}
		});
		aCropB.setText(tde.gui.lngs.get("StereoAligner.SecureCropBottom"));
		
		final Button aResizeS = new Button(aCropContainer, SWT.CHECK);
		tde.gui.applyColorFont(aResizeS);
		GridData aCRSGD = new GridData(GridData.FILL_HORIZONTAL);
		aCRSGD.heightHint = 20;
		aResizeS.setLayoutData(aCRSGD);
		aResizeS.setSelection(sa.isSecureResize());
		aResizeS.addSelectionListener(new SelectionListener() {
			@Override
			public void widgetSelected(SelectionEvent arg0) {
				sa.setSecureResize(aResizeS.getSelection());
				tde.gui.monitorGUI.redraw();
			}
			@Override
			public void widgetDefaultSelected(SelectionEvent arg0) {
				sa.setSecureResize(aResizeS.getSelection());
				tde.gui.monitorGUI.redraw();
			}
		});
		aResizeS.setText(tde.gui.lngs.get("StereoAligner.SecureResize"));
		
		final Button aDrawCropRectS = new Button(aCropContainer, SWT.CHECK);
		tde.gui.applyColorFont(aDrawCropRectS);
		GridData aDCSGD = new GridData(GridData.FILL_HORIZONTAL);
		aDCSGD.heightHint = 20;
		aDrawCropRectS.setLayoutData(aDCSGD);
		aDrawCropRectS.setSelection(sa.isShowSecureCropBox());
		aDrawCropRectS.addSelectionListener(new SelectionListener() {
			@Override
			public void widgetSelected(SelectionEvent arg0) {
				sa.setShowSecureCropBox(aDrawCropRectS.getSelection());
				tde.gui.monitorGUI.redraw();
			}
			@Override
			public void widgetDefaultSelected(SelectionEvent arg0) {
				sa.setShowSecureCropBox(aDrawCropRectS.getSelection());
				tde.gui.monitorGUI.redraw();
			}
		});
		aDrawCropRectS.setText(tde.gui.lngs.get("StereoAligner.ShowSecureCropBox"));
		
		LabelImageButton aAutoAlign = new LabelImageButton(tde,wgt,tde.gui.lngs.get("StereoAligner.AutoAlign"),"auto.png") {
			@Override
			public void clicked() {
				Media aMedia = tde.selected.clips.elementAt(tde.selected.clips.size()-1);
				AutoAligner aAA = new AutoAligner(tde,aMedia);
				aAA.process2Awt(aMedia, 3, tde.timeLineStack.getTimePosMS());
			}
		};

//		LabelImageButton aAutoDM = new LabelImageButton(tde,wgt,tde.gui.lngs.get("StereoAligner.AutoDepthmap"),"rhino.gif") {
//			@Override
//			public void clicked() {
//				Media aMedia = tde.selected.clips.elementAt(tde.selected.clips.size()-1);
//				AutoDepthmap aAA = new AutoDepthmap(tde,aMedia);
//				aAA.process2Awt(aMedia, 3, tde.timeLineStack.getTimePosMS());
//			}
//		};

		int aSelectedKey = sa.getPrevKeyGT(tde.timeLineStack.getTimePosMS());
		if(aSelectedKey < 0) {
			aSelectedKey = 0;
		}

		lkf = new LabelKeyFrame(tde,wgt,sa,"K") {
			@Override
			public void change(int aValue) {
				if(aValue >= 0) {
					xLLS.setSelection((int)(sa.getXL(aValue)*10000));
					yLLS.setSelection((int)(-sa.getYL(aValue)*10000));
					wLLS.setSelection((int)(sa.getWL(aValue)-1.0));
					hLLS.setSelection((int)(sa.getHL(aValue)-1.0));
					rLLS.setSelection((int)(-(sa.getRL(aValue)/Math.PI)*10000));
				}
			}
			@Override
			public void move(int aTime) {
				tde.timeLineStack.setRequestedTimePosMS(((Clip)sa.targetMedia).getStartTimeMS()+aTime);
				tde.gui.timeLinesGUI.redraw();
				tde.gui.monitorGUI.redraw();
			}
		};
		lkf.setSelection(aSelectedKey);

		xLLS = new LabelScale(tde,wgt,"X",-3000,0,3000,(int)(sa.getXL(aSelectedKey)*10000)) {
			@Override
			public void change(int aValue) {
				if(lkf.getSelection() >= 0) {
					sa.setXL(lkf.getSelection(),aValue/10000.0);
					tde.gui.monitorGUI.redraw();
				}
			}
		};
		yLLS = new LabelScale(tde,wgt,"Y",-3000,0,3000,(int)(-sa.getYL(aSelectedKey)*10000)) {
			@Override
			public void change(int aValue) {
				if(lkf.getSelection() >= 0) {
					sa.setYL(lkf.getSelection(),-aValue/10000.0);
					tde.gui.monitorGUI.redraw();
				}
			}
		};
		wLLS = new LabelScale(tde,wgt,tde.gui.lngs.get("Generic.WidthLetter"),-3000,0,3000,(int)((sa.getWL(aSelectedKey)-1.0)*10000)) {
			@Override
			public void change(int aValue) {
				if(lkf.getSelection() >= 0) {
					sa.setWL(lkf.getSelection(),1.0+aValue/10000.0);
					tde.gui.monitorGUI.redraw();
				}
			}
		};
		hLLS = new LabelScale(tde,wgt,tde.gui.lngs.get("Generic.HeightLetter"),-3000,0,3000,(int)((sa.getHL(aSelectedKey)-1.0)*10000)) {
			@Override
			public void change(int aValue) {
				if(lkf.getSelection() >= 0) {
					sa.setHL(lkf.getSelection(),1.0+aValue/10000.0);
					tde.gui.monitorGUI.redraw();
				}
			}
		};
		rLLS = new LabelScale(tde,wgt,tde.gui.lngs.get("Generic.RotationLetter"),-1000,0,1000,(int)(-(sa.getRL(aSelectedKey)/Math.PI)*10000)) {
			@Override
			public void change(int aValue) {
				if(lkf.getSelection() >= 0) {
					sa.setRL(lkf.getSelection(),-Math.PI*aValue/10000.0);
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
		int aSelectedKey = sa.getPrevKeyGT(tde.timeLineStack.getTimePosMS());
		if(aSelectedKey < 0) {
			aSelectedKey = 0;
		}
		final int aKey = aSelectedKey;
		tde.gui.display.asyncExec(new Runnable() {
			@Override
			public void run() {
				lkf.setSelection(aKey);
				lkf.checkAddMode(tde.timeLineStack.getTimePosMS());
				xLLS.setSelection((int)(sa.getXL(aKey)*10000));
				yLLS.setSelection((int)(-sa.getYL(aKey)*10000));
				wLLS.setSelection((int)(sa.getWL(aKey)-1.0));
				hLLS.setSelection((int)(sa.getHL(aKey)-1.0));
				rLLS.setSelection((int)(-(sa.getRL(aKey)/Math.PI)*10000));
			}
		});
	}
}