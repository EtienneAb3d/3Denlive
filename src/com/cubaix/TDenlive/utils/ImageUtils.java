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
		BufferedImage aBI = ImageIO.read(new File(aPath));
		if(aBI == null) {
			//A format ImageIO does not read (WebP, HEIC / HEIF, AVIF...): decoded by ffmpeg (HEIC tile grids included)
			aBI = loadWithFfmpeg(aPath);
		}
		return aBI;
	}

	/**
	 * First image of aPath decoded by ffmpeg (formats unknown to ImageIO: WebP, animated WebP, HEIC / HEIF, AVIF...), as an RGBA PNG
	 * through a pipe; null if ffmpeg can't read it
	 */
	static BufferedImage loadWithFfmpeg(String aPath) throws Exception {
		String aFfmpeg = com.cubaix.TDenlive.ffmpeg.Ffmpeg.ffmpegPath();
		if(aFfmpeg == null || !new File(aPath).isFile()) {
			return null;
		}
		Process aP = new ProcessBuilder(aFfmpeg, "-hide_banner", "-v", "error", "-i", aPath, "-frames:v", "1"
				, "-f", "image2pipe", "-c:v", "png", "-pix_fmt", "rgba", "-")
				.redirectError(ProcessBuilder.Redirect.DISCARD).start();
		byte[] aPng;
		try(java.io.InputStream aIS = aP.getInputStream()) {
			aPng = aIS.readAllBytes();
		}
		aP.waitFor();
		if(aPng.length == 0) {
			System.err.println("Image not readable (ImageIO, ffmpeg): "+aPath);
			return null;
		}
		BufferedImage aRead = ImageIO.read(new java.io.ByteArrayInputStream(aPng));
		if(aRead == null) {
			return null;
		}
		//Integer ARGB pixels, as the images read by ImageIO in the rest of the application
		BufferedImage aBI = new BufferedImage(aRead.getWidth(), aRead.getHeight(), BufferedImage.TYPE_INT_ARGB);
		java.awt.Graphics2D aG = aBI.createGraphics();
		aG.drawImage(aRead, 0, 0, null);
		aG.dispose();
		return aBI;
	}
	
	static public final void saveImage(String aPath,BufferedImage aBI) throws Exception {
		ImageIO.write(aBI, "png", new File(aPath));
	}
	
	/**
	 * aBI resized to the size of aRef if needed (e.g. a depth map of a video rounded to even sizes),
	 * of the same kind (HDR or not) as aRef
	 */
	static public final BufferedImage fitTo(BufferedImage aBI,BufferedImage aRef) {
		if(aBI == null || aRef == null || (aBI.getWidth() == aRef.getWidth() && aBI.getHeight() == aRef.getHeight())) {
			return aBI;
		}
		BufferedImage aOut = createImage(aRef.getWidth(), aRef.getHeight(), aRef.getRaster().getDataBuffer() instanceof java.awt.image.DataBufferFloat);
		java.awt.Graphics2D aG = aOut.createGraphics();
		aG.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION, java.awt.RenderingHints.VALUE_INTERPOLATION_BILINEAR);
		if(FloatDraw.isFloat(aBI)) {
			FloatDraw.draw(aG, aOut, aBI, 0, 0, aRef.getWidth(), aRef.getHeight());
		}
		else {
			aG.drawImage(aBI, 0, 0, aRef.getWidth(), aRef.getHeight(), null);
		}
		aG.dispose();
		return aOut;
	}

	/**
	 * The image shows nothing for a thumbnail: (nearly) black, uniform or transparent (32 x 32 samples, the
	 * transparent parts counted as black): the start of a video or of a montage fading in from black
	 */
	static public final boolean isEmptyImage(BufferedImage aBI) {
		if(aBI == null) {
			return true;
		}
		int aW = aBI.getWidth(), aH = aBI.getHeight();
		if(aW <= 0 || aH <= 0) {
			return true;
		}
		final int N = 32;
		double aSum = 0, aSum2 = 0, aMax = 0;
		int aN = 0;
		boolean aFloat = aBI.getRaster().getDataBuffer() instanceof DataBufferFloat;
		BufferedImage aI = aFloat ? FloatDraw.clamped(aBI) : aBI;
		for(int j = 0;j < N;j++) {
			int y = (int)((j+0.5)*aH/N);
			for(int i = 0;i < N;i++) {
				int x = (int)((i+0.5)*aW/N);
				int c = aI.getRGB(x, y);
				double a = (aI.getColorModel().hasAlpha() ? (c >>> 24) : 255)/255.0;
				double l = a*(0.299*((c >> 16) & 255)+0.587*((c >> 8) & 255)+0.114*(c & 255));
				aSum += l;
				aSum2 += l*l;
				aMax = Math.max(aMax, l);
				aN++;
			}
		}
		double aMean = aSum/aN;
		double aStd = Math.sqrt(Math.max(0, aSum2/aN-aMean*aMean));
		//Black (even with some noise or a faint start of a fade), or one color only
		return aMax < 40 || (aMean < 24 && aStd < 8) || aStd < 2;
	}

	static public final BufferedImage createImage(int aWidth,int aHeight,boolean aHdr) {
		if(aHdr) {
			return createHdr(aWidth, aHeight);
		}
		return createImage(aWidth, aHeight);
	}
	
	static public final BufferedImage createImage(int aWidth,int aHeight) {
		//Transparent black: a new Java array is already all 0
		return new BufferedImage(aWidth,aHeight,BufferedImage.TYPE_INT_ARGB);
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
		
		if(FloatDraw.isFloat(aBI)) {
			//Values above 1 kept (HDR video), else clamped
			FloatDraw.draw(g2, aHdrBI, aBI, 0, 0, aWidth, aHeight);
		}
		else {
			g2.drawImage(aBI,0,0,aWidth,aHeight,null);
		}
		g2.dispose();

		return aHdrBI;
	}
	
	/**
	 * White / gray checkerboard of aCell x aCell pixels squares, from the top left corner
	 */
	static public final void checkerBck(BufferedImage aBI,int aCell) {
		int aWidth = aBI.getWidth();
		int aHeight = aBI.getHeight();
		Graphics2D g2 = (Graphics2D)aBI.getGraphics();
		g2.setColor(Color.WHITE);
		g2.fillRect(0, 0, aWidth, aHeight);
		g2.setColor(Color.LIGHT_GRAY);
		for(int y = 0;y < aHeight;y += aCell) {
			for(int x = ((y/aCell)%2)*aCell;x < aWidth;x += 2*aCell) {
				g2.fillRect(x, y, aCell, aCell);
			}
		}
		g2.dispose();
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
		//32 bits lines without padding, each pixel high byte first (as ImageData.setPixel()): the ARGB ints copied as they are
		java.nio.ByteBuffer.wrap(aID.data).asIntBuffer().put(aDBF, 0, aWidth*aHeight);
		return new Image(aDisplay,aID);
	}
	
	static public final Image hdrToSwt(Display aDisplay,BufferedImage aBI) {
		int aWidth = aBI.getWidth();
		int aHeight = aBI.getHeight();
		ImageData aID = new ImageData(aWidth, aHeight, 32, new PaletteData(0xFF0000, 0xFF00, 0xFF));
		float[][] aDBF = ((DataBufferFloat)aBI.getRaster().getDataBuffer()).getBankData();
		//Bands of lines on all the cores (setPixel() of distinct pixels writes distinct bytes)
		Par.rows(aHeight, (aY0, aY1) -> {
		for(int y = aY0;y<aY1;y++) {
			for(int x = 0;x<aWidth;x++) {
				float aR = aDBF[0][y*aWidth*4+x*4];//Red
				float aG = aDBF[0][y*aWidth*4+x*4+1];//Green
				float aB = aDBF[0][y*aWidth*4+x*4+2];//Blue
				float aA = aDBF[0][y*aWidth*4+x*4+3];//Alpha
				//Clamped: the values above 1 (HDR) would wrap
				int aPix = FloatDraw.to8(aA) << 24
						| FloatDraw.to8(aR) << 16
						| FloatDraw.to8(aG) << 8
						| FloatDraw.to8(aB)
						;
				aID.setPixel(x, y, aPix);
			}
		}
		});
		return new Image(aDisplay,aID);
	}
}
