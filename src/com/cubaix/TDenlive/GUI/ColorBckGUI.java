package com.cubaix.TDenlive.GUI;

import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.CLabel;
import org.eclipse.swt.events.SelectionAdapter;
import org.eclipse.swt.events.SelectionEvent;
import org.eclipse.swt.graphics.Color;
import org.eclipse.swt.graphics.RGB;
import org.eclipse.swt.graphics.Rectangle;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Canvas;
import org.eclipse.swt.widgets.ColorDialog;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Spinner;

import com.cubaix.TDenlive.TDenlive;

/**
 * Popup to create a plain color clip: color preview, R/G/B values, the
 * system color picker, and the Create / Cancel buttons.
 * open() returns the chosen color, or null if canceled.
 */
public class ColorBckGUI {
	TDenlive tde = null;
	Shell shell = null;
	Canvas preview = null;
	Spinner[] spinners = new Spinner[3];
	RGB rgb = null;
	RGB result = null;

	public ColorBckGUI(TDenlive aTDe, RGB aInitRGB) {
		tde = aTDe;
		rgb = aInitRGB != null ? new RGB(aInitRGB.red, aInitRGB.green, aInitRGB.blue) : new RGB(0, 0, 0);
		createContents();
	}

	public RGB open() {
		shell.pack();
		Rectangle aP = tde.gui.shell.getBounds();
		Rectangle aS = shell.getBounds();
		shell.setLocation(aP.x + (aP.width - aS.width) / 2, aP.y + (aP.height - aS.height) / 2);
		shell.open();
		while (!shell.isDisposed()) {
			if (!tde.gui.display.readAndDispatch()) {
				tde.gui.display.sleep();
			}
		}
		return result;
	}

	void createContents() {
		shell = new Shell(tde.gui.shell, SWT.DIALOG_TRIM | SWT.APPLICATION_MODAL);
		shell.setText(tde.gui.lngs.get("ColorBck.Title"));
		shell.setLayout(new GridLayout(1, false));
		tde.gui.applyColorFont(shell);
		shell.setImage(tde.gui.imgsSwt.getIcon("3Denlive32.png"));

		preview = new Canvas(shell, SWT.BORDER);
		GridData aPGD = new GridData(GridData.FILL_HORIZONTAL);
		aPGD.widthHint = 260;
		aPGD.heightHint = 80;
		preview.setLayoutData(aPGD);
		preview.addPaintListener(e -> {
			Color aC = new Color(rgb);
			e.gc.setBackground(aC);
			e.gc.fillRectangle(preview.getClientArea());
		});

		Composite aValues = new Composite(shell, SWT.NONE);
		aValues.setLayoutData(new GridData(GridData.FILL_HORIZONTAL));
		tde.gui.applyColorFont(aValues);
		GridLayout aVGL = new GridLayout(7, false);
		tde.gui.applyNoMargin(aVGL);
		aValues.setLayout(aVGL);
		String[] aLetters = { "Generic.RedLetter", "Generic.GreenLetter", "Generic.BlueLetter" };
		int[] aInit = { rgb.red, rgb.green, rgb.blue };
		for (int c = 0; c < 3; c++) {
			CLabel aL = new CLabel(aValues, SWT.NONE);
			aL.setText(tde.gui.lngs.get(aLetters[c]));
			tde.gui.applyColorFont(aL);
			Spinner aSp = new Spinner(aValues, SWT.BORDER);
			aSp.setValues(aInit[c], 0, 255, 0, 1, 16);
			tde.gui.applyColorFont(aSp);
			aSp.addModifyListener(e -> updateFromSpinners());
			spinners[c] = aSp;
		}
		Button aPalette = new Button(aValues, SWT.PUSH);
		aPalette.setText(tde.gui.lngs.get("ColorBck.Palette"));
		aPalette.setImage(tde.gui.imgsSwt.getIcon("colourPalette.gif"));
		tde.gui.applyColorFont(aPalette);
		GridData aPalGD = new GridData(GridData.HORIZONTAL_ALIGN_END | GridData.GRAB_HORIZONTAL);
		aPalette.setLayoutData(aPalGD);
		aPalette.addSelectionListener(new SelectionAdapter() {
			@Override
			public void widgetSelected(SelectionEvent arg0) {
				ColorDialog aCD = new ColorDialog(shell);
				aCD.setRGB(rgb);
				RGB aRGB = aCD.open();
				if (aRGB != null) {
					spinners[0].setSelection(aRGB.red);
					spinners[1].setSelection(aRGB.green);
					spinners[2].setSelection(aRGB.blue);
					updateFromSpinners();
				}
			}
		});

		Composite aButtons = new Composite(shell, SWT.NONE);
		aButtons.setLayoutData(new GridData(GridData.HORIZONTAL_ALIGN_END));
		tde.gui.applyColorFont(aButtons);
		GridLayout aBGL = new GridLayout(2, true);
		tde.gui.applyNoMargin(aBGL);
		aButtons.setLayout(aBGL);

		Button aCancel = new Button(aButtons, SWT.PUSH);
		aCancel.setText(tde.gui.lngs.get("ColorBck.Cancel"));
		tde.gui.applyColorFont(aCancel);
		aCancel.setLayoutData(new GridData(GridData.FILL_HORIZONTAL));
		aCancel.addSelectionListener(new SelectionAdapter() {
			@Override
			public void widgetSelected(SelectionEvent arg0) {
				result = null;
				shell.close();
			}
		});

		Button aCreate = new Button(aButtons, SWT.PUSH);
		aCreate.setText(tde.gui.lngs.get("ColorBck.Create"));
		aCreate.setImage(tde.gui.imgsSwt.getIcon("checked.gif"));
		tde.gui.applyColorFont(aCreate);
		aCreate.setLayoutData(new GridData(GridData.FILL_HORIZONTAL));
		aCreate.addSelectionListener(new SelectionAdapter() {
			@Override
			public void widgetSelected(SelectionEvent arg0) {
				updateFromSpinners();
				result = new RGB(rgb.red, rgb.green, rgb.blue);
				shell.close();
			}
		});
		shell.setDefaultButton(aCreate);
	}

	void updateFromSpinners() {
		rgb = new RGB(spinners[0].getSelection(), spinners[1].getSelection(), spinners[2].getSelection());
		preview.redraw();
	}
}
