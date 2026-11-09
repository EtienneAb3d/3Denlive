package com.cubaix.TDenlive.shapes;

import java.awt.BasicStroke;
import java.awt.geom.AffineTransform;
import java.awt.geom.Path2D;
import java.awt.geom.PathIterator;
import java.awt.geom.Point2D;
import java.util.Vector;
import java.util.function.Consumer;

import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.Color;
import org.eclipse.swt.graphics.GC;
import org.eclipse.swt.graphics.Path;
import org.eclipse.swt.graphics.Rectangle;

/**
 * Vector editor of a list of shapes, drawn over an image of the frame (SWT):
 * selection, move, resize (box handles), vertices, rotation, creation with a tool.
 * The frame is shown in one or several views (side by side: one per eye),
 * see setViews(), each with the geometry of the processors after the shapes. The shapes come from a Source: a plain list (creation popup)
 * or the keys of a Shapes processor (the clip, edited on the monitor).
 */
public class ShapesEditor {
	/**
	 * Where the shapes come from, and how the structural changes are made
	 */
	public interface Source {
		/** Shapes shown now (may be an interpolated copy) */
		Vector<Shape> getShapes();
		/** Shapes to modify now (may create a key), null if not editable */
		Vector<Shape> getShapesForEdit();
		/** Add a new shape (its id is set) */
		void addShape(Shape aS);
		/** Delete a shape (or only hide it) */
		void deleteShape(int aId);
		/** Show / hide a shape */
		void setHidden(int aId,boolean aHidden);
		/** Move a shape on top of the others (or below them) */
		void reorder(int aId,boolean aToFront);
		/** True to show the hidden shapes as very light ghosts */
		boolean showGhosts();
		/** Shapes of the keys after the one edited now (none without keys) */
		default Vector<Vector<Shape>> getFollowingShapes() {
			return new Vector<Vector<Shape>>();
		}
	}

	public interface Listener {
		/** The shapes changed (redraw) */
		void shapesChanged();
		/** Selection or tool changed (update the properties) */
		void selectionChanged();
	}

	static final int HANDLE = 4;//Half side of the handles (pixels)
	static final int HANDLE_OFF = 9;//Box handles out of the box
	static final int ROTATE_OFF = 24;//Rotation handle above the box
	static final int DRAG_MIN = 3;//Pixels before a move is a drag

	static final int DRAG_NONE = 0;
	static final int DRAG_MOVE = 1;
	static final int DRAG_VERTEX = 2;
	static final int DRAG_BOX = 3;
	static final int DRAG_ROTATE = 4;
	static final int DRAG_CREATE = 5;

	Source source = null;
	Vector<Listener> listeners = new Vector<Listener>();
	int selectedId = -1;
	//-1: selection, else the type of the shapes to create
	int tool = -1;
	//Style of the next created shapes (follows the last changes)
	public Shape style = new Shape();
	//Id of the shape just created with a tool (-1 once taken, see takeCreated())
	int createdId = -1;

	/**
	 * A view of the frame on the screen: its rectangle (pixels of the control), the eye shown,
	 * and the geometry of the processors after the shapes (shapes frame 0..1 -> frame 0..1),
	 * so that the handles are where the shapes are displayed
	 */
	public static class View {
		public double x = 0;
		public double y = 0;
		public double w = 1;
		public double h = 1;
		public int eye = 0;
		//Views of the same frame on the screen (e.g. the half of a side by side view), see MonitorGUI
		public int group = 0;
		AffineTransform chain = new AffineTransform();
		//Pixels of the shapes layer (height 1, width = ratio) -> screen, and back
		AffineTransform toScreen = null;
		AffineTransform fromScreen = null;

		public View(double aX,double aY,double aW,double aH,int aEye) {
			x = aX;
			y = aY;
			w = aW;
			h = aH;
			eye = aEye;
			setChain(null);
		}

		/**
		 * Geometry of the processors after the shapes (frame coordinates 0..1), null for none
		 */
		public void setChain(AffineTransform aChain) {
			chain = aChain == null ? new AffineTransform() : new AffineTransform(aChain);
			toScreen = new AffineTransform();
			toScreen.translate(x, y);
			toScreen.scale(w, h);
			toScreen.concatenate(chain);
			toScreen.scale(1.0/ratio(), 1.0);
			try {
				fromScreen = toScreen.createInverse();
			}
			catch(Throwable t) {
				//Flattened by a processor: not editable
				fromScreen = null;
			}
		}

		/** Width / height of the frame */
		public double ratio() {
			return h > 0 ? w/h : 1.0;
		}

		boolean contains(int aX,int aY) {
			return aX >= x && aX < x+w && aY >= y && aY < y+h;
		}

		boolean sameRect(View aV) {
			return aV != null && x == aV.x && y == aV.y && w == aV.w && h == aV.h;
		}

		/**
		 * View between this one and aV (same rectangle, the other eye): no depth shift, mean geometry
		 */
		View center(View aV) {
			double[] aA = new double[6];
			double[] aB = new double[6];
			chain.getMatrix(aA);
			aV.chain.getMatrix(aB);
			for(int m = 0;m < 6;m++) {
				aA[m] = (aA[m]+aB[m])/2;
			}
			View aC = new View(x, y, w, h, 0);
			aC.group = group;
			aC.setChain(new AffineTransform(aA));
			return aC;
		}

		boolean same(View aV) {
			return aV != null && x == aV.x && y == aV.y && w == aV.w && h == aV.h && eye == aV.eye && chain.equals(aV.chain);
		}
	}

	Vector<View> views = new Vector<View>();

	int dragMode = DRAG_NONE;
	View dragView = null;
	int dragIndex = -1;
	int downX = 0;
	int downY = 0;
	int curX = 0;
	int curY = 0;
	boolean dragStarted = false;
	double[] origXs = null;
	double[] origYs = null;
	double[] origBox = null;


	public ShapesEditor(Source aSource) {
		source = aSource;
	}

	public void addListener(Listener aL) {
		if(!listeners.contains(aL)) {
			listeners.add(aL);
		}
	}

	public void removeListener(Listener aL) {
		listeners.remove(aL);
	}

	void fireShapesChanged() {
		for(Listener aL : new Vector<Listener>(listeners)) {
			aL.shapesChanged();
		}
	}

	void fireSelectionChanged() {
		for(Listener aL : new Vector<Listener>(listeners)) {
			aL.selectionChanged();
		}
	}

	public Source getSource() {
		return source;
	}

	/**
	 * Views of the frame in pixels of the control (one per eye side by side)
	 */
	public void setViews(Vector<View> aViews) {
		views = aViews;
	}

	/**
	 * A single view of the frame, without processors geometry
	 */
	public void setView(double aX,double aY,double aW,double aH) {
		Vector<View> aV = new Vector<View>();
		aV.add(new View(aX, aY, aW, aH, 0));
		views = aV;
	}

	public int getTool() {
		return tool;
	}

	public void setTool(int aTool) {
		tool = aTool;
		if(tool >= 0) {
			selectedId = -1;
		}
		fireSelectionChanged();
	}

	public Shape getSelected() {
		if(selectedId < 0) {
			return null;
		}
		return Shape.findById(source.getShapes(), selectedId);
	}

	public void select(int aId) {
		selectedId = aId;
		Shape aS = getSelected();
		if(aS != null) {
			style.copyStyle(aS);
		}
		fireSelectionChanged();
	}

	/**
	 * Change the style of the selected shape (and of the next created ones)
	 */
	public void editSelected(Consumer<Shape> aEdit) {
		aEdit.accept(style);
		if(selectedId >= 0) {
			Vector<Shape> aEd = source.getShapesForEdit();
			Shape aS = Shape.findById(aEd, selectedId);
			if(aS != null) {
				aEdit.accept(aS);
			}
		}
		fireShapesChanged();
	}

	/**
	 * Change the text or the font of the selected text (and of the next ones): its box follows the text.
	 * With keys, from the current key on, until a key where the text was already different
	 */
	public void editSelectedText(Consumer<Shape> aEdit) {
		double aR = getFrameRatio();
		Shape aBefore = null;
		if(selectedId >= 0) {
			Shape aCur = Shape.findById(source.getShapesForEdit(), selectedId);
			aBefore = aCur == null ? null : aCur.copy();
		}
		editSelected(s -> {
			aEdit.accept(s);
			s.fitText(aR);
		});
		if(aBefore == null) {
			return;
		}
		for(Vector<Shape> aShapes : source.getFollowingShapes()) {
			Shape aS = Shape.findById(aShapes, selectedId);
			if(aS == null || !aS.sameText(aBefore)) {
				break;
			}
			aEdit.accept(aS);
			aS.fitText(aR);
		}
		fireShapesChanged();
	}

	/**
	 * Width / height of the frame (of the first view)
	 */
	public double getFrameRatio() {
		return views.size() > 0 ? views.elementAt(0).ratio() : 16.0/9.0;
	}

	/**
	 * True once if the selected shape has just been created with a tool
	 */
	public boolean takeCreated() {
		boolean aNew = createdId >= 0 && createdId == selectedId;
		createdId = -1;
		return aNew;
	}

	public void deleteSelected() {
		if(selectedId < 0) {
			return;
		}
		source.deleteShape(selectedId);
		if(getSelected() == null) {
			selectedId = -1;
		}
		fireShapesChanged();
		fireSelectionChanged();
	}

	public void setSelectedHidden(boolean aHidden) {
		if(selectedId < 0) {
			return;
		}
		source.setHidden(selectedId, aHidden);
		fireShapesChanged();
		fireSelectionChanged();
	}

	public void reorderSelected(boolean aToFront) {
		if(selectedId < 0) {
			return;
		}
		source.reorder(selectedId, aToFront);
		fireShapesChanged();
	}

	public void duplicateSelected() {
		Shape aS = getSelected();
		if(aS == null) {
			return;
		}
		Shape aCopy = aS.copy();
		aCopy.hidden = false;
		for(int p = 0;p < aCopy.xs.length;p++) {
			aCopy.xs[p] += 0.03;
			aCopy.ys[p] += 0.03;
		}
		source.addShape(aCopy);
		selectedId = aCopy.id;
		fireShapesChanged();
		fireSelectionChanged();
	}


	//
	// Geometry on the screen: the shapes are built in the pixels of the shapes layer
	// (height 1, width = ratio), with their depth shift, then sent to the screen by the view
	//

	/**
	 * Shift of the shape for the eye of the view, in frame coordinates (0..1)
	 */
	static double shiftOf(Shape aS,View aV) {
		return aV.eye*aS.depth/2;
	}

	Point2D.Double toScreen(Shape aS,View aV,double aX,double aY) {
		Point2D.Double aP = new Point2D.Double((aX+shiftOf(aS, aV))*aV.ratio(), aY);
		aV.toScreen.transform(aP, aP);
		return aP;
	}

	/**
	 * Point of the frame (0..1) under a screen point, without depth shift; null if not editable
	 */
	Point2D.Double fromScreen(View aV,double aX,double aY) {
		if(aV.fromScreen == null) {
			return null;
		}
		Point2D.Double aP = new Point2D.Double(aX, aY);
		aV.fromScreen.transform(aP, aP);
		aP.x /= aV.ratio();
		return aP;
	}

	java.awt.Shape screenPath(Shape aS,View aV) {
		double aR = aV.ratio();
		return aV.toScreen.createTransformedShape(aS.buildPath(0, 0, aR, 1, shiftOf(aS, aV)*aR));
	}

	/**
	 * Outline used to select the shape: its path, the box of a text
	 */
	java.awt.Shape screenOutline(Shape aS,View aV) {
		if(!aS.isText()) {
			return screenPath(aS, aV);
		}
		double aR = aV.ratio();
		return aV.toScreen.createTransformedShape(aS.buildPolygon(0, 0, aR, 1, shiftOf(aS, aV)*aR));
	}

	java.awt.Shape screenArrowHead(Shape aS,View aV) {
		double aR = aV.ratio();
		Path2D.Double aHead = aS.buildArrowHead(0, 0, aR, 1, shiftOf(aS, aV)*aR);
		return aHead == null ? null : aV.toScreen.createTransformedShape(aHead);
	}

	/**
	 * Width of the border on the screen (pixels)
	 */
	double screenStroke(Shape aS,View aV) {
		Point2D aD = aV.toScreen.deltaTransform(new Point2D.Double(0, aS.strokeWidth), null);
		return Math.hypot(aD.getX(), aD.getY());
	}

	/**
	 * Positions of the 8 box handles (TL, T, TR, R, BR, B, BL, L) then the rotation one:
	 * the box of the shape on the screen, the handles a bit out of it
	 */
	double[][] boxHandles(Shape aS,View aV) {
		double[] aB = aS.getBox();
		double aCX = (aB[0]+aB[2])/2;
		double aCY = (aB[1]+aB[3])/2;
		double[][] aPts = {{aB[0],aB[1]},{aCX,aB[1]},{aB[2],aB[1]},{aB[2],aCY},{aB[2],aB[3]},{aCX,aB[3]},{aB[0],aB[3]},{aB[0],aCY}};
		Point2D.Double aC = toScreen(aS, aV, aCX, aCY);
		double[][] aH = new double[9][];
		for(int h = 0;h < 8;h++) {
			Point2D.Double aP = toScreen(aS, aV, aPts[h][0], aPts[h][1]);
			double[] aU = unit(aP.x-aC.x, aP.y-aC.y);
			aH[h] = new double[] {aP.x+aU[0]*HANDLE_OFF, aP.y+aU[1]*HANDLE_OFF};
		}
		double[] aU = unit(aH[1][0]-aC.x, aH[1][1]-aC.y);
		if(aU[0] == 0 && aU[1] == 0) {
			aU = new double[] {0,-1};
		}
		aH[8] = new double[] {aH[1][0]+aU[0]*ROTATE_OFF, aH[1][1]+aU[1]*ROTATE_OFF};
		return aH;
	}

	static double[] unit(double aX,double aY) {
		double aL = Math.hypot(aX, aY);
		return aL < 1e-9 ? new double[] {0,0} : new double[] {aX/aL, aY/aL};
	}

	static boolean near(double aX,double aY,int aPX,int aPY,int aTol) {
		return Math.abs(aX-aPX) <= aTol && Math.abs(aY-aPY) <= aTol;
	}

	boolean hit(Shape aS,View aV,int aX,int aY) {
		java.awt.Shape aP = screenOutline(aS, aV);
		if(!aS.isLine() && aP.contains(aX, aY)) {
			return true;
		}
		float aW = (float)Math.max(10, screenStroke(aS, aV)+6);
		return new BasicStroke(aW).createStrokedShape(aP).contains(aX, aY);
	}

	/**
	 * Views under the point (the left and right ones of an anaglyph), else the nearest ones
	 */
	Vector<View> candidates(int aX,int aY) {
		Vector<View> aC = new Vector<View>();
		for(View aV : views) {
			if(aV.contains(aX, aY) && aV.fromScreen != null) {
				aC.add(aV);
			}
		}
		if(aC.size() > 0) {
			return aC;
		}
		//Handles may be out of the frame: the nearest views
		double aBestD = Double.MAX_VALUE;
		for(View aV : views) {
			if(aV.fromScreen == null) {
				continue;
			}
			double aDX = Math.max(0, Math.max(aV.x-aX, aX-(aV.x+aV.w)));
			double aDY = Math.max(0, Math.max(aV.y-aY, aY-(aV.y+aV.h)));
			double aD = Math.hypot(aDX, aDY);
			if(aD < aBestD-1e-9) {
				aBestD = aD;
				aC.clear();
			}
			if(Math.abs(aD-aBestD) <= 1e-9) {
				aC.add(aV);
			}
		}
		return aC;
	}

	/**
	 * What is under the click, in the view (eye) where it is the nearest
	 */
	static class Pick {
		View view = null;
		int mode = DRAG_NONE;
		int index = -1;
		Shape shape = null;
		double dist = Double.MAX_VALUE;
	}

	/**
	 * The nearest handle of the selected shape in the views under the click (left or right eye),
	 * else the shape hit whose image (left or right) is the nearest to the click, else null
	 */
	Pick pick(Vector<Shape> aShapes,int aX,int aY) {
		Vector<View> aCands = candidates(aX, aY);
		Shape aSel = Shape.findById(aShapes, selectedId);
		Pick aBest = null;
		if(aSel != null) {
			for(View aV : aCands) {
				//The box of a text stays a box: no vertex handles
				for(int p = aSel.isText() ? -1 : aSel.xs.length-1;p >= 0;p--) {
					Point2D.Double aP = toScreen(aSel, aV, aSel.xs[p], aSel.ys[p]);
					aBest = better(aBest, aV, DRAG_VERTEX, p, aSel, aP.x, aP.y, aX, aY, HANDLE+3);
				}
				if(!aSel.isLine()) {
					double[][] aH = boxHandles(aSel, aV);
					aBest = better(aBest, aV, DRAG_ROTATE, 8, aSel, aH[8][0], aH[8][1], aX, aY, HANDLE+4);
					for(int h = 0;h < 8;h++) {
						aBest = better(aBest, aV, DRAG_BOX, h, aSel, aH[h][0], aH[h][1], aX, aY, HANDLE+3);
					}
				}
			}
		}
		if(aBest != null) {
			return aBest;
		}
		for(View aV : aCands) {
			Shape aS = shapeAt(aShapes, aV, aX, aY);
			if(aS == null) {
				continue;
			}
			java.awt.geom.Rectangle2D aB = screenOutline(aS, aV).getBounds2D();
			aBest = better(aBest, aV, DRAG_MOVE, -1, aS, aB.getCenterX(), aB.getCenterY(), aX, aY, Double.MAX_VALUE);
		}
		return aBest;
	}

	static Pick better(Pick aBest,View aV,int aMode,int aIndex,Shape aS,double aPX,double aPY,int aX,int aY,double aTol) {
		double aD = Math.hypot(aPX-aX, aPY-aY);
		if(aD > aTol || (aBest != null && aBest.dist <= aD)) {
			return aBest;
		}
		Pick aP = new Pick();
		aP.view = aV;
		aP.mode = aMode;
		aP.index = aIndex;
		aP.shape = aS;
		aP.dist = aD;
		return aP;
	}

	Shape shapeAt(Vector<Shape> aShapes,View aV,int aX,int aY) {
		boolean aGhosts = source.showGhosts();
		for(int s = aShapes.size()-1;s >= 0;s--) {
			Shape aS = aShapes.elementAt(s);
			if(aS.hidden && !aGhosts) {
				continue;
			}
			if(hit(aS, aV, aX, aY)) {
				return aS;
			}
		}
		return null;
	}

	/**
	 * SWT cursor for the position (SWT.CURSOR_*), -1 for the default one
	 */
	public int getCursorAt(int aX,int aY) {
		if(candidates(aX, aY).size() <= 0) {
			return -1;
		}
		if(tool >= 0) {
			return SWT.CURSOR_CROSS;
		}
		Vector<Shape> aShapes = source.getShapes();
		if(aShapes == null) {
			return -1;
		}
		Pick aP = pick(aShapes, aX, aY);
		if(aP == null) {
			return -1;
		}
		switch(aP.mode) {
		case DRAG_BOX:
			int[] aC = {SWT.CURSOR_SIZENW,SWT.CURSOR_SIZEN,SWT.CURSOR_SIZENE,SWT.CURSOR_SIZEE
					,SWT.CURSOR_SIZESE,SWT.CURSOR_SIZES,SWT.CURSOR_SIZESW,SWT.CURSOR_SIZEW};
			return aC[aP.index];
		case DRAG_ROTATE:
			return SWT.CURSOR_HAND;
		case DRAG_VERTEX:
			return SWT.CURSOR_CROSS;
		}
		return SWT.CURSOR_SIZEALL;
	}

	//
	// Mouse
	//

	/**
	 * Returns true if the click is for the editor (else the control may use it, e.g. to pan)
	 */
	public boolean mouseDown(int aX,int aY) {
		dragMode = DRAG_NONE;
		dragStarted = false;
		if(views.size() <= 0) {
			return false;
		}
		Vector<View> aCands = candidates(aX, aY);
		if(aCands.size() <= 0) {
			return false;
		}
		Vector<Shape> aShapes = source.getShapes();
		if(aShapes == null) {
			return false;
		}
		downX = curX = aX;
		downY = curY = aY;
		if(tool >= 0) {
			//Created between the left and right views (its depth shifts them)
			dragView = aCands.size() >= 2 && aCands.elementAt(0).sameRect(aCands.elementAt(1))
					? aCands.elementAt(0).center(aCands.elementAt(1)) : aCands.elementAt(0);
			if(dragView.fromScreen == null) {
				return false;
			}
			dragMode = DRAG_CREATE;
			return true;
		}
		Pick aPick = pick(aShapes, aX, aY);
		if(aPick == null) {
			if(selectedId >= 0) {
				selectedId = -1;
				fireSelectionChanged();
				fireShapesChanged();
			}
			return false;
		}
		//The view (eye) of the handle or shape clicked: the moves are measured in it, and
		//change the shape, so both views
		dragView = aPick.view;
		dragMode = aPick.mode;
		dragIndex = aPick.index;
		Shape aTarget = aPick.shape;
		if(aTarget.id != selectedId) {
			select(aTarget.id);
			fireShapesChanged();
		}
		origXs = aTarget.xs.clone();
		origYs = aTarget.ys.clone();
		origBox = aTarget.getBox();
		return true;
	}

	public void mouseMove(int aX,int aY) {
		if(dragMode == DRAG_NONE) {
			return;
		}
		curX = aX;
		curY = aY;
		if(!dragStarted) {
			if(Math.abs(aX-downX) < DRAG_MIN && Math.abs(aY-downY) < DRAG_MIN) {
				return;
			}
			dragStarted = true;
		}
		if(dragMode == DRAG_CREATE) {
			fireShapesChanged();
			return;
		}
		Vector<Shape> aEd = source.getShapesForEdit();
		Shape aS = Shape.findById(aEd, selectedId);
		if(aS == null || origXs == null || aS.xs.length != origXs.length) {
			return;
		}
		View aV = dragView;
		Point2D.Double aDown = fromScreen(aV, downX, downY);
		Point2D.Double aCur = fromScreen(aV, aX, aY);
		if(aDown == null || aCur == null) {
			return;
		}
		//Move of the mouse in the frame of the shapes
		double aDX = aCur.x-aDown.x;
		double aDY = aCur.y-aDown.y;
		switch(dragMode) {
		case DRAG_MOVE:
			for(int p = 0;p < aS.xs.length;p++) {
				aS.xs[p] = origXs[p]+aDX;
				aS.ys[p] = origYs[p]+aDY;
			}
			break;
		case DRAG_VERTEX:
			if(dragIndex < aS.xs.length) {
				aS.xs[dragIndex] = origXs[dragIndex]+aDX;
				aS.ys[dragIndex] = origYs[dragIndex]+aDY;
			}
			break;
		case DRAG_BOX: {
			//Moved sides of the box (TL, T, TR, R, BR, B, BL, L), the opposite ones stay
			boolean aLeft = dragIndex == 0 || dragIndex == 6 || dragIndex == 7;
			boolean aRight = dragIndex == 2 || dragIndex == 3 || dragIndex == 4;
			boolean aTop = dragIndex == 0 || dragIndex == 1 || dragIndex == 2;
			boolean aBottom = dragIndex == 4 || dragIndex == 5 || dragIndex == 6;
			double aAX = aLeft ? origBox[2] : origBox[0];
			double aAY = aTop ? origBox[3] : origBox[1];
			double aSX = 1.0;
			double aSY = 1.0;
			double aOW = origBox[2]-origBox[0];
			double aOH = origBox[3]-origBox[1];
			if(aOW > 1e-6 && (aLeft || aRight)) {
				aSX = (aOW+(aRight ? aDX : -aDX))/aOW;
			}
			if(aOH > 1e-6 && (aTop || aBottom)) {
				aSY = (aOH+(aBottom ? aDY : -aDY))/aOH;
			}
			if((aLeft || aRight) && (aTop || aBottom)) {
				//Corner: same scale on both axes (keeps the ratio), the side handles change it
				double aScale = Math.abs(aSX-1.0) >= Math.abs(aSY-1.0) ? aSX : aSY;
				if(aOW <= 1e-6) {
					aScale = aSY;
				}
				else if(aOH <= 1e-6) {
					aScale = aSX;
				}
				aSX = aSY = aScale;
			}
			for(int p = 0;p < aS.xs.length;p++) {
				aS.xs[p] = aAX+(origXs[p]-aAX)*aSX;
				aS.ys[p] = aAY+(origYs[p]-aAY)*aSY;
			}
			break;
		}
		case DRAG_ROTATE: {
			//Angle on the screen around the center of the box, applied in the pixels of the shapes
			double aCX = (origBox[0]+origBox[2])/2;
			double aCY = (origBox[1]+origBox[3])/2;
			Point2D.Double aC = toScreen(aS, aV, aCX, aCY);
			double aA = Math.atan2(aY-aC.y, aX-aC.x)-Math.atan2(downY-aC.y, downX-aC.x);
			if(aV.toScreen.getDeterminant() < 0) {
				//Mirrored by a processor
				aA = -aA;
			}
			double aR = aV.ratio();
			double aCos = Math.cos(aA);
			double aSin = Math.sin(aA);
			for(int p = 0;p < aS.xs.length;p++) {
				double aPX = (origXs[p]-aCX)*aR;
				double aPY = origYs[p]-aCY;
				aS.xs[p] = aCX+(aPX*aCos-aPY*aSin)/aR;
				aS.ys[p] = aCY+aPX*aSin+aPY*aCos;
			}
			break;
		}
		}
		fireShapesChanged();
	}

	/**
	 * Shape being created by the drag from (downX,downY) to (aX,aY), in the frame coordinates:
	 * its box stays square (the proportions of the tool), except for the lines
	 */
	Shape shapeFromDrag(int aX,int aY) {
		View aV = dragView;
		boolean aIsLine = tool == Shape.LINE || tool == Shape.LINE_ARROW;
		boolean aIsText = tool == Shape.TEXT;
		double aR = aV.ratio();
		//Width / height of the text: its box has the proportions of the text
		double aTR = aIsText ? Math.max(0.05, Shape.create(tool, 0, 0, 1, 1, style).textRatio()) : 1.0;
		Point2D.Double aDown = fromScreen(aV, downX, downY);
		Point2D.Double aCur = fromScreen(aV, aX, aY);
		if(aDown == null || aCur == null) {
			return Shape.create(tool, 0, 0, 0, 0, style);
		}
		double aShift = aV.eye*style.depth/2;
		double aX0 = aDown.x-aShift;
		double aY0 = aDown.y;
		double aX1;
		double aY1;
		if(!dragStarted) {
			//A simple click: a default size (square)
			double aHW = 0.1/aR;
			if(aIsLine) {
				aX1 = aX0+2*aHW;
				aY1 = aY0;
			}
			else if(aIsText) {
				//From the click, 0.1 of the frame height
				aX1 = aX0+0.1*aTR/aR;
				aY1 = aY0+0.1;
			}
			else {
				aX1 = aX0+aHW;
				aY1 = aY0+0.1;
				aX0 -= aHW;
				aY0 -= 0.1;
			}
		}
		else {
			//In the pixels of the shapes (height 1)
			double aDX = (aCur.x-aDown.x)*aR;
			double aDY = aCur.y-aDown.y;
			if(aIsText) {
				//The height of the drag, the width of the text
				double aSize = Math.max(Math.abs(aDY), Math.abs(aDX)/aTR);
				aDX = (aDX >= 0 ? 1 : -1)*aSize*aTR;
				aDY = (aDY >= 0 ? 1 : -1)*aSize;
			}
			else if(!aIsLine) {
				double aSize = Math.max(Math.abs(aDX), Math.abs(aDY));
				aDX = (aDX >= 0 ? 1 : -1)*aSize;
				aDY = (aDY >= 0 ? 1 : -1)*aSize;
			}
			aX1 = aX0+aDX/aR;
			aY1 = aY0+aDY;
		}
		return Shape.create(tool, aX0, aY0, aX1, aY1, style);
	}

	public void mouseUp(int aX,int aY) {
		if(dragMode == DRAG_CREATE) {
			Shape aS = shapeFromDrag(aX, aY);
			source.addShape(aS);
			selectedId = aS.id;
			createdId = aS.id;
			tool = -1;
			dragMode = DRAG_NONE;
			fireShapesChanged();
			fireSelectionChanged();
			return;
		}
		boolean aChanged = dragStarted;
		dragMode = DRAG_NONE;
		dragStarted = false;
		if(aChanged) {
			fireShapesChanged();
		}
	}

	//
	// Drawing (SWT)
	//

	static Path toSwtPath(GC aGC,java.awt.Shape aShape) {
		Path aP = new Path(aGC.getDevice());
		PathIterator aPI = aShape.getPathIterator(null);
		float[] aC = new float[6];
		while(!aPI.isDone()) {
			switch(aPI.currentSegment(aC)) {
			case PathIterator.SEG_MOVETO:
				aP.moveTo(aC[0], aC[1]);
				break;
			case PathIterator.SEG_LINETO:
				aP.lineTo(aC[0], aC[1]);
				break;
			case PathIterator.SEG_QUADTO:
				aP.quadTo(aC[0], aC[1], aC[2], aC[3]);
				break;
			case PathIterator.SEG_CUBICTO:
				aP.cubicTo(aC[0], aC[1], aC[2], aC[3], aC[4], aC[5]);
				break;
			case PathIterator.SEG_CLOSE:
				aP.close();
				break;
			}
			aPI.next();
		}
		return aP;
	}

	/**
	 * Draw a shape with its style (SWT), e.g. the one being created
	 */
	void drawShape(GC aGC,Shape aS,View aV) {
		int aAlpha = (int)Math.round(255*Math.max(0, Math.min(1, aS.opacity)));
		Path aP = toSwtPath(aGC, screenPath(aS, aV));
		if(aS.filled && !aS.isLine()) {
			Color aC = new Color(aGC.getDevice(), (aS.fillColor>>16)&0xFF, (aS.fillColor>>8)&0xFF, aS.fillColor&0xFF);
			aGC.setAlpha(aAlpha*((aS.fillColor>>24)&0xFF)/255);
			aGC.setBackground(aC);
			aGC.fillPath(aP);
			aC.dispose();
		}
		if(aS.stroked || aS.isLine()) {
			Color aC = new Color(aGC.getDevice(), (aS.strokeColor>>16)&0xFF, (aS.strokeColor>>8)&0xFF, aS.strokeColor&0xFF);
			aGC.setAlpha(aAlpha*((aS.strokeColor>>24)&0xFF)/255);
			aGC.setForeground(aC);
			aGC.setBackground(aC);
			aGC.setLineWidth(Math.max(1, (int)Math.round(screenStroke(aS, aV))));
			aGC.setLineCap(aS.isLine() ? SWT.CAP_ROUND : SWT.CAP_FLAT);
			int[] aStyles = {SWT.LINE_SOLID,SWT.LINE_DASH,SWT.LINE_DOT,SWT.LINE_DASHDOT};
			aGC.setLineStyle(aStyles[Math.max(0, Math.min(3, aS.strokeStyle))]);
			aGC.drawPath(aP);
			java.awt.Shape aHead = screenArrowHead(aS, aV);
			if(aHead != null) {
				Path aHP = toSwtPath(aGC, aHead);
				aGC.setLineStyle(SWT.LINE_SOLID);
				aGC.fillPath(aHP);
				aGC.drawPath(aHP);
				aHP.dispose();
			}
			aC.dispose();
		}
		aP.dispose();
		aGC.setAlpha(255);
		aGC.setLineStyle(SWT.LINE_SOLID);
		aGC.setLineCap(SWT.CAP_FLAT);
		aGC.setLineWidth(1);
	}

	/**
	 * Draw the overlay of all the views, aNbGroups images of the frame side by side in aImage
	 * (pixels of the control): each one clipped to its part, with its views (group)
	 */
	public void drawOverlay(GC aGC,Rectangle aImage,int aNbGroups,int aClipH) {
		Vector<View> aAll = views;
		if(aAll == null || aNbGroups <= 1) {
			drawOverlay(aGC);
			return;
		}
		Rectangle aOldClip = aGC.getClipping();
		int aSW = aImage.width/aNbGroups;
		try {
			for(int g = 0;g < aNbGroups;g++) {
				aGC.setClipping(new Rectangle(aImage.x+g*aSW, 0, aSW, aClipH).intersection(aOldClip));
				Vector<View> aGroup = new Vector<View>();
				for(View aV : aAll) {
					if(aV.group == g) {
						aGroup.add(aV);
					}
				}
				views = aGroup;
				drawOverlay(aGC);
			}
		}
		finally {
			aGC.setClipping(aOldClip);
			views = aAll;
		}
	}

	/**
	 * Draw the ghosts of the hidden shapes, the selection and its handles, the shape being created
	 */
	public void drawOverlay(GC aGC) {
		Vector<Shape> aShapes = source.getShapes();
		if(aShapes == null) {
			return;
		}
		Rectangle aOldClip = aGC.getClipping();
		aGC.setAntialias(SWT.ON);
		Color aGhost = new Color(aGC.getDevice(), 200, 200, 200);
		Color aSelC = new Color(aGC.getDevice(), 230, 0, 126);
		//Handles of the left / right images of a shape shifted in depth (anaglyph colors)
		Color aLeftC = new Color(aGC.getDevice(), 220, 30, 30);
		Color aRightC = new Color(aGC.getDevice(), 0, 170, 210);
		Color aWhite = aGC.getDevice().getSystemColor(SWT.COLOR_WHITE);
		try {
			for(int v = 0;v < views.size();v++) {
				View aV = views.elementAt(v);
				if(aV.fromScreen == null) {
					continue;
				}
				int aM = HANDLE_OFF+ROTATE_OFF;
				Rectangle aClip = new Rectangle((int)aV.x-aM, (int)aV.y-aM, (int)aV.w+2*aM, (int)aV.h+2*aM);
				aGC.setClipping(aOldClip != null ? aClip.intersection(aOldClip) : aClip);
				//Hidden shapes: very light, 90% transparent
				if(source.showGhosts()) {
					for(Shape aS : aShapes) {
						if(!aS.hidden || drawnBefore(aS, v)) {
							continue;
						}
						Path aP = toSwtPath(aGC, screenPath(aS, aV));
						aGC.setLineStyle(SWT.LINE_SOLID);
						aGC.setLineWidth(Math.max(1, (int)screenStroke(aS, aV)));
						aGC.setAlpha(26);
						aGC.setBackground(aGhost);
						aGC.setForeground(aGhost);
						if(!aS.isLine()) {
							aGC.fillPath(aP);
						}
						aGC.drawPath(aP);
						aP.dispose();
					}
					aGC.setAlpha(255);
				}
				Shape aSel = Shape.findById(aShapes, selectedId);
				if(aSel != null && !drawnBefore(aSel, v)) {
					//Doubled by the depth: each eye has its handles, in its color
					Color aEyeC = isDoubled(aSel, v) ? (aV.eye < 0 ? aLeftC : aRightC) : aSelC;
					//Outline
					Path aP = toSwtPath(aGC, screenOutline(aSel, aV));
					aGC.setLineWidth(1);
					aGC.setLineStyle(SWT.LINE_SOLID);
					aGC.setForeground(aWhite);
					aGC.drawPath(aP);
					aGC.setLineStyle(SWT.LINE_DOT);
					aGC.setForeground(aEyeC);
					aGC.drawPath(aP);
					aP.dispose();
					if(!aSel.isLine()) {
						double[][] aH = boxHandles(aSel, aV);
						//Box (may be rotated by the processors) through the corner handles
						aGC.setLineStyle(SWT.LINE_DASH);
						aGC.setForeground(aEyeC);
						aGC.drawPolygon(new int[] {(int)aH[0][0],(int)aH[0][1],(int)aH[2][0],(int)aH[2][1]
								,(int)aH[4][0],(int)aH[4][1],(int)aH[6][0],(int)aH[6][1]});
						aGC.setLineStyle(SWT.LINE_SOLID);
						aGC.drawLine((int)aH[1][0], (int)aH[1][1], (int)aH[8][0], (int)aH[8][1]);
						for(int h = 0;h < 8;h++) {
							aGC.setBackground(aWhite);
							aGC.fillRectangle((int)aH[h][0]-HANDLE, (int)aH[h][1]-HANDLE, 2*HANDLE, 2*HANDLE);
							aGC.setForeground(aEyeC);
							aGC.drawRectangle((int)aH[h][0]-HANDLE, (int)aH[h][1]-HANDLE, 2*HANDLE, 2*HANDLE);
						}
						aGC.setBackground(aEyeC);
						aGC.fillOval((int)aH[8][0]-HANDLE-1, (int)aH[8][1]-HANDLE-1, 2*HANDLE+2, 2*HANDLE+2);
						aGC.setForeground(aWhite);
						aGC.drawOval((int)aH[8][0]-HANDLE-1, (int)aH[8][1]-HANDLE-1, 2*HANDLE+2, 2*HANDLE+2);
					}
					//Vertices (not for a text: its box)
					for(int p = 0;p < (aSel.isText() ? 0 : aSel.xs.length);p++) {
						Point2D.Double aPt = toScreen(aSel, aV, aSel.xs[p], aSel.ys[p]);
						int aPX = (int)aPt.x;
						int aPY = (int)aPt.y;
						aGC.setBackground(aEyeC);
						aGC.fillOval(aPX-HANDLE, aPY-HANDLE, 2*HANDLE, 2*HANDLE);
						aGC.setForeground(aWhite);
						aGC.drawOval(aPX-HANDLE, aPY-HANDLE, 2*HANDLE, 2*HANDLE);
					}
				}
				//The shape being created, in each eye of the frame where it is drawn
				//(the views are recomputed at each paint: compare the values)
				if(dragMode == DRAG_CREATE && dragStarted && aV.sameRect(dragView)) {
					Shape aNew = shapeFromDrag(curX, curY);
					if(!drawnBefore(aNew, v)) {
						drawShape(aGC, aNew, aV);
					}
				}
			}
		}
		finally {
			aGC.setAlpha(255);
			aGC.setLineStyle(SWT.LINE_SOLID);
			aGC.setLineWidth(1);
			aGC.setClipping(aOldClip);
			aGhost.dispose();
			aSelC.dispose();
			aLeftC.dispose();
			aRightC.dispose();
		}
	}

	/**
	 * True if the shape is at the same place on the screen in the views aV1 and aV2 (no stereo shift)
	 */
	boolean coincide(Shape aS,View aV1,View aV2) {
		if(!aV1.sameRect(aV2) || aV2.fromScreen == null) {
			return false;
		}
		for(int p = 0;p < aS.xs.length;p++) {
			Point2D.Double aP1 = toScreen(aS, aV1, aS.xs[p], aS.ys[p]);
			Point2D.Double aP2 = toScreen(aS, aV2, aS.xs[p], aS.ys[p]);
			if(Math.abs(aP1.x-aP2.x) > 0.5 || Math.abs(aP1.y-aP2.y) > 0.5) {
				return false;
			}
		}
		return true;
	}

	/**
	 * True if an earlier view already shows the shape at the same place (drawn only once)
	 */
	boolean drawnBefore(Shape aS,int aView) {
		View aV = views.elementAt(aView);
		for(int v = 0;v < aView;v++) {
			if(coincide(aS, aV, views.elementAt(v))) {
				return true;
			}
		}
		return false;
	}

	/**
	 * True if another view of the same frame shows the shape elsewhere (stereo shift)
	 */
	boolean isDoubled(Shape aS,int aView) {
		View aV = views.elementAt(aView);
		for(int v = 0;v < views.size();v++) {
			View aV2 = views.elementAt(v);
			if(v != aView && aV.sameRect(aV2) && aV2.fromScreen != null && !coincide(aS, aV, aV2)) {
				return true;
			}
		}
		return false;
	}
}
