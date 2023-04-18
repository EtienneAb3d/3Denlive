package com.cubaix.TDenlive.utils;

import java.awt.Color;
import java.awt.Graphics;
import java.awt.image.BufferedImage;
import java.io.File;
import java.net.URLDecoder;
import java.util.Arrays;
import java.util.Vector;

import com.cubaix.TDenlive.TDenlive;

public class Lumas {
	TDenlive tde = null;
	String lumaDir = "lumas";
	static final Vector<Luma> lumas = new Vector<Luma>();
	int thumbH = 45;
	
	public Lumas(TDenlive aTDe) {
		tde = aTDe;
		thumbH = tde.config.thumbH;
	}
	
	public Luma getLuma(String aPath) {
		for(Luma aL : listLumas()) {
			if(aL.path.equals(aPath)) {
				return aL;
			}
		}
		return null;
	}
	
	public int getLumaIndex(String aPath) {
		int aI = 0;
		for(Luma aL : listLumas()) {
			if(aL.path.equals(aPath)) {
				return aI;
			}
			aI++;
		}
		return -1;
	}
	
	public Vector<Luma> listLumas() {
		if(lumas.size() > 0) {
			return lumas;
		}
		
		Luma aL = new Luma("BLANK");
		aL.bi = ImageUtils.createImage(1920,1080);
		Graphics aG = aL.bi.getGraphics();
		aG.setColor(Color.WHITE);
		aG.fillRect(0, 0, 1920,1080);
		double aRatio = aL.bi.getWidth()/(double)aL.bi.getHeight();
		BufferedImage aTb = ImageUtils.createImage((int)(thumbH*aRatio),thumbH);
		aTb.getGraphics().drawImage(aL.bi, 0, 0, (int)(thumbH*aRatio),thumbH
				, 0, 0, aL.bi.getWidth(), aL.bi.getHeight(), null);
		aL.thumbSwt = ImageUtils.imageToSwt(tde.gui.display, aTb);
		lumas.add(aL);

		String aDir = Lumas.class.getProtectionDomain().getCodeSource().getLocation().getPath();
		if(aDir.toLowerCase().matches(".*[.](jar|exe)")) {
			aDir = new File(aDir).getParent();
		}
		aDir = URLDecoder.decode(aDir);
		System.out.println("Root: "+aDir);
		
		if(!new File(aDir+File.separatorChar+lumaDir).exists()) {
			aDir = new File(aDir).getParent();
			System.out.println("LumaSearch: "+aDir);
		}
		if(!new File(aDir+File.separatorChar+lumaDir).exists()) {
			aDir = aDir+File.separator+"distrib";
			System.out.println("LumaSearch: "+aDir);
		}
		if(!new File(aDir+File.separatorChar+lumaDir).exists()) {
			System.out.println("LumaSearch: NOT FOUND");
			return lumas;
		}
		
		lumaDir = aDir+File.separatorChar+lumaDir;
		System.out.println("LumaDir: "+lumaDir);
		
		listLumas(lumaDir);
		
		return lumas;
	}
	
	void listLumas(String aDir) {
		File[] aFs = new File(aDir).listFiles();
		Arrays.sort(aFs);
		for(File aF : aFs) {
			if(aF.isDirectory()) {
				listLumas(aF.getPath());
			}
			if(aF.getName().toLowerCase().matches(".*[.](png|pgm|bmp|jpg|jpeg)")) {
				String aPath = aF.getPath();
				aPath = aPath.substring(aPath.indexOf(lumaDir+File.separatorChar)+lumaDir.length()+1);
				aPath = aPath.replaceAll("[\\\\\\\\]", "/");
				Luma aL = new Luma(aPath);
				try {
					aL.bi = ImageUtils.loadImage(aF.getPath());
					double aRatio = aL.bi.getWidth()/(double)aL.bi.getHeight();
					BufferedImage aTb = ImageUtils.createImage((int)(thumbH*aRatio),thumbH);
					aTb.getGraphics().drawImage(aL.bi, 0, 0, (int)(thumbH*aRatio),thumbH
							, 0, 0, aL.bi.getWidth(), aL.bi.getHeight(), null);
					aL.thumbSwt = ImageUtils.imageToSwt(tde.gui.display, aTb);
					lumas.add(aL);
					System.out.println("Luma: "+aPath);
				}
				catch(Throwable t) {
					t.printStackTrace(System.err);
				}
			}
		}
	}
	
    public static void main(String[] args) {
    	try {
    		new Lumas(null).listLumas();
    	}
    	catch(Throwable t) {
    		t.printStackTrace(System.err);
    	}
    }
}
