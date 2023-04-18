package com.cubaix.TDenlive.GUI;

import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.Color;
import org.eclipse.swt.widgets.Display;

import com.cubaix.TDenlive.TDenlive;

public class ColorsSwt {
	TDenlive tde = null;

	public Color WHITE = null;
	public Color LIGHTGRAY = null;
	public Color GRAY = null;
	public Color DARK_GRAY = null;
	public Color BLACK = null;
	
	public Color RED = null;
	public Color CYAN = null;

	public Color CUBAIX_BLUE = null;
	public Color BLUE_L60 = null;
	
	public Color CUBAIX_PINK = null;//L=32.5
	public Color PINK_L35 = null;
	public Color PINK_L40 = null;
	public Color PINK_L50 = null;
	public Color PINK_L86 = null;
	public Color PINK_L92 = null;
	
	public ColorsSwt(TDenlive aTDe){
		tde = aTDe;

		WHITE = tde.gui.display.getSystemColor(SWT.COLOR_WHITE);
		LIGHTGRAY = new Color(tde.gui.display,200,200,200);
		GRAY = tde.gui.display.getSystemColor(SWT.COLOR_GRAY);
		DARK_GRAY = tde.gui.display.getSystemColor(SWT.COLOR_DARK_GRAY);
		BLACK = tde.gui.display.getSystemColor(SWT.COLOR_BLACK);

		RED = tde.gui.display.getSystemColor(SWT.COLOR_RED);
		CYAN = tde.gui.display.getSystemColor(SWT.COLOR_CYAN);

		CUBAIX_BLUE = new Color(tde.gui.display,0,50,141);
		BLUE_L60 = new Color(tde.gui.display,127,138,244);
		
		CUBAIX_PINK = new Color(tde.gui.display,121,44,125);
		PINK_L35 = new Color(tde.gui.display,128,50,132);
		PINK_L40 = new Color(tde.gui.display,141,63,145);
		PINK_L50 = new Color(tde.gui.display,169,90,171);
		PINK_L86 = new Color(tde.gui.display,255,197,255);
		PINK_L92 = new Color(tde.gui.display,255,222,255);
	}
}
