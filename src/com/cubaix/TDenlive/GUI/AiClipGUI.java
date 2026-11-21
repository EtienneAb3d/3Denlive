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
import com.cubaix.TDenlive.ai.ColorGenerator;
import com.cubaix.TDenlive.ai.MaskGenerator;
import com.cubaix.TDenlive.ai.PromptExtender;
import com.cubaix.TDenlive.ai.PythonAI;
import com.cubaix.TDenlive.ai.SceneGenerator;
import com.cubaix.TDenlive.model3d.SmartFill;
import com.cubaix.TDenlive.ai.SoundGenerator;
import com.cubaix.TDenlive.ai.SpeechGenerator;
import com.cubaix.TDenlive.ai.WanGenerator;
import com.cubaix.TDenlive.depth.DepthEstimator;
import com.cubaix.TDenlive.depth.DepthModel;
import com.cubaix.TDenlive.medias.AudioMedia;
import com.cubaix.TDenlive.medias.ColorBck;
import com.cubaix.TDenlive.medias.Media;
import com.cubaix.TDenlive.medias.Model3DMedia;
import com.cubaix.TDenlive.medias.ShapesMedia;
import com.cubaix.TDenlive.medias.StereoImage;
import com.cubaix.TDenlive.medias.StereoMovie;
import com.cubaix.TDenlive.utils.ImageUtils;
import com.cubaix.TDenlive.utils.SubtitleFile;

/**
 * AI clip (button right of the 3D model one). A selector at the top of the form chooses what is generated
 * (always the music first, the video panel asking for the download of the LLM of its prompt; "Something else":
 * the same kind); each kind has its panel with its own models
 * (the one advised for the hardware selected first; the user's choice kept in 3De.conf when it differs):
 * - Video: a 2D video by Wan2.2-TI2V-5B (see ai.WanGenerator), transformer in Q8_0, Q6_K or Q4_K_M; prompt,
 * duration (at most 5 s), reference image, GPU needed. The progress window shows an approximate preview of the
 * video being denoised (animated latent frames).
 * - Mask: objects of an image or a video (stereo side by side included) delimited by clicks and tracked by SAM 2.1
 * (see ai.MaskGenerator) in a large editor window (AiMaskGUI), which writes a black and white mask (image or video)
 * for the Transparency processor.
 * - 3D scene: a photo turned into a scene of 3D Gaussians by Depth Pro or Flash3D (see ai.SceneGenerator), explored with a pair
 * of stereo cameras in AiSceneGUI (spacing and screen plane set by sliders, the pair moved in the scene), whose views
 * are added to the media list; a scene saved before (.ply) can be opened again.
 * - Subtitles / Voice translation: the speech of a video transcribed (Qwen3-ASR or Parakeet), translated into one or
 * several languages by the LLM, or spoken in another language with the cloned voice (Qwen3-TTS), see
 * ai.SpeechGenerator; result window AiSpeechResultGUI.
 * - Music: MusicGen Small, Medium or Large; Sound effects: Woosh-DFlow or Woosh-Flow (see ai.SoundGenerator),
 * with what the generator is made for, its licence and the hardware each model needs, on the GPU or else on the
 * CPU.
 * Each panel has a tree of the example prompts of its kind (res/ai/ai_prompts.txt); the video panel shows those
 * of the kind "image" (animate the image) while a reference image is chosen.
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
	//Music: short by default, then continued from the series window (AiSoundSeriesGUI)
	static double lastMusicSeconds = 3;
	static double lastSfxSeconds = 5;

	TDenlive tde;
	//Kind shown (VIDEO, MUSIC, SFX); music: a sound (MUSIC or SFX) instead of a video
	String kind = MUSIC;
	boolean music = true;
	//Colorization of a photo (COLOR) instead of a generation
	boolean color = false;
	ColorGenerator.Model colorModel = ColorGenerator.MODELSCOPE;
	//Mask of objects (MASK): the editor window instead of a generation
	boolean mask = false;
	MaskGenerator.Model maskModel = MaskGenerator.TINY;
	AiMaskGUI.Source maskSrc = new AiMaskGUI.Source();
	//3D scene of a photo (SCENE): the viewer AiSceneGUI after the computation, or at once for a scene saved before
	boolean scene = false;
	SceneGenerator.Detail sceneDetail = SceneGenerator.FINE;
	SceneGenerator.Model sceneModel = SceneGenerator.MODELS[0];
	//Scene file to open instead of a computation (button of the panel), else null
	String scenePly = null;
	//Speech of a video (SUBS: subtitles, DUB: voice translation) instead of a generation
	boolean speech = false;
	boolean dub = false;
	SpeechGenerator.Model asrModel = SpeechGenerator.QWEN3_ASR;
	SpeechGenerator.Model ttsModel = SpeechGenerator.TTS_17;
	//Language spoken (English name, "": recognized), subtitle languages ("": the spoken one), voice language
	String spokenLang = "";
	Vector<String> subLangs = new Vector<String>();
	String dubLang = "English";
	//Voice of the cascade (SpeechGenerator.VOICE_*)
	String dubVoice = SpeechGenerator.VOICE_MALE;
	/**
	 * Video or sound whose speech is used: its file, the media of the library it comes from (null: a file of the
	 * disk), the name shown
	 */
	static class SpeechSource {
		String path = null;
		Media media = null;
		String name = null;
		//Subtitles file of its speech given by the user (.srt, .vtt): used instead of the recognition, else null
		String srt = null;
	}
	SpeechSource subsSrc = new SpeechSource();
	SpeechSource dubSrc = new SpeechSource();

	SpeechSource speechSource() {
		return dub ? dubSrc : subsSrc;
	}
	SoundGenerator.Model soundModel = SoundGenerator.SMALL;
	WanGenerator.Variant videoModel = WanGenerator.Q8_0;
	String prompt = null;
	//Name of the clip in the media list (its file name in the AI folder); empty: AI_<date>
	String clipName = "";
	double seconds = WanGenerator.MAX_SECONDS;
	/**
	 * An image chosen in an image row (createImageRow()): its path (null: none) and the name shown when it comes
	 * from the library or from a video (its left view / frame exported to a PNG), else null
	 */
	static class RefImage {
		String path = null;
		String name = null;
	}
	//Reference image animated by the video model (null: from the text only)
	RefImage videoImg = new RefImage();
	//Black and white photo of the colorization panel
	RefImage colorImg = new RefImage();
	//Photo of the 3D scene panel
	RefImage sceneImg = new RefImage();
	//Images of the music and sound effects panels (a prompt suited to them proposed by the LLM)
	RefImage musicImg = new RefImage();
	RefImage sfxImg = new RefImage();
	//Refresh of the reference image row
	Runnable imageRowShow = null;

	/**
	 * Reference image used
	 */
	String refImage() {
		return videoImg.path;
	}
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
	//Number of the generation run (a canceled run that ends late does not touch the next one)
	volatile int runId = 0;
	//Generator of a canceled run, whose process may still be ending (the next run waits for its end)
	PythonAI ending = null;
	long stepStartMS = -1;
	long stepStartDone = 0;
	long lastUpdateMS = 0;
	String stepKeyNow = null;

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

	/**
	 * Size of the video closest to the ratio of a reference image of aW x aH, so that it is not cropped: the
	 * same number of pixels as 1280x704 (same GPU memory and time), sides multiple of 32 (VAE 16 x patch 2),
	 * ratio limited to 9:16 .. 16:9 (the formats the model was trained with; beyond, the image is cropped to
	 * the limit by the script)
	 */
	void setSizeFor(int aW,int aH) {
		double aMax = WanGenerator.WIDTH/(double)WanGenerator.HEIGHT;
		double aR = Math.max(1/aMax, Math.min(aMax, aW/(double)aH));
		long aArea = (long)WanGenerator.WIDTH*WanGenerator.HEIGHT;
		//Sides multiple of 32 with 90 to 100 % of the pixels, the ratio closest to that of the image
		int aBW = WanGenerator.WIDTH;
		int aBH = WanGenerator.HEIGHT;
		double aBest = Double.MAX_VALUE;
		for(int h = 32;h <= WanGenerator.WIDTH;h += 32) {
			for(int w = 32;w <= WanGenerator.WIDTH;w += 32) {
				long aA = (long)w*h;
				if(aA > aArea || aA < aArea*0.9) {
					continue;
				}
				double aD = Math.abs(Math.log(w/(double)h/aR));
				if(aD < aBest-1e-9 || (aD < aBest+1e-9 && aA > (long)aBW*aBH)) {
					aBest = aD;
					aBW = w;
					aBH = h;
				}
			}
		}
		width = aBW;
		height = aBH;
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
	 * Retry (from the result window): the same generation at once, without the form
	 */
	void retry() {
		start();
	}

	/**
	 * Something else (from the result window): the form with the last kind, prompt, name and duration
	 */
	void again() {
		againKind = kind;
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

	/**
	 * The color of a processing message: violet while an action is in progress (loading a model, computing...),
	 * the normal text color otherwise
	 */
	void working(Label aL,boolean aWorking) {
		if(aL != null && !aL.isDisposed()) {
			aL.setForeground(aWorking ? tde.gui.colorsSwt.VIOLET : tde.gui.colorsSwt.CUBAIX_BLUE);
		}
	}

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
			aChecks[k] = VIDEO.equals(KINDS[k]) ? createVideoPanel(aPanels[k])
					: COLOR.equals(KINDS[k]) ? createColorPanel(aPanels[k])
					: MASK.equals(KINDS[k]) ? createMaskPanel(aPanels[k])
					: SCENE.equals(KINDS[k]) ? createScenePanel(aPanels[k])
					: SUBS.equals(KINDS[k]) || DUB.equals(KINDS[k]) ? createSpeechPanel(aPanels[k], DUB.equals(KINDS[k]))
					: createSoundPanel(aPanels[k], KINDS[k]);
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
		formOkB = aOk;
		aOk.setText(lng("AI.Generate"));
		aOk.setImage(tde.gui.imgsSwt.getIcon("checked.gif"));
		tde.gui.applyColorFont(aOk);
		aOk.setLayoutData(new GridData(GridData.FILL_HORIZONTAL));
		aOk.addSelectionListener(new SelectionAdapter() {
			@Override
			public void widgetSelected(SelectionEvent e) {
				prompt = null;
				if(!SCENE.equals(kind)) {
					scenePly = null;
				}
				aChecks[kindIndex(kind)].run();
				if(prompt != null) {
					//The form leaves the LLM of the video prompt (still loaded: unloaded only if the generation loads
					//another model, see PythonAI.beforeLoad())
					if(videoPair != null) {
						videoPair.hidden();
					}
					tde.config.aiKind = kind;
					tde.config.saveGeneral(tde.configPath);
					aResult[0] = true;
					aShell.close();
				}
			}
		});

		Runnable aShowKind = () -> {
			//The video panel needs the LLM of the prompt: download asked, loaded while the panel is shown
			if(videoPair != null) {
				if(VIDEO.equals(kind) && WanGenerator.isPlatformSupported()) {
					if(!videoPair.shown()) {
						kind = MUSIC;
						for(Button aB : aKindB) {
							aB.setSelection(kind.equals(aB.getData()));
						}
					}
				}
				if(!VIDEO.equals(kind)) {
					videoPair.hidden();
				}
			}
			int k = kindIndex(kind);
			color = COLOR.equals(kind);
			mask = MASK.equals(kind);
			scene = SCENE.equals(kind);
			dub = DUB.equals(kind);
			speech = dub || SUBS.equals(kind);
			music = !VIDEO.equals(kind) && !color && !mask && !scene && !speech;
			if(music) {
				soundModel = (SoundGenerator.Model)aPanels[k].getData(MODEL);
			}
			aSL.topControl = aPanels[k];
			aShell.setText(lng(KIND_TITLES[k]));
			aOk.setEnabled(!VIDEO.equals(kind) || WanGenerator.isPlatformSupported());
			//The mask opens its editor
			aOk.setText(lng(mask ? "AI.MaskOpen" : "AI.Generate"));
			aButtons.layout(true);
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
		//Always the music first (the video panel asks for the download of its LLM), but the kind of "Generate
		//again"; not the video when it is not possible here
		kind = againKind != null && kindIndex(againKind) >= 0 ? againKind : MUSIC;
		againKind = null;
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
	//Examples of the video panel when a reference image is chosen (res/ai/ai_prompts.txt), not a panel
	static final String IMAGE = "image";
	//Data of an examples tree: the kind of its examples
	static final String KIND_DATA = "AiClipGUI.kind";
	//Colorization of a black and white photo (DDColor, see ai.ColorGenerator)
	static final String COLOR = "color";
	//Mask of objects delimited and tracked (SAM 2.1, see ai.MaskGenerator and AiMaskGUI)
	static final String MASK = "mask";
	//Navigable 3D scene of a photo (Depth Pro or Flash3D, see ai.SceneGenerator and AiSceneGUI)
	static final String SCENE = "scene";
	//Speech of a video: subtitles in one or several languages, voice translation (see ai.SpeechGenerator)
	static final String SUBS = "subtitles";
	static final String DUB = "dub";
	static final String[] KINDS = {VIDEO, COLOR, MASK, SCENE, MUSIC, SFX, SUBS, DUB};
	static final String[] KIND_KEYS = {"AI.KindVideo", "AI.KindColor", "AI.KindMask", "AI.KindScene", "AI.KindMusic", "AI.KindSfx", "AI.KindSubs", "AI.KindDub"};
	static final String[] KIND_TITLES = {"AI.Title", "AI.ColorTitle", "AI.MaskTitle", "AI.SceneTitle", "AI.MusicTitle", "AI.SfxTitle", "AI.SubsTitle", "AI.DubTitle"};
	//OK button of the form (the panel of the 3D scene validates it to open a saved scene)
	Button formOkB = null;
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

	//Download of the rewriting LLM confirmed (session)
	static boolean extendConfirmed = false;
	//Prompt pair of the video panel (its LLM is loaded while the video panel is shown)
	PromptPair videoPair = null;
	//Kind of the form reopened by "Something else" (else the music panel first)
	String againKind = null;
	static String lastUserPrompt = "";
	//Pause in the typing before the prompt is rewritten
	static final int EXTEND_DELAY_MS = 800;

	/**
	 * Red warning when the LLM can't run comfortably on the GPU here, else null
	 */
	String llmWarning() {
		return llmWarning(false);
	}

	/**
	 * Same, for the analysis of an image (aImage: sound panels) or the rewriting of the video prompt
	 */
	String llmWarning(boolean aImage) {
		//Already loaded: its memory is taken by itself, no warning
		if(PromptExtender.isLoaded()) {
			return null;
		}
		long aFree = PromptExtender.freeGpuMemory();
		if(PromptExtender.isComfortable(aFree)) {
			return null;
		}
		String aNeed = DepthModel.gb(PromptExtender.gpuMemory());
		if(!PythonAI.hasGpu() || aFree < 0) {
			return lng(aImage ? "AI.ImageLlmNoGpu" : "AI.LlmNoGpu").replace("%NEED%", aNeed);
		}
		return lng(aImage ? "AI.ImageLlmLowMemory" : "AI.LlmLowMemory").replace("%FREE%", DepthModel.gb(aFree)).replace("%NEED%", aNeed);
	}

	//Window of the installation in progress (installWindow()), receives the progress of the extender
	PythonAI.Progress installProgress = null;

	/**
	 * Modal window: download of the LLM (and of the Python environment if needed), progress, Cancel
	 * @return true when installed, false if canceled or failed
	 */
	boolean installWindow(Shell aParent,PromptExtender aPE) {
		return installWindow(aParent, aPE, "AI.ExtendInstalling");
	}

	boolean installWindow(Shell aParent,PromptExtender aPE,String aTitleKey) {
		Shell aD = new Shell(aParent, SWT.DIALOG_TRIM | SWT.APPLICATION_MODAL);
		aD.setText(lng("AI.Title"));
		tde.gui.applyColorFont(aD);
		aD.setLayout(new GridLayout(1, false));
		Label aTitle = new Label(aD, SWT.WRAP);
		tde.gui.applyColorFont(aTitle);
		aTitle.setText(lng(aTitleKey));
		aTitle.setFont(tde.gui.fontsSwt.robotoBold);
		GridData aTGD = new GridData(GridData.FILL_HORIZONTAL);
		aTGD.widthHint = 460;
		aTitle.setLayoutData(aTGD);
		Label aStep = new Label(aD, SWT.WRAP);
		tde.gui.applyColorFont(aStep);
		aStep.setText(lng("AI.Starting"));
		working(aStep, true);
		GridData aSGD = new GridData(GridData.FILL_HORIZONTAL);
		aSGD.widthHint = 460;
		aStep.setLayoutData(aSGD);
		ProgressBar aBar = new ProgressBar(aD, SWT.HORIZONTAL);
		aBar.setMaximum(1000);
		aBar.setLayoutData(new GridData(GridData.FILL_HORIZONTAL));
		Button aCancel = new Button(aD, SWT.PUSH);
		tde.gui.applyColorFont(aCancel);
		aCancel.setText(lng("AI.Cancel"));
		aCancel.setLayoutData(new GridData(SWT.END, SWT.CENTER, false, false));
		final boolean[] aOk = {false};
		final String[] aErr = {null};
		installProgress = new PythonAI.Progress() {
			@Override
			public void status(String aKey, String aDetail, long aDone, long aTotal) {
				String aT = lng(aKey)+(aKey.startsWith("AI.Downloading") && aTotal > 0
						? " "+DepthModel.gb(aDone)+" / "+DepthModel.gb(aTotal)+" GB" : "");
				int aV = aTotal > 0 ? (int)Math.min(1000, 1000*aDone/aTotal) : -1;
				tde.gui.display.asyncExec(() -> {
					if(aD.isDisposed()) {
						return;
					}
					aStep.setText(aT);
					if(aV >= 0) {
						aBar.setSelection(aV);
					}
				});
			}
			@Override
			public boolean isCanceled() {
				return false;
			}
		};
		Thread aT = new Thread(() -> {
			try {
				aOk[0] = aPE.install();
			}
			catch(Throwable t) {
				t.printStackTrace(System.err);
				aErr[0] = t.getMessage();
			}
			tde.gui.display.asyncExec(() -> {
				if(!aD.isDisposed()) {
					aD.dispose();
				}
			});
		}, "PromptExtender install");
		aT.setDaemon(true);
		aCancel.addListener(SWT.Selection, e -> {
			aPE.close();
			aD.dispose();
		});
		aD.addListener(SWT.Close, e -> aPE.close());
		aD.pack();
		Rectangle aP = aParent.getBounds();
		Rectangle aS = aD.getBounds();
		aD.setLocation(aP.x + (aP.width - aS.width) / 2, aP.y + (aP.height - aS.height) / 2);
		aD.open();
		aT.start();
		while(!aD.isDisposed()) {
			if(!tde.gui.display.readAndDispatch()) {
				tde.gui.display.sleep();
			}
		}
		installProgress = null;
		if(aErr[0] != null) {
			message(tde, SWT.ICON_ERROR, lng("AI.ExtendError")+" "+aErr[0]);
		}
		return aOk[0];
	}

	/**
	 * The download of the model (and of the Python environment) accepted, asked once per session
	 */
	boolean confirmInstall(Shell aParent) {
		if(extendConfirmed) {
			return true;
		}
		long aBytes = PromptExtender.bytesToInstall(tde.tdeDir);
		if(aBytes <= 0) {
			return true;
		}
		MessageBox aMB = new MessageBox(aParent, SWT.ICON_QUESTION | SWT.OK | SWT.CANCEL);
		aMB.setText(lng("AI.Title"));
		String aWarn = llmWarning();
		aMB.setMessage(lng("AI.ExtendDownload").replace("%GB%", DepthModel.gb(aBytes))+(aWarn == null ? "" : "\n\n"+aWarn));
		extendConfirmed = aMB.open() == SWT.OK;
		return extendConfirmed;
	}

	//Analysis of an image by the LLM accepted (session): download, or the limits of the machine
	static boolean imageLlmConfirmed = false;

	/**
	 * Image of a sound panel: Qwen3-VL (PromptExtender) looks at it and proposes the prompt of a music or of a
	 * sound effect suited to it (with its duration for a sound effect), following the description already typed
	 * (any language) if it is not the previous proposal. ready() runs before the image is chosen: asks for the
	 * download of the model or warns about the limits of the machine, once per session. The LLM stays loaded
	 * after (resident), until another model is loaded (the generation of the sound).
	 */
	class SoundImage {
		final boolean sfx;
		final Text prompt;
		final Spinner seconds;
		final Button clean;
		Label status = null;
		PromptExtender extender = null;
		volatile boolean busy = false;
		//Image of the last proposal
		String lastImage = null;

		SoundImage(boolean aSfx,Text aPrompt,Spinner aSeconds,Button aClean) {
			sfx = aSfx;
			prompt = aPrompt;
			seconds = aSeconds;
			clean = aClean;
		}

		RefImage ref() {
			return sfx ? sfxImg : musicImg;
		}

		/**
		 * Before an image is chosen: the model downloaded (asked) or the limits of the machine told
		 * @return false: no image
		 */
		boolean ready() {
			Shell aShell = prompt.getShell();
			long aBytes = PromptExtender.bytesToInstall(tde.tdeDir);
			String aWarn = llmWarning(true);
			if(aBytes > 0) {
				MessageBox aMB = new MessageBox(aShell, SWT.ICON_QUESTION | SWT.OK | SWT.CANCEL);
				aMB.setText(lng("AI.Title"));
				aMB.setMessage(lng("AI.ImageLlmDownload").replace("%GB%", DepthModel.gb(aBytes))+(aWarn == null ? "" : "\n\n"+aWarn));
				if(aMB.open() != SWT.OK) {
					return false;
				}
				imageLlmConfirmed = true;
				if(!installWindow(aShell, extender(), "AI.ImageLlmInstalling")) {
					return false;
				}
				return true;
			}
			if(aWarn != null && !imageLlmConfirmed) {
				MessageBox aMB = new MessageBox(aShell, SWT.ICON_WARNING | SWT.OK | SWT.CANCEL);
				aMB.setText(lng("AI.Title"));
				aMB.setMessage(lng("AI.ImageLlmLimits").replace("%WARN%", aWarn));
				if(aMB.open() != SWT.OK) {
					return false;
				}
				imageLlmConfirmed = true;
			}
			return true;
		}

		/**
		 * The image chosen (or removed): a prompt proposed for a new image
		 */
		void analyze() {
			String aPath = ref().path;
			if(aPath == null || aPath.equals(lastImage) || status == null || busy) {
				return;
			}
			lastImage = aPath;
			busy = true;
			//Another image: the prompt is reset, the new one comes from the image alone
			prompt.setText("");
			String aRequest = "";
			setStatus(lng("AI.AnalyzingImage"), true);
			PromptExtender aPE = extender();
			Thread aT = new Thread(() -> {
				String aR = null;
				String aErr = null;
				try {
					aR = aPE.soundFromImage(aRequest, aPath, sfx ? PromptExtender.SFX_MODE : PromptExtender.MUSIC_MODE);
				}
				catch(Throwable t) {
					t.printStackTrace(System.err);
					aErr = t.getMessage() == null ? t.toString() : t.getMessage();
				}
				final String aRes = aR;
				final String aE = aErr;
				tde.gui.display.asyncExec(() -> {
					busy = false;
					if(prompt.isDisposed()) {
						return;
					}
					if(aE != null || aRes == null || aRes.isBlank()) {
						lastImage = null;
						setStatus(aE == null ? " " : lng("AI.ImagePromptError")+" "+aE);
						return;
					}
					String aP = aRes.trim();
					//Sound effect: "prompt|seconds"
					int aBar = aP.lastIndexOf('|');
					if(aBar > 0) {
						try {
							int aS = (int)Math.round(Double.parseDouble(aP.substring(aBar+1).replaceAll("[^0-9.]", "")));
							if(seconds != null && !seconds.isDisposed()) {
								seconds.setSelection(Math.max(seconds.getMinimum(), Math.min(seconds.getMaximum(), aS)));
							}
						}
						catch(NumberFormatException ex) {
							//No duration: the duration is left
						}
						aP = aP.substring(0, aBar).trim();
					}
					prompt.setText(clean != null && clean.getSelection() ? SoundGenerator.withClean(aP) : aP);
					setStatus(lng("AI.ImagePromptDone"));
					System.out.println("AI sound prompt from the image "+aPath+": "+aRes);
				});
			}, "Sound prompt from an image");
			aT.setDaemon(true);
			aT.start();
		}

		void setStatus(String aText) {
			setStatus(aText, false);
		}

		void setStatus(String aText,boolean aWorking) {
			if(status != null && !status.isDisposed()) {
				status.setText(aText);
				working(status, aWorking);
				status.getParent().layout(true);
			}
		}

		PromptExtender extender() {
			if(extender == null) {
				//The LLM still loaded (video panel, previous form) is taken back
				extender = PromptExtender.attach(tde.tdeDir, new PythonAI.Progress() {
					@Override
					public void status(String aKey, String aDetail, long aDone, long aTotal) {
						PythonAI.Progress aIP = installProgress;
						if(aIP != null) {
							aIP.status(aKey, aDetail, aDone, aTotal);
							return;
						}
						//Loading of the model: shown while the image is analyzed
						if(busy && ("AI.LoadingLlm".equals(aKey) || "AI.Starting".equals(aKey))) {
							tde.gui.display.asyncExec(() -> setStatus(lng("AI.AnalyzingImage")+" ("+lng(aKey)+")", true));
						}
					}
					@Override
					public boolean isCanceled() {
						return false;
					}
				});
			}
			return extender;
		}

		/**
		 * The form leaves the LLM (closed, generation started): it stays loaded until another model is loaded
		 */
		void hidden() {
			if(extender != null) {
				extender.detach();
				extender = null;
			}
		}
	}

	/**
	 * Prompt of the video in two boxes side by side: the description of the user (any language) and the final
	 * English prompt given to the model (editable). The description is rewritten by PromptExtender after a pause
	 * in the typing, and again when the reference image is chosen or removed (image to video mode). The video
	 * panel needs the LLM: shown() asks for its download and loads it (or takes it back if still loaded),
	 * hidden() leaves it loaded (other panel, form closed, generation started): it is unloaded only when another
	 * model has to be loaded (PythonAI.beforeLoad()).
	 */
	class PromptPair {
		Text user;
		Text fin;
		Label status;
		PromptExtender extender = null;
		boolean busy = false;
		boolean pending = false;
		boolean setting = false;
		boolean finEdited = false;
		int typing = 0;
		//Description and mode of the final prompt shown
		String lastInput = null;
		String lastImage = null;
		//Example clicked: its description (left box) and its English prompt, given to the LLM instead of the
		//description while the left box is unchanged
		String exampleDescription = null;
		String examplePrompt = null;

		PromptPair(Composite aParent) {
			addLabel(aParent, lng("AI.Prompt"), 0);
			//Red warning: the LLM will be slow or short of memory here
			String aWarn = llmWarning();
			if(aWarn != null) {
				Label aW = addLabel(aParent, aWarn, 0);
				aW.setForeground(tde.gui.display.getSystemColor(SWT.COLOR_RED));
			}
			Composite aBoxes = new Composite(aParent, SWT.NONE);
			tde.gui.applyColorFont(aBoxes);
			aBoxes.setLayoutData(new GridData(GridData.FILL_HORIZONTAL));
			GridLayout aGL = new GridLayout(2, true);
			tde.gui.applyNoMargin(aGL);
			aGL.horizontalSpacing = 8;
			aBoxes.setLayout(aGL);
			int aW = (labelWidth-8)/2;
			Label aUL = new Label(aBoxes, SWT.WRAP);
			tde.gui.applyColorFont(aUL);
			aUL.setText(lng("AI.PromptUser"));
			GridData aULGD = new GridData(GridData.FILL_HORIZONTAL);
			aULGD.widthHint = aW;
			aUL.setLayoutData(aULGD);
			Label aFL = new Label(aBoxes, SWT.WRAP);
			tde.gui.applyColorFont(aFL);
			aFL.setText(lng("AI.PromptFinal"));
			GridData aFLGD = new GridData(GridData.FILL_HORIZONTAL);
			aFLGD.widthHint = aW;
			aFL.setLayoutData(aFLGD);
			user = box(aBoxes, aW, lastUserPrompt);
			fin = box(aBoxes, aW, lastPrompt);
			if(!lastUserPrompt.trim().isEmpty()) {
				lastInput = lastUserPrompt.trim();
				lastImage = refImage();
			}
			Composite aRow = new Composite(aParent, SWT.NONE);
			tde.gui.applyColorFont(aRow);
			aRow.setLayoutData(new GridData(GridData.FILL_HORIZONTAL));
			GridLayout aRGL = new GridLayout(1, false);
			tde.gui.applyNoMargin(aRGL);
			aRow.setLayout(aRGL);
			status = new Label(aRow, SWT.NONE);
			tde.gui.applyColorFont(status);
			status.setLayoutData(new GridData(GridData.FILL_HORIZONTAL));
			user.addListener(SWT.Modify, e -> {
				if(setting) {
					return;
				}
				//Rewritten at each change of the description, once the typing pauses
				int aT = ++typing;
				if(!busy) {
					setStatus(lng("AI.ExtendSoon"), null);
				}
				tde.gui.display.timerExec(EXTEND_DELAY_MS, () -> {
					if(aT == typing && !user.isDisposed()) {
						request();
					}
				});
			});
			//Leaving the box: at once
			user.addListener(SWT.FocusOut, e -> {
				typing++;
				request();
			});
			fin.addListener(SWT.Modify, e -> {
				if(!setting) {
					finEdited = true;
				}
			});
			aRow.addListener(SWT.Dispose, e -> hidden());
		}

		Text box(Composite aParent,int aW,String aText) {
			Text aT = new Text(aParent, SWT.MULTI | SWT.WRAP | SWT.BORDER | SWT.V_SCROLL);
			tde.gui.applyColorFont(aT);
			GridData aGD = new GridData(GridData.FILL_HORIZONTAL);
			aGD.widthHint = aW;
			aGD.heightHint = tde.gui.h(110);
			aT.setLayoutData(aGD);
			aT.setText(aText);
			return aT;
		}

		void setFin(String aText) {
			setting = true;
			fin.setText(aText);
			setting = false;
			finEdited = false;
		}

		void setStatus(String aText,String aTip) {
			setStatus(aText, aTip, false);
		}

		void setStatus(String aText,String aTip,boolean aWorking) {
			if(!status.isDisposed()) {
				status.setText(aText == null ? "" : aText);
				working(status, aWorking);
				status.setToolTipText(aTip);
				status.getParent().layout(true);
			}
		}

		/**
		 * An example: its description in the language of the user on the left, its English prompt on the right,
		 * then enriched by the LLM (from the English prompt) when the automatic rewriting is on
		 */
		void setExample(String aDescription,String aPrompt) {
			typing++;
			setting = true;
			user.setText(aDescription);
			setting = false;
			setFin(aPrompt);
			exampleDescription = aDescription.trim();
			examplePrompt = aPrompt;
			lastInput = null;
			setStatus("", null);
			request();
		}

		void imageChanged() {
			if(!user.getText().trim().isEmpty()) {
				request();
			}
		}

		/**
		 * The video panel is shown: asks for the download of the LLM if needed, then loads it in the background
		 * @return false if the download is refused (the video panel can't be used)
		 */
		boolean shown() {
			if(!confirmInstall(user.getShell())) {
				System.out.println("AI prompt rewriting: download refused");
				return false;
			}
			//Download right after the agreement, in a window (the user sees what happens)
			if(PromptExtender.bytesToInstall(tde.tdeDir) > 0 && !installWindow(user.getShell(), extender())) {
				System.out.println("AI prompt rewriting: download canceled");
				hidden();
				return false;
			}
			PromptExtender aPE = extender();
			Thread aT = new Thread(() -> {
				try {
					if(aPE.start()) {
						System.out.println("AI prompt rewriting: LLM started");
					}
				}
				catch(Throwable t) {
					t.printStackTrace(System.err);
					String aErr = lng("AI.ExtendError")+" "+t.getMessage();
					tde.gui.display.asyncExec(() -> setStatus(aErr, aErr));
				}
			}, "PromptExtender start");
			aT.setDaemon(true);
			aT.start();
			return true;
		}

		/**
		 * The LLM (created if needed; started by start() or the first request)
		 */
		PromptExtender extender() {
			if(extender == null) {
				//The LLM still loaded by a previous form is taken back
				extender = PromptExtender.attach(tde.tdeDir, new PythonAI.Progress() {
					long lastMS = 0;
					@Override
					public void status(String aKey, String aDetail, long aDone, long aTotal) {
						PythonAI.Progress aIP = installProgress;
						if(aIP != null) {
							aIP.status(aKey, aDetail, aDone, aTotal);
						}
						//Also sent after each answer: shown only after the loading (the result message stays)
						if("AI.LlmReady".equals(aKey)) {
							tde.gui.display.asyncExec(() -> {
								String aC = status.isDisposed() ? null : status.getText();
								if(!busy && aC != null && (aC.isEmpty() || aC.equals(lng("AI.LoadingLlm")) || aC.equals(lng("AI.Starting")))) {
									setStatus(lng("AI.LlmReady"), null);
								}
							});
							return;
						}
						long aNow = System.currentTimeMillis();
						if(aNow-lastMS < 200 && aDone > 0 && aDone < aTotal) {
							return;
						}
						lastMS = aNow;
						String aT = lng(aKey);
						if(aKey.startsWith("AI.Downloading") && aTotal > 0) {
							aT += " "+DepthModel.gb(aDone)+" / "+DepthModel.gb(aTotal)+" GB";
						}
						else if(aTotal > 0) {
							aT += " "+(100*aDone/aTotal)+" %";
						}
						final String aF = aT;
						tde.gui.display.asyncExec(() -> setStatus(aF, null, true));
					}
					@Override
					public boolean isCanceled() {
						return false;
					}
				});
			}
			return extender;
		}

		/**
		 * The LLM is no longer needed (other panel, form closed, generation started): the form detaches from it,
		 * it stays loaded until another model has to be loaded (a video generation unloads it, see
		 * PythonAI.beforeLoad())
		 */
		void hidden() {
			if(extender != null) {
				extender.detach();
				extender = null;
				System.out.println("AI prompt rewriting: LLM left loaded");
			}
		}

		/**
		 * Rewrite the description if it (or the mode) changed since the final prompt shown
		 */
		void request() {
			if(user.isDisposed()) {
				return;
			}
			String aText = user.getText().trim();
			String aImage = refImage();
			if(aText.isEmpty() || (aText.equals(lastInput) && java.util.Objects.equals(aImage, lastImage))) {
				if(!busy && lng("AI.ExtendSoon").equals(status.getText())) {
					setStatus("", null);
				}
				return;
			}
			if(busy) {
				pending = true;
				return;
			}
			System.out.println("AI prompt rewriting requested ("+(aImage != null ? "i2v, image "+aImage : "t2v")+"): "+aText);
			if(!confirmInstall(user.getShell())) {
				System.out.println("AI prompt rewriting: download refused");
				setFin(user.getText());
				return;
			}
			busy = true;
			setStatus(lng("AI.Starting"), null, true);
			PromptExtender aPE = extender();
			//An example unchanged: the LLM starts from its English prompt
			String aSource = aText.equals(exampleDescription) && examplePrompt != null ? examplePrompt : aText;
			Thread aT = new Thread(() -> {
				String aR = null;
				String aErr = null;
				try {
					aR = aPE.extend(aSource, aImage);
				}
				catch(Throwable t) {
					t.printStackTrace(System.err);
					aErr = t instanceof PythonAI.GenerationException && "OOM".equals(((PythonAI.GenerationException)t).code)
							? lng("AI.ExtendOom") : lng("AI.ExtendError")+" "+t.getMessage();
				}
				final String aResult = aR;
				final String aError = aErr;
				tde.gui.display.asyncExec(() -> {
					busy = false;
					if(user.isDisposed()) {
						return;
					}
					lastInput = aText;
					lastImage = aImage;
					if(aResult != null) {
						setFin(aResult);
						setStatus(lng("AI.ExtendDone"), null);
					}
					else {
						if(fin.getText().trim().isEmpty()) {
							setFin(user.getText());
						}
						setStatus(aError, aError);
					}
					if(pending) {
						pending = false;
						request();
					}
				});
			}, "PromptExtender");
			aT.setDaemon(true);
			aT.start();
		}

		/**
		 * The final prompt is up to date (else the rewriting is started or waited for)
		 */
		boolean ready() {
			String aText = user.getText().trim();
			if(busy || (!aText.isEmpty() && !finEdited && !(aText.equals(lastInput) && java.util.Objects.equals(refImage(), lastImage)))) {
				request();
				setStatus(lng("AI.ExtendWait"), null, true);
				return false;
			}
			return true;
		}

		/**
		 * Prompt given to the model: the final one, else the description
		 */
		String prompt() {
			String aP = fin.getText().trim();
			return aP.isEmpty() ? user.getText().trim() : aP;
		}
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
			//The model kept loaded (the LLM of the prompts...) is unloaded before Wan: its memory counts as free
			long aAvail = PythonAI.availableGpuMemory();
			if(aAvail >= 0) {
				aFree[0].setText(lng("AI.GpuFree").replace("%GB%", gb(aAvail))
						+(aAvail < videoModel.gpuMemory ? "\n"+lng("AI.GpuTooSmall") : ""));
				aFree[0].setForeground(aAvail < videoModel.gpuMemory ? tde.gui.colorsSwt.RED : aWarn.getForeground());
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
		PromptPair aPair = new PromptPair(aRight);
		videoPair = aPair;
		aPanel.setData(PROMPT, aPair.user);
		Label aExL = addLabel(aRight, lng("AI.PromptExamples"), 0);
		//Examples to create a video from the text, replaced by those to animate the image once one is chosen
		Tree aTree = createExamples(aRight, videoImg.path == null ? VIDEO : IMAGE);
		//Lower than for the sounds: the reference image row is below
		((GridData)aTree.getLayoutData()).heightHint = tde.gui.h(200);
		aTree.addListener(SWT.Selection, e -> {
			TreeItem aI = (TreeItem)e.item;
			Object aD = aI == null ? null : aI.getData();
			if(aD instanceof String[]) {
				aPair.setExample(((String[])aD)[5], ((String[])aD)[3]);
				aName.setText(((String[])aD)[4]);
			}
		});
		aTree.addListener(SWT.DefaultSelection, e -> aPair.fin.setFocus());

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

		createImageRow(aRight, aDMax, videoImg, lng("AI.Image"), () -> {
			String aK = refImage() == null ? VIDEO : IMAGE;
			if(!aK.equals(aTree.getData(KIND_DATA))) {
				fillExamples(aTree, aK);
				aExL.setText(lng(IMAGE.equals(aK) ? "AI.PromptExamplesImage" : "AI.PromptExamples"));
				aRight.layout(true, true);
			}
			aPair.imageChanged();
		});

		aDownload[0] = addLabel(aRight, " \n ", 0);
		addLabel(aRight, lng("AI.Note2D"), 0);
		addLabel(aRight, lng("AI.Licence"), 0);
		labelWidth = 560;
		aShow.run();
		return () -> {
			if(!aPair.ready()) {
				return;
			}
			String aP = aPair.prompt();
			if(aP.isEmpty()) {
				aPair.user.setFocus();
				return;
			}
			if(videoImg.path != null && !new File(videoImg.path).isFile()) {
				videoImg.path = null;
			}
			prompt = aP;
			clipName = aName.getText().trim();
			seconds = aSeconds.getSelection()/10.0;
			lastPrompt = aP;
			lastUserPrompt = aPair.user.getText();
			lastSeconds = seconds;
			tde.config.aiVideoModel = videoModel == aDefault ? "" : videoModel.id;
		};
	}

	/**
	 * Colorization panel (DDColor): what it does, its models with where they run; the photo (image row, required),
	 * name, downloads. Unlike the video generator, the photo is kept as it is (size, details): only its colors are
	 * predicted.
	 * @return the check of the OK button: sets prompt (left null when the form is not valid)
	 */
	Runnable createColorPanel(Composite aPanel) {
		Composite[] aCols = columns(aPanel);
		Composite aLeft = aCols[0];
		Composite aRight = aCols[1];
		Label aIntro = addLabel(aLeft, lng("AI.ColorIntro"), 0);
		aIntro.setFont(tde.gui.fontsSwt.robotoBold);
		addLabel(aLeft, lng("AI.GenColorDesc"), 0);

		ColorGenerator.Model aChosen = ColorGenerator.byId(tde.config.aiColorModel);
		colorModel = aChosen != null ? aChosen : ColorGenerator.MODELSCOPE;
		Choice[] aChoices = new Choice[ColorGenerator.MODELS.length];
		for(int m = 0;m < aChoices.length;m++) {
			ColorGenerator.Model aM = ColorGenerator.MODELS[m];
			Choice aC = aChoices[m] = new Choice();
			aC.model = aM;
			aC.advised = aM == ColorGenerator.MODELSCOPE;
			aC.name = lng("AI.ColorModelName").replace("%NAME%", aM.id).replace("%PARAMS%", aM.params)
					.replace("%GB%", gb(aM.downloadSize()))+downloaded(aM.isDownloaded(tde.tdeDir));
			aC.details = lng("AI.Color"+aM.id+"Note");
		}
		Label[] aWhere = {null};
		Label[] aDownload = {null};
		Runnable aShow = () -> {
			String aReason = ColorGenerator.cpuReason(colorModel);
			aWhere[0].setText(aReason == null ? lng("AI.ColorOnGpu") : lng("AI.ColorOnCpu"));
			long aBytes = colorModel.bytesToDownload(tde.tdeDir)+PythonAI.envBytesToInstall(tde.tdeDir);
			aDownload[0].setText(aBytes > 0 ? lng("AI.MusicDownload").replace("%GB%", gb(aBytes)) : lng("AI.MusicReady"));
			aPanel.layout(true, true);
		};
		createModelChoice(aLeft, aChoices, colorModel, aM -> {
			colorModel = (ColorGenerator.Model)aM;
			aShow.run();
		});
		aWhere[0] = addLabel(aLeft, " \n ", 0);
		((GridData)aWhere[0].getLayoutData()).verticalIndent = 8;

		Text aName = createNameField(aRight, COLOR);
		createImageRow(aRight, null, colorImg, lng("AI.ColorImage"), () -> {});
		Label aNote = addLabel(aRight, lng("AI.ColorNote"), 0);
		((GridData)aNote.getLayoutData()).verticalIndent = 8;
		aDownload[0] = addLabel(aRight, " \n ", 0);
		addLabel(aRight, lng("AI.ColorLicence"), 0);
		labelWidth = 560;
		aShow.run();
		return () -> {
			if(colorImg.path != null && !new File(colorImg.path).isFile()) {
				colorImg.path = null;
				colorImg.name = null;
			}
			if(colorImg.path == null) {
				message(tde, SWT.ICON_INFORMATION, lng("AI.ColorNoImage"));
				return;
			}
			String aSrc = colorImg.name != null ? colorImg.name : new File(colorImg.path).getName();
			prompt = lng("AI.ColorPrompt").replace("%NAME%", aSrc);
			clipName = aName.getText().trim();
			if(clipName.isEmpty()) {
				//The name of the photo, colorized
				String aB = aSrc.replaceAll("[.][A-Za-z0-9]{2,4}$", "");
				clipName = aB+" "+lng("AI.ColorSuffix");
			}
			tde.config.aiColorModel = colorModel == ColorGenerator.MODELSCOPE ? "" : colorModel.id;
		};
	}

	/**
	 * 3D scene panel: what it does, the model (Depth Pro or Flash3D) and the detail of the scene with where it runs; the photo (image row,
	 * required), name, downloads, licences, and a button opening a scene saved before (.ply) in the viewer
	 * @return the check of the OK button: sets prompt (left null when the form is not valid)
	 */
	Runnable createScenePanel(Composite aPanel) {
		if(!SceneGenerator.isPlatformSupported()) {
			Label aU = addLabel(aPanel, lng("AI.UnsupportedAll"), 0);
			aU.setForeground(tde.gui.colorsSwt.RED);
			return () -> {};
		}
		Composite[] aCols = columns(aPanel);
		Composite aLeft = aCols[0];
		Composite aRight = aCols[1];
		Label aIntro = addLabel(aLeft, lng("AI.SceneIntro"), 0);
		aIntro.setFont(tde.gui.fontsSwt.robotoBold);
		addLabel(aLeft, lng("AI.GenSceneDesc"), 0);

		//Model (its notes), then the detail (Gaussians of the model), where it runs, the downloads and the licence
		//(right column): updated when the model changes
		sceneModel = SceneGenerator.modelById(tde.config.aiSceneModel);
		SceneGenerator.Detail aChosen = SceneGenerator.detailById(tde.config.aiSceneDetail);
		sceneDetail = aChosen != null ? aChosen : SceneGenerator.FINE;
		Vector<Runnable> aModelChanged = new Vector<Runnable>();
		Label aML = addLabel(aLeft, lng("AI.SceneModel"), 0);
		((GridData)aML.getLayoutData()).verticalIndent = 8;
		for(SceneGenerator.Model aM : SceneGenerator.MODELS) {
			Button aR = new Button(aLeft, SWT.RADIO);
			tde.gui.applyColorFont(aR);
			boolean aAdvised = aM == SceneGenerator.MODELS[0];
			aR.setText(lng(aM.key())+(aAdvised ? " "+lng("AI.MusicAdvised") : ""));
			if(aAdvised) {
				aR.setFont(tde.gui.fontsSwt.robotoBold);
			}
			aR.setSelection(aM == sceneModel);
			aR.setEnabled(aM.isSupported());
			aR.addSelectionListener(new SelectionAdapter() {
				@Override
				public void widgetSelected(SelectionEvent e) {
					if(aR.getSelection() && sceneModel != aM) {
						sceneModel = aM;
						for(Runnable aRun : aModelChanged) {
							aRun.run();
						}
						//Longer texts: the window grows if needed
						Shell aSh = aPanel.getShell();
						aSh.layout(true, true);
						org.eclipse.swt.graphics.Point aNeed = aSh.computeSize(aSh.getSize().x, SWT.DEFAULT);
						if(aNeed.y > aSh.getSize().y) {
							aSh.setSize(aSh.getSize().x, aNeed.y);
						}
					}
				}
			});
			addLabel(aLeft, lng(aM.key()+"Note"), 20);
		}
		//Model filling the holes of the views (model3d.SmartFill): downloaded when chosen (refused: back to the
		//previous one); not possible here: disabled
		SmartFill.Model aFillNow = SmartFill.modelById(tde.config.aiSceneFill);
		Label aFL = addLabel(aLeft, lng("AI.SceneFill"), 0);
		((GridData)aFL.getLayoutData()).verticalIndent = 8;
		Vector<Button> aFillBs = new Vector<Button>();
		for(SmartFill.Model aM : SmartFill.MODELS) {
			Button aR = new Button(aLeft, SWT.RADIO);
			tde.gui.applyColorFont(aR);
			boolean aDefault = aM == SmartFill.DEFAULT_MODEL;
			aR.setText(lng(aM.key())+(aDefault ? " "+lng("AI.SceneFillDefault") : ""));
			if(aDefault) {
				aR.setFont(tde.gui.fontsSwt.robotoBold);
			}
			aR.setEnabled(aM.capable());
			aR.setSelection(aM == aFillNow);
			aR.setData(aM);
			aFillBs.add(aR);
			aR.addSelectionListener(new SelectionAdapter() {
				@Override
				public void widgetSelected(SelectionEvent e) {
					SmartFill.Model aOld = SmartFill.modelById(tde.config.aiSceneFill);
					if(!aR.getSelection() || aOld == aM) {
						return;
					}
					if(FillModelGUI.ensure(tde, aPanel.getShell(), aM)) {
						tde.config.aiSceneFill = aM == SmartFill.DEFAULT_MODEL ? "" : aM.id;
					}
					else {
						for(Button aB : aFillBs) {
							aB.setSelection(aB.getData() == aOld);
						}
					}
				}
			});
			addLabel(aLeft, lng(aM.key()+"Note")+(aM.capable() ? "" : " "+lng("AI.SceneFillNotHere")), 20);
		}
		Label aDL = addLabel(aLeft, lng("AI.SceneDetail"), 0);
		((GridData)aDL.getLayoutData()).verticalIndent = 8;
		for(SceneGenerator.Detail aD : SceneGenerator.DETAILS) {
			Button aR = new Button(aLeft, SWT.RADIO);
			tde.gui.applyColorFont(aR);
			boolean aAdvised = aD == SceneGenerator.FINE;
			Runnable aText = () -> aR.setText(lng("AI.SceneDetail"+aD.id).replace("%N%", String.format("%,d", aD.gaussians(sceneModel)))
					+(aAdvised ? " "+lng("AI.MusicAdvised") : ""));
			aText.run();
			aModelChanged.add(aText);
			if(aAdvised) {
				aR.setFont(tde.gui.fontsSwt.robotoBold);
			}
			aR.setSelection(aD == sceneDetail);
			aR.addSelectionListener(new SelectionAdapter() {
				@Override
				public void widgetSelected(SelectionEvent e) {
					if(aR.getSelection()) {
						sceneDetail = aD;
					}
				}
			});
			addLabel(aLeft, lng("AI.SceneDetail"+aD.id+"Note"), 20);
		}
		Label aWhere = addLabel(aLeft, "", 0);
		((GridData)aWhere.getLayoutData()).verticalIndent = 8;
		Runnable aWhereText = () -> aWhere.setText(lng(SceneGenerator.cpuReason(sceneModel) == null ? "AI.SceneOnGpu" : "AI.SceneOnCpu")
				.replace("%GB%", gb(sceneModel.gpuMemory)));
		aWhereText.run();
		aModelChanged.add(aWhereText);

		Text aName = createNameField(aRight, SCENE);
		createImageRow(aRight, null, sceneImg, lng("AI.SceneImage"), () -> {});
		//Interpolation (the mesh of the visible Gaussians, else the Gaussians), then the smart filling (kept in 3De.conf)
		Composite aFillRow = new Composite(aRight, SWT.NONE);
		tde.gui.applyColorFont(aFillRow);
		aFillRow.setLayoutData(new GridData(GridData.FILL_HORIZONTAL));
		GridLayout aFRGL = new GridLayout(4, false);
		tde.gui.applyNoMargin(aFRGL);
		aFillRow.setLayout(aFRGL);
		Button aDepth = new Button(aFillRow, SWT.CHECK);
		tde.gui.applyColorFont(aDepth);
		aDepth.setText(lng("AI.SceneDepth"));
		aDepth.setToolTipText(lng("AI.SceneDepthTip"));
		aDepth.setSelection(tde.config.aiSceneDepth);
		aDepth.addListener(SWT.Selection, e -> tde.config.aiSceneDepth = aDepth.getSelection());
		Button aInterp = new Button(aFillRow, SWT.CHECK);
		tde.gui.applyColorFont(aInterp);
		aInterp.setText(lng("AI.SceneInterp"));
		aInterp.setToolTipText(lng("AI.SceneInterpTip"));
		aInterp.setSelection(tde.config.aiSceneInterp);
		aInterp.addListener(SWT.Selection, e -> tde.config.aiSceneInterp = aInterp.getSelection());
		Button aCut = new Button(aFillRow, SWT.CHECK);
		tde.gui.applyColorFont(aCut);
		aCut.setText(lng("AI.SceneCut"));
		aCut.setToolTipText(lng("AI.SceneCutTip"));
		aCut.setSelection(tde.config.aiSceneCut);
		aCut.addListener(SWT.Selection, e -> tde.config.aiSceneCut = aCut.getSelection());
		Button aFill = new Button(aFillRow, SWT.CHECK);
		tde.gui.applyColorFont(aFill);
		aFill.setText(lng("AI.SceneSmartFill"));
		aFill.setToolTipText(lng("AI.SceneSmartFillTip"));
		aFill.setSelection(tde.config.aiSceneSmartFill);
		aFill.addListener(SWT.Selection, e -> tde.config.aiSceneSmartFill = aFill.getSelection());
		Label aNote = addLabel(aRight, lng("AI.SceneNote"), 0);
		((GridData)aNote.getLayoutData()).verticalIndent = 8;
		Label aDownload = addLabel(aRight, "", 0);
		Label aLicence = addLabel(aRight, "", 0);
		Runnable aDownloadText = () -> {
			long aBytes = SceneGenerator.bytesToDownload(tde.tdeDir, sceneModel);
			aDownload.setText(aBytes > 0 ? lng("AI.MusicDownload").replace("%GB%", gb(aBytes)) : lng("AI.MusicReady"));
			aLicence.setText(lng(sceneModel.licenceKey));
		};
		aDownloadText.run();
		aModelChanged.add(aDownloadText);
		Button aOpen = new Button(aRight, SWT.PUSH);
		tde.gui.applyColorFont(aOpen);
		aOpen.setText(lng("AI.SceneOpen"));
		GridData aOGD = new GridData();
		aOGD.verticalIndent = 8;
		aOpen.setLayoutData(aOGD);
		aOpen.addSelectionListener(new SelectionAdapter() {
			@Override
			public void widgetSelected(SelectionEvent e) {
				FileDialog aFD = new FileDialog(aPanel.getShell(), SWT.OPEN);
				aFD.setText(lng("AI.SceneOpen"));
				aFD.setFilterNames(new String[] {lng("AI.SceneFilter")});
				aFD.setFilterExtensions(new String[] {"*.ply;*.PLY"});
				File aDir = outDir();
				aFD.setFilterPath(aDir.isDirectory() ? aDir.getAbsolutePath() : tde.tdeDir);
				String aPath = aFD.open();
				if(aPath != null && formOkB != null && !formOkB.isDisposed()) {
					scenePly = aPath;
					formOkB.notifyListeners(SWT.Selection, new org.eclipse.swt.widgets.Event());
				}
			}
		});
		labelWidth = 560;
		return () -> {
			tde.config.aiSceneDetail = sceneDetail == SceneGenerator.FINE ? "" : sceneDetail.id;
			tde.config.aiSceneModel = sceneModel == SceneGenerator.MODELS[0] ? "" : sceneModel.id;
			if(scenePly != null) {
				prompt = new File(scenePly).getName();
				return;
			}
			if(sceneImg.path != null && !new File(sceneImg.path).isFile()) {
				sceneImg.path = null;
				sceneImg.name = null;
			}
			if(sceneImg.path == null) {
				message(tde, SWT.ICON_INFORMATION, lng("AI.SceneNoImage"));
				return;
			}
			String aSrc = sceneImg.name != null ? sceneImg.name : new File(sceneImg.path).getName();
			prompt = lng("AI.ScenePrompt").replace("%NAME%", aSrc);
			clipName = aName.getText().trim();
			if(clipName.isEmpty()) {
				//The name of the photo, in 3D
				clipName = aSrc.replaceAll("[.][A-Za-z0-9]{2,4}$", "")+" "+lng("AI.SceneSuffix");
			}
		};
	}

	/**
	 * Mask panel (SAM 2.1): what it does, its models with their memory and where they run, the licence; the image
	 * or video (a file, stereo side by side or not, or a media of the library), the name of the mask, downloads.
	 * OK opens the editor (AiMaskGUI).
	 * @return the check of the OK button: sets prompt (left null when the form is not valid)
	 */
	Runnable createMaskPanel(Composite aPanel) {
		if(!MaskGenerator.isPlatformSupported()) {
			Label aU = addLabel(aPanel, lng("AI.UnsupportedAll"), 0);
			aU.setForeground(tde.gui.colorsSwt.RED);
			return () -> {};
		}
		Composite[] aCols = columns(aPanel);
		Composite aLeft = aCols[0];
		Composite aRight = aCols[1];
		Label aIntro = addLabel(aLeft, lng("AI.MaskIntro"), 0);
		aIntro.setFont(tde.gui.fontsSwt.robotoBold);
		addLabel(aLeft, lng("AI.GenMaskDesc"), 0);

		MaskGenerator.Model aDefault = MaskGenerator.defaultModel();
		MaskGenerator.Model aChosen = MaskGenerator.byId(tde.config.aiMaskModel);
		maskModel = aChosen != null ? aChosen : aDefault;
		Choice[] aChoices = new Choice[MaskGenerator.MODELS.length];
		for(int m = 0;m < aChoices.length;m++) {
			MaskGenerator.Model aM = MaskGenerator.MODELS[m];
			Choice aC = aChoices[m] = new Choice();
			aC.model = aM;
			aC.advised = aM == aDefault;
			aC.name = lng("AI.MaskModelName").replace("%NAME%", aM.name).replace("%PARAMS%", aM.params)
					.replace("%GB%", gb(aM.downloadSize()))+downloaded(aM.isDownloaded(tde.tdeDir));
			aC.details = lng("AI.Mask"+aM.id+"Note").replace("%GB%", gb(aM.gpuMemory));
		}
		Label[] aWhere = {null};
		Label[] aDownload = {null};
		Runnable aShow = () -> {
			String aReason = MaskGenerator.cpuReason(maskModel);
			aWhere[0].setText(aReason == null ? lng("AI.MaskOnGpu") : lng("AI.MaskOnCpu"));
			long aBytes = maskModel.bytesToDownload(tde.tdeDir)+PythonAI.envBytesToInstall(tde.tdeDir);
			aDownload[0].setText(aBytes > 0 ? lng("AI.MusicDownload").replace("%GB%", gb(aBytes)) : lng("AI.MusicReady"));
			aPanel.layout(true, true);
		};
		createModelChoice(aLeft, aChoices, maskModel, aM -> {
			maskModel = (MaskGenerator.Model)aM;
			aShow.run();
		});
		aWhere[0] = addLabel(aLeft, " \n ", 0);
		((GridData)aWhere[0].getLayoutData()).verticalIndent = 8;

		Text aName = createNameField(aRight, MASK);
		aName.setMessage(lng("AI.MaskNameHint"));
		createMaskSourceRow(aRight);
		Label aNote = addLabel(aRight, lng("AI.MaskNote"), 0);
		((GridData)aNote.getLayoutData()).verticalIndent = 8;
		aDownload[0] = addLabel(aRight, " \n ", 0);
		addLabel(aRight, lng("AI.MaskLicence"), 0);
		labelWidth = 560;
		aShow.run();
		return () -> {
			boolean aOk = maskSrc.left != null || (maskSrc.path != null && new File(maskSrc.path).isFile());
			if(!aOk) {
				message(tde, SWT.ICON_INFORMATION, lng("AI.MaskNoSource"));
				return;
			}
			prompt = maskSrc.shownName();
			clipName = aName.getText().trim();
			tde.config.aiMaskModel = maskModel == aDefault ? "" : maskModel.id;
		};
	}

	/**
	 * Row of the image or video of the mask: name, Choose (a file), From the library (an image or a video of the
	 * media list: its stereo layout known), and the side by side checkbox for a file
	 */
	void createMaskSourceRow(Composite aParent) {
		Label aL = addLabel(aParent, lng("AI.MaskSource"), 0);
		((GridData)aL.getLayoutData()).verticalIndent = 8;
		Composite aRow = new Composite(aParent, SWT.NONE);
		tde.gui.applyColorFont(aRow);
		aRow.setLayoutData(new GridData(GridData.FILL_HORIZONTAL));
		GridLayout aRGL = new GridLayout(3, false);
		tde.gui.applyNoMargin(aRGL);
		aRow.setLayout(aRGL);
		Label aNameL = new Label(aRow, SWT.WRAP);
		tde.gui.applyColorFont(aNameL);
		GridData aNGD = new GridData(GridData.FILL_HORIZONTAL);
		aNGD.widthHint = 260;
		aNameL.setLayoutData(aNGD);
		Button aChoose = new Button(aRow, SWT.PUSH);
		tde.gui.applyColorFont(aChoose);
		aChoose.setText(lng("AI.ImageChoose"));
		Button aLib = new Button(aRow, SWT.PUSH);
		tde.gui.applyColorFont(aLib);
		aLib.setText(lng("AI.ImageLibrary"));
		aLib.setEnabled(!libraryImages().isEmpty());
		Button aSbs = new Button(aParent, SWT.CHECK);
		tde.gui.applyColorFont(aSbs);
		aSbs.setText(lng("AI.MaskSbs"));
		aSbs.setToolTipText(lng("AI.MaskSbsTip"));
		Runnable aShow = () -> {
			boolean aHas = maskSrc.left != null || maskSrc.path != null;
			String aKind = !aHas ? "" : maskSrc.video ? lng("AI.ImageLibraryVideo") : "";
			String aStereo = aHas && maskSrc.isStereo() ? " - "+lng("AI.MaskStereo") : "";
			aNameL.setText(aHas ? maskSrc.shownName()+aKind+aStereo : lng("AI.MaskSourceNone"));
			aNameL.setFont(aHas ? tde.gui.fontsSwt.robotoBold : aChoose.getFont());
			//A media of the library knows its layout
			aSbs.setEnabled(aHas && maskSrc.media == null);
			aSbs.setSelection(maskSrc.layout == AiMaskGUI.Source.SBS_P);
			aRow.layout(true);
			aParent.layout(true, true);
		};
		aChoose.addListener(SWT.Selection, e -> {
			FileDialog aFD = new FileDialog(aParent.getShell(), SWT.OPEN);
			aFD.setText(lng("AI.MaskSource"));
			aFD.setFilterNames(new String[] {lng("AI.ImageFilter")});
			String aExts = "*.jpg;*.jpeg;*.png;*.webp;*.bmp;*.gif;"+VIDEO_EXTS;
			aFD.setFilterExtensions(new String[] {aExts+aExts.toUpperCase()});
			String aDir = lastImageDir != null ? lastImageDir : (tde.lastDir != null ? tde.lastDir : tde.tdeDir);
			aFD.setFilterPath(aDir);
			String aPath = aFD.open();
			if(aPath != null) {
				lastImageDir = new File(aPath).getParent();
				boolean aWasSbs = aSbs.getSelection();
				maskSrc = new AiMaskGUI.Source();
				maskSrc.path = aPath;
				maskSrc.video = isVideoFile(aPath);
				maskSrc.layout = aWasSbs ? AiMaskGUI.Source.SBS_P : AiMaskGUI.Source.MONO;
				aShow.run();
			}
		});
		aLib.addListener(SWT.Selection, e -> {
			StereoImage aM = (StereoImage)chooseLibraryMedia(aParent.getShell(), new Vector<Media>(libraryImages()), lng("AI.MaskLibraryTitle"));
			if(aM != null) {
				maskSrc = maskSource(aM);
				aShow.run();
			}
		});
		aSbs.addListener(SWT.Selection, e -> {
			if(maskSrc.media == null) {
				maskSrc.layout = aSbs.getSelection() ? AiMaskGUI.Source.SBS_P : AiMaskGUI.Source.MONO;
				aShow.run();
			}
		});
		aShow.run();
	}

	/**
	 * Source of the mask from a media of the library: a video with its file(s) and its stereo layout, a still
	 * image with its views as loaded (before rotation: the mask follows the orientation of the clip)
	 */
	static AiMaskGUI.Source maskSource(StereoImage aM) {
		AiMaskGUI.Source aS = new AiMaskGUI.Source();
		aS.media = aM;
		aS.name = aM.fileName;
		String aL = aM.getPathL();
		String aR = aM.getPathR();
		boolean aTwo = aR != null && aL != null && !aR.equals(aL);
		if(aM instanceof StereoMovie) {
			aS.video = true;
			aS.path = aL;
			aS.pathR = aR;
			aS.layout = aM.origStereo == 1 ? AiMaskGUI.Source.SBS_X : aM.origStereo == 2 ? AiMaskGUI.Source.SBS_P
					: aTwo ? AiMaskGUI.Source.TWO : AiMaskGUI.Source.MONO;
		}
		else {
			aS.path = aL;
			aS.left = aM.loadedLeft;
			boolean aStereo = aM.origStereo != 0 || aTwo || (aL != null && aL.toLowerCase().endsWith(".mpo"));
			aS.right = aStereo ? aM.loadedRight : null;
			aS.layout = aStereo && aS.right != null ? AiMaskGUI.Source.TWO : AiMaskGUI.Source.MONO;
		}
		return aS;
	}

	/**
	 * Panel of the speech of a video (see ai.SpeechGenerator). Subtitles (aDub false): the languages of the
	 * subtitles, checked in a list (several at once: one SRT per language, the spoken one transcribed, the others
	 * translated by the LLM). Voice translation (aDub true): the language of the voice, the original sound kept
	 * under it or not. Both: the video or the sound (file or library), the speech recognition model, the language
	 * spoken (recognized or chosen), where it runs, downloads.
	 * @return the check of the OK button: sets prompt (left null when the form is not valid)
	 */
	Runnable createSpeechPanel(Composite aPanel,boolean aDub) {
		if(!SpeechGenerator.isPlatformSupported()) {
			Label aU = addLabel(aPanel, lng("AI.UnsupportedAll"), 0);
			aU.setForeground(tde.gui.colorsSwt.RED);
			return () -> {};
		}
		Composite[] aCols = columns(aPanel);
		Composite aLeft = aCols[0];
		Composite aRight = aCols[1];
		SpeechSource aSrc = aDub ? dubSrc : subsSrc;
		Label aIntro = addLabel(aLeft, lng(aDub ? "AI.DubIntro" : "AI.SubsIntro"), 0);
		aIntro.setFont(tde.gui.fontsSwt.robotoBold);
		addLabel(aLeft, lng(aDub ? "AI.DubDesc" : "AI.SubsDesc"), 0);

		SpeechGenerator.Model aAsrChosen = SpeechGenerator.byId(SpeechGenerator.ASR_MODELS, tde.config.aiAsrModel);
		SpeechGenerator.Model[] aAsr = {aAsrChosen != null ? aAsrChosen : SpeechGenerator.QWEN3_ASR};
		SpeechGenerator.Model aTtsChosen = SpeechGenerator.byId(SpeechGenerator.TTS_MODELS, tde.config.aiTtsModel);
		SpeechGenerator.Model aTtsAdvised = SpeechGenerator.defaultTts();
		SpeechGenerator.Model[] aTts = {aTtsChosen != null ? aTtsChosen : aTtsAdvised};
		Choice[] aAsrChoices = speechChoices(SpeechGenerator.ASR_MODELS, SpeechGenerator.QWEN3_ASR);
		Label[] aWhere = {null};
		Label[] aDownload = {null};
		Label[] aLlm = {null};
		org.eclipse.swt.widgets.Combo[] aSpoken = {null};
		org.eclipse.swt.widgets.Table[] aLangs = {null};
		org.eclipse.swt.widgets.Combo[] aTarget = {null};
		org.eclipse.swt.widgets.Combo[] aVoice = {null};
		//Voices of the combo, in its order
		//The voice of each speaker first (default: several speakers, men and women, keep their own voice)
		String[] aVoices = {SpeechGenerator.VOICE_SPEAKERS, SpeechGenerator.VOICE_ORIGINAL, SpeechGenerator.VOICE_MALE, SpeechGenerator.VOICE_FEMALE};
		//Radio buttons of the voice models; the last cascade model and voice (given back when leaving Hibiki-Zero)
		java.util.Map<SpeechGenerator.Model,Button> aTtsRadios = new java.util.HashMap<SpeechGenerator.Model,Button>();
		SpeechGenerator.Model[] aCascade = {aTts[0] == SpeechGenerator.HIBIKI ? aTtsAdvised : aTts[0]};
		int[] aCascadeVoice = {-1};
		//Refresh of the row of the subtitles file
		Runnable[] aSubsShow = {null};
		//Languages of the subtitles (English names, "" the spoken one) / of the voice, as checked or chosen
		java.util.function.Supplier<Vector<String>> aTargets = () -> {
			Vector<String> aV = new Vector<String>();
			if(aDub) {
				int aI = aTarget[0].getSelectionIndex();
				aV.add(SpeechGenerator.TTS_LANGUAGES[Math.max(0, aI)]);
			}
			else {
				for(org.eclipse.swt.widgets.TableItem aI : aLangs[0].getItems()) {
					if(aI.getChecked()) {
						aV.add((String)aI.getData());
					}
				}
			}
			return aV;
		};
		//The LLM is needed when a language is not the spoken one
		java.util.function.BooleanSupplier aTranslates = () -> {
			//Direct translation: no LLM
			if(aDub && aTts[0] == SpeechGenerator.HIBIKI) {
				return false;
			}
			String aS = aSpoken[0] == null ? "" : spokenOf(aSpoken[0], aAsr[0]);
			for(String aT : aTargets.get()) {
				if(!aT.isEmpty() && !aT.equals(aS)) {
					return true;
				}
			}
			return false;
		};
		Runnable aShow = () -> {
			if(aWhere[0] == null || aDownload[0] == null || aLlm[0] == null) {
				return;
			}
			boolean aDirect = aDub && aTts[0] == SpeechGenerator.HIBIKI;
			boolean aOwn = aSrc.srt != null;
			String aW = aDirect ? lng("AI.DubDirectNoAsr") : aOwn ? lng("AI.SpeechOwnSubsNoAsr")
					: lng(SpeechGenerator.cpuReason(aAsr[0]) == null ? "AI.SpeechOnGpu" : "AI.SpeechOnCpu");
			if(aDub) {
				aW += "\n"+lng(SpeechGenerator.cpuReason(aTts[0]) == null ? "AI.VoiceOnGpu" : aDirect ? "AI.DubDirectOnCpu" : "AI.VoiceOnCpu");
			}
			aWhere[0].setText(aW);
			if(aTarget[0] != null) {
				//Hibiki-Zero speaks English only, with the voice of the speaker (another choice goes to the cascade)
				if(aDirect) {
					aTarget[0].select(java.util.Arrays.asList(SpeechGenerator.TTS_LANGUAGES).indexOf("English"));
					int aOrig = java.util.Arrays.asList(aVoices).indexOf(SpeechGenerator.VOICE_ORIGINAL);
					if(aVoice[0].getSelectionIndex() != aOrig) {
						aCascadeVoice[0] = aVoice[0].getSelectionIndex();
						aVoice[0].select(aOrig);
					}
				}
				else if(aCascadeVoice[0] >= 0) {
					aVoice[0].select(aCascadeVoice[0]);
					aCascadeVoice[0] = -1;
				}
			}
			boolean aTr = aTranslates.getAsBoolean();
			String aWarn = aTr ? llmWarning() : null;
			aLlm[0].setText(aDirect ? lng("AI.DubDirectNoLlm") : aTr ? lng("AI.SpeechLlm")+(aWarn == null ? "" : "\n"+aWarn) : lng("AI.SpeechNoLlm"));
			aLlm[0].setForeground(aWarn != null ? tde.gui.colorsSwt.RED : aIntro.getForeground());
			long aBytes = (aDirect || aOwn ? 0 : aAsr[0].bytesToDownload(tde.tdeDir))+SpeechGenerator.envBytesToInstall(tde.tdeDir, aDub ? aTts[0] : null)
					+(aDub ? aTts[0].bytesToDownload(tde.tdeDir) : 0)+(aTr ? PromptExtender.bytesToDownload(tde.tdeDir) : 0)
					+(aDub && !aDirect && aVoice[0] != null ? SpeechGenerator.designBytesToDownload(tde.tdeDir
							, aVoices[Math.max(0, aVoice[0].getSelectionIndex())], aTargets.get().firstElement()) : 0);
			aDownload[0].setText(aBytes > 0 ? lng("AI.MusicDownload").replace("%GB%", gb(aBytes)) : lng("AI.MusicReady"));
			aPanel.layout(true, true);
		};
		//A choice that Hibiki-Zero cannot do (spoken language, target, native voice): the cascade model instead
		java.util.function.BooleanSupplier aNeedsCascade = () -> {
			//Own subtitles: Hibiki-Zero does not use subtitles
			if(aSrc.srt != null) {
				return true;
			}
			String aS = spokenOf(aSpoken[0], aAsr[0]);
			if(!aS.isEmpty() && !java.util.Arrays.asList(SpeechGenerator.HIBIKI_SOURCES).contains(aS)) {
				return true;
			}
			if(!"English".equals(SpeechGenerator.TTS_LANGUAGES[Math.max(0, aTarget[0].getSelectionIndex())])) {
				return true;
			}
			return !SpeechGenerator.VOICE_ORIGINAL.equals(aVoices[Math.max(0, aVoice[0].getSelectionIndex())]);
		};
		Runnable aChoiceChanged = () -> {
			if(aDub && aTts[0] == SpeechGenerator.HIBIKI && aNeedsCascade.getAsBoolean()) {
				aCascadeVoice[0] = -1;
				aTts[0] = aCascade[0];
				for(java.util.Map.Entry<SpeechGenerator.Model,Button> aE : aTtsRadios.entrySet()) {
					aE.getValue().setSelection(aE.getKey() == aTts[0]);
				}
			}
			aShow.run();
		};
		Runnable aFillSpoken = () -> {
			String aKeep = spokenOf(aSpoken[0], aAsr[0]);
			if(aSpoken[0].getItemCount() == 0) {
				aKeep = tde.config.aiSpokenLang;
			}
			aSpoken[0].removeAll();
			aSpoken[0].add(lng(aAsr[0] == SpeechGenerator.PARAKEET ? "AI.SpeechSpokenAutoParakeet" : "AI.SpeechSpokenAuto"));
			String[] aNames = sortedLanguages(aAsr[0].languages);
			aSpoken[0].setData(aNames);
			int aSel = 0;
			for(int i = 0;i < aNames.length;i++) {
				aSpoken[0].add(SpeechGenerator.displayName(aNames[i], tde.config.lng));
				if(aNames[i].equals(aKeep)) {
					aSel = i+1;
				}
			}
			aSpoken[0].select(aSel);
		};
		createModelChoice(aLeft, aAsrChoices, aAsr[0], aM -> {
			aAsr[0] = (SpeechGenerator.Model)aM;
			aFillSpoken.run();
			aShow.run();
		});
		if(aDub) {
			Label aVL = addLabel(aLeft, lng("AI.DubVoiceModel"), 0);
			((GridData)aVL.getLayoutData()).verticalIndent = 8;
			for(Choice aC : speechChoices(SpeechGenerator.TTS_MODELS, aTtsAdvised)) {
				Button aR = new Button(aLeft, SWT.RADIO);
				tde.gui.applyColorFont(aR);
				aR.setText(aC.name+(aC.advised ? " "+lng("AI.MusicAdvised") : ""));
				if(aC.advised) {
					aR.setFont(tde.gui.fontsSwt.robotoBold);
				}
				aR.setSelection(aC.model == aTts[0]);
				aTtsRadios.put((SpeechGenerator.Model)aC.model, aR);
				aR.addListener(SWT.Selection, e -> {
					if(aR.getSelection()) {
						aTts[0] = (SpeechGenerator.Model)aC.model;
						if(aTts[0] != SpeechGenerator.HIBIKI) {
							aCascade[0] = aTts[0];
						}
						else if(aSrc.srt != null && aSubsShow[0] != null) {
							//Direct translation from the sound: no subtitles used
							aSrc.srt = null;
							aSubsShow[0].run();
						}
						aShow.run();
					}
				});
				if(aC.model == SpeechGenerator.HIBIKI) {
					//Its main limit, in bold
					Label aOnly = addLabel(aLeft, lng("AI.SpeechHibikiOnly"), 20);
					aOnly.setFont(tde.gui.fontsSwt.robotoBold);
				}
				addLabel(aLeft, aC.details, 20);
			}
		}
		aWhere[0] = addLabel(aLeft, " \n ", 0);
		((GridData)aWhere[0].getLayoutData()).verticalIndent = 8;
		aLlm[0] = addLabel(aLeft, " \n ", 0);

		Text aName = createNameField(aRight, aDub ? DUB : SUBS);
		aName.setMessage(lng("AI.SpeechNameHint"));
		createSourceRow(aRight, aSrc);
		aSubsShow[0] = createSubsFileRow(aRight, aSrc, aChoiceChanged);

		Composite aSp = new Composite(aRight, SWT.NONE);
		tde.gui.applyColorFont(aSp);
		GridData aSpGD = new GridData(GridData.FILL_HORIZONTAL);
		aSpGD.verticalIndent = 8;
		aSp.setLayoutData(aSpGD);
		GridLayout aSpGL = new GridLayout(2, false);
		tde.gui.applyNoMargin(aSpGL);
		aSp.setLayout(aSpGL);
		Label aSpL = new Label(aSp, SWT.NONE);
		tde.gui.applyColorFont(aSpL);
		aSpL.setText(lng("AI.SpeechSpoken"));
		aSpoken[0] = new org.eclipse.swt.widgets.Combo(aSp, SWT.READ_ONLY | SWT.DROP_DOWN);
		tde.gui.applyColorFont(aSpoken[0]);
		aSpoken[0].setVisibleItemCount(20);
		aSpoken[0].setLayoutData(new GridData(GridData.FILL_HORIZONTAL));
		aFillSpoken.run();
		aSpoken[0].addListener(SWT.Selection, e -> aChoiceChanged.run());

		if(aDub) {
			Composite aT = new Composite(aRight, SWT.NONE);
			tde.gui.applyColorFont(aT);
			aT.setLayoutData(new GridData(GridData.FILL_HORIZONTAL));
			GridLayout aTGL = new GridLayout(2, false);
			tde.gui.applyNoMargin(aTGL);
			aT.setLayout(aTGL);
			Label aTL = new Label(aT, SWT.NONE);
			tde.gui.applyColorFont(aTL);
			aTL.setText(lng("AI.DubTarget"));
			aTarget[0] = new org.eclipse.swt.widgets.Combo(aT, SWT.READ_ONLY | SWT.DROP_DOWN);
			tde.gui.applyColorFont(aTarget[0]);
			aTarget[0].setLayoutData(new GridData(GridData.FILL_HORIZONTAL));
			int aSel = 0;
			for(int i = 0;i < SpeechGenerator.TTS_LANGUAGES.length;i++) {
				aTarget[0].add(SpeechGenerator.displayName(SpeechGenerator.TTS_LANGUAGES[i], tde.config.lng));
				if(SpeechGenerator.TTS_LANGUAGES[i].equals(tde.config.aiDubLang)) {
					aSel = i;
				}
			}
			aTarget[0].select(aSel);
			aTarget[0].addListener(SWT.Selection, e -> aChoiceChanged.run());
			Label aVL2 = new Label(aT, SWT.NONE);
			tde.gui.applyColorFont(aVL2);
			aVL2.setText(lng("AI.DubVoiceKind"));
			aVoice[0] = new org.eclipse.swt.widgets.Combo(aT, SWT.READ_ONLY | SWT.DROP_DOWN);
			tde.gui.applyColorFont(aVoice[0]);
			aVoice[0].setLayoutData(new GridData(GridData.FILL_HORIZONTAL));
			aVoice[0].add(lng("AI.DubVoiceSpeakers"));
			aVoice[0].add(lng("AI.DubVoiceOriginal"));
			aVoice[0].add(lng("AI.DubVoiceMale"));
			aVoice[0].add(lng("AI.DubVoiceFemale"));
			aVoice[0].setToolTipText(lng("AI.DubVoiceTip"));
			aVoice[0].select(Math.max(0, java.util.Arrays.asList(aVoices).indexOf("original".equals(tde.config.aiDubVoice) ? "" : tde.config.aiDubVoice)));
			aVoice[0].addListener(SWT.Selection, e -> aChoiceChanged.run());
			Label aNote = addLabel(aRight, lng("AI.DubNote"), 0);
			((GridData)aNote.getLayoutData()).verticalIndent = 8;
		}
		else {
			Label aLL = addLabel(aRight, lng("AI.SubsLanguages"), 0);
			((GridData)aLL.getLayoutData()).verticalIndent = 8;
			aLangs[0] = new org.eclipse.swt.widgets.Table(aRight, SWT.BORDER | SWT.CHECK | SWT.V_SCROLL | SWT.SINGLE);
			tde.gui.applyColorFont(aLangs[0]);
			GridData aLGD = new GridData(GridData.FILL_BOTH);
			aLGD.widthHint = labelWidth;
			aLGD.heightHint = tde.gui.h(190);
			aLangs[0].setLayoutData(aLGD);
			java.util.Set<String> aChecked = new java.util.HashSet<String>(java.util.Arrays.asList(tde.config.aiSubLangs.split(",")));
			org.eclipse.swt.widgets.TableItem aOrig = new org.eclipse.swt.widgets.TableItem(aLangs[0], SWT.NONE);
			aOrig.setText(lng("AI.SubsSpokenLanguage"));
			aOrig.setData("");
			aOrig.setChecked(aChecked.contains("-"));
			String[] aAll = new String[SpeechGenerator.SUBTITLE_LANGUAGES.length];
			for(int i = 0;i < aAll.length;i++) {
				aAll[i] = SpeechGenerator.SUBTITLE_LANGUAGES[i][1];
			}
			for(String aL : sortedLanguages(aAll)) {
				org.eclipse.swt.widgets.TableItem aI = new org.eclipse.swt.widgets.TableItem(aLangs[0], SWT.NONE);
				aI.setText(SpeechGenerator.displayName(aL, tde.config.lng));
				aI.setData(aL);
				aI.setChecked(aChecked.contains(aL));
			}
			aLangs[0].addListener(SWT.Selection, e -> {
				//A click on the name checks it too
				if(e.detail != SWT.CHECK && e.item instanceof org.eclipse.swt.widgets.TableItem) {
					org.eclipse.swt.widgets.TableItem aI = (org.eclipse.swt.widgets.TableItem)e.item;
					aI.setChecked(!aI.getChecked());
				}
				aShow.run();
			});
			Label aNote = addLabel(aRight, lng("AI.SubsNote"), 0);
			((GridData)aNote.getLayoutData()).verticalIndent = 8;
		}
		aDownload[0] = addLabel(aRight, " \n ", 0);
		addLabel(aRight, lng(aDub ? "AI.DubLicence" : "AI.SubsLicence"), 0);
		labelWidth = 560;
		aShow.run();
		return () -> {
			if(aSrc.path != null && !new File(aSrc.path).isFile()) {
				aSrc.path = null;
			}
			if(aSrc.path == null) {
				message(tde, SWT.ICON_INFORMATION, lng("AI.SpeechNoSource"));
				return;
			}
			if(aSrc.srt != null) {
				int aNb = 0;
				try {
					aNb = new File(aSrc.srt).isFile() ? SubtitleFile.read(aSrc.srt).size() : 0;
				}
				catch(Throwable t) {
					t.printStackTrace(System.err);
				}
				if(aNb <= 0) {
					message(tde, SWT.ICON_INFORMATION, lng("AI.SpeechOwnSubsEmpty").replace("%s", new File(aSrc.srt).getName()));
					return;
				}
			}
			Vector<String> aT = aTargets.get();
			if(aT.isEmpty()) {
				message(tde, SWT.ICON_INFORMATION, lng("AI.SubsNoLanguage"));
				return;
			}
			if(aDub && aTts[0] == SpeechGenerator.HIBIKI && !aT.firstElement().equals("English")) {
				message(tde, SWT.ICON_INFORMATION, lng("AI.DubDirectEnglishOnly"));
				return;
			}
			asrModel = aAsr[0];
			ttsModel = aTts[0];
			spokenLang = spokenOf(aSpoken[0], aAsr[0]);
			subLangs = aT;
			if(aDub) {
				dubLang = aT.firstElement();
				dubVoice = aVoices[Math.max(0, aVoice[0].getSelectionIndex())];
			}
			String aSrcName = aSrc.name != null ? aSrc.name : new File(aSrc.path).getName();
			StringBuilder aNames = new StringBuilder();
			for(String aL : aT) {
				aNames.append(aNames.length() > 0 ? ", " : "").append(aL.isEmpty() ? lng("AI.SubsSpokenLanguage") : SpeechGenerator.displayName(aL, tde.config.lng));
			}
			prompt = lng(aDub ? "AI.DubPrompt" : "AI.SubsPrompt").replace("%NAME%", aSrcName).replace("%LANGS%", aNames.toString());
			clipName = aName.getText().trim();
			if(clipName.isEmpty()) {
				//The name of its file (the name shown of a library media may hold both files of a stereo pair)
				clipName = new File(aSrc.path).getName().replaceAll("[.][A-Za-z0-9]{2,4}$", "");
			}
			//Kept in 3De.conf (the models only when they are not the advised ones)
			tde.config.aiAsrModel = asrModel == SpeechGenerator.QWEN3_ASR ? "" : asrModel.id;
			tde.config.aiSpokenLang = spokenLang;
			if(aDub) {
				tde.config.aiTtsModel = ttsModel == aTtsAdvised ? "" : ttsModel.id;
				tde.config.aiDubLang = dubLang;
				tde.config.aiDubVoice = dubVoice.isEmpty() ? "original" : dubVoice;
			}
			else {
				StringBuilder aSB = new StringBuilder();
				for(String aL : aT) {
					aSB.append(aSB.length() > 0 ? "," : "").append(aL.isEmpty() ? "-" : aL);
				}
				tde.config.aiSubLangs = aSB.toString();
			}
		};
	}

	/**
	 * Language chosen in the combo of the spoken language (English name), "" to recognize it
	 */
	static String spokenOf(org.eclipse.swt.widgets.Combo aC,SpeechGenerator.Model aAsr) {
		if(aC == null || aC.getSelectionIndex() <= 0 || !(aC.getData() instanceof String[])) {
			return "";
		}
		String[] aNames = (String[])aC.getData();
		int aI = aC.getSelectionIndex()-1;
		return aI < aNames.length ? aNames[aI] : "";
	}

	/**
	 * Languages (English names) sorted by their name in the language of the interface
	 */
	String[] sortedLanguages(String[] aNames) {
		String[] aS = aNames.clone();
		java.text.Collator aCol = java.text.Collator.getInstance(java.util.Locale.forLanguageTag(tde.config.lng.toLowerCase()));
		java.util.Arrays.sort(aS, (a, b) -> aCol.compare(SpeechGenerator.displayName(a, tde.config.lng), SpeechGenerator.displayName(b, tde.config.lng)));
		return aS;
	}

	/**
	 * Choices of speech models (the advised one in bold): name, size, hardware, note (AI.Speech<id>Note)
	 */
	Choice[] speechChoices(SpeechGenerator.Model[] aModels,SpeechGenerator.Model aAdvised) {
		Choice[] aChoices = new Choice[aModels.length];
		for(int m = 0;m < aModels.length;m++) {
			SpeechGenerator.Model aM = aModels[m];
			Choice aC = aChoices[m] = new Choice();
			aC.model = aM;
			aC.advised = aM == aAdvised;
			aC.name = lng("AI.ColorModelName").replace("%NAME%", aM.name).replace("%PARAMS%", aM.params)
					.replace("%GB%", gb(aM.downloadSize()))+downloaded(aM.isDownloaded(tde.tdeDir));
			aC.details = lng("AI.SpeechHw").replace("%GB%", gb(aM.gpuMemory))+"\n"+lng("AI.Speech"+aM.id+"Note");
		}
		return aChoices;
	}

	//Sound files accepted as the source of the speech panels (with the videos)
	static final String SOUND_EXTS = "*.wav;*.mp3;*.m4a;*.aac;*.flac;*.ogg;*.opus;*.wma;*.aif;*.aiff;";

	/**
	 * Row of the video (or sound) of a speech panel: name, Choose (a file), From the library (a video or a sound of
	 * the media list)
	 */
	void createSourceRow(Composite aParent,SpeechSource aSrc) {
		Label aL = addLabel(aParent, lng("AI.SpeechSource"), 0);
		((GridData)aL.getLayoutData()).verticalIndent = 8;
		Composite aRow = new Composite(aParent, SWT.NONE);
		tde.gui.applyColorFont(aRow);
		aRow.setLayoutData(new GridData(GridData.FILL_HORIZONTAL));
		GridLayout aRGL = new GridLayout(3, false);
		tde.gui.applyNoMargin(aRGL);
		aRow.setLayout(aRGL);
		Label aName = new Label(aRow, SWT.WRAP);
		tde.gui.applyColorFont(aName);
		GridData aNGD = new GridData(GridData.FILL_HORIZONTAL);
		aNGD.widthHint = 260;
		aName.setLayoutData(aNGD);
		Button aChoose = new Button(aRow, SWT.PUSH);
		tde.gui.applyColorFont(aChoose);
		aChoose.setText(lng("AI.ImageChoose"));
		Button aLib = new Button(aRow, SWT.PUSH);
		tde.gui.applyColorFont(aLib);
		aLib.setText(lng("AI.ImageLibrary"));
		aLib.setToolTipText(lng("AI.SpeechLibraryTip"));
		aLib.setEnabled(!librarySpeech().isEmpty());
		Runnable aShow = () -> {
			aName.setText(aSrc.path == null ? lng("AI.SpeechNoSourceYet")
					: aSrc.name != null ? aSrc.name : new File(aSrc.path).getName());
			aName.setFont(aSrc.path == null ? aChoose.getFont() : tde.gui.fontsSwt.robotoBold);
			aRow.layout(true);
		};
		aChoose.addListener(SWT.Selection, e -> {
			FileDialog aFD = new FileDialog(aParent.getShell(), SWT.OPEN);
			aFD.setText(lng("AI.SpeechSource"));
			aFD.setFilterNames(new String[] {lng("AI.SpeechFilter")});
			String aExts = VIDEO_EXTS+SOUND_EXTS;
			aFD.setFilterExtensions(new String[] {aExts+aExts.toUpperCase()});
			String aDir = lastImageDir != null ? lastImageDir : (tde.lastDir != null ? tde.lastDir : tde.tdeDir);
			aFD.setFilterPath(aDir);
			String aPath = aFD.open();
			if(aPath != null) {
				lastImageDir = new File(aPath).getParent();
				aSrc.path = aPath;
				aSrc.media = null;
				aSrc.name = null;
				//Already in the media list: its clips can get the subtitles
				for(Media aM : librarySpeech()) {
					if(new File(aPath).getAbsolutePath().equals(new File(speechPath(aM)).getAbsolutePath())) {
						aSrc.media = aM;
						aSrc.name = aM.fileName;
					}
				}
				aShow.run();
			}
		});
		aLib.addListener(SWT.Selection, e -> {
			Media aM = chooseLibraryMedia(aParent.getShell(), librarySpeech(), lng("AI.SpeechLibraryTitle"));
			if(aM != null) {
				aSrc.path = speechPath(aM);
				aSrc.media = aM;
				aSrc.name = aM.fileName;
				aShow.run();
			}
		});
		aShow.run();
	}

	/**
	 * Row of the subtitles file of the speech (optional): name, Choose, Remove. Given, its subtitles are used as
	 * they are instead of the speech recognition (the language spoken chosen is the one of the file)
	 * @param aChanged run after a change
	 * @return the refresh of the row
	 */
	Runnable createSubsFileRow(Composite aParent,SpeechSource aSrc,Runnable aChanged) {
		Label aL = addLabel(aParent, lng("AI.SpeechOwnSubs"), 0);
		((GridData)aL.getLayoutData()).verticalIndent = 4;
		aL.setToolTipText(lng("AI.SpeechOwnSubsTip"));
		Composite aRow = new Composite(aParent, SWT.NONE);
		tde.gui.applyColorFont(aRow);
		aRow.setLayoutData(new GridData(GridData.FILL_HORIZONTAL));
		GridLayout aRGL = new GridLayout(3, false);
		tde.gui.applyNoMargin(aRGL);
		aRow.setLayout(aRGL);
		Label aName = new Label(aRow, SWT.WRAP);
		tde.gui.applyColorFont(aName);
		GridData aNGD = new GridData(GridData.FILL_HORIZONTAL);
		aNGD.widthHint = 260;
		aName.setLayoutData(aNGD);
		aName.setToolTipText(lng("AI.SpeechOwnSubsTip"));
		Button aChoose = new Button(aRow, SWT.PUSH);
		tde.gui.applyColorFont(aChoose);
		aChoose.setText(lng("AI.ImageChoose"));
		aChoose.setToolTipText(lng("AI.SpeechOwnSubsTip"));
		Button aRemove = new Button(aRow, SWT.PUSH);
		tde.gui.applyColorFont(aRemove);
		aRemove.setText(lng("AI.ImageRemove"));
		Runnable aShow = () -> {
			if(aRow.isDisposed()) {
				return;
			}
			aName.setText(aSrc.srt == null ? lng("AI.SpeechOwnSubsNone") : new File(aSrc.srt).getName());
			aName.setFont(aSrc.srt == null ? aChoose.getFont() : tde.gui.fontsSwt.robotoBold);
			aRemove.setEnabled(aSrc.srt != null);
			aRow.layout(true);
		};
		aChoose.addListener(SWT.Selection, e -> {
			FileDialog aFD = new FileDialog(aParent.getShell(), SWT.OPEN);
			aFD.setText(lng("AI.SpeechOwnSubs"));
			aFD.setFilterNames(new String[] {lng("Subtitles.Files"), "*"});
			aFD.setFilterExtensions(new String[] {"*.srt;*.vtt;*.SRT;*.VTT", "*"});
			String aDir = aSrc.srt != null ? new File(aSrc.srt).getParent()
					: aSrc.path != null ? new File(aSrc.path).getParent()
					: lastImageDir != null ? lastImageDir : (tde.lastDir != null ? tde.lastDir : tde.tdeDir);
			if(aDir != null) {
				aFD.setFilterPath(aDir);
			}
			String aPath = aFD.open();
			if(aPath != null) {
				aSrc.srt = aPath;
				aShow.run();
				aChanged.run();
			}
		});
		aRemove.addListener(SWT.Selection, e -> {
			aSrc.srt = null;
			aShow.run();
			aChanged.run();
		});
		aShow.run();
		return aShow;
	}

	/**
	 * Videos and sounds of the media list (their speech can be used)
	 */
	Vector<Media> librarySpeech() {
		Vector<Media> aL = new Vector<Media>();
		if(tde.mediaList == null) {
			return aL;
		}
		for(Media aM : tde.mediaList.mediaList) {
			String aP = speechPath(aM);
			if(aP != null && new File(aP).isFile()) {
				aL.add(aM);
			}
		}
		return aL;
	}

	/**
	 * File of the sound of a video (its left file, as the sound of its clips by default) or of a sound, else null
	 */
	static String speechPath(Media aM) {
		if(aM instanceof StereoMovie) {
			return ((StereoMovie)aM).getPathL();
		}
		if(aM instanceof AudioMedia) {
			Vector<String> aPs = ((AudioMedia)aM).getFilePaths();
			return aPs == null || aPs.isEmpty() ? null : aPs.firstElement();
		}
		return null;
	}

	/**
	 * Panel of a sound kind (MUSIC: MusicGen, SFX: Woosh): what the generator is made for and its licence,
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
			//The model kept loaded is unloaded before another one: its memory counts as free
			long aAvail = PythonAI.availableGpuMemory();
			long aRam = SoundGenerator.ramSize();
			//Already loaded: its memory is taken by itself, no warning
			boolean aLoaded = SoundGenerator.isLoaded(aM);
			String aW;
			boolean aRed = false;
			if(aLoaded) {
				aW = lng(aReason == null ? "AI.MusicOnGpu" : "AI.MusicOnCpu")+" "+lng("AI.ModelLoaded");
			}
			else if(aReason == null) {
				aW = lng("AI.MusicOnGpu");
				if(aAvail >= 0) {
					aW += " "+lng("AI.MusicGpuFree").replace("%GB%", gb(aAvail));
					if(aAvail < aM.gpuMemory()) {
						//Apple Silicon: the memory is shared, the CPU would not do better
						aW += "\n"+lng("AI.MusicMemoryLow").replace("%GB%", gb(aM.gpuMemory()));
						aRed = true;
					}
				}
			}
			else {
				aW = lng("AI.MusicOnCpu")+" "+lng("AI.MusicCpu"+aReason)
						.replace("%FREE%", aAvail < 0 ? "?" : gb(aAvail)).replace("%GB%", gb(aM.cudaMemory));
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
				String[] aE = (String[])aD;
				aPrompt.setText(aSfx && tde.config.aiSfxClean ? SoundGenerator.withClean(aE[3]) : aE[3]);
				aName.setText(aE[4]);
				//The duration suited to this sound (within the maximum of the model)
				if(aE.length > 6 && !aE[6].isEmpty()) {
					try {
						aSeconds[0].setSelection(Math.max(aSeconds[0].getMinimum(), Math.min(aSeconds[0].getMaximum(), Integer.parseInt(aE[6]))));
					}
					catch(NumberFormatException ex) {
						//Not a number: the duration is left
					}
				}
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

		//No background noise: for an isolated sound (not an ambience)
		Button aClean = null;
		if(aSfx) {
			aClean = new Button(aRight, SWT.CHECK);
			tde.gui.applyColorFont(aClean);
			aClean.setText(lng("AI.SfxClean"));
			aClean.setToolTipText(lng("AI.SfxCleanTip"));
			aClean.setSelection(tde.config.aiSfxClean);
			//The words go into the prompt, where they can be seen and changed
			if(tde.config.aiSfxClean && !aPrompt.getText().trim().isEmpty()) {
				aPrompt.setText(SoundGenerator.withClean(aPrompt.getText()));
			}
			final Button aCB = aClean;
			aClean.addListener(SWT.Selection, e -> {
				tde.config.aiSfxClean = aCB.getSelection();
				String aT = aPrompt.getText();
				aPrompt.setText(aCB.getSelection() ? SoundGenerator.withClean(aT) : SoundGenerator.withoutClean(aT));
			});
		}
		final Button aCleanB = aClean;
		//Optional image: Qwen3-VL proposes the prompt of a sound suited to it
		SoundImage aSI = new SoundImage(aSfx, aPrompt, aSeconds[0], aCleanB);
		createImageRow(aRight, null, aSfx ? sfxImg : musicImg, lng(aSfx ? "AI.SfxImage" : "AI.MusicImage"), aSI::analyze, aSI::ready);
		aSI.status = addLabel(aRight, " ", 0);
		aSI.status.addListener(SWT.Dispose, e -> aSI.hidden());
		aDownload[0] = addLabel(aRight, " \n ", 0);
		addLabel(aRight, lng("AI.SoundNote"), 0);
		labelWidth = 560;
		aShow.run();
		return () -> {
			//The prompt is being proposed from the image: wait for it
			if(aSI.busy) {
				aSI.setStatus(lng("AI.ImageAnalysisWait"), true);
				return;
			}
			String aP = aPrompt.getText().trim();
			if(aP.isEmpty()) {
				aPrompt.setFocus();
				return;
			}
			aSI.hidden();
			//Typed after the box was checked: the words are added now (seen in the prompt)
			if(aCleanB != null && aCleanB.getSelection()) {
				aP = SoundGenerator.withClean(aP);
				aPrompt.setText(aP);
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
				tde.config.aiSfxClean = aCleanB != null && aCleanB.getSelection();
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
	 * ("kind|Category/Subcategory|BPM|Name|prompt[|seconds]"): {kind, path, bpm, prompt, name, description, seconds ("": none)}.
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
				String[] aF = aL.split("[|]", 6);
				if(aL.startsWith("#") || aF.length < 5) {
					continue;
				}
				String aPrompt = aF[4].trim();
				String[] aT = aTr.get(aF[0].trim()+"|"+aF[3].trim());
				aEx.add(new String[] {aF[0].trim(), aF[1].trim(), aF[2].trim(), aPrompt
						, aT == null ? aF[3].trim() : aT[0]
						//The tempo is shown first, not again at the end of the prompt
						, aT == null ? aPrompt.replaceAll(",\\s*[0-9]+ bpm$", "") : aT[1]
						//Duration suited to the sound (sfx), "" if none
						, aF.length > 5 ? aF[5].trim() : ""});
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
	//Example last chosen in the library of each kind (video, image, music, sfx): {English prompt, name}, shown
	//selected again when the form comes back (Retry, Something else, cancel)
	java.util.Map<String,String[]> lastExamples = new java.util.HashMap<String,String[]>();

	Tree createExamples(Composite aPanel,String aKind) {
		Tree aTree = new Tree(aPanel, SWT.BORDER | SWT.SINGLE | SWT.V_SCROLL | SWT.H_SCROLL);
		aTree.addListener(SWT.Selection, e -> {
			Object aD = e.item == null ? null : e.item.getData();
			if(aD instanceof String[]) {
				String[] aE = (String[])aD;
				lastExamples.put(aE[0], new String[] {aE[3], aE[4]});
			}
		});
		tde.gui.applyColorFont(aTree);
		GridData aGD = new GridData(GridData.FILL_BOTH);
		aGD.widthHint = labelWidth;
		aGD.heightHint = tde.gui.h(300);
		aTree.setLayoutData(aGD);
		fillExamples(aTree, aKind);
		return aTree;
	}

	/**
	 * (Re)fills an examples tree with the examples of a kind (see createExamples())
	 */
	void fillExamples(Tree aTree,String aKind) {
		aTree.setRedraw(false);
		aTree.removeAll();
		aTree.setData(KIND_DATA, aKind);
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
		aTree.setRedraw(true);
		//The example last chosen of this kind: selected and shown (its categories opened)
		String[] aLast = lastExamples.get(aKind);
		TreeItem aItem = aLast == null ? null : findExample(aTree.getItems(), aLast);
		if(aItem != null) {
			aTree.setSelection(aItem);
			aTree.showItem(aItem);
			//Scrolled again once the window has its size
			tde.gui.display.asyncExec(() -> {
				if(!aTree.isDisposed() && !aItem.isDisposed()) {
					aTree.showItem(aItem);
				}
			});
		}
	}

	/**
	 * Leaf of the examples whose English prompt and name are aExample, null if none
	 */
	static TreeItem findExample(TreeItem[] aItems,String[] aExample) {
		for(TreeItem aI : aItems) {
			Object aD = aI.getData();
			if(aD instanceof String[] && aExample[0].equals(((String[])aD)[3]) && aExample[1].equals(((String[])aD)[4])) {
				return aI;
			}
			TreeItem aF = findExample(aI.getItems(), aExample);
			if(aF != null) {
				return aF;
			}
		}
		return null;
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
	 * Image row (aRef): thumbnail, name, Choose / From the library / Remove (a video: one of its frames).
	 * Video panel (aSizeL: the size label): the optional reference image, the video starts from it (cropped to
	 * the ratio of the video, whose orientation follows the image) and animates it according to the prompt;
	 * colorization panel (aSizeL null): the photo to colorize.
	 * aOnChange runs after each change of the image (and once at the creation).
	 */
	void createImageRow(Composite aShell,Label aSizeL,RefImage aRef,String aLabel,Runnable aOnChange) {
		createImageRow(aShell, aSizeL, aRef, aLabel, aOnChange, null);
	}

	/**
	 * Same; aBefore (null: none) runs when Choose or From the library is clicked, before the choice: false
	 * cancels it
	 */
	void createImageRow(Composite aShell,Label aSizeL,RefImage aRef,String aLabel,Runnable aOnChange,java.util.function.BooleanSupplier aBefore) {
		Label aIL = addLabel(aShell, aLabel, 0);
		((GridData)aIL.getLayoutData()).verticalIndent = 8;
		Composite aRow = new Composite(aShell, SWT.NONE);
		tde.gui.applyColorFont(aRow);
		aRow.setLayoutData(new GridData(GridData.FILL_HORIZONTAL));
		GridLayout aRGL = new GridLayout(5, false);
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
		aNGD.widthHint = 200;
		aName.setLayoutData(aNGD);
		Button aChoose = new Button(aRow, SWT.PUSH);
		tde.gui.applyColorFont(aChoose);
		aChoose.setText(lng("AI.ImageChoose"));
		//Second selector: a still image of the media list
		Button aLib = new Button(aRow, SWT.PUSH);
		tde.gui.applyColorFont(aLib);
		aLib.setText(lng("AI.ImageLibrary"));
		aLib.setToolTipText(lng("AI.ImageLibraryTip"));
		aLib.setEnabled(!libraryImages().isEmpty());
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
			aRemove.setEnabled(aRef.path != null);
			if(aRef.path == null) {
				//Video: generated from the text alone; the others: nothing chosen yet
				aName.setText(lng(aRef == videoImg ? "AI.ImageNone" : aRef == sceneImg ? "AI.SceneImageNone" : "AI.ImageNoneChosen"));
				if(aSizeL != null) {
					setOrientation(tde.config.outRatio > 0 && tde.config.outRatio < 1);
				}
			}
			else {
				aName.setText(aRef.name != null ? aRef.name : new File(aRef.path).getName());
				try {
					BufferedImage aBI = ImageUtils.loadImage(aRef.path);
					if(aSizeL != null) {
						setSizeFor(aBI.getWidth(), aBI.getHeight());
					}
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
			if(aSizeL != null) {
				aSizeL.setText(sizeText());
				aSizeL.getParent().layout(true);
			}
			aRow.layout(true);
			aOnChange.run();
		};
		if(aRef == videoImg) {
			imageRowShow = aShow;
		}
		aChoose.addSelectionListener(new SelectionAdapter() {
			@Override
			public void widgetSelected(SelectionEvent e) {
				if(aBefore != null && !aBefore.getAsBoolean()) {
					return;
				}
				FileDialog aFD = new FileDialog(aShell.getShell(), SWT.OPEN);
				aFD.setText(lng("AI.Image"));
				aFD.setFilterNames(new String[] {lng("AI.ImageFilter")});
				String aExts = "*.jpg;*.jpeg;*.png;*.webp;*.bmp;*.gif;"+VIDEO_EXTS;
				aFD.setFilterExtensions(new String[] {aExts+aExts.toUpperCase()});
				String aDir = lastImageDir != null ? lastImageDir : (tde.lastDir != null ? tde.lastDir : tde.tdeDir);
				aFD.setFilterPath(aDir);
				String aPath = aFD.open();
				if(aPath != null) {
					lastImageDir = new File(aPath).getParent();
					if(isVideoFile(aPath)) {
						//A video: one of its frames, chosen in a preview
						String aFrame = chooseVideoFrame(aShell.getShell(), aPath, null);
						if(aFrame == null) {
							return;
						}
						aRef.path = aFrame;
						aRef.name = frameName;
					}
					else {
						aRef.path = aPath;
						aRef.name = null;
					}
					aShow.run();
				}
			}
		});
		aRemove.addSelectionListener(new SelectionAdapter() {
			@Override
			public void widgetSelected(SelectionEvent e) {
				aRef.path = null;
				aRef.name = null;
				aShow.run();
			}
		});
		aLib.addSelectionListener(new SelectionAdapter() {
			@Override
			public void widgetSelected(SelectionEvent e) {
				if(aBefore != null && !aBefore.getAsBoolean()) {
					return;
				}
				StereoImage aM = chooseLibraryImage(aShell.getShell());
				if(aM == null) {
					return;
				}
				String aPath;
				String aShown;
				if(aM instanceof StereoMovie) {
					aPath = chooseVideoFrame(aShell.getShell(), aM.getPathL(), (StereoMovie)aM);
					aShown = frameName;
				}
				else {
					aPath = exportLibraryImage(aM);
					aShown = aM.fileName;
				}
				if(aPath != null) {
					aRef.path = aPath;
					aRef.name = aShown != null ? aShown : new File(aPath).getName();
					aShow.run();
				}
			}
		});
		aShow.run();
	}

	//Videos accepted as the source of a reference image (one frame chosen in VideoFrameGUI)
	static final String VIDEO_EXTS = "*.mp4;*.mpg;*.mpeg;*.avi;*.mov;*.mkv;*.vob;*.ogv;*.wmv;*.ts;*.mts;*.m2ts;*.flv;*.dv;*.webm;*.m4v;";
	//Name shown for the last frame chosen by chooseVideoFrame(): "video @ time"
	String frameName = null;

	static boolean isVideoFile(String aPath) {
		String aN = new File(aPath).getName().toLowerCase();
		int aDot = aN.lastIndexOf('.');
		return aDot > 0 && VIDEO_EXTS.contains("*"+aN.substring(aDot)+";");
	}

	/**
	 * Frame of a video chosen in a preview (aMovie: the library media, null for a file of the disk), written to
	 * a PNG of the WorkCache; null if canceled or on error. Sets frameName.
	 */
	String chooseVideoFrame(Shell aParent,String aVideo,StereoMovie aMovie) {
		VideoFrameGUI aVF = new VideoFrameGUI(tde, aVideo, aMovie);
		BufferedImage aBI = aVF.choose(aParent);
		if(aBI == null) {
			return null;
		}
		String aBase = aMovie != null && aMovie.fileName != null ? aMovie.fileName : new File(aVideo).getName();
		frameName = aBase+" @ "+VideoFrameGUI.clock(aVF.chosenMS);
		return writeRefImage(aBI, "AI_RefImage_"+fileBase(new File(aVideo).getName())+"_"+aVF.chosenMS);
	}

	/**
	 * Medias of the media list that can give a reference image: still images (not the color backgrounds, the
	 * shapes or the 3D models), loaded, and videos (a frame chosen in VideoFrameGUI)
	 */
	Vector<StereoImage> libraryImages() {
		Vector<StereoImage> aL = new Vector<StereoImage>();
		if(tde.mediaList == null) {
			return aL;
		}
		for(Media aM : tde.mediaList.mediaList) {
			if(aM instanceof StereoMovie ? ((StereoMovie)aM).getPathL() != null
					: aM instanceof StereoImage && !(aM instanceof ColorBck)
					&& !(aM instanceof ShapesMedia) && !(aM instanceof Model3DMedia)
					&& leftView((StereoImage)aM) != null) {
				aL.add((StereoImage)aM);
			}
		}
		return aL;
	}

	/**
	 * Left view of a media, with its rotation / anamorphosis
	 */
	static BufferedImage leftView(StereoImage aM) {
		return aM.rotatedLeft != null ? aM.rotatedLeft : aM.loadedLeft;
	}

	/**
	 * Window listing the still images of the media list (thumbnail + name): OK or a double click chooses one
	 */
	StereoImage chooseLibraryImage(Shell aParent) {
		return (StereoImage)chooseLibraryMedia(aParent, new Vector<Media>(libraryImages()), lng("AI.ImageLibraryTitle"));
	}

	/**
	 * Window listing medias of the media list (thumbnail + name): OK or a double click chooses one
	 */
	Media chooseLibraryMedia(Shell aParent,Vector<Media> aImages,String aTitle) {
		if(aImages.isEmpty()) {
			return null;
		}
		Shell aShell = new Shell(aParent, SWT.DIALOG_TRIM | SWT.APPLICATION_MODAL | SWT.RESIZE);
		aShell.setText(aTitle);
		tde.gui.applyColorFont(aShell);
		aShell.setLayout(new GridLayout(1, false));
		org.eclipse.swt.widgets.Table aTable = new org.eclipse.swt.widgets.Table(aShell, SWT.BORDER | SWT.SINGLE | SWT.FULL_SELECTION | SWT.V_SCROLL);
		tde.gui.applyColorFont(aTable);
		GridData aGD = new GridData(GridData.FILL_BOTH);
		aGD.widthHint = 420;
		aGD.heightHint = 360;
		aTable.setLayoutData(aGD);
		Vector<Image> aThumbs = new Vector<Image>();
		aShell.addListener(SWT.Dispose, e -> {
			for(Image aI : aThumbs) {
				aI.dispose();
			}
		});
		//Thumbnails of the same size (the rows of a table take the height of the first image)
		final int aTW = 96, aTH = 54;
		for(Media aM : aImages) {
			org.eclipse.swt.widgets.TableItem aItem = new org.eclipse.swt.widgets.TableItem(aTable, SWT.NONE);
			aItem.setText((aM.fileName != null ? aM.fileName : "?")+(aM instanceof StereoMovie ? lng("AI.ImageLibraryVideo")
					: aM instanceof AudioMedia ? lng("AI.LibrarySound") : ""));
			aItem.setData(aM);
			try {
				BufferedImage aSrc = aM instanceof StereoImage && !(aM instanceof StereoMovie) ? leftView((StereoImage)aM) : aM.getThumbAwt();
				if(aSrc == null) {
					continue;
				}
				BufferedImage aBI = new BufferedImage(aTW, aTH, BufferedImage.TYPE_INT_ARGB);
				double aS = Math.min(aTW/(double)aSrc.getWidth(), aTH/(double)aSrc.getHeight());
				int aW = Math.max(1, (int)(aSrc.getWidth()*aS));
				int aH = Math.max(1, (int)(aSrc.getHeight()*aS));
				java.awt.Graphics2D aG = aBI.createGraphics();
				aG.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION, java.awt.RenderingHints.VALUE_INTERPOLATION_BILINEAR);
				aG.drawImage(aSrc, (aTW-aW)/2, (aTH-aH)/2, aW, aH, null);
				aG.dispose();
				Image aI = ImageUtils.imageToSwt(tde.gui.display, aBI);
				aThumbs.add(aI);
				aItem.setImage(aI);
			}
			catch(Throwable t) {
				t.printStackTrace(System.err);
			}
		}
		aTable.select(0);
		Composite aButtons = new Composite(aShell, SWT.NONE);
		tde.gui.applyColorFont(aButtons);
		aButtons.setLayoutData(new GridData(SWT.END, SWT.CENTER, true, false));
		aButtons.setLayout(new GridLayout(2, true));
		Button aCancel = new Button(aButtons, SWT.PUSH);
		tde.gui.applyColorFont(aCancel);
		aCancel.setText(lng("AI.Cancel"));
		aCancel.setLayoutData(new GridData(GridData.FILL_HORIZONTAL));
		Button aOk = new Button(aButtons, SWT.PUSH);
		tde.gui.applyColorFont(aOk);
		aOk.setText(lng("AI.Ok"));
		aOk.setLayoutData(new GridData(GridData.FILL_HORIZONTAL));
		final Media[] aResult = {null};
		Runnable aChoose = () -> {
			int aI = aTable.getSelectionIndex();
			if(aI >= 0) {
				aResult[0] = (Media)aTable.getItem(aI).getData();
				aShell.dispose();
			}
		};
		aOk.addListener(SWT.Selection, e -> aChoose.run());
		aTable.addListener(SWT.DefaultSelection, e -> aChoose.run());
		aCancel.addListener(SWT.Selection, e -> aShell.dispose());
		aShell.setDefaultButton(aOk);
		aShell.pack();
		Rectangle aP = aParent.getBounds();
		Rectangle aS = aShell.getBounds();
		aShell.setLocation(aP.x + (aP.width - aS.width) / 2, aP.y + (aP.height - aS.height) / 2);
		aShell.open();
		while(!aShell.isDisposed()) {
			if(!tde.gui.display.readAndDispatch()) {
				tde.gui.display.sleep();
			}
		}
		return aResult[0];
	}

	/**
	 * Left view of a library image written to a PNG of the WorkCache (an MPO or a side-by-side file is not a
	 * reference image as is), null on error
	 */
	String exportLibraryImage(StereoImage aM) {
		return writeRefImage(leftView(aM), "AI_RefImage_"+aM.UID);
	}

	/**
	 * Image written to <WorkCache>/<aBase>.png (opaque RGB), its path, null on error
	 */
	String writeRefImage(BufferedImage aSrc,String aBase) {
		try {
			BufferedImage aBI = new BufferedImage(aSrc.getWidth(), aSrc.getHeight(), BufferedImage.TYPE_INT_RGB);
			java.awt.Graphics2D aG = aBI.createGraphics();
			aG.setColor(java.awt.Color.BLACK);
			aG.fillRect(0, 0, aBI.getWidth(), aBI.getHeight());
			aG.drawImage(aSrc, 0, 0, null);
			aG.dispose();
			File aDir = new File(tde.getWorkCacheDir());
			aDir.mkdirs();
			File aF = new File(aDir, aBase+".png");
			ImageIO.write(aBI, "png", aF);
			return aF.getAbsolutePath();
		}
		catch(Throwable t) {
			t.printStackTrace(System.err);
			return null;
		}
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
		return outDir(tde);
	}

	public static File outDir(TDenlive aTDe) {
		String aDir = aTDe.projectDir == null || aTDe.projectName == null ? aTDe.tdeDir : aTDe.projectDir;
		return new File(aDir, "AI");
	}

	/**
	 * Progress window (with the preview for a video), and generation thread
	 */
	void start() {
		running = true;
		canceled = false;
		final int aRun = ++runId;
		final PythonAI aEnding = ending;
		ending = null;
		generator = null;
		//Music and sound effects: generated in series, several ahead, listened and kept in the same window
		if(music && !color) {
			new AiSoundSeriesGUI(this, aEnding).open();
			return;
		}
		//Mask: its editor window (running until it is closed)
		if(mask) {
			new AiMaskGUI(this, maskSrc, maskModel, clipName.isEmpty() ? null : clipName).open();
			return;
		}
		//3D scene saved before: the viewer at once (not a generation: other AI clips can be made meanwhile)
		if(scene && scenePly != null) {
			String aPly = scenePly;
			scenePly = null;
			running = false;
			if(!new AiSceneGUI(tde, new File(aPly)).open()) {
				again();
			}
			return;
		}
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
		if(color) {
			addLabel(shell, "DDColor "+colorModel.id+" - "+lng(ColorGenerator.cpuReason(colorModel) == null ? "AI.MusicOnGpu" : "AI.MusicOnCpu"), 0);
		}
		else if(scene) {
			addLabel(shell, lng(sceneModel.key())+" - "+lng("AI.SceneDetail"+sceneDetail.id).replace("%N%", String.format("%,d", sceneDetail.gaussians(sceneModel)))
					+" - "+lng(SceneGenerator.cpuReason(sceneModel) == null ? "AI.MusicOnGpu" : "AI.MusicOnCpu"), 0);
		}
		else if(speech) {
			addLabel(shell, dub && ttsModel == SpeechGenerator.HIBIKI ? ttsModel.name : asrModel.name+(dub ? " + "+ttsModel.name : ""), 0);
		}
		else if(music) {
			String aReason = SoundGenerator.cpuReason(soundModel);
			addLabel(shell, (SoundGenerator.MUSIC.equals(soundModel.kind) ? "MusicGen " : "")+soundModel.id+" : "+String.format("%.0f s", seconds)
					+" - "
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
		working(statusL, true);
		working(detailL, true);
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
		final Shell aShell = shell;
		if(!music && !color && !scene && !speech) {
			animate();
		}
		if(speech) {
			runSpeech(aRun, aEnding, aShell);
			return;
		}

		File aOutDir = outDir();
		String aExt = music ? ".wav" : color ? ".png" : scene ? ".ply" : ".mp4";
		String aBase = fileBase(clipName);
		if(aBase.isEmpty()) {
			aBase = (music ? (SoundGenerator.SFX.equals(soundModel.kind) ? "AI_Sfx_" : "AI_Music_") : color ? "AI_Color_" : scene ? "AI_Scene_" : "AI_")+new SimpleDateFormat("yyyyMMdd_HHmmss").format(new Date());
		}
		//A name already taken in the folder: name_2, name_3...
		aBase = freeBase(aOutDir, aBase, aExt);
		File aOut = new File(aOutDir, aBase+aExt);
		File aPromptFile = new File(aOutDir, aBase+".txt");
		String aRef = color ? colorImg.path : scene ? sceneImg.path : videoImg.path;
		//The result window shows the colorized photo in its ratio
		if(color) {
			try {
				BufferedImage aBI = ImageUtils.loadImage(aRef);
				width = aBI.getWidth();
				height = aBI.getHeight();
			}
			catch(Throwable t) {
				t.printStackTrace(System.err);
			}
		}
		int aFrames = WanGenerator.frames(seconds);
		Thread aTh = new Thread(new Runnable() {
			@Override
			public void run() {
				String aError = null;
				boolean aDone = false;
				try {
					//The process of a canceled generation is still ending (a long GPU operation): its memory is
					//given back first
					for(int w = 0;aEnding != null && aEnding.isRunning() && !isCanceled(aRun) && w < 1200;w++) {
						Thread.sleep(100);
					}
					aOutDir.mkdirs();
					//The prompt stays next to the video
					Files.write(aPromptFile.toPath(), prompt.getBytes(StandardCharsets.UTF_8));
					PythonAI.Progress aProgress = new PythonAI.Progress() {
						@Override
						public void status(String aKey, String aDetail, long aDoneN, long aTotal) {
							if(!AiClipGUI.this.isCanceled(aRun)) {
								showStatus(aKey, aDetail, aDoneN, aTotal);
							}
						}
						@Override
						public void preview(String aPng, int aNbFrames) {
							if(!AiClipGUI.this.isCanceled(aRun)) {
								showPreview(aPng, aNbFrames);
							}
						}
						@Override
						public boolean isCanceled() {
							return AiClipGUI.this.isCanceled(aRun);
						}
					};
					if(scene) {
						SceneGenerator aSG = new SceneGenerator(tde.tdeDir, sceneModel, aProgress);
						generator = aSG;
						aDone = aSG.generate(new File(aRef), sceneDetail, aOut) && !isCanceled(aRun);
					}
					else if(color) {
						ColorGenerator aCG = new ColorGenerator(tde.tdeDir, colorModel, aProgress);
						generator = aCG;
						aDone = aCG.colorize(new File[] {new File(aRef)}, new File[] {aOut}) && !isCanceled(aRun);
					}
					else if(music) {
						SoundGenerator aMG = new SoundGenerator(tde.tdeDir, soundModel, aProgress);
						generator = aMG;
						aDone = aMG.generate(aPromptFile, seconds, aOut) && !isCanceled(aRun);
					}
					else {
						WanGenerator aWG = new WanGenerator(tde.tdeDir, videoModel, aProgress);
						generator = aWG;
						aDone = aWG.generate(aPromptFile, aRef == null ? null : new File(aRef), aFrames, width, height, aOut) && !isCanceled(aRun);
					}
				}
				catch(Throwable t) {
					aError = errorMessage(t);
				}
				final boolean aCanceled = isCanceled(aRun);
				if(!aDone) {
					aPromptFile.delete();
					if(aCanceled) {
						aOut.delete();
					}
				}
				final String aErr = aError;
				final boolean aOk = aDone;
				tde.gui.display.asyncExec(new Runnable() {
					@Override
					public void run() {
						//Canceled: the form is already shown again (or closed), nothing to report
						if(aCanceled) {
							return;
						}
						if(!aShell.isDisposed()) {
							aShell.dispose();
						}
						if(aOk && scene) {
							//The scene stays in the AI folder (opened again with the button of the panel); the viewer
							//is not a generation: other AI clips can be made meanwhile
							running = false;
							new AiSceneGUI(tde, aOut).open();
							return;
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
	 * Subtitles or voice translation of the video (thread), then the result window (AiSpeechResultGUI)
	 */
	void runSpeech(int aRun,PythonAI aEnding,Shell aShell) {
		File aOutDir = outDir();
		String aBase = fileBase(clipName);
		if(aBase.isEmpty()) {
			aBase = "AI_Speech_"+new SimpleDateFormat("yyyyMMdd_HHmmss").format(new Date());
		}
		final String aB = aBase;
		final SpeechSource aSrc = speechSource();
		final boolean aDub = dub;
		Thread aTh = new Thread(() -> {
			String aError = null;
			SpeechGenerator.Result aRes = null;
			try {
				for(int w = 0;aEnding != null && aEnding.isRunning() && !isCanceled(aRun) && w < 1200;w++) {
					Thread.sleep(100);
				}
				aOutDir.mkdirs();
				PythonAI.Progress aProgress = new PythonAI.Progress() {
					@Override
					public void status(String aKey, String aDetail, long aDoneN, long aTotal) {
						if(!AiClipGUI.this.isCanceled(aRun)) {
							showStatus(aKey, aDetail, aDoneN, aTotal);
						}
					}
					@Override
					public boolean isCanceled() {
						return AiClipGUI.this.isCanceled(aRun);
					}
				};
				SpeechGenerator aSG = new SpeechGenerator(tde.tdeDir, tde.getWorkCacheDir(), aProgress);
				generator = aSG;
				File aIn = new File(aSrc.path);
				File aOwn = aSrc.srt == null ? null : new File(aSrc.srt);
				aRes = aDub ? aSG.dub(aIn, aOwn, asrModel, spokenLang, dubLang, ttsModel, dubVoice, aOutDir, aB)
						: aSG.subtitles(aIn, aOwn, asrModel, spokenLang, subLangs, aOutDir, aB);
				if(isCanceled(aRun) && aRes != null) {
					for(File aF : aRes.files) {
						aF.delete();
					}
					if(aRes.voice != null) {
						aRes.voice.delete();
					}
					aRes = null;
				}
			}
			catch(Throwable t) {
				aError = errorMessage(t);
			}
			final boolean aCanceled = isCanceled(aRun);
			final String aErr = aError;
			final SpeechGenerator.Result aR = aRes;
			tde.gui.display.asyncExec(() -> {
				if(aCanceled) {
					return;
				}
				if(!aShell.isDisposed()) {
					aShell.dispose();
				}
				if(aR != null) {
					new AiSpeechResultGUI(this, aR, aSrc.media, aSrc.path).open();
					return;
				}
				running = false;
				if(aErr != null) {
					message(tde, SWT.ICON_ERROR, aErr);
				}
			});
		}, "AI speech");
		aTh.start();
	}


	/**
	 * Message of a failed generation
	 */
	String errorMessage(Throwable aT) {
		aT.printStackTrace(System.err);
		if(aT instanceof PythonAI.GenerationException) {
			PythonAI.GenerationException e = (PythonAI.GenerationException)aT;
			if(speech && ("NOSOUND".equals(e.code) || "NOSPEECH".equals(e.code))) {
				return lng("NOSOUND".equals(e.code) ? "AI.SpeechNoSound" : "AI.SpeechNoSpeech");
			}
			if("OOM".equals(e.code)) {
				return speech ? lng("AI.SpeechErrorOom")
						: color ? lng("AI.ColorErrorOom")
						: scene ? lng("AI.SceneErrorOom")
						: music
						? lng("AI.MusicErrorOom").replace("%GB%", DepthModel.gb(soundModel.gpuMemory()))
						: lng("AI.ErrorOom").replace("%GB%", DepthModel.gb(videoModel.gpuMemory));
			}
			if("NOGPU".equals(e.code)) {
				return lng("AI.ErrorNoGpu");
			}
			return lng(errorKey())+"\n"+e.code;
		}
		return lng(errorKey())+"\n"+(aT.getMessage() == null ? aT.toString() : aT.getMessage());
	}

	/**
	 * aBase+aExt in aDir, or aBase_2, aBase_3... when taken (with its prompt file .txt)
	 */
	static String freeBase(File aDir,String aBase,String aExt) {
		String aFree = aBase;
		for(int n = 2;new File(aDir, aFree+aExt).exists() || new File(aDir, aFree+".txt").exists();n++) {
			aFree = aBase+"_"+n;
		}
		return aFree;
	}

	/**
	 * Language key of the failure message of the kind generated
	 */
	String errorKey() {
		return speech ? "AI.SpeechError" : color ? "AI.ColorError" : scene ? "AI.SceneError" : !music ? "AI.Error" : SFX.equals(soundModel.kind) ? "AI.SfxError" : "AI.MusicError";
	}

	/**
	 * Run aRun is canceled (or replaced by a new run)
	 */
	boolean isCanceled(int aRun) {
		return canceled || aRun != runId;
	}

	/**
	 * Cancel of the progress window: the generation is stopped and the form comes back with the same kind and
	 * settings (prompt, image, duration, name), as before the generation
	 */
	void cancel() {
		if(canceled) {
			return;
		}
		canceled = true;
		System.out.println("AI generation canceled by the user");
		PythonAI aG = generator;
		if(aG != null) {
			aG.cancel();
		}
		ending = aG;
		//The window closes at once (the generation thread ends by itself: its process is killed)
		if(shell != null && !shell.isDisposed()) {
			shell.dispose();
		}
		//The form again, once the window is closed (still running meanwhile: one generation at a time)
		tde.gui.display.asyncExec(this::again);
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
		boolean aSteps = "AI.Generating".equals(aKey) || "AI.GeneratingMusic".equals(aKey) || "AI.GeneratingSfx".equals(aKey)
				|| "AI.Transcribing".equals(aKey) || "AI.Translating".equals(aKey) || "AI.SpeakingTranslation".equals(aKey)
				|| "AI.TranslatingVoice".equals(aKey);
		//Another counted step (speech: transcription, translation, voice): its own remaining time
		if(aSteps && !aKey.equals(stepKeyNow)) {
			stepKeyNow = aKey;
			stepStartMS = -1;
		}
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
				working(statusL, true);
				working(detailL, true);
				working(etaL, true);
				if(aBar >= 0) {
					bar.setSelection(aBar);
				}
			}
		});
	}
}
