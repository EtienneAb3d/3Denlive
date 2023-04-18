package com.cubaix.TDenlive.utils;

import java.awt.image.BufferedImage;

import org.eclipse.swt.graphics.Image;

public class Frame {
	public String path = "";
	public BufferedImage bi = null;
	public Image thumbSwt = null;
	
	public Frame(String aPath) {
		path = aPath;
	}

}
