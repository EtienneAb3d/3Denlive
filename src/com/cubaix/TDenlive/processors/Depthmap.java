package com.cubaix.TDenlive.processors;

import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferFloat;
import java.awt.image.DataBufferInt;
import java.util.Vector;

import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.graphics.Rectangle;

import com.cubaix.TDenlive.TDConfig;
import com.cubaix.TDenlive.TDenlive;
import com.cubaix.TDenlive.depth.ComputedDepthmap;
import com.cubaix.TDenlive.medias.Clip;
import com.cubaix.TDenlive.medias.Media;
import com.cubaix.TDenlive.processors.Chromakey.Key;
import com.cubaix.TDenlive.utils.ImageUtils;
import com.cubaix.TDenlive.xml.XmlObject;
import com.cubaix.TDenlive.xml.XmlTag;

public class Depthmap extends Processor {
	class Key{
		double d = 0;
		double x = 0;
		double p = 0;
		//Mix of the depth map on the source before the conversion (0: source, 1: depth map)
		double m = 0;
		long time = 0;
		boolean blackIsFront = true;
	}
	Vector<Key> keys = new Vector<Key>();
	//Depth maps computed for the clip (see depth.DepthConverter), null if none
	public ComputedDepthmap computed = null;
	//Show the depth map instead of the result on the monitor (not when rendering, not saved)
	public boolean showDepth = false;
	
	public Depthmap(TDenlive aTDe,Media aTargetMedia) {
		super(aTDe,aTargetMedia);
		//Always the first processor after the TimeController (see Clip.placeDepthProcessor())
		isUnmovable = true;
		isExpended = true;
		keys.add(new Key());
	}

	@Override
	public String getClassName() {return "Depthmap";}
	
	@Override
	public int openProject(Vector<XmlObject> aOs, int o) throws Exception {
		keys.clear();
		boolean[] aHasComputed = {false};
		for(;o < aOs.size();o++) {
			XmlObject aO = aOs.elementAt(o);
			if(aO instanceof XmlTag) {
				XmlTag aT = (XmlTag)aO;
				if("/Depthmap".equalsIgnoreCase(aT.tagName)) {
					if(aHasComputed[0]) {
						attachComputed();
					}
					return o;
				}
				if(ComputedDepthmap.read(aOs, o, aT.tagName, aHasComputed)) {
					continue;
				}
				if("d".equalsIgnoreCase(aT.tagName)) {
					o++;
					keys.add(new Key());
					keys.lastElement().d = Double.parseDouble(aOs.elementAt(o).text);
				}
				if("x".equalsIgnoreCase(aT.tagName)) {
					o++;
					keys.lastElement().x = Double.parseDouble(aOs.elementAt(o).text);
				}
				if("p".equalsIgnoreCase(aT.tagName)) {
					o++;
					keys.lastElement().p = Double.parseDouble(aOs.elementAt(o).text);
				}
				if("m".equalsIgnoreCase(aT.tagName)) {
					o++;
					keys.lastElement().m = Double.parseDouble(aOs.elementAt(o).text);
				}
				if("time".equalsIgnoreCase(aT.tagName)) {
					o++;
					keys.lastElement().time = Long.parseLong(aOs.elementAt(o).text);
				}
				if("blackIsFront".equalsIgnoreCase(aT.tagName)) {
					o++;
					keys.lastElement().blackIsFront = Boolean.parseBoolean(aOs.elementAt(o).text);
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
		aSB.append("						<Depthmap>\n");
		for(Key aK : keys) {
			aSB.append("							<d>" + aK.d + "</d>\n");
			aSB.append("							<x>" + aK.x + "</x>\n");
			aSB.append("							<p>" + aK.p + "</p>\n");
			aSB.append("							<m>" + aK.m + "</m>\n");
			aSB.append("							<time>" + aK.time + "</time>\n");
			aSB.append("							<blackIsFront>" + aK.blackIsFront + "</blackIsFront>\n");
		}
		if(computed != null) {
			computed.save(aSB, "							");
		}
		aSB.append("						</Depthmap>\n");
	}

	/**
	 * Depth maps computed for the clip (replace the previous ones)
	 */
	public void setComputed(ComputedDepthmap aCD) {
		if(computed != null) {
			computed.detach();
		}
		computed = aCD;
	}

	@Override
	public void removed() {
		setComputed(null);
	}

	/**
	 * The depth maps computed before for the media of the clip (files next to it), if still there
	 */
	public void attachComputed() {
		try {
			setComputed(ComputedDepthmap.attach(tde, (Clip)targetMedia));
		}
		catch(Throwable t) {
			t.printStackTrace(System.err);
		}
	}

	@Override
	void setDescr() {
		name = tde.gui.lngs.get("Processor.Depthmap");
		icon = "arrowParallax.gif";
	}

	public boolean isZero() {
		return false;
//		for(Key aK : keys) {
//			if(!(aK.x == 0 && aK.d == 0)) {
//				return false;
//			}
//		}
//		return true; 
	}
	
	public int getNbKeys() {
		return keys.size();
	}
	
	public int addKeyGT(long aTime){
		if(aTime < ((Clip)targetMedia).getStartTimeMS() || aTime <= ((Clip)targetMedia).getKeysOriginMS() || aTime > ((Clip)targetMedia).getStartTimeMS() + ((Clip)targetMedia).getDurationMS()) {
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
		long aT = aTime-((Clip)targetMedia).getKeysOriginMS();
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
		long aT = aTime-((Clip)targetMedia).getKeysOriginMS();
		for(int k = 0;k < keys.size();k++) {
			Key aK = keys.elementAt(k);
			if(aK.time > aT) {
				return k;
			}
		}
		//No key after
		return -1;
	}
	
	public double getX(int aKey) {
		return key(aKey).x;
	}

	public void setX(int aKey,double x) {
		warnNextAlignments();
		key(aKey).x = x;
	}

	public double getP(int aKey) {
		return key(aKey).p;
	}

	public void setP(int aKey,double p) {
		warnNextAlignments();
		key(aKey).p = p;
	}

	public double getD(int aKey) {
		return key(aKey).d;
	}

	public void setD(int aKey,double y) {
		warnNextAlignments();
		key(aKey).d = y;
	}

	public double getM(int aKey) {
		return key(aKey).m;
	}

	public void setM(int aKey,double m) {
		key(aKey).m = m;
	}

	public boolean getBlackIsFront(int aKey) {
		return key(aKey).blackIsFront;
	}

	public void setBlackIsFront(int aKey,boolean aBlackIsFront) {
		warnNextAlignments();
		key(aKey).blackIsFront = aBlackIsFront;
	}
	
	public long getTime(int aKey) {
		return key(aKey).time;
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

	/** Values interpolated at the time cursor, for the display (index DISPLAY_KEY, UI thread only) */
	Key displayKey = null;

	@Override
	public boolean setDisplayKeyGT(long aTime) {
		try {
			displayKey = getInterKeyGT(aTime);
		}
		catch(Throwable t) {
			displayKey = null;
		}
		return displayKey != null;
	}

	/** The key aKey, or the display key */
	Key key(int aKey) {
		return aKey == DISPLAY_KEY && displayKey != null ? displayKey : keys.elementAt(aKey);
	}

	Key getInterKeyGT(long aTimePos){
		long aTime = aTimePos-((Clip)targetMedia).getKeysOriginMS();
		for(int k = keys.size()-1;k >= 0;k--) {
			Key aKey = keys.elementAt(k);
			if(aKey.time <= aTime) {
				if(k == keys.size()-1) {
					Key aInter = new Key();
					aInter.d = aKey.d;
					aInter.x = aKey.x;
					aInter.p = aKey.p;
					aInter.m = aKey.m;
					aInter.time = aTime;
					aInter.blackIsFront = aKey.blackIsFront;
					return aInter;
				}
				Key aNext = keys.elementAt(k+1);
				Key aInter = new Key();
				double aFact = (aTime-aKey.time)/(double)(aNext.time-aKey.time);
				aInter.d = aKey.d*(1.0-aFact)+aNext.d*aFact;
				aInter.x = aKey.x*(1.0-aFact)+aNext.x*aFact;
				aInter.p = aKey.p*(1.0-aFact)+aNext.p*aFact;
				aInter.m = aKey.m*(1.0-aFact)+aNext.m*aFact;
				aInter.time = aTime;
				aInter.blackIsFront = aKey.blackIsFront;
				return aInter;
			}
		}
		//??
		Key aKey = keys.firstElement();
		Key aInter = new Key();
		aInter.d = aKey.d;
		aInter.x = aKey.x;
		aInter.p = aKey.p;
		aInter.m = aKey.m;
		aInter.time = aTime;
		aInter.blackIsFront = aKey.blackIsFront;
		return aInter;
	}
	
	@Override
	public void process2Awt(Media aMedia,int aProcessingMode,long aTime) {
		if(isZero()) {
			return;
		}
		try {
			BufferedImage aBISrc = aMedia.getWorkLeft(aProcessingMode,aTime);
			//Depth map: the computed one (of the left view), else the right view of the media (2D + depth map)
			ComputedDepthmap aCD = computed;
			BufferedImage aBIR = ImageUtils.fitTo(aCD != null && aCD.clip != null
					? aCD.clip.getWorkLeft(aProcessingMode,aTime)
					: aMedia.getWorkRight(aProcessingMode,aTime), aBISrc);
			if(showDepth && aProcessingMode != TDConfig.PROCESSING_MODE_RENDER) {
				aMedia.setWorkLeft(aBIR);
				aMedia.setWorkRight(aBIR);
				return;
			}
			//Depth map mixed on the source, then converted
			BufferedImage aBIL = mixDepth(aBISrc, aBIR, getInterKeyGT(aTime).m);
			Thread aThL = new Thread(new Runnable() {
				@Override
				public void run() {
					BufferedImage aProcessedBIL = 
							tde.config.processingHdr[aProcessingMode]?
							processAwtHdr(aBIL,aBIR,aProcessingMode,aTime,true):
								processAwtImage(aBIL,aBIR,aProcessingMode,aTime,true);
					aMedia.setWorkLeft(aProcessedBIL);
				}
			});
			aThL.start();
			Thread aThR = new Thread(new Runnable() {
				@Override
				public void run() {
					BufferedImage aProcessedBIR = 
							tde.config.processingHdr[aProcessingMode]?
									processAwtHdr(aBIL,aBIR,aProcessingMode,aTime,false):
										processAwtImage(aBIL,aBIR,aProcessingMode,aTime,false);
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
	
	/**
	 * The source image mixed with its depth map before the stereo conversion: aSrc*(1-aM) + aDM*aM
	 * on the colors (the alpha of the source is kept). aM <= 0: aSrc itself
	 */
	static BufferedImage mixDepth(BufferedImage aSrc,BufferedImage aDM,double aM) {
		if(aSrc == null || aDM == null || aM <= 0) {
			return aSrc;
		}
		aM = Math.min(1.0, aM);
		int aW = aSrc.getWidth();
		int aH = aSrc.getHeight();
		boolean aHdr = aSrc.getRaster().getDataBuffer() instanceof DataBufferFloat;
		if((aDM.getRaster().getDataBuffer() instanceof DataBufferFloat) != aHdr
				|| aDM.getWidth() != aW || aDM.getHeight() != aH) {
			//Same size and type as the source
			BufferedImage aConv = ImageUtils.createImage(aW, aH, aHdr);
			Graphics2D aG = aConv.createGraphics();
			aG.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
			aG.drawImage(aDM, 0, 0, aW, aH, null);
			aG.dispose();
			aDM = aConv;
		}
		BufferedImage aOut = ImageUtils.createImage(aW, aH, aHdr);
		double aS = 1.0-aM;
		if(aHdr) {
			float[] aSB = ((DataBufferFloat)aSrc.getRaster().getDataBuffer()).getData();
			float[] aDB = ((DataBufferFloat)aDM.getRaster().getDataBuffer()).getData();
			float[] aOB = ((DataBufferFloat)aOut.getRaster().getDataBuffer()).getData();
			for(int p = 0;p+3 < aOB.length;p += 4) {
				aOB[p] = (float)(aSB[p]*aS+aDB[p]*aM);
				aOB[p+1] = (float)(aSB[p+1]*aS+aDB[p+1]*aM);
				aOB[p+2] = (float)(aSB[p+2]*aS+aDB[p+2]*aM);
				aOB[p+3] = aSB[p+3];
			}
		}
		else {
			int[] aSB = ((DataBufferInt)aSrc.getRaster().getDataBuffer()).getData();
			int[] aDB = ((DataBufferInt)aDM.getRaster().getDataBuffer()).getData();
			int[] aOB = ((DataBufferInt)aOut.getRaster().getDataBuffer()).getData();
			for(int p = 0;p < aOB.length;p++) {
				int aSP = aSB[p];
				int aDP = aDB[p];
				int aR = (int)(((aSP>>16)&0xFF)*aS+((aDP>>16)&0xFF)*aM);
				int aG = (int)(((aSP>>8)&0xFF)*aS+((aDP>>8)&0xFF)*aM);
				int aB = (int)((aSP&0xFF)*aS+(aDP&0xFF)*aM);
				aOB[p] = (aSP&0xFF000000)|(aR<<16)|(aG<<8)|aB;
			}
		}
		return aOut;
	}

	BufferedImage processAwtImage(BufferedImage aBI,BufferedImage aDM,int aProcessingMode,long aTime,boolean aIsL) {
		int aWidth = aBI.getWidth();
		int aHeight = aBI.getHeight();
		BufferedImage aFBI = ImageUtils.createImage(aWidth, aHeight);

		Key aKey = getInterKeyGT(aTime);

		int[] aDB = ((DataBufferInt)aBI.getRaster().getDataBuffer()).getData();
		int[] aDMDB = ((DataBufferInt)aDM.getRaster().getDataBuffer()).getData();
		int[] aFDB = ((DataBufferInt)aFBI.getRaster().getDataBuffer()).getData();
		
		int aHoleThX = 10+(int)(3*Math.abs(aKey.x));//(int)Math.sqrt(aWidth/1280.0);
		double aHoleThZ = 0.02/Math.sqrt(aWidth/1280.0);//Remark: 0,007843=2*1/255 = two depthmap layers

		//Min/Max
		double aMin = 1.0;
		double aMax = 0.0;
		for(int p = 0;p < aDMDB.length;p++) {
			int aDMPix = aDMDB[p];
			double aDMAlpha = (aDMPix>>24) & 0xFF;//Alpha
			double aDMR = (int)((aDMPix>>16) & 0xFF);//Red
			double aDMG = (int)((aDMPix>>8) & 0xFF);//Green
			double aDMB = (int)(aDMPix & 0xFF);//Blue

			if(aDMAlpha < 255.0) {
				//Nothing here, not a fully opaque depthmap
				continue;
			}

			double aD = (aDMR+aDMG+aDMB)/(3.0*255);
			if(aD < aMin) {
				aMin = aD;
			}
			if(aD > aMax) {
				aMax = aD;
			}
		}
		for(int y = 0;y<aHeight;y++) {
			//Reverse
			double[] aDLineZ = new double[aWidth];
			double[] aDLineA = new double[aWidth];
			double[] aLineZSrc = new double[aWidth];
			int aLastX = -1;
			int aLastAlpha = 0;
			int aLastR = 0;
			int aLastG = 0;
			int aLastB = 0;
			for(int x = 0;x<aWidth;x++) {
				int aDMPix = aDMDB[y*aWidth+x];
				double aDMA = (aDMPix>>24) & 0xFF;//Alpha
				double aDMR = (int)((aDMPix>>16) & 0xFF);//Red
				double aDMG = (int)((aDMPix>>8) & 0xFF);//Green
				double aDMB = (int)(aDMPix & 0xFF);//Blue

				if(aDMA < 255.0) {
					//Nothing here, not a fully opaque depthmap
					continue;
				}

				double aZ = aLineZSrc[x] = (aKey.blackIsFront?1:-1)*((((aDMR+aDMG+aDMB)/(3.0*255))-aMin)/(aMax-aMin)-0.5);
				double aDx = -(int)((aIsL?-1+aKey.x:+1+aKey.x)*
						(aKey.d*aZ)
						*aWidth
						/4.0)
						;
				aDx += aKey.p*aZ*(aWidth/2.0 - (x+aDx));
				int aXd = (int)(x+aDx);

				if(aXd < 0 || aXd >= aWidth) {
					//Out
					continue;
				}
				
				double aDMTZ = aDLineZ[aXd];
				double aDMTA = aDLineA[aXd];//Alpha

				if(aDMTA <= 0.0) {
					//Nothing already here, set it
					aDLineZ[aXd] = aZ;
					aDLineA[aXd] = aDMA;
					aFDB[y*aWidth+aXd] = aDB[y*aWidth+x];
				}
				else {
					//Test Z position
					if(aZ < aDMTZ) {
						//Overlap
						aDLineZ[aXd] = aZ;
						aDLineA[aXd] = aDMA;
						aFDB[y*aWidth+aXd] = aDB[y*aWidth+x];
					}
				}

				int aPix = aFDB[y*aWidth+aXd];
				int aAlpha = (aPix>>24) & 0xFF;//Alpha
				int aR = (int)((aPix>>16) & 0xFF);//Red
				int aG = (int)((aPix>>8) & 0xFF);//Green
				int aB = (int)(aPix & 0xFF);//Blue

				//Interpolate small hole
				if(aLastX >= 0 && Math.abs(aLastX-aXd) >= 2 && 
						Math.abs(aLastX-aXd) <= aHoleThX
						&& Math.abs(aDLineZ[aLastX]-aDLineZ[aXd]) <= aHoleThZ) {
					int aBegX = aLastX<aXd?aLastX:aXd;
					int aEndX = aLastX<aXd?aXd:aLastX;
					double aDMBZ = aDLineZ[aBegX];
					double aDMBA = aDLineA[aBegX];//Alpha
					double aDMBR = aLastX<aXd?aLastR:aR;
					double aDMBG = aLastX<aXd?aLastG:aG;
					double aDMBB = aLastX<aXd?aLastB:aB;

					double aDMEZ = aDLineZ[aEndX];
					double aDMEA = aDLineA[aEndX];//Alpha
					double aDMER = aLastX<aXd?aR:aLastR;
					double aDMEG = aLastX<aXd?aG:aLastG;
					double aDMEB = aLastX<aXd?aB:aLastB;

					for(int ix = aBegX+1;ix <= aEndX - 1;ix++) {
						double aDMXZ = aDLineZ[ix];//aDMDB[y*aWidth+x];
						double aDMXA = aDLineA[ix];//Alpha

						double aDMIA = (aDMBA*(aEndX - ix)+aDMEA*(ix - aBegX))/((double)(aEndX - aBegX));
						double aDMIZ = (aDMBZ*(aEndX - ix)+aDMEZ*(ix - aBegX))/((double)(aEndX - aBegX));

						if(aDMXA <= 0 || aDMIZ < aDMXZ) {
							aDLineZ[ix] = aDMIZ;
							aDLineA[ix] = aDMIA;
							
							int aAlphaN = (int)((aDMBA*(aEndX - ix)+aDMEA*(ix - aBegX))/((double)(aEndX - aBegX)));
							int aRN = (int)((aDMBR*(aEndX - ix)+aDMER*(ix - aBegX))/((double)(aEndX - aBegX)));
							int aGN = (int)((aDMBG*(aEndX - ix)+aDMEG*(ix - aBegX))/((double)(aEndX - aBegX)));
							int aBN = (int)((aDMBB*(aEndX - ix)+aDMEB*(ix - aBegX))/((double)(aEndX - aBegX)));
							aFDB[y*aWidth+ix] = (aAlphaN<<24)|(aRN<<16)|(aGN<<8)|aBN;
						}
					}
				}

				aLastX = aXd;
				aLastAlpha = aAlpha;
				aLastR = aR;
				aLastG = aG;
				aLastB = aB;
			}
			//Interpolate large hole
//			aLastX = -1;
//			for(int x = 0;x<aWidth;x++) {
//				double aDMZ = aDLineZ[x];//aDMDB[y*aWidth+x];
//				double aDMA = aDLineA[x];//Alpha
//
//				if(aDMA > 0.0) {
//					//Something here
//					if(aLastX >= 0 && aLastX < x - 1) {
//						//Interpolate
//						for(int ix = aLastX+1;ix < x;ix++) {
//							double aDMLZ = aDLineZ[aLastX];//aDMDB[y*aWidth+x];
//							double aDMLA = aDLineA[aLastX];//Alpha
//
//							double aDMIA = (aDMLA*(x - ix)+aDMA*(ix - aLastX))/((double)(x - aLastX));
//							double aDMIZ = (aDMLZ*(x - ix)+aDMZ*(ix - aLastX))/((double)(x - aLastX));
//							aDLineZ[ix] = aDMIZ;
//							aDLineA[ix] = aDMIA;
//						}
//					}
//					//Store
//					aLastX = x;
//					continue;
//				}
//			}
//
//			//Projection
//			for(int x = 0;x<aWidth;x++) {
//				aLineZSrc[x] = 1.0;
//			}
//			for(int x = 0;x<aWidth;x++) {
//				double aZ = aDLineZ[x];//aDMDB[y*aWidth+x];
//				double aDMA = aDLineA[x];//Alpha
//
//				if(aDMA <= 0.0) {
//					//Nothing here
//					continue;
//				}
//
//				double aDx = (int)((aIsL?-1+aKey.x:+1+aKey.x)*
//						(aKey.d*aZ)
//						*aWidth
//						/4.0)
//						;
//				aDx -= aKey.p*aZ*(aWidth/2.0 - x);
//				int aXd = (int)(x+aDx);
//
//				if(aXd < 0 || aXd >= aWidth) {
//					//Out
//					continue;
//				}
//				
//				if(aZ > aLineZSrc[aXd]) {
//					//Don't overlap
//					continue;
//				}
//				aLineZSrc[aXd] = aZ;
//			}
			//Draw
//			for(int x = 0;x<aWidth;x++) {
//				double aZ = aDLineZ[x];//aDMDB[y*aWidth+x];
//				double aDMA = aDLineA[x];//Alpha
//
//				if(aDMA <= 0.0) {
//					//Nothing here
//					aFDB[y*aWidth+x] = 0;
//					continue;
//				}
//
//				double aDx = (int)((aIsL?-1+aKey.x:+1+aKey.x)*
//						(aKey.d*aZ)
//						*aWidth
//						/4.0)
//						;
//				aDx -= aKey.p*aZ*(aWidth/2.0 - x);
//				int aXd = (int)(x+aDx);
//
//				if(aXd < 0 || aXd >= aWidth) {
//					//Out
//					aFDB[y*aWidth+x] = 0;
//					continue;
//				}
//				
//				if(aZ > aLineZSrc[aXd]) {
//					//Don't overlap
//					continue;
//				}
//				aFDB[y*aWidth+x] = aDB[y*aWidth+aXd];
//			}
			//Interpolate
			aLastX = -1;
			for(int x = 0;x<aWidth;x++) {
				int aPix = aFDB[y*aWidth+x];
				int aAlpha = (aPix>>24) & 0xFF;//Alpha
				int aR = (int)((aPix>>16) & 0xFF);//Red
				int aG = (int)((aPix>>8) & 0xFF);//Green
				int aB = (int)(aPix & 0xFF);//Blue

				if(aAlpha > 0.0) {
					//Something here
					if(aLastX >= 0 && aLastX < x - 1) {
						//Interpolate
						for(int ix = aLastX+1;ix < x;ix++) {
							int aAlphaN = (int)((aLastAlpha*(x - ix)+aAlpha*(ix - aLastX))/((double)(x - aLastX)));
							int aRN = (int)((aLastR*(x - ix)+aR*(ix - aLastX))/((double)(x - aLastX)));
							int aGN = (int)((aLastG*(x - ix)+aG*(ix - aLastX))/((double)(x - aLastX)));
							int aBN = (int)((aLastB*(x - ix)+aB*(ix - aLastX))/((double)(x - aLastX)));
							aFDB[y*aWidth+ix] = (aAlphaN<<24)|(aRN<<16)|(aGN<<8)|aBN;
						}
					}
					//Store
					aLastX = x;
					aLastAlpha = aAlpha;
					aLastR = aR;
					aLastG = aG;
					aLastB = aB;
					continue;
				}
			}
		}

		return aFBI;
	}

	BufferedImage processAwtHdr(BufferedImage aBI,BufferedImage aDM,int aProcessingMode,long aTime,boolean aIsL) {
		int aWidth = aBI.getWidth();
		int aHeight = aBI.getHeight();
		BufferedImage aFBI = ImageUtils.createHdr(aWidth, aHeight);

		Key aKey = getInterKeyGT(aTime);

		float[] aDB = ((DataBufferFloat)aBI.getRaster().getDataBuffer()).getData();
		float[] aDMDB = ((DataBufferFloat)aDM.getRaster().getDataBuffer()).getData();
		float[] aFDB = ((DataBufferFloat)aFBI.getRaster().getDataBuffer()).getData();

		int aHoleThX = 10+(int)(3*Math.abs(aKey.x));//(int)Math.sqrt(aWidth/1280.0);
		double aHoleThZ = 0.02/Math.sqrt(aWidth/1280.0);//Remark: 0,007843=2*1/255 = two depthmap layers

		//Min/Max
		double aMin = 1.0;
		double aMax = 0.0;
		for(int x = 0;x<aWidth;x++) {
			for(int y = 0;y<aHeight;y++) {
				double aDMR = aDMDB[y*aWidth*4+x*4];//Red
				double aDMG = aDMDB[y*aWidth*4+x*4+1];//Green
				double aDMB = aDMDB[y*aWidth*4+x*4+2];//Blue
				double aDMA = aDMDB[y*aWidth*4+x*4+3];//Alpha

				if(aDMA < 1.0) {
					//Nothing here, not a fully opaque depthmap
					continue;
				}

				double aD = (aDMR+aDMG+aDMB)/3.0;
				if(aD < aMin) {
					aMin = aD;
				}
				if(aD > aMax) {
					aMax = aD;
				}
			}
		}
		for(int y = 0;y<aHeight;y++) {
			//Reverse
			double[] aDLineZ = new double[aWidth];
			double[] aDLineA = new double[aWidth];
			int aLastX = -1;
			double aLastAlpha = 0;
			double aLastR = 0;
			double aLastG = 0;
			double aLastB = 0;
			for(int x = 0;x<aWidth;x++) {
				double aDMR = aDMDB[y*aWidth*4+x*4];//Red
				double aDMG = aDMDB[y*aWidth*4+x*4+1];//Green
				double aDMB = aDMDB[y*aWidth*4+x*4+2];//Blue
				double aDMA = aDMDB[y*aWidth*4+x*4+3];//Alpha

				if(aDMA < 1.0) {
					//Nothing here, not a fully opaque depthmap
					continue;
				}

				double aZ = (aKey.blackIsFront?1:-1)*((((aDMR+aDMG+aDMB)/3.0)-aMin)/(aMax-aMin)-0.5);
				int aDx = -(int)((aIsL?-1+aKey.x:+1+aKey.x)*
						(aKey.d*aZ)
						*aWidth
						/4.0)
						;
				aDx += aKey.p*aZ*(aWidth/2.0 - (x+aDx));
				int aXd = (int)(x+aDx);

				if(aXd < 0 || aXd >= aWidth) {
					//Out
					continue;
				}

				double aDMTZ = aDLineZ[aXd];
				double aDMTA = aDLineA[aXd];//Alpha

				if(aDMTA <= 0.0) {
					//Nothing already here, set it
					aDLineZ[aXd] = aZ;
					aDLineA[aXd] = aDMA;
					aFDB[y*aWidth*4+aXd*4] = aDB[y*aWidth*4+x*4];
					aFDB[y*aWidth*4+aXd*4+1] = aDB[y*aWidth*4+x*4+1];
					aFDB[y*aWidth*4+aXd*4+2] = aDB[y*aWidth*4+x*4+2];
					aFDB[y*aWidth*4+aXd*4+3] = aDB[y*aWidth*4+x*4+3];
				}
				else {
					//Test Z position
					if(aZ < aDMTZ) {
						//Overlap
						aDLineZ[aXd] = aZ;
						aDLineA[aXd] = aDMA;
						aFDB[y*aWidth*4+aXd*4] = aDB[y*aWidth*4+x*4];
						aFDB[y*aWidth*4+aXd*4+1] = aDB[y*aWidth*4+x*4+1];
						aFDB[y*aWidth*4+aXd*4+2] = aDB[y*aWidth*4+x*4+2];
						aFDB[y*aWidth*4+aXd*4+3] = aDB[y*aWidth*4+x*4+3];
					}
				}

				double aR = aFDB[y*aWidth*4+aXd*4];
				double aG = aFDB[y*aWidth*4+aXd*4+1];
				double aB = aFDB[y*aWidth*4+aXd*4+2];
				double aAlpha = aFDB[y*aWidth*4+aXd*4+3];
				
				//Interpolate small hole
				if(aLastX >= 0 && Math.abs(aLastX-aXd) >= 2 && 
						Math.abs(aLastX-aXd) <= aHoleThX
						&& Math.abs(aDLineZ[aLastX]-aDLineZ[aXd]) <= aHoleThZ) {
					int aBegX = aLastX<aXd?aLastX:aXd;
					int aEndX = aLastX<aXd?aXd:aLastX;
					double aDMBZ = aDLineZ[aBegX];
					double aDMBA = aDLineA[aBegX];//Alpha
					double aDMBR = aLastX<aXd?aLastR:aR;
					double aDMBG = aLastX<aXd?aLastG:aG;
					double aDMBB = aLastX<aXd?aLastB:aB;

					double aDMEZ = aDLineZ[aEndX];
					double aDMEA = aDLineA[aEndX];//Alpha
					double aDMER = aLastX<aXd?aR:aLastR;
					double aDMEG = aLastX<aXd?aG:aLastG;
					double aDMEB = aLastX<aXd?aB:aLastB;

					for(int ix = aBegX+1;ix <= aEndX - 1;ix++) {
						double aDMXZ = aDLineZ[ix];//aDMDB[y*aWidth+x];
						double aDMXA = aDLineA[ix];//Alpha

						double aDMIA = (aDMBA*(aEndX - ix)+aDMEA*(ix - aBegX))/((double)(aEndX - aBegX));
						double aDMIZ = (aDMBZ*(aEndX - ix)+aDMEZ*(ix - aBegX))/((double)(aEndX - aBegX));

						if(aDMXA <= 0 || aDMIZ < aDMXZ) {
							aDLineZ[ix] = aDMIZ;
							aDLineA[ix] = aDMIA;
							
							double aAlphaN = ((aDMBA*(aEndX - ix)+aDMEA*(ix - aBegX))/((double)(aEndX - aBegX)));
							double aRN = ((aDMBR*(aEndX - ix)+aDMER*(ix - aBegX))/((double)(aEndX - aBegX)));
							double aGN = ((aDMBG*(aEndX - ix)+aDMEG*(ix - aBegX))/((double)(aEndX - aBegX)));
							double aBN = ((aDMBB*(aEndX - ix)+aDMEB*(ix - aBegX))/((double)(aEndX - aBegX)));
							aFDB[y*aWidth*4+ix*4] = (float)aRN;
							aFDB[y*aWidth*4+ix*4+1] = (float)aGN;
							aFDB[y*aWidth*4+ix*4+2] = (float)aBN;
							aFDB[y*aWidth*4+ix*4+3] = (float)aAlphaN;
						}
					}
				}

				aLastX = aXd;
				aLastAlpha = aAlpha;
				aLastR = aR;
				aLastG = aG;
				aLastB = aB;
			}
			//Interpolate large hole
//			aLastX = -1;
//			for(int x = 0;x<aWidth;x++) {
//				double aDMZ = aDLineZ[x];//aDMDB[y*aWidth+x];
//				double aDMA = aDLineA[x];//Alpha
//
//				if(aDMA > 0.0) {
//					//Something here
//					if(aLastX >= 0 && aLastX < x - 1) {
//						//Interpolate
//						for(int ix = aLastX+1;ix < x;ix++) {
//							double aDMLZ = aDLineZ[aLastX];//aDMDB[y*aWidth+x];
//							double aDMLA = aDLineA[aLastX];//Alpha
//
//							double aDMIA = (aDMLA*(x - ix)+aDMA*(ix - aLastX))/((double)(x - aLastX));
//							double aDMIZ = (aDMLZ*(x - ix)+aDMZ*(ix - aLastX))/((double)(x - aLastX));
//							aDLineZ[ix] = aDMIZ;
//							aDLineA[ix] = aDMIA;
//						}
//					}
//					//Store
//					aLastX = x;
//					continue;
//				}
//			}
//
//			//Projection
//			double[] aLineZSrc = new double[aWidth];
//			for(int x = 0;x<aWidth;x++) {
//				aLineZSrc[x] = 1.0;
//			}
//			for(int x = 0;x<aWidth;x++) {
//				double aZ = aDLineZ[x];//aDMDB[y*aWidth+x];
//				double aDMA = aDLineA[x];//Alpha
//
//				if(aDMA <= 0.0) {
//					//Nothing here
//					continue;
//				}
//
//				int aDx = (int)((aIsL?-1+aKey.x:+1+aKey.x)*
//						(aKey.d*aZ)
//						*aWidth
//						/4.0)
//						;
//				aDx -= aKey.p*aZ*(aWidth/2.0 - x);
//				int aXd = (int)(x+aDx);
//
//				if(aXd < 0 || aXd >= aWidth) {
//					//Out
//					continue;
//				}
//
//				if(aZ > aLineZSrc[aXd]) {
//					//Don't overlap
//					continue;
//				}
//				aLineZSrc[aXd] = aZ;
//			}
//			//Draw
//			for(int x = 0;x<aWidth;x++) {
//				double aZ = aDLineZ[x];//aDMDB[y*aWidth+x];
//				double aDMA = aDLineA[x];//Alpha
//
//				if(aDMA <= 0.0) {
//					//Nothing here
//					aFDB[y*aWidth*4+x*4] = 0;
//					aFDB[y*aWidth*4+x*4+1] = 0;
//					aFDB[y*aWidth*4+x*4+2] = 0;
//					aFDB[y*aWidth*4+x*4+3] = 0;
//					continue;
//				}
//
//				int aDx = (int)((aIsL?-1+aKey.x:+1+aKey.x)*
//						(aKey.d*aZ)
//						*aWidth
//						/4.0)
//						;
//				aDx -= aKey.p*aZ*(aWidth/2.0 - x);
//				int aXd = (int)(x+aDx);
//
//				if(aXd < 0 || aXd >= aWidth) {
//					//Out
//					aFDB[y*aWidth*4+x*4] = 0;
//					aFDB[y*aWidth*4+x*4+1] = 0;
//					aFDB[y*aWidth*4+x*4+2] = 0;
//					aFDB[y*aWidth*4+x*4+3] = 0;
//					continue;
//				}
//
//				if(aZ > aLineZSrc[aXd]) {
//					//Don't overlap
//					continue;
//				}
//				aFDB[y*aWidth*4+x*4] = aDB[y*aWidth*4+aXd*4];
//				aFDB[y*aWidth*4+x*4+1] = aDB[y*aWidth*4+aXd*4+1];
//				aFDB[y*aWidth*4+x*4+2] = aDB[y*aWidth*4+aXd*4+2];
//				aFDB[y*aWidth*4+x*4+3] = aDB[y*aWidth*4+aXd*4+3];
//			}
			//Interpolate
			aLastX = -1;
			for(int x = 0;x<aWidth;x++) {
				double aAlpha = aFDB[y*aWidth*4+x*4+3];//Alpha
				double aR = aFDB[y*aWidth*4+x*4];//Red
				double aG = aFDB[y*aWidth*4+x*4+1];//Green
				double aB = aFDB[y*aWidth*4+x*4+2];//Blue

				if(aAlpha > 0.0) {
					//Something here
					if(aLastX >= 0 && aLastX < x - 1) {
						//Interpolate
						for(int ix = aLastX+1;ix < x;ix++) {
							double aAlphaN = ((aLastAlpha*(x - ix)+aAlpha*(ix - aLastX))/((double)(x - aLastX)));
							double aRN = ((aLastR*(x - ix)+aR*(ix - aLastX))/((double)(x - aLastX)));
							double aGN = ((aLastG*(x - ix)+aG*(ix - aLastX))/((double)(x - aLastX)));
							double aBN = ((aLastB*(x - ix)+aB*(ix - aLastX))/((double)(x - aLastX)));
							aFDB[y*aWidth*4+ix*4] = (float)aRN;
							aFDB[y*aWidth*4+ix*4+1] = (float)aGN;
							aFDB[y*aWidth*4+ix*4+2] = (float)aBN;
							aFDB[y*aWidth*4+ix*4+3] = (float)aAlphaN;
						}
					}
					//Store
					aLastX = x;
					aLastAlpha = aAlpha;
					aLastR = aR;
					aLastG = aG;
					aLastB = aB;
					continue;
				}
			}
		}

		return aFBI;
	}
}
