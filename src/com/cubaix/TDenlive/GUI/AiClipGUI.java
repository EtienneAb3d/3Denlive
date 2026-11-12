package com.cubaix.TDenlive.GUI;

import java.awt.image.BufferedImage;
import java.io.BufferedReader;
import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Vector;

import javax.imageio.ImageIO;

import org.eclipse.swt.SWT;
import org.eclipse.swt.events.SelectionAdapter;
import org.eclipse.swt.events.SelectionEvent;
import org.eclipse.swt.custom.StackLayout;
import org.eclipse.swt.graphics.GC;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.graphics.Rectangle;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Canvas;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.FileDialog;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.MessageBox;
import org.eclipse.swt.widgets.ProgressBar;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Spinner;
import org.eclipse.swt.widgets.Text;
import org.eclipse.swt.widgets.Tree;
import org.eclipse.swt.widgets.TreeItem;

import com.cubaix.TDenlive.TDConfig;
import com.cubaix.TDenlive.TDenlive;
import com.cubaix.TDenlive.ai.PythonAI;
import com.cubaix.TDenlive.ai.SoundGenerator;
import com.cubaix.TDenlive.ai.WanGenerator;
import com.cubaix.TDenlive.depth.DepthEstimator;
import com.cubaix.TDenlive.depth.DepthModel;
import com.cubaix.TDenlive.utils.ImageUtils;

/**
 * AI clip (button right of the 3D model one). A selector at the top of the form chooses what is generated
 * (the kind last chosen, TDConfig.aiKind, music the first time); each kind has its panel with its own models
 * (the one advised for the hardware selected first; the user's choice kept in 3De.conf when it differs):
 * - Video: a 2D video by Wan2.2-TI2V-5B (see ai.WanGenerator), transformer in Q8_0, Q6_K or Q4_K_M; prompt,
 * duration (at most 5 s), reference image, GPU needed. The progress window shows an approximate preview of the
 * video being denoised (animated latent frames).
 * - Music: MusicGen Small, Medium or Large; Sound effects: AudioLDM 2 or AudioLDM 2 Large (see ai.SoundGenerator),
 * with what the generator is made for, its licence and the hardware each model needs, on the GPU or else on the
 * CPU.
 * Each panel has a tree of the example prompts of its kind (res/ai/ai_prompts.txt).
 * Models are downloaded at their first use. The generation runs in background. The video (mp4) or the sound
 * (wav) is written into the AI folder of the project (with its prompt in a .txt) and added to the media list.
 */
public class AiClipGUI {
	//One generation at a time
	static boolean running = false;
	//Last prompt and duration (default of the next form)
	static String lastPrompt = "";
	static double lastSeconds = WanGenerator.MAX_SECONDS;
	static String lastImageDir = null;
	//Sound: last prompts and durations
	static String lastMusicPrompt = "";
	static String lastSfxPrompt = "";
	static double lastMusicSeconds = 10;
	static double lastSfxSeconds = 5;

	TDenlive tde;
	//Kind shown (VIDEO, MUSIC, SFX); music: a sound (MUSIC or SFX) instead of a video
	String kind = MUSIC;
	boolean music = true;
	SoundGenerator.Model soundModel = SoundGenerator.SMALL;
	WanGenerator.Variant videoModel = WanGenerator.Q8_0;
	String prompt = null;
	//Name of the clip in the media list (its file name in the AI folder); empty: AI_<date>
	String clipName = "";
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
	PythonAI generator = null;
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
		//Music also runs on the CPU: nothing is possible only without Python / PyTorch for this platform
		if(!SoundGenerator.isPlatformSupported()) {
			message(aTDe, SWT.ICON_INFORMATION, aTDe.gui.lngs.get("AI.UnsupportedAll"));
			return;
		}
		AiClipGUI aGUI = new AiClipGUI(aTDe);
		if(!aGUI.form()) {
			return;
		}
		aGUI.start();
	}

	/**
	 * Generate again (from the result window): the form with the last kind, prompt, name and duration
	 */
	void again() {
		if(form()) {
			start();
		}
		else {
			running = false;
		}
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

	String gb(long aBytes) {
		return DepthModel.gb(aBytes);
	}

	//Width of the wrapped labels and of the prompt (narrower in the two columns of the sound panel)
	int labelWidth = 560;

	Label addLabel(Composite aParent,String aText,int aIndent) {
		Label aL = new Label(aParent, SWT.WRAP);
		tde.gui.applyColorFont(aL);
		aL.setText(aText);
		GridData aGD = new GridData(GridData.FILL_HORIZONTAL);
		aGD.horizontalIndent = aIndent;
		aGD.widthHint = labelWidth-aIndent;
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
	 * Selector Video / Music / Sound effects (the kind last chosen, music the first time), then the panel of
	 * the kind: its models (the one advised for the hardware selected first), where it runs, prompt, duration,
	 * downloads
	 * @return false if canceled
	 */
	boolean form() {
		final boolean[] aResult = {false};
		Shell aShell = new Shell(tde.gui.shell, SWT.DIALOG_TRIM | SWT.APPLICATION_MODAL | SWT.RESIZE);
		aShell.setLayout(new GridLayout(1, false));
		tde.gui.applyColorFont(aShell);
		aShell.setImage(tde.gui.imgsSwt.getIcon("3Denlive32.png"));

		//Kind of generation
		Composite aKind = new Composite(aShell, SWT.NONE);
		tde.gui.applyColorFont(aKind);
		aKind.setLayoutData(new GridData(GridData.FILL_HORIZONTAL));
		GridLayout aKGL = new GridLayout(1+KINDS.length, false);
		tde.gui.applyNoMargin(aKGL);
		aKGL.horizontalSpacing = 12;
		aKind.setLayout(aKGL);
		Label aKL = new Label(aKind, SWT.NONE);
		tde.gui.applyColorFont(aKL);
		aKL.setText(lng("AI.Kind"));
		aKL.setFont(tde.gui.fontsSwt.robotoBold);
		Button[] aKindB = new Button[KINDS.length];
		for(int k = 0;k < KINDS.length;k++) {
			aKindB[k] = new Button(aKind, SWT.RADIO);
			tde.gui.applyColorFont(aKindB[k]);
			aKindB[k].setText(lng(KIND_KEYS[k]));
			aKindB[k].setData(KINDS[k]);
		}
		Label aSep = new Label(aShell, SWT.SEPARATOR | SWT.HORIZONTAL);
		aSep.setLayoutData(new GridData(GridData.FILL_HORIZONTAL));

		Composite aStack = new Composite(aShell, SWT.NONE);
		tde.gui.applyColorFont(aStack);
		aStack.setLayoutData(new GridData(GridData.FILL_BOTH));
		StackLayout aSL = new StackLayout();
		aStack.setLayout(aSL);
		Composite[] aPanels = new Composite[KINDS.length];
		Runnable[] aChecks = new Runnable[KINDS.length];
		for(int k = 0;k < KINDS.length;k++) {
			aPanels[k] = panel(aStack);
			aChecks[k] = VIDEO.equals(KINDS[k]) ? createVideoPanel(aPanels[k]) : createSoundPanel(aPanels[k], KINDS[k]);
		}

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
				prompt = null;
				aChecks[kindIndex(kind)].run();
				if(prompt != null) {
					tde.config.aiKind = kind;
					tde.config.saveGeneral(tde.configPath);
					aResult[0] = true;
					aShell.close();
				}
			}
		});

		Runnable aShowKind = () -> {
			int k = kindIndex(kind);
			music = !VIDEO.equals(kind);
			if(music) {
				soundModel = (SoundGenerator.Model)aPanels[k].getData(MODEL);
			}
			aSL.topControl = aPanels[k];
			aShell.setText(lng(KIND_TITLES[k]));
			aOk.setEnabled(music || WanGenerator.isPlatformSupported());
			aStack.layout(true, true);
		};
		SelectionAdapter aKindL = new SelectionAdapter() {
			@Override
			public void widgetSelected(SelectionEvent e) {
				if(!((Button)e.widget).getSelection()) {
					return;
				}
				kind = (String)e.widget.getData();
				aShowKind.run();
				focusPrompt(aSL.topControl);
			}
		};
		for(Button aB : aKindB) {
			aB.addSelectionListener(aKindL);
		}
		//Last kind (music the first time), not the video when it is not possible here
		kind = kindIndex(tde.config.aiKind) >= 0 ? tde.config.aiKind : MUSIC;
		if(VIDEO.equals(kind) && !WanGenerator.isPlatformSupported()) {
			kind = MUSIC;
		}
		for(Button aB : aKindB) {
			aB.setSelection(kind.equals(aB.getData()));
		}
		aShowKind.run();

		center(aShell);
		aShell.open();
		focusPrompt(aSL.topControl);
		while(!aShell.isDisposed()) {
			if(!tde.gui.display.readAndDispatch()) {
				tde.gui.display.sleep();
			}
		}
		return aResult[0];
	}

	static final String VIDEO = "video";
	static final String MUSIC = SoundGenerator.MUSIC;
	static final String SFX = SoundGenerator.SFX;
	static final String[] KINDS = {VIDEO, MUSIC, SFX};
	static final String[] KIND_KEYS = {"AI.KindVideo", "AI.KindMusic", "AI.KindSfx"};
	static final String[] KIND_TITLES = {"AI.Title", "AI.MusicTitle", "AI.SfxTitle"};
	//Data of a sound panel: its model chosen
	static final String MODEL = "AiClipGUI.model";

	static int kindIndex(String aKind) {
		for(int k = 0;k < KINDS.length;k++) {
			if(KINDS[k].equals(aKind)) {
				return k;
			}
		}
		return -1;
	}

	Composite panel(Composite aParent) {
		Composite aP = new Composite(aParent, SWT.NONE);
		tde.gui.applyColorFont(aP);
		GridLayout aGL = new GridLayout(1, false);
		aGL.marginWidth = 0;
		aP.setLayout(aGL);
		return aP;
	}

	void focusPrompt(Control aPanel) {
		Object aT = aPanel == null ? null : aPanel.getData(PROMPT);
		if(aT instanceof Text && !((Text)aT).isDisposed()) {
			((Text)aT).setFocus();
		}
	}

	static final String PROMPT = "AiClipGUI.prompt";

	Text createPrompt(Composite aParent,String aLabel,String aText) {
		addLabel(aParent, aLabel, 0);
		Text aPrompt = new Text(aParent, SWT.MULTI | SWT.WRAP | SWT.BORDER | SWT.V_SCROLL);
		tde.gui.applyColorFont(aPrompt);
		GridData aPGD = new GridData(GridData.FILL_HORIZONTAL);
		aPGD.widthHint = labelWidth;
		aPGD.heightHint = tde.gui.h(70);
		aPrompt.setLayoutData(aPGD);
		aPrompt.setText(aText);
		aPrompt.selectAll();
		return aPrompt;
	}

	/**
	 * Two columns in aPanel (whose data PROMPT will be the prompt): {left, right}. The labels are narrower.
	 */
	Composite[] columns(Composite aPanel) {
		labelWidth = 520;
		GridLayout aPGL = new GridLayout(2, false);
		aPGL.marginWidth = 0;
		aPGL.horizontalSpacing = 16;
		aPanel.setLayout(aPGL);
		Composite aLeft = panel(aPanel);
		aLeft.setLayoutData(new GridData(GridData.FILL_VERTICAL));
		Composite aRight = panel(aPanel);
		aRight.setLayoutData(new GridData(GridData.FILL_BOTH));
		return new Composite[] {aLeft, aRight};
	}

	/**
	 * A model in the list of a panel
	 */
	static class Choice {
		Object model;
		//Name (with its size), hardware needed, what it is good at
		String name;
		String details;
		boolean advised;
	}

	/**
	 * Radio buttons of the models of a panel (the advised one in bold), aOnChange called with the model chosen
	 */
	void createModelChoice(Composite aParent,Choice[] aChoices,Object aSelected,java.util.function.Consumer<Object> aOnChange) {
		Label aML = addLabel(aParent, lng("AI.MusicModel"), 0);
		((GridData)aML.getLayoutData()).verticalIndent = 8;
		for(Choice aC : aChoices) {
			Button aR = new Button(aParent, SWT.RADIO);
			tde.gui.applyColorFont(aR);
			aR.setText(aC.name+(aC.advised ? " "+lng("AI.MusicAdvised") : ""));
			if(aC.advised) {
				aR.setFont(tde.gui.fontsSwt.robotoBold);
			}
			aR.setSelection(aC.model == aSelected);
			aR.addSelectionListener(new SelectionAdapter() {
				@Override
				public void widgetSelected(SelectionEvent e) {
					if(aR.getSelection()) {
						aOnChange.accept(aC.model);
					}
				}
			});
			addLabel(aParent, aC.details, 20);
		}
	}

	String downloaded(boolean aDone) {
		return aDone ? " "+lng("AI.MusicDownloaded") : "";
	}

	/**
	 * Video panel (Wan2.2): GPU needed, variants of the model, free GPU memory; prompt, duration, reference
	 * image, downloads, licence
	 * @return the check of the OK button: sets prompt (left null when the form is not valid)
	 */
	Runnable createVideoPanel(Composite aPanel) {
		if(!WanGenerator.isPlatformSupported()) {
			Label aU = addLabel(aPanel, lng("AI.Unsupported").replace("%GB%", gb(WanGenerator.Q4_K_M.gpuMemory)), 0);
			aU.setForeground(tde.gui.colorsSwt.RED);
			return () -> {};
		}
		Composite[] aCols = columns(aPanel);
		Composite aLeft = aCols[0];
		Composite aRight = aCols[1];
		//GPU needed: shown first, in bold
		Label aWarn = addLabel(aLeft, lng("AI.GpuNeeded").replace("%GB%", gb(WanGenerator.Q4_K_M.gpuMemory)), 0);
		aWarn.setFont(tde.gui.fontsSwt.robotoBold);

		WanGenerator.Variant aDefault = WanGenerator.defaultVariant();
		WanGenerator.Variant aChosen = WanGenerator.variantById(tde.config.aiVideoModel);
		videoModel = aChosen != null ? aChosen : aDefault;
		Choice[] aChoices = new Choice[WanGenerator.VARIANTS.length];
		for(int v = 0;v < aChoices.length;v++) {
			WanGenerator.Variant aV = WanGenerator.VARIANTS[v];
			Choice aC = aChoices[v] = new Choice();
			aC.model = aV;
			aC.advised = aV == aDefault;
			aC.name = lng("AI.WanModelName").replace("%NAME%", aV.id).replace("%GB%", gb(aV.size))
					+downloaded(aV.isDownloaded(tde.tdeDir));
			aC.details = lng("AI.WanHw").replace("%GB%", gb(aV.gpuMemory))+"\n"+lng("AI.Wan"+aV.id.replace("_", "")+"Note");
		}
		Label[] aFree = {null};
		Label[] aDownload = {null};
		Runnable aShow = () -> {
			long[] aBest = DepthEstimator.bestGpu();
			if(aBest != null) {
				aFree[0].setText(lng("AI.GpuFree").replace("%GB%", gb(aBest[1]))
						+(aBest[1] < videoModel.gpuMemory ? "\n"+lng("AI.GpuTooSmall") : ""));
				aFree[0].setForeground(aBest[1] < videoModel.gpuMemory ? tde.gui.colorsSwt.RED : aWarn.getForeground());
			}
			long aBytes = WanGenerator.modelBytesToDownload(tde.tdeDir, videoModel)+WanGenerator.envBytesToInstall(tde.tdeDir);
			aDownload[0].setText(aBytes > 0 ? lng("AI.Download").replace("%GB%", gb(aBytes)) : lng("AI.MusicReady"));
			aPanel.layout(true, true);
		};
		createModelChoice(aLeft, aChoices, videoModel, aM -> {
			videoModel = (WanGenerator.Variant)aM;
			aShow.run();
		});
		aFree[0] = addLabel(aLeft, " \n ", 0);
		((GridData)aFree[0].getLayoutData()).verticalIndent = 8;

		Text aName = createNameField(aRight, VIDEO);
		Text aPrompt = createPrompt(aRight, lng("AI.Prompt"), lastPrompt);
		aPanel.setData(PROMPT, aPrompt);
		addLabel(aRight, lng("AI.PromptExamples"), 0);
		Tree aTree = createExamples(aRight, VIDEO);
		//Lower than for the sounds: the reference image row is below
		((GridData)aTree.getLayoutData()).heightHint = tde.gui.h(200);
		aTree.addListener(SWT.Selection, e -> {
			TreeItem aI = (TreeItem)e.item;
			Object aD = aI == null ? null : aI.getData();
			if(aD instanceof String[]) {
				aPrompt.setText(((String[])aD)[3]);
				aName.setText(((String[])aD)[4]);
			}
		});
		aTree.addListener(SWT.DefaultSelection, e -> aPrompt.setFocus());

		Composite aDur = new Composite(aRight, SWT.NONE);
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

		createImageRow(aRight, aDMax);

		aDownload[0] = addLabel(aRight, " \n ", 0);
		addLabel(aRight, lng("AI.Note2D"), 0);
		addLabel(aRight, lng("AI.Licence"), 0);
		labelWidth = 560;
		aShow.run();
		return () -> {
			String aP = aPrompt.getText().trim();
			if(aP.isEmpty()) {
				aPrompt.setFocus();
				return;
			}
			if(imagePath != null && !new File(imagePath).isFile()) {
				imagePath = null;
			}
			prompt = aP;
			clipName = aName.getText().trim();
			seconds = aSeconds.getSelection()/10.0;
			lastPrompt = aP;
			lastSeconds = seconds;
			tde.config.aiVideoModel = videoModel == aDefault ? "" : videoModel.id;
		};
	}

	/**
	 * Panel of a sound kind (MUSIC: MusicGen, SFX: AudioLDM 2): what the generator is made for and its licence,
	 * its models (the one advised for the hardware selected first) with the hardware each one needs, where it
	 * will run; prompt with the tree of the examples of the kind, duration, downloads.
	 * The model chosen is the data MODEL of aPanel.
	 * @return the check of the OK button: sets prompt (left null when the form is not valid)
	 */
	Runnable createSoundPanel(Composite aPanel,String aKind) {
		boolean aSfx = SFX.equals(aKind);
		Composite[] aCols = columns(aPanel);
		Composite aLeft = aCols[0];
		Composite aRight = aCols[1];
		Label aIntro = addLabel(aLeft, lng(aSfx ? "AI.SfxIntro" : "AI.MusicIntro"), 0);
		aIntro.setFont(tde.gui.fontsSwt.robotoBold);
		addLabel(aLeft, lng(aSfx ? "AI.GenSfxDesc" : "AI.GenMusicDesc"), 0);

		SoundGenerator.Model[] aModels = SoundGenerator.models(aKind);
		SoundGenerator.Model aDefault = SoundGenerator.defaultModel(aKind);
		SoundGenerator.Model aChosen = SoundGenerator.byId(aKind, aSfx ? tde.config.aiSfxModel : tde.config.aiMusicModel);
		aPanel.setData(MODEL, aChosen != null ? aChosen : aDefault);
		Choice[] aChoices = new Choice[aModels.length];
		for(int m = 0;m < aModels.length;m++) {
			SoundGenerator.Model aM = aModels[m];
			Choice aC = aChoices[m] = new Choice();
			aC.model = aM;
			aC.advised = aM == aDefault;
			aC.name = lng("AI.MusicModelName").replace("%NAME%", aM.id).replace("%PARAMS%", aM.params)
					.replace("%GB%", gb(aM.downloadSize()))+downloaded(aM.isDownloaded(tde.tdeDir));
			aC.details = hwText(aM)+"\n"+lng("AI.Model"+aM.id.replaceAll("[^A-Za-z0-9]", "")+"Note");
		}
		Label[] aWhere = {null};
		Label[] aDownload = {null};
		Spinner[] aSeconds = {null};
		Label[] aDMax = {null};
		Runnable aShow = () -> {
			SoundGenerator.Model aM = (SoundGenerator.Model)aPanel.getData(MODEL);
			soundModel = aM;
			String aReason = SoundGenerator.cpuReason(aM);
			long[] aBest = DepthEstimator.bestGpu();
			long aRam = SoundGenerator.ramSize();
			String aW;
			boolean aRed = false;
			if(aReason == null) {
				aW = lng("AI.MusicOnGpu");
				if(aBest != null) {
					aW += " "+lng("AI.MusicGpuFree").replace("%GB%", gb(aBest[1]));
					if(aBest[1] < aM.gpuMemory()) {
						//Apple Silicon: the memory is shared, the CPU would not do better
						aW += "\n"+lng("AI.MusicMemoryLow").replace("%GB%", gb(aM.gpuMemory()));
						aRed = true;
					}
				}
			}
			else {
				aW = lng("AI.MusicOnCpu")+" "+lng("AI.MusicCpu"+aReason)
						.replace("%FREE%", aBest == null ? "?" : gb(aBest[1])).replace("%GB%", gb(aM.cudaMemory));
				if(aRam > 0 && aRam < aM.cpuMemory) {
					aW += "\n"+lng("AI.MusicRamLow").replace("%RAM%", gb(aRam)).replace("%GB%", gb(aM.cpuMemory));
					aRed = true;
				}
				else if(aSfx || aM != aModels[0]) {
					aW += "\n"+lng(aSfx ? "AI.SfxCpuSlow" : "AI.MusicCpuSlow");
				}
			}
			aWhere[0].setText(aW);
			aWhere[0].setForeground(aRed ? tde.gui.colorsSwt.RED : aIntro.getForeground());
			int aMax = (int)aM.maxSeconds;
			aSeconds[0].setMaximum(aMax);
			aSeconds[0].setSelection(Math.max(1, Math.min(aMax, (int)Math.round(aSfx ? lastSfxSeconds : lastMusicSeconds))));
			aDMax[0].setText(lng("AI.MusicDurationMax").replace("%MAX%", ""+aMax).replace("%KHZ%", ""+aM.sampleRate/1000));
			long aBytes = aM.bytesToDownload(tde.tdeDir)+aM.envBytesToInstall(tde.tdeDir);
			aDownload[0].setText(aBytes > 0 ? lng("AI.MusicDownload").replace("%GB%", gb(aBytes)) : lng("AI.MusicReady"));
			aPanel.layout(true, true);
		};
		createModelChoice(aLeft, aChoices, aPanel.getData(MODEL), aM -> {
			aPanel.setData(MODEL, aM);
			aShow.run();
		});
		aWhere[0] = addLabel(aLeft, " \n ", 0);
		((GridData)aWhere[0].getLayoutData()).verticalIndent = 8;

		Text aName = createNameField(aRight, aKind);
		Text aPrompt = createPrompt(aRight, lng(aSfx ? "AI.SfxPrompt" : "AI.MusicPrompt"), aSfx ? lastSfxPrompt : lastMusicPrompt);
		aPanel.setData(PROMPT, aPrompt);
		addLabel(aRight, lng("AI.PromptExamples"), 0);
		Tree aTree = createExamples(aRight, aKind);
		aTree.addListener(SWT.Selection, e -> {
			TreeItem aI = (TreeItem)e.item;
			Object aD = aI == null ? null : aI.getData();
			if(aD instanceof String[]) {
				aPrompt.setText(((String[])aD)[3]);
				aName.setText(((String[])aD)[4]);
			}
		});
		aTree.addListener(SWT.DefaultSelection, e -> aPrompt.setFocus());

		Composite aDur = new Composite(aRight, SWT.NONE);
		tde.gui.applyColorFont(aDur);
		aDur.setLayoutData(new GridData(GridData.FILL_HORIZONTAL));
		GridLayout aDGL = new GridLayout(3, false);
		tde.gui.applyNoMargin(aDGL);
		aDur.setLayout(aDGL);
		Label aDL = new Label(aDur, SWT.NONE);
		tde.gui.applyColorFont(aDL);
		aDL.setText(lng("AI.Duration"));
		aSeconds[0] = new Spinner(aDur, SWT.BORDER);
		aSeconds[0].setMinimum((int)SoundGenerator.MIN_SECONDS);
		aSeconds[0].setIncrement(1);
		aDMax[0] = new Label(aDur, SWT.NONE);
		tde.gui.applyColorFont(aDMax[0]);
		aDMax[0].setLayoutData(new GridData(GridData.FILL_HORIZONTAL));

		aDownload[0] = addLabel(aRight, " \n ", 0);
		addLabel(aRight, lng("AI.SoundNote"), 0);
		labelWidth = 560;
		aShow.run();
		return () -> {
			String aP = aPrompt.getText().trim();
			if(aP.isEmpty()) {
				aPrompt.setFocus();
				return;
			}
			SoundGenerator.Model aM = (SoundGenerator.Model)aPanel.getData(MODEL);
			soundModel = aM;
			prompt = aP;
			clipName = aName.getText().trim();
			seconds = aSeconds[0].getSelection();
			//Kept in 3De.conf: the model only when it is not the advised one
			String aId = aM == aDefault ? "" : aM.id;
			if(aSfx) {
				lastSfxPrompt = aP;
				lastSfxSeconds = seconds;
				tde.config.aiSfxModel = aId;
			}
			else {
				lastMusicPrompt = aP;
				lastMusicSeconds = seconds;
				tde.config.aiMusicModel = aId;
			}
		};
	}

	/**
	 * Hardware needed by a sound model
	 */
	String hwText(SoundGenerator.Model aM) {
		return lng("AI.MusicHw").replace("%CUDA%", gb(aM.cudaMemory))
				.replace("%MAC%", gb(aM.macMemory)).replace("%CPU%", gb(aM.cpuMemory));
	}

	/**
	 * Example prompts of res/ai/ai_prompts.txt, one per line
	 * ("kind|Category/Subcategory|BPM|Name|prompt"): {kind, path, bpm, prompt, name, description}.
	 * The prompt stays in English (the language of the models); the name and the description shown come from
	 * res/ai/ai_prompts_<aLng>.txt ("kind|English name|name|description") when the language has it, else
	 * the English ones.
	 */
	static Vector<String[]> soundExamples(String aLng) {
		java.util.Map<String,String[]> aTr = new java.util.HashMap<String,String[]>();
		if(aLng != null && !"EN".equalsIgnoreCase(aLng)) {
			try(InputStream aIS = AiClipGUI.class.getResourceAsStream("/com/cubaix/TDenlive/res/ai/ai_prompts_"+aLng.toUpperCase()+".txt")) {
				if(aIS != null) {
					BufferedReader aR = new BufferedReader(new InputStreamReader(aIS, StandardCharsets.UTF_8));
					String aL;
					while((aL = aR.readLine()) != null) {
						String[] aF = aL.trim().split("[|]", 4);
						if(aL.startsWith("#") || aF.length < 4) {
							continue;
						}
						aTr.put(aF[0].trim()+"|"+aF[1].trim(), new String[] {aF[2].trim(), aF[3].trim()});
					}
				}
			}
			catch(Throwable t) {
				t.printStackTrace(System.err);
			}
		}
		Vector<String[]> aEx = new Vector<String[]>();
		try(InputStream aIS = AiClipGUI.class.getResourceAsStream("/com/cubaix/TDenlive/res/ai/ai_prompts.txt")) {
			BufferedReader aR = new BufferedReader(new InputStreamReader(aIS, StandardCharsets.UTF_8));
			String aL;
			while((aL = aR.readLine()) != null) {
				aL = aL.trim();
				String[] aF = aL.split("[|]", 5);
				if(aL.startsWith("#") || aF.length < 5) {
					continue;
				}
				String aPrompt = aF[4].trim();
				String[] aT = aTr.get(aF[0].trim()+"|"+aF[3].trim());
				aEx.add(new String[] {aF[0].trim(), aF[1].trim(), aF[2].trim(), aPrompt
						, aT == null ? aF[3].trim() : aT[0]
						//The tempo is shown first, not again at the end of the prompt
						, aT == null ? aPrompt.replaceAll(",\\s*[0-9]+ bpm$", "") : aT[1]});
			}
		}
		catch(Throwable t) {
			t.printStackTrace(System.err);
		}
		return aEx;
	}

	/**
	 * Name of a category, translated when the language has it (AI.PromptCat<letters of the name>)
	 */
	String categoryName(String aName) {
		String aT = lng("AI.PromptCat"+aName.replaceAll("[^A-Za-z]", ""));
		return aT == null || aT.startsWith("(") ? aName : aT;
	}

	/**
	 * Tree of the example prompts of a kind: category > subcategory > prompts, in the order of the file; music
	 * prompts by tempo in their subcategory. A subcategory named as its category is not a level.
	 * Leaves: "name : description" in the interface language (music: the tempo first), data {kind, path, bpm,
	 * prompt in English, name, description}: a click fills in the English prompt and the translated name.
	 */
	Tree createExamples(Composite aPanel,String aKind) {
		Tree aTree = new Tree(aPanel, SWT.BORDER | SWT.SINGLE | SWT.V_SCROLL | SWT.H_SCROLL);
		tde.gui.applyColorFont(aTree);
		GridData aGD = new GridData(GridData.FILL_BOTH);
		aGD.widthHint = labelWidth;
		aGD.heightHint = tde.gui.h(300);
		aTree.setLayoutData(aGD);
		java.util.Map<String,Vector<String[]>> aLeaves = new java.util.LinkedHashMap<String,Vector<String[]>>();
		for(String[] aE : soundExamples(tde.config.lng)) {
			if(aKind.equals(aE[0])) {
				aLeaves.computeIfAbsent(aE[1], k -> new Vector<String[]>()).add(aE);
			}
		}
		java.util.Map<String,TreeItem> aNodes = new java.util.LinkedHashMap<String,TreeItem>();
		for(java.util.Map.Entry<String,Vector<String[]>> aEntry : aLeaves.entrySet()) {
			Vector<String[]> aList = aEntry.getValue();
			TreeItem aParent = null;
			String aPath = "";
			String aPrev = null;
			for(String aPart : aEntry.getKey().split("/")) {
				aPart = aPart.trim();
				if(aPart.isEmpty() || aPart.equals(aPrev)) {
					continue;
				}
				aPrev = aPart;
				aPath += "/"+aPart;
				TreeItem aNode = aNodes.get(aPath);
				if(aNode == null) {
					aNode = aParent == null ? new TreeItem(aTree, SWT.NONE) : new TreeItem(aParent, SWT.NONE);
					aNode.setText(categoryName(aPart));
					aNodes.put(aPath, aNode);
				}
				aParent = aNode;
			}
			if(aParent == null) {
				continue;
			}
			if(MUSIC.equals(aKind)) {
				aList.sort((a, b) -> Integer.compare(bpm(a), bpm(b)));
			}
			for(String[] aE : aList) {
				TreeItem aLeaf = new TreeItem(aParent, SWT.NONE);
				aLeaf.setText((aE[2].isEmpty() ? "" : aE[2]+" bpm · ")+aE[4]+" : "+aE[5]);
				aLeaf.setData(aE);
			}
		}
		//Number of prompts of each category
		for(TreeItem aN : aNodes.values()) {
			aN.setText(aN.getText()+" ("+countLeaves(aN)+")");
		}
		return aTree;
	}

	static int bpm(String[] aE) {
		try {
			return Integer.parseInt(aE[2]);
		}
		catch(Throwable t) {
			return 0;
		}
	}

	static int countLeaves(TreeItem aI) {
		if(aI.getData() instanceof String[]) {
			return 1;
		}
		int aN = 0;
		for(TreeItem aC : aI.getItems()) {
			aN += countLeaves(aC);
		}
		return aN;
	}

	/**
	 * Optional reference image: thumbnail, name, Choose / Remove. The video starts from it (cropped to the
	 * ratio of the video, whose orientation follows the image) and animates it according to the prompt.
	 */
	void createImageRow(Composite aShell,Label aSizeL) {
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
				FileDialog aFD = new FileDialog(aShell.getShell(), SWT.OPEN);
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
	 * Name of the clip as a file name: characters not allowed in a file name replaced, at most 80 characters
	 */
	static String fileBase(String aName) {
		String aB = aName == null ? "" : aName.replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", "_").replaceAll("\\s+", " ").trim();
		if(aB.length() > 80) {
			aB = aB.substring(0, 80).trim();
		}
		//No leading dot (hidden file) nor trailing dot (Windows)
		return aB.replaceAll("^[.]+", "").replaceAll("[. ]+$", "");
	}

	/**
	 * Field of the name of the clip in the media list, filled in by the examples; the name of the last
	 * generation of aKind when generating again
	 */
	Text createNameField(Composite aParent,String aKind) {
		Composite aRow = new Composite(aParent, SWT.NONE);
		tde.gui.applyColorFont(aRow);
		aRow.setLayoutData(new GridData(GridData.FILL_HORIZONTAL));
		GridLayout aGL = new GridLayout(2, false);
		tde.gui.applyNoMargin(aGL);
		aRow.setLayout(aGL);
		Label aL = new Label(aRow, SWT.NONE);
		tde.gui.applyColorFont(aL);
		aL.setText(lng("AI.ClipName"));
		Text aT = new Text(aRow, SWT.SINGLE | SWT.BORDER);
		tde.gui.applyColorFont(aT);
		aT.setLayoutData(new GridData(GridData.FILL_HORIZONTAL));
		aT.setMessage(lng("AI.ClipNameHint"));
		if(aKind.equals(kind) && clipName != null) {
			aT.setText(clipName);
		}
		return aT;
	}

	/**
	 * Folder of the generated videos: AI next to the project (~/3Denlive/AI before the first save)
	 */
	File outDir() {
		String aDir = tde.projectDir == null || tde.projectName == null ? tde.tdeDir : tde.projectDir;
		return new File(aDir, "AI");
	}

	/**
	 * Progress window (with the preview for a video), and generation thread
	 */
	void start() {
		running = true;
		canceled = false;
		generator = null;
		stepStartMS = -1;
		stepStartDone = 0;
		lastUpdateMS = 0;
		previewFrames = 1;
		previewFrame = 0;
		shell = new Shell(tde.gui.shell, SWT.DIALOG_TRIM | SWT.RESIZE);
		shell.setText(lng(KIND_TITLES[Math.max(0, kindIndex(kind))]));
		shell.setLayout(new GridLayout(1, false));
		tde.gui.applyColorFont(shell);
		shell.setImage(tde.gui.imgsSwt.getIcon("3Denlive32.png"));
		addLabel(shell, prompt.length() > 200 ? prompt.substring(0, 200)+"..." : prompt, 0).setFont(tde.gui.fontsSwt.robotoBold);
		if(music) {
			String aReason = SoundGenerator.cpuReason(soundModel);
			addLabel(shell, (SoundGenerator.MUSIC.equals(soundModel.kind) ? "MusicGen " : "")+soundModel.id+" : "+String.format("%.0f s", seconds)+" - "
					+lng(aReason == null ? "AI.MusicOnGpu" : "AI.MusicOnCpu"), 0);
		}
		else {
			addLabel(shell, WanGenerator.MODEL_NAME+" "+videoModel.id+" : "+String.format("%.1f s", seconds), 0);
			previewC = new Canvas(shell, SWT.BORDER | SWT.DOUBLE_BUFFERED);
			GridData aCGD = new GridData(GridData.FILL_BOTH);
			aCGD.widthHint = 560;
			aCGD.heightHint = width >= height ? 560*height/width : 420;
			previewC.setLayoutData(aCGD);
			previewC.addListener(SWT.Paint, e -> drawPreview(e.gc));
			previewC.addListener(SWT.Resize, e -> previewC.redraw());
		}
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
		if(!music) {
			animate();
		}

		File aOutDir = outDir();
		String aExt = music ? ".wav" : ".mp4";
		String aBase = fileBase(clipName);
		if(aBase.isEmpty()) {
			aBase = (music ? (SoundGenerator.SFX.equals(soundModel.kind) ? "AI_Sfx_" : "AI_Music_") : "AI_")+new SimpleDateFormat("yyyyMMdd_HHmmss").format(new Date());
		}
		//A name already taken in the folder: name_2, name_3...
		String aFree = aBase;
		for(int n = 2;new File(aOutDir, aFree+aExt).exists() || new File(aOutDir, aFree+".txt").exists();n++) {
			aFree = aBase+"_"+n;
		}
		aBase = aFree;
		File aOut = new File(aOutDir, aBase+aExt);
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
					PythonAI.Progress aProgress = new PythonAI.Progress() {
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
					};
					if(music) {
						SoundGenerator aMG = new SoundGenerator(tde.tdeDir, soundModel, aProgress);
						generator = aMG;
						aDone = aMG.generate(aPromptFile, seconds, aOut) && !canceled;
					}
					else {
						WanGenerator aWG = new WanGenerator(tde.tdeDir, videoModel, aProgress);
						generator = aWG;
						aDone = aWG.generate(aPromptFile, imagePath == null ? null : new File(imagePath), aFrames, width, height, aOut) && !canceled;
					}
				}
				catch(PythonAI.GenerationException e) {
					e.printStackTrace(System.err);
					if("OOM".equals(e.code)) {
						aError = music
								? lng("AI.MusicErrorOom").replace("%GB%", DepthModel.gb(soundModel.gpuMemory()))
								: lng("AI.ErrorOom").replace("%GB%", DepthModel.gb(videoModel.gpuMemory));
					}
					else if("NOGPU".equals(e.code)) {
						aError = lng("AI.ErrorNoGpu");
					}
					else {
						aError = lng(errorKey())+"\n"+e.code;
					}
				}
				catch(Throwable t) {
					t.printStackTrace(System.err);
					aError = lng(errorKey())+"\n"+(t.getMessage() == null ? t.toString() : t.getMessage());
				}
				if(!aDone) {
					aPromptFile.delete();
					if(canceled) {
						aOut.delete();
					}
				}
				final String aErr = aError;
				final boolean aOk = aDone;
				tde.gui.display.asyncExec(new Runnable() {
					@Override
					public void run() {
						if(!shell.isDisposed()) {
							shell.dispose();
						}
						if(aOk) {
							//Still running until the result is added or discarded
							new AiResultGUI(AiClipGUI.this, aOut, aPromptFile).open();
							return;
						}
						running = false;
						if(aErr != null) {
							message(tde, SWT.ICON_ERROR, aErr);
						}
					}
				});
			}
		});
		aTh.start();
	}

	/**
	 * Language key of the failure message of the kind generated
	 */
	String errorKey() {
		return !music ? "AI.Error" : SFX.equals(soundModel.kind) ? "AI.SfxError" : "AI.MusicError";
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
		boolean aSteps = "AI.Generating".equals(aKey) || "AI.GeneratingMusic".equals(aKey) || "AI.GeneratingSfx".equals(aKey);
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
