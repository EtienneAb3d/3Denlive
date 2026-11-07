package com.cubaix.TDenlive.medias;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.Vector;

import org.eclipse.swt.graphics.GC;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.graphics.Rectangle;

import com.cubaix.TDenlive.TDenlive;
import com.cubaix.TDenlive.GUI.Drawable;
import com.cubaix.TDenlive.ffmpeg.AudioReader;
import com.cubaix.TDenlive.utils.ImageUtils;
import com.cubaix.TDenlive.xml.XmlObject;
import com.cubaix.TDenlive.xml.XmlTag;

/**
 * A sound only media (music, voice...), to be placed on the audio time lines.
 * Its images (monitor) only show the waveform of the whole file.
 */
public class AudioMedia extends Media {
	String path = null;
	AudioReader reader = null;
	BufferedImage work = null;
	int lastProcessedRes = -1;
	boolean lastProcessedHdr = false;

	public AudioMedia(TDenlive aTDe) {
		super(aTDe);
	}

	@Override
	public int openProject(Vector<XmlObject> aOs, int o) throws Exception {
		for(;o < aOs.size();o++) {
			XmlObject aO = aOs.elementAt(o);
			if(aO instanceof XmlTag) {
				XmlTag aT = (XmlTag)aO;
				if("/AudioMedia".equalsIgnoreCase(aT.tagName)) {
					load(path);
					return o;
				}
				if("UID".equalsIgnoreCase(aT.tagName)) {
					o++;
					UID=Long.parseLong(aOs.elementAt(o).text);
				}
				if("path".equalsIgnoreCase(aT.tagName)) {
					o++;
					path=aOs.elementAt(o).text;
				}
				if("durationMS".equalsIgnoreCase(aT.tagName)) {
					o++;
					durationMS=Long.parseLong(aOs.elementAt(o).text);
				}
			}
		}
		return o;
	}

	@Override
	public java.util.Vector<String> getFilePaths() {
		java.util.Vector<String> aPaths = new java.util.Vector<String>();
		if(path != null) {
			aPaths.add(path);
		}
		return aPaths;
	}

	@Override
	public void saveProject(StringBuffer aSB) throws Exception {
		aSB.append("		<AudioMedia>\n");
		aSB.append("			<UID>" + UID + "</UID>\n");
		aSB.append("			<path>" + path + "</path>\n");
		aSB.append("			<durationMS>" + durationMS + "</durationMS>\n");
		aSB.append("		</AudioMedia>\n");
	}

	String findPath(String aPath) {
		if(new File(aPath).exists()) {
			return aPath;
		}
		if(tde.projectDir != null && new File(tde.projectDir+File.separatorChar+new File(aPath).getName()).exists()) {
			return tde.projectDir+File.separatorChar+new File(aPath).getName();
		}
		if(tde.lastDir != null && new File(tde.lastDir+File.separatorChar+new File(aPath).getName()).exists()) {
			return tde.lastDir+File.separatorChar+new File(aPath).getName();
		}
		return aPath;
	}

	public void load(String aPath) {
		path = findPath(aPath);
		fileName = new File(path).getName();
		reader = AudioReader.get(path, tde.getWorkCacheDir());
		long aDuration = reader.getDurationMS(10000);
		if(aDuration > 0) {
			durationMS = aDuration;
		}
		metaData = fileName+"\n"+path+"\n"+getDurationClock()+"\n";
		buildThumb();
	}

	@Override
	public AudioReader getAudioReader() {
		return reader;
	}

	@Override
	public void buildThumb() {
		BufferedImage aBI = ImageUtils.createImage(tde.config.thumbW, tde.config.thumbH);
		Graphics2D aG = (Graphics2D)aBI.getGraphics();
		aG.setColor(tde.gui.colorsAwt.WHITE);
		aG.fillRect(0, 0, aBI.getWidth(), aBI.getHeight());
		BufferedImage aIcon = tde.gui.imgsAwt.getIcon("sound.gif");
		if(aIcon != null) {
			int aS = Math.min(aBI.getWidth(), aBI.getHeight())*2/3;
			aG.drawImage(aIcon, (aBI.getWidth()-aS)/2, (aBI.getHeight()-aS)/2, aS, aS, null);
		}
		aG.dispose();
		setThumbAwt(aBI);
		setThumbSwt(ImageUtils.imageToSwt(tde.gui.display,aBI,false));
	}

	@Override
	public long getMaxTimePosMS() {
		return durationMS;
	}

	@Override
	public void reBuild(int aProcessingMode, long aTime) {
		int aRes = tde.config.processingResValues[aProcessingMode];
		boolean aHdr = tde.config.processingHdr[aProcessingMode];
		if(work != null && lastProcessedRes == aRes && lastProcessedHdr == aHdr && reader != null && reader.isDecoded()) {
			return;
		}
		//Waveform of the whole file, with the current position
		int aW = (int)(tde.config.outRatio*aRes);
		int aH = aRes;
		BufferedImage aBI = ImageUtils.createImage(aW, aH);
		Graphics2D aG = (Graphics2D)aBI.getGraphics();
		aG.setRenderingHint(RenderingHints.KEY_ANTIALIASING,RenderingHints.VALUE_ANTIALIAS_ON);
		aG.setColor(Color.BLACK);
		aG.fillRect(0, 0, aW, aH);
		if(reader != null && durationMS > 0) {
			aG.setColor(new Color(0x40,0xA0,0xFF));
			for(int x = 0;x < aW;x++) {
				float aPeak = reader.getPeak(x*durationMS/aW, (x+1)*durationMS/aW);
				if(aPeak <= 0) {
					continue;
				}
				int aPH = (int)(Math.sqrt(aPeak)*aH*0.45);
				aG.drawLine(x, aH/2-aPH, x, aH/2+aPH);
			}
		}
		aG.setColor(Color.WHITE);
		aG.setFont(tde.gui.fontsAwt.robotoBold.deriveFont(aH/20.0f));
		aG.drawString(fileName == null ? "" : fileName, aH/40, aH/40+aG.getFontMetrics().getAscent());
		aG.dispose();
		//Same image format as the other medias (HDR or not)
		work = ImageUtils.toRes(aBI, aRes, aHdr, false, false);
		lastProcessedRes = aRes;
		lastProcessedHdr = aHdr;
	}

	@Override
	public void setWorkLeft(BufferedImage aBI) {
		work = aBI;
	}

	@Override
	public BufferedImage getWorkLeft(int aProcessingMode, long aTime) {
		if(work == null) {
			reBuild(aProcessingMode, aTime);
		}
		return work;
	}

	@Override
	public void setWorkRight(BufferedImage aBI) {
		work = aBI;
	}

	@Override
	public BufferedImage getWorkRight(int aProcessingMode, long aTime) {
		return getWorkLeft(aProcessingMode, aTime);
	}

	@Override
	public BufferedImage getFinalLeft(int aProcessingMode, long aTime) {
		return getWorkLeft(aProcessingMode, aTime);
	}

	@Override
	public BufferedImage getFinalRight(int aProcessingMode, long aTime) {
		return getWorkLeft(aProcessingMode, aTime);
	}

	@Override
	public Rectangle drawSwt(GC aGC, int aX, int aY, Rectangle aClippingR) {
		int aW = 0;
		int aH = 0;

		aGC.setFont(tde.gui.fontsSwt.robotoBold);
		int aLinesH = aGC.getFontMetrics().getHeight();
		aGC.setFont(tde.gui.fontsSwt.roboto);
		aLinesH += 2 * aGC.getFontMetrics().getHeight();
		int aFullH = Math.max(tde.config.thumbH, aLinesH);

		aGC.setBackground(selState == 0 ? tde.gui.colorsSwt.WHITE : tde.gui.colorsSwt.BLUE_L60);
		aGC.setForeground(selState == 0 ? tde.gui.colorsSwt.CUBAIX_BLUE : tde.gui.colorsSwt.WHITE);
		aGC.fillRectangle(aClippingR.x, aY, aClippingR.width, aFullH);

		if(getThumbSwt() == null || fileName == null) {//While loading, not yet available
			return new Rectangle(aClippingR.x, aY, aClippingR.width, aFullH);
		}

		aGC.drawImage(getThumbSwt(), aX, aY);
		aW += tde.config.thumbW;
		aGC.fillRectangle(aX+aW, aY, aClippingR.width, aFullH);
		aW += 3;

		aGC.setFont(tde.gui.fontsSwt.robotoBold);
		aGC.drawText(fileName, aX+aW, aY);
		Point aTPF = aGC.stringExtent(fileName);

		aGC.setFont(tde.gui.fontsSwt.roboto);
		String aKind = tde.gui.lngs.get("MediaOpen.Audio");
		aGC.drawText(aKind, aX+aW, aY+aTPF.y);
		Point aTPS = aGC.stringExtent(aKind);

		String aClock = getDurationClock();
		aGC.drawText(aClock, aX+aW, aY+aTPF.y+aTPS.y);
		Point aTPC = aGC.stringExtent(aClock);

		aW += Math.max(Math.max(aTPF.x,aTPS.x),aTPC.x);
		aH += aTPF.y + aTPS.y + aTPC.y;

		aH = Math.max(tde.config.thumbH, aH);
		aW = Math.max(aW, aClippingR.width - aX);

		bounds = new Rectangle(aX, aY, aW, aH);

		if(selState > 0) {
			aGC.setForeground(tde.gui.colorsSwt.CUBAIX_BLUE);
			aGC.drawRectangle(aX, aY, aW-1, aH-1);
		}
		return bounds;
	}

	@Override
	public Rectangle drawAwt(Graphics2D aGC, int aX, int aY, Rectangle aClippingR) {
		//Not drawn with AWT (the media list is drawn with SWT)
		return bounds = new Rectangle(aX, aY, aClippingR.width, tde.config.thumbH);
	}

	@Override
	public boolean selectWidget(int aX, int aY) {
		return false;
	}

	@Override
	public void select(int aX, int aY, boolean aOutUnselect) {
		if(aX > 0 && aY > 0 //Negative values are used to unselect all (can't click outside the panel)
				&& bounds != null && bounds.contains(aX, aY)) {
			selState = Drawable.THUMBSELSTATE_SELECTED;
			tde.selected.medias.add(this);
		}
		else if(aOutUnselect) {
			selState = Drawable.THUMBSELSTATE_NONE;
		}
	}
}
