package com.cubaix.TDenlive.GUI.processors;

import org.eclipse.swt.SWT;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Button;
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
		
		Clip aClip = (Clip)tc.targetMedia;
		if(tde.timeLineStack.isAudioClip(aClip)) {
			//Sound clip: moves between the audio time lines
			new LabelImageButton(tde,aWGT,tde.gui.lngs.get("TimeController.MoveClipUp"),"arrowUp.png") {
				@Override
				public void clicked() {
					tde.timeLineStack.moveUpAudio(aClip);
					tde.gui.timeLinesGUI.redraw();
				}
			}.tip("MoveClipUp");
			new LabelImageButton(tde,aWGT,tde.gui.lngs.get("TimeController.MoveClipDown"),"arrowDown.png") {
				@Override
				public void clicked() {
					tde.timeLineStack.moveDownAudio(aClip);
					tde.gui.timeLinesGUI.redraw();
				}
			}.tip("MoveClipDown");
		}
		else {
			new LabelImageButton(tde,aWGT,tde.gui.lngs.get("TimeController.MoveClipUp"),"arrowUp.png") {
				@Override
				public void clicked() {
					tde.timeLineStack.moveUpVideo(aClip);
					tde.gui.timeLinesGUI.redraw();
					tde.gui.monitorGUI.redraw();
				}
			}.tip("MoveClipUp");

			new LabelImageButton(tde,aWGT,tde.gui.lngs.get("TimeController.MoveClipDown"),"arrowDown.png") {
				@Override
				public void clicked() {
					tde.timeLineStack.moveDownVideo(aClip);
					tde.gui.timeLinesGUI.redraw();
					tde.gui.monitorGUI.redraw();
				}
			}.tip("MoveClipDown");

			new LabelImageButton(tde,aWGT,tde.gui.lngs.get("TimeController.DuplicateClipUp"),"copy.png") {
				@Override
				public void clicked() {
					tde.timeLineStack.duplicateUpVideo(aClip);
					tde.gui.timeLinesGUI.redraw();
					tde.gui.monitorGUI.redraw();
				}
			}.tip("DuplicateClipUp");

		}
		
		if(aClip.getMedia() != null && aClip.getMedia().getAudioReader() != null) {
			//Sound of this clip heard or not (the line can also be muted)
			Button aSoundB = new Button(aWGT, SWT.CHECK);
			tde.gui.applyColorFont(aSoundB);
			tde.gui.tip(aSoundB, "ClipSoundOn");
			GridData aSGD = new GridData(GridData.FILL_HORIZONTAL);
			aSGD.heightHint = tde.gui.h(20);
			aSoundB.setLayoutData(aSGD);
			aSoundB.setText(tde.gui.lngs.get("TimeController.SoundOn"));
			aSoundB.setSelection(aClip.soundOn);
			aSoundB.addListener(SWT.Selection, e -> {
				aClip.soundOn = aSoundB.getSelection();
				tde.gui.timeLinesGUI.redraw();
			});
		}
		
		if(aClip.getTwinClips().size() > 0) {
			new LabelImageButton(tde,aWGT,tde.gui.lngs.get("TimeController.UnlinkTwins"),"cut.gif") {
				@Override
				public void clicked() {
					aClip.unlinkTwins();
					//Rebuild this panel after the end of the click event
					tde.gui.display.asyncExec(() -> tde.gui.processorGUI.setProcessors(aClip.getProcessors()));
				}
			}.tip("UnlinkTwins");
		}
		
		new LabelImageButton(tde,aWGT,tde.gui.lngs.get(aClip.getTwinClips().size() > 0 ? "TimeController.TrashClipAndTwins" : "TimeController.TrashClip"),"trash.png") {
			@Override
			public void clicked() {
				tde.timeLineStack.trashClip(aClip);
				//Remove this panel after the end of the click event
				tde.gui.display.asyncExec(() -> tde.gui.processorGUI.clean());
				tde.gui.timeLinesGUI.redraw();
				tde.gui.monitorGUI.redraw();
			}
		}.tip("TrashClip");
		
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
