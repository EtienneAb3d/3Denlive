package com.cubaix.TDenlive.medias;

import java.awt.image.BufferedImage;
import java.io.BufferedWriter;
import java.util.HashMap;
import java.util.Vector;

import org.eclipse.swt.graphics.GC;
import org.eclipse.swt.graphics.Rectangle;

import com.cubaix.TDenlive.TDenlive;
import com.cubaix.TDenlive.ffmpeg.VideoReader;
import com.cubaix.TDenlive.xml.XmlObject;

public class StereoMovie extends StereoImage {
	VideoReader leftReader = null;
	VideoReader rightReader = null;
	
	public StereoMovie(TDenlive aTDe) {
		super(aTDe);
	}

	@Override
	public int openProject(Vector<XmlObject> aOs, int o) throws Exception {
		return super.openProject(aOs, o,"StereoMovie");
	}

	@Override
	public void saveProject(StringBuffer aSB) throws Exception {
		super.saveProject(aSB,"StereoMovie");
	}

	@Override
	BufferedImage loadImage(String aPath,int aProcessingMode,long aTime,boolean aIsLeft) throws Exception {
		BufferedImage aBI = null;
		if(aIsLeft) {
			if(leftReader == null) {
				leftReader = new VideoReader(tde,aPath,null);
				durationMS = leftReader.durationMS;
			}
			aBI = leftReader.getImage(aProcessingMode,aTime);
		}
		else if(!aIsLeft) {
			if(rightReader == null) {
				rightReader = new VideoReader(tde,aPath,leftReader);
			}
			aBI = rightReader.getImage(aProcessingMode,aTime);
		}
		if(origWidth <= 0) {
			origWidth = aBI.getWidth();
			origHeight = aBI.getHeight();
		}
		return aBI;
	}
	
	@Override
	void reloadOrig(int aProcessingMode,long aTime) throws Exception {
		synchronized (this) {
			switch(origStereo) {
			case 0:
				loadImageLR(pathL,pathR,aProcessingMode,aTime);
				break;
			case 1:
				loadImageX(pathL,aProcessingMode,aTime);
				break;
			case 2:
				loadImageP(pathL,aProcessingMode,aTime);
				break;
			}
		}
	}
	
	@Override
	public void reBuild(int aProcessingMode,long aTime) {
		synchronized (this) {
			//Always rebuild from file
			try {
				loadedLeft = loadedRight = workLeft = workRight = null;
				reloadOrig(aProcessingMode,aTime);
				rotate();
			}
			catch(Throwable t) {
				t.printStackTrace(System.err);
			}
			getWorkLeft(aProcessingMode,aTime);
			getWorkRight(aProcessingMode,aTime);
			lastProcessedRes = tde.config.processingResValues[aProcessingMode];
			lastProcessedHdr = tde.config.processingHdr[aProcessingMode];
		}
	}
	
	@Override
	public BufferedImage getWorkLeft(int aProcessingMode,long aTime) {
		synchronized (this) {
			return super.getWorkLeft(aProcessingMode,aTime);
		}
	}

	@Override
	public BufferedImage getWorkRight(int aProcessingMode,long aTime) {
		synchronized (this) {
			return super.getWorkRight(aProcessingMode,aTime);
		}
	}


	@Override
	public void loadImageLR(String aPathL, String aPathR,int aProcessingMode, long aTime) throws Exception {
		synchronized (this) {
			super.loadImageLR(aPathL, aPathR,aProcessingMode, aTime);
		}
	}

	@Override
	public void loadImageX(String aPath,int aProcessingMode, long aTime) throws Exception {
		synchronized (this) {
			super.loadImageX(aPath,aProcessingMode,aTime);
		}
	}

	@Override
	public void loadImageP(String aPath,int aProcessingMode, long aTime) throws Exception {
		synchronized (this) {
			super.loadImageP(aPath,aProcessingMode, aTime);
		}
	}

	@Override
	public void loadImageMPO(String aPath) throws Exception {
		synchronized (this) {
			super.loadImageMPO(aPath);
		}
	}

	@Override
	public void rotate() {
		synchronized (this) {
			super.rotate();
		}
	}

	@Override
	public void setWorkLeft(BufferedImage aBI) {
		synchronized (this) {
			super.setWorkLeft(aBI);
		}
	}

	@Override
	public void setWorkRight(BufferedImage aBI) {
		synchronized (this) {
			super.setWorkRight(aBI);
		}
	}

	@Override
	public BufferedImage getFinalLeft(int aProcessingMode, long aTime) {
		synchronized (this) {
			return super.getFinalLeft(aProcessingMode, aTime);
		}
	}

	@Override
	public BufferedImage getFinalRight(int aProcessingMode, long aTime) {
		synchronized (this) {
			return super.getFinalRight(aProcessingMode, aTime);
		}
	}

	@Override
	public void buildThumb() {
		synchronized (this) {
			super.buildThumb();
		}
	}

	@Override
	public Rectangle drawSwt(GC aGC, int aX, int aY, Rectangle aClippingR) {
		synchronized (this) {
			return super.drawSwt(aGC, aX, aY, aClippingR);
		}
	}
}
