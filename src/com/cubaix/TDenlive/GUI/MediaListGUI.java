package com.cubaix.TDenlive.GUI;

import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.GC;
import org.eclipse.swt.graphics.Rectangle;
import org.eclipse.swt.widgets.Composite;

import com.cubaix.TDenlive.TDenlive;
import com.cubaix.TDenlive.medias.Media;

public class MediaListGUI extends CanvasPanel {

	public MediaListGUI(TDenlive aTDe,Composite aParent) {
		super(aTDe, aParent);
		createContents(aParent);
	}

	void createContents(Composite aParent) {
		canvas.addListener(SWT.MouseDown,e -> {
			tde.mediaList.select(e.x, e.y, true);
			tde.gui.setMultiPropsPanel(TDeGUI.MULTIPROPSPANEL_PROPS);
			tde.gui.setMultiMonitorPanel(TDeGUI.MULTIMONITORPANEL_MONITOR);
			if(tde.selected.medias.size() == 1) {
				tde.gui.monitorGUI.setMedia(tde.selected.medias.elementAt(0));
				tde.gui.propsGUI.setMedia(tde.selected.medias.elementAt(0));
			}
			else {
				tde.gui.monitorGUI.clean();
				tde.gui.propsGUI.clean();
			}
			canvas.redraw();
		});
		
		canvas.addListener(SWT.MouseDoubleClick,e -> {
			tde.mediaList.select(e.x, e.y, true);
			if(tde.selected.medias.size() == 1) {
				Media aSelMedia = tde.selected.medias.elementAt(0);
				tde.timeLineStack.addMedia(aSelMedia);
				
				tde.gui.setMultiPropsPanel(TDeGUI.MULTIPROPSPANEL_PROCESSORS);
				tde.gui.setMultiMonitorPanel(TDeGUI.MULTIMONITORPANEL_MONITOR);
				tde.gui.monitorGUI.setMedia(tde.timeLineStack);
				tde.gui.processorGUI.setProcessors(tde.selected.clips.elementAt(0).getProcessors());//Must be in
				tde.gui.monitorGUI.redraw();
				tde.gui.timeLinesGUI.redraw();
			}
			else {
				//??
			}
			canvas.redraw();
		});

	}
	
	@Override
	void draw(GC aGC) {
		Rectangle aClientR = canvas.getClientArea();
		aGC.setBackground(tde.gui.colorsSwt.WHITE);
		aGC.fillRectangle(aClientR);
		Rectangle aR = tde.mediaList.drawSwt(aGC, origin.x, origin.y,aClientR);
		if(aR.width != bounds.width || aR.height != bounds.height) {
			bounds = aR;
			adjustScrollBars();
		}
		else {
			bounds = aR;
		}
	}
}
