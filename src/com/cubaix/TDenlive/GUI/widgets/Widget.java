package com.cubaix.TDenlive.GUI.widgets;

import org.eclipse.swt.SWT;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.widgets.Composite;

import com.cubaix.TDenlive.TDenlive;
import com.cubaix.TDenlive.medias.TimeListener;

public abstract class Widget extends TimeListener{
	public TDenlive tde = null;
	Composite container = null;
	
	public Widget(TDenlive aTDe, Composite aParent) {
		tde = aTDe;
		
		container = new Composite(aParent, SWT.NONE);
		container.setLayoutData(new GridData(GridData.FILL_HORIZONTAL));
		tde.gui.applyColorFont(container);

		//Bug !? Recreate when resized
//		aParent.addListener(SWT.Resize, new Listener() {
//			@Override
//			public void handleEvent(Event e) {
//				for(Control aC : container.getChildren()) {
//					aC.dispose();
//				}
//				createContents();
//			}
//		});
	}
	
	abstract void createContents();

}
