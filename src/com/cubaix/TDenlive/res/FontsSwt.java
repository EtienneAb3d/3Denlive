package com.cubaix.TDenlive.res;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;

import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.Font;
import org.eclipse.swt.widgets.Display;

import com.cubaix.TDenlive.TDenlive;

public class FontsSwt {
	TDenlive tde = null;

	public Font roboto = null;
	public Font robotoBold = null;
	
	public FontsSwt(TDenlive aTDe) {
		tde = aTDe;
		try {
			loadFont(tde.gui.display,"Roboto-Regular.ttf");
			roboto = new Font(tde.gui.display,"Robot",tde.config.fontSize,SWT.NORMAL);
			loadFont(tde.gui.display,"Roboto-Bold.ttf");
			robotoBold = new Font(tde.gui.display,"Robot",tde.config.fontSize,SWT.BOLD);
		}
		catch(Throwable t) {
			t.printStackTrace(System.err);
		}
	}
	
	void loadFont(Display aDisplay,String aFileName) throws Exception {
		String aPref = aFileName.substring(0, aFileName.lastIndexOf("."));
		String aSuff = aFileName.substring(aFileName.lastIndexOf("."));
		File tempFile = File.createTempFile(aPref, aSuff);
		tempFile.setWritable(true);
		tempFile.deleteOnExit();
		InputStream is = FontsSwt.class.getResourceAsStream("fonts/"+aFileName);
		OutputStream os = new FileOutputStream(tempFile);
		byte[] aBuf = new byte[1024];
		while(is.read(aBuf) > 0) {
			os.write(aBuf);
		}
		os.flush();
		os.close();
		is.close();
		aDisplay.loadFont(tempFile.getAbsolutePath());
	}
}
