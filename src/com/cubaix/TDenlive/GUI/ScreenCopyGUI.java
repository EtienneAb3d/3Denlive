package com.cubaix.TDenlive.GUI;

import java.awt.image.BufferedImage;

import org.eclipse.swt.SWT;
import org.eclipse.swt.dnd.Clipboard;
import org.eclipse.swt.dnd.ImageTransfer;
import org.eclipse.swt.dnd.Transfer;
import org.eclipse.swt.graphics.GC;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.graphics.ImageData;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.graphics.Rectangle;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Menu;
import org.eclipse.swt.widgets.MenuItem;
import org.eclipse.swt.widgets.MessageBox;

import com.cubaix.TDenlive.TDConfig;
import com.cubaix.TDenlive.TDenlive;
import com.cubaix.TDenlive.medias.Media;

/**
 * "Copy" button of the main toolbar (left of the news): a menu to make illustrations, copied to the clipboard as an
 * image: the image of the monitor in its view mode (anaglyph, side by side...) at the resolution of its processing
 * mode (576 lines by default), the window of the application, or the whole screen holding it. The menu is closed and
 * the window painted again before the capture (it is not on the result); a camera shutter sound is played meanwhile.
 * macOS: the window and the screen by screencapture (Screen Recording permission for the other applications shown),
 * elsewhere by a GC on the display.
 */
public class ScreenCopyGUI {
	//Time left to the menu to disappear and to the window to be painted again before the capture
	static final int CAPTURE_DELAY_MS = 250;

	/** The menu below aButton */
	public static void show(TDenlive aTDe, Control aButton) {
		TDeGUI aGUI = aTDe.gui;
		Menu aMenu = new Menu(aButton.getShell(), SWT.POP_UP);
		int aMode = aGUI.monitorGUI != null ? aGUI.monitorGUI.processingMode : TDConfig.PROCESSING_MODE_WORK;
		item(aMenu, aGUI.lngs.get("Copy.Monitor").replace("%RES%", ""+aTDe.config.processingResValues[aMode])
				, aGUI.imgsSwt.getIcon("camera.png"), () -> copyMonitor(aTDe));
		item(aMenu, aGUI.lngs.get("Copy.Window"), null, () -> copyWindow(aTDe));
		item(aMenu, aGUI.lngs.get("Copy.Screen"), null, () -> copyScreen(aTDe));
		Rectangle aB = aButton.getBounds();
		Point aP = aButton.getParent().toDisplay(aB.x, aB.y+aB.height);
		aMenu.setLocation(aP);
		aMenu.setVisible(true);
	}

	static void item(Menu aMenu, String aText, Image aIcon, Runnable aAction) {
		MenuItem aI = new MenuItem(aMenu, SWT.PUSH);
		aI.setText(aText);
		if(aIcon != null) {
			aI.setImage(aIcon);
		}
		aI.addListener(SWT.Selection, e -> later(aMenu, aAction));
	}

	/** aAction once the menu is gone and the window painted again */
	static void later(Menu aMenu, Runnable aAction) {
		org.eclipse.swt.widgets.Display aD = aMenu.getDisplay();
		aD.asyncExec(() -> {
			if(!aMenu.isDisposed()) {
				aMenu.setVisible(false);
				aMenu.dispose();
			}
			for(org.eclipse.swt.widgets.Shell aS : aD.getShells()) {
				if(!aS.isDisposed()) {
					aS.update();
				}
			}
			aD.timerExec(CAPTURE_DELAY_MS, aAction);
		});
	}

	static boolean isMac() {
		return System.getProperty("os.name").toLowerCase().contains("mac");
	}

	/** The monitor in its view mode at the resolution of its processing mode, the part shown by its zoom and position */
	static void copyMonitor(TDenlive aTDe) {
		TDeGUI aGUI = aTDe.gui;
		MonitorGUI aMon = aGUI.monitorGUI;
		if(aMon == null || aMon.media == null || aMon.mixer == null) {
			error(aTDe, aGUI.lngs.get("Copy.MonitorEmpty"));
			return;
		}
		PlayerControlGUI.pauseForEdit(aTDe);
		aGUI.shell.setCursor(aGUI.waitCursor);
		Media aMedia = aMon.media;
		int aMode = aMon.processingMode;
		com.cubaix.TDenlive.processors.Mixer aMixer = aMon.mixer.copyMode();
		//The part of the image shown: zoom and position of the monitor
		aMixer.setZoom(aMon.mixer.getZoom());
		aMixer.setX(aMon.mixer.getX());
		aMixer.setY(aMon.mixer.getY());
		shutter();
		//Computed apart under the lock of the monitor (the UI thread must not wait for it: its processors may
		//syncExec), copied by the UI thread
		Thread aTh = new Thread(() -> {
			ImageData aID = null;
			String aErr = null;
			try {
				BufferedImage aL, aR;
				//Not during an image of the AI hole filling precomputation, nor of the monitor
				com.cubaix.TDenlive.processors.Inpainting.Precomputer.suspend();
				try {
					synchronized(aMon) {
						long aT = aMedia.getRequestedTimePosMS();
						aMedia.reBuild(aMode, aT);
						aL = aMedia.getFinalLeft(aMode, aT);
						aR = aMedia.getFinalRight(aMode, aT);
					}
				}
				finally {
					com.cubaix.TDenlive.processors.Inpainting.Precomputer.resume();
				}
				if(aL == null || aR == null) {
					throw new Exception(aGUI.lngs.get("Copy.MonitorEmpty"));
				}
				//At the size of the views: one view (anaglyph...) or both side by side
				Image aI = aMixer.mix2Swt(aL, aR, aMode, new Rectangle(0, 0, 2*aL.getWidth(), aL.getHeight()));
				try {
					aID = aI.getImageData();
				}
				finally {
					aI.dispose();
				}
			}
			catch(Throwable t) {
				t.printStackTrace(System.err);
				aErr = t.getMessage() == null ? t.toString() : t.getMessage();
			}
			final ImageData aFID = aID;
			final String aFErr = aErr;
			aGUI.display.asyncExec(() -> {
				if(aGUI.shell.isDisposed()) {
					return;
				}
				aGUI.shell.setCursor(null);
				if(aFID != null) {
					toClipboard(aTDe, aFID);
				}
				else {
					error(aTDe, aFErr);
				}
			});
		}, "3De-CopyMonitor");
		aTh.setDaemon(true);
		aTh.start();
	}

	/** The main window of the application */
	static void copyWindow(TDenlive aTDe) {
		copyArea(aTDe, aTDe.gui.shell.getBounds());
	}

	/** The whole screen holding the main window */
	static void copyScreen(TDenlive aTDe) {
		copyArea(aTDe, aTDe.gui.shell.getMonitor().getBounds());
	}

	/** The area aR of the screen (display coordinates) */
	static void copyArea(TDenlive aTDe, Rectangle aR) {
		shutter();
		try {
			if(isMac()) {
				//-x: no sound of its own, -c: to the clipboard, -R: the area (points)
				Process aP = new ProcessBuilder("/usr/sbin/screencapture", "-x", "-c", "-R"
						, aR.x+","+aR.y+","+aR.width+","+aR.height).redirectErrorStream(true).start();
				String aOut = new String(aP.getInputStream().readAllBytes());
				int aExit = aP.waitFor();
				if(aExit != 0) {
					throw new Exception("screencapture "+aExit+" "+aOut.trim());
				}
				return;
			}
			org.eclipse.swt.widgets.Display aD = aTDe.gui.display;
			Image aI = new Image(aD, aR.width, aR.height);
			GC aGC = new GC(aD);
			try {
				aGC.copyArea(aI, aR.x, aR.y);
			}
			finally {
				aGC.dispose();
			}
			try {
				toClipboard(aTDe, aI.getImageData());
			}
			finally {
				aI.dispose();
			}
		}
		catch(Throwable t) {
			t.printStackTrace(System.err);
			error(aTDe, t.getMessage() == null ? t.toString() : t.getMessage());
		}
	}

	static void toClipboard(TDenlive aTDe, ImageData aID) {
		Clipboard aCB = new Clipboard(aTDe.gui.display);
		try {
			aCB.setContents(new Object[] {aID}, new Transfer[] {ImageTransfer.getInstance()});
		}
		finally {
			aCB.dispose();
		}
	}

	static void error(TDenlive aTDe, String aMsg) {
		MessageBox aMB = new MessageBox(aTDe.gui.shell, SWT.ICON_ERROR|SWT.OK);
		aMB.setText("3Denlive");
		aMB.setMessage(aTDe.gui.lngs.get("Copy.Failed")+"\n"+aMsg);
		aMB.open();
	}

	//Shutter sound, made once (44.1 kHz mono 16 bits)
	static byte[] SHUTTER = null;
	static final float RATE = 44100f;

	/**
	 * Camera shutter sound (synthesized: the opening then the closing of the shutter, two short clicks of filtered
	 * noise over a low thump), played in the background
	 */
	public static void shutter() {
		Thread aTh = new Thread(() -> {
			try {
				byte[] aPcm = shutterPcm();
				javax.sound.sampled.AudioFormat aF = new javax.sound.sampled.AudioFormat(RATE, 16, 1, true, false);
				javax.sound.sampled.SourceDataLine aLine = javax.sound.sampled.AudioSystem.getSourceDataLine(aF);
				aLine.open(aF, aPcm.length);
				aLine.start();
				aLine.write(aPcm, 0, aPcm.length);
				aLine.drain();
				aLine.close();
			}
			catch(Throwable t) {
				//No sound device: no sound
				System.err.println("Shutter sound: "+t);
			}
		}, "3De-Shutter");
		aTh.setDaemon(true);
		aTh.start();
	}

	static synchronized byte[] shutterPcm() {
		if(SHUTTER == null) {
			int aN = (int)(RATE*0.22);
			double[] aS = new double[aN];
			java.util.Random aRnd = new java.util.Random(3);
			//Two clicks: at 0 and 75 ms
			double[] aStarts = {0.0, 0.075};
			double[] aGains = {1.0, 0.8};
			double aLp = 0, aHpIn = 0, aHpOut = 0;
			for(int i = 0;i < aN;i++) {
				double t = i/RATE;
				double v = 0;
				for(int c = 0;c < aStarts.length;c++) {
					double d = t-aStarts[c];
					if(d >= 0) {
						//Sharp attack, fast decay of the noise; a low thump of the mechanism
						double aEnv = Math.exp(-d/0.012)*(1-Math.exp(-d/0.0004));
						v += aGains[c]*(aEnv*(aRnd.nextDouble()*2-1)
								+0.6*Math.exp(-d/0.02)*Math.sin(2*Math.PI*140*d));
					}
				}
				//Band: a light low pass then a high pass (a dry click, not a hiss)
				aLp += 0.45*(v-aLp);
				aHpOut = 0.97*(aHpOut+aLp-aHpIn);
				aHpIn = aLp;
				aS[i] = aHpOut;
			}
			double aMax = 1e-9;
			for(double v : aS) {
				aMax = Math.max(aMax, Math.abs(v));
			}
			byte[] aB = new byte[2*aN];
			for(int i = 0;i < aN;i++) {
				int v = (int)Math.round(aS[i]/aMax*0.8*32767);
				aB[2*i] = (byte)(v & 0xFF);
				aB[2*i+1] = (byte)((v >> 8) & 0xFF);
			}
			SHUTTER = aB;
		}
		return SHUTTER;
	}
}
