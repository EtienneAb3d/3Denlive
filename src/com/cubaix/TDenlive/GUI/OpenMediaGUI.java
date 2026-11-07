package com.cubaix.TDenlive.GUI;

import java.awt.image.BufferedImage;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;

import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.CLabel;
import org.eclipse.swt.events.SelectionEvent;
import org.eclipse.swt.events.SelectionListener;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.FileDialog;
import org.eclipse.swt.widgets.MessageBox;
import org.eclipse.swt.widgets.Shell;

import com.cubaix.TDenlive.TDConfig;
import com.cubaix.TDenlive.TDenlive;
import com.cubaix.TDenlive.GUI.widgets.LabelImageButton;
import com.cubaix.TDenlive.medias.Media;
import com.cubaix.TDenlive.medias.StereoImage;
import com.cubaix.TDenlive.utils.ImageUtils;
import com.cubaix.TDenlive.utils.StringUtils;

public class OpenMediaGUI {
	TDenlive tde = null;
	Shell shell = null;
	String currentMediaDir = null;
	String currentMediaDir2 = null;
	CLabel preview = null;
	
	public OpenMediaGUI(TDenlive aTDe) {
		tde = aTDe;
		loadPath();
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
		
		new LabelImageButton(tde,shell,tde.gui.lngs.get("MediaOpen.OpenX"),"openObject.png"){
			@Override
			public void clicked() {
				String aPath = fileChoose(currentMediaDir == null ? currentMediaDir = tde.tdeDir:currentMediaDir);
				if(aPath != null){
					currentMediaDir = new File(aPath).getParent();
					savePath();
					tde.gui.setWaitCursor(true,shell);
					Thread aTh = new Thread(new Runnable() {
						@Override
						public void run() {
							try {
								tde.gui.setWaitCursor(true,shell);
								if(aPath.toLowerCase().endsWith(".mpo")) {
									tde.mediaList.loadImageMPO(aPath);
								}
								else {
									tde.mediaList.loadImageX(aPath,TDConfig.PROCESSING_MODE_RENDER,0);
								}
								tde.gui.display.syncExec(new Runnable() {
									@Override
									public void run() {
										if(new File(StringUtils.path2processing(StringUtils.path2workingcopy(aPath, tde.getWorkCacheDir()))).exists()) {
											MessageBox messageBox = new MessageBox(shell,SWT.ICON_INFORMATION|SWT.OK);
										    messageBox.setMessage(tde.gui.lngs.get("MediaOpen.WorkCopyProcessing"));
										    int rc = messageBox.open();
										}
										showPreview();
										preview.layout(true);
										tde.gui.mediaListGUI.redraw();
										tde.gui.setWaitCursor(false,shell);
									}
								});
							}
							catch(Throwable t) {
								t.printStackTrace(System.err);
							}
						}
					});
					aTh.start();
				}
			}
		}.tip("OpenX");
		new LabelImageButton(tde,shell,tde.gui.lngs.get("MediaOpen.OpenP"),"openObject.png"){
			@Override
			public void clicked() {
				String aPath = fileChoose(currentMediaDir == null ? currentMediaDir = tde.tdeDir:currentMediaDir);
				if(aPath != null){
					currentMediaDir = new File(aPath).getParent();
					savePath();
					tde.gui.setWaitCursor(true,shell);
					Thread aTh = new Thread(new Runnable() {
						@Override
						public void run() {
							try {
								if(aPath.toLowerCase().endsWith(".mpo")) {
									tde.mediaList.loadImageMPO(aPath);
								}
								else {
									tde.mediaList.loadImageP(aPath,TDConfig.PROCESSING_MODE_RENDER,0);
								}
								tde.gui.display.syncExec(new Runnable() {
									@Override
									public void run() {
										if(new File(StringUtils.path2processing(StringUtils.path2workingcopy(aPath, tde.getWorkCacheDir()))).exists()) {
											MessageBox messageBox = new MessageBox(shell,SWT.ICON_INFORMATION|SWT.OK);
										    messageBox.setMessage(tde.gui.lngs.get("MediaOpen.WorkCopyProcessing"));
										    int rc = messageBox.open();
										}
										showPreview();
										preview.layout(true);
										tde.gui.mediaListGUI.redraw();
										tde.gui.setWaitCursor(false,shell);
									}
								});
							}
							catch(Throwable t) {
								t.printStackTrace(System.err);
							}
						}
					});
					aTh.start();
				}
			}
		}.tip("OpenP");
		new LabelImageButton(tde,shell,tde.gui.lngs.get("MediaOpen.OpenLR"),"openObject.png"){
			@Override
			public void clicked() {
				final String[] aPaths = new String[2];
				aPaths[0] = fileChoose(currentMediaDir == null ? currentMediaDir = tde.tdeDir:currentMediaDir);
				if(aPaths[0] != null){
					currentMediaDir = new File(aPaths[0]).getParent();
					savePath();
					if(aPaths[0].toLowerCase().endsWith(".mpo")) {
						aPaths[1] = aPaths[0];
					}
					else {
						aPaths[1] = fileChoose(currentMediaDir2 != null ? currentMediaDir2 : (currentMediaDir == null ? currentMediaDir = tde.tdeDir:currentMediaDir));
					}
					if(aPaths[1] != null){
						currentMediaDir2 = new File(aPaths[1]).getParent();
						tde.gui.setWaitCursor(true,shell);
						Thread aTh = new Thread(new Runnable() {
							@Override
							public void run() {
								try {
									if(aPaths[0].toLowerCase().endsWith(".mpo")) {
										tde.mediaList.loadImageMPO(aPaths[0]);
									}
									else {
										tde.mediaList.loadImageLR(aPaths[0],aPaths[1],TDConfig.PROCESSING_MODE_RENDER,0);
									}
									tde.gui.display.syncExec(new Runnable() {
										@Override
										public void run() {
											if(new File(StringUtils.path2processing(StringUtils.path2workingcopy(aPaths[0], tde.getWorkCacheDir()))).exists()) {
												MessageBox messageBox = new MessageBox(shell,SWT.ICON_INFORMATION|SWT.OK);
											    messageBox.setMessage(tde.gui.lngs.get("MediaOpen.WorkCopyProcessing"));
											    int rc = messageBox.open();
											}
											showPreview();
											preview.layout(true);
											tde.gui.mediaListGUI.redraw();
											tde.gui.setWaitCursor(false,shell);
										}
									});
								}
								catch(Throwable t) {
									t.printStackTrace(System.err);
								}
							}
						});
						aTh.start();
					}
				}
			}
		}.tip("OpenLR");
		
		new LabelImageButton(tde,shell,tde.gui.lngs.get("MediaOpen.OpenFlat"),"openObject.png"){
			@Override
			public void clicked() {
				String aPath = fileChoose(currentMediaDir == null ? currentMediaDir = tde.tdeDir:currentMediaDir);
				if(aPath != null){
					currentMediaDir = new File(aPath).getParent();
					savePath();
					tde.gui.setWaitCursor(true,shell);
					Thread aTh = new Thread(new Runnable() {
						@Override
						public void run() {
							try {
								if(aPath.toLowerCase().endsWith(".mpo")) {
									tde.mediaList.loadImageMPO(aPath);
								}
								else {
									tde.mediaList.loadImageLR(aPath,aPath,TDConfig.PROCESSING_MODE_RENDER,0);
								}
								tde.gui.display.syncExec(new Runnable() {
									@Override
									public void run() {
										if(new File(StringUtils.path2processing(StringUtils.path2workingcopy(aPath, tde.getWorkCacheDir()))).exists()) {
											MessageBox messageBox = new MessageBox(shell,SWT.ICON_INFORMATION|SWT.OK);
										    messageBox.setMessage(tde.gui.lngs.get("MediaOpen.WorkCopyProcessing"));
										    int rc = messageBox.open();
										}
										showPreview();
										preview.layout(true);
										tde.gui.mediaListGUI.redraw();
										tde.gui.setWaitCursor(false,shell);
									}
								});
							}
							catch(Throwable t) {
								t.printStackTrace(System.err);
							}
						}
					});
					aTh.start();
				}
			}
		}.tip("OpenFlat");

		new LabelImageButton(tde,shell,tde.gui.lngs.get("MediaOpen.OpenAudio"),"sound.gif"){
			@Override
			public void clicked() {
				String aPath = fileChoose(currentMediaDir == null ? currentMediaDir = tde.tdeDir:currentMediaDir,true);
				if(aPath != null){
					currentMediaDir = new File(aPath).getParent();
					savePath();
					tde.gui.setWaitCursor(true,shell);
					Thread aTh = new Thread(new Runnable() {
						@Override
						public void run() {
							try {
								tde.mediaList.loadAudio(aPath);
							}
							catch(Throwable t) {
								t.printStackTrace(System.err);
							}
							tde.gui.display.syncExec(new Runnable() {
								@Override
								public void run() {
									showPreview();
									preview.layout(true);
									tde.gui.mediaListGUI.redraw();
									tde.gui.setWaitCursor(false,shell);
								}
							});
						}
					});
					aTh.start();
				}
			}
		}.tip("OpenAudio");

		Button aOk = new Button(shell, SWT.PUSH);
		GridData aOGD = new GridData();
		aOGD.heightHint = tde.gui.h(20);
		aOk.setLayoutData(aOGD);
		aOk.setText(tde.gui.lngs.get("MediaOpen.Done"));
		aOk.setImage(tde.gui.imgsSwt.getIcon("checked.gif"));
		tde.gui.applyColorFont(aOk);
		tde.gui.tip(aOk, "CloseWindow");
		aOk.setAlignment(SWT.CENTER);
		aOk.addSelectionListener(new SelectionListener() {
			@Override
			public void widgetSelected(SelectionEvent arg0) {
				shell.close();
				shell.dispose();
			}
			@Override
			public void widgetDefaultSelected(SelectionEvent arg0) {
				shell.close();
				shell.dispose();
			}
		});
		
		preview = new CLabel(shell, SWT.NONE);
		preview.setBackground(tde.gui.colorsSwt.WHITE);
		preview.setForeground(tde.gui.colorsSwt.CUBAIX_BLUE);
		preview.setFont(tde.gui.fontsSwt.roboto);
		preview.setLayoutData(new GridData(GridData.FILL_BOTH));
		preview.setAlignment(SWT.CENTER);
	}

	void showPreview() {
		if(tde.mediaList.mediaList.size() <= 0) {
			return;
		}
		Media aM = tde.mediaList.mediaList.lastElement();
		BufferedImage aBI = aM instanceof StereoImage ? ((StereoImage)aM).loadedLeft
				: aM.getFinalLeft(TDConfig.PROCESSING_MODE_WORK, 0);
		if(aBI != null) {
			preview.setImage(ImageUtils.imageToSwt(tde.gui.display,ImageUtils.toRes(aBI,300,false,false,true)));
		}
	}

	void savePath() {
		try {
			BufferedWriter aOut = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(tde.tdeDir+File.separatorChar+"Path.conf"), "UTF-8"));
			aOut.write("OpenMediaPath="+currentMediaDir);
			aOut.flush();
			aOut.close();
		}
		catch(Throwable t) {
			t.printStackTrace(System.err);
		}
	}
	
	void loadPath() {
		try {
			BufferedReader aBR = new BufferedReader(
					new InputStreamReader(
							new FileInputStream(tde.tdeDir+File.separatorChar+"Path.conf"), "UTF8"));
			String aP = null;
			while((aP = aBR.readLine()) != null) {
				if(aP.startsWith("OpenMediaPath=")) {
					currentMediaDir = aP.substring("OpenMediaPath=".length());
				}
			}
			aBR.close();
		}
		catch(Throwable t) {
			t.printStackTrace(System.err);
		}
	}
	
	static final String AUDIO_EXTS = "*.mp3;*.wav;*.wave;*.ogg;*.oga;*.opus;*.flac;*.aac;*.m4a;*.wma;*.aif;*.aiff;*.ac3;*.mka;";
	
	String fileChoose(String filterPath) {
		return fileChoose(filterPath, false);
	}
	
	String fileChoose(String filterPath,boolean aAudio) {
		FileDialog aFD = new FileDialog (shell, SWT.OPEN);
		String [] filterNames = new String [] {aAudio ? "Sound Files" : "Image Files", "All Files (*)"};
		String aExts = aAudio ? AUDIO_EXTS : "*.gif;*.png;*.xpm;*.jpg;*.jpeg;*.tiff;*.mpo;"
				+ "*.mp4;*.mpg;*.mpeg;*.avi;*.mov;*.mkv;*.vob;*.ogv;*.wmv;*.ts;*.mts;*.m2ts;*.flv;*.dv;"
				+ "*.svg;";
		String [] filterExtensions = new String [] {aExts+aExts.toUpperCase(), "*"};
//		String platform = SWT.getPlatform();
//		if (platform.equals("win32")) {
//			filterNames = new String [] {"Image Files", "All Files (*.*)"};
//			filterExtensions = new String [] {"*.gif;*.png;*.bmp;*.jpg;*.jpeg;*.tiff", "*.*"};
//			filterPath = "c:\\";
//		}
		aFD.setFilterNames (filterNames);
		aFD.setFilterExtensions (filterExtensions);
		aFD.setFilterPath (filterPath);
//		dialog.setFileName ("myfile");//
		String aPath = aFD.open ();
		System.out.println ("Path: " + aPath);
		if(aPath != null && aPath.matches(".*_3De_WorkCopy.*")) {
			MessageBox messageBox = new MessageBox(shell,SWT.ICON_INFORMATION|SWT.OK);
		    messageBox.setMessage(tde.gui.lngs.get("MediaOpen.WorkCopyChose"));
		    int rc = messageBox.open();
		    return null;
		}
		return aPath;
	}
}
