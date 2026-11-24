package com.cubaix.TDenlive.GUI;

import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.GC;
import org.eclipse.swt.graphics.Rectangle;
import org.eclipse.swt.widgets.Canvas;
import org.eclipse.swt.widgets.Composite;

import com.cubaix.TDenlive.TDenlive;

/**
 * Double slider: a start and an end thumb on one track, between 0 and max. A click moves the nearest thumb
 * there, a drag follows the mouse; the start stays before the end (minGap at least).
 * The listener gets the thumb moved (START / END) and whether the mouse is still down.
 */
public class RangeSlider extends Canvas {
	public static final int START = 0;
	public static final int END = 1;

	public interface Listener {
		void changed(int aThumb, boolean aDragging);
	}

	static final int THUMB_W = 9;
	static final int TRACK_H = 4;

	TDenlive tde;
	long max = 1;
	long minGap = 1;
	long start = 0;
	long end = 1;
	int dragged = -1;
	Listener listener = null;

	public RangeSlider(TDenlive aTDe, Composite aParent, Listener aListener) {
		super(aParent, SWT.DOUBLE_BUFFERED);
		tde = aTDe;
		listener = aListener;
		setBackground(aParent.getBackground());
		addPaintListener(e -> paint(e.gc));
		addListener(SWT.MouseDown, e -> {
			if(e.button != 1 || !getEnabled()) {
				return;
			}
			long aV = valueAt(e.x);
			//The nearest thumb (the end when both are at the same place and the click is on their right)
			long aDS = Math.abs(aV - start), aDE = Math.abs(aV - end);
			dragged = aDS < aDE || (aDS == aDE && aV < start) ? START : END;
			moveTo(aV, true);
		});
		addListener(SWT.MouseMove, e -> {
			if(dragged >= 0) {
				moveTo(valueAt(e.x), true);
			}
		});
		addListener(SWT.MouseUp, e -> {
			if(dragged >= 0) {
				int aThumb = dragged;
				dragged = -1;
				if(listener != null) {
					listener.changed(aThumb, false);
				}
			}
		});
	}

	@Override
	public org.eclipse.swt.graphics.Point computeSize(int aWHint, int aHHint, boolean aChanged) {
		return new org.eclipse.swt.graphics.Point(aWHint == SWT.DEFAULT ? 200 : aWHint, aHHint == SWT.DEFAULT ? 20 : aHHint);
	}

	/**
	 * Bounds and values (no event)
	 */
	public void setValues(long aMax, long aStart, long aEnd, long aMinGap) {
		max = Math.max(1, aMax);
		minGap = Math.max(0, Math.min(aMinGap, max));
		start = Math.max(0, Math.min(aStart, max - minGap));
		end = Math.max(start + minGap, Math.min(aEnd, max));
		redraw();
	}

	public long getStart() {
		return start;
	}

	public long getEnd() {
		return end;
	}

	public long getMax() {
		return max;
	}

	public boolean isDragging() {
		return dragged >= 0;
	}

	void moveTo(long aV, boolean aDragging) {
		if(dragged == START) {
			start = Math.max(0, Math.min(aV, end - minGap));
		}
		else {
			end = Math.min(max, Math.max(aV, start + minGap));
		}
		redraw();
		if(listener != null) {
			listener.changed(dragged, aDragging);
		}
	}

	int x0() {
		return THUMB_W/2 + 1;
	}

	int trackW() {
		return Math.max(1, getClientArea().width - THUMB_W - 2);
	}

	long valueAt(int aX) {
		double aF = (aX - x0())/(double)trackW();
		return Math.round(Math.max(0, Math.min(1, aF))*max);
	}

	int xOf(long aV) {
		return x0() + (int)Math.round(trackW()*aV/(double)max);
	}

	void paint(GC aGC) {
		Rectangle aR = getClientArea();
		int aMid = aR.height/2;
		boolean aOn = getEnabled();
		aGC.setBackground(tde.gui.colorsSwt.LIGHTGRAY);
		aGC.fillRectangle(x0(), aMid - TRACK_H/2, trackW(), TRACK_H);
		int aXS = xOf(start), aXE = xOf(end);
		aGC.setBackground(aOn ? tde.gui.colorsSwt.CUBAIX_BLUE : tde.gui.colorsSwt.GRAY);
		aGC.fillRectangle(aXS, aMid - TRACK_H/2, Math.max(1, aXE - aXS), TRACK_H);
		for(int aX : new int[] {aXS, aXE}) {
			aGC.setBackground(aOn ? tde.gui.colorsSwt.WHITE : tde.gui.colorsSwt.LIGHTGRAY);
			aGC.fillRectangle(aX - THUMB_W/2, 2, THUMB_W, aR.height - 4);
			aGC.setForeground(aOn ? tde.gui.colorsSwt.CUBAIX_BLUE : tde.gui.colorsSwt.GRAY);
			aGC.drawRectangle(aX - THUMB_W/2, 2, THUMB_W - 1, aR.height - 5);
		}
	}
}
