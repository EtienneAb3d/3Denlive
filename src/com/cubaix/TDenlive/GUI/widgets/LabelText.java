package com.cubaix.TDenlive.GUI.widgets;

import org.eclipse.swt.SWT;
import org.eclipse.swt.events.ModifyEvent;
import org.eclipse.swt.events.ModifyListener;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Text;

import com.cubaix.TDenlive.TDenlive;

public abstract class LabelText extends Widget {
	String label = "";
	String value = null;
	public Text text = null;
	int textStyle = SWT.RIGHT;
	
	public LabelText(TDenlive aTDe, Composite aParent,String aLabel,String aValue) {
		this(aTDe,aParent,aLabel,aValue,SWT.RIGHT);
	}
	public LabelText(TDenlive aTDe, Composite aParent,String aLabel,String aValue,int aTextStyle) {
		super(aTDe, aParent);
		label = aLabel;
		value = aValue;
		textStyle = aTextStyle;
		createContents();
	}

	public void setWarning(boolean aW) {
		if(aW) {
			text.setBackground(tde.gui.colorsSwt.RED);
		}
		else {
			text.setBackground(tde.gui.colorsSwt.WHITE);
		}
	}
	
	public void setValue(String aValue) {
		value = aValue;//Set before to avoid a change event
		text.setText(aValue);
	}
	
	@Override
	void createContents() {
		GridLayout aGL = new GridLayout(3,false);
		tde.gui.applyNoMargin(aGL);
		container.setLayout(aGL);
		tde.gui.applyColorFont(container);

		Composite aSpace = new Composite(container, SWT.SEPARATOR);
		tde.gui.applyColorFont(aSpace);
		GridData aSGD = new GridData(GridData.HORIZONTAL_ALIGN_CENTER | GridData.VERTICAL_ALIGN_CENTER); 
		aSGD.widthHint = 3;
		aSGD.heightHint = 12;//First element set the real height of the Widget
		aSpace.setLayoutData(aSGD);

		Label aL = new Label(container, SWT.NONE);
		tde.gui.applyColorFont(aL);
		GridData aLGD = new GridData(GridData.HORIZONTAL_ALIGN_BEGINNING|GridData.VERTICAL_ALIGN_CENTER);//GridData.FILL_HORIZONTAL);
		aLGD.heightHint = 12;
		aL.setLayoutData(aLGD);
		aL.setText(label);
		tde.gui.applyColorFont(aL);

		text = new Text(container, textStyle);
		GridData aCGD = new GridData(GridData.FILL_HORIZONTAL | GridData.VERTICAL_ALIGN_CENTER);//GridData.HORIZONTAL_ALIGN_END|GridData.VERTICAL_ALIGN_CENTER);
		aCGD.heightHint = 12;
		text.setLayoutData(aCGD);
		tde.gui.applyColorFont(text);
		text.setText(value);
		text.addModifyListener(new ModifyListener() {
			@Override
			public void modifyText(ModifyEvent arg0) {
				if(text.getText().equals(value)) {
					//No real change
					return;
				}
				change(value = text.getText());
			}
		});
	}

	public abstract void change(String aValue);

}
