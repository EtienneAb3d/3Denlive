package com.cubaix.TDenlive.GUI.widgets;

import org.eclipse.swt.SWT;
import org.eclipse.swt.events.KeyEvent;
import org.eclipse.swt.events.KeyListener;
import org.eclipse.swt.events.MouseEvent;
import org.eclipse.swt.events.MouseListener;
import org.eclipse.swt.events.MouseMoveListener;
import org.eclipse.swt.events.MouseTrackListener;
import org.eclipse.swt.events.MouseWheelListener;
import org.eclipse.swt.events.PaintEvent;
import org.eclipse.swt.events.PaintListener;
import org.eclipse.swt.graphics.GC;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.graphics.Rectangle;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.widgets.Composite;

import com.cubaix.TDenlive.TDenlive;

public abstract class ScaleBar extends Widget {
	int min = -10;
	int zero = 0;
	int max = 10;
	int init = 0;
	int selection = 0;
	boolean isFocused = false;
	int lineX = 0;
	int lineW = 100;
	boolean isMouseDown = false;
	
	public ScaleBar(TDenlive aTDe, Composite aParent,int aMin,int aZero,int aMax,int aInit) {
		super(aTDe, aParent);
		min = aMin;
		zero = aZero;
		max = aMax;
		selection = init = aInit;
		createContents();
	}
	
	public abstract void change(int aValue);

	public int getSelection() {
		return selection;
	}

	public void setSelection(int aSelection) {
		selection = aSelection;
		if(container.isDisposed()) {
			//Concurrent deletion?
			return;
		}
		container.redraw();
	}
	
	public int getIncrement() {
		return (int)((max-min)/1000.0);
	}

	public int getPageIncrement() {
		return (int)((max-min)/100.0);
	}
	
	@Override
	void createContents() {
		GridData aGD = new GridData(GridData.FILL_HORIZONTAL);
		aGD.heightHint = 20;
		container.setLayoutData(aGD);
		container.addPaintListener(new PaintListener() {
			@Override
			public void paintControl(PaintEvent e) {
				draw(e.gc);
			}
		});
		container.addMouseListener(new MouseListener() {
			@Override
			public void mouseUp(MouseEvent e) {
				isMouseDown = false;
			}
			@Override
			public void mouseDown(MouseEvent e) {
				isMouseDown = true;
				selectFromPos(e.x);
			}
			@Override
			public void mouseDoubleClick(MouseEvent e) {
				selectFromPos(e.x);
			}
		});
		container.addMouseMoveListener(new MouseMoveListener() {
			@Override
			public void mouseMove(MouseEvent e) {
				if(isMouseDown) {//Dragging
					selectFromPos(e.x);
				}
			}
		});
		container.addMouseTrackListener(new MouseTrackListener() {
			@Override
			public void mouseHover(MouseEvent e) {
			}
			@Override
			public void mouseExit(MouseEvent e) {
				isFocused = false;
				container.redraw();
			}
			@Override
			public void mouseEnter(MouseEvent e) {
				isFocused = true;
				container.setFocus();
				container.redraw();
			}
		});
		container.addMouseWheelListener(new MouseWheelListener() {
			@Override
			public void mouseScrolled(MouseEvent e) {
				if((e.stateMask & SWT.SHIFT) > 0 || (e.stateMask & SWT.CTRL) > 0) {
					selection += (int)(e.count*getPageIncrement());
					if(selection < min) {
						selection = min;
					}
					if(selection > max) {
						selection = max;
					}
					change(selection);
				}
				else {
					selection += (int)(e.count*getIncrement());
					if(selection < min) {
						selection = min;
					}
					if(selection > max) {
						selection = max;
					}
					change(selection);
				}
				container.redraw();
			}
		});
		container.addKeyListener(new KeyListener() {
			@Override
			public void keyReleased(KeyEvent e) {}
			@Override
			public void keyPressed(KeyEvent e) {
				int aMove = 0;
				if(e.keyCode == SWT.ARROW_LEFT || e.keyCode == SWT.ARROW_DOWN) {
					aMove = -1;
				}
				if(e.keyCode == SWT.ARROW_RIGHT || e.keyCode == SWT.ARROW_UP) {
					aMove = 1;
				}
				if((e.stateMask & SWT.SHIFT) > 0 || (e.stateMask & SWT.CTRL) > 0) {
					selection += (int)(aMove*getPageIncrement());
					if(selection < min) {
						selection = min;
					}
					if(selection > max) {
						selection = max;
					}
					change(selection);
				}
				else {
					selection += (int)(aMove*getIncrement());
					if(selection < min) {
						selection = min;
					}
					if(selection > max) {
						selection = max;
					}
					change(selection);
				}
				container.redraw();
			}
		});
	}
	
	void selectFromPos(int aX) {
		selection = (int)(min+(max-min)*(aX - lineX)/(double)lineW);
		if(selection < min) {
			selection = min;
		}
		if(selection > max) {
			selection = max;
		}
		change(selection);
		container.redraw();
	}
	
	void draw(GC aGC) {
		Rectangle aCR = container.getClientArea();
//		aGC.setBackground(tde.gui.colors.CUBAIX_PINK);
//		aGC.fillRectangle(aCR);
		aGC.setForeground(tde.gui.colorsSwt.CUBAIX_BLUE);
		lineX = aCR.x+10;
		int aLineY = aCR.y+aCR.height/2;
		lineW = aCR.width-20;
		aGC.drawLine(lineX, aLineY, lineX+lineW, aLineY);
		aGC.drawLine(lineX, aLineY-5, lineX, aLineY+5);
//		aGC.drawLine(getX2Pos(min/2), aLineY-2, getX2Pos(min/2), aLineY);
		aGC.drawLine(getX2Pos(0), aLineY-5, getX2Pos(0), aLineY);
//		aGC.drawLine(getX2Pos(max/2), aLineY-2, getX2Pos(max/2), aLineY);
		aGC.drawLine(lineX+lineW, aLineY-5, lineX+lineW, aLineY+5);//Max
		int aBulletX = getX2Pos(selection);
		aGC.setBackground(tde.gui.colorsSwt.CUBAIX_BLUE);
		aGC.fillOval(aBulletX-2, aLineY-2, 5, 5);
		if(isFocused) {
			aGC.setForeground(tde.gui.colorsSwt.GRAY);
			aGC.setLineDash(new int[] {1,1});
			aGC.drawRectangle(2, 2, aCR.width-5, aCR.height-5);
		}
	}
	
	int getX2Pos(int aX) {
		return lineX+(int)(lineW*(aX-min)/(double)(max-min)); 
	}
}
