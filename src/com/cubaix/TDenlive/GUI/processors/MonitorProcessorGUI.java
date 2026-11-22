package com.cubaix.TDenlive.GUI.processors;

import java.util.Vector;

import org.eclipse.swt.SWT;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Canvas;
import org.eclipse.swt.widgets.Composite;

import com.cubaix.TDenlive.TDenlive;
import com.cubaix.TDenlive.GUI.widgets.LabelCombo;
import com.cubaix.TDenlive.processors.MonitorProcessor;
import com.cubaix.TDenlive.processors.Processor;

/**
 * Panel of a MonitorProcessor: its mode (combo), a reset button if any, and the drawing of
 * its last analysis, redrawn at each new one (polled every MIN_MS). The processor computes
 * only while this panel is shown (MonitorProcessor.isWatched(), renewed by the polling)
 */
public class MonitorProcessorGUI extends ProcessorGUI {
	static final long MIN_MS = 40;
	MonitorProcessor mp = null;
	Composite wgt = null;
	Canvas canvas = null;
	long shownVersion = -1;

	public MonitorProcessorGUI(TDenlive aTDe, Composite aParent,Processor aP) {
		super(aTDe, aParent,aP);
		mp = (MonitorProcessor)proc;
		createContents();
	}

	void createContents() {
		String aCls = mp.getClassName();
		wgt = new Composite(container, SWT.NONE);
		wgt.setLayoutData(new GridData(GridData.FILL_HORIZONTAL));
		GridLayout aGL = new GridLayout(1, false);
		aGL.marginLeft = aGL.marginTop = aGL.marginRight = aGL.marginBottom = 0;
		aGL.verticalSpacing = aGL.horizontalSpacing = 0;
		aGL.marginWidth = aGL.marginHeight = 0;
		wgt.setLayout(aGL);
		wgt.setBackground(tde.gui.colorsSwt.WHITE);
		wgt.setForeground(tde.gui.colorsSwt.CUBAIX_BLUE);

		String[] aModes = mp.modes();
		if(aModes != null) {
			Vector<String[]> aVals = new Vector<String[]>();
			for(int m = 0;m < aModes.length;m++) {
				aVals.add(new String[] {""+m, tde.gui.lngs.get(aCls+".Mode_"+aModes[m])});
			}
			new LabelCombo(tde,wgt,tde.gui.lngs.get(aCls+".Mode"),aVals,""+mp.mode) {
				@Override
				public void change(String aValue) {
					mp.mode = Integer.parseInt(aValue);
					tde.gui.monitorGUI.redraw();
				}
			}.tip(aCls+"_Mode");
		}
		if(mp.resetKey() != null) {
			Button aB = new Button(wgt, SWT.PUSH);
			aB.setText(tde.gui.lngs.get(aCls+"."+mp.resetKey()));
			aB.setFont(tde.gui.fontsSwt.roboto);
			aB.setLayoutData(new GridData(GridData.HORIZONTAL_ALIGN_END));
			aB.addListener(SWT.Selection, e -> mp.reset());
		}

		canvas = new Canvas(wgt, SWT.DOUBLE_BUFFERED);
		GridData aCGD = new GridData(GridData.FILL_HORIZONTAL);
		aCGD.heightHint = tde.gui.h(mp.drawingHeight());
		canvas.setLayoutData(aCGD);
		canvas.addListener(SWT.Paint, e -> {
			try {
				mp.paint(e.gc, canvas.getClientArea());
			}
			catch(Throwable t) {
				t.printStackTrace(System.err);
			}
		});

		mp.watchedMS = System.currentTimeMillis();
		poll();
		//First analysis of the image shown
		if(!mp.isAudio()) {
			tde.gui.monitorGUI.redraw();
		}
	}

	/**
	 * Every MIN_MS while the canvas exists: tells the processor that it is watched (it computes
	 * meanwhile) and redraws when a new analysis is there
	 */
	void poll() {
		if(canvas.isDisposed()) {
			return;
		}
		if(canvas.isVisible()) {
			boolean aWasWatched = mp.isWatched();
			mp.watchedMS = System.currentTimeMillis();
			if(!aWasWatched && !mp.isAudio()) {
				//Shown again (panel expanded, scrolled): analyse the image now
				tde.gui.monitorGUI.redraw();
			}
		}
		if(mp.version != shownVersion) {
			shownVersion = mp.version;
			canvas.redraw();
		}
		tde.gui.display.timerExec((int)MIN_MS, () -> poll());
	}
}
