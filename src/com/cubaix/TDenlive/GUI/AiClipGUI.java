package com.cubaix.TDenlive.GUI;

import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.text.SimpleDateFormat;
import java.util.Date;

import javax.imageio.ImageIO;

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
import org.eclipse.swt.widgets.FileDialog;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.MessageBox;
import org.eclipse.swt.widgets.ProgressBar;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Spinner;
import org.eclipse.swt.widgets.Text;

import com.cubaix.TDenlive.TDConfig;
import com.cubaix.TDenlive.TDenlive;
import com.cubaix.TDenlive.ai.WanGenerator;
import com.cubaix.TDenlive.depth.DepthEstimator;
import com.cubaix.TDenlive.depth.DepthModel;
import com.cubaix.TDenlive.utils.ImageUtils;

/**
 * AI clip (button right of the shapes clip one): a 2D video generated from a prompt by Wan2.2-TI2V-5B
 * (see ai.WanGenerator). The form gives the prompt and the duration (at most 5 s), with the GPU needed
 * and what is downloaded at the first use. The generation runs in background with a progress window
 * that shows an approximate preview of the video being denoised (animated latent frames).
 * The video is written into the AI folder of the project (with its prompt in a .txt) and added
 * to the media list as a flat media.
 */
public class AiClipGUI {
	//One generation at a time
	static boolean running = false;
	//Last prompt and duration (default of the next form)
	static String lastPrompt = "";
	static double lastSeconds = WanGenerator.MAX_SECONDS;
	static String lastImageDir = null;

	TDenlive tde;
	String prompt = null;
	double seconds = WanGenerator.MAX_SECONDS;
	//Reference image animated by the model (null: from the text only)
	String imagePath = null;
	int width = WanGenerator.WIDTH;
	int height = WanGenerator.HEIGHT;
	Shell shell = null;
	Label statusL = null;
	Label detailL = null;
	Label etaL = null;
	ProgressBar bar = null;
	Canvas previewC = null;
	Image previewI = null;
	int previewFrames = 1;
	int previewFrame = 0;
	volatile boolean canceled = false;
	WanGenerator generator = null;
	long stepStartMS = -1;
	long stepStartDone = 0;
	long lastUpdateMS = 0;

	AiClipGUI(TDenlive aTDe) {
		tde = aTDe;
		setOrientation(tde.config.outRatio > 0 && tde.config.outRatio < 1);
	}

	/**
	 * Portrait (704x1280) or landscape (1280x704) video
	 */
	void setOrientation(boolean aPortrait) {
		width = aPortrait ? WanGenerator.HEIGHT : WanGenerator.WIDTH;
		height = aPortrait ? WanGenerator.WIDTH : WanGenerator.HEIGHT;
	}

	String sizeText() {
		return lng("AI.DurationMax")
				.replace("%MAX%", String.format("%.0f", WanGenerator.MAX_SECONDS))
				.replace("%FPS%", ""+WanGenerator.FPS)
				.replace("%W%", ""+width).replace("%H%", ""+height);
	}

	/**
	 * Form, then generation (from the media list toolbar)
	 */
	public static void open(TDenlive aTDe) {
		if(running) {
			message(aTDe, SWT.ICON_INFORMATION, aTDe.gui.lngs.get("AI.Running"));
			return;
		}
		if(!WanGenerator.isPlatformSupported()) {
			message(aTDe, SWT.ICON_INFORMATION, aTDe.gui.lngs.get("AI.Unsupported")
					.replace("%GB%", DepthModel.gb(WanGenerator.GPU_MEMORY)));
			return;
		}
		AiClipGUI aGUI = new AiClipGUI(aTDe);
		if(!aGUI.form()) {
			return;
		}
		aGUI.start();
	}

	static void message(TDenlive aTDe,int aIcon,String aText) {
		MessageBox aMB = new MessageBox(aTDe.gui.shell, aIcon|SWT.OK);
		aMB.setText(aTDe.gui.lngs.get("AI.Title"));
		aMB.setMessage(aText);
		aMB.open();
	}

	String lng(String aKey) {
		return tde.gui.lngs.get(aKey);
	}

	Label addLabel(Composite aParent,String aText,int aIndent) {
		Label aL = new Label(aParent, SWT.WRAP);
		tde.gui.applyColorFont(aL);
		aL.setText(aText);
		GridData aGD = new GridData(GridData.FILL_HORIZONTAL);
		aGD.horizontalIndent = aIndent;
		aGD.widthHint = 560;
		aL.setLayoutData(aGD);
		return aL;
	}

	void center(Shell aShell) {
		aShell.pack();
		Rectangle aP = tde.gui.shell.getBounds();
		Rectangle aS = aShell.getBounds();
		aShell.setLocation(aP.x + (aP.width - aS.width) / 2, aP.y + (aP.height - aS.height) / 2);
	}

	/**
	 * Prompt and duration, with the GPU needed and the downloads
	 * @return false if canceled
	 */
	boolean form() {
		final boolean[] aResult = {false};
		Shell aShell = new Shell(tde.gui.shell, SWT.DIALOG_TRIM | SWT.APPLICATION_MODAL | SWT.RESIZE);
		aShell.setText(lng("AI.Title"));
		aShell.setLayout(new GridLayout(1, false));
		tde.gui.applyColorFont(aShell);
		aShell.setImage(tde.gui.imgsSwt.getIcon("3Denlive32.png"));

		//GPU needed: shown first, in bold
		Label aWarn = addLabel(aShell, lng("AI.GpuNeeded").replace("%GB%", DepthModel.gb(WanGenerator.GPU_MEMORY)), 0);
		aWarn.setFont(tde.gui.fontsSwt.robotoBold);
		long[] aBest = DepthEstimator.bestGpu();
		if(aBest != null) {
			Label aFree = addLabel(aShell, lng("AI.GpuFree").replace("%GB%", DepthModel.gb(aBest[1])), 0);
			if(aBest[1] < WanGenerator.GPU_MEMORY) {
				aFree.setText(aFree.getText()+"\n"+lng("AI.GpuTooSmall"));
				aFree.setForeground(tde.gui.colorsSwt.RED);
			}
		}

		Label aPL = addLabel(aShell, lng("AI.Prompt"), 0);
		((GridData)aPL.getLayoutData()).verticalIndent = 8;
		Text aPrompt = new Text(aShell, SWT.MULTI | SWT.WRAP | SWT.BORDER | SWT.V_SCROLL);
		tde.gui.applyColorFont(aPrompt);
		GridData aPGD = new GridData(GridData.FILL_BOTH);
		aPGD.widthHint = 560;
		aPGD.heightHint = tde.gui.h(110);
		aPrompt.setLayoutData(aPGD);
		aPrompt.setText(lastPrompt);
		aPrompt.selectAll();

		Composite aDur = new Composite(aShell, SWT.NONE);
		tde.gui.applyColorFont(aDur);
		aDur.setLayoutData(new GridData(GridData.FILL_HORIZONTAL));
		GridLayout aDGL = new GridLayout(3, false);
		tde.gui.applyNoMargin(aDGL);
		aDur.setLayout(aDGL);
		Label aDL = new Label(aDur, SWT.NONE);
		tde.gui.applyColorFont(aDL);
		aDL.setText(lng("AI.Duration"));
		//Tenths of seconds
		Spinner aSeconds = new Spinner(aDur, SWT.BORDER);
		aSeconds.setDigits(1);
		aSeconds.setMinimum((int)Math.round(WanGenerator.MIN_SECONDS*10));
		aSeconds.setMaximum((int)Math.round(WanGenerator.MAX_SECONDS*10));
		aSeconds.setIncrement(5);
		aSeconds.setSelection((int)Math.round(lastSeconds*10));
		Label aDMax = new Label(aDur, SWT.NONE);
		tde.gui.applyColorFont(aDMax);
		aDMax.setText(sizeText());

		createImageRow(aShell, aDMax);

		long aDownload = WanGenerator.modelBytesToDownload(tde.tdeDir)+WanGenerator.envBytesToInstall(tde.tdeDir);
		if(aDownload > 0) {
			addLabel(aShell, lng("AI.Download").replace("%GB%", DepthModel.gb(aDownload)), 0);
		}
		addLabel(aShell, lng("AI.Note2D"), 0);
		addLabel(aShell, lng("AI.Licence"), 0);

		Composite aButtons = new Composite(aShell, SWT.NONE);
		GridData aBGD = new GridData(GridData.HORIZONTAL_ALIGN_END);
		aBGD.verticalIndent = 8;
		aButtons.setLayoutData(aBGD);
		tde.gui.applyColorFont(aButtons);
		GridLayout aBGL = new GridLayout(2, true);
		tde.gui.applyNoMargin(aBGL);
		aButtons.setLayout(aBGL);
		Button aCancel = new Button(aButtons, SWT.PUSH);
		aCancel.setText(lng("AI.Cancel"));
		tde.gui.applyColorFont(aCancel);
		aCancel.setLayoutData(new GridData(GridData.FILL_HORIZONTAL));
		aCancel.addSelectionListener(new SelectionAdapter() {
			@Override
			public void widgetSelected(SelectionEvent e) {
				aShell.close();
			}
		});
		Button aOk = new Button(aButtons, SWT.PUSH);
		aOk.setText(lng("AI.Generate"));
		aOk.setImage(tde.gui.imgsSwt.getIcon("checked.gif"));
		tde.gui.applyColorFont(aOk);
		aOk.setLayoutData(new GridData(GridData.FILL_HORIZONTAL));
		aOk.addSelectionListener(new SelectionAdapter() {
			@Override
			public void widgetSelected(SelectionEvent e) {
				String aP = aPrompt.getText().trim();
				if(aP.isEmpty()) {
					aPrompt.setFocus();
					return;
				}
				if(imagePath != null && !new File(imagePath).isFile()) {
					imagePath = null;
				}
				prompt = aP;
				seconds = aSeconds.getSelection()/10.0;
				lastPrompt = aP;
				lastSeconds = seconds;
				aResult[0] = true;
				aShell.close();
			}
		});

		center(aShell);
		aShell.open();
		aPrompt.setFocus();
		while(!aShell.isDisposed()) {
			if(!tde.gui.display.readAndDispatch()) {
				tde.gui.display.sleep();
			}
		}
		return aResult[0];
	}

	/**
	 * Optional reference image: thumbnail, name, Choose / Remove. The video starts from it (cropped to the
	 * ratio of the video, whose orientation follows the image) and animates it according to the prompt.
	 */
	void createImageRow(Shell aShell,Label aSizeL) {
		Label aIL = addLabel(aShell, lng("AI.Image"), 0);
		((GridData)aIL.getLayoutData()).verticalIndent = 8;
		Composite aRow = new Composite(aShell, SWT.NONE);
		tde.gui.applyColorFont(aRow);
		aRow.setLayoutData(new GridData(GridData.FILL_HORIZONTAL));
		GridLayout aRGL = new GridLayout(4, false);
		tde.gui.applyNoMargin(aRGL);
		aRow.setLayout(aRGL);
		Label aThumb = new Label(aRow, SWT.BORDER | SWT.CENTER);
		tde.gui.applyColorFont(aThumb);
		GridData aTGD = new GridData();
		aTGD.widthHint = 120;
		aTGD.heightHint = 68;
		aThumb.setLayoutData(aTGD);
		Label aName = new Label(aRow, SWT.WRAP);
		tde.gui.applyColorFont(aName);
		GridData aNGD = new GridData(GridData.FILL_HORIZONTAL);
		aNGD.widthHint = 250;
		aName.setLayoutData(aNGD);
		Button aChoose = new Button(aRow, SWT.PUSH);
		tde.gui.applyColorFont(aChoose);
		aChoose.setText(lng("AI.ImageChoose"));
		Button aRemove = new Button(aRow, SWT.PUSH);
		tde.gui.applyColorFont(aRemove);
		aRemove.setText(lng("AI.ImageRemove"));
		final Image[] aThumbI = {null};
		aShell.addListener(SWT.Dispose, e -> {
			if(aThumbI[0] != null) {
				aThumbI[0].dispose();
			}
		});
		Runnable aShow = () -> {
			if(aThumbI[0] != null) {
				aThumbI[0].dispose();
				aThumbI[0] = null;
			}
			aThumb.setImage(null);
			aRemove.setEnabled(imagePath != null);
			if(imagePath == null) {
				aName.setText(lng("AI.ImageNone"));
				setOrientation(tde.config.outRatio > 0 && tde.config.outRatio < 1);
			}
			else {
				aName.setText(new File(imagePath).getName());
				try {
					BufferedImage aBI = ImageUtils.loadImage(imagePath);
					setOrientation(aBI.getHeight() > aBI.getWidth());
					double aS = Math.min(118.0/aBI.getWidth(), 66.0/aBI.getHeight());
					int aW = Math.max(1, (int)(aBI.getWidth()*aS));
					int aH = Math.max(1, (int)(aBI.getHeight()*aS));
					BufferedImage aSmall = new BufferedImage(aW, aH, BufferedImage.TYPE_INT_ARGB);
					java.awt.Graphics2D aG = aSmall.createGraphics();
					aG.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION, java.awt.RenderingHints.VALUE_INTERPOLATION_BILINEAR);
					aG.drawImage(aBI, 0, 0, aW, aH, null);
					aG.dispose();
					aThumbI[0] = ImageUtils.imageToSwt(tde.gui.display, aSmall);
					aThumb.setImage(aThumbI[0]);
				}
				catch(Throwable t) {
					t.printStackTrace(System.err);
				}
			}
			aSizeL.setText(sizeText());
			aSizeL.getParent().layout(true);
			aRow.layout(true);
		};
		aChoose.addSelectionListener(new SelectionAdapter() {
			@Override
			public void widgetSelected(SelectionEvent e) {
				FileDialog aFD = new FileDialog(aShell, SWT.OPEN);
				aFD.setText(lng("AI.Image"));
				aFD.setFilterNames(new String[] {lng("AI.ImageFilter")});
				aFD.setFilterExtensions(new String[] {"*.jpg;*.jpeg;*.png;*.bmp;*.gif;*.JPG;*.JPEG;*.PNG;*.BMP;*.GIF"});
				String aDir = lastImageDir != null ? lastImageDir : (tde.lastDir != null ? tde.lastDir : tde.tdeDir);
				aFD.setFilterPath(aDir);
				String aPath = aFD.open();
				if(aPath != null) {
					imagePath = aPath;
					lastImageDir = new File(aPath).getParent();
					aShow.run();
				}
			}
		});
		aRemove.addSelectionListener(new SelectionAdapter() {
			@Override
			public void widgetSelected(SelectionEvent e) {
				imagePath = null;
				aShow.run();
			}
		});
		aShow.run();
	}

	/**
	 * Folder of the generated videos: AI next to the project (~/3Denlive/AI before the first save)
	 */
	File outDir() {
		String aDir = tde.projectDir == null || tde.projectName == null ? tde.tdeDir : tde.projectDir;
		return new File(aDir, "AI");
	}

	/**
	 * Progress window with the preview, and generation thread
	 */
	void start() {
		running = true;
		shell = new Shell(tde.gui.shell, SWT.DIALOG_TRIM | SWT.RESIZE);
		shell.setText(lng("AI.Title"));
		shell.setLayout(new GridLayout(1, false));
		tde.gui.applyColorFont(shell);
		shell.setImage(tde.gui.imgsSwt.getIcon("3Denlive32.png"));
		addLabel(shell, prompt.length() > 200 ? prompt.substring(0, 200)+"..." : prompt, 0).setFont(tde.gui.fontsSwt.robotoBold);
		previewC = new Canvas(shell, SWT.BORDER | SWT.DOUBLE_BUFFERED);
		GridData aCGD = new GridData(GridData.FILL_BOTH);
		aCGD.widthHint = 560;
		aCGD.heightHint = width >= height ? 560*height/width : 420;
		previewC.setLayoutData(aCGD);
		previewC.addListener(SWT.Paint, e -> drawPreview(e.gc));
		previewC.addListener(SWT.Resize, e -> previewC.redraw());
		statusL = addLabel(shell, lng("AI.Starting"), 0);
		detailL = addLabel(shell, " ", 0);
		bar = new ProgressBar(shell, SWT.SMOOTH | SWT.HORIZONTAL);
		bar.setMinimum(0);
		bar.setMaximum(1000);
		bar.setLayoutData(new GridData(GridData.FILL_HORIZONTAL));
		etaL = addLabel(shell, " ", 0);
		Button aCancel = new Button(shell, SWT.PUSH);
		aCancel.setText(lng("AI.Cancel"));
		aCancel.setImage(tde.gui.imgsSwt.getIcon("stop.gif"));
		tde.gui.applyColorFont(aCancel);
		aCancel.setLayoutData(new GridData(GridData.HORIZONTAL_ALIGN_END));
		aCancel.addSelectionListener(new SelectionAdapter() {
			@Override
			public void widgetSelected(SelectionEvent e) {
				cancel();
			}
		});
		//Closing the window cancels
		shell.addListener(SWT.Close, e -> cancel());
		shell.addListener(SWT.Dispose, e -> {
			if(previewI != null) {
				previewI.dispose();
				previewI = null;
			}
		});
		center(shell);
		shell.open();
		animate();

		File aOutDir = outDir();
		String aBase = "AI_"+new SimpleDateFormat("yyyyMMdd_HHmmss").format(new Date());
		File aOut = new File(aOutDir, aBase+".mp4");
		File aPromptFile = new File(aOutDir, aBase+".txt");
		int aFrames = WanGenerator.frames(seconds);
		Thread aTh = new Thread(new Runnable() {
			@Override
			public void run() {
				String aError = null;
				boolean aDone = false;
				try {
					aOutDir.mkdirs();
					//The prompt stays next to the video
					Files.write(aPromptFile.toPath(), prompt.getBytes(StandardCharsets.UTF_8));
					generator = new WanGenerator(tde.tdeDir, new WanGenerator.Progress() {
						@Override
						public void status(String aKey, String aDetail, long aDoneN, long aTotal) {
							showStatus(aKey, aDetail, aDoneN, aTotal);
						}
						@Override
						public void preview(String aPng, int aNbFrames) {
							showPreview(aPng, aNbFrames);
						}
						@Override
						public boolean isCanceled() {
							return canceled;
						}
					});
					if(generator.generate(aPromptFile, imagePath == null ? null : new File(imagePath), aFrames, width, height, aOut) && !canceled) {
						showStatus("AI.Loading", null, 0, 0);
						tde.mediaList.loadImageLR(aOut.getPath(), aOut.getPath(), TDConfig.PROCESSING_MODE_RENDER, 0);
						aDone = true;
					}
				}
				catch(WanGenerator.GenerationException e) {
					e.printStackTrace(System.err);
					if("OOM".equals(e.code)) {
						aError = lng("AI.ErrorOom").replace("%GB%", DepthModel.gb(WanGenerator.GPU_MEMORY));
					}
					else if("NOGPU".equals(e.code)) {
						aError = lng("AI.ErrorNoGpu");
					}
					else {
						aError = lng("AI.Error")+"\n"+e.code;
					}
				}
				catch(Throwable t) {
					t.printStackTrace(System.err);
					aError = lng("AI.Error")+"\n"+(t.getMessage() == null ? t.toString() : t.getMessage());
				}
				if(!aDone) {
					aPromptFile.delete();
				}
				final String aErr = aError;
				final boolean aOk = aDone;
				tde.gui.display.asyncExec(new Runnable() {
					@Override
					public void run() {
						running = false;
						if(!shell.isDisposed()) {
							shell.dispose();
						}
						if(aErr != null) {
							message(tde, SWT.ICON_ERROR, aErr);
							return;
						}
						if(aOk) {
							tde.gui.mediaListGUI.redraw();
						}
					}
				});
			}
		});
		aTh.start();
	}

	void cancel() {
		if(canceled) {
			return;
		}
		canceled = true;
		if(statusL != null && !statusL.isDisposed()) {
			statusL.setText(lng("AI.Canceling"));
		}
		if(generator != null) {
			generator.cancel();
		}
	}

	/**
	 * Preview frames played in a loop (6 latent frames per second: one per 4 video frames at 24 fps)
	 */
	void animate() {
		if(shell == null || shell.isDisposed()) {
			return;
		}
		if(previewI != null && previewFrames > 1) {
			previewFrame = (previewFrame+1)%previewFrames;
			previewC.redraw();
		}
		tde.gui.display.timerExec(1000*4/WanGenerator.FPS, this::animate);
	}

	void drawPreview(GC aGC) {
		Rectangle aCA = previewC.getClientArea();
		aGC.setBackground(tde.gui.colorsSwt.DARK_GRAY);
		aGC.fillRectangle(aCA);
		if(previewI == null) {
			aGC.setForeground(tde.gui.colorsSwt.WHITE);
			String aT = lng("AI.PreviewWait");
			org.eclipse.swt.graphics.Point aE = aGC.textExtent(aT);
			aGC.drawText(aT, (aCA.width-aE.x)/2, (aCA.height-aE.y)/2, true);
			return;
		}
		Rectangle aIB = previewI.getBounds();
		int aFW = aIB.width/Math.max(1, previewFrames);
		int aFH = aIB.height;
		double aScale = Math.min(aCA.width/(double)aFW, aCA.height/(double)aFH);
		int aW = (int)(aFW*aScale);
		int aH = (int)(aFH*aScale);
		aGC.setInterpolation(SWT.HIGH);
		aGC.drawImage(previewI, Math.min(previewFrame, previewFrames-1)*aFW, 0, aFW, aFH
				, (aCA.width-aW)/2, (aCA.height-aH)/2, aW, aH);
	}

	/**
	 * New preview strip (generation thread)
	 */
	void showPreview(String aPng,int aNbFrames) {
		BufferedImage aBI = null;
		try {
			BufferedImage aRead = ImageIO.read(new File(aPng));
			//PNG read as 3 bytes per pixel: ImageUtils.imageToSwt() needs integer ARGB pixels
			if(aRead != null) {
				aBI = new BufferedImage(aRead.getWidth(), aRead.getHeight(), BufferedImage.TYPE_INT_ARGB);
				java.awt.Graphics2D aG = aBI.createGraphics();
				aG.drawImage(aRead, 0, 0, null);
				aG.dispose();
			}
		}
		catch(Throwable t) {
			t.printStackTrace(System.err);
		}
		if(aBI == null) {
			return;
		}
		final BufferedImage aFinal = aBI;
		tde.gui.display.asyncExec(new Runnable() {
			@Override
			public void run() {
				if(shell == null || shell.isDisposed()) {
					return;
				}
				if(previewI != null) {
					previewI.dispose();
				}
				previewI = ImageUtils.imageToSwt(tde.gui.display, aFinal);
				previewFrames = Math.max(1, aNbFrames);
				previewC.redraw();
			}
		});
	}

	void showStatus(String aKey,String aDetail,long aDone,long aTotal) {
		long aNow = System.currentTimeMillis();
		boolean aSteps = "AI.Generating".equals(aKey);
		if(aSteps && stepStartMS < 0 && aTotal > 0) {
			stepStartMS = aNow;
			stepStartDone = aDone;
		}
		if(aNow-lastUpdateMS < 200 && aDone > 0 && aDone < aTotal) {
			return;
		}
		lastUpdateMS = aNow;
		String aText = lng(aKey);
		String aDet = aDetail == null ? " " : aDetail;
		String aEta = " ";
		if(aKey.startsWith("AI.Downloading") && aTotal > 0) {
			aDet = (aDetail == null ? "" : aDetail+": ")+DepthModel.gb(aDone)+" / "+DepthModel.gb(aTotal)+" GB";
		}
		else if(aSteps && aTotal > 0) {
			aText += " "+aDone+" / "+aTotal;
			long aDoneHere = aDone-stepStartDone;
			if(aDoneHere > 0) {
				long aLeftS = (aNow-stepStartMS)*(aTotal-aDone)/aDoneHere/1000;
				aEta = lng("AI.Remaining")+" "+(aLeftS >= 60 ? (aLeftS/60)+" min " : "")+(aLeftS%60)+" s";
			}
		}
		final int aBar = aTotal > 0 ? (int)Math.min(1000, 1000*aDone/aTotal) : -1;
		final String aT = aText;
		final String aD = aDet;
		final String aE = aEta;
		tde.gui.display.asyncExec(new Runnable() {
			@Override
			public void run() {
				if(shell == null || shell.isDisposed() || canceled) {
					return;
				}
				statusL.setText(aT);
				detailL.setText(aD);
				etaL.setText(aE);
				if(aBar >= 0) {
					bar.setSelection(aBar);
				}
			}
		});
	}
}
