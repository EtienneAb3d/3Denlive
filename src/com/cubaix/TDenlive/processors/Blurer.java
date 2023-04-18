package com.cubaix.TDenlive.processors;

import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.io.BufferedWriter;
import java.util.Vector;

import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.graphics.Rectangle;

import com.cubaix.TDenlive.TDenlive;
import com.cubaix.TDenlive.medias.Media;
import com.cubaix.TDenlive.xml.XmlObject;

public class Blurer extends Processor {
    private static final double SCALE = 1.8D;
    double blur = 0.5;

	public Blurer(TDenlive aTDe,Media aTargetMedia,double aBlur) {
		super(aTDe,aTargetMedia);
		blur = aBlur;
	}

	@Override
	public String getClassName() {return "Blurer";}

	@Override
	public int openProject(Vector<XmlObject> aOs, int o) throws Exception {
		return o;
	}

	@Override
	public void saveProject(StringBuffer aSB) throws Exception {}

	@Override
	void setDescr() {
	}

	@Override
	public Image process2Swt(Media aMedia, int aProcessingMode, Rectangle aTargetSize, long aTime) {
		return null;
	}

	@Override
	public void process2Awt(Media aMedia, int aProcessingMode, long aTime) {
		try {
			Thread aThL = new Thread(new Runnable() {
				@Override
				public void run() {
					BufferedImage aBIL = aMedia.getFinalLeft(aProcessingMode,aTime);
					if(aBIL == null) {
						//Not available? Possibly the AutoAligner processor
						return;
					}
					BufferedImage aProcessedBIL = tde.config.processingHdr[aProcessingMode]?
							processAwtHdr(aBIL):processAwtImage(aBIL);
					aMedia.setWorkLeft(aProcessedBIL);
				}
			});
			aThL.start();
			Thread aThR = new Thread(new Runnable() {
				@Override
				public void run() {
					BufferedImage aBIR = aMedia.getFinalRight(aProcessingMode,aTime);
					if(aBIR == null) {
						//Not available? Possibly the AutoAligner processor
						return;
					}
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
		int[] aPixels = ((DataBufferInt)aBI.getRaster().getDataBuffer()).getData();
		int[] aPixelsFiltered = filterRGBAll(aBI.getWidth(), aBI.getHeight(), aPixels);
		for(int p = 0;p<aPixels.length;p++) {
			aPixels[p] = aPixelsFiltered[p];
		}
		return aBI;
	}
	
	BufferedImage processAwtHdr(BufferedImage aBI) {
		//Do nothing for now
		return aBI;
	}


	public int[] filterRGBAll( int width, int height, int[] rgbPixels ){
		int[] newPixels = new int[height*width];
		double sum;
		int r, g, b, a;

		//keep borders
		try{
			for ( int col = 0; col < width; ++col ){
				newPixels[0 * width + col] = rgbPixels[0 * width + col];
				newPixels[(height - 1) * width + col] = rgbPixels[0 * width + col];
			}
		} catch (RuntimeException e) { throw e; }
		try {
			for ( int row = 1; row < height - 1; ++row ){
				//keep borders
				newPixels[row * width + 0] = rgbPixels[row * width + 0];
				newPixels[row * width + width - 1] = rgbPixels[row * width + width - 1];
				// The real pixels.
				for ( int col = 1; col < width - 1; ++col ){
					sum = (blur * ((rgbPixels[(row - 1) * width + col - 1]>>24&0xFF)
							+ (rgbPixels[(row - 1) * width + col]>>24&0xFF)
							+ (rgbPixels[(row - 1) * width + col + 1]>>24&0xFF)
							+ (rgbPixels[(row + 1) * width + col - 1]>>24&0xFF)
							+ (rgbPixels[(row + 1) * width + col]>>24&0xFF)
							+ (rgbPixels[(row + 1) * width + col + 1]>>24&0xFF)
							+ (rgbPixels[(row) * width + col - 1]>>24&0xFF)
							+ (rgbPixels[(row) * width + col + 1]>>24&0xFF)
							) / 8.0
							+ (rgbPixels[(row) * width + col]>>24&0xFF))
						   / (1.0+blur)
						 ;
					a = Math.min((int)sum,255);

					sum = (blur * ((rgbPixels[(row - 1) * width + col - 1]>>16&0xFF)
							+ (rgbPixels[(row - 1) * width + col]>>16&0xFF)
							+ (rgbPixels[(row - 1) * width + col + 1]>>16&0xFF)
							+ (rgbPixels[(row + 1) * width + col - 1]>>16&0xFF)
							+ (rgbPixels[(row + 1) * width + col]>>16&0xFF)
							+ (rgbPixels[(row + 1) * width + col + 1]>>16&0xFF)
							+ (rgbPixels[(row) * width + col - 1]>>16&0xFF)
							+ (rgbPixels[(row) * width + col + 1]>>16&0xFF)
							) / 8.0
							+ (rgbPixels[(row) * width + col]>>16&0xFF))
						   / (1.0+blur)
						 ;
					r = Math.min((int)sum,255);

					sum = (blur * ((rgbPixels[(row - 1) * width + col - 1]>>8&0xFF)
							+ (rgbPixels[(row - 1) * width + col]>>8&0xFF)
							+ (rgbPixels[(row - 1) * width + col + 1]>>8&0xFF)
							+ (rgbPixels[(row + 1) * width + col - 1]>>8&0xFF)
							+ (rgbPixels[(row + 1) * width + col]>>8&0xFF)
							+ (rgbPixels[(row + 1) * width + col + 1]>>8&0xFF)
							+ (rgbPixels[(row) * width + col - 1]>>8&0xFF)
							+ (rgbPixels[(row) * width + col + 1]>>8&0xFF)
							) / 8.0
							+ (rgbPixels[(row) * width + col]>>8&0xFF))
						   / (1.0+blur)
						 ;
					g = Math.min((int)sum,255);
					
					sum = (blur * ((rgbPixels[(row - 1) * width + col - 1]&0xFF)
							+ (rgbPixels[(row - 1) * width + col]&0xFF)
							+ (rgbPixels[(row - 1) * width + col + 1]&0xFF)
							+ (rgbPixels[(row + 1) * width + col - 1]&0xFF)
							+ (rgbPixels[(row + 1) * width + col]&0xFF)
							+ (rgbPixels[(row + 1) * width + col + 1]&0xFF)
							+ (rgbPixels[(row) * width + col - 1]&0xFF)
							+ (rgbPixels[(row) * width + col + 1]&0xFF)
							) / 8.0
							+ (rgbPixels[(row) * width + col]&0xFF))
						   / (1.0+blur)
						 ;
					b = Math.min((int)sum,255);
							
					newPixels[row * width + col] = (a << 24) | (r << 16) | (g << 8) | b;
				}
			}
		} 
		catch (RuntimeException e)
		{ throw e; }
		return newPixels;
	}

	public float[] filterRGBAllHdr( int width, int height, float[] rgbPixels ){
		float[] newPixels = new float[height*width*4];
		double sum;
		float r, g, b, a;

		//keep borders
		try{
			for ( int col = 0; col < width; ++col ){
				newPixels[0 * width * 4 + col * 4] = rgbPixels[0 * width * 4 + col * 4];
				newPixels[0 * width * 4 + col * 4 + 1] = rgbPixels[0 * width * 4 + col * 4 + 1];
				newPixels[0 * width * 4 + col * 4 + 2] = rgbPixels[0 * width * 4 + col * 4 + 2];
				newPixels[0 * width * 4 + col * 4 + 3] = rgbPixels[0 * width * 4 + col * 4 + 3];
				newPixels[(height - 1) * width * 4 + col * 4] = rgbPixels[0 * width * 4 + col * 4];
				newPixels[(height - 1) * width * 4 + col * 4 + 1] = rgbPixels[0 * width * 4 + col * 4 + 1];
				newPixels[(height - 1) * width * 4 + col * 4 + 2] = rgbPixels[0 * width * 4 + col * 4 + 2];
				newPixels[(height - 1) * width * 4 + col * 4 + 3] = rgbPixels[0 * width * 4 + col * 4 + 3];
			}
		} catch (RuntimeException e) { throw e; }
		try {
			for ( int row = 1; row < height - 1; ++row ){
				//keep borders
				newPixels[row * width + 0] = rgbPixels[row * width + 0];
				newPixels[row * width + width - 1] = rgbPixels[row * width + width - 1];
				// The real pixels.
				for ( int col = 1; col < width - 1; ++col ){
					sum = (blur * (rgbPixels[(row - 1) * width * 4 + (col - 1) * 4]
							+ rgbPixels[(row - 1) * width * 4 + col * 4]
							+ rgbPixels[(row - 1) * width * 4  + (col + 1) * 4]
							+ rgbPixels[(row + 1) * width * 4  + (col - 1) * 4]
							+ rgbPixels[(row + 1) * width * 4  + col * 4]
							+ rgbPixels[(row + 1) * width * 4  + (col + 1) * 4]
							+ rgbPixels[(row) * width * 4  + (col - 1) * 4]
							+ rgbPixels[(row) * width * 4  + (col + 1) * 4]
							) / 8.0
							+ rgbPixels[(row) * width * 4  + col * 4])
						   / (1.0+blur)
						 ;
					r = (float)Math.min(sum,1);

					sum = (blur * (rgbPixels[(row - 1) * width * 4 + (col - 1) * 4 + 1]
							+ rgbPixels[(row - 1) * width * 4 + col * 4 + 1]
							+ rgbPixels[(row - 1) * width * 4  + (col + 1) * 4 + 1]
							+ rgbPixels[(row + 1) * width * 4  + (col - 1) * 4 + 1]
							+ rgbPixels[(row + 1) * width * 4  + col * 4 + 1]
							+ rgbPixels[(row + 1) * width * 4  + (col + 1) * 4 + 1]
							+ rgbPixels[(row) * width * 4  + (col - 1) * 4 + 1]
							+ rgbPixels[(row) * width * 4  + (col + 1) * 4 + 1]
							) / 8.0
							+ rgbPixels[(row) * width * 4  + col * 4 + 1])
						   / (1.0+blur)
						 ;
					g = (float)Math.min(sum,1);
					
					sum = (blur * (rgbPixels[(row - 1) * width * 4 + (col - 1) * 4 + 2]
							+ rgbPixels[(row - 1) * width * 4 + col * 4 + 2]
							+ rgbPixels[(row - 1) * width * 4  + (col + 1) * 4 + 2]
							+ rgbPixels[(row + 1) * width * 4  + (col - 1) * 4 + 2]
							+ rgbPixels[(row + 1) * width * 4  + col * 4 + 2]
							+ rgbPixels[(row + 1) * width * 4  + (col + 1) * 4 + 2]
							+ rgbPixels[(row) * width * 4  + (col - 1) * 4 + 2]
							+ rgbPixels[(row) * width * 4  + (col + 1) * 4 + 2]
							) / 8.0
							+ rgbPixels[(row) * width * 4  + col * 4 + 2])
						   / (1.0+blur)
						 ;
					b = (float)Math.min(sum,1);
							
					sum = (blur * (rgbPixels[(row - 1) * width * 4 + (col - 1) * 4 + 3]
							+ rgbPixels[(row - 1) * width * 4 + col * 4 + 3]
							+ rgbPixels[(row - 1) * width * 4  + (col + 1) * 4 + 3]
							+ rgbPixels[(row + 1) * width * 4  + (col - 1) * 4 + 3]
							+ rgbPixels[(row + 1) * width * 4  + col * 4 + 3]
							+ rgbPixels[(row + 1) * width * 4  + (col + 1) * 4 + 3]
							+ rgbPixels[(row) * width * 4  + (col - 1) * 4 + 3]
							+ rgbPixels[(row) * width * 4  + (col + 1) * 4 + 3]
							) / 8.0
							+ rgbPixels[(row) * width * 4  + col * 4 + 3])
						   / (1.0+blur)
						 ;
					a = (float)Math.min(sum,1);

					newPixels[row * width * 4 + col * 4] = r;
					newPixels[row * width * 4 + col * 4 + 1] = g;
					newPixels[row * width * 4 + col * 4 + 2] = b;
					newPixels[row * width * 4 + col * 4 + 3] = a;
				}
			}
		} 
		catch (RuntimeException e)
		{ throw e; }
		return newPixels;
	}
}
