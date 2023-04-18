package com.cubaix.TDenlive.processors;

import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.BasicFileAttributeView;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.util.Vector;

import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.graphics.Rectangle;

import com.cubaix.TDenlive.TDConfig;
import com.cubaix.TDenlive.TDenlive;
import com.cubaix.TDenlive.medias.Clip;
import com.cubaix.TDenlive.medias.Media;
import com.cubaix.TDenlive.utils.ImageUtils;
import com.cubaix.TDenlive.xml.XmlObject;
import com.cubaix.TDenlive.xml.XmlTag;

import bsh.Interpreter;

public class Scripter extends Processor {
	class Key{
		String script = "/home/etienne/3DeDemos/DemoScript/script.bsh";
		long time = 0;
		Interpreter bsh = null;
		long scriptDate = 0;
	}
	Vector<Key> keys = new Vector<Key>();
	
	public Scripter(TDenlive aTDe,Media aTargetMedia) {
		super(aTDe,aTargetMedia);
		isExpended = true;
		keys.add(new Key());
	}

	@Override
	public String getClassName() {return "Scripter";}
	
	@Override
	public int openProject(Vector<XmlObject> aOs, int o) throws Exception {
		keys.clear();
		for(;o < aOs.size();o++) {
			XmlObject aO = aOs.elementAt(o);
			if(aO instanceof XmlTag) {
				XmlTag aT = (XmlTag)aO;
				if("/Scripter".equalsIgnoreCase(aT.tagName)) {
					return o;
				}
				if("script".equalsIgnoreCase(aT.tagName)) {
					o++;
					keys.add(new Key());
					keys.lastElement().script = aOs.elementAt(o).text;
				}
				if("time".equalsIgnoreCase(aT.tagName)) {
					o++;
					keys.lastElement().time = Long.parseLong(aOs.elementAt(o).text);
				}
			}
		}
		if(keys.size() <= 0) {
			keys.add(new Key());
		}
		return o;
	}
	
	@Override
	public void saveProject(StringBuffer aSB) throws Exception {
		aSB.append("						<Scripter>\n");
		for(Key aK : keys) {
			aSB.append("							<script>" + aK.script + "</script>\n");
			aSB.append("							<time>" + aK.time + "</time>\n");
		}
		aSB.append("						</Scripter>\n");
	}

	@Override
	void setDescr() {
		name = tde.gui.lngs.get("Processor.Scripter");
		icon = "script.png";
	}

	public boolean isZero() {
		for(Key aK : keys) {
			if(aK.script != null) {
				return false;
			}
		}
		return true; 
	}
	
	public int getNbKeys() {
		return keys.size();
	}
	
	public int addKeyGT(long aTime){
		if(aTime <= ((Clip)targetMedia).getStartTimeMS() || aTime > ((Clip)targetMedia).getStartTimeMS() + ((Clip)targetMedia).getDurationMS()) {
			//Can't insert outside
			return -1;
		}
		Key aKey = getInterKeyGT(aTime);
		for(int k = keys.size() - 1;k >= 0;k--) {
			Key aK = keys.elementAt(k);
			if(aK.time <= aKey.time) {
				keys.add(k+1, aKey);
				return k+1;
			}
		}
		//??
		keys.add(aKey);
		return 0;
	}
	
	public void deleteKey(int aKey) {
		//Can't delete first
		if(aKey <= 0 || aKey >= keys.size()) {
			return;
		}
		keys.remove(aKey);
	}
	
	public int getPrevKeyGT(long aTime){
		long aT = aTime - ((Clip)targetMedia).getStartTimeMS();
		for(int k = keys.size() - 1;k >= 0;k--) {
			Key aK = keys.elementAt(k);
			if(aK.time <= aT) {
				return k;
			}
		}
		//No key before
		return -1;
	}
	
	public int getNextKeyGT(long aTime){
		long aT = aTime - ((Clip)targetMedia).getStartTimeMS();
		for(int k = 0;k < keys.size();k++) {
			Key aK = keys.elementAt(k);
			if(aK.time > aT) {
				return k;
			}
		}
		//No key after
		return -1;
	}
	
	public String getScript(int aKey) {
		return keys.elementAt(aKey).script;
	}

	public void setScript(int aKey,String script) {
		warnNextAlignments();
		keys.elementAt(aKey).script = script;
	}

	public long getTime(int aKey) {
		return keys.elementAt(aKey).time;
	}

	public void warnNextAlignments() {
		//Changing the placement will alter next alignments !
		boolean aSawMe = false;
		for(Processor aP : ((Clip)targetMedia).getProcessors()) {
			if(this == aP) {
				aSawMe = true;
			}
			if(aSawMe && 
					(aP instanceof StereoAligner
							|| aP instanceof Cropper)
					&& !aP.isWarning) {
				aP.isWarning = true;
				tde.gui.display.asyncExec(new Runnable() {
					@Override
					public void run() {
						//Force refresh
						tde.gui.processorGUI.setProcessors(((Clip)targetMedia).getProcessors());
					}
				});
			}
		}
	}
	
	@Override
	public Image process2Swt(Media aMedia,int aProcessingMode,Rectangle aTargetSize,long aTime) {
		// TODO Auto-generated method stub
		return null;
	}

	String findPath(String aPath) {
		if(new File(aPath).exists()) {
			return aPath;
		}
		if(new File(tde.projectDir+File.separatorChar+new File(aPath).getName()).exists()) {
			return tde.projectDir+File.separatorChar+new File(aPath).getName();
		}
		return tde.lastDir+File.separatorChar+new File(aPath).getName();
	}
	
	Key getInterKeyGT(long aTimePos){
		long aTime = aTimePos-((Clip)targetMedia).getStartTimeMS();
		for(int k = keys.size()-1;k >= 0;k--) {
			Key aKey = keys.elementAt(k);
			synchronized (aKey) {
				if(aKey.script != null) {
					try {
						Path p = Paths.get(aKey.script = findPath(aKey.script));
						BasicFileAttributes view = Files.getFileAttributeView(p, BasicFileAttributeView.class)
								.readAttributes();
						FileTime aLastUpdated = view.lastModifiedTime();
						if(aKey.bsh == null || aLastUpdated.toMillis() != aKey.scriptDate) {
							aKey.bsh = new Interpreter();
							aKey.bsh.source(aKey.script);
							aKey.scriptDate = aLastUpdated.toMillis();
						}
					}
					catch(Throwable t) {
						t.printStackTrace(System.err);
					}
				}
			}
			if(aKey.time <= aTime) {
				if(k == keys.size()-1) {
					Key aInter = new Key();
					aInter.script = aKey.script;
					aInter.time = aTime;
					aInter.bsh = aKey.bsh;
					return aInter;
				}
				Key aNext = keys.elementAt(k+1);
				Key aInter = new Key();
				double aFact = (aTime-aKey.time)/(double)(aNext.time-aKey.time);
				aInter.script = aKey.script;
				aInter.time = aTime;
				aInter.bsh = aKey.bsh;
				return aInter;
			}
		}
		//??
		Key aKey = keys.firstElement();
		Key aInter = new Key();
		aInter.script = aKey.script;
		aInter.time = aTime;
		aInter.bsh = aKey.bsh;
		return aInter;
	}
	
	@Override
	public void process2Awt(Media aMedia,int aProcessingMode,long aTime) {
		if(isZero()) {
			return;
		}
		try {
			Thread aThL = new Thread(new Runnable() {
				@Override
				public void run() {
					BufferedImage aBIL = aMedia.getWorkLeft(aProcessingMode,aTime);
					int aWidth = aBIL.getWidth();
					int aHeight = aBIL.getHeight();
					int aWidthOut = (int)(tde.config.outRatio*tde.config.processingResValues[aProcessingMode]);
					int aHeightOut = tde.config.processingResValues[aProcessingMode];
					BufferedImage aProcessedBIL = ImageUtils.createImage(aWidthOut,aHeightOut,tde.config.processingHdr[aProcessingMode]);
					Graphics2D aGL2 = (Graphics2D)aProcessedBIL.getGraphics();
					Key aKey = getInterKeyGT(aTime);
					if(!tde.config.fastMode || aProcessingMode == TDConfig.PROCESSING_MODE_RENDER) {
						aGL2.setRenderingHint(RenderingHints.KEY_ANTIALIASING,RenderingHints.VALUE_ANTIALIAS_ON);
						aGL2.setRenderingHint(RenderingHints.KEY_RENDERING,RenderingHints.VALUE_RENDER_QUALITY);
					}
					if(aKey.script == null || aKey.bsh == null) {
						aGL2.drawImage(aBIL,0,0,null);
					}
					else {
						try {
							aKey.bsh.set("sourceIL", aBIL);
							aKey.bsh.set("sourceW", aWidth);
							aKey.bsh.set("sourceH", aHeight);
							aKey.bsh.set("targetIL", aProcessedBIL);
							aKey.bsh.set("targetW", aWidthOut);
							aKey.bsh.set("targetH", aHeightOut);
							aKey.bsh.set("targetGL", aGL2);
							aKey.bsh.set("timeMS", aTime-((Clip)targetMedia).getStartTimeMS());
							aKey.bsh.eval("processL()");
						}
						catch(Throwable t) {
							t.printStackTrace(System.err);
						}
					}
					aGL2.dispose();
					aMedia.setWorkLeft(aProcessedBIL);
				}
			});
			aThL.start();
			Thread aThR = new Thread(new Runnable() {
				@Override
				public void run() {
					BufferedImage aBIR = aMedia.getWorkRight(aProcessingMode,aTime);
					int aWidth = aBIR.getWidth();
					int aHeight = aBIR.getHeight();
					int aWidthOut = (int)(tde.config.outRatio*tde.config.processingResValues[aProcessingMode]);
					int aHeightOut = tde.config.processingResValues[aProcessingMode];
					BufferedImage aProcessedBIR = ImageUtils.createImage(aWidthOut,aHeightOut,tde.config.processingHdr[aProcessingMode]);
					Graphics2D aGR2 = (Graphics2D)aProcessedBIR.getGraphics();
					AffineTransform aATR = new AffineTransform();
					Key aKey = getInterKeyGT(aTime);
					if(!tde.config.fastMode || aProcessingMode == TDConfig.PROCESSING_MODE_RENDER) {
						aGR2.setRenderingHint(RenderingHints.KEY_ANTIALIASING,RenderingHints.VALUE_ANTIALIAS_ON);
						aGR2.setRenderingHint(RenderingHints.KEY_RENDERING,RenderingHints.VALUE_RENDER_QUALITY);
					}
					if(aKey.script == null || aKey.bsh == null) {
						aGR2.drawImage(aBIR,0,0,null);
					}
					else {
						try {
							aKey.bsh.set("sourceIR", aBIR);
							aKey.bsh.set("sourceW", aWidth);
							aKey.bsh.set("sourceH", aHeight);
							aKey.bsh.set("targetIR", aProcessedBIR);
							aKey.bsh.set("targetW", aWidthOut);
							aKey.bsh.set("targetH", aHeightOut);
							aKey.bsh.set("targetGR", aGR2);
							aKey.bsh.set("timeMS", aTime-((Clip)targetMedia).getStartTimeMS());
							aKey.bsh.eval("processR()");
						}
						catch(Throwable t) {
							t.printStackTrace(System.err);
						}
					}
					aGR2.dispose();
					aMedia.setWorkRight(aProcessedBIR);
				}
			});
			aThR.start();
			aThL.join();
			aThR.join();
		}
		catch(Throwable t) {
			t.printStackTrace(System.err);
		}
	}

}
