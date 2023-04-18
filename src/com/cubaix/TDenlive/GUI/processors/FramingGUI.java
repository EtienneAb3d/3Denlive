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
import com.cubaix.TDenlive.processors.Framing;
import com.cubaix.TDenlive.processors.Processor;
import com.cubaix.TDenlive.utils.Frame;

public class FramingGUI extends ProcessorGUI {
	Framing fr = null;
	Composite wgt = null;
	LabelKeyFrame lkf = null;
	LabelScale opacityLS = null;
	TableCombo frameC = null;
	LabelScale xLS = null;
	
	public FramingGUI(TDenlive aTDe, Composite aParent,Processor aP) {
		super(aTDe, aParent,aP);
		fr = (Framing)proc;
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

		int aSelectedKey = fr.getPrevKeyGT(tde.timeLineStack.getTimePosMS());
		if(aSelectedKey < 0) {
			aSelectedKey = 0;
		}

		lkf = new LabelKeyFrame(tde,wgt,fr,"K") {
			@Override
			public void change(int aValue) {
				if(aValue >= 0) {
					opacityLS.setSelection((int)(fr.getOpacity(aValue)*10000));
					frameC.select(tde.gui.frames.getFrameIndex(fr.getFrame(aValue).path));
					xLS.setSelection((int)(fr.getX(aValue)*40000));
				}
			}
			@Override
			public void move(int aTime) {
				tde.timeLineStack.setRequestedTimePosMS(((Clip)fr.targetMedia).getStartTimeMS()+aTime);
				tde.gui.timeLinesGUI.redraw();
				tde.gui.monitorGUI.redraw();
			}
		};
		lkf.setSelection(aSelectedKey);

		Label aOL = new Label(wgt,SWT.NONE);
		tde.gui.applyColorFont(aOL);
		GridData aOLGD = new GridData(GridData.FILL_HORIZONTAL);
		aOL.setLayoutData(aOLGD);
		aOL.setText(tde.gui.lngs.get("Framing.Opacity"));
		
		opacityLS = new LabelScale(tde,wgt,"O",0,0,10000,(int)(fr.getOpacity(aSelectedKey)*10000)) {
			@Override
			public void change(int aValue) {
				if(lkf.getSelection() >= 0) {
					fr.setOpacity(lkf.getSelection(),aValue/10000.0);
					tde.gui.monitorGUI.redraw();
				}
			}
		};
		
		frameC = new TableCombo(wgt, SWT.NONE);
		tde.gui.applyColorFont(frameC);
		GridData aTCGD = new GridData(GridData.FILL_HORIZONTAL);
		aTCGD.heightHint = tde.config.thumbH;
		frameC.setLayoutData(aTCGD);
		frameC.setShowImageWithinSelection(true);
		frameC.setShowTableHeader(false);
		frameC.setEditable(false);
		frameC.getTable().addListener(SWT.MeasureItem, new Listener() {
			@Override
			public void handleEvent(Event e) {
//				TableItem item = (TableItem)e.item;
				e.height = tde.config.thumbH;
			}
		});
		for(Frame aF : tde.gui.frames.listFrames()) {
			TableItem aTI = new TableItem(frameC.getTable(), SWT.NONE);
			aTI.setImage(0,aF.thumbSwt);
			aTI.setText(aF.path);
			aTI.setData(aF);
		}
		Point aP = frameC.computeSize(300, tde.config.thumbH);
		frameC.addSelectionListener(new SelectionListener() {
			@Override
			public void widgetSelected(SelectionEvent arg0) {
				fr.setFrame(lkf.getSelection(),tde.gui.frames.listFrames().elementAt(frameC.getSelectionIndex()));
				tde.gui.monitorGUI.redraw();
			}
			@Override
			public void widgetDefaultSelected(SelectionEvent arg0) {
				fr.setFrame(lkf.getSelection(),tde.gui.frames.listFrames().elementAt(frameC.getSelectionIndex()));
				tde.gui.monitorGUI.redraw();
			}
		});
		frameC.select(tde.gui.frames.getFrameIndex(fr.getFrame(aSelectedKey).path));

		Label aXL = new Label(wgt,SWT.NONE);
		tde.gui.applyColorFont(aXL);
		GridData aXLGD = new GridData(GridData.FILL_HORIZONTAL);
		aXL.setLayoutData(aXLGD);
		aXL.setText(tde.gui.lngs.get("Framing.X"));
		
		xLS = new LabelScale(tde,wgt,"X",-10000,0,10000,(int)(fr.getX(aSelectedKey)*40000)) {
			@Override
			public void change(int aValue) {
				if(lkf.getSelection() >= 0) {
					fr.setX(lkf.getSelection(),aValue/40000.0);
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
		int aSelectedKey = fr.getPrevKeyGT(tde.timeLineStack.getTimePosMS());
		if(aSelectedKey < 0) {
			aSelectedKey = 0;
		}
		final int aKey = aSelectedKey;
		tde.gui.display.asyncExec(new Runnable() {
			@Override
			public void run() {
				lkf.setSelection(aKey);
				opacityLS.setSelection((int)(fr.getOpacity(aKey)*10000));
				frameC.select(tde.gui.frames.getFrameIndex(fr.getFrame(aKey).path));
				xLS.setSelection((int)(fr.getX(aKey)*40000));
				lkf.checkAddMode(tde.timeLineStack.getTimePosMS());
			}
		});
	}
}
