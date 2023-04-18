package com.cubaix.TDenlive.utils;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.Transparency;
import java.awt.color.ColorSpace;
import java.awt.image.BufferedImage;
import java.awt.image.ColorModel;
import java.awt.image.ComponentColorModel;
import java.awt.image.DataBuffer;
import java.awt.image.DataBufferFloat;
import java.awt.image.DataBufferInt;
import java.awt.image.PixelInterleavedSampleModel;
import java.awt.image.Raster;
import java.awt.image.SampleModel;
import java.awt.image.WritableRaster;
import java.io.File;

import javax.imageio.ImageIO;
import javax.swing.text.GapContent;

import org.eclipse.swt.graphics.GC;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.graphics.ImageData;
import org.eclipse.swt.graphics.PaletteData;
import org.eclipse.swt.widgets.Display;

import com.cubaix.TDenlive.TDConfig;

public class ImageUtils {
	static public final BufferedImage loadImage(String aPath) throws Exception {
		if(aPath.toLowerCase().endsWith(".pgm")) {
			return PGMIO.read(aPath);
		}
		return ImageIO.read(new File(aPath));
	}
	
	static public final void saveImage(String aPath,BufferedImage aBI) throws Exception {
		ImageIO.write(aBI, "png", new File(aPath));
	}
	
	static public final BufferedImage createImage(int aWidth,int aHeight,boolean aHdr) {
		if(aHdr) {
			return createHdr(aWidth, aHeight);
		}
		return createImage(aWidth, aHeight);
	}
	
	static public final BufferedImage createImage(int aWidth,int aHeight) {
		BufferedImage aBI = new BufferedImage(aWidth,aHeight,BufferedImage.TYPE_INT_ARGB);
        int[] aDBI = ((DataBufferInt)aBI.getRaster().getDataBuffer()).getData();
        for(int p = 0;p<aDBI.length;p++) {
        	aDBI[p] = 0;
        }
        return aBI;
	}

	static public final BufferedImage createHdr(int aWidth,int aHeight) {
        int bands = 4; // 4 bands for ARGB, 3 for RGB etc
        int[] bandOffsets = {0, 1, 2, 3}; // length == bands, 0 == R, 1 == G, 2 == B and 3 == A

        // Create a TYPE_FLOAT sample model (specifying how the pixels are stored)
        SampleModel sampleModel = new PixelInterleavedSampleModel(DataBuffer.TYPE_FLOAT, aWidth, aHeight, bands, aWidth  * bands, bandOffsets);
        // ...and data buffer (where the pixels are stored)
        DataBufferFloat buffer = new DataBufferFloat(aWidth * aHeight * bands);

        // Wrap it in a writable raster
        WritableRaster raster = Raster.createWritableRaster(sampleModel, buffer, null);

        // Create a color model compatible with this sample model/raster (TYPE_FLOAT)
        // Note that the number of bands must equal the number of color components in the 
        // color space (3 for RGB) + 1 extra band if the color model contains alpha 
        ColorSpace colorSpace = ColorSpace.getInstance(ColorSpace.CS_sRGB);
        ColorModel colorModel = new ComponentColorModel(colorSpace
        		, true//hasAlpha
        		, false//isAlphaMultiplied
        		, Transparency.TRANSLUCENT, DataBuffer.TYPE_FLOAT);

        // And finally create an image with this raster
        return new BufferedImage(colorModel, raster, colorModel.isAlphaPremultiplied(), null);
	}
	
	static public final BufferedImage toRes(BufferedImage aBI,int aH,boolean aHdr,boolean aFastMode,boolean aAddChess) {
		double aZoom = aH/(double)aBI.getHeight();
        int aWidth = (int)(aBI.getWidth()*aZoom);
        int aHeight = aH;
        
        BufferedImage aHdrBI = createImage(aWidth, aHeight,aHdr);
        
		Graphics2D g2 = (Graphics2D)aHdrBI.getGraphics();
		if(!aFastMode) {
			g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING,RenderingHints.VALUE_ANTIALIAS_ON);
			g2.setRenderingHint(RenderingHints.KEY_RENDERING,RenderingHints.VALUE_RENDER_QUALITY);
		}
		
		if(aAddChess) {
			chessBck(aHdrBI);
		}
		
		g2.drawImage(aBI,0,0,aWidth,aHeight,null);
		g2.dispose();

		return aHdrBI;
	}
	
	static public final void chessBck(BufferedImage aBI) {
		int aWidth = aBI.getWidth();
		int aHeight = aBI.getHeight();
		Graphics2D g2 = (Graphics2D)aBI.getGraphics();
		g2.setColor(Color.LIGHT_GRAY);
		g2.fillRect(0, 0, aWidth, aHeight);
		g2.setColor(Color.GRAY);
		for(int x = 0;x <= aWidth;x+=5) {
			for(int y = 0;y<= aHeight;y+=5) {
				if(((x+y)%2) == 0) {
					continue;
				}
				g2.fillRect(x, y, 5, 5);
			}
		}
		g2.dispose();
	}
	
	static public final Image imageToSwt(Display aDisplay,BufferedImage aBI,boolean aHdr) {
		if(aHdr) {
			return hdrToSwt(aDisplay, aBI);
		}
		return imageToSwt(aDisplay, aBI);
	}
	
	static public final Image imageToSwt(Display aDisplay,BufferedImage aBI) {
		int aWidth = aBI.getWidth();
		int aHeight = aBI.getHeight();
		ImageData aID = new ImageData(aWidth, aHeight, 32, new PaletteData(0xFF0000, 0xFF00, 0xFF));
		int[] aDBF = ((DataBufferInt)aBI.getRaster().getDataBuffer()).getData();
		for(int x = 0;x<aWidth;x++) {
			for(int y = 0;y<aHeight;y++) {
				int aARGB = aDBF[y*aWidth+x];
				int aR = (aARGB>>16) & 0xFF;//Red
				int aG = (aARGB>>8) & 0xFF;//Green
				int aB = aARGB & 0xFF;//Blue
				int aA = (aARGB>>24) & 0xFF;//Alpha
				int aPix = (aA & 0xFF) << 24
						| (aR & 0xFF) << 16
						| (aG & 0xFF) << 8
						| aB & 0xFF
						;
				aID.setPixel(x, y, aPix);
			}
		}
		return new Image(aDisplay,aID);
	}
	
	static public final Image hdrToSwt(Display aDisplay,BufferedImage aBI) {
		int aWidth = aBI.getWidth();
		int aHeight = aBI.getHeight();
		ImageData aID = new ImageData(aWidth, aHeight, 32, new PaletteData(0xFF0000, 0xFF00, 0xFF));
		float[][] aDBF = ((DataBufferFloat)aBI.getRaster().getDataBuffer()).getBankData();
		for(int x = 0;x<aWidth;x++) {
			for(int y = 0;y<aHeight;y++) {
				float aR = aDBF[0][y*aWidth*4+x*4];//Red
				float aG = aDBF[0][y*aWidth*4+x*4+1];//Green
				float aB = aDBF[0][y*aWidth*4+x*4+2];//Blue
				float aA = aDBF[0][y*aWidth*4+x*4+3];//Alpha
				int aPix = (((int)(aA*255)) & 0xFF) << 24
						| (((int)(aR*255)) & 0xFF) << 16
						| (((int)(aG*255)) & 0xFF) << 8
						| (((int)(aB*255)) & 0xFF)
						;
				aID.setPixel(x, y, aPix);
			}
		}
		return new Image(aDisplay,aID);
	}
}
