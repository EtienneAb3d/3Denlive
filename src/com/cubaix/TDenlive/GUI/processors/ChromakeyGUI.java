package com.cubaix.TDenlive.GUI.processors;

import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.CLabel;
import org.eclipse.swt.events.MouseEvent;
import org.eclipse.swt.events.MouseListener;
import org.eclipse.swt.graphics.Color;
import org.eclipse.swt.graphics.Cursor;
import org.eclipse.swt.graphics.RGB;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.ColorDialog;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Label;

import com.cubaix.TDenlive.TDConfig;
import com.cubaix.TDenlive.TDenlive;
import com.cubaix.TDenlive.GUI.MonitorListener;
import com.cubaix.TDenlive.GUI.widgets.LabelKeyFrame;
import com.cubaix.TDenlive.GUI.widgets.LabelScale;
import com.cubaix.TDenlive.medias.Clip;
import com.cubaix.TDenlive.processors.Chromakey;
import com.cubaix.TDenlive.processors.Processor;

public class ChromakeyGUI extends ProcessorGUI {
	Chromakey ck = null;
	Composite wgt = null;
	LabelKeyFrame lkf = null;
	LabelScale toleranceLS = null;
	public Label colorL = null;
	LabelScale bevelLS = null;
	
	public ChromakeyGUI(TDenlive aTDe, Composite aParent,Processor aP) {
		super(aTDe, aParent,aP);
		ck = (Chromakey)proc;
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

		int aSelectedKey = ck.getPrevKeyGT(tde.timeLineStack.getTimePosMS());
		if(aSelectedKey < 0) {
			aSelectedKey = 0;
		}

		lkf = new LabelKeyFrame(tde,wgt,ck,"K") {
			@Override
			public void change(int aValue) {
				if(aValue >= 0) {
					toleranceLS.setSelection((int)(ck.getTolerance(aValue)*10000));
					bevelLS.setSelection((int)(ck.getBevel(aValue)*10000));
				}
			}
			@Override
			public void move(int aTime) {
				tde.timeLineStack.setRequestedTimePosMS(((Clip)ck.targetMedia).getStartTimeMS()+aTime);
				tde.gui.timeLinesGUI.redraw();
				tde.gui.monitorGUI.redraw();
			}
		};
		lkf.setSelection(aSelectedKey);

		Label aOL = new Label(wgt,SWT.NONE);
		tde.gui.applyColorFont(aOL);
		GridData aOLGD = new GridData(GridData.FILL_HORIZONTAL);
		aOL.setLayoutData(aOLGD);
		aOL.setText(tde.gui.lngs.get("Chromakey.Tolerance"));
		
		toleranceLS = new LabelScale(tde,wgt,tde.gui.lngs.get("Chromakey.ToleranceLetter"),0,0,10000,(int)(ck.getTolerance(aSelectedKey)*10000)) {
			@Override
			public void change(int aValue) {
				if(lkf.getSelection() >= 0) {
					ck.setTolerance(lkf.getSelection(),aValue/10000.0);
					tde.gui.monitorGUI.redraw();
				}
			}
		};
		
		Composite aCC = new Composite(wgt,SWT.NONE);
		tde.gui.applyColorFont(aCC);
		aCC.setLayoutData(new GridData(GridData.FILL_HORIZONTAL));
		GridLayout aCCGL = new GridLayout(3, false);
		tde.gui.applyNoMargin(aCCGL);
		aCC.setLayout(aCCGL);
		CLabel aPickColor = new CLabel(aCC,SWT.NONE);
		aPickColor.setImage(tde.gui.imgsSwt.getIcon("colorpicker.png"));
		aPickColor.setAlignment(SWT.CENTER);
		GridData aPCGD = new GridData(GridData.HORIZONTAL_ALIGN_CENTER);
		aPCGD.widthHint = 32;
		aPCGD.heightHint = 25;
		aPickColor.setLayoutData(aPCGD);
		tde.gui.applyColorFont(aPickColor);
		aPickColor.addMouseListener(new MouseListener() {
			@Override
			public void mouseUp(MouseEvent arg0) {}
			@Override
			public void mouseDown(MouseEvent arg0) {
				tde.gui.shell.setCursor(new Cursor(tde.gui.display, SWT.CURSOR_UPARROW));
				tde.gui.monitorGUI.addMonitorListener(new MonitorListener() {
					@Override
					public void click(double aPIX, double aPIY,int aColor) {
						ck.setColor(lkf.getSelection(), aColor);
						RGB aRGB = new RGB(aColor>>16&0xFF,aColor>>8&0xFF,aColor&0xFF);
						colorL.setBackground(new Color(aRGB));
						tde.gui.monitorGUI.removeMonitorListener(this);
						tde.gui.monitorGUI.redraw();
						tde.gui.shell.setCursor(null);
					}
				});
			}
			@Override
			public void mouseDoubleClick(MouseEvent arg0) {}
		});
		colorL = new Label(aCC, SWT.NONE);
		tde.gui.applyColorFont(colorL);
		GridData aCLGD = new GridData(GridData.FILL_HORIZONTAL);
//		aCLGD.widthHint = 32;
		aCLGD.heightHint = 25;
		colorL.setLayoutData(aCLGD);
		int aColor = ck.getColor(aSelectedKey);
		RGB aRGB = new RGB(aColor>>16&0xFF,aColor>>8&0xFF,aColor&0xFF);
		colorL.setBackground(new Color(aRGB));
		CLabel aChooseColor = new CLabel(aCC,SWT.NONE);
		aChooseColor.setImage(tde.gui.imgsSwt.getIcon("colourPalette.gif"));
		aChooseColor.setAlignment(SWT.CENTER);
		GridData aCCGD = new GridData(GridData.HORIZONTAL_ALIGN_CENTER);
		aCCGD.widthHint = 32;
		aCCGD.heightHint = 25;
		aChooseColor.setLayoutData(aCCGD);
		tde.gui.applyColorFont(aChooseColor);
		aChooseColor.addMouseListener(new MouseListener() {
			@Override
			public void mouseUp(MouseEvent arg0) {}
			@Override
			public void mouseDown(MouseEvent arg0) {
				ColorDialog aCD = new ColorDialog(tde.gui.shell);
				int aColor = ck.getColor(lkf.getSelection());
				RGB aRGB = new RGB(aColor>>16&0xFF,aColor>>8&0xFF,aColor&0xFF);
				aRGB = aCD.open();
				if(aRGB != null) {
					ck.setColor(lkf.getSelection(),0xFF000000|((aRGB.red&0xFF)<<16)|((aRGB.green&0xFF)<<8)|(aRGB.blue&0xFF));
					colorL.setBackground(new Color(aRGB));
					tde.gui.monitorGUI.redraw();
				}
			}
			@Override
			public void mouseDoubleClick(MouseEvent arg0) {}
		});

		Label aBL = new Label(wgt,SWT.NONE);
		tde.gui.applyColorFont(aBL);
		GridData aBLGD = new GridData(GridData.FILL_HORIZONTAL);
		aBL.setLayoutData(aBLGD);
		aBL.setText(tde.gui.lngs.get("Chromakey.Bevel"));
		
		bevelLS = new LabelScale(tde,wgt,tde.gui.lngs.get("Chromakey.BevelLetter"),0,0,10000,(int)(ck.getBevel(aSelectedKey)*10000)) {
			@Override
			public void change(int aValue) {
				if(lkf.getSelection() >= 0) {
					ck.setBevel(lkf.getSelection(),aValue/10000.0);
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
		int aSelectedKey = ck.getPrevKeyGT(tde.timeLineStack.getTimePosMS());
		if(aSelectedKey < 0) {
			aSelectedKey = 0;
		}
		final int aKey = aSelectedKey;
		tde.gui.display.asyncExec(new Runnable() {
			@Override
			public void run() {
				lkf.setSelection(aKey);
				toleranceLS.setSelection((int)(ck.getTolerance(aKey)*10000));
				bevelLS.setSelection((int)(ck.getBevel(aKey)*10000));
				lkf.checkAddMode(tde.timeLineStack.getTimePosMS());
			}
		});
	}
}
