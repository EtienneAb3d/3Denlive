package com.cubaix.TDenlive.processors;

import java.awt.image.BufferedImage;
import java.awt.image.DataBufferFloat;
import java.awt.image.DataBufferInt;
import java.io.BufferedWriter;
import java.util.Vector;

import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.graphics.Rectangle;

import com.cubaix.TDenlive.TDenlive;
import com.cubaix.TDenlive.medias.Media;
import com.cubaix.TDenlive.utils.ImageUtils;
import com.cubaix.TDenlive.xml.XmlObject;
import com.cubaix.TDenlive.xml.XmlTag;

public class ColorAdapter extends Processor {
    double wR = 2.0
    ,tRH = 0.6
    ,rToGB = 0.5,gFromR = 0.25,bFromR = 0.25
    ,tRL = 0.4
    ,rFromGB = 0.5,gToR = 0.25,bToR = 0.25;
    String presetR = "Half";
    String presetC = "Half";

	public ColorAdapter(TDenlive aTDe,Media aTargetMedia) {
		super(aTDe,aTargetMedia);
		setPresetR("NoEffect");
		setPresetC("NoEffect");
		isExpended = true;
	}
	
	@Override
	public String getClassName() {return "ColorAdapter";}

	@Override
	public int openProject(Vector<XmlObject> aOs, int o) throws Exception {
		for(;o < aOs.size();o++) {
			XmlObject aO = aOs.elementAt(o);
			if(aO instanceof XmlTag) {
				XmlTag aT = (XmlTag)aO;
				if("/ColorAdapter".equalsIgnoreCase(aT.tagName)) {
					return o;
				}
				if("presetR".equalsIgnoreCase(aT.tagName)) {
					o++;
					presetR = aOs.elementAt(o).text;
				}
				if("presetC".equalsIgnoreCase(aT.tagName)) {
					o++;
					presetC = aOs.elementAt(o).text;
				}
				if("wR".equalsIgnoreCase(aT.tagName)) {
					o++;
					wR = Double.parseDouble(aOs.elementAt(o).text);
				}
				if("tRH".equalsIgnoreCase(aT.tagName)) {
					o++;
					tRH = Double.parseDouble(aOs.elementAt(o).text);
				}
				if("rToGB".equalsIgnoreCase(aT.tagName)) {
					o++;
					rToGB = Double.parseDouble(aOs.elementAt(o).text);
				}
				if("gFromR".equalsIgnoreCase(aT.tagName)) {
					o++;
					gFromR = Double.parseDouble(aOs.elementAt(o).text);
				}
				if("bFromR".equalsIgnoreCase(aT.tagName)) {
					o++;
					bFromR = Double.parseDouble(aOs.elementAt(o).text);
				}
				if("tRL".equalsIgnoreCase(aT.tagName)) {
					o++;
					tRL = Double.parseDouble(aOs.elementAt(o).text);
				}
				if("rFromGB".equalsIgnoreCase(aT.tagName)) {
					o++;
					rFromGB = Double.parseDouble(aOs.elementAt(o).text);
				}
				if("gToR".equalsIgnoreCase(aT.tagName)) {
					o++;
					gToR = Double.parseDouble(aOs.elementAt(o).text);
				}
				if("bToR".equalsIgnoreCase(aT.tagName)) {
					o++;
					bToR = Double.parseDouble(aOs.elementAt(o).text);
				}
			}
		}
		return o;
	}

	@Override
	public void saveProject(StringBuffer aSB) throws Exception {
		aSB.append("						<ColorAdapter>\n");
		aSB.append("							<presetR>" + presetR + "</presetR>\n");
		aSB.append("							<presetC>" + presetC + "</presetC>\n");
		aSB.append("							<wR>" + wR + "</wR>\n");
		aSB.append("							<tRH>" + tRH + "</tRH>\n");
		aSB.append("							<rToGB>" + rToGB + "</rToGB>\n");
		aSB.append("							<gFromR>" + gFromR + "</gFromR>\n");
		aSB.append("							<bFromR>" + bFromR + "</bFromR>\n");
		aSB.append("							<tRL>" + tRL + "</tRL>\n");
		aSB.append("							<rFromGB>" + rFromGB + "</rFromGB>\n");
		aSB.append("							<gToR>" + gToR + "</gToR>\n");
		aSB.append("							<bToR>" + rToGB + "</bToR>\n");
		aSB.append("						</ColorAdapter>\n");
	}

	@Override
	void setDescr() {
		name = tde.gui.lngs.get("Processor.AdaptColor");
		icon = "colourPalette2.gif";
	}

	public double gettRH() {
		return tRH;
	}

	public void settRH(double tRH) {
		this.tRH = tRH;
	}

	public double gettRL() {
		return tRL;
	}

	public void settRL(double tRL) {
		this.tRL = tRL;
	}

	public String getPresetR() {
		return presetR;
	}

	public String[] getPresetListR() {
		return new String[] {
				"NoEffect"
				,"Half"
				,"Brown"
				,"Orange"
				,"Yellow"
				,"Green"
				,"Purple"
				,"Magenta"
				,"Blue"
				,"White"
				,"LightGray"
				,"Gray"
				,"DarkGray"
		};
	}
	
	public String[] getPresetListC() {
		return new String[] {
				"NoEffect"
				,"Half"
				,"Blue"
				,"Purple"
				,"Magenta"
				,"Green"
				,"Yellow"
				,"Orange"
				,"White"
				,"LightGray"
				,"Gray"
				,"DarkGray"
		};
	}
	
	public void setPresetR(String aPreset) {
		presetR = aPreset;
		if("NoEffect".equals(aPreset)) {
			wR = 2.0;
			tRH = 1.0;
			rToGB = 0;
			gFromR = 0;
			bFromR = 0;
		}
		else if("Half".equals(aPreset)) {
			wR = 2.0;
			tRH = 0.6;
			rToGB = 0.5;
			gFromR = 0.25;
			bFromR = 0.25;
		}
		else if("Brown".equals(aPreset)) {
			wR = 2.0;
			tRH = 0.6;
			rToGB = 0.5;
			gFromR = 0.4;
			bFromR = 0.0;
		}
		else if("Orange".equals(aPreset)) {
			wR = 2.0;
			tRH = 0.6;
			rToGB = 0.0;
			gFromR = 0.5;
			bFromR = 0.0;
		}
		else if("Yellow".equals(aPreset)) {
			wR = 2.0;
			tRH = 0.6;
			rToGB = 0.0;
			gFromR = 1;
			bFromR = 0.0;
		}
		else if("Green".equals(aPreset)) {
			wR = 2.0;
			tRH = 0.6;
			rToGB = 0.5;
			gFromR = 1;
			bFromR = 0.0;
		}
		else if("Purple".equals(aPreset)) {
			wR = 2.0;
			tRH = 0.6;
			rToGB = 0.5;
			gFromR = 0;
			bFromR = 0.5;
		}
		else if("Magenta".equals(aPreset)) {
			wR = 2.0;
			tRH = 0.6;
			rToGB = 0.0;
			gFromR = 0;
			bFromR = 1.0;
		}
		else if("Blue".equals(aPreset)) {
			wR = 2.0;
			tRH = 0.6;
			rToGB = 0.5;
			gFromR = 0;
			bFromR = 1.0;
		}
		else if("White".equals(aPreset)) {
			wR = 2.0;
			tRH = 0.5;
			rToGB = 0.0;
			gFromR = 1.0;
			bFromR = 1.0;
		}
		else if("LightGray".equals(aPreset)) {
			wR = 2.0;
			tRH = 0.5;
			rToGB = 0.2;
			gFromR = 0.8;
			bFromR = 0.8;
		}
		else if("Gray".equals(aPreset)) {
			wR = 2.0;
			tRH = 0.5;
			rToGB = 0.5;
			gFromR = 0.5;
			bFromR = 0.5;
		}
		else if("DarkGray".equals(aPreset)) {
			wR = 2.0;
			tRH = 0.5;
			rToGB = 0.7;
			gFromR = 0.3;
			bFromR = 0.3;
		}
	}

	public String getPresetC() {
		return presetC;
	}

	public void setPresetC(String aPreset) {
		presetC = aPreset;
		if("NoEffect".equals(aPreset)) {
			wR = 2.0;
			tRL = 0.0;
			rFromGB = 0;
			gToR = 0;
			bToR = 0;
		}
		else if("Half".equals(aPreset)) {
			wR = 2.0;
			tRL = 0.4;
			rFromGB = 0.5;
			gToR = 0.25;
			bToR = 0.25;
		}
		else if("Blue".equals(aPreset)) {
			wR = 2.0;
			tRL = 0.4;
			rFromGB = 0.5;
			gToR = 1.0;
			bToR = 0.0;
		}
		else if("Violet".equals(aPreset)) {
			wR = 2.0;
			tRL = 0.4;
			rFromGB = 1.0;
			gToR = 1.0;
			bToR = 0.0;
		}
		else if("Magenta".equals(aPreset)) {
			wR = 2.0;
			tRL = 0.4;
			rFromGB = 1.0;
			gToR = 1.0;
			bToR = 0.5;
		}
		else if("Green".equals(aPreset)) {
			wR = 2.0;
			tRL = 0.4;
			rFromGB = 0.5;
			gToR = 0.0;
			bToR = 1.0;
		}
		else if("Yellow".equals(aPreset)) {
			wR = 2.0;
			tRL = 0.4;
			rFromGB = 1.0;
			gToR = 0.0;
			bToR = 1.0;
		}
		else if("Orange".equals(aPreset)) {
			wR = 2.0;
			tRL = 0.4;
			rFromGB = 1.0;
			gToR = 0.5;
			bToR = 1.0;
		}
		else if("White".equals(aPreset)) {
			wR = 2.0;
			tRL = 0.5;
			rFromGB = 1.0;
			gToR = 0;
			bToR = 0;
		}
		else if("LightGray".equals(aPreset)) {
			wR = 2.0;
			tRL = 0.5;
			rFromGB = 0.7;
			gToR = 0.3;
			bToR = 0.3;
		}
		else if("Gray".equals(aPreset)) {
			wR = 2.0;
			tRL = 0.5;
			rFromGB = 0.5;
			gToR = 0.5;
			bToR = 0.5;
		}
		else if("DarkGray".equals(aPreset)) {
			wR = 2.0;
			tRL = 0.5;
			rFromGB = 0.3;
			gToR = 0.7;
			bToR = 0.7;
		}
	}

	@Override
	public Image process2Swt(Media aMedia, int aProcessingMode, Rectangle aTargetSize, long aTime) {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public void process2Awt(Media aMedia, int aProcessingMode, long aTime) {
		try {
			Thread aThL = new Thread(new Runnable() {
				@Override
				public void run() {
					BufferedImage aBIL = aMedia.getWorkLeft(aProcessingMode,aTime);
					BufferedImage aProcessedBIL = tde.config.processingHdr[aProcessingMode]?
							processAwtHdr(aBIL):processAwtImage(aBIL);
					aMedia.setWorkLeft(aProcessedBIL);
				}
			});
			aThL.start();
			Thread aThR = new Thread(new Runnable() {
				@Override
				public void run() {
					BufferedImage aBIR = aMedia.getWorkRight(aProcessingMode,aTime);
					BufferedImage aProcessedBIR = tde.config.processingHdr[aProcessingMode]?
							processAwtHdr(aBIR):processAwtImage(aBIR);
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
	
	BufferedImage processAwtImage(BufferedImage aBI) {
		int aWidth = aBI.getWidth();
		int aHeight = aBI.getHeight();
		int[] aDB = ((DataBufferInt)aBI.getRaster().getDataBuffer()).getData();
		BufferedImage aFBI = ImageUtils.createImage(aWidth, aHeight);
		int[] aFDB = ((DataBufferInt)aFBI.getRaster().getDataBuffer()).getData();

		for(int x = 0;x<aWidth;x++) {
			for(int y = 0;y<aHeight;y++) {
				int aARGBL = aDB[y*aWidth+x];
				int aAlpha = (aARGBL>>24) & 0xFF;//Alpha
				int aR = (int)((aARGBL>>16) & 0xFF);//Red
				int aG = (int)((aARGBL>>8) & 0xFF);//Green
				int aB = (int)(aARGBL & 0xFF);//Blue

				double aLum = wR * aR + aG + aB;

			    double aNewR = aR,aNewG = aG,aNewB = aB;
			    if(wR * aR > tRH * aLum){
			    	double aDelta = wR * aR - tRH * aLum;
			    	aNewR = aR - rToGB * aDelta;
			    	aNewG = aG + gFromR * aDelta;
			    	aNewB = aB + bFromR * aDelta;
			    	aNewR = (aNewR < 0) ? 0 : ((aNewR > 255) ? 255 : aNewR);
			    	aNewG = (aNewG < 0) ? 0 : ((aNewG > 255) ? 255 : aNewG);
			    	aNewB = (aNewB < 0) ? 0 : ((aNewB > 255) ? 255 : aNewB);
			    }
			    else if(wR * aR < tRL * aLum){
			    	double aDelta = tRL * aLum - wR * aR;
			    	aNewR = aR + rFromGB * aDelta;
			    	aNewG = aG - gToR * aDelta;
			    	aNewB = aB - bToR * aDelta;
			    	aNewR = (aNewR < 0) ? 0 : ((aNewR > 255) ? 255 : aNewR);
			    	aNewG = (aNewG < 0) ? 0 : ((aNewG > 255) ? 255 : aNewG);
			    	aNewB = (aNewB < 0) ? 0 : ((aNewB > 255) ? 255 : aNewB);
			    }

				int aPix = ((int)aAlpha) << 24
						| ((int)aNewR) << 16
						| ((int)aNewG) << 8
						| (int)aNewB
						;
				aFDB[y*aWidth+x] = aPix;
			}
		}
		return aFBI;

	}

	BufferedImage processAwtHdr(BufferedImage aBI) {
		int aWidth = aBI.getWidth();
		int aHeight = aBI.getHeight();
		float[] aDB = ((DataBufferFloat)aBI.getRaster().getDataBuffer()).getData();
		BufferedImage aFBI = ImageUtils.createHdr(aWidth, aHeight);
		float[] aFDB = ((DataBufferFloat)aFBI.getRaster().getDataBuffer()).getData();

		for(int x = 0;x<aWidth;x++) {
			for(int y = 0;y<aHeight;y++) {
				float aR = aDB[y*aWidth*4+x*4];//Red
				float aG = aDB[y*aWidth*4+x*4+1];//Green
				float aB = aDB[y*aWidth*4+x*4+2];//Blue
				float aAlpha = aDB[y*aWidth*4+x*4+3];//Alpha

				double aLum = wR * aR + aG + aB;

			    double aNewR = aR,aNewG = aG,aNewB = aB;
			    if(wR * aR > tRH * aLum){
			    	double aDelta = wR * aR - tRH * aLum;
			    	aNewR = aR - rToGB * aDelta;
			    	aNewG = aG + gFromR * aDelta;
			    	aNewB = aB + bFromR * aDelta;
			    	aNewR = (aNewR < 0) ? 0 : ((aNewR > 255) ? 255 : aNewR);
			    	aNewG = (aNewG < 0) ? 0 : ((aNewG > 255) ? 255 : aNewG);
			    	aNewB = (aNewB < 0) ? 0 : ((aNewB > 255) ? 255 : aNewB);
			    }
			    else if(wR * aR < tRL * aLum){
			    	double aDelta = tRL * aLum - wR * aR;
			    	aNewR = aR + rFromGB * aDelta;
			    	aNewG = aG - gToR * aDelta;
			    	aNewB = aB - bToR * aDelta;
			    	aNewR = (aNewR < 0) ? 0 : ((aNewR > 255) ? 255 : aNewR);
			    	aNewG = (aNewG < 0) ? 0 : ((aNewG > 255) ? 255 : aNewG);
			    	aNewB = (aNewB < 0) ? 0 : ((aNewB > 255) ? 255 : aNewB);
			    }

			    aFDB[y*aWidth*4+x*4]=(float)aNewR;//Red
			    aFDB[y*aWidth*4+x*4+1]=(float)aNewG;//Green
			    aFDB[y*aWidth*4+x*4+2]=(float)aNewB;//Blue
			    aFDB[y*aWidth*4+x*4+3]=aAlpha;//Alpha
			}
		}
		return aFBI;

	}

}
