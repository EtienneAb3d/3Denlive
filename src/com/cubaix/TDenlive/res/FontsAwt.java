package com.cubaix.TDenlive.res;

import java.awt.Font;
import java.io.File;
import java.io.InputStream;

import com.cubaix.TDenlive.TDenlive;

public class FontsAwt {
	TDenlive tde = null;

	public Font roboto = null;
	public Font robotoBold = null;
	
	public FontsAwt(TDenlive aTDe) {
		tde = aTDe;
		try {
			roboto = loadFont("Roboto-Regular.ttf");
			robotoBold = loadFont("Roboto-Bold.ttf");
		}
		catch(Throwable t) {
			t.printStackTrace(System.err);
		}
	}
	
	Font loadFont(String aFileName) throws Exception {
		String aPref = aFileName.substring(0, aFileName.lastIndexOf("."));
		String aSuff = aFileName.substring(aFileName.lastIndexOf(".")+1);
		File tempFile = File.createTempFile(aPref, aSuff);
		tempFile.setWritable(true);
		tempFile.deleteOnExit();
		InputStream is = FontsAwt.class.getResourceAsStream("fonts/"+aFileName);
		Font aF = Font.createFont(Font.TRUETYPE_FONT, is).deriveFont(14f);
		is.close();
		return aF; 
	}
}
