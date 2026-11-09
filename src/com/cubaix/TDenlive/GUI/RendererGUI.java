package com.cubaix.TDenlive.GUI;

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
	
	String pathOut = null;
	int outputRes = 720;
	double outputFPS = 30;
	boolean playing = false;
	boolean startAtTimePos = false;
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
		for(int i = 1;i < 10000;i++) {
			pathOut = tde.renderDir+File.separatorChar+aPrefix+aNF.format(i)+"_P.mp4";
			if(new File(pathOut).exists()) {
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
		shell.setSize(600, 600+THUMB_H);
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

		playing = true;
		doneButton.setEnabled(false);
		renderer = null;//Not the one of a previous rendering
		lastL = lastR = thumbL = null;
		progress = null;
		if(tde.gui.monitorGUI != null) {
			tde.gui.monitorGUI.setMedia(null);
		}
		int aHeight = outputRes;
		int aWidth = (int)(tde.config.outRatio * outputRes);
		timeStartMS = System.currentTimeMillis();
		
		startTimePos = startAtTimePos?tde.timeLineStack.getTimePosMS():0;
		endTimePos = tde.timeLineStack.getMaxTimePosMS();
		tde.gui.setWaitCursor(true,shell);
		status.setText(tde.gui.lngs.get("Renderer.MixingAudio"));
		Thread aTh = new Thread(new Runnable() {
			@Override
			public void run() {
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
				renderer = new Renderer(tde,aWidth,aHeight,outputFPS,ffmpegVideoOpts,pathOut
						,audioWav == null ? null : audioWav.getAbsolutePath());
				timeStartMS = System.currentTimeMillis();
				countFrames = 0;
				tde.timeLineStack.setTimePosMS(startTimePos);
				tde.timeLineStack.setRequestedTimePosMS(startTimePos);
				while(playing) {
					tde.timeLineStack.reBuild(TDConfig.PROCESSING_MODE_RENDER, tde.timeLineStack.getTimePosMS());
					BufferedImage aL = tde.timeLineStack.getFinalLeft(TDConfig.PROCESSING_MODE_RENDER, tde.timeLineStack.getTimePosMS());
					BufferedImage aR = tde.timeLineStack.getFinalRight(TDConfig.PROCESSING_MODE_RENDER, tde.timeLineStack.getTimePosMS());
					renderer.addPair(aL, aR);
					lastL = aL;
					lastR = aR;
					tde.gui.display.asyncExec(new Runnable() {
						@Override
						public void run() {
							updateStatus();
							tde.gui.timeLinesGUI.redraw();
						}
					});
					countFrames++;
					tde.timeLineStack.setTimePosMS(startTimePos+(int)(1000*countFrames/outputFPS));
					if(tde.timeLineStack.getTimePosMS() >= endTimePos) {
						stop();
					}
				}
				tde.timeLineStack.setTimePosMS(startTimePos);
			}
		});
		aTh.start();
	}
	
	void updateStatus() {
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
		if(renderer != null) {
			renderer.stop();
		}
		if(audioWav != null) {
			audioWav.delete();
			audioWav = null;
		}
		//Page to view the video with the stereo player (not for image sequences)
		File aHtml = null;
		if(renderer != null && FfmpegEncoder.canHaveAudio(pathOut) && new File(pathOut).exists()) {
			aHtml = HtmlViewer.writeVideoPage(tde, pathOut);
		}
		final File aHtmlF = aHtml;
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
				tde.gui.timeLinesGUI.redraw();
				if(tde.gui.monitorGUI != null) {
					tde.gui.monitorGUI.setMedia(tde.timeLineStack);
				}
				tde.gui.setWaitCursor(false,shell);
				MessageBox messageBox = new MessageBox(shell,SWT.ICON_INFORMATION|SWT.OK);
			    messageBox.setMessage(aHtmlF == null ? pathOut : pathOut+"\n"+aHtmlF.getAbsolutePath());
			    messageBox.open();
			    if(aHtmlF != null) {
			    	BrowserControl.openFile(aHtmlF);
			    }
			}
		});
	}
}
