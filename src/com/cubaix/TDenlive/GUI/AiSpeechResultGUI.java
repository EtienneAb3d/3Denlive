package com.cubaix.TDenlive.GUI;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Vector;

import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.Clip;
import javax.sound.sampled.LineEvent;

import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.StyleRange;
import org.eclipse.swt.custom.StyledText;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Combo;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.MessageBox;
import org.eclipse.swt.widgets.ProgressBar;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Table;
import org.eclipse.swt.widgets.TableItem;

import com.cubaix.TDenlive.TDConfig;
import com.cubaix.TDenlive.TDenlive;
import com.cubaix.TDenlive.ai.SpeechGenerator;
import com.cubaix.TDenlive.medias.Media;
import com.cubaix.TDenlive.processors.Processor;
import com.cubaix.TDenlive.processors.Subtitles;

/**
 * End of the subtitles or of the voice translation of a video (AiClipGUI, speech kinds): the files written into
 * the AI folder of the project (one SRT per language, the WAV of the translated voice), the text of the subtitles
 * of the language selected in the list, the voice played with Play / Pause (never automatically). Then:
 * - the subtitles of a language put on the clips of the video (a Subtitles processor on each, its file replaced
 * if it has one already), when the video is in the media list; else the video added to the media list;
 * - the voice added to the media list (a sound, for an audio line);
 * - Close keeps the files (the file chooser of the Subtitles processor opens on them), Discard deletes them,
 * Something else: back to the form with the same settings.
 */
public class AiSpeechResultGUI {
	AiClipGUI ai;
	TDenlive tde;
	SpeechGenerator.Result result;
	//Media of the library the speech comes from (null: a file of the disk), its file
	Media media;
	String sourcePath;
	Shell shell = null;
	Clip clip = null;
	Button playB = null;
	ProgressBar position = null;
	Label statusL = null;
	volatile boolean closed = false;
	//Something added to the project: the files are kept
	boolean used = false;
	Button discardB = null;
	//Text of the SRT shown, its cues: {start ms, end ms, first char, end char} in the text; the cue selected
	//StyledText: a background of the cue, seen without the focus (the selection of a Text is not drawn without it on macOS)
	StyledText text = null;
	Vector<long[]> cues = new Vector<long[]>();
	int cueShown = -1;

	AiSpeechResultGUI(AiClipGUI aAI,SpeechGenerator.Result aResult,Media aMedia,String aSourcePath) {
		ai = aAI;
		tde = aAI.tde;
		result = aResult;
		media = aMedia;
		sourcePath = aSourcePath;
	}

	String lng(String aKey) {
		return tde.gui.lngs.get(aKey);
	}

	String languageName(String aL) {
		return aL == null || aL.isEmpty() ? lng("AI.SpeechUnknownLanguage") : SpeechGenerator.displayName(aL, tde.config.lng);
	}

	void open() {
		shell = new Shell(tde.gui.shell, SWT.DIALOG_TRIM | SWT.RESIZE);
		shell.setText(lng("AI.ResultTitle")+" - "+lng(ai.dub ? "AI.DubTitle" : "AI.SubsTitle"));
		shell.setLayout(new GridLayout(1, false));
		tde.gui.applyColorFont(shell);
		shell.setImage(tde.gui.imgsSwt.getIcon("3Denlive32.png"));
		ai.labelWidth = 560;
		ai.addLabel(shell, ai.prompt, 0).setFont(tde.gui.fontsSwt.robotoBold);
		if(!result.spoken.isEmpty()) {
			ai.addLabel(shell, lng("AI.SpeechRecognized").replace("%LANG%", languageName(result.spoken)), 0);
		}
		ai.addLabel(shell, lng("AI.SpeechFiles").replace("%DIR%", ai.outDir().getPath()), 0);

		//Files and the text of the one selected
		Table aFiles = new Table(shell, SWT.BORDER | SWT.SINGLE | SWT.FULL_SELECTION | SWT.V_SCROLL);
		tde.gui.applyColorFont(aFiles);
		GridData aFGD = new GridData(GridData.FILL_HORIZONTAL);
		aFGD.heightHint = tde.gui.h(Math.min(6, result.files.size()+(result.voice != null ? 1 : 0))*18+4);
		aFiles.setLayoutData(aFGD);
		for(int i = 0;i < result.files.size();i++) {
			TableItem aI = new TableItem(aFiles, SWT.NONE);
			aI.setText(languageName(result.languages.elementAt(i))+" : "+result.files.elementAt(i).getName());
			aI.setData(result.files.elementAt(i));
		}
		if(result.voice != null) {
			TableItem aI = new TableItem(aFiles, SWT.NONE);
			aI.setText(lng("AI.DubVoice")+" : "+result.voice.getName());
		}
		StyledText aText = text = new StyledText(shell, SWT.MULTI | SWT.WRAP | SWT.BORDER | SWT.V_SCROLL | SWT.READ_ONLY);
		tde.gui.applyColorFont(aText);
		GridData aTGD = new GridData(GridData.FILL_BOTH);
		aTGD.widthHint = 560;
		aTGD.heightHint = tde.gui.h(220);
		aText.setLayoutData(aTGD);
		aFiles.addListener(SWT.Selection, e -> showFile(aFiles, aText));
		aFiles.select(result.voice != null && result.files.size() > 1 ? result.files.size()-1 : 0);
		showFile(aFiles, aText);

		//The voice: Play / Pause and its position
		if(result.voice != null) {
			Composite aPlay = new Composite(shell, SWT.NONE);
			tde.gui.applyColorFont(aPlay);
			aPlay.setLayoutData(new GridData(GridData.FILL_HORIZONTAL));
			GridLayout aPGL = new GridLayout(2, false);
			tde.gui.applyNoMargin(aPGL);
			aPlay.setLayout(aPGL);
			playB = new Button(aPlay, SWT.PUSH);
			tde.gui.applyColorFont(playB);
			playB.addListener(SWT.Selection, e -> togglePlay());
			position = new ProgressBar(aPlay, SWT.SMOOTH | SWT.HORIZONTAL);
			position.setMaximum(1000);
			position.setLayoutData(new GridData(GridData.FILL_HORIZONTAL));
			startSound();
			updatePlay();
		}

		//Into the project
		Composite aUse = new Composite(shell, SWT.NONE);
		tde.gui.applyColorFont(aUse);
		GridData aUGD = new GridData(GridData.FILL_HORIZONTAL);
		aUGD.verticalIndent = 8;
		aUse.setLayoutData(aUGD);
		GridLayout aUGL = new GridLayout(3, false);
		tde.gui.applyNoMargin(aUGL);
		aUse.setLayout(aUGL);
		Vector<com.cubaix.TDenlive.medias.Clip> aClips = media == null ? new Vector<com.cubaix.TDenlive.medias.Clip>() : tde.timeLineStack.clipsOf(media);
		if(media != null && !aClips.isEmpty()) {
			Label aL = new Label(aUse, SWT.NONE);
			tde.gui.applyColorFont(aL);
			aL.setText(lng("AI.SubsApplyLang"));
			Combo aLang = new Combo(aUse, SWT.READ_ONLY | SWT.DROP_DOWN);
			tde.gui.applyColorFont(aLang);
			for(String aLg : result.languages) {
				aLang.add(languageName(aLg));
			}
			//The language of the voice when dubbed, else the first one
			aLang.select(ai.dub ? result.languages.size()-1 : 0);
			Button aApply = new Button(aUse, SWT.PUSH);
			tde.gui.applyColorFont(aApply);
			aApply.setText(lng("AI.SubsApply").replace("%N%", ""+aClips.size()));
			aApply.setImage(tde.gui.imgsSwt.getIcon("note.png"));
			aApply.addListener(SWT.Selection, e -> {
				int aI = aLang.getSelectionIndex();
				if(aI >= 0) {
					apply(aClips, result.files.elementAt(aI));
				}
			});
		}
		else if(media == null && sourcePath != null && AiClipGUI.isVideoFile(sourcePath)) {
			Button aAddVideo = new Button(aUse, SWT.PUSH);
			tde.gui.applyColorFont(aAddVideo);
			aAddVideo.setText(lng("AI.SpeechAddVideo"));
			aAddVideo.setLayoutData(new GridData(SWT.BEGINNING, SWT.CENTER, false, false, 3, 1));
			aAddVideo.addListener(SWT.Selection, e -> {
				aAddVideo.setEnabled(false);
				load(sourcePath, false, aAddVideo);
			});
		}
		else if(media != null) {
			ai.addLabel(aUse, lng("AI.SubsNoClip"), 0);
		}
		if(result.voice != null) {
			Button aAddVoice = new Button(aUse, SWT.PUSH);
			tde.gui.applyColorFont(aAddVoice);
			aAddVoice.setText(lng("AI.DubAddVoice"));
			aAddVoice.setImage(tde.gui.imgsSwt.getIcon("checked.gif"));
			aAddVoice.setLayoutData(new GridData(SWT.BEGINNING, SWT.CENTER, false, false, 3, 1));
			aAddVoice.addListener(SWT.Selection, e -> {
				aAddVoice.setEnabled(false);
				load(result.voice.getPath(), true, aAddVoice);
			});
		}
		ai.addLabel(shell, lng("AI.SubsUseLater"), 0);
		statusL = ai.addLabel(shell, " ", 0);

		Composite aButtons = new Composite(shell, SWT.NONE);
		GridData aBGD = new GridData(GridData.HORIZONTAL_ALIGN_END);
		aBGD.verticalIndent = 8;
		aButtons.setLayoutData(aBGD);
		tde.gui.applyColorFont(aButtons);
		GridLayout aBGL = new GridLayout(3, true);
		tde.gui.applyNoMargin(aBGL);
		aButtons.setLayout(aBGL);
		discardB = button(aButtons, lng("AI.ResultDiscard"), "trash.png");
		discardB.addListener(SWT.Selection, e -> {
			MessageBox aMB = new MessageBox(shell, SWT.ICON_QUESTION | SWT.YES | SWT.NO);
			aMB.setText(shell.getText());
			aMB.setMessage(lng("AI.SpeechDiscardAsk"));
			if(aMB.open() != SWT.YES) {
				return;
			}
			discard();
			close();
			AiClipGUI.running = false;
		});
		Button aOther = button(aButtons, lng("AI.ResultOther"), null);
		aOther.addListener(SWT.Selection, e -> {
			close();
			ai.again();
		});
		Button aClose = button(aButtons, lng("AI.ResultClose"), "checked.gif");
		aClose.addListener(SWT.Selection, e -> {
			close();
			AiClipGUI.running = false;
		});
		shell.addListener(SWT.Close, e -> {
			stop();
			AiClipGUI.running = false;
		});
		shell.addListener(SWT.Dispose, e -> stop());
		ai.center(shell);
		shell.setDefaultButton(aClose);
		shell.open();
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
	 * Text of the SRT selected (the voice: the subtitles of its language)
	 */
	void showFile(Table aFiles,StyledText aText) {
		int aI = aFiles.getSelectionIndex();
		File aF = aI >= 0 && aI < result.files.size() ? result.files.elementAt(aI) : result.files.isEmpty() ? null : result.files.lastElement();
		try {
			String aS = aF == null ? "" : new String(Files.readAllBytes(aF.toPath()), StandardCharsets.UTF_8);
			aText.setText(aS.replace("\r\n", "\n").replace('\r', '\n'));
		}
		catch(Throwable t) {
			t.printStackTrace(System.err);
			aText.setText("");
		}
		parseCues(aText.getText());
		cueShown = -1;
		syncText();
	}

	/**
	 * Cues of the SRT text aS (as shown: offsets in the text widget)
	 */
	void parseCues(String aS) {
		cues.clear();
		java.util.regex.Matcher aM = java.util.regex.Pattern.compile(
				"(\\d+):(\\d+):(\\d+)[,.](\\d+)\\s*-->\\s*(\\d+):(\\d+):(\\d+)[,.](\\d+)[^\\n]*\\n").matcher(aS);
		while(aM.find()) {
			long aStart = ms(aM, 1);
			long aEnd = ms(aM, 5);
			int aFrom = aM.end();
			//The text: up to the empty line
			int aTo = aS.indexOf("\n\n", aFrom);
			if(aTo < 0) {
				aTo = aS.length();
			}
			while(aTo > aFrom && Character.isWhitespace(aS.charAt(aTo-1))) {
				aTo--;
			}
			cues.add(new long[] {aStart, aEnd, aFrom, aTo});
		}
	}

	static long ms(java.util.regex.Matcher aM,int aG) {
		return ((Long.parseLong(aM.group(aG))*60+Long.parseLong(aM.group(aG+1)))*60+Long.parseLong(aM.group(aG+2)))*1000
				+Long.parseLong(aM.group(aG+3));
	}

	/**
	 * The cue at the position of the sound selected in the text (the last one started; none before the first one)
	 */
	void syncText() {
		if(clip == null || text == null || text.isDisposed()) {
			return;
		}
		long aT = clip.getMicrosecondPosition()/1000;
		int aCue = -1;
		for(int i = 0;i < cues.size();i++) {
			if(cues.elementAt(i)[0] <= aT) {
				aCue = i;
			}
			else {
				break;
			}
		}
		if(aCue == cueShown) {
			return;
		}
		cueShown = aCue;
		text.setStyleRanges(new StyleRange[0]);
		if(aCue < 0) {
			text.setTopIndex(0);
			return;
		}
		long[] aC = cues.elementAt(aCue);
		int aFrom = (int)aC[2];
		int aTo = Math.min((int)aC[3], text.getCharCount());
		if(aTo <= aFrom) {
			return;
		}
		StyleRange aR = new StyleRange(aFrom, aTo-aFrom, tde.gui.colorsSwt.BLACK, tde.gui.colorsSwt.PINK_L86);
		aR.fontStyle = SWT.BOLD;
		text.setStyleRange(aR);
		//Its first line shown, with two lines of the previous cue above it
		int aLine = text.getLineAtOffset(aFrom);
		int aVisible = Math.max(1, text.getClientArea().height/Math.max(1, text.getLineHeight()));
		int aTop = text.getTopIndex();
		if(aLine < aTop || text.getLineAtOffset(aTo) >= aTop+aVisible) {
			text.setTopIndex(Math.max(0, aLine-2));
		}
	}

	/**
	 * The subtitles of aSrt on each clip: in its Subtitles processor (the first one), else in a new one
	 */
	void apply(Vector<com.cubaix.TDenlive.medias.Clip> aClips,File aSrt) {
		int aN = 0;
		try {
			for(com.cubaix.TDenlive.medias.Clip aC : aClips) {
				Subtitles aS = null;
				for(Processor aP : aC.getProcessors()) {
					if(aP instanceof Subtitles) {
						aS = (Subtitles)aP;
						break;
					}
				}
				if(aS == null) {
					Processor aP = aC.addProcessor("Subtitles");
					aS = aP instanceof Subtitles ? (Subtitles)aP : null;
				}
				if(aS != null && aS.loadFile(aSrt.getPath()) > 0) {
					aN++;
				}
			}
		}
		catch(Throwable t) {
			t.printStackTrace(System.err);
			AiClipGUI.message(tde, SWT.ICON_ERROR, lng("Subtitles.Error").replace("%s", aSrt.getName())+"\n"+t.getMessage());
		}
		used = true;
		discardB.setEnabled(false);
		com.cubaix.TDenlive.medias.Clip aMain = tde.selected.clips.isEmpty() ? null : tde.selected.clips.lastElement();
		if(aMain != null && aClips.contains(aMain) && tde.gui.processorGUI != null) {
			tde.gui.processorGUI.setProcessors(aMain.getProcessors());
		}
		tde.gui.monitorGUI.redraw();
		ai.working(statusL, false);
		statusL.setText(lng("AI.SubsApplied").replace("%N%", ""+aN).replace("%FILE%", aSrt.getName()));
		AiClipGUI.fit(shell);
	}

	/**
	 * A file into the media list (a sound, or a video), in a thread
	 */
	void load(String aPath,boolean aSound,Button aButton) {
		statusL.setText(lng(aSound ? "AI.LoadingSound" : "AI.Loading"));
		AiClipGUI.fit(statusL);
		ai.working(statusL, true);
		new Thread(() -> {
			String aErr = null;
			try {
				if(aSound) {
					tde.mediaList.loadAudio(aPath);
				}
				else {
					tde.mediaList.loadImageLR(aPath, aPath, TDConfig.PROCESSING_MODE_RENDER, 0);
				}
			}
			catch(Throwable t) {
				t.printStackTrace(System.err);
				aErr = t.getMessage() == null ? t.toString() : t.getMessage();
			}
			final String aE = aErr;
			tde.gui.display.asyncExec(() -> {
				tde.gui.mediaListGUI.redraw();
				if(shell.isDisposed()) {
					return;
				}
				if(aE != null) {
					aButton.setEnabled(true);
					statusL.setText(" ");
					AiClipGUI.fit(statusL);
					ai.working(statusL, false);
					AiClipGUI.message(tde, SWT.ICON_ERROR, aE);
					return;
				}
				used = true;
				discardB.setEnabled(false);
				aButton.setText(lng("AI.ResultAdded"));
				statusL.setText(lng("AI.ResultAdded"));
				AiClipGUI.fit(statusL);
				ai.working(statusL, false);
				AiClipGUI.fit(shell);
			});
		}).start();
	}

	void close() {
		stop();
		if(!shell.isDisposed()) {
			shell.dispose();
		}
	}

	void discard() {
		stop();
		if(used) {
			return;
		}
		for(File aF : result.files) {
			aF.delete();
		}
		if(result.voice != null) {
			result.voice.delete();
		}
	}

	void stop() {
		closed = true;
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
	}

	void startSound() {
		try {
			AudioInputStream aAIS = AudioSystem.getAudioInputStream(result.voice);
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
			playB.setEnabled(false);
		}
		position.addListener(SWT.MouseDown, e -> {
			if(clip != null && position.getSize().x > 0) {
				clip.setFramePosition((int)((long)clip.getFrameLength()*e.x/position.getSize().x));
			}
		});
		tick();
	}

	void tick() {
		if(closed || shell.isDisposed()) {
			return;
		}
		if(clip != null && clip.getFrameLength() > 0) {
			position.setSelection((int)(1000L*clip.getFramePosition()/clip.getFrameLength()));
			syncText();
		}
		tde.gui.display.timerExec(100, this::tick);
	}

	void togglePlay() {
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
		updatePlay();
	}

	void updatePlay() {
		if(playB == null || playB.isDisposed()) {
			return;
		}
		boolean aOn = clip != null && clip.isRunning();
		playB.setText(lng(aOn ? "AI.ResultPause" : "AI.ResultPlay"));
		Image aI = tde.gui.imgsSwt.getIcon(aOn ? "pause.png" : "play.gif");
		if(aI != null) {
			playB.setImage(aI);
		}
		playB.getParent().layout(true, true);
	}
}
