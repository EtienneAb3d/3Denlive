package com.cubaix.TDenlive.GUI;

import java.io.File;
import java.text.NumberFormat;

import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.CLabel;
import org.eclipse.swt.events.SelectionEvent;
import org.eclipse.swt.events.SelectionListener;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Combo;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.MessageBox;
import org.eclipse.swt.widgets.Shell;

import com.cubaix.TDenlive.TDConfig;
import com.cubaix.TDenlive.TDenlive;
import com.cubaix.TDenlive.processors.Renderer;
import com.cubaix.TDenlive.utils.StringUtils;

public class RendererGUI {
	TDenlive tde = null;
	Shell shell = null;
	
	String pathOut = null;
	int outputRes = 720;
	int outputFPS = 30;
	boolean playing = false;
	boolean startAtTimePos = false;
	long startTimePos = 0;
	long endTimePos = 0;
	long timeStartMS = 0;
	CLabel status = null;
	int countFrames = 0;

	Renderer renderer = null;
	
	public RendererGUI(TDenlive aTDe) {
		tde = aTDe;
		createContents();
		shell.open();
	}
	
	void createContents() {
		shell = new Shell(tde.gui.display);
		shell.setText("3Denlive");
		shell.setLayout(new GridLayout(1, false));
		shell.setSize(600, 600);
		tde.gui.applyColorFont(shell);
		shell.setImage(tde.gui.imgsSwt.getIcon("3Denlive32.png"));
		
		//Work res
		Composite aWorkRC = new Composite(shell,SWT.NONE);
		GridData aWCGD = new GridData(GridData.FILL_HORIZONTAL);
		aWCGD.heightHint = 25;
		aWorkRC.setLayoutData(aWCGD);
		tde.gui.applyColorFont(aWorkRC);
		GridLayout aWCGL = new GridLayout(3, false);
		tde.gui.applyNoMargin(aWCGL);
		aWorkRC.setLayout(aWCGL);

		CLabel aWorkRT = new CLabel(aWorkRC, SWT.NONE);
		GridData aWTGD = new GridData();
		aWTGD.heightHint = 20;
		aWorkRT.setLayoutData(aWTGD);
		aWorkRT.setText(tde.gui.lngs.get("Renderer.ProcessingRes"));
		tde.gui.applyColorFont(aWorkRT);

		Composite aSpaceW = new Composite(aWorkRC, SWT.SEPARATOR|SWT.FILL);
		tde.gui.applyColorFont(aSpaceW);
		GridData aSpaceWGD = new GridData(GridData.FILL_HORIZONTAL);
		aSpaceWGD.heightHint = 25;
		aSpaceW.setLayoutData(aSpaceWGD);

		Combo aProcessingRes = new Combo(aWorkRC, SWT.DROP_DOWN);//|SWT.READ_ONLY);
		GridData aResGD = new GridData(GridData.HORIZONTAL_ALIGN_CENTER);
		aResGD.widthHint = 80;
		aResGD.heightHint = 20;
		aProcessingRes.setLayoutData(aResGD);
		tde.gui.applyColorFont(aProcessingRes);
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
		aORCGD.heightHint = 25;
		aOutRC.setLayoutData(aORCGD);
		tde.gui.applyColorFont(aOutRC);
		GridLayout aORCGL = new GridLayout(3, false);
		tde.gui.applyNoMargin(aORCGL);
		aOutRC.setLayout(aORCGL);

		CLabel aOutRT = new CLabel(aOutRC, SWT.NONE);
		GridData aORTGD = new GridData();
		aORTGD.heightHint = 20;
		aOutRT.setLayoutData(aORTGD);
		aOutRT.setText(tde.gui.lngs.get("Renderer.OutputRes"));
		tde.gui.applyColorFont(aOutRT);

		Composite aSpaceORT = new Composite(aOutRC, SWT.SEPARATOR|SWT.FILL);
		tde.gui.applyColorFont(aSpaceORT);
		GridData aSORTGD = new GridData(GridData.FILL_HORIZONTAL);
		aSORTGD.heightHint = 25;
		aSpaceORT.setLayoutData(aSORTGD);

		Combo aOutRes = new Combo(aOutRC, SWT.DROP_DOWN);//|SWT.READ_ONLY);
		GridData aORGD = new GridData(GridData.HORIZONTAL_ALIGN_CENTER);
		aORGD.widthHint = 80;
		aORGD.heightHint = 20;
		aOutRes.setLayoutData(aORGD);
		tde.gui.applyColorFont(aOutRes);
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
		aOFPSCGD.heightHint = 25;
		aOutFPSC.setLayoutData(aOFPSCGD);
		tde.gui.applyColorFont(aOutFPSC);
		GridLayout aOFPSCGL = new GridLayout(3, false);
		tde.gui.applyNoMargin(aOFPSCGL);
		aOutFPSC.setLayout(aOFPSCGL);

		CLabel aOutFPST = new CLabel(aOutFPSC, SWT.NONE);
		GridData aOFPSTGD = new GridData();
		aOFPSTGD.heightHint = 20;
		aOutFPST.setLayoutData(aOFPSTGD);
		aOutFPST.setText(tde.gui.lngs.get("Renderer.OutputFPS"));
		tde.gui.applyColorFont(aOutFPST);

		Composite aSpaceOFPTT = new Composite(aOutFPSC, SWT.SEPARATOR|SWT.FILL);
		tde.gui.applyColorFont(aSpaceOFPTT);
		GridData aSOFPSGD = new GridData(GridData.FILL_HORIZONTAL);
		aSOFPSGD.heightHint = 25;
		aSpaceOFPTT.setLayoutData(aSOFPSGD);

		Combo aOutFPS = new Combo(aOutFPSC, SWT.DROP_DOWN);//|SWT.READ_ONLY);
		GridData aOFPSGD = new GridData(GridData.HORIZONTAL_ALIGN_CENTER);
		aOFPSGD.widthHint = 80;
		aOFPSGD.heightHint = 20;
		aOutFPS.setLayoutData(aOFPSGD);
		tde.gui.applyColorFont(aOutFPS);
		aOutFPS.setItems(TDConfig.FPSLIST);
		aOutFPS.addSelectionListener(new SelectionListener() {
			@Override
			public void widgetSelected(SelectionEvent arg0) {
				outputFPS = Integer.parseInt(TDConfig.FPSLIST[aOutFPS.getSelectionIndex()]);
			}
			@Override
			public void widgetDefaultSelected(SelectionEvent arg0) {
				outputFPS = Integer.parseInt(TDConfig.FPSLIST[aOutFPS.getSelectionIndex()]);
			}
		});
		aOutFPS.setText(""+outputFPS);

		final Button aStartPosCB = new Button(shell, SWT.CHECK);
		tde.gui.applyColorFont(aStartPosCB);
		GridData aSPCBGD = new GridData(GridData.FILL_HORIZONTAL);
		aSPCBGD.heightHint = 20;
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
		aOGD.heightHint = 20;
		aProcess.setLayoutData(aOGD);
		aProcess.setText(tde.gui.lngs.get("Renderer.Process"));
		aProcess.setImage(tde.gui.imgsSwt.getIcon("play.gif"));
		tde.gui.applyColorFont(aProcess);
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
		aSGD.heightHint = 20;
		aStop.setLayoutData(aSGD);
		aStop.setText(tde.gui.lngs.get("Renderer.Stop"));
		aStop.setImage(tde.gui.imgsSwt.getIcon("stop.gif"));
		tde.gui.applyColorFont(aStop);
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
		aOkGD.heightHint = 20;
		aOk.setLayoutData(aOkGD);
		aOk.setText(tde.gui.lngs.get("Renderer.Done"));
		aOk.setImage(tde.gui.imgsSwt.getIcon("checked.gif"));
		tde.gui.applyColorFont(aOk);
		aOk.setAlignment(SWT.CENTER);
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
	}
	
	void start() {
		if(playing) {
			//Not twice
			return;
		}
		NumberFormat aNF = NumberFormat.getInstance();
		aNF.setMinimumIntegerDigits(4);
		aNF.setGroupingUsed(false);
		for(int i = 1;i < 10000;i++) {
			pathOut = tde.renderDir+File.separatorChar+"3De_"+aNF.format(i)+"_P.mp4";
			if(new File(pathOut).exists()) {
				continue;
			}
			break;
		}

		playing = true;
		if(tde.gui.monitorGUI != null) {
			tde.gui.monitorGUI.setMedia(null);
		}
		int aHeight = outputRes;
		int aWidth = (int)(tde.config.outRatio * outputRes);
		renderer = new Renderer(tde,aWidth,aHeight,outputFPS,pathOut);
		timeStartMS = System.currentTimeMillis();
		
		startTimePos = startAtTimePos?tde.timeLineStack.getTimePosMS():0;
		endTimePos = tde.timeLineStack.getMaxTimePosMS();
		tde.gui.setWaitCursor(true,shell);
		Thread aTh = new Thread(new Runnable() {
			@Override
			public void run() {
				countFrames = 0;
				tde.timeLineStack.setTimePosMS(startTimePos);
				tde.timeLineStack.setRequestedTimePosMS(startTimePos);
				while(playing) {
					tde.timeLineStack.reBuild(TDConfig.PROCESSING_MODE_RENDER, tde.timeLineStack.getTimePosMS());
					renderer.addPair(tde.timeLineStack.getFinalLeft(TDConfig.PROCESSING_MODE_RENDER, tde.timeLineStack.getTimePosMS())
							, tde.timeLineStack.getFinalRight(TDConfig.PROCESSING_MODE_RENDER, tde.timeLineStack.getTimePosMS()));
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
		long aTimeLeft = (int)(outputFPS*(endTimePos-tde.timeLineStack.getTimePosMS())/aFps);
		status.setText(StringUtils.time2Clock(tde.timeLineStack.getTimePosMS())+" "
				+countFrames+" f processed => "
				+aNF.format(aFps)+" fps / "
				+ renderer.encoded+ " f encoded "
				+ "=> "+StringUtils.time2Clock(aTimeLeft)
				);
	}
	
	void stop() {
		if(!playing) {
			//Not twice
			return;
		}
		playing = false;
		renderer.stop();
		NumberFormat aNF = NumberFormat.getInstance();
		aNF.setMinimumFractionDigits(3);
		aNF.setMaximumFractionDigits(3);
		aNF.setMinimumIntegerDigits(3);
		tde.gui.display.syncExec(new Runnable() {
			@Override
			public void run() {
				updateStatus();
				tde.timeLineStack.setTimePosMS(startTimePos);
				tde.gui.timeLinesGUI.redraw();
				if(tde.gui.monitorGUI != null) {
					tde.gui.monitorGUI.setMedia(tde.timeLineStack);
				}
				tde.gui.setWaitCursor(false,shell);
				MessageBox messageBox = new MessageBox(shell,SWT.ICON_INFORMATION|SWT.OK);
			    messageBox.setMessage(pathOut);
			    int rc = messageBox.open();
			}
		});
	}
}
