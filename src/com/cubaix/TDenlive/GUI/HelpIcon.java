package com.cubaix.TDenlive.GUI;

import java.util.function.Supplier;

import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.graphics.Rectangle;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Shell;

import com.cubaix.TDenlive.TDenlive;

/**
 * Help icon (informationCircle.gif): its text shown in a popup while the mouse is over it, instead of a long
 * explanation in a panel
 */
public class HelpIcon {
	//Width of the text of the popup
	static final int WIDTH = 380;

	final TDenlive tde;
	final Label icon;
	final Supplier<String> text;
	Shell popup = null;

	HelpIcon(TDenlive aTDe,Composite aParent,Supplier<String> aText) {
		tde = aTDe;
		text = aText;
		icon = new Label(aParent, SWT.NONE);
		tde.gui.applyColorFont(icon);
		icon.setImage(tde.gui.imgsSwt.getIcon("informationCircle.gif"));
		icon.setCursor(icon.getDisplay().getSystemCursor(SWT.CURSOR_HELP));
		icon.setLayoutData(new GridData(SWT.END, SWT.CENTER, false, false));
		icon.addListener(SWT.MouseEnter, e -> show());
		icon.addListener(SWT.MouseExit, e -> hide());
		icon.addListener(SWT.Dispose, e -> hide());
	}

	/**
	 * A help icon in aParent (a cell of its layout) showing aText at each hover (read then: texts that change)
	 */
	public static Label create(TDenlive aTDe,Composite aParent,Supplier<String> aText) {
		return new HelpIcon(aTDe, aParent, aText).icon;
	}

	void show() {
		hide();
		String aT = text.get();
		if(aT == null || aT.isEmpty() || icon.isDisposed()) {
			return;
		}
		popup = new Shell(icon.getShell(), SWT.TOOL | SWT.ON_TOP | SWT.NO_FOCUS);
		popup.setBackground(tde.gui.colorsSwt.CUBAIX_BLUE);
		GridLayout aGL = new GridLayout(1, false);
		aGL.marginWidth = aGL.marginHeight = 1;
		popup.setLayout(aGL);
		Composite aIn = new Composite(popup, SWT.NONE);
		tde.gui.applyColorFont(aIn);
		aIn.setLayoutData(new GridData(GridData.FILL_BOTH));
		GridLayout aIGL = new GridLayout(1, false);
		aIGL.marginWidth = aIGL.marginHeight = 8;
		aIn.setLayout(aIGL);
		Label aL = new Label(aIn, SWT.WRAP);
		tde.gui.applyColorFont(aL);
		aL.setText(aT);
		GridData aGD = new GridData(GridData.FILL_HORIZONTAL);
		aGD.widthHint = WIDTH;
		aL.setLayoutData(aGD);
		Point aSize = popup.computeSize(SWT.DEFAULT, SWT.DEFAULT);
		popup.setSize(aSize);
		//Below the icon, its right edge at that of the icon, kept on the screen
		Rectangle aB = icon.getBounds();
		Point aAt = icon.getParent().toDisplay(aB.x+aB.width, aB.y+aB.height+2);
		Rectangle aScr = icon.getMonitor().getClientArea();
		int aX = Math.max(aScr.x, Math.min(aAt.x-aSize.x, aScr.x+aScr.width-aSize.x));
		int aY = aAt.y;
		if(aY+aSize.y > aScr.y+aScr.height) {
			aY = Math.max(aScr.y, icon.getParent().toDisplay(aB.x, aB.y).y-aSize.y-2);
		}
		popup.setLocation(aX, aY);
		popup.setVisible(true);
	}

	void hide() {
		if(popup != null && !popup.isDisposed()) {
			popup.dispose();
		}
		popup = null;
	}
}
