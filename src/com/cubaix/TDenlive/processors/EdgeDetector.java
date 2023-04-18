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

public class EdgeDetector extends Processor {
    private static final double SCALE = 1.8D;

	public EdgeDetector(TDenlive aTDe,Media aTargetMedia) {
		super(aTDe,aTargetMedia);
	}

	@Override
	public String getClassName() {return "EdgeDetector";}

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
		long sum1, sum2;
		double sum;
		int r, g, b;

		// First and last rows are black.
		try{
			for ( int col = 0; col < width; ++col ){
				newPixels[0 * width + col] = 0xff000000;
				newPixels[(height - 1) * width + col] = 0xff000000;
			}
		} catch (RuntimeException e) { throw e; }
		try {
			for ( int row = 1; row < height - 1; ++row ){
				// First and last columns are black too.
				newPixels[row * width + 0] = 0xff000000;
				newPixels[row * width + width - 1] = 0xff000000;
				// The real pixels.
				for ( int col = 1; col < width - 1; ++col ){
					sum1 =
							rgbPixels[(row - 1) * width + col + 1]>>16&0xFF -
						rgbPixels[(row - 1) * width + col - 1]>>16&0xFF +
					2 * (
							rgbPixels[row * width + col + 1] >>16&0xFF -
							rgbPixels[row * width + col - 1] >>16&0xFF
							) +
					rgbPixels[(row + 1) * width + col + 1]>>16&0xFF -
					rgbPixels[(row + 1) * width + col - 1] >>16&0xFF;
					sum2 = (
							rgbPixels[(row + 1) * width + col - 1]>>16&0xFF +
							2 *  rgbPixels[(row + 1) * width + col]>>16&0xFF +
							rgbPixels[(row + 1) * width + col + 1]>>16&0xFF
							) - (
									rgbPixels[(row - 1) * width + col - 1]>>16&0xFF +
									2 *  rgbPixels[(row - 1) * width + col]>>16&0xFF +
									rgbPixels[(row - 1) * width + col + 1]>>16&0xFF
									);
					sum = Math.sqrt( (double) ( sum1*sum1 + sum2*sum2 ) ) / SCALE;
					r = Math.min( (int) sum, 255 );

					sum1 =
							rgbPixels[(row - 1) * width + col + 1]>>8&0xFF -
							rgbPixels[(row - 1) * width + col - 1]>>8&0xFF +
							2 * (
									rgbPixels[row * width + col + 1]>>8&0xFF -
									rgbPixels[row * width + col - 1]>>8&0xFF
									) +
							rgbPixels[(row + 1) * width + col + 1]>>8&0xFF -
							rgbPixels[(row + 1) * width + col - 1]>>8&0xFF;
					sum2 = (
									rgbPixels[(row + 1) * width + col - 1]>>8&0xFF +
									2 *  rgbPixels[(row + 1) * width + col]>>8&0xFF +
									rgbPixels[(row + 1) * width + col + 1]>>8&0xFF
									) - (
											rgbPixels[(row - 1) * width + col - 1]>>8&0xFF +
											2 *  rgbPixels[(row - 1) * width + col]>>8&0xFF +
											rgbPixels[(row - 1) * width + col + 1]>>8&0xFF
											);
					
					sum = Math.sqrt( (double) ( sum1*sum1 + sum2*sum2 ) ) / SCALE;
					g = Math.min( (int) sum, 255 );

					sum1 =
									rgbPixels[(row - 1) * width + col + 1]&0xFF -
									rgbPixels[(row - 1) * width + col - 1]&0xFF +
									2 * (
											rgbPixels[row * width + col + 1]&0xFF -
											rgbPixels[row * width + col - 1]&0xFF
											) +
									rgbPixels[(row + 1) * width + col + 1]&0xFF -
									rgbPixels[(row + 1) * width + col - 1]&0xFF;
					sum2 = (
									rgbPixels[(row + 1) * width + col - 1]&0xFF +
									2 *  rgbPixels[(row + 1) * width + col]&0xFF +
									rgbPixels[(row + 1) * width + col + 1]&0xFF
									) - (
											rgbPixels[(row - 1) * width + col - 1]&0xFF +
											2 *  rgbPixels[(row - 1) * width + col]&0xFF +
											rgbPixels[(row - 1) * width + col + 1]&0xFF
											);
					sum = Math.sqrt( (double) ( sum1*sum1 + sum2*sum2 ) ) / SCALE;
					b = Math.min( (int) sum, 255 );

					r = b = g = (r+g+b)/3;

					newPixels[row * width + col] =
									0xff000000 | ( r << 16 ) | ( g << 8 ) | b;
				}
			}
		} 
		catch (RuntimeException e)
		{ throw e; }
		return newPixels;
	}

}
