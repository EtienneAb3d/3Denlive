package com.cubaix.TDenlive.GUI;

import java.util.function.Consumer;
import java.util.function.Supplier;

import org.eclipse.swt.SWT;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Combo;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Spinner;

import com.cubaix.TDenlive.TDenlive;
import com.cubaix.TDenlive.model3d.Lamp;
import com.cubaix.TDenlive.model3d.Model3D;

/**
 * Lamp of a 3D model (modeler, processor panel of the 3D clips): tied to the camera, or placed in the scene by its
 * azimuth, height (degrees around the target, as the camera) and distance. Placed in the scene, it starts where the
 * lamp tied to the camera lights from (Model3D.lampFromCamera() with the camera of aCamera).
 */
public class LampFields {
	TDenlive tde;
	Combo mode;
	Spinner yaw, pitch, distance;
	boolean updating = false;
	Lamp lamp = new Lamp();
	Supplier<Model3D> camera;
	Consumer<Lamp> changed;

	public LampFields(TDenlive aTDe, Composite aParent, Supplier<Model3D> aCamera, Consumer<Lamp> aChanged) {
		tde = aTDe;
		camera = aCamera;
		changed = aChanged;
		Composite aRow = new Composite(aParent, SWT.NONE);
		aRow.setLayoutData(new GridData(GridData.FILL_HORIZONTAL));
		tde.gui.applyColorFont(aRow);
		GridLayout aGL = new GridLayout(2, false);
		aGL.marginWidth = aGL.marginHeight = 0;
		aRow.setLayout(aGL);
		label(aRow, l("Lamp"));
		mode = new Combo(aRow, SWT.READ_ONLY | SWT.DROP_DOWN);
		tde.gui.applyColorFont(mode);
		mode.setItems(l("LampCamera"), l("LampScene"));
		mode.select(Model3D.LAMP_CAMERA);
		mode.setLayoutData(new GridData(GridData.FILL_HORIZONTAL));
		tde.gui.tip(mode, "Model3DLamp");
		mode.addListener(SWT.Selection, e -> {
			if(updating) {
				return;
			}
			int aMode = mode.getSelectionIndex();
			if(aMode == lamp.mode) {
				return;
			}
			if(aMode == Model3D.LAMP_SCENE) {
				//Where the lamp tied to the camera lights from: the same image to start with
				Model3D aM = camera.get().copy();
				aM.lampFromCamera();
				lamp = Lamp.of(aM);
			}
			lamp.mode = aMode;
			show();
			changed.accept(lamp.copy());
		});

		Composite aPlace = new Composite(aParent, SWT.NONE);
		aPlace.setLayoutData(new GridData(GridData.FILL_HORIZONTAL));
		tde.gui.applyColorFont(aPlace);
		GridLayout aPGL = new GridLayout(6, false);
		aPGL.marginWidth = aPGL.marginHeight = 0;
		aPlace.setLayout(aPGL);
		label(aPlace, l("LampYaw"));
		yaw = spinner(aPlace, -180, 180, 0, 5);
		label(aPlace, l("LampPitch"));
		pitch = spinner(aPlace, (int)Math.ceil(Model3D.MIN_PITCH), (int)Math.floor(Model3D.MAX_PITCH), 0, 5);
		label(aPlace, l("LampDistance"));
		distance = spinner(aPlace, 1, 1000, 1, 5);
		show();
	}

	String l(String aKey) {
		return tde.gui.lngs.get("Model3D."+aKey);
	}

	Label label(Composite aParent, String aText) {
		Label aL = new Label(aParent, SWT.NONE);
		aL.setText(aText);
		tde.gui.applyColorFont(aL);
		return aL;
	}

	Spinner spinner(Composite aParent, int aMin, int aMax, int aDigits, int aIncrement) {
		Spinner aS = new Spinner(aParent, SWT.BORDER);
		aS.setDigits(aDigits);
		aS.setMinimum(aMin);
		aS.setMaximum(aMax);
		aS.setIncrement(aIncrement);
		tde.gui.applyColorFont(aS);
		aS.setLayoutData(new GridData(GridData.FILL_HORIZONTAL));
		aS.addListener(SWT.Modify, e -> {
			if(updating || lamp.mode != Model3D.LAMP_SCENE) {
				return;
			}
			lamp.yaw = yaw.getSelection();
			lamp.pitch = pitch.getSelection();
			lamp.distance = distance.getSelection()/10.0;
			changed.accept(lamp.copy());
		});
		return aS;
	}

	/**
	 * Shows aL (no change sent)
	 */
	public void set(Lamp aL) {
		if(mode.isDisposed()) {
			return;
		}
		lamp = aL.copy();
		show();
	}

	void show() {
		updating = true;
		try {
			if(mode.getSelectionIndex() != lamp.mode) {
				mode.select(lamp.mode);
			}
			boolean aScene = lamp.mode == Model3D.LAMP_SCENE;
			int aYaw = (int)Math.round(((lamp.yaw%360)+540)%360-180);
			if(yaw.getSelection() != aYaw) {
				yaw.setSelection(aYaw);
			}
			int aPitch = (int)Math.round(lamp.pitch);
			if(pitch.getSelection() != aPitch) {
				pitch.setSelection(aPitch);
			}
			int aDist = (int)Math.round(lamp.distance*10);
			if(distance.getSelection() != aDist) {
				distance.setSelection(aDist);
			}
			yaw.setEnabled(aScene);
			pitch.setEnabled(aScene);
			distance.setEnabled(aScene);
		}
		finally {
			updating = false;
		}
	}
}
