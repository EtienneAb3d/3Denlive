package com.cubaix.TDenlive.GUI.processors;

import org.eclipse.swt.SWT;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Label;

import com.cubaix.TDenlive.TDenlive;
import com.cubaix.TDenlive.GUI.widgets.LabelImageButton;
import com.cubaix.TDenlive.GUI.widgets.LabelText;
import com.cubaix.TDenlive.medias.Clip;
import com.cubaix.TDenlive.processors.Processor;
import com.cubaix.TDenlive.processors.TimeController;

public class TimeControllerGUI extends ProcessorGUI {
	TimeController tc = null;
	LabelText startTime = null;
	LabelText shiftTime = null;
	LabelText duration = null;
	LabelText endTime = null;
	LabelText fadeIn = null;
	LabelText fadeOut = null;
	
	public TimeControllerGUI(TDenlive aTDe, Composite aParent, Processor aP) {
		super(aTDe, aParent, aP);
		tc = (TimeController)aP;
		createContents();
	}

	void createContents() {
		Composite aWGT = new Composite(container, SWT.NONE);
		aWGT.setLayoutData(new GridData(GridData.FILL_HORIZONTAL));
		GridLayout aGL = new GridLayout(1, false);
		aGL.marginLeft = aGL.marginTop = aGL.marginRight = aGL.marginBottom = 0;
		aGL.verticalSpacing = aGL.horizontalSpacing = 0;
		aGL.marginWidth = aGL.marginHeight = 0;
		aWGT.setLayout(aGL);
		aWGT.setBackground(tde.gui.colorsSwt.WHITE);
		aWGT.setForeground(tde.gui.colorsSwt.CUBAIX_BLUE);
		
		Label aName = new Label(aWGT,SWT.NULL);
		aName.setLayoutData(new GridData(GridData.FILL_HORIZONTAL));
		aName.setBackground(tde.gui.colorsSwt.WHITE);
		aName.setForeground(tde.gui.colorsSwt.CUBAIX_BLUE);
		aName.setFont(tde.gui.fontsSwt.robotoBold);
		aName.setText(tc.targetMedia.fileName);
		aName.setAlignment(SWT.CENTER);
		
		LabelImageButton aMvupClip = new LabelImageButton(tde,aWGT,tde.gui.lngs.get("TimeController.MoveClipUp"),"arrowUp.png") {
			@Override
			public void clicked() {
				tde.timeLineStack.moveUpVideo((Clip)tc.targetMedia);
				tde.gui.timeLinesGUI.redraw();
				tde.gui.monitorGUI.redraw();
			}
		};
		
		LabelImageButton aMvdwnClip = new LabelImageButton(tde,aWGT,tde.gui.lngs.get("TimeController.MoveClipDown"),"arrowDown.png") {
			@Override
			public void clicked() {
				tde.timeLineStack.moveDownVideo((Clip)tc.targetMedia);
				tde.gui.timeLinesGUI.redraw();
				tde.gui.monitorGUI.redraw();
			}
		};
		
		LabelImageButton aDupClip = new LabelImageButton(tde,aWGT,tde.gui.lngs.get("TimeController.DuplicateClipUp"),"copy.png") {
			@Override
			public void clicked() {
				tde.timeLineStack.duplicateUpVideo((Clip)tc.targetMedia);
				tde.gui.timeLinesGUI.redraw();
				tde.gui.monitorGUI.redraw();
			}
		};
		
		LabelImageButton aTwinToAudioClip = new LabelImageButton(tde,aWGT,tde.gui.lngs.get("TimeController.TwinToAudioClip"),"sound.gif") {
			@Override
			public void clicked() {
				tde.timeLineStack.twinToAudio((Clip)tc.targetMedia);
				tde.gui.timeLinesGUI.redraw();
				tde.gui.monitorGUI.redraw();
			}
		};
		
		LabelImageButton aTrashClip = new LabelImageButton(tde,aWGT,tde.gui.lngs.get("TimeController.TrashClip"),"trash.png") {
			@Override
			public void clicked() {
				tde.timeLineStack.trashVideoClip((Clip)tc.targetMedia);
				tde.timeLineStack.trashAudioClip((Clip)tc.targetMedia);
				tde.gui.timeLinesGUI.redraw();
				tde.gui.monitorGUI.redraw();
			}
		};
		
		startTime = new LabelText(tde,aWGT,tde.gui.lngs.get("TimeController.StartTime"),tc.getStartClock()) {
			@Override
			public void change(String aValue) {
				long aT = tc.setStartClock(aValue);
				if(aT < 0) {
					startTime.setWarning(true);
				}
				else {
					startTime.setWarning(false);
					endTime.setValue(tc.getEndClock());
					tde.gui.timeLinesGUI.redraw();
					tde.gui.monitorGUI.redraw();
				}
			}
		};

		shiftTime = new LabelText(tde,aWGT,tde.gui.lngs.get("TimeController.ShiftTime"),tc.getShiftClock()) {
			@Override
			public void change(String aValue) {
				long aT = tc.setShiftClock(aValue);
				if(aT < 0) {
					shiftTime.setWarning(true);
				}
				else {
					shiftTime.setWarning(false);
					duration.setValue(tc.getDurationClock());//Be sure the value is ok
					endTime.setValue(tc.getEndClock());
					tde.gui.timeLinesGUI.redraw();
					tde.gui.monitorGUI.redraw();
				}
			}
		};

		duration = new LabelText(tde,aWGT,tde.gui.lngs.get("TimeController.Duration"),tc.getDurationClock()) {
			@Override
			public void change(String aValue) {
				long aT = tc.setDurationClock(aValue);
				if(aT < 0) {
					duration.setWarning(true);
				}
				else {
					duration.setWarning(false);
					duration.setValue(tc.getDurationClock());//Be sure the value is ok
					endTime.setValue(tc.getEndClock());
					tde.gui.timeLinesGUI.redraw();
					tde.gui.monitorGUI.redraw();
				}
			}
		};

		endTime = new LabelText(tde,aWGT,tde.gui.lngs.get("TimeController.EndTime"),tc.getEndClock()) {
			@Override
			public void change(String aValue) {
				long aT = tc.setEndClock(aValue);
				if(aT < 0) {
					endTime.setWarning(true);
				}
				else {
					endTime.setWarning(false);
					duration.setValue(tc.getDurationClock());
					endTime.setValue(tc.getEndClock());
					tde.gui.timeLinesGUI.redraw();
					tde.gui.monitorGUI.redraw();
				}
			}
		};

		fadeIn = new LabelText(tde,aWGT,tde.gui.lngs.get("TimeController.FadeIn"),tc.getFadeInClock()) {
			@Override
			public void change(String aValue) {
				long aT = tc.setFadeInClock(aValue);
				if(aT < 0) {
					fadeIn.setWarning(true);
				}
				else {
					fadeIn.setWarning(false);
					tde.gui.timeLinesGUI.redraw();
					tde.gui.monitorGUI.redraw();
				}
			}
		};
		
		fadeOut = new LabelText(tde,aWGT,tde.gui.lngs.get("TimeController.FadeOut"),tc.getFadeOutClock()) {
			@Override
			public void change(String aValue) {
				long aT = tc.setFadeOutClock(aValue);
				if(aT < 0) {
					fadeOut.setWarning(true);
				}
				else {
					fadeOut.setWarning(false);
					tde.gui.timeLinesGUI.redraw();
					tde.gui.monitorGUI.redraw();
				}
			}
		};
	}
}
