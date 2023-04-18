package com.cubaix.TDenlive.GUI;

import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.MessageBox;
import org.eclipse.swt.widgets.Tree;
import org.eclipse.swt.widgets.TreeItem;

import com.cubaix.TDenlive.TDenlive;
import com.cubaix.TDenlive.medias.Clip;

public class ProcessorLibGUI extends TreeView {
	
	public ProcessorLibGUI(TDenlive aTDe, Composite aParent) {
		super(aTDe, aParent);
		populateTree();
	}

	@Override
	void populateTree() {
		TreeItem aFolder = createFolder(tree, tde.gui.lngs.get("Processor.Stereo"));
		createProcessor(aFolder,tde.gui.lngs.get("Processor.Align"), "StereoAligner",true);
		createProcessor(aFolder,tde.gui.lngs.get("Processor.AdaptColor"), "ColorAdapter",true);
		createProcessor(aFolder,tde.gui.lngs.get("Processor.Depthmap"), "Depthmap",true);
		createProcessor(aFolder,tde.gui.lngs.get("Processor.DepthmapStereo"), "DepthmapStereo",true);
		aFolder = createFolder(tree, tde.gui.lngs.get("Processor.Colors"));
		createProcessor(aFolder,tde.gui.lngs.get("Processor.HueSaturation"), "HueSaturation",true);
		createProcessor(aFolder,tde.gui.lngs.get("Processor.BrightnessContrast"), "BrightnessContrast",true);
		aFolder = createFolder(tree, tde.gui.lngs.get("Processor.Image"));
		createProcessor(aFolder,tde.gui.lngs.get("Processor.LensCorrection"), "LensCorrection",true);
		aFolder = createFolder(tree, tde.gui.lngs.get("Processor.Composition"));
		createProcessor(aFolder,tde.gui.lngs.get("Processor.Cropper"), "Cropper",true);
		createProcessor(aFolder,tde.gui.lngs.get("Processor.Placer"), "Placer",true);
		createProcessor(aFolder,tde.gui.lngs.get("Processor.Transparency"), "Transparency",true);
		createProcessor(aFolder,tde.gui.lngs.get("Processor.Chromakey"), "Chromakey",true);
		aFolder = createFolder(tree, tde.gui.lngs.get("Processor.Design"));
		createProcessor(aFolder,tde.gui.lngs.get("Processor.Framing"), "Framing",true);
		aFolder = createFolder(tree, tde.gui.lngs.get("Processor.Expert"));
		createProcessor(aFolder,tde.gui.lngs.get("Processor.Scripter"), "Scripter",true);
		
		tree.addListener(SWT.MouseDoubleClick, e -> {
			Point point = new Point(e.x, e.y);
			TreeItem aTI = tree.getItem (point);
			if(aTI.getData() != null && aTI.getData() instanceof String && "Unavailable".equals(aTI.getData())) {
				MessageBox messageBox = new MessageBox(tde.gui.shell,SWT.ICON_INFORMATION|SWT.OK);
			    messageBox.setMessage(tde.gui.lngs.get("Processor.NotYetAvailable"));
			    int rc = messageBox.open();
			    return;
			}
			if(aTI.getData() == null) {
				//Certainly a folder
				return;
			}
			if(tde.selected.clips.size() <= 0) {
				MessageBox messageBox = new MessageBox(tde.gui.shell,SWT.ICON_INFORMATION|SWT.OK);
			    messageBox.setMessage(tde.gui.lngs.get("Processor.SelectAClip"));
			    int rc = messageBox.open();
				return;
			}
			Clip aClip = tde.selected.clips.lastElement();
			aClip.addProcessor((String)aTI.getData());
			tde.gui.monitorGUI.redraw();
		});
	}
	
	TreeItem createFolder(Tree aTree,String aText) {
		TreeItem aFolder = new TreeItem(aTree, SWT.NONE);
		aFolder.setForeground(tde.gui.colorsSwt.CUBAIX_BLUE);
		aFolder.setFont(tde.gui.fontsSwt.robotoBold);//Font is not really changed(!?)
		aFolder.setText(aText);
		aFolder.setExpanded(true);
		return aFolder;
	}
	TreeItem createProcessor(TreeItem aFolder,String aText,String aProcessorName,boolean aIsAvailable) {
		TreeItem aProcessor = new TreeItem(aFolder, SWT.NONE);
		aProcessor.setForeground(aIsAvailable?tde.gui.colorsSwt.CUBAIX_PINK:tde.gui.colorsSwt.LIGHTGRAY);
		aFolder.setFont(tde.gui.fontsSwt.roboto);//Font is not really changed(!?)
		aProcessor.setText(aText);
		aProcessor.setData(aIsAvailable?aProcessorName:"Unavailable");
		aProcessor.setGrayed(!aIsAvailable);
		return aProcessor;
	}
}
