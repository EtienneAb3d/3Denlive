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

public class BrightnessContrast extends Processor {
	public int applyTo = 2;//0=left, 1=right, 2=both
	public int color = 3;//0=red, 1=green, 2=blue, 3=all, 4=cyan, 5=magenta, 6=yellow
	public double brightness = 0.0;
	public double contrast = 0.0;
	public double gamma = 0.0;
	public double th = 0.0;

	public BrightnessContrast(TDenlive aTDe, Media aTargetMedia) {
		super(aTDe, aTargetMedia);
		isExpended = true;
	}

	@Override
	public String getClassName() {return "BrightnessContrast";}

	@Override
	public int openProject(Vector<XmlObject> aOs, int o) throws Exception {
		for(;o < aOs.size();o++) {
			XmlObject aO = aOs.elementAt(o);
			if(aO instanceof XmlTag) {
				XmlTag aT = (XmlTag)aO;
				if("/BrightnessContrast".equalsIgnoreCase(aT.tagName)) {
					return o;
				}
				if("applyTo".equalsIgnoreCase(aT.tagName)) {
					o++;
					applyTo = Integer.parseInt(aOs.elementAt(o).text);
				}
				if("color".equalsIgnoreCase(aT.tagName)) {
					o++;
					color = Integer.parseInt(aOs.elementAt(o).text);
				}
				if("brightness".equalsIgnoreCase(aT.tagName)) {
					o++;
					brightness = Double.parseDouble(aOs.elementAt(o).text);
				}
				if("contrast".equalsIgnoreCase(aT.tagName)) {
					o++;
					contrast = Double.parseDouble(aOs.elementAt(o).text);
				}
				if("gamma".equalsIgnoreCase(aT.tagName)) {
					o++;
					gamma = Double.parseDouble(aOs.elementAt(o).text);
				}
				if("th".equalsIgnoreCase(aT.tagName)
						//Bug in the name
						||"sh".equalsIgnoreCase(aT.tagName)) {
					o++;
					th = Double.parseDouble(aOs.elementAt(o).text);
				}
			}
		}
		return o;
	}

	@Override
	public void saveProject(StringBuffer aSB) throws Exception {
		aSB.append("						<BrightnessContrast>\n");
		aSB.append("							<applyTo>" + applyTo + "</applyTo>\n");
		aSB.append("							<color>" + color + "</color>\n");
		aSB.append("							<brightness>" + brightness + "</brightness>\n");
		aSB.append("							<contrast>" + contrast + "</contrast>\n");
		aSB.append("							<gamma>" + gamma + "</gamma>\n");
		aSB.append("							<th>" + th + "</th>\n");
		aSB.append("						</BrightnessContrast>\n");
	}

	@Override
	void setDescr() {
		name = tde.gui.lngs.get("Processor.BrightnessContrast");
		icon = "starsColor.gif";
	}

	public int getApplyTo() {
		return applyTo;
	}

	public void setApplyTo(int applyTo) {
		this.applyTo = applyTo;
	}

	public int getColor() {
		return color;
	}

	public void setColor(int color) {
		this.color = color;
	}

	public double getBrightness() {
		return brightness;
	}

	public void setBrightness(double brightness) {
		this.brightness = brightness;
	}

	public double getContrast() {
		return contrast;
	}

	public void setContrast(double contrast) {
		this.contrast = contrast;
	}

	public double getGamma() {
		return gamma;
	}

	public void setGamma(double gamma) {
		this.gamma = gamma;
	}

	public double getTh() {
		return th;
	}

	public void setTh(double th) {
		this.th = th;
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
					if(applyTo == 0 || applyTo == 2) {
						BufferedImage aBIL = aMedia.getWorkLeft(aProcessingMode,aTime);
						BufferedImage aProcessedBIL = tde.config.processingHdr[aProcessingMode]?
								processAwtHdr(aBIL,true):processAwtImage(aBIL,aProcessingMode,true);
								aMedia.setWorkLeft(aProcessedBIL);
					}
				}
			});
			aThL.start();
			Thread aThR = new Thread(new Runnable() {
				@Override
				public void run() {
					if(applyTo == 1 || applyTo == 2) {
						BufferedImage aBIR = aMedia.getWorkRight(aProcessingMode,aTime);
						BufferedImage aProcessedBIR = tde.config.processingHdr[aProcessingMode]?
								processAwtHdr(aBIR,false):processAwtImage(aBIR,aProcessingMode,false);
								aMedia.setWorkRight(aProcessedBIR);
					}
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

	static final double atanh(double x) {
		return 0.5*Math.log((1+x)/(1-x));
	}
	
	BufferedImage processAwtImage(BufferedImage aBI,int aProcessingMode,boolean aIsL) {
		int aWidth = aBI.getWidth();
		int aHeight = aBI.getHeight();
		int[] aDB = ((DataBufferInt)aBI.getRaster().getDataBuffer()).getData();
		BufferedImage aFBI = ImageUtils.createImage(aWidth, aHeight);
		int[] aFDB = ((DataBufferInt)aFBI.getRaster().getDataBuffer()).getData();

	    int aNbVal = 10000;

	    double[] aTh = new double[aNbVal+1];
	    for(int f = 0;f <= aNbVal;f++) {
	    	if(th > 0) {
	    		aTh[f] = (Math.tanh(10*th*(f/(double)aNbVal - 0.5))-Math.tanh(10*th*(-0.5)))
	    				/(Math.tanh(10*th*0.5)-Math.tanh(10*th*(-0.5)));
	    	}
	    	else if(th < 0) {
	    		aTh[f] = (Math.tanh(-10*th*(f/(double)aNbVal - 0.5))-Math.tanh(-10*th*(-0.5)))
	    				/(Math.tanh(-10*th*0.5)-Math.tanh(-10*th*(-0.5)));
	    	}
	    	else {
	    		aTh[f] = f/(double)aNbVal;
	    	}
	    }
	    if(th < 0) {
		    double[] aAth = new double[aNbVal+1];
		    aAth[aNbVal] = 1.0;
		    for(int f = 0;f < aNbVal;f++) {
		    	int aY1 = (int)(aNbVal*aTh[f]);
		    	int aY2 = (int)(aNbVal*aTh[f+1]);
		    	aY1 = aY1 <= 0 ? 0 : (aY1 >= aNbVal ? aNbVal : aY1);
		    	aY2 = aY2 <= 0 ? 0 : (aY2 >= aNbVal ? aNbVal : aY2);
		    	for(int y = aY1;y <= aY2;y++) {
		    		aAth[y] = (f+(y <= (aY2-aY1)/2.0 ? 0 : 1))/(double)aNbVal;
		    	}
		    }
		    aTh = aAth;
	    }
	    double aContrast = (contrast >= 0 ? (1.0+contrast):1.0/(1.0-contrast));
	    double aGamma = (gamma >= 0 ?1.0/(1.0+5*gamma) : (1.0-5*gamma));
	    double[] aF = new double[aNbVal+1];
	    for(int f = 0;f <= aNbVal;f++) {
		    aF[f] = aContrast*(f/(double)aNbVal-0.5)+0.5+brightness;
		    aF[f] = aF[f] > 1.0 ? 1.0:(aF[f] < 0.0 ? 0.0 : aF[f]);
		    aF[f] = Math.pow(aF[f], aGamma);
		    aF[f] = aF[f] > 1.0 ? 1.0:(aF[f] < 0.0 ? 0.0 : aF[f]);
		    aF[f] = aTh[(int)(aNbVal*aF[f])];
		    aF[f] = aF[f] > 1.0 ? 1.0:(aF[f] < 0.0 ? 0.0 : aF[f]);
	    }
	    
		for(int x = 0;x<aWidth;x++) {
			for(int y = 0;y<aHeight;y++) {
				int aARGBL = aDB[y*aWidth+x];
				double aAlpha = ((aARGBL>>24) & 0xFF)/255.0;//Alpha
				double aR = ((aARGBL>>16) & 0xFF)/255.0;//Red
				double aG = ((aARGBL>>8) & 0xFF)/255.0;//Green
				double aB = (aARGBL & 0xFF)/255.0;//Blue

			    double aNewR = aR,aNewG = aG,aNewB = aB;

			    int aX;
			    double aW;

			    if(color == 0 || color == 3 || color == 5 || color == 6) {
			    	aX = (int)Math.floor(aR*aNbVal);
			    	aW = aR*aNbVal - aX;
			    	aNewR = aX >= aNbVal ? aF[aNbVal]:aF[aX]*(1.0-aW)+aF[aX+1]*aW; 
			    }

			    if(color == 1 || color == 3 || color == 4 || color == 6) {
			    	aX = (int)Math.floor(aG*aNbVal);
			    	aW = aG*aNbVal - aX;
			    	aNewG = aX >= aNbVal ? aF[aNbVal]:aF[aX]*(1.0-aW)+aF[aX+1]*aW; 
			    }

			    if(color == 2 || color == 3 || color == 4 || color == 5) {
			    	aX = (int)Math.floor(aB*aNbVal);
			    	aW = aB*aNbVal - aX;
			    	aNewB = aX >= aNbVal ? aF[aNbVal]:aF[aX]*(1.0-aW)+aF[aX+1]*aW; 
			    }
			    
				int aPix = ((int)(aAlpha*255.0)) << 24
						| ((int)(aNewR*255.0)) << 16
						| ((int)(aNewG*255.0)) << 8
						| (int)(aNewB*255.0)
						;
				aFDB[y*aWidth+x] = aPix;
			}
		}
		
		return aFBI;
	}

	BufferedImage processAwtHdr(BufferedImage aBI,boolean aIsL) {
		int aWidth = aBI.getWidth();
		int aHeight = aBI.getHeight();
		float[] aDB = ((DataBufferFloat)aBI.getRaster().getDataBuffer()).getData();
		BufferedImage aFBI = ImageUtils.createHdr(aWidth, aHeight);
		float[] aFDB = ((DataBufferFloat)aFBI.getRaster().getDataBuffer()).getData();

	    int aNbVal = 10000;

	    double[] aTh = new double[aNbVal+1];
	    for(int f = 0;f <= aNbVal;f++) {
	    	if(th > 0) {
	    		aTh[f] = (Math.tanh(10*th*(f/(double)aNbVal - 0.5))-Math.tanh(10*th*(-0.5)))
	    				/(Math.tanh(10*th*0.5)-Math.tanh(10*th*(-0.5)));
	    	}
	    	else if(th < 0) {
	    		aTh[f] = (Math.tanh(-10*th*(f/(double)aNbVal - 0.5))-Math.tanh(-10*th*(-0.5)))
	    				/(Math.tanh(-10*th*0.5)-Math.tanh(-10*th*(-0.5)));
	    	}
	    	else {
	    		aTh[f] = f/(double)aNbVal;
	    	}
	    }
	    if(th < 0) {
		    double[] aAth = new double[aNbVal+1];
		    aAth[aNbVal] = 1.0;
		    for(int f = 0;f < aNbVal;f++) {
		    	int aY1 = (int)(aNbVal*aTh[f]);
		    	int aY2 = (int)(aNbVal*aTh[f+1]);
		    	for(int y = aY1;y <= aY2;y++) {
		    		aAth[y] = (f+(y <= (aY2-aY1)/2.0 ? 0 : 1))/(double)aNbVal;
		    	}
		    }
		    aTh = aAth;
	    }
	    double aContrast = (contrast >= 0 ? (1.0+contrast):1.0/(1.0-contrast));
	    double aGamma = (gamma >= 0 ?1.0/(1.0+5*gamma) : (1.0-5*gamma));
	    double[] aF = new double[aNbVal+1];
	    for(int f = 0;f <= aNbVal;f++) {
		    aF[f] = aContrast*(f/(double)aNbVal-0.5)+0.5+brightness;
		    aF[f] = aF[f] > 1.0 ? 1.0:(aF[f] < 0.0 ? 0.0 : aF[f]);
		    aF[f] = Math.pow(aF[f], aGamma);
		    aF[f] = aF[f] > 1.0 ? 1.0:(aF[f] < 0.0 ? 0.0 : aF[f]);
		    aF[f] = aTh[(int)(aNbVal*aF[f])];
		    aF[f] = aF[f] > 1.0 ? 1.0:(aF[f] < 0.0 ? 0.0 : aF[f]);
	    }
	    
		for(int x = 0;x<aWidth;x++) {
			for(int y = 0;y<aHeight;y++) {
				float aR = aDB[y*aWidth*4+x*4];//Red
				float aG = aDB[y*aWidth*4+x*4+1];//Green
				float aB = aDB[y*aWidth*4+x*4+2];//Blue
				float aAlpha = aDB[y*aWidth*4+x*4+3];//Alpha

			    double aNewR = aR,aNewG = aG,aNewB = aB;
			    
			    int aX;
			    double aW;

			    if(color == 0 || color == 3 || color == 5 || color == 6) {
			    	aX = (int)Math.floor(aR*aNbVal);
			    	aW = aR*aNbVal - aX;
			    	aNewR = aX >= aNbVal ? aF[aNbVal]:aF[aX]*(1.0-aW)+aF[aX+1]*aW; 
			    }

			    if(color == 1 || color == 3 || color == 4 || color == 6) {
			    	aX = (int)Math.floor(aG*aNbVal);
			    	aW = aG*aNbVal - aX;
			    	aNewG = aX >= aNbVal ? aF[aNbVal]:aF[aX]*(1.0-aW)+aF[aX+1]*aW; 
			    }

			    if(color == 2 || color == 3 || color == 4 || color == 5) {
			    	aX = (int)Math.floor(aB*aNbVal);
			    	aW = aB*aNbVal - aX;
			    	aNewB = aX >= aNbVal ? aF[aNbVal]:aF[aX]*(1.0-aW)+aF[aX+1]*aW; 
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
