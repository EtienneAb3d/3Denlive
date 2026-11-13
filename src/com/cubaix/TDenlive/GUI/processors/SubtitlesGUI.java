package com.cubaix.TDenlive.GUI.processors;

import java.io.File;

import org.eclipse.swt.SWT;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.FileDialog;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.MessageBox;

import com.cubaix.TDenlive.TDenlive;
import com.cubaix.TDenlive.GUI.ShapesPanel;
import com.cubaix.TDenlive.GUI.widgets.LabelImageButton;
import com.cubaix.TDenlive.GUI.widgets.LabelKeyFrame;
import com.cubaix.TDenlive.medias.Clip;
import com.cubaix.TDenlive.processors.Processor;
import com.cubaix.TDenlive.processors.Subtitles;
import com.cubaix.TDenlive.shapes.ShapesEditor;

/**
 * Subtitles file of the clip, keys and style / position of its text (edited on the monitor at
 * the time cursor: the first change adds a key there; the text field shows the subtitle at the
 * time cursor, and changes it)
 */
public class SubtitlesGUI extends ProcessorGUI implements ShapesEditor.Listener {
	Subtitles sub = null;
	Composite wgt = null;
	Label fileL = null;
	LabelKeyFrame lkf = null;
	ShapesPanel panel = null;

	public SubtitlesGUI(TDenlive aTDe, Composite aParent,Processor aP) {
		super(aTDe, aParent,aP);
		sub = (Subtitles)proc;
		createContents();
	}

	void createContents() {
		wgt = new Composite(container, SWT.NONE);
		wgt.setLayoutData(new GridData(GridData.FILL_HORIZONTAL));
		GridLayout aGL = new GridLayout(1, false);
		tde.gui.applyNoMargin(aGL);
		wgt.setLayout(aGL);
		wgt.setBackground(tde.gui.colorsSwt.WHITE);
		wgt.setForeground(tde.gui.colorsSwt.CUBAIX_BLUE);

		new LabelImageButton(tde,wgt,tde.gui.lngs.get("Subtitles.Add"),"open.gif") {
			@Override
			public void clicked() {
				chooseFile(tde, sub);
			}
		}.tip("SubtitlesAdd");

		lkf = new LabelKeyFrame(tde,wgt,sub,"K") {
			@Override
			public void change(int aValue) {
				panel.update();
				tde.gui.monitorGUI.redraw();
			}
			@Override
			public void move(int aTime) {
				tde.timeLineStack.setRequestedTimePosMS(((Clip)sub.targetMedia).getStartTimeMS()+aTime);
				tde.gui.timeLinesGUI.redraw();
				tde.gui.monitorGUI.redraw();
			}
		};
		updateKey();

		fileL = new Label(wgt, SWT.WRAP);
		tde.gui.applyColorFont(fileL);
		GridData aFGD = new GridData(GridData.FILL_HORIZONTAL);
		aFGD.widthHint = 100;
		fileL.setLayoutData(aFGD);
		updateFile();

		//Help in the popup of the file name
		fileL.setToolTipText(tde.gui.lngs.get("Subtitles.Hint"));

		panel = new ShapesPanel(tde, wgt, sub.getEditor(), true, true);
		panel.textEdited = aT -> {
			sub.setCurrentText(aT);
			tde.gui.monitorGUI.redraw();
		};
		sub.getEditor().addListener(this);
		wgt.addDisposeListener(e -> sub.getEditor().removeListener(this));

		tde.timeLineStack.addTimeListener(this);
	}

	/**
	 * Key widget on the key at the current time (delete mode) or before it (add mode)
	 */
	void updateKey() {
		long aTime = tde.timeLineStack.getTimePosMS();
		int aKey = sub.getPrevKeyGT(aTime);
		if(aKey < 0) {
			aKey = 0;
		}
		lkf.setSelection(aKey);
		boolean aOnKey = sub.isKeyAt(aKey, aTime);
		lkf.setAddMode(!aOnKey);
	}

	void updateFile() {
		if(fileL == null || fileL.isDisposed()) {
			return;
		}
		String aName = sub.getFileName();
		String aText = aName == null ? tde.gui.lngs.get("Subtitles.NoFile")
				: tde.gui.lngs.get("Subtitles.File").replace("%s", aName).replace("%n", ""+sub.getNbCues());
		if(!aText.equals(fileL.getText())) {
			fileL.setText(aText);
			wgt.getParent().layout(true, true);
		}
	}

	/**
	 * Choose the subtitles file of the processor (.srt, .vtt) and read it
	 */
	public static void chooseFile(TDenlive aTDe,Subtitles aSub) {
		FileDialog aFD = new FileDialog(aTDe.gui.shell, SWT.OPEN);
		aFD.setText(aTDe.gui.lngs.get("Subtitles.Add"));
		aFD.setFilterNames(new String[] {aTDe.gui.lngs.get("Subtitles.Files"), "*"});
		aFD.setFilterExtensions(new String[] {"*.srt;*.vtt;*.SRT;*.VTT", "*"});
		String aDir = null;
		if(aSub.getFilePath() != null) {
			aDir = new File(aSub.getFilePath()).getParent();
		}
		else if(aSub.targetMedia instanceof Clip && ((Clip)aSub.targetMedia).getMedia() != null
				&& ((Clip)aSub.targetMedia).getMedia().fileName != null) {
			aDir = new File(((Clip)aSub.targetMedia).getMedia().fileName).getParent();
		}
		if(aDir != null && new File(aDir).isDirectory()) {
			aFD.setFilterPath(aDir);
		}
		String aPath = aFD.open();
		if(aPath == null) {
			return;
		}
		String aError = null;
		try {
			if(aSub.loadFile(aPath) <= 0) {
				aError = aTDe.gui.lngs.get("Subtitles.Empty");
			}
		}
		catch(Throwable t) {
			t.printStackTrace(System.err);
			aError = aTDe.gui.lngs.get("Subtitles.Error")+"\n"+t.getMessage();
		}
		if(aError != null) {
			MessageBox aMB = new MessageBox(aTDe.gui.shell, SWT.ICON_WARNING|SWT.OK);
			aMB.setMessage(aError.replace("%s", new File(aPath).getName()));
			aMB.open();
			return;
		}
		aTDe.gui.monitorGUI.redraw();
	}

	@Override
	public void shapesChanged() {
		if(wgt.isDisposed()) {
			return;
		}
		//A change may have added a key
		updateKey();
		updateFile();
	}

	@Override
	public void selectionChanged() {
		//Selected by a click on the text, unselected by a click outside (see ShapesEditor)
	}

	@Override
	public void timeChanged(long aTimeNew) {
		super.timeChanged(aTimeNew);
		if(wgt.isDisposed()) {
			tde.timeLineStack.removeTimeListener(this);
			return;
		}
		tde.gui.display.asyncExec(new Runnable() {
			@Override
			public void run() {
				if(wgt.isDisposed()) {
					return;
				}
				updateKey();
				panel.update();
			}
		});
	}
}
