package com.cubaix.TDenlive.res;

import java.awt.image.BufferedImage;
import java.io.InputStream;
import java.util.HashMap;

import javax.imageio.ImageIO;

import com.cubaix.TDenlive.TDenlive;

public class ImgsAwt {
	TDenlive tde = null;
	HashMap<String,BufferedImage> imgs = new HashMap<String,BufferedImage>();
	
	public ImgsAwt(TDenlive aTDe) {
		tde = aTDe;
	}
	public BufferedImage getIcon(String aFileName) {
		return getImage("icons/"+aFileName);
	}
	public BufferedImage getImage(String aFileName) {
		BufferedImage aI = imgs.get(aFileName);
		if(aI != null) {
			return aI;
		}
	    try{
			InputStream aS = ImgsAwt.class.getResourceAsStream("imgs/"+aFileName);
			aI = ImageIO.read(aS);
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
