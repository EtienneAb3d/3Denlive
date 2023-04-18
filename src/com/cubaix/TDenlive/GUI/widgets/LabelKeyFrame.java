package com.cubaix.TDenlive.GUI.widgets;

import org.eclipse.swt.SWT;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Label;

import com.cubaix.TDenlive.TDenlive;
import com.cubaix.TDenlive.medias.Clip;
import com.cubaix.TDenlive.processors.Processor;

public abstract class LabelKeyFrame extends Widget {
	Processor proc = null;
	String label = "";
	KeyFrame kf = null;
	Label addDelete = null;
	boolean addMode = true;
	
	public LabelKeyFrame(TDenlive aTDe, Composite aParent,Processor aClip,String aLabel) {
		super(aTDe, aParent);
		proc = aClip;
		label = aLabel;
		createContents();
	}
	
	public void setSelection(int aSelection) {
		kf.setSelection(aSelection);
	}
	
	public void checkAddMode(long aTime) {
		int aSelected = kf.getSelection();
		if(aSelected < 0) {
			return;
		}
		long aSelectedTime = proc.getTime(aSelected)+((Clip)proc.targetMedia).getStartTimeMS();
		if(aTime != aSelectedTime) {
			setAddMode(true);
		}
	}
	
	public void setAddMode(boolean aMode) {
		if(addMode == aMode) {
			return;
		}
		addMode = aMode;
		if(addMode) {
			addDelete.setImage(tde.gui.imgsSwt.getIcon("add.gif"));
			addMode = true;
		}
		else {
			addDelete.setImage(tde.gui.imgsSwt.getIcon("delete.png"));
			addMode = false;
		}
	}
	
	public int getSelection() {
		return kf.getSelection();
	}
	
	@Override
	void createContents() {
		GridLayout aGL = new GridLayout(6,false);
		aGL.marginLeft = aGL.marginTop = aGL.marginRight = aGL.marginBottom = 0;
		aGL.verticalSpacing = aGL.horizontalSpacing = 0;
		aGL.marginWidth = aGL.marginHeight = 0;
		container.setLayout(aGL);
		container.setBackground(tde.gui.colorsSwt.WHITE);
		container.setForeground(tde.gui.colorsSwt.CUBAIX_BLUE);

		Composite aSpace = new Composite(container, SWT.SEPARATOR);
		tde.gui.applyColorFont(aSpace);
		GridData aSGD = new GridData(GridData.HORIZONTAL_ALIGN_CENTER | GridData.VERTICAL_ALIGN_CENTER); 
		aSGD.widthHint = 6;
		aSGD.heightHint = 16;
		aSpace.setLayoutData(aSGD);

		Label aL = new Label(container, SWT.NONE);
		aL.setBackground(tde.gui.colorsSwt.WHITE);
		aL.setForeground(tde.gui.colorsSwt.CUBAIX_BLUE);
		GridData aLGD = new GridData();
		aLGD.widthHint = 16;
		aL.setLayoutData(aLGD);
		aL.setText(label);

//		Scale aS = new Scale(container, SWT.NONE);
		final LabelKeyFrame aThis = this;//Avoid the confusion between inner class and this class 
		kf = new KeyFrame(tde, container, proc){
			@Override
			public void change(int aKey) {
				aThis.change(aKey);
			}
			@Override
			public void move(int aTime) {
				aThis.move(aTime);
			}
		};
		
		Label aLeft = new Label(container,SWT.NONE);
		aLeft.setLayoutData(new GridData(GridData.FILL_VERTICAL));
		aLeft.setImage(tde.gui.imgsSwt.getIcon("arrowLeftSmallCubaixBlue.gif"));
		aLeft.setBackground(tde.gui.colorsSwt.WHITE);
		aLeft.addListener(SWT.MouseDown,e -> {
			int aKey = proc.getPrevKeyGT(tde.timeLineStack.getTimePosMS()-1);
			if(aKey < 0) {
				aKey = 0;
			}
			long aTimeNew = proc.getTime(aKey)
					+((Clip)proc.targetMedia).getStartTimeMS();
			tde.timeLineStack.setTimePosMS(aTimeNew);
			tde.timeLineStack.setRequestedTimePosMS(aTimeNew);
			
			kf.setSelection(aKey);
			change(aKey);
			setAddMode(false);
			kf.container.redraw();
			tde.gui.timeLinesGUI.redraw();
			tde.gui.monitorGUI.redraw();
		});
		addDelete = new Label(container, SWT.NONE);
		addDelete.setLayoutData(new GridData(GridData.FILL_VERTICAL));
		addDelete.setImage(tde.gui.imgsSwt.getIcon("add.gif"));
		addDelete.setBackground(tde.gui.colorsSwt.WHITE);
		addDelete.addListener(SWT.MouseDown,e -> {
			int aKey = -1;
			if(addMode) {
				aKey = proc.addKeyGT(tde.timeLineStack.getTimePosMS());
				kf.setSelection(aKey);
				change(aKey);
				setAddMode(false);
			}
			else {
				proc.deleteKey(aKey = kf.getSelection());
				if(aKey > 0) {
					aKey--;
				}
				kf.setSelection(aKey);
				change(aKey);
				setAddMode(true);
			}
			kf.setSelection(aKey);
			kf.container.redraw();
		});
		Label aRight = new Label(container,SWT.NONE);
		aRight.setLayoutData(new GridData(GridData.FILL_VERTICAL));
		aRight.setImage(tde.gui.imgsSwt.getIcon("arrowRightSmallCubaixBlue.gif"));
		aRight.setBackground(tde.gui.colorsSwt.WHITE);
		aRight.addListener(SWT.MouseDown,e -> {
			int aKey = proc.getNextKeyGT(tde.timeLineStack.getTimePosMS());
			if(aKey < 0) {
				aKey = proc.getNbKeys()-1;
				long aTimeNew = proc.getTime(0)
						+((Clip)proc.targetMedia).getStartTimeMS()
						+((Clip)proc.targetMedia).getDurationMS()
						;
				tde.timeLineStack.setTimePosMS(aTimeNew-1);
				tde.timeLineStack.setRequestedTimePosMS(aTimeNew-1);
				addDelete.setImage(tde.gui.imgsSwt.getIcon("add.gif"));
				addMode = true;
			}
			else {
				long aTimeNew = proc.getTime(aKey)
						+((Clip)proc.targetMedia).getStartTimeMS();
				tde.timeLineStack.setTimePosMS(aTimeNew);
				tde.timeLineStack.setRequestedTimePosMS(aTimeNew);
				addDelete.setImage(tde.gui.imgsSwt.getIcon("delete.png"));
				addMode = false;
			}
			kf.setSelection(aKey);
			change(aKey);
			kf.container.redraw();
			tde.gui.timeLinesGUI.redraw();
			tde.gui.monitorGUI.redraw();
		});
	}

	public abstract void change(int aKey);
	public abstract void move(int aTime);
}
