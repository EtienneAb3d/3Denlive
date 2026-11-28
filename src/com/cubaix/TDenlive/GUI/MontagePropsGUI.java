package com.cubaix.TDenlive.GUI;

import java.util.Vector;

import org.eclipse.swt.SWT;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Text;

import com.cubaix.TDenlive.TDenlive;
import com.cubaix.TDenlive.medias.Montage;
import com.cubaix.TDenlive.medias.StereoMovie;
import com.cubaix.TDenlive.utils.StringUtils;

/**
 * Properties of a montage (processors area, after a click in the montage list, or the montage opened in the time
 * lines): its name (editable, as the name of a media; "Master" by default for the main montage), duration, state of its
 * final rendering with a button to make it, a button opening it in the time lines (when it is not the one edited), the
 * montages it includes and those that include it (a click selects that montage in the list).
 */
public class MontagePropsGUI extends CompositePanel {
	Composite box;
	Text nameText;
	boolean settingName = false;
	Label duration;
	Label state;
	Button renderButton;
	Button editButton;
	Composite includes;
	Composite includers;
	Montage shown = null;
	PropsSection occurrences;

	public MontagePropsGUI(TDenlive aTDe, Composite aParent) {
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

		Label aTitle = label(box, tde.gui.lngs.get("Montage.PropsTitle"), true);
		GridData aTGD = new GridData(GridData.FILL_HORIZONTAL);
		aTGD.horizontalSpan = 2;
		aTitle.setLayoutData(aTGD);

		label(box, tde.gui.lngs.get("Montage.Name"), false);
		nameText = new Text(box, SWT.BORDER | SWT.SINGLE);
		GridData aGD = new GridData(GridData.FILL_HORIZONTAL);
		aGD.heightHint = tde.gui.h(18);
		nameText.setLayoutData(aGD);
		nameText.setFont(tde.gui.fontsSwt.roboto);
		nameText.setForeground(tde.gui.colorsSwt.CUBAIX_BLUE);
		tde.gui.tip(nameText, "MontageName");
		nameText.addListener(SWT.Modify, e -> {
			Montage aM = shown;
			if(settingName || aM == null) {
				return;
			}
			String aV = nameText.getText().trim();
			aM.name = aV.isEmpty() ? null : aV;
			//The clips of its rendering show its name
			StereoMovie aR = aM.renderMedia(tde);
			if(aR != null) {
				aR.libName = aM.displayName(tde);
			}
			if(tde.gui.montageListGUI != null) {
				tde.gui.montageListGUI.redraw();
			}
			tde.gui.timeLinesGUI.redraw();
		});

		label(box, tde.gui.lngs.get("Montage.Duration"), false);
		duration = label(box, "", false);
		duration.setLayoutData(new GridData(GridData.FILL_HORIZONTAL));

		label(box, tde.gui.lngs.get("Montage.RenderLabel"), false);
		state = label(box, "", false);
		state.setLayoutData(new GridData(GridData.FILL_HORIZONTAL));

		Composite aButtons = new Composite(box, SWT.NONE);
		GridData aBsGD = new GridData(GridData.FILL_HORIZONTAL);
		aBsGD.horizontalSpan = 2;
		aButtons.setLayoutData(aBsGD);
		GridLayout aBGL = new GridLayout(2, false);
		aBGL.marginWidth = 0;
		aBGL.marginHeight = 0;
		aButtons.setLayout(aBGL);
		aButtons.setBackground(tde.gui.colorsSwt.WHITE);
		editButton = new Button(aButtons, SWT.PUSH);
		editButton.setLayoutData(new GridData());
		editButton.setFont(tde.gui.fontsSwt.roboto);
		editButton.setImage(tde.gui.imgsSwt.getIcon("pencil.png"));
		editButton.setText(tde.gui.lngs.get("Montage.EditButton"));
		tde.gui.tip(editButton, "MontageEdit");
		editButton.addListener(SWT.Selection, e -> {
			Montage aM = shown;
			if(aM != null && tde.montages.contains(aM) && !MontageRenderGUI.isRunning()) {
				tde.gui.showMontage(aM, true);
			}
		});
		renderButton = new Button(aButtons, SWT.PUSH);
		renderButton.setLayoutData(new GridData());
		renderButton.setFont(tde.gui.fontsSwt.roboto);
		tde.gui.tip(renderButton, "MontageRender");
		renderButton.addListener(SWT.Selection, e -> {
			Montage aM = shown;
			if(aM == null || aM.isMain() || MontageRenderGUI.isRunning()) {
				return;
			}
			if(aM.stack.getMaxTimePosMS() <= 0) {
				tde.gui.montageListGUI.warn(tde.gui.lngs.get("Montage.Empty").replace("%s", aM.displayName(tde)));
				return;
			}
			//Its included montages out of date first, then itself
			Vector<Montage> aChain = Montage.staleChain(tde, aM);
			aChain.add(aM);
			MontageRenderGUI.render(tde, aChain, aOk -> refresh());
		});

		Label aInc = label(box, tde.gui.lngs.get("Montage.Includes"), true);
		GridData aIGD = new GridData(GridData.FILL_HORIZONTAL);
		aIGD.horizontalSpan = 2;
		aInc.setLayoutData(aIGD);
		includes = list();
		Label aIncBy = label(box, tde.gui.lngs.get("Montage.IncludedIn"), true);
		GridData aIBGD = new GridData(GridData.FILL_HORIZONTAL);
		aIBGD.horizontalSpan = 2;
		aIncBy.setLayoutData(aIBGD);
		includers = list();
		//Clips of its rendering in the time lines of all the montages
		occurrences = new PropsSection(tde, box, "Montage.Occurrences", "Montage.NoOccurrence", "MontageOccurrences"
				, new String[] {"MediaProps.OccMontage", "MediaProps.OccLine", "MediaProps.OccStart", "MediaProps.OccEnd"}, aD -> {
					Object[] aU = (Object[])aD;
					tde.gui.display.asyncExec(() -> goTo((Montage)aU[0], (com.cubaix.TDenlive.medias.Clip)aU[1]));
				});
		GridData aOGD = (GridData)occurrences.box.getLayoutData();
		aOGD.horizontalSpan = 2;
		refresh();
	}

	Composite list() {
		Composite aC = new Composite(box, SWT.NONE);
		GridData aGD = new GridData(GridData.FILL_HORIZONTAL);
		aGD.horizontalSpan = 2;
		aC.setLayoutData(aGD);
		GridLayout aGL = new GridLayout(1, false);
		aGL.marginWidth = 8;
		aGL.marginHeight = 0;
		aGL.verticalSpacing = 1;
		aC.setLayout(aGL);
		aC.setBackground(tde.gui.colorsSwt.WHITE);
		return aC;
	}

	/** One clickable line per montage (shows it), a gray text when none */
	void fill(Composite aList, java.util.Collection<Montage> aMs) {
		for(org.eclipse.swt.widgets.Control aC : aList.getChildren()) {
			aC.dispose();
		}
		if(aMs.isEmpty()) {
			Label aL = label(aList, tde.gui.lngs.get("Montage.NoneListed"), false);
			aL.setForeground(tde.gui.colorsSwt.GRAY);
		}
		for(Montage aM : aMs) {
			Label aL = label(aList, "• "+aM.displayName(tde), false);
			tde.gui.tip(aL, "MontageGoTo");
			aL.addListener(SWT.MouseDown, e -> tde.gui.display.asyncExec(() -> {
				if(MontageRenderGUI.isRunning() || tde.gui.montageListGUI == null) {
					return;
				}
				if(aM.isMain()) {
					tde.gui.showMontage(aM, true);
				}
				else {
					tde.gui.montageListGUI.selectMontage(aM);
				}
			}));
		}
	}

	/**
	 * The montage aIn edited, the time cursor on aClip (a clip of the rendering of the montage shown); this panel
	 * keeps showing the same montage
	 */
	void goTo(Montage aIn, com.cubaix.TDenlive.medias.Clip aClip) {
		Montage aShown = shown;
		tde.gui.goToClip(aIn, aClip);
		if(aShown != null && tde.gui.montagePropsGUI != null) {
			if(tde.gui.montageListGUI != null) {
				tde.gui.montageListGUI.selected = aShown;
				tde.gui.montageListGUI.redraw();
			}
			tde.gui.montagePropsGUI.setMontage(aShown);
		}
	}

	/** Shows the properties of aM */
	public void setMontage(Montage aM) {
		shown = aM;
		refresh();
	}

	/** Contents for the montage shown (the active one when none or removed) */
	public void refresh() {
		if(box == null || box.isDisposed()) {
			return;
		}
		Montage aM = shown != null && tde.montages.contains(shown) ? shown : tde.montage;
		shown = aM;
		if(aM == null) {
			return;
		}
		settingName = true;
		String aName = aM.name == null ? "" : aM.name;
		if(!nameText.getText().equals(aName)) {
			nameText.setText(aName);
		}
		//Empty: its default name
		nameText.setMessage(aM.isMain() ? Montage.MASTER_NAME : tde.gui.lngs.get("Montage.NewName"));
		settingName = false;
		duration.setText(StringUtils.time2Clock(aM.stack.getMaxTimePosMS()));
		if(aM.isMain()) {
			state.setText(tde.gui.lngs.get("Montage.MainNoRender"));
			state.setForeground(tde.gui.colorsSwt.GRAY);
		}
		else {
			StereoMovie aR = aM.renderMedia(tde);
			state.setText(tde.gui.lngs.get(aR == null ? "Montage.StateNone" : (aR.montageStale ? "Montage.StateStale" : "Montage.StateOk")));
			state.setForeground(aR == null ? tde.gui.colorsSwt.GRAY : (aR.montageStale ? tde.gui.colorsSwt.RED : tde.gui.display.getSystemColor(SWT.COLOR_DARK_GREEN)));
			renderButton.setText(tde.gui.lngs.get(aR == null ? "Montage.RenderNow" : "Montage.RenderAgain"));
		}
		renderButton.setVisible(!aM.isMain());
		((GridData)renderButton.getLayoutData()).exclude = aM.isMain();
		boolean aEdited = aM == tde.montage;
		editButton.setVisible(!aEdited);
		((GridData)editButton.getLayoutData()).exclude = aEdited;
		renderButton.getParent().setVisible(!aM.isMain() || !aEdited);
		((GridData)renderButton.getParent().getLayoutData()).exclude = aM.isMain() && aEdited;
		fill(includes, aM.included(tde));
		fill(includers, aM.includers(tde));
		//Occurrences of its rendering (none for the main montage, never included)
		java.util.List<String[]> aRows = new java.util.ArrayList<String[]>();
		java.util.List<Object> aDatas = new java.util.ArrayList<Object>();
		StereoMovie aRender = aM.isMain() ? null : aM.renderMedia(tde);
		for(Object[] aO : TDeGUI.occurrencesOf(tde, aRender)) {
			Montage aIn = (Montage)aO[0];
			com.cubaix.TDenlive.medias.Clip aC = (com.cubaix.TDenlive.medias.Clip)aO[1];
			com.cubaix.TDenlive.medias.VideoTimeLine aTL = aIn.stack.getLineOf(aC);
			aRows.add(new String[] {aIn.displayName(tde), aTL != null && aTL.getName() != null ? aTL.getName() : "?"
					, StringUtils.time2Clock(aC.getStartTimeMS())
					, StringUtils.time2Clock(aC.getStartTimeMS()+aC.getDurationMS())});
			aDatas.add(aO);
		}
		occurrences.fill(aRows, aDatas);
		occurrences.box.setVisible(!aM.isMain());
		((GridData)occurrences.box.getLayoutData()).exclude = aM.isMain();
		box.layout(true, true);
		container.layout(true, true);
	}
}
