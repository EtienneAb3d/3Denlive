package com.cubaix.TDenlive.GUI;

import java.io.File;

import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.CLabel;
import org.eclipse.swt.events.SelectionEvent;
import org.eclipse.swt.events.SelectionListener;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Combo;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.DirectoryDialog;
import org.eclipse.swt.widgets.Shell;

import com.cubaix.TDenlive.TDConfig;
import com.cubaix.TDenlive.TDenlive;

public class SettingsGUI {
	TDenlive tde = null;
	Shell shell = null;

	public SettingsGUI(TDenlive aTDe) {
		tde = aTDe;
		createContents();
		shell.open();
	}
	
	void createContents() {
		shell = new Shell(tde.gui.display);
		shell.setText("3Denlive");
		shell.setLayout(new GridLayout(1, false));
		shell.setSize(600, 600);
		tde.gui.applyColorFont(shell);
		shell.setImage(tde.gui.imgsSwt.getIcon("3Denlive32.png"));
		
		Button aRB = new Button(shell, SWT.PUSH);
		tde.gui.applyColorFont(aRB);
		aRB.setLayoutData(new GridData(GridData.FILL_HORIZONTAL));
		aRB.setText(tde.gui.lngs.get("Config.RenderDir")+"="+tde.renderDir);
		aRB.addSelectionListener(new SelectionListener() {
			@Override
			public void widgetSelected(SelectionEvent arg0) {
				DirectoryDialog aDD = new DirectoryDialog(shell, SWT.OPEN);
				aDD.setFilterPath(tde.renderDir);
				String aDir = aDD.open();
				if(aDir != null && !aDir.trim().isEmpty() && new File(aDir).exists()) {
					tde.renderDir = tde.config.renderDir = aDir;
					tde.config.saveGeneral(tde.configPath);
					aRB.setText(tde.gui.lngs.get("Config.RenderDir")+"="+tde.renderDir);
				}
			}
			@Override
			public void widgetDefaultSelected(SelectionEvent arg0) {
			}
		});
		
		addModeLine(TDConfig.PROCESSING_MODE_WORK, tde.gui.lngs.get("Config.WorkMode"));
		addModeLine(TDConfig.PROCESSING_MODE_FULLSCREEN, tde.gui.lngs.get("Config.FullScreenMode"));
		addModeLine(TDConfig.PROCESSING_MODE_RENDER, tde.gui.lngs.get("Config.RenderMode"));
		
		Button aOk = new Button(shell, SWT.PUSH);
		GridData aOGD = new GridData();
		aOGD.heightHint = 20;
		aOk.setLayoutData(aOGD);
		aOk.setText(tde.gui.lngs.get("Config.Done"));
		aOk.setImage(tde.gui.imgsSwt.getIcon("checked.gif"));
		tde.gui.applyColorFont(aOk);
		aOk.setAlignment(SWT.CENTER);
		aOk.addSelectionListener(new SelectionListener() {
			@Override
			public void widgetSelected(SelectionEvent arg0) {
				shell.close();
				shell.dispose();
			}
			@Override
			public void widgetDefaultSelected(SelectionEvent arg0) {
				shell.close();
				shell.dispose();
			}
		});
	}
	
	void addModeLine(final int aMode,String aText) {
		Composite aWorkC = new Composite(shell,SWT.NONE); 
		GridData aWCGD = new GridData(GridData.FILL_HORIZONTAL);
		aWCGD.heightHint = 25;
		aWorkC.setLayoutData(aWCGD);
		tde.gui.applyColorFont(aWorkC);
		GridLayout aGL = new GridLayout(4, false);
		tde.gui.applyNoMargin(aGL);
		aWorkC.setLayout(aGL);

		CLabel aWork = new CLabel(aWorkC, SWT.NONE);
		GridData aWGD = new GridData();
		aWGD.heightHint = 20;
		aWork.setLayoutData(aWGD);
		aWork.setText(aText);
		tde.gui.applyColorFont(aWork);

		Composite aSpace = new Composite(aWorkC, SWT.SEPARATOR|SWT.FILL);
		tde.gui.applyColorFont(aSpace);
		GridData aSpaceGD = new GridData(GridData.FILL_HORIZONTAL);
		aSpaceGD.heightHint = 25;
		aSpace.setLayoutData(aSpaceGD);

		Combo processingRes = new Combo(aWorkC, SWT.DROP_DOWN);//|SWT.READ_ONLY);
		GridData aResGD = new GridData(GridData.HORIZONTAL_ALIGN_CENTER);
		aResGD.widthHint = 80;
		aResGD.heightHint = 20;
		processingRes.setLayoutData(aResGD);
		tde.gui.applyColorFont(processingRes);
		processingRes.setItems(TDConfig.RESNAMES);
		processingRes.addSelectionListener(new SelectionListener() {
			@Override
			public void widgetSelected(SelectionEvent arg0) {
				tde.config.processingResNames[aMode] = TDConfig.RESSHORTNAMES[processingRes.getSelectionIndex()];
				tde.config.processingResValues[aMode] = TDConfig.RESVALUES[processingRes.getSelectionIndex()];
				processingRes.setText(TDConfig.RESSHORTNAMES[processingRes.getSelectionIndex()]);//Doesn't work if READ_ONLY !
				tde.config.saveGeneral(tde.configPath);
			}
			@Override
			public void widgetDefaultSelected(SelectionEvent arg0) {
				tde.config.processingResNames[aMode] = TDConfig.RESSHORTNAMES[processingRes.getSelectionIndex()];
				tde.config.processingResValues[aMode] = TDConfig.RESVALUES[processingRes.getSelectionIndex()];
				processingRes.setText(TDConfig.RESSHORTNAMES[processingRes.getSelectionIndex()]);//Doesn't work if READ_ONLY !
				tde.config.saveGeneral(tde.configPath);
			}
		});
		processingRes.setText(tde.config.processingResNames[aMode]);

		Combo processingHdr = new Combo(aWorkC, SWT.DROP_DOWN);//|SWT.READ_ONLY);
		GridData aHdrGD = new GridData(GridData.HORIZONTAL_ALIGN_CENTER);
		aHdrGD.widthHint = 80;
		aHdrGD.heightHint = 20;
		processingHdr.setLayoutData(aHdrGD);
		tde.gui.applyColorFont(processingHdr);
		processingHdr.setItems(TDConfig.HDRLIST);
		processingHdr.addSelectionListener(new SelectionListener() {
			@Override
			public void widgetSelected(SelectionEvent arg0) {
				tde.config.processingHdr[aMode] = processingHdr.getSelectionIndex() == 1;
				tde.config.saveGeneral(tde.configPath);
			}
			@Override
			public void widgetDefaultSelected(SelectionEvent arg0) {
				tde.config.processingHdr[aMode] = processingHdr.getSelectionIndex() == 1;
				tde.config.saveGeneral(tde.configPath);
			}
		});
		processingHdr.setText(tde.config.processingHdr[aMode]?TDConfig.HDRLIST[1]:TDConfig.HDRLIST[0]);
	}
}
