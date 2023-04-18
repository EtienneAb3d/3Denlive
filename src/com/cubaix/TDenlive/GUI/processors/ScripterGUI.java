package com.cubaix.TDenlive.GUI.processors;

import java.io.File;

import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.CLabel;
import org.eclipse.swt.events.MouseEvent;
import org.eclipse.swt.events.MouseListener;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.FileDialog;

import com.cubaix.TDenlive.TDenlive;
import com.cubaix.TDenlive.GUI.widgets.LabelKeyFrame;
import com.cubaix.TDenlive.medias.Clip;
import com.cubaix.TDenlive.processors.Processor;
import com.cubaix.TDenlive.processors.Scripter;

public class ScripterGUI extends ProcessorGUI {
	Scripter scr = null;
	Composite wgt = null;
	LabelKeyFrame lkf = null;
	CLabel scriptL = null;

	public ScripterGUI(TDenlive aTDe, Composite aParent,Processor aP) {
		super(aTDe, aParent,aP);
		scr = (Scripter)proc;
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

		int aSelectedKey = scr.getPrevKeyGT(tde.timeLineStack.getTimePosMS());
		if(aSelectedKey < 0) {
			aSelectedKey = 0;
		}

		lkf = new LabelKeyFrame(tde,wgt,scr,"K") {
			@Override
			public void change(int aValue) {
				if(aValue >= 0) {
					scriptL.setText(new File(scr.getScript(aValue)).getName());
				}
			}
			@Override
			public void move(int aTime) {
				tde.timeLineStack.setRequestedTimePosMS(((Clip)scr.targetMedia).getStartTimeMS()+aTime);
				tde.gui.timeLinesGUI.redraw();
				tde.gui.monitorGUI.redraw();
			}
		};
		lkf.setSelection(aSelectedKey);

		Composite aCC = new Composite(wgt,SWT.NONE);
		tde.gui.applyColorFont(aCC);
		aCC.setLayoutData(new GridData(GridData.FILL_HORIZONTAL));
		GridLayout aCCGL = new GridLayout(2, false);
		tde.gui.applyNoMargin(aCCGL);
		aCC.setLayout(aCCGL);
		CLabel aChooseScript = new CLabel(aCC,SWT.NONE);
		aChooseScript.setImage(tde.gui.imgsSwt.getIcon("script.png"));
		aChooseScript.setAlignment(SWT.CENTER);
		GridData aCSGD = new GridData(GridData.HORIZONTAL_ALIGN_CENTER);
		aCSGD.widthHint = 32;
		aCSGD.heightHint = 25;
		aChooseScript.setLayoutData(aCSGD);
		tde.gui.applyColorFont(aChooseScript);
		aChooseScript.addMouseListener(new MouseListener() {
			@Override
			public void mouseUp(MouseEvent arg0) {}
			@Override
			public void mouseDown(MouseEvent arg0) {
				String aPath = fileChoose();
				if(aPath != null && new File(aPath).exists()) {
					int aSelectedKey = scr.getPrevKeyGT(tde.timeLineStack.getTimePosMS());
					scr.setScript(aSelectedKey, aPath);
					scriptL.setText(new File(scr.getScript(aSelectedKey)).getName());
					tde.gui.monitorGUI.redraw();
				}
			}
			@Override
			public void mouseDoubleClick(MouseEvent arg0) {}
		});
		scriptL = new CLabel(aCC,SWT.NONE);
		scriptL.setText(new File(scr.getScript(aSelectedKey)).getName());
		tde.gui.applyColorFont(scriptL);
		
		tde.timeLineStack.addTimeListener(this);
	}
	
	@Override
	public void timeChanged(long aTimeNew) {
		super.timeChanged(aTimeNew);
		if(wgt.isDisposed()) {
			tde.timeLineStack.removeTimeListener(this);
			return;
		}
		int aSelectedKey = scr.getPrevKeyGT(tde.timeLineStack.getTimePosMS());
		if(aSelectedKey < 0) {
			aSelectedKey = 0;
		}
		final int aKey = aSelectedKey;
		tde.gui.display.asyncExec(new Runnable() {
			@Override
			public void run() {
				lkf.setSelection(aKey);
				lkf.checkAddMode(tde.timeLineStack.getTimePosMS());
				scriptL.setText(new File(scr.getScript(aKey)).getName());
			}
		});
	}
	
	String fileChoose() {
		FileDialog aFD = new FileDialog (tde.gui.shell, SWT.OPEN);
		String [] filterNames = new String [] {"Script Files", "All Files (*)"};
		String aExts = "*.bsh;";
		String [] filterExtensions = new String [] {aExts+aExts.toUpperCase(), "*"};
		String filterPath = tde.lastDir != null ? tde.lastDir : (tde.projectDir != null ? tde.projectDir : tde.tdeDir);
		aFD.setFilterNames (filterNames);
		aFD.setFilterExtensions (filterExtensions);
		aFD.setFilterPath (filterPath);
//		dialog.setFileName ("myfile");//
		String aPath = aFD.open ();
		System.out.println ("Path: " + aPath);
		return aPath;
	}
}
