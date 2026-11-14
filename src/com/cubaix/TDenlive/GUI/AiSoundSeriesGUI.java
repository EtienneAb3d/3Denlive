package com.cubaix.TDenlive.GUI;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Vector;

import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.Clip;

import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.ScrolledComposite;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.ProgressBar;
import org.eclipse.swt.widgets.Scale;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Spinner;

import com.cubaix.TDenlive.TDenlive;
import com.cubaix.TDenlive.ai.PythonAI;
import com.cubaix.TDenlive.ai.SoundGenerator;

/**
 * Sounds (music or sound effects) generated in series, in one window with their playing: each sound generated gets a row (Play /
 * Pause, position slider, "Keep" = added to the media list), and the next one is generated meanwhile, AHEAD
 * sounds not listened yet at most (a sound counts as listened once played). Closing the window (or "Something
 * else": back to the form) deletes the sounds not kept; a generation in progress ends in the background, so that
 * the model stays loaded.
 */
public class AiSoundSeriesGUI {
	//Sounds generated ahead, not listened yet
	static final int AHEAD = 5;

	AiClipGUI ai;
	TDenlive tde;
	//Sound effects (else music)
	final boolean sfx;
	//Process of a canceled generation still ending (waited for before the first generation)
	PythonAI ending;
	Shell shell;
	Composite rows;
	ScrolledComposite scroll;
	Label nextL;
	Button closeB;

	/**
	 * A sound generated: its files, its player
	 */
	class Item {
		File wav;
		File txt;
		boolean listened = false;
		boolean kept = false;
		Clip clip = null;
		//Started by Play, until Stop or the end (Clip.isRunning() is still false just after start())
		volatile boolean playing = false;
		long playStartMS = 0;
		Button play;
		Scale position;
		Button keep;
		Label number;
		Button trash;
		//Its number and duration
		int n;
		double seconds;
		//Music: duration of a longer version, and its button
		Spinner longer;
		Button extend;
		//A longer version asked and not generated yet: the arrow shows an hourglass
		boolean longerPending = false;
		//Its duration then an arrow (left of the field), the unit (right of it)
		Label durL;
		Label unitL;
	}
	//Generation in progress: a sound ahead (not a longer version), and its stop file (see SoundGenerator.stopFile)
	volatile boolean aheadRunning = false;
	volatile File stopFile = null;
	//Longer versions asked (music): {source item, seconds}, generated before the next sounds ahead
	final Vector<Object[]> extensions = new Vector<Object[]>();
	final Vector<Item> items = new Vector<Item>();
	//Window closed: no more generations
	volatile boolean closed = false;
	//Closed during the installation or the loading of the model: the generation is stopped
	volatile boolean hardCancel = false;
	//Error: no more generations
	volatile String error = null;
	//Number of sounds generated (their numbers)
	int count = 0;
	//Name of the files when no name is given: the date of the series
	final String dateBase;

	AiSoundSeriesGUI(AiClipGUI aAI,PythonAI aEnding) {
		ai = aAI;
		tde = aAI.tde;
		ending = aEnding;
		sfx = SoundGenerator.SFX.equals(ai.soundModel.kind);
		dateBase = (sfx ? "AI_Sfx_" : "AI_Music_")+new SimpleDateFormat("yyyyMMdd_HHmmss").format(new Date());
	}

	String lng(String aKey) {
		return tde.gui.lngs.get(aKey);
	}

	void open() {
		shell = new Shell(tde.gui.shell, SWT.DIALOG_TRIM | SWT.RESIZE);
		shell.setText(lng(sfx ? "AI.SfxTitle" : "AI.MusicTitle"));
		shell.setLayout(new GridLayout(1, false));
		tde.gui.applyColorFont(shell);
		shell.setImage(tde.gui.imgsSwt.getIcon("3Denlive32.png"));
		ai.labelWidth = 560;
		String aP = ai.prompt;
		ai.addLabel(shell, aP.length() > 400 ? aP.substring(0, 400)+"..." : aP, 0).setFont(tde.gui.fontsSwt.robotoBold);
		String aReason = SoundGenerator.cpuReason(ai.soundModel);
		ai.addLabel(shell, (sfx ? "" : "MusicGen ")+ai.soundModel.id+" : "+String.format("%.0f s", ai.seconds)+" - "
				+lng(aReason == null ? "AI.MusicOnGpu" : "AI.MusicOnCpu"), 0);
		ai.addLabel(shell, lng("AI.SfxSeriesIntro").replace("%N%", ""+AHEAD), 0);

		scroll = new ScrolledComposite(shell, SWT.V_SCROLL | SWT.BORDER);
		GridData aSGD = new GridData(GridData.FILL_BOTH);
		aSGD.widthHint = 560;
		aSGD.heightHint = tde.gui.h(32)*6;
		scroll.setLayoutData(aSGD);
		scroll.setExpandHorizontal(true);
		scroll.setExpandVertical(true);
		rows = new Composite(scroll, SWT.NONE);
		tde.gui.applyColorFont(rows);
		rows.setLayout(new GridLayout(sfx ? 5 : 9, false));
		scroll.setContent(rows);
		scroll.addListener(SWT.Resize, e -> updateScroll());

		//Generation in progress: the widgets of AiClipGUI.showStatus()
		nextL = ai.addLabel(shell, " ", 0);
		((GridData)nextL.getLayoutData()).verticalIndent = 6;
		ai.shell = shell;
		ai.statusL = ai.addLabel(shell, lng("AI.Starting"), 0);
		ai.detailL = ai.addLabel(shell, " ", 0);
		ai.bar = new ProgressBar(shell, SWT.SMOOTH | SWT.HORIZONTAL);
		ai.bar.setMinimum(0);
		ai.bar.setMaximum(1000);
		ai.bar.setLayoutData(new GridData(GridData.FILL_HORIZONTAL));
		ai.etaL = ai.addLabel(shell, " ", 0);

		Composite aButtons = new Composite(shell, SWT.NONE);
		GridData aBGD = new GridData(GridData.HORIZONTAL_ALIGN_END);
		aBGD.verticalIndent = 8;
		aButtons.setLayoutData(aBGD);
		tde.gui.applyColorFont(aButtons);
		GridLayout aBGL = new GridLayout(2, true);
		tde.gui.applyNoMargin(aBGL);
		aButtons.setLayout(aBGL);
		//Something else: back to the form, with the same settings
		Button aOther = button(aButtons, lng("AI.ResultOther"), null);
		aOther.addListener(SWT.Selection, e -> {
			close();
			ai.again();
		});
		closeB = button(aButtons, lng("AI.ResultClose"), null);
		closeB.addListener(SWT.Selection, e -> close());
		shell.addListener(SWT.Close, e -> close());
		ai.center(shell);
		shell.open();
		Thread aTh = new Thread(this::series, "AI sound series");
		aTh.setDaemon(true);
		aTh.start();
	}

	Button button(Composite aParent,String aText,String aIcon) {
		Button aB = new Button(aParent, SWT.PUSH);
		if(aText != null) {
			aB.setText(aText);
		}
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

	/**
	 * Sounds generated and not listened yet
	 */
	synchronized int notListened() {
		int n = 0;
		for(Item aI : items) {
			if(!aI.listened) {
				n++;
			}
		}
		return n;
	}

	/**
	 * Generation thread: one sound after the other while fewer than AHEAD are waiting to be listened
	 */
	void series() {
		try {
			//The process of a canceled generation is still ending: its memory is given back first
			for(int w = 0;ending != null && ending.isRunning() && !closed && w < 1200;w++) {
				Thread.sleep(100);
			}
			while(true) {
				synchronized(this) {
					while(!closed && error == null && extensions.isEmpty() && notListened() >= AHEAD) {
						showWaiting();
						wait();
					}
				}
				if(closed || error != null) {
					break;
				}
				Object[] aExt = null;
				synchronized(this) {
					if(!extensions.isEmpty()) {
						aExt = extensions.remove(0);
					}
				}
				if(aExt == null) {
					generateOne(null, ai.seconds);
				}
				//The source removed meanwhile: nothing to continue
				else {
					if(((Item)aExt[0]).wav.isFile()) {
						generateOne((Item)aExt[0], (Double)aExt[1]);
					}
					longerDone((Item)aExt[0]);
				}
			}
		}
		catch(InterruptedException e) {
		}
		System.out.println("AI sound series ended ("+count+" generated)");
	}

	/**
	 * Waiting for a sound to be listened
	 */
	void showWaiting() {
		tde.gui.display.asyncExec(() -> {
			if(shell.isDisposed()) {
				return;
			}
			nextL.setText(lng("AI.SfxSeriesWaiting"));
			ai.statusL.setText(" ");
			ai.detailL.setText(" ");
			ai.etaL.setText(" ");
			ai.bar.setSelection(0);
		});
	}

	/**
	 * One sound of aSeconds; aFrom: the music continued (null: a new one)
	 */
	void generateOne(Item aFrom,double aSeconds) {
		final int aN = ++count;
		File aOutDir = ai.outDir();
		aOutDir.mkdirs();
		String aBase = AiClipGUI.fileBase(ai.clipName);
		if(aBase.isEmpty()) {
			aBase = dateBase;
		}
		aBase = AiClipGUI.freeBase(aOutDir, aBase, ".wav");
		File aOut = new File(aOutDir, aBase+".wav");
		File aTxt = new File(aOutDir, aBase+".txt");
		File aStop = new File(aOutDir, aBase+".stop");
		aStop.delete();
		stopFile = aStop;
		aheadRunning = aFrom == null;
		//A longer version asked just now (it could not see this sound ahead running yet): it goes first
		if(aFrom == null && !extensions.isEmpty()) {
			aheadRunning = false;
			count--;
			return;
		}
		tde.gui.display.asyncExec(() -> {
			if(!shell.isDisposed()) {
				nextL.setText(aFrom != null
						? lng("AI.MusicSeriesLonger").replace("%N%", ""+aN).replace("%FROM%", ""+aFrom.n).replace("%S%", ""+(int)aSeconds)
						: lng(sfx ? "AI.SfxSeriesNext" : "AI.MusicSeriesNext").replace("%N%", ""+aN));
			}
		});
		ai.stepStartMS = -1;
		ai.stepStartDone = 0;
		ai.lastUpdateMS = 0;
		String aError = null;
		boolean aDone = false;
		SoundGenerator aMG = null;
		try {
			Files.write(aTxt.toPath(), ai.prompt.getBytes(StandardCharsets.UTF_8));
			PythonAI.Progress aProgress = new PythonAI.Progress() {
				@Override
				public void status(String aKey, String aDetail, long aDoneN, long aTotal) {
					if(!closed) {
						ai.showStatus(aKey, aDetail, aDoneN, aTotal);
					}
				}
				@Override
				public boolean isCanceled() {
					return hardCancel;
				}
			};
			aMG = new SoundGenerator(tde.tdeDir, ai.soundModel, aProgress);
			aMG.stopFile = aStop;
			ai.generator = aMG;
			aDone = aMG.generate(aTxt, aSeconds, aFrom == null ? null : aFrom.wav, aOut);
		}
		catch(PythonAI.GenerationException e) {
			//Stopped for a longer version (arrow): not an error, its number is given again
			if("STOPPED".equals(e.code)) {
				System.out.println("AI sound "+aN+" stopped: a longer version first");
				aOut.delete();
				aTxt.delete();
				aStop.delete();
				count--;
				if(ai.generator == aMG) {
					ai.generator = null;
				}
				aheadRunning = false;
				return;
			}
			aError = closed ? null : ai.errorMessage(e);
		}
		catch(Throwable t) {
			aError = closed ? null : ai.errorMessage(t);
		}
		aheadRunning = false;
		aStop.delete();
		//(A new series may already have its own generator)
		if(ai.generator == aMG) {
			ai.generator = null;
		}
		//Closed meanwhile (or failed): nothing to show
		if(closed || !aDone) {
			aOut.delete();
			aTxt.delete();
		}
		if(closed) {
			return;
		}
		if(aError != null || !aDone) {
			error = aError == null ? lng(ai.errorKey()) : aError;
			final String aE = error;
			tde.gui.display.asyncExec(() -> {
				if(shell.isDisposed()) {
					return;
				}
				nextL.setText(" ");
				ai.statusL.setText(aE.replace("\n", " - "));
				ai.statusL.setForeground(tde.gui.display.getSystemColor(SWT.COLOR_RED));
				ai.detailL.setText(" ");
				ai.etaL.setText(" ");
				shell.layout(true, true);
			});
			return;
		}
		Item aI = new Item();
		aI.wav = aOut;
		aI.txt = aTxt;
		aI.n = aN;
		aI.seconds = aSeconds;
		final Item aSrc = aFrom;
		synchronized(this) {
			items.add(aI);
		}
		tde.gui.display.syncExec(() -> {
			if(shell.isDisposed() || closed) {
				return;
			}
			addRow(aI, aN, aSrc);
		});
	}

	/**
	 * Row of a sound: number, Play / Pause, position slider, Keep
	 */
	void addRow(Item aI,int aN,Item aFrom) {
		Label aL = new Label(rows, SWT.NONE);
		tde.gui.applyColorFont(aL);
		aL.setText(aN+"."+(aFrom == null ? "" : " "+lng("AI.MusicSeriesFrom").replace("%FROM%", ""+aFrom.n)));
		aL.setToolTipText(String.format("%.0f s", aI.seconds));
		aI.number = aL;
		//Slider, then the three buttons side by side: Play, Keep, Trash (music: then the longer version)
		aI.position = new Scale(rows, SWT.HORIZONTAL);
		aI.position.setMinimum(0);
		aI.position.setMaximum(1000);
		aI.position.setLayoutData(new GridData(GridData.FILL_HORIZONTAL));
		aI.play = button(rows, null, "play.gif");
		aI.play.setLayoutData(new GridData());
		aI.play.setToolTipText(lng("AI.ResultPlay"));
		aI.keep = button(rows, null, "checkGreen.png");
		aI.keep.setLayoutData(new GridData());
		aI.keep.setToolTipText(lng("AI.SfxSeriesKeep"));
		aI.trash = button(rows, null, "trash.png");
		aI.trash.setLayoutData(new GridData());
		aI.trash.setToolTipText(lng("AI.SeriesRemove"));
		//Music, right of the trash: a longer version (the continuation of this one) of the duration typed
		if(!sfx) {
			aI.durL = new Label(rows, SWT.NONE);
			tde.gui.applyColorFont(aI.durL);
			aI.durL.setText(seconds(aI.seconds));
			//The arrow is the button: a longer version of the duration of the field on its right
			aI.extend = button(rows, null, "arrowRight.gif");
			aI.extend.setLayoutData(new GridData());
			aI.extend.setToolTipText(lng("AI.MusicSeriesLongerTip"));
			aI.extend.addListener(SWT.Selection, e -> extend(aI));
			aI.longer = new Spinner(rows, SWT.BORDER);
			tde.gui.applyColorFont(aI.longer);
			int aMin = (int)Math.floor(aI.seconds)+1;
			aI.longer.setMinimum(aMin);
			aI.longer.setMaximum((int)SoundGenerator.CONTINUE_MAX_SECONDS);
			//Proposed: the next multiple of 30 s above its duration (3 s -> 30 s, 30 s -> 60 s)
			int aNext = ((int)Math.floor(aI.seconds+0.05)/30+1)*30;
			aI.longer.setSelection(Math.max(aMin, Math.min((int)SoundGenerator.CONTINUE_MAX_SECONDS, aNext)));
			aI.longer.setToolTipText(lng("AI.MusicSeriesLongerTip"));
			aI.unitL = new Label(rows, SWT.NONE);
			tde.gui.applyColorFont(aI.unitL);
			aI.unitL.setText("s");
		}
		try {
			AudioInputStream aAIS = AudioSystem.getAudioInputStream(aI.wav);
			aI.clip = AudioSystem.getClip();
			aI.clip.open(aAIS);
		}
		catch(Throwable t) {
			t.printStackTrace(System.err);
			aI.clip = null;
			aI.play.setEnabled(false);
		}
		aI.play.addListener(SWT.Selection, e -> togglePlay(aI));
		aI.position.addListener(SWT.Selection, e -> {
			if(aI.clip != null) {
				aI.clip.setFramePosition((int)((long)aI.clip.getFrameLength()*aI.position.getSelection()/1000));
			}
		});
		aI.keep.addListener(SWT.Selection, e -> keep(aI));
		aI.trash.addListener(SWT.Selection, e -> remove(aI));
		updateScroll();
		scroll.setOrigin(0, rows.getSize().y);
		tick(aI);
	}

	void updateScroll() {
		if(scroll.isDisposed()) {
			return;
		}
		int aW = scroll.getClientArea().width;
		Point aS = rows.computeSize(aW > 0 ? aW : SWT.DEFAULT, SWT.DEFAULT);
		scroll.setMinSize(aS);
		rows.layout(true, true);
	}

	void togglePlay(Item aI) {
		if(aI.clip == null) {
			return;
		}
		//Pause: the position stays, Play goes on from there
		if(aI.playing) {
			pausePlay(aI);
			updatePlay(aI);
			return;
		}
		//One sound at a time
		for(Item aO : items) {
			if(aO != aI && aO.clip != null && aO.playing) {
				pausePlay(aO);
				updatePlay(aO);
			}
		}
		if(aI.clip.getFramePosition() >= aI.clip.getFrameLength()-1) {
			aI.clip.setFramePosition(0);
		}
		aI.playing = true;
		aI.playStartMS = System.currentTimeMillis();
		aI.clip.start();
		updatePlay(aI);
		//Listened: one more sound is generated
		if(!aI.listened) {
			synchronized(this) {
				aI.listened = true;
				notifyAll();
			}
		}
	}

	/**
	 * Playing of aI paused at its position
	 */
	void pausePlay(Item aI) {
		aI.playing = false;
		aI.clip.stop();
	}

	void updatePlay(Item aI) {
		if(aI.play == null || aI.play.isDisposed()) {
			return;
		}
		boolean aOn = aI.clip != null && aI.playing;
		//Playing: a Pause button
		aI.play.setToolTipText(lng(aOn ? "AI.ResultPause" : "AI.ResultPlay"));
		Image aIm = tde.gui.imgsSwt.getIcon(aOn ? "pause.png" : "play.gif");
		if(aIm != null) {
			aI.play.setImage(aIm);
		}
	}

	void tick(Item aI) {
		if(closed || aI.position.isDisposed()) {
			return;
		}
		//The end of the sound: Play again (seen here, not by a STOP event: a late one could follow a new start)
		//(not running a while after its start: the start delay of the line is over)
		if(aI.playing && aI.clip != null && !aI.clip.isRunning() && System.currentTimeMillis()-aI.playStartMS > 500) {
			aI.playing = false;
			updatePlay(aI);
		}
		if(aI.clip != null && aI.clip.isRunning() && aI.clip.getFrameLength() > 0) {
			aI.position.setSelection((int)(1000L*aI.clip.getFramePosition()/aI.clip.getFrameLength()));
		}
		tde.gui.display.timerExec(100, () -> tick(aI));
	}

	/**
	 * Keep: the sound added to the media list (loaded in a thread), its files kept
	 */
	void keep(Item aI) {
		aI.keep.setEnabled(false);
		aI.kept = true;
		final String aPath = aI.wav.getPath();
		new Thread(() -> {
			String aErr = null;
			try {
				tde.mediaList.loadAudio(aPath);
			}
			catch(Throwable t) {
				aErr = ai.errorMessage(t);
			}
			final String aE = aErr;
			tde.gui.display.asyncExec(() -> {
				tde.gui.mediaListGUI.redraw();
				if(aE != null) {
					aI.kept = false;
					if(!aI.keep.isDisposed()) {
						aI.keep.setEnabled(true);
					}
					AiClipGUI.message(tde, SWT.ICON_ERROR, aE);
					return;
				}
				if(!aI.keep.isDisposed()) {
					//Kept: the check stays disabled, the number in green
					aI.keep.setToolTipText(lng("AI.SfxSeriesKept"));
					aI.number.setForeground(tde.gui.display.getSystemColor(SWT.COLOR_DARK_GREEN));
					aI.number.setText(aI.number.getText()+" "+lng("AI.SfxSeriesKept"));
					aI.number.getParent().layout(true, true);
				}
			});
		}).start();
	}

	/**
	 * The longer version of aI generated (or failed): its arrow again (generation thread)
	 */
	void longerDone(Item aI) {
		tde.gui.display.asyncExec(() -> {
			aI.longerPending = false;
			if(aI.extend != null && !aI.extend.isDisposed()) {
				aI.extend.setImage(tde.gui.imgsSwt.getIcon("arrowRight.gif"));
				aI.extend.setToolTipText(lng("AI.MusicSeriesLongerTip"));
			}
		});
	}

	/**
	 * A duration in seconds: "3 s", "2.5 s"
	 */
	static String seconds(double aS) {
		return (Math.abs(aS-Math.round(aS)) < 0.05 ? ""+Math.round(aS) : String.format("%.1f", aS))+" s";
	}

	/**
	 * Arrow (music): a longer version of aI, the continuation of its music, generated next (a sound ahead in
	 * progress is stopped, a longer version in progress goes on; several: in this order)
	 */
	void extend(Item aI) {
		//Already asked: the hourglass until its end
		if(aI.longerPending) {
			return;
		}
		//The text typed (a value above the maximum is not taken by the field while typing)
		int aS = aI.longer.getSelection();
		try {
			aS = Integer.parseInt(aI.longer.getText().trim());
		}
		catch(NumberFormatException e) {
		}
		aS = Math.max(aI.longer.getMinimum(), Math.min(aI.longer.getMaximum(), aS));
		aI.longer.setSelection(aS);
		if(aS <= aI.seconds) {
			return;
		}
		aI.longerPending = true;
		aI.extend.setImage(tde.gui.imgsSwt.getIcon("wait.png"));
		aI.extend.setToolTipText(lng("AI.MusicSeriesLongerWait"));
		synchronized(this) {
			extensions.add(new Object[] {aI, (double)aS});
			notifyAll();
		}
		//A sound ahead in progress stops: the longer version first (a longer version in progress goes on)
		File aStop = stopFile;
		if(aheadRunning && aStop != null) {
			try {
				aStop.createNewFile();
			}
			catch(Throwable t) {
				t.printStackTrace(System.err);
			}
		}
	}

	/**
	 * Trash: the row removed, its files deleted (not those of a kept sound: in the media list); a sound not
	 * listened yet frees its place ahead, so another one is generated
	 */
	void remove(Item aI) {
		if(aI.clip != null) {
			try {
				aI.clip.stop();
				aI.clip.close();
			}
			catch(Throwable t) {
				t.printStackTrace(System.err);
			}
		}
		if(!aI.kept) {
			aI.wav.delete();
			aI.txt.delete();
		}
		synchronized(this) {
			items.remove(aI);
			notifyAll();
		}
		for(Control aC : new Control[] {aI.number, aI.play, aI.position, aI.keep, aI.trash, aI.durL, aI.extend, aI.longer, aI.unitL}) {
			if(aC != null) {
				aC.dispose();
			}
		}
		updateScroll();
	}

	/**
	 * End of the series: the sounds not kept deleted; a generation in progress ends in the background (its sound
	 * deleted) unless the model is still being installed or loaded
	 */
	void close() {
		if(closed) {
			return;
		}
		synchronized(this) {
			closed = true;
			notifyAll();
		}
		PythonAI aG = ai.generator;
		if(aG != null && !SoundGenerator.isLoaded(ai.soundModel)) {
			hardCancel = true;
			aG.cancel();
		}
		//The next generation waits for its end
		ai.ending = aG;
		for(Item aI : items) {
			if(aI.clip != null) {
				try {
					aI.clip.stop();
					aI.clip.close();
				}
				catch(Throwable t) {
					t.printStackTrace(System.err);
				}
			}
			if(!aI.kept) {
				aI.wav.delete();
				aI.txt.delete();
			}
		}
		if(!shell.isDisposed()) {
			shell.dispose();
		}
		AiClipGUI.running = false;
	}
}
