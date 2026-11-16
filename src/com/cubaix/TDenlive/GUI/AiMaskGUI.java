package com.cubaix.TDenlive.GUI;

import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.io.BufferedReader;
import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Vector;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import javax.imageio.ImageIO;

import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.GC;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.graphics.Rectangle;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Canvas;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.MessageBox;
import org.eclipse.swt.widgets.ProgressBar;
import org.eclipse.swt.widgets.Scale;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Slider;
import org.eclipse.swt.widgets.Table;
import org.eclipse.swt.widgets.TableItem;
import org.eclipse.swt.widgets.Text;

import com.cubaix.TDenlive.TDConfig;
import com.cubaix.TDenlive.TDenlive;
import com.cubaix.TDenlive.ai.MaskGenerator;
import com.cubaix.TDenlive.ai.PythonAI;
import com.cubaix.TDenlive.depth.DepthModel;
import com.cubaix.TDenlive.ffmpeg.Ffmpeg;
import com.cubaix.TDenlive.medias.Media;

/**
 * Editor of an AI mask (AI window, "Mask" panel): the image or video (a stereo source: its two views side by side,
 * left first) in a large preview, a slider for the time. A click adds the place to the selected object (SAM 2.1,
 * see ai.MaskGenerator), Shift+click or a right click removes it; in a stereo source the other view is delimited at
 * the same time (shifted by the parallax), a click in it corrects it. Other tools: a box dragged around the object,
 * and a brush / eraser that paints the mask of the object on the frame (starting from the mask shown), given to the
 * model as the mask of that frame: kept as painted, and the reference of the tracking. The delimitation is drawn over the image in
 * the color of each object, semi-transparent with an opaque outline. The objects are then tracked over the video,
 * and the mask (white = the objects, black elsewhere; the views side by side as in the source, at its full size;
 * one PNG or an H.264 video at the frame rate of the source) is written into the AI folder and added to the media
 * list, to be used as the mask of the Transparency processor of a clip.
 * The frames are extracted by ffmpeg into a work folder of the WorkCache (each view at most WORK_SIDE pixels),
 * deleted at the closing.
 */
public class AiMaskGUI {
	//Longest side of a view in the work frames (SAM sees 1024 x 1024; the masks are enlarged to the full size)
	static final int WORK_SIDE = 1280;
	//Colors of the objects (RGB)
	static final int[] COLORS = {0xFF3B30, 0x34C759, 0x007AFF, 0xFFCC00, 0xAF52DE, 0x00C7BE, 0xFF9500, 0xFF2D55
			, 0x5856D6, 0xA2845E, 0x30B0C7, 0x8E8E93, 0x64D2FF, 0xBF5AF2, 0xFFD60A, 0x32D74B};
	//Opacity of the inside of an object over the image (its outline is opaque)
	static final double FILL_ALPHA = 0.45;
	//Transparency of the image under the objects, 0..100 % (100: the mask alone, the objects opaque on black),
	//kept for the next windows of the session
	static volatile int bgTransparency = 0;

	/**
	 * Image or video to delimit
	 */
	public static class Source {
		//Layouts: one view, two views side by side (left view on the left / on the right), two files
		public static final int MONO = 0;
		public static final int SBS_P = 1;
		public static final int SBS_X = 2;
		public static final int TWO = 3;
		public String path = null;
		public String pathR = null;
		public int layout = MONO;
		public boolean video = false;
		//Name shown (null: the file name)
		public String name = null;
		//Media of the library it comes from (null: a file of the disk)
		public Media media = null;
		//Still image of the library: its views as loaded (before rotation: the mask follows the orientation of the
		//clip, see Transparency), right null for a 2D image
		public BufferedImage left = null;
		public BufferedImage right = null;

		public String shownName() {
			return name != null ? name : (path != null ? new File(path).getName() : "?");
		}

		public boolean isStereo() {
			return layout != MONO;
		}
	}

	AiClipGUI ai;
	TDenlive tde;
	Source src;
	MaskGenerator gen;
	String maskName;

	Shell shell;
	Canvas canvas;
	Slider slider;
	Button prevB;
	Button nextB;
	Label timeL;
	Label statusL;
	Label detailL;
	ProgressBar bar;
	Table objT;
	Button newObjB;
	Button delObjB;
	Button undoB;
	Button trackB;
	Button resumeB;
	Button stopB;
	Button createB;
	Text nameT;
	Vector<Image> swatches = new Vector<Image>();

	File workDir;
	File framesDir;
	File labelsDir;
	File stopFile;
	//Frames, views; size of a view in the work frames and at full size
	int count = 0;
	int nViews = 1;
	int viewW = 0;
	int viewH = 0;
	int fullW = 0;
	int fullH = 0;
	double fps = 25;
	String fpsText = "25";

	volatile boolean ready = false;
	volatile boolean busy = false;
	volatile boolean tracking = false;
	volatile boolean closed = false;
	//Hard cancel (window closed while installing / loading): the script is killed
	volatile boolean canceled = false;
	//The labels cover the whole video for the objects as they are now (else tracked again before the mask)
	boolean tracked = false;
	//First frame changed since the last tracking started (MAX_VALUE: none): a resume starts there at the latest
	int firstChanged = Integer.MAX_VALUE;
	//A tracking was done once: a later change asks for a new tracking
	volatile boolean everTracked = false;
	//A mask was created since the last change (no question at the closing)
	boolean created = false;
	//Objects (ids 1..16) in the order of creation, the selected one
	Vector<Integer> objects = new Vector<Integer>();
	int current = -1;
	/**
	 * Prompt of an object in a view on a frame: points ({x, y, 1 in / 0 out}, view coordinates of the work frame)
	 * with a box ({x0, y0, x1, y1}, null: none), or a painted mask (PNG of the size of the view, then no points nor
	 * box)
	 */
	static class Prompt {
		Vector<double[]> pts = new Vector<double[]>();
		double[] box = null;
		File mask = null;

		Prompt copy() {
			Prompt aP = new Prompt();
			for(double[] aPt : pts) {
				aP.pts.add(aPt.clone());
			}
			aP.box = box == null ? null : box.clone();
			aP.mask = mask;
			return aP;
		}

		boolean isEmpty() {
			return pts.isEmpty() && box == null && mask == null;
		}
	}
	//Prompts by "object/view/frame"
	final Map<String,Prompt> prompts = new HashMap<String,Prompt>();
	//Undo: {key, the prompt before the action (null: none)}, in the order of the actions
	Vector<Object[]> history = new Vector<Object[]>();

	//Tools
	static final int TOOL_POINTS = 0;
	static final int TOOL_BOX = 1;
	static final int TOOL_BRUSH = 2;
	static final int TOOL_ERASER = 3;
	static final String[] TOOL_KEYS = {"AI.MaskToolPoints", "AI.MaskToolBox", "AI.MaskToolBrush", "AI.MaskToolEraser"};
	//Tool and brush radius (pixels of the work frame), kept for the next windows of the session
	static int tool = TOOL_POINTS;
	static int brushSize = 15;
	//Mouse over the preview (canvas coordinates, -1: outside), button down
	int mouseX = -1;
	int mouseY = -1;
	//Box being dragged: view, start and end (view coordinates), null: none
	double[] dragBox = null;
	int dragView = 0;
	//Mask being painted (view size, of the object paintObj in the view paintView on the frame paintFrame), shown
	//instead of its labels until the model has it; null: none
	volatile boolean[] paintBuf = null;
	volatile int paintObj = -1;
	volatile int paintView = 0;
	volatile int paintFrame = -1;
	boolean paintErase = false;
	double paintLastX = 0;
	double paintLastY = 0;
	int paintSeq = 0;

	ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
		Thread aT = new Thread(r, "AI mask");
		aT.setDaemon(true);
		return aT;
	});

	//Frame shown, frame asked to the loading thread (latest wins), its composite image
	volatile int frame = 0;
	volatile int wanted = 0;
	volatile int loadedFrame = -1;
	volatile boolean reload = true;
	Image shownI = null;
	int shownW = 0;
	int shownH = 0;
	//Place of the image in the canvas
	Rectangle drawR = new Rectangle(0, 0, 0, 0);
	long lastFollowMS = 0;
	long stepStartMS = -1;
	long stepStartDone = 0;

	public AiMaskGUI(AiClipGUI aAi,Source aSrc,MaskGenerator.Model aModel,String aName) {
		ai = aAi;
		tde = aAi.tde;
		src = aSrc;
		maskName = aName;
		nViews = src.isStereo() ? 2 : 1;
		gen = new MaskGenerator(tde.tdeDir, aModel, new PythonAI.Progress() {
			@Override
			public void status(String aKey, String aDetail, long aDone, long aTotal) {
				showStatus(aKey, aDetail, aDone, aTotal);
			}
			@Override
			public boolean isCanceled() {
				return canceled;
			}
		}, aFrame -> labelsWritten(aFrame));
	}

	String lng(String aKey) {
		return tde.gui.lngs.get(aKey);
	}

	Label addLabel(Composite aParent,String aText,int aWidth) {
		Label aL = new Label(aParent, SWT.WRAP);
		tde.gui.applyColorFont(aL);
		aL.setText(aText);
		GridData aGD = new GridData(GridData.FILL_HORIZONTAL);
		aGD.widthHint = aWidth;
		aL.setLayoutData(aGD);
		return aL;
	}

	Button addButton(Composite aParent,String aText) {
		Button aB = new Button(aParent, SWT.PUSH);
		tde.gui.applyColorFont(aB);
		aB.setText(aText);
		aB.setLayoutData(new GridData(GridData.FILL_HORIZONTAL));
		return aB;
	}

	/**
	 * Opens the window (non modal) and prepares the source in the background
	 */
	public void open() {
		shell = new Shell(tde.gui.shell, SWT.SHELL_TRIM);
		shell.setText(lng("AI.MaskEditorTitle").replace("%NAME%", src.shownName()));
		shell.setImage(tde.gui.imgsSwt.getIcon("3Denlive32.png"));
		tde.gui.applyColorFont(shell);
		GridLayout aGL = new GridLayout(2, false);
		shell.setLayout(aGL);

		//Left: the preview, the time
		Composite aLeft = new Composite(shell, SWT.NONE);
		tde.gui.applyColorFont(aLeft);
		aLeft.setLayoutData(new GridData(GridData.FILL_BOTH));
		GridLayout aLGL = new GridLayout(1, false);
		tde.gui.applyNoMargin(aLGL);
		aLeft.setLayout(aLGL);
		canvas = new Canvas(aLeft, SWT.BORDER | SWT.DOUBLE_BUFFERED | SWT.NO_BACKGROUND);
		canvas.setLayoutData(new GridData(GridData.FILL_BOTH));
		canvas.addListener(SWT.Paint, e -> paint(e.gc));
		canvas.addListener(SWT.Resize, e -> canvas.redraw());
		canvas.addListener(SWT.MouseDown, e -> mouseDown(e.x, e.y, e.button == 3 || (e.stateMask & SWT.SHIFT) != 0));
		canvas.addListener(SWT.MouseMove, e -> mouseMove(e.x, e.y));
		canvas.addListener(SWT.MouseUp, e -> mouseUp(e.x, e.y));
		canvas.addListener(SWT.MouseExit, e -> {
			mouseX = mouseY = -1;
			canvas.redraw();
		});
		//No context menu: the right click removes
		canvas.addListener(SWT.MenuDetect, e -> e.doit = false);

		Composite aRow = new Composite(aLeft, SWT.NONE);
		tde.gui.applyColorFont(aRow);
		aRow.setLayoutData(new GridData(GridData.FILL_HORIZONTAL));
		GridLayout aRGL = new GridLayout(4, false);
		tde.gui.applyNoMargin(aRGL);
		aRow.setLayout(aRGL);
		prevB = new Button(aRow, SWT.PUSH);
		tde.gui.applyColorFont(prevB);
		prevB.setText("<");
		prevB.setToolTipText(lng("AI.FramePrev"));
		slider = new Slider(aRow, SWT.HORIZONTAL);
		slider.setLayoutData(new GridData(GridData.FILL_HORIZONTAL));
		slider.setValues(0, 0, 1, 1, 1, 10);
		nextB = new Button(aRow, SWT.PUSH);
		tde.gui.applyColorFont(nextB);
		nextB.setText(">");
		nextB.setToolTipText(lng("AI.FrameNext"));
		timeL = new Label(aRow, SWT.NONE);
		tde.gui.applyColorFont(timeL);
		GridData aTGD = new GridData(SWT.END, SWT.CENTER, false, false);
		aTGD.widthHint = 190;
		timeL.setLayoutData(aTGD);
		slider.addListener(SWT.Selection, e -> showFrame(slider.getSelection()));
		prevB.addListener(SWT.Selection, e -> showFrame(Math.max(0, frame-1)));
		nextB.addListener(SWT.Selection, e -> showFrame(Math.min(count-1, frame+1)));
		//Hidden for a still image
		boolean aVideo = src.video;
		((GridData)aRow.getLayoutData()).exclude = !aVideo;
		aRow.setVisible(aVideo);

		//Right: help, objects, tracking, mask
		Composite aRight = new Composite(shell, SWT.NONE);
		tde.gui.applyColorFont(aRight);
		GridData aRGD = new GridData(GridData.FILL_VERTICAL);
		aRGD.widthHint = 300;
		aRight.setLayoutData(aRGD);
		GridLayout aRiGL = new GridLayout(1, false);
		tde.gui.applyNoMargin(aRiGL);
		aRight.setLayout(aRiGL);
		Label aModelL = addLabel(aRight, "SAM 2.1 "+gen.getModel().name, 290);
		aModelL.setFont(tde.gui.fontsSwt.robotoBold);
		addLabel(aRight, lng(src.isStereo() ? "AI.MaskHelpStereo" : "AI.MaskHelp"), 290);
		//Video: prompts at any time refine the tracking, which is then computed again
		if(src.video) {
			Label aVH = addLabel(aRight, lng("AI.MaskHelpVideo"), 290);
			((GridData)aVH.getLayoutData()).verticalIndent = 6;
			aVH.setForeground(tde.gui.colorsSwt.VIOLET);
		}

		//Tools: points, box, brush, eraser; size of the brush
		Label aToolL = addLabel(aRight, lng("AI.MaskTools"), 290);
		((GridData)aToolL.getLayoutData()).verticalIndent = 8;
		Composite aTools = new Composite(aRight, SWT.NONE);
		tde.gui.applyColorFont(aTools);
		aTools.setLayoutData(new GridData(GridData.FILL_HORIZONTAL));
		GridLayout aToGL = new GridLayout(2, true);
		tde.gui.applyNoMargin(aToGL);
		aTools.setLayout(aToGL);
		Label aSizeL = new Label(aRight, SWT.NONE);
		Scale aSizeS = new Scale(aRight, SWT.HORIZONTAL);
		Runnable aSizeShow = () -> {
			aSizeL.setText(lng("AI.MaskBrushSize").replace("%N%", ""+brushSize));
			boolean aBrush = tool == TOOL_BRUSH || tool == TOOL_ERASER;
			aSizeL.setEnabled(aBrush);
			aSizeS.setEnabled(aBrush);
		};
		for(int t = 0;t < TOOL_KEYS.length;t++) {
			final int aT = t;
			Button aB = new Button(aTools, SWT.RADIO);
			tde.gui.applyColorFont(aB);
			aB.setText(lng(TOOL_KEYS[t]));
			aB.setToolTipText(lng(TOOL_KEYS[t]+"Tip"));
			aB.setSelection(tool == t);
			aB.addListener(SWT.Selection, e -> {
				if(aB.getSelection()) {
					tool = aT;
					aSizeShow.run();
					canvas.redraw();
				}
			});
		}
		tde.gui.applyColorFont(aSizeL);
		aSizeL.setLayoutData(new GridData(GridData.FILL_HORIZONTAL));
		aSizeS.setLayoutData(new GridData(GridData.FILL_HORIZONTAL));
		aSizeS.setMinimum(1);
		aSizeS.setMaximum(100);
		aSizeS.setIncrement(1);
		aSizeS.setPageIncrement(10);
		aSizeS.setSelection(brushSize);
		aSizeS.addListener(SWT.Selection, e -> {
			brushSize = Math.max(1, aSizeS.getSelection());
			aSizeShow.run();
			canvas.redraw();
		});
		aSizeShow.run();

		Label aOL = addLabel(aRight, lng("AI.MaskObjects"), 290);
		((GridData)aOL.getLayoutData()).verticalIndent = 10;
		objT = new Table(aRight, SWT.BORDER | SWT.SINGLE | SWT.FULL_SELECTION | SWT.V_SCROLL);
		tde.gui.applyColorFont(objT);
		GridData aOGD = new GridData(GridData.FILL_HORIZONTAL);
		aOGD.heightHint = tde.gui.h(130);
		objT.setLayoutData(aOGD);
		objT.addListener(SWT.Selection, e -> {
			int aI = objT.getSelectionIndex();
			if(aI >= 0 && aI < objects.size()) {
				current = objects.get(aI);
				canvas.redraw();
			}
		});
		Composite aOB = new Composite(aRight, SWT.NONE);
		tde.gui.applyColorFont(aOB);
		aOB.setLayoutData(new GridData(GridData.FILL_HORIZONTAL));
		GridLayout aOBGL = new GridLayout(2, true);
		tde.gui.applyNoMargin(aOBGL);
		aOB.setLayout(aOBGL);
		newObjB = addButton(aOB, lng("AI.MaskNewObject"));
		delObjB = addButton(aOB, lng("AI.MaskDeleteObject"));
		undoB = addButton(aRight, lng("AI.MaskUndoPoint"));

		//Transparency of the image: at 100 % only the mask is seen
		Label aBgL = addLabel(aRight, bgText(), 290);
		((GridData)aBgL.getLayoutData()).verticalIndent = 10;
		aBgL.setToolTipText(lng("AI.MaskBgTransparencyTip"));
		Scale aBgS = new Scale(aRight, SWT.HORIZONTAL);
		aBgS.setLayoutData(new GridData(GridData.FILL_HORIZONTAL));
		aBgS.setMinimum(0);
		aBgS.setMaximum(100);
		aBgS.setIncrement(5);
		aBgS.setPageIncrement(25);
		aBgS.setSelection(bgTransparency);
		aBgS.setToolTipText(lng("AI.MaskBgTransparencyTip"));
		aBgS.addListener(SWT.Selection, e -> {
			bgTransparency = aBgS.getSelection();
			aBgL.setText(bgText());
			ask(frame, true);
		});
		newObjB.addListener(SWT.Selection, e -> newObject());
		delObjB.addListener(SWT.Selection, e -> deleteObject());
		undoB.addListener(SWT.Selection, e -> undo());

		if(aVideo) {
			Label aSep = new Label(aRight, SWT.SEPARATOR | SWT.HORIZONTAL);
			GridData aSGD = new GridData(GridData.FILL_HORIZONTAL);
			aSGD.verticalIndent = 6;
			aSep.setLayoutData(aSGD);
			Composite aTB = new Composite(aRight, SWT.NONE);
			tde.gui.applyColorFont(aTB);
			aTB.setLayoutData(new GridData(GridData.FILL_HORIZONTAL));
			GridLayout aTBGL = new GridLayout(2, true);
			tde.gui.applyNoMargin(aTBGL);
			aTB.setLayout(aTBGL);
			trackB = addButton(aTB, lng("AI.MaskTrack"));
			trackB.setToolTipText(lng("AI.MaskTrackTip"));
			//From the time shown (or the first frame changed before it) to the end, the frames before kept
			resumeB = addButton(aTB, lng("AI.MaskResume"));
			resumeB.setToolTipText(lng("AI.MaskResumeTip"));
			stopB = new Button(aTB, SWT.PUSH);
			tde.gui.applyColorFont(stopB);
			stopB.setText(lng("AI.MaskStop"));
			stopB.setImage(tde.gui.imgsSwt.getIcon("stop.gif"));
			GridData aStGD = new GridData(GridData.FILL_HORIZONTAL);
			aStGD.horizontalSpan = 2;
			stopB.setLayoutData(aStGD);
			trackB.addListener(SWT.Selection, e -> track(null, -1));
			resumeB.addListener(SWT.Selection, e -> track(null, Math.min(frame, firstChanged)));
			stopB.addListener(SWT.Selection, e -> stopTracking());
		}

		statusL = addLabel(aRight, " \n ", 290);
		((GridData)statusL.getLayoutData()).verticalIndent = 6;
		detailL = addLabel(aRight, " ", 290);
		bar = new ProgressBar(aRight, SWT.SMOOTH | SWT.HORIZONTAL);
		bar.setMinimum(0);
		bar.setMaximum(1000);
		bar.setLayoutData(new GridData(GridData.FILL_HORIZONTAL));

		//Fills the space: the mask part at the bottom
		Label aFill = new Label(aRight, SWT.NONE);
		aFill.setLayoutData(new GridData(GridData.FILL_BOTH));
		Label aSep2 = new Label(aRight, SWT.SEPARATOR | SWT.HORIZONTAL);
		aSep2.setLayoutData(new GridData(GridData.FILL_HORIZONTAL));
		addLabel(aRight, lng("AI.MaskName"), 290);
		nameT = new Text(aRight, SWT.SINGLE | SWT.BORDER);
		tde.gui.applyColorFont(nameT);
		nameT.setLayoutData(new GridData(GridData.FILL_HORIZONTAL));
		nameT.setMessage(defaultName());
		if(maskName != null) {
			nameT.setText(maskName);
		}
		addLabel(aRight, lng(src.isStereo() ? "AI.MaskOutStereo" : "AI.MaskOut"), 290);
		createB = addButton(aRight, lng("AI.MaskCreate"));
		createB.setImage(tde.gui.imgsSwt.getIcon("checked.gif"));
		createB.addListener(SWT.Selection, e -> createMask());
		Button aClose = addButton(aRight, lng("AI.ResultClose"));
		aClose.addListener(SWT.Selection, e -> shell.close());

		shell.addListener(SWT.Close, e -> e.doit = confirmClose());
		shell.addListener(SWT.Dispose, e -> disposed());

		//Most of the screen
		Rectangle aM = tde.gui.shell.getMonitor().getClientArea();
		int aW = aM.width*88/100;
		int aH = aM.height*88/100;
		shell.setBounds(aM.x+(aM.width-aW)/2, aM.y+(aM.height-aH)/2, aW, aH);
		enableAll();
		shell.open();
		startLoader();
		worker.submit(this::prepare);
	}

	String bgText() {
		return lng("AI.MaskBgTransparency").replace("%P%", ""+bgTransparency);
	}

	String defaultName() {
		return src.shownName().replaceAll("[.][A-Za-z0-9]{2,4}$", "")+" "+lng("AI.MaskSuffix");
	}

	/**
	 * Buttons enabled for the state
	 */
	void enableAll() {
		if(shell == null || shell.isDisposed()) {
			return;
		}
		boolean aFree = ready && !busy;
		boolean aSel = current > 0;
		newObjB.setEnabled(aFree && objects.size() < MaskGenerator.MAX_OBJECTS);
		delObjB.setEnabled(aFree && aSel);
		undoB.setEnabled(aFree && !history.isEmpty());
		if(trackB != null) {
			trackB.setEnabled(aFree && !prompts.isEmpty());
			//After a first tracking (else the script tracks the whole video anyway)
			resumeB.setEnabled(aFree && !prompts.isEmpty() && everTracked);
			stopB.setEnabled(tracking);
		}
		createB.setEnabled(aFree && !prompts.isEmpty());
		slider.setEnabled(ready);
		prevB.setEnabled(ready);
		nextB.setEnabled(ready);
	}

	void ui(Runnable aR) {
		if(closed) {
			return;
		}
		tde.gui.display.asyncExec(() -> {
			if(shell != null && !shell.isDisposed()) {
				aR.run();
			}
		});
	}

	void status(String aText,String aDetail,boolean aWorking,int aBar) {
		ui(() -> {
			statusL.setText(aText);
			detailL.setText(aDetail == null ? " " : aDetail);
			ai.working(statusL, aWorking);
			ai.working(detailL, aWorking);
			if(aBar >= 0) {
				bar.setSelection(aBar);
			}
		});
	}

	long lastStatusMS = 0;

	void showStatus(String aKey,String aDetail,long aDone,long aTotal) {
		long aNow = System.currentTimeMillis();
		if(aNow-lastStatusMS < 150 && aDone > 0 && aDone < aTotal) {
			return;
		}
		lastStatusMS = aNow;
		String aText = lng(aKey);
		String aDet = aDetail;
		if(aKey.startsWith("AI.Downloading") && aTotal > 0) {
			aDet = (aDetail == null ? "" : aDetail+": ")+DepthModel.gb(aDone)+" / "+DepthModel.gb(aTotal)+" GB";
		}
		else if(aTotal > 0) {
			aText += " "+aDone+" / "+aTotal;
			if(stepStartMS < 0 || aDone < stepStartDone) {
				stepStartMS = aNow;
				stepStartDone = aDone;
			}
			else if(aDone > stepStartDone) {
				long aLeftS = (aNow-stepStartMS)*(aTotal-aDone)/(aDone-stepStartDone)/1000;
				aDet = lng("AI.Remaining")+" "+(aLeftS >= 60 ? (aLeftS/60)+" min " : "")+(aLeftS%60)+" s";
			}
		}
		status(aText, aDet, true, aTotal > 0 ? (int)Math.min(1000, 1000*aDone/aTotal) : -1);
	}

	/**
	 * Install / download, frames of the work copy, model loaded with the session (worker thread)
	 */
	void prepare() {
		try {
			status(lng("AI.Starting"), null, true, 0);
			if(!gen.install()) {
				return;
			}
			workDir = new File(tde.getWorkCacheDir(), "AI_Mask_"+System.currentTimeMillis());
			framesDir = new File(workDir, "frames");
			labelsDir = new File(workDir, "labels");
			stopFile = new File(workDir, "stop");
			framesDir.mkdirs();
			labelsDir.mkdirs();
			if(src.video) {
				extractFrames();
			}
			else {
				writeImageFrame();
			}
			if(canceled || closed) {
				return;
			}
			if(count <= 0) {
				throw new Exception("No frame read from "+src.path);
			}
			stepStartMS = -1;
			status(lng("AI.LoadingMaskModel"), null, true, -1);
			int[][] aViews = new int[nViews][];
			for(int v = 0;v < nViews;v++) {
				aViews[v] = new int[] {v*viewW, 0, viewW, viewH};
			}
			gen.open(framesDir, count, aViews, labelsDir);
			if(canceled || closed) {
				return;
			}
			ready = true;
			ui(() -> {
				slider.setValues(0, 0, Math.max(1, count), 1, 1, Math.max(1, count/20));
				showFrame(0);
				enableAll();
			});
			status(lng("AI.MaskReady")+(gen.device != null && "cpu".equals(gen.device) ? "\n"+lng("AI.MaskOnCpu") : ""), null, false, 0);
		}
		catch(Throwable t) {
			failed(t);
		}
	}

	void failed(Throwable aT) {
		aT.printStackTrace(System.err);
		if(closed) {
			return;
		}
		String aMsg;
		if(aT instanceof PythonAI.GenerationException && "OOM".equals(((PythonAI.GenerationException)aT).code)) {
			aMsg = lng("AI.MaskErrorOom");
		}
		else {
			aMsg = lng("AI.MaskError")+"\n"+(aT.getMessage() == null ? aT.toString() : aT.getMessage());
		}
		status(aMsg, null, false, 0);
		ui(() -> {
			statusL.setForeground(tde.gui.colorsSwt.RED);
			shell.layout(true, true);
		});
	}

	static int even(double aV) {
		return Math.max(2, ((int)Math.round(aV/2))*2);
	}

	/**
	 * Work size of a view from its full size
	 */
	void setSizes(int aFullW,int aFullH) {
		fullW = aFullW;
		fullH = aFullH;
		double aS = Math.min(1.0, WORK_SIDE/(double)Math.max(aFullW, aFullH));
		viewW = even(aFullW*aS);
		viewH = even(aFullH*aS);
	}

	/**
	 * Still image: its views side by side at the work size, frame 0
	 */
	void writeImageFrame() throws Exception {
		BufferedImage aL = src.left;
		BufferedImage aR = src.right;
		if(aL == null) {
			BufferedImage aBI = ImageIO.read(new File(src.path));
			if(aBI == null) {
				throw new Exception("Unreadable image "+src.path);
			}
			if(src.layout == Source.SBS_P || src.layout == Source.SBS_X) {
				int aHW = aBI.getWidth()/2;
				BufferedImage aA = aBI.getSubimage(0, 0, aHW, aBI.getHeight());
				BufferedImage aB = aBI.getSubimage(aHW, 0, aHW, aBI.getHeight());
				aL = src.layout == Source.SBS_P ? aA : aB;
				aR = src.layout == Source.SBS_P ? aB : aA;
			}
			else if(src.layout == Source.TWO && src.pathR != null) {
				aL = aBI;
				aR = ImageIO.read(new File(src.pathR));
			}
			else {
				aL = aBI;
			}
		}
		if(nViews == 2 && aR == null) {
			aR = aL;
		}
		setSizes(aL.getWidth(), aL.getHeight());
		BufferedImage aOut = new BufferedImage(viewW*nViews, viewH, BufferedImage.TYPE_INT_RGB);
		java.awt.Graphics2D aG = aOut.createGraphics();
		aG.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION, java.awt.RenderingHints.VALUE_INTERPOLATION_BILINEAR);
		aG.setColor(java.awt.Color.BLACK);
		aG.fillRect(0, 0, aOut.getWidth(), aOut.getHeight());
		aG.drawImage(aL, 0, 0, viewW, viewH, null);
		if(nViews == 2) {
			aG.drawImage(aR, viewW, 0, viewW, viewH, null);
		}
		aG.dispose();
		ImageIO.write(aOut, "png", new File(framesDir, "000000.png"));
		count = 1;
	}

	/**
	 * Filters of the views of the video: {input, crop or null}
	 */
	String[][] viewInputs() {
		switch(src.layout) {
		case Source.SBS_P:
			return new String[][] {{"0", "crop=iw/2:ih:0:0"}, {"0", "crop=iw/2:ih:iw/2:0"}};
		case Source.SBS_X:
			return new String[][] {{"0", "crop=iw/2:ih:iw/2:0"}, {"0", "crop=iw/2:ih:0:0"}};
		case Source.TWO:
			return new String[][] {{"0", null}, {"1", null}};
		default:
			return new String[][] {{"0", null}};
		}
	}

	/**
	 * Video: full size of a view (first frame decoded with its crop, rotation of the file applied by ffmpeg as for
	 * the montage), frame rate, then all the frames at the work size, the views side by side (JPEG)
	 */
	void extractFrames() throws Exception {
		VideoFrameGUI aVF = new VideoFrameGUI(tde, src.path, null);
		aVF.probe();
		fps = aVF.fps;
		fpsText = String.format(Locale.ROOT, "%.3f", fps).replaceAll("0+$", "").replaceAll("[.]$", "");
		String[][] aIn = viewInputs();
		//Full size of a view
		Vector<String> aCmd = new Vector<String>();
		aCmd.add(Ffmpeg.ffmpegPath()); aCmd.add("-v"); aCmd.add("error");
		aCmd.add("-i"); aCmd.add(src.path);
		aCmd.add("-frames:v"); aCmd.add("1");
		if(aIn[0][1] != null) {
			aCmd.add("-vf"); aCmd.add(aIn[0][1]);
		}
		aCmd.add("-f"); aCmd.add("image2pipe"); aCmd.add("-vcodec"); aCmd.add("bmp"); aCmd.add("-");
		Process aP = new ProcessBuilder(aCmd).redirectError(ProcessBuilder.Redirect.DISCARD).start();
		BufferedImage aFirst;
		try(InputStream aIS = aP.getInputStream()) {
			aFirst = Ffmpeg.readBmp(aIS);
		}
		aP.waitFor();
		if(aFirst == null) {
			throw new Exception("Unreadable video "+src.path);
		}
		setSizes(aFirst.getWidth(), aFirst.getHeight());
		long aExpected = Math.max(1, Math.round(aVF.durationMS*fps/1000.0));

		//All the frames
		StringBuilder aF = new StringBuilder();
		if(aIn.length == 2 && aIn[0][0].equals(aIn[1][0])) {
			aF.append("[0:v]split=2[i0][i1];");
		}
		for(int v = 0;v < aIn.length;v++) {
			String aSrc = aIn.length == 2 && aIn[0][0].equals(aIn[1][0]) ? "[i"+v+"]" : "["+aIn[v][0]+":v]";
			aF.append(aSrc).append(aIn[v][1] != null ? aIn[v][1]+"," : "")
					.append("scale=").append(viewW).append(':').append(viewH).append(",setsar=1[v").append(v).append("];");
		}
		//Constant frame rate (fps filter: -vsync / -fps_mode depend on the version of ffmpeg): frame n of the mask =
		//frame n of the video (time n / fps)
		if(aIn.length == 2) {
			aF.append("[v0][v1]hstack=inputs=2,fps=").append(fpsText).append("[out]");
		}
		else {
			aF.setLength(aF.length()-"[v0];".length());
			aF.append(",fps=").append(fpsText).append("[out]");
		}
		aCmd.clear();
		aCmd.add(Ffmpeg.ffmpegPath()); aCmd.add("-v"); aCmd.add("error"); aCmd.add("-nostats");
		aCmd.add("-progress"); aCmd.add("pipe:1");
		aCmd.add("-i"); aCmd.add(src.path);
		if(src.layout == Source.TWO) {
			aCmd.add("-i"); aCmd.add(src.pathR);
		}
		aCmd.add("-filter_complex"); aCmd.add(aF.toString());
		aCmd.add("-map"); aCmd.add("[out]");
		aCmd.add("-q:v"); aCmd.add("3");
		aCmd.add("-start_number"); aCmd.add("0");
		aCmd.add(new File(framesDir, "%06d.jpg").getAbsolutePath());
		System.out.println("AI mask frames: "+String.join(" ", aCmd));
		Process aE = new ProcessBuilder(aCmd).redirectError(ProcessBuilder.Redirect.INHERIT).start();
		try(BufferedReader aR = new BufferedReader(new InputStreamReader(aE.getInputStream()))) {
			String aL;
			while((aL = aR.readLine()) != null) {
				if(canceled || closed) {
					aE.destroyForcibly();
					return;
				}
				if(aL.startsWith("frame=")) {
					try {
						long aN = Long.parseLong(aL.substring(6).trim());
						showStatus("AI.MaskPreparing", null, Math.min(aN, aExpected), aExpected);
					}
					catch(NumberFormatException e) {
					}
				}
			}
		}
		if(aE.waitFor() != 0) {
			throw new Exception("ffmpeg failed to read "+src.path+" (see log/3De.log)");
		}
		String[] aNames = framesDir.list((d, n) -> n.endsWith(".jpg"));
		count = aNames == null ? 0 : aNames.length;
	}

	//////////////////////////////////////////////////////////////////////////////////////////////
	// Preview

	/**
	 * Loading thread: the frame asked (latest wins) and its labels, composed
	 */
	void startLoader() {
		Thread aT = new Thread(() -> {
			while(!closed) {
				int aF;
				synchronized(this) {
					while(!closed && !(ready && (wanted != loadedFrame || reload))) {
						try {
							wait(500);
						}
						catch(InterruptedException e) {
							return;
						}
					}
					aF = wanted;
					reload = false;
				}
				if(closed) {
					return;
				}
				try {
					BufferedImage aBI = compose(aF);
					loadedFrame = aF;
					if(aBI != null) {
						tde.gui.display.asyncExec(() -> setShown(aBI));
					}
				}
				catch(Throwable t) {
					t.printStackTrace(System.err);
				}
			}
		}, "AI mask preview");
		aT.setDaemon(true);
		aT.start();
	}

	synchronized void ask(int aF,boolean aReload) {
		wanted = aF;
		if(aReload) {
			reload = true;
		}
		notifyAll();
	}

	File frameFile(int aF) {
		File aJ = new File(framesDir, String.format("%06d.jpg", aF));
		return aJ.isFile() ? aJ : new File(framesDir, String.format("%06d.png", aF));
	}

	/**
	 * The frame with the objects over it: inside semi-transparent, outline opaque, in the color of each object
	 * (the first of its bits where objects overlap). The image is faded to black by bgTransparency and the inside
	 * of the objects gets opaque with it: at 100 %, the objects alone in their colors on black (the mask)
	 */
	BufferedImage compose(int aF) throws Exception {
		BufferedImage aSrc = ImageIO.read(frameFile(aF));
		if(aSrc == null) {
			return null;
		}
		int aW = aSrc.getWidth();
		int aH = aSrc.getHeight();
		BufferedImage aOut = new BufferedImage(aW, aH, BufferedImage.TYPE_INT_RGB);
		java.awt.Graphics2D aG = aOut.createGraphics();
		aG.drawImage(aSrc, 0, 0, null);
		aG.dispose();
		double aT = bgTransparency/100.0;
		double aFill = FILL_ALPHA+(1-FILL_ALPHA)*aT;
		if(aT > 0) {
			int[] aPx = ((DataBufferInt)aOut.getRaster().getDataBuffer()).getData();
			double aK = 1-aT;
			for(int i = 0;i < aPx.length;i++) {
				int aP = aPx[i];
				aPx[i] = ((int)(((aP>>16)&0xFF)*aK)<<16)|((int)(((aP>>8)&0xFF)*aK)<<8)|(int)((aP&0xFF)*aK);
			}
		}
		int[] aL = labels(aF, aW, aH);
		//The mask being painted, instead of the labels of its object
		boolean[] aPaint = paintBuf;
		if(aPaint != null && paintFrame == aF && aPaint.length == viewW*viewH) {
			if(aL == null) {
				aL = new int[aW*aH];
			}
			int aBit = 1 << (paintObj-1);
			int aX0 = paintView*viewW;
			for(int y = 0;y < viewH && y < aH;y++) {
				for(int x = 0;x < viewW && aX0+x < aW;x++) {
					int i = y*aW+aX0+x;
					aL[i] = aPaint[y*viewW+x] ? aL[i] | aBit : aL[i] & ~aBit;
				}
			}
		}
		if(aL == null) {
			return aOut;
		}
		int aKeep = 0;
		synchronized(objects) {
			for(int aO : objects) {
				aKeep |= 1 << (aO-1);
			}
		}
		int[] aPx = ((DataBufferInt)aOut.getRaster().getDataBuffer()).getData();
		for(int y = 0;y < aH;y++) {
			for(int x = 0;x < aW;x++) {
				int i = y*aW+x;
				int aV = aL[i] & aKeep;
				if(aV == 0) {
					continue;
				}
				int aCol = COLORS[Integer.numberOfTrailingZeros(aV) % COLORS.length];
				boolean aEdge = x == 0 || y == 0 || x == aW-1 || y == aH-1
						|| (aL[i-1] & aKeep) != aV || (aL[i+1] & aKeep) != aV
						|| (aL[i-aW] & aKeep) != aV || (aL[i+aW] & aKeep) != aV;
				if(aEdge) {
					aPx[i] = aCol;
				}
				else {
					int aP = aPx[i];
					int aR = (int)(((aP>>16)&0xFF)*(1-aFill)+((aCol>>16)&0xFF)*aFill);
					int aGr = (int)(((aP>>8)&0xFF)*(1-aFill)+((aCol>>8)&0xFF)*aFill);
					int aB = (int)((aP&0xFF)*(1-aFill)+(aCol&0xFF)*aFill);
					aPx[i] = (aR<<16)|(aGr<<8)|aB;
				}
			}
		}
		return aOut;
	}

	/**
	 * Labels of a frame (bit k-1: object k), null if none yet or being replaced
	 */
	int[] labels(int aF,int aW,int aH) {
		File aLF = new File(labelsDir, String.format("%06d.png", aF));
		if(!aLF.isFile()) {
			return null;
		}
		try {
			BufferedImage aLab = ImageIO.read(aLF);
			if(aLab == null || aLab.getWidth() != aW || aLab.getHeight() != aH) {
				return null;
			}
			return aLab.getRaster().getPixels(0, 0, aW, aH, (int[])null);
		}
		catch(Throwable t) {
			//Being replaced: shown at the next change
			return null;
		}
	}

	void setShown(BufferedImage aBI) {
		if(shell == null || shell.isDisposed()) {
			return;
		}
		if(shownI != null) {
			shownI.dispose();
		}
		shownI = com.cubaix.TDenlive.utils.ImageUtils.imageToSwt(tde.gui.display, aBI);
		shownW = aBI.getWidth();
		shownH = aBI.getHeight();
		canvas.redraw();
	}

	void showFrame(int aF) {
		if(count <= 0) {
			return;
		}
		frame = Math.max(0, Math.min(count-1, aF));
		if(slider.getSelection() != frame) {
			slider.setSelection(frame);
		}
		long aMS = Math.round(frame*1000/fps);
		long aEnd = Math.round(count*1000/fps);
		timeL.setText(VideoFrameGUI.clock(aMS)+" / "+VideoFrameGUI.clock(aEnd)+"  ("+(frame+1)+"/"+count+")");
		ask(frame, false);
		canvas.redraw();
	}

	/**
	 * Labels of a frame written by the script: shown if it is that frame; while tracking, the preview follows
	 * the frame tracked (at most a few times a second)
	 */
	void labelsWritten(int aF) {
		if(tracking) {
			long aNow = System.currentTimeMillis();
			if(aNow-lastFollowMS < 150) {
				return;
			}
			lastFollowMS = aNow;
			ui(() -> showFrame(aF));
			ask(aF, true);
			return;
		}
		if(aF == frame) {
			ask(aF, true);
		}
	}

	void paint(GC aGC) {
		Rectangle aC = canvas.getClientArea();
		aGC.setBackground(tde.gui.display.getSystemColor(SWT.COLOR_BLACK));
		aGC.fillRectangle(aC);
		if(shownI == null || shownW <= 0) {
			return;
		}
		double aS = Math.min(aC.width/(double)shownW, aC.height/(double)shownH);
		int aDW = Math.max(1, (int)(shownW*aS));
		int aDH = Math.max(1, (int)(shownH*aS));
		drawR = new Rectangle(aC.x+(aC.width-aDW)/2, aC.y+(aC.height-aDH)/2, aDW, aDH);
		aGC.setInterpolation(SWT.HIGH);
		aGC.drawImage(shownI, 0, 0, shownW, shownH, drawR.x, drawR.y, drawR.width, drawR.height);
		//Separation of the views
		if(nViews == 2) {
			aGC.setForeground(tde.gui.display.getSystemColor(SWT.COLOR_GRAY));
			int aX = drawR.x+drawR.width/2;
			aGC.drawLine(aX, drawR.y, aX, drawR.y+drawR.height);
		}
		//Prompts of this frame: points green in, red out, boxes in the color of their object; those of the selected
		//object larger
		aGC.setAntialias(SWT.ON);
		synchronized(prompts) {
			for(Map.Entry<String,Prompt> aE : prompts.entrySet()) {
				String[] aK = aE.getKey().split("/");
				if(Integer.parseInt(aK[2]) != frame) {
					continue;
				}
				int aObj = Integer.parseInt(aK[0]);
				int aView = Integer.parseInt(aK[1]);
				int aR = aObj == current ? 7 : 5;
				double[] aBx = aE.getValue().box;
				if(aBx != null) {
					drawBox(aGC, aView, aBx, color(aObj), aObj == current ? 2 : 1);
				}
				for(double[] aP : aE.getValue().pts) {
					int aX = drawR.x+(int)Math.round((aP[0]+aView*viewW)*drawR.width/(double)shownW);
					int aY = drawR.y+(int)Math.round(aP[1]*drawR.height/(double)shownH);
					aGC.setBackground(tde.gui.display.getSystemColor(aP[2] > 0 ? SWT.COLOR_GREEN : SWT.COLOR_RED));
					aGC.fillOval(aX-aR, aY-aR, 2*aR, 2*aR);
					aGC.setForeground(tde.gui.display.getSystemColor(SWT.COLOR_WHITE));
					aGC.drawOval(aX-aR, aY-aR, 2*aR, 2*aR);
					aGC.drawLine(aX-aR+3, aY, aX+aR-3, aY);
					if(aP[2] > 0) {
						aGC.drawLine(aX, aY-aR+3, aX, aY+aR-3);
					}
				}
			}
		}
		//Box being dragged
		if(dragBox != null) {
			drawBox(aGC, dragView, new double[] {Math.min(dragBox[0], dragBox[2]), Math.min(dragBox[1], dragBox[3])
					, Math.max(dragBox[0], dragBox[2]), Math.max(dragBox[1], dragBox[3])}, tde.gui.display.getSystemColor(SWT.COLOR_WHITE), 2);
		}
		//Brush: its circle under the mouse
		if((tool == TOOL_BRUSH || tool == TOOL_ERASER) && mouseX >= 0 && drawR.contains(mouseX, mouseY)) {
			int aR = Math.max(1, (int)Math.round(brushSize*drawR.width/(double)shownW));
			aGC.setLineWidth(1);
			aGC.setForeground(tde.gui.display.getSystemColor(SWT.COLOR_BLACK));
			aGC.drawOval(mouseX-aR-1, mouseY-aR-1, 2*aR+2, 2*aR+2);
			aGC.setForeground(tde.gui.display.getSystemColor(tool == TOOL_ERASER || paintErase && paintBuf != null ? SWT.COLOR_RED : SWT.COLOR_WHITE));
			aGC.drawOval(mouseX-aR, mouseY-aR, 2*aR, 2*aR);
		}
	}

	org.eclipse.swt.graphics.Color color(int aObj) {
		int aC = COLORS[(aObj-1) % COLORS.length];
		return new org.eclipse.swt.graphics.Color(tde.gui.display, (aC>>16)&0xFF, (aC>>8)&0xFF, aC&0xFF);
	}

	void drawBox(GC aGC,int aView,double[] aB,org.eclipse.swt.graphics.Color aCol,int aWidth) {
		int aX0 = drawR.x+(int)Math.round((aB[0]+aView*viewW)*drawR.width/(double)shownW);
		int aY0 = drawR.y+(int)Math.round(aB[1]*drawR.height/(double)shownH);
		int aX1 = drawR.x+(int)Math.round((aB[2]+aView*viewW)*drawR.width/(double)shownW);
		int aY1 = drawR.y+(int)Math.round(aB[3]*drawR.height/(double)shownH);
		aGC.setLineWidth(aWidth);
		aGC.setLineStyle(SWT.LINE_DASH);
		aGC.setForeground(aCol);
		aGC.drawRectangle(aX0, aY0, aX1-aX0, aY1-aY0);
		aGC.setLineStyle(SWT.LINE_SOLID);
		aGC.setLineWidth(1);
	}

	//////////////////////////////////////////////////////////////////////////////////////////////
	// Objects and points

	Image swatch(int aObj) {
		Image aI = new Image(tde.gui.display, 14, 14);
		GC aGC = new GC(aI);
		int aC = COLORS[(aObj-1) % COLORS.length];
		org.eclipse.swt.graphics.Color aCol = new org.eclipse.swt.graphics.Color(tde.gui.display, (aC>>16)&0xFF, (aC>>8)&0xFF, aC&0xFF);
		aGC.setBackground(aCol);
		aGC.fillRectangle(0, 0, 14, 14);
		aGC.dispose();
		aCol.dispose();
		swatches.add(aI);
		return aI;
	}

	void refreshObjects() {
		objT.removeAll();
		int aSel = -1;
		synchronized(objects) {
			for(int i = 0;i < objects.size();i++) {
				int aO = objects.get(i);
				TableItem aIt = new TableItem(objT, SWT.NONE);
				aIt.setText(lng("AI.MaskObject").replace("%N%", ""+aO));
				aIt.setImage(swatch(aO));
				if(aO == current) {
					aSel = i;
				}
			}
		}
		if(aSel >= 0) {
			objT.select(aSel);
		}
		enableAll();
	}

	/**
	 * A new object (the lowest free id), selected
	 */
	int newObject() {
		synchronized(objects) {
			if(objects.size() >= MaskGenerator.MAX_OBJECTS) {
				return -1;
			}
			int aId = 1;
			while(objects.contains(aId)) {
				aId++;
			}
			objects.add(aId);
			current = aId;
		}
		refreshObjects();
		return current;
	}

	/**
	 * A prompt changed on the frame aFrame: tracked again from there at the latest
	 */
	void changed(int aFrame) {
		tracked = false;
		created = false;
		firstChanged = Math.min(firstChanged, aFrame);
	}

	/**
	 * Work frame coordinates of a canvas point: {view, x, y in the view}, null outside the image
	 */
	double[] toView(int aCX,int aCY,boolean aClamp) {
		if(shownW <= 0 || drawR.width <= 0) {
			return null;
		}
		if(!aClamp && !drawR.contains(aCX, aCY)) {
			return null;
		}
		double aX = (aCX-drawR.x)*shownW/(double)drawR.width;
		double aY = (aCY-drawR.y)*shownH/(double)drawR.height;
		int aView = nViews == 2 && aX >= viewW ? 1 : 0;
		return new double[] {aView, aX-aView*viewW, aY};
	}

	/**
	 * The prompt of aKey (a copy, a new one if none) with its state before the action kept for the undo
	 */
	Prompt beginAction(String aKey) {
		Prompt aOld;
		synchronized(prompts) {
			aOld = prompts.get(aKey);
		}
		history.add(new Object[] {aKey, aOld == null ? null : aOld.copy()});
		return aOld == null ? new Prompt() : aOld.copy();
	}

	void endAction(String aKey,Prompt aNew) {
		synchronized(prompts) {
			if(aNew == null || aNew.isEmpty()) {
				prompts.remove(aKey);
			}
			else {
				prompts.put(aKey, aNew);
			}
		}
		changed(Integer.parseInt(aKey.split("/")[2]));
		canvas.redraw();
		send(aKey);
	}

	void mouseDown(int aCX,int aCY,boolean aOut) {
		if(!ready || busy) {
			return;
		}
		double[] aV = toView(aCX, aCY, false);
		if(aV == null) {
			return;
		}
		int aView = (int)aV[0];
		boolean aErase = tool == TOOL_ERASER || ((tool == TOOL_BRUSH) && aOut);
		if(current < 0) {
			//Nothing to remove from
			if((tool == TOOL_POINTS && aOut) || aErase) {
				return;
			}
			newObject();
		}
		if(tool == TOOL_BOX) {
			dragView = aView;
			dragBox = new double[] {aV[1], aV[2], aV[1], aV[2]};
			return;
		}
		if(tool == TOOL_BRUSH || tool == TOOL_ERASER) {
			startPaint(aView, aV[1], aV[2], aErase);
			return;
		}
		//A point; a painted mask on this frame is replaced by the points
		String aKey = current+"/"+aView+"/"+frame;
		Prompt aP = beginAction(aKey);
		if(aP.mask != null) {
			aP = new Prompt();
		}
		aP.pts.add(new double[] {aV[1], aV[2], aOut ? 0 : 1});
		endAction(aKey, aP);
	}

	void mouseMove(int aCX,int aCY) {
		mouseX = aCX;
		mouseY = aCY;
		if(dragBox != null) {
			double[] aV = toView(aCX, aCY, true);
			if(aV != null) {
				//Kept in the view where it started
				double aX = aV[1]+(aV[0]-dragView)*viewW;
				dragBox[2] = Math.max(0, Math.min(viewW, aX));
				dragBox[3] = Math.max(0, Math.min(viewH, aV[2]));
			}
		}
		else if(paintBuf != null) {
			double[] aV = toView(aCX, aCY, true);
			if(aV != null) {
				double aX = aV[1]+(aV[0]-paintView)*viewW;
				stroke(paintLastX, paintLastY, aX, aV[2]);
				paintLastX = aX;
				paintLastY = aV[2];
				ask(frame, true);
			}
		}
		canvas.redraw();
	}

	void mouseUp(int aCX,int aCY) {
		if(dragBox != null) {
			double[] aB = {Math.min(dragBox[0], dragBox[2]), Math.min(dragBox[1], dragBox[3])
					, Math.max(dragBox[0], dragBox[2]), Math.max(dragBox[1], dragBox[3])};
			dragBox = null;
			canvas.redraw();
			//A click without a drag: no box
			if(aB[2]-aB[0] < 3 || aB[3]-aB[1] < 3) {
				return;
			}
			String aKey = current+"/"+dragView+"/"+frame;
			Prompt aP = beginAction(aKey);
			if(aP.mask != null) {
				aP = new Prompt();
			}
			aP.box = aB;
			endAction(aKey, aP);
			return;
		}
		if(paintBuf != null) {
			endPaint();
		}
	}

	/**
	 * Painting of the mask of the selected object in a view, from the mask shown on this frame
	 */
	void startPaint(int aView,double aX,double aY,boolean aErase) {
		boolean[] aBuf = new boolean[viewW*viewH];
		int[] aL = labels(frame, viewW*nViews, viewH);
		if(aL != null) {
			int aBit = 1 << (current-1);
			for(int y = 0;y < viewH;y++) {
				for(int x = 0;x < viewW;x++) {
					aBuf[y*viewW+x] = (aL[y*viewW*nViews+aView*viewW+x] & aBit) != 0;
				}
			}
		}
		paintObj = current;
		paintView = aView;
		paintFrame = frame;
		paintErase = aErase;
		paintLastX = aX;
		paintLastY = aY;
		paintBuf = aBuf;
		stroke(aX, aY, aX, aY);
		ask(frame, true);
	}

	/**
	 * Disks of the brush along a segment (view coordinates)
	 */
	void stroke(double aX0,double aY0,double aX1,double aY1) {
		boolean[] aBuf = paintBuf;
		if(aBuf == null) {
			return;
		}
		int aR = Math.max(1, brushSize);
		double aLen = Math.hypot(aX1-aX0, aY1-aY0);
		int aSteps = Math.max(1, (int)Math.ceil(aLen/Math.max(1, aR/2.0)));
		for(int s = 0;s <= aSteps;s++) {
			double aCx = aX0+(aX1-aX0)*s/aSteps;
			double aCy = aY0+(aY1-aY0)*s/aSteps;
			int aXa = Math.max(0, (int)Math.floor(aCx-aR));
			int aXb = Math.min(viewW-1, (int)Math.ceil(aCx+aR));
			int aYa = Math.max(0, (int)Math.floor(aCy-aR));
			int aYb = Math.min(viewH-1, (int)Math.ceil(aCy+aR));
			for(int y = aYa;y <= aYb;y++) {
				for(int x = aXa;x <= aXb;x++) {
					double aDx = x+0.5-aCx;
					double aDy = y+0.5-aCy;
					if(aDx*aDx+aDy*aDy <= aR*aR) {
						aBuf[y*viewW+x] = !paintErase;
					}
				}
			}
		}
	}

	/**
	 * End of a stroke: the painted mask written to a PNG and given to the model as the prompt of its frame (the
	 * paint shown until the labels have it)
	 */
	void endPaint() {
		boolean[] aBuf = paintBuf;
		String aKey = paintObj+"/"+paintView+"/"+paintFrame;
		try {
			File aDir = new File(workDir, "prompts");
			aDir.mkdirs();
			File aPng = new File(aDir, "o"+paintObj+"_v"+paintView+"_f"+paintFrame+"_"+(++paintSeq)+".png");
			BufferedImage aBI = new BufferedImage(viewW, viewH, BufferedImage.TYPE_BYTE_GRAY);
			byte[] aD = ((java.awt.image.DataBufferByte)aBI.getRaster().getDataBuffer()).getData();
			for(int i = 0;i < aBuf.length && i < aD.length;i++) {
				aD[i] = aBuf[i] ? (byte)255 : 0;
			}
			ImageIO.write(aBI, "png", aPng);
			Prompt aP = beginAction(aKey);
			aP = new Prompt();
			aP.mask = aPng;
			endAction(aKey, aP);
		}
		catch(Throwable t) {
			paintBuf = null;
			failed(t);
		}
	}

	/**
	 * The prompt of aKey given to the model (worker), the labels of its frame shown again
	 */
	void send(String aKey) {
		String[] aK = aKey.split("/");
		Prompt aCopy;
		synchronized(prompts) {
			Prompt aP = prompts.get(aKey);
			aCopy = aP == null ? new Prompt() : aP.copy();
		}
		int aObj = Integer.parseInt(aK[0]);
		int aView = Integer.parseInt(aK[1]);
		int aFrame = Integer.parseInt(aK[2]);
		run(lng("AI.MaskComputing"), () -> {
			try {
				if(aCopy.mask != null) {
					gen.mask(aObj, aView, aFrame, aCopy.mask);
				}
				else {
					gen.points(aObj, aView, aFrame, aCopy.pts, aCopy.box);
				}
			}
			finally {
				//The labels have the painted mask now
				if(paintFrame == aFrame && paintObj == aObj && paintView == aView) {
					paintBuf = null;
				}
				ask(frame, true);
			}
			status(lng(!src.video ? "AI.MaskPointDoneImage" : everTracked ? "AI.MaskRetrack" : "AI.MaskPointDone"), null, false, 0);
		});
	}

	/**
	 * The last action undone: its prompt as it was before
	 */
	void undo() {
		if(history.isEmpty()) {
			return;
		}
		Object[] aH = history.remove(history.size()-1);
		String aKey = (String)aH[0];
		Prompt aPrev = (Prompt)aH[1];
		synchronized(prompts) {
			if(aPrev == null || aPrev.isEmpty()) {
				prompts.remove(aKey);
			}
			else {
				prompts.put(aKey, aPrev);
			}
		}
		//Shows the frame of that action
		int aF = Integer.parseInt(aKey.split("/")[2]);
		if(aF != frame) {
			showFrame(aF);
		}
		changed(aF);
		canvas.redraw();
		send(aKey);
	}

	void deleteObject() {
		int aObj = current;
		if(aObj < 0) {
			return;
		}
		synchronized(objects) {
			objects.remove((Integer)aObj);
			current = objects.isEmpty() ? -1 : objects.lastElement();
		}
		synchronized(prompts) {
			prompts.keySet().removeIf(k -> k.startsWith(aObj+"/"));
		}
		history.removeIf(h -> ((String)h[0]).startsWith(aObj+"/"));
		//The script tracks the whole video after a removal (its sessions are rebuilt)
		changed(0);
		refreshObjects();
		canvas.redraw();
		ask(frame, true);
		run(lng("AI.MaskComputing"), () -> {
			gen.delete(aObj);
			ask(frame, true);
			status(lng("AI.MaskObjectDeleted"), null, false, 0);
		});
	}

	interface Task {
		void run() throws Exception;
	}

	/**
	 * A request in the worker thread (in order); the buttons disabled meanwhile
	 */
	void run(String aStatus,Task aTask) {
		busy = true;
		enableAll();
		status(aStatus, null, true, -1);
		worker.submit(() -> {
			try {
				aTask.run();
			}
			catch(Throwable t) {
				failed(t);
			}
			finally {
				busy = false;
				ui(this::enableAll);
			}
		});
	}

	//////////////////////////////////////////////////////////////////////////////////////////////
	// Tracking, mask

	/**
	 * Tracking (worker) over the whole video (aResume < 0), or resumed from the frame aResume to the end (the frames
	 * before kept; the script tracks the whole video when it can't resume), then aThen if not stopped
	 */
	void track(Task aThen,int aResume) {
		if(prompts.isEmpty()) {
			return;
		}
		tracking = true;
		everTracked = true;
		stepStartMS = -1;
		int aFrom = aResume < 0 ? -1 : Math.max(0, Math.min(aResume, count));
		//Taken into account from now: a later change starts the next resume
		firstChanged = Integer.MAX_VALUE;
		String aStart = aFrom >= 0 && aFrom < frame ? lng("AI.MaskResumeFrom").replace("%N%", ""+(aFrom+1)) : lng("AI.MaskTracking");
		run(aStart, () -> {
			boolean aDone;
			try {
				aDone = gen.track(stopFile, aFrom);
			}
			finally {
				tracking = false;
				ui(this::enableAll);
			}
			if(!aDone) {
				status(lng("AI.MaskStopped"), null, false, 0);
				return;
			}
			ui(() -> tracked = true);
			ask(frame, true);
			//A resume asked but the whole video was tracked (never tracked, object added or removed): said
			String aFull = aFrom >= 0 && gen.trackFull ? lng("AI.MaskResumeFull")+"\n" : "";
			if(aThen != null) {
				aThen.run();
			}
			else {
				status(aFull+lng("AI.MaskTracked"), null, false, 1000);
			}
		});
		enableAll();
	}

	void stopTracking() {
		try {
			if(stopFile != null) {
				stopFile.createNewFile();
			}
		}
		catch(Throwable t) {
			t.printStackTrace(System.err);
		}
	}

	/**
	 * The mask into the AI folder (tracked first if the objects changed), added to the media list
	 */
	void createMask() {
		if(prompts.isEmpty()) {
			return;
		}
		String aName = nameT.getText().trim();
		if(aName.isEmpty()) {
			aName = defaultName();
		}
		File aDir = AiClipGUI.outDir(tde);
		String aExt = src.video ? ".mp4" : ".png";
		String aBase = AiClipGUI.fileBase(aName);
		if(aBase.isEmpty()) {
			aBase = "AI_Mask";
		}
		aBase = AiClipGUI.freeBase(aDir, aBase, aExt);
		File aOut = new File(aDir, aBase+aExt);
		Task aExport = () -> {
			stepStartMS = -1;
			aDir.mkdirs();
			gen.export(aOut, fullW, fullH, Ffmpeg.ffmpegPath(), fpsText);
			status(lng("AI.MaskLoading"), null, true, -1);
			if(nViews == 2) {
				tde.mediaList.loadImageP(aOut.getAbsolutePath(), TDConfig.PROCESSING_MODE_RENDER, 0);
			}
			else {
				tde.mediaList.loadImageLR(aOut.getAbsolutePath(), aOut.getAbsolutePath(), TDConfig.PROCESSING_MODE_RENDER, 0);
			}
			ui(() -> {
				created = true;
				tde.gui.mediaListGUI.redraw();
			});
			status(lng("AI.MaskAdded").replace("%NAME%", aOut.getName()), null, false, 1000);
		};
		//Only what changed since the last tracking is tracked again (resume from the first frame changed)
		if(src.video && !tracked) {
			track(aExport, everTracked ? Math.min(firstChanged, count) : -1);
		}
		else {
			run(lng("AI.MaskWriting"), aExport);
		}
	}

	boolean confirmClose() {
		if(created || objects.isEmpty() || !ready) {
			return true;
		}
		MessageBox aMB = new MessageBox(shell, SWT.ICON_QUESTION | SWT.YES | SWT.NO);
		aMB.setText(lng("AI.MaskTitle"));
		aMB.setMessage(lng("AI.MaskCloseConfirm"));
		return aMB.open() == SWT.YES;
	}

	/**
	 * Closed: a tracking is stopped, an installation or a loading canceled; the session is forgotten (the model
	 * stays loaded) and the work folder deleted in the worker thread, after the request in progress
	 */
	void disposed() {
		closed = true;
		canceled = !ready;
		stopTracking();
		synchronized(this) {
			notifyAll();
		}
		if(shownI != null) {
			shownI.dispose();
			shownI = null;
		}
		for(Image aI : swatches) {
			aI.dispose();
		}
		swatches.clear();
		worker.submit(() -> {
			if(ready) {
				gen.close();
			}
			if(workDir != null) {
				deleteTree(workDir);
			}
		});
		worker.shutdown();
		AiClipGUI.running = false;
	}

	static void deleteTree(File aF) {
		File[] aFs = aF.listFiles();
		if(aFs != null) {
			for(File aC : aFs) {
				deleteTree(aC);
			}
		}
		aF.delete();
	}
}
