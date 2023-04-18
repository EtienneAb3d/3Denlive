package com.cubaix.TDenlive.GUI.widgets;

import org.eclipse.swt.SWT;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Scale;
import org.omg.PortableServer.ServantRetentionPolicyValue;

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
		aMid.addListener(SWT.MouseDown,e -> {
			sb.setSelection(zero);
			change(sb.getSelection());
			container.redraw();
		});
		Label aRight = new Label(container,SWT.NONE);
		aRight.setLayoutData(new GridData(GridData.FILL_VERTICAL));
		aRight.setImage(tde.gui.imgsSwt.getIcon("arrowRightSmallCubaixBlue.gif"));
		aRight.setBackground(tde.gui.colorsSwt.WHITE);
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

	public abstract void change(int aValue);
}
