package com.cubaix.TDenlive.medias;

import java.awt.image.BufferedImage;
import java.util.Vector;

import com.cubaix.TDenlive.TDConfig;
import com.cubaix.TDenlive.TDenlive;
import com.cubaix.TDenlive.ffmpeg.AudioReader;
import com.cubaix.TDenlive.utils.ImageUtils;
import com.cubaix.TDenlive.xml.XmlObject;

/**
 * Provisional rendering of a montage not computed yet (its rendering canceled, failed or asked for later): a
 * StereoMovie for the montage machinery (StereoImage.montageUID, out of date: no signature, replaced by
 * Montage.replaceMedia() at its next rendering), without any file: its image « Montage not computed » with the name
 * of the montage is drawn in memory (image(), both views alike, at the default rendering resolution), drawn again
 * when the language, the name of the montage or the size change; its duration is that of the montage; no sound.
 * Saved as <MontagePlaceholder> (no path).
 */
public class MontagePlaceholder extends StereoMovie {
	//Last image drawn and what it shows (title, name, size)
	BufferedImage image = null;
	String imageKey = null;

	public MontagePlaceholder(TDenlive aTDe) {
		super(aTDe);
	}

	/** The provisional rendering of aM */
	public MontagePlaceholder(TDenlive aTDe, Montage aM) {
		super(aTDe);
		montageUID = aM.UID;
		montageSig = null;
		montageStale = true;
		libName = aM.displayName(aTDe);
		origStereo = 2;
		try {
			loadImageP(null, TDConfig.PROCESSING_MODE_RENDER, 0);
		}
		catch(Throwable t) {
			t.printStackTrace(System.err);
		}
	}

	@Override
	public int openProject(Vector<XmlObject> aOs, int o) throws Exception {
		return openProject(aOs, o, "MontagePlaceholder");
	}

	@Override
	public void saveProject(StringBuffer aSB) throws Exception {
		saveProject(aSB, "MontagePlaceholder");
	}

	Montage montage() {
		return tde.montageByUID(montageUID);
	}

	@Override
	public long getDurationMS() {
		Montage aM = montage();
		if(aM != null) {
			durationMS = Math.max(1000, aM.stack.getMaxTimePosMS());
		}
		return durationMS;
	}

	@Override
	public long getMaxTimePosMS() {
		return getDurationMS();
	}

	/**
	 * The image: both views side by side are the same (at the depth of the screen), redrawn only when its title (the
	 * language), the name of the montage or the rendering size changed
	 */
	synchronized BufferedImage image() {
		int aH = Math.max(2, tde.config.processingResValues[TDConfig.PROCESSING_MODE_RENDER]/2*2);
		int aW = Math.max(2, (int)Math.round(tde.config.outRatio*aH)/2*2);
		String aTitle = tde.gui != null && tde.gui.lngs != null ? tde.gui.lngs.get("Montage.NotComputed") : "Montage not computed";
		Montage aM = montage();
		String aName = aM != null ? aM.displayName(tde) : (libName != null ? libName : "");
		String aKey = aTitle+"|"+aName+"|"+aW+"x"+aH;
		if(image != null && aKey.equals(imageKey)) {
			return image;
		}
		BufferedImage aBI = ImageUtils.createImage(aW, aH);
		java.awt.Graphics2D aG = aBI.createGraphics();
		aG.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING, java.awt.RenderingHints.VALUE_ANTIALIAS_ON);
		aG.setRenderingHint(java.awt.RenderingHints.KEY_TEXT_ANTIALIASING, java.awt.RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
		aG.setColor(new java.awt.Color(40, 40, 48));
		aG.fillRect(0, 0, aW, aH);
		//Diagonal stripes, as a placeholder
		aG.setColor(new java.awt.Color(52, 52, 62));
		aG.setStroke(new java.awt.BasicStroke(aH/24f));
		for(int x = -aH;x < aW+aH;x += Math.max(1, aH/8)) {
			aG.drawLine(x, aH, x+aH, 0);
		}
		aG.setColor(new java.awt.Color(230, 230, 235));
		java.awt.Font aF = new java.awt.Font(java.awt.Font.SANS_SERIF, java.awt.Font.BOLD, Math.max(10, aH/12));
		aG.setFont(aF);
		java.awt.FontMetrics aFM = aG.getFontMetrics();
		aG.drawString(aTitle, (aW-aFM.stringWidth(aTitle))/2, aH/2-aFM.getDescent());
		aG.setColor(new java.awt.Color(170, 170, 185));
		aG.setFont(aF.deriveFont(java.awt.Font.PLAIN, Math.max(8, aH/20f)));
		aFM = aG.getFontMetrics();
		aG.drawString(aName, (aW-aFM.stringWidth(aName))/2, aH/2+aFM.getAscent()+aH/40);
		aG.dispose();
		image = aBI;
		imageKey = aKey;
		return image;
	}

	@Override
	public void loadImageP(String aPath, int aProcessingMode, long aTime) throws Exception {
		synchronized(this) {
			origStereo = 2;
			pathL = pathR = null;
			BufferedImage aI = image();
			loadedLeft = aI;
			loadedRight = aI;
			origWidth = 2*aI.getWidth();
			origHeight = aI.getHeight();
			origRes = aI.getHeight();
			origRatio = aI.getWidth()/(double)aI.getHeight();
			fileName = libName;
			buildThumb();
		}
	}

	@Override
	public void loadImageLR(String aPathL, String aPathR, int aProcessingMode, long aTime) throws Exception {
		loadImageP(null, aProcessingMode, aTime);
	}

	@Override
	public void loadImageX(String aPath, int aProcessingMode, long aTime) throws Exception {
		loadImageP(null, aProcessingMode, aTime);
	}

	//Image key of the current thumbnails
	String thumbImageKey = null;

	/** The thumbnails (AWT and SWT) of the current image, made again when it changed */
	@Override
	public void buildThumb() {
		synchronized(this) {
			BufferedImage aI = image();
			if(hasThumb() && imageKey != null && imageKey.equals(thumbImageKey)) {
				return;
			}
			if(loadedLeft != aI) {
				loadedLeft = loadedRight = aI;
				workLeft = workRight = null;
			}
			//StereoMovie.buildThumb() keeps a thumbnail of the same orientation: made again
			thumbKey = null;
			//Marked first: StereoImage.buildThumb() calls getThumbAwt() (no loop)
			thumbImageKey = imageKey;
			super.buildThumb();
		}
	}

	@Override
	public BufferedImage getThumbAwt() {
		buildThumb();
		return super.getThumbAwt();
	}

	@Override
	public org.eclipse.swt.graphics.Image getThumbSwt() {
		buildThumb();
		return super.getThumbSwt();
	}

	@Override
	public BufferedImage getThumbAwtAt(long aTimeMS) {
		return getThumbAwt();
	}

	@Override
	public AudioReader getAudioReader() {
		return null;
	}

	@Override
	public AudioReader getAudioReader(boolean aRight) {
		return null;
	}

	@Override
	public Vector<String> getFilePaths() {
		return new Vector<String>();
	}

	@Override
	public BufferedImage[] textureViews(long aTime) {
		BufferedImage aI = image();
		return new BufferedImage[] {aI, aI};
	}

	@Override
	public BufferedImage[] textureViews(long aTime, String aStream) {
		return textureViews(aTime);
	}

	@Override
	public void copyWorkCopies(String aFromDir, String aToDir) {
	}
}
