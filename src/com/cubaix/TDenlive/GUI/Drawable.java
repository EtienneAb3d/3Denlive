package com.cubaix.TDenlive.GUI;

import java.awt.Graphics2D;

import org.eclipse.swt.graphics.GC;
import org.eclipse.swt.graphics.Rectangle;

import com.cubaix.TDenlive.TDenlive;
import com.cubaix.TDenlive.medias.Media;

public abstract class Drawable {
	static final public int THUMBSELSTATE_NONE = 0;
	static final public int THUMBSELSTATE_SELECTED = 1;
	
	public TDenlive tde = null;
	
	public Rectangle bounds = null;
	public int selState = Drawable.THUMBSELSTATE_NONE;
	public boolean isActive = true;
	
	public Drawable(TDenlive aTDe) {
		tde = aTDe;
	}
	
	public abstract Rectangle drawSwt(GC aGC,int aX, int aY,Rectangle aClippingR);
	public abstract Rectangle drawAwt(Graphics2D aGC,int aX, int aY,Rectangle aClippingR);
	public abstract boolean selectWidget(int aX,int aY);
	public abstract void select(int aX,int aY,boolean aOutUnselect);
}
