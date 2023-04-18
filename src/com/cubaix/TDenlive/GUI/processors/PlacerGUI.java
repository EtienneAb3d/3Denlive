package com.cubaix.TDenlive.GUI.processors;

import org.eclipse.swt.SWT;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Composite;

import com.cubaix.TDenlive.TDenlive;
import com.cubaix.TDenlive.GUI.widgets.LabelKeyFrame;
import com.cubaix.TDenlive.GUI.widgets.LabelScale;
import com.cubaix.TDenlive.medias.Clip;
import com.cubaix.TDenlive.processors.Placer;
import com.cubaix.TDenlive.processors.Processor;

public class PlacerGUI extends ProcessorGUI {
	Placer pl = null;
	Composite wgt = null;
	LabelKeyFrame lkf = null;
	LabelScale xLS = null;
	LabelScale yLS = null;
	LabelScale sLS = null;

	public PlacerGUI(TDenlive aTDe, Composite aParent,Processor aP) {
		super(aTDe, aParent,aP);
		pl = (Placer)proc;
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
					xLS.setSelection((int)(pl.getX(aValue)*10000));
					yLS.setSelection((int)(-pl.getY(aValue)*10000));
					sLS.setSelection(scale2bar(pl.getScale(aValue)));
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

		xLS = new LabelScale(tde,wgt,"X",-10000,0,10000,(int)(pl.getX(aSelectedKey)*10000)) {
			@Override
			public void change(int aValue) {
				if(lkf.getSelection() >= 0) {
					pl.setX(lkf.getSelection(),aValue/10000.0);
					tde.gui.monitorGUI.redraw();
				}
			}
		};
		yLS = new LabelScale(tde,wgt,"Y",-10000,0,10000,(int)(-pl.getY(aSelectedKey)*10000)) {
			@Override
			public void change(int aValue) {
				if(lkf.getSelection() >= 0) {
					pl.setY(lkf.getSelection(),-aValue/10000.0);
					tde.gui.monitorGUI.redraw();
				}
			}
		};
		sLS = new LabelScale(tde,wgt,tde.gui.lngs.get("Generic.SizeLetter"),-10000,0,10000,scale2bar(pl.getScale(aSelectedKey))) {
			@Override
			public void change(int aValue) {
				if(lkf.getSelection() >= 0) {
					pl.setScale(lkf.getSelection(),bar2Scale(aValue));
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
				xLS.setSelection((int)(pl.getX(aKey)*10000));
				yLS.setSelection((int)(-pl.getY(aKey)*10000));
				sLS.setSelection(scale2bar(pl.getScale(aKey)));
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
