package com.cubaix.TDenlive.res;

import java.io.InputStream;
import java.util.HashMap;

import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.widgets.Display;

import com.cubaix.TDenlive.TDenlive;
import com.cubaix.TDenlive.GUI.TDeGUI;

public class ImgsSwt {
	TDenlive tde = null;
	HashMap<String,Image> imgs = new HashMap<String,Image>();
	
	public ImgsSwt(TDenlive aTDe) {
		tde = aTDe;
	}
	public Image getIcon(String aFileName) {
		return getImage("icons/"+aFileName);
	}
	public Image getImage(String aFileName) {
		Image aI = imgs.get(aFileName);
		if(aI != null) {
			return aI;
		}
	    try{
			InputStream aS = ImgsSwt.class.getResourceAsStream("imgs/"+aFileName);
			aI = new Image(tde.gui.display, aS);
			imgs.put(aFileName, aI);
			return aI;
		}
		catch(Throwable t){
			System.err.println("Icon not found : imgs/"+aFileName);
			t.printStackTrace(System.err);
		}
	    return null;
	}
}
