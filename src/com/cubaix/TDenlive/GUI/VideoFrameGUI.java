package com.cubaix.TDenlive.GUI;

import java.awt.image.BufferedImage;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;

import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.graphics.Rectangle;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Slider;

import com.cubaix.TDenlive.TDenlive;
import com.cubaix.TDenlive.ffmpeg.Ffmpeg;
import com.cubaix.TDenlive.medias.StereoMovie;
import com.cubaix.TDenlive.utils.ImageUtils;
import com.cubaix.TDenlive.utils.StringUtils;

/**
 * Chooses one frame of a video (reference image of the AI video panel): preview decoded by ffmpeg at the time of
 * a slider, frame steps, OK gives the frame at full size. A video of the library gives its left view (crop of a
 * side-by-side file, rotation and anamorphosis of the media); a file of the disk is taken as a whole.
 */
public class VideoFrameGUI {
	static final int PREVIEW_W = 640;
	static final int PREVIEW_H = 360;

	TDenlive tde;
	String path;
	StereoMovie movie;
	String crop = null;
	long durationMS = 0;
	double fps = 25;
	//Time asked to the decoding thread (latest wins), time of the preview shown
	volatile long wantedMS = 0;
	volatile boolean closed = false;
	//Time of the frame chosen (after choose())
	public long chosenMS = 0;

	/**
	 * aMovie: the library media of aPath, or null for a file of the disk
	 */
	public VideoFrameGUI(TDenlive aTDe,String aPath,StereoMovie aMovie) {
		tde = aTDe;
		path = aPath;
		movie = aMovie;
		if(movie != null) {
			//Left view only (as StereoMovie.getThumbnailer())
			crop = movie.origStereo == 1 ? "crop=iw/2:ih:iw/2:0" : (movie.origStereo == 2 ? "crop=iw/2:ih:0:0" : null);
		}
	}

	String lng(String aKey) {
		return tde.gui.lngs.get(aKey);
	}

	/**
	 * Duration and frame rate of the video, from the header printed by ffmpeg
	 */
	void probe() {
		try {
			ProcessBuilder aPB = new ProcessBuilder(Ffmpeg.ffmpegPath(), "-hide_banner", "-i", path);
			aPB.redirectErrorStream(true);
			Process aP = aPB.start();
			BufferedReader aR = new BufferedReader(new InputStreamReader(aP.getInputStream()));
			String aL;
			while((aL = aR.readLine()) != null) {
				aL = aL.trim();
				if(aL.matches("Duration: [0-9][0-9]:[0-9][0-9]:[0-9][0-9][.][0-9][0-9],.*")) {
					String aD = aL.substring("Duration: ".length());
					durationMS = Math.max(durationMS, StringUtils.ffmpeg2Time(aD.substring(0, aD.indexOf(","))));
				}
				if(aL.matches("Stream #.*: Video: .*, [0-9.]+ fps,.*")) {
					String aF = aL.substring(0, aL.indexOf(" fps,"));
					aF = aF.substring(aF.lastIndexOf(",")+1).trim();
					fps = Double.parseDouble(aF);
				}
			}
			aP.waitFor();
		}
		catch(Throwable t) {
			t.printStackTrace(System.err);
		}
		if(movie != null && durationMS <= 0) {
			durationMS = movie.getDurationMS();
		}
		if(fps <= 0 || fps > 240) {
			fps = 25;
		}
	}

	/**
	 * Frame of the video at a time (left view of a library media), at most aMaxW wide (0: full size), null on error
	 */
	BufferedImage frame(long aTimeMS,int aMaxW) {
		Process aP = null;
		try {
			String aVf = crop == null ? "" : crop;
			if(aMaxW > 0) {
				aVf += (aVf.isEmpty() ? "" : ",")+"scale='min("+aMaxW+",iw)':-2";
			}
			java.util.Vector<String> aCmd = new java.util.Vector<String>();
			aCmd.add(Ffmpeg.ffmpegPath());
			aCmd.add("-v");
			aCmd.add("error");
			aCmd.add("-ss");
			aCmd.add(String.format(java.util.Locale.ROOT, "%.3f", aTimeMS/1000.0));
			aCmd.add("-i");
			aCmd.add(path);
			aCmd.add("-frames:v");
			aCmd.add("1");
			if(!aVf.isEmpty()) {
				aCmd.add("-vf");
				aCmd.add(aVf);
			}
			aCmd.add("-f");
			aCmd.add("image2pipe");
			aCmd.add("-vcodec");
			aCmd.add("bmp");
			aCmd.add("-");
			ProcessBuilder aPB = new ProcessBuilder(aCmd);
			aPB.redirectError(ProcessBuilder.Redirect.DISCARD);
			aP = aPB.start();
			InputStream aIS = new java.io.BufferedInputStream(aP.getInputStream(), 1 << 20);
			BufferedImage aBI = Ffmpeg.readBmp(aIS);
			aP.waitFor();
			if(aBI != null && movie != null) {
				aBI = movie.orient(aBI);
			}
			return aBI;
		}
		catch(Throwable t) {
			t.printStackTrace(System.err);
			if(aP != null) {
				aP.destroyForcibly();
			}
			return null;
		}
	}

	public static String clock(long aMS) {
		long aS = aMS/1000;
		return String.format("%d:%02d.%02d", aS/60, aS%60, (aMS%1000)/10);
	}

	/**
	 * Opens the window; returns the chosen frame at full size, or null if canceled
	 */
	public BufferedImage choose(Shell aParent) {
		probe();
		Shell aShell = new Shell(aParent, SWT.DIALOG_TRIM | SWT.APPLICATION_MODAL | SWT.RESIZE);
		aShell.setText(lng("AI.FrameTitle"));
		tde.gui.applyColorFont(aShell);
		aShell.setLayout(new GridLayout(1, false));
		Label aInfo = new Label(aShell, SWT.WRAP);
		tde.gui.applyColorFont(aInfo);
		aInfo.setText(lng("AI.FrameInfo"));
		GridData aIGD = new GridData(GridData.FILL_HORIZONTAL);
		aIGD.widthHint = PREVIEW_W;
		aInfo.setLayoutData(aIGD);
		Label aPreview = new Label(aShell, SWT.BORDER | SWT.CENTER);
		tde.gui.applyColorFont(aPreview);
		GridData aPGD = new GridData(SWT.CENTER, SWT.CENTER, true, true);
		aPGD.widthHint = PREVIEW_W;
		aPGD.heightHint = PREVIEW_H;
		aPreview.setLayoutData(aPGD);
		aPreview.setBackground(tde.gui.display.getSystemColor(SWT.COLOR_BLACK));

		Composite aRow = new Composite(aShell, SWT.NONE);
		tde.gui.applyColorFont(aRow);
		aRow.setLayoutData(new GridData(GridData.FILL_HORIZONTAL));
		GridLayout aRGL = new GridLayout(4, false);
		tde.gui.applyNoMargin(aRGL);
		aRow.setLayout(aRGL);
		Button aPrev = new Button(aRow, SWT.PUSH);
		tde.gui.applyColorFont(aPrev);
		aPrev.setText("<");
		aPrev.setToolTipText(lng("AI.FramePrev"));
		Slider aSlider = new Slider(aRow, SWT.HORIZONTAL);
		aSlider.setLayoutData(new GridData(GridData.FILL_HORIZONTAL));
		long aFrameMS = Math.max(1, Math.round(1000/fps));
		int aMax = (int)Math.max(1, Math.max(0, durationMS - aFrameMS));
		aSlider.setValues(0, 0, aMax+1, 1, (int)aFrameMS, (int)Math.max(aFrameMS, aMax/20));
		Button aNext = new Button(aRow, SWT.PUSH);
		tde.gui.applyColorFont(aNext);
		aNext.setText(">");
		aNext.setToolTipText(lng("AI.FrameNext"));
		Label aTime = new Label(aRow, SWT.NONE);
		tde.gui.applyColorFont(aTime);
		aTime.setText(clock(0)+" / "+clock(durationMS));
		aTime.setLayoutData(new GridData(SWT.END, SWT.CENTER, false, false));

		Composite aButtons = new Composite(aShell, SWT.NONE);
		tde.gui.applyColorFont(aButtons);
		aButtons.setLayoutData(new GridData(SWT.END, SWT.CENTER, true, false));
		aButtons.setLayout(new GridLayout(2, true));
		Button aCancel = new Button(aButtons, SWT.PUSH);
		tde.gui.applyColorFont(aCancel);
		aCancel.setText(lng("AI.Cancel"));
		aCancel.setLayoutData(new GridData(GridData.FILL_HORIZONTAL));
		Button aOk = new Button(aButtons, SWT.PUSH);
		tde.gui.applyColorFont(aOk);
		aOk.setText(lng("AI.FrameUse"));
		aOk.setLayoutData(new GridData(GridData.FILL_HORIZONTAL));

		final Image[] aImg = {null};
		aShell.addListener(SWT.Dispose, e -> {
			closed = true;
			synchronized(this) {
				notifyAll();
			}
			if(aImg[0] != null) {
				aImg[0].dispose();
			}
		});
		//Decoding thread: decodes the last time asked, shows it
		Thread aDecoder = new Thread(() -> {
			long aShown = -1;
			while(!closed) {
				long aT;
				synchronized(this) {
					while(!closed && wantedMS == aShown) {
						try {
							wait(500);
						}
						catch(InterruptedException e) {
							return;
						}
					}
					aT = wantedMS;
				}
				if(closed) {
					return;
				}
				BufferedImage aBI = frame(aT, PREVIEW_W);
				aShown = aT;
				if(aBI == null || closed) {
					continue;
				}
				double aS = Math.min(PREVIEW_W/(double)aBI.getWidth(), PREVIEW_H/(double)aBI.getHeight());
				int aW = Math.max(1, (int)(aBI.getWidth()*aS));
				int aH = Math.max(1, (int)(aBI.getHeight()*aS));
				BufferedImage aSmall = new BufferedImage(aW, aH, BufferedImage.TYPE_INT_RGB);
				java.awt.Graphics2D aG = aSmall.createGraphics();
				aG.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION, java.awt.RenderingHints.VALUE_INTERPOLATION_BILINEAR);
				aG.drawImage(aBI, 0, 0, aW, aH, null);
				aG.dispose();
				tde.gui.display.asyncExec(() -> {
					if(aPreview.isDisposed()) {
						return;
					}
					Image aOld = aImg[0];
					aImg[0] = ImageUtils.imageToSwt(tde.gui.display, aSmall);
					aPreview.setImage(aImg[0]);
					if(aOld != null) {
						aOld.dispose();
					}
				});
			}
		}, "VideoFrameGUI");
		aDecoder.setDaemon(true);

		Runnable aAsk = () -> {
			long aT = aSlider.getSelection();
			aTime.setText(clock(aT)+" / "+clock(durationMS));
			aTime.getParent().layout(true);
			synchronized(this) {
				wantedMS = aT;
				notifyAll();
			}
		};
		aSlider.addListener(SWT.Selection, e -> aAsk.run());
		aPrev.addListener(SWT.Selection, e -> {
			aSlider.setSelection(Math.max(0, aSlider.getSelection()-(int)aFrameMS));
			aAsk.run();
		});
		aNext.addListener(SWT.Selection, e -> {
			aSlider.setSelection(Math.min(aMax, aSlider.getSelection()+(int)aFrameMS));
			aAsk.run();
		});
		final BufferedImage[] aResult = {null};
		aOk.addListener(SWT.Selection, e -> {
			aShell.setCursor(tde.gui.display.getSystemCursor(SWT.CURSOR_WAIT));
			chosenMS = aSlider.getSelection();
			aResult[0] = frame(chosenMS, 0);
			aShell.dispose();
		});
		aCancel.addListener(SWT.Selection, e -> aShell.dispose());
		aShell.setDefaultButton(aOk);
		aShell.pack();
		Rectangle aPB = aParent.getBounds();
		Rectangle aSB = aShell.getBounds();
		aShell.setLocation(aPB.x + (aPB.width - aSB.width) / 2, aPB.y + (aPB.height - aSB.height) / 2);
		aShell.open();
		wantedMS = 0;
		aDecoder.start();
		synchronized(this) {
			//First preview (the thread waits for a time different from the one shown, -1 at the start)
			notifyAll();
		}
		while(!aShell.isDisposed()) {
			if(!tde.gui.display.readAndDispatch()) {
				tde.gui.display.sleep();
			}
		}
		return aResult[0];
	}
}
