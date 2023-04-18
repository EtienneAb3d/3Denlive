package com.cubaix.TDenlive.GUI.processors;

import org.eclipse.swt.widgets.Composite;

import com.cubaix.TDenlive.TDenlive;
import com.cubaix.TDenlive.GUI.CompositePanel;
import com.cubaix.TDenlive.processors.Processor;

public class ProcessorGUI extends CompositePanel {
	Processor proc = null;
	
	public ProcessorGUI(TDenlive aTDe,Composite aParent,Processor aP) {
		super(aTDe, aParent);
		proc = aP;
	}

}
