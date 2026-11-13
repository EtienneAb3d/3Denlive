package com.cubaix.TDenlive.GUI.processors;

import java.util.HashSet;
import java.util.Vector;

import org.eclipse.swt.SWT;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Spinner;
import org.eclipse.swt.widgets.Tree;

import com.cubaix.TDenlive.TDenlive;
import com.cubaix.TDenlive.GUI.Model3DGUI;
import com.cubaix.TDenlive.GUI.Model3DView;
import com.cubaix.TDenlive.GUI.widgets.LabelKeyFrame;
import com.cubaix.TDenlive.medias.Clip;
import com.cubaix.TDenlive.medias.Model3DMedia;
import com.cubaix.TDenlive.model3d.Model3D;
import com.cubaix.TDenlive.model3d.Object3D;
import com.cubaix.TDenlive.processors.Objects3D;
import com.cubaix.TDenlive.processors.Processor;

/**
 * Keys of the objects of a 3D clip: a small view of the model at the time cursor (select, move, turn,
 * scale with the handles), its tree and its numeric fields. A change goes to the selected keys, else to
 * the key at the time cursor (added if needed), as for the other processors.
 */
public class Objects3DGUI extends ProcessorGUI implements Model3DView.Source {
	Objects3D ob = null;
	Composite wgt = null;
	LabelKeyFrame lkf = null;
	Model3DView view = null;
	Tree tree = null;
	String treeSignature = null;
	HashSet<Integer> collapsed = new HashSet<Integer>();
	Spinner[][] spinners = new Spinner[3][3];
	Button[] toolButtons = new Button[3];
	Button[] axisButtons = new Button[4];
	Button snapCheck = null;
	Button resetButton = null;
	//Model shown at the time cursor (or the main selected key), the camera of the view
	Model3D model = null;
	Model3D camera = null;
	//Keys changed by the current action
	Vector<Integer> editKeys = new Vector<Integer>();
	boolean updating = false;

	public Objects3DGUI(TDenlive aTDe, Composite aParent, Processor aP) {
		super(aTDe, aParent, aP);
		ob = (Objects3D)proc;
		camera = ob.getBaseModel().copy();
		createContents();
	}

	String l(String aKey) {
		return tde.gui.lngs.get("Model3D."+aKey);
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

		//The model of the media, in the modeler
		Button aEdit = new Button(wgt, SWT.PUSH);
		aEdit.setText(l("Edit"));
		aEdit.setImage(tde.gui.imgsSwt.getIcon("cube3d.png"));
		tde.gui.applyColorFont(aEdit);
		aEdit.setLayoutData(new GridData(GridData.FILL_HORIZONTAL));
		aEdit.addListener(SWT.Selection, e -> editModel());

		lkf = new LabelKeyFrame(tde, wgt, ob, "K") {
			@Override
			public void change(int aKey) {
				refreshModel();
				refreshPanel();
				view.redraw();
				tde.gui.monitorGUI.redraw();
			}
			@Override
			public void move(int aTime) {
				tde.timeLineStack.setRequestedTimePosMS(((Clip)ob.targetMedia).getStartTimeMS()+aTime);
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
		aHint.setText(l("HintClip"));

		refreshModel();
		view = new Model3DView(tde, wgt, SWT.BORDER, this, null);
		view.setCompact(true);
		GridData aVGD = new GridData(GridData.FILL_HORIZONTAL);
		aVGD.heightHint = 200;
		aVGD.widthHint = 100;
		view.canvas.setLayoutData(aVGD);

		Model3DGUI.createToolRows(tde, wgt, view, toolButtons, axisButtons);
		Composite aRow = new Composite(wgt, SWT.NONE);
		aRow.setLayoutData(new GridData(GridData.FILL_HORIZONTAL));
		tde.gui.applyColorFont(aRow);
		GridLayout aRGL = new GridLayout(3, false);
		tde.gui.applyNoMargin(aRGL);
		aRGL.horizontalSpacing = 2;
		aRow.setLayout(aRGL);
		snapCheck = new Button(aRow, SWT.CHECK);
		snapCheck.setText(l("SnapShort"));
		tde.gui.applyColorFont(snapCheck);
		snapCheck.setLayoutData(new GridData(GridData.FILL_HORIZONTAL));
		snapCheck.addListener(SWT.Selection, e -> view.snap = snapCheck.getSelection());
		Button aFrame = new Button(aRow, SWT.PUSH);
		aFrame.setText(l("Frame"));
		tde.gui.applyColorFont(aFrame);
		aFrame.addListener(SWT.Selection, e -> view.frameSelection());
		resetButton = new Button(aRow, SWT.PUSH);
		resetButton.setText(l("Reset"));
		tde.gui.applyColorFont(resetButton);
		tde.gui.tip(resetButton, "Objects3DReset");
		resetButton.addListener(SWT.Selection, e -> resetSelected());

		tree = Model3DGUI.createTree(tde, wgt, 5, view, collapsed, () -> {
			refreshPanel();
			view.redraw();
		});
		Model3DGUI.createTransformFields(tde, wgt, spinners, (r, c) -> spinnerChanged(r, c));

		refreshPanel();
		tde.timeLineStack.addTimeListener(this);
	}

	/**
	 * Key widget on the key at the current time (delete mode) or before it (add mode)
	 */
	void updateKey() {
		long aTime = tde.timeLineStack.getTimePosMS();
		int aKey = ob.getPrevKeyGT(aTime);
		if(aKey < 0) {
			aKey = 0;
		}
		lkf.setSelection(aKey);
		boolean aOnKey = ob.isKeyAt(aKey, aTime);
		lkf.setAddMode(!aOnKey);
	}

	/**
	 * Model shown: the main selected key, else the time cursor
	 */
	void refreshModel() {
		if(view != null && view.isDragging()) {
			return;
		}
		int aMain = ob.getMainSelectedKey();
		model = aMain >= 0 ? ob.modelForKey(aMain) : ob.modelAt(tde.timeLineStack.getTimePosMS());
	}

	void refreshPanel() {
		if(wgt.isDisposed()) {
			return;
		}
		updating = true;
		try {
			view.cleanSelection();
			treeSignature = Model3DGUI.fillTree(tde, tree, model, treeSignature, collapsed, view.selection);
			Model3DGUI.setSpinners(spinners, view.main());
			Model3DGUI.refreshToolRows(view, toolButtons, axisButtons);
			snapCheck.setSelection(view.snap);
			resetButton.setEnabled(view.main() != null);
		}
		finally {
			updating = false;
		}
	}

	//== Model3DView.Source ==

	@Override
	public Model3D getModel() {
		return model;
	}

	@Override
	public Model3D getCamera() {
		return camera;
	}

	/**
	 * Keys of the change: the selected ones, else the one at the time cursor (added if needed);
	 * the view then edits the model of the main one
	 */
	@Override
	public void beginChange() {
		editKeys = ob.getSelectedKeys();
		if(editKeys.isEmpty()) {
			int aK = lkf.editKey();
			if(aK >= 0) {
				editKeys.add(aK);
			}
		}
		if(!editKeys.isEmpty()) {
			model = ob.modelForKey(editKeys.lastElement());
		}
	}

	@Override
	public void changed(boolean aFinal) {
		//The same transform of the edited nodes in all the keys of the change
		for(int aK : editKeys) {
			for(Object3D aO : view.roots()) {
				ob.setPose(aK, aO);
			}
		}
		if(aFinal) {
			refreshModel();
			refreshPanel();
			lkf.setSelection(editKeys.isEmpty() ? lkf.getSelection() : editKeys.lastElement());
			tde.gui.monitorGUI.redraw();
			tde.gui.timeLinesGUI.redraw();
		}
		else {
			updating = true;
			try {
				Model3DGUI.setSpinners(spinners, view.main());
			}
			finally {
				updating = false;
			}
		}
	}

	@Override
	public void selectionChanged() {
		refreshPanel();
	}

	//== Fields ==

	void spinnerChanged(int aRow, int aCol) {
		if(updating) {
			return;
		}
		Object3D aMain = view.main();
		if(aMain == null) {
			return;
		}
		int aMainId = aMain.id;
		Vector<Integer> aSel = new Vector<Integer>(view.selection);
		double aV = Model3DGUI.spinnerValue(spinners[aRow][aCol], aRow);
		lkf.edit(aEK -> {
			Model3D aM = ob.modelForKey(aEK);
			Object3D aKM = aM.findById(aMainId);
			if(aKM == null) {
				return;
			}
			Vector<Object3D> aNodes = new Vector<Object3D>();
			for(Object3D aO : aM.objects) {
				if(aSel.contains(aO.id)) {
					aNodes.add(aO);
				}
			}
			Model3DGUI.applyField(aKM, aNodes, aRow, aCol, aV);
			for(Object3D aO : aNodes) {
				ob.setPose(aEK, aO);
			}
		});
		refreshModel();
		view.redraw();
		tde.gui.monitorGUI.redraw();
	}

	/**
	 * The selected nodes back to the model of the media, in the keys of the change
	 */
	void resetSelected() {
		Vector<Integer> aSel = new Vector<Integer>(view.selection);
		if(aSel.isEmpty()) {
			return;
		}
		lkf.edit(aEK -> {
			for(Integer aId : aSel) {
				ob.resetPose(aEK, aId);
			}
		});
		refreshModel();
		refreshPanel();
		view.redraw();
		tde.gui.monitorGUI.redraw();
	}

	void editModel() {
		if(!(((Clip)ob.targetMedia).getMedia() instanceof Model3DMedia)) {
			return;
		}
		Model3DMedia aMM = (Model3DMedia)((Clip)ob.targetMedia).getMedia();
		Model3D aModel = new Model3DGUI(tde, aMM.getModel()).open();
		if(aModel == null || wgt.isDisposed()) {
			return;
		}
		aMM.setModel(aModel);
		refreshModel();
		refreshPanel();
		view.redraw();
		tde.gui.mediaListGUI.redraw();
		tde.gui.monitorGUI.redraw();
		tde.gui.timeLinesGUI.redraw();
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
			refreshModel();
			refreshPanel();
			view.redraw();
		});
	}
}
