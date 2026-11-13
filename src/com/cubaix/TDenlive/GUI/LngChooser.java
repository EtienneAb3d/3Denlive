package com.cubaix.TDenlive.GUI;

import org.eclipse.swt.SWT;
import org.eclipse.swt.events.SelectionEvent;
import org.eclipse.swt.events.SelectionListener;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Shell;

import com.cubaix.TDenlive.TDenlive;
import com.cubaix.TDenlive.res.Lngs;

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
		//One flag button per interface language, 3 per row (2 rows for 6 languages)
		shell.setLayout(new GridLayout(3, true));
		shell.setSize(600, 340);
		shell.setBackground(tde.gui.colorsSwt.WHITE);
		shell.setForeground(tde.gui.colorsSwt.CUBAIX_BLUE);
		shell.setFont(tde.gui.fontsSwt.roboto);
		shell.setImage(tde.gui.imgsSwt.getIcon("3Denlive32.png"));

		for(String[] aL : Lngs.LANGUAGES) {
			final String aCode = aL[0];
			Button aB = new Button(shell, SWT.PUSH);
			aB.setLayoutData(new GridData(GridData.FILL_BOTH));
			aB.setImage(tde.gui.imgsSwt.getImage(aCode+".png"));
			aB.setBackground(tde.gui.colorsSwt.WHITE);
			aB.setForeground(tde.gui.colorsSwt.CUBAIX_BLUE);
			aB.setFont(tde.gui.fontsSwt.roboto);
			aB.setToolTipText(aL[1]);
			aB.addSelectionListener(new SelectionListener() {
				@Override
				public void widgetSelected(SelectionEvent arg0) {
					choose(aCode);
				}
				@Override
				public void widgetDefaultSelected(SelectionEvent arg0) {
					choose(aCode);
				}
			});
		}
	}

	void choose(String aCode) {
		tde.config.lng = aCode;
		shell.close();
		shell.dispose();
	}
}
