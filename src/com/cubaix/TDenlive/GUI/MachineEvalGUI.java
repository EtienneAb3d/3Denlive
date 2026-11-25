package com.cubaix.TDenlive.GUI;

import java.util.List;
import java.util.Vector;

import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.Color;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Shell;

import com.cubaix.TDenlive.TDenlive;
import com.cubaix.TDenlive.ai.ColorGenerator;
import com.cubaix.TDenlive.ai.FillGenerator;
import com.cubaix.TDenlive.ai.MaskGenerator;
import com.cubaix.TDenlive.ai.PromptExtender;
import com.cubaix.TDenlive.ai.PythonAI;
import com.cubaix.TDenlive.ai.RemixGenerator;
import com.cubaix.TDenlive.ai.SceneGenerator;
import com.cubaix.TDenlive.ai.SoundGenerator;
import com.cubaix.TDenlive.ai.SpeechGenerator;
import com.cubaix.TDenlive.ai.WanGenerator;
import com.cubaix.TDenlive.depth.DepthEstimator;
import com.cubaix.TDenlive.depth.DepthModel;
import com.cubaix.TDenlive.model3d.SmartFill;

/**
 * Evaluation of the machine (button right of the settings): what the AI can do here. One line per function using an
 * AI model, one column per model of that function; each cell: GPU (green) when the machine can run the model on its
 * GPU, CPU (red) when it would run on the CPU (slow), No (red) when the model needs a GPU, - (gray) when it is not
 * available on this system. Evaluated on the total memory of the best GPU (the capacity of the machine, as the
 * generators compare with the free memory at the computation: other applications may then make it run on the CPU).
 */
public class MachineEvalGUI {
	static final long GB = 1024L*1024*1024;
	static final int GPU = 0, CPU = 1, NO = 2, NA = 3;

	TDenlive tde;
	Shell shell;
	Composite body;
	Color green, red, gray;

	//The machine
	boolean mac, python, torchGpu, onnxSupported, onnxGpu;
	//GPU memory usable by a model: the most of the NVIDIA GPUs, 3/4 of the memory on Apple Silicon; -1: none
	long capacity = -1;
	Vector<String> summary = new Vector<String>();

	public MachineEvalGUI(TDenlive aTDe) {
		tde = aTDe;
		shell = new Shell(tde.gui.shell, SWT.DIALOG_TRIM | SWT.RESIZE);
		shell.setText(l("Title"));
		shell.setImage(tde.gui.imgsSwt.getIcon("3Denlive32.png"));
		GridLayout aGL = new GridLayout(1, false);
		aGL.marginWidth = aGL.marginHeight = 10;
		shell.setLayout(aGL);
		tde.gui.applyColorFont(shell);
		green = new Color(tde.gui.display, 30, 150, 60);
		red = new Color(tde.gui.display, 200, 40, 40);
		gray = new Color(tde.gui.display, 150, 150, 150);
		body = new Composite(shell, SWT.NONE);
		body.setLayoutData(new GridData(GridData.FILL_BOTH));
		tde.gui.applyColorFont(body);
		GridLayout aBGL = new GridLayout(1, false);
		aBGL.marginWidth = aBGL.marginHeight = 0;
		body.setLayout(aBGL);
		Composite aButtons = new Composite(shell, SWT.NONE);
		aButtons.setLayoutData(new GridData(GridData.HORIZONTAL_ALIGN_END));
		tde.gui.applyColorFont(aButtons);
		aButtons.setLayout(new GridLayout(2, true));
		Button aRefresh = new Button(aButtons, SWT.PUSH);
		aRefresh.setText(l("Refresh"));
		tde.gui.applyColorFont(aRefresh);
		aRefresh.setLayoutData(new GridData(GridData.FILL_HORIZONTAL));
		aRefresh.addListener(SWT.Selection, e -> fill());
		Button aClose = new Button(aButtons, SWT.PUSH);
		aClose.setText(l("Close"));
		tde.gui.applyColorFont(aClose);
		aClose.setLayoutData(new GridData(GridData.FILL_HORIZONTAL));
		aClose.addListener(SWT.Selection, e -> shell.close());
		fill();
		shell.open();
	}

	String l(String aKey) {
		return tde.gui.lngs.get("Machine."+aKey);
	}

	static String gb(long aBytes) {
		return DepthModel.gb(aBytes);
	}

	/**
	 * The machine looked at (again) and the table made
	 */
	void fill() {
		tde.gui.setWaitCursor(true);
		try {
			evaluate();
			for(Control aC : body.getChildren()) {
				aC.dispose();
			}
			for(String aS : summary) {
				label(body, aS, null, SWT.NONE);
			}
			Composite aGrid = new Composite(body, SWT.NONE);
			GridData aGGD = new GridData(GridData.FILL_BOTH);
			aGGD.verticalIndent = 8;
			aGrid.setLayoutData(aGGD);
			tde.gui.applyColorFont(aGrid);
			Vector<Object[]> aRows = rows();
			int aCols = 1;
			for(Object[] aR : aRows) {
				aCols = Math.max(aCols, 1+((Object[][])aR[1]).length);
			}
			GridLayout aGL = new GridLayout(aCols, false);
			aGL.horizontalSpacing = 6;
			aGL.verticalSpacing = 4;
			aGrid.setLayout(aGL);
			label(aGrid, l("Function"), tde.gui.fontsSwt.robotoBold, SWT.NONE);
			Label aMH = label(aGrid, l("Models"), tde.gui.fontsSwt.robotoBold, SWT.NONE);
			aMH.setLayoutData(new GridData(SWT.BEGINNING, SWT.CENTER, false, false, aCols-1, 1));
			for(Object[] aR : aRows) {
				label(aGrid, (String)aR[0], tde.gui.fontsSwt.robotoBold, SWT.NONE);
				Object[][] aCells = (Object[][])aR[1];
				for(int c = 0;c < aCols-1;c++) {
					if(c >= aCells.length) {
						new Label(aGrid, SWT.NONE);
						continue;
					}
					cell(aGrid, (String)aCells[c][0], (Integer)aCells[c][1], (Long)aCells[c][2]);
				}
			}
			Label aLegend = label(body, l("Legend"), null, SWT.WRAP);
			GridData aLGD = new GridData(GridData.FILL_HORIZONTAL);
			aLGD.widthHint = 640;
			aLGD.verticalIndent = 8;
			aLegend.setLayoutData(aLGD);
			aLegend.setForeground(tde.gui.colorsSwt.DARK_GRAY);
			body.layout(true, true);
			shell.pack();
		}
		finally {
			tde.gui.setWaitCursor(false);
		}
	}

	Label label(Composite aParent, String aText, org.eclipse.swt.graphics.Font aFont, int aStyle) {
		Label aL = new Label(aParent, aStyle);
		tde.gui.applyColorFont(aL);
		aL.setText(aText);
		if(aFont != null) {
			aL.setFont(aFont);
		}
		return aL;
	}

	/**
	 * Cell of a model: its name, its status in color (GPU green, CPU / No red, - gray), the details in the tooltip
	 */
	void cell(Composite aParent, String aName, int aStatus, long aNeed) {
		Composite aC = new Composite(aParent, SWT.BORDER);
		aC.setLayoutData(new GridData(GridData.FILL_HORIZONTAL));
		tde.gui.applyColorFont(aC);
		GridLayout aGL = new GridLayout(2, false);
		aGL.marginWidth = 4;
		aGL.marginHeight = 2;
		aC.setLayout(aGL);
		Label aN = label(aC, aName, null, SWT.NONE);
		aN.setLayoutData(new GridData(GridData.FILL_HORIZONTAL));
		Label aS = label(aC, " "+l(aStatus == GPU ? "GpuS" : aStatus == CPU ? "Cpu" : aStatus == NO ? "No" : "Na")+" ", tde.gui.fontsSwt.robotoBold, SWT.CENTER);
		GridData aSGD = new GridData();
		aSGD.widthHint = 44;
		aS.setLayoutData(aSGD);
		aS.setBackground(aStatus == GPU ? green : aStatus == NA ? gray : red);
		aS.setForeground(tde.gui.colorsSwt.WHITE);
		String aTip;
		String aCap = capacity > 0 ? gb(capacity) : "0";
		switch(aStatus) {
		case GPU: aTip = l("TipGpu").replace("%NEED%", gb(aNeed)).replace("%CAP%", aCap); break;
		case CPU: aTip = capacity > 0 && (mac ? torchGpu || onnxGpu : true) ? l("TipCpu").replace("%NEED%", gb(aNeed)).replace("%CAP%", aCap) : l("TipCpuNoGpu"); break;
		case NO: aTip = l("TipNo").replace("%NEED%", gb(aNeed)).replace("%CAP%", aCap); break;
		default: aTip = l("TipNa");
		}
		aC.setToolTipText(aTip);
		aN.setToolTipText(aTip);
		aS.setToolTipText(aTip);
	}

	/**
	 * The machine: system, GPUs and what the runtimes of the models can use here
	 */
	void evaluate() {
		summary.clear();
		String aOs = System.getProperty("os.name");
		String aArch = System.getProperty("os.arch").toLowerCase();
		mac = aOs.toLowerCase().contains("mac");
		boolean aArm64 = aArch.equals("aarch64") || aArch.equals("arm64");
		python = PythonAI.hasPython();
		torchGpu = PythonAI.hasGpu();
		onnxSupported = DepthEstimator.isPlatformSupported();
		onnxGpu = onnxSupported && (mac ? aArm64 : DepthEstimator.useGpuRuntime());
		long aRam = -1;
		try {
			aRam = ((com.sun.management.OperatingSystemMXBean)java.lang.management.ManagementFactory.getOperatingSystemMXBean()).getTotalMemorySize();
		}
		catch(Throwable t) {
			t.printStackTrace(System.err);
		}
		summary.add(l("System").replace("%OS%", aOs+" "+System.getProperty("os.version")).replace("%ARCH%", aArch)
				.replace("%CORES%", ""+Runtime.getRuntime().availableProcessors()).replace("%RAM%", aRam > 0 ? gb(aRam) : "?"));
		capacity = -1;
		if(mac && aArm64) {
			//Unified memory: the GPU uses about 3/4 of it
			capacity = aRam > 0 ? aRam*3/4 : -1;
			summary.add(l("GpuMac").replace("%TOTAL%", capacity > 0 ? gb(capacity) : "?"));
		}
		else {
			List<String[]> aGpus = DepthEstimator.nvidiaGpus();
			for(String[] aG : aGpus) {
				long aTotal = Long.parseLong(aG[2]);
				capacity = Math.max(capacity, aTotal);
				summary.add(l("Gpu").replace("%I%", aG[0]).replace("%NAME%", aG[1]).replace("%TOTAL%", gb(aTotal)).replace("%FREE%", gb(Long.parseLong(aG[3]))));
			}
			if(aGpus.isEmpty()) {
				summary.add(l("NoGpu"));
			}
		}
		if(!torchGpu && !onnxGpu) {
			capacity = -1;
		}
	}

	/**
	 * Model run by ONNX Runtime (Java): GPU by CUDA (NVIDIA) or WebGPU (Apple Silicon)
	 */
	int onnx(long aNeed, boolean aGpuOnly) {
		if(!onnxSupported) {
			return NA;
		}
		if(onnxGpu && capacity >= aNeed) {
			return GPU;
		}
		return aGpuOnly ? NO : CPU;
	}

	/**
	 * Model run by PyTorch (Python environment): GPU by CUDA (NVIDIA) or MPS (Apple Silicon)
	 */
	int torch(long aNeed, boolean aGpuOnly) {
		if(!python) {
			return NA;
		}
		if(torchGpu && capacity >= aNeed) {
			return GPU;
		}
		return aGpuOnly ? NO : CPU;
	}

	Object[] cellOf(String aName, int aStatus, long aNeed) {
		return new Object[] {aName, aStatus, aNeed};
	}

	/**
	 * {function name, cells {model name, status, GPU memory needed}}
	 */
	Vector<Object[]> rows() {
		Vector<Object[]> aRows = new Vector<Object[]>();
		Vector<Object[]> aC = new Vector<Object[]>();
		for(DepthModel aM : DepthModel.MODELS) {
			aC.add(cellOf("Depth Anything V2 "+aM.id, onnx(aM.gpuMemory, false), aM.gpuMemory));
		}
		aRows.add(new Object[] {l("Depth"), aC.toArray(new Object[0][])});
		aC.clear();
		for(SmartFill.Model aM : SmartFill.MODELS) {
			if(aM.engine != null) {
				aC.add(cellOf(tde.gui.lngs.get(aM.key()), onnx(aM.engine.gpuMemory, aM.engine.gpuOnly), aM.engine.gpuMemory));
			}
			else {
				aC.add(cellOf(tde.gui.lngs.get(aM.key()), torch(FillGenerator.GPU_MEMORY, true), FillGenerator.GPU_MEMORY));
			}
		}
		aRows.add(new Object[] {l("Fill"), aC.toArray(new Object[0][])});
		aC.clear();
		for(SceneGenerator.Model aM : SceneGenerator.MODELS) {
			aC.add(cellOf(tde.gui.lngs.get(aM.key()), aM.depthModel != null ? onnx(aM.gpuMemory, false) : torch(aM.gpuMemory, false), aM.gpuMemory));
		}
		aRows.add(new Object[] {l("Scene"), aC.toArray(new Object[0][])});
		aC.clear();
		for(WanGenerator.Variant aV : WanGenerator.VARIANTS) {
			aC.add(cellOf("Wan2.2 5B "+aV.id, torch(aV.gpuMemory, true), aV.gpuMemory));
		}
		aRows.add(new Object[] {l("Video"), aC.toArray(new Object[0][])});
		aC.clear();
		for(SoundGenerator.Model aM : SoundGenerator.MUSIC_MODELS) {
			long aNeed = mac ? aM.macMemory : aM.cudaMemory;
			aC.add(cellOf("MusicGen "+aM.id, torch(aNeed, false), aNeed));
		}
		aRows.add(new Object[] {l("Music"), aC.toArray(new Object[0][])});
		aC.clear();
		for(SoundGenerator.Model aM : SoundGenerator.SFX_MODELS) {
			long aNeed = mac ? aM.macMemory : aM.cudaMemory;
			aC.add(cellOf(aM.id, torch(aNeed, false), aNeed));
		}
		aRows.add(new Object[] {l("Sfx"), aC.toArray(new Object[0][])});
		aC.clear();
		for(SpeechGenerator.Model aM : SpeechGenerator.ASR_MODELS) {
			aC.add(cellOf(aM.name, torch(aM.gpuMemory, false), aM.gpuMemory));
		}
		aRows.add(new Object[] {l("Asr"), aC.toArray(new Object[0][])});
		aC.clear();
		for(SpeechGenerator.Model aM : SpeechGenerator.TTS_MODELS) {
			aC.add(cellOf(aM.name, torch(aM.gpuMemory, false), aM.gpuMemory));
		}
		aRows.add(new Object[] {l("Tts"), aC.toArray(new Object[0][])});
		aC.clear();
		for(MaskGenerator.Model aM : MaskGenerator.MODELS) {
			aC.add(cellOf("SAM 2.1 "+aM.name, torch(aM.gpuMemory, false), aM.gpuMemory));
		}
		aRows.add(new Object[] {l("Mask"), aC.toArray(new Object[0][])});
		aC.clear();
		for(ColorGenerator.Model aM : ColorGenerator.MODELS) {
			aC.add(cellOf("DDColor "+aM.id, torch(aM.gpuMemory, false), aM.gpuMemory));
		}
		aRows.add(new Object[] {l("Color"), aC.toArray(new Object[0][])});
		aC.clear();
		for(RemixGenerator.Model aM : RemixGenerator.MODELS) {
			aC.add(cellOf("Demucs "+aM.id, torch(aM.gpuMemory, false), aM.gpuMemory));
		}
		aRows.add(new Object[] {l("Remix"), aC.toArray(new Object[0][])});
		aC.clear();
		aC.add(cellOf("Qwen3-VL 4B", torch(PromptExtender.gpuMemory(), false), PromptExtender.gpuMemory()));
		aRows.add(new Object[] {l("Prompt"), aC.toArray(new Object[0][])});
		return aRows;
	}
}
