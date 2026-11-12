package com.cubaix.TDenlive.medias;

import org.eclipse.swt.SWT;
import org.eclipse.swt.widgets.Canvas;
import org.eclipse.swt.widgets.Menu;
import org.eclipse.swt.widgets.MenuItem;

import com.cubaix.TDenlive.ffmpeg.AudioReader;
import java.awt.BasicStroke;
import java.awt.Graphics2D;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;
import java.io.BufferedWriter;
import java.util.Vector;

import org.eclipse.swt.graphics.GC;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.graphics.Rectangle;

import com.cubaix.TDenlive.TDenlive;
import com.cubaix.TDenlive.GUI.Drawable;
import com.cubaix.TDenlive.xml.XmlObject;
import com.cubaix.TDenlive.xml.XmlTag;

public class VideoTimeLine extends Media {
	public Vector<Clip> clipList = new Vector<Clip>();
	String name = null;
	Rectangle isActiveCheckboxBounds = null;
	Rectangle soundIconBounds = null;
	//Arrows moving the whole line up / down in the stack (see TimeLineStack.moveLine()), null if not shown
	Rectangle lineUpBounds = null;
	Rectangle lineDownBounds = null;
	//Set by TimeLineStack before drawing: the line is not the top / bottom one of its kind
	boolean canMoveUp = false;
	boolean canMoveDown = false;
	public static final int SOUND_BAR_H = 16;
	//Sound of the clips of this line (play and rendering): the left video (the only one for a single file),
	//the right one of two files (else the single one), or none
	public static final int SOUND_LEFT = 0;
	public static final int SOUND_RIGHT = 1;
	public static final int SOUND_MUTE = 2;
	static final String[] SOUND_NAMES = {"left", "right", "mute"};
	public int soundSource = SOUND_LEFT;

	public VideoTimeLine(TDenlive aTDe,String aName) {
		super(aTDe);
		name = aName;
	}

	@Override
	public int openProject(Vector<XmlObject> aOs,int o) throws Exception{
		for(;o < aOs.size();o++) {
			XmlObject aO = aOs.elementAt(o);
			if(aO instanceof XmlTag) {
				XmlTag aT = (XmlTag)aO;
				if("/VideoTimeLine".equalsIgnoreCase(aT.tagName)
						//For compatibility
						|| "/TimeLine".equalsIgnoreCase(aT.tagName)) {
					return o;
				}
				if("isActive".equalsIgnoreCase(aT.tagName)) {
					o++;
					isActive = !"false".equalsIgnoreCase(aOs.elementAt(o).text);
				}
				if("soundMuted".equalsIgnoreCase(aT.tagName)) {
					//Projects before the sound source choice
					o++;
					soundSource = "true".equalsIgnoreCase(aOs.elementAt(o).text) ? SOUND_MUTE : SOUND_LEFT;
				}
				if("soundSource".equalsIgnoreCase(aT.tagName)) {
					o++;
					String aV = aOs.elementAt(o).text.trim();
					soundSource = SOUND_LEFT;
					for(int s = 0;s < SOUND_NAMES.length;s++) {
						if(SOUND_NAMES[s].equalsIgnoreCase(aV)) {
							soundSource = s;
						}
					}
				}
				if("Clip".equalsIgnoreCase(aT.tagName)) {
					Clip aC = new Clip(tde, null);
					clipList.add(aC);
					o = aC.openProject(aOs, o);
				}
			}
		}
		return o;
	}

	@Override
	public void saveProject(StringBuffer aSB) throws Exception {
		aSB.append("			<VideoTimeLine>\n");
		aSB.append("				<isActive>" + isActive + "</isActive>\n");
		aSB.append("				<soundSource>" + SOUND_NAMES[soundSource] + "</soundSource>\n");
		for(Clip aTL : clipList) {
			aTL.saveProject(aSB);
		}
		aSB.append("			</VideoTimeLine>\n");
	}

	/**
	 * New clip for a media placed on this time line
	 */
	protected Clip newClip(Media aMedia) {
		return new Clip(tde,aMedia);
	}
	
	protected Rectangle drawClipAwt(Clip aC,Graphics2D aGC, int aX, int aY, Rectangle aClippingR) {
		aC.soundRight = soundSource == SOUND_RIGHT;
		return aC.drawAwt(aGC, aX, aY, aClippingR, SOUND_BAR_H, isSoundMuted() || !isActive);
	}
	
	/**
	 * True if no clip of this time line overlaps [aStartMS, aEndMS[
	 */
	public boolean isFree(long aStartMS,long aEndMS,Clip aIgnored) {
		for(Clip aC : clipList) {
			if(aC == aIgnored) {
				continue;
			}
			if(aC.getStartTimeMS() < aEndMS && aC.getStartTimeMS()+aC.getDurationMS() > aStartMS) {
				return false;
			}
		}
		return true;
	}
	
	public boolean addMedia(Media aMedia,boolean aOnlyAfterSelected) {
		Clip aClipNew = null;
		//After the main selected clip (the last selected) if it is on this line
		Clip aMain = tde.selected.clips.size() > 0 ? tde.selected.clips.lastElement() : null;
		int aMainI = aMain == null ? -1 : clipList.indexOf(aMain);
		if(aMainI >= 0) {
			aClipNew = newClip(aMedia);
			aClipNew.startTimeMS = aMain.startTimeMS+aMain.durationMS;
			clipList.add(aMainI+1,aClipNew);
		}
		else if(!aOnlyAfterSelected || selState > 0) {
			aClipNew = newClip(aMedia);
			if(clipList.size() > 0) {
				aClipNew.startTimeMS = clipList.lastElement().startTimeMS+clipList.lastElement().durationMS;
			}
			clipList.add(aClipNew);
		}
		if(aClipNew != null) {
			//The new clip is the only selection
			tde.timeLineStack.selectClip(aClipNew, false);
		}
		return aClipNew != null;
	}
	
	public void addClip(Clip aClip) {
		for(int c = 0;c < clipList.size();c++) {
			Clip aC = clipList.elementAt(c);
			if(aC.getStartTimeMS() > aClip.getStartTimeMS()) {
				clipList.add(c, aClip);
				return;
			}
		}
		clipList.add(aClip);
	}

	public boolean containsClip(Clip aClip) {
		for(int c = 0;c < clipList.size();c++) {
			Clip aC = clipList.elementAt(c);
			if(aC == aClip) {
				return true;
			}
		}
		return false;
	}
	
	public Clip getClipByUID(long aUID) {
		for(int c = 0;c < clipList.size();c++) {
			Clip aC = clipList.elementAt(c);
			if(aC.UID == aUID) {
				return aC;
			}
		}
		return null;
	}

	public void trashClip(Clip aClip) {
		for(int c = 0;c < clipList.size();c++) {
			Clip aC = clipList.elementAt(c);
			if(aC == aClip) {
				clipList.remove(c);
				return;
			}
		}
	}
	
	public void trashAllClips() {
		clipList.clear();
	}
	
	public String getName() {
		return name;
	}

	public void setName(String name) {
		this.name = name;
	}

	@Override
	public Rectangle drawSwt(GC aGC, int aX, int aY, Rectangle aClippingR) {
		aGC.setClipping(aClippingR);
		aGC.setBackground(selState == 0 ? tde.gui.colorsSwt.WHITE : tde.gui.colorsSwt.BLUE_L60);
		aGC.setForeground(selState == 0 ? tde.gui.colorsSwt.CUBAIX_BLUE : tde.gui.colorsSwt.WHITE);
		aGC.fillRectangle(aClippingR.x, aY, aClippingR.width, tde.config.thumbH);
		
		aGC.setFont(tde.gui.fontsSwt.robotoBold);
		aGC.drawText(name, aClippingR.x+10, aY+10);
//		Point aTPN = aGC.stringExtent(name);
		//Some else to write...
		//..
		
		isActiveCheckboxBounds = new Rectangle(aClippingR.x+tde.config.thumbW-20, aY+8, 16, 16);
		aGC.drawImage(tde.gui.imgsSwt.getIcon(isActive?"checkboxBlackChecked.gif":"checkboxBlackUnchecked.gif")
				, isActiveCheckboxBounds.x, isActiveCheckboxBounds.y);
		
		aGC.setForeground(tde.gui.colorsSwt.CUBAIX_BLUE);
		aGC.drawLine(tde.config.thumbW, aY, tde.config.thumbW, aY+tde.config.thumbH);
		
		int aStart = aX+tde.config.thumbW;
		Rectangle aClippingNew = new Rectangle(aClippingR.x+tde.config.thumbW+2, aY, aClippingR.width-tde.config.thumbW, tde.config.thumbH);
		int aW = 0;
		for(Clip aC : clipList) {
			Rectangle aR = aC.drawSwt(aGC, aStart, aY, aClippingNew);
			if(aX+aW < aR.x+aR.width) {
				aW = aR.x+aR.width-aX;
			}
		}
		
		if(selState > 0) {
			aGC.setForeground(tde.gui.colorsSwt.CUBAIX_BLUE);
			aGC.drawRectangle(aStart, aY, aW-1, tde.config.thumbH-1);
		}
		
		return bounds = new Rectangle(aX, aY, Math.max(aW+200,aClippingR.x+aClippingR.width-aX), tde.config.thumbH);
	}

	@Override
	public Rectangle drawAwt(Graphics2D aGC, int aX, int aY, Rectangle aClippingR) {
		int aRowH = getRowH();
		aGC.setClip(aClippingR.x,aClippingR.y,aClippingR.width,aClippingR.height);
		aGC.setColor(selState == 0 ? tde.gui.colorsAwt.WHITE : tde.gui.colorsAwt.BLUE_L60);
		aGC.fillRect(aClippingR.x, aY, aClippingR.width, aRowH);
		aGC.setColor(selState == 0 ? tde.gui.colorsAwt.CUBAIX_BLUE : tde.gui.colorsAwt.WHITE);
		
		aGC.setFont(tde.gui.fontsAwt.robotoBold);
		Rectangle2D aFR = aGC.getFontMetrics().getStringBounds(name,aGC);
		aGC.drawString(name, aClippingR.x+10, aY+10+(int)aFR.getHeight());
		
		isActiveCheckboxBounds = new Rectangle(aClippingR.x+tde.config.thumbW-20, aY+8, 16, 16);
		aGC.drawImage(tde.gui.imgsAwt.getIcon(isActive?"checkboxBlackChecked.gif":"checkboxBlackUnchecked.gif")
				, isActiveCheckboxBounds.x, isActiveCheckboxBounds.y,null);
		if(hasSoundIcon()) {
			//Sound source of the clips of the line (left / right video, mute), left of the checkbox
			soundIconBounds = new Rectangle(isActiveCheckboxBounds.x-20, aY+8, 16, 16);
			aGC.drawImage(tde.gui.imgsAwt.getIcon("sound.gif")
					, soundIconBounds.x, soundIconBounds.y, 16, 16, null);
			if(isSoundMuted()) {
				aGC.setColor(tde.gui.colorsAwt.RED);
				aGC.setStroke(new BasicStroke(2));
				aGC.drawLine(soundIconBounds.x, soundIconBounds.y+soundIconBounds.height
						, soundIconBounds.x+soundIconBounds.width, soundIconBounds.y);
				aGC.setStroke(new BasicStroke(1));
			}
		}
		
		//Below the sound icon and the checkbox: move the whole line up / down
		lineUpBounds = lineDownBounds = null;
		if(canMoveUp) {
			lineUpBounds = new Rectangle(isActiveCheckboxBounds.x-20, aY+28, 16, 16);
			aGC.drawImage(tde.gui.imgsAwt.getIcon("arrowUp.png"), lineUpBounds.x, lineUpBounds.y, 16, 16, null);
		}
		if(canMoveDown) {
			lineDownBounds = new Rectangle(isActiveCheckboxBounds.x, aY+28, 16, 16);
			aGC.drawImage(tde.gui.imgsAwt.getIcon("arrowDown.png"), lineDownBounds.x, lineDownBounds.y, 16, 16, null);
		}
		
		aGC.setColor(tde.gui.colorsAwt.CUBAIX_BLUE);
		aGC.drawLine(tde.config.thumbW, aY, tde.config.thumbW, aY+aRowH);
		
		int aStart = aX+tde.config.thumbW;
		Rectangle aClippingNew = new Rectangle(aClippingR.x+tde.config.thumbW+2,Math.max(aClippingR.y,aY)
				,aClippingR.width-tde.config.thumbW, aRowH);
		int aW = 0;
		for(Clip aC : clipList) {
			Rectangle aR = drawClipAwt(aC, aGC, aStart, aY, aClippingNew);
			if(aX+aW < aR.x+aR.width) {
				aW = aR.x+aR.width-aX;
			}
		}
		
		if(selState > 0) {
			aGC.setColor(tde.gui.colorsAwt.CUBAIX_BLUE);
			aGC.drawRect(aStart, aY, aW-1, aRowH-1);
		}
		
		return bounds = new Rectangle(aX, aY, Math.max(aW+200,aClippingR.x+aClippingR.width-aX), aRowH);
	}

	/**
	 * Height of the line: images, and the sound bar below them
	 */
	public int getRowH() {
		return tde.config.thumbH+SOUND_BAR_H;
	}
	
	protected boolean hasSoundIcon() {
		return true;
	}
	
	/**
	 * Add the sound of the clips of the line to aOut (see Clip.mixAudio).
	 * Nothing if the line is off or muted.
	 */
	public void mixAudio(long aStartFrame,float[] aOut,int aNbFrames) {
		if(!isActive || isSoundMuted()) {
			return;
		}
		for(Clip aC : new Vector<Clip>(clipList)) {
			if(!aC.soundOn) {
				continue;
			}
			aC.soundRight = soundSource == SOUND_RIGHT;
			try {
				aC.mixAudio(aStartFrame, aOut, aNbFrames);
			}
			catch(Throwable t) {
				t.printStackTrace(System.err);
			}
		}
	}
	
	public boolean isSoundMuted() {
		return soundSource == SOUND_MUTE;
	}

	/**
	 * Sound read for a clip of this line (left or right video)
	 */
	public AudioReader soundReaderOf(Clip aC) {
		aC.soundRight = soundSource == SOUND_RIGHT;
		return aC.getSoundReader();
	}

	/**
	 * Popup menu of the sound icon: Left (default) / Right / Mute
	 */
	void chooseSoundSource() {
		Canvas aCanvas = tde.gui.timeLinesGUI.getCanvas();
		Menu aMenu = new Menu(aCanvas);
		String[] aKeys = {"TimeLine.SoundLeft", "TimeLine.SoundRight", "TimeLine.SoundMute"};
		for(int s = 0;s < aKeys.length;s++) {
			final int aSource = s;
			MenuItem aMI = new MenuItem(aMenu, SWT.RADIO);
			aMI.setText(tde.gui.lngs.get(aKeys[s]));
			aMI.setSelection(soundSource == s);
			aMI.addListener(SWT.Selection, e -> {
				if(aMI.getSelection() && soundSource != aSource) {
					soundSource = aSource;
					tde.gui.timeLinesGUI.redraw();
				}
			});
		}
		aMenu.setLocation(tde.gui.display.getCursorLocation());
		aMenu.setVisible(true);
	}

	/**
	 * True if something can be heard from this line
	 */
	public boolean hasSound() {
		if(!isActive || isSoundMuted()) {
			return false;
		}
		for(Clip aC : new Vector<Clip>(clipList)) {
			aC.soundRight = soundSource == SOUND_RIGHT;
			if(aC.soundOn && aC.hasSound()) {
				return true;
			}
		}
		return false;
	}

	/**
	 * Key (in the "Tip" language section) of the help message of the widget at this place, or null
	 */
	public String getWidgetTip(int aX, int aY) {
		if(isActiveCheckboxBounds != null && isActiveCheckboxBounds.contains(aX,aY)) {
			return (this instanceof AudioTimeLine) ? "AudioLineActive" : "LineActive";
		}
		if(hasSoundIcon() && soundIconBounds != null && soundIconBounds.contains(aX,aY)) {
			return "LineSound";
		}
		if(lineUpBounds != null && lineUpBounds.contains(aX,aY)) {
			return "LineUp";
		}
		if(lineDownBounds != null && lineDownBounds.contains(aX,aY)) {
			return "LineDown";
		}
		return null;
	}

	@Override
	public boolean selectWidget(int aX, int aY) {
		if(isActiveCheckboxBounds != null && isActiveCheckboxBounds.contains(aX,aY)) {
			isActive = !isActive;
			return true;
		}
		if(hasSoundIcon() && soundIconBounds != null && soundIconBounds.contains(aX,aY)) {
			chooseSoundSource();
			return true;
		}
		return false;
	}

	@Override
	public void select(int aX, int aY, boolean aOutUnselect) {
		boolean aClipSelected = false;
		for(Clip aC : clipList) {
			aC.select(aX, aY,aOutUnselect);
			aClipSelected |= aC.selState > 0;
		}
		if(!aClipSelected && aX > 0 && aY > 0 //Negative values are used to unselect all (can't click outside the panel)
				&& bounds != null && bounds.contains(aX, aY)) {
			selState = Drawable.THUMBSELSTATE_SELECTED;
			tde.selected.timeLines.add(this);
		}
		else if(aOutUnselect) {
			selState = Drawable.THUMBSELSTATE_NONE;
		}
	}

	@Override
	public long getMaxTimePosMS() {
		long aMax = 0;
		for(Clip aClip : clipList) {//In fact, should be only one
			long aMaxC = aClip.getMaxTimePosMS();
			if(aMaxC > aMax) {
				aMax = aMaxC;
			}
		}
		return aMax;
	}

	@Override
	public void reBuild(int aProcessingMode, long aTime) {
		for(Clip aClip : clipList) {//In fact, should be only one
			aClip.reBuild(aProcessingMode, aTime);
		}
	}

	@Override
	public void setWorkLeft(BufferedImage aBI) {
		// TODO Auto-generated method stub
		
	}

	@Override
	public void setWorkRight(BufferedImage aBI) {
		// TODO Auto-generated method stub
		
	}

	@Override
	public BufferedImage getWorkLeft(int aProcessingMode,long aTime) {
		return null;
	}

	@Override
	public BufferedImage getWorkRight(int aProcessingMode,long aTime) {
		return null;
	}

	@Override
	public BufferedImage getFinalLeft(int aProcessingMode,long aTime) {
		BufferedImage aBI = null;
		for(Clip aClip : clipList) {//In fact, should be only one, otherwise the last is overlapping
			BufferedImage aCBI = aClip.getFinalLeft(aProcessingMode, aTime);
			if(aCBI != null) {
				aBI = aCBI;
			}
		}
		return aBI;
	}

	@Override
	public BufferedImage getFinalRight(int aProcessingMode,long aTime) {
		BufferedImage aBI = null;
		for(Clip aClip : clipList) {//In fact, should be only one, otherwise the last is overlapping
			BufferedImage aCBI = aClip.getFinalRight(aProcessingMode, aTime);
			if(aCBI != null) {
				aBI = aCBI;
			}
		}
		return aBI;
	}

	@Override
	public float getAlpha(long aTime) {
		float aAlpha = -1.0f;
		for(Clip aClip : clipList) {//In fact, should be only one, otherwise the last is overlapping
			float aCA = aClip.getAlpha(aTime);
			if(aCA >= 0) {
				aAlpha = aCA;
			}
		}
		return aAlpha;
	}
	
	

}
