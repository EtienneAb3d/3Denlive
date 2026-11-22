package com.cubaix.TDenlive.GUI;

import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.MessageBox;
import org.eclipse.swt.widgets.Tree;
import org.eclipse.swt.widgets.TreeItem;

import com.cubaix.TDenlive.TDenlive;
import com.cubaix.TDenlive.medias.Clip;
import com.cubaix.TDenlive.GUI.processors.SubtitlesGUI;
import com.cubaix.TDenlive.processors.Processor;
import com.cubaix.TDenlive.processors.Subtitles;

public class ProcessorLibGUI extends TreeView {
	//Processors working on the sound (see Processor.isAudio())
	static final String AUDIO_PROCESSORS = ";Volume;TrackMixer;Loudness;Equalizer;DeEsser;AudioMeter;Spectrum;";
	
	public ProcessorLibGUI(TDenlive aTDe, Composite aParent) {
		super(aTDe, aParent);
		populateTree();
	}

	@Override
	void populateTree() {
		TreeItem aFolder = createFolder(tree, tde.gui.lngs.get("Processor.Stereo"));
		createProcessor(aFolder,tde.gui.lngs.get("Processor.Align"), "StereoAligner",true);
		createProcessor(aFolder,tde.gui.lngs.get("Processor.AdaptColor"), "ColorAdapter",true);
		createProcessor(aFolder,tde.gui.lngs.get("Processor.Depthmap"), "Depthmap",true);
		createProcessor(aFolder,tde.gui.lngs.get("Processor.DepthmapStereo"), "DepthmapStereo",true);
		createProcessor(aFolder,tde.gui.lngs.get("Processor.Scene3D"), "Scene3D",true);
		aFolder = createFolder(tree, tde.gui.lngs.get("Processor.Colors"));
		createProcessor(aFolder,tde.gui.lngs.get("Processor.HueSaturation"), "HueSaturation",true);
		createProcessor(aFolder,tde.gui.lngs.get("Processor.BrightnessContrast"), "BrightnessContrast",true);
		createProcessor(aFolder,tde.gui.lngs.get("Processor.Curves"), "Curves",true);
		createProcessor(aFolder,tde.gui.lngs.get("Processor.ColorTemperature"), "ColorTemperature",true);
		createProcessor(aFolder,tde.gui.lngs.get("Processor.ColorBalance"), "ColorBalance",true);
		createProcessor(aFolder,tde.gui.lngs.get("Processor.Vibrance"), "Vibrance",true);
		aFolder = createFolder(tree, tde.gui.lngs.get("Processor.Image"));
		createProcessor(aFolder,tde.gui.lngs.get("Processor.LensCorrection"), "LensCorrection",true);
		createProcessor(aFolder,tde.gui.lngs.get("Processor.Sharpen"), "Sharpen",true);
		createProcessor(aFolder,tde.gui.lngs.get("Processor.Smoothing"), "Smoothing",true);
		createProcessor(aFolder,tde.gui.lngs.get("Processor.Vignette"), "Vignette",true);
		createProcessor(aFolder,tde.gui.lngs.get("Processor.Grain"), "Grain",true);
		createProcessor(aFolder,tde.gui.lngs.get("Processor.Pixelize"), "Pixelize",true);
		aFolder = createFolder(tree, tde.gui.lngs.get("Processor.Composition"));
		createProcessor(aFolder,tde.gui.lngs.get("Processor.Cropper"), "Cropper",true);
		createProcessor(aFolder,tde.gui.lngs.get("Processor.Placer"), "Placer",true);
		createProcessor(aFolder,tde.gui.lngs.get("Processor.Transparency"), "Transparency",true);
		createProcessor(aFolder,tde.gui.lngs.get("Processor.Chromakey"), "Chromakey",true);
		createProcessor(aFolder,tde.gui.lngs.get("Processor.Despill"), "Despill",true);
		aFolder = createFolder(tree, tde.gui.lngs.get("Processor.Design"));
		createProcessor(aFolder,tde.gui.lngs.get("Processor.Framing"), "Framing",true);
		aFolder = createFolder(tree, tde.gui.lngs.get("Processor.Video"));
		createProcessor(aFolder,tde.gui.lngs.get("Processor.Subtitles"), "Subtitles",true);
		createProcessor(aFolder,tde.gui.lngs.get("Processor.Speed"), "Speed",true);
		aFolder = createFolder(tree, tde.gui.lngs.get("Processor.Audio"));
		createProcessor(aFolder,tde.gui.lngs.get("Processor.Volume"), "Volume",true);
		createProcessor(aFolder,tde.gui.lngs.get("Processor.TrackMixer"), "TrackMixer",true);
		//Same processor as in the Video folder: speed of the images and of the sound of any video or sound clip
		createProcessor(aFolder,tde.gui.lngs.get("Processor.Speed"), "Speed",true);
		createProcessor(aFolder,tde.gui.lngs.get("Processor.Loudness"), "Loudness",true);
		createProcessor(aFolder,tde.gui.lngs.get("Processor.Equalizer"), "Equalizer",true);
		createProcessor(aFolder,tde.gui.lngs.get("Processor.DeEsser"), "DeEsser",true);
		aFolder = createFolder(tree, tde.gui.lngs.get("Processor.Monitor"));
		TreeItem aSub = createSubFolder(aFolder, tde.gui.lngs.get("Processor.Image"));
		createProcessor(aSub,tde.gui.lngs.get("Processor.Histogram"), "Histogram",true);
		createProcessor(aSub,tde.gui.lngs.get("Processor.Waveform"), "Waveform",true);
		createProcessor(aSub,tde.gui.lngs.get("Processor.Vectorscope"), "Vectorscope",true);
		aSub.setExpanded(true);
		aSub = createSubFolder(aFolder, tde.gui.lngs.get("Processor.Audio"));
		createProcessor(aSub,tde.gui.lngs.get("Processor.AudioMeter"), "AudioMeter",true);
		createProcessor(aSub,tde.gui.lngs.get("Processor.Spectrum"), "Spectrum",true);
		aSub.setExpanded(true);
		//Collapsed like the other folders (their setExpanded() in createFolder() has no effect without children
		//yet); its sub-folders open with it
		aFolder = createFolder(tree, tde.gui.lngs.get("Processor.Expert"));
		createProcessor(aFolder,tde.gui.lngs.get("Processor.Scripter"), "Scripter",true);
		
		//Help message: description of the processor under the mouse
		tde.gui.tip(tree, "ProcLib");
		tree.addListener(SWT.MouseMove, e -> {
			TreeItem aTI = tree.getItem(new Point(e.x, e.y));
			String aTip = tde.gui.lngs.get("Tip.ProcLib");
			if(aTI != null && aTI.getData() instanceof String && !"Unavailable".equals(aTI.getData())) {
				aTip = tde.gui.lngs.get("Tip.ProcLib_"+aTI.getData())+"\n"+aTip;
			}
			if(!aTip.equals(tree.getToolTipText())) {
				tree.setToolTipText(aTip);
			}
		});
		
		tree.addListener(SWT.MouseDoubleClick, e -> {
			Point point = new Point(e.x, e.y);
			TreeItem aTI = tree.getItem (point);
			if(aTI.getData() != null && aTI.getData() instanceof String && "Unavailable".equals(aTI.getData())) {
				MessageBox messageBox = new MessageBox(tde.gui.shell,SWT.ICON_INFORMATION|SWT.OK);
			    messageBox.setMessage(tde.gui.lngs.get("Processor.NotYetAvailable"));
			    int rc = messageBox.open();
			    return;
			}
			if(aTI.getData() == null) {
				//Certainly a folder
				return;
			}
			if(tde.selected.clips.size() <= 0) {
				MessageBox messageBox = new MessageBox(tde.gui.shell,SWT.ICON_INFORMATION|SWT.OK);
			    messageBox.setMessage(tde.gui.lngs.get("Processor.SelectAClip"));
			    int rc = messageBox.open();
				return;
			}
			if(tde.selected.clips.size() > 1) {
				MessageBox messageBox = new MessageBox(tde.gui.shell,SWT.ICON_INFORMATION|SWT.OK);
			    messageBox.setMessage(tde.gui.lngs.get("Processor.SelectOneClip"));
			    messageBox.open();
				return;
			}
			Clip aClip = tde.selected.clips.lastElement();
			//Sound processors only on clips with a sound, image processors not on the audio lines
			boolean aIsAudioClip = tde.timeLineStack.isAudioClip(aClip);
			boolean aIsAudioProc = AUDIO_PROCESSORS.indexOf(";"+aTI.getData()+";") >= 0;
			boolean aHasSound = aClip.getMedia() != null && aClip.getMedia().getAudioReader() != null;
			if("Speed".equals(aTI.getData())) {
				//Speed: any clip of a video or a sound (also on the audio lines), only one
				String aMsg = aClip.getMediaLimitMS() < 0 ? "Processor.SelectAVideoOrSoundClip"
						: (aClip.getSpeedProcessor(false) != null ? "Processor.OneSpeed" : null);
				if(aMsg != null) {
					MessageBox messageBox = new MessageBox(tde.gui.shell,SWT.ICON_INFORMATION|SWT.OK);
				    messageBox.setMessage(tde.gui.lngs.get(aMsg));
				    messageBox.open();
					return;
				}
			}
			else if((aIsAudioProc && !aHasSound) || (!aIsAudioProc && aIsAudioClip)) {
				MessageBox messageBox = new MessageBox(tde.gui.shell,SWT.ICON_INFORMATION|SWT.OK);
			    messageBox.setMessage(tde.gui.lngs.get(aIsAudioProc ? "Processor.SelectAnAudioClip" : "Processor.SelectAVideoClip"));
			    messageBox.open();
				return;
			}
			if("TrackMixer".equals(aTI.getData()) && aClip.getTrackMixer(false) != null) {
				//One multi-track mixer per clip
				MessageBox messageBox = new MessageBox(tde.gui.shell,SWT.ICON_INFORMATION|SWT.OK);
				messageBox.setMessage(tde.gui.lngs.get("Processor.OneTrackMixer"));
				messageBox.open();
				return;
			}
			if(Clip.isDepthProcessorName((String)aTI.getData()) && aClip.getDepthProcessor() != null) {
				MessageBox messageBox = new MessageBox(tde.gui.shell,SWT.ICON_INFORMATION|SWT.OK);
			    messageBox.setMessage(tde.gui.lngs.get("Processor.OneDepthmap"));
			    messageBox.open();
				return;
			}
			//2D => 3D scene: an image or a video of a file
			if("Scene3D".equals(aTI.getData()) && !com.cubaix.TDenlive.depth.ComputedScenes.isConvertible(aClip.getMedia())) {
				MessageBox messageBox = new MessageBox(tde.gui.shell,SWT.ICON_INFORMATION|SWT.OK);
			    messageBox.setMessage(tde.gui.lngs.get("SceneConvert.NotConvertible"));
			    messageBox.open();
				return;
			}
			Processor aNewP = aClip.addProcessor((String)aTI.getData());
			tde.gui.monitorGUI.redraw();
			//A depth map processor on a clip without depth map: propose to compute it
			if(aNewP != null) {
				tde.gui.display.asyncExec(() -> DepthmapConvertGUI.checkNewProcessor(tde, aClip, aNewP));
			}
			//2D => 3D scene: its scenes computed at once (asked)
			if(aNewP instanceof com.cubaix.TDenlive.processors.Scene3D) {
				tde.gui.display.asyncExec(() -> SceneConvertGUI.checkNewProcessor(tde, aClip, (com.cubaix.TDenlive.processors.Scene3D)aNewP));
			}
			//Subtitles: choose their file at once
			if(aNewP instanceof Subtitles) {
				tde.gui.display.asyncExec(() -> SubtitlesGUI.chooseFile(tde, (Subtitles)aNewP));
			}
		});
	}
	
	TreeItem createFolder(Tree aTree,String aText) {
		TreeItem aFolder = new TreeItem(aTree, SWT.NONE);
		aFolder.setForeground(tde.gui.colorsSwt.CUBAIX_BLUE);
		//No bold font: shown only on some folders (Monitor, whose sub-folders are expanded), all alike without it
		aFolder.setText(aText);
		aFolder.setExpanded(true);
		return aFolder;
	}
	/** Folder inside a folder (no data: a double-click does nothing) */
	TreeItem createSubFolder(TreeItem aParent,String aText) {
		TreeItem aFolder = new TreeItem(aParent, SWT.NONE);
		aFolder.setForeground(tde.gui.colorsSwt.CUBAIX_BLUE);
		aFolder.setText(aText);
		return aFolder;
	}

	TreeItem createProcessor(TreeItem aFolder,String aText,String aProcessorName,boolean aIsAvailable) {
		TreeItem aProcessor = new TreeItem(aFolder, SWT.NONE);
		aProcessor.setForeground(aIsAvailable?tde.gui.colorsSwt.CUBAIX_PINK:tde.gui.colorsSwt.LIGHTGRAY);
		aFolder.setFont(tde.gui.fontsSwt.roboto);//Font is not really changed(!?)
		aProcessor.setText(aText);
		aProcessor.setData(aIsAvailable?aProcessorName:"Unavailable");
		aProcessor.setGrayed(!aIsAvailable);
		return aProcessor;
	}
}
