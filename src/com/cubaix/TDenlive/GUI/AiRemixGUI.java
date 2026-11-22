package com.cubaix.TDenlive.GUI;

import java.io.File;
import java.io.RandomAccessFile;
import java.util.Vector;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.SourceDataLine;

import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.MessageBox;
import org.eclipse.swt.widgets.Scale;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Text;

import com.cubaix.TDenlive.TDConfig;
import com.cubaix.TDenlive.TDenlive;
import com.cubaix.TDenlive.ai.RemixGenerator;

/**
 * Listening window of the AI remix (AiClipGUI, kind REMIX), after the separation of the tracks by Demucs: one row
 * per track (drums, bass, other, vocals...; then the original sound, muted at first) with its checkbox (heard),
 * Solo and its volume (0 to 200 %); Play / Pause and the position of the sound. The tracks are the WAV files of the
 * work folder (44.1 kHz stereo 16 bits), read by random access and mixed in real time at the volumes of the moment
 * (ramped over a block of 20 ms: no clicks), only to listen: the volumes are not saved. "Save the multi-track MP4"
 * writes AI/<name>.mp4 (the video of the source copied; .m4a without video): the original sound, then each track
 * at 100 %, streams named "[3De] ..." (RemixGenerator.mux()), no mix; added to the media list: on the time line,
 * its clips get the multi-track mixer (processors.TrackMixer) with all the tracks and without the original.
 * Close deletes the work folder (asked when nothing was saved).
 */
public class AiRemixGUI {
	static final int RATE = 44100;
	static final int CHUNK = RATE/50;
	static final int VOL_MAX = 200;

	AiClipGUI ai;
	TDenlive tde;
	String sourcePath;
	//Audio stream of the source separated (its original sound in the MP4)
	int sourceTrack;
	String sourceName;
	Vector<RemixGenerator.Stem> stems;
	File work;
	Shell shell = null;
	//Tracks: the sources, then the original sound (input.wav of the work folder, if kept)
	Vector<Wav> wavs = new Vector<Wav>();
	Vector<String> names = new Vector<String>();
	Button[] onB;
	Button[] soloB;
	Scale[] volS;
	Label[] volL;
	//Gains heard (set by the UI thread, read by the play thread)
	volatile double[] gains;
	Button playB = null;
	Scale posS = null;
	Label timeL = null;
	Label statusL = null;
	Text nameT = null;
	Button saveB = null;
	long nbFrames = 0;
	//Play: from startFrame, the position heard = startFrame + frames played by the line
	volatile boolean playing = false;
	volatile long startFrame = 0;
	volatile SourceDataLine line = null;
	Thread playTh = null;
	boolean saved = false;
	volatile boolean saving = false;

	/**
	 * A WAV file (PCM 16 bits stereo) read by random access
	 */
	static class Wav {
		RandomAccessFile raf;
		long dataStart;
		long frames;
		int channels;

		Wav(File aF) throws Exception {
			raf = new RandomAccessFile(aF, "r");
			byte[] aH = new byte[12];
			raf.readFully(aH);
			if(aH[0] != 'R' || aH[1] != 'I' || aH[2] != 'F' || aH[3] != 'F') {
				throw new Exception("Not a WAV: "+aF);
			}
			long aPos = 12;
			channels = 2;
			while(aPos+8 <= raf.length()) {
				raf.seek(aPos);
				byte[] aC = new byte[8];
				raf.readFully(aC);
				String aId = new String(aC, 0, 4, "ISO-8859-1");
				long aSize = (aC[4] & 0xFFL) | (aC[5] & 0xFFL) << 8 | (aC[6] & 0xFFL) << 16 | (aC[7] & 0xFFL) << 24;
				if("fmt ".equals(aId)) {
					byte[] aFmt = new byte[4];
					raf.readFully(aFmt);
					channels = (aFmt[2] & 0xFF) | (aFmt[3] & 0xFF) << 8;
				}
				if("data".equals(aId)) {
					dataStart = aPos+8;
					frames = Math.min(aSize, raf.length()-dataStart)/(2L*channels);
					return;
				}
				aPos += 8+aSize+(aSize & 1);
			}
			throw new Exception("No data in "+aF);
		}

		/** aN frames from aFrame added to aOut (stereo) × aG0 .. aG1 (ramp) */
		synchronized void addTo(long aFrame,float[] aOut,int aN,double aG0,double aG1) throws Exception {
			long aFrom = Math.max(0, aFrame);
			long aTo = Math.min(frames, aFrame+aN);
			if(aTo <= aFrom) {
				return;
			}
			int aCount = (int)(aTo-aFrom);
			byte[] aB = new byte[aCount*2*channels];
			raf.seek(dataStart+aFrom*2*channels);
			raf.readFully(aB);
			int aOff = (int)(aFrom-aFrame);
			for(int f = 0;f < aCount;f++) {
				double aFact = (aOff+f)/(double)aN;
				float aG = (float)(aG0*(1.0-aFact)+aG1*aFact);
				for(int c = 0;c < 2;c++) {
					int aCh = Math.min(c, channels-1);
					int i = 2*(f*channels+aCh);
					short aS = (short)((aB[i] & 0xFF) | (aB[i+1] << 8));
					aOut[2*(aOff+f)+c] += aS/32768f*aG;
				}
			}
		}

		synchronized void close() {
			try {
				raf.close();
			}
			catch(Throwable t) {
			}
		}
	}

	AiRemixGUI(AiClipGUI aAI,String aSourcePath,int aSourceTrack,String aSourceName,Vector<RemixGenerator.Stem> aStems,File aWork) {
		ai = aAI;
		tde = aAI.tde;
		sourcePath = aSourcePath;
		sourceTrack = aSourceTrack;
		sourceName = aSourceName;
		stems = aStems;
		work = aWork;
	}

	String lng(String aKey) {
		return tde.gui.lngs.get(aKey);
	}

	/** Name of a source of Demucs in the language of the interface */
	String stemName(String aSource) {
		String aKey = "AI.RemixStem_"+aSource;
		String aN = lng(aKey);
		return aN == null || aN.contains(aKey) || aN.equals(aKey) ? aSource : aN;
	}

	static String time(long aFrames) {
		long aS = aFrames/RATE;
		return (aS/60)+":"+String.format("%02d", aS%60);
	}

	void open() {
		try {
			for(RemixGenerator.Stem aS : stems) {
				wavs.add(new Wav(aS.file));
				names.add(stemName(aS.name));
			}
			File aIn = new File(work, "input.wav");
			if(aIn.isFile()) {
				wavs.add(new Wav(aIn));
				names.add(lng("AI.RemixOriginal"));
			}
		}
		catch(Throwable t) {
			t.printStackTrace(System.err);
			AiClipGUI.message(tde, SWT.ICON_ERROR, lng("AI.RemixError")+"\n"+t.getMessage());
			cleanUp();
			AiClipGUI.running = false;
			return;
		}
		for(Wav aW : wavs) {
			nbFrames = Math.max(nbFrames, aW.frames);
		}
		int aN = wavs.size();
		gains = new double[aN];
		shell = new Shell(tde.gui.shell, SWT.DIALOG_TRIM | SWT.RESIZE);
		shell.setText(lng("AI.RemixTitle")+" - "+sourceName);
		shell.setLayout(new GridLayout(1, false));
		tde.gui.applyColorFont(shell);
		shell.setImage(tde.gui.imgsSwt.getIcon("3Denlive32.png"));
		ai.labelWidth = 560;
		ai.addLabel(shell, ai.prompt, 0).setFont(tde.gui.fontsSwt.robotoBold);
		ai.addLabel(shell, lng("AI.RemixListen"), 0);

		//Tracks: heard, Solo, volume
		Composite aTracks = new Composite(shell, SWT.NONE);
		tde.gui.applyColorFont(aTracks);
		aTracks.setLayoutData(new GridData(GridData.FILL_HORIZONTAL));
		GridLayout aTGL = new GridLayout(4, false);
		aTGL.horizontalSpacing = 12;
		aTracks.setLayout(aTGL);
		onB = new Button[aN];
		soloB = new Button[aN];
		volS = new Scale[aN];
		volL = new Label[aN];
		boolean aHasOriginal = aN > stems.size();
		for(int t = 0;t < aN;t++) {
			final int aT = t;
			boolean aOriginal = aHasOriginal && t == aN-1;
			onB[t] = new Button(aTracks, SWT.CHECK);
			tde.gui.applyColorFont(onB[t]);
			onB[t].setText(names.elementAt(t));
			onB[t].setFont(tde.gui.fontsSwt.robotoBold);
			onB[t].setSelection(!aOriginal);
			onB[t].setToolTipText(lng("AI.RemixOnTip"));
			GridData aOGD = new GridData();
			aOGD.widthHint = 170;
			onB[t].setLayoutData(aOGD);
			soloB[t] = new Button(aTracks, SWT.TOGGLE);
			tde.gui.applyColorFont(soloB[t]);
			soloB[t].setText(lng("AI.RemixSolo"));
			soloB[t].setToolTipText(lng("AI.RemixSoloTip"));
			volS[t] = new Scale(aTracks, SWT.HORIZONTAL);
			volS[t].setMinimum(0);
			volS[t].setMaximum(VOL_MAX);
			volS[t].setPageIncrement(10);
			volS[t].setSelection(100);
			GridData aSGD = new GridData(GridData.FILL_HORIZONTAL);
			aSGD.widthHint = 260;
			volS[t].setLayoutData(aSGD);
			volL[t] = new Label(aTracks, SWT.NONE);
			tde.gui.applyColorFont(volL[t]);
			GridData aLGD = new GridData();
			aLGD.widthHint = 48;
			volL[t].setLayoutData(aLGD);
			onB[t].addListener(SWT.Selection, e -> updateGains());
			soloB[t].addListener(SWT.Selection, e -> updateGains());
			volS[t].addListener(SWT.Selection, e -> updateGains());
			//Double click on the % : back to 100 %
			volL[t].addListener(SWT.MouseDoubleClick, e -> {
				volS[aT].setSelection(100);
				updateGains();
			});
		}
		updateGains();

		//Play / Pause, position
		Composite aPlay = new Composite(shell, SWT.NONE);
		tde.gui.applyColorFont(aPlay);
		aPlay.setLayoutData(new GridData(GridData.FILL_HORIZONTAL));
		GridLayout aPGL = new GridLayout(3, false);
		tde.gui.applyNoMargin(aPGL);
		aPlay.setLayout(aPGL);
		playB = new Button(aPlay, SWT.PUSH);
		tde.gui.applyColorFont(playB);
		playB.addListener(SWT.Selection, e -> {
			if(playing) {
				pause();
			}
			else {
				play();
			}
			updatePlay();
		});
		posS = new Scale(aPlay, SWT.HORIZONTAL);
		posS.setMinimum(0);
		posS.setMaximum(1000);
		posS.setLayoutData(new GridData(GridData.FILL_HORIZONTAL));
		posS.addListener(SWT.Selection, e -> {
			long aF = nbFrames*posS.getSelection()/1000;
			boolean aWas = playing;
			pause();
			startFrame = aF;
			if(aWas) {
				play();
			}
			updatePlay();
		});
		timeL = new Label(aPlay, SWT.NONE);
		tde.gui.applyColorFont(timeL);
		GridData aTLGD = new GridData();
		aTLGD.widthHint = 90;
		timeL.setLayoutData(aTLGD);
		updatePlay();

		//Name of the file, save
		Composite aNameRow = new Composite(shell, SWT.NONE);
		tde.gui.applyColorFont(aNameRow);
		GridData aNRGD = new GridData(GridData.FILL_HORIZONTAL);
		aNRGD.verticalIndent = 8;
		aNameRow.setLayoutData(aNRGD);
		GridLayout aNGL = new GridLayout(2, false);
		tde.gui.applyNoMargin(aNGL);
		aNameRow.setLayout(aNGL);
		Label aNL = new Label(aNameRow, SWT.NONE);
		tde.gui.applyColorFont(aNL);
		aNL.setText(lng("AI.ClipName"));
		nameT = new Text(aNameRow, SWT.SINGLE | SWT.BORDER);
		tde.gui.applyColorFont(nameT);
		nameT.setLayoutData(new GridData(GridData.FILL_HORIZONTAL));
		nameT.setText(ai.clipName == null ? "" : ai.clipName);
		ai.addLabel(shell, lng("AI.RemixSaveNote"), 0);
		statusL = ai.addLabel(shell, " ", 0);

		Composite aButtons = new Composite(shell, SWT.NONE);
		GridData aBGD = new GridData(GridData.HORIZONTAL_ALIGN_END);
		aBGD.verticalIndent = 8;
		aButtons.setLayoutData(aBGD);
		tde.gui.applyColorFont(aButtons);
		GridLayout aBGL = new GridLayout(3, false);
		tde.gui.applyNoMargin(aBGL);
		aButtons.setLayout(aBGL);
		Button aOther = button(aButtons, lng("AI.ResultOther"), null);
		aOther.addListener(SWT.Selection, e -> {
			if(!confirmClose()) {
				return;
			}
			close();
			ai.again();
		});
		Button aClose = button(aButtons, lng("AI.ResultClose"), null);
		aClose.addListener(SWT.Selection, e -> {
			if(!confirmClose()) {
				return;
			}
			close();
			AiClipGUI.running = false;
		});
		saveB = button(aButtons, lng("AI.RemixSave"), "checked.gif");
		saveB.addListener(SWT.Selection, e -> save());
		shell.addListener(SWT.Close, e -> {
			if(!confirmClose()) {
				e.doit = false;
				return;
			}
			stop();
			cleanUp();
			AiClipGUI.running = false;
		});
		shell.addListener(SWT.Dispose, e -> {
			stop();
			cleanUp();
		});
		ai.center(shell);
		shell.setDefaultButton(saveB);
		shell.open();
		tick();
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

	/**
	 * Gains heard from the widgets: a track checked, Solo (when any track is solo, only those are heard), volume
	 */
	void updateGains() {
		int aN = onB.length;
		boolean aAnySolo = false;
		for(Button aB : soloB) {
			aAnySolo |= aB.getSelection();
		}
		double[] aG = new double[aN];
		for(int t = 0;t < aN;t++) {
			volL[t].setText(volS[t].getSelection()+" %");
			boolean aHeard = onB[t].getSelection() && (!aAnySolo || soloB[t].getSelection());
			aG[t] = aHeard ? volS[t].getSelection()/100.0 : 0;
		}
		gains = aG;
	}

	long position() {
		SourceDataLine aL = line;
		return aL == null || !playing ? startFrame : startFrame+aL.getLongFramePosition();
	}

	void play() {
		if(playing) {
			return;
		}
		if(startFrame >= nbFrames) {
			startFrame = 0;
		}
		SourceDataLine aLine;
		try {
			AudioFormat aFormat = new AudioFormat(RATE, 16, 2, true, false);
			aLine = AudioSystem.getSourceDataLine(aFormat);
			aLine.open(aFormat, RATE/8*4);
		}
		catch(Throwable t) {
			System.err.println("No sound output: "+t);
			return;
		}
		line = aLine;
		playing = true;
		final long aStart = startFrame;
		playTh = new Thread(() -> {
			float[] aMix = new float[CHUNK*2];
			byte[] aPcm = new byte[CHUNK*4];
			double[] aPrev = gains.clone();
			long aFrame = aStart;
			aLine.start();
			while(playing && aFrame < nbFrames) {
				try {
					java.util.Arrays.fill(aMix, 0f);
					double[] aG = gains;
					for(int t = 0;t < wavs.size();t++) {
						if(aPrev[t] > 0 || aG[t] > 0) {
							wavs.elementAt(t).addTo(aFrame, aMix, CHUNK, aPrev[t], aG[t]);
						}
					}
					aPrev = aG.clone();
					for(int s = 0;s < CHUNK*2;s++) {
						int aV = Math.round(Math.max(-1f, Math.min(1f, aMix[s]))*32767);
						aPcm[2*s] = (byte)aV;
						aPcm[2*s+1] = (byte)(aV >> 8);
					}
					aLine.write(aPcm, 0, aPcm.length);
					aFrame += CHUNK;
				}
				catch(Throwable t) {
					t.printStackTrace(System.err);
					break;
				}
			}
			if(playing) {
				//End of the sound
				aLine.drain();
				playing = false;
				startFrame = 0;
			}
			try {
				aLine.stop();
				aLine.flush();
				aLine.close();
			}
			catch(Throwable t) {
			}
		}, "AI remix play");
		playTh.setDaemon(true);
		playTh.start();
	}

	void pause() {
		if(!playing) {
			return;
		}
		long aPos = position();
		stop();
		startFrame = Math.min(aPos, nbFrames);
	}

	void stop() {
		playing = false;
		Thread aTh = playTh;
		if(aTh != null) {
			try {
				aTh.join(1000);
			}
			catch(Throwable t) {
			}
		}
		playTh = null;
		line = null;
	}

	void updatePlay() {
		if(playB == null || playB.isDisposed()) {
			return;
		}
		playB.setText(lng(playing ? "AI.ResultPause" : "AI.ResultPlay"));
		Image aI = tde.gui.imgsSwt.getIcon(playing ? "pause.png" : "play.gif");
		if(aI != null) {
			playB.setImage(aI);
		}
		long aPos = position();
		if(nbFrames > 0) {
			posS.setSelection((int)(1000*Math.min(aPos, nbFrames)/nbFrames));
		}
		timeL.setText(time(aPos)+" / "+time(nbFrames));
		playB.getParent().layout(true, true);
	}

	/** Position and button while the window is open */
	void tick() {
		if(shell == null || shell.isDisposed()) {
			return;
		}
		if(playing || !playB.getText().equals(lng("AI.ResultPlay"))) {
			updatePlay();
		}
		tde.gui.display.timerExec(100, this::tick);
	}

	/**
	 * The multi-track MP4 (thread), then added to the media list
	 */
	void save() {
		if(saving) {
			return;
		}
		saving = true;
		saveB.setEnabled(false);
		statusL.setText(lng("AI.RemixSaving"));
		AiClipGUI.fit(statusL);
		ai.working(statusL, true);
		//The original sound, then the sources (named "[3De] ..." by mux()), in English: translated when shown
		//(TrackMixer.showTitle()); no mix: done in the montage
		String[] aNames = new String[stems.size()+1];
		aNames[0] = "Original";
		for(int s = 0;s < stems.size();s++) {
			String aId = stems.elementAt(s).name;
			aNames[s+1] = aId.isEmpty() ? aId : Character.toUpperCase(aId.charAt(0))+aId.substring(1);
		}
		String aBase = AiClipGUI.fileBase(nameT.getText().trim());
		if(aBase.isEmpty()) {
			aBase = AiClipGUI.fileBase(new File(sourcePath).getName().replaceAll("[.][A-Za-z0-9]{2,4}$", "")+" "+lng("AI.RemixSuffix"));
		}
		final String aB = aBase;
		final File aDir = ai.outDir();
		new Thread(() -> {
			String aErr = null;
			File aOut = null;
			try {
				File aSrc = new File(sourcePath);
				boolean aVideo = RemixGenerator.hasVideo(aSrc);
				String aExt = aVideo ? ".mp4" : ".m4a";
				aDir.mkdirs();
				aOut = new File(aDir, AiClipGUI.freeBase(aDir, aB, aExt)+aExt);
				RemixGenerator.mux(aSrc, sourceTrack, aVideo, stems, aNames, aOut);
				if(aVideo) {
					tde.mediaList.loadImageLR(aOut.getPath(), aOut.getPath(), TDConfig.PROCESSING_MODE_RENDER, 0);
				}
				else {
					tde.mediaList.loadAudio(aOut.getPath());
				}
			}
			catch(Throwable t) {
				t.printStackTrace(System.err);
				aErr = t.getMessage() == null ? t.toString() : t.getMessage();
			}
			final String aE = aErr;
			final File aO = aOut;
			tde.gui.display.asyncExec(() -> {
				saving = false;
				tde.gui.mediaListGUI.redraw();
				if(shell.isDisposed()) {
					return;
				}
				saveB.setEnabled(true);
				ai.working(statusL, false);
				if(aE != null) {
					statusL.setText(" ");
					AiClipGUI.fit(statusL);
					AiClipGUI.message(tde, SWT.ICON_ERROR, lng("AI.RemixError")+"\n"+aE);
					return;
				}
				saved = true;
				statusL.setText(lng("AI.RemixSaved").replace("%FILE%", aO.getName()));
				AiClipGUI.fit(shell);
			});
		}, "AI remix save").start();
	}

	/**
	 * Nothing saved: the tracks would be lost (asked)
	 */
	boolean confirmClose() {
		if(saving) {
			return false;
		}
		if(saved) {
			return true;
		}
		MessageBox aMB = new MessageBox(shell, SWT.ICON_QUESTION | SWT.YES | SWT.NO);
		aMB.setText(shell.getText());
		aMB.setMessage(lng("AI.RemixCloseAsk"));
		return aMB.open() == SWT.YES;
	}

	void close() {
		stop();
		cleanUp();
		if(!shell.isDisposed()) {
			shell.dispose();
		}
	}

	void cleanUp() {
		for(Wav aW : wavs) {
			aW.close();
		}
		wavs.clear();
		deleteTree(work);
	}

	static void deleteTree(File aF) {
		if(aF == null) {
			return;
		}
		File[] aFs = aF.listFiles();
		if(aFs != null) {
			for(File aC : aFs) {
				deleteTree(aC);
			}
		}
		aF.delete();
	}
}
