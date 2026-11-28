package com.cubaix.TDenlive.GUI;

import java.io.File;
import java.util.Arrays;
import java.util.Vector;

import org.eclipse.swt.SWT;
import org.eclipse.swt.dnd.DND;
import org.eclipse.swt.dnd.DropTarget;
import org.eclipse.swt.dnd.DropTargetAdapter;
import org.eclipse.swt.dnd.DropTargetEvent;
import org.eclipse.swt.dnd.FileTransfer;
import org.eclipse.swt.dnd.Transfer;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.graphics.Rectangle;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.MessageBox;
import org.eclipse.swt.widgets.Shell;

import com.cubaix.TDenlive.TDConfig;
import com.cubaix.TDenlive.TDenlive;
import com.cubaix.TDenlive.utils.ProjectFiles;

/**
 * Files dropped from the file manager onto the media list: the sounds (by extension) are added as sounds,
 * the other files get one type asked for all of them (2D, parallel, crossed, left + right: the files sorted
 * by name taken two by two; MPO and .ply files keep their own reading), then the copy into the project
 * folder is proposed as for the open window, and they are loaded in a thread.
 */
public class MediaDropGUI {
	static final int FLAT = 0, PARALLEL = 1, CROSSED = 2, LEFT_RIGHT = 3, ANAGLYPH = 4;
	static final String[] TYPE_KEYS = {"OpenFlat", "OpenP", "OpenX", "OpenLR", "OpenAnaglyph"};
	//Kind of anaglyph last chosen (session, Anaglyph.TYPES): the one of the ANAGLYPH type
	public static String lastAnaglyph = com.cubaix.TDenlive.utils.Anaglyph.TYPES[0];
	//Last type chosen (session): the default button of the next drop
	static int lastType = FLAT;

	public static void install(TDenlive aTDe, Control aControl) {
		DropTarget aDT = new DropTarget(aControl, DND.DROP_COPY | DND.DROP_MOVE | DND.DROP_LINK | DND.DROP_DEFAULT);
		//Files of the file manager, and the medias of the list itself dragged to change their order
		aDT.setTransfer(new Transfer[] {FileTransfer.getInstance(), org.eclipse.swt.dnd.TextTransfer.getInstance()});
		aDT.addDropListener(new DropTargetAdapter() {
			@Override
			public void dragEnter(DropTargetEvent e) {
				accept(e);
			}

			@Override
			public void dragOperationChanged(DropTargetEvent e) {
				accept(e);
			}

			@Override
			public void dragOver(DropTargetEvent e) {
				accept(e);
			}

			@Override
			public void dragLeave(DropTargetEvent e) {
				if(aTDe.gui.mediaListGUI != null) {
					aTDe.gui.mediaListGUI.reorderClear();
				}
			}

			void accept(DropTargetEvent e) {
				//A media of the list moved in it
				if(MediaListGUI.draggedMedia != null) {
					for(org.eclipse.swt.dnd.TransferData aTD : e.dataTypes) {
						if(org.eclipse.swt.dnd.TextTransfer.getInstance().isSupportedType(aTD)) {
							e.currentDataType = aTD;
							break;
						}
					}
					Point aP = aControl.toControl(e.x, e.y);
					e.detail = aTDe.gui.mediaListGUI != null && aTDe.gui.mediaListGUI.reorderOver(aP.x, aP.y) ? DND.DROP_MOVE : DND.DROP_NONE;
					return;
				}
				boolean aFiles = false;
				for(org.eclipse.swt.dnd.TransferData aTD : e.dataTypes) {
					if(FileTransfer.getInstance().isSupportedType(aTD)) {
						e.currentDataType = aTD;
						aFiles = true;
						break;
					}
				}
				//Always a copy: the files stay where they are (the copy into the project is asked)
				e.detail = aFiles ? DND.DROP_COPY : DND.DROP_NONE;
			}

			@Override
			public void drop(DropTargetEvent e) {
				if(e.data instanceof String && ((String)e.data).startsWith(MediaListGUI.MEDIA_DRAG_PREFIX)) {
					if(aTDe.gui.mediaListGUI != null) {
						Point aP = aControl.toControl(e.x, e.y);
						//The media by the UID of the data (the dragged one may be cleared already)
						com.cubaix.TDenlive.medias.Media aM = null;
						try {
							aM = aTDe.mediaList.getMediaByUID(Long.parseLong(((String)e.data).substring(MediaListGUI.MEDIA_DRAG_PREFIX.length())));
						}
						catch(Throwable t) {
							t.printStackTrace(System.err);
						}
						aTDe.gui.mediaListGUI.reorderDrop(aM, aP.x, aP.y);
					}
					return;
				}
				if(!(e.data instanceof String[])) {
					return;
				}
				String[] aPaths = (String[])e.data;
				//After the end of the drag (the file manager waits for it)
				aTDe.gui.display.asyncExec(() -> dropped(aTDe, aPaths));
			}
		});
	}

	static boolean isAudio(String aPath) {
		String aL = aPath.toLowerCase();
		int aDot = aL.lastIndexOf('.');
		return aDot >= 0 && OpenMediaGUI.AUDIO_EXTS.contains("*"+aL.substring(aDot)+";");
	}

	static void dropped(TDenlive aTDe, String[] aPaths) {
		Vector<String> aAudio = new Vector<String>();
		Vector<String> aOthers = new Vector<String>();
		Vector<String> aIgnored = new Vector<String>();
		String[] aSorted = aPaths.clone();
		Arrays.sort(aSorted, String.CASE_INSENSITIVE_ORDER);
		for(String aP : aSorted) {
			File aF = new File(aP);
			if(!aF.isFile() || aP.matches(".*_3De_WorkCopy.*") || aP.toLowerCase().endsWith(".3de")) {
				aIgnored.add(aF.getName());
			}
			else if(isAudio(aP)) {
				aAudio.add(aP);
			}
			else {
				aOthers.add(aP);
			}
		}
		Shell aShell = aTDe.gui.shell;
		if(!aIgnored.isEmpty()) {
			MessageBox aMB = new MessageBox(aShell, SWT.ICON_WARNING | SWT.OK);
			aMB.setText("3Denlive");
			aMB.setMessage(aTDe.gui.lngs.get("MediaOpen.DropIgnored")+"\n\n"+String.join("\n", aIgnored));
			aMB.open();
		}
		if(aAudio.isEmpty() && aOthers.isEmpty()) {
			return;
		}
		int aType = FLAT;
		if(!aOthers.isEmpty()) {
			aType = askType(aTDe, aShell, aOthers);
			if(aType < 0) {
				return;
			}
			lastType = aType;
		}
		Vector<String> aAll = new Vector<String>(aOthers);
		aAll.addAll(aAudio);
		boolean aCopy = askCopy(aTDe, aShell, aAll.toArray(new String[0]));
		final int aT = aType;
		aTDe.gui.setWaitCursor(true);
		Thread aTh = new Thread(() -> {
			try {
				Vector<String> aImgs = new Vector<String>();
				for(String aP : aOthers) {
					aImgs.add(aCopy ? ProjectFiles.importFile(aTDe, aP, false, null) : aP);
				}
				for(int i = 0;i < aImgs.size();i++) {
					String aP = aImgs.elementAt(i);
					try {
						if(aP.toLowerCase().endsWith(".mpo")) {
							aTDe.mediaList.loadImageMPO(aP);
						}
						else if(aT == LEFT_RIGHT) {
							if(i+1 < aImgs.size()) {
								aTDe.mediaList.loadImageLR(aP, aImgs.elementAt(i+1), TDConfig.PROCESSING_MODE_RENDER, 0);
							}
							i++;
						}
						else if(aT == PARALLEL) {
							aTDe.mediaList.loadImageP(aP, TDConfig.PROCESSING_MODE_RENDER, 0);
						}
						else if(aT == CROSSED) {
							aTDe.mediaList.loadImageX(aP, TDConfig.PROCESSING_MODE_RENDER, 0);
						}
						else if(aT == ANAGLYPH) {
							aTDe.mediaList.loadImageAnaglyph(aP, lastAnaglyph, TDConfig.PROCESSING_MODE_RENDER, 0);
						}
						else {
							aTDe.mediaList.loadImageLR(aP, aP, TDConfig.PROCESSING_MODE_RENDER, 0);
						}
					}
					catch(Throwable t) {
						t.printStackTrace(System.err);
					}
					redraw(aTDe);
				}
				for(String aP : aAudio) {
					try {
						aTDe.mediaList.loadAudio(aCopy ? ProjectFiles.importFile(aTDe, aP, false, null) : aP);
					}
					catch(Throwable t) {
						t.printStackTrace(System.err);
					}
					redraw(aTDe);
				}
			}
			finally {
				aTDe.gui.display.asyncExec(() -> aTDe.gui.setWaitCursor(false));
			}
		}, "Dropped files");
		aTh.setDaemon(true);
		aTh.start();
	}

	static void redraw(TDenlive aTDe) {
		aTDe.gui.display.asyncExec(() -> {
			if(aTDe.gui.mediaListGUI != null) {
				aTDe.gui.mediaListGUI.redraw();
			}
		});
	}

	/**
	 * Type of the dropped images / videos, the same for all; -1 if canceled
	 */
	static int askType(TDenlive aTDe, Shell aParent, Vector<String> aFiles) {
		return askType(aTDe, aParent, aFiles, aTDe.gui.lngs.get("MediaOpen.DropTitle"));
	}

	/**
	 * Same, with the title aTitle
	 */
	static int askType(TDenlive aTDe, Shell aParent, Vector<String> aFiles, String aTitle) {
		Shell aD = new Shell(aParent, SWT.DIALOG_TRIM | SWT.APPLICATION_MODAL);
		aD.setText(aTitle);
		aTDe.gui.applyColorFont(aD);
		aD.setImage(aTDe.gui.imgsSwt.getIcon("3Denlive32.png"));
		GridLayout aGL = new GridLayout(1, false);
		aGL.marginWidth = 12;
		aGL.marginHeight = 10;
		aGL.verticalSpacing = 6;
		aD.setLayout(aGL);
		Label aAsk = new Label(aD, SWT.WRAP);
		aTDe.gui.applyColorFont(aAsk);
		aAsk.setFont(aTDe.gui.fontsSwt.robotoBold);
		aAsk.setText(aTDe.gui.lngs.get("MediaOpen.DropAsk").replace("%N%", ""+aFiles.size()));
		GridData aAGD = new GridData(GridData.FILL_HORIZONTAL);
		aAGD.widthHint = 440;
		aAsk.setLayoutData(aAGD);
		Label aList = new Label(aD, SWT.WRAP);
		aTDe.gui.applyColorFont(aList);
		StringBuilder aSB = new StringBuilder();
		for(int i = 0;i < aFiles.size();i++) {
			if(i == 6) {
				aSB.append("…");
				break;
			}
			aSB.append(i > 0 ? "\n" : "").append(new File(aFiles.elementAt(i)).getName());
		}
		aList.setText(aSB.toString());
		GridData aLGD = new GridData(GridData.FILL_HORIZONTAL);
		aLGD.widthHint = 440;
		aList.setLayoutData(aLGD);
		final int[] aChoice = {-1};
		boolean aEven = aFiles.size() % 2 == 0;
		Button aDefault = null;
		for(int t = 0;t < TYPE_KEYS.length;t++) {
			final int aT = t;
			Composite aRow = aD;
			if(t == ANAGLYPH) {
				//The button, then the kind of anaglyph
				aRow = new Composite(aD, SWT.NONE);
				GridLayout aRL = new GridLayout(2, false);
				aRL.marginWidth = aRL.marginHeight = 0;
				aRow.setLayout(aRL);
				aRow.setLayoutData(new GridData(GridData.FILL_HORIZONTAL));
				aTDe.gui.applyColorFont(aRow);
			}
			Button aB = new Button(aRow, SWT.PUSH);
			aTDe.gui.applyColorFont(aB);
			aB.setText(aTDe.gui.lngs.get("MediaOpen."+TYPE_KEYS[t]));
			aB.setImage(aTDe.gui.imgsSwt.getIcon("openObject.png"));
			aB.setAlignment(SWT.LEFT);
			aB.setLayoutData(new GridData(GridData.FILL_HORIZONTAL));
			String aTip = aTDe.gui.lngs.get("Tip."+TYPE_KEYS[t]);
			if(t == LEFT_RIGHT) {
				aB.setEnabled(aEven);
				aTip = aEven ? aTDe.gui.lngs.get("MediaOpen.DropPairs") : aTDe.gui.lngs.get("MediaOpen.DropOdd");
			}
			aB.setToolTipText(aTip);
			if(t == ANAGLYPH) {
				anaglyphCombo(aTDe, aRow);
			}
			aB.addListener(SWT.Selection, e -> {
				aChoice[0] = aT;
				aD.dispose();
			});
			if(t == lastType && aB.getEnabled()) {
				aDefault = aB;
			}
		}
		Button aCancel = new Button(aD, SWT.PUSH);
		aTDe.gui.applyColorFont(aCancel);
		aCancel.setText(aTDe.gui.lngs.get("MediaOpen.DropCancel"));
		aCancel.setLayoutData(new GridData(SWT.END, SWT.CENTER, false, false));
		aCancel.addListener(SWT.Selection, e -> aD.dispose());
		if(aDefault != null) {
			aD.setDefaultButton(aDefault);
		}
		aD.pack();
		Rectangle aPB = aParent.getBounds();
		Rectangle aS = aD.getBounds();
		aD.setLocation(aPB.x + (aPB.width - aS.width) / 2, aPB.y + (aPB.height - aS.height) / 2);
		aD.open();
		if(aDefault != null) {
			aDefault.setFocus();
		}
		while(!aD.isDisposed()) {
			if(!aTDe.gui.display.readAndDispatch()) {
				aTDe.gui.display.sleep();
			}
		}
		return aChoice[0];
	}

	/** Combo of the kinds of anaglyph (lastAnaglyph selected, and set at each choice) */
	public static org.eclipse.swt.widgets.Combo anaglyphCombo(TDenlive aTDe, Composite aParent) {
		org.eclipse.swt.widgets.Combo aC = new org.eclipse.swt.widgets.Combo(aParent, SWT.READ_ONLY);
		aTDe.gui.applyColorFont(aC);
		String[] aTypes = com.cubaix.TDenlive.utils.Anaglyph.TYPES;
		for(String aT : aTypes) {
			aC.add(aTDe.gui.lngs.get("Anaglyph.Type_"+aT));
		}
		aC.select(Math.max(0, com.cubaix.TDenlive.utils.Anaglyph.indexOf(lastAnaglyph)));
		aC.setToolTipText(aTDe.gui.lngs.get("Tip.AnaglyphType"));
		aC.addListener(SWT.Selection, e -> {
			if(aC.getSelectionIndex() >= 0) {
				lastAnaglyph = aTypes[aC.getSelectionIndex()];
			}
		});
		return aC;
	}

	/**
	 * Files outside the folder of the saved project: copy them into it? (as OpenMediaGUI.askCopy())
	 */
	static boolean askCopy(TDenlive aTDe, Shell aShell, String... aPaths) {
		Vector<String> aOutside = ProjectFiles.outside(aTDe, aPaths);
		if(aOutside.isEmpty()) {
			return false;
		}
		MessageBox aMB = new MessageBox(aShell, SWT.ICON_QUESTION | SWT.YES | SWT.NO);
		aMB.setText(aTDe.gui.lngs.get("MediaOpen.CopyTitle"));
		aMB.setMessage(aTDe.gui.lngs.get("MediaOpen.CopyAsk").replace("%DIR%", ProjectFiles.projectDir(aTDe))
				+"\n\n"+String.join("\n", aOutside));
		java.util.HashMap<Integer,String> aLabels = new java.util.HashMap<Integer,String>();
		aLabels.put(SWT.YES, aTDe.gui.lngs.get("MediaOpen.CopyYes"));
		aLabels.put(SWT.NO, aTDe.gui.lngs.get("MediaOpen.CopyNo"));
		aMB.setButtonLabels(aLabels);
		return aMB.open() == SWT.YES;
	}
}
