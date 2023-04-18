package com.cubaix.TDenlive.GUI;

import org.eclipse.swt.SWT;
import org.eclipse.swt.events.SelectionEvent;
import org.eclipse.swt.events.SelectionListener;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Shell;

import com.cubaix.TDenlive.TDenlive;

public class LngChooser {
	TDenlive tde = null;
	Shell shell = null;

	public LngChooser(TDenlive aTDe) {
		tde = aTDe;
		createContents();
		shell.open();
		//Wait
		while (!shell.isDisposed()) {
			if (!tde.gui.display.readAndDispatch()) {
				tde.gui.display.sleep();
			}
		}
	}

	void createContents() {
		shell = new Shell(tde.gui.display);
		shell.setText("3Denlive");
		shell.setLayout(new GridLayout(2, true));
		shell.setSize(600, 300);
		shell.setBackground(tde.gui.colorsSwt.WHITE);
		shell.setForeground(tde.gui.colorsSwt.CUBAIX_BLUE);
		shell.setFont(tde.gui.fontsSwt.roboto);
		shell.setImage(tde.gui.imgsSwt.getIcon("3Denlive32.png"));

		Button aFR = new Button(shell, SWT.PUSH);
		GridData aFRGD = new GridData(GridData.FILL_BOTH);
		aFR.setLayoutData(aFRGD);
		aFR.setImage(tde.gui.imgsSwt.getImage("FR.png"));
		aFR.setBackground(tde.gui.colorsSwt.WHITE);
		aFR.setForeground(tde.gui.colorsSwt.CUBAIX_BLUE);
		aFR.setFont(tde.gui.fontsSwt.roboto);
		aFR.addSelectionListener(new SelectionListener() {
			@Override
			public void widgetSelected(SelectionEvent arg0) {
				tde.config.lng = "FR";
				shell.close();
				shell.dispose();
			}
			@Override
			public void widgetDefaultSelected(SelectionEvent arg0) {
				tde.config.lng = "FR";
				shell.close();
				shell.dispose();
			}
		});
		Button aEN = new Button(shell, SWT.PUSH);
		GridData aENGD = new GridData(GridData.FILL_BOTH);
		aEN.setLayoutData(aENGD);
		aEN.setImage(tde.gui.imgsSwt.getImage("EN.png"));
		aEN.setBackground(tde.gui.colorsSwt.WHITE);
		aEN.setForeground(tde.gui.colorsSwt.CUBAIX_BLUE);
		aEN.setFont(tde.gui.fontsSwt.roboto);
		aEN.addSelectionListener(new SelectionListener() {
			@Override
			public void widgetSelected(SelectionEvent arg0) {
				tde.config.lng = "EN";
				shell.close();
				shell.dispose();
			}
			@Override
			public void widgetDefaultSelected(SelectionEvent arg0) {
				tde.config.lng = "EN";
				shell.close();
				shell.dispose();
			}
		});
	}
}
