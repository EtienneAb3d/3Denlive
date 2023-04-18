package com.cubaix.TDenlive.GUI;

import org.eclipse.swt.SWT;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Layout;

import com.cubaix.TDenlive.TDenlive;
import com.cubaix.TDenlive.medias.TimeListener;

public class CompositePanel extends TimeListener {
	public TDenlive tde = null;
	public Composite container = null;

	public CompositePanel(TDenlive aTDe,Composite aParent) {
		tde = aTDe;
		createContainer(aParent);
	}
	
	void createContainer(Composite aParent){
		container = new Composite(aParent, SWT.V_SCROLL);
		container.getVerticalBar().setVisible(false);
		container.setLayoutData(new GridData(GridData.FILL_BOTH));
		container.setBackground(tde.gui.colorsSwt.WHITE);
		GridLayout aLayout = new GridLayout(1,false);
		aLayout.marginLeft = aLayout.marginTop = aLayout.marginRight = aLayout.marginBottom = 0;
		aLayout.horizontalSpacing = aLayout.verticalSpacing = 0;
		aLayout.marginHeight = aLayout.marginWidth = 0;
		container.setLayout(aLayout);
	}
	
	public void clean() {
		for(Control aC : container.getChildren()) {
			aC.dispose();
		}
	}
	
	public Composite getContainer() {
		return container;
	}
}
