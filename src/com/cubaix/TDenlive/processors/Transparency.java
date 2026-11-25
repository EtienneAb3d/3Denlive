package com.cubaix.TDenlive.processors;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.awt.image.ConvolveOp;
import java.awt.image.DataBufferFloat;
import java.awt.image.DataBufferInt;
import java.awt.image.Kernel;
import java.io.BufferedWriter;
import java.util.Vector;

import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.graphics.Rectangle;

import com.cubaix.TDenlive.TDConfig;
import com.cubaix.TDenlive.TDenlive;
import com.cubaix.TDenlive.medias.Clip;
import com.cubaix.TDenlive.medias.Media;
import com.cubaix.TDenlive.utils.ImageUtils;
import com.cubaix.TDenlive.utils.Luma;
import com.cubaix.TDenlive.xml.XmlObject;
import com.cubaix.TDenlive.xml.XmlTag;

/**
 * Opacity and transitions by luminance masks (lumas/). A key can also take its mask from a media of the media list
 * (maskClip, e.g. an AI mask made by GUI/AiMaskGUI): a black and white image or video, white = visible, black =
 * transparent, read at the media time of the clip as a paired clip (Clip.addPairedClip(), the orientation of the
 * clip media followed), one view per eye; Opacity multiplies it, Invert swaps black and white, X moves it
 * horizontally in opposite directions for the two eyes (its depth, as for the lumas, without enlarging it) and Bevel
 * softens its edges (blur of up to MASK_FEATHER of the width). M mixes the image with the mask shown in violet
 * (MASK_COLOR, black where the mask is black), opaque: 0 = the masked image, 1 = the violet mask alone (to see it).
 */
public class Transparency extends Processor {
	//Blur of the edges of a mask of the media list at Bevel 1, fraction of the image width
	static final double MASK_FEATHER = 0.05;
	//Color of the mask mixed with the image (M)
	static final int MASK_COLOR = (130<<16)|(30<<8)|200;
	class Key{
		double opacity = 1.0;
		Luma luma = null;
		double bevel = 0.0;
		double x = 0.0;
		boolean invert = false;
		long time = 0;
		//Mask of the media list (paired clip), null: the luma
		Clip maskClip = null;
		//Mix of the mask, in violet, with the image (0..1)
		double m = 0;
	}
	Vector<Key> keys = new Vector<Key>();
	
	public Transparency(TDenlive aTDe,Media aTargetMedia) {
		super(aTDe,aTargetMedia);
		isExpended = true;
		keys.add(new Key());
		keys.lastElement().luma = tde.gui.lumas.getLuma("BLANK");
	}

	@Override
	public String getClassName() {return "Transparency";}
	
	@Override
	public int openProject(Vector<XmlObject> aOs, int o) throws Exception {
		keys.clear();
		for(;o < aOs.size();o++) {
			XmlObject aO = aOs.elementAt(o);
			if(aO instanceof XmlTag) {
				XmlTag aT = (XmlTag)aO;
				if("/Transparency".equalsIgnoreCase(aT.tagName)) {
					return o;
				}
				if("opacity".equalsIgnoreCase(aT.tagName)) {
					o++;
					keys.add(new Key());
					keys.lastElement().opacity = Double.parseDouble(aOs.elementAt(o).text);
				}
				if("luma".equalsIgnoreCase(aT.tagName)) {
					o++;
					keys.lastElement().luma = tde.gui.lumas.getLuma(aOs.elementAt(o).text.replaceAll("[\\\\\\\\]", "/"));
				}
				if("bevel".equalsIgnoreCase(aT.tagName)) {
					o++;
					keys.lastElement().bevel = Double.parseDouble(aOs.elementAt(o).text);
				}
				if("x".equalsIgnoreCase(aT.tagName)) {
					o++;
					keys.lastElement().x = Double.parseDouble(aOs.elementAt(o).text);
				}
				if("invert".equalsIgnoreCase(aT.tagName)) {
					o++;
					keys.lastElement().invert = "true".equals(aOs.elementAt(o).text);
				}
				if("time".equalsIgnoreCase(aT.tagName)) {
					o++;
					keys.lastElement().time = Long.parseLong(aOs.elementAt(o).text);
				}
				if("m".equalsIgnoreCase(aT.tagName)) {
					o++;
					keys.lastElement().m = Double.parseDouble(aOs.elementAt(o).text);
				}
				if("maskMedia".equalsIgnoreCase(aT.tagName)) {
					o++;
					long aMediaId = Long.parseLong(aOs.elementAt(o).text.trim());
					Media aM = aMediaId >= 0 ? tde.mediaList.getMediaByUID(aMediaId) : null;
					if(aM != null) {
						keys.lastElement().maskClip = maskClipFor(aM);
					}
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
		aSB.append("						<Transparency>\n");
		for(Key aK : keys) {
			aSB.append("							<opacity>" + aK.opacity + "</opacity>\n");
			aSB.append("							<luma>" + aK.luma.path + "</luma>\n");
			aSB.append("							<bevel>" + aK.bevel + "</bevel>\n");
			aSB.append("							<x>" + aK.x + "</x>\n");
			aSB.append("							<invert>" + aK.invert + "</invert>\n");
			aSB.append("							<time>" + aK.time + "</time>\n");
			aSB.append("							<m>" + aK.m + "</m>\n");
			if(aK.maskClip != null) {
				aSB.append("							<maskMedia>" + aK.maskClip.getMedia().UID + "</maskMedia>\n");
			}
		}
		aSB.append("						</Transparency>\n");
	}

	@Override
	void setDescr() {
		name = tde.gui.lngs.get("Processor.Transparency");
		icon = "spiral.gif";
	}

	public boolean isZero() {
		return true; 
	}
	
	public int getNbKeys() {
		return keys.size();
	}
	
	public int addKeyGT(long aTime){
		if(aTime < ((Clip)targetMedia).getStartTimeMS() || keyTime(aTime) <= 0 || aTime > ((Clip)targetMedia).getStartTimeMS() + ((Clip)targetMedia).getDurationMS()) {
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
		Clip aOld = keys.remove(aKey).maskClip;
		releaseUnused(aOld);
	}
	
	public int getPrevKeyGT(long aTime){
		long aT = keyTime(aTime);
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
		long aT = keyTime(aTime);
		for(int k = 0;k < keys.size();k++) {
			Key aK = keys.elementAt(k);
			if(aK.time > aT) {
				return k;
			}
		}
		//No key after
		return -1;
	}
	
	public double getOpacity(int aKey) {
		return key(aKey).opacity;
	}

	public void setOpacity(int aKey,double aOpacity) {
		key(aKey).opacity = aOpacity;
	}

	public double getM(int aKey) {
		return key(aKey).m;
	}

	public void setM(int aKey,double aM) {
		key(aKey).m = aM;
	}

	public double getBevel(int aKey) {
		return key(aKey).bevel;
	}

	public void setBevel(int aKey,double aBevel) {
		key(aKey).bevel = aBevel;
	}

	public double getX(int aKey) {
		return key(aKey).x;
	}

	public void setX(int aKey,double aX) {
		key(aKey).x = aX;
	}

	public boolean getInvert(int aKey) {
		return key(aKey).invert;
	}

	public void setInvert(int aKey,boolean aI) {
		key(aKey).invert = aI;
	}

	public Luma getLuma(int aKey) {
		return key(aKey).luma;
	}

	public void setLuma(int aKey,Luma luma) {
		key(aKey).luma = luma;
	}

	public long getTime(int aKey) {
		return key(aKey).time;
	}

	/**
	 * Paired clip of the mask aMedia (the one of another key if any: one per media)
	 */
	Clip maskClipFor(Media aMedia) {
		for(Key aK : keys) {
			if(aK.maskClip != null && aK.maskClip.getMedia() == aMedia) {
				return aK.maskClip;
			}
		}
		Clip aC = ((Clip)targetMedia).addPairedClip(aMedia, 1);
		//A mask made from the file of the clip: rotated as the clip media
		aC.followsOrientation = true;
		return aC;
	}

	public Clip getMaskClip(int aKey) {
		return key(aKey).maskClip;
	}

	/**
	 * Mask of the key aKey: the media of UID aUID ("": none, the luma)
	 */
	public void setMaskMedia(int aKey,String aUID) {
		Clip aOld = key(aKey).maskClip;
		Media aMedia = aUID == null || aUID.trim().isEmpty() ? null : tde.mediaList.getMediaByUID(Long.parseLong(aUID.trim()));
		key(aKey).maskClip = aMedia == null ? null : maskClipFor(aMedia);
		releaseUnused(aOld);
	}

	/**
	 * The paired clip aOld released if no key uses it any more
	 */
	void releaseUnused(Clip aOld) {
		if(aOld == null) {
			return;
		}
		for(Key aK : keys) {
			if(aK.maskClip == aOld) {
				return;
			}
		}
		((Clip)targetMedia).removePairedClip(aOld);
	}

	/**
	 * True if a key uses aMedia (of the media list) as mask
	 */
	@Override
	public String usesMediaKind(Media aMedia) {
		return usesMaskMedia(aMedia) ? "Mask" : null;
	}

	public boolean usesMaskMedia(Media aMedia) {
		for(Key aK : keys) {
			if(aK.maskClip != null && aK.maskClip.getMedia() == aMedia) {
				return true;
			}
		}
		return false;
	}

	/**
	 * aMedia leaves the media list: the keys that use it as mask take the luma again
	 */
	public void releaseMaskMedia(Media aMedia) {
		for(int k = 0;k < keys.size();k++) {
			Clip aC = keys.elementAt(k).maskClip;
			if(aC != null && aC.getMedia() == aMedia) {
				setMaskMedia(k, "");
			}
		}
	}

	@Override
	public void removed() {
		for(Key aK : keys) {
			if(aK.maskClip != null) {
				((Clip)targetMedia).removePairedClip(aK.maskClip);
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
		long aTime = keyTime(aTimePos);
		for(int k = keys.size()-1;k >= 0;k--) {
			Key aKey = keys.elementAt(k);
			if(aKey.time <= aTime) {
				if(k == keys.size()-1) {
					Key aInter = new Key();
					aInter.opacity = aKey.opacity;
					aInter.luma = aKey.luma;
					aInter.bevel = aKey.bevel;
					aInter.x = aKey.x;
					aInter.invert = aKey.invert;
					aInter.time = aTime;
					aInter.maskClip = aKey.maskClip;
					aInter.m = aKey.m;
					return aInter;
				}
				Key aNext = keys.elementAt(k+1);
				Key aInter = new Key();
				double aFact = (aTime-aKey.time)/(double)(aNext.time-aKey.time);
				aInter.opacity = aKey.opacity*(1.0-aFact)+aNext.opacity*aFact;
				aInter.luma = aKey.luma;
				aInter.bevel = aKey.bevel*(1.0-aFact)+aNext.bevel*aFact;
				aInter.m = aKey.m*(1.0-aFact)+aNext.m*aFact;
				aInter.x = aKey.x*(1.0-aFact)+aNext.x*aFact;
				aInter.invert = aKey.invert;
				aInter.time = aTime;
				aInter.maskClip = aKey.maskClip;
				return aInter;
			}
		}
		//??
		Key aKey = keys.firstElement();
		Key aInter = new Key();
		aInter.opacity = aKey.opacity;
		aInter.luma = aKey.luma;
		aInter.bevel = aKey.bevel;
		aInter.x = aKey.x;
		aInter.invert = aKey.invert;
		aInter.time = aTime;
		aInter.maskClip = aKey.maskClip;
		aInter.m = aKey.m;
		return aInter;
	}
	
	@Override
	public void process2Awt(Media aMedia,int aProcessingMode,long aTime) {
		try {
			Thread aThL = new Thread(new Runnable() {
				@Override
				public void run() {
					BufferedImage aBIL = aMedia.getWorkLeft(aProcessingMode,aTime);
					BufferedImage aProcessedBIL = 
//							tde.config.processingHdr[aProcessingMode]?
//							processAwtHdr(aBIL,aProcessingMode,true):
								processAwtImage(aBIL,aProcessingMode,aTime,true,tde.config.processingHdr[aProcessingMode]);
					aMedia.setWorkLeft(aProcessedBIL);
				}
			});
			aThL.start();
			Thread aThR = new Thread(new Runnable() {
				@Override
				public void run() {
					BufferedImage aBIR = aMedia.getWorkRight(aProcessingMode,aTime);
					BufferedImage aProcessedBIR = 
//							tde.config.processingHdr[aProcessingMode]?
//							processAwtHdr(aBIR,aProcessingMode,false):
								processAwtImage(aBIR,aProcessingMode,aTime,false,tde.config.processingHdr[aProcessingMode]);
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

	BufferedImage processAwtImage(BufferedImage aBI,int aProcessingMode,long aTime,boolean aIsL,boolean aIsHdr) {
		Key aMK = getInterKeyGT(aTime);
		if(aMK.maskClip != null) {
			BufferedImage aM = aIsL ? aMK.maskClip.getWorkLeft(aProcessingMode, aTime) : aMK.maskClip.getWorkRight(aProcessingMode, aTime);
			if(aM != null) {
				return applyMediaMask(aBI, ImageUtils.fitTo(aM, aBI), aMK, aIsL, aIsHdr);
			}
		}
		int aWidth = aBI.getWidth();
		int aHeight = aBI.getHeight();
		BufferedImage aFBI = aIsHdr?ImageUtils.createHdr(aWidth, aHeight)
				:ImageUtils.createImage(aWidth, aHeight);
		
		//Create mask
		Graphics2D aFG = (Graphics2D)aFBI.getGraphics();
		if(!tde.config.fastMode || aProcessingMode == TDConfig.PROCESSING_MODE_RENDER) {
			aFG.setRenderingHint(RenderingHints.KEY_ANTIALIASING,RenderingHints.VALUE_ANTIALIAS_ON);
			aFG.setRenderingHint(RenderingHints.KEY_RENDERING,RenderingHints.VALUE_RENDER_QUALITY);
		}
		
		Key aKey = getInterKeyGT(aTime);
		if(aKey.luma == null || aKey.luma.bi == null || "BLANK".equals(aKey.luma.path)) {
			Color aC = new Color((float)aKey.opacity,(float)aKey.opacity,(float)aKey.opacity);
			aFG.setColor(aC);
			aFG.fillRect(0, 0, aWidth, aHeight);
		}
		else {
			if(aIsL) {
				aFG.drawImage(aKey.luma.bi
						, (aKey.x>=0?0:(int)(aKey.x*aWidth)), (int)(-Math.abs(aKey.x)*aHeight/2)
						, aWidth+(aKey.x>0?(int)(Math.abs(aKey.x)*aWidth):0), aHeight+(int)(Math.abs(aKey.x)*aHeight)
						, 0, 0, aKey.luma.bi.getWidth(), aKey.luma.bi.getHeight(), null);
			}
			else {
				aFG.drawImage(aKey.luma.bi
						, (aKey.x<=0?0:(int)(-aKey.x*aWidth)), (int)(-Math.abs(aKey.x)*aHeight/2)
						, aWidth+(aKey.x<0?(int)(Math.abs(aKey.x)*aWidth):0), aHeight+(int)(Math.abs(aKey.x)*aHeight)
						, 0, 0, aKey.luma.bi.getWidth(), aKey.luma.bi.getHeight(), null);
			}

			double aBevel = aKey.bevel <= 0.0 ? 0.001/255.0:aKey.bevel;

			if(aIsHdr) {
				float[] aFDB = ((DataBufferFloat)aFBI.getRaster().getDataBuffer()).getData();
				for(int x = 0;x<aWidth;x++) {
					for(int y = 0;y<aHeight;y++) {
						double aLA = (aFDB[y*aWidth*4+x*4]//Red
								+aFDB[y*aWidth*4+x*4+1]//Green
										+aFDB[y*aWidth*4+x*4+2]//Blue
								)/3.0f;
						if(aKey.invert) {
							aLA = 1.0-aLA;
						}
						aLA = -(aLA - aKey.opacity)/aBevel;
						aLA = aLA <= 0 ? 0.0 : (aLA >= 1.0 ? 1.0 : aLA);
					    aFDB[y*aWidth*4+x*4]=(float)aLA;//Red
					    aFDB[y*aWidth*4+x*4+1]=(float)aLA;//Green
					    aFDB[y*aWidth*4+x*4+2]=(float)aLA;//Blue
					}
				}
			}
			else {
				int[] aFDB = ((DataBufferInt)aFBI.getRaster().getDataBuffer()).getData();
				for(int x = 0;x<aWidth;x++) {
					for(int y = 0;y<aHeight;y++) {
						double aLA = ((aFDB[x+y*aWidth]>>16&0xFF)//Red
								+(aFDB[x+y*aWidth]>>8&0xFF)//Green
								+(aFDB[x+y*aWidth]&0xFF)//Blue
								)/(3*255.0);
						if(aKey.invert) {
							aLA = 1.0-aLA;
						}
						aLA = -(aLA - aKey.opacity)/aBevel;
						aLA = aLA <= 0 ? 0.0 : (aLA >= 1.0 ? 1.0 : aLA);
						aFDB[x+y*aWidth]= ((int)(aLA*255))&0xFF
								| (((int)(aLA*255))&0xFF)<<8
								| (((int)(aLA*255))&0xFF)<<16;
					}
				}
				//Smooth
//				BufferedImage aSBI = aIsHdr?ImageUtils.createHdr(aWidth, aHeight)
//						:ImageUtils.createImage(aWidth, aHeight);
//				Kernel aK = new Kernel(3, 3, new float[] {
//						1/9.0f,1/9.0f,1/9.0f,
//						1/9.0f,1/9.0f,1/9.0f,
//						1/9.0f,1/9.0f,1/9.0f
//						});
//				ConvolveOp aCO = new ConvolveOp(aK);
//				aCO.filter(aFBI, aSBI);
//				aFBI = aSBI;
			}
		}
		
		
		//Apply mask
		if(aIsHdr) {
			float[] aDB = ((DataBufferFloat)aBI.getRaster().getDataBuffer()).getData();
			float[] aFDB = ((DataBufferFloat)aFBI.getRaster().getDataBuffer()).getData();
			for(int x = 0;x<aWidth;x++) {
				for(int y = 0;y<aHeight;y++) {
					float aR = aDB[y*aWidth*4+x*4];//Red
					float aG = aDB[y*aWidth*4+x*4+1];//Green
					float aB = aDB[y*aWidth*4+x*4+2];//Blue
					float aAlpha = aDB[y*aWidth*4+x*4+3]//Alpha
							*aFDB[y*aWidth*4+x*4+2];//Blue
				    aFDB[y*aWidth*4+x*4]=(float)aR;//Red
				    aFDB[y*aWidth*4+x*4+1]=(float)aG;//Green
				    aFDB[y*aWidth*4+x*4+2]=(float)aB;//Blue
				    aFDB[y*aWidth*4+x*4+3]=aAlpha;//Alpha
				}
			}
		}
		else {
			int[] aDB = ((DataBufferInt)aBI.getRaster().getDataBuffer()).getData();
			int[] aFDB = ((DataBufferInt)aFBI.getRaster().getDataBuffer()).getData();
			for(int p = 0;p < aDB.length;p++) {
				double aAlpha = 255.0
						*(((aDB[p]>>24)&0xFF)/255.0) 
						*((aFDB[p] & 0xFF)/255.0);//Blue
				aAlpha = aAlpha < 0 ? 0 : (aAlpha > 255 ? 255 : aAlpha);
				aFDB[p] =
						//					aDB[p];
						//					aFDB[p];
						(((int)aAlpha)&0xFF)<<24
						|(aDB[p]&0xFFFFFF);
			}
		}
			
		return aFBI;
	}

	/**
	 * aBI with its alpha multiplied by the luminance of the mask aM (same size), moved for the eye by X, its edges
	 * softened by Bevel, 1 - that if inverted, and by the opacity of the key
	 */
	BufferedImage applyMediaMask(BufferedImage aBI,BufferedImage aM,Key aKey,boolean aIsL,boolean aIsHdr) {
		int aWidth = aBI.getWidth();
		int aHeight = aBI.getHeight();
		int aN = aWidth*aHeight;
		//Luminance of the mask, 0..1
		float[] aLum = new float[aN];
		if(aM.getRaster().getDataBuffer() instanceof DataBufferFloat) {
			float[] aMD = ((DataBufferFloat)aM.getRaster().getDataBuffer()).getData();
			for(int p = 0;p < aN;p++) {
				aLum[p] = (aMD[p*4]+aMD[p*4+1]+aMD[p*4+2])/3.0f;
			}
		}
		else if(aM.getRaster().getDataBuffer() instanceof DataBufferInt && aM.getRaster().getNumBands() >= 3) {
			int[] aMD = ((DataBufferInt)aM.getRaster().getDataBuffer()).getData();
			for(int p = 0;p < aN;p++) {
				int aP = aMD[p];
				aLum[p] = (((aP>>16)&0xFF)+((aP>>8)&0xFF)+(aP&0xFF))/765.0f;
			}
		}
		else {
			int[] aRGB = aM.getRGB(0, 0, aWidth, aHeight, null, 0, aWidth);
			for(int p = 0;p < aN;p++) {
				int aP = aRGB[p];
				aLum[p] = (((aP>>16)&0xFF)+((aP>>8)&0xFF)+(aP&0xFF))/765.0f;
			}
		}
		for(int p = 0;p < aN;p++) {
			aLum[p] = aLum[p] < 0 ? 0 : (aLum[p] > 1 ? 1 : aLum[p]);
		}
		//Stereo position: half of X x the width to the right for the left eye, to the left for the right eye (as
		//the lumas), nothing where the mask comes from outside
		int aDx = (int)Math.round((aIsL ? 1 : -1)*aKey.x*aWidth/2.0);
		if(aDx != 0) {
			float[] aS = new float[aN];
			for(int y = 0;y < aHeight;y++) {
				int aRow = y*aWidth;
				for(int x = Math.max(0, aDx);x < Math.min(aWidth, aWidth+aDx);x++) {
					aS[aRow+x] = aLum[aRow+x-aDx];
				}
			}
			aLum = aS;
		}
		//Bevel: soft edges (two box blurs, close to a gaussian)
		int aRadius = (int)Math.round(aKey.bevel*MASK_FEATHER*aWidth/2.0);
		if(aRadius >= 1) {
			for(int aPass = 0;aPass < 2;aPass++) {
				aLum = boxBlur(aLum, aWidth, aHeight, aRadius, true);
				aLum = boxBlur(aLum, aWidth, aHeight, aRadius, false);
			}
		}
		float aOpacity = (float)aKey.opacity;
		for(int p = 0;p < aN;p++) {
			float aL = aLum[p] < 0 ? 0 : (aLum[p] > 1 ? 1 : aLum[p]);
			aLum[p] = (aKey.invert ? 1-aL : aL)*aOpacity;
		}
		//M: the colors toward the mask in violet (black where it is black), the alpha toward opaque
		float aMix = (float)Math.max(0, Math.min(1, aKey.m));
		float aVR = ((MASK_COLOR>>16)&0xFF)/255f;
		float aVG = ((MASK_COLOR>>8)&0xFF)/255f;
		float aVB = (MASK_COLOR&0xFF)/255f;
		if(aIsHdr) {
			BufferedImage aFBI = ImageUtils.createHdr(aWidth, aHeight);
			float[] aDB = ((DataBufferFloat)aBI.getRaster().getDataBuffer()).getData();
			float[] aFDB = ((DataBufferFloat)aFBI.getRaster().getDataBuffer()).getData();
			for(int p = 0;p < aN;p++) {
				float aV = aLum[p];
				aFDB[p*4] = aDB[p*4]*(1-aMix)+aVR*aV*aMix;
				aFDB[p*4+1] = aDB[p*4+1]*(1-aMix)+aVG*aV*aMix;
				aFDB[p*4+2] = aDB[p*4+2]*(1-aMix)+aVB*aV*aMix;
				aFDB[p*4+3] = aDB[p*4+3]*(aV*(1-aMix)+aMix);
			}
			return aFBI;
		}
		BufferedImage aFBI = ImageUtils.createImage(aWidth, aHeight);
		int[] aDB = ((DataBufferInt)aBI.getRaster().getDataBuffer()).getData();
		int[] aFDB = ((DataBufferInt)aFBI.getRaster().getDataBuffer()).getData();
		for(int p = 0;p < aN;p++) {
			float aV = aLum[p];
			int aSrc = aDB[p];
			int aA = Math.round(((aSrc>>24)&0xFF)*(aV*(1-aMix)+aMix));
			int aRgb = aSrc&0xFFFFFF;
			if(aMix > 0) {
				int aR = Math.round(((aSrc>>16)&0xFF)*(1-aMix)+255*aVR*aV*aMix);
				int aG = Math.round(((aSrc>>8)&0xFF)*(1-aMix)+255*aVG*aV*aMix);
				int aB = Math.round((aSrc&0xFF)*(1-aMix)+255*aVB*aV*aMix);
				aRgb = (Math.min(255, aR)<<16)|(Math.min(255, aG)<<8)|Math.min(255, aB);
			}
			aFDB[p] = ((aA < 0 ? 0 : (aA > 255 ? 255 : aA))<<24) | aRgb;
		}
		return aFBI;
	}

	/**
	 * Mean over 2 x aR + 1 pixels along the lines (aH true) or the columns, the edges of the image repeated
	 */
	static float[] boxBlur(float[] aSrc,int aW,int aH,int aR,boolean aHoriz) {
		float[] aOut = new float[aSrc.length];
		int aLen = aHoriz ? aW : aH;
		int aLines = aHoriz ? aH : aW;
		int aStep = aHoriz ? 1 : aW;
		float aNorm = 1.0f/(2*aR+1);
		for(int l = 0;l < aLines;l++) {
			int aBase = aHoriz ? l*aW : l;
			double aSum = 0;
			for(int k = -aR;k <= aR;k++) {
				aSum += aSrc[aBase+Math.max(0, Math.min(aLen-1, k))*aStep];
			}
			for(int i = 0;i < aLen;i++) {
				aOut[aBase+i*aStep] = (float)(aSum*aNorm);
				aSum += aSrc[aBase+Math.min(aLen-1, i+aR+1)*aStep]-aSrc[aBase+Math.max(0, i-aR)*aStep];
			}
		}
		return aOut;
	}
}
