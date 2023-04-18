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
import com.cubaix.TDenlive.GUI.widgets.LabelCombo;
import com.cubaix.TDenlive.GUI.widgets.LabelKeyFrame;
import com.cubaix.TDenlive.GUI.widgets.LabelScale;
import com.cubaix.TDenlive.medias.Clip;
import com.cubaix.TDenlive.medias.Media;
import com.cubaix.TDenlive.processors.Depthmap;
import com.cubaix.TDenlive.processors.Processor;

public class DepthmapGUI extends ProcessorGUI {
	Depthmap pl = null;
	Composite wgt = null;
	LabelKeyFrame lkf = null;
	LabelScale xLS = null;
	LabelScale dLS = null;
	LabelScale pLS = null;

	public DepthmapGUI(TDenlive aTDe, Composite aParent,Processor aP) {
		super(aTDe, aParent,aP);
		pl = (Depthmap)proc;
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

		lkf = new LabelKeyFrame(tde,wgt,pl,"K") {
			@Override
			public void change(int aValue) {
				if(aValue >= 0) {
					dLS.setSelection((int)(-pl.getD(aValue)*30000));
					xLS.setSelection((int)(pl.getX(aValue)*1000));
					pLS.setSelection((int)(pl.getP(aValue)*10000));

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

		final Button aBIFB = new Button(wgt, SWT.CHECK);
		tde.gui.applyColorFont(aBIFB);
		GridData aBIFGD = new GridData(GridData.FILL_HORIZONTAL);
		aBIFGD.heightHint = 20;
		aBIFB.setLayoutData(aBIFGD);
		aBIFB.setSelection(pl.getBlackIsFront(aSelectedKey));
		aBIFB.addSelectionListener(new SelectionListener() {
			@Override
			public void widgetSelected(SelectionEvent arg0) {
				pl.setBlackIsFront(lkf.getSelection(), aBIFB.getSelection());
				tde.gui.monitorGUI.redraw();
			}
			@Override
			public void widgetDefaultSelected(SelectionEvent arg0) {
				pl.setBlackIsFront(lkf.getSelection(), aBIFB.getSelection());
				tde.gui.monitorGUI.redraw();
			}
		});
		aBIFB.setText(tde.gui.lngs.get("Depthmap.BlackIsFront"));
		

		dLS = new LabelScale(tde,wgt,"Z",-10000,0,10000,(int)(-pl.getD(aSelectedKey)*30000)) {
			@Override
			public void change(int aValue) {
				if(lkf.getSelection() >= 0) {
					pl.setD(lkf.getSelection(),-aValue/30000.0);
					tde.gui.monitorGUI.redraw();
				}
			}
		};
		xLS = new LabelScale(tde,wgt,"X",-10000,0,10000,(int)(pl.getX(aSelectedKey)*1000)) {
			@Override
			public void change(int aValue) {
				if(lkf.getSelection() >= 0) {
					pl.setX(lkf.getSelection(),aValue/1000.0);
					tde.gui.monitorGUI.redraw();
				}
			}
		};
		pLS = new LabelScale(tde,wgt,"P",-10000,0,10000,(int)(pl.getP(aSelectedKey)*10000)) {
			@Override
			public void change(int aValue) {
				if(lkf.getSelection() >= 0) {
					pl.setP(lkf.getSelection(),aValue/10000.0);
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
		int aSelectedKey = pl.getPrevKeyGT(tde.timeLineStack.getTimePosMS());
		if(aSelectedKey < 0) {
			aSelectedKey = 0;
		}
		final int aKey = aSelectedKey;
		tde.gui.display.asyncExec(new Runnable() {
			@Override
			public void run() {
				lkf.setSelection(aKey);
				lkf.checkAddMode(tde.timeLineStack.getTimePosMS());
				dLS.setSelection((int)(-pl.getD(aKey)*30000));
				xLS.setSelection((int)(pl.getX(aKey)*1000));
				pLS.setSelection((int)(pl.getP(aKey)*10000));
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
