package com.cubaix.TDenlive.GUI;

import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.Color;
import org.eclipse.swt.graphics.GC;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.graphics.RGB;
import org.eclipse.swt.graphics.Rectangle;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Canvas;
import org.eclipse.swt.widgets.ColorDialog;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Scale;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Spinner;

import com.cubaix.TDenlive.TDenlive;
import com.cubaix.TDenlive.medias.ColorBck;
import com.cubaix.TDenlive.model3d.Model3D;
import com.cubaix.TDenlive.model3d.Object3D;
import com.cubaix.TDenlive.model3d.Renderer3D;
import com.cubaix.TDenlive.utils.ImageUtils;
import com.cubaix.TDenlive.utils.StringUtils;

/**
 * Popup of the 3D modeler to choose the texture of the selected objects: preview of the main object
 * turning slowly (zoom slider and mouse wheel, 50 to 800 %), its two colors (the color of the object, and the second color of the procedural
 * textures), the library of the color clips (TextureLibrary; plain color = no texture), the
 * scale of the texture (%, logarithmic), its rotation, the repeats on a face (U / V, linked or not) and its
 * offset (% of a repeat). One row per face (one for an object without faces): "Modify..." (the textures in a popup:
 * library of textures, image or video of the disk, media of the media list; for the first face of several: to all
 * the faces or only to it), a thumbnail of its texture (a video: the image of the start of its range), its name and
 * where the texture comes from; a click on a row edits the settings of that face (again: all the faces).
 * open() returns an object holding the chosen color and texture (Object3D.copyTextureTo()), or null.
 */
public class Texture3DGUI {
	static final int PREVIEW_W = 300;
	static final int PREVIEW_H = 200;
	//Repeats: spinners in tenths
	static final int REPEAT_MAX = 1000;
	//Scale of the texture in %, on a logarithmic slider of SCALE_STEPS steps
	static final double SCALE_MIN = 10;
	static final double SCALE_MAX = 1000;
	static final int SCALE_STEPS = 400;
	//Zoom of the preview in %, on a logarithmic slider of ZOOM_STEPS steps
	static final double ZOOM_MIN = 50;
	static final double ZOOM_MAX = 800;
	static final int ZOOM_STEPS = 300;

	TDenlive tde = null;
	Shell shell = null;
	Object3D obj = null;
	Object3D result = null;
	Model3D previewModel = null;
	Canvas preview = null;
	Image previewImg = null;
	double previewAngle = -30;
	double previewZoom = 100;
	Scale zoom = null;
	Label zoomValue = null;
	Runnable spin = null;
	Button[] colorButtons = new Button[2];
	Image[] colorImages = new Image[2];
	Scale scale = null;
	Label scaleValue = null;
	Button scaleReset = null;
	Scale rotation = null;
	Label rotationValue = null;
	Button rotationReset = null;
	Spinner[] repeats = new Spinner[2];
	Scale[] offsets = new Scale[2];
	Label[] offsetValues = new Label[2];
	Button[] links = new Button[2];
	boolean updating = false;
	//Face edited (Object3D.faceNames()), -1 = all faces
	int face = -1;
	//Settings of the texture of the face edited (Object3D.texParams()), stored by commit()
	Object3D.TexParams cp = null;
	//Rows of the faces (Modify, thumbnail of the texture, name, source), the label of the settings edited
	Composite facesBox = null;
	java.util.Vector<FaceRow> faceRows = new java.util.Vector<FaceRow>();
	Label editedLabel = null;
	static final int FACE_THUMB = 40;

	class FaceRow {
		int face;
		String name;
		Composite row;
		Label thumb;
		Image img;
		Label src;
	}
	//Video texture: range of the media time looped (Object3D.texStartMS / texEndMS), shown only for a video
	Composite rangeRow = null;
	RangeSlider range = null;
	Label rangeValue = null;
	//>= 0: media time shown by the preview (the thumb last moved), else the start of the range
	long previewTexMS = -1;

	/**
	 * aObj: the main selected object (not modified)
	 */
	public Texture3DGUI(TDenlive aTDe, Object3D aObj) {
		tde = aTDe;
		obj = aObj.copy();
		cp = Object3D.TexParams.of(obj);
		createContents();
	}

	String l(String aKey) {
		return tde.gui.lngs.get(aKey);
	}

	public Object3D open() {
		shell.pack();
		Rectangle aP = tde.gui.shell.getBounds();
		Rectangle aS = shell.getBounds();
		shell.setLocation(aP.x + (aP.width - aS.width) / 2, aP.y + (aP.height - aS.height) / 2);
		shell.open();
		//The preview turns slowly: all the faces are seen
		spin = () -> {
			if(preview == null || preview.isDisposed()) {
				return;
			}
			previewAngle += 2;
			redrawPreview();
			tde.gui.display.timerExec(80, spin);
		};
		tde.gui.display.timerExec(80, spin);
		while (!shell.isDisposed()) {
			if (!tde.gui.display.readAndDispatch()) {
				tde.gui.display.sleep();
			}
		}
		return result;
	}

	void createContents() {
		shell = new Shell(tde.gui.shell, SWT.DIALOG_TRIM | SWT.APPLICATION_MODAL);
		shell.setText(l("Texture3D.Title"));
		shell.setLayout(new GridLayout(1, false));
		tde.gui.applyColorFont(shell);
		shell.setImage(tde.gui.imgsSwt.getIcon("3Denlive32.png"));
		shell.addDisposeListener(e -> {
			for(Image aI : colorImages) {
				if(aI != null) {
					aI.dispose();
				}
			}
			if(previewImg != null) {
				previewImg.dispose();
			}
			for(FaceRow aR : faceRows) {
				if(aR.img != null) {
					aR.img.dispose();
				}
			}
		});

		preview = new Canvas(shell, SWT.BORDER | SWT.DOUBLE_BUFFERED);
		GridData aPGD = new GridData(GridData.HORIZONTAL_ALIGN_CENTER);
		aPGD.widthHint = PREVIEW_W;
		aPGD.heightHint = PREVIEW_H;
		preview.setLayoutData(aPGD);
		preview.addPaintListener(e -> {
			Rectangle aR = preview.getClientArea();
			if(aR.width <= 0 || aR.height <= 0) {
				return;
			}
			if(previewImg == null) {
				previewImg = ImageUtils.imageToSwt(tde.gui.display, renderPreview(aR.width, aR.height));
			}
			e.gc.drawImage(previewImg, 0, 0);
		});

		//Zoom of the preview (also with the mouse wheel on it), then a reset to 100 %
		Composite aZoomRow = new Composite(shell, SWT.NONE);
		GridData aZRGD = new GridData(GridData.HORIZONTAL_ALIGN_CENTER);
		aZRGD.widthHint = PREVIEW_W;
		aZoomRow.setLayoutData(aZRGD);
		tde.gui.applyColorFont(aZoomRow);
		GridLayout aZGL = new GridLayout(4, false);
		tde.gui.applyNoMargin(aZGL);
		aZoomRow.setLayout(aZGL);
		Label aZoomL = new Label(aZoomRow, SWT.NONE);
		aZoomL.setText(l("Texture3D.Zoom"));
		tde.gui.applyColorFont(aZoomL);
		zoom = new Scale(aZoomRow, SWT.HORIZONTAL);
		zoom.setMinimum(0);
		zoom.setMaximum(ZOOM_STEPS);
		zoom.setIncrement(1);
		zoom.setPageIncrement(15);
		zoom.setLayoutData(new GridData(GridData.FILL_HORIZONTAL));
		zoom.addListener(SWT.Selection, e -> setZoom(sliderToZoom(zoom.getSelection())));
		zoomValue = new Label(aZoomRow, SWT.RIGHT);
		GridData aZVGD = new GridData();
		aZVGD.widthHint = 50;
		zoomValue.setLayoutData(aZVGD);
		tde.gui.applyColorFont(zoomValue);
		Button aZoomReset = new Button(aZoomRow, SWT.PUSH);
		aZoomReset.setText("100 %");
		tde.gui.applyColorFont(aZoomReset);
		aZoomReset.addListener(SWT.Selection, e -> setZoom(100));
		preview.addListener(SWT.MouseWheel, e -> setZoom(previewZoom*Math.pow(1.1, e.count > 0 ? 1 : -1)));
		setZoom(100);

		//Colors: of the object, second color of the procedural textures
		Composite aColors = new Composite(shell, SWT.NONE);
		aColors.setLayoutData(new GridData(GridData.FILL_HORIZONTAL));
		tde.gui.applyColorFont(aColors);
		GridLayout aCGL = new GridLayout(2, false);
		tde.gui.applyNoMargin(aCGL);
		aColors.setLayout(aCGL);
		for(int c = 0;c < 2;c++) {
			final int aC = c;
			Button aB = new Button(aColors, SWT.PUSH);
			aB.setText(l(c == 0 ? "ColorBck.Color1" : "ColorBck.Color2"));
			tde.gui.applyColorFont(aB);
			aB.addListener(SWT.Selection, e -> chooseColor(aC));
			colorButtons[c] = aB;
		}

		//The faces and their textures: one row per face (one for an object without faces)
		Label aFacesTitle = new Label(shell, SWT.NONE);
		aFacesTitle.setText(l("Texture3D.FacesTitle"));
		tde.gui.applyColorFont(aFacesTitle);
		aFacesTitle.setFont(tde.gui.fontsSwt.robotoBold);
		facesBox = new Composite(shell, SWT.NONE);
		facesBox.setLayoutData(new GridData(GridData.FILL_HORIZONTAL));
		tde.gui.applyColorFont(facesBox);
		GridLayout aFBL = new GridLayout(1, false);
		tde.gui.applyNoMargin(aFBL);
		aFBL.verticalSpacing = 2;
		facesBox.setLayout(aFBL);
		String[] aFaces = Object3D.faceNames(obj.type);
		if(aFaces.length > 1) {
			for(int f = 0;f < aFaces.length;f++) {
				addFaceRow(f, l("Texture3D.Face_" + aFaces[f]));
			}
		}
		else {
			addFaceRow(-1, l("Texture3D.ObjectRow"));
		}
		//Settings below: of the face chosen, or of all the faces
		editedLabel = new Label(shell, SWT.NONE);
		editedLabel.setLayoutData(new GridData(GridData.FILL_HORIZONTAL));
		tde.gui.applyColorFont(editedLabel);
		editedLabel.setFont(tde.gui.fontsSwt.robotoBold);

		//Repeats and offset
		Composite aTiles = new Composite(shell, SWT.NONE);
		aTiles.setLayoutData(new GridData(GridData.FILL_HORIZONTAL));
		tde.gui.applyColorFont(aTiles);
		GridLayout aTGL = new GridLayout(4, false);
		tde.gui.applyNoMargin(aTGL);
		aTiles.setLayout(aTGL);
		//Scale: the size of the texture on the object, then a reset to 100 %
		Label aScaleL = new Label(aTiles, SWT.NONE);
		aScaleL.setText(l("Texture3D.Scale"));
		tde.gui.applyColorFont(aScaleL);
		scale = new Scale(aTiles, SWT.HORIZONTAL);
		scale.setMinimum(0);
		scale.setMaximum(SCALE_STEPS);
		scale.setIncrement(1);
		scale.setPageIncrement(20);
		GridData aSclGD = new GridData(GridData.FILL_HORIZONTAL);
		aSclGD.widthHint = 240;
		scale.setLayoutData(aSclGD);
		scale.setSelection(scaleToSlider(cp.scale));
		scale.addListener(SWT.Selection, e -> {
			cp.scale = sliderToScale(scale.getSelection());
			commit();
			updateScaleLabel();
			redrawPreview();
		});
		scaleValue = new Label(aTiles, SWT.RIGHT);
		GridData aSVGD = new GridData();
		aSVGD.widthHint = 50;
		scaleValue.setLayoutData(aSVGD);
		tde.gui.applyColorFont(scaleValue);
		scaleReset = new Button(aTiles, SWT.PUSH);
		scaleReset.setText("100 %");
		tde.gui.applyColorFont(scaleReset);
		scaleReset.addListener(SWT.Selection, e -> {
			cp.scale = 100;
			commit();
			scale.setSelection(scaleToSlider(100));
			updateScaleLabel();
			redrawPreview();
		});

		//Rotation of the texture, -180..180 degrees, then a reset to 0
		Label aRotL = new Label(aTiles, SWT.NONE);
		aRotL.setText(l("ColorBck.Rotation"));
		tde.gui.applyColorFont(aRotL);
		rotation = new Scale(aTiles, SWT.HORIZONTAL);
		rotation.setMinimum(0);
		rotation.setMaximum(360);
		rotation.setIncrement(1);
		rotation.setPageIncrement(15);
		GridData aRotGD = new GridData(GridData.FILL_HORIZONTAL);
		aRotGD.widthHint = 240;
		rotation.setLayoutData(aRotGD);
		rotation.setSelection((int)Math.round(cp.rot) + 180);
		rotation.addListener(SWT.Selection, e -> {
			cp.rot = rotation.getSelection() - 180;
			commit();
			updateScaleLabel();
			redrawPreview();
		});
		rotationValue = new Label(aTiles, SWT.RIGHT);
		GridData aRVGD = new GridData();
		aRVGD.widthHint = 50;
		rotationValue.setLayoutData(aRVGD);
		tde.gui.applyColorFont(rotationValue);
		rotationReset = new Button(aTiles, SWT.PUSH);
		rotationReset.setText("0°");
		tde.gui.applyColorFont(rotationReset);
		rotationReset.addListener(SWT.Selection, e -> {
			cp.rot = 0;
			commit();
			rotation.setSelection(180);
			updateScaleLabel();
			redrawPreview();
		});

		String[] aKeys = {"Texture3D.RepeatU", "Texture3D.RepeatV", "Texture3D.OffsetU", "Texture3D.OffsetV"};
		for(int s = 0;s < 4;s++) {
			Label aL = new Label(aTiles, SWT.NONE);
			aL.setText(l(aKeys[s]));
			tde.gui.applyColorFont(aL);
			final int aIdx = s;
			if(s < 2) {
				Spinner aSp = new Spinner(aTiles, SWT.BORDER);
				aSp.setDigits(1);
				aSp.setValues((int)Math.round((s == 0 ? cp.repU : cp.repV)*10), 1, REPEAT_MAX, 1, 1, 10);
				tde.gui.applyColorFont(aSp);
				GridData aSGD = new GridData();
				aSGD.horizontalSpan = 2;
				aSp.setLayoutData(aSGD);
				aSp.addListener(SWT.Modify, e -> {
					if(!updating) {
						changed(aIdx);
					}
				});
				repeats[s] = aSp;
			}
			else {
				Scale aSc = new Scale(aTiles, SWT.HORIZONTAL);
				aSc.setMinimum(0);
				aSc.setMaximum(100);
				aSc.setIncrement(1);
				aSc.setPageIncrement(10);
				aSc.setSelection((int)Math.round(s == 2 ? cp.offU : cp.offV));
				GridData aScGD = new GridData(GridData.FILL_HORIZONTAL);
				aScGD.widthHint = 240;
				aSc.setLayoutData(aScGD);
				aSc.addListener(SWT.Selection, e -> changed(aIdx));
				offsets[s-2] = aSc;
				Label aV = new Label(aTiles, SWT.RIGHT);
				GridData aVGD = new GridData();
				aVGD.widthHint = 50;
				aV.setLayoutData(aVGD);
				tde.gui.applyColorFont(aV);
				offsetValues[s-2] = aV;
			}
			if(s % 2 == 0) {
				Button aLink = new Button(aTiles, SWT.CHECK);
				aLink.setText(l("Texture3D.Link"));
				GridData aLGD = new GridData(GridData.VERTICAL_ALIGN_CENTER);
				aLGD.verticalSpan = 2;
				aLink.setLayoutData(aLGD);
				tde.gui.applyColorFont(aLink);
				aLink.setSelection(s == 0 ? cp.repU == cp.repV : cp.offU == cp.offV);
				aLink.addListener(SWT.Selection, e -> {
					if(aLink.getSelection()) {
						changed(aIdx);
					}
				});
				links[s/2] = aLink;
			}
		}

		//Video texture: start / end of the range looped, the preview showing the image of the thumb moved
		rangeRow = new Composite(shell, SWT.NONE);
		rangeRow.setLayoutData(new GridData(GridData.FILL_HORIZONTAL));
		tde.gui.applyColorFont(rangeRow);
		GridLayout aRGL = new GridLayout(4, false);
		tde.gui.applyNoMargin(aRGL);
		rangeRow.setLayout(aRGL);
		Label aRangeL = new Label(rangeRow, SWT.NONE);
		aRangeL.setText(l("Texture3D.VideoRange"));
		tde.gui.applyColorFont(aRangeL);
		range = new RangeSlider(tde, rangeRow, (aThumb, aDragging) -> {
			cp.startMS = range.getStart();
			cp.endMS = range.getEnd() >= range.getMax() ? -1 : range.getEnd();
			commit();
			previewTexMS = aThumb == RangeSlider.START ? range.getStart() : Math.max(range.getStart(), range.getEnd()-1);
			updateRangeLabel();
			redrawPreview();
		});
		range.setToolTipText(l("Texture3D.VideoRangeTip"));
		GridData aRSGD = new GridData(GridData.FILL_HORIZONTAL);
		aRSGD.widthHint = 240;
		aRSGD.heightHint = 20;
		range.setLayoutData(aRSGD);
		rangeValue = new Label(rangeRow, SWT.CENTER);
		tde.gui.applyColorFont(rangeValue);
		rangeValue.setText(StringUtils.time2Clock(0) + " - " + StringUtils.time2Clock(0));
		rangeValue.setLayoutData(new GridData(rangeValue.computeSize(SWT.DEFAULT, SWT.DEFAULT).x + 8, SWT.DEFAULT));
		Button aWhole = new Button(rangeRow, SWT.PUSH);
		aWhole.setText(l("Texture3D.WholeVideo"));
		tde.gui.applyColorFont(aWhole);
		aWhole.addListener(SWT.Selection, e -> {
			cp.startMS = 0;
			cp.endMS = -1;
			commit();
			previewTexMS = -1;
			refreshWidgets();
		});

		Composite aButtons = new Composite(shell, SWT.NONE);
		aButtons.setLayoutData(new GridData(GridData.HORIZONTAL_ALIGN_END));
		tde.gui.applyColorFont(aButtons);
		GridLayout aBGL = new GridLayout(2, true);
		tde.gui.applyNoMargin(aBGL);
		aButtons.setLayout(aBGL);
		Button aCancel = new Button(aButtons, SWT.PUSH);
		aCancel.setText(l("ColorBck.Cancel"));
		tde.gui.applyColorFont(aCancel);
		aCancel.setLayoutData(new GridData(GridData.FILL_HORIZONTAL));
		aCancel.addListener(SWT.Selection, e -> shell.close());
		Button aOK = new Button(aButtons, SWT.PUSH);
		aOK.setText(l("Texture3D.Apply"));
		aOK.setImage(tde.gui.imgsSwt.getIcon("checked.gif"));
		tde.gui.applyColorFont(aOK);
		aOK.setLayoutData(new GridData(GridData.FILL_HORIZONTAL));
		aOK.addListener(SWT.Selection, e -> {
			result = obj;
			shell.close();
		});
		shell.setDefaultButton(aOK);

		refreshWidgets();
	}

	/**
	 * The two colors of the object, for the thumbnails of the procedural textures
	 */
	ColorBck.Fill colorsFill() {
		ColorBck.Fill aF = new ColorBck.Fill();
		aF.color = new java.awt.Color(obj.color);
		aF.color2 = new java.awt.Color(obj.color2);
		return aF;
	}

	void chooseColor(int aC) {
		int aCol = aC == 0 ? obj.color : obj.color2;
		ColorDialog aCD = new ColorDialog(shell);
		aCD.setRGB(new RGB((aCol >> 16) & 0xFF, (aCol >> 8) & 0xFF, aCol & 0xFF));
		RGB aRGB = aCD.open();
		if(aRGB == null) {
			return;
		}
		aCol = (aRGB.red << 16) | (aRGB.green << 8) | aRGB.blue;
		if(aC == 0) {
			obj.color = aCol;
		}
		else {
			obj.color2 = aCol;
		}
		refreshWidgets();
	}

	void changed(int aIdx) {
		if(aIdx < 2) {
			double aV = repeats[aIdx].getSelection()/10.0;
			if(aIdx == 0) {
				cp.repU = aV;
			}
			else {
				cp.repV = aV;
			}
			if(links[0].getSelection()) {
				cp.repU = cp.repV = aV;
				updating = true;
				repeats[1-aIdx].setSelection(repeats[aIdx].getSelection());
				updating = false;
			}
		}
		else {
			int aV = offsets[aIdx-2].getSelection();
			if(aIdx == 2) {
				cp.offU = aV;
			}
			else {
				cp.offV = aV;
			}
			if(links[1].getSelection()) {
				cp.offU = cp.offV = aV;
				offsets[3-aIdx].setSelection(aV);
			}
			updateOffsetLabels();
		}
		commit();
		redrawPreview();
	}

	/**
	 * The settings edited to the face edited (its own from then on), or to the object for all faces
	 */
	void commit() {
		if(face < 0) {
			cp.applyTo(obj);
		}
		else {
			obj.setFaceParams(face, cp.copy());
		}
	}

	/**
	 * Sliders, spinners and links set from the settings edited (another face)
	 */
	void loadWidgets() {
		updating = true;
		try {
			scale.setSelection(scaleToSlider(cp.scale));
			rotation.setSelection((int)Math.round(cp.rot) + 180);
			repeats[0].setSelection((int)Math.round(cp.repU*10));
			repeats[1].setSelection((int)Math.round(cp.repV*10));
			offsets[0].setSelection((int)Math.round(cp.offU));
			offsets[1].setSelection((int)Math.round(cp.offV));
			links[0].setSelection(cp.repU == cp.repV);
			links[1].setSelection(cp.offU == cp.offV);
		}
		finally {
			updating = false;
		}
	}

	static int zoomToSlider(double aZoom) {
		return (int)Math.round(ZOOM_STEPS*Math.log(aZoom/ZOOM_MIN)/Math.log(ZOOM_MAX/ZOOM_MIN));
	}

	static double sliderToZoom(int aV) {
		return Math.round(ZOOM_MIN*Math.pow(ZOOM_MAX/ZOOM_MIN, aV/(double)ZOOM_STEPS));
	}

	/**
	 * Zoom of the preview in % (the camera comes closer to the object)
	 */
	void setZoom(double aZoom) {
		previewZoom = Math.max(ZOOM_MIN, Math.min(ZOOM_MAX, aZoom));
		if(zoom.getSelection() != zoomToSlider(previewZoom)) {
			zoom.setSelection(zoomToSlider(previewZoom));
		}
		zoomValue.setText(Math.round(previewZoom) + " %");
		redrawPreview();
	}

	static int scaleToSlider(double aScale) {
		double aS = Math.max(SCALE_MIN, Math.min(SCALE_MAX, aScale));
		return (int)Math.round(SCALE_STEPS*Math.log(aS/SCALE_MIN)/Math.log(SCALE_MAX/SCALE_MIN));
	}

	static double sliderToScale(int aV) {
		//Rounded to the % shown
		return Math.round(SCALE_MIN*Math.pow(SCALE_MAX/SCALE_MIN, aV/(double)SCALE_STEPS));
	}

	void updateScaleLabel() {
		scaleValue.setText(Math.round(cp.scale) + " %");
		rotationValue.setText(Math.round(cp.rot) + "°");
	}

	void updateOffsetLabels() {
		offsetValues[0].setText(Math.round(cp.offU) + " %");
		offsetValues[1].setText(Math.round(cp.offV) + " %");
	}

	void refreshWidgets() {
		boolean aTextured = obj.hasAnyTexture();
		//The images have their own colors; the second color is that of the procedural textures
		boolean aProcedural = false;
		for(String aT : obj.textures()) {
			aProcedural |= aT != null && !ColorBck.Fill.PLAIN.equals(aT) && !ColorBck.Fill.isBitmap(aT) && !ColorBck.Fill.isFile(aT)
					&& !com.cubaix.TDenlive.model3d.Texture3D.isMedia(aT);
		}
		colorButtons[1].setEnabled(aProcedural);
		for(Spinner aSp : repeats) {
			aSp.setEnabled(aTextured);
		}
		scale.setEnabled(aTextured);
		scaleReset.setEnabled(aTextured);
		rotation.setEnabled(aTextured);
		rotationReset.setEnabled(aTextured);
		updateScaleLabel();
		for(Scale aSc : offsets) {
			aSc.setEnabled(aTextured);
		}
		for(Button aL : links) {
			aL.setEnabled(aTextured);
		}
		for(int c = 0;c < 2;c++) {
			int aCol = c == 0 ? obj.color : obj.color2;
			if(colorImages[c] != null) {
				colorButtons[c].setImage(null);
				colorImages[c].dispose();
			}
			colorImages[c] = new Image(tde.gui.display, 14, 12);
			GC aGC = new GC(colorImages[c]);
			Color aSwt = new Color((aCol >> 16) & 0xFF, (aCol >> 8) & 0xFF, aCol & 0xFF);
			aGC.setBackground(aSwt);
			aGC.fillRectangle(0, 0, 14, 12);
			aGC.setForeground(tde.gui.colorsSwt.DARK_GRAY);
			aGC.drawRectangle(0, 0, 13, 11);
			aGC.dispose();
			colorButtons[c].setImage(colorImages[c]);
		}
		colorButtons[0].getParent().layout();
		updateOffsetLabels();
		refreshFaces();
		updateRange();
		redrawPreview();
	}

	/**
	 * Row of the face aFace (-1: the object without faces): Modify, thumbnail, name, source; a click on it edits
	 * the settings of that face
	 */
	void addFaceRow(int aFace, String aName) {
		FaceRow aR = new FaceRow();
		aR.face = aFace;
		aR.name = aName;
		aR.row = new Composite(facesBox, SWT.NONE);
		aR.row.setLayoutData(new GridData(GridData.FILL_HORIZONTAL));
		tde.gui.applyColorFont(aR.row);
		GridLayout aGL = new GridLayout(4, false);
		aGL.marginWidth = 3;
		aGL.marginHeight = 2;
		aR.row.setLayout(aGL);
		Button aModify = new Button(aR.row, SWT.PUSH);
		aModify.setText(l("Texture3D.Modify"));
		aModify.setToolTipText(l("Texture3D.ModifyTip"));
		tde.gui.applyColorFont(aModify);
		aModify.addListener(SWT.Selection, e -> modify(aR));
		aR.thumb = new Label(aR.row, SWT.BORDER);
		GridData aTGD = new GridData();
		aTGD.widthHint = aTGD.heightHint = FACE_THUMB + 2;
		aR.thumb.setLayoutData(aTGD);
		Label aN = new Label(aR.row, SWT.NONE);
		aN.setText(aName);
		tde.gui.applyColorFont(aN);
		aN.setFont(tde.gui.fontsSwt.robotoBold);
		GridData aNGD = new GridData();
		aNGD.widthHint = 80;
		aN.setLayoutData(aNGD);
		//A long path on two lines
		aR.src = new Label(aR.row, SWT.WRAP);
		GridData aSGD = new GridData(GridData.FILL_HORIZONTAL);
		aSGD.widthHint = 300;
		aR.src.setLayoutData(aSGD);
		tde.gui.applyColorFont(aR.src);
		if(aFace >= 0) {
			for(org.eclipse.swt.widgets.Control aC : new org.eclipse.swt.widgets.Control[] {aR.row, aR.thumb, aN, aR.src}) {
				aC.addListener(SWT.MouseDown, e -> selectFace(aR.face, true));
				aC.setToolTipText(l("Texture3D.FaceRowTip"));
			}
		}
		faceRows.add(aR);
	}

	/**
	 * The settings below edit the face aFace (its own from the first change), -1 all the faces; aToggle: a click
	 * on the face already edited goes back to all the faces
	 */
	void selectFace(int aFace, boolean aToggle) {
		face = aToggle && aFace == face ? -1 : aFace;
		previewTexMS = -1;
		cp = obj.texParams(face).copy();
		loadWidgets();
		refreshWidgets();
	}

	/**
	 * Another texture for the face of the row: chosen in the library of textures, an image or a video of the
	 * disk, or a media of the media list; for the first face of several, to all the faces or only to it
	 */
	void modify(FaceRow aR) {
		if(aR.face >= 0) {
			selectFace(aR.face, false);
		}
		String aCur = aR.face < 0 ? obj.texture : obj.faceTexture(aR.face);
		String aT = chooseTexture(aR.name, aCur);
		if(aT == null) {
			return;
		}
		boolean aAll = aR.face < 0;
		if(aR.face == 0 && faceRows.size() > 1) {
			int aAnswer = askAllFaces(aR.name);
			if(aAnswer < 0) {
				return;
			}
			aAll = aAnswer == 1;
		}
		applyTexture(aR.face, aT, aAll);
	}

	/**
	 * aT on the face aFace, or on all the faces (the texture of the object, the own textures of the faces removed,
	 * their settings kept)
	 */
	void applyTexture(int aFace, String aT, boolean aAll) {
		String aOld = aAll ? obj.texture : obj.faceTexture(aFace);
		if(aAll) {
			obj.texture = aT;
			for(int f = 0;f < Object3D.faceNames(obj.type).length;f++) {
				obj.setFaceTexture(f, null);
			}
			face = faceRows.size() > 1 ? -1 : face;
		}
		else {
			obj.setFaceTexture(aFace, aT);
			face = aFace;
		}
		cp = obj.texParams(face).copy();
		//Another video: looped whole at first
		if(com.cubaix.TDenlive.model3d.Texture3D.isMoving(aT) && !aT.equals(aOld)) {
			cp.startMS = 0;
			cp.endMS = -1;
			commit();
		}
		previewTexMS = -1;
		loadWidgets();
		refreshWidgets();
	}

	/**
	 * Popup of the textures (library of textures, image or video of the disk, media of the media list) for aName,
	 * aCurrent shown selected; the texture clicked, null if canceled
	 */
	String chooseTexture(String aName, String aCurrent) {
		Shell aS = new Shell(shell, SWT.DIALOG_TRIM | SWT.APPLICATION_MODAL);
		aS.setText(l("Texture3D.ChooseTitle").replace("%s", aName));
		aS.setLayout(new GridLayout(1, false));
		tde.gui.applyColorFont(aS);
		final String[] aRes = {null};
		new TextureLibrary(tde, aS, aCurrent, () -> colorsFill(), aT -> {
			aRes[0] = aT;
			aS.close();
		}, true);
		Button aCancel = new Button(aS, SWT.PUSH);
		aCancel.setText(l("ColorBck.Cancel"));
		tde.gui.applyColorFont(aCancel);
		aCancel.setLayoutData(new GridData(GridData.HORIZONTAL_ALIGN_END));
		aCancel.addListener(SWT.Selection, e -> aS.close());
		centerAndRun(aS);
		return aRes[0];
	}

	/**
	 * 1: all the faces, 0: only the face aName, -1: canceled
	 */
	int askAllFaces(String aName) {
		Shell aS = new Shell(shell, SWT.DIALOG_TRIM | SWT.APPLICATION_MODAL);
		aS.setText(l("Texture3D.Title"));
		aS.setLayout(new GridLayout(1, false));
		tde.gui.applyColorFont(aS);
		Label aQ = new Label(aS, SWT.WRAP);
		aQ.setText(l("Texture3D.AllFacesQuestion").replace("%s", aName));
		tde.gui.applyColorFont(aQ);
		Composite aBs = new Composite(aS, SWT.NONE);
		aBs.setLayoutData(new GridData(GridData.HORIZONTAL_ALIGN_END));
		tde.gui.applyColorFont(aBs);
		aBs.setLayout(new GridLayout(3, false));
		final int[] aRes = {-1};
		String[] aTexts = {l("Texture3D.ApplyAllFaces"), l("Texture3D.ApplyOnlyFace").replace("%s", aName), l("ColorBck.Cancel")};
		int[] aVals = {1, 0, -1};
		for(int b = 0;b < 3;b++) {
			Button aB = new Button(aBs, SWT.PUSH);
			aB.setText(aTexts[b]);
			tde.gui.applyColorFont(aB);
			final int aV = aVals[b];
			aB.addListener(SWT.Selection, e -> {
				aRes[0] = aV;
				aS.close();
			});
			if(b == 0) {
				aS.setDefaultButton(aB);
			}
		}
		centerAndRun(aS);
		return aRes[0];
	}

	/**
	 * A popup of this window centered on it, until it is closed
	 */
	void centerAndRun(Shell aS) {
		aS.pack();
		Rectangle aP = shell.getBounds();
		Rectangle aB = aS.getBounds();
		aS.setLocation(aP.x + (aP.width - aB.width) / 2, aP.y + (aP.height - aB.height) / 2);
		aS.open();
		while(!aS.isDisposed()) {
			if(!tde.gui.display.readAndDispatch()) {
				tde.gui.display.sleep();
			}
		}
	}

	/**
	 * Thumbnails and sources of the faces, the face edited highlighted, the label of the settings
	 */
	void refreshFaces() {
		ColorBck.Fill aColors = colorsFill();
		for(FaceRow aR : faceRows) {
			if(aR.row.isDisposed()) {
				continue;
			}
			String aT = aR.face < 0 ? obj.texture : obj.faceTexture(aR.face);
			if(aR.img != null) {
				aR.thumb.setImage(null);
				aR.img.dispose();
			}
			//A video: the image of the start of the range of the face
			long aStart = com.cubaix.TDenlive.model3d.Texture3D.isMoving(aT) ? Math.max(0, obj.texParams(aR.face).startMS) : -1;
			aR.img = ImageUtils.imageToSwt(tde.gui.display, TextureLibrary.thumbOf(tde, aT, aColors, FACE_THUMB, aStart));
			aR.thumb.setImage(aR.img);
			String aSrc = TextureLibrary.sourceOf(tde, aT);
			aR.src.setText(aSrc);
			aR.src.setToolTipText(aSrc);
			boolean aEdited = aR.face >= 0 && aR.face == face;
			Color aBg = aEdited ? tde.gui.colorsSwt.PINK_L92 : shell.getBackground();
			aR.row.setBackground(aBg);
			for(org.eclipse.swt.widgets.Control aC : aR.row.getChildren()) {
				if(!(aC instanceof Button)) {
					aC.setBackground(aBg);
				}
			}
		}
		if(editedLabel != null && !editedLabel.isDisposed()) {
			String aName = null;
			for(FaceRow aR : faceRows) {
				if(aR.face >= 0 && aR.face == face) {
					aName = aR.name;
				}
			}
			editedLabel.setText(faceRows.size() <= 1 ? l("Texture3D.SettingsTitle")
					: aName == null ? l("Texture3D.SettingsAll") : l("Texture3D.SettingsFace").replace("%s", aName));
		}
		facesBox.layout(true, true);
	}


	/**
	 * Texture of the face edited (that of the object for all faces)
	 */
	String editedTexture() {
		return face < 0 ? obj.texture : obj.faceTexture(face);
	}

	/**
	 * Range row shown only when the texture edited is a video (of a known duration), its thumbs set from the object
	 */
	void updateRange() {
		if(range == null) {
			return;
		}
		long aDur = com.cubaix.TDenlive.model3d.Texture3D.durationMS(editedTexture());
		boolean aShow = aDur > 0;
		if(aShow) {
			if(!range.isDragging()) {
				range.setValues(aDur, cp.startMS, cp.endMS < 0 ? aDur : cp.endMS, Math.min(aDur, 40));
			}
			updateRangeLabel();
		}
		if(rangeRow.getVisible() != aShow) {
			rangeRow.setVisible(aShow);
			((GridData)rangeRow.getLayoutData()).exclude = !aShow;
			if(shell.isVisible()) {
				shell.pack();
			}
			else {
				shell.layout(true, true);
			}
		}
	}

	void updateRangeLabel() {
		rangeValue.setText(StringUtils.time2Clock(range.getStart()) + " - " + StringUtils.time2Clock(range.getEnd()));
	}

	/**
	 * The object alone, centered, turned by previewAngle, framed
	 */
	java.awt.image.BufferedImage renderPreview(int aW, int aH) {
		if(previewModel == null) {
			previewModel = new Model3D();
			previewModel.pitch = 20;
		}
		Object3D aO = obj.copy();
		aO.id = 0;
		aO.parent = -1;
		aO.px = aO.py = aO.pz = 0;
		aO.rx = aO.ry = aO.rz = 0;
		if(aO.isGroup()) {
			aO.type = Object3D.CUBE;
		}

		previewModel.objects.clear();
		previewModel.objects.add(aO);
		previewModel.lastId = 0;
		previewModel.yaw = 0;
		//Framed unturned, on its bounding sphere: the same size all around
		previewModel.frame(null, aW/(double)aH);
		previewModel.distance *= 1.1*100/previewZoom;
		//A face chosen (not a round side): turned toward the camera, swaying a little; else all around
		double[] aN = face >= 0 ? faceNormal(aO, face) : null;
		if(aN != null) {
			double aEl = Math.toRadians(previewModel.pitch);
			double[] aToCam = {0, Math.sin(aEl), Math.cos(aEl)};
			double aDot = Math.max(-1, Math.min(1, com.cubaix.TDenlive.model3d.Math3D.dot(aN, aToCam)));
			double[] aAxis = com.cubaix.TDenlive.model3d.Math3D.cross(aN, aToCam);
			double[] aAlign;
			if(com.cubaix.TDenlive.model3d.Math3D.length(aAxis) < 1e-9) {
				aAlign = aDot > 0 ? com.cubaix.TDenlive.model3d.Math3D.euler(0, 0, 0) : com.cubaix.TDenlive.model3d.Math3D.euler(0, 180, 0);
			}
			else {
				aAlign = com.cubaix.TDenlive.model3d.Math3D.rotation(aAxis, Math.acos(aDot));
			}
			double aSway = 30*Math.sin(Math.toRadians(previewAngle*1.5));
			aO.setRotation(com.cubaix.TDenlive.model3d.Math3D.mulM(com.cubaix.TDenlive.model3d.Math3D.euler(0, aSway, 0), aAlign));
		}
		else {
			aO.ry = previewAngle;
		}
		Renderer3D.Options aOpt = new Renderer3D.Options();
		//Preview: no supersampling
		aOpt.ss = 1;
		aOpt.bck = 0xFF505050;
		//Video texture: the image of the thumb moved, else the start of its range
		aOpt.texTimeMS = previewTexMS;
		return Renderer3D.render(previewModel, aW, aH, 0, 0, aOpt).image;
	}

	/**
	 * Mean normal of the face aF of the object (its scale applied, not its rotation), null for a round side or
	 * an object without faces
	 */
	static double[] faceNormal(Object3D aO, int aF) {
		com.cubaix.TDenlive.model3d.Mesh3D aMesh = aO.mesh();
		if(aMesh == null || aMesh.triFace == null) {
			return null;
		}
		double[] aS = aO.getScale();
		double[] aSum = {0, 0, 0};
		double aArea = 0;
		for(int t = 0;t < aMesh.nbTris;t++) {
			if(aMesh.faceOf(t) != aF) {
				continue;
			}
			double[][] aP = new double[3][];
			for(int i = 0;i < 3;i++) {
				int aV = aMesh.tris[t*3+i];
				aP[i] = new double[] {aMesh.pos[aV*3]*aS[0], aMesh.pos[aV*3+1]*aS[1], aMesh.pos[aV*3+2]*aS[2]};
			}
			double[] aC = com.cubaix.TDenlive.model3d.Math3D.cross(com.cubaix.TDenlive.model3d.Math3D.sub(aP[1], aP[0]),
					com.cubaix.TDenlive.model3d.Math3D.sub(aP[2], aP[0]));
			aSum = com.cubaix.TDenlive.model3d.Math3D.add(aSum, aC);
			aArea += com.cubaix.TDenlive.model3d.Math3D.length(aC);
		}
		double aL = com.cubaix.TDenlive.model3d.Math3D.length(aSum);
		//A round side: its normals cancel out
		if(aArea <= 0 || aL < 0.3*aArea) {
			return null;
		}
		return com.cubaix.TDenlive.model3d.Math3D.mul(aSum, 1/aL);
	}

	void redrawPreview() {
		if(previewImg != null) {
			previewImg.dispose();
			previewImg = null;
		}
		if(preview != null && !preview.isDisposed()) {
			preview.redraw();
		}
	}
}
