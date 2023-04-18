package com.cubaix.TDenlive.GUI;

import java.util.StringTokenizer;
import java.util.Vector;

import org.eclipse.swt.SWT;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Tree;
import org.eclipse.swt.widgets.TreeItem;

import com.cubaix.TDenlive.TDenlive;
import com.cubaix.TDenlive.GUI.widgets.LabelCombo;
import com.cubaix.TDenlive.medias.Media;

public class MediaPropsGUI extends CompositePanel {
	TreeView treeView = null;
	LabelCombo anamorphW = null;
	LabelCombo rotateL = null;
	LabelCombo rotateR = null;
	Media media	= null;
	String props = null;
	
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
		
		treeView = new TreeView(tde,aC) {
			@Override
			void populateTree() {
			}
		};
	}
	
	public void setMedia(Media aMedia) {
		media = aMedia;
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
