package com.cubaix.TDenlive.GUI;

import java.util.StringTokenizer;
import java.util.Vector;

import org.eclipse.swt.SWT;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Table;
import org.eclipse.swt.widgets.TableColumn;
import org.eclipse.swt.widgets.TableItem;
import org.eclipse.swt.widgets.Text;
import org.eclipse.swt.widgets.Tree;
import org.eclipse.swt.widgets.TreeItem;

import com.cubaix.TDenlive.TDenlive;
import com.cubaix.TDenlive.GUI.widgets.LabelCombo;
import com.cubaix.TDenlive.medias.Clip;
import com.cubaix.TDenlive.medias.Media;
import com.cubaix.TDenlive.medias.TimeLineStack;
import com.cubaix.TDenlive.medias.VideoTimeLine;
import com.cubaix.TDenlive.utils.StringUtils;

public class MediaPropsGUI extends CompositePanel {
	TreeView treeView = null;
	LabelCombo anamorphW = null;
	LabelCombo rotateL = null;
	LabelCombo rotateR = null;
	Media media	= null;
	String props = null;
	//Name of the media in the media list (Media.libName)
	Text nameText = null;
	boolean settingName = false;
	//Clips of the media in the time lines, medias of the media list using it, medias it uses
	PropsSection occTimeLines = null;
	PropsSection occLibrary = null;
	PropsSection occUses = null;
	PropsSection occProcessors = null;
	
	public MediaPropsGUI(TDenlive aTDe,Composite aParent) {
		super(aTDe, aParent);
		createContents();
	}
	
	void createContents() {
		Composite aC = new Composite(container, SWT.NONE);
		aC.setLayoutData(new GridData(GridData.FILL_BOTH));
		GridLayout aGL = new GridLayout(1, false);
		tde.gui.applyNoMargin(aGL);
		aC.setLayout(aGL);
		tde.gui.applyColorFont(aC);
		
		Vector<String[]> aAnamorphW = new Vector<String[]>();
		aAnamorphW.add(new String[] {"1","x1"});
		aAnamorphW.add(new String[] {"2","x2"});
		anamorphW = new LabelCombo(tde, aC,tde.gui.lngs.get("MediaProps.AnamorphW")
				,aAnamorphW,media == null ? "1" : media.anamorphW) {
			@Override
			public void change(String aValue) {
				if(media != null) {
					media.setAnamorphW(aValue);
					tde.gui.mediaListGUI.redraw();
					tde.gui.monitorGUI.redraw();
					tde.gui.timeLinesGUI.redraw();
				}
			}
		};

		Vector<String[]> aRots = new Vector<String[]>();
		aRots.add(new String[] {"0","0°"});
		aRots.add(new String[] {"90","+90°"});
		aRots.add(new String[] {"-90","-90°"});
		aRots.add(new String[] {"180","180°"});
		rotateL = new LabelCombo(tde, aC,tde.gui.lngs.get("MediaProps.RotateLeft")
				,aRots,media == null ? "0" : media.rotateL) {
			@Override
			public void change(String aValue) {
				if(media != null) {
					media.setRotateL(aValue);
					tde.gui.mediaListGUI.redraw();
					tde.gui.monitorGUI.redraw();
					tde.gui.timeLinesGUI.redraw();
				}
			}
		};
		rotateR = new LabelCombo(tde, aC,tde.gui.lngs.get("MediaProps.RotateRight")
				,aRots,media == null ? "0" : media.rotateR) {
			@Override
			public void change(String aValue) {
				if(media != null) {
					media.setRotateR(aValue);
					tde.gui.mediaListGUI.redraw();
					tde.gui.monitorGUI.redraw();
					tde.gui.timeLinesGUI.redraw();
				}
			}
		};
		
		createNameRow(aC);
		createOccurrences(aC);

		treeView = new TreeView(tde,aC) {
			@Override
			void populateTree() {
			}
		};
	}
	
	/**
	 * Name of the media in the media list, editable (empty: the file name, shown as a hint)
	 */
	void createNameRow(Composite aParent) {
		Composite aRow = new Composite(aParent, SWT.NONE);
		aRow.setLayoutData(new GridData(GridData.FILL_HORIZONTAL));
		GridLayout aGL = new GridLayout(2, false);
		aGL.marginWidth = 3;
		aGL.marginHeight = 2;
		aRow.setLayout(aGL);
		aRow.setBackground(tde.gui.colorsSwt.WHITE);
		Label aL = new Label(aRow, SWT.NONE);
		aL.setBackground(tde.gui.colorsSwt.WHITE);
		aL.setForeground(tde.gui.colorsSwt.CUBAIX_BLUE);
		aL.setFont(tde.gui.fontsSwt.roboto);
		aL.setText(tde.gui.lngs.get("MediaProps.Name"));
		nameText = new Text(aRow, SWT.BORDER | SWT.SINGLE);
		GridData aGD = new GridData(GridData.FILL_HORIZONTAL);
		aGD.heightHint = tde.gui.h(18);
		nameText.setLayoutData(aGD);
		nameText.setFont(tde.gui.fontsSwt.roboto);
		nameText.setForeground(tde.gui.colorsSwt.CUBAIX_BLUE);
		tde.gui.tip(nameText, "MediaName");
		nameText.addListener(SWT.Modify, e -> {
			if(settingName || media == null) {
				return;
			}
			String aV = nameText.getText();
			media.libName = aV.trim().isEmpty() || aV.trim().equals(media.fileName) ? null : aV.trim();
			tde.gui.mediaListGUI.redraw();
			tde.gui.timeLinesGUI.redraw();
		});
	}

	/**
	 * Clips of the media in the time lines (line, start, end; a click puts the time cursor at the start of the clip
	 * and scrolls the time lines to its line), the medias of the media list that use it and those it uses (a click
	 * selects that media)
	 */
	void createOccurrences(Composite aParent) {
		occTimeLines = new PropsSection(tde, aParent, "MediaProps.Occurrences", "MediaProps.NoOccurrence", "MediaOccurrences"
				, new String[] {"MediaProps.OccMontage", "MediaProps.OccLine", "MediaProps.OccStart", "MediaProps.OccEnd"}, aD -> {
					Object[] aU = (Object[])aD;
					tde.gui.display.asyncExec(() -> goTo((com.cubaix.TDenlive.medias.Montage)aU[0], (Clip)aU[1]));
				});
		occProcessors = new PropsSection(tde, aParent, "MediaProps.InProcessors", "MediaProps.InProcessorsNone", "MediaInProcessors"
				, new String[] {"MediaProps.OccMontage", "MediaProps.OccLine", "MediaProps.OccStart", "MediaProps.ClipCol", "MediaProps.ProcessorCol"}, aD -> {
					Object[] aU = (Object[])aD;
					//After the event: the media panel is replaced by the processors one
					tde.gui.display.asyncExec(() -> goToProcessor((com.cubaix.TDenlive.medias.Montage)aU[3], (Clip)aU[0], (com.cubaix.TDenlive.processors.Processor)aU[1]));
				});
		occLibrary = new PropsSection(tde, aParent, "MediaProps.UsedBy", "MediaProps.UsedByNone", "MediaUsedBy"
				, new String[] {"MediaProps.MediaCol", "MediaProps.UseCol"}, aD -> tde.gui.mediaListGUI.selectMedia((Media)aD));
		occUses = new PropsSection(tde, aParent, "MediaProps.Uses", "MediaProps.UsesNone", "MediaUses"
				, new String[] {"MediaProps.MediaCol", "MediaProps.UseCol"}, aD -> tde.gui.mediaListGUI.selectMedia((Media)aD));
	}

	public void updateOccurrences() {
		if(occTimeLines == null || occTimeLines.box.isDisposed()) {
			return;
		}
		//Time lines of all the montages (the one edited first)
		java.util.List<String[]> aRows = new java.util.ArrayList<String[]>();
		java.util.List<Object> aDatas = new java.util.ArrayList<Object>();
		for(Object[] aO : TDeGUI.occurrencesOf(tde, media)) {
			com.cubaix.TDenlive.medias.Montage aM = (com.cubaix.TDenlive.medias.Montage)aO[0];
			Clip aC = (Clip)aO[1];
			VideoTimeLine aTL = aM.stack.getLineOf(aC);
			aRows.add(new String[] {aM.displayName(tde), aTL != null && aTL.getName() != null ? aTL.getName() : "?"
					, StringUtils.time2Clock(aC.getStartTimeMS())
					, StringUtils.time2Clock(aC.getStartTimeMS()+aC.getDurationMS())});
			aDatas.add(aO);
		}
		occTimeLines.fill(aRows, aDatas);
		//Processors of the clips using it (depth map, mask...), in all the montages
		aRows = new java.util.ArrayList<String[]>();
		aDatas = new java.util.ArrayList<Object>();
		if(media != null) {
			for(com.cubaix.TDenlive.medias.Montage aM : TDeGUI.montagesEditedFirst(tde)) {
				Vector<Object[]> aUses = aM.stack.processorUsesOf(media);
				aUses.sort((aA, aB) -> Long.compare(((Clip)aA[0]).getStartTimeMS(), ((Clip)aB[0]).getStartTimeMS()));
				for(Object[] aU : aUses) {
					Clip aC = (Clip)aU[0];
					com.cubaix.TDenlive.processors.Processor aP = (com.cubaix.TDenlive.processors.Processor)aU[1];
					VideoTimeLine aTL = aM.stack.getLineOf(aC);
					aRows.add(new String[] {aM.displayName(tde), aTL != null && aTL.getName() != null ? aTL.getName() : "?"
							, StringUtils.time2Clock(aC.getStartTimeMS())
							, aC.getMedia() != null ? nameOf(aC.getMedia()) : "?"
							, (aP.name != null ? aP.name : aP.getClassName())+" ("+tde.gui.lngs.get("MediaProps.Use_"+aU[2])+")"});
					aDatas.add(new Object[] {aU[0], aU[1], aU[2], aM});
				}
			}
		}
		occProcessors.fill(aRows, aDatas);
		//Medias of the media list using this one
		aRows = new java.util.ArrayList<String[]>();
		aDatas = new java.util.ArrayList<Object>();
		if(media != null && tde.mediaList != null) {
			for(java.util.Map.Entry<Media, String> aE : tde.mediaList.usersOf(media).entrySet()) {
				aRows.add(new String[] {nameOf(aE.getKey()), tde.gui.lngs.get("MediaProps.Use_"+aE.getValue())});
				aDatas.add(aE.getKey());
			}
		}
		occLibrary.fill(aRows, aDatas);
		//Medias of the media list this one uses
		aRows = new java.util.ArrayList<String[]>();
		aDatas = new java.util.ArrayList<Object>();
		if(media != null && tde.mediaList != null) {
			for(java.util.Map.Entry<Long, String> aE : media.usedMedias().entrySet()) {
				Media aM = tde.mediaList.getMediaByUID(aE.getKey());
				if(aM != null) {
					aRows.add(new String[] {nameOf(aM), tde.gui.lngs.get("MediaProps.Use_"+aE.getValue())});
					aDatas.add(aM);
				}
			}
		}
		occUses.fill(aRows, aDatas);
		occTimeLines.box.getParent().layout(true, true);
	}

	String nameOf(Media aM) {
		String aN = aM.displayName();
		return aN == null ? "?" : new java.io.File(aN).getName();
	}

	/**
	 * The clip selected alone (as by a click on it), the time cursor at its start, its processors shown with aP
	 * expanded, focused and scrolled to
	 */
	void goToProcessor(com.cubaix.TDenlive.medias.Montage aM, Clip aClip, com.cubaix.TDenlive.processors.Processor aP) {
		if(!aClip.getProcessors().contains(aP)) {
			return;
		}
		goTo(aM, aClip);
		if(tde.timeLineStack == null) {
			return;
		}
		tde.timeLineStack.selectClip(aClip, false);
		tde.gui.setMultiPropsPanel(TDeGUI.MULTIPROPSPANEL_PROCESSORS);
		ProcessorsGUI aPG = tde.gui.processorGUI;
		if(aPG.shownClip != aClip) {
			//The others as for any clip shown (expanded by class during the session), aP expanded
			for(com.cubaix.TDenlive.processors.Processor aO : aClip.getProcessors()) {
				Boolean aE = ProcessorsGUI.EXPANDED.get(aO.getClass());
				aO.isExpended = aE != null && aE;
			}
			aPG.shownClip = aClip;
		}
		aP.isExpended = true;
		aClip.setFocused(aP);
		aPG.setProcessors(aClip.getProcessors());
		aPG.reveal(aP);
		tde.gui.timeLinesGUI.redraw();
		tde.gui.mediaListGUI.redraw();
	}

	/**
	 * The montage aM edited in the time lines (if not yet), the time cursor at the start of the clip, the montage in
	 * the monitor, its line scrolled into view; the panel of the media stays
	 */
	void goTo(com.cubaix.TDenlive.medias.Montage aM, Clip aClip) {
		Media aMedia = media;
		boolean aOther = aM != null && aM != tde.montage;
		tde.gui.goToClip(aM, aClip);
		if(aOther && aMedia != null) {
			//Back to the panel of this media (showMontage() showed the one of the montage)
			if(tde.gui.montageListGUI != null) {
				tde.gui.montageListGUI.unselect();
			}
			tde.gui.setMultiPropsPanel(TDeGUI.MULTIPROPSPANEL_PROPS);
			if(tde.gui.propsGUI != null) {
				tde.gui.propsGUI.setMedia(aMedia);
			}
		}
	}

	public void setMedia(Media aMedia) {
		media = aMedia;
		if(nameText != null && !nameText.isDisposed()) {
			settingName = true;
			try {
				nameText.setText(media.libName != null ? media.libName : (media.fileName != null ? media.fileName : ""));
				nameText.setMessage(media.fileName != null ? media.fileName : "");
			}
			finally {
				settingName = false;
			}
		}
		updateOccurrences();
		setProps(media.metaData);
		anamorphW.select(media.anamorphW);
		rotateL.select(aMedia.rotateL);
		rotateR.select(aMedia.rotateR);
	}
	
	private void setProps(String aProps) {
		treeView.clean();
		props = aProps;
		populateTree();
	}
	
	void populateTree() {
		if(props == null) {
			return;
		}
		StringTokenizer aST = new StringTokenizer(props,"\n");
		TreeItem aLastImageGroup = null;
		String aLastPropName = null;
		TreeItem aLastPropsGroup = null;
		while(aST.hasMoreElements()) {
			String aLine = aST.nextToken().trim();
			if(aLine.isEmpty()) {
				continue;
			}
			if(aLine.charAt(0) != '[') {
				aLastImageGroup = createFolder(treeView.tree, aLine);
				continue;
			}
			String aPropName = aLine.substring(0, aLine.indexOf(']')+1);
			if(!aPropName.equals(aLastPropName)) {
				aLastPropsGroup = createSubFolder(aLastImageGroup, aPropName);
				aLastPropName = aPropName;
			}
			createProp(aLastPropsGroup, aLine);
		}
		treeView.tree.redraw();
	}
	
	TreeItem createFolder(Tree aTree,String aText) {
		TreeItem aFolder = new TreeItem(aTree, SWT.NONE);
		aFolder.setForeground(tde.gui.colorsSwt.CUBAIX_BLUE);
		aFolder.setFont(tde.gui.fontsSwt.robotoBold);//Font is not really changed(!?)
		aFolder.setText(aText);
		aFolder.setExpanded(true);
		return aFolder;
	}
	TreeItem createSubFolder(TreeItem aFolder,String aText) {
		TreeItem aSubFolder = new TreeItem(aFolder, SWT.NONE);
		aSubFolder.setForeground(tde.gui.colorsSwt.CUBAIX_PINK);
		aSubFolder.setFont(tde.gui.fontsSwt.robotoBold);//Font is not really changed(!?)
		aSubFolder.setText(aText);
		return aSubFolder;
	}
	TreeItem createProp(TreeItem aFolder,String aText) {
		TreeItem aProcessor = new TreeItem(aFolder, SWT.NONE);
		aProcessor.setForeground(tde.gui.colorsSwt.PINK_L50);
		aFolder.setFont(tde.gui.fontsSwt.roboto);//Font is not really changed(!?)
		aProcessor.setText(aText);
		return aProcessor;
	}

}
