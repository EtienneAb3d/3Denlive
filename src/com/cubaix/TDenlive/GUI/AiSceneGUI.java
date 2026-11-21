package com.cubaix.TDenlive.GUI;

import java.awt.Graphics;
import java.awt.image.BufferedImage;
import java.io.File;

import org.eclipse.swt.SWT;
import org.eclipse.swt.events.SelectionAdapter;
import org.eclipse.swt.events.SelectionEvent;
import org.eclipse.swt.graphics.GC;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.graphics.Rectangle;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Canvas;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Scale;
import org.eclipse.swt.widgets.Shell;

import com.cubaix.TDenlive.TDConfig;
import com.cubaix.TDenlive.TDenlive;
import com.cubaix.TDenlive.model3d.GaussianScene;
import com.cubaix.TDenlive.model3d.StereoCam;
import com.cubaix.TDenlive.processors.Mixer;
import com.cubaix.TDenlive.utils.ImageUtils;

/**
 * Viewer of a 3D scene of Gaussians (made from a photo by Flash3D, see ai.SceneGenerator, or a PLY saved before):
 * a pair of parallel stereo cameras, side by side along their right axis, their spacing set by a slider, zero
 * parallax at the distance of the screen plane (second slider: nearer objects come out of the screen), shown in the
 * mode of the monitor (anaglyph, side by side...). The pair starts at the viewpoint of the photo and moves in the
 * scene: drag = turn around the point of the screen plane in front of it, Shift + drag or right drag = move in its
 * plane, MOD1 + drag = look around (turn in place), wheel / arrows = forward, back, sideways, Page up / down = up,
 * down. Rendered by GaussianScene in a thread (at half the size while the mouse drags), the latest view wins.
 * "Add this stereo view" renders both eyes at the full screen resolution and adds them side by side (parallel) to
 * the media list.
 */
public class AiSceneGUI {
	//Slider of the spacing: 0 = none, else 1 mm to 2 m (logarithmic)
	static final double BASE_MIN = 0.001;
	static final double BASE_MAX = 2.0;
	//Slider of the screen plane: 0.1 m to 200 m (logarithmic)
	static final double SCREEN_MIN = 0.1;
	static final double SCREEN_MAX = 200.0;
	static final int STEPS = 1000;

	TDenlive tde;
	File ply;
	GaussianScene scene;
	Shell shell;
	Canvas canvas;
	Mixer mixer;
	Label baseL, screenL, statusL;
	Scale baseS, screenS;
	Button addB;

	//Pair of cameras: center, heading (around the vertical axis of the photo) and pitch (radians), spacing and
	//distance of the screen plane (metres)
	double[] pos = new double[3];
	double yaw = 0, pitch = 0;
	double base = 0.065;
	double screen = 2;

	//Drag in progress (0: none) and its kind
	static final int DRAG_NONE = 0, DRAG_ORBIT = 1, DRAG_PAN = 2, DRAG_LOOK = 3;
	int drag = DRAG_NONE;
	int lastX, lastY;

	//Image shown and its place
	Image shown = null;
	Rectangle shownRect = null;
	//Rendering thread: the latest request wins
	final Object lock = new Object();
	Request request = null;
	volatile boolean closed = false;
	//Smart filling of the pixels not covered (option kept in 3De.conf)
	boolean smartFill = true;
	//Interpolation: the mesh of the visible Gaussians, else the Gaussians
	boolean interp = true;
	//The estimated depths in gray
	boolean depthView = false;
	//Thresholding: |cos| below which the triangles are holes, shown or not
	double cosCut = com.cubaix.TDenlive.model3d.SmartFill.DEFAULT_COS;
	boolean cutView = false;
	//Label, slider and value of the threshold (enabled with the smart filling)
	org.eclipse.swt.widgets.Control[] thresholdCtl = new org.eclipse.swt.widgets.Control[0];
	//Number of views added (file names)
	int views = 0;

	/** A view to render: the cameras, the frame on the canvas, the number of views of the mixer, half size */
	static class Request {
		double[] pos;
		double yaw, pitch, base, screen;
		Rectangle frame;
		int nbViews;
		boolean low;
	}

	public AiSceneGUI(TDenlive aTDe,File aPly) {
		tde = aTDe;
		ply = aPly;
	}

	String lng(String aKey) {
		return tde.gui.lngs.get(aKey);
	}

	/**
	 * Read the scene and open the window
	 * @return false if the file can't be read (a message shown)
	 */
	public boolean open() {
		tde.gui.shell.setCursor(tde.gui.waitCursor);
		try {
			//The depth smoothed as in the scene medias and processors (GaussianScene.DEFAULT_BLUR)
			scene = GaussianScene.load(ply).smoothedDvd(GaussianScene.DEFAULT_BLUR);
		}
		catch(Throwable t) {
			t.printStackTrace(System.err);
			tde.gui.shell.setCursor(null);
			AiClipGUI.message(tde, SWT.ICON_ERROR, lng("AI.SceneOpenFailed").replace("%MSG%", t.getMessage() == null ? t.toString() : t.getMessage()));
			return false;
		}
		tde.gui.shell.setCursor(null);
		System.out.println("AI 3D scene: "+ply+" ("+scene.n+" Gaussians, depth "+String.format("%.2f / %.2f / %.2f", scene.nearZ, scene.medianZ, scene.farZ)+" m)");
		//The nearest objects at the screen, the 1/30 rule of the stereo photographers
		screen = clamp(scene.nearZ, SCREEN_MIN, SCREEN_MAX);
		base = clamp(screen/30, BASE_MIN, BASE_MAX);
		mixer = tde.gui.copyMonitorMixer();
		smartFill = tde.config.aiSceneSmartFill;
		interp = tde.config.aiSceneInterp;
		depthView = tde.config.aiSceneDepth;
		cosCut = tde.config.aiSceneCos;
		cutView = tde.config.aiSceneCut;

		shell = new Shell(tde.gui.shell, SWT.SHELL_TRIM);
		shell.setText(lng("AI.SceneViewTitle").replace("%NAME%", ply.getName()));
		shell.setImage(tde.gui.imgsSwt.getIcon("3Denlive32.png"));
		shell.setLayout(new GridLayout(1, false));
		tde.gui.applyColorFont(shell);

		canvas = new Canvas(shell, SWT.BORDER | SWT.DOUBLE_BUFFERED | SWT.NO_BACKGROUND);
		canvas.setLayoutData(new GridData(GridData.FILL_BOTH));
		canvas.addListener(SWT.Paint, e -> paint(e.gc));
		canvas.addListener(SWT.Resize, e -> render(false));
		installMouse();

		Composite aCtl = new Composite(shell, SWT.NONE);
		tde.gui.applyColorFont(aCtl);
		aCtl.setLayoutData(new GridData(GridData.FILL_HORIZONTAL));
		GridLayout aCGL = new GridLayout(3, false);
		tde.gui.applyNoMargin(aCGL);
		aCtl.setLayout(aCGL);
		Label aBL = new Label(aCtl, SWT.NONE);
		tde.gui.applyColorFont(aBL);
		aBL.setText(lng("AI.SceneBaseline"));
		aBL.setToolTipText(lng("AI.SceneBaselineTip"));
		baseS = new Scale(aCtl, SWT.HORIZONTAL);
		tde.gui.applyColorFont(baseS);
		baseS.setMinimum(0);
		baseS.setMaximum(STEPS);
		baseS.setPageIncrement(50);
		baseS.setLayoutData(new GridData(GridData.FILL_HORIZONTAL));
		baseS.setToolTipText(lng("AI.SceneBaselineTip"));
		baseL = new Label(aCtl, SWT.NONE);
		tde.gui.applyColorFont(baseL);
		GridData aBLGD = new GridData();
		aBLGD.widthHint = 90;
		baseL.setLayoutData(aBLGD);
		Label aSL = new Label(aCtl, SWT.NONE);
		tde.gui.applyColorFont(aSL);
		aSL.setText(lng("AI.SceneScreen"));
		aSL.setToolTipText(lng("AI.SceneScreenTip"));
		screenS = new Scale(aCtl, SWT.HORIZONTAL);
		tde.gui.applyColorFont(screenS);
		screenS.setMinimum(0);
		screenS.setMaximum(STEPS);
		screenS.setPageIncrement(50);
		screenS.setLayoutData(new GridData(GridData.FILL_HORIZONTAL));
		screenS.setToolTipText(lng("AI.SceneScreenTip"));
		screenL = new Label(aCtl, SWT.NONE);
		tde.gui.applyColorFont(screenL);
		screenL.setLayoutData(new GridData(GridData.FILL_HORIZONTAL));
		baseS.addListener(SWT.Selection, e -> {
			base = baseFromSlider(baseS.getSelection());
			showValues();
			render(true);
		});
		screenS.addListener(SWT.Selection, e -> {
			screen = screenFromSlider(screenS.getSelection());
			showValues();
			render(true);
		});
		//Threshold of |cos| of the triangles (thresholding), enabled with the thresholding or the smart filling
		Label aTL = new Label(aCtl, SWT.NONE);
		tde.gui.applyColorFont(aTL);
		aTL.setText(lng("AI.SceneCos"));
		aTL.setToolTipText(lng("AI.SceneCosTip"));
		Scale aThS = new Scale(aCtl, SWT.HORIZONTAL);
		tde.gui.applyColorFont(aThS);
		aThS.setMinimum(0);
		//Logarithmic (SmartFill.sliderToCos())
		aThS.setMaximum(com.cubaix.TDenlive.model3d.SmartFill.COS_STEPS);
		aThS.setPageIncrement(com.cubaix.TDenlive.model3d.SmartFill.COS_DECADE/2);
		aThS.setSelection(com.cubaix.TDenlive.model3d.SmartFill.cosToSlider(cosCut));
		aThS.setLayoutData(new GridData(GridData.FILL_HORIZONTAL));
		aThS.setToolTipText(aTL.getToolTipText());
		Label aThL = new Label(aCtl, SWT.NONE);
		tde.gui.applyColorFont(aThL);
		aThL.setText(com.cubaix.TDenlive.model3d.SmartFill.formatCos(cosCut));
		aThL.setLayoutData(new GridData(GridData.FILL_HORIZONTAL));
		aThS.addListener(SWT.Selection, e -> aThL.setText(com.cubaix.TDenlive.model3d.SmartFill.formatCos(com.cubaix.TDenlive.model3d.SmartFill.sliderToCos(aThS.getSelection()))));
		org.eclipse.swt.widgets.Listener aSet = e -> {
			cosCut = com.cubaix.TDenlive.model3d.SmartFill.sliderToCos(aThS.getSelection());
			tde.config.aiSceneCos = cosCut;
			if(e.type == SWT.MouseUp) {
				tde.config.saveGeneral(tde.configPath);
			}
			render(false);
		};
		aThS.addListener(SWT.MouseUp, aSet);
		aThS.addListener(SWT.KeyUp, aSet);
		thresholdCtl = new org.eclipse.swt.widgets.Control[] {aTL, aThS, aThL};
		//Full size once the slider is released
		baseS.addListener(SWT.MouseUp, e -> render(false));
		screenS.addListener(SWT.MouseUp, e -> render(false));

		statusL = new Label(shell, SWT.NONE);
		tde.gui.applyColorFont(statusL);
		statusL.setLayoutData(new GridData(GridData.FILL_HORIZONTAL));

		Composite aButtons = new Composite(shell, SWT.NONE);
		tde.gui.applyColorFont(aButtons);
		aButtons.setLayoutData(new GridData(GridData.FILL_HORIZONTAL));
		GridLayout aBGL = new GridLayout(9, false);
		tde.gui.applyNoMargin(aBGL);
		aButtons.setLayout(aBGL);
		Button aReset = new Button(aButtons, SWT.PUSH);
		tde.gui.applyColorFont(aReset);
		aReset.setText(lng("AI.SceneReset"));
		aReset.addSelectionListener(new SelectionAdapter() {
			@Override
			public void widgetSelected(SelectionEvent e) {
				pos = new double[3];
				yaw = pitch = 0;
				render(false);
				canvas.setFocus();
			}
		});
		//The estimated depths in gray instead of the colors (kept in 3De.conf)
		Button aDepthB = new Button(aButtons, SWT.CHECK);
		tde.gui.applyColorFont(aDepthB);
		aDepthB.setText(lng("AI.SceneDepth"));
		aDepthB.setToolTipText(lng("AI.SceneDepthTip"));
		aDepthB.setSelection(depthView);
		aDepthB.addListener(SWT.Selection, e -> {
			depthView = aDepthB.getSelection();
			tde.config.aiSceneDepth = depthView;
			tde.config.saveGeneral(tde.configPath);
			render(false);
			canvas.setFocus();
		});
		//Interpolation: the mesh of the visible Gaussians, else the Gaussians (kept in 3De.conf)
		Button aInterpB = new Button(aButtons, SWT.CHECK);
		tde.gui.applyColorFont(aInterpB);
		aInterpB.setText(lng("AI.SceneInterp"));
		aInterpB.setToolTipText(lng("AI.SceneInterpTip"));
		aInterpB.setSelection(interp);
		aInterpB.addListener(SWT.Selection, e -> {
			interp = aInterpB.getSelection();
			tde.config.aiSceneInterp = interp;
			tde.config.saveGeneral(tde.configPath);
			render(false);
			canvas.setFocus();
		});
		//Thresholding: the triangles seen edge on shown as holes (kept in 3De.conf)
		Button aCutB = new Button(aButtons, SWT.CHECK);
		tde.gui.applyColorFont(aCutB);
		aCutB.setText(lng("AI.SceneCut"));
		aCutB.setToolTipText(lng("AI.SceneCutTip"));
		aCutB.setSelection(cutView);
		//Pixels not covered filled by the inpainting model (the full size views, not while dragging)
		Button aFillB = new Button(aButtons, SWT.CHECK);
		tde.gui.applyColorFont(aFillB);
		aFillB.setText(lng("AI.SceneSmartFill"));
		aFillB.setToolTipText(lng("AI.SceneSmartFillTip"));
		aFillB.setSelection(smartFill);
		for(org.eclipse.swt.widgets.Control aC : thresholdCtl) {
			aC.setEnabled(smartFill || cutView);
		}
		aCutB.addListener(SWT.Selection, e -> {
			cutView = aCutB.getSelection();
			for(org.eclipse.swt.widgets.Control aC : thresholdCtl) {
				aC.setEnabled(smartFill || cutView);
			}
			tde.config.aiSceneCut = cutView;
			tde.config.saveGeneral(tde.configPath);
			render(false);
			canvas.setFocus();
		});
		aFillB.addListener(SWT.Selection, e -> {
			smartFill = aFillB.getSelection();
			for(org.eclipse.swt.widgets.Control aC : thresholdCtl) {
				aC.setEnabled(smartFill || cutView);
			}
			tde.config.aiSceneSmartFill = smartFill;
			tde.config.saveGeneral(tde.configPath);
			render(false);
			canvas.setFocus();
		});
		Label aSpace = new Label(aButtons, SWT.NONE);
		aSpace.setLayoutData(new GridData(GridData.FILL_HORIZONTAL));
		addB = new Button(aButtons, SWT.PUSH);
		tde.gui.applyColorFont(addB);
		addB.setText(lng("AI.SceneAddImage"));
		addB.setToolTipText(lng("AI.SceneAddImageTip"));
		addB.setImage(tde.gui.imgsSwt.getIcon("checked.gif"));
		addB.addSelectionListener(new SelectionAdapter() {
			@Override
			public void widgetSelected(SelectionEvent e) {
				addView();
			}
		});
		//The scene itself (model of points) into the media list and the time lines, from this view
		Button aSceneB = new Button(aButtons, SWT.PUSH);
		tde.gui.applyColorFont(aSceneB);
		aSceneB.setText(lng("AI.SceneAddScene"));
		aSceneB.setToolTipText(lng("AI.SceneAddSceneTip"));
		aSceneB.setImage(tde.gui.imgsSwt.getIcon("cube3d.png"));
		aSceneB.addSelectionListener(new SelectionAdapter() {
			@Override
			public void widgetSelected(SelectionEvent e) {
				addScene();
			}
		});
		Button aClose = new Button(aButtons, SWT.PUSH);
		tde.gui.applyColorFont(aClose);
		aClose.setText(lng("AI.SceneClose"));
		aClose.addSelectionListener(new SelectionAdapter() {
			@Override
			public void widgetSelected(SelectionEvent e) {
				shell.close();
			}
		});
		shell.addListener(SWT.Dispose, e -> {
			closed = true;
			synchronized(lock) {
				lock.notifyAll();
			}
			if(shown != null) {
				shown.dispose();
				shown = null;
			}
		});

		baseS.setSelection(sliderFromBase(base));
		screenS.setSelection(sliderFromScreen(screen));
		showValues();
		//About 85 % of the main window
		Rectangle aP = tde.gui.shell.getBounds();
		int aW = aP.width*85/100, aH = aP.height*85/100;
		shell.setBounds(aP.x+(aP.width-aW)/2, aP.y+(aP.height-aH)/2, aW, aH);
		Thread aTh = new Thread(this::renderLoop, "AI 3D scene render");
		aTh.setDaemon(true);
		aTh.start();
		shell.open();
		canvas.setFocus();
		render(false);
		return true;
	}

	static double clamp(double aV,double aMin,double aMax) {
		return Math.max(aMin, Math.min(aMax, aV));
	}

	static double baseFromSlider(int aV) {
		return aV <= 0 ? 0 : BASE_MIN*Math.pow(BASE_MAX/BASE_MIN, (aV-1)/(double)(STEPS-1));
	}

	static int sliderFromBase(double aB) {
		if(aB < BASE_MIN) {
			return 0;
		}
		return 1+(int)Math.round((STEPS-1)*Math.log(aB/BASE_MIN)/Math.log(BASE_MAX/BASE_MIN));
	}

	static double screenFromSlider(int aV) {
		return SCREEN_MIN*Math.pow(SCREEN_MAX/SCREEN_MIN, aV/(double)STEPS);
	}

	static int sliderFromScreen(double aS) {
		return (int)Math.round(STEPS*Math.log(aS/SCREEN_MIN)/Math.log(SCREEN_MAX/SCREEN_MIN));
	}

	static String metres(double aM) {
		if(aM <= 0) {
			return "0";
		}
		if(aM < 0.01) {
			return String.format("%.1f mm", aM*1000);
		}
		if(aM < 1) {
			return String.format("%.1f cm", aM*100);
		}
		return String.format(aM < 10 ? "%.2f m" : "%.1f m", aM);
	}

	void showValues() {
		if(baseL == null || statusL == null || baseL.isDisposed()) {
			return;
		}
		baseL.setText(metres(base));
		screenL.setText(metres(screen));
		statusL.setText(lng("AI.ScenePosition")
				.replace("%X%", String.format("%.2f", pos[0]))
				.replace("%Y%", String.format("%.2f", -pos[1]))
				.replace("%Z%", String.format("%.2f", pos[2]))
				.replace("%YAW%", String.format("%.0f", Math.toDegrees(yaw)))
				.replace("%PITCH%", String.format("%.0f", Math.toDegrees(pitch))));
	}

	static double[][] axes(double aYaw,double aPitch) {
		return StereoCam.axes(aYaw, aPitch);
	}

	/**
	 * Cameras of a request
	 */
	static StereoCam cam(Request aR) {
		StereoCam aC = new StereoCam();
		aC.pos = aR.pos.clone();
		aC.yaw = aR.yaw;
		aC.pitch = aR.pitch;
		aC.base = aR.base;
		aC.screen = aR.screen;
		return aC;
	}

	/**
	 * View of one eye (-1 left, 1 right) of an image aW x aH (see StereoCam.view())
	 */
	GaussianScene.View view(Request aR,int aEye,int aW,int aH) {
		return cam(aR).view(scene, aEye, aW, aH);
	}

	/**
	 * Ratio width / height of the views (the photo)
	 */
	double ratio() {
		if(scene.width > 0 && scene.height > 0) {
			return scene.width/(double)scene.height;
		}
		return 16/9.0;
	}

	/**
	 * Frame of the mixed image in the canvas: the ratio of the views, times the number of views of the mixer
	 */
	Rectangle frame(int aN) {
		Rectangle aCA = canvas.getClientArea();
		int aTop = tde.gui.textH+10;
		int aAvW = Math.max(16, aCA.width-8), aAvH = Math.max(16, aCA.height-aTop-4);
		double aR = ratio()*aN;
		int aW = aAvW, aH = (int)(aAvW/aR);
		if(aH > aAvH) {
			aH = aAvH;
			aW = (int)(aAvH*aR);
		}
		return new Rectangle(aCA.x+(aCA.width-aW)/2, aCA.y+aTop+(aAvH-aH)/2, Math.max(1, aW), Math.max(1, aH));
	}

	/**
	 * Ask for the image of the current cameras (aLow: half size, while dragging)
	 */
	void render(boolean aLow) {
		if(closed || canvas == null || canvas.isDisposed()) {
			return;
		}
		Request aR = new Request();
		aR.pos = pos.clone();
		aR.yaw = yaw;
		aR.pitch = pitch;
		aR.base = base;
		aR.screen = screen;
		aR.nbViews = Math.max(1, mixer.getNbViews());
		aR.frame = frame(aR.nbViews);
		aR.low = aLow;
		synchronized(lock) {
			request = aR;
			lock.notifyAll();
		}
		showValues();
	}

	void renderLoop() {
		while(!closed) {
			Request aR;
			synchronized(lock) {
				while(!closed && request == null) {
					try {
						lock.wait();
					}
					catch(InterruptedException e) {
						return;
					}
				}
				aR = request;
				request = null;
			}
			if(closed) {
				return;
			}
			try {
				int aEW = Math.max(8, aR.frame.width/aR.nbViews);
				int aEH = Math.max(8, aR.frame.height);
				if(aR.low) {
					aEW = Math.max(8, aEW/2);
					aEH = Math.max(8, aEH/2);
				}
				//While dragging: the mesh not cut (fast), the inpainting once the view stops
				BufferedImage aL = aR.low ? cam(aR).render(scene, -1, aEW, aEH, interp, depthView)
						: com.cubaix.TDenlive.model3d.SmartFill.render(scene, cam(aR), -1, aEW, aEH, depthView, interp, cutView, smartFill, cosCut, com.cubaix.TDenlive.model3d.SmartFill.Model.LIGHT.id, tde.tdeDir);
				BufferedImage aRI = aR.low ? cam(aR).render(scene, 1, aEW, aEH, interp, depthView)
						: com.cubaix.TDenlive.model3d.SmartFill.render(scene, cam(aR), 1, aEW, aEH, depthView, interp, cutView, smartFill, cosCut, com.cubaix.TDenlive.model3d.SmartFill.Model.LIGHT.id, tde.tdeDir);
				tde.gui.display.asyncExec(() -> {
					if(closed || canvas.isDisposed()) {
						return;
					}
					try {
						Image aI = mixer.mix2Swt(aL, aRI, TDConfig.PROCESSING_MODE_WORK, new Rectangle(0, 0, aR.frame.width, aR.frame.height));
						if(shown != null) {
							shown.dispose();
						}
						shown = aI;
						shownRect = aR.frame;
						canvas.redraw();
					}
					catch(Throwable t) {
						t.printStackTrace(System.err);
					}
				});
			}
			catch(Throwable t) {
				t.printStackTrace(System.err);
			}
		}
	}

	void paint(GC aGC) {
		Rectangle aCA = canvas.getClientArea();
		aGC.setBackground(tde.gui.colorsSwt.DARK_GRAY);
		aGC.fillRectangle(aCA);
		if(shown != null && shownRect != null) {
			Rectangle aIB = shown.getBounds();
			aGC.setInterpolation(SWT.HIGH);
			if(aIB.width == shownRect.width && aIB.height == shownRect.height) {
				aGC.drawImage(shown, shownRect.x, shownRect.y);
			}
			else {
				aGC.drawImage(shown, 0, 0, aIB.width, aIB.height, shownRect.x, shownRect.y, shownRect.width, shownRect.height);
			}
		}
		aGC.setForeground(tde.gui.colorsSwt.WHITE);
		String aHelp = lng("AI.SceneHelp").replace("%MOD%", "cocoa".equals(SWT.getPlatform()) ? "Cmd" : "Ctrl");
		aGC.drawText(aHelp, aCA.x+8, aCA.y+4, true);
	}

	/**
	 * Distance of a step of the keys and of the wheel: a part of the distance to the background in front
	 * (StereoCam.step())
	 */
	double step() {
		return StereoCam.step(scene, pos, yaw, pitch);
	}

	void move(double aRight,double aDown,double aFwd) {
		double[][] aA = axes(yaw, pitch);
		for(int c = 0;c < 3;c++) {
			pos[c] += aA[0][c]*aRight+aA[1][c]*aDown+aA[2][c]*aFwd;
		}
	}

	void installMouse() {
		canvas.addListener(SWT.MouseDown, e -> {
			canvas.setFocus();
			if(e.button == 1 || e.button == 3) {
				drag = e.button == 3 || (e.stateMask & SWT.SHIFT) != 0 ? DRAG_PAN
						: (e.stateMask & SWT.MOD1) != 0 ? DRAG_LOOK : DRAG_ORBIT;
				lastX = e.x;
				lastY = e.y;
			}
		});
		canvas.addListener(SWT.MouseMove, e -> {
			if(drag == DRAG_NONE) {
				return;
			}
			int dx = e.x-lastX, dy = e.y-lastY;
			lastX = e.x;
			lastY = e.y;
			if(dx == 0 && dy == 0) {
				return;
			}
			Rectangle aF = shownRect != null ? shownRect : frame(1);
			int aN = Math.max(1, mixer.getNbViews());
			double aFocal = scene.focalFor(Math.max(8, aF.width/aN));
			if(drag == DRAG_PAN) {
				//The screen plane follows the mouse
				double k = screen/aFocal;
				move(-dx*k, -dy*k, 0);
			}
			else if(drag == DRAG_LOOK) {
				yaw -= dx/aFocal;
				pitch = clamp(pitch+dy/aFocal, -Math.toRadians(85), Math.toRadians(85));
			}
			else {
				//Around the point of the screen plane in front of the pair
				double[][] aA = axes(yaw, pitch);
				double[] aPivot = new double[3];
				for(int c = 0;c < 3;c++) {
					aPivot[c] = pos[c]+aA[2][c]*screen;
				}
				yaw += dx*0.005;
				pitch = clamp(pitch-dy*0.005, -Math.toRadians(85), Math.toRadians(85));
				aA = axes(yaw, pitch);
				for(int c = 0;c < 3;c++) {
					pos[c] = aPivot[c]-aA[2][c]*screen;
				}
			}
			render(true);
		});
		canvas.addListener(SWT.MouseUp, e -> {
			if(drag != DRAG_NONE) {
				drag = DRAG_NONE;
				render(false);
			}
		});
		canvas.addListener(SWT.MouseVerticalWheel, e -> {
			//A wheel notch (count 3 on most systems) = one step; the many small events of a trackpad, less each
			move(0, 0, clamp(e.count/3.0, -3, 3)*step());
			render(true);
			renderLater();
		});
		canvas.addListener(SWT.KeyDown, e -> {
			double s = step()*((e.stateMask & SWT.SHIFT) != 0 ? 4 : 1);
			switch(e.keyCode) {
			case SWT.ARROW_UP: move(0, 0, s); break;
			case SWT.ARROW_DOWN: move(0, 0, -s); break;
			case SWT.ARROW_LEFT: move(-s, 0, 0); break;
			case SWT.ARROW_RIGHT: move(s, 0, 0); break;
			case SWT.PAGE_UP: move(0, -s, 0); break;
			case SWT.PAGE_DOWN: move(0, s, 0); break;
			default: return;
			}
			e.doit = false;
			render(true);
			renderLater();
		});
		//The arrows and pages go to the canvas (KeyDown), not to the traversal of the window
		canvas.addListener(SWT.Traverse, e -> {
			if(e.detail == SWT.TRAVERSE_ARROW_NEXT || e.detail == SWT.TRAVERSE_ARROW_PREVIOUS
					|| e.detail == SWT.TRAVERSE_PAGE_NEXT || e.detail == SWT.TRAVERSE_PAGE_PREVIOUS) {
				e.doit = false;
			}
		});
	}

	//Full size once the wheel and the keys stop
	int laterSeq = 0;

	void renderLater() {
		final int aSeq = ++laterSeq;
		tde.gui.display.timerExec(300, () -> {
			if(aSeq == laterSeq && drag == DRAG_NONE && !closed) {
				render(false);
			}
		});
	}

	/**
	 * The scene (PLY) into the media list with the cameras of this view, and a clip of it into the time lines
	 * (after the selected clip), whose processor SceneCamera moves the cameras with keys
	 */
	void addScene() {
		shell.setCursor(tde.gui.waitCursor);
		StereoCam aC = new StereoCam();
		aC.pos = pos.clone();
		aC.yaw = yaw;
		aC.pitch = pitch;
		aC.base = base;
		aC.screen = screen;
		if(com.cubaix.TDenlive.model3d.SmartFill.modelById(tde.config.aiSceneFill) == com.cubaix.TDenlive.model3d.SmartFill.Model.SDXL) {
			//Its views are rendered at the loading: SDXL takes seconds
			statusL.setText(lng("AI.FillComputing"));
		}
		//Loaded in a thread (the filling of its views), then into the time lines
		new Thread(() -> {
			Throwable aErr = null;
			com.cubaix.TDenlive.medias.SceneMedia aSM = null;
			try {
				aSM = tde.mediaList.loadScene(ply.getAbsolutePath(), aC, smartFill, cosCut);
			}
			catch(Throwable t) {
				aErr = t;
			}
			final Throwable aE = aErr;
			final com.cubaix.TDenlive.medias.SceneMedia aM = aSM;
			tde.gui.display.asyncExec(() -> {
				try {
					if(aE != null) {
						throw aE;
					}
					tde.timeLineStack.addMedia(aM);
					tde.gui.setMultiPropsPanel(TDeGUI.MULTIPROPSPANEL_PROCESSORS);
					tde.gui.setMultiMonitorPanel(TDeGUI.MULTIMONITORPANEL_MONITOR);
					tde.gui.monitorGUI.setMedia(tde.timeLineStack);
					if(tde.selected.clips.size() > 0) {
						tde.gui.processorGUI.setProcessors(tde.selected.clips.lastElement().getProcessors());
					}
					tde.gui.mediaListGUI.redraw();
					tde.gui.monitorGUI.redraw();
					tde.gui.timeLinesGUI.redraw();
					if(!statusL.isDisposed()) {
						statusL.setText(lng("AI.SceneSceneAdded").replace("%NAME%", ply.getName()));
					}
				}
				catch(Throwable t) {
					t.printStackTrace(System.err);
					AiClipGUI.message(tde, SWT.ICON_ERROR, lng("AI.SceneError")+"\n"+(t.getMessage() == null ? t.toString() : t.getMessage()));
				}
				finally {
					if(!shell.isDisposed()) {
						shell.setCursor(null);
					}
				}
			});
		}, "Add 3D scene").start();
	}

	/**
	 * Both eyes at the full screen resolution, side by side (parallel), next to the scene, into the media list
	 */
	void addView() {
		addB.setEnabled(false);
		shell.setCursor(tde.gui.waitCursor);
		Request aR = new Request();
		aR.pos = pos.clone();
		aR.yaw = yaw;
		aR.pitch = pitch;
		aR.base = base;
		aR.screen = screen;
		int aEH = Math.max(64, tde.config.processingResValues[TDConfig.PROCESSING_MODE_FULLSCREEN]);
		int aEW = Math.max(64, (int)Math.round(aEH*ratio()));
		String aBase = ply.getName().replaceAll("(?i)[.]ply$", "");
		//<scene>_View_0001.png... next to the scene, the first free number
		File aOut = null;
		for(int i = views+1;aOut == null;i++) {
			File aF = new File(ply.getParentFile(), aBase+"_View_"+String.format("%04d", i)+".png");
			if(!aF.exists()) {
				aOut = aF;
				views = i;
			}
		}
		final File aFile = aOut;
		new Thread(() -> {
			String aErr = null;
			try {
				BufferedImage aL = com.cubaix.TDenlive.model3d.SmartFill.render(scene, cam(aR), -1, aEW, aEH, depthView, interp, cutView, smartFill, cosCut, tde.config.aiSceneFill, tde.tdeDir);
				BufferedImage aRI = com.cubaix.TDenlive.model3d.SmartFill.render(scene, cam(aR), 1, aEW, aEH, depthView, interp, cutView, smartFill, cosCut, tde.config.aiSceneFill, tde.tdeDir);
				BufferedImage aBI = ImageUtils.createImage(2*aEW, aEH);
				Graphics aG = aBI.getGraphics();
				aG.drawImage(aL, 0, 0, null);
				aG.drawImage(aRI, aEW, 0, null);
				aG.dispose();
				ImageUtils.saveImage(aFile.getAbsolutePath(), aBI);
				tde.mediaList.loadImageP(aFile.getAbsolutePath(), TDConfig.PROCESSING_MODE_RENDER, 0);
			}
			catch(Throwable t) {
				t.printStackTrace(System.err);
				aErr = t.getMessage() == null ? t.toString() : t.getMessage();
			}
			final String aE = aErr;
			tde.gui.display.asyncExec(() -> {
				if(tde.gui.mediaListGUI != null) {
					tde.gui.mediaListGUI.redraw();
				}
				if(shell.isDisposed()) {
					return;
				}
				shell.setCursor(null);
				addB.setEnabled(true);
				if(aE != null) {
					AiClipGUI.message(tde, SWT.ICON_ERROR, lng("AI.SceneError")+"\n"+aE);
					return;
				}
				statusL.setText(lng("AI.SceneAdded").replace("%NAME%", aFile.getName()));
			});
		}, "AI 3D scene view").start();
	}
}
