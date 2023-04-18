package com.cubaix.TDenlive.GUI.processors;

import org.eclipse.nebula.widgets.tablecombo.TableCombo;
import org.eclipse.swt.SWT;
import org.eclipse.swt.events.SelectionEvent;
import org.eclipse.swt.events.SelectionListener;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Event;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Listener;
import org.eclipse.swt.widgets.TableItem;

import com.cubaix.TDenlive.TDenlive;
import com.cubaix.TDenlive.GUI.widgets.LabelKeyFrame;
import com.cubaix.TDenlive.GUI.widgets.LabelScale;
import com.cubaix.TDenlive.medias.Clip;
import com.cubaix.TDenlive.processors.Processor;
import com.cubaix.TDenlive.processors.Transparency;
import com.cubaix.TDenlive.utils.Luma;

public class TransparencyGUI extends ProcessorGUI {
	Transparency tr = null;
	Composite wgt = null;
	LabelKeyFrame lkf = null;
	LabelScale opacityLS = null;
	TableCombo lumaC = null;
	LabelScale bevelLS = null;
	LabelScale xLS = null;
	Button invB = null;
	
	public TransparencyGUI(TDenlive aTDe, Composite aParent,Processor aP) {
		super(aTDe, aParent,aP);
		tr = (Transparency)proc;
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

		int aSelectedKey = tr.getPrevKeyGT(tde.timeLineStack.getTimePosMS());
		if(aSelectedKey < 0) {
			aSelectedKey = 0;
		}

		lkf = new LabelKeyFrame(tde,wgt,tr,"K") {
			@Override
			public void change(int aValue) {
				if(aValue >= 0) {
					opacityLS.setSelection((int)(tr.getOpacity(aValue)*10000));
					lumaC.select(tde.gui.lumas.getLumaIndex(tr.getLuma(aValue).path));
					bevelLS.setSelection((int)(tr.getBevel(aValue)*10000));
					xLS.setSelection((int)(tr.getX(aValue)*40000));
					invB.setSelection(tr.getInvert(aValue));
				}
			}
			@Override
			public void move(int aTime) {
				tde.timeLineStack.setRequestedTimePosMS(((Clip)tr.targetMedia).getStartTimeMS()+aTime);
				tde.gui.timeLinesGUI.redraw();
				tde.gui.monitorGUI.redraw();
			}
		};
		lkf.setSelection(aSelectedKey);

		Label aOL = new Label(wgt,SWT.NONE);
		tde.gui.applyColorFont(aOL);
		GridData aOLGD = new GridData(GridData.FILL_HORIZONTAL);
		aOL.setLayoutData(aOLGD);
		aOL.setText(tde.gui.lngs.get("Transparency.Opacity"));
		
		opacityLS = new LabelScale(tde,wgt,"O",0,0,10000,(int)(tr.getOpacity(aSelectedKey)*10000)) {
			@Override
			public void change(int aValue) {
				if(lkf.getSelection() >= 0) {
					tr.setOpacity(lkf.getSelection(),aValue/10000.0);
					tde.gui.monitorGUI.redraw();
				}
			}
		};
		
		lumaC = new TableCombo(wgt, SWT.NONE);
		tde.gui.applyColorFont(lumaC);
		GridData aTCGD = new GridData(GridData.FILL_HORIZONTAL);
		aTCGD.heightHint = tde.config.thumbH;
		lumaC.setLayoutData(aTCGD);
		lumaC.setShowImageWithinSelection(true);
		lumaC.setShowTableHeader(false);
		lumaC.setEditable(false);
		lumaC.getTable().addListener(SWT.MeasureItem, new Listener() {
			@Override
			public void handleEvent(Event e) {
//				TableItem item = (TableItem)e.item;
				e.height = tde.config.thumbH;
			}
		});
		for(Luma aL : tde.gui.lumas.listLumas()) {
			TableItem aTI = new TableItem(lumaC.getTable(), SWT.NONE);
			aTI.setImage(0,aL.thumbSwt);
			aTI.setText(aL.path);
			aTI.setData(aL);
		}
		Point aP = lumaC.computeSize(300, tde.config.thumbH);
		lumaC.addSelectionListener(new SelectionListener() {
			@Override
			public void widgetSelected(SelectionEvent arg0) {
				tr.setLuma(lkf.getSelection(),tde.gui.lumas.listLumas().elementAt(lumaC.getSelectionIndex()));
				tde.gui.monitorGUI.redraw();
			}
			@Override
			public void widgetDefaultSelected(SelectionEvent arg0) {
				tr.setLuma(lkf.getSelection(),tde.gui.lumas.listLumas().elementAt(lumaC.getSelectionIndex()));
				tde.gui.monitorGUI.redraw();
			}
		});
		lumaC.select(tde.gui.lumas.getLumaIndex(tr.getLuma(aSelectedKey).path));
		
		Label aBL = new Label(wgt,SWT.NONE);
		tde.gui.applyColorFont(aBL);
		GridData aBLGD = new GridData(GridData.FILL_HORIZONTAL);
		aBL.setLayoutData(aBLGD);
		aBL.setText(tde.gui.lngs.get("Transparency.Bevel"));
		
		bevelLS = new LabelScale(tde,wgt,tde.gui.lngs.get("Transparency.BevelLetter"),0,0,10000,(int)(tr.getBevel(aSelectedKey)*10000)) {
			@Override
			public void change(int aValue) {
				if(lkf.getSelection() >= 0) {
					tr.setBevel(lkf.getSelection(),aValue/10000.0);
					tde.gui.monitorGUI.redraw();
				}
			}
		};

		Label aXL = new Label(wgt,SWT.NONE);
		tde.gui.applyColorFont(aXL);
		GridData aXLGD = new GridData(GridData.FILL_HORIZONTAL);
		aXL.setLayoutData(aXLGD);
		aXL.setText(tde.gui.lngs.get("Transparency.X"));
		
		xLS = new LabelScale(tde,wgt,"X",-10000,0,10000,(int)(tr.getX(aSelectedKey)*40000)) {
			@Override
			public void change(int aValue) {
				if(lkf.getSelection() >= 0) {
					tr.setX(lkf.getSelection(),aValue/40000.0);
					tde.gui.monitorGUI.redraw();
				}
			}
		};

		invB = new Button(wgt, SWT.CHECK);
		tde.gui.applyColorFont(invB);
		GridData aIGD = new GridData(GridData.FILL_HORIZONTAL);
		aIGD.heightHint = 20;
		invB.setLayoutData(aIGD);
		invB.addSelectionListener(new SelectionListener() {
			@Override
			public void widgetSelected(SelectionEvent arg0) {
				tr.setInvert(lkf.getSelection(), invB.getSelection());
				tde.gui.monitorGUI.redraw();
			}
			@Override
			public void widgetDefaultSelected(SelectionEvent arg0) {
				tr.setInvert(lkf.getSelection(), invB.getSelection());
				tde.gui.monitorGUI.redraw();
			}
		});
		invB.setText(tde.gui.lngs.get("Transparency.Invert"));
		invB.setSelection(tr.getInvert(aSelectedKey));

		tde.timeLineStack.addTimeListener(this);
	}
	
	@Override
	public void timeChanged(long aTimeNew) {
		super.timeChanged(aTimeNew);
		if(wgt.isDisposed()) {
			tde.timeLineStack.removeTimeListener(this);
			return;
		}
		int aSelectedKey = tr.getPrevKeyGT(tde.timeLineStack.getTimePosMS());
		if(aSelectedKey < 0) {
			aSelectedKey = 0;
		}
		final int aKey = aSelectedKey;
		tde.gui.display.asyncExec(new Runnable() {
			@Override
			public void run() {
				lkf.setSelection(aKey);
				opacityLS.setSelection((int)(tr.getOpacity(aKey)*10000));
				lumaC.select(tde.gui.lumas.getLumaIndex(tr.getLuma(aKey).path));
				bevelLS.setSelection((int)(tr.getBevel(aKey)*10000));
				xLS.setSelection((int)(tr.getX(aKey)*40000));
				invB.setSelection(tr.getInvert(aKey));
				lkf.checkAddMode(tde.timeLineStack.getTimePosMS());
			}
		});
	}
}
