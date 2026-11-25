package com.cubaix.TDenlive.GUI;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.function.Consumer;

import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.graphics.Rectangle;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Canvas;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.MessageBox;
import org.eclipse.swt.widgets.ProgressBar;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Text;
import org.eclipse.swt.widgets.Tree;
import org.eclipse.swt.widgets.TreeItem;

import com.cubaix.TDenlive.TDenlive;
import com.cubaix.TDenlive.depth.Downloader;
import com.cubaix.TDenlive.model3d.Model3D;
import com.cubaix.TDenlive.model3d.Object3D;
import com.cubaix.TDenlive.model3d.ObjectCatalog;
import com.cubaix.TDenlive.model3d.Renderer3D;
import com.cubaix.TDenlive.utils.ImageUtils;

/**
 * Library of the textured objects in the add panel of the 3D modeler (type "Objects"): search field,
 * tree category > subcategory > objects (names in the language of the interface, the objects not
 * downloaded yet in gray), turning preview of the selected object once downloaded, its licence, and
 * "Add" (or a double click): download first if needed (question with the size, modal window), then
 * aOnAdd with the object of the catalog.
 */
public class ObjectLibraryGUI {
	TDenlive tde;
	Composite panel;
	Text search;
	Tree tree;
	Canvas preview;
	Image previewImg = null;
	Label info;
	Button addButton;
	Consumer<ObjectCatalog.Item> onAdd;
	ObjectCatalog.Item selected = null;
	Model3D previewModel = null;
	double previewAngle = 0;
	Runnable spin;
	//Session: the last object selected, shown again at the next opening
	static String lastId = null;
	//Thumbnails of the tree (ObjectCatalog.thumb() reduced to ICON pixels), by object id
	static final int ICON = 40;
	java.util.HashMap<String, Image> icons = new java.util.HashMap<String, Image>();

	public ObjectLibraryGUI(TDenlive aTDe, Composite aParent, Consumer<ObjectCatalog.Item> aOnAdd) {
		tde = aTDe;
		onAdd = aOnAdd;
		panel = new Composite(aParent, SWT.NONE);
		tde.gui.applyColorFont(panel);
		GridLayout aGL = new GridLayout(1, false);
		tde.gui.applyNoMargin(aGL);
		aGL.verticalSpacing = 3;
		panel.setLayout(aGL);

		search = new Text(panel, SWT.SEARCH | SWT.ICON_SEARCH | SWT.ICON_CANCEL | SWT.BORDER);
		tde.gui.applyColorFont(search);
		search.setMessage(l("ObjSearch"));
		search.setLayoutData(new GridData(GridData.FILL_HORIZONTAL));
		search.addListener(SWT.Modify, e -> fill());

		tree = new Tree(panel, SWT.BORDER | SWT.SINGLE | SWT.V_SCROLL | SWT.H_SCROLL);
		tde.gui.applyColorFont(tree);
		tree.addListener(SWT.Dispose, e -> {
			for(Image aI : icons.values()) {
				aI.dispose();
			}
			icons.clear();
		});
		GridData aTGD = new GridData(GridData.FILL_HORIZONTAL);
		aTGD.heightHint = tde.gui.h(280);
		tree.setLayoutData(aTGD);
		tree.addListener(SWT.Selection, e -> select(e.item instanceof TreeItem ? (TreeItem)e.item : null));
		tree.addListener(SWT.DefaultSelection, e -> {
			if(e.item instanceof TreeItem && ((TreeItem)e.item).getData() instanceof ObjectCatalog.Item) {
				add();
			}
			else if(e.item instanceof TreeItem) {
				TreeItem aI = (TreeItem)e.item;
				aI.setExpanded(!aI.getExpanded());
			}
		});

		Composite aRow = new Composite(panel, SWT.NONE);
		tde.gui.applyColorFont(aRow);
		aRow.setLayoutData(new GridData(GridData.FILL_HORIZONTAL));
		GridLayout aRGL = new GridLayout(2, false);
		tde.gui.applyNoMargin(aRGL);
		aRGL.horizontalSpacing = 4;
		aRow.setLayout(aRGL);
		preview = new Canvas(aRow, SWT.BORDER | SWT.DOUBLE_BUFFERED);
		GridData aPGD = new GridData(SWT.BEGINNING, SWT.BEGINNING, false, false);
		aPGD.widthHint = 110;
		aPGD.heightHint = 110;
		preview.setLayoutData(aPGD);
		preview.addListener(SWT.Paint, e -> paintPreview(e.gc));
		preview.addListener(SWT.Dispose, e -> disposePreview());
		info = new Label(aRow, SWT.WRAP);
		tde.gui.applyColorFont(info);
		GridData aIGD = new GridData(GridData.FILL_HORIZONTAL);
		aIGD.widthHint = 150;
		info.setLayoutData(aIGD);

		addButton = new Button(panel, SWT.PUSH);
		tde.gui.applyColorFont(addButton);
		addButton.setText(l("ObjAdd"));
		addButton.setLayoutData(new GridData(GridData.FILL_HORIZONTAL));
		addButton.addListener(SWT.Selection, e -> add());

		spin = () -> {
			if(preview.isDisposed()) {
				return;
			}
			if(selected != null && ObjectCatalog.isInstalled(selected) && panel.isVisible()) {
				previewAngle = (previewAngle + 3) % 360;
				disposePreview();
				preview.redraw();
			}
			tde.gui.display.timerExec(80, spin);
		};
		tde.gui.display.timerExec(80, spin);

		ObjectCatalog.onInstalled = () -> tde.gui.display.asyncExec(() -> {
			if(!tree.isDisposed()) {
				refreshInstalled(tree.getItems());
				showInfo();
			}
		});
		fill();
	}

	public Composite getControl() {
		return panel;
	}

	String l(String aKey) {
		return tde.gui.lngs.get("Model3D."+aKey);
	}

	String lng() {
		return tde.config.lng;
	}

	/**
	 * Tree of the categories, filtered by the words of the search field (all in the name, English or
	 * translated, or in the category); with a filter everything is expanded
	 */
	void fill() {
		String aF = search.getText().trim().toLowerCase();
		String[] aWords = aF.isEmpty() ? new String[0] : aF.split("\\s+");
		tree.setRedraw(false);
		tree.removeAll();
		LinkedHashMap<String, List<ObjectCatalog.Item>> aCats = ObjectCatalog.byCategory();
		LinkedHashMap<String, TreeItem> aNodes = new LinkedHashMap<String, TreeItem>();
		TreeItem aToSelect = null;
		for(java.util.Map.Entry<String, List<ObjectCatalog.Item>> aE : aCats.entrySet()) {
			String[] aPath = aE.getKey().split("/");
			StringBuilder aCatText = new StringBuilder();
			for(String aP : aPath) {
				aCatText.append(aP.toLowerCase()).append(' ').append(ObjectCatalog.categoryName(aP, lng()).toLowerCase()).append(' ');
			}
			for(ObjectCatalog.Item aI : aE.getValue()) {
				String aName = ObjectCatalog.name(aI, lng());
				if(aWords.length > 0) {
					String aText = aCatText+aName.toLowerCase()+" "+aI.name.toLowerCase();
					boolean aOK = true;
					for(String aW : aWords) {
						if(!aText.contains(aW)) {
							aOK = false;
							break;
						}
					}
					if(!aOK) {
						continue;
					}
				}
				//Category nodes, created when an object is shown in them
				TreeItem aParent = null;
				String aKey = "";
				for(String aP : aPath) {
					aKey += "/"+aP;
					TreeItem aN = aNodes.get(aKey);
					if(aN == null) {
						aN = aParent == null ? new TreeItem(tree, SWT.NONE) : new TreeItem(aParent, SWT.NONE);
						aN.setText(ObjectCatalog.categoryName(aP, lng()));
						aN.setFont(tde.gui.fontsSwt.robotoBold);
						aNodes.put(aKey, aN);
					}
					aParent = aN;
				}
				TreeItem aIt = new TreeItem(aParent, SWT.NONE);
				aIt.setText(aName);
				aIt.setData(aI);
				aIt.setImage(icon(aI));
				if(!ObjectCatalog.isInstalled(aI)) {
					aIt.setForeground(tde.gui.colorsSwt.GRAY);
				}
				if(aI.id.equals(selected != null ? selected.id : lastId)) {
					aToSelect = aIt;
				}
			}
		}
		//Counts of the categories
		for(java.util.Map.Entry<String, TreeItem> aE : aNodes.entrySet()) {
			aE.getValue().setText(aE.getValue().getText()+" ("+count(aE.getValue())+")");
			if(aWords.length > 0) {
				aE.getValue().setExpanded(true);
			}
		}
		if(aToSelect != null) {
			for(TreeItem aP = aToSelect.getParentItem();aP != null;aP = aP.getParentItem()) {
				aP.setExpanded(true);
			}
			tree.setSelection(aToSelect);
			select(aToSelect);
		}
		else {
			select(null);
		}
		tree.setRedraw(true);
	}

	/**
	 * Thumbnail of an object for the tree, null if it has none (not downloaded)
	 */
	Image icon(ObjectCatalog.Item aI) {
		Image aImg = icons.get(aI.id);
		if(aImg != null) {
			return aImg;
		}
		java.io.File aF = ObjectCatalog.thumb(aI);
		if(aF == null) {
			return null;
		}
		try {
			Image aSrc = new Image(tde.gui.display, aF.getAbsolutePath());
			Rectangle aB = aSrc.getBounds();
			aImg = new Image(tde.gui.display, ICON, ICON);
			org.eclipse.swt.graphics.GC aGC = new org.eclipse.swt.graphics.GC(aImg);
			aGC.setAntialias(SWT.ON);
			aGC.setInterpolation(SWT.HIGH);
			aGC.drawImage(aSrc, 0, 0, aB.width, aB.height, 0, 0, ICON, ICON);
			aGC.dispose();
			aSrc.dispose();
			icons.put(aI.id, aImg);
		}
		catch(Throwable t) {
			t.printStackTrace(System.err);
		}
		return aImg;
	}

	static int count(TreeItem aI) {
		int aN = 0;
		for(TreeItem aC : aI.getItems()) {
			aN += aC.getData() instanceof ObjectCatalog.Item ? 1 : count(aC);
		}
		return aN;
	}

	void refreshInstalled(TreeItem[] aItems) {
		for(TreeItem aI : aItems) {
			if(aI.getData() instanceof ObjectCatalog.Item) {
				ObjectCatalog.Item aO = (ObjectCatalog.Item)aI.getData();
				aI.setForeground(ObjectCatalog.isInstalled(aO) ? null : tde.gui.colorsSwt.GRAY);
				if(aI.getImage() == null) {
					aI.setImage(icon(aO));
				}
			}
			else {
				refreshInstalled(aI.getItems());
			}
		}
	}

	void select(TreeItem aItem) {
		selected = aItem != null && aItem.getData() instanceof ObjectCatalog.Item ? (ObjectCatalog.Item)aItem.getData() : null;
		if(selected != null) {
			lastId = selected.id;
		}
		previewModel = null;
		showInfo();
		disposePreview();
		preview.redraw();
	}

	void showInfo() {
		addButton.setEnabled(selected != null);
		if(selected == null) {
			info.setText(l("ObjChoose"));
		}
		else {
			String aT = ObjectCatalog.name(selected, lng())+"\n"+l("ObjLicence").replace("%LICENCE%", selected.pack.licence).replace("%AUTHOR%", selected.pack.author);
			if(!ObjectCatalog.isInstalled(selected)) {
				aT += "\n"+l("ObjToDownload").replace("%MB%", mb(ObjectCatalog.downloadSize(selected)));
			}
			info.setText(aT);
		}
		info.getParent().layout();
	}

	static String mb(long aBytes) {
		return String.format(java.util.Locale.ROOT, aBytes < 10_000_000 ? "%.1f" : "%.0f", aBytes/1e6);
	}

	void disposePreview() {
		if(previewImg != null) {
			previewImg.dispose();
			previewImg = null;
		}
	}

	void paintPreview(org.eclipse.swt.graphics.GC aGC) {
		Rectangle aR = preview.getClientArea();
		if(selected == null || !ObjectCatalog.isInstalled(selected)) {
			aGC.setBackground(tde.gui.colorsSwt.DARK_GRAY);
			aGC.fillRectangle(aR);
			if(selected != null) {
				//Not downloaded yet: the size to download in the middle
				String aT = mb(ObjectCatalog.downloadSize(selected))+" MB";
				org.eclipse.swt.graphics.Point aE = aGC.textExtent(aT);
				aGC.setForeground(tde.gui.colorsSwt.LIGHTGRAY);
				aGC.drawText(aT, (aR.width-aE.x)/2, (aR.height-aE.y)/2, true);
			}
			return;
		}
		if(previewImg == null && aR.width > 0 && aR.height > 0) {
			try {
				previewImg = ImageUtils.imageToSwt(tde.gui.display, render(selected, aR.width, aR.height));
			}
			catch(Throwable t) {
				t.printStackTrace(System.err);
			}
		}
		if(previewImg != null) {
			aGC.drawImage(previewImg, 0, 0);
		}
	}

	java.awt.image.BufferedImage render(ObjectCatalog.Item aI, int aW, int aH) {
		if(previewModel == null) {
			previewModel = new Model3D();
			Object3D aO = new Object3D(Object3D.ASSET);
			aO.asset = aI.id;
			aO.id = 0;
			previewModel.objects.add(aO);
			previewModel.lastId = 0;
			previewModel.pitch = 20;
			previewModel.yaw = 0;
			previewModel.frame(null, aW/(double)aH);
			previewModel.distance *= 1.05;
		}
		previewModel.objects.get(0).ry = previewAngle;
		Renderer3D.Options aOpt = new Renderer3D.Options();
		//Preview: no supersampling
		aOpt.ss = 1;
		aOpt.bck = 0xFF505050;
		return Renderer3D.render(previewModel, aW, aH, 0, 0, aOpt).image;
	}

	void add() {
		ObjectCatalog.Item aI = selected;
		if(aI == null) {
			return;
		}
		if(!ObjectCatalog.isInstalled(aI) && !download(aI)) {
			return;
		}
		refreshInstalled(tree.getItems());
		showInfo();
		previewModel = null;
		disposePreview();
		preview.redraw();
		onAdd.accept(aI);
	}

	/**
	 * Question with the size, then the download in a modal window
	 */
	boolean download(ObjectCatalog.Item aI) {
		Shell aParent = panel.getShell();
		MessageBox aMB = new MessageBox(aParent, SWT.ICON_QUESTION | SWT.YES | SWT.NO);
		aMB.setText(l("Title"));
		int aN = ObjectCatalog.packCount(aI);
		String aMsg = l(aN > 1 ? "ObjDownloadPack" : "ObjDownloadAsk").replace("%NAME%", ObjectCatalog.name(aI, lng()))
				.replace("%MB%", mb(ObjectCatalog.downloadSize(aI))).replace("%N%", ""+aN)
				.replace("%LICENCE%", aI.pack.licence).replace("%AUTHOR%", aI.pack.author);
		aMB.setMessage(aMsg);
		if(aMB.open() != SWT.YES) {
			return false;
		}
		Shell aD = new Shell(aParent, SWT.DIALOG_TRIM | SWT.APPLICATION_MODAL);
		aD.setText(l("Title"));
		tde.gui.applyColorFont(aD);
		aD.setLayout(new GridLayout(1, false));
		Label aStep = new Label(aD, SWT.WRAP);
		tde.gui.applyColorFont(aStep);
		aStep.setText(l("ObjDownloading").replace("%NAME%", ObjectCatalog.name(aI, lng())));
		aStep.setForeground(tde.gui.colorsSwt.VIOLET);
		GridData aSGD = new GridData(GridData.FILL_HORIZONTAL);
		aSGD.widthHint = 420;
		aStep.setLayoutData(aSGD);
		ProgressBar aBar = new ProgressBar(aD, SWT.HORIZONTAL);
		aBar.setMaximum(1000);
		aBar.setLayoutData(new GridData(GridData.FILL_HORIZONTAL));
		Button aCancel = new Button(aD, SWT.PUSH);
		tde.gui.applyColorFont(aCancel);
		aCancel.setText(l("Cancel"));
		aCancel.setLayoutData(new GridData(SWT.END, SWT.CENTER, false, false));
		final boolean[] aCanceled = {false};
		final String[] aErr = {null};
		Downloader.Progress aP = new Downloader.Progress() {
			@Override
			public void downloaded(long aDone, long aTotal) {
				tde.gui.display.asyncExec(() -> {
					if(aD.isDisposed()) {
						return;
					}
					aStep.setText(l("ObjDownloading").replace("%NAME%", ObjectCatalog.name(aI, lng()))
							+" "+mb(aDone)+(aTotal > 0 ? " / "+mb(aTotal) : "")+" MB");
					if(aTotal > 0) {
						aBar.setSelection((int)Math.min(1000, 1000*aDone/aTotal));
					}
				});
			}

			@Override
			public boolean isCanceled() {
				return aCanceled[0];
			}
		};
		Thread aT = new Thread(() -> {
			try {
				ObjectCatalog.install(aI, aP);
				if(!aCanceled[0] && ObjectCatalog.isInstalled(aI) && ObjectCatalog.thumb(aI) == null) {
					ObjectCatalog.makeThumb(aI);
				}
			}
			catch(Throwable t) {
				t.printStackTrace(System.err);
				aErr[0] = t.getMessage() == null ? t.toString() : t.getMessage();
			}
			tde.gui.display.asyncExec(() -> {
				if(!aD.isDisposed()) {
					aD.dispose();
				}
			});
		}, "Objects3D download");
		aT.setDaemon(true);
		aCancel.addListener(SWT.Selection, e -> {
			aCanceled[0] = true;
			aD.dispose();
		});
		aD.addListener(SWT.Close, e -> aCanceled[0] = true);
		aD.pack();
		Rectangle aPB = aParent.getBounds();
		Rectangle aS = aD.getBounds();
		aD.setLocation(aPB.x + (aPB.width - aS.width) / 2, aPB.y + (aPB.height - aS.height) / 2);
		aD.open();
		aT.start();
		while(!aD.isDisposed()) {
			if(!tde.gui.display.readAndDispatch()) {
				tde.gui.display.sleep();
			}
		}
		if(aErr[0] != null && !aCanceled[0]) {
			MessageBox aE = new MessageBox(aParent, SWT.ICON_ERROR | SWT.OK);
			aE.setText(l("Title"));
			aE.setMessage(l("ObjDownloadError")+" "+aErr[0]);
			aE.open();
			return false;
		}
		return !aCanceled[0] && ObjectCatalog.isInstalled(aI);
	}
}
