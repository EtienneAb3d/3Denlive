package com.cubaix.TDenlive.GUI.processors;

import java.util.Vector;

import org.eclipse.swt.SWT;
import org.eclipse.swt.events.SelectionEvent;
import org.eclipse.swt.events.SelectionListener;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;

import com.cubaix.TDenlive.TDenlive;
import com.cubaix.TDenlive.GUI.DepthmapConvertGUI;
import com.cubaix.TDenlive.GUI.widgets.LabelCombo;
import com.cubaix.TDenlive.GUI.widgets.LabelImageButton;
import com.cubaix.TDenlive.GUI.widgets.LabelKeyFrame;
import com.cubaix.TDenlive.GUI.widgets.LabelScale;
import com.cubaix.TDenlive.medias.Clip;
import com.cubaix.TDenlive.medias.Media;
import com.cubaix.TDenlive.processors.Depthmap;
import com.cubaix.TDenlive.processors.DepthmapStereo;
import com.cubaix.TDenlive.processors.Processor;

public class DepthmapStereoGUI extends ProcessorGUI {
	DepthmapStereo pl = null;
	Composite wgt = null;
	LabelKeyFrame lkf = null;
	LabelCombo dmLC = null;
	LabelScale xLS = null;
	LabelScale dLS = null;
	LabelScale pLS = null;
	LabelScale mLS = null;

	public DepthmapStereoGUI(TDenlive aTDe, Composite aParent,Processor aP) {
		super(aTDe, aParent,aP);
		pl = (DepthmapStereo)proc;
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

		int aSelectedKey = pl.getPrevKeyGT(tde.timeLineStack.getTimePosMS());
		if(aSelectedKey < 0) {
			aSelectedKey = 0;
		}

		//Depth map(s) computed from the clip (Depth Anything V2), and on its right the depth map shown instead of the result
		Composite aDepthRow = new Composite(wgt, SWT.NONE);
		aDepthRow.setLayoutData(new GridData(GridData.FILL_HORIZONTAL));
		GridLayout aDRGL = new GridLayout(2, false);
		tde.gui.applyNoMargin(aDRGL);
		aDepthRow.setLayout(aDRGL);
		tde.gui.applyColorFont(aDepthRow);
		new LabelImageButton(tde,aDepthRow,tde.gui.lngs.get("DepthConvert.Button"),"rhino.gif") {
			@Override
			public void clicked() {
				DepthmapConvertGUI.convert(tde, (Clip)pl.targetMedia, pl, true);
			}
		}.tip("DepthConvert");
		final Button aShowB = new Button(aDepthRow, SWT.CHECK);
		tde.gui.applyColorFont(aShowB);
		tde.gui.tip(aShowB, "DepthShow");
		aShowB.setText(tde.gui.lngs.get("DepthConvert.Show"));
		aShowB.setSelection(pl.showDepth);
		aShowB.setLayoutData(new GridData(GridData.HORIZONTAL_ALIGN_END | GridData.VERTICAL_ALIGN_CENTER));
		aShowB.addSelectionListener(new SelectionListener() {
			@Override
			public void widgetSelected(SelectionEvent arg0) {
				pl.showDepth = aShowB.getSelection();
				tde.gui.monitorGUI.redraw();
			}
			@Override
			public void widgetDefaultSelected(SelectionEvent arg0) {
				widgetSelected(arg0);
			}
		});

		lkf = new LabelKeyFrame(tde,wgt,pl,"K") {
			@Override
			public void change(int aValue) {
				if(aValue >= 0) {
					Clip aDC = pl.getDepthmapClipMedia(aValue);
					String aUID = aDC == null ? "" : ""+aDC.getMedia().UID;
					dmLC.select(aUID);
					dLS.setSelection((int)(-pl.getD(aValue)*30000));
					xLS.setSelection((int)(pl.getX(aValue)*10000));
					pLS.setSelection((int)(pl.getP(aValue)*10000));
					mLS.setSelection((int)Math.round(pl.getM(aValue)*100));

				}
			}
			@Override
			public void move(int aTime) {
				tde.timeLineStack.setRequestedTimePosMS(((Clip)pl.targetMedia).getStartTimeMS()+aTime);
				tde.gui.timeLinesGUI.redraw();
				tde.gui.monitorGUI.redraw();
			}
		};
		lkf.setSelection(aSelectedKey);

		Vector<String[]> aValTextsR = new Vector<String[]>();
		//No depth map of the key: the computed ones if any
		aValTextsR.add(new String[] {"",pl.computed != null ? tde.gui.lngs.get("DepthConvert.Computed") : ""});
		for(Media aMedia : tde.mediaList.mediaList) {
			String aFileName = aMedia.fileName;
			if(aFileName.indexOf(" / ") > 0) {
				aFileName = aFileName.substring(0,aFileName.indexOf(" / "));
			}
			if(aFileName.length() > 25) {
				aFileName = aFileName.substring(0,22)+"...";
			}
			aValTextsR.add(new String[] {""+aMedia.UID,aFileName});
		}
		Clip aDC = pl.getDepthmapClipMedia(aSelectedKey);
		String aUID = aDC == null ? "" : ""+aDC.getMedia().UID;
		dmLC = new LabelCombo(tde,wgt,tde.gui.lngs.get("DepthmapStereo.Depthmap"),aValTextsR,aUID) {
			@Override
			public void change(String aValue) {
				lkf.edit(aEK -> pl.setDepthmapClipMedia(aEK, aValue));
				tde.gui.monitorGUI.redraw();
			}
		};

		final Button aBIFB = new Button(wgt, SWT.CHECK);
		tde.gui.applyColorFont(aBIFB);
		tde.gui.tip(aBIFB, "BlackIsFront");
		GridData aBIFGD = new GridData(GridData.FILL_HORIZONTAL);
		aBIFGD.heightHint = tde.gui.h(20);
		aBIFB.setLayoutData(aBIFGD);
		aBIFB.setSelection(pl.getBlackIsFront(aSelectedKey));
		aBIFB.addSelectionListener(new SelectionListener() {
			@Override
			public void widgetSelected(SelectionEvent arg0) {
				lkf.edit(aEK -> pl.setBlackIsFront(aEK, aBIFB.getSelection()));
				tde.gui.monitorGUI.redraw();
			}
			@Override
			public void widgetDefaultSelected(SelectionEvent arg0) {
				lkf.edit(aEK -> pl.setBlackIsFront(aEK, aBIFB.getSelection()));
				tde.gui.monitorGUI.redraw();
			}
		});
		aBIFB.setText(tde.gui.lngs.get("Depthmap.BlackIsFront"));
		

		dLS = new LabelScale(tde,wgt,"Z",-10000,0,10000,(int)(-pl.getD(aSelectedKey)*30000)) {
			@Override
			public void change(int aValue) {
				if(lkf.getSelection() >= 0) {
					lkf.edit(aEK -> pl.setD(aEK, -aValue/30000.0));
					tde.gui.monitorGUI.redraw();
				}
			}
		}.tip("DepthZ");
		xLS = new LabelScale(tde,wgt,"X",-10000,0,10000,(int)(pl.getX(aSelectedKey)*10000)) {
			@Override
			public void change(int aValue) {
				if(lkf.getSelection() >= 0) {
					lkf.edit(aEK -> pl.setX(aEK, aValue/10000.0));
					tde.gui.monitorGUI.redraw();
				}
			}
		}.tip("DepthX");
		pLS = new LabelScale(tde,wgt,"P",-10000,0,10000,(int)(pl.getP(aSelectedKey)*10000)) {
			@Override
			public void change(int aValue) {
				if(lkf.getSelection() >= 0) {
					lkf.edit(aEK -> pl.setP(aEK, aValue/10000.0));
					tde.gui.monitorGUI.redraw();
				}
			}
		}.tip("DepthP");
		mLS = new LabelScale(tde,wgt,"M",0,0,100,(int)Math.round(pl.getM(aSelectedKey)*100)) {
			@Override
			public void change(int aValue) {
				if(lkf.getSelection() >= 0) {
					lkf.edit(aEK -> pl.setM(aEK, aValue/100.0));
					tde.gui.monitorGUI.redraw();
				}
			}
		}.tip("DepthM");
		
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
		int aSelectedKey = pl.getPrevKeyGT(tde.timeLineStack.getTimePosMS());
		if(aSelectedKey < 0) {
			aSelectedKey = 0;
		}
		final int aKey = aSelectedKey;
		tde.gui.display.asyncExec(new Runnable() {
			@Override
			public void run() {
				lkf.setSelection(aKey);
				int aShow = lkf.displayKey(aKey);
				lkf.checkAddMode(tde.timeLineStack.getTimePosMS());
				Clip aDC = pl.getDepthmapClipMedia(aShow);
				String aUID = aDC == null ? "" : ""+aDC.getMedia().UID;
				dmLC.select(aUID);
				dLS.setSelection((int)(-pl.getD(aShow)*30000));
				xLS.setSelection((int)(pl.getX(aShow)*10000));
				pLS.setSelection((int)(pl.getP(aShow)*10000));
				mLS.setSelection((int)Math.round(pl.getM(aShow)*100));
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
