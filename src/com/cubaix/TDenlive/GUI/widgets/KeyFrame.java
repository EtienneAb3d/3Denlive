package com.cubaix.TDenlive.GUI.widgets;

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
import org.eclipse.swt.graphics.Rectangle;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.widgets.Composite;

import com.cubaix.TDenlive.TDenlive;
import com.cubaix.TDenlive.medias.Clip;
import com.cubaix.TDenlive.processors.Processor;

public abstract class KeyFrame extends Widget {
	Processor proc = null;
	int selection = -1;
	boolean isFocused = false;
	int lineX = 0;
	int lineW = 100;
	boolean isMouseDown = false;
	
	public KeyFrame(TDenlive aTDe, Composite aParent,Processor aClip) {
		super(aTDe, aParent);
		proc = aClip;
		createContents();
	}
	
	public abstract void change(int aKey);
	public abstract void move(int aTime);

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
				int aX = e.x-lineX;
				if(aX < 0) {
					aX = 0;
				}
				if(aX > lineW) {
					aX = lineW;
				}
				int aTime = (int)((double)((Clip)proc.targetMedia).getDurationMS()*aX/(double)lineW);
				move(aTime);
			}
			@Override
			public void mouseDoubleClick(MouseEvent e) {
			}
		});
		container.addMouseMoveListener(new MouseMoveListener() {
			@Override
			public void mouseMove(MouseEvent e) {
				if(isMouseDown) {
					int aX = e.x-lineX;
					if(aX < 0) {
						aX = 0;
					}
					if(aX > lineW) {
						aX = lineW;
					}
					int aTime = (int)((double)((Clip)proc.targetMedia).getDurationMS()*aX/(double)lineW);
					move(aTime);
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
			}
		});
		container.addKeyListener(new KeyListener() {
			@Override
			public void keyReleased(KeyEvent e) {}
			@Override
			public void keyPressed(KeyEvent e) {}
		});
	}
	
	void draw(GC aGC) {
		Rectangle aCR = container.getClientArea();
		aGC.setForeground(tde.gui.colorsSwt.CUBAIX_BLUE);
		lineX = aCR.x+10;
		int aLineY = aCR.y+aCR.height/2;
		lineW = aCR.width-20;
		aGC.drawLine(lineX, aLineY, lineX+lineW, aLineY);
		aGC.drawLine(lineX, aLineY-5, lineX, aLineY+5);
		aGC.drawLine(getX2Pos(0), aLineY-5, getX2Pos(0), aLineY);
		aGC.drawLine(lineX+lineW, aLineY-5, lineX+lineW, aLineY+5);//Max
		if(proc != null 
//				&& tde.timeLineStack.getTimePosMS() >= ((Clip)proc.targetMedia).getStartTimeMS()
//				&& tde.timeLineStack.getTimePosMS() <= ((Clip)proc.targetMedia).getStartTimeMS()+((Clip)proc.targetMedia).getDurationMS()
				) {
			int aNbKey = proc.getNbKeys();
			for(int k = 0;k < aNbKey;k++) {
				double aPos = proc.getTime(k)/
						(double)((Clip)proc.targetMedia).getDurationMS();
				int aBulletX = lineX+(int)(aPos*lineW);
				if(k == selection) {
					aGC.setBackground(tde.gui.colorsSwt.CUBAIX_PINK);
					aGC.setForeground(tde.gui.colorsSwt.CUBAIX_PINK);
					aGC.drawOval(aBulletX-5, aLineY-5, 10, 10);
					int aBulletXN = lineX+lineW;
					if(k < aNbKey-1) {
						double aPosN = proc.getTime(k+1)/
								(double)((Clip)proc.targetMedia).getDurationMS();
						aBulletXN = lineX+(int)(aPosN*lineW);
					}
					aGC.fillRectangle(aBulletX, aLineY, aBulletXN-aBulletX, 2);
				}
				//				else {
				aGC.setBackground(tde.gui.colorsSwt.CUBAIX_BLUE);
				aGC.fillOval(aBulletX-2, aLineY-2, 5, 5);
				//				}
			}
			double aPos = (tde.timeLineStack.getTimePosMS() - ((Clip)proc.targetMedia).getStartTimeMS())/
					(double)((Clip)proc.targetMedia).getDurationMS();
			int aPosX = lineX+(int)(aPos*lineW);
			aGC.setForeground(tde.gui.colorsSwt.CUBAIX_PINK);
			aGC.drawLine(aPosX, aLineY, aPosX-5, aLineY-5);
			aGC.drawLine(aPosX, aLineY, aPosX+5, aLineY-5);
			aGC.drawLine(aPosX-5, aLineY-5, aPosX+5, aLineY-5);
		}
		if(isFocused) {
			aGC.setForeground(tde.gui.colorsSwt.GRAY);
			aGC.setLineDash(new int[] {1,1});
			aGC.drawRectangle(2, 2, aCR.width-5, aCR.height-5);
		}
	}
	
	int getX2Pos(int aX) {
		return lineX+(int)(lineW*(aX)/(double)lineW); 
	}
}
