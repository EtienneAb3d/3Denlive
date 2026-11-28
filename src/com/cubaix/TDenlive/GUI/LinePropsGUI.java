package com.cubaix.TDenlive.GUI;

import java.util.Vector;

import org.eclipse.swt.SWT;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Text;

import com.cubaix.TDenlive.TDenlive;
import com.cubaix.TDenlive.medias.AudioTimeLine;
import com.cubaix.TDenlive.medias.Clip;
import com.cubaix.TDenlive.medias.VideoTimeLine;
import com.cubaix.TDenlive.utils.StringUtils;

/**
 * Properties of a time line (processors area, after a click on its head): its title (editable, its name by
 * position V1, A1... when empty), its kind and duration, and its clips (media, start, end; a click puts the time
 * cursor at the start of the clip)
 */
public class LinePropsGUI extends CompositePanel {
	Composite box;
	Label kind;
	Text titleText;
	boolean settingTitle = false;
	Label info;
	PropsSection clips;
	VideoTimeLine line = null;

	public LinePropsGUI(TDenlive aTDe, Composite aParent) {
		super(aTDe, aParent);
		createContents();
	}

	Label label(Composite aParent, String aText, boolean aBold) {
		Label aL = new Label(aParent, SWT.WRAP);
		aL.setBackground(tde.gui.colorsSwt.WHITE);
		aL.setForeground(tde.gui.colorsSwt.CUBAIX_BLUE);
		aL.setFont(aBold ? tde.gui.fontsSwt.robotoBold : tde.gui.fontsSwt.roboto);
		aL.setText(aText);
		return aL;
	}

	void createContents() {
		box = new Composite(container, SWT.NONE);
		box.setLayoutData(new GridData(GridData.FILL_BOTH));
		GridLayout aGL = new GridLayout(2, false);
		aGL.marginWidth = 3;
		aGL.marginHeight = 3;
		box.setLayout(aGL);
		box.setBackground(tde.gui.colorsSwt.WHITE);

		kind = label(box, tde.gui.lngs.get("Line.PropsTitle"), true);
		GridData aTGD = new GridData(GridData.FILL_HORIZONTAL);
		aTGD.horizontalSpan = 2;
		kind.setLayoutData(aTGD);

		label(box, tde.gui.lngs.get("Line.Title"), false);
		titleText = new Text(box, SWT.BORDER | SWT.SINGLE);
		GridData aGD = new GridData(GridData.FILL_HORIZONTAL);
		aGD.heightHint = tde.gui.h(18);
		titleText.setLayoutData(aGD);
		titleText.setFont(tde.gui.fontsSwt.roboto);
		titleText.setForeground(tde.gui.colorsSwt.CUBAIX_BLUE);
		tde.gui.tip(titleText, "LineTitle");
		titleText.addListener(SWT.Modify, e -> {
			VideoTimeLine aL = line;
			if(settingTitle || aL == null) {
				return;
			}
			String aV = titleText.getText().trim();
			aL.title = aV.isEmpty() ? null : aV;
			tde.gui.timeLinesGUI.redraw();
			if(tde.gui.propsGUI != null) {
				tde.gui.propsGUI.updateOccurrences();
			}
		});

		label(box, tde.gui.lngs.get("Line.Info"), false);
		info = label(box, "", false);
		info.setLayoutData(new GridData(GridData.FILL_HORIZONTAL));

		clips = new PropsSection(tde, box, "Line.Clips", "Line.NoClip", "LineClips"
				, new String[] {"Line.ClipCol", "MediaProps.OccStart", "MediaProps.OccEnd"}, aD -> {
					Clip aC = (Clip)aD;
					tde.gui.display.asyncExec(() -> tde.gui.goToClip(null, aC));
				});
		((GridData)clips.box.getLayoutData()).horizontalSpan = 2;
	}

	/** Shows the properties of aLine */
	public void setLine(VideoTimeLine aLine) {
		line = aLine;
		refresh();
	}

	public void refresh() {
		if(box == null || box.isDisposed() || line == null) {
			return;
		}
		boolean aAudio = line instanceof AudioTimeLine;
		kind.setText(tde.gui.lngs.get(aAudio ? "Line.PropsTitleAudio" : "Line.PropsTitle")+" "+line.defaultName());
		settingTitle = true;
		String aT = line.title == null ? "" : line.title;
		if(!titleText.getText().equals(aT)) {
			titleText.setText(aT);
		}
		titleText.setMessage(line.defaultName() == null ? "" : line.defaultName());
		settingTitle = false;
		Vector<Clip> aClips = new Vector<Clip>(line.clipList);
		aClips.sort((aA, aB) -> Long.compare(aA.getStartTimeMS(), aB.getStartTimeMS()));
		long aEnd = 0;
		java.util.List<String[]> aRows = new java.util.ArrayList<String[]>();
		java.util.List<Object> aDatas = new java.util.ArrayList<Object>();
		for(Clip aC : aClips) {
			long aE = aC.getStartTimeMS()+aC.getDurationMS();
			aEnd = Math.max(aEnd, aE);
			String aN = aC.getMedia() == null ? "?" : aC.getMedia().displayName();
			aRows.add(new String[] {aN == null ? "?" : new java.io.File(aN).getName()
					, StringUtils.time2Clock(aC.getStartTimeMS()), StringUtils.time2Clock(aE)});
			aDatas.add(aC);
		}
		info.setText(tde.gui.lngs.get("Line.InfoText").replace("%N%", ""+aClips.size())
				.replace("%END%", StringUtils.time2Clock(aEnd))
				+(line.isActive ? "" : " - "+tde.gui.lngs.get("Line.Inactive")));
		clips.fill(aRows, aDatas);
		box.layout(true, true);
		container.layout(true, true);
	}
}
