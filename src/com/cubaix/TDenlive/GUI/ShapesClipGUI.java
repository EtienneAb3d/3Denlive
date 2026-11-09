package com.cubaix.TDenlive.GUI;

import java.awt.image.BufferedImage;
import java.util.Vector;

import org.eclipse.swt.SWT;
import org.eclipse.swt.events.SelectionAdapter;
import org.eclipse.swt.events.SelectionEvent;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.graphics.Rectangle;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Canvas;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Shell;

import com.cubaix.TDenlive.TDConfig;
import com.cubaix.TDenlive.TDenlive;
import com.cubaix.TDenlive.processors.Mixer;
import com.cubaix.TDenlive.shapes.Shape;
import com.cubaix.TDenlive.shapes.ShapeRenderer;
import com.cubaix.TDenlive.shapes.ShapesEditor;

/**
 * Popup to create a shapes clip: vector editor on a frame of the output ratio,
 * with the tools and properties panel, and the Create / Cancel buttons.
 * open() returns the shapes, or null if canceled.
 */
public class ShapesClipGUI implements ShapesEditor.Listener {
	static final int MARGIN = 36;//Room for the handles around the frame
	TDenlive tde = null;
	Shell shell = null;
	Canvas canvas = null;
	ShapesEditor editor = null;
	ShapesPanel panel = null;
	Vector<Shape> shapes = new Vector<Shape>();
	Vector<Shape> result = null;
	boolean isMouseDown = false;
	//Same view mode as the monitor (anaglyph, side by side...), without its zoom
	Mixer mixer = null;

	public ShapesClipGUI(TDenlive aTDe) {
		tde = aTDe;
		mixer = tde.gui.monitorGUI.mixer.copyMode();
		editor = new ShapesEditor(new ShapesEditor.Source() {
			@Override
			public Vector<Shape> getShapes() {
				return shapes;
			}
			@Override
			public Vector<Shape> getShapesForEdit() {
				return shapes;
			}
			@Override
			public void addShape(Shape aS) {
				int aId = 0;
				for(Shape aS2 : shapes) {
					aId = Math.max(aId, aS2.id+1);
				}
				aS.id = aId;
				aS.hidden = false;
				shapes.add(aS);
			}
			@Override
			public void deleteShape(int aId) {
				Shape aS = Shape.findById(shapes, aId);
				if(aS != null) {
					shapes.remove(aS);
				}
			}
			@Override
			public void setHidden(int aId, boolean aHidden) {
				Shape aS = Shape.findById(shapes, aId);
				if(aS != null) {
					aS.hidden = aHidden;
				}
			}
			@Override
			public void reorder(int aId, boolean aToFront) {
				Shape aS = Shape.findById(shapes, aId);
				if(aS != null) {
					shapes.remove(aS);
					if(aToFront) {
						shapes.add(aS);
					}
					else {
						shapes.add(0, aS);
					}
				}
			}
			@Override
			public boolean showGhosts() {
				return false;
			}
		});
		//A first shape tool ready to draw
		editor.setTool(Shape.RECT);
		editor.addListener(this);
		createContents();
	}

	public Vector<Shape> open() {
		shell.pack();
		//Not larger than the screen (the buttons stay visible)
		Rectangle aScreen = tde.gui.shell.getMonitor().getClientArea();
		Rectangle aS = shell.getBounds();
		if(aS.width > aScreen.width || aS.height > aScreen.height) {
			shell.setSize(Math.min(aS.width, aScreen.width), Math.min(aS.height, aScreen.height));
			aS = shell.getBounds();
		}
		Rectangle aP = tde.gui.shell.getBounds();
		shell.setLocation(aP.x + (aP.width - aS.width) / 2, aP.y + (aP.height - aS.height) / 2);
		shell.open();
		while (!shell.isDisposed()) {
			if (!tde.gui.display.readAndDispatch()) {
				tde.gui.display.sleep();
			}
		}
		return result;
	}

	void createContents() {
		shell = new Shell(tde.gui.shell, SWT.DIALOG_TRIM | SWT.APPLICATION_MODAL | SWT.RESIZE);
		shell.setText(tde.gui.lngs.get("Shapes.Title"));
		shell.setLayout(new GridLayout(2, false));
		shell.addListener(SWT.Traverse, e -> {
			//ESC is for the editor (selection), not to close
			if(e.detail == SWT.TRAVERSE_ESCAPE) {
				e.doit = false;
			}
		});
		tde.gui.applyColorFont(shell);
		shell.setImage(tde.gui.imgsSwt.getIcon("3Denlive32.png"));

		Composite aLeft = new Composite(shell, SWT.NONE);
		GridData aLGD = new GridData(GridData.FILL_VERTICAL);
		aLGD.widthHint = 270;
		aLeft.setLayoutData(aLGD);
		tde.gui.applyColorFont(aLeft);
		GridLayout aLGL = new GridLayout(1, false);
		tde.gui.applyNoMargin(aLGL);
		aLeft.setLayout(aLGL);
		panel = new ShapesPanel(tde, aLeft, editor, false);
		createButtons(aLeft);

		canvas = new Canvas(shell, SWT.BORDER | SWT.DOUBLE_BUFFERED);
		GridData aCGD = new GridData(GridData.FILL_BOTH);
		int aNbViews = nbViews();
		aCGD.widthHint = aNbViews > 1 ? 960 : 720;
		aCGD.heightHint = (int)(aCGD.widthHint/(aNbViews*Math.max(0.2, tde.config.outRatio)))+2*MARGIN/3;
		canvas.setLayoutData(aCGD);
		canvas.addListener(SWT.Paint, e -> draw(e.gc));
		canvas.addListener(SWT.MouseDown, e -> {
			if(e.button != 1) {
				return;
			}
			canvas.setFocus();
			setView();
			isMouseDown = editor.mouseDown(e.x, e.y);
			canvas.redraw();
		});
		canvas.addListener(SWT.MouseMove, e -> {
			if(isMouseDown) {
				editor.mouseMove(e.x, e.y);
			}
			else {
				int aC = editor.getCursorAt(e.x, e.y);
				canvas.setCursor(aC < 0 ? null : tde.gui.display.getSystemCursor(aC));
			}
		});
		canvas.addListener(SWT.MouseUp, e -> {
			if(isMouseDown) {
				isMouseDown = false;
				editor.mouseUp(e.x, e.y);
				canvas.redraw();
			}
		});
		canvas.addListener(SWT.KeyDown, e -> {
			if(e.keyCode == SWT.DEL || e.keyCode == SWT.BS) {
				editor.deleteSelected();
			}
			else if(e.keyCode == SWT.ESC) {
				editor.setTool(-1);
				editor.select(-1);
				canvas.redraw();
			}
		});
		canvas.addListener(SWT.Resize, e -> canvas.redraw());

	}

	/**
	 * Save / Cancel, at the bottom of the tools panel
	 */
	void createButtons(Composite aParent) {
		Composite aButtons = new Composite(aParent, SWT.NONE);
		aButtons.setLayoutData(new GridData(GridData.FILL_HORIZONTAL | GridData.VERTICAL_ALIGN_END | GridData.GRAB_VERTICAL));
		tde.gui.applyColorFont(aButtons);
		GridLayout aBGL = new GridLayout(2, true);
		aBGL.marginWidth = 2;
		aBGL.marginHeight = 4;
		aButtons.setLayout(aBGL);

		Button aCancel = new Button(aButtons, SWT.PUSH);
		aCancel.setText(tde.gui.lngs.get("Shapes.Cancel"));
		tde.gui.applyColorFont(aCancel);
		aCancel.setLayoutData(new GridData(GridData.FILL_HORIZONTAL));
		aCancel.addSelectionListener(new SelectionAdapter() {
			@Override
			public void widgetSelected(SelectionEvent arg0) {
				result = null;
				shell.close();
			}
		});

		Button aSave = new Button(aButtons, SWT.PUSH);
		aSave.setText(tde.gui.lngs.get("Shapes.Save"));
		aSave.setImage(tde.gui.imgsSwt.getIcon("save.gif"));
		tde.gui.applyColorFont(aSave);
		aSave.setLayoutData(new GridData(GridData.FILL_HORIZONTAL));
		aSave.addSelectionListener(new SelectionAdapter() {
			@Override
			public void widgetSelected(SelectionEvent arg0) {
				result = Shape.copyAll(shapes);
				shell.close();
			}
		});
	}

	int nbViews() {
		return Math.max(1, mixer.getNbViews());
	}

	/**
	 * Image of the frame(s) of the output ratio (side by side: one per view), centered in the canvas
	 */
	Rectangle frameRect() {
		Rectangle aCA = canvas.getClientArea();
		double aRatio = Math.max(0.2, tde.config.outRatio)*nbViews();
		double aW = Math.max(10, aCA.width-2*MARGIN);
		double aH = Math.max(10, aCA.height-2*MARGIN);
		if(aW/aH > aRatio) {
			aW = aH*aRatio;
		}
		else {
			aH = aW/aRatio;
		}
		return new Rectangle((int)((aCA.width-aW)/2), (int)((aCA.height-aH)/2), (int)aW, (int)aH);
	}

	/**
	 * Views of the frame as on the monitor: one per image side by side, and one per eye when
	 * both eyes are on the same image (anaglyph...): a shape shifted in depth has its handles doubled
	 */
	void setView() {
		Rectangle aF = frameRect();
		int aN = nbViews();
		double aSW = aF.width/(double)aN;
		Vector<ShapesEditor.View> aVs = new Vector<ShapesEditor.View>();
		for(int v = 0;v < aN;v++) {
			int aEye = mixer.getViewEye(v);
			int[] aEyes = aEye == 0 ? new int[] {-1,1} : new int[] {aEye};
			for(int aE : aEyes) {
				ShapesEditor.View aV = new ShapesEditor.View(aF.x+v*aSW, aF.y, aSW, aF.height, aE);
				aV.group = v;
				aVs.add(aV);
			}
		}
		editor.setViews(aVs);
	}

	void draw(org.eclipse.swt.graphics.GC aGC) {
		Rectangle aCA = canvas.getClientArea();
		aGC.setBackground(tde.gui.colorsSwt.DARK_GRAY);
		aGC.fillRectangle(aCA);
		setView();
		Rectangle aF = frameRect();
		//Left and right images of the shapes (shifted by their depth), mixed as on the monitor
		int aN = nbViews();
		int aEW = Math.max(1, aF.width/aN);
		int aEH = Math.max(1, aF.height);
		BufferedImage aLI = ShapeRenderer.render(shapes, aEW, aEH, -1);
		BufferedImage aRI = ShapeRenderer.render(shapes, aEW, aEH, 1);
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
		aGC.setForeground(tde.gui.colorsSwt.WHITE);
		for(int v = 0;v < aN;v++) {
			int aX0 = aF.x+(int)Math.round(v*aF.width/(double)aN);
			int aX1 = aF.x+(int)Math.round((v+1)*aF.width/(double)aN);
			aGC.drawRectangle(aX0-1, aF.y-1, aX1-aX0+1, aF.height+1);
		}
		editor.drawOverlay(aGC, aF, aN, aCA.height);
	}

	@Override
	public void shapesChanged() {
		if(!canvas.isDisposed()) {
			canvas.redraw();
		}
	}

	@Override
	public void selectionChanged() {
		if(!canvas.isDisposed()) {
			canvas.redraw();
		}
	}
}
