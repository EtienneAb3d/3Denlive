package com.cubaix.TDenlive.GUI;

import com.cubaix.TDenlive.processors.TwoPassRender;

import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.io.File;
import java.text.NumberFormat;

import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.CLabel;
import org.eclipse.swt.events.KeyEvent;
import org.eclipse.swt.events.KeyListener;
import org.eclipse.swt.events.ModifyEvent;
import org.eclipse.swt.events.ModifyListener;
import org.eclipse.swt.events.SelectionEvent;
import org.eclipse.swt.events.SelectionListener;
import org.eclipse.swt.graphics.GC;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.graphics.ImageData;
import org.eclipse.swt.graphics.PaletteData;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.graphics.Rectangle;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Canvas;
import org.eclipse.swt.widgets.Combo;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.MessageBox;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Text;

import com.cubaix.TDenlive.TDConfig;
import com.cubaix.TDenlive.TDenlive;
import com.cubaix.TDenlive.audio.AudioMixer;
import com.cubaix.TDenlive.ffmpeg.FfmpegEncoder;
import com.cubaix.TDenlive.processors.Renderer;
import com.cubaix.TDenlive.utils.BrowserControl;
import com.cubaix.TDenlive.utils.HtmlViewer;
import com.cubaix.TDenlive.utils.StringUtils;

public class RendererGUI {
	TDenlive tde = null;
	Shell shell = null;
	
	//Output path without the format suffix: FfmpegEncoder.FORMAT_SUFFIXES are added before its extension
	String pathOut = null;
	//Formats to generate (parallel by default)
	boolean[] formatsOn = new boolean[FfmpegEncoder.FORMAT_SUFFIXES.length];
	//Anaglyphs in H.264 4:4:4 (separate encoder), enabled when an anaglyph is checked
	boolean anaglyph444 = false;
	Button anaglyph444CB = null;
	//Files of the rendering in progress, with their formats
	String[] pathsOut = null;
	int[] formatsOut = null;
	int outputRes = 720;
	double outputFPS = 30;
	boolean playing = false;
	boolean startAtTimePos = false;
	//Only the selected time range of the time lines (TimeLineStack.rangeStartMS / rangeEndMS)
	boolean onlyTimeRange = false;
	long startTimePos = 0;
	long endTimePos = 0;
	long timeStartMS = 0;
	CLabel status = null;
	//Greyed while rendering
	Button doneButton = null;
	int countFrames = 0;
	String ffmpegVideoOpts = "";

	Renderer renderer = null;
	File audioWav = null;

	//Anaglyph thumbnail of the last produced pair, refreshed with the counters, the progress over it
	static final int THUMB_H = 200;
	Canvas thumbCanvas = null;
	Image thumbSwt = null;
	volatile BufferedImage lastL = null;
	volatile BufferedImage lastR = null;
	BufferedImage thumbL = null;//Pair of the current thumbnail
	String progress = null;
	
	public RendererGUI(TDenlive aTDe) {
		tde = aTDe;
		
		NumberFormat aNF = NumberFormat.getInstance();
		aNF.setMinimumIntegerDigits(4);
		aNF.setGroupingUsed(false);
		//Named after the project once saved, 3De_ before
		String aPrefix = tde.projectName == null ? "3De_" : tde.projectName.replaceAll("(?i)([.]3de)+$", "")+"_";
		formatsOn[FfmpegEncoder.FORMAT_P] = true;
		for(int i = 1;i < 10000;i++) {
			pathOut = tde.renderDir+File.separatorChar+aPrefix+aNF.format(i)+".mp4";
			boolean aTaken = false;
			for(String aSuffix : FfmpegEncoder.FORMAT_SUFFIXES) {
				aTaken |= new File(withSuffix(pathOut, aSuffix)).exists();
			}
			if(aTaken) {
				continue;
			}
			break;
		}

		createContents();
		shell.open();
	}
	
	void createContents() {
		shell = new Shell(tde.gui.display);
		shell.setText("3Denlive");
		shell.setLayout(new GridLayout(1, false));
		shell.setSize(600, 760+THUMB_H);
		tde.gui.applyColorFont(shell);
		shell.setImage(tde.gui.imgsSwt.getIcon("3Denlive32.png"));
		
		//Work res
		Composite aWorkRC = new Composite(shell,SWT.NONE);
		GridData aWCGD = new GridData(GridData.FILL_HORIZONTAL);
		aWCGD.heightHint = tde.gui.h(25);
		aWorkRC.setLayoutData(aWCGD);
		tde.gui.applyColorFont(aWorkRC);
		GridLayout aWCGL = new GridLayout(3, false);
		tde.gui.applyNoMargin(aWCGL);
		aWorkRC.setLayout(aWCGL);

		CLabel aWorkRT = new CLabel(aWorkRC, SWT.NONE);
		GridData aWTGD = new GridData();
		aWTGD.heightHint = tde.gui.h(20);
		aWorkRT.setLayoutData(aWTGD);
		aWorkRT.setText(tde.gui.lngs.get("Renderer.ProcessingRes"));
		tde.gui.applyColorFont(aWorkRT);

		Composite aSpaceW = new Composite(aWorkRC, SWT.SEPARATOR|SWT.FILL);
		tde.gui.applyColorFont(aSpaceW);
		GridData aSpaceWGD = new GridData(GridData.FILL_HORIZONTAL);
		aSpaceWGD.heightHint = tde.gui.h(25);
		aSpaceW.setLayoutData(aSpaceWGD);

		Combo aProcessingRes = new Combo(aWorkRC, SWT.DROP_DOWN);//|SWT.READ_ONLY);
		GridData aResGD = new GridData(GridData.HORIZONTAL_ALIGN_CENTER);
		aResGD.widthHint = 80;
		aResGD.heightHint = tde.gui.h(20);
		aProcessingRes.setLayoutData(aResGD);
		tde.gui.applyColorFont(aProcessingRes);
		tde.gui.tip(aProcessingRes, "RenderProcessingRes");
		aProcessingRes.setItems(TDConfig.RESNAMES);
		aResGD.widthHint = tde.gui.comboW(aProcessingRes, 80);
		aProcessingRes.addSelectionListener(new SelectionListener() {
			@Override
			public void widgetSelected(SelectionEvent arg0) {
				tde.config.processingResNames[TDConfig.PROCESSING_MODE_RENDER] = TDConfig.RESSHORTNAMES[aProcessingRes.getSelectionIndex()];
				tde.config.processingResValues[TDConfig.PROCESSING_MODE_RENDER] = TDConfig.RESVALUES[aProcessingRes.getSelectionIndex()];
				aProcessingRes.setText(TDConfig.RESSHORTNAMES[aProcessingRes.getSelectionIndex()]);//Doesn't work if READ_ONLY !
				tde.config.saveGeneral(tde.configPath);
			}
			@Override
			public void widgetDefaultSelected(SelectionEvent arg0) {
				tde.config.processingResNames[TDConfig.PROCESSING_MODE_RENDER] = TDConfig.RESSHORTNAMES[aProcessingRes.getSelectionIndex()];
				tde.config.processingResValues[TDConfig.PROCESSING_MODE_RENDER] = TDConfig.RESVALUES[aProcessingRes.getSelectionIndex()];
				aProcessingRes.setText(TDConfig.RESSHORTNAMES[aProcessingRes.getSelectionIndex()]);//Doesn't work if READ_ONLY !
				tde.config.saveGeneral(tde.configPath);
			}
		});
		aProcessingRes.setText(tde.config.processingResNames[TDConfig.PROCESSING_MODE_RENDER]);
		
		//Out res
		Composite aOutRC = new Composite(shell,SWT.NONE);
		GridData aORCGD = new GridData(GridData.FILL_HORIZONTAL);
		aORCGD.heightHint = tde.gui.h(25);
		aOutRC.setLayoutData(aORCGD);
		tde.gui.applyColorFont(aOutRC);
		GridLayout aORCGL = new GridLayout(3, false);
		tde.gui.applyNoMargin(aORCGL);
		aOutRC.setLayout(aORCGL);

		CLabel aOutRT = new CLabel(aOutRC, SWT.NONE);
		GridData aORTGD = new GridData();
		aORTGD.heightHint = tde.gui.h(20);
		aOutRT.setLayoutData(aORTGD);
		aOutRT.setText(tde.gui.lngs.get("Renderer.OutputRes"));
		tde.gui.applyColorFont(aOutRT);

		Composite aSpaceORT = new Composite(aOutRC, SWT.SEPARATOR|SWT.FILL);
		tde.gui.applyColorFont(aSpaceORT);
		GridData aSORTGD = new GridData(GridData.FILL_HORIZONTAL);
		aSORTGD.heightHint = tde.gui.h(25);
		aSpaceORT.setLayoutData(aSORTGD);

		Combo aOutRes = new Combo(aOutRC, SWT.DROP_DOWN);//|SWT.READ_ONLY);
		GridData aORGD = new GridData(GridData.HORIZONTAL_ALIGN_CENTER);
		aORGD.widthHint = 80;
		aORGD.heightHint = tde.gui.h(20);
		aOutRes.setLayoutData(aORGD);
		tde.gui.applyColorFont(aOutRes);
		tde.gui.tip(aOutRes, "RenderOutputRes");
		aOutRes.setItems(TDConfig.RESNAMES);
		aORGD.widthHint = tde.gui.comboW(aOutRes, 80);
		aOutRes.addSelectionListener(new SelectionListener() {
			@Override
			public void widgetSelected(SelectionEvent arg0) {
//				finalRes = TDConfig.RESSHORTNAMES[aOutRes.getSelectionIndex()];
				outputRes = TDConfig.RESVALUES[aOutRes.getSelectionIndex()];
				aOutRes.setText(TDConfig.RESSHORTNAMES[aOutRes.getSelectionIndex()]);//Doesn't work if READ_ONLY !
				tde.config.saveGeneral(tde.configPath);
			}
			@Override
			public void widgetDefaultSelected(SelectionEvent arg0) {
//				finalRes = TDConfig.RESSHORTNAMES[aOutRes.getSelectionIndex()];
				outputRes = TDConfig.RESVALUES[aOutRes.getSelectionIndex()];
				aOutRes.setText(TDConfig.RESSHORTNAMES[aOutRes.getSelectionIndex()]);//Doesn't work if READ_ONLY !
				tde.config.saveGeneral(tde.configPath);
			}
		});
		outputRes = TDConfig.RESVALUES[tde.config.renderedDefaultRes];
		aOutRes.setText(TDConfig.RESSHORTNAMES[tde.config.renderedDefaultRes]);
		
		//Out FPS
		Composite aOutFPSC = new Composite(shell,SWT.NONE);
		GridData aOFPSCGD = new GridData(GridData.FILL_HORIZONTAL);
		aOFPSCGD.heightHint = tde.gui.h(25);
		aOutFPSC.setLayoutData(aOFPSCGD);
		tde.gui.applyColorFont(aOutFPSC);
		GridLayout aOFPSCGL = new GridLayout(3, false);
		tde.gui.applyNoMargin(aOFPSCGL);
		aOutFPSC.setLayout(aOFPSCGL);

		CLabel aOutFPST = new CLabel(aOutFPSC, SWT.NONE);
		GridData aOFPSTGD = new GridData();
		aOFPSTGD.heightHint = tde.gui.h(20);
		aOutFPST.setLayoutData(aOFPSTGD);
		aOutFPST.setText(tde.gui.lngs.get("Renderer.OutputFPS"));
		tde.gui.applyColorFont(aOutFPST);

		Composite aSpaceOFPTT = new Composite(aOutFPSC, SWT.SEPARATOR|SWT.FILL);
		tde.gui.applyColorFont(aSpaceOFPTT);
		GridData aSOFPSGD = new GridData(GridData.FILL_HORIZONTAL);
		aSOFPSGD.heightHint = tde.gui.h(25);
		aSpaceOFPTT.setLayoutData(aSOFPSGD);

		Combo aOutFPS = new Combo(aOutFPSC, SWT.DROP_DOWN);//|SWT.READ_ONLY);
		GridData aOFPSGD = new GridData(GridData.HORIZONTAL_ALIGN_CENTER);
		aOFPSGD.widthHint = 80;
		aOFPSGD.heightHint = tde.gui.h(20);
		aOutFPS.setLayoutData(aOFPSGD);
		tde.gui.applyColorFont(aOutFPS);
		tde.gui.tip(aOutFPS, "RenderFPS");
		aOutFPS.setItems(TDConfig.FPSLIST);
		aOutFPS.addModifyListener(new ModifyListener() {
			@Override
			public void modifyText(ModifyEvent arg0) {
				String aValue = aOutFPS.getText().trim();
				if(aValue.matches("[0-9]+([.,][0-9]+)?")) {
					outputFPS = Double.parseDouble(aValue);
				}
			}
		});
		aOutFPS.setText(""+outputFPS);

		final CLabel aFVOL = new CLabel(shell,SWT.NONE);
		aFVOL.setText(tde.gui.lngs.get("Renderer.FFmpegVideoOpts"));
		tde.gui.applyColorFont(aFVOL);
		GridData aFVOLGD = new GridData(GridData.FILL_HORIZONTAL);
		aFVOLGD.heightHint = tde.gui.h(20);
		aFVOL.setLayoutData(aFVOLGD);
		final CLabel aFVOLEx = new CLabel(shell,SWT.NONE);
		aFVOLEx.setText(tde.gui.lngs.get("Renderer.FFmpegVideoOptsEx"));
		tde.gui.applyColorFont(aFVOLEx);
		GridData aFVOLExGD = new GridData(GridData.FILL_HORIZONTAL);
		aFVOLExGD.heightHint = tde.gui.h(20);
		aFVOLEx.setLayoutData(aFVOLExGD);
		final Text aFVOT = tde.gui.framedText(shell,SWT.SINGLE);
		tde.gui.tip(aFVOT, "RenderFFmpegOpts");
		GridData aFVOTGD = new GridData(GridData.FILL_HORIZONTAL);
		aFVOTGD.heightHint = tde.gui.h(20);
		aFVOT.getParent().setLayoutData(aFVOTGD);
		aFVOT.addKeyListener(new KeyListener() {
			@Override
			public void keyReleased(KeyEvent arg0) {
				ffmpegVideoOpts = aFVOT.getText();
			}
			@Override
			public void keyPressed(KeyEvent arg0) {
			}
		});
		
		final CLabel aOutL = new CLabel(shell,SWT.NONE);
		aOutL.setText(tde.gui.lngs.get("Renderer.OutputFile"));
		tde.gui.applyColorFont(aOutL);
		GridData aOutLGD = new GridData(GridData.FILL_HORIZONTAL);
		aOutLGD.heightHint = tde.gui.h(20);
		aOutL.setLayoutData(aOutLGD);

		final Text aOutT = tde.gui.framedText(shell,SWT.SINGLE);
		tde.gui.tip(aOutT, "RenderOutputFile");
		GridData aOutTGD = new GridData(GridData.FILL_HORIZONTAL);
		aOutTGD.heightHint = tde.gui.h(20);
		aOutT.getParent().setLayoutData(aOutTGD);
		aOutT.addKeyListener(new KeyListener() {
			@Override
			public void keyReleased(KeyEvent arg0) {
				pathOut = aOutT.getText();
			}
			@Override
			public void keyPressed(KeyEvent arg0) {
			}
		});
		aOutT.setText(pathOut);

		//Formats generated in the same pass, 3 per row
		final CLabel aFormatsL = new CLabel(shell,SWT.NONE);
		aFormatsL.setText(tde.gui.lngs.get("Renderer.Formats"));
		tde.gui.applyColorFont(aFormatsL);
		tde.gui.tip(aFormatsL, "RenderFormats");
		GridData aFLGD = new GridData(GridData.FILL_HORIZONTAL);
		aFLGD.heightHint = tde.gui.h(20);
		aFormatsL.setLayoutData(aFLGD);
		Composite aFormatsC = new Composite(shell, SWT.NONE);
		tde.gui.applyColorFont(aFormatsC);
		aFormatsC.setLayoutData(new GridData(GridData.FILL_HORIZONTAL));
		GridLayout aFCGL = new GridLayout(3, true);
		aFCGL.marginHeight = 0;
		aFormatsC.setLayout(aFCGL);
		for(int f = 0;f < FfmpegEncoder.FORMAT_SUFFIXES.length;f++) {
			final int aF = f;
			final Button aFCB = new Button(aFormatsC, SWT.CHECK);
			tde.gui.applyColorFont(aFCB);
			tde.gui.tip(aFCB, "RenderFormats");
			GridData aFCBGD = new GridData(GridData.FILL_HORIZONTAL);
			aFCBGD.heightHint = tde.gui.h(20);
			aFCB.setLayoutData(aFCBGD);
			aFCB.setText(tde.gui.lngs.get(FfmpegEncoder.FORMAT_LABELS[f]));
			aFCB.setSelection(formatsOn[f]);
			aFCB.addListener(SWT.Selection, e -> {
				formatsOn[aF] = aFCB.getSelection();
				updateAnaglyph444();
			});
		}
		anaglyph444CB = new Button(shell, SWT.CHECK);
		tde.gui.applyColorFont(anaglyph444CB);
		tde.gui.tip(anaglyph444CB, "RenderAnaglyph444");
		anaglyph444CB.setLayoutData(new GridData(GridData.FILL_HORIZONTAL));
		anaglyph444CB.setText(tde.gui.lngs.get("Renderer.Anaglyph444"));
		anaglyph444CB.setSelection(anaglyph444);
		anaglyph444CB.addListener(SWT.Selection, e -> anaglyph444 = anaglyph444CB.getSelection());
		//Quality and portability of the result
		final Label aA444Note = new Label(shell, SWT.WRAP);
		tde.gui.applyColorFont(aA444Note);
		aA444Note.setText(tde.gui.lngs.get("Renderer.Anaglyph444Note"));
		GridData aANGD = new GridData(GridData.FILL_HORIZONTAL);
		aANGD.widthHint = 560;
		aANGD.horizontalIndent = 20;
		aA444Note.setLayoutData(aANGD);
		updateAnaglyph444();
		
		final Button aStartPosCB = new Button(shell, SWT.CHECK);
		tde.gui.applyColorFont(aStartPosCB);
		tde.gui.tip(aStartPosCB, "RenderStartAtTimePos");
		GridData aSPCBGD = new GridData(GridData.FILL_HORIZONTAL);
		aSPCBGD.heightHint = tde.gui.h(20);
		aStartPosCB.setLayoutData(aSPCBGD);
		aStartPosCB.setSelection(false);
		aStartPosCB.addSelectionListener(new SelectionListener() {
			@Override
			public void widgetSelected(SelectionEvent arg0) {
				startAtTimePos = aStartPosCB.getSelection();
			}
			@Override
			public void widgetDefaultSelected(SelectionEvent arg0) {
				startAtTimePos = aStartPosCB.getSelection();
			}
		});
		aStartPosCB.setText(tde.gui.lngs.get("Renderer.StartAtTimePos"));

		final Button aRangeCB = new Button(shell, SWT.CHECK);
		tde.gui.applyColorFont(aRangeCB);
		tde.gui.tip(aRangeCB, "RenderOnlyTimeRange");
		GridData aRCBGD = new GridData(GridData.FILL_HORIZONTAL);
		aRCBGD.heightHint = tde.gui.h(20);
		aRangeCB.setLayoutData(aRCBGD);
		boolean aHasRange = tde.timeLineStack.hasTimeRange();
		aRangeCB.setText(tde.gui.lngs.get("Renderer.OnlyTimeRange")
				+(aHasRange ? " ("+StringUtils.time2Clock(tde.timeLineStack.rangeStartMS)
						+" - "+StringUtils.time2Clock(tde.timeLineStack.rangeEndMS)+")" : ""));
		aRangeCB.setEnabled(aHasRange);
		aRangeCB.setSelection(false);
		aRangeCB.addListener(SWT.Selection, e -> {
			onlyTimeRange = aRangeCB.getSelection();
			//The range gives its own start
			aStartPosCB.setEnabled(!onlyTimeRange);
		});
		Composite aStartStop = new Composite(shell, SWT.NONE);
		tde.gui.applyColorFont(aStartStop);
		aStartStop.setLayout(new GridLayout(3, false));
		
		Button aProcess = new Button(aStartStop, SWT.PUSH);
		GridData aOGD = new GridData();
		aOGD.heightHint = tde.gui.h(20);
		aProcess.setLayoutData(aOGD);
		aProcess.setText(tde.gui.lngs.get("Renderer.Process"));
		aProcess.setImage(tde.gui.imgsSwt.getIcon("play.gif"));
		tde.gui.applyColorFont(aProcess);
		tde.gui.tip(aProcess, "RenderProcess");
		aProcess.setAlignment(SWT.CENTER);
		aProcess.addSelectionListener(new SelectionListener() {
			@Override
			public void widgetSelected(SelectionEvent arg0) {
				start();
			}
			@Override
			public void widgetDefaultSelected(SelectionEvent arg0) {
				start();
			}
		});
		
		Button aStop = new Button(aStartStop, SWT.PUSH);
		GridData aSGD = new GridData();
		aSGD.heightHint = tde.gui.h(20);
		aStop.setLayoutData(aSGD);
		aStop.setText(tde.gui.lngs.get("Renderer.Stop"));
		aStop.setImage(tde.gui.imgsSwt.getIcon("stop.gif"));
		tde.gui.applyColorFont(aStop);
		tde.gui.tip(aStop, "RenderStop");
		aStop.setAlignment(SWT.CENTER);
		aStop.addSelectionListener(new SelectionListener() {
			@Override
			public void widgetSelected(SelectionEvent arg0) {
				stop();
			}
			@Override
			public void widgetDefaultSelected(SelectionEvent arg0) {
				stop();
			}
		});
		
		Button aOk = new Button(aStartStop, SWT.PUSH);
		GridData aOkGD = new GridData();
		aOkGD.heightHint = tde.gui.h(20);
		aOk.setLayoutData(aOkGD);
		aOk.setText(tde.gui.lngs.get("Renderer.Done"));
		aOk.setImage(tde.gui.imgsSwt.getIcon("checked.gif"));
		tde.gui.applyColorFont(aOk);
		tde.gui.tip(aOk, "RenderDone");
		aOk.setAlignment(SWT.CENTER);
		doneButton = aOk;
		aOk.addSelectionListener(new SelectionListener() {
			@Override
			public void widgetSelected(SelectionEvent arg0) {
				stop();
				shell.close();
				shell.dispose();
			}
			@Override
			public void widgetDefaultSelected(SelectionEvent arg0) {
				stop();
				shell.close();
				shell.dispose();
			}
		});
		
		status = new CLabel(shell, SWT.NONE);
		tde.gui.applyColorFont(status);
		status.setLayoutData(new GridData(GridData.FILL_HORIZONTAL));

		thumbCanvas = new Canvas(shell, SWT.DOUBLE_BUFFERED);
		tde.gui.applyColorFont(thumbCanvas);
		GridData aThGD = new GridData(GridData.FILL_HORIZONTAL);
		aThGD.heightHint = THUMB_H;
		thumbCanvas.setLayoutData(aThGD);
		thumbCanvas.addListener(SWT.Paint, e -> paintThumb(e.gc));
		shell.addListener(SWT.Dispose, e -> {
			if(thumbSwt != null && !thumbSwt.isDisposed()) {
				thumbSwt.dispose();
			}
			thumbSwt = null;
		});
	}

	void paintThumb(GC aGC) {
		Rectangle aCA = thumbCanvas.getClientArea();
		if(thumbSwt == null || thumbSwt.isDisposed()) {
			return;
		}
		Rectangle aIB = thumbSwt.getBounds();
		int aX = (aCA.width-aIB.width)/2;
		int aY = (aCA.height-aIB.height)/2;
		aGC.drawImage(thumbSwt, aX, aY);
		if(progress == null) {
			return;
		}
		//Progress in the bottom right corner of the image, white on a dark box
		aGC.setFont(tde.gui.fontsSwt.robotoBold);
		Point aTE = aGC.textExtent(progress);
		int aM = 4;
		int aBX = aX+aIB.width-aTE.x-3*aM;
		int aBY = aY+aIB.height-aTE.y-3*aM;
		aGC.setAlpha(170);
		aGC.setBackground(tde.gui.colorsSwt.BLACK);
		aGC.fillRoundRectangle(aBX, aBY, aTE.x+2*aM, aTE.y+2*aM, 6, 6);
		aGC.setAlpha(255);
		aGC.setForeground(tde.gui.colorsSwt.WHITE);
		aGC.drawText(progress, aBX+aM, aBY+aM, true);
	}

	//Simple red/cyan anaglyph of the last pair, fitted in the thumbnail area
	void updateThumb() {
		BufferedImage aL = lastL;
		BufferedImage aR = lastR;
		if(aL == null || aR == null || aL == thumbL || thumbCanvas == null || thumbCanvas.isDisposed()) {
			return;
		}
		Rectangle aCA = thumbCanvas.getClientArea();
		if(aCA.width <= 0 || aCA.height <= 0 || aL.getWidth() <= 0 || aL.getHeight() <= 0) {
			return;
		}
		thumbL = aL;
		int aH = aCA.height;
		int aW = (int)Math.round(aH*aL.getWidth()/(double)aL.getHeight());
		if(aW > aCA.width) {
			aW = aCA.width;
			aH = Math.max(1, (int)Math.round(aW*aL.getHeight()/(double)aL.getWidth()));
		}
		try {
			int[] aPL = scaled(aL, aW, aH);
			int[] aPR = scaled(aR, aW, aH);
			ImageData aID = new ImageData(aW, aH, 32, new PaletteData(0xFF0000, 0xFF00, 0xFF));
			int[] aRow = new int[aW];
			for(int y = 0;y < aH;y++) {
				for(int x = 0;x < aW;x++) {
					int p = y*aW+x;
					aRow[x] = (aPL[p] & 0xFF0000) | (aPR[p] & 0x00FFFF);
				}
				aID.setPixels(0, y, aW, aRow, 0);
			}
			Image aOld = thumbSwt;
			thumbSwt = new Image(tde.gui.display, aID);
			if(aOld != null && !aOld.isDisposed()) {
				aOld.dispose();
			}
		}
		catch(Throwable t) {
			t.printStackTrace(System.err);
		}
	}

	static int[] scaled(BufferedImage aBI, int aW, int aH) {
		BufferedImage aS = new BufferedImage(aW, aH, BufferedImage.TYPE_INT_RGB);
		Graphics2D aG = aS.createGraphics();
		aG.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
		aG.drawImage(aBI, 0, 0, aW, aH, null);
		aG.dispose();
		return ((DataBufferInt)aS.getRaster().getDataBuffer()).getData();
	}
	
	void start() {
		if(playing) {
			//Not twice
			return;
		}
		//One file per format checked
		int aNbF = 0;
		for(boolean aOn : formatsOn) {
			aNbF += aOn ? 1 : 0;
		}
		if(aNbF <= 0) {
			MessageBox aMB = new MessageBox(shell,SWT.ICON_WARNING|SWT.OK);
			aMB.setMessage(tde.gui.lngs.get("Renderer.NoFormat"));
			aMB.open();
			return;
		}
		pathsOut = new String[aNbF];
		formatsOut = new int[aNbF];
		for(int f = 0, o = 0;f < formatsOn.length;f++) {
			if(formatsOn[f]) {
				formatsOut[o] = f;
				pathsOut[o++] = withSuffix(pathOut, FfmpegEncoder.FORMAT_SUFFIXES[f]);
			}
		}
		final String[] aPathsOut = pathsOut;
		final int[] aFormatsOut = formatsOut;

		playing = true;
		doneButton.setEnabled(false);
		renderer = null;//Not the one of a previous rendering
		lastL = lastR = thumbL = null;
		progress = null;
		if(tde.gui.monitorGUI != null) {
			tde.gui.monitorGUI.setMedia(null);
			//The images produced are shown in the monitor
			tde.gui.monitorGUI.setRendering(true);
		}
		int aHeight = outputRes;
		int aWidth = (int)(tde.config.outRatio * outputRes);
		timeStartMS = System.currentTimeMillis();
		
		startTimePos = startAtTimePos?tde.timeLineStack.getTimePosMS():0;
		endTimePos = tde.timeLineStack.getMaxTimePosMS();
		if(onlyTimeRange && tde.timeLineStack.hasTimeRange()) {
			startTimePos = tde.timeLineStack.rangeStartMS;
			endTimePos = Math.min(endTimePos, tde.timeLineStack.rangeEndMS);
		}
		tde.gui.setWaitCursor(true,shell);
		status.setText(tde.gui.lngs.get("Renderer.MixingAudio"));
		Thread aTh = new Thread(new Runnable() {
			@Override
			public void run() {
				//No precomputation of the AI hole filling meanwhile: the image it computes, if any, ends first
				com.cubaix.TDenlive.processors.Inpainting.Precomputer.rendering = true;
				if(tde.gui.monitorGUI != null) {
					synchronized(tde.gui.monitorGUI) {
					}
				}
				//Sound track first, mixed into a temporary WAV given to ffmpeg with the images
				audioWav = null;
				if(FfmpegEncoder.canHaveAudio(pathOut)) {
					try {
						File aWav = File.createTempFile("3De_audio_", ".wav");
						aWav.deleteOnExit();
						if(new AudioMixer(tde).renderWav(startTimePos, endTimePos, aWav)) {
							audioWav = aWav;
						}
						else {
							aWav.delete();
						}
					}
					catch(Throwable t) {
						t.printStackTrace(System.err);
					}
				}
				if(!playing) {
					//Stopped while mixing
					if(audioWav != null) {
						audioWav.delete();
						audioWav = null;
					}
					return;
				}
				renderer = new Renderer(tde,aWidth,aHeight,outputFPS,ffmpegVideoOpts,aPathsOut,aFormatsOut
						,audioWav == null ? null : audioWav.getAbsolutePath(), anaglyph444);
				timeStartMS = System.currentTimeMillis();
				countFrames = 0;
				keyPass = null;
				//Keyframes at TwoPassRender.KEY_FPS first, then the output at its rate (processors.TwoPassRender)
				TwoPassRender.run(tde, renderer, startTimePos, endTimePos, outputFPS, () -> playing
						, (aPass, aIndex, aTotal, aTimeMS, aL, aR, aKey) -> {
					lastL = aL;
					lastR = aR;
					if(aPass == 1) {
						keyPass = new int[] {aIndex+1, aTotal};
					}
					else if(keyPass != null) {
						//Output pass: its speed measured from its start
						keyPass = null;
						timeStartMS = System.currentTimeMillis();
					}
					if(tde.gui.monitorGUI != null) {
						tde.gui.monitorGUI.showRendered(aL, aR);
					}
					//The time cursor (time lines and ruler of the monitor) on the image just produced
					tde.gui.display.asyncExec(new Runnable() {
						@Override
						public void run() {
							updateStatus();
							if(playing) {
								tde.timeLineStack.setRequestedTimePosMS(aTimeMS);
							}
							tde.gui.timeLinesGUI.redraw();
							if(tde.gui.monitorRulerGUI != null) {
								tde.gui.monitorRulerGUI.redraw();
							}
						}
					});
					if(aPass == 2) {
						countFrames++;
					}
				});
				//End of the rendering (nothing when stopped by the user meanwhile)
				stop();
				tde.timeLineStack.setTimePosMS(startTimePos);
			}
		});
		aTh.start();
	}
	
	/**
	 * Opens the pages in the browser, one after the other (a browser launched by the first one gets the next ones
	 * as tabs), the most suited last so that its tab is in front.
	 */
	static void openPages(java.util.List<File> aHtmls) {
		if(aHtmls.isEmpty()) {
			return;
		}
		Thread aTh = new Thread(() -> {
			for(int i = aHtmls.size()-1;i >= 0;i--) {
				BrowserControl.openFile(aHtmls.get(i));
				try {
					Thread.sleep(i == aHtmls.size()-1 ? 1500 : 400);
				}
				catch(Throwable t) {
					t.printStackTrace(System.err);
				}
			}
		}, "3De-OpenPages");
		aTh.setDaemon(true);
		aTh.start();
	}

		void updateAnaglyph444() {
		if(anaglyph444CB == null || anaglyph444CB.isDisposed()) {
			return;
		}
		boolean aAna = false;
		for(int f = 0;f < formatsOn.length;f++) {
			aAna |= formatsOn[f] && FfmpegEncoder.isAnaglyph(f);
		}
		anaglyph444CB.setEnabled(aAna);
	}

	/**
	 * aPath with the suffix of a format before its extension (".../3De_0001.mp4" -> ".../3De_0001_P.mp4"); a
	 * format suffix already at the end of the name is replaced, not doubled.
	 */
	static String withSuffix(String aPath, String aSuffix) {
		int aSep = Math.max(aPath.lastIndexOf('/'), aPath.lastIndexOf(File.separatorChar));
		int aDot = aPath.lastIndexOf('.');
		if(aDot <= aSep) {
			aDot = aPath.length();
		}
		String aStem = aPath.substring(0, aDot);
		for(String aS : FfmpegEncoder.FORMAT_SUFFIXES) {
			if(aStem.length() > aSep+1+aS.length() && aStem.toLowerCase().endsWith(aS.toLowerCase())) {
				aStem = aStem.substring(0, aStem.length()-aS.length());
				break;
			}
		}
		return aStem+aSuffix+aPath.substring(aDot);
	}

	/** First image of the video of a format, from the first parallel image (left view | right view) */
	static BufferedImage previewFor(int aFormat, BufferedImage aP) {
		if(aP == null) {
			return null;
		}
		int aW = aP.getWidth()/2, aH = aP.getHeight();
		BufferedImage aOut = new BufferedImage(aFormat == FfmpegEncoder.FORMAT_P || aFormat == FfmpegEncoder.FORMAT_X ? 2*aW : aW
				, aH, BufferedImage.TYPE_INT_RGB);
		Graphics2D aG = aOut.createGraphics();
		switch(FfmpegEncoder.FORMAT_SUFFIXES[aFormat]) {
		case "_P":
			aG.drawImage(aP, 0, 0, null);
			break;
		case "_X"://Views swapped
			aG.drawImage(aP, 0, 0, aW, aH, aW, 0, 2*aW, aH, null);
			aG.drawImage(aP, aW, 0, 2*aW, aH, 0, 0, aW, aH, null);
			break;
		case "_Left":
			aG.drawImage(aP, 0, 0, aW, aH, 0, 0, aW, aH, null);
			break;
		case "_Right":
			aG.drawImage(aP, 0, 0, aW, aH, aW, 0, 2*aW, aH, null);
			break;
		default://Anaglyphs, as the stereo3d filter of ffmpeg (arcc, arcd)
			boolean aDubois = FfmpegEncoder.FORMAT_SUFFIXES[aFormat].equals("_Dubois");
			for(int y = 0;y < aH;y++) {
				for(int x = 0;x < aW;x++) {
					int aL = aP.getRGB(x, y), aR = aP.getRGB(x+aW, y);
					int lr = (aL>>16)&0xFF, lg = (aL>>8)&0xFF, lb = aL&0xFF;
					int rr = (aR>>16)&0xFF, rg = (aR>>8)&0xFF, rb = aR&0xFF;
					int r, g, b;
					if(aDubois) {
						r = clamp(0.456*lr+0.500*lg+0.176*lb-0.043*rr-0.088*rg-0.002*rb);
						g = clamp(-0.040*lr-0.038*lg-0.016*lb+0.378*rr+0.734*rg-0.018*rb);
						b = clamp(-0.015*lr-0.021*lg-0.005*lb-0.072*rr-0.113*rg+1.226*rb);
					}
					else {
						r = lr; g = rg; b = rb;
					}
					aOut.setRGB(x, y, (r<<16)|(g<<8)|b);
				}
			}
		}
		aG.dispose();
		return aOut;
	}

	static int clamp(double aV) {
		return (int)Math.max(0, Math.min(255, Math.round(aV)));
	}

	//Pass of the keyframes in progress: {done, total}, null in the output pass
	volatile int[] keyPass = null;

	void updateStatus() {
		int[] aKP = keyPass;
		if(aKP != null) {
			status.setText(tde.gui.lngs.get("Renderer.KeyPass").replace("%FPS%", TwoPassRender.keyFpsText()).replace("%DONE%", ""+aKP[0]).replace("%TOTAL%", ""+aKP[1]));
			progress = (aKP[1] <= 0 ? 0 : 100*aKP[0]/aKP[1])+"% ("+TwoPassRender.keyFpsText()+" fps)";
			updateThumb();
			if(thumbCanvas != null && !thumbCanvas.isDisposed()) {
				thumbCanvas.redraw();
			}
			return;
		}
		NumberFormat aNF = NumberFormat.getInstance();
		aNF.setMinimumFractionDigits(3);
		aNF.setMaximumFractionDigits(3);
		aNF.setMinimumIntegerDigits(3);
		double aFps = countFrames/((System.currentTimeMillis()-timeStartMS)/1000.0);
		if(aFps <= 0) {
			//??
			aFps = 0.0001;
		}
		//The last frame moves the position up to (or a little past) the end: never beyond it, never a negative time left
		long aTimePos = Math.min(tde.timeLineStack.getTimePosMS(), endTimePos);
		long aTimeLeft = Math.max(0, (long)(outputFPS*(endTimePos-aTimePos)/aFps));
		status.setText(StringUtils.time2Clock(aTimePos)+" "
				+countFrames+" f processed => "
				+aNF.format(aFps)+" fps / "
				+ (renderer == null ? 0 : renderer.encoded)+ " f encoded "
				+ "=> "+StringUtils.time2Clock(aTimeLeft)
				);
		long aDuration = endTimePos-startTimePos;
		int aPercent = aDuration <= 0 ? 100 : (int)Math.max(0, Math.min(100, 100*(aTimePos-startTimePos)/aDuration));
		progress = aPercent+"%";
		updateThumb();
		if(thumbCanvas != null && !thumbCanvas.isDisposed()) {
			thumbCanvas.redraw();
		}
	}
	
	void stop() {
		if(!playing) {
			//Not twice
			return;
		}
		playing = false;
		com.cubaix.TDenlive.processors.Inpainting.Precomputer.rendering = false;
		if(renderer != null) {
			renderer.stop();
		}
		if(audioWav != null) {
			audioWav.delete();
			audioWav = null;
		}
		//One page per video made to view it with the stereo player (not for image sequences), each with its preview
		//(its first frame, in its layout), in the order of preference of the formats (parallel, crossed...)
		java.util.ArrayList<File> aHtmls = new java.util.ArrayList<File>();
		StringBuffer aDone = new StringBuffer();
		if(renderer != null && pathsOut != null) {
			for(String aPath : pathsOut) {
				aDone.append(aPath).append("\n");
			}
			if(FfmpegEncoder.canHaveAudio(pathOut)) {
				for(int aF : FfmpegEncoder.VIEWER_ORDER) {
					for(int o = 0;o < formatsOut.length;o++) {
						if(formatsOut[o] != aF || !new File(pathsOut[o]).exists()) {
							continue;
						}
						BufferedImage aPreview = null;
						try {
							aPreview = previewFor(aF, renderer.firstP);
						}
						catch(Throwable t) {
							t.printStackTrace(System.err);
						}
						File aHtml = HtmlViewer.writeVideoPage(tde, pathsOut[o], FfmpegEncoder.FORMAT_LAYOUTS[aF], aPreview);
						if(aHtml != null) {
							aHtmls.add(aHtml);
							aDone.append(aHtml.getAbsolutePath()).append("\n");
						}
					}
				}
			}
		}
		final String aDoneS = aDone.length() > 0 ? aDone.toString().trim() : pathOut;
		NumberFormat aNF = NumberFormat.getInstance();
		aNF.setMinimumFractionDigits(3);
		aNF.setMaximumFractionDigits(3);
		aNF.setMinimumIntegerDigits(3);
		tde.gui.display.syncExec(new Runnable() {
			@Override
			public void run() {
				updateStatus();
				if(!doneButton.isDisposed()) {
					doneButton.setEnabled(true);
				}
				tde.timeLineStack.setTimePosMS(startTimePos);
				tde.timeLineStack.setRequestedTimePosMS(startTimePos);
				tde.gui.timeLinesGUI.redraw();
				if(tde.gui.monitorGUI != null) {
					tde.gui.monitorGUI.setRendering(false);
					tde.gui.monitorGUI.setMedia(tde.timeLineStack);
				}
				tde.gui.setWaitCursor(false,shell);
				MessageBox messageBox = new MessageBox(shell,SWT.ICON_INFORMATION|SWT.OK);
			    messageBox.setMessage(aDoneS);
			    messageBox.open();
			    openPages(aHtmls);
			}
		});
	}
}
