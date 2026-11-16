package com.cubaix.TDenlive.GUI;

import java.awt.image.BufferedImage;

import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.CLabel;
import org.eclipse.swt.events.SelectionAdapter;
import org.eclipse.swt.events.SelectionEvent;
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

import com.cubaix.TDenlive.TDConfig;
import com.cubaix.TDenlive.TDenlive;
import com.cubaix.TDenlive.medias.ColorBck;
import com.cubaix.TDenlive.utils.ImageUtils;

/**
 * Popup to create a color or texture clip: preview, two colors (R/G/B values and
 * the system color picker), the library of textures (TextureLibrary: geometric
 * first, plain color then the checkerboard of the monitor...; then the images of
 * res/textures/ and of the disk), the size of the tiles, their start position and
 * the rotation of the texture, and the Create / Cancel buttons.
 * open() returns the chosen fill, or null if canceled.
 */
public class ColorBckGUI {
	//Tile sizes in % of the image height, on a logarithmic scale of SIZE_STEPS steps
	static final double SIZE_MIN = 0.5;
	static final double SIZE_MAX = 400;
	static final int SIZE_STEPS = 1000;
	//Preview height (its width follows the output ratio)
	static final int PREVIEW_H = 200;

	TDenlive tde = null;
	Shell shell = null;
	Canvas preview = null;
	Image previewImg = null;
	TextureLibrary library = null;
	Button[] palettes = new Button[2];
	Spinner[][] spinners = new Spinner[2][3];
	Scale[] scales = new Scale[4];
	Label[] scaleValues = new Label[4];
	Scale rotation = null;
	Label rotationValue = null;
	Button rotationReset = null;
	//Linked sliders: width / height, X / Y (the other one takes the same value)
	Button[] links = new Button[2];
	ColorBck.Fill fill = null;
	ColorBck.Fill result = null;
	boolean updating = false;

	public ColorBckGUI(TDenlive aTDe, ColorBck.Fill aInit) {
		tde = aTDe;
		fill = aInit != null ? aInit.copy() : new ColorBck.Fill();
		createContents();
	}

	public ColorBck.Fill open() {
		shell.pack();
		Rectangle aP = tde.gui.shell.getBounds();
		Rectangle aS = shell.getBounds();
		shell.setLocation(aP.x + (aP.width - aS.width) / 2, aP.y + (aP.height - aS.height) / 2);
		shell.open();
		while (!shell.isDisposed()) {
			if (!tde.gui.display.readAndDispatch()) {
				tde.gui.display.sleep();
			}
		}
		return result;
	}

	void createContents() {
		shell = new Shell(tde.gui.shell, SWT.DIALOG_TRIM | SWT.APPLICATION_MODAL);
		shell.setText(tde.gui.lngs.get("ColorBck.Title"));
		shell.setLayout(new GridLayout(1, false));
		tde.gui.applyColorFont(shell);
		shell.setImage(tde.gui.imgsSwt.getIcon("3Denlive32.png"));
		shell.addDisposeListener(e -> disposeImages());

		//Preview at 100 %: the top left corner of the image at the output size
		Label aPreviewL = new Label(shell, SWT.NONE);
		aPreviewL.setText(tde.gui.lngs.get("ColorBck.Preview100"));
		aPreviewL.setLayoutData(new GridData(GridData.HORIZONTAL_ALIGN_CENTER));
		tde.gui.applyColorFont(aPreviewL);
		preview = new Canvas(shell, SWT.BORDER | SWT.DOUBLE_BUFFERED);
		GridData aPGD = new GridData(GridData.HORIZONTAL_ALIGN_CENTER);
		double aRatio = tde.config.outRatio > 0 ? tde.config.outRatio : 16.0 / 9.0;
		aPGD.widthHint = Math.max(PREVIEW_H, Math.min(3 * PREVIEW_H, (int)Math.round(PREVIEW_H * aRatio)));
		aPGD.heightHint = PREVIEW_H;
		preview.setLayoutData(aPGD);
		preview.addPaintListener(e -> {
			Rectangle aR = preview.getClientArea();
			if(aR.width <= 0 || aR.height <= 0) {
				return;
			}
			if(previewImg == null || previewImg.getBounds().width != aR.width || previewImg.getBounds().height != aR.height) {
				if(previewImg != null) {
					previewImg.dispose();
				}
				BufferedImage aBI = ImageUtils.createImage(aR.width, aR.height);
				fill.paint(aBI, Math.max(1, tde.config.processingResValues[TDConfig.PROCESSING_MODE_RENDER]));
				previewImg = ImageUtils.imageToSwt(tde.gui.display, aBI);
			}
			e.gc.drawImage(previewImg, 0, 0);
		});

		createColorRow(0, "ColorBck.Color1");
		createColorRow(1, "ColorBck.Color2");

		//Library of the textures: category, then its textures
		library = new TextureLibrary(tde, shell, fill.texture, () -> fill, aT -> selectTexture(aT));

		//Size and start position of the tiles
		Composite aTiles = new Composite(shell, SWT.NONE);
		aTiles.setLayoutData(new GridData(GridData.FILL_HORIZONTAL));
		tde.gui.applyColorFont(aTiles);
		GridLayout aTGL = new GridLayout(4, false);
		tde.gui.applyNoMargin(aTGL);
		aTiles.setLayout(aTGL);
		String[] aKeys = { "ColorBck.TileW", "ColorBck.TileH", "ColorBck.TileX", "ColorBck.TileY" };
		for(int s = 0; s < 4; s++) {
			Label aL = new Label(aTiles, SWT.NONE);
			aL.setText(tde.gui.lngs.get(aKeys[s]));
			tde.gui.applyColorFont(aL);
			Scale aSc = new Scale(aTiles, SWT.HORIZONTAL);
			if(s < 2) {
				aSc.setMinimum(0);
				aSc.setMaximum(SIZE_STEPS);
				aSc.setIncrement(1);
				aSc.setPageIncrement(10);
			}
			else {
				aSc.setMinimum(0);
				aSc.setMaximum(100);
				aSc.setIncrement(1);
				aSc.setPageIncrement(10);
			}
			GridData aScGD = new GridData(GridData.FILL_HORIZONTAL);
			aScGD.widthHint = 260;
			aSc.setLayoutData(aScGD);
			final int aIdx = s;
			aSc.addListener(SWT.Selection, e -> scaleChanged(aIdx));
			scales[s] = aSc;
			Label aV = new Label(aTiles, SWT.RIGHT);
			GridData aVGD = new GridData();
			aVGD.widthHint = 60;
			aV.setLayoutData(aVGD);
			tde.gui.applyColorFont(aV);
			scaleValues[s] = aV;
			if(s % 2 == 0) {
				//Link of this slider and the next one, beside both
				Button aLink = new Button(aTiles, SWT.CHECK);
				aLink.setText(tde.gui.lngs.get(s == 0 ? "ColorBck.LinkSize" : "ColorBck.LinkPos"));
				GridData aLGD2 = new GridData(GridData.VERTICAL_ALIGN_CENTER);
				aLGD2.verticalSpan = 2;
				aLink.setLayoutData(aLGD2);
				tde.gui.applyColorFont(aLink);
				aLink.setSelection(s == 0 ? Math.abs(fill.tileW - fill.tileH * sizeRatio()) < 0.1 : fill.tileX == fill.tileY);
				final int aFirst = s;
				aLink.addListener(SWT.Selection, e -> {
					if(aLink.getSelection()) {
						//The first slider sets the second one
						scaleChanged(aFirst);
					}
				});
				links[s / 2] = aLink;
			}
		}

		//Rotation of the texture, -180..180 degrees, then a reset to 0
		Label aRotL = new Label(aTiles, SWT.NONE);
		aRotL.setText(tde.gui.lngs.get("ColorBck.Rotation"));
		tde.gui.applyColorFont(aRotL);
		rotation = new Scale(aTiles, SWT.HORIZONTAL);
		rotation.setMinimum(0);
		rotation.setMaximum(360);
		rotation.setIncrement(1);
		rotation.setPageIncrement(15);
		GridData aRotGD = new GridData(GridData.FILL_HORIZONTAL);
		aRotGD.widthHint = 260;
		rotation.setLayoutData(aRotGD);
		rotation.addListener(SWT.Selection, e -> {
			fill.tileRot = rotation.getSelection() - 180;
			updateScaleLabels();
			redrawPreview();
		});
		rotationValue = new Label(aTiles, SWT.RIGHT);
		GridData aRVGD = new GridData();
		aRVGD.widthHint = 60;
		rotationValue.setLayoutData(aRVGD);
		tde.gui.applyColorFont(rotationValue);
		rotationReset = new Button(aTiles, SWT.PUSH);
		rotationReset.setText("0°");
		tde.gui.applyColorFont(rotationReset);
		rotationReset.addListener(SWT.Selection, e -> {
			fill.tileRot = 0;
			rotation.setSelection(180);
			updateScaleLabels();
			redrawPreview();
		});

		Composite aButtons = new Composite(shell, SWT.NONE);
		aButtons.setLayoutData(new GridData(GridData.HORIZONTAL_ALIGN_END));
		tde.gui.applyColorFont(aButtons);
		GridLayout aBGL = new GridLayout(2, true);
		tde.gui.applyNoMargin(aBGL);
		aButtons.setLayout(aBGL);

		Button aCancel = new Button(aButtons, SWT.PUSH);
		aCancel.setText(tde.gui.lngs.get("ColorBck.Cancel"));
		tde.gui.applyColorFont(aCancel);
		aCancel.setLayoutData(new GridData(GridData.FILL_HORIZONTAL));
		aCancel.addSelectionListener(new SelectionAdapter() {
			@Override
			public void widgetSelected(SelectionEvent arg0) {
				result = null;
				shell.close();
			}
		});

		Button aCreate = new Button(aButtons, SWT.PUSH);
		aCreate.setText(tde.gui.lngs.get("ColorBck.Create"));
		aCreate.setImage(tde.gui.imgsSwt.getIcon("checked.gif"));
		tde.gui.applyColorFont(aCreate);
		aCreate.setLayoutData(new GridData(GridData.FILL_HORIZONTAL));
		aCreate.addSelectionListener(new SelectionAdapter() {
			@Override
			public void widgetSelected(SelectionEvent arg0) {
				updateFromSpinners();
				result = fill.copy();
				shell.close();
			}
		});
		shell.setDefaultButton(aCreate);

		refreshWidgets();
	}

	/**
	 * Row of one of the two colors (aC = 0 or 1): name, R/G/B spinners, palette
	 */
	void createColorRow(int aC, String aKey) {
		Composite aValues = new Composite(shell, SWT.NONE);
		aValues.setLayoutData(new GridData(GridData.FILL_HORIZONTAL));
		tde.gui.applyColorFont(aValues);
		GridLayout aVGL = new GridLayout(8, false);
		tde.gui.applyNoMargin(aVGL);
		aValues.setLayout(aVGL);
		Label aName = new Label(aValues, SWT.NONE);
		aName.setText(tde.gui.lngs.get(aKey));
		GridData aNGD = new GridData();
		aNGD.widthHint = 90;
		aName.setLayoutData(aNGD);
		tde.gui.applyColorFont(aName);
		String[] aLetters = { "Generic.RedLetter", "Generic.GreenLetter", "Generic.BlueLetter" };
		for (int c = 0; c < 3; c++) {
			CLabel aL = new CLabel(aValues, SWT.NONE);
			aL.setText(tde.gui.lngs.get(aLetters[c]));
			tde.gui.applyColorFont(aL);
			Spinner aSp = new Spinner(aValues, SWT.BORDER);
			aSp.setValues(0, 0, 255, 0, 1, 16);
			tde.gui.applyColorFont(aSp);
			aSp.addModifyListener(e -> {
				if(!updating) {
					updateFromSpinners();
				}
			});
			spinners[aC][c] = aSp;
		}
		Button aPalette = new Button(aValues, SWT.PUSH);
		aPalette.setText(tde.gui.lngs.get("ColorBck.Palette"));
		aPalette.setImage(tde.gui.imgsSwt.getIcon("colourPalette.gif"));
		tde.gui.applyColorFont(aPalette);
		GridData aPalGD = new GridData(GridData.HORIZONTAL_ALIGN_END | GridData.GRAB_HORIZONTAL);
		aPalette.setLayoutData(aPalGD);
		aPalette.addSelectionListener(new SelectionAdapter() {
			@Override
			public void widgetSelected(SelectionEvent arg0) {
				ColorDialog aCD = new ColorDialog(shell);
				java.awt.Color aCol = aC == 0 ? fill.color : fill.color2;
				aCD.setRGB(new RGB(aCol.getRed(), aCol.getGreen(), aCol.getBlue()));
				RGB aRGB = aCD.open();
				if (aRGB != null) {
					updating = true;
					spinners[aC][0].setSelection(aRGB.red);
					spinners[aC][1].setSelection(aRGB.green);
					spinners[aC][2].setSelection(aRGB.blue);
					updating = false;
					updateFromSpinners();
				}
			}
		});
		palettes[aC] = aPalette;
	}

	/**
	 * Width / height of the tile kept by the W / H link: that of the bitmap, else square
	 */
	double sizeRatio() {
		return ColorBck.Fill.ratioOf(fill.texture);
	}

	void selectTexture(String aTexture) {
		boolean aWasBitmap = fill.isBitmap();
		boolean aWasFile = fill.isFile();
		fill.texture = aTexture;
		if(fill.isBitmap()) {
			if(!aWasBitmap || fill.isFile() != aWasFile) {
				//A texture of the library: tiles of half the height; an image of the disk: the
				//whole height; in its proportions
				fill.tileH = fill.isFile() ? 100 : 50;
				fill.tileX = fill.tileY = 0;
				links[0].setSelection(true);
			}
			if(links[0].getSelection()) {
				keepRatio(true);
			}
		}
		else if(aWasBitmap) {
			fill.tileW = fill.tileH = 10;
		}
		if(ColorBck.Fill.CHECKER.equals(aTexture)) {
			//The checkerboard of the monitor: light gray and white, cells of 1 % of the height
			fill.color = ColorBck.Fill.CHECKER_GRAY;
			fill.color2 = ColorBck.Fill.CHECKER_WHITE;
			fill.tileW = fill.tileH = 2;
			fill.tileX = fill.tileY = 0;
		}
		refreshWidgets();
	}

	void scaleChanged(int aIdx) {
		int aV = scales[aIdx].getSelection();
		setTileValue(aIdx, aV);
		if(links[aIdx / 2].getSelection()) {
			int aOther = aIdx ^ 1;
			if(aIdx < 2) {
				keepRatio(aIdx == 1);
				scales[0].setSelection(sizeToScale(fill.tileW));
				scales[1].setSelection(sizeToScale(fill.tileH));
			}
			else {
				setTileValue(aOther, aV);
				scales[aOther].setSelection(aV);
			}
		}
		updateScaleLabels();
		redrawPreview();
	}

	/**
	 * Width / height of the tile in the proportions of the texture, from the height (aFromH)
	 * or the width; when the other one is out of the range, the given one gives way
	 */
	void keepRatio(boolean aFromH) {
		double aR = sizeRatio();
		if(aFromH) {
			fill.tileW = clampSize(fill.tileH * aR);
			fill.tileH = clampSize(fill.tileW / aR);
		}
		else {
			fill.tileH = clampSize(fill.tileW / aR);
			fill.tileW = clampSize(fill.tileH * aR);
		}
	}

	double clampSize(double aSize) {
		return Math.max(SIZE_MIN, Math.min(SIZE_MAX, aSize));
	}

	static int sizeToScale(double aSize) {
		return (int)Math.round(SIZE_STEPS * Math.log(Math.max(SIZE_MIN, aSize) / SIZE_MIN) / Math.log(SIZE_MAX / SIZE_MIN));
	}

	static double scaleToSize(int aV) {
		//Rounded to 0.1 %: the value shown
		return Math.round(10 * SIZE_MIN * Math.pow(SIZE_MAX / SIZE_MIN, aV / (double)SIZE_STEPS)) / 10.0;
	}

	void setTileValue(int aIdx, int aV) {
		switch(aIdx) {
		case 0: fill.tileW = scaleToSize(aV); break;
		case 1: fill.tileH = scaleToSize(aV); break;
		case 2: fill.tileX = aV; break;
		case 3: fill.tileY = aV; break;
		}
	}

	/**
	 * All the widgets from the fill
	 */
	void refreshWidgets() {
		updating = true;
		java.awt.Color[] aCols = { fill.color, fill.color2 };
		for(int c = 0; c < 2; c++) {
			spinners[c][0].setSelection(aCols[c].getRed());
			spinners[c][1].setSelection(aCols[c].getGreen());
			spinners[c][2].setSelection(aCols[c].getBlue());
		}
		scales[0].setSelection(sizeToScale(fill.tileW));
		scales[1].setSelection(sizeToScale(fill.tileH));
		scales[2].setSelection((int)Math.round(fill.tileX));
		scales[3].setSelection((int)Math.round(fill.tileY));
		rotation.setSelection((int)Math.round(fill.tileRot) + 180);
		updating = false;
		boolean aTextured = !fill.isPlain();
		//A bitmap has its own colors
		boolean aBitmap = fill.isBitmap();
		for(int c = 0; c < 2; c++) {
			boolean aOn = !aBitmap && (c == 0 || aTextured);
			for(Spinner aSp : spinners[c]) {
				aSp.setEnabled(aOn);
			}
			palettes[c].setEnabled(aOn);
		}
		for(Scale aSc : scales) {
			aSc.setEnabled(aTextured);
		}
		rotation.setEnabled(aTextured);
		rotationReset.setEnabled(aTextured);
		for(Button aLink : links) {
			aLink.setEnabled(aTextured);
		}
		library.setTexture(fill.texture);
		updateScaleLabels();
		redrawPreview();
	}

	void updateScaleLabels() {
		scaleValues[0].setText(String.format("%.1f %%", fill.tileW));
		scaleValues[1].setText(String.format("%.1f %%", fill.tileH));
		scaleValues[2].setText((int)Math.round(fill.tileX) + " %");
		scaleValues[3].setText((int)Math.round(fill.tileY) + " %");
		rotationValue.setText((int)Math.round(fill.tileRot) + "°");
	}

	void updateFromSpinners() {
		fill.color = new java.awt.Color(spinners[0][0].getSelection(), spinners[0][1].getSelection(), spinners[0][2].getSelection());
		fill.color2 = new java.awt.Color(spinners[1][0].getSelection(), spinners[1][1].getSelection(), spinners[1][2].getSelection());
		library.colorsChanged();
		redrawPreview();
	}

	void redrawPreview() {
		if(previewImg != null) {
			previewImg.dispose();
			previewImg = null;
		}
		preview.redraw();
	}

	void disposeImages() {
		if(previewImg != null) {
			previewImg.dispose();
			previewImg = null;
		}
	}
}
