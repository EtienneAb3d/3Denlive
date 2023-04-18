package com.cubaix.TDenlive.medias;

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
		for(Clip aTL : clipList) {
			aTL.saveProject(aSB);
		}
		aSB.append("			</VideoTimeLine>\n");
	}

	public boolean addMedia(Media aMedia,boolean aOnlyAfterSelected) {
		Clip aClipNew = null;
		for(int c = 0;c < clipList.size();c++) {
			Clip aClip = clipList.elementAt(c);
			if(aClip.selState > 0) {
				aClipNew = new Clip(tde,aMedia);
				clipList.add(c,aClipNew);
				aClip.selState = 0;
				aClipNew.startTimeMS = aClip.startTimeMS+aClip.durationMS;
				aClipNew.selState = 1;
				tde.selected.clips.clear();
				tde.selected.clips.add(aClipNew);
				c++;
			}
		}
		if(!aOnlyAfterSelected || selState > 0) {
			aClipNew = new Clip(tde,aMedia);
			if(clipList.size() > 0) {
				aClipNew.startTimeMS = clipList.lastElement().startTimeMS+clipList.lastElement().durationMS;
			}
			clipList.add(aClipNew);
			aClipNew.selState = 1;
			tde.selected.clips.clear();
			tde.selected.clips.add(aClipNew);
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
		aGC.setClip(aClippingR.x,aClippingR.y,aClippingR.width,aClippingR.height);
		aGC.setColor(selState == 0 ? tde.gui.colorsAwt.WHITE : tde.gui.colorsAwt.BLUE_L60);
		aGC.fillRect(aClippingR.x, aY, aClippingR.width, tde.config.thumbH);
		aGC.setColor(selState == 0 ? tde.gui.colorsAwt.CUBAIX_BLUE : tde.gui.colorsAwt.WHITE);
		
		aGC.setFont(tde.gui.fontsAwt.robotoBold);
		Rectangle2D aFR = aGC.getFontMetrics().getStringBounds(name,aGC);
		aGC.drawString(name, aClippingR.x+10, aY+10+(int)aFR.getHeight());
		//Some else to write...
		//..
		
		isActiveCheckboxBounds = new Rectangle(aClippingR.x+tde.config.thumbW-20, aY+8, 16, 16);
		aGC.drawImage(tde.gui.imgsAwt.getIcon(isActive?"checkboxBlackChecked.gif":"checkboxBlackUnchecked.gif")
				, isActiveCheckboxBounds.x, isActiveCheckboxBounds.y,null);
		
		aGC.setColor(tde.gui.colorsAwt.CUBAIX_BLUE);
		aGC.drawLine(tde.config.thumbW, aY, tde.config.thumbW, aY+tde.config.thumbH);
		
		int aStart = aX+tde.config.thumbW;
		Rectangle aClippingNew = new Rectangle(aClippingR.x+tde.config.thumbW+2,Math.max(aClippingR.y,aY)
				,aClippingR.width-tde.config.thumbW, tde.config.thumbH);
		int aW = 0;
		for(Clip aC : clipList) {
			Rectangle aR = aC.drawAwt(aGC, aStart, aY, aClippingNew);
			if(aX+aW < aR.x+aR.width) {
				aW = aR.x+aR.width-aX;
			}
		}
		
		if(selState > 0) {
			aGC.setColor(tde.gui.colorsAwt.CUBAIX_BLUE);
			aGC.drawRect(aStart, aY, aW-1, tde.config.thumbH-1);
		}
		
		return bounds = new Rectangle(aX, aY, Math.max(aW+200,aClippingR.x+aClippingR.width-aX), tde.config.thumbH);
	}

	@Override
	public boolean selectWidget(int aX, int aY) {
		if(isActiveCheckboxBounds != null && isActiveCheckboxBounds.contains(aX,aY)) {
			isActive = !isActive;
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
