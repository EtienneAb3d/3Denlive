package com.cubaix.TDenlive.GUI;

import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.GC;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.graphics.Rectangle;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.widgets.Canvas;
import org.eclipse.swt.widgets.Composite;

import com.cubaix.TDenlive.TDenlive;
import com.cubaix.TDenlive.medias.Media;
import com.cubaix.TDenlive.utils.StringUtils;

/**
 * Time ruler above the player buttons of the monitor: the whole duration of the media shown
 * (the montage from 0 to the end of its last clip, or the library media played alone) fits its width,
 * with the time cursor (inverted triangle, as on the ruler of the time lines) that a click or a drag moves
 */
public class MonitorRulerGUI {
	//Room for half the cursor at both ends
	static final int MARGIN = 8;
	//Graduation steps, the first whose labels do not overlap is taken
	static final long[] STEPS_MS = {10, 20, 50, 100, 200, 500, 1000, 2000, 5000, 10000, 15000, 30000
			, 60000, 120000, 300000, 600000, 900000, 1800000, 3600000};

	TDenlive tde = null;
	Canvas canvas = null;
	boolean dragging = false;
	//The playing was suspended by the click: it goes on from the new position at the release
	boolean resumeAfterDrag = false;

	public MonitorRulerGUI(TDenlive aTDe, Composite aParent) {
		tde = aTDe;
		canvas = new Canvas(aParent, SWT.DOUBLE_BUFFERED | SWT.NO_BACKGROUND);
		GridData aGD = new GridData(GridData.FILL_HORIZONTAL);
		aGD.heightHint = tde.gui.h(26);
		canvas.setLayoutData(aGD);
		tde.gui.applyColorFont(canvas);
		tde.gui.tip(canvas, "Ruler");
		canvas.addListener(SWT.Paint, e -> draw(e.gc));
		canvas.addListener(SWT.Resize, e -> canvas.redraw());
		canvas.addListener(SWT.MouseDown, e -> {
			if(e.button != 1) {
				return;
			}
			dragging = true;
			resumeAfterDrag = false;
			if(tde.gui.monitorGUI != null && tde.gui.monitorGUI.getMedia() == null) {
				tde.gui.monitorGUI.setMedia(tde.timeLineStack);
			}
			Media aTarget = getTarget();
			PlayerControlGUI aPC = tde.gui.playerControlGUI;
			if(aPC != null && aPC.playing && aPC.playedMedia == aTarget) {
				//Like a pause (the buttons are kept): the playing thread ends where it is
				aPC.paused = true;
				aPC.playing = false;
				resumeAfterDrag = true;
			}
			moveTo(e.x);
		});
		canvas.addListener(SWT.MouseMove, e -> {
			if(dragging) {
				moveTo(e.x);
			}
		});
		canvas.addListener(SWT.MouseUp, e -> {
			if(!dragging) {
				return;
			}
			dragging = false;
			PlayerControlGUI aPC = tde.gui.playerControlGUI;
			if(resumeAfterDrag && aPC != null && aPC.paused && aPC.playedMedia != null) {
				aPC.startPlay(aPC.playedMedia, aPC.playedIsLib, aPC.playedMedia.getRequestedTimePosMS(), aPC.returnPosMS);
			}
			resumeAfterDrag = false;
		});
	}

	/**
	 * Media whose time the ruler shows: the library media shown in the monitor, otherwise the montage
	 */
	Media getTarget() {
		Media aShown = tde.gui.monitorGUI == null ? null : tde.gui.monitorGUI.getMedia();
		return aShown != null && aShown != tde.timeLineStack && tde.mediaList.mediaList.contains(aShown)
				? aShown : tde.timeLineStack;
	}

	/**
	 * Callable from any thread (e.g. TimeLineStackGUI.redraw() while a project is opened in the background)
	 */
	public void redraw() {
		if(canvas.isDisposed()) {
			return;
		}
		canvas.getDisplay().syncExec(() -> {
			if(!canvas.isDisposed()) {
				canvas.redraw();
			}
		});
	}

	/**
	 * Abscissa of a time, the whole duration aDur fitting the width aW
	 */
	int timeToX(long aTime, long aDur, int aW) {
		if(aDur <= 0) {
			return MARGIN;
		}
		return MARGIN + (int)Math.round(aTime*(aW-2.0*MARGIN)/aDur);
	}

	/**
	 * Time cursor of the target at this abscissa (between 0 and its last frame)
	 */
	void moveTo(int aX) {
		Media aTarget = getTarget();
		long aDur = aTarget.getMaxTimePosMS();
		int aW = canvas.getClientArea().width;
		long aTime = 0;
		if(aDur > 0 && aW > 2*MARGIN) {
			aTime = Math.round((aX-MARGIN)*(double)aDur/(aW-2.0*MARGIN));
			aTime = Math.max(0, Math.min(aTime, aDur-1));
		}
		if(aTime == aTarget.getRequestedTimePosMS()) {
			return;
		}
		aTarget.setRequestedTimePosMS(aTime);
		tde.gui.monitorGUI.redraw();
		if(aTarget == tde.timeLineStack && tde.gui.timeLinesGUI != null) {
			tde.gui.timeLinesGUI.redraw();
		}
		if(tde.gui.playerControlGUI != null) {
			tde.gui.playerControlGUI.showClock(aTime);
		}
		redraw();
	}

	void draw(GC aGC) {
		Rectangle aR = canvas.getClientArea();
		aGC.setBackground(tde.gui.colorsSwt.WHITE);
		aGC.fillRectangle(aR);
		aGC.setFont(tde.gui.fontsSwt.roboto);
		Media aTarget = getTarget();
		long aDur = Math.max(0, aTarget.getMaxTimePosMS());
		int aW = aR.width;
		int aH = aR.height;
		Point aLabelP = aGC.textExtent(StringUtils.time2Clock(0));
		int aTextH = aLabelP.y;
		int aLabelW = aLabelP.x + 20;

		//Total duration at the right end
		String aEndClock = StringUtils.time2Clock(aDur);
		int aEndX = timeToX(aDur, aDur, aW);
		int aEndLabelX = Math.max(MARGIN, aEndX - aGC.textExtent(aEndClock).x);

		//Graduations: labelled steps that do not overlap, 4 sub steps
		aGC.setForeground(tde.gui.colorsSwt.GRAY);
		aGC.drawLine(0, aH-1, aW, aH-1);
		if(aDur > 0 && aW > 2*MARGIN) {
			double aPixPerMS = (aW-2.0*MARGIN)/aDur;
			long aStep = STEPS_MS[STEPS_MS.length-1];
			for(long aS : STEPS_MS) {
				if(aS*aPixPerMS >= aLabelW) {
					aStep = aS;
					break;
				}
			}
			while(aStep*aPixPerMS < aLabelW) {
				aStep *= 2;
			}
			for(long aT = 0;aT <= aDur;aT += aStep) {
				int aX = timeToX(aT, aDur, aW);
				aGC.setForeground(tde.gui.colorsSwt.GRAY);
				aGC.drawLine(aX, aTextH, aX, aH-1);
				for(int s = 1;s < 4;s++) {
					long aST = aT + s*aStep/4;
					if(aST > aDur) {
						break;
					}
					int aSX = timeToX(aST, aDur, aW);
					aGC.drawLine(aSX, (aTextH+aH)/2, aSX, aH-1);
				}
				if(aX + aLabelW <= aEndLabelX) {
					aGC.setForeground(tde.gui.colorsSwt.CUBAIX_BLUE);
					aGC.drawString(StringUtils.time2Clock(aT), aX, 0, true);
				}
			}
		}
		aGC.setForeground(tde.gui.colorsSwt.GRAY);
		aGC.drawLine(aEndX, 0, aEndX, aH-1);
		aGC.setForeground(tde.gui.colorsSwt.CUBAIX_BLUE);
		aGC.drawString(aEndClock, aEndLabelX, 0, true);

		//Cursor, as on the ruler of the time lines
		long aTime = Math.max(0, Math.min(aTarget.getRequestedTimePosMS(), aDur));
		int aCX = timeToX(aTime, aDur, aW);
		aGC.setForeground(tde.gui.colorsSwt.CUBAIX_PINK);
		aGC.drawLine(aCX, 0, aCX, aH-1);
		aGC.drawLine(aCX, aH-1, aCX-5, aH-11);
		aGC.drawLine(aCX, aH-1, aCX+5, aH-11);
		aGC.drawLine(aCX-5, aH-11, aCX+5, aH-11);
	}
}
