package com.cubaix.TDenlive.GUI.processors;

import java.awt.image.BufferedImage;
import java.util.Vector;

import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.GC;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.graphics.Rectangle;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Canvas;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Label;

import com.cubaix.TDenlive.TDenlive;
import com.cubaix.TDenlive.GUI.SceneConvertGUI;
import com.cubaix.TDenlive.GUI.widgets.LabelImageButton;
import com.cubaix.TDenlive.GUI.widgets.LabelKeyFrame;
import com.cubaix.TDenlive.GUI.widgets.LabelScale;
import com.cubaix.TDenlive.depth.ComputedScenes;
import com.cubaix.TDenlive.medias.Clip;
import com.cubaix.TDenlive.model3d.GaussianScene;
import com.cubaix.TDenlive.model3d.StereoCam;
import com.cubaix.TDenlive.processors.Processor;
import com.cubaix.TDenlive.processors.Scene3D;
import com.cubaix.TDenlive.processors.SceneCamKeys;
import com.cubaix.TDenlive.utils.ImageUtils;

/**
 * Keys of the stereo cameras of a 3D scene (SceneCamera of a SceneMedia clip, Scene3D of a 2D clip): a small view of
 * the scene seen from the cameras at the time cursor (or the main selected key), navigated with the mouse as the AI
 * viewer (drag: turn around, Shift + drag or right drag: move, MOD1 + drag: look around, wheel: forward / back), the
 * spacing (B) and the screen plane (S) sliders, "Viewpoint of the image". A change goes to the selected keys, else to
 * the key at the time cursor (added if needed). Scene3D: button computing the scenes, and their state.
 */
public class SceneCameraGUI extends ProcessorGUI {
	static final double BASE_MIN = 0.001, BASE_MAX = 2.0;
	static final double SCREEN_MIN = 0.1, SCREEN_MAX = 200.0;
	static final int STEPS = 1000;
	static final int VIEW_H = 170;

	SceneCamKeys sc = null;
	Composite wgt = null;
	LabelKeyFrame lkf = null;
	LabelScale baseLS = null, screenLS = null;
	Label valuesL = null, scenesL = null;
	LabelImageButton fillNowB = null;
	Canvas view = null;
	Image viewI = null;
	//Cameras shown, and those of the drag in progress
	StereoCam cam = new StereoCam();
	int drag = 0;
	int lastX, lastY;
	Vector<Integer> editKeys = new Vector<Integer>();
	long lastMonitorMS = 0;
	boolean updating = false;

	public SceneCameraGUI(TDenlive aTDe, Composite aParent, Processor aP) {
		super(aTDe, aParent, aP);
		sc = (SceneCamKeys)proc;
		createContents();
	}

	String l(String aKey) {
		return tde.gui.lngs.get("SceneCam."+aKey);
	}

	/**
	 * Row "Filling:" (label, combo) of the model filling the holes (model3d.SmartFill.Model) in a grid of 2 columns;
	 * a model to download is asked first (FillModelGUI), refused: back to the previous one
	 */
	void addFillRow(Composite aParent) {
		Label aFL = new Label(aParent, SWT.NONE);
		tde.gui.applyColorFont(aFL);
		aFL.setBackground(tde.gui.colorsSwt.WHITE);
		aFL.setText(tde.gui.lngs.get("AI.SceneFill"));
		org.eclipse.swt.widgets.Combo aFC = new org.eclipse.swt.widgets.Combo(aParent, SWT.READ_ONLY);
		tde.gui.applyColorFont(aFC);
		aFC.setLayoutData(new GridData(GridData.FILL_HORIZONTAL));
		aFC.setToolTipText(l("FillTip"));
		Runnable aSelect = () -> {
			com.cubaix.TDenlive.model3d.SmartFill.Model aCur = com.cubaix.TDenlive.model3d.SmartFill.forProcessor(sc.fillModel);
			for(int i = 0;i < com.cubaix.TDenlive.model3d.SmartFill.PROCESSOR_MODELS.length;i++) {
				if(com.cubaix.TDenlive.model3d.SmartFill.PROCESSOR_MODELS[i] == aCur) {
					aFC.select(i);
				}
			}
		};
		for(com.cubaix.TDenlive.model3d.SmartFill.Model aM : com.cubaix.TDenlive.model3d.SmartFill.PROCESSOR_MODELS) {
			aFC.add(tde.gui.lngs.get(aM.key())+(aM.capable() ? "" : " "+tde.gui.lngs.get("AI.SceneFillNotHere")));
		}
		aSelect.run();
		aFC.addListener(SWT.Selection, e -> {
			int i = aFC.getSelectionIndex();
			if(i < 0) {
				return;
			}
			com.cubaix.TDenlive.model3d.SmartFill.Model aM = com.cubaix.TDenlive.model3d.SmartFill.PROCESSOR_MODELS[i];
			if(aM.id.equals(sc.fillModel)) {
				return;
			}
			if(!com.cubaix.TDenlive.GUI.FillModelGUI.ensure(tde, aFC.getShell(), aM)) {
				aSelect.run();
				return;
			}
			sc.fillModel = aM.id;
			updateFillNow();
			tde.gui.monitorGUI.redraw();
		});
	}

	/**
	 * Button "Fill this view" (shown with SDXL only: the monitor does not compute it, too slow): the image of the
	 * monitor at the time cursor filled by SDXL, then kept (cache) for the monitor and the rendering
	 */
	void addFillNowButton(Composite aParent) {
		fillNowB = new LabelImageButton(tde, aParent, l("FillNow"), "colorpicker.png") {
			@Override
			public void clicked() {
				com.cubaix.TDenlive.model3d.SmartFill.Model aM = com.cubaix.TDenlive.model3d.SmartFill.forProcessor(sc.fillModel);
				if(!com.cubaix.TDenlive.GUI.FillModelGUI.ensure(tde, wgt.getShell(), aM)) {
					return;
				}
				sc.fillNow = true;
				tde.gui.monitorGUI.redraw();
			}
		}.tip("SceneFillNow");
		fillNowB.getContainer().setLayoutData(new GridData(SWT.BEGINNING, SWT.CENTER, false, false));
		updateFillNow();
	}

	void updateFillNow() {
		if(fillNowB == null || fillNowB.getContainer().isDisposed()) {
			return;
		}
		boolean aShow = com.cubaix.TDenlive.model3d.SmartFill.forProcessor(sc.fillModel) == com.cubaix.TDenlive.model3d.SmartFill.Model.SDXL;
		Composite aC = fillNowB.getContainer();
		((GridData)aC.getLayoutData()).exclude = !aShow;
		aC.setVisible(aShow);
		aC.getParent().layout(true, true);
		wgt.layout(true, true);
	}

	void createContents() {
		wgt = new Composite(container, SWT.NONE);
		wgt.setLayoutData(new GridData(GridData.FILL_HORIZONTAL));
		GridLayout aGL = new GridLayout(1, false);
		tde.gui.applyNoMargin(aGL);
		aGL.verticalSpacing = 2;
		wgt.setLayout(aGL);
		wgt.setBackground(tde.gui.colorsSwt.WHITE);
		wgt.setForeground(tde.gui.colorsSwt.CUBAIX_BLUE);

		//Actions first: computation of the scenes / filling of this view
		if(sc instanceof Scene3D) {
			Composite aBR = new Composite(wgt, SWT.NONE);
			tde.gui.applyColorFont(aBR);
			aBR.setBackground(tde.gui.colorsSwt.WHITE);
			aBR.setLayoutData(new GridData(GridData.FILL_HORIZONTAL));
			GridLayout aBGL = new GridLayout(3, false);
			tde.gui.applyNoMargin(aBGL);
			aBR.setLayout(aBGL);
			LabelImageButton aCB = new LabelImageButton(tde, aBR, l("Compute"), "rhino.gif") {
				@Override
				public void clicked() {
					SceneConvertGUI.convert(tde, (Clip)sc.targetMedia, (Scene3D)sc);
				}
			}.tip("SceneConvert");
			aCB.getContainer().setLayoutData(new GridData(SWT.BEGINNING, SWT.CENTER, false, false));
			//State of the scenes right of the compute button, then "Fill this view" (SDXL only)
			scenesL = new Label(aBR, SWT.WRAP);
			tde.gui.applyColorFont(scenesL);
			GridData aSGD = new GridData(GridData.FILL_HORIZONTAL | GridData.VERTICAL_ALIGN_CENTER);
			aSGD.widthHint = 100;
			scenesL.setLayoutData(aSGD);
			addFillNowButton(aBR);
		}
		else {
			addFillNowButton(wgt);
		}

		//Display: depths in gray, interpolation (the mesh of the visible Gaussians, else the Gaussians), thresholding
		//(settings of the clip, not keyed), on one row
		Composite aFillRow = new Composite(wgt, SWT.NONE);
		tde.gui.applyColorFont(aFillRow);
		aFillRow.setLayoutData(new GridData(GridData.FILL_HORIZONTAL));
		GridLayout aFRGL = new GridLayout(3, false);
		tde.gui.applyNoMargin(aFRGL);
		aFillRow.setLayout(aFRGL);
		//The estimated depths in gray instead of the colors
		Button aDepthB = new Button(aFillRow, SWT.CHECK);
		tde.gui.applyColorFont(aDepthB);
		aDepthB.setText(l("Depth"));
		tde.gui.tip(aDepthB, "SceneDepth");
		aDepthB.setSelection(sc.depthView);
		aDepthB.addListener(SWT.Selection, e -> {
			sc.depthView = aDepthB.getSelection();
			renderView(false);
			tde.gui.monitorGUI.redraw();
		});
		Button aInterpB = new Button(aFillRow, SWT.CHECK);
		tde.gui.applyColorFont(aInterpB);
		aInterpB.setText(l("Interp"));
		tde.gui.tip(aInterpB, "SceneInterp");
		aInterpB.setSelection(sc.interp);
		aInterpB.addListener(SWT.Selection, e -> {
			sc.interp = aInterpB.getSelection();
			renderView(false);
			tde.gui.monitorGUI.redraw();
		});
		//Thresholding: the triangles seen edge on (|cos| below the threshold) shown as holes
		Button aCutB = new Button(aFillRow, SWT.CHECK);
		tde.gui.applyColorFont(aCutB);
		aCutB.setText(l("Cut"));
		tde.gui.tip(aCutB, "SceneCut");
		aCutB.setSelection(sc.cutView);

		//Settings: model of the scenes, model of the filling
		if(sc instanceof Scene3D) {
			//Model computing the scenes (used by the next computation)
			Composite aMR = new Composite(wgt, SWT.NONE);
			tde.gui.applyColorFont(aMR);
			aMR.setBackground(tde.gui.colorsSwt.WHITE);
			aMR.setLayoutData(new GridData(GridData.FILL_HORIZONTAL));
			GridLayout aMGL = new GridLayout(2, false);
			tde.gui.applyNoMargin(aMGL);
			aMR.setLayout(aMGL);
			Label aML = new Label(aMR, SWT.NONE);
			tde.gui.applyColorFont(aML);
			aML.setBackground(tde.gui.colorsSwt.WHITE);
			aML.setText(tde.gui.lngs.get("AI.SceneModel"));
			org.eclipse.swt.widgets.Combo aMC = new org.eclipse.swt.widgets.Combo(aMR, SWT.READ_ONLY);
			tde.gui.applyColorFont(aMC);
			aMC.setLayoutData(new GridData(GridData.FILL_HORIZONTAL));
			aMC.setToolTipText(l("ModelTip"));
			com.cubaix.TDenlive.ai.SceneGenerator.Model aCur = com.cubaix.TDenlive.ai.SceneGenerator.modelById(((Scene3D)sc).sceneModel);
			for(com.cubaix.TDenlive.ai.SceneGenerator.Model aM : com.cubaix.TDenlive.ai.SceneGenerator.MODELS) {
				aMC.add(tde.gui.lngs.get(aM.key()));
				if(aM == aCur) {
					aMC.select(aMC.getItemCount()-1);
				}
			}
			aMC.addListener(SWT.Selection, e -> {
				int i = aMC.getSelectionIndex();
				String aId = i >= 0 ? com.cubaix.TDenlive.ai.SceneGenerator.MODELS[i].id : null;
				if(aId != null && !aId.equals(((Scene3D)sc).sceneModel)) {
					//The scenes of that model (kept by model), else their computation is proposed
					((Scene3D)sc).sceneModel = aId;
					((Scene3D)sc).scenesChanged();
					if(((Scene3D)sc).computed() != null) {
						((Scene3D)sc).fixAutoCam();
					}
					refreshWidgets();
					tde.gui.monitorGUI.redraw();
					if(((Scene3D)sc).computed() == null) {
						tde.gui.display.asyncExec(() -> SceneConvertGUI.convert(tde, (Clip)sc.targetMedia, (Scene3D)sc));
					}
				}
			});
			addFillRow(aMR);
		}
		else {
			Composite aFR = new Composite(wgt, SWT.NONE);
			tde.gui.applyColorFont(aFR);
			aFR.setBackground(tde.gui.colorsSwt.WHITE);
			aFR.setLayoutData(new GridData(GridData.FILL_HORIZONTAL));
			GridLayout aFGL = new GridLayout(2, false);
			tde.gui.applyNoMargin(aFGL);
			aFR.setLayout(aFGL);
			addFillRow(aFR);
		}

		//Smart filling and its carry-overs (settings of the clip, not keyed): left eye -> right eye, previous images ->
		//this one, enabled with the smart filling, on one row below the filling model
		Composite aCarryRow = new Composite(wgt, SWT.NONE);
		tde.gui.applyColorFont(aCarryRow);
		aCarryRow.setLayoutData(new GridData(GridData.FILL_HORIZONTAL));
		GridLayout aCRGL = new GridLayout(3, false);
		tde.gui.applyNoMargin(aCRGL);
		aCarryRow.setLayout(aCRGL);
		Button aFillB = new Button(aCarryRow, SWT.CHECK);
		tde.gui.applyColorFont(aFillB);
		aFillB.setText(l("SmartFill"));
		tde.gui.tip(aFillB, "SceneSmartFill");
		aFillB.setSelection(sc.smartFill);
		Button aPairB = new Button(aCarryRow, SWT.CHECK);
		tde.gui.applyColorFont(aPairB);
		aPairB.setText(l("PairCarry"));
		tde.gui.tip(aPairB, "ScenePairCarry");
		aPairB.setSelection(sc.pairCarry);
		aPairB.addListener(SWT.Selection, e -> {
			sc.pairCarry = aPairB.getSelection();
			tde.gui.monitorGUI.redraw();
		});
		Button aPrevB = new Button(aCarryRow, SWT.CHECK);
		tde.gui.applyColorFont(aPrevB);
		aPrevB.setText(l("PrevCarry"));
		tde.gui.tip(aPrevB, "ScenePrevCarry");
		aPrevB.setSelection(sc.prevCarry);
		aPrevB.addListener(SWT.Selection, e -> {
			sc.prevCarry = aPrevB.getSelection();
			tde.gui.monitorGUI.redraw();
		});
		Runnable aCarryOn = () -> {
			aPairB.setEnabled(sc.smartFill);
			aPrevB.setEnabled(sc.smartFill);
		};
		aCarryOn.run();
		//Smoothing of the depth of the scene (slider S, tenths of a pixel at the DVD resolution)
		blurL = new Label(wgt, SWT.NONE);
		tde.gui.applyColorFont(blurL);
		blurL.setLayoutData(new GridData(GridData.FILL_HORIZONTAL));
		tde.gui.tip(blurL, "SceneBlur");
		blurLS = new LabelScale(tde, wgt, "S", 0, (int)Math.round(GaussianScene.DEFAULT_BLUR*10), BLUR_STEPS, (int)Math.round(sc.depthBlur*10)) {
			@Override
			public void change(int aValue) {
				if(updating) {
					return;
				}
				sc.depthBlur = aValue/10.0;
				blurText();
				//The monitor at the end of a drag (each image is made again)
				if(!isDragging()) {
					renderView(false);
					tde.gui.monitorGUI.redraw();
				}
			}
			@Override
			public void changeEnd() {
				renderView(false);
				tde.gui.monitorGUI.redraw();
			}
		}.tip("SceneBlur");
		blurText();

		//Threshold of |cos| (settings of the clip, not keyed), shown with the thresholding or the smart filling
		Composite aThRow = new Composite(wgt, SWT.NONE);
		tde.gui.applyColorFont(aThRow);
		aThRow.setLayoutData(new GridData(GridData.FILL_HORIZONTAL));
		GridLayout aThGL = new GridLayout(2, false);
		tde.gui.applyNoMargin(aThGL);
		aThRow.setLayout(aThGL);
		Label aThL = new Label(aThRow, SWT.NONE);
		tde.gui.applyColorFont(aThL);
		tde.gui.tip(aThL, "SceneCos");
		//Warning right of the value: the smart filling of an animation (each image from the previous ones) may differ
		//much at a high resolution
		Label aThWarn = new Label(aThRow, SWT.NONE);
		tde.gui.applyColorFont(aThWarn);
		aThWarn.setImage(tde.gui.imgsSwt.getIcon("warning.gif"));
		tde.gui.tip(aThWarn, "SceneFillWarn");
		Runnable aThText = () -> {
			aThL.setText(l("CosThreshold").replace("%V%", com.cubaix.TDenlive.model3d.SmartFill.formatCos(sc.cosCut)));
			aThRow.layout(true);
		};
		aThText.run();
		LabelScale aThLS = new LabelScale(tde, wgt, "C", 0, com.cubaix.TDenlive.model3d.SmartFill.cosToSlider(com.cubaix.TDenlive.model3d.SmartFill.DEFAULT_COS), com.cubaix.TDenlive.model3d.SmartFill.COS_STEPS, com.cubaix.TDenlive.model3d.SmartFill.cosToSlider(sc.cosCut)) {
			@Override
			public void change(int aValue) {
				if(updating) {
					return;
				}
				sc.cosCut = com.cubaix.TDenlive.model3d.SmartFill.sliderToCos(aValue);
				aThText.run();
				//The monitor at the end of a drag (each image is made again)
				if(!isDragging()) {
					tde.gui.monitorGUI.redraw();
				}
			}
			@Override
			public void changeEnd() {
				tde.gui.monitorGUI.redraw();
			}
		}.tip("SceneCos");
		Runnable aShowTh = () -> {
			boolean aOn = sc.cutView || sc.smartFill;
			for(org.eclipse.swt.widgets.Control aC : new org.eclipse.swt.widgets.Control[] {aThRow, aThLS.getContainer()}) {
				aC.setVisible(aOn);
				((GridData)aC.getLayoutData()).exclude = !aOn;
			}
			wgt.layout(true, true);
			container.getParent().layout(true, true);
		};
		aShowTh.run();
		aCutB.addListener(SWT.Selection, e -> {
			sc.cutView = aCutB.getSelection();
			aShowTh.run();
			tde.gui.monitorGUI.redraw();
		});
		aFillB.addListener(SWT.Selection, e -> {
			sc.smartFill = aFillB.getSelection();
			aCarryOn.run();
			aShowTh.run();
			tde.gui.monitorGUI.redraw();
		});

		//Keys of the cameras, their view and their sliders
		lkf = new LabelKeyFrame(tde, wgt, sc, "K") {
			@Override
			public void change(int aKey) {
				refreshCam();
				refreshWidgets();
				tde.gui.monitorGUI.redraw();
			}
			@Override
			public void move(int aTime) {
				tde.timeLineStack.setRequestedTimePosMS(((Clip)sc.targetMedia).getStartTimeMS()+aTime);
				tde.gui.timeLinesGUI.redraw();
				tde.gui.monitorGUI.redraw();
			}
		};
		updateKey();
		Label aHint = new Label(wgt, SWT.WRAP);
		tde.gui.applyColorFont(aHint);
		GridData aHGD = new GridData(GridData.FILL_HORIZONTAL);
		aHGD.widthHint = 100;
		aHint.setLayoutData(aHGD);
		aHint.setText(l("Hint").replace("%MOD%", "cocoa".equals(SWT.getPlatform()) ? "Cmd" : "Ctrl"));

		view = new Canvas(wgt, SWT.BORDER | SWT.DOUBLE_BUFFERED);
		GridData aVGD = new GridData(GridData.FILL_HORIZONTAL);
		aVGD.heightHint = VIEW_H;
		aVGD.widthHint = 100;
		view.setLayoutData(aVGD);
		view.addListener(SWT.Paint, e -> paint(e.gc));
		view.addListener(SWT.Resize, e -> renderView(false));
		view.addListener(SWT.Dispose, e -> {
			if(viewI != null) {
				viewI.dispose();
				viewI = null;
			}
		});
		installMouse();

		baseLS = new LabelScale(tde, wgt, "B", 0, 0, STEPS, 0) {
			@Override
			public void change(int aValue) {
				if(updating) {
					return;
				}
				double aB = baseFromSlider(aValue);
				lkf.edit(aEK -> {
					StereoCam aC = sc.camForKey(aEK);
					aC.base = aB;
					sc.setCam(aEK, aC);
				});
				changed(!isDragging());
			}
		}.tip("SceneBase");
		screenLS = new LabelScale(tde, wgt, "P", 0, 0, STEPS, 0) {
			@Override
			public void change(int aValue) {
				if(updating) {
					return;
				}
				double aS = screenFromSlider(aValue);
				lkf.edit(aEK -> {
					StereoCam aC = sc.camForKey(aEK);
					aC.screen = aS;
					sc.setCam(aEK, aC);
				});
				changed(!isDragging());
			}
		}.tip("SceneScreen");
		valuesL = new Label(wgt, SWT.NONE);
		tde.gui.applyColorFont(valuesL);
		valuesL.setLayoutData(new GridData(GridData.FILL_HORIZONTAL));

		new LabelImageButton(tde, wgt, l("Reset"), "moveRC.png") {
			@Override
			public void clicked() {
				lkf.edit(aEK -> {
					StereoCam aC = sc.camForKey(aEK);
					aC.resetView();
					sc.setCam(aEK, aC);
				});
				changed(true);
			}
		}.tip("SceneReset");

		refreshCam();
		refreshWidgets();
		tde.timeLineStack.addTimeListener(this);
	}

	static double baseFromSlider(int aV) {
		return aV <= 0 ? 0 : BASE_MIN*Math.pow(BASE_MAX/BASE_MIN, (aV-1)/(double)(STEPS-1));
	}

	static int sliderFromBase(double aB) {
		if(aB < BASE_MIN) {
			return 0;
		}
		return Math.min(STEPS, 1+(int)Math.round((STEPS-1)*Math.log(aB/BASE_MIN)/Math.log(BASE_MAX/BASE_MIN)));
	}

	static double screenFromSlider(int aV) {
		return SCREEN_MIN*Math.pow(SCREEN_MAX/SCREEN_MIN, aV/(double)STEPS);
	}

	static int sliderFromScreen(double aS) {
		return Math.max(0, Math.min(STEPS, (int)Math.round(STEPS*Math.log(aS/SCREEN_MIN)/Math.log(SCREEN_MAX/SCREEN_MIN))));
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

	/**
	 * Key widget on the key at the current time (delete mode) or before it (add mode)
	 */
	void updateKey() {
		long aTime = tde.timeLineStack.getTimePosMS();
		int aKey = sc.getPrevKeyGT(aTime);
		if(aKey < 0) {
			aKey = 0;
		}
		lkf.setSelection(aKey);
		lkf.setAddMode(!sc.isKeyAt(aKey, aTime));
	}

	/**
	 * Cameras shown: the main selected key, else the time cursor
	 */
	void refreshCam() {
		if(drag != 0) {
			return;
		}
		int aMain = sc.getMainSelectedKey();
		cam = aMain >= 0 ? sc.camForKey(aMain) : sc.camAt(tde.timeLineStack.getTimePosMS());
	}

	GaussianScene scene() {
		return sc.sceneAt(tde.timeLineStack.getTimePosMS());
	}

	//Slider S of the smoothing of the depth of the scene: 0 to 20 pixels at the DVD resolution
	static final int BLUR_STEPS = 200;
	Label blurL = null;
	LabelScale blurLS = null;

	void blurText() {
		blurL.setText(l("Blur").replace("%V%", String.format("%.1f", sc.depthBlur)));
	}

	void refreshWidgets() {
		if(wgt.isDisposed()) {
			return;
		}
		GaussianScene aS = scene();
		updating = true;
		try {
			baseLS.setSelection(sliderFromBase(cam.baseFor(aS)));
			screenLS.setSelection(sliderFromScreen(cam.screenFor(aS)));
		}
		finally {
			updating = false;
		}
		valuesL.setText(l("Values").replace("%B%", metres(cam.baseFor(aS))).replace("%S%", metres(cam.screenFor(aS))));
		if(scenesL != null) {
			ComputedScenes aCS = ((Scene3D)sc).computed();
			scenesL.setText(aCS == null ? l("NoScene")
					: aCS.video ? l("ScenesVideo").replace("%DONE%", ""+aCS.framesDone()).replace("%N%", ""+aCS.frames)
					: l("SceneImage"));
			//A status, in violet as all the statuses of the processor panels
			scenesL.setForeground(tde.gui.colorsSwt.VIOLET);
			//Its row laid out again (the text may change length)
			scenesL.getParent().layout(true);
		}
		if(playing()) {
			//While playing: the monitor shows the scene; this view (rendered by the UI thread, as heavy as an image of
			//the monitor) once the time stops
			tde.gui.display.timerExec(VIEW_DEFER_MS, deferredView);
			return;
		}
		wgt.layout(true, true);
		renderView(false);
	}

	//Time without change after which the view is rendered again while playing (the pause, the stop)
	static final int VIEW_DEFER_MS = 300;
	//Rescheduled by each change of time (SWT timerExec of the same runnable)
	final Runnable deferredView = () -> {
		if(wgt.isDisposed()) {
			return;
		}
		if(playing()) {
			tde.gui.display.timerExec(VIEW_DEFER_MS, this.deferredView);
			return;
		}
		wgt.layout(true, true);
		renderView(false);
	};

	boolean playing() {
		return tde.gui.playerControlGUI != null && tde.gui.playerControlGUI.isPlaying();
	}

	/**
	 * After a change of the keys: the view, the values, and the monitor (at most every 200 ms while dragging)
	 */
	void changed(boolean aFinal) {
		refreshCam();
		if(aFinal) {
			refreshWidgets();
			tde.gui.monitorGUI.redraw();
			tde.gui.timeLinesGUI.redraw();
			return;
		}
		renderView(true);
		long aNow = System.currentTimeMillis();
		if(aNow-lastMonitorMS > 200) {
			lastMonitorMS = aNow;
			tde.gui.monitorGUI.redraw();
		}
	}

	/**
	 * The center view of the scene with the cameras shown (half size while dragging)
	 */
	void renderView(boolean aLow) {
		if(view == null || view.isDisposed()) {
			return;
		}
		Rectangle aCA = view.getClientArea();
		GaussianScene aS = scene();
		if(viewI != null) {
			viewI.dispose();
			viewI = null;
		}
		if(aS == null || aCA.width < 8 || aCA.height < 8) {
			view.redraw();
			return;
		}
		double aR = aS.width > 0 && aS.height > 0 ? aS.width/(double)aS.height : 16/9.0;
		int aW = aCA.width, aH = (int)(aW/aR);
		if(aH > aCA.height) {
			aH = aCA.height;
			aW = (int)(aH*aR);
		}
		int aRW = Math.max(8, aLow ? aW/2 : aW), aRH = Math.max(8, aLow ? aH/2 : aH);
		try {
			//The mesh (the inpainting only in the monitor: this view is redrawn while dragging)
			BufferedImage aBI = cam.render(aS, 0, aRW, aRH, sc.interp, sc.depthView);
			if(aLow) {
				BufferedImage aBig = new BufferedImage(aW, aH, BufferedImage.TYPE_INT_ARGB);
				java.awt.Graphics2D aG = aBig.createGraphics();
				aG.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION, java.awt.RenderingHints.VALUE_INTERPOLATION_BILINEAR);
				aG.drawImage(aBI, 0, 0, aW, aH, null);
				aG.dispose();
				aBI = aBig;
			}
			viewI = ImageUtils.imageToSwt(tde.gui.display, aBI);
		}
		catch(Throwable t) {
			t.printStackTrace(System.err);
		}
		view.redraw();
	}

	void paint(GC aGC) {
		Rectangle aCA = view.getClientArea();
		aGC.setBackground(tde.gui.colorsSwt.DARK_GRAY);
		aGC.fillRectangle(aCA);
		if(viewI != null) {
			Rectangle aIB = viewI.getBounds();
			aGC.drawImage(viewI, (aCA.width-aIB.width)/2, (aCA.height-aIB.height)/2);
		}
		else {
			aGC.setForeground(tde.gui.colorsSwt.WHITE);
			aGC.drawText(l("NoView"), 6, 6, true);
		}
	}

	/**
	 * Keys of the change: the selected ones, else the one at the time cursor (added if needed); the drag moves the
	 * cameras of the main one, given to all of them
	 */
	void beginChange() {
		editKeys = sc.getSelectedKeys();
		if(editKeys.isEmpty()) {
			int aK = lkf.editKey();
			if(aK >= 0) {
				editKeys.add(aK);
			}
		}
		if(!editKeys.isEmpty()) {
			cam = sc.camForKey(editKeys.lastElement());
		}
	}

	void applyCam() {
		for(int aK : editKeys) {
			StereoCam aC = sc.camForKey(aK);
			//The position and the direction of the drag, the spacing and screen of each key kept
			aC.pos = cam.pos.clone();
			aC.yaw = cam.yaw;
			aC.pitch = cam.pitch;
			sc.setCam(aK, aC);
		}
	}

	//== Navigation in the view of the panel ==

	/**
	 * Start of a drag (1 orbit, 2 pan, 3 look around) on the keys of the change
	 * @return false if there is nothing to move (no scene at the time, no key)
	 */
	boolean startDrag(int aKind) {
		if(wgt.isDisposed() || scene() == null) {
			return false;
		}
		beginChange();
		if(editKeys.isEmpty()) {
			return false;
		}
		drag = aKind;
		return true;
	}

	/**
	 * A move of the drag in progress, on a view aViewW pixels wide (focal of the scene at that width)
	 */
	void dragBy(int aDx,int aDy,int aViewW) {
		if(drag == 0 || (aDx == 0 && aDy == 0)) {
			return;
		}
		GaussianScene aS = scene();
		if(aS == null) {
			return;
		}
		cam.drag(drag, aDx, aDy, aS.focalFor(Math.max(8, aViewW)), aS);
		applyCam();
		renderView(true);
		long aNow = System.currentTimeMillis();
		if(aNow-lastMonitorMS > 200) {
			lastMonitorMS = aNow;
			tde.gui.monitorGUI.redraw();
		}
	}

	void endDrag() {
		if(drag != 0) {
			drag = 0;
			changed(true);
			lkf.setSelection(editKeys.isEmpty() ? lkf.getSelection() : editKeys.lastElement());
		}
	}

	/**
	 * Wheel: forward / back (a notch = one step)
	 */
	void wheel(int aCount) {
		GaussianScene aS = scene();
		if(wgt.isDisposed() || aS == null) {
			return;
		}
		beginChange();
		cam.move(0, 0, Math.max(-3, Math.min(3, aCount/3.0))*cam.step(aS));
		applyCam();
		changed(true);
	}

	static int dragKind(org.eclipse.swt.widgets.Event e) {
		return e.button == 3 || (e.stateMask & SWT.SHIFT) != 0 ? 2 : (e.stateMask & SWT.MOD1) != 0 ? 3 : 1;
	}

	void installMouse() {
		view.addListener(SWT.MouseDown, e -> {
			if(e.button != 1 && e.button != 3) {
				return;
			}
			if(startDrag(dragKind(e))) {
				lastX = e.x;
				lastY = e.y;
			}
		});
		view.addListener(SWT.MouseMove, e -> {
			if(drag == 0) {
				return;
			}
			int dx = e.x-lastX, dy = e.y-lastY;
			lastX = e.x;
			lastY = e.y;
			dragBy(dx, dy, view.getClientArea().width);
		});
		view.addListener(SWT.MouseUp, e -> endDrag());
		view.addListener(SWT.MouseVerticalWheel, e -> wheel(e.count));
	}

	@Override
	public void timeChanged(long aTimeNew) {
		super.timeChanged(aTimeNew);
		if(wgt.isDisposed()) {
			tde.timeLineStack.removeTimeListener(this);
			return;
		}
		tde.gui.display.asyncExec(() -> {
			if(wgt.isDisposed()) {
				return;
			}
			updateKey();
			lkf.checkAddMode(tde.timeLineStack.getTimePosMS());
			refreshCam();
			refreshWidgets();
		});
	}
}
