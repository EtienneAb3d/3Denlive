package com.cubaix.TDenlive.GUI.processors;

import org.eclipse.swt.SWT;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Label;

import com.cubaix.TDenlive.TDenlive;
import com.cubaix.TDenlive.GUI.ShapesPanel;
import com.cubaix.TDenlive.GUI.widgets.LabelKeyFrame;
import com.cubaix.TDenlive.medias.Clip;
import com.cubaix.TDenlive.processors.Processor;
import com.cubaix.TDenlive.processors.Shapes;
import com.cubaix.TDenlive.shapes.ShapesEditor;

/**
 * Keys and tools of the shapes of a clip: the shapes are edited on the monitor,
 * at the current time (a key is added there at the first change)
 */
public class ShapesGUI extends ProcessorGUI implements ShapesEditor.Listener {
	Shapes sh = null;
	Composite wgt = null;
	LabelKeyFrame lkf = null;
	ShapesPanel panel = null;

	public ShapesGUI(TDenlive aTDe, Composite aParent,Processor aP) {
		super(aTDe, aParent,aP);
		sh = (Shapes)proc;
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

		lkf = new LabelKeyFrame(tde,wgt,sh,"K") {
			@Override
			public void change(int aValue) {
				panel.update();
				tde.gui.monitorGUI.redraw();
			}
			@Override
			public void move(int aTime) {
				tde.timeLineStack.setRequestedTimePosMS(((Clip)sh.targetMedia).getStartTimeMS()+aTime);
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
		aHint.setText(tde.gui.lngs.get("Shapes.HintClip"));

		panel = new ShapesPanel(tde, wgt, sh.getEditor(), true);
		sh.getEditor().addListener(this);
		wgt.addDisposeListener(e -> sh.getEditor().removeListener(this));

		tde.timeLineStack.addTimeListener(this);
	}

	/**
	 * Key widget on the key at the current time (delete mode) or before it (add mode)
	 */
	void updateKey() {
		long aTime = tde.timeLineStack.getTimePosMS();
		int aKey = sh.getPrevKeyGT(aTime);
		if(aKey < 0) {
			aKey = 0;
		}
		lkf.setSelection(aKey);
		boolean aOnKey = sh.getTime(aKey)+((Clip)sh.targetMedia).getStartTimeMS() == aTime;
		lkf.setAddMode(!aOnKey);
	}

	@Override
	public void shapesChanged() {
		if(wgt.isDisposed()) {
			return;
		}
		//A change may have added a key (the monitor redraws itself)
		updateKey();
	}

	@Override
	public void selectionChanged() {
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
