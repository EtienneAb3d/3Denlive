package com.cubaix.TDenlive.GUI;

import java.awt.image.BufferedImage;
import java.io.File;
import java.io.InputStream;
import java.util.Vector;

import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.Clip;
import javax.sound.sampled.LineEvent;

import org.eclipse.swt.SWT;
import org.eclipse.swt.events.SelectionAdapter;
import org.eclipse.swt.events.SelectionEvent;
import org.eclipse.swt.graphics.GC;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.graphics.Rectangle;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Canvas;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.MessageBox;
import org.eclipse.swt.widgets.ProgressBar;
import org.eclipse.swt.widgets.Shell;

import com.cubaix.TDenlive.TDConfig;
import com.cubaix.TDenlive.TDenlive;
import com.cubaix.TDenlive.ai.WanGenerator;
import com.cubaix.TDenlive.ffmpeg.Ffmpeg;
import com.cubaix.TDenlive.utils.ImageUtils;

/**
 * End of an AI generation (AiClipGUI): the result can be played in the window, never automatically (the user may not
 * be in front of the screen): the video in a loop, the sound with Play / Pause; then added to the media list, generated again (back to the form, with the same prompt and name)
 * or discarded (its files deleted). Closing the window asks before discarding.
 */
public class AiResultGUI {
	//Width of the video played in the window (frames decoded by ffmpeg at this width)
	static final int VIDEO_W = 560;

	AiClipGUI ai;
	TDenlive tde;
	File out;
	File promptFile;
	Shell shell = null;
	//Video: decoded frames, played in a loop
	Canvas videoC = null;
	Vector<Image> frames = new Vector<Image>();
	int frame = 0;
	//Nothing plays before a click on Play (the user may not be in front of the screen at the end)
	boolean playing = false;
	volatile boolean closed = false;
	Process ffmpeg = null;
	//Sound
	Clip clip = null;
	ProgressBar position = null;
	Button playB = null;
	Label statusL = null;
	//The window is closed by a button (no discard question)
	boolean decided = false;

	AiResultGUI(AiClipGUI aAI,File aOut,File aPromptFile) {
		ai = aAI;
		tde = aAI.tde;
		out = aOut;
		promptFile = aPromptFile;
	}

	String lng(String aKey) {
		return tde.gui.lngs.get(aKey);
	}

	void open() {
		shell = new Shell(tde.gui.shell, SWT.DIALOG_TRIM | SWT.RESIZE);
		shell.setText(lng("AI.ResultTitle")+" - "+out.getName());
		shell.setLayout(new GridLayout(1, false));
		tde.gui.applyColorFont(shell);
		shell.setImage(tde.gui.imgsSwt.getIcon("3Denlive32.png"));
		ai.labelWidth = 560;
		ai.addLabel(shell, lng("AI.ResultIntro"), 0);
		String aP = ai.prompt;
		ai.addLabel(shell, aP.length() > 200 ? aP.substring(0, 200)+"..." : aP, 0).setFont(tde.gui.fontsSwt.robotoBold);

		Composite aPlay = new Composite(shell, SWT.NONE);
		tde.gui.applyColorFont(aPlay);
		aPlay.setLayoutData(new GridData(GridData.FILL_BOTH));
		GridLayout aPGL = new GridLayout(2, false);
		tde.gui.applyNoMargin(aPGL);
		aPlay.setLayout(aPGL);
		if(!ai.music) {
			videoC = new Canvas(aPlay, SWT.BORDER | SWT.DOUBLE_BUFFERED);
			GridData aCGD = new GridData(GridData.FILL_BOTH);
			aCGD.horizontalSpan = 2;
			aCGD.widthHint = VIDEO_W;
			aCGD.heightHint = ai.width >= ai.height ? VIDEO_W*ai.height/ai.width : 420;
			videoC.setLayoutData(aCGD);
			videoC.addListener(SWT.Paint, e -> drawVideo(e.gc));
			videoC.addListener(SWT.Resize, e -> videoC.redraw());
			videoC.addListener(SWT.MouseDown, e -> togglePlay());
		}
		playB = new Button(aPlay, SWT.PUSH);
		tde.gui.applyColorFont(playB);
		playB.addSelectionListener(new SelectionAdapter() {
			@Override
			public void widgetSelected(SelectionEvent e) {
				togglePlay();
			}
		});
		position = new ProgressBar(aPlay, SWT.SMOOTH | SWT.HORIZONTAL);
		position.setMinimum(0);
		position.setMaximum(1000);
		position.setLayoutData(new GridData(GridData.FILL_HORIZONTAL));
		statusL = ai.addLabel(shell, " ", 0);

		Composite aButtons = new Composite(shell, SWT.NONE);
		GridData aBGD = new GridData(GridData.HORIZONTAL_ALIGN_END);
		aBGD.verticalIndent = 8;
		aButtons.setLayoutData(aBGD);
		tde.gui.applyColorFont(aButtons);
		GridLayout aBGL = new GridLayout(3, true);
		tde.gui.applyNoMargin(aBGL);
		aButtons.setLayout(aBGL);
		Button aDiscard = button(aButtons, lng("AI.ResultDiscard"), "trash.png");
		aDiscard.addSelectionListener(new SelectionAdapter() {
			@Override
			public void widgetSelected(SelectionEvent e) {
				discard();
				close();
				AiClipGUI.running = false;
			}
		});
		Button aAgain = button(aButtons, lng("AI.ResultAgain"), null);
		aAgain.addSelectionListener(new SelectionAdapter() {
			@Override
			public void widgetSelected(SelectionEvent e) {
				discard();
				close();
				ai.again();
			}
		});
		Button aAdd = button(aButtons, lng("AI.ResultAdd"), "checked.gif");
		aAdd.addSelectionListener(new SelectionAdapter() {
			@Override
			public void widgetSelected(SelectionEvent e) {
				add();
			}
		});
		shell.addListener(SWT.Close, e -> {
			if(decided) {
				return;
			}
			MessageBox aMB = new MessageBox(shell, SWT.ICON_QUESTION | SWT.YES | SWT.NO);
			aMB.setText(shell.getText());
			aMB.setMessage(lng("AI.ResultDiscardAsk"));
			if(aMB.open() != SWT.YES) {
				e.doit = false;
				return;
			}
			stop();
			discard();
			AiClipGUI.running = false;
		});
		shell.addListener(SWT.Dispose, e -> stop());
		ai.center(shell);
		shell.setDefaultButton(aAdd);
		shell.open();
		if(ai.music) {
			startSound();
		}
		else {
			startVideo();
		}
		updatePlay();
	}

	Button button(Composite aParent,String aText,String aIcon) {
		Button aB = new Button(aParent, SWT.PUSH);
		aB.setText(aText);
		if(aIcon != null) {
			Image aI = tde.gui.imgsSwt.getIcon(aIcon);
			if(aI != null) {
				aB.setImage(aI);
			}
		}
		tde.gui.applyColorFont(aB);
		aB.setLayoutData(new GridData(GridData.FILL_HORIZONTAL));
		return aB;
	}

	void close() {
		decided = true;
		stop();
		if(!shell.isDisposed()) {
			shell.dispose();
		}
	}

	/**
	 * Stop the playing and release the frames / the sound line
	 */
	void stop() {
		closed = true;
		if(ffmpeg != null) {
			ffmpeg.destroyForcibly();
		}
		if(clip != null) {
			try {
				clip.stop();
				clip.close();
			}
			catch(Throwable t) {
				t.printStackTrace(System.err);
			}
			clip = null;
		}
		for(Image aI : frames) {
			aI.dispose();
		}
		frames.clear();
	}

	/**
	 * The video and its prompt file deleted
	 */
	void discard() {
		stop();
		out.delete();
		promptFile.delete();
	}

	/**
	 * Load into the media list (in a thread, as the files are read), then close
	 */
	void add() {
		decided = true;
		stop();
		shell.setEnabled(false);
		statusL.setText(lng(ai.music ? "AI.LoadingSound" : "AI.Loading"));
		final String aPath = out.getPath();
		final boolean aMusic = ai.music;
		new Thread(() -> {
			String aErr = null;
			try {
				if(aMusic) {
					tde.mediaList.loadAudio(aPath);
				}
				else {
					tde.mediaList.loadImageLR(aPath, aPath, TDConfig.PROCESSING_MODE_RENDER, 0);
				}
			}
			catch(Throwable t) {
				t.printStackTrace(System.err);
				aErr = lng(ai.errorKey())+"\n"+(t.getMessage() == null ? t.toString() : t.getMessage());
			}
			final String aE = aErr;
			tde.gui.display.asyncExec(() -> {
				AiClipGUI.running = false;
				if(!shell.isDisposed()) {
					shell.dispose();
				}
				if(aE != null) {
					AiClipGUI.message(tde, SWT.ICON_ERROR, aE);
				}
				tde.gui.mediaListGUI.redraw();
			});
		}).start();
	}

	void togglePlay() {
		if(ai.music) {
			if(clip == null) {
				return;
			}
			if(clip.isRunning()) {
				clip.stop();
			}
			else {
				if(clip.getFramePosition() >= clip.getFrameLength()-1) {
					clip.setFramePosition(0);
				}
				clip.start();
			}
		}
		else {
			playing = !playing;
		}
		updatePlay();
	}

	void updatePlay() {
		if(playB == null || playB.isDisposed()) {
			return;
		}
		boolean aOn = ai.music ? clip != null && clip.isRunning() : playing;
		playB.setText(lng(aOn ? "AI.ResultPause" : "AI.ResultPlay"));
		Image aI = tde.gui.imgsSwt.getIcon(aOn ? "pause.png" : "play.gif");
		if(aI != null) {
			playB.setImage(aI);
		}
		playB.getParent().layout(true, true);
	}

	//Sound: a javax.sound clip of the WAV file, the position shown by the bar (a click there moves it)
	void startSound() {
		try {
			AudioInputStream aAIS = AudioSystem.getAudioInputStream(out);
			clip = AudioSystem.getClip();
			clip.open(aAIS);
			clip.addLineListener(e -> {
				if(e.getType() == LineEvent.Type.STOP) {
					tde.gui.display.asyncExec(this::updatePlay);
				}
			});
		}
		catch(Throwable t) {
			t.printStackTrace(System.err);
			clip = null;
			statusL.setText(lng("AI.ResultNoPlay"));
			playB.setEnabled(false);
		}
		position.addListener(SWT.MouseDown, e -> {
			if(clip != null && position.getSize().x > 0) {
				clip.setFramePosition((int)((long)clip.getFrameLength()*e.x/position.getSize().x));
			}
		});
		tickSound();
	}

	void tickSound() {
		if(closed || shell.isDisposed()) {
			return;
		}
		if(clip != null && clip.getFrameLength() > 0) {
			position.setSelection((int)(1000L*clip.getFramePosition()/clip.getFrameLength()));
		}
		tde.gui.display.timerExec(100, this::tickSound);
	}

	//Video: frames decoded by ffmpeg (BMP pipe) at VIDEO_W, played in a loop at the video fps
	void startVideo() {
		statusL.setText(lng("AI.ResultDecoding"));
		position.addListener(SWT.MouseDown, e -> {
			if(!frames.isEmpty() && position.getSize().x > 0) {
				frame = Math.max(0, Math.min(frames.size()-1, frames.size()*e.x/position.getSize().x));
				videoC.redraw();
			}
		});
		new Thread(() -> {
			try {
				String aFfmpeg = Ffmpeg.ffmpegPath();
				ProcessBuilder aPB = new ProcessBuilder(aFfmpeg, "-v", "error", "-i", out.getAbsolutePath()
						, "-vf", "scale="+VIDEO_W+":-2", "-f", "image2pipe", "-vcodec", "bmp", "-");
				aPB.redirectError(ProcessBuilder.Redirect.DISCARD);
				ffmpeg = aPB.start();
				InputStream aIS = new java.io.BufferedInputStream(ffmpeg.getInputStream(), 1 << 20);
				BufferedImage aBI;
				while(!closed && (aBI = Ffmpeg.readBmp(aIS)) != null) {
					//ImageUtils.imageToSwt() needs integer ARGB pixels
					BufferedImage aARGB = new BufferedImage(aBI.getWidth(), aBI.getHeight(), BufferedImage.TYPE_INT_ARGB);
					java.awt.Graphics2D aG = aARGB.createGraphics();
					aG.drawImage(aBI, 0, 0, null);
					aG.dispose();
					tde.gui.display.syncExec(() -> {
						if(!closed && !shell.isDisposed()) {
							frames.add(ImageUtils.imageToSwt(tde.gui.display, aARGB));
							//The first image shown at once (nothing plays before a click on Play)
							if(frames.size() == 1) {
								videoC.redraw();
							}
						}
					});
				}
				ffmpeg.waitFor();
			}
			catch(Throwable t) {
				if(!closed) {
					t.printStackTrace(System.err);
				}
			}
			tde.gui.display.asyncExec(() -> {
				if(!shell.isDisposed()) {
					statusL.setText(frames.isEmpty() ? lng("AI.ResultNoPlay") : " ");
				}
			});
		}).start();
		tickVideo();
	}

	void tickVideo() {
		if(closed || shell.isDisposed()) {
			return;
		}
		if(playing && !frames.isEmpty()) {
			frame = (frame+1)%frames.size();
			videoC.redraw();
		}
		if(!frames.isEmpty()) {
			position.setSelection(1000*frame/Math.max(1, frames.size()-1));
		}
		tde.gui.display.timerExec(1000/WanGenerator.FPS, this::tickVideo);
	}

	void drawVideo(GC aGC) {
		Rectangle aCA = videoC.getClientArea();
		aGC.setBackground(tde.gui.colorsSwt.DARK_GRAY);
		aGC.fillRectangle(aCA);
		if(frames.isEmpty()) {
			return;
		}
		Image aI = frames.elementAt(Math.min(frame, frames.size()-1));
		Rectangle aIB = aI.getBounds();
		double aScale = Math.min(aCA.width/(double)aIB.width, aCA.height/(double)aIB.height);
		int aW = (int)(aIB.width*aScale);
		int aH = (int)(aIB.height*aScale);
		aGC.setInterpolation(SWT.HIGH);
		aGC.drawImage(aI, 0, 0, aIB.width, aIB.height, (aCA.width-aW)/2, (aCA.height-aH)/2, aW, aH);
	}
}
