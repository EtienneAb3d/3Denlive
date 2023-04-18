package com.cubaix.TDenlive.GUI;

import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.GC;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.graphics.Rectangle;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.widgets.Canvas;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.ScrollBar;

import com.cubaix.TDenlive.TDenlive;
import com.cubaix.TDenlive.medias.Media;

public abstract class CanvasPanel {
	TDenlive tde = null;
	Canvas canvas = null;
	Point origin = null;
	Rectangle bounds = null;

	public CanvasPanel(TDenlive aTDe,Composite aParent) {
		tde = aTDe;
		createCanvas(aParent);
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
	
	void createCanvas(Composite aParent) {
		canvas = new Canvas (aParent,
//				SWT.BORDER |
				SWT.NO_BACKGROUND 
				| SWT.FILL
				| SWT.V_SCROLL 
				| SWT.H_SCROLL
				);
		canvas.setLayoutData(new GridData(GridData.FILL_BOTH));
		origin = new Point(0, 0);
		bounds = new Rectangle(0, 0, 0, 0);
		
		final ScrollBar hBar = canvas.getHorizontalBar ();
		hBar.addListener (SWT.Selection, e -> {
			int hSelection = hBar.getSelection ();
			int destX = -hSelection - origin.x;
//			canvas.scroll (destX, 0, 0, 0, bounds.width, bounds.height, false);
			origin.x = -hSelection;
		});
		
		final ScrollBar vBar = canvas.getVerticalBar ();
		vBar.addListener (SWT.Selection, e -> {
			int vSelection = vBar.getSelection ();
			int destY = -vSelection - origin.y;
//			canvas.scroll (0, destY, 0, 0, bounds.width, bounds.height, false);
			origin.y = -vSelection;
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
		if(hBar.getMaximum() != bounds.width) {
			aChangeMade = true;
			hBar.setMaximum (bounds.width);
		}
		if(vBar.getMaximum() != bounds.height) {
			aChangeMade = true;
			vBar.setMaximum (bounds.height);
		}
		if(hBar.getThumb() != Math.min(bounds.width, client.width)) {
			aChangeMade = true;
			hBar.setThumb(Math.min(bounds.width, client.width));
		}
		if(vBar.getThumb() != Math.min(bounds.height, client.height)) {
			aChangeMade = true;
			vBar.setThumb(Math.min(bounds.height, client.height));
		}
		int hPage = bounds.width - client.width;
		int vPage = bounds.height - client.height;
		int hSelection = hBar.getSelection();
		int vSelection = vBar.getSelection();
		if(hSelection >= hPage) {
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
