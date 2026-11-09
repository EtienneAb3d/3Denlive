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
		long aSelectedTime = proc.getTime(aSelected)+((Clip)proc.targetMedia).getKeysOriginMS();
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
			tde.gui.tip(addDelete, "KeyAdd");
			addMode = true;
		}
		else {
			addDelete.setImage(tde.gui.imgsSwt.getIcon("delete.png"));
			tde.gui.tip(addDelete, "KeyDelete");
			addMode = false;
		}
	}
	
	public int getSelection() {
		return kf.getSelection();
	}

	/**
	 * Key to modify for a parameter change at the time cursor: the key exactly at the cursor,
	 * otherwise a new key inserted there (interpolated values) and selected.
	 * Outside the clip, the selected key.
	 * The widgets are refreshed from the key after the current event, once the change is stored.
	 */
	public int editKey() {
		long aTimePos = tde.timeLineStack.getTimePosMS();
		long aOrigin = ((Clip)proc.targetMedia).getKeysOriginMS();
		int aKey = proc.getPrevKeyGT(aTimePos);
		if(aKey >= 0 && proc.getTime(aKey)+aOrigin == aTimePos) {
			if(kf.getSelection() != aKey) {
				kf.setSelection(aKey);
				kf.container.redraw();
			}
			setAddMode(false);
			return aKey;
		}
		int aNew = proc.addKeyGT(aTimePos);
		if(aNew < 0) {
			return kf.getSelection();
		}
		kf.setSelection(aNew);
		setAddMode(false);
		kf.container.redraw();
		tde.gui.timeLinesGUI.redraw();
		final int aK = aNew;
		tde.gui.display.asyncExec(() -> {
			if(!container.isDisposed() && kf.getSelection() == aK) {
				change(aK);
			}
		});
		return aNew;
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
		
		tde.gui.tip(aL, "KeyFrames");
		tde.gui.tip(kf.container, "KeyFrames");
		
		Label aLeft = new Label(container,SWT.NONE);
		aLeft.setLayoutData(new GridData(GridData.FILL_VERTICAL));
		aLeft.setImage(tde.gui.imgsSwt.getIcon("arrowLeftSmallCubaixBlue.gif"));
		aLeft.setBackground(tde.gui.colorsSwt.WHITE);
		tde.gui.tip(aLeft, "KeyPrev");
		aLeft.addListener(SWT.MouseDown,e -> {
			int aKey = proc.getPrevKeyGT(tde.timeLineStack.getTimePosMS()-1);
			if(aKey < 0) {
				aKey = 0;
			}
			//Not before the clip (the first keys of the second part of a cut clip)
			long aTimeNew = Math.max(((Clip)proc.targetMedia).getStartTimeMS()
					, proc.getTime(aKey)+((Clip)proc.targetMedia).getKeysOriginMS());
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
		tde.gui.tip(addDelete, "KeyAdd");
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
		tde.gui.tip(aRight, "KeyNext");
		aRight.addListener(SWT.MouseDown,e -> {
			Clip aClip = (Clip)proc.targetMedia;
			long aEnd = aClip.getStartTimeMS()+aClip.getDurationMS();
			int aKey = proc.getNextKeyGT(tde.timeLineStack.getTimePosMS());
			if(aKey >= 0 && proc.getTime(aKey)+aClip.getKeysOriginMS() >= aEnd) {
				//After the end of the clip (the last keys of the first part of a cut clip)
				aKey = -1;
			}
			if(aKey < 0) {
				aKey = Math.max(0, proc.getPrevKeyGT(aEnd-1));
				long aTimeNew = ((Clip)proc.targetMedia).getStartTimeMS()
						+((Clip)proc.targetMedia).getDurationMS();
				tde.timeLineStack.setTimePosMS(aTimeNew-1);
				tde.timeLineStack.setRequestedTimePosMS(aTimeNew-1);
				addDelete.setImage(tde.gui.imgsSwt.getIcon("add.gif"));
				tde.gui.tip(addDelete, "KeyAdd");
				addMode = true;
			}
			else {
				long aTimeNew = proc.getTime(aKey)
						+((Clip)proc.targetMedia).getKeysOriginMS();
				tde.timeLineStack.setTimePosMS(aTimeNew);
				tde.timeLineStack.setRequestedTimePosMS(aTimeNew);
				addDelete.setImage(tde.gui.imgsSwt.getIcon("delete.png"));
				tde.gui.tip(addDelete, "KeyDelete");
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
