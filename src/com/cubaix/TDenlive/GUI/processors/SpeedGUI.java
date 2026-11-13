package com.cubaix.TDenlive.GUI.processors;

import java.util.Locale;

import org.eclipse.swt.SWT;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Text;

import com.cubaix.TDenlive.TDenlive;
import com.cubaix.TDenlive.GUI.widgets.LabelImageButton;
import com.cubaix.TDenlive.GUI.widgets.LabelKeyFrame;
import com.cubaix.TDenlive.GUI.widgets.LabelScale;
import com.cubaix.TDenlive.medias.Clip;
import com.cubaix.TDenlive.processors.Processor;
import com.cubaix.TDenlive.processors.Speed;

/**
 * Speed of a clip, with key frames. The slider is logarithmic (0 = normal speed, +1000 = twice faster);
 * the field gives the speed in %. A change keeps the end of the clip in its media, so the clip
 * gets longer when slowed down and shorter when sped up (Clip.keepMediaEnd()).
 */
public class SpeedGUI extends ProcessorGUI {
	static final double LOG_SCALE = 1000.0;//Slider value for a speed x2
	Speed sp = null;
	Clip clip = null;
	Composite wgt = null;
	LabelKeyFrame lkf = null;
	LabelScale speedLS = null;
	Text percentT = null;
	//Media times of the end of the clip and of the cursor at the start of the current drag of the slider
	//(null: no drag), the reference of all its changes: no drift of the cursor nor of the clip end
	Clip.TimeRef dragRef = null;
	//Key edited by the current drag in automatic mode (its time: media time, kept by the speed changes)
	Long dragKeyTime = null;

	public SpeedGUI(TDenlive aTDe, Composite aParent,Processor aP) {
		super(aTDe, aParent,aP);
		sp = (Speed)proc;
		clip = (Clip)sp.targetMedia;
		createContents();
	}

	static int toSlider(double aSpeed) {
		return (int)Math.round(Math.log(aSpeed)/Math.log(2)*LOG_SCALE);
	}

	static double fromSlider(int aValue) {
		return Math.pow(2, aValue/LOG_SCALE);
	}

	static String percent(double aSpeed) {
		return String.format(Locale.ROOT, "%.0f", aSpeed*100);
	}

	void createContents() {
		wgt = new Composite(container, SWT.NONE);
		wgt.setLayoutData(new GridData(GridData.FILL_HORIZONTAL));
		GridLayout aGL = new GridLayout(1, false);
		aGL.marginLeft = aGL.marginTop = aGL.marginRight = aGL.marginBottom = 0;
		aGL.verticalSpacing = aGL.horizontalSpacing = 0;
		aGL.marginWidth = aGL.marginHeight = 0;
		wgt.setLayout(aGL);
		wgt.setBackground(tde.gui.colorsSwt.WHITE);
		wgt.setForeground(tde.gui.colorsSwt.CUBAIX_BLUE);

		int aSelectedKey = sp.getPrevKeyGT(tde.timeLineStack.getTimePosMS());
		if(aSelectedKey < 0) {
			aSelectedKey = 0;
		}

		lkf = new LabelKeyFrame(tde,wgt,sp,"K") {
			@Override
			public void change(int aValue) {
				if(aValue >= 0) {
					show(sp.getSpeed(aValue));
				}
			}
			@Override
			public void move(int aTime) {
				tde.timeLineStack.setRequestedTimePosMS(clip.getStartTimeMS()+aTime);
				tde.gui.timeLinesGUI.redraw();
				tde.gui.monitorGUI.redraw();
			}
		};
		lkf.setSelection(aSelectedKey);

		Composite aRow = new Composite(wgt, SWT.NONE);
		aRow.setLayoutData(new GridData(GridData.FILL_HORIZONTAL));
		GridLayout aRGL = new GridLayout(3, false);
		tde.gui.applyNoMargin(aRGL);
		aRow.setLayout(aRGL);
		tde.gui.applyColorFont(aRow);
		Label aSL = new Label(aRow,SWT.NONE);
		tde.gui.applyColorFont(aSL);
		aSL.setLayoutData(new GridData(GridData.FILL_HORIZONTAL));
		aSL.setText(tde.gui.lngs.get("Speed.Speed"));
		percentT = tde.gui.framedText(aRow, SWT.RIGHT);
		GridData aTGD = new GridData(GridData.VERTICAL_ALIGN_CENTER);
		aTGD.widthHint = 50;
		aTGD.heightHint = tde.gui.h(12)+2;//+ the frame
		percentT.getParent().setLayoutData(aTGD);
		tde.gui.tip(percentT, "SpeedPercent");
		percentT.addListener(SWT.DefaultSelection, e -> applyPercent());
		percentT.addListener(SWT.FocusOut, e -> applyPercent());
		Label aPL = new Label(aRow,SWT.NONE);
		tde.gui.applyColorFont(aPL);
		aPL.setText(" %");

		speedLS = new LabelScale(tde,wgt,tde.gui.lngs.get("Speed.SpeedLetter")
				,toSlider(Speed.SPEED_MIN),0,toSlider(Speed.SPEED_MAX),toSlider(sp.getSpeed(aSelectedKey))) {
			@Override
			public void change(int aValue) {
				if(lkf.getSelection() >= 0) {
					double aSpeed = Speed.clamp(fromSlider(aValue));
					if(isDragging()) {
						if(dragRef == null) {
							dragRef = clip.timeRef();
						}
						setSpeed(aSpeed, dragRef);
					}
					else {
						//Single step (arrows, wheel, keys, default value)
						setSpeed(aSpeed, null);
					}
					percentT.setText(percent(aSpeed));
				}
			}
			@Override
			public void changeEnd() {
				//The next drag starts from the effective times
				dragRef = null;
				dragKeyTime = null;
			}
		}.tip("Speed");

		Button aPitchB = new Button(wgt, SWT.CHECK);
		tde.gui.applyColorFont(aPitchB);
		tde.gui.tip(aPitchB, "SpeedKeepPitch");
		GridData aPGD = new GridData(GridData.FILL_HORIZONTAL);
		aPGD.heightHint = tde.gui.h(20);
		aPitchB.setLayoutData(aPGD);
		aPitchB.setText(tde.gui.lngs.get("Speed.KeepPitch"));
		aPitchB.setSelection(sp.isKeepPitch());
		aPitchB.addListener(SWT.Selection, e -> sp.setKeepPitch(aPitchB.getSelection()));

		new LabelImageButton(tde,wgt,tde.gui.lngs.get("Speed.FitToMedia"),"clockRange.gif") {
			@Override
			public void clicked() {
				long aD = clip.durationToMediaEndMS();
				if(aD > 0) {
					clip.setDurationMS(aD);
					redrawAll();
				}
			}
		}.tip("SpeedFitToMedia");

		lkf.change(lkf.displayKey(lkf.getSelection()));
		tde.timeLineStack.addTimeListener(this);
	}

	void show(double aSpeed) {
		speedLS.setSelection(toSlider(aSpeed));
		percentT.setText(percent(aSpeed));
	}

	void applyPercent() {
		if(percentT.isDisposed()) {
			return;
		}
		try {
			double aSpeed = Speed.clamp(Double.parseDouble(percentT.getText().trim().replace(',', '.'))/100.0);
			int aShow = lkf.displayKey(lkf.getSelection());
			if(lkf.getSelection() >= 0 && Math.abs(sp.getSpeed(aShow)-aSpeed) > 1e-6) {
				setSpeed(aSpeed, null);
			}
			show(aSpeed);
		}
		catch(Throwable t) {
			//Not a number: back to the value of the key
			int aShow = lkf.displayKey(lkf.getSelection());
			if(aShow >= 0) {
				show(sp.getSpeed(aShow));
			}
		}
	}

	/**
	 * New speed for the selected keys (or the key at the time cursor); the clip keeps the same
	 * end in its media, so its duration on the time line follows
	 */
	void setSpeed(double aSpeed,Clip.TimeRef aRef) {
		Clip.TimeRef aR = aRef != null ? aRef : clip.timeRef();
		clip.keepMediaEnd(() -> {
			if(!sp.isAutoKey()) {
				//Selected keys: all of them
				lkf.edit(aEK -> sp.setSpeed(aEK, aSpeed));
				return;
			}
			//Automatic mode: the key at the cursor (added if none); during a drag, always the same one
			int aKey = -1;
			if(aRef != null && dragKeyTime != null) {
				for(int k = 0;k < sp.getNbKeys();k++) {
					if(sp.getTime(k) == dragKeyTime) {
						aKey = k;
						break;
					}
				}
			}
			if(aKey < 0) {
				aKey = lkf.editKey();
				if(aRef != null && aKey >= 0) {
					dragKeyTime = sp.getTime(aKey);
				}
			}
			if(aKey >= 0) {
				sp.setSpeed(aKey, aSpeed);
			}
		}, aR);
		redrawAll();
	}

	void redrawAll() {
		tde.gui.timeLinesGUI.redraw();
		tde.gui.monitorGUI.redraw();
	}

	@Override
	public void timeChanged(long aTimeNew) {
		super.timeChanged(aTimeNew);
		if(wgt.isDisposed()) {
			tde.timeLineStack.removeTimeListener(this);
			return;
		}
		int aSelectedKey = sp.getPrevKeyGT(tde.timeLineStack.getTimePosMS());
		if(aSelectedKey < 0) {
			aSelectedKey = 0;
		}
		final int aKey = aSelectedKey;
		tde.gui.display.asyncExec(new Runnable() {
			@Override
			public void run() {
				if(wgt.isDisposed()) {
					return;
				}
				lkf.setSelection(aKey);
				show(sp.getSpeed(lkf.displayKey(aKey)));
				lkf.checkAddMode(tde.timeLineStack.getTimePosMS());
			}
		});
	}
}
