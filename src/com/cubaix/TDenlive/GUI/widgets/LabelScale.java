package com.cubaix.TDenlive.GUI.widgets;

import org.eclipse.swt.SWT;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Label;

import com.cubaix.TDenlive.TDenlive;

public abstract class LabelScale extends Widget {
	String label = "";
	ScaleBar sb = null;
	int min = 0;
	int max = 100;
	int zero = 0;
	int init = 0;
			
	public LabelScale(TDenlive aTDe, Composite aParent,String aLabel,int aMin,int aZero,int aMax,int aInit) {
		super(aTDe, aParent);
		label = aLabel;
		min = aMin;
		max = aMax;
		zero = aZero;
		init = aInit;
		createContents();
	}
	
	public void setSelection(int aSelection) {
		sb.setSelection(aSelection);
	}

	public int getSelection() {
		return sb.getSelection();
	}

	/**
	 * Small check box right of the slider (to link it to other sliders), with the help "Tip.<aTipKey>"
	 */
	public Button addLinkCheck(boolean aInit,String aTipKey) {
		((GridLayout)container.getLayout()).numColumns++;
		Button aB = new Button(container, SWT.CHECK);
		aB.setBackground(tde.gui.colorsSwt.WHITE);
		aB.setLayoutData(new GridData(GridData.VERTICAL_ALIGN_CENTER));
		aB.setSelection(aInit);
		tde.gui.tip(aB, aTipKey);
		container.layout();
		return aB;
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
		aSpace.setBackground(tde.gui.colorsSwt.WHITE);
		aSpace.setForeground(tde.gui.colorsSwt.CUBAIX_BLUE);
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
		final LabelScale aThis = this;//Avoid the confusion between inner class and this class 
		sb = new ScaleBar(tde, container, min, zero, max, init){
			@Override
			public void change(int aValue) {
				aThis.change(aValue);
			}
			@Override
			public void changeEnd() {
				aThis.changeEnd();
			}
		};
//		aS.setLayoutData(new GridData(GridData.FILL_BOTH));
//		aS.setBackground(tde.gui.colors.WHITE);
//		aS.setMinimum(0);
//		aS.setMaximum(max-min);
//		aS.setSelection(init-min);
//		int aInc = (max-min)/1000;
//		aInc = aInc <= 0?1:aInc;
//		aS.setIncrement(aInc);
//		aS.setPageIncrement(aInc*20);
//		aS.addListener(SWT.Selection,e -> {
//			change(aS.getSelection()+min);
//			aS.redraw();
//		});
		
		Label aLeft = new Label(container,SWT.NONE);
		aLeft.setLayoutData(new GridData(GridData.FILL_VERTICAL));
		aLeft.setImage(tde.gui.imgsSwt.getIcon("arrowLeftSmallCubaixBlue.gif"));
		aLeft.setBackground(tde.gui.colorsSwt.WHITE);
		tde.gui.tip(aLeft, "ScaleMinus");
		aLeft.addListener(SWT.MouseDown,e -> {
			if((e.stateMask & SWT.SHIFT) != 0 || (e.stateMask & SWT.CTRL) != 0) {
				sb.setSelection(sb.getSelection()-sb.getPageIncrement());
			}
			else {
				sb.setSelection(sb.getSelection()-sb.getIncrement());
			}
			change(sb.getSelection());
		});
		Label aMid = new Label(container, SWT.NONE);
		aMid.setLayoutData(new GridData(GridData.FILL_VERTICAL));
		aMid.setImage(tde.gui.imgsSwt.getIcon("goalCubaixBlueLight.png"));
		aMid.setBackground(tde.gui.colorsSwt.WHITE);
		tde.gui.tip(aMid, "ScaleZero");
		aMid.addListener(SWT.MouseDown,e -> {
			sb.setSelection(zero);
			change(sb.getSelection());
			container.redraw();
		});
		Label aRight = new Label(container,SWT.NONE);
		aRight.setLayoutData(new GridData(GridData.FILL_VERTICAL));
		aRight.setImage(tde.gui.imgsSwt.getIcon("arrowRightSmallCubaixBlue.gif"));
		aRight.setBackground(tde.gui.colorsSwt.WHITE);
		tde.gui.tip(aRight, "ScalePlus");
		aRight.addListener(SWT.MouseDown,e -> {
			if((e.stateMask & SWT.SHIFT) != 0 || (e.stateMask & SWT.CTRL) != 0) {
				sb.setSelection(sb.getSelection()+sb.getPageIncrement());
			}
			else {
				sb.setSelection(sb.getSelection()+sb.getIncrement());
			}
			change(sb.getSelection());
		});
	}

	/**
	 * Help message of this slider: the "Tip.<aKey>" language entry, shown on the letter and the bar,
	 * and before the help of the -, default and + buttons
	 */
	public LabelScale tip(String aKey) {
		String aTip = tde.gui.lngs.get("Tip."+aKey);
		for(Control aC : container.getChildren()) {
			String aOwn = aC.getToolTipText();
			aC.setToolTipText(aOwn == null ? aTip : aTip+"\n"+aOwn);
		}
		return this;
	}

	public abstract void change(int aValue);

	/** End of a drag of the slider */
	public void changeEnd() {}

	/** True while the slider is dragged: change() is called again until changeEnd() */
	public boolean isDragging() {
		return sb != null && sb.isDragging();
	}
}
