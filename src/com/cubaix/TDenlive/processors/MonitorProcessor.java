package com.cubaix.TDenlive.processors;

import java.util.Vector;

import org.eclipse.swt.graphics.GC;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.graphics.Rectangle;

import com.cubaix.TDenlive.TDenlive;
import com.cubaix.TDenlive.medias.Media;
import com.cubaix.TDenlive.xml.XmlObject;
import com.cubaix.TDenlive.xml.XmlTag;

/**
 * Analysis processor of the "Monitor" folder: it changes neither the image nor the sound, it
 * analyses them as they are at its place in the stack of the processors of the clip, and its
 * panel (GUI/processors/MonitorProcessorGUI) draws the result (paint()). Nothing is computed
 * while no panel shows it (isWatched()), nor in the final rendering. One setting, the mode
 * (modes(): language keys <ClassName>.Mode_<mode>), saved as <viewMode>
 */
public abstract class MonitorProcessor extends Processor {
	/** How long an analysis goes on after the last sign of its panel (MonitorProcessorGUI polls every 50 ms) */
	static final long WATCH_MS = 2000;
	public int mode = 0;
	/** Last time a panel of this processor was shown (set by its polling) */
	public volatile long watchedMS = 0;
	/** Incremented at each new analysis: the panel redraws when it changes */
	public volatile long version = 0;

	public MonitorProcessor(TDenlive aTDe, Media aTargetMedia) {
		super(aTDe, aTargetMedia);
		isExpended = true;
	}

	/** Names of the modes (null: no choice) */
	public String[] modes() {return null;}

	/** Height of the drawing in the panel, for 12 px text lines (TDeGUI.h()) */
	public abstract int drawingHeight();

	/** Draws the last analysis into aR (UI thread) */
	public abstract void paint(GC aGC,Rectangle aR);

	/** Language key (section of the processor) of a reset button, null: none */
	public String resetKey() {return null;}

	public void reset() {}

	@Override
	void setDescr() {
		name = tde.gui.lngs.get("Processor."+getClassName());
	}

	/** True while a panel shows this processor: the analyses are computed */
	public boolean isWatched() {
		return System.currentTimeMillis()-watchedMS < WATCH_MS;
	}

	protected void updated() {
		version++;
	}

	@Override
	public int openProject(Vector<XmlObject> aOs, int o) throws Exception {
		for(;o < aOs.size();o++) {
			XmlObject aO = aOs.elementAt(o);
			if(aO instanceof XmlTag) {
				XmlTag aT = (XmlTag)aO;
				if(("/"+getClassName()).equalsIgnoreCase(aT.tagName)) {
					break;
				}
				if("viewMode".equalsIgnoreCase(aT.tagName)) {
					o++;
					mode = Integer.parseInt(aOs.elementAt(o).text.trim());
				}
			}
		}
		return o;
	}

	@Override
	public void saveProject(StringBuffer aSB) throws Exception {
		aSB.append("						<"+getClassName()+">\n");
		aSB.append("							<viewMode>" + mode + "</viewMode>\n");
		aSB.append("						</"+getClassName()+">\n");
	}

	@Override
	public Image process2Swt(Media aMedia, int aProcessingMode, Rectangle aTargetSize, long aTime) {
		return null;
	}

	@Override
	public void process2Awt(Media aMedia, int aProcessingMode, long aTime) {}
}
