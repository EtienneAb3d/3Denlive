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
	Section occTimeLines = null;
	Section occLibrary = null;
	Section occUses = null;
	Section occProcessors = null;
	
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
	 * Section of the panel: a title, a table whose rows are clickable (one action per row data), and a text when
	 * it is empty
	 */
	class Section {
		Composite box;
		Table table;
		Label none;

		Section(Composite aParent, String aTitleKey, String aNoneKey, String aTipKey, String[] aColKeys, java.util.function.Consumer<Object> aOnClick) {
			box = new Composite(aParent, SWT.NONE);
			box.setLayoutData(new GridData(GridData.FILL_HORIZONTAL));
			GridLayout aGL = new GridLayout(1, false);
			aGL.marginWidth = 3;
			aGL.marginHeight = 2;
			aGL.verticalSpacing = 2;
			box.setLayout(aGL);
			box.setBackground(tde.gui.colorsSwt.WHITE);
			Label aTitle = new Label(box, SWT.NONE);
			aTitle.setBackground(tde.gui.colorsSwt.WHITE);
			aTitle.setForeground(tde.gui.colorsSwt.CUBAIX_BLUE);
			aTitle.setFont(tde.gui.fontsSwt.robotoBold);
			aTitle.setText(tde.gui.lngs.get(aTitleKey));
			none = new Label(box, SWT.NONE);
			none.setBackground(tde.gui.colorsSwt.WHITE);
			none.setForeground(tde.gui.colorsSwt.GRAY);
			none.setFont(tde.gui.fontsSwt.roboto);
			none.setText(tde.gui.lngs.get(aNoneKey));
			none.setLayoutData(new GridData(GridData.FILL_HORIZONTAL));
			table = new Table(box, SWT.SINGLE | SWT.FULL_SELECTION | SWT.V_SCROLL | SWT.BORDER);
			table.setLayoutData(new GridData(GridData.FILL_HORIZONTAL));
			table.setHeaderVisible(true);
			table.setLinesVisible(true);
			table.setFont(tde.gui.fontsSwt.roboto);
			table.setForeground(tde.gui.colorsSwt.CUBAIX_BLUE);
			tde.gui.tip(table, aTipKey);
			for(String aK : aColKeys) {
				TableColumn aCol = new TableColumn(table, SWT.LEFT);
				aCol.setText(tde.gui.lngs.get(aK));
			}
			//Each click, also on the row already selected
			table.addListener(SWT.MouseUp, e -> {
				TableItem aI = table.getItem(new Point(e.x, e.y));
				if(aI != null && aI.getData() != null) {
					aOnClick.accept(aI.getData());
				}
			});
			table.addListener(SWT.DefaultSelection, e -> {
				if(e.item != null && e.item.getData() != null) {
					aOnClick.accept(e.item.getData());
				}
			});
		}

		/**
		 * The rows (texts of the columns) and the data given to the click of each
		 */
		void fill(java.util.List<String[]> aRows, java.util.List<Object> aDatas) {
			if(table.isDisposed()) {
				return;
			}
			table.removeAll();
			for(int r = 0;r < aRows.size();r++) {
				TableItem aI = new TableItem(table, SWT.NONE);
				aI.setText(aRows.get(r));
				aI.setData(aDatas.get(r));
			}
			for(TableColumn aCol : table.getColumns()) {
				aCol.pack();
			}
			boolean aAny = !aRows.isEmpty();
			((GridData)table.getLayoutData()).exclude = !aAny;
			table.setVisible(aAny);
			((GridData)none.getLayoutData()).exclude = aAny;
			none.setVisible(!aAny);
			if(aAny) {
				//At most 6 rows shown, scrolled beyond
				int aNb = Math.min(6, aRows.size());
				((GridData)table.getLayoutData()).heightHint = table.getHeaderHeight() + aNb*table.getItemHeight() + 2*table.getBorderWidth();
			}
		}
	}

	/**
	 * Clips of the media in the time lines (line, start, end; a click puts the time cursor at the start of the clip
	 * and scrolls the time lines to its line), the medias of the media list that use it and those it uses (a click
	 * selects that media)
	 */
	void createOccurrences(Composite aParent) {
		occTimeLines = new Section(aParent, "MediaProps.Occurrences", "MediaProps.NoOccurrence", "MediaOccurrences"
				, new String[] {"MediaProps.OccLine", "MediaProps.OccStart", "MediaProps.OccEnd"}, aD -> goTo((Clip)aD));
		occProcessors = new Section(aParent, "MediaProps.InProcessors", "MediaProps.InProcessorsNone", "MediaInProcessors"
				, new String[] {"MediaProps.OccLine", "MediaProps.OccStart", "MediaProps.ClipCol", "MediaProps.ProcessorCol"}, aD -> {
					Object[] aU = (Object[])aD;
					//After the event: the media panel is replaced by the processors one
					tde.gui.display.asyncExec(() -> goToProcessor((Clip)aU[0], (com.cubaix.TDenlive.processors.Processor)aU[1]));
				});
		occLibrary = new Section(aParent, "MediaProps.UsedBy", "MediaProps.UsedByNone", "MediaUsedBy"
				, new String[] {"MediaProps.MediaCol", "MediaProps.UseCol"}, aD -> tde.gui.mediaListGUI.selectMedia((Media)aD));
		occUses = new Section(aParent, "MediaProps.Uses", "MediaProps.UsesNone", "MediaUses"
				, new String[] {"MediaProps.MediaCol", "MediaProps.UseCol"}, aD -> tde.gui.mediaListGUI.selectMedia((Media)aD));
	}

	void updateOccurrences() {
		if(occTimeLines == null || occTimeLines.box.isDisposed()) {
			return;
		}
		//Time lines
		java.util.List<String[]> aRows = new java.util.ArrayList<String[]>();
		java.util.List<Object> aDatas = new java.util.ArrayList<Object>();
		Vector<Clip> aClips = media == null || tde.timeLineStack == null ? new Vector<Clip>() : tde.timeLineStack.clipsOf(media);
		aClips.sort((aA, aB) -> Long.compare(aA.getStartTimeMS(), aB.getStartTimeMS()));
		for(Clip aC : aClips) {
			VideoTimeLine aTL = tde.timeLineStack.getLineOf(aC);
			aRows.add(new String[] {aTL != null && aTL.getName() != null ? aTL.getName() : "?"
					, StringUtils.time2Clock(aC.getStartTimeMS())
					, StringUtils.time2Clock(aC.getStartTimeMS()+aC.getDurationMS())});
			aDatas.add(aC);
		}
		occTimeLines.fill(aRows, aDatas);
		//Processors of the clips using it (depth map, mask...)
		aRows = new java.util.ArrayList<String[]>();
		aDatas = new java.util.ArrayList<Object>();
		if(media != null && tde.timeLineStack != null) {
			Vector<Object[]> aUses = tde.timeLineStack.processorUsesOf(media);
			aUses.sort((aA, aB) -> Long.compare(((Clip)aA[0]).getStartTimeMS(), ((Clip)aB[0]).getStartTimeMS()));
			for(Object[] aU : aUses) {
				Clip aC = (Clip)aU[0];
				com.cubaix.TDenlive.processors.Processor aP = (com.cubaix.TDenlive.processors.Processor)aU[1];
				VideoTimeLine aTL = tde.timeLineStack.getLineOf(aC);
				aRows.add(new String[] {aTL != null && aTL.getName() != null ? aTL.getName() : "?"
						, StringUtils.time2Clock(aC.getStartTimeMS())
						, aC.getMedia() != null ? nameOf(aC.getMedia()) : "?"
						, (aP.name != null ? aP.name : aP.getClassName())+" ("+tde.gui.lngs.get("MediaProps.Use_"+aU[2])+")"});
				aDatas.add(aU);
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
	void goToProcessor(Clip aClip, com.cubaix.TDenlive.processors.Processor aP) {
		if(tde.timeLineStack == null || !aClip.getProcessors().contains(aP)) {
			return;
		}
		goTo(aClip);
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
	 * Time cursor at the start of the clip, the montage in the monitor, its line scrolled into view
	 */
	void goTo(Clip aClip) {
		TimeLineStack aTLS = tde.timeLineStack;
		if(aTLS == null) {
			return;
		}
		long aT = aClip.getStartTimeMS();
		PlayerControlGUI aPC = tde.gui.playerControlGUI;
		//A play in progress paused first (its thread would set the time again)
		PlayerControlGUI.pauseForEdit(tde);
		aTLS.setRequestedTimePosMS(aT);
		if(tde.gui.monitorGUI != null) {
			tde.gui.monitorGUI.setMedia(aTLS);
		}
		if(aPC != null) {
			aPC.showClock(aT);
		}
		tde.gui.timeLinesGUI.showLine(aTLS.getLineOf(aClip));
		tde.gui.timeLinesGUI.redraw();
		if(tde.gui.monitorGUI != null) {
			tde.gui.monitorGUI.redraw();
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
