package com.cubaix.TDenlive.processors;

import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.util.Vector;

import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.graphics.Rectangle;

import com.cubaix.TDenlive.TDConfig;
import com.cubaix.TDenlive.TDenlive;
import com.cubaix.TDenlive.ffmpeg.FfmpegFilter;
import com.cubaix.TDenlive.medias.Clip;
import com.cubaix.TDenlive.medias.Media;
import com.cubaix.TDenlive.utils.ImageUtils;
import com.cubaix.TDenlive.xml.XmlObject;
import com.cubaix.TDenlive.xml.XmlTag;

public class LensCorrection extends Processor {
	class Key{
		double k1 = 0;
		double k2 = 0;
		double cx = 0.5;
		double cy = 0.5;
		double k1LR = 0;
		double k2LR = 0;
		double cxLR = 0;
		double cyLR = 0;
		long time = 0;
	}
	Vector<Key> keys = new Vector<Key>();

	FfmpegFilter leftFilter = null;
	FfmpegFilter rightFilter = null;
	
	public LensCorrection(TDenlive aTDe,Media aTargetMedia) {
		super(aTDe,aTargetMedia);
		isExpended = true;
		keys.add(new Key());
	}

	@Override
	public String getClassName() {return "LensCorrection";}
	
	@Override
	public int openProject(Vector<XmlObject> aOs, int o) throws Exception {
		keys.clear();
		for(;o < aOs.size();o++) {
			XmlObject aO = aOs.elementAt(o);
			if(aO instanceof XmlTag) {
				XmlTag aT = (XmlTag)aO;
				if("/LensCorrection".equalsIgnoreCase(aT.tagName)) {
					return o;
				}
				if("k1".equalsIgnoreCase(aT.tagName)) {
					o++;
					keys.add(new Key());
					keys.lastElement().k1 = Double.parseDouble(aOs.elementAt(o).text);
				}
				if("k2".equalsIgnoreCase(aT.tagName)) {
					o++;
					keys.lastElement().k2 = Double.parseDouble(aOs.elementAt(o).text);
				}
				if("cx".equalsIgnoreCase(aT.tagName)) {
					o++;
					keys.lastElement().cx = Double.parseDouble(aOs.elementAt(o).text);
				}
				if("cy".equalsIgnoreCase(aT.tagName)) {
					o++;
					keys.lastElement().cy = Double.parseDouble(aOs.elementAt(o).text);
				}
				if("k1LR".equalsIgnoreCase(aT.tagName)) {
					o++;
					keys.lastElement().k1LR = Double.parseDouble(aOs.elementAt(o).text);
				}
				if("k2LR".equalsIgnoreCase(aT.tagName)) {
					o++;
					keys.lastElement().k2LR = Double.parseDouble(aOs.elementAt(o).text);
				}
				if("cxLR".equalsIgnoreCase(aT.tagName)) {
					o++;
					keys.lastElement().cxLR = Double.parseDouble(aOs.elementAt(o).text);
				}
				if("cyLR".equalsIgnoreCase(aT.tagName)) {
					o++;
					keys.lastElement().cyLR = Double.parseDouble(aOs.elementAt(o).text);
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
		aSB.append("						<LensCorrection>\n");
		for(Key aK : keys) {
			aSB.append("							<k1>" + aK.k1 + "</k1>\n");
			aSB.append("							<k2>" + aK.k2 + "</k2>\n");
			aSB.append("							<cx>" + aK.cx + "</cx>\n");
			aSB.append("							<cy>" + aK.cy + "</cy>\n");
			aSB.append("							<k1LR>" + aK.k1LR + "</k1LR>\n");
			aSB.append("							<k2LR>" + aK.k2LR + "</k2LR>\n");
			aSB.append("							<cxLR>" + aK.cxLR + "</cxLR>\n");
			aSB.append("							<cyLR>" + aK.cyLR + "</cyLR>\n");
			aSB.append("							<time>" + aK.time + "</time>\n");
		}
		aSB.append("						</LensCorrection>\n");
	}

	@Override
	void setDescr() {
		name = tde.gui.lngs.get("Processor.LensCorrection");
		icon = "ballwire.png";
	}

	public boolean isZero() {
		for(Key aK : keys) {
			if(!(aK.k1 == 0 && aK.k2 == 0 && aK.cx == 0.5 && aK.cy == 0.5
					&& aK.k1LR == 0 && aK.k2LR == 0 && aK.cxLR == 0 && aK.cyLR == 0)) {
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
	
	public double getK1(int aKey) {
		return keys.elementAt(aKey).k1;
	}

	public void setK1(int aKey,double aK1) {
		warnNextAlignments();
		keys.elementAt(aKey).k1 = aK1;
	}

	public double getK2(int aKey) {
		return keys.elementAt(aKey).k2;
	}

	public void setK2(int aKey,double aK2) {
		warnNextAlignments();
		keys.elementAt(aKey).k2 = aK2;
	}

	public double getCX(int aKey) {
		return keys.elementAt(aKey).cx;
	}

	public void setCX(int aKey,double aCX) {
		warnNextAlignments();
		keys.elementAt(aKey).cx = aCX;
	}

	public double getCY(int aKey) {
		return keys.elementAt(aKey).cy;
	}

	public void setCY(int aKey,double aCY) {
		warnNextAlignments();
		keys.elementAt(aKey).cy = aCY;
	}

	public double getK1LR(int aKey) {
		return keys.elementAt(aKey).k1LR;
	}

	public void setK1LR(int aKey,double aK1) {
		warnNextAlignments();
		keys.elementAt(aKey).k1LR = aK1;
	}

	public double getK2LR(int aKey) {
		return keys.elementAt(aKey).k2LR;
	}

	public void setK2LR(int aKey,double aK2) {
		warnNextAlignments();
		keys.elementAt(aKey).k2LR = aK2;
	}

	public double getCXLR(int aKey) {
		return keys.elementAt(aKey).cxLR;
	}

	public void setCXLR(int aKey,double aCXLR) {
		warnNextAlignments();
		keys.elementAt(aKey).cxLR = aCXLR;
	}

	public double getCYLR(int aKey) {
		return keys.elementAt(aKey).cyLR;
	}

	public void setCYLR(int aKey,double aCYLR) {
		warnNextAlignments();
		keys.elementAt(aKey).cyLR = aCYLR;
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

	Key getInterKeyGT(long aTimePos){
		long aTime = aTimePos-((Clip)targetMedia).getStartTimeMS();
		for(int k = keys.size()-1;k >= 0;k--) {
			Key aKey = keys.elementAt(k);
			if(aKey.time <= aTime) {
				if(k == keys.size()-1) {
					Key aInter = new Key();
					aInter.k1 = aKey.k1;
					aInter.k2 = aKey.k2;
					aInter.cx = aKey.cx;
					aInter.cy = aKey.cy;
					aInter.k1LR = aKey.k1LR;
					aInter.k2LR = aKey.k2LR;
					aInter.cxLR = aKey.cxLR;
					aInter.cyLR = aKey.cyLR;
					aInter.time = aTime;
					return aInter;
				}
				Key aNext = keys.elementAt(k+1);
				Key aInter = new Key();
				double aFact = (aTime-aKey.time)/(double)(aNext.time-aKey.time);
				aInter.k1 = aKey.k1*(1.0-aFact)+aNext.k1*aFact;
				aInter.k2 = aKey.k2*(1.0-aFact)+aNext.k2*aFact;
				aInter.cx = aKey.cx*(1.0-aFact)+aNext.cx*aFact;
				aInter.cy = aKey.cy*(1.0-aFact)+aNext.cy*aFact;
				aInter.k1LR = aKey.k1LR*(1.0-aFact)+aNext.k1LR*aFact;
				aInter.k2LR = aKey.k2LR*(1.0-aFact)+aNext.k2LR*aFact;
				aInter.cxLR = aKey.cxLR*(1.0-aFact)+aNext.cxLR*aFact;
				aInter.cyLR = aKey.cyLR*(1.0-aFact)+aNext.cyLR*aFact;
				aInter.time = aTime;
				return aInter;
			}
		}
		//??
		Key aKey = keys.firstElement();
		Key aInter = new Key();
		aInter.k1 = aKey.k1;
		aInter.k2 = aKey.k2;
		aInter.cx = aKey.cx;
		aInter.cy = aKey.cy;
		aInter.k1LR = aKey.k1LR;
		aInter.k2LR = aKey.k2LR;
		aInter.cxLR = aKey.cxLR;
		aInter.cyLR = aKey.cyLR;
		aInter.time = aTime;
		return aInter;
	}
	
	void checkFilterLeft(Key aKey,int aWidth,int aHeight) {
		String aFilterVal = "lenscorrection=k1=" + aKey.k1 + ":k2="+aKey.k2+":cx="+aKey.cx+":cy="+aKey.cy;
		if(leftFilter != null) {
//			if(aFilterVal.equals(leftFilter.filterVal)) {
//				//No change
//				return;
//			}
			leftFilter.stop();
		}
		leftFilter = new FfmpegFilter("-vf",aWidth,aHeight, aFilterVal);
	}
	
	void checkFilterRight(Key aKey,int aWidth,int aHeight) {
		String aFilterVal = "lenscorrection=k1=" + (aKey.k1+aKey.k1LR) + ":k2="+ (aKey.k2+aKey.k2LR)
				+":cx="+(aKey.cx+aKey.cxLR)+":cy="+(aKey.cy+aKey.cyLR);
		if(rightFilter != null) {
//			if(aFilterVal.equals(leftFilter.filterVal)) {
//				//No change
//				return;
//			}
			rightFilter.stop();
		}
		rightFilter = new FfmpegFilter("-vf",aWidth,aHeight, aFilterVal);
	}
	
	@Override
	public void process2Awt(Media aMedia,int aProcessingMode,long aTime) {
		if(isZero()) {
			return;
		}
		Key aKey = getInterKeyGT(aTime);
		try {
			Thread aThL = new Thread(new Runnable() {
				@Override
				public void run() {
					BufferedImage aBIL = aMedia.getWorkLeft(aProcessingMode,aTime);
					int aWidth = aBIL.getWidth();
					int aHeight = aBIL.getHeight();
					checkFilterLeft(aKey,aWidth,aHeight);
					BufferedImage aI = new BufferedImage(aWidth,aHeight, BufferedImage.TYPE_3BYTE_BGR);
					Graphics2D aG = (Graphics2D)aI.getGraphics();
					aG.drawImage(aBIL,0,0,null);
					aG.dispose();
					BufferedImage aL = ImageUtils.createImage(aWidth, aHeight, tde.config.processingHdr[aProcessingMode]);
					aG = (Graphics2D)aL.getGraphics();
					aG.drawImage(leftFilter.filterImg(aI),0,0,null);
					aG.dispose();
					aMedia.setWorkLeft(aL);
				}
			});
			aThL.start();
			Thread aThR = new Thread(new Runnable() {
				@Override
				public void run() {
					BufferedImage aBIR = aMedia.getWorkRight(aProcessingMode,aTime);
					int aWidth = aBIR.getWidth();
					int aHeight = aBIR.getHeight();
					checkFilterRight(aKey,aWidth,aHeight);
					BufferedImage aI = new BufferedImage(aWidth,aHeight, BufferedImage.TYPE_3BYTE_BGR);
					Graphics2D aG = (Graphics2D)aI.getGraphics();
					aG.drawImage(aBIR,0,0,null);
					aG.dispose();
					BufferedImage aR = ImageUtils.createImage(aWidth, aHeight, tde.config.processingHdr[aProcessingMode]);
					aG = (Graphics2D)aR.getGraphics();
					aG.drawImage(rightFilter.filterImg(aI),0,0,null);
					aG.dispose();
					aMedia.setWorkRight(aR);
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
