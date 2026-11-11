package com.cubaix.TDenlive.GUI;

import java.util.HashSet;
import java.util.Vector;

import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.ScrolledComposite;
import org.eclipse.swt.graphics.Color;
import org.eclipse.swt.graphics.GC;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.graphics.RGB;
import org.eclipse.swt.graphics.Rectangle;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.ColorDialog;
import org.eclipse.swt.widgets.Combo;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Event;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Scale;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Spinner;
import org.eclipse.swt.widgets.Text;
import org.eclipse.swt.widgets.Tree;
import org.eclipse.swt.widgets.TreeItem;

import com.cubaix.TDenlive.TDenlive;
import com.cubaix.TDenlive.model3d.Math3D;
import com.cubaix.TDenlive.model3d.Model3D;
import com.cubaix.TDenlive.model3d.Object3D;
import com.cubaix.TDenlive.shapes.ShapeText;

/**
 * Mini 3D modeler: primitives, extruded texts and groups (tree), placed, turned and scaled with the mouse
 * (Model3DView: gizmo handles, axis constraints, snapping) or with numeric fields, orbit / pan / zoom view,
 * undo / redo, stereo preview in the mode of the monitor. open() returns the model, or null if canceled.
 */
public class Model3DGUI implements Model3DView.Source {
	static final int MAX_UNDO = 200;
	static final int[] PALETTE = {0x4A90D9, 0xE8604C, 0x5CB85C, 0xF0AD4E, 0x9B59B6, 0x1ABC9C, 0xE74C3C, 0xF1C40F, 0xDDE3E8, 0x34495E};

	TDenlive tde = null;
	Shell shell = null;
	Model3DView view = null;
	Model3D model = null;
	Model3D result = null;

	Vector<Model3D> undoStates = new Vector<Model3D>();
	Vector<String> undoKeys = new Vector<String>();
	int undoPos = -1;
	Runnable commitLater = () -> commit();

	Text nameText = null;
	Tree objTree = null;
	String treeSignature = null;
	HashSet<Integer> collapsed = new HashSet<Integer>();
	Spinner[][] spinners = new Spinner[3][3];//Position, rotation, scale × X, Y, Z (local, in the group)
	Button colorButton = null;
	Image colorImage = null;
	Button duplicateButton = null;
	Button deleteButton = null;
	Button groupButton = null;
	Button ungroupButton = null;
	Text textText = null;
	Combo fontCombo = null;
	Button boldCheck = null;
	Button italicCheck = null;
	Spinner extrusionSpinner = null;
	Button[] toolButtons = new Button[3];
	Button[] axisButtons = new Button[4];
	Button snapCheck = null;
	Button gridCheck = null;
	Button stereoCheck = null;
	Scale depthScale = null;
	Label depthValue = null;
	Spinner spinSpinner = null;
	Button undoButton = null;
	Button redoButton = null;
	boolean updating = false;

	/**
	 * @param aModel model to edit (copied), null for a new one
	 */
	public Model3DGUI(TDenlive aTDe, Model3D aModel) {
		tde = aTDe;
		model = aModel != null ? aModel.copy() : new Model3D();
		createContents();
		commit();
		refreshPanel();
	}

	public Model3D open() {
		shell.pack();
		//Not larger than the screen (the buttons stay visible)
		Rectangle aScreen = tde.gui.shell.getMonitor().getClientArea();
		Rectangle aS = shell.getBounds();
		if(aS.width > aScreen.width || aS.height > aScreen.height) {
			shell.setSize(Math.min(aS.width, aScreen.width), Math.min(aS.height, aScreen.height));
			aS = shell.getBounds();
		}
		Rectangle aP = tde.gui.shell.getBounds();
		shell.setLocation(Math.max(aScreen.x, aP.x + (aP.width - aS.width) / 2), Math.max(aScreen.y, aP.y + (aP.height - aS.height) / 2));
		shell.open();
		view.canvas.setFocus();
		while (!shell.isDisposed()) {
			if (!tde.gui.display.readAndDispatch()) {
				tde.gui.display.sleep();
			}
		}
		if(colorImage != null) {
			colorImage.dispose();
		}
		return result;
	}

	String l(String aKey) {
		return tde.gui.lngs.get("Model3D."+aKey);
	}

	//== Model3DView.Source ==

	@Override
	public Model3D getModel() {
		return model;
	}

	@Override
	public Model3D getCamera() {
		return model;
	}

	@Override
	public void changed(boolean aFinal) {
		if(aFinal) {
			commit();
			refreshPanel();
		}
		else {
			refreshFields();
		}
	}

	@Override
	public void selectionChanged() {
		refreshPanel();
	}

	@Override
	public boolean extraKey(Event e) {
		boolean aMod = (e.stateMask & SWT.MOD1) != 0;
		boolean aShift = (e.stateMask & SWT.SHIFT) != 0;
		int aK = e.keyCode;
		if(aK >= 'A' && aK <= 'Z') {
			aK += 'a'-'A';
		}
		if(aMod) {
			switch(aK) {
			case 'z':
				if(aShift) {
					redo();
				}
				else {
					undo();
				}
				return true;
			case 'y':
				redo();
				return true;
			case 'd':
				duplicateSelected();
				return true;
			case 'a':
				selectAll();
				return true;
			case 'g':
				if(aShift) {
					ungroupSelected();
				}
				else {
					groupSelected();
				}
				return true;
			}
			return false;
		}
		if(aK == SWT.DEL || aK == SWT.BS) {
			deleteSelected();
			return true;
		}
		return false;
	}

	//== Contents ==

	void createContents() {
		shell = new Shell(tde.gui.shell, SWT.DIALOG_TRIM | SWT.APPLICATION_MODAL | SWT.RESIZE);
		shell.setText(l("Title"));
		shell.setLayout(new GridLayout(2, false));
		shell.addListener(SWT.Traverse, e -> {
			//ESC is for the editor (selection), not to close
			if(e.detail == SWT.TRAVERSE_ESCAPE) {
				e.doit = false;
			}
		});
		tde.gui.applyColorFont(shell);
		shell.setImage(tde.gui.imgsSwt.getIcon("3Denlive32.png"));

		ScrolledComposite aSC = new ScrolledComposite(shell, SWT.V_SCROLL);
		GridData aSCGD = new GridData(GridData.FILL_VERTICAL);
		aSCGD.widthHint = 310;
		aSC.setLayoutData(aSCGD);
		tde.gui.applyColorFont(aSC);
		Composite aPanel = new Composite(aSC, SWT.NONE);
		tde.gui.applyColorFont(aPanel);
		GridLayout aPGL = new GridLayout(1, false);
		aPGL.marginWidth = 4;
		aPGL.marginHeight = 2;
		aPGL.verticalSpacing = 3;
		aPanel.setLayout(aPGL);

		view = new Model3DView(tde, shell, SWT.BORDER, this, tde.gui.copyMonitorMixer());
		createPanel(aPanel);
		aSC.setContent(aPanel);
		aSC.setExpandHorizontal(true);
		aSC.setExpandVertical(true);
		aSC.setMinSize(aPanel.computeSize(290, SWT.DEFAULT));

		GridData aCGD = new GridData(GridData.FILL_BOTH);
		aCGD.widthHint = 900;
		aCGD.heightHint = Math.max(420, Math.min(640, (int)(aCGD.widthHint/Math.max(0.2, tde.config.outRatio)))) + 2*Model3DView.MARGIN;
		view.canvas.setLayoutData(aCGD);
	}

	static Label section(TDenlive aTDe, Composite aParent, String aText) {
		Label aL = new Label(aParent, SWT.NONE);
		aL.setText(aText);
		aTDe.gui.applyColorFont(aL);
		aL.setFont(aTDe.gui.fontsSwt.robotoBold);
		aL.setForeground(aTDe.gui.colorsSwt.CUBAIX_PINK);
		GridData aGD = new GridData(GridData.FILL_HORIZONTAL);
		aGD.verticalIndent = 4;
		aL.setLayoutData(aGD);
		return aL;
	}

	static Composite row(TDenlive aTDe, Composite aParent, int aCols, boolean aEqual) {
		Composite aC = new Composite(aParent, SWT.NONE);
		aC.setLayoutData(new GridData(GridData.FILL_HORIZONTAL));
		aTDe.gui.applyColorFont(aC);
		GridLayout aGL = new GridLayout(aCols, aEqual);
		aTDe.gui.applyNoMargin(aGL);
		aGL.horizontalSpacing = 2;
		aGL.verticalSpacing = 2;
		aC.setLayout(aGL);
		return aC;
	}

	static Button button(TDenlive aTDe, Composite aParent, int aStyle, String aText, Runnable aAction) {
		Button aB = new Button(aParent, aStyle);
		aB.setText(aText);
		aTDe.gui.applyColorFont(aB);
		aB.setLayoutData(new GridData(GridData.FILL_HORIZONTAL));
		aB.addListener(SWT.Selection, e -> aAction.run());
		return aB;
	}

	Label section(Composite aParent, String aKey) {
		return section(tde, aParent, l(aKey));
	}

	Composite row(Composite aParent, int aCols, boolean aEqual) {
		return row(tde, aParent, aCols, aEqual);
	}

	Button button(Composite aParent, int aStyle, String aText, Runnable aAction) {
		return button(tde, aParent, aStyle, aText, aAction);
	}

	Label label(Composite aParent, String aText) {
		Label aL = new Label(aParent, SWT.NONE);
		aL.setText(aText);
		tde.gui.applyColorFont(aL);
		return aL;
	}

	/**
	 * Tool and axis toggles (shared with the processor panel of the 3D clips)
	 */
	public static void createToolRows(TDenlive aTDe, Composite aP, Model3DView aView, Button[] aTools, Button[] aAxes) {
		Composite aTR = row(aTDe, aP, 3, true);
		for(int t = 0;t < 3;t++) {
			final int aTool = t;
			aTools[t] = button(aTDe, aTR, SWT.TOGGLE, aTDe.gui.lngs.get("Model3D."+Model3DView.TOOL_KEYS[t]), () -> aView.setTool(aTool));
		}
		Composite aAR = row(aTDe, aP, 4, true);
		String[] aAxisNames = {aTDe.gui.lngs.get("Model3D.Free"), "X", "Y", "Z"};
		for(int a = 0;a < 4;a++) {
			final int aAxis = a-1;
			aAxes[a] = button(aTDe, aAR, SWT.TOGGLE, aAxisNames[a], () -> aView.setAxis(aAxis));
		}
	}

	public static void refreshToolRows(Model3DView aView, Button[] aTools, Button[] aAxes) {
		for(int t = 0;t < 3;t++) {
			aTools[t].setSelection(t == aView.tool);
		}
		for(int a = 0;a < 4;a++) {
			aAxes[a].setSelection(a-1 == aView.axis);
		}
	}

	/**
	 * Position / rotation / scale fields (shared with the processor panel of the 3D clips)
	 */
	public static void createTransformFields(TDenlive aTDe, Composite aP, Spinner[][] aSpinners, java.util.function.BiConsumer<Integer,Integer> aChanged) {
		Composite aT = row(aTDe, aP, 4, false);
		Label aCorner = new Label(aT, SWT.NONE);
		aTDe.gui.applyColorFont(aCorner);
		for(String aAx : new String[] {"X", "Y", "Z"}) {
			Label aL = new Label(aT, SWT.CENTER);
			aL.setText(aAx);
			aTDe.gui.applyColorFont(aL);
			aL.setLayoutData(new GridData(GridData.FILL_HORIZONTAL));
		}
		String[] aRows = {"Position", "Rotation", "Size"};
		int[] aDigits = {2, 1, 2};
		int[][] aRanges = {{-100000, 100000}, {-3600, 3600}, {1, 100000}};
		int[] aIncs = {10, 50, 10};
		for(int r = 0;r < 3;r++) {
			Label aL = new Label(aT, SWT.NONE);
			aL.setText(aTDe.gui.lngs.get("Model3D."+aRows[r]));
			aTDe.gui.applyColorFont(aL);
			for(int c = 0;c < 3;c++) {
				Spinner aS = new Spinner(aT, SWT.BORDER);
				aS.setDigits(aDigits[r]);
				aS.setMinimum(aRanges[r][0]);
				aS.setMaximum(aRanges[r][1]);
				aS.setIncrement(aIncs[r]);
				aTDe.gui.applyColorFont(aS);
				GridData aGD = new GridData(GridData.FILL_HORIZONTAL);
				aGD.widthHint = 52;
				aS.setLayoutData(aGD);
				final int aRow = r, aCol = c;
				aS.addListener(SWT.Modify, e -> aChanged.accept(aRow, aCol));
				aSpinners[r][c] = aS;
			}
		}
	}

	public static double spinnerValue(Spinner aS, int aRow) {
		return aS.getSelection()/(aRow == 1 ? 10.0 : 100.0);
	}

	public static void setSpinners(Spinner[][] aSpinners, Object3D aO) {
		for(int r = 0;r < 3;r++) {
			for(int c = 0;c < 3;c++) {
				aSpinners[r][c].setEnabled(aO != null);
				if(aO != null) {
					aSpinners[r][c].setSelection((int)Math.round(get(aO, r, c)*(r == 1 ? 10 : 100)));
				}
			}
		}
	}

	void createPanel(Composite aP) {
		section(aP, "Name");
		nameText = tde.gui.framedText(aP, SWT.SINGLE);
		nameText.getParent().setLayoutData(new GridData(GridData.FILL_HORIZONTAL));
		nameText.setText(model.name == null ? "" : model.name);
		nameText.setMessage(l("MediaName"));
		nameText.addListener(SWT.Modify, e -> {
			if(!updating) {
				model.name = nameText.getText();
				tde.gui.display.timerExec(700, commitLater);
			}
		});

		section(aP, "Add");
		Composite aAdd = row(aP, 3, true);
		for(int t = 0;t <= Object3D.TEXT;t++) {
			final int aType = t;
			button(aAdd, SWT.PUSH, l(Object3D.TYPE_NAMES[t]), () -> addObject(aType));
		}

		section(aP, "Tool");
		createToolRows(tde, aP, view, toolButtons, axisButtons);
		snapCheck = button(aP, SWT.CHECK, l("Snap"), () -> {
			view.snap = snapCheck.getSelection();
			view.redraw();
		});

		section(aP, "Objects");
		objTree = createTree(tde, aP, 7, view, collapsed, () -> {
			refreshPanel();
			view.redraw();
		});
		Composite aObjButtons = row(aP, 3, true);
		duplicateButton = button(aObjButtons, SWT.PUSH, l("Duplicate"), () -> duplicateSelected());
		deleteButton = button(aObjButtons, SWT.PUSH, l("Delete"), () -> deleteSelected());
		colorButton = button(aObjButtons, SWT.PUSH, l("Color"), () -> chooseColor());
		Composite aGroupButtons = row(aP, 2, true);
		groupButton = button(aGroupButtons, SWT.PUSH, l("GroupAction"), () -> groupSelected());
		ungroupButton = button(aGroupButtons, SWT.PUSH, l("Ungroup"), () -> ungroupSelected());

		section(aP, "TextProps");
		textText = new Text(aP, SWT.MULTI | SWT.BORDER | SWT.WRAP | SWT.V_SCROLL);
		tde.gui.applyColorFont(textText);
		GridData aTTGD = new GridData(GridData.FILL_HORIZONTAL);
		aTTGD.heightHint = 2*textText.getLineHeight()+4;
		textText.setLayoutData(aTTGD);
		textText.addListener(SWT.Modify, e -> {
			if(!updating) {
				editTexts(aO -> aO.text = textText.getText().replace("\r", ""), false);
			}
		});
		fontCombo = new Combo(aP, SWT.READ_ONLY);
		tde.gui.applyColorFont(fontCombo);
		fontCombo.setLayoutData(new GridData(GridData.FILL_HORIZONTAL));
		fontCombo.setItems(ShapeText.getFamilies());
		fontCombo.setVisibleItemCount(20);
		fontCombo.addListener(SWT.Selection, e -> {
			if(!updating && fontCombo.getSelectionIndex() >= 0) {
				String aF = fontCombo.getItem(fontCombo.getSelectionIndex());
				editTexts(aO -> aO.fontName = aF, true);
			}
		});
		Composite aStyle = row(aP, 4, false);
		boldCheck = button(aStyle, SWT.CHECK, l("Bold"), () -> editTexts(aO -> aO.bold = boldCheck.getSelection(), true));
		italicCheck = button(aStyle, SWT.CHECK, l("Italic"), () -> editTexts(aO -> aO.italic = italicCheck.getSelection(), true));
		label(aStyle, l("Extrusion"));
		extrusionSpinner = new Spinner(aStyle, SWT.BORDER);
		extrusionSpinner.setDigits(2);
		extrusionSpinner.setMinimum(1);
		extrusionSpinner.setMaximum(1000);
		extrusionSpinner.setIncrement(5);
		tde.gui.applyColorFont(extrusionSpinner);
		extrusionSpinner.addListener(SWT.Modify, e -> {
			if(!updating) {
				double aV = extrusionSpinner.getSelection()/100.0;
				editTexts(aO -> aO.extrusion = aV, false);
			}
		});

		section(aP, "Transform");
		createTransformFields(tde, aP, spinners, (r, c) -> spinnerChanged(r, c));

		section(aP, "View");
		Composite aViews = row(aP, 3, true);
		button(aViews, SWT.PUSH, l("Front"), () -> view.setView(0, 0));
		button(aViews, SWT.PUSH, l("Right"), () -> view.setView(90, 0));
		button(aViews, SWT.PUSH, l("Top"), () -> view.setView(0, Model3D.MAX_PITCH));
		Composite aViews2 = row(aP, 2, true);
		button(aViews2, SWT.PUSH, l("Perspective"), () -> view.setView(35, 25));
		button(aViews2, SWT.PUSH, l("Frame"), () -> view.frameSelection());
		gridCheck = button(aP, SWT.CHECK, l("Grid"), () -> {
			view.showGrid = gridCheck.getSelection();
			view.redraw();
		});
		stereoCheck = button(aP, SWT.CHECK, l("StereoPreview"), () -> {
			view.stereoPreview = stereoCheck.getSelection();
			view.redraw();
		});

		section(aP, "Stereo");
		Composite aDepth = row(aP, 3, false);
		label(aDepth, l("Depth"));
		depthScale = new Scale(aDepth, SWT.HORIZONTAL);
		depthScale.setMinimum(0);
		depthScale.setMaximum(100);
		depthScale.setIncrement(1);
		depthScale.setPageIncrement(5);
		tde.gui.applyColorFont(depthScale);
		depthScale.setLayoutData(new GridData(GridData.FILL_HORIZONTAL));
		depthScale.addListener(SWT.Selection, e -> {
			if(updating) {
				return;
			}
			model.depth = depthScale.getSelection()/1000.0;
			depthValue.setText(depthText());
			view.redraw();
			tde.gui.display.timerExec(700, commitLater);
		});
		depthValue = new Label(aDepth, SWT.RIGHT);
		tde.gui.applyColorFont(depthValue);
		GridData aDVGD = new GridData();
		aDVGD.widthHint = 44;
		depthValue.setLayoutData(aDVGD);
		Composite aSpin = row(aP, 2, false);
		label(aSpin, l("Spin"));
		spinSpinner = new Spinner(aSpin, SWT.BORDER);
		spinSpinner.setMinimum(-360);
		spinSpinner.setMaximum(360);
		spinSpinner.setIncrement(5);
		tde.gui.applyColorFont(spinSpinner);
		spinSpinner.setLayoutData(new GridData(GridData.FILL_HORIZONTAL));
		spinSpinner.addListener(SWT.Modify, e -> {
			if(!updating) {
				model.spin = spinSpinner.getSelection();
				tde.gui.display.timerExec(700, commitLater);
			}
		});

		Composite aUndo = row(aP, 2, true);
		((GridData)aUndo.getLayoutData()).verticalIndent = 6;
		undoButton = button(aUndo, SWT.PUSH, l("Undo"), () -> undo());
		redoButton = button(aUndo, SWT.PUSH, l("Redo"), () -> redo());

		Label aHelp = new Label(aP, SWT.WRAP);
		aHelp.setText(l("Help"));
		tde.gui.applyColorFont(aHelp);
		aHelp.setForeground(tde.gui.colorsSwt.DARK_GRAY);
		GridData aHGD = new GridData(GridData.FILL_HORIZONTAL);
		aHGD.widthHint = 280;
		aHGD.verticalIndent = 4;
		aHelp.setLayoutData(aHGD);

		Composite aButtons = row(aP, 2, true);
		GridData aBGD = new GridData(GridData.FILL_HORIZONTAL | GridData.VERTICAL_ALIGN_END | GridData.GRAB_VERTICAL);
		aBGD.verticalIndent = 6;
		aButtons.setLayoutData(aBGD);
		button(aButtons, SWT.PUSH, l("Cancel"), () -> {
			result = null;
			shell.close();
		});
		Button aSave = button(aButtons, SWT.PUSH, l("Save"), () -> {
			result = model.copy();
			shell.close();
		});
		aSave.setImage(tde.gui.imgsSwt.getIcon("save.gif"));
	}

	String depthText() {
		return String.format("%.1f %%", model.depth*100);
	}

	//== Tree of the nodes (shared with the processor panel of the 3D clips) ==

	/**
	 * Tree whose selection is the one of the view (the item clicked is the main node)
	 */
	public static Tree createTree(TDenlive aTDe, Composite aP, int aNbLines, Model3DView aView, java.util.Set<Integer> aCollapsed, Runnable aOnSelect) {
		Tree aTree = new Tree(aP, SWT.MULTI | SWT.V_SCROLL | SWT.BORDER);
		aTDe.gui.applyColorFont(aTree);
		GridData aTGD = new GridData(GridData.FILL_HORIZONTAL);
		aTGD.heightHint = aNbLines*aTree.getItemHeight();
		aTree.setLayoutData(aTGD);
		aTree.addListener(SWT.Selection, e -> {
			if(aTree.getData("updating") != null) {
				return;
			}
			aView.selection.clear();
			Object aMain = e.item instanceof TreeItem ? ((TreeItem)e.item).getData() : null;
			boolean aMainSelected = false;
			for(TreeItem aI : aTree.getSelection()) {
				if(aI.getData() == null) {
					continue;
				}
				if(aI.getData().equals(aMain)) {
					aMainSelected = true;
				}
				else {
					aView.selection.add((Integer)aI.getData());
				}
			}
			if(aMainSelected) {
				aView.selection.add((Integer)aMain);
			}
			aOnSelect.run();
		});
		aTree.addListener(SWT.Collapse, e -> aCollapsed.add((Integer)((TreeItem)e.item).getData()));
		aTree.addListener(SWT.Expand, e -> aCollapsed.remove((Integer)((TreeItem)e.item).getData()));
		return aTree;
	}

	/**
	 * Name of a node in the tree
	 */
	public static String nodeLabel(TDenlive aTDe, Model3D aM, Object3D aO) {
		if(aO.name != null && !aO.name.isBlank()) {
			return aO.name;
		}
		String aType = aTDe.gui.lngs.get("Model3D."+Object3D.TYPE_NAMES[aO.type]);
		if(aO.type == Object3D.TEXT) {
			String aT = aO.text == null ? "" : aO.text.replace("\n", " ");
			if(aT.length() > 20) {
				aT = aT.substring(0, 20)+"…";
			}
			return aType+" « "+aT+" »";
		}
		return aType+" "+(aM.objects.indexOf(aO)+1);
	}

	/**
	 * Items of the nodes, rebuilt only when they change; selection of the view.
	 * Returns the signature of the items, to give back at the next call
	 */
	public static String fillTree(TDenlive aTDe, Tree aTree, Model3D aM, String aOldSignature, java.util.Set<Integer> aCollapsed, Vector<Integer> aSelection) {
		aTree.setData("updating", Boolean.TRUE);
		try {
			StringBuffer aSig = new StringBuffer();
			for(Object3D aO : aM.objects) {
				aSig.append(aO.id).append(':').append(aM.parentOf(aO) == null ? -1 : aO.parent).append(':').append(nodeLabel(aTDe, aM, aO)).append('\n');
			}
			String aSignature = aSig.toString();
			if(!aSignature.equals(aOldSignature)) {
				aTree.setRedraw(false);
				aTree.removeAll();
				addItems(aTDe, aTree, null, aM, -1, aCollapsed);
				aTree.setRedraw(true);
			}
			Vector<TreeItem> aSel = new Vector<TreeItem>();
			collectSelected(aTree.getItems(), aSelection, aSel);
			TreeItem[] aCur = aTree.getSelection();
			boolean aSame = aCur.length == aSel.size();
			for(TreeItem aI : aCur) {
				aSame &= aSel.contains(aI);
			}
			if(!aSame) {
				aTree.setSelection(aSel.toArray(new TreeItem[0]));
			}
			return aSignature;
		}
		finally {
			aTree.setData("updating", null);
		}
	}

	static void addItems(TDenlive aTDe, Tree aTree, TreeItem aParent, Model3D aM, int aParentId, java.util.Set<Integer> aCollapsed) {
		for(Object3D aO : aM.children(aParentId)) {
			TreeItem aI = aParent == null ? new TreeItem(aTree, SWT.NONE) : new TreeItem(aParent, SWT.NONE);
			aI.setText(nodeLabel(aTDe, aM, aO));
			aI.setData(aO.id);
			if(aO.isGroup()) {
				addItems(aTDe, aTree, aI, aM, aO.id, aCollapsed);
				aI.setExpanded(!aCollapsed.contains(aO.id));
			}
		}
	}

	static void collectSelected(TreeItem[] aItems, Vector<Integer> aSelection, Vector<TreeItem> aOut) {
		for(TreeItem aI : aItems) {
			if(aSelection.contains(aI.getData())) {
				aOut.add(aI);
				for(TreeItem aP = aI.getParentItem();aP != null;aP = aP.getParentItem()) {
					aP.setExpanded(true);
				}
			}
			collectSelected(aI.getItems(), aSelection, aOut);
		}
	}

	/**
	 * Widgets from the model and the selection
	 */
	void refreshPanel() {
		if(shell == null || shell.isDisposed()) {
			return;
		}
		updating = true;
		try {
			view.cleanSelection();
			treeSignature = fillTree(tde, objTree, model, treeSignature, collapsed, view.selection);
			Object3D aM = view.main();
			setSpinners(spinners, aM);
			duplicateButton.setEnabled(aM != null);
			deleteButton.setEnabled(aM != null);
			colorButton.setEnabled(aM != null);
			groupButton.setEnabled(aM != null);
			ungroupButton.setEnabled(aM != null && aM.isGroup());
			if(colorImage != null) {
				colorButton.setImage(null);
				colorImage.dispose();
				colorImage = null;
			}
			if(aM != null) {
				colorImage = new Image(tde.gui.display, 14, 12);
				GC aGC = new GC(colorImage);
				aGC.setBackground(new Color((aM.color >> 16) & 0xFF, (aM.color >> 8) & 0xFF, aM.color & 0xFF));
				aGC.fillRectangle(0, 0, 14, 12);
				aGC.setForeground(tde.gui.colorsSwt.DARK_GRAY);
				aGC.drawRectangle(0, 0, 13, 11);
				aGC.dispose();
				colorButton.setImage(colorImage);
			}
			Object3D aText = mainText();
			for(org.eclipse.swt.widgets.Control aC : new org.eclipse.swt.widgets.Control[] {textText, fontCombo, boldCheck, italicCheck, extrusionSpinner}) {
				aC.setEnabled(aText != null);
			}
			if(aText != null) {
				if(!textText.getText().replace("\r", "").equals(aText.text)) {
					textText.setText(aText.text);
				}
				int aFI = fontCombo.indexOf(aText.fontName);
				if(aFI < 0 && aText.fontName != null) {
					//Not installed here: kept, drawn with a default font
					fontCombo.add(aText.fontName, 0);
					aFI = 0;
				}
				if(fontCombo.getSelectionIndex() != aFI) {
					fontCombo.select(aFI);
				}
				boldCheck.setSelection(aText.bold);
				italicCheck.setSelection(aText.italic);
				extrusionSpinner.setSelection((int)Math.round(aText.extrusion*100));
			}
			refreshToolRows(view, toolButtons, axisButtons);
			snapCheck.setSelection(view.snap);
			gridCheck.setSelection(view.showGrid);
			stereoCheck.setSelection(view.stereoPreview);
			depthScale.setSelection((int)Math.round(model.depth*1000));
			depthValue.setText(depthText());
			spinSpinner.setSelection((int)Math.round(model.spin));
			if(!nameText.getText().equals(model.name == null ? "" : model.name)) {
				nameText.setText(model.name == null ? "" : model.name);
			}
			refreshUndoButtons();
		}
		finally {
			updating = false;
		}
	}

	/**
	 * Numeric fields only (during the drags)
	 */
	void refreshFields() {
		updating = true;
		try {
			setSpinners(spinners, view.main());
		}
		finally {
			updating = false;
		}
	}

	void refreshUndoButtons() {
		if(undoButton == null || undoButton.isDisposed()) {
			return;
		}
		undoButton.setEnabled(undoPos > 0);
		redoButton.setEnabled(undoPos < undoStates.size()-1);
	}

	/**
	 * Value of a numeric field: aRow 0 position, 1 rotation (degrees), 2 scale; aCol the axis (local)
	 */
	public static double get(Object3D aO, int aRow, int aCol) {
		switch(aRow) {
		case 0: return aO.getPosition()[aCol];
		case 1: return new double[] {aO.rx, aO.ry, aO.rz}[aCol];
		default: return aO.getScale()[aCol];
		}
	}

	public static void set(Object3D aO, int aRow, int aCol, double aV) {
		switch(aRow) {
		case 0:
			double[] aP = aO.getPosition();
			aP[aCol] = aV;
			aO.setPosition(aP);
			break;
		case 1:
			if(aCol == 0) aO.rx = aV;
			else if(aCol == 1) aO.ry = aV;
			else aO.rz = aV;
			break;
		default:
			double[] aS = aO.getScale();
			aS[aCol] = aV;
			aO.setScale(aS);
			break;
		}
	}

	/**
	 * A field changed: aMain takes the value aV, the other nodes of aNodes the same change
	 */
	public static void applyField(Object3D aMain, Vector<Object3D> aNodes, int aRow, int aCol, double aV) {
		double aOld = get(aMain, aRow, aCol);
		for(Object3D aO : aNodes) {
			if(aRow == 2) {
				set(aO, aRow, aCol, aOld == 0 ? aV : get(aO, aRow, aCol)*aV/aOld);
			}
			else {
				set(aO, aRow, aCol, get(aO, aRow, aCol) + aV - aOld);
			}
		}
	}

	void spinnerChanged(int aRow, int aCol) {
		if(updating) {
			return;
		}
		Object3D aM = view.main();
		if(aM == null) {
			return;
		}
		applyField(aM, view.selectedObjects(), aRow, aCol, spinnerValue(spinners[aRow][aCol], aRow));
		view.redraw();
		tde.gui.display.timerExec(700, commitLater);
	}

	//== Objects ==

	Object3D mainText() {
		Object3D aM = view.main();
		if(aM != null && aM.type == Object3D.TEXT) {
			return aM;
		}
		for(Object3D aO : view.selectedObjects()) {
			if(aO.type == Object3D.TEXT) {
				return aO;
			}
		}
		return null;
	}

	void editTexts(java.util.function.Consumer<Object3D> aEdit, boolean aNow) {
		for(Object3D aO : view.selectedObjects()) {
			if(aO.type == Object3D.TEXT) {
				aEdit.accept(aO);
			}
		}
		view.redraw();
		if(aNow) {
			commit();
			refreshPanel();
		}
		else {
			treeSignature = fillTree(tde, objTree, model, treeSignature, collapsed, view.selection);
			tde.gui.display.timerExec(700, commitLater);
		}
	}

	void addObject(int aType) {
		Object3D aO = new Object3D(aType);
		aO.id = model.nextId();
		aO.color = PALETTE[model.objects.size() % PALETTE.length];
		if(aType == Object3D.TEXT) {
			aO.text = l("Text");
		}
		double[] aT = model.getTarget();
		aO.px = view.snap ? Model3DView.snapValue(aT[0], Model3DView.GRID_STEP) : aT[0];
		aO.pz = view.snap ? Model3DView.snapValue(aT[2], Model3DView.GRID_STEP) : aT[2];
		//On the floor
		aO.py = aType == Object3D.PLANE ? 0 : 0.5;
		model.objects.add(aO);
		view.selection.clear();
		view.selection.add(aO.id);
		commit();
		refreshPanel();
		view.redraw();
		view.canvas.setFocus();
	}

	void duplicateSelected() {
		Vector<Object3D> aOs = view.roots();
		if(aOs.isEmpty()) {
			return;
		}
		Vector<Integer> aNew = new Vector<Integer>();
		for(Object3D aO : aOs) {
			Object3D aC = model.duplicate(aO);
			double[] aW = model.world(aC);
			model.setWorld(aC, Model3D.matrixOf(aW), Math3D.add(Model3D.translationOf(aW), new double[] {0.5, 0, 0}));
			aNew.add(aC.id);
		}
		view.selection = aNew;
		commit();
		refreshPanel();
		view.redraw();
	}

	void deleteSelected() {
		if(model.remove(view.selection)) {
			view.selection.clear();
			commit();
			refreshPanel();
			view.redraw();
		}
	}

	void selectAll() {
		view.selection.clear();
		for(Object3D aO : model.children(-1)) {
			view.selection.add(aO.id);
		}
		refreshPanel();
		view.redraw();
	}

	void groupSelected() {
		Object3D aG = model.group(view.selection);
		if(aG == null) {
			return;
		}
		view.selection.clear();
		view.selection.add(aG.id);
		commit();
		refreshPanel();
		view.redraw();
	}

	void ungroupSelected() {
		Vector<Integer> aNew = new Vector<Integer>();
		for(Object3D aO : view.selectedObjects()) {
			if(aO.isGroup()) {
				for(Object3D aC : model.ungroup(aO)) {
					aNew.add(aC.id);
				}
			}
		}
		if(aNew.isEmpty()) {
			return;
		}
		view.selection = aNew;
		commit();
		refreshPanel();
		view.redraw();
	}

	void chooseColor() {
		Object3D aM = view.main();
		if(aM == null) {
			return;
		}
		ColorDialog aCD = new ColorDialog(shell);
		aCD.setRGB(new RGB((aM.color >> 16) & 0xFF, (aM.color >> 8) & 0xFF, aM.color & 0xFF));
		RGB aRGB = aCD.open();
		if(aRGB == null) {
			return;
		}
		int aColor = (aRGB.red << 16) | (aRGB.green << 8) | aRGB.blue;
		//A group: all its objects
		for(Object3D aO : model.objects) {
			for(Integer aId : view.selection) {
				if(model.isUnder(aO, aId)) {
					aO.color = aColor;
					break;
				}
			}
		}
		commit();
		refreshPanel();
		view.redraw();
	}

	//== Undo / redo (the view is not part of it) ==

	static String undoKey(Model3D aM) {
		Model3D aC = aM.copy();
		aC.copyCameraFrom(new Model3D());
		return aC.toXml();
	}

	void commit() {
		String aKey = undoKey(model);
		if(undoPos >= 0 && aKey.equals(undoKeys.elementAt(undoPos))) {
			return;
		}
		while(undoStates.size() > undoPos+1) {
			undoStates.remove(undoStates.size()-1);
			undoKeys.remove(undoKeys.size()-1);
		}
		undoStates.add(model.copy());
		undoKeys.add(aKey);
		undoPos++;
		if(undoStates.size() > MAX_UNDO) {
			undoStates.remove(0);
			undoKeys.remove(0);
			undoPos--;
		}
		refreshUndoButtons();
	}

	void undo() {
		commit();
		if(undoPos <= 0) {
			return;
		}
		undoPos--;
		restore(undoStates.elementAt(undoPos));
	}

	void redo() {
		if(undoPos >= undoStates.size()-1) {
			return;
		}
		undoPos++;
		restore(undoStates.elementAt(undoPos));
	}

	void restore(Model3D aState) {
		Model3D aM = aState.copy();
		aM.copyCameraFrom(model);
		model = aM;
		view.cleanSelection();
		refreshPanel();
		view.redraw();
	}
}
