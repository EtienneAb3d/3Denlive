package com.cubaix.TDenlive.utils;

import java.awt.Color;
import java.awt.Graphics;
import java.awt.image.BufferedImage;
import java.io.File;
import java.net.URLDecoder;
import java.util.Arrays;
import java.util.Vector;

import com.cubaix.TDenlive.TDenlive;

public class Frames {
	TDenlive tde = null;
	String frameDir = "frames";
	static final Vector<Frame> frames = new Vector<Frame>();
	int thumbH = 45;
	
	public Frames(TDenlive aTDe) {
		tde = aTDe;
		thumbH = tde.config.thumbH;
	}
	
	public Frame getFrame(String aPath) {
		for(Frame aF : listFrames()) {
			if(aF.path.equals(aPath)) {
				return aF;
			}
		}
		return null;
	}
	
	public int getFrameIndex(String aPath) {
		int aI = 0;
		for(Frame aF : listFrames()) {
			if(aF.path.equals(aPath)) {
				return aI;
			}
			aI++;
		}
		return -1;
	}
	
	public Vector<Frame> listFrames() {
		if(frames.size() > 0) {
			return frames;
		}
		
		Frame aF = new Frame("BLANK");
		aF.bi = ImageUtils.createImage(1920,1080);
		Graphics aG = aF.bi.getGraphics();
		aG.setColor(Color.WHITE);
		aG.fillRect(0, 0, 1920,1080);
		double aRatio = aF.bi.getWidth()/(double)aF.bi.getHeight();
		BufferedImage aTb = ImageUtils.createImage((int)(thumbH*aRatio),thumbH);
		aTb.getGraphics().drawImage(aF.bi, 0, 0, (int)(thumbH*aRatio),thumbH
				, 0, 0, aF.bi.getWidth(), aF.bi.getHeight(), null);
		aF.thumbSwt = ImageUtils.imageToSwt(tde.gui.display, aTb);
		frames.add(aF);

		String aDir = Frames.class.getProtectionDomain().getCodeSource().getLocation().getPath();
		if(aDir.toLowerCase().matches(".*[.](jar|exe)")) {
			aDir = new File(aDir).getParent();
		}
		aDir = URLDecoder.decode(aDir);
		System.out.println("Root: "+aDir);
		
		if(!new File(aDir+File.separatorChar+frameDir).exists()) {
			aDir = new File(aDir).getParent();
			System.out.println("FrameSearch: "+aDir);
		}
		if(!new File(aDir+File.separatorChar+frameDir).exists()) {
			aDir = aDir+File.separator+"distrib";
			System.out.println("FrameSearch: "+aDir);
		}
		if(!new File(aDir+File.separatorChar+frameDir).exists()) {
			System.out.println("FrameSearch: NOT FOUND");
			return frames;
		}
		
		frameDir = aDir+File.separatorChar+frameDir;
		System.out.println("FrameDir: "+frameDir);
		
		listFrames(frameDir);
		
		return frames;
	}
	
	void listFrames(String aDir) {
		File[] aFs = new File(aDir).listFiles();
		Arrays.sort(aFs);
		for(File aF : aFs) {
			if(aF.isDirectory()) {
				listFrames(aF.getPath());
			}
			if(aF.getName().toLowerCase().matches(".*[.](png|pgm|bmp|jpg|jpeg)")) {
				String aPath = aF.getPath();
				aPath = aPath.substring(aPath.indexOf(frameDir+File.separatorChar)+frameDir.length()+1);
				aPath = aPath.replaceAll("[\\\\\\\\]", "/");
				Frame aFrame = new Frame(aPath);
				try {
					aFrame.bi = ImageUtils.loadImage(aF.getPath());
					double aRatio = aFrame.bi.getWidth()/(double)aFrame.bi.getHeight();
					BufferedImage aTb = ImageUtils.createImage((int)(thumbH*aRatio),thumbH);
					ImageUtils.chessBck(aTb);
					aTb.getGraphics().drawImage(aFrame.bi, 0, 0, (int)(thumbH*aRatio),thumbH
							, 0, 0, aFrame.bi.getWidth(), aFrame.bi.getHeight(), null);
					aFrame.thumbSwt = ImageUtils.imageToSwt(tde.gui.display, aTb);
					frames.add(aFrame);
					System.out.println("Frame: "+aPath);
				}
				catch(Throwable t) {
					t.printStackTrace(System.err);
				}
			}
		}
	}
	
    public static void main(String[] args) {
    	try {
    		new Frames(null).listFrames();
    	}
    	catch(Throwable t) {
    		t.printStackTrace(System.err);
    	}
    }
}
