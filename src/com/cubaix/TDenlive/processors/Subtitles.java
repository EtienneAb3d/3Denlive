package com.cubaix.TDenlive.processors;

import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.Vector;

import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.graphics.Rectangle;

import com.cubaix.TDenlive.TDenlive;
import com.cubaix.TDenlive.medias.Clip;
import com.cubaix.TDenlive.medias.Media;
import com.cubaix.TDenlive.shapes.Shape;
import com.cubaix.TDenlive.shapes.ShapeRenderer;
import com.cubaix.TDenlive.shapes.ShapesEditor;
import com.cubaix.TDenlive.utils.SubtitleFile;
import com.cubaix.TDenlive.xml.XmlObject;
import com.cubaix.TDenlive.xml.XmlTag;

/**
 * Subtitles of a clip, read from a .srt / .vtt file: a single text shape (style, position, size,
 * depth: edited like the shapes of a shapes clip, with keys) whose text is the subtitle of the
 * current time of the media (clip time + its shift in the media). The box keeps the height of a
 * line and the middle of its bottom side: the texts of several lines grow upward.
 * Drawn over the image of the clip, in the frame of the montage (the image is put at its top left
 * corner, as the montage does), so that the default position is at the bottom of the frame.
 * The cues are saved in the project (the file is only remembered).
 */
public class Subtitles extends Shapes {
	//Height of a line, bottom of the text (frame coordinates)
	static final double LINE_H = 0.06;
	static final double BOTTOM = 0.93;

	String filePath = null;
	Vector<SubtitleFile.Cue> cues = new Vector<SubtitleFile.Cue>();

	public Subtitles(TDenlive aTDe,Media aTargetMedia) {
		super(aTDe,aTargetMedia);
		isUndeletable = false;
		isUnmovable = false;
		isDeactivable = true;
		//A single key with a single text (not the shapes of a shapes clip)
		Key aKey = new Key();
		aKey.shapes.add(createText());
		keys.clear();
		keys.add(aKey);
	}

	/**
	 * Default text: white, black border, centered at the bottom of the frame, depth 0
	 */
	Shape createText() {
		double aR = tde.config.outRatio > 0 ? tde.config.outRatio : 16.0/9.0;
		Shape aS = Shape.create(Shape.TEXT, 0.4, BOTTOM-LINE_H, 0.6, BOTTOM, null);
		aS.id = 0;
		aS.filled = true;
		aS.fillColor = 0xFFFFFFFF;
		aS.stroked = true;
		aS.strokeColor = 0xFF000000;
		aS.strokeWidth = 0.003;
		aS.depth = 0;
		aS.bold = true;
		aS.align = Shape.ALIGN_CENTER;
		aS.text = sampleText();
		aS.fitText(aR);
		//Centered
		double aDX = 0.5-(aS.xs[0]+aS.xs[1])/2;
		for(int p = 0;p < aS.xs.length;p++) {
			aS.xs[p] += aDX;
		}
		return aS;
	}

	String sampleText() {
		return tde.gui != null && tde.gui.lngs != null ? tde.gui.lngs.get("Subtitles.Sample") : "Subtitle";
	}

	@Override
	public String getClassName() {return "Subtitles";}

	@Override
	void setDescr() {
		name = tde.gui.lngs.get("Processor.Subtitles");
		icon = "note.png";
	}

	public String getFilePath() {
		return filePath;
	}

	public int getNbCues() {
		return cues.size();
	}

	/**
	 * Read the subtitles of a file, returns the number of cues (0: none found, the current ones are kept)
	 */
	public int loadFile(String aPath) throws Exception {
		Vector<SubtitleFile.Cue> aCues = SubtitleFile.read(aPath);
		if(aCues.size() <= 0) {
			return 0;
		}
		synchronized (keys) {
			cues = aCues;
			filePath = aPath;
		}
		if(editor != null) {
			editor.fireShapesChanged();
			editor.fireSelectionChanged();
		}
		return aCues.size();
	}

	/**
	 * Time in the media at aTimePos (time line)
	 */
	long mediaTime(long aTimePos) {
		Clip aClip = getClip();
		return aTimePos-aClip.getStartTimeMS()+aClip.getShiftTimeMS();
	}

	/**
	 * Cue at aTimePos (time line), null if none
	 */
	SubtitleFile.Cue cueAt(long aTimePos) {
		synchronized (keys) {
			return SubtitleFile.cueAt(cues, mediaTime(aTimePos));
		}
	}

	/**
	 * Change the text of the subtitle shown at the time cursor (from the text field)
	 */
	public void setCurrentText(String aText) {
		SubtitleFile.Cue aC = cueAt(tde.timeLineStack.getTimePosMS());
		if(aC == null) {
			return;
		}
		String aT = aText == null ? "" : aText.replace("\r", "").strip();
		if(aT.isEmpty() || aT.equals(aC.text)) {
			return;
		}
		aC.text = aT;
		if(editor != null) {
			editor.fireShapesChanged();
		}
	}

	/**
	 * Text at aTimePos (time line), null if none. aEditing: without any subtitle loaded,
	 * the sample text (to place it)
	 */
	String textAt(long aTimePos,boolean aEditing) {
		synchronized (keys) {
			if(cues.size() > 0) {
				SubtitleFile.Cue aC = SubtitleFile.cueAt(cues, mediaTime(aTimePos));
				return aC == null ? null : aC.text;
			}
		}
		return aEditing ? sampleText() : null;
	}

	double frameRatio() {
		return tde.config.outRatio > 0 ? tde.config.outRatio : 16.0/9.0;
	}

	/**
	 * The shapes with the text at aTimePos (time line), in place: hidden without subtitle there
	 */
	void adapt(Vector<Shape> aShapes,long aTimePos,boolean aEditing) {
		String aText = textAt(aTimePos, aEditing);
		for(Shape aS : aShapes) {
			if(!aS.isText()) {
				continue;
			}
			if(aText == null) {
				aS.hidden = true;
				continue;
			}
			aS.hidden = false;
			if(!aText.equals(aS.text)) {
				aS.setTextKeepLines(aText, frameRatio());
			}
		}
	}

	/**
	 * Copies of the shapes of a key with the text aText (same line height, same bottom)
	 */
	Vector<Shape> withText(Vector<Shape> aShapes,String aText) {
		Vector<Shape> aCopy = Shape.copyAll(aShapes);
		for(Shape aS : aCopy) {
			if(aS.isText() && !aText.equals(aS.text)) {
				aS.setTextKeepLines(aText, frameRatio());
			}
		}
		return aCopy;
	}

	/**
	 * Shapes at aT (time of the keys): the keys take the text of that time before being
	 * interpolated, so that the height of a line is interpolated, not the height of the texts
	 * (the texts stored in the keys are those shown when they were edited)
	 */
	@Override
	Vector<Shape> interpolate(long aT) {
		String aText = textAt(aT+getClip().getKeysOriginMS(), true);
		if(aText == null) {
			aText = sampleText();
		}
		synchronized (keys) {
			for(int k = keys.size()-1;k >= 0;k--) {
				Key aKey = keys.elementAt(k);
				if(aKey.time <= aT || k == 0) {
					Vector<Shape> a0 = withText(aKey.shapes, aText);
					if(k == keys.size()-1 || aKey.time >= aT) {
						return a0;
					}
					Key aNext = keys.elementAt(k+1);
					Vector<Shape> a1 = withText(aNext.shapes, aText);
					double aF = (aT-aKey.time)/(double)(aNext.time-aKey.time);
					Vector<Shape> aShapes = new Vector<Shape>();
					for(Shape aS : a0) {
						aShapes.add(Shape.lerp(aS, Shape.findById(a1, aS.id), aF, frameRatio()));
					}
					return aShapes;
				}
			}
		}
		return new Vector<Shape>();
	}

	@Override
	public Vector<Shape> getShapesAt(long aTimePos) {
		Vector<Shape> aShapes = interpolate(aTimePos-getClip().getKeysOriginMS());
		adapt(aShapes, aTimePos, false);
		return aShapes;
	}

	@Override
	public int openProject(Vector<XmlObject> aOs, int o) throws Exception {
		Vector<Key> aKeys = new Vector<Key>();
		Vector<SubtitleFile.Cue> aCues = new Vector<SubtitleFile.Cue>();
		String aPath = null;
		for(;o < aOs.size();o++) {
			XmlObject aO = aOs.elementAt(o);
			if(aO instanceof XmlTag) {
				XmlTag aT = (XmlTag)aO;
				if("/Subtitles".equalsIgnoreCase(aT.tagName)) {
					break;
				}
				if("file".equalsIgnoreCase(aT.tagName)) {
					aPath = Shape.decode(aOs.elementAt(++o).text.trim());
				}
				if("cue".equalsIgnoreCase(aT.tagName)) {
					String[] aParts = aOs.elementAt(++o).text.trim().split(";", 3);
					if(aParts.length == 3) {
						aCues.add(new SubtitleFile.Cue(Long.parseLong(aParts[0]), Long.parseLong(aParts[1]), Shape.decode(aParts[2])));
					}
				}
				if("Key".equalsIgnoreCase(aT.tagName)) {
					aKeys.add(new Key());
				}
				if("time".equalsIgnoreCase(aT.tagName) && aKeys.size() > 0) {
					aKeys.lastElement().time = Long.parseLong(aOs.elementAt(++o).text.trim());
				}
				if("Shape".equalsIgnoreCase(aT.tagName)) {
					Shape aS = new Shape();
					o = aS.openProject(aOs, o);
					if(aKeys.size() <= 0) {
						aKeys.add(new Key());
					}
					aKeys.lastElement().shapes.add(aS);
				}
			}
		}
		if(aKeys.size() <= 0) {
			aKeys.add(new Key());
		}
		for(Key aK : aKeys) {
			if(aK.shapes.size() <= 0) {
				aK.shapes.add(createText());
			}
		}
		synchronized (keys) {
			keys.clear();
			keys.addAll(aKeys);
			cues = aCues;
			filePath = aPath;
		}
		return o;
	}

	@Override
	public void saveProject(StringBuffer aSB) throws Exception {
		aSB.append("						<Subtitles>\n");
		synchronized (keys) {
			if(filePath != null) {
				aSB.append("							<file>" + Shape.encode(filePath) + "</file>\n");
			}
			for(Key aK : keys) {
				aSB.append("							<Key>\n");
				aSB.append("								<time>" + aK.time + "</time>\n");
				for(Shape aS : aK.shapes) {
					aS.saveProject(aSB, "								");
				}
				aSB.append("							</Key>\n");
			}
			for(SubtitleFile.Cue aC : cues) {
				aSB.append("							<cue>" + aC.startMS + ";" + aC.endMS + ";" + Shape.encode(aC.text) + "</cue>\n");
			}
		}
		aSB.append("						</Subtitles>\n");
	}

	@Override
	public Image process2Swt(Media aMedia,int aProcessingMode,Rectangle aTargetSize,long aTime) {
		return null;
	}

	@Override
	public void process2Awt(Media aMedia,int aProcessingMode,long aTime) {
		try {
			//Always the size of the montage (the size of the image does not change with the subtitles)
			int aW = Math.max(1, (int)(tde.config.outRatio*tde.config.processingResValues[aProcessingMode]));
			int aH = Math.max(1, tde.config.processingResValues[aProcessingMode]);
			boolean aHdr = tde.config.processingHdr[aProcessingMode];
			Vector<Shape> aShapes = getShapesAt(aTime);
			aMedia.setWorkLeft(draw(aMedia.getWorkLeft(aProcessingMode,aTime), aShapes, aW, aH, aHdr, -1));
			aMedia.setWorkRight(draw(aMedia.getWorkRight(aProcessingMode,aTime), aShapes, aW, aH, aHdr, 1));
		}
		catch(Throwable t) {
			t.printStackTrace(System.err);
		}
	}

	BufferedImage draw(BufferedImage aBI,Vector<Shape> aShapes,int aW,int aH,boolean aHdr,int aEye) {
		BufferedImage aOut = com.cubaix.TDenlive.utils.ImageUtils.createImage(aW, aH, aHdr);
		Graphics2D aG = (Graphics2D)aOut.getGraphics();
		if(aBI != null) {
			aG.drawImage(aBI, 0, 0, null);
		}
		ShapeRenderer.draw(aG, aShapes, 0, 0, aW, aH, aEye);
		aG.dispose();
		return aOut;
	}

	//
	// Edition: the text shown now, its style and box are edited (no creation, no deletion)
	//

	@Override
	public ShapesEditor getEditor() {
		if(editor != null) {
			return editor;
		}
		editor = new ShapesEditor(new ShapesEditor.Source() {
			@Override
			public Vector<Shape> getShapes() {
				long aT = currentClipTime();
				if(aT < 0) {
					return null;
				}
				Vector<Shape> aShapes;
				if(autoKey) {
					aShapes = interpolate(aT);
				}
				else {
					//Manual key mode: the style of the key left of the time cursor
					synchronized (keys) {
						aShapes = Shape.copyAll(keys.elementAt(manualKey()).shapes);
					}
				}
				adapt(aShapes, tde.timeLineStack.getTimePosMS(), true);
				for(Shape aS : aShapes) {
					if(aS.hidden) {
						//No subtitle now: nothing to edit
						return null;
					}
				}
				return aShapes;
			}
			@Override
			public Vector<Shape> getDisplayedShapes() {
				//As drawn in the image: interpolated, with the text of the time
				long aT = currentClipTime();
				if(aT < 0) {
					return null;
				}
				Vector<Shape> aShapes = interpolate(aT);
				adapt(aShapes, tde.timeLineStack.getTimePosMS(), true);
				return aShapes;
			}
			@Override
			public Vector<Shape> getShapesForEdit() {
				if(getShapes() == null) {
					return null;
				}
				//Key at the time cursor (created if needed), its text takes the text shown,
				//so that the box edited is the one shown
				int aK = ensureCurrentKey();
				if(aK < 0) {
					return null;
				}
				Vector<Shape> aShapes = keys.elementAt(aK).shapes;
				adapt(aShapes, tde.timeLineStack.getTimePosMS(), true);
				return aShapes;
			}
			@Override
			public void addShape(Shape aS) {
			}
			@Override
			public void deleteShape(int aId) {
			}
			@Override
			public void setHidden(int aId, boolean aHidden) {
			}
			@Override
			public void reorder(int aId, boolean aToFront) {
			}
			@Override
			public boolean showGhosts() {
				return false;
			}
		});
		return editor;
	}

	/**
	 * Name of the subtitles file, null if none
	 */
	public String getFileName() {
		return filePath == null ? null : new File(filePath).getName();
	}
}
