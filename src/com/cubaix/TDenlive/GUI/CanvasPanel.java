package com.cubaix.TDenlive.GUI;

import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.GC;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.graphics.Rectangle;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Canvas;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.ScrollBar;
import org.eclipse.swt.widgets.Slider;

import com.cubaix.TDenlive.TDenlive;
import com.cubaix.TDenlive.medias.Media;

public abstract class CanvasPanel {
	TDenlive tde = null;
	Canvas canvas = null;
	Point origin = null;
	Rectangle bounds = null;
	//Panel holding the canvas, and a bottom row (own horizontal scroll bar + tools) when ownHBar
	Composite wrapper = null;
	Composite bottomRow = null;
	Slider hSlider = null;

	public CanvasPanel(TDenlive aTDe,Composite aParent) {
		this(aTDe, aParent, false);
	}
	
	/**
	 * aOwnHBar: the horizontal scroll bar is a Slider in a bottom row (bottomRow),
	 * where the sub-class can add its own tools on the right
	 */
	public CanvasPanel(TDenlive aTDe,Composite aParent,boolean aOwnHBar) {
		tde = aTDe;
		if(aOwnHBar) {
			wrapper = new Composite(aParent, SWT.NONE);
			wrapper.setLayoutData(new GridData(GridData.FILL_BOTH));
			GridLayout aGL = new GridLayout(1, false);
			aGL.marginWidth = aGL.marginHeight = 0;
			aGL.verticalSpacing = 0;
			wrapper.setLayout(aGL);
			createCanvas(wrapper, false);
			bottomRow = new Composite(wrapper, SWT.NONE);
			bottomRow.setLayoutData(new GridData(GridData.FILL_HORIZONTAL));
			bottomRow.setBackground(tde.gui.colorsSwt.WHITE);
			GridLayout aBL = new GridLayout(1, false);
			aBL.marginWidth = aBL.marginHeight = 0;
			aBL.horizontalSpacing = 2;
			bottomRow.setLayout(aBL);
			hSlider = new Slider(bottomRow, SWT.HORIZONTAL);
			hSlider.setLayoutData(new GridData(GridData.FILL_HORIZONTAL));
			hSlider.setMinimum(0);
			hSlider.setMaximum(1);
			hSlider.setThumb(1);
			tde.gui.tip(hSlider, "HScroll");
			hSlider.addListener(SWT.Selection, e -> {
				origin.x = -hSlider.getSelection();
				redraw();
			});
		}
		else {
			createCanvas(aParent, true);
		}
	}
	
	public void redraw() {
		tde.gui.display.syncExec(new Runnable() {
			@Override
			public void run() {
				if(!canvas.isDisposed()) {
					canvas.redraw();
				}
			}
		});
	}
	
	void createCanvas(Composite aParent,boolean aHScroll) {
		canvas = new Canvas (aParent,
//				SWT.BORDER |
				SWT.NO_BACKGROUND 
				| SWT.FILL
				| SWT.V_SCROLL 
				| (aHScroll ? SWT.H_SCROLL : 0)
				);
		canvas.setLayoutData(new GridData(GridData.FILL_BOTH));
		origin = new Point(0, 0);
		bounds = new Rectangle(0, 0, 0, 0);
		
		final ScrollBar hBar = canvas.getHorizontalBar ();
		if(hBar != null) hBar.addListener (SWT.Selection, e -> {
			int hSelection = hBar.getSelection ();
			int destX = -hSelection - origin.x;
//			canvas.scroll (destX, 0, 0, 0, bounds.width, bounds.height, false);
			origin.x = -hSelection;
			//The content is drawn from origin: draw it again
			redraw();
		});
		
		final ScrollBar vBar = canvas.getVerticalBar ();
		vBar.addListener (SWT.Selection, e -> {
			int vSelection = vBar.getSelection ();
			int destY = -vSelection - origin.y;
//			canvas.scroll (0, destY, 0, 0, bounds.width, bounds.height, false);
			origin.y = -vSelection;
			redraw();
		});
		
		canvas.addListener (SWT.Resize,  e -> {
			adjustScrollBars();
		});
		
		canvas.addListener (SWT.Paint, e -> {
			draw(e.gc);
		});
	}
	
	void adjustScrollBars() {
		Rectangle client = canvas.getClientArea();
		if(client.width <= 0 || client.height <= 0) {
			//Not initialized ?
			return;
		}
		boolean aChangeMade = false;
		final ScrollBar hBar = canvas.getHorizontalBar ();
		final ScrollBar vBar = canvas.getVerticalBar ();
		if(hSlider != null) {
			//Own horizontal bar: follows origin.x (which may be changed by a zoom)
			int aMax = Math.max(bounds.width, client.width-origin.x);
			int aThumb = Math.max(1, Math.min(aMax, client.width));
			if(hSlider.getMaximum() != aMax || hSlider.getThumb() != aThumb) {
				hSlider.setValues(-origin.x, 0, aMax, aThumb, Math.max(1, aThumb/20), Math.max(1, aThumb*9/10));
			}
			else if(hSlider.getSelection() != -origin.x) {
				hSlider.setSelection(-origin.x);
			}
		}
		else if(hBar.getMaximum() != bounds.width) {
			aChangeMade = true;
			hBar.setMaximum (bounds.width);
		}
		if(vBar.getMaximum() != bounds.height) {
			aChangeMade = true;
			vBar.setMaximum (bounds.height);
		}
		if(hBar != null && hBar.getThumb() != Math.min(bounds.width, client.width)) {
			aChangeMade = true;
			hBar.setThumb(Math.min(bounds.width, client.width));
		}
		if(vBar.getThumb() != Math.min(bounds.height, client.height)) {
			aChangeMade = true;
			vBar.setThumb(Math.min(bounds.height, client.height));
		}
		int hPage = bounds.width - client.width;
		int vPage = bounds.height - client.height;
		int hSelection = hBar != null ? hBar.getSelection() : -origin.x;
		int vSelection = vBar.getSelection();
		if(hBar != null && hSelection >= hPage) {
			if(hPage <= 0) {
				hSelection = 0;
			}
			origin.x = -hSelection;
		}
		if(vSelection >= vPage) {
			if(vPage <= 0) {
				vSelection = 0;
			}
			origin.y = -vSelection;
		}
		if(aChangeMade) {
			//Be sure it's finally ok
			redraw();
		}
	}

	abstract void draw(GC aGC);
}
