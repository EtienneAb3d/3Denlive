package com.cubaix.TDenlive.utils;

import java.nio.ByteBuffer;

import org.eclipse.swt.graphics.GC;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.graphics.ImageData;
import org.eclipse.swt.graphics.PaletteData;
import org.eclipse.swt.graphics.Rectangle;
import org.eclipse.swt.widgets.Display;

/**
 * An image shown by SWT made of horizontal bands, each its own SWT Image: they are converted at the same time on all
 * the cores (SWT converts the pixels of an ImageData one by one, on one thread), and drawn one under the other
 */
public final class SwtStrips {
	final Image[] images;
	//Top line of each band
	final int[] ys;
	public final int width;
	public final int height;

	SwtStrips(Image[] aImages, int[] aYs, int aWidth, int aHeight) {
		images = aImages;
		ys = aYs;
		width = aWidth;
		height = aHeight;
	}

	/**
	 * A single image as one band
	 */
	public static SwtStrips of(Image aI) {
		Rectangle aB = aI.getBounds();
		return new SwtStrips(new Image[] {aI}, new int[] {0}, aB.width, aB.height);
	}

	/**
	 * The ARGB pixels aPix (aW x aH, line by line) in bands converted on all the cores. aAlpha: with the alpha of the
	 * pixels (as ImageData.setAlpha((int)(alpha/255.0*255))), else opaque (as ImageUtils.imageToSwt())
	 */
	public static SwtStrips of(Display aDisplay, int[] aPix, int aW, int aH, boolean aAlpha) {
		int aNb = Par.bandsFor(aH);
		Image[] aImages = new Image[aNb];
		int[] aYs = new int[aNb];
		try {
			Par.each(aNb, b -> {
				int aY0 = b*aH/aNb;
				aYs[b] = aY0;
				aImages[b] = band(aDisplay, aPix, aW, aY0, (b+1)*aH/aNb, aAlpha);
			});
		}
		catch(RuntimeException | Error e) {
			for(Image aI : aImages) {
				if(aI != null) {
					aI.dispose();
				}
			}
			throw e;
		}
		return new SwtStrips(aImages, aYs, aW, aH);
	}

	/**
	 * The whole image as one SWT Image (same conversion as of())
	 */
	public static Image image(Display aDisplay, int[] aPix, int aW, int aH, boolean aAlpha) {
		return band(aDisplay, aPix, aW, 0, aH, aAlpha);
	}

	/**
	 * Lines [aY0, aY1[ of aPix as an SWT Image
	 */
	static Image band(Display aDisplay, int[] aPix, int aW, int aY0, int aY1, boolean aAlpha) {
		int aBH = aY1-aY0;
		ImageData aID = new ImageData(aW, aBH, 32, new PaletteData(0xFF0000, 0xFF00, 0xFF));
		//32 bits lines without padding, each pixel high byte first (as ImageData.setPixel())
		ByteBuffer.wrap(aID.data).asIntBuffer().put(aPix, aY0*aW, aBH*aW);
		if(aAlpha) {
			byte[] aA = new byte[aW*aBH];
			for(int p = 0;p < aA.length;p++) {
				aA[p] = (byte)(int)(((aPix[aY0*aW+p]>>24) & 0xFF)/255.0*255);
			}
			aID.alphaData = aA;
		}
		return new Image(aDisplay, aID);
	}

	public Rectangle getBounds() {
		return new Rectangle(0, 0, width, height);
	}

	/**
	 * Drawn with its top left corner at (aX, aY)
	 */
	public void draw(GC aGC, int aX, int aY) {
		for(int b = 0;b < images.length;b++) {
			aGC.drawImage(images[b], aX, aY+ys[b]);
		}
	}

	/**
	 * Pixel value at (aX, aY), as ImageData.getPixel() of the whole image
	 */
	public int getPixel(int aX, int aY) {
		int b = images.length-1;
		while(b > 0 && aY < ys[b]) {
			b--;
		}
		return images[b].getImageData().getPixel(aX, aY-ys[b]);
	}

	public boolean isDisposed() {
		return images[0].isDisposed();
	}

	public void dispose() {
		for(Image aI : images) {
			if(!aI.isDisposed()) {
				aI.dispose();
			}
		}
	}
}
