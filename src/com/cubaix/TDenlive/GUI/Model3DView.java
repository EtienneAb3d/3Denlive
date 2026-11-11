package com.cubaix.TDenlive.GUI;

import java.awt.image.BufferedImage;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Vector;

import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.Color;
import org.eclipse.swt.graphics.GC;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.graphics.Rectangle;
import org.eclipse.swt.widgets.Canvas;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Event;

import com.cubaix.TDenlive.TDConfig;
import com.cubaix.TDenlive.TDenlive;
import com.cubaix.TDenlive.model3d.Camera3D;
import com.cubaix.TDenlive.model3d.Math3D;
import com.cubaix.TDenlive.model3d.Model3D;
import com.cubaix.TDenlive.model3d.Object3D;
import com.cubaix.TDenlive.model3d.Renderer3D;
import com.cubaix.TDenlive.processors.Mixer;
import com.cubaix.TDenlive.utils.ImageUtils;

/**
 * Interactive view of a 3D model (modeler and processor panel of the 3D clips): picking (the group of
 * the root level, Cmd / Ctrl: the object itself; Shift: add / remove), gizmo handles of the tools Move /
 * Rotate / Scale, axis constraint, snapping, orbit / pan / zoom, preset views, arrows.
 * The transforms are done in the world and stored in the local transforms of the nodes.
 */
public class Model3DView {
	/**
	 * What the view shows and edits
	 */
	public interface Source {
		/** Model shown and edited (its objects are changed in place) */
		Model3D getModel();
		/** Holder of the view (target, angles, distance): the model itself or a view of its own */
		Model3D getCamera();
		/** Before the first change of an action (the source may give another model to edit) */
		default void beginChange() {}
		/** The objects changed (aFinal: end of the action) */
		void changed(boolean aFinal);
		/** The selection or the tool changed */
		void selectionChanged();
		/** Other keys (delete, duplicate, undo...): true if used */
		default boolean extraKey(Event e) {return false;}
	}

	public static final int TOOL_MOVE = 0;
	public static final int TOOL_ROTATE = 1;
	public static final int TOOL_SCALE = 2;
	public static final String[] TOOL_KEYS = {"ToolMove", "ToolRotate", "ToolScale"};
	static final int MARGIN = 20;
	static final int GIZMO_PX = 80;
	static final int HIT_PX = 7;
	static final int DRAG_NONE = 0;
	static final int DRAG_ORBIT = 1;
	static final int DRAG_PAN = 2;
	static final int DRAG_PENDING = 3;//Mouse down on a selected object, not moved yet
	static final int DRAG_TRANSFORM = 4;
	static final int DRAG_EMPTY = 5;//Mouse down on the background: click (unselect) or orbit
	public static final double GRID_STEP = 0.25;
	public static final double ANGLE_STEP = 15;
	public static final double SCALE_STEP = 0.1;
	static final int BCK = 0xFF3A3D42;
	static final Color[] AXIS_COLORS = {new Color(230, 70, 70), new Color(90, 200, 90), new Color(80, 130, 245)};
	static final Color ACTIVE_COLOR = new Color(255, 220, 40);

	TDenlive tde = null;
	public Canvas canvas = null;
	Source src = null;
	//Ids of the selected nodes, the last one is the main one
	public Vector<Integer> selection = new Vector<Integer>();
	public int tool = TOOL_MOVE;
	public int axis = -1;//Constraint of the drags of the objects: -1 free, 0 X, 1 Y, 2 Z
	public boolean snap = false;
	public boolean showGrid = true;
	//Both eyes mixed in the mode of the monitor, view only (null mixer: never)
	public boolean stereoPreview = false;
	Mixer mixer = null;
	//Gizmo handles and frame size reduced for a small view
	int gizmoPx = GIZMO_PX;
	int margin = MARGIN;

	Renderer3D.Result lastRender = null;
	int drag = DRAG_NONE;
	int dragX0, dragY0, lastX, lastY;
	boolean dragShift = false;
	boolean dragDeep = false;
	int dragAxis = -1;
	//Local transforms and world transforms of the moved nodes at the start of the drag
	HashMap<Integer,Object3D> dragLocal = null;
	HashMap<Integer,double[]> dragWorld = null;
	double[] dragCenter = null;
	Camera3D dragCam = null;
	int hoverAxis = -1;
	double gestureDistance = 1;

	public Model3DView(TDenlive aTDe, Composite aParent, int aStyle, Source aSource, Mixer aMixer) {
		tde = aTDe;
		src = aSource;
		mixer = aMixer;
		canvas = new Canvas(aParent, aStyle | SWT.DOUBLE_BUFFERED);
		canvas.addListener(SWT.Paint, e -> draw(e.gc));
		canvas.addListener(SWT.MouseDown, e -> mouseDown(e));
		canvas.addListener(SWT.MouseMove, e -> mouseMove(e));
		canvas.addListener(SWT.MouseUp, e -> mouseUp(e));
		canvas.addListener(SWT.MouseVerticalWheel, e -> {
			zoom(e.count);
			canvas.redraw();
		});
		canvas.addListener(SWT.MouseExit, e -> {
			if(hoverAxis >= 0 && drag == DRAG_NONE) {
				hoverAxis = -1;
				canvas.redraw();
			}
		});
		canvas.addGestureListener(e -> {
			if(e.detail == SWT.GESTURE_BEGIN) {
				gestureDistance = cam().distance;
			}
			else if(e.detail == SWT.GESTURE_MAGNIFY && e.magnification > 0) {
				cam().distance = clampDistance(gestureDistance/e.magnification);
				canvas.redraw();
			}
		});
		canvas.addListener(SWT.KeyDown, e -> keyDown(e));
		canvas.addListener(SWT.Resize, e -> canvas.redraw());
	}

	/**
	 * Small view (processor panel): shorter handles, thinner margins
	 */
	public void setCompact(boolean aCompact) {
		gizmoPx = aCompact ? 50 : GIZMO_PX;
		margin = aCompact ? 6 : MARGIN;
	}

	public boolean isDragging() {
		return drag != DRAG_NONE;
	}

	Model3D model() {
		return src.getModel();
	}

	Model3D cam() {
		return src.getCamera();
	}

	/**
	 * The model with the view of the camera holder
	 */
	Model3D viewModel() {
		Model3D aM = model();
		Model3D aC = cam();
		if(aM == aC) {
			return aM;
		}
		Model3D aV = new Model3D();
		aV.objects = aM.objects;
		aV.copyCameraFrom(aC);
		aV.depth = aM.depth;
		return aV;
	}

	public void redraw() {
		if(!canvas.isDisposed()) {
			canvas.redraw();
		}
	}

	//== Selection ==

	public Object3D main() {
		Model3D aM = model();
		for(int s = selection.size()-1;s >= 0;s--) {
			Object3D aO = aM.findById(selection.elementAt(s));
			if(aO != null) {
				return aO;
			}
		}
		return null;
	}

	public Vector<Object3D> selectedObjects() {
		Vector<Object3D> aOs = new Vector<Object3D>();
		for(Object3D aO : model().objects) {
			if(selection.contains(aO.id)) {
				aOs.add(aO);
			}
		}
		return aOs;
	}

	/**
	 * Selected nodes that are not below another selected node (moved as a whole)
	 */
	public Vector<Object3D> roots() {
		Model3D aM = model();
		Vector<Object3D> aRoots = new Vector<Object3D>();
		for(Object3D aO : selectedObjects()) {
			boolean aUnder = false;
			for(Integer aId : selection) {
				if(aId != aO.id && aM.isUnder(aO, aId)) {
					aUnder = true;
					break;
				}
			}
			if(!aUnder) {
				aRoots.add(aO);
			}
		}
		return aRoots;
	}

	public void cleanSelection() {
		Model3D aM = model();
		selection.removeIf(aId -> aM.findById(aId) == null);
	}

	double[] selectionCenter() {
		Vector<Object3D> aOs = roots();
		if(aOs.isEmpty()) {
			return null;
		}
		Model3D aM = model();
		double[] aC = {0, 0, 0};
		for(Object3D aO : aOs) {
			aC = Math3D.add(aC, aM.worldPosition(aO));
		}
		return Math3D.mul(aC, 1.0/aOs.size());
	}

	public void setTool(int aTool) {
		tool = aTool;
		src.selectionChanged();
		redraw();
	}

	public void setAxis(int aAxis) {
		axis = aAxis;
		src.selectionChanged();
		redraw();
	}

	//== View ==

	Rectangle frameRect(int aNbViews) {
		Rectangle aCA = canvas.getClientArea();
		double aRatio = Math.max(0.2, tde.config.outRatio)*aNbViews;
		double aW = Math.max(10, aCA.width-2*margin);
		double aH = Math.max(10, aCA.height-2*margin);
		if(aW/aH > aRatio) {
			aW = aH*aRatio;
		}
		else {
			aH = aW/aRatio;
		}
		return new Rectangle((int)((aCA.width-aW)/2), (int)((aCA.height-aH)/2), (int)aW, (int)aH);
	}

	Camera3D viewCamera() {
		Rectangle aCA = canvas.getClientArea();
		return new Camera3D(viewModel(), aCA.width/2.0, aCA.height/2.0, frameRect(1).height, 0);
	}

	public void setView(double aYaw, double aPitch) {
		cam().yaw = aYaw;
		cam().pitch = aPitch;
		redraw();
		canvas.setFocus();
	}

	public void frameSelection() {
		Vector<Object3D> aOs = roots();
		Model3D aV = viewModel();
		aV.frame(aOs.isEmpty() ? null : aOs, tde.config.outRatio);
		if(aV != cam()) {
			cam().copyCameraFrom(aV);
		}
		redraw();
		canvas.setFocus();
	}

	static double clampDistance(double aD) {
		return Math.max(0.05, Math.min(1000, aD));
	}

	void zoom(int aCount) {
		cam().distance = clampDistance(cam().distance*Math.pow(0.92, aCount));
	}

	void orbit(int aDx, int aDy) {
		Model3D aC = cam();
		aC.yaw = (aC.yaw - aDx*0.4) % 360;
		aC.pitch = Math.max(Model3D.MIN_PITCH, Math.min(Model3D.MAX_PITCH, aC.pitch + aDy*0.4));
	}

	void pan(int aDx, int aDy) {
		double[] aD = viewCamera().screenDelta(aDx, aDy, cam().distance);
		cam().setTarget(Math3D.sub(cam().getTarget(), aD));
	}

	//== Mouse ==

	void mouseDown(Event e) {
		canvas.setFocus();
		dragX0 = lastX = e.x;
		dragY0 = lastY = e.y;
		dragShift = (e.stateMask & SWT.SHIFT) != 0;
		dragDeep = (e.stateMask & SWT.MOD1) != 0;
		if(e.button > 3) {
			return;
		}
		if(e.button == 2 || e.button == 3 || (e.stateMask & SWT.ALT) != 0 || stereoPreview) {
			drag = dragShift ? DRAG_PAN : DRAG_ORBIT;
			return;
		}
		int aHandle = handleAt(e.x, e.y);
		if(aHandle >= 0) {
			startTransform(aHandle);
			drag = DRAG_TRANSFORM;
			return;
		}
		int aId = lastRender != null ? lastRender.idAt(e.x, e.y) : -1;
		Object3D aHit = aId >= 0 ? model().findById(aId) : null;
		if(aHit == null) {
			drag = DRAG_EMPTY;
			return;
		}
		//The object itself with Cmd / Ctrl; else the node already selected that holds it (an object chosen
		//inside a group stays the one edited), else the group of the root level
		if(!dragDeep) {
			Object3D aSelected = null;
			for(Object3D aN = aHit;aN != null;aN = model().parentOf(aN)) {
				if(selection.contains(aN.id)) {
					aSelected = aN;
					break;
				}
			}
			aHit = aSelected != null ? aSelected : model().topLevel(aHit);
		}
		aId = aHit.id;
		if(dragShift) {
			if(selection.contains(aId)) {
				selection.remove((Integer)aId);
			}
			else {
				selection.add(aId);
			}
			drag = DRAG_NONE;
		}
		else {
			if(!selection.contains(aId)) {
				selection.clear();
			}
			selection.remove((Integer)aId);
			selection.add(aId);
			drag = DRAG_PENDING;
		}
		src.selectionChanged();
		redraw();
	}

	void mouseMove(Event e) {
		if(drag == DRAG_NONE) {
			int aH = stereoPreview ? -1 : handleAt(e.x, e.y);
			if(aH != hoverAxis) {
				hoverAxis = aH;
				redraw();
			}
			return;
		}
		int aDx = e.x-lastX;
		int aDy = e.y-lastY;
		boolean aMoved = Math.abs(e.x-dragX0) > 3 || Math.abs(e.y-dragY0) > 3;
		if(drag == DRAG_EMPTY && aMoved) {
			drag = dragShift ? DRAG_PAN : DRAG_ORBIT;
		}
		if(drag == DRAG_PENDING && aMoved) {
			startTransform(-1);
			drag = DRAG_TRANSFORM;
		}
		switch(drag) {
		case DRAG_ORBIT:
			orbit(aDx, aDy);
			break;
		case DRAG_PAN:
			pan(aDx, aDy);
			break;
		case DRAG_TRANSFORM:
			applyTransform(e.x, e.y);
			src.changed(false);
			break;
		default:
			return;
		}
		lastX = e.x;
		lastY = e.y;
		redraw();
	}

	void mouseUp(Event e) {
		int aDrag = drag;
		drag = DRAG_NONE;
		dragLocal = null;
		dragWorld = null;
		if(aDrag == DRAG_EMPTY && !dragShift) {
			selection.clear();
			src.selectionChanged();
		}
		if(aDrag == DRAG_TRANSFORM) {
			src.changed(true);
		}
		redraw();
	}

	//== Gizmo and transforms ==

	/**
	 * Gizmo axes: world axes to move and turn, axes of the main object to scale
	 */
	double[][] gizmoAxes() {
		Object3D aM = main();
		if(tool == TOOL_SCALE && aM != null) {
			double[] aW = Model3D.matrixOf(model().world(aM));
			return new double[][] {Math3D.normalize(Math3D.column(aW, 0)), Math3D.normalize(Math3D.column(aW, 1)), Math3D.normalize(Math3D.column(aW, 2))};
		}
		return new double[][] {{1, 0, 0}, {0, 1, 0}, {0, 0, 1}};
	}

	double gizmoLength(Camera3D aCam, double[] aCenter) {
		double aZ = Math.max(Camera3D.NEAR, aCam.toCam(aCenter)[2]);
		return gizmoPx*aZ/aCam.f;
	}

	static Vector<double[]> ring(Camera3D aCam, double[] aC, int aAxis, double aL) {
		Vector<double[]> aPts = new Vector<double[]>();
		for(int k = 0;k <= 48;k++) {
			double aA = 2*Math.PI*k/48;
			double[] aP = aC.clone();
			aP[(aAxis+1)%3] += aL*Math.cos(aA);
			aP[(aAxis+2)%3] += aL*Math.sin(aA);
			aPts.add(aCam.project(aP));
		}
		return aPts;
	}

	static double segDist(double aX, double aY, double[] aA, double[] aB) {
		if(aA == null || aB == null) {
			return Double.MAX_VALUE;
		}
		double aDx = aB[0]-aA[0], aDy = aB[1]-aA[1];
		double aL2 = aDx*aDx + aDy*aDy;
		double aT = aL2 < 1e-9 ? 0 : Math.max(0, Math.min(1, ((aX-aA[0])*aDx + (aY-aA[1])*aDy)/aL2));
		double aPx = aA[0]+aT*aDx - aX, aPy = aA[1]+aT*aDy - aY;
		return Math.sqrt(aPx*aPx + aPy*aPy);
	}

	int handleAt(int aX, int aY) {
		double[] aC = selectionCenter();
		if(aC == null || stereoPreview) {
			return -1;
		}
		Camera3D aCam = viewCamera();
		if(aCam.project(aC) == null) {
			return -1;
		}
		double aL = gizmoLength(aCam, aC);
		double[][] aAxes = gizmoAxes();
		int aBest = -1;
		double aBestD = HIT_PX;
		for(int a = 0;a < 3;a++) {
			double aD = Double.MAX_VALUE;
			if(tool == TOOL_ROTATE) {
				Vector<double[]> aPts = ring(aCam, aC, a, aL);
				for(int k = 0;k < aPts.size()-1;k++) {
					aD = Math.min(aD, segDist(aX, aY, aPts.elementAt(k), aPts.elementAt(k+1)));
				}
			}
			else {
				//The end part of the handle: the center stays free to drag the object
				double[] aP0 = aCam.project(Math3D.add(aC, Math3D.mul(aAxes[a], aL*0.3)));
				double[] aP1 = aCam.project(Math3D.add(aC, Math3D.mul(aAxes[a], aL*1.1)));
				aD = segDist(aX, aY, aP0, aP1);
			}
			if(aD < aBestD) {
				aBestD = aD;
				aBest = a;
			}
		}
		return aBest;
	}

	void startTransform(int aHandle) {
		src.beginChange();
		cleanSelection();
		Model3D aM = model();
		dragLocal = new HashMap<Integer,Object3D>();
		dragWorld = new HashMap<Integer,double[]>();
		for(Object3D aO : roots()) {
			dragLocal.put(aO.id, aO.copy());
			dragWorld.put(aO.id, aM.world(aO));
		}
		dragCenter = selectionCenter();
		dragCam = viewCamera();
		dragAxis = aHandle >= 0 ? aHandle : axis;
	}

	double axisAmount(double[] aC, double[] aAxis, double aDx, double aDy) {
		double[] aP0 = dragCam.project(aC);
		double[] aP1 = dragCam.project(Math3D.add(aC, aAxis));
		if(aP0 == null || aP1 == null) {
			return 0;
		}
		double aSx = aP1[0]-aP0[0], aSy = aP1[1]-aP0[1];
		double aL2 = aSx*aSx + aSy*aSy;
		if(aL2 < 1e-6) {
			return 0;
		}
		return (aDx*aSx + aDy*aSy)/aL2;
	}

	public static double snapValue(double aV, double aStep) {
		return Math.round(aV/aStep)*aStep;
	}

	/**
	 * The moved nodes from their state at the start of the drag, for the total move of the mouse
	 */
	void applyTransform(int aX, int aY) {
		if(dragLocal == null || dragCenter == null) {
			return;
		}
		Model3D aM = model();
		double aDx = aX-dragX0, aDy = aY-dragY0;
		double[] aC = dragCenter;
		for(Integer aId : dragLocal.keySet()) {
			Object3D aT = aM.findById(aId);
			if(aT != null) {
				dragLocal.get(aId).copyTransformTo(aT);
			}
		}
		if(tool == TOOL_MOVE) {
			double[] aDelta;
			if(dragAxis >= 0) {
				double[] aAx = new double[3];
				aAx[dragAxis] = 1;
				double aAmount = axisAmount(aC, aAx, aDx, aDy);
				if(snap) {
					aAmount = snapValue(aAmount, GRID_STEP);
				}
				aDelta = Math3D.mul(aAx, aAmount);
			}
			else {
				aDelta = dragCam.screenDelta(aDx, aDy, dragCam.toCam(aC)[2]);
			}
			for(Integer aId : dragLocal.keySet()) {
				Object3D aT = aM.findById(aId);
				if(aT == null) {
					continue;
				}
				double[] aW = dragWorld.get(aId);
				double[] aP = Math3D.add(Model3D.translationOf(aW), aDelta);
				if(snap && dragAxis < 0) {
					for(int c = 0;c < 3;c++) {
						aP[c] = snapValue(aP[c], GRID_STEP);
					}
				}
				aM.setWorld(aT, Model3D.matrixOf(aW), aP);
			}
		}
		else if(tool == TOOL_ROTATE) {
			double[] aR;
			if(dragAxis >= 0) {
				double[] aAx = new double[3];
				aAx[dragAxis] = 1;
				double[] aPc = dragCam.project(aC);
				if(aPc == null) {
					return;
				}
				double aA0 = Math.atan2(-(dragY0-aPc[1]), dragX0-aPc[0]);
				double aA1 = Math.atan2(-(aY-aPc[1]), aX-aPc[0]);
				double aDeg = Math.toDegrees(aA1-aA0);
				//Counterclockwise on the screen = positive around an axis pointing to the viewer
				if(Math3D.dot(aAx, Math3D.sub(dragCam.pos, aC)) < 0) {
					aDeg = -aDeg;
				}
				if(snap) {
					aDeg = snapValue(aDeg, ANGLE_STEP);
				}
				aR = Math3D.rotation(aAx, Math.toRadians(aDeg));
			}
			else {
				aR = Math3D.mulM(Math3D.rotation(dragCam.up, aDx*0.01), Math3D.rotation(dragCam.right, aDy*0.01));
			}
			for(Integer aId : dragLocal.keySet()) {
				Object3D aT = aM.findById(aId);
				if(aT == null) {
					continue;
				}
				double[] aW = dragWorld.get(aId);
				aM.setWorld(aT, Math3D.mulM(aR, Model3D.matrixOf(aW)), Math3D.add(aC, Math3D.apply(aR, Math3D.sub(Model3D.translationOf(aW), aC))));
			}
		}
		else {
			Object3D aMain = main();
			if(dragAxis >= 0 && aMain != null && dragWorld.containsKey(aMain.id)) {
				//Along an axis of the main object: its local scale (the others: the same factor)
				double[] aAx = Math3D.normalize(Math3D.column(Model3D.matrixOf(dragWorld.get(aMain.id)), dragAxis));
				double aL = gizmoLength(dragCam, aC);
				double aK = Math.max(0.01, 1 + axisAmount(aC, aAx, aDx, aDy)/aL);
				for(Integer aId : dragLocal.keySet()) {
					Object3D aT = aM.findById(aId);
					if(aT == null) {
						continue;
					}
					double[] aS = dragLocal.get(aId).getScale();
					aS[dragAxis] = snap ? Math.max(SCALE_STEP, snapValue(aS[dragAxis]*aK, SCALE_STEP)) : aS[dragAxis]*aK;
					aT.setScale(aS);
				}
			}
			else {
				double aK = Math.exp((aDx-aDy)*0.01);
				for(Integer aId : dragLocal.keySet()) {
					Object3D aT = aM.findById(aId);
					if(aT == null) {
						continue;
					}
					double[] aW = dragWorld.get(aId);
					aM.setWorld(aT, Math3D.scaleM(Model3D.matrixOf(aW), aK), Math3D.add(aC, Math3D.mul(Math3D.sub(Model3D.translationOf(aW), aC), aK)));
					if(snap) {
						double[] aS = aT.getScale();
						for(int c = 0;c < 3;c++) {
							aS[c] = Math.max(SCALE_STEP, snapValue(aS[c], SCALE_STEP));
						}
						aT.setScale(aS);
					}
				}
			}
		}
	}

	//== Keyboard ==

	void keyDown(Event e) {
		if(src.extraKey(e)) {
			return;
		}
		if((e.stateMask & SWT.MOD1) != 0) {
			return;
		}
		boolean aShift = (e.stateMask & SWT.SHIFT) != 0;
		int aK = e.keyCode;
		if(aK >= 'A' && aK <= 'Z') {
			aK += 'a'-'A';
		}
		switch(aK) {
		case SWT.ESC:
			selection.clear();
			src.selectionChanged();
			break;
		case 'g':
		case 'm':
			setTool(TOOL_MOVE);
			return;
		case 'r':
			setTool(TOOL_ROTATE);
			return;
		case 's':
			setTool(TOOL_SCALE);
			return;
		case 'x':
		case 'y':
		case 'z':
			int aAxis = aK-'x';
			setAxis(axis == aAxis ? -1 : aAxis);
			return;
		case 'f':
			frameSelection();
			return;
		case '1':
		case SWT.KEYPAD_1:
			setView(0, 0);
			return;
		case '3':
		case SWT.KEYPAD_3:
			setView(90, 0);
			return;
		case '7':
		case SWT.KEYPAD_7:
			setView(0, Model3D.MAX_PITCH);
			return;
		case '5':
		case '0':
		case SWT.KEYPAD_5:
		case SWT.KEYPAD_0:
			setView(35, 25);
			return;
		case SWT.ARROW_LEFT:
		case SWT.ARROW_RIGHT:
		case SWT.ARROW_UP:
		case SWT.ARROW_DOWN:
		case SWT.PAGE_UP:
		case SWT.PAGE_DOWN:
			arrow(aK, aShift);
			break;
		default:
			return;
		}
		redraw();
	}

	/**
	 * Arrows: move the selection on the floor (page up / down: height), or turn the view if nothing is selected
	 */
	void arrow(int aKey, boolean aShift) {
		if(roots().isEmpty()) {
			switch(aKey) {
			case SWT.ARROW_LEFT: orbit(-38, 0); break;
			case SWT.ARROW_RIGHT: orbit(38, 0); break;
			case SWT.ARROW_UP: orbit(0, -38); break;
			case SWT.ARROW_DOWN: orbit(0, 38); break;
			case SWT.PAGE_UP: zoom(1); break;
			case SWT.PAGE_DOWN: zoom(-1); break;
			}
			return;
		}
		double aStep = (snap ? GRID_STEP : 0.1)*(aShift ? 0.1 : 1);
		double[] aD = new double[3];
		switch(aKey) {
		case SWT.ARROW_LEFT: aD[0] = -aStep; break;
		case SWT.ARROW_RIGHT: aD[0] = aStep; break;
		case SWT.ARROW_UP: aD[2] = -aStep; break;
		case SWT.ARROW_DOWN: aD[2] = aStep; break;
		case SWT.PAGE_UP: aD[1] = aStep; break;
		case SWT.PAGE_DOWN: aD[1] = -aStep; break;
		}
		src.beginChange();
		cleanSelection();
		Model3D aM = model();
		for(Object3D aO : roots()) {
			double[] aW = aM.world(aO);
			aM.setWorld(aO, Model3D.matrixOf(aW), Math3D.add(Model3D.translationOf(aW), aD));
		}
		src.changed(true);
	}

	//== Drawing ==

	void draw(GC aGC) {
		Rectangle aCA = canvas.getClientArea();
		if(aCA.width <= 0 || aCA.height <= 0) {
			return;
		}
		if(stereoPreview && mixer != null) {
			drawStereo(aGC, aCA);
			return;
		}
		Model3D aV = viewModel();
		Rectangle aF = frameRect(1);
		Renderer3D.Options aOpt = new Renderer3D.Options();
		aOpt.ss = drag == DRAG_NONE || drag == DRAG_PENDING || drag == DRAG_EMPTY ? 2 : 1;
		aOpt.bck = BCK;
		aOpt.grid = showGrid;
		aOpt.frameH = aF.height;
		aOpt.ids = true;
		//Outline of the selected nodes: the objects below them
		HashSet<Integer> aOutlined = new HashSet<Integer>();
		for(Object3D aO : aV.objects) {
			for(Integer aId : selection) {
				if(aV.isUnder(aO, aId)) {
					aOutlined.add(aO.id);
					break;
				}
			}
		}
		aOpt.selected = aOutlined;
		lastRender = Renderer3D.render(aV, aCA.width, aCA.height, 0, 0, aOpt);
		Image aI = ImageUtils.imageToSwt(tde.gui.display, lastRender.image);
		aGC.drawImage(aI, 0, 0);
		aI.dispose();

		//Outside the output frame: darker
		aGC.setAlpha(110);
		aGC.setBackground(tde.gui.display.getSystemColor(SWT.COLOR_BLACK));
		aGC.fillRectangle(0, 0, aCA.width, aF.y);
		aGC.fillRectangle(0, aF.y+aF.height, aCA.width, aCA.height-aF.y-aF.height);
		aGC.fillRectangle(0, aF.y, aF.x, aF.height);
		aGC.fillRectangle(aF.x+aF.width, aF.y, aCA.width-aF.x-aF.width, aF.height);
		aGC.setAlpha(255);
		aGC.setForeground(tde.gui.display.getSystemColor(SWT.COLOR_GRAY));
		aGC.drawRectangle(aF.x-1, aF.y-1, aF.width+1, aF.height+1);

		aGC.setAntialias(SWT.ON);
		drawGizmo(aGC);
		drawTriad(aGC, aCA);
		String aInfo = l(TOOL_KEYS[tool]) + "  ·  " + (axis < 0 ? l("Free") : new String[] {"X", "Y", "Z"}[axis]) + (snap ? "  ·  " + l("Snap") : "");
		drawText(aGC, aInfo, 8, 6);
		if(aV.objects.isEmpty()) {
			String aHint = l("EmptyHint");
			Point aE = aGC.textExtent(aHint);
			drawText(aGC, aHint, (aCA.width-aE.x)/2, (aCA.height-aE.y)/2);
		}
	}

	String l(String aKey) {
		return tde.gui.lngs.get("Model3D."+aKey);
	}

	void drawText(GC aGC, String aText, int aX, int aY) {
		aGC.setFont(tde.gui.fontsSwt.roboto);
		Point aE = aGC.textExtent(aText);
		aGC.setAlpha(150);
		aGC.setBackground(tde.gui.display.getSystemColor(SWT.COLOR_BLACK));
		aGC.fillRoundRectangle(aX-4, aY-2, aE.x+8, aE.y+4, 6, 6);
		aGC.setAlpha(255);
		aGC.setForeground(tde.gui.display.getSystemColor(SWT.COLOR_WHITE));
		aGC.drawText(aText, aX, aY, true);
	}

	void drawGizmo(GC aGC) {
		double[] aC = selectionCenter();
		if(aC == null) {
			return;
		}
		Camera3D aCam = viewCamera();
		double[] aPc = aCam.project(aC);
		if(aPc == null) {
			return;
		}
		double aL = gizmoLength(aCam, aC);
		double[][] aAxes = gizmoAxes();
		int aActive = drag == DRAG_TRANSFORM ? dragAxis : hoverAxis;
		if(drag == DRAG_TRANSFORM && dragAxis >= 0 && tool != TOOL_ROTATE) {
			double[] aP0 = aCam.project(Math3D.sub(aC, Math3D.mul(aAxes[dragAxis], aL*20)));
			double[] aP1 = aCam.project(Math3D.add(aC, Math3D.mul(aAxes[dragAxis], aL*20)));
			if(aP0 != null && aP1 != null) {
				aGC.setLineWidth(1);
				aGC.setForeground(AXIS_COLORS[dragAxis]);
				aGC.drawLine((int)aP0[0], (int)aP0[1], (int)aP1[0], (int)aP1[1]);
			}
		}
		for(int a = 0;a < 3;a++) {
			Color aCol = a == aActive ? ACTIVE_COLOR : AXIS_COLORS[a];
			aGC.setForeground(aCol);
			aGC.setBackground(aCol);
			aGC.setLineWidth(a == aActive ? 3 : 2);
			if(tool == TOOL_ROTATE) {
				Vector<double[]> aPts = ring(aCam, aC, a, aL);
				for(int k = 0;k < aPts.size()-1;k++) {
					double[] aP = aPts.elementAt(k), aQ = aPts.elementAt(k+1);
					if(aP != null && aQ != null) {
						aGC.drawLine((int)aP[0], (int)aP[1], (int)aQ[0], (int)aQ[1]);
					}
				}
				continue;
			}
			double[] aP1 = aCam.project(Math3D.add(aC, Math3D.mul(aAxes[a], aL)));
			if(aP1 == null) {
				continue;
			}
			aGC.drawLine((int)aPc[0], (int)aPc[1], (int)aP1[0], (int)aP1[1]);
			if(tool == TOOL_SCALE) {
				aGC.fillRectangle((int)aP1[0]-4, (int)aP1[1]-4, 9, 9);
			}
			else {
				double aDx = aP1[0]-aPc[0], aDy = aP1[1]-aPc[1];
				double aLen = Math.sqrt(aDx*aDx + aDy*aDy);
				if(aLen > 1) {
					aDx /= aLen;
					aDy /= aLen;
					int[] aHead = {
							(int)(aP1[0]+aDx*10), (int)(aP1[1]+aDy*10),
							(int)(aP1[0]-aDy*5), (int)(aP1[1]+aDx*5),
							(int)(aP1[0]+aDy*5), (int)(aP1[1]-aDx*5)};
					aGC.fillPolygon(aHead);
				}
			}
		}
		aGC.setLineWidth(1);
		aGC.setBackground(tde.gui.display.getSystemColor(SWT.COLOR_WHITE));
		aGC.fillOval((int)aPc[0]-3, (int)aPc[1]-3, 7, 7);
	}

	void drawTriad(GC aGC, Rectangle aCA) {
		Camera3D aCam = viewCamera();
		int aR = gizmoPx < GIZMO_PX ? 14 : 22;
		int aX = aR+12, aY = aCA.height-aR-12;
		String[] aNames = {"X", "Y", "Z"};
		aGC.setLineWidth(2);
		aGC.setFont(tde.gui.fontsSwt.robotoBold);
		for(int a = 0;a < 3;a++) {
			double[] aAx = new double[3];
			aAx[a] = 1;
			int aEx = aX + (int)(Math3D.dot(aAx, aCam.right)*aR);
			int aEy = aY - (int)(Math3D.dot(aAx, aCam.up)*aR);
			aGC.setForeground(AXIS_COLORS[a]);
			aGC.drawLine(aX, aY, aEx, aEy);
			aGC.drawText(aNames[a], aEx + (aEx-aX)/4 - 3, aEy + (aEy-aY)/4 - 7, true);
		}
		aGC.setLineWidth(1);
	}

	void drawStereo(GC aGC, Rectangle aCA) {
		lastRender = null;
		aGC.setBackground(tde.gui.colorsSwt.DARK_GRAY);
		aGC.fillRectangle(aCA);
		Model3D aV = viewModel();
		int aN = Math.max(1, mixer.getNbViews());
		Rectangle aF = frameRect(aN);
		int aEW = Math.max(1, aF.width/aN);
		int aEH = Math.max(1, aF.height);
		Renderer3D.Options aOpt = new Renderer3D.Options();
		aOpt.ss = drag == DRAG_NONE ? 2 : 1;
		aOpt.bck = BCK;
		BufferedImage aLI = Renderer3D.render(aV, aEW, aEH, -1, 0, aOpt).image;
		BufferedImage aRI = Renderer3D.render(aV, aEW, aEH, 1, 0, aOpt).image;
		Image aI = null;
		try {
			aI = mixer.mix2Swt(aLI, aRI, TDConfig.PROCESSING_MODE_WORK, new Rectangle(0, 0, aEW*aN, aEH));
			aGC.drawImage(aI, aF.x, aF.y);
		}
		catch(Throwable t) {
			t.printStackTrace(System.err);
		}
		finally {
			if(aI != null) {
				aI.dispose();
			}
		}
		aGC.setAntialias(SWT.ON);
		drawText(aGC, l("StereoHint"), 8, 6);
	}
}
