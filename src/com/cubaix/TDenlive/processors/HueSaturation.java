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

public class HueSaturation extends Processor {
	public int applyTo = 2;//0=left, 1=right, 2=both
	public int color = 3;//0=red, 1=green, 2=blue, 3=all, 4=cyan, 5=magenta, 6=yellow
	public double brightness = 1.0;
	public double saturation = 1.0;
	public double hue = 0.0;
	public double threshold = 0.5;
	public double range = 50;

	public HueSaturation(TDenlive aTDe, Media aTargetMedia) {
		super(aTDe, aTargetMedia);
		isExpended = true;
	}

	@Override
	public String getClassName() {return "HueSaturation";}

	@Override
	public int openProject(Vector<XmlObject> aOs, int o) throws Exception {
		for(;o < aOs.size();o++) {
			XmlObject aO = aOs.elementAt(o);
			if(aO instanceof XmlTag) {
				XmlTag aT = (XmlTag)aO;
				if("/HueSaturation".equalsIgnoreCase(aT.tagName)) {
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
				if("saturation".equalsIgnoreCase(aT.tagName)) {
					o++;
					saturation = Double.parseDouble(aOs.elementAt(o).text);
				}
				if("hue".equalsIgnoreCase(aT.tagName)) {
					o++;
					hue = Double.parseDouble(aOs.elementAt(o).text);
				}
				if("threshold".equalsIgnoreCase(aT.tagName)) {
					o++;
					threshold = Double.parseDouble(aOs.elementAt(o).text);
				}
				if("range".equalsIgnoreCase(aT.tagName)) {
					o++;
					range = Double.parseDouble(aOs.elementAt(o).text);
				}
			}
		}
		return o;
	}

	@Override
	public void saveProject(StringBuffer aSB) throws Exception {
		aSB.append("						<HueSaturation>\n");
		aSB.append("							<applyTo>" + applyTo + "</applyTo>\n");
		aSB.append("							<color>" + color + "</color>\n");
		aSB.append("							<brightness>" + brightness + "</brightness>\n");
		aSB.append("							<saturation>" + saturation + "</saturation>\n");
		aSB.append("							<hue>" + hue + "</hue>\n");
		aSB.append("							<threshold>" + threshold + "</threshold>\n");
		aSB.append("							<range>" + range + "</range>\n");
		aSB.append("						</HueSaturation>\n");
	}

	@Override
	void setDescr() {
		name = tde.gui.lngs.get("Processor.HueSaturation");
		icon = "colorPalette.gif";
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

	public double getSaturation() {
		return saturation;
	}

	public void setSaturation(double saturation) {
		this.saturation = saturation;
	}

	public double getHue() {
		return hue;
	}

	public void setHue(double hue) {
		this.hue = hue;
	}

	public double getThreshold() {
		return threshold;
	}

	public void setThreshold(double threshold) {
		this.threshold = threshold;
	}

	public double getRange() {
		return range;
	}

	public void setRange(double range) {
		this.range = range;
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
		double[] aGss = new double[aNbVal+1];
		for(int g = 0;g <= aNbVal;g++) {
			double aX = 180.0*(2.0*g/(double)aNbVal-1.0);
			aGss[g] = Math.exp(-aX*aX/(2*range*range));
		}
		
		double[] aTh = new double[aNbVal+1];
		for(int g = 0;g <= aNbVal;g++) {
			double aX = 2.0*g/(double)aNbVal-1.0+1.0-threshold;
			aTh[g] = (1.0+Math.tanh(aX*5))/2.0;
		}
		
		for(int x = 0;x<aWidth;x++) {
			for(int y = 0;y<aHeight;y++) {
				int aARGBL = aDB[y*aWidth+x];
				double aAlpha = ((aARGBL>>24) & 0xFF)/255.0;//Alpha
				double r = ((aARGBL>>16) & 0xFF);//Red
				double g = ((aARGBL>>8) & 0xFF);//Green
				double b = (aARGBL & 0xFF);//Blue

			    //https://github.com/openjdk-mirror/jdk7u-jdk/blob/master/src/share/classes/java/awt/Color.java
				double hue, saturation, brightness;
				double cmax = (r > g) ? r : g;
		        if (b > cmax) cmax = b;
		        double cmin = (r < g) ? r : g;
		        if (b < cmin) cmin = b;

		        brightness = ((float) cmax) / 255.0f;
		        if (cmax != 0)
		            saturation = ((float) (cmax - cmin)) / ((float) cmax);
		        else
		            saturation = 0;
		        if (saturation == 0)
		            hue = 0;
		        else {
		            float redc = ((float) (cmax - r)) / ((float) (cmax - cmin));
		            float greenc = ((float) (cmax - g)) / ((float) (cmax - cmin));
		            float bluec = ((float) (cmax - b)) / ((float) (cmax - cmin));
		            if (r == cmax)
		                hue = bluec - greenc;
		            else if (g == cmax)
		                hue = 2.0f + redc - bluec;
		            else
		                hue = 4.0f + greenc - redc;
		            hue = hue / 6.0f;
		            if (hue < 0)
		                hue = hue + 1.0f;
		        }

		        double aWeight = 1.0;
		        switch (color) {
		        case 6://Yellow
		        	aWeight = aGss[(int)(aNbVal*((hue*360+120)%360)/360.0)]*aTh[(int)(saturation*aNbVal)];
		        	break;
		        case 5://Magenta
		        	aWeight = aGss[(int)(aNbVal*((hue*360+240)%360)/360.0)]*aTh[(int)(saturation*aNbVal)];
		        	break;
		        case 4://Cyan
		        	aWeight = aGss[(int)(aNbVal*((hue*360)%360)/360.0)]*aTh[(int)(saturation*aNbVal)];
		        	break;
		        case 2://Blue
		        	aWeight = aGss[(int)(aNbVal*((hue*360+300)%360)/360.0)]*aTh[(int)(saturation*aNbVal)];
		        	break;
		        case 1://Green
		        	aWeight = aGss[(int)(aNbVal*((hue*360+60)%360)/360.0)]*aTh[(int)(saturation*aNbVal)];
		        	break;
		        case 0://Red
		        	aWeight = aGss[(int)(aNbVal*((hue*360+180)%360)/360.0)]*aTh[(int)(saturation*aNbVal)];
		        	break;
		        case 3://All
		        default:
		        	break;
		        }
		        saturation = saturation*(1-aWeight)+saturation*aWeight*this.saturation;
		        saturation = (saturation >= 1.0f) ? 1.0f : ((saturation <= 0f) ? 0f : saturation);
		        brightness = brightness*(1-aWeight)+brightness*aWeight*this.brightness;
		    	brightness = (brightness >= 1.0f) ? 1.0f : ((brightness <= 0f) ? 0f : brightness);
		    	hue = hue + aWeight*this.hue/360.0f;

		        if (saturation == 0) {
		            r = g = b = (int) (brightness * 255.0f + 0.5f);
		        } else {
		        	double h = (hue - Math.floor(hue)) * 6.0f;
		        	double f = h - java.lang.Math.floor(h);
		        	double p = brightness * (1.0f - saturation);
		        	double q = brightness * (1.0f - saturation * f);
		        	double t = brightness * (1.0f - (saturation * (1.0f - f)));
		            switch ((int) h) {
		            case 0:
		                r = (int) (brightness * 255.0f + 0.5f);
		                g = (int) (t * 255.0f + 0.5f);
		                b = (int) (p * 255.0f + 0.5f);
		                break;
		            case 1:
		                r = (int) (q * 255.0f + 0.5f);
		                g = (int) (brightness * 255.0f + 0.5f);
		                b = (int) (p * 255.0f + 0.5f);
		                break;
		            case 2:
		                r = (int) (p * 255.0f + 0.5f);
		                g = (int) (brightness * 255.0f + 0.5f);
		                b = (int) (t * 255.0f + 0.5f);
		                break;
		            case 3:
		                r = (int) (p * 255.0f + 0.5f);
		                g = (int) (q * 255.0f + 0.5f);
		                b = (int) (brightness * 255.0f + 0.5f);
		                break;
		            case 4:
		                r = (int) (t * 255.0f + 0.5f);
		                g = (int) (p * 255.0f + 0.5f);
		                b = (int) (brightness * 255.0f + 0.5f);
		                break;
		            case 5:
		                r = (int) (brightness * 255.0f + 0.5f);
		                g = (int) (p * 255.0f + 0.5f);
		                b = (int) (q * 255.0f + 0.5f);
		                break;
		            }
		        }
		        
				int aPix = ((int)(aAlpha*255.0)) << 24
						| ((int)(r)) << 16
						| ((int)(g)) << 8
						| (int)(b)
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
		double[] aGss = new double[aNbVal+1];
		for(int g = 0;g <= aNbVal;g++) {
			double aX = 180.0*(2.0*g/(double)aNbVal-1.0);
			aGss[g] = Math.exp(-aX*aX/(2*range*range));
		}
		
		double[] aTh = new double[aNbVal+1];
		for(int g = 0;g <= aNbVal;g++) {
			double aX = 2.0*g/(double)aNbVal-1.0+1.0-threshold;
			aTh[g] = (1.0+Math.tanh(aX*5))/2.0;
		}
		
		for(int x = 0;x<aWidth;x++) {
			for(int y = 0;y<aHeight;y++) {
				float r = aDB[y*aWidth*4+x*4]*255;//Red
				float g = aDB[y*aWidth*4+x*4+1]*255;//Green
				float b = aDB[y*aWidth*4+x*4+2]*255;//Blue
				float aAlpha = aDB[y*aWidth*4+x*4+3];//Alpha

			    //https://github.com/openjdk-mirror/jdk7u-jdk/blob/master/src/share/classes/java/awt/Color.java
				double hue, saturation, brightness;
				double cmax = (r > g) ? r : g;
		        if (b > cmax) cmax = b;
		        double cmin = (r < g) ? r : g;
		        if (b < cmin) cmin = b;

		        brightness = ((float) cmax) / 255.0f;
		        if (cmax != 0)
		            saturation = ((float) (cmax - cmin)) / ((float) cmax);
		        else
		            saturation = 0;
		        if (saturation == 0)
		            hue = 0;
		        else {
		            float redc = ((float) (cmax - r)) / ((float) (cmax - cmin));
		            float greenc = ((float) (cmax - g)) / ((float) (cmax - cmin));
		            float bluec = ((float) (cmax - b)) / ((float) (cmax - cmin));
		            if (r == cmax)
		                hue = bluec - greenc;
		            else if (g == cmax)
		                hue = 2.0f + redc - bluec;
		            else
		                hue = 4.0f + greenc - redc;
		            hue = hue / 6.0f;
		            if (hue < 0)
		                hue = hue + 1.0f;
		        }

		        double aWeight = 1.0;
		        switch (color) {
		        case 6://Yellow
		        	aWeight = aGss[(int)(aNbVal*((hue*360+120)%360)/360.0)]*aTh[(int)(saturation*aNbVal)];
		        	break;
		        case 5://Magenta
		        	aWeight = aGss[(int)(aNbVal*((hue*360+240)%360)/360.0)]*aTh[(int)(saturation*aNbVal)];
		        	break;
		        case 4://Cyan
		        	aWeight = aGss[(int)(aNbVal*((hue*360)%360)/360.0)]*aTh[(int)(saturation*aNbVal)];
		        	break;
		        case 2://Blue
		        	aWeight = aGss[(int)(aNbVal*((hue*360+300)%360)/360.0)]*aTh[(int)(saturation*aNbVal)];
		        	break;
		        case 1://Green
		        	aWeight = aGss[(int)(aNbVal*((hue*360+60)%360)/360.0)]*aTh[(int)(saturation*aNbVal)];
		        	break;
		        case 0://Red
		        	aWeight = aGss[(int)(aNbVal*((hue*360+180)%360)/360.0)]*aTh[(int)(saturation*aNbVal)];
		        	break;
		        case 3://All
		        default:
		        	break;
		        }
		        saturation = saturation*(1-aWeight)+saturation*aWeight*this.saturation;
		        saturation = (saturation >= 1.0f) ? 1.0f : ((saturation <= 0f) ? 0f : saturation);
		        brightness = brightness*(1-aWeight)+brightness*aWeight*this.brightness;
		    	brightness = (brightness >= 1.0f) ? 1.0f : ((brightness <= 0f) ? 0f : brightness);
		    	hue = hue + aWeight*this.hue/360.0f;

		        if (saturation == 0) {
		            r = g = b = (int) (brightness * 255.0f + 0.5f);
		        } else {
		        	double h = (hue - Math.floor(hue)) * 6.0f;
		        	double f = h - java.lang.Math.floor(h);
		        	double p = brightness * (1.0f - saturation);
		        	double q = brightness * (1.0f - saturation * f);
		        	double t = brightness * (1.0f - (saturation * (1.0f - f)));
		            switch ((int) h) {
		            case 0:
		                r = (int) (brightness * 255.0f + 0.5f);
		                g = (int) (t * 255.0f + 0.5f);
		                b = (int) (p * 255.0f + 0.5f);
		                break;
		            case 1:
		                r = (int) (q * 255.0f + 0.5f);
		                g = (int) (brightness * 255.0f + 0.5f);
		                b = (int) (p * 255.0f + 0.5f);
		                break;
		            case 2:
		                r = (int) (p * 255.0f + 0.5f);
		                g = (int) (brightness * 255.0f + 0.5f);
		                b = (int) (t * 255.0f + 0.5f);
		                break;
		            case 3:
		                r = (int) (p * 255.0f + 0.5f);
		                g = (int) (q * 255.0f + 0.5f);
		                b = (int) (brightness * 255.0f + 0.5f);
		                break;
		            case 4:
		                r = (int) (t * 255.0f + 0.5f);
		                g = (int) (p * 255.0f + 0.5f);
		                b = (int) (brightness * 255.0f + 0.5f);
		                break;
		            case 5:
		                r = (int) (brightness * 255.0f + 0.5f);
		                g = (int) (p * 255.0f + 0.5f);
		                b = (int) (q * 255.0f + 0.5f);
		                break;
		            }
		        }
		        
				int aPix = ((int)(aAlpha*255.0)) << 24
						| ((int)(r)) << 16
						| ((int)(g)) << 8
						| (int)(b)
						;
		    
			    aFDB[y*aWidth*4+x*4]=r/255.0f;//Red
			    aFDB[y*aWidth*4+x*4+1]=g/255.0f;//Green
			    aFDB[y*aWidth*4+x*4+2]=b/255.0f;//Blue
			    aFDB[y*aWidth*4+x*4+3]=aAlpha;//Alpha
			}
		}
		
		return aFBI;
	}
}
