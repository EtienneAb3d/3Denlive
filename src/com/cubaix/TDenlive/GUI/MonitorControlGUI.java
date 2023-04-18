package com.cubaix.TDenlive.GUI;

import java.awt.Color;
import java.awt.Graphics;
import java.awt.image.BufferedImage;
import java.io.File;
import java.text.NumberFormat;

import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.CLabel;
import org.eclipse.swt.events.MouseEvent;
import org.eclipse.swt.events.MouseListener;
import org.eclipse.swt.events.SelectionEvent;
import org.eclipse.swt.events.SelectionListener;
import org.eclipse.swt.graphics.Cursor;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.graphics.Rectangle;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Combo;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Event;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Listener;
import org.eclipse.swt.widgets.Menu;
import org.eclipse.swt.widgets.MenuItem;
import org.eclipse.swt.widgets.MessageBox;

import com.cubaix.TDenlive.TDConfig;
import com.cubaix.TDenlive.TDenlive;
import com.cubaix.TDenlive.utils.ImageUtils;

public class MonitorControlGUI extends CompositePanel {
	Composite toolBar = null;
	Combo processingRatio = null;
	public boolean isRatio1mSelected = false;
	Combo processingRes = null;
	Combo processingHdr = null;
	Combo fastMode = null;
	CLabel edgesView = null;
	boolean edgesViewOn = false;
	CLabel glasses = null;
	CLabel grid = null;
	CLabel status = null;
	int wobble = 0;

	public MonitorControlGUI(TDenlive aTDe, Composite aParent) {
		super(aTDe, aParent);
		createContents();
	}
	
	void createContents() {
		toolBar = new Composite(container, SWT.NULL);
		GridData aTB = new GridData(GridData.FILL_HORIZONTAL);
		aTB.heightHint = 25;
		toolBar.setLayoutData(aTB);
		tde.gui.applyColorFont(toolBar);
		GridLayout aGL = new GridLayout(11, false);
		tde.gui.applyNoMargin(aGL);
		toolBar.setLayout(aGL);
		
		processingRatio = new Combo(toolBar, SWT.DROP_DOWN);//|SWT.READ_ONLY);
		GridData aResGD = new GridData(GridData.HORIZONTAL_ALIGN_CENTER);
		aResGD.widthHint = 80;
		aResGD.heightHint = 20;
		processingRatio.setLayoutData(aResGD);
		tde.gui.applyColorFont(processingRatio);
		processingRatio.setItems(TDConfig.RATIONAMES);
		processingRatio.addSelectionListener(new SelectionListener() {
			@Override
			public void widgetSelected(SelectionEvent arg0) {
				if(processingRatio.getSelectionIndex() == 0) {
					tde.config.outRatio = tde.config.origRatio;
					isRatio1mSelected = true;
				}
				else {
					tde.config.outRatio = TDConfig.RATIOVALUES[processingRatio.getSelectionIndex()];
					isRatio1mSelected = false;
				}
				tde.config.outRatioName = TDConfig.RATIOSHORTNAMES[processingRatio.getSelectionIndex()];
				processingRatio.setText(TDConfig.RATIOSHORTNAMES[processingRatio.getSelectionIndex()]);//Doesn't work if READ_ONLY !
				tde.gui.monitorGUI.redraw();
			}
			@Override
			public void widgetDefaultSelected(SelectionEvent arg0) {
				if(processingRatio.getSelectionIndex() == 0) {
					tde.config.outRatio = tde.config.origRatio;
					isRatio1mSelected = true;
				}
				else {
					tde.config.outRatio = TDConfig.RATIOVALUES[processingRatio.getSelectionIndex()];
					isRatio1mSelected = false;
				}
				tde.config.outRatioName = TDConfig.RATIOSHORTNAMES[processingRatio.getSelectionIndex()];
				processingRatio.setText(TDConfig.RATIOSHORTNAMES[processingRatio.getSelectionIndex()]);//Doesn't work if READ_ONLY !
				tde.gui.monitorGUI.redraw();
			}
		});

		processingRes = new Combo(toolBar, SWT.DROP_DOWN);//|SWT.READ_ONLY);
		aResGD = new GridData(GridData.HORIZONTAL_ALIGN_CENTER);
		aResGD.widthHint = 80;
		aResGD.heightHint = 20;
		processingRes.setLayoutData(aResGD);
		tde.gui.applyColorFont(processingRes);
		processingRes.setItems(TDConfig.RESNAMES);
		processingRes.addSelectionListener(new SelectionListener() {
			@Override
			public void widgetSelected(SelectionEvent arg0) {
				tde.config.processingResNames[TDConfig.PROCESSING_MODE_WORK] = TDConfig.RESSHORTNAMES[processingRes.getSelectionIndex()];
				tde.config.processingResValues[TDConfig.PROCESSING_MODE_WORK] = TDConfig.RESVALUES[processingRes.getSelectionIndex()];
				processingRes.setText(TDConfig.RESSHORTNAMES[processingRes.getSelectionIndex()]);//Doesn't work if READ_ONLY !
				tde.gui.monitorGUI.redraw();
			}
			@Override
			public void widgetDefaultSelected(SelectionEvent arg0) {
				tde.config.processingResNames[TDConfig.PROCESSING_MODE_WORK] = TDConfig.RESSHORTNAMES[processingRes.getSelectionIndex()];
				tde.config.processingResValues[TDConfig.PROCESSING_MODE_WORK] = TDConfig.RESVALUES[processingRes.getSelectionIndex()];
				processingRes.setText(TDConfig.RESSHORTNAMES[processingRes.getSelectionIndex()]);//Doesn't work if READ_ONLY !
				tde.gui.monitorGUI.redraw();
			}
		});
		
		processingHdr = new Combo(toolBar, SWT.DROP_DOWN);//|SWT.READ_ONLY);
		GridData aHdrGD = new GridData(GridData.HORIZONTAL_ALIGN_CENTER);
		aHdrGD.widthHint = 80;
		aHdrGD.heightHint = 20;
		processingHdr.setLayoutData(aHdrGD);
		tde.gui.applyColorFont(processingHdr);
		processingHdr.setItems(TDConfig.HDRLIST);
		processingHdr.addSelectionListener(new SelectionListener() {
			@Override
			public void widgetSelected(SelectionEvent arg0) {
				tde.config.processingHdr[0] = processingHdr.getSelectionIndex() == 1;
				tde.gui.monitorGUI.redraw();
			}
			@Override
			public void widgetDefaultSelected(SelectionEvent arg0) {
				tde.config.processingHdr[0] = processingHdr.getSelectionIndex() == 1;
				tde.gui.monitorGUI.redraw();
			}
		});
		
		fastMode = new Combo(toolBar, SWT.DROP_DOWN);//|SWT.READ_ONLY);
		GridData aFastGD = new GridData(GridData.HORIZONTAL_ALIGN_CENTER);
		aFastGD.widthHint = 80;
		aFastGD.heightHint = 20;
		fastMode.setLayoutData(aFastGD);
		tde.gui.applyColorFont(fastMode);
		fastMode.setItems(new String[] {
				tde.gui.lngs.get("Monitor.Fast")
				,tde.gui.lngs.get("Monitor.Quality")
		});
		fastMode.addSelectionListener(new SelectionListener() {
			@Override
			public void widgetSelected(SelectionEvent arg0) {
				tde.config.fastMode = fastMode.getSelectionIndex() == 0;
				tde.gui.monitorGUI.redraw();
			}
			@Override
			public void widgetDefaultSelected(SelectionEvent arg0) {
				tde.config.fastMode = fastMode.getSelectionIndex() == 0;
				tde.gui.monitorGUI.redraw();
			}
		});
		fastMode.select(tde.config.fastMode?0:1);
		
		glasses = new CLabel(toolBar,SWT.NONE);
		glasses.setImage(tde.gui.imgsSwt.getIcon("glassesMulti.png"));
		glasses.setAlignment(SWT.CENTER);
		GridData aGlassesGD = new GridData(GridData.HORIZONTAL_ALIGN_CENTER);
		aGlassesGD.widthHint = 32;
		aGlassesGD.heightHint = 25;
		glasses.setLayoutData(aGlassesGD);
		tde.gui.applyColorFont(glasses);
		glasses.addMouseListener(new MouseListener() {
			@Override
			public void mouseUp(MouseEvent arg0) {
			}
			@Override
			public void mouseDown(MouseEvent arg0) {
			}
			@Override
			public void mouseDoubleClick(MouseEvent arg0) {
			}
		});
		final Menu glassesValues = new Menu(tde.gui.shell, SWT.POP_UP);
		String[][] aMixers = new String[][] {
				new String[] {"AnaMixerRC",tde.gui.lngs.get("Glasses.AnaMixerRC")}
				,new String[] {"AnaMixerRC_Dubois",tde.gui.lngs.get("Glasses.AnaMixerRC_Dubois")}
				,new String[] {"AnaMixerRC_ZhangAllister",tde.gui.lngs.get("Glasses.AnaMixerRC_ZhangAllister")}
				,new String[] {"AnaMixerYB",tde.gui.lngs.get("Glasses.AnaMixerYB")}
				,new String[] {"AnaMixerGM",tde.gui.lngs.get("Glasses.AnaMixerGM")}
				,new String[] {"",""}
				,new String[] {"SbsMixerP",tde.gui.lngs.get("Glasses.SbsMixerP")}
				,new String[] {"SbsMixerX",tde.gui.lngs.get("Glasses.SbsMixerX")}
				,new String[] {"",""}
				,new String[] {"InterlacedH1",tde.gui.lngs.get("Glasses.InterlacedH1")}
				,new String[] {"InterlacedH2",tde.gui.lngs.get("Glasses.InterlacedH2")}
				,new String[] {"InterlacedV1",tde.gui.lngs.get("Glasses.InterlacedV1")}
				,new String[] {"InterlacedV2",tde.gui.lngs.get("Glasses.InterlacedV2")}
				,new String[] {"",""}
				,new String[] {"Left",tde.gui.lngs.get("Glasses.Left")}
				,new String[] {"Right",tde.gui.lngs.get("Glasses.Right")}
				,new String[] {"Wobble",tde.gui.lngs.get("Glasses.Wobble")}
				};
		for(String[] m : aMixers){
			if(m[0].isEmpty()) {
				final MenuItem itemGrid = new MenuItem(glassesValues, SWT.SEPARATOR);
				continue;
			}
			final MenuItem itemGrid = new MenuItem(glassesValues, SWT.PUSH);
			itemGrid.setText(m[1]);
			itemGrid.setData(m[0]);
			itemGrid.addListener(SWT.Selection, new Listener() {
				public void handleEvent(Event event) {
					if("Wobble".equals((String)itemGrid.getData())) {
						wobble = 1;
						Thread aTh = new Thread(new Runnable() {
							@Override
							public void run() {
								while(wobble > 0) {
									tde.gui.display.syncExec(new Runnable() {
										@Override
										public void run() {
											if((wobble%2) == 0) {
												glasses.setBackground(tde.gui.colorsSwt.RED);
												tde.gui.monitorGUI.setProcessor("Left");
											}
											else {
												glasses.setBackground(tde.gui.colorsSwt.CYAN);
												tde.gui.monitorGUI.setProcessor("Right");
											}
											tde.gui.monitorGUI.redraw();
										}
									});
									try {
										Thread.sleep(1000);
									}
									catch (Throwable t) {}
									wobble++;
								}
							}
						});
						aTh.start();
					}
					else {
						if(wobble > 0) {
							wobble = -10;
							try {
								Thread.sleep(1000);
							}
							catch (Throwable t) {}
							glasses.setBackground(tde.gui.colorsSwt.WHITE);
						}
						if("Left".equals((String)itemGrid.getData())) {
							glasses.setBackground(tde.gui.colorsSwt.RED);
						}
						else if("Right".equals((String)itemGrid.getData())) {
							glasses.setBackground(tde.gui.colorsSwt.CYAN);
						}
						tde.gui.monitorGUI.setProcessor((String)itemGrid.getData());
						tde.gui.monitorGUI.redraw();
					}
				}
			});
		}
		glasses.addMouseListener(new MouseListener() {
			@Override
			public void mouseUp(MouseEvent arg0) {
			}
			@Override
			public void mouseDown(MouseEvent arg0) {
				Rectangle rect = glasses.getBounds();
				Point pt = new Point(rect.x, rect.y + rect.height);
				pt = toolBar.toDisplay(pt);
				glassesValues.setLocation(pt.x, pt.y);
				glassesValues.setVisible(true);
			}
			@Override
			public void mouseDoubleClick(MouseEvent arg0) {
			}
		});

		grid = new CLabel(toolBar,SWT.NONE);
		grid.setImage(tde.gui.imgsSwt.getIcon("grid.png"));
		grid.setAlignment(SWT.CENTER);
		GridData aGridGD = new GridData(GridData.HORIZONTAL_ALIGN_CENTER);
		aGridGD.widthHint = 32;
		aGridGD.heightHint = 25;
		grid.setLayoutData(aGridGD);
		tde.gui.applyColorFont(grid);
		grid.addMouseListener(new MouseListener() {
			@Override
			public void mouseUp(MouseEvent arg0) {
			}
			@Override
			public void mouseDown(MouseEvent arg0) {
			}
			@Override
			public void mouseDoubleClick(MouseEvent arg0) {
			}
		});
		final Menu gridValues = new Menu(tde.gui.shell, SWT.POP_UP);
		for(String g : new String[] {"0","1","2","3","4","5","10","15","20","30"}){
			final MenuItem itemGrid = new MenuItem(gridValues, SWT.PUSH);
			itemGrid.setText(g);
			itemGrid.addListener(SWT.Selection, new Listener() {
				public void handleEvent(Event event) {
					tde.gui.monitorGUI.grid = Integer.parseInt(itemGrid.getText());
					tde.gui.monitorGUI.redraw();
				}
			});
		}
		grid.addMouseListener(new MouseListener() {
			@Override
			public void mouseUp(MouseEvent arg0) {
			}
			@Override
			public void mouseDown(MouseEvent arg0) {
				Rectangle rect = grid.getBounds();
				Point pt = new Point(rect.x, rect.y + rect.height);
				pt = toolBar.toDisplay(pt);
				gridValues.setLocation(pt.x, pt.y);
				gridValues.setVisible(true);
			}
			@Override
			public void mouseDoubleClick(MouseEvent arg0) {
			}
		});

		edgesView = new CLabel(toolBar,SWT.NONE);
		edgesView.setImage(tde.gui.imgsSwt.getIcon("edgesOff.png"));
		edgesView.setAlignment(SWT.CENTER);
		GridData aEdgesGD = new GridData(GridData.HORIZONTAL_ALIGN_CENTER);
		aEdgesGD.widthHint = 32;
		aEdgesGD.heightHint = 25;
		edgesView.setLayoutData(aEdgesGD);
		tde.gui.applyColorFont(edgesView);
		edgesView.addMouseListener(new MouseListener() {
			@Override
			public void mouseUp(MouseEvent arg0) {
			}
			
			@Override
			public void mouseDown(MouseEvent arg0) {
				edgesViewOn = !edgesViewOn;
				if(edgesViewOn) {
					edgesView.setImage(tde.gui.imgsSwt.getIcon("edgesOn.png"));
				}
				else {
					edgesView.setImage(tde.gui.imgsSwt.getIcon("edgesOff.png"));
				}
				tde.gui.monitorGUI.setEdgeDetector(edgesViewOn);
			}
			
			@Override
			public void mouseDoubleClick(MouseEvent arg0) {
			}
		});

		Composite aSpace = new Composite(toolBar, SWT.SEPARATOR|SWT.FILL);
		tde.gui.applyColorFont(aSpace);
		GridData aSpaceGD = new GridData(GridData.FILL_HORIZONTAL);
		aSpaceGD.heightHint = 25;
		aSpace.setLayoutData(aSpaceGD);
		
		Label aSave = new Label(toolBar,SWT.NONE);
		aSave.setImage(tde.gui.imgsSwt.getIcon("saveView.png"));
		aSave.setAlignment(SWT.CENTER);
		GridData aSaveGD = new GridData(GridData.HORIZONTAL_ALIGN_CENTER);
		aSaveGD.widthHint = 32;
		aSaveGD.heightHint = 25;
		aSave.setLayoutData(aSaveGD);
		tde.gui.applyColorFont(aSave);
		aSave.addMouseListener(new MouseListener() {
			@Override
			public void mouseUp(MouseEvent arg0) {
			}
			@Override
			public void mouseDown(MouseEvent arg0) {
				tde.gui.shell.setCursor(new Cursor(tde.gui.display, SWT.CURSOR_WAIT));
				tde.timeLineStack.reBuild(TDConfig.PROCESSING_MODE_RENDER, tde.timeLineStack.getTimePosMS());
				BufferedImage aBIL = tde.gui.monitorGUI.media.getFinalLeft(TDConfig.PROCESSING_MODE_RENDER, tde.timeLineStack.getTimePosMS());
				BufferedImage aBIR = tde.gui.monitorGUI.media.getFinalRight(TDConfig.PROCESSING_MODE_RENDER, tde.timeLineStack.getTimePosMS());
				String aPathL = null;
				String aPathR = null;
				String aPathX = null;
				String aPathP = null;
				NumberFormat aNF = NumberFormat.getInstance();
				aNF.setMinimumIntegerDigits(4);
				aNF.setGroupingUsed(false);
				for(int i = 1;i < 10000;i++) {
					aPathL = tde.renderDir+File.separatorChar+"3De_"+aNF.format(i) + "_Left"+".png";
					aPathR = tde.renderDir+File.separatorChar+"3De_"+aNF.format(i) + "_Right"+".png";
					aPathX = tde.renderDir+File.separatorChar+"3De_"+aNF.format(i) + "_X"+".png";
					aPathP = tde.renderDir+File.separatorChar+"3De_"+aNF.format(i) + "_P"+".png";
					if(new File(aPathL).exists() || new File(aPathR).exists()) {
						continue;
					}
					break;
				}
				try {
					ImageUtils.saveImage(aPathL, aBIL);
					ImageUtils.saveImage(aPathR, aBIR);
					BufferedImage aBI = ImageUtils.createImage(aBIL.getWidth()*2, aBIL.getHeight(), tde.config.processingHdr[TDConfig.PROCESSING_MODE_RENDER]);
					Graphics aG = aBI.getGraphics();
					aG.drawImage(aBIL, 0, 0, null);
					aG.drawImage(aBIR, aBIL.getWidth(), 0, null);
					ImageUtils.saveImage(aPathP, aBI);
					aG.drawImage(aBIR, 0, 0, null);
					aG.drawImage(aBIL, aBIL.getWidth(), 0, null);
					ImageUtils.saveImage(aPathX, aBI);
				}
				catch(Throwable t) {
					t.printStackTrace(System.err);
					MessageBox messageBox = new MessageBox(tde.gui.shell,SWT.ICON_ERROR|SWT.OK);
				    messageBox.setMessage("Error: "+t.toString());
				    tde.gui.shell.setCursor(null);
				    int rc = messageBox.open();
				    return;
				}
				MessageBox messageBox = new MessageBox(tde.gui.shell,SWT.ICON_INFORMATION|SWT.OK);
			    messageBox.setMessage(aPathL+"\n"+aPathR+"\n"+aPathX+"\n"+aPathP);
			    tde.gui.shell.setCursor(null);
			    int rc = messageBox.open();
			}
			@Override
			public void mouseDoubleClick(MouseEvent arg0) {
			}
		});

		Label aFullScreen = new Label(toolBar,SWT.NONE);
		aFullScreen.setImage(tde.gui.imgsSwt.getIcon("screen.png"));
		aFullScreen.setAlignment(SWT.CENTER);
		GridData aFullScreenGD = new GridData(GridData.HORIZONTAL_ALIGN_CENTER);
		aFullScreenGD.widthHint = 32;
		aFullScreenGD.heightHint = 25;
		aFullScreen.setLayoutData(aFullScreenGD);
		tde.gui.applyColorFont(aFullScreen);
		aFullScreen.addMouseListener(new MouseListener() {
			@Override
			public void mouseUp(MouseEvent arg0) {
			}
			@Override
			public void mouseDown(MouseEvent arg0) {
				tde.gui.monitorGUI.setMedia(null);
				FullScreenGUI aFSG = new FullScreenGUI(tde);
			}
			@Override
			public void mouseDoubleClick(MouseEvent arg0) {
			}
		});
		
		status = new CLabel(toolBar, SWT.NONE);
		status.setImage(tde.gui.imgsSwt.getIcon("tuneButton.gif"));
		status.setText("000.000 fps");
		status.setAlignment(SWT.CENTER);
		GridData aStatusGD = new GridData(GridData.HORIZONTAL_ALIGN_CENTER);
		aStatusGD.widthHint = 100;
		aStatusGD.heightHint = 25;
		status.setLayoutData(aStatusGD);
		tde.gui.applyColorFont(status);
		
		processingRatio.setText(tde.config.outRatioName);
		processingRes.setText(tde.config.processingResNames[0]);
		processingHdr.setText(tde.config.processingHdr[0]?TDConfig.HDRLIST[1]:TDConfig.HDRLIST[0]);
		
		toolBar.pack();
	}
	
	public void setRatio(String aRatioName) {
		tde.gui.display.syncExec(new Runnable() {
			@Override
			public void run() {
				processingRatio.setText(tde.config.outRatioName);
			}
		});
	}
}
