package com.cubaix.TDenlive.GUI;

import java.awt.Color;

import com.cubaix.TDenlive.TDenlive;

public class ColorsAwt {
	TDenlive tde = null;

	public Color WHITE = null;
	public Color LIGHTGRAY = null;
	public Color GRAY = null;
	public Color DARK_GRAY = null;
	public Color BLACK = null;
	
	public Color RED = null;

	public Color CUBAIX_BLUE = null;
	public Color BLUE_L60 = null;
	
	public Color CUBAIX_PINK = null;//L=32.5
	public Color PINK_L35 = null;
	public Color PINK_L40 = null;
	public Color PINK_L50 = null;
	public Color PINK_L86 = null;
	public Color PINK_L92 = null;
	
	public ColorsAwt(TDenlive aTDe){
		tde = aTDe;

		WHITE = Color.WHITE;
		LIGHTGRAY = new Color(200,200,200);
		GRAY = Color.GRAY;
		DARK_GRAY = Color.DARK_GRAY;
		BLACK = Color.BLACK;

		RED = Color.RED;

		CUBAIX_BLUE = new Color(0,50,141);
		BLUE_L60 = new Color(127,138,244);
		
		CUBAIX_PINK = new Color(121,44,125);
		PINK_L35 = new Color(128,50,132);
		PINK_L40 = new Color(141,63,145);
		PINK_L50 = new Color(169,90,171);
		PINK_L86 = new Color(255,197,255);
		PINK_L92 = new Color(255,222,255);
	}
}
