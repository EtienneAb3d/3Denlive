package com.cubaix.TDenlive.GUI;

import java.awt.image.BufferedImage;
import java.io.BufferedInputStream;
import java.io.File;
import java.io.InputStream;
import java.util.Vector;

import org.eclipse.swt.SWT;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.MessageBox;
import org.eclipse.swt.widgets.ProgressBar;
import org.eclipse.swt.widgets.Shell;

import com.cubaix.TDenlive.TDenlive;
import com.cubaix.TDenlive.ai.PythonAI;
import com.cubaix.TDenlive.ai.SceneGenerator;
import com.cubaix.TDenlive.depth.ComputedScenes;
import com.cubaix.TDenlive.depth.DepthModel;
import com.cubaix.TDenlive.ffmpeg.Ffmpeg;
import com.cubaix.TDenlive.medias.Clip;
import com.cubaix.TDenlive.medias.StereoImage;
import com.cubaix.TDenlive.medias.StereoMovie;
import com.cubaix.TDenlive.processors.Scene3D;
import com.cubaix.TDenlive.utils.ImageUtils;

/**
 * Precomputation of the 3D scenes of a clip for the processor Scene3D (as the depth maps of the Depthmap processor):
 * proposed when the processor is added to a clip whose media has none, or from the button of the processor. The left
 * view of the media as it is shown (rotation included) goes to the model of the processor (Depth Pro or Flash3D,
 * ai.SceneGenerator, Scene3D.sceneModel): an image gives one PLY
 * (detail Fine); a video is decoded by ffmpeg at the rate of the montage (30 images per second at most, at most
 * FRAME_W wide) and gives one compact scene per image (detail Standard), computed by batches of BATCH (a canceled
 * computation goes on from the images missing). Files: see depth.ComputedScenes. Progress window, canceled by its
 * button or its closing; the monitor shows the images as soon as their scene is there.
 */
public class SceneConvertGUI {
	static boolean running = false;
	static final int BATCH = 12;
	static final int FRAME_W = 1024;
	//Disk size of a compact scene of the detail Standard (about 20 bytes x 2 Gaussians x 256 x 384)
	static final long FRAME_BYTES = 4L*1024*1024;

	TDenlive tde;
	Clip clip;
	Scene3D proc;
	Shell shell;
	Label statusL;
	ProgressBar bar;
	volatile boolean canceled = false;
	volatile SceneGenerator generator = null;
	//Model of the scenes (that of the processor, Scene3D.sceneModel)
	final SceneGenerator.Model model;
	//Run at the end of a computation without error nor cancel (offerMissing(): the next clip)
	Runnable onDone = null;

	SceneConvertGUI(TDenlive aTDe,Clip aClip,Scene3D aP) {
		tde = aTDe;
		clip = aClip;
		proc = aP;
		model = SceneGenerator.modelById(aP.sceneModel);
	}

	static String lng(TDenlive aTDe,String aKey) {
		return aTDe.gui.lngs.get(aKey);
	}

	/**
	 * Processor just added: its scenes are used if they exist, else their computation is proposed
	 */
	public static void checkNewProcessor(TDenlive aTDe,Clip aClip,Scene3D aP) {
		if(aP.computed() != null) {
			aP.fixAutoCam();
			return;
		}
		if(running || !SceneGenerator.modelById(aP.sceneModel).isSupported()) {
			return;
		}
		convert(aTDe, aClip, aP);
	}

	/**
	 * Ask, then compute the scenes of the clip (again, those of the images already computed are replaced)
	 */
	public static void convert(TDenlive aTDe,Clip aClip,Scene3D aP) {
		if(running) {
			AiClipGUI.message(aTDe, SWT.ICON_INFORMATION, lng(aTDe, "SceneConvert.Running"));
			return;
		}
		if(!SceneGenerator.modelById(aP.sceneModel).isSupported()) {
			AiClipGUI.message(aTDe, SWT.ICON_INFORMATION, lng(aTDe, "AI.UnsupportedAll"));
			return;
		}
		if(!ComputedScenes.isConvertible(aClip.getMedia())) {
			AiClipGUI.message(aTDe, SWT.ICON_INFORMATION, lng(aTDe, "SceneConvert.NotConvertible"));
			return;
		}
		StereoImage aSI = (StereoImage)aClip.getMedia();
		boolean aVideo = aSI instanceof StereoMovie;
		double aFps = fps(aTDe);
		int aFrames = aVideo ? Math.max(1, (int)Math.ceil(aSI.getDurationMS()*aFps/1000.0)) : 1;
		SceneGenerator.Model aModel = SceneGenerator.modelById(aP.sceneModel);
		String aMsg = lng(aTDe, aVideo ? "SceneConvert.AskVideo" : "SceneConvert.AskImage")
				.replace("%MODEL%", lng(aTDe, aModel.key()))
				.replace("%N%", ""+aFrames)
				.replace("%FPS%", String.format("%.0f", aFps))
				.replace("%DISK%", DepthModel.gb(aFrames*FRAME_BYTES))
				.replace("%WHERE%", lng(aTDe, SceneGenerator.cpuReason(aModel) == null ? "SceneConvert.OnGpu" : "SceneConvert.OnCpu")
						.replace("%S%", aModel.seconds < 1 ? "< 1" : String.format("%.0f", aModel.seconds)));
		long aDl = SceneGenerator.bytesToDownload(aTDe.tdeDir, aModel);
		if(aDl > 0) {
			aMsg += "\n\n"+lng(aTDe, "AI.MusicDownload").replace("%GB%", DepthModel.gb(aDl));
		}
		aMsg += "\n\n"+lng(aTDe, aModel.licenceKey);
		MessageBox aMB = new MessageBox(aTDe.gui.shell, SWT.ICON_QUESTION|SWT.YES|SWT.NO);
		aMB.setText(lng(aTDe, "SceneConvert.Title").replace("%MODEL%", lng(aTDe, aModel.key())));
		aMB.setMessage(aMsg);
		if(aMB.open() != SWT.YES) {
			return;
		}
		new SceneConvertGUI(aTDe, aClip, aP).start(aVideo, aFps, aFrames);
	}

	/**
	 * Project just opened: the clips of 2D-&gt;3D scenes (active Scene3D) whose scenes are not computed here (a demo
	 * pack does not hold them all, medias moved...) are listed, and their computation is proposed at once, one clip
	 * after the other (the same media with the same model only once; the models not supported here are left out)
	 */
	public static void offerMissing(TDenlive aTDe) {
		try {
			if(running || aTDe.timeLineStack == null) {
				return;
			}
			//Scenes file / folder -> {clip, processor}
			java.util.LinkedHashMap<String,Object[]> aMissing = new java.util.LinkedHashMap<String,Object[]>();
			for(com.cubaix.TDenlive.medias.VideoTimeLine aTL : new Vector<com.cubaix.TDenlive.medias.VideoTimeLine>(aTDe.timeLineStack.videoTimeLineStack)) {
				for(Clip aC : new Vector<Clip>(aTL.clipList)) {
					for(com.cubaix.TDenlive.processors.Processor aP : new Vector<com.cubaix.TDenlive.processors.Processor>(aC.getProcessors())) {
						if(!(aP instanceof Scene3D) || !aP.isActive) {
							continue;
						}
						Scene3D aS = (Scene3D)aP;
						SceneGenerator.Model aModel = SceneGenerator.modelById(aS.sceneModel);
						if(!ComputedScenes.isConvertible(aC.getMedia()) || !aModel.isSupported()
								|| ComputedScenes.find(aTDe, aC.getMedia(), aS.sceneModel) != null) {
							continue;
						}
						String aKey = ComputedScenes.path((StereoImage)aC.getMedia(), aModel.id, null).getAbsolutePath();
						aMissing.putIfAbsent(aKey, new Object[] {aC, aS});
					}
				}
			}
			if(aMissing.isEmpty()) {
				return;
			}
			double aFps = fps(aTDe);
			StringBuilder aList = new StringBuilder();
			long aImages = 0;
			java.util.LinkedHashSet<SceneGenerator.Model> aModels = new java.util.LinkedHashSet<SceneGenerator.Model>();
			for(Object[] aCS : aMissing.values()) {
				StereoImage aSI = (StereoImage)((Clip)aCS[0]).getMedia();
				boolean aVideo = aSI instanceof StereoMovie;
				int aFrames = aVideo ? Math.max(1, (int)Math.ceil(aSI.getDurationMS()*aFps/1000.0)) : 1;
				aImages += aFrames;
				SceneGenerator.Model aModel = SceneGenerator.modelById(((Scene3D)aCS[1]).sceneModel);
				aModels.add(aModel);
				aList.append("- ").append(aSI.fileName)
					.append(aVideo ? " ("+aFrames+" "+lng(aTDe, "SceneConvert.Images")+")" : "")
					.append(" - ").append(lng(aTDe, aModel.key())).append('\n');
			}
			String aMsg = lng(aTDe, "SceneConvert.OpenMissing").replace("%N%", ""+aMissing.size())
					.replace("%LIST%", aList.toString()).replace("%IMAGES%", ""+aImages)
					.replace("%DISK%", DepthModel.gb(aImages*FRAME_BYTES));
			for(SceneGenerator.Model aModel : aModels) {
				aMsg += "\n\n"+lng(aTDe, aModel.key())+" : "
						+lng(aTDe, SceneGenerator.cpuReason(aModel) == null ? "SceneConvert.OnGpu" : "SceneConvert.OnCpu")
						.replace("%S%", aModel.seconds < 1 ? "< 1" : String.format("%.0f", aModel.seconds));
				long aDl = SceneGenerator.bytesToDownload(aTDe.tdeDir, aModel);
				if(aDl > 0) {
					aMsg += " "+lng(aTDe, "AI.MusicDownload").replace("%GB%", DepthModel.gb(aDl));
				}
				aMsg += "\n"+lng(aTDe, aModel.licenceKey);
			}
			MessageBox aMB = new MessageBox(aTDe.gui.shell, SWT.ICON_QUESTION|SWT.YES|SWT.NO);
			aMB.setText(lng(aTDe, "SceneConvert.OpenTitle"));
			aMB.setMessage(aMsg);
			if(aMB.open() != SWT.YES) {
				return;
			}
			computeChain(aTDe, new Vector<Object[]>(aMissing.values()), 0);
		}
		catch(Throwable t) {
			t.printStackTrace(System.err);
		}
	}

	/**
	 * The scenes of the clips aTodo from aI, one after the other (a cancel or an error stops)
	 */
	static void computeChain(TDenlive aTDe, Vector<Object[]> aTodo, int aI) {
		if(aI >= aTodo.size() || running) {
			return;
		}
		Clip aC = (Clip)aTodo.get(aI)[0];
		Scene3D aS = (Scene3D)aTodo.get(aI)[1];
		if(ComputedScenes.find(aTDe, aC.getMedia(), aS.sceneModel) != null) {
			//Computed meanwhile
			aS.scenesChanged();
			computeChain(aTDe, aTodo, aI+1);
			return;
		}
		StereoImage aSI = (StereoImage)aC.getMedia();
		boolean aVideo = aSI instanceof StereoMovie;
		double aFps = fps(aTDe);
		int aFrames = aVideo ? Math.max(1, (int)Math.ceil(aSI.getDurationMS()*aFps/1000.0)) : 1;
		SceneConvertGUI aGUI = new SceneConvertGUI(aTDe, aC, aS);
		aGUI.onDone = () -> computeChain(aTDe, aTodo, aI+1);
		aGUI.start(aVideo, aFps, aFrames);
	}

	/**
	 * Rate of the scenes of a video: that of the montage, 30 at most
	 */
	static double fps(TDenlive aTDe) {
		double aF = aTDe.config.outFps > 0 ? aTDe.config.outFps : 25;
		return Math.min(30, aF);
	}

	void start(boolean aVideo,double aFps,int aFrames) {
		running = true;
		shell = new Shell(tde.gui.shell, SWT.DIALOG_TRIM);
		shell.setText(lng(tde, "SceneConvert.Title").replace("%MODEL%", lng(tde, model.key())));
		shell.setImage(tde.gui.imgsSwt.getIcon("3Denlive32.png"));
		shell.setLayout(new GridLayout(1, false));
		tde.gui.applyColorFont(shell);
		Label aName = new Label(shell, SWT.NONE);
		tde.gui.applyColorFont(aName);
		aName.setText(clip.getMedia().fileName);
		aName.setFont(tde.gui.fontsSwt.robotoBold);
		statusL = new Label(shell, SWT.WRAP);
		tde.gui.applyColorFont(statusL);
		GridData aSGD = new GridData(GridData.FILL_HORIZONTAL);
		aSGD.widthHint = 420;
		statusL.setLayoutData(aSGD);
		statusL.setText(lng(tde, "AI.Starting"));
		statusL.setForeground(tde.gui.colorsSwt.VIOLET);
		bar = new ProgressBar(shell, SWT.SMOOTH | SWT.HORIZONTAL);
		bar.setMaximum(1000);
		bar.setLayoutData(new GridData(GridData.FILL_HORIZONTAL));
		Button aCancel = new Button(shell, SWT.PUSH);
		tde.gui.applyColorFont(aCancel);
		aCancel.setText(lng(tde, "AI.Cancel"));
		aCancel.setImage(tde.gui.imgsSwt.getIcon("stop.gif"));
		aCancel.setLayoutData(new GridData(GridData.HORIZONTAL_ALIGN_END));
		aCancel.addListener(SWT.Selection, e -> shell.close());
		shell.addListener(SWT.Close, e -> cancel());
		shell.pack();
		org.eclipse.swt.graphics.Rectangle aP = tde.gui.shell.getBounds();
		org.eclipse.swt.graphics.Rectangle aS = shell.getBounds();
		shell.setLocation(aP.x+aP.width-aS.width-40, aP.y+aP.height-aS.height-60);
		shell.open();
		StereoImage aSI = (StereoImage)clip.getMedia();
		Thread aTh = new Thread(() -> {
			String aErr = null;
			try {
				if(aVideo) {
					computeVideo((StereoMovie)aSI, aFps, aFrames);
				}
				else {
					computeImage(aSI);
				}
			}
			catch(Throwable t) {
				t.printStackTrace(System.err);
				aErr = t instanceof PythonAI.GenerationException && "OOM".equals(((PythonAI.GenerationException)t).code)
						? lng(tde, "AI.SceneErrorOom") : (t.getMessage() == null ? t.toString() : t.getMessage());
			}
			finally {
				//Depth maps: the estimator stopped (its memory given back)
				SceneGenerator aG = generator;
				if(aG != null) {
					aG.close();
				}
			}
			final String aE = aErr;
			tde.gui.display.asyncExec(() -> {
				try {
					running = false;
					proc.scenesChanged();
					proc.fixAutoCam();
					if(!shell.isDisposed()) {
						shell.dispose();
					}
					tde.gui.monitorGUI.redraw();
					//The panel of the clip if it shows it (none at the opening of a project: offerMissing())
					if(tde.gui.processorGUI != null && !tde.selected.clips.isEmpty() && tde.selected.clips.lastElement() == clip) {
						tde.gui.processorGUI.setProcessors(clip.getProcessors());
					}
					if(aE != null && !canceled) {
						AiClipGUI.message(tde, SWT.ICON_ERROR, lng(tde, "AI.SceneError")+"\n"+aE);
					}
				}
				catch(Throwable t) {
					t.printStackTrace(System.err);
				}
				finally {
					//The next clip of offerMissing() (not after an error nor a cancel)
					if(onDone != null && aE == null && !canceled) {
						onDone.run();
					}
				}
			});
		}, "3D scenes of a clip");
		aTh.start();
	}

	void cancel() {
		canceled = true;
		SceneGenerator aG = generator;
		if(aG != null) {
			aG.cancel();
		}
	}

	void status(String aText,long aDone,long aTotal) {
		tde.gui.display.asyncExec(() -> {
			if(shell.isDisposed()) {
				return;
			}
			statusL.setText(aText);
			if(aTotal > 0) {
				bar.setSelection((int)(1000*aDone/aTotal));
			}
			shell.layout(true);
		});
	}

	PythonAI.Progress progress(long[] aBase,long aTotal) {
		return new PythonAI.Progress() {
			@Override
			public void status(String aKey, String aDetail, long aDone, long aT) {
				String aText = lng(tde, aKey);
				if(aKey.startsWith("AI.Downloading") && aT > 0) {
					SceneConvertGUI.this.status(aText+" "+DepthModel.gb(aDone)+" / "+DepthModel.gb(aT)+" GB", aDone, aT);
				}
				else if("AI.SceneComputing".equals(aKey) && aTotal > 0) {
					SceneConvertGUI.this.status(lng(tde, "SceneConvert.Frames").replace("%DONE%", ""+(aBase[0]+aDone)).replace("%N%", ""+aTotal), aBase[0]+aDone, aTotal);
				}
				else {
					SceneConvertGUI.this.status(aText+(aDetail == null ? "" : " "+aDetail), 0, 0);
				}
			}
			@Override
			public boolean isCanceled() {
				return canceled;
			}
		};
	}

	/**
	 * An image: its left view as shown, into a PNG of the work folder, then its scene
	 */
	void computeImage(StereoImage aSI) throws Exception {
		BufferedImage aL = aSI.rotatedLeft != null ? aSI.rotatedLeft : aSI.loadedLeft;
		if(aL == null) {
			throw new Exception("No image");
		}
		File aTmp = new File(tde.getWorkCacheDir(), "AI_Scene3D_"+aSI.UID+".png");
		aTmp.getParentFile().mkdirs();
		//8 bits (also from an HDR image)
		BufferedImage aBI = ImageUtils.createImage(aL.getWidth(), aL.getHeight());
		java.awt.Graphics2D aG = aBI.createGraphics();
		aG.drawImage(aL, 0, 0, null);
		aG.dispose();
		ImageUtils.saveImage(aTmp.getAbsolutePath(), aBI);
		try {
			File aOut = ComputedScenes.writePath(tde, aSI, model.id);
			File aPart = new File(aOut.getPath()+".tmp.ply");
			generator = new SceneGenerator(tde.tdeDir, model, progress(new long[] {0}, 1));
			if(generator.generate(aTmp, SceneGenerator.FINE, aPart) && !canceled) {
				java.nio.file.Files.move(aPart.toPath(), aOut.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
			}
			aPart.delete();
		}
		finally {
			aTmp.delete();
		}
	}

	/**
	 * A video: its frames decoded by ffmpeg (left view, oriented) into PNGs of the work folder, then their scenes
	 * by batches
	 */
	void computeVideo(StereoMovie aSM, double aFps, int aFrames) throws Exception {
		File aDir = ComputedScenes.writePath(tde, aSM, model.id);
		File aWork = new File(tde.getWorkCacheDir(), "AI_Scene3D_"+aSM.UID+"_"+System.currentTimeMillis());
		aWork.mkdirs();
		try {
			//The scenes of the previous computation are replaced
			File[] aOld = aDir.listFiles();
			if(aOld != null) {
				for(File aF : aOld) {
					aF.delete();
				}
			}
			ComputedScenes.writeInfo(aDir, aFps, aFrames);
			tde.gui.display.asyncExec(proc::scenesChanged);
			status(lng(tde, "SceneConvert.Decoding"), 0, 0);
			int aDecoded = decode(aSM, aFps, aFrames, aWork);
			if(canceled) {
				return;
			}
			if(aDecoded < aFrames) {
				//The video is shorter than its duration said
				ComputedScenes.writeInfo(aDir, aFps, aDecoded);
			}
			long[] aBase = {0};
			generator = new SceneGenerator(tde.tdeDir, model, progress(aBase, aDecoded));
			for(int f = 0;f < aDecoded && !canceled;f += BATCH) {
				int aN = Math.min(BATCH, aDecoded-f);
				File[] aIns = new File[aN];
				File[] aOuts = new File[aN];
				for(int i = 0;i < aN;i++) {
					aIns[i] = new File(aWork, String.format("%06d.png", f+i));
					aOuts[i] = ComputedScenes.frameFile(aDir, f+i);
				}
				aBase[0] = f;
				if(!generator.generate(aIns, SceneGenerator.STANDARD, aOuts, true)) {
					return;
				}
				for(File aI : aIns) {
					aI.delete();
				}
				//The monitor shows the images computed
				tde.gui.display.asyncExec(() -> tde.gui.monitorGUI.redraw());
			}
		}
		finally {
			File[] aFs = aWork.listFiles();
			if(aFs != null) {
				for(File aF : aFs) {
					aF.delete();
				}
			}
			aWork.delete();
		}
	}

	/**
	 * Frames of the left view at aFps (at most FRAME_W wide), oriented as the media, into aWork/000000.png...
	 * @return the number of frames
	 */
	int decode(StereoMovie aSM,double aFps,int aFrames,File aWork) throws Exception {
		String aCrop = aSM.origStereo == 1 ? "crop=iw/2:ih:iw/2:0," : (aSM.origStereo == 2 ? "crop=iw/2:ih:0:0," : "");
		Vector<String> aCmd = new Vector<String>();
		aCmd.add(Ffmpeg.ffmpegPath());
		aCmd.add("-v"); aCmd.add("error");
		aCmd.add("-i"); aCmd.add(aSM.getPathL());
		aCmd.add("-an");
		//HDR video: tone mapped to SDR
		String aSdr = Ffmpeg.sdrFilter(aSM.getPathL());
		aCmd.add("-vf"); aCmd.add((aSdr == null ? "" : aSdr+",")+aCrop+"fps="+String.format(java.util.Locale.ROOT, "%.3f", aFps)+",scale='min("+FRAME_W+",iw)':-2");
		aCmd.add("-frames:v"); aCmd.add(""+aFrames);
		aCmd.add("-f"); aCmd.add("image2pipe");
		aCmd.add("-vcodec"); aCmd.add("bmp");
		aCmd.add("-");
		ProcessBuilder aPB = new ProcessBuilder(aCmd);
		aPB.redirectError(ProcessBuilder.Redirect.DISCARD);
		Process aP = aPB.start();
		int aN = 0;
		try(InputStream aIS = new BufferedInputStream(aP.getInputStream(), 1 << 20)) {
			BufferedImage aBI;
			while(!canceled && (aBI = Ffmpeg.readBmp(aIS)) != null) {
				aBI = aSM.orient(aBI);
				ImageUtils.saveImage(new File(aWork, String.format("%06d.png", aN)).getAbsolutePath(), aBI);
				aN++;
				if(aN%10 == 0) {
					status(lng(tde, "SceneConvert.Decoding")+" "+aN+" / "+aFrames, aN, aFrames);
				}
			}
		}
		finally {
			if(aP.isAlive()) {
				aP.destroyForcibly();
			}
		}
		return aN;
	}
}
