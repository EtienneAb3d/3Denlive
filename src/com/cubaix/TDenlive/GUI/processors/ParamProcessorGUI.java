package com.cubaix.TDenlive.GUI.processors;

import java.util.Vector;

import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.CLabel;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Composite;

import com.cubaix.TDenlive.TDenlive;
import com.cubaix.TDenlive.GUI.widgets.LabelCombo;
import com.cubaix.TDenlive.GUI.widgets.LabelKeyFrame;
import com.cubaix.TDenlive.GUI.widgets.LabelScale;
import com.cubaix.TDenlive.medias.Clip;
import com.cubaix.TDenlive.processors.Loudness;
import com.cubaix.TDenlive.processors.ParamProcessor;
import com.cubaix.TDenlive.processors.ParamProcessor.Param;
import com.cubaix.TDenlive.processors.Processor;

/**
 * Panel of a ParamProcessor: the K row, then for each parameter its line (header) and its
 * slider, or a combo for a choice. Language entries in the section <ClassName>: <header>,
 * <tag>Letter, <tag> and <tag>_<choice> for the choices; tips Tip.<ClassName>_<tag>
 */
public class ParamProcessorGUI extends ProcessorGUI {
	ParamProcessor pp = null;
	Composite wgt = null;
	LabelKeyFrame lkf = null;
	Object[] widgets = null;
	CLabel infoL = null;

	public ParamProcessorGUI(TDenlive aTDe, Composite aParent,Processor aP) {
		super(aTDe, aParent,aP);
		pp = (ParamProcessor)proc;
		createContents();
	}

	int toSlider(Param aP,double aV) {
		return (int)Math.round(aV*aP.div);
	}

	void show(int aKey) {
		Param[] aPs = pp.params();
		for(int p = 0;p < aPs.length;p++) {
			if(aPs[p].isChoice()) {
				((LabelCombo)widgets[p]).select(""+(int)pp.get(aKey, p));
			}
			else {
				((LabelScale)widgets[p]).setSelection(toSlider(aPs[p], pp.get(aKey, p)));
			}
		}
		showInfo();
	}

	/** The measured loudness for the Loudness processor */
	void showInfo() {
		if(infoL == null || infoL.isDisposed() || !(pp instanceof Loudness)) {
			return;
		}
		Double aL = ((Loudness)pp).measure(false);
		infoL.setText(tde.gui.lngs.get("Loudness.Measured")+" "
				+(aL == null ? tde.gui.lngs.get("Loudness.Measuring") : (aL.isNaN() || aL.isInfinite() ? "-" : String.format("%.1f LUFS", aL))));
		if(aL == null) {
			tde.gui.display.timerExec(500, () -> showInfo());
		}
	}

	void changed() {
		if(!pp.isAudio()) {
			tde.gui.monitorGUI.redraw();
		}
	}

	void createContents() {
		String aCls = pp.getClassName();
		wgt = new Composite(container, SWT.NONE);
		wgt.setLayoutData(new GridData(GridData.FILL_HORIZONTAL));
		GridLayout aGL = new GridLayout(1, false);
		aGL.marginLeft = aGL.marginTop = aGL.marginRight = aGL.marginBottom = 0;
		aGL.verticalSpacing = aGL.horizontalSpacing = 0;
		aGL.marginWidth = aGL.marginHeight = 0;
		wgt.setLayout(aGL);
		wgt.setBackground(tde.gui.colorsSwt.WHITE);
		wgt.setForeground(tde.gui.colorsSwt.CUBAIX_BLUE);

		int aSelectedKey = pp.getPrevKeyGT(tde.timeLineStack.getTimePosMS());
		if(aSelectedKey < 0) {
			aSelectedKey = 0;
		}

		lkf = new LabelKeyFrame(tde,wgt,pp,"K") {
			@Override
			public void change(int aValue) {
				if(aValue >= 0) {
					show(aValue);
				}
			}
			@Override
			public void move(int aTime) {
				tde.timeLineStack.setRequestedTimePosMS(((Clip)pp.targetMedia).getStartTimeMS()+aTime);
				tde.gui.timeLinesGUI.redraw();
				tde.gui.monitorGUI.redraw();
			}
		};
		lkf.setSelection(aSelectedKey);

		Param[] aPs = pp.params();
		widgets = new Object[aPs.length];
		for(int p = 0;p < aPs.length;p++) {
			final int aIdx = p;
			final Param aP = aPs[p];
			String aTip = aP.tipKey != null ? aP.tipKey : aCls+"_"+aP.tag;
			if(aP.isChoice()) {
				Vector<String[]> aVals = new Vector<String[]>();
				for(int c = 0;c < aP.choices.length;c++) {
					String aC = aP.choices[c];
					aVals.add(new String[] {""+c, aC.startsWith("=") ? aC.substring(1) : tde.gui.lngs.get(aCls+"."+aP.tag+"_"+aC)});
				}
				widgets[p] = new LabelCombo(tde,wgt,tde.gui.lngs.get(aCls+"."+aP.tag),aVals,""+(int)pp.get(aSelectedKey, p)) {
					@Override
					public void change(String aValue) {
						lkf.edit(aEK -> pp.set(aEK, aIdx, Integer.parseInt(aValue)));
						changed();
					}
				}.tip(aTip);
				continue;
			}
			if(aP.header != null) {
				CLabel aL = new CLabel(wgt, SWT.NONE);
				tde.gui.applyColorFont(aL);
				//A header starting with '=' is shown as it is (names of the audio streams of TrackMixer)
				aL.setText(aP.header.startsWith("=") ? aP.header.substring(1) : tde.gui.lngs.get(aCls+"."+aP.header));
			}
			String aLetter = tde.gui.lngs.get(aP.letterKey != null ? aP.letterKey : aCls+"."+aP.tag+"Letter");
			widgets[p] = new LabelScale(tde,wgt,aLetter,toSlider(aP, aP.min),toSlider(aP, aP.def),toSlider(aP, aP.max)
					,toSlider(aP, pp.get(aSelectedKey, p))) {
				@Override
				public void change(int aValue) {
					if(lkf.getSelection() >= 0) {
						lkf.edit(aEK -> pp.set(aEK, aIdx, aValue/(double)aP.div));
						changed();
					}
				}
			}.tip(aTip);
		}
		if(pp instanceof Loudness) {
			infoL = new CLabel(wgt, SWT.NONE);
			tde.gui.applyColorFont(infoL);
			//A status, in violet as all the statuses of the processor panels
			infoL.setForeground(tde.gui.colorsSwt.VIOLET);
			infoL.setLayoutData(new GridData(GridData.FILL_HORIZONTAL));
		}

		lkf.change(lkf.displayKey(lkf.getSelection()));
		tde.timeLineStack.addTimeListener(this);
	}

	@Override
	public void timeChanged(long aTimeNew) {
		super.timeChanged(aTimeNew);
		if(wgt.isDisposed()) {
			tde.timeLineStack.removeTimeListener(this);
			return;
		}
		int aSelectedKey = pp.getPrevKeyGT(tde.timeLineStack.getTimePosMS());
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
				show(lkf.displayKey(aKey));
				lkf.checkAddMode(tde.timeLineStack.getTimePosMS());
			}
		});
	}
}
