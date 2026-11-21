package com.cubaix.TDenlive.GUI;

import java.awt.image.BufferedImage;

import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.graphics.Rectangle;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Canvas;
import org.eclipse.swt.widgets.Combo;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.FileDialog;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.MessageBox;

import com.cubaix.TDenlive.TDenlive;
import com.cubaix.TDenlive.medias.ColorBck;
import com.cubaix.TDenlive.utils.ImageUtils;

/**
 * Library of the textures (ColorBck.Fill), shared by the color / texture clips (ColorBckGUI) and the
 * objects of the 3D modeler (Texture3DGUI): a row with the category combo, the "Load an image..."
 * button and the name of the selected texture, then the thumbnails of the category. The procedural
 * thumbnails are drawn in the two colors given by colors() (colorsChanged() to draw them again).
 */
public class TextureLibrary {
	//Thumbnails of the textures, in rows
	static final int THUMB = 48;
	static final int THUMB_GAP = 4;
	static final int THUMBS_PER_ROW = 8;

	public interface Listener {
		void selected(String aTexture);
	}

	TDenlive tde = null;
	Composite parent = null;
	java.util.function.Supplier<ColorBck.Fill> colors = null;
	Listener listener = null;
	String texture = ColorBck.Fill.PLAIN;
	Canvas library = null;
	Image[] thumbs = null;
	//Thumbnails of the bitmaps (independent of the colors), kept until the closing
	java.util.HashMap<String, Image> bitmapThumbs = new java.util.HashMap<String, Image>();
	Combo categoryCombo = null;
	//Category shown and its textures
	String viewCategory = null;
	java.util.Vector<String> viewTextures = null;
	Label textureName = null;

	/**
	 * In aParent (a GridLayout of one column): its row and its thumbnails
	 */
	public TextureLibrary(TDenlive aTDe, Composite aParent, String aTexture, java.util.function.Supplier<ColorBck.Fill> aColors, Listener aListener) {
		tde = aTDe;
		parent = aParent;
		colors = aColors;
		listener = aListener;
		texture = aTexture == null ? ColorBck.Fill.PLAIN : aTexture;
		if(ColorBck.Fill.isFile(texture)) {
			ColorBck.Fill.addFile(texture);
		}
		viewCategory = ColorBck.Fill.categoryOf(texture);
		viewTextures = ColorBck.Fill.texturesOf(viewCategory);
		createContents();
		aParent.addDisposeListener(e -> disposeImages());
	}

	public static String label(TDenlive aTDe, String aTexture) {
		if(ColorBck.Fill.isFile(aTexture)) {
			return ColorBck.Fill.baseName(aTexture);
		}
		return aTDe.gui.lngs.get("ColorBck.Tex_" + ColorBck.Fill.baseName(aTexture));
	}

	void createContents() {
		Composite aLibRow = new Composite(parent, SWT.NONE);
		aLibRow.setLayoutData(new GridData(GridData.FILL_HORIZONTAL));
		tde.gui.applyColorFont(aLibRow);
		GridLayout aLRGL = new GridLayout(4, false);
		tde.gui.applyNoMargin(aLRGL);
		aLibRow.setLayout(aLRGL);
		Label aCatL = new Label(aLibRow, SWT.NONE);
		aCatL.setText(tde.gui.lngs.get("ColorBck.Category"));
		tde.gui.applyColorFont(aCatL);
		categoryCombo = new Combo(aLibRow, SWT.READ_ONLY);
		tde.gui.applyColorFont(categoryCombo);
		java.util.Vector<String> aCats = ColorBck.Fill.categories();
		int aMaxCount = 0;
		for(String aCat : aCats) {
			categoryCombo.add(tde.gui.lngs.get("ColorBck.Cat_" + aCat));
			aMaxCount = Math.max(aMaxCount, ColorBck.Fill.texturesOf(aCat).size());
		}
		categoryCombo.setVisibleItemCount(aCats.size());
		categoryCombo.select(Math.max(0, aCats.indexOf(viewCategory)));
		categoryCombo.addListener(SWT.Selection, e -> {
			int aI = categoryCombo.getSelectionIndex();
			if(aI >= 0) {
				showCategory(aCats.elementAt(aI));
			}
		});
		Button aLoad = new Button(aLibRow, SWT.PUSH);
		aLoad.setText(tde.gui.lngs.get("ColorBck.LoadImage"));
		aLoad.setImage(tde.gui.imgsSwt.getIcon("open.gif"));
		tde.gui.applyColorFont(aLoad);
		aLoad.addListener(SWT.Selection, e -> loadImage());
		textureName = new Label(aLibRow, SWT.NONE);
		GridData aTNGD = new GridData(GridData.FILL_HORIZONTAL);
		aTNGD.horizontalIndent = 12;
		textureName.setLayoutData(aTNGD);
		tde.gui.applyColorFont(textureName);
		library = new Canvas(parent, SWT.DOUBLE_BUFFERED);
		GridData aLGD = new GridData(GridData.HORIZONTAL_ALIGN_CENTER);
		//Height of the largest category: the window does not change size
		int aRows = (aMaxCount + THUMBS_PER_ROW - 1) / THUMBS_PER_ROW;
		aLGD.widthHint = THUMBS_PER_ROW * (THUMB + THUMB_GAP) + THUMB_GAP;
		aLGD.heightHint = aRows * (THUMB + THUMB_GAP) + THUMB_GAP;
		library.setLayoutData(aLGD);
		tde.gui.applyColorFont(library);
		library.addPaintListener(e -> {
			if(thumbs == null) {
				buildThumbs();
			}
			for(int t = 0; t < thumbs.length; t++) {
				Rectangle aR = thumbRect(t);
				e.gc.drawImage(thumbs[t], aR.x, aR.y);
				boolean aSel = viewTextures.elementAt(t).equals(texture);
				e.gc.setForeground(aSel ? tde.gui.colorsSwt.CUBAIX_BLUE : tde.gui.display.getSystemColor(SWT.COLOR_GRAY));
				e.gc.setLineWidth(aSel ? 3 : 1);
				e.gc.drawRectangle(aR.x - 1, aR.y - 1, aR.width + 1, aR.height + 1);
			}
		});
		library.addListener(SWT.MouseDown, e -> {
			int aT = thumbAt(e.x, e.y);
			if(aT >= 0) {
				select(viewTextures.elementAt(aT));
			}
		});
		library.addListener(SWT.MouseMove, e -> {
			int aT = thumbAt(e.x, e.y);
			String aTip = aT >= 0 ? label(tde, viewTextures.elementAt(aT)) : null;
			if(aTip == null ? library.getToolTipText() != null : !aTip.equals(library.getToolTipText())) {
				library.setToolTipText(aTip);
			}
		});
		updateName();
	}

	void showCategory(String aCategory) {
		viewCategory = aCategory;
		viewTextures = ColorBck.Fill.texturesOf(viewCategory);
		categoryCombo.select(ColorBck.Fill.categories().indexOf(viewCategory));
		disposeThumbs();
		library.redraw();
	}

	void select(String aTexture) {
		setTexture(aTexture);
		listener.selected(aTexture);
	}

	/**
	 * Texture shown as selected (the category shown is unchanged)
	 */
	public void setTexture(String aTexture) {
		texture = aTexture;
		updateName();
		library.redraw();
	}

	public String getTexture() {
		return texture;
	}

	/**
	 * The colors of the procedural textures changed: their thumbnails are drawn again
	 */
	public void colorsChanged() {
		disposeThumbs();
		library.redraw();
	}

	void updateName() {
		textureName.setText(tde.gui.lngs.get("ColorBck.Texture") + " " + label(tde, texture));
	}

	Rectangle thumbRect(int aT) {
		int aCol = aT % THUMBS_PER_ROW;
		int aRow = aT / THUMBS_PER_ROW;
		return new Rectangle(THUMB_GAP + aCol * (THUMB + THUMB_GAP), THUMB_GAP + aRow * (THUMB + THUMB_GAP), THUMB, THUMB);
	}

	int thumbAt(int aX, int aY) {
		for(int t = 0; t < viewTextures.size(); t++) {
			if(thumbRect(t).contains(aX, aY)) {
				return t;
			}
		}
		return -1;
	}

	/**
	 * Thumbnails of the category shown: the procedural textures in the current colors,
	 * two tiles per side; a bitmap whole, once
	 */
	void buildThumbs() {
		disposeThumbs();
		ColorBck.Fill aColors = colors.get();
		thumbs = new Image[viewTextures.size()];
		for(int t = 0; t < thumbs.length; t++) {
			ColorBck.Fill aF = aColors.copy();
			aF.texture = viewTextures.elementAt(t);
			if(aF.isBitmap()) {
				Image aI = bitmapThumbs.get(aF.texture);
				if(aI == null) {
					aF.tileH = 100;
					aF.tileW = 100 * ColorBck.Fill.ratioOf(aF.texture);
					aF.tileX = aF.tileY = 0;
					BufferedImage aBI = ImageUtils.createImage(THUMB, THUMB);
					aF.paint(aBI);
					aI = ImageUtils.imageToSwt(tde.gui.display, aBI);
					bitmapThumbs.put(aF.texture, aI);
				}
				thumbs[t] = aI;
				continue;
			}
			if(ColorBck.Fill.CHECKER.equals(aF.texture)) {
				aF.color = ColorBck.Fill.CHECKER_GRAY;
				aF.color2 = ColorBck.Fill.CHECKER_WHITE;
				//A single 2 x 2 motif: more cells would be too small to be read
				aF.tileW = aF.tileH = 100;
			}
			else {
				aF.tileW = aF.tileH = 50;
			}
			aF.tileX = aF.tileY = 0;
			BufferedImage aBI = ImageUtils.createImage(THUMB, THUMB);
			aF.paint(aBI);
			thumbs[t] = ImageUtils.imageToSwt(tde.gui.display, aBI);
		}
	}

	/**
	 * An image of the disk as texture: put first in the FILES category, shown and selected
	 */
	void loadImage() {
		FileDialog aFD = new FileDialog(parent.getShell(), SWT.OPEN);
		aFD.setText(tde.gui.lngs.get("ColorBck.LoadImage"));
		String aExts = "*.jpg;*.jpeg;*.png;*.webp;*.bmp;*.gif;*.tif;*.tiff;*.pgm";
		aFD.setFilterExtensions(new String[] {aExts + ";" + aExts.toUpperCase(), "*"});
		String aDir = ColorBck.Fill.isFile(texture) ? new java.io.File(texture.substring(ColorBck.Fill.FILE.length())).getParent() : tde.lastDir;
		if(aDir != null) {
			aFD.setFilterPath(aDir);
		}
		String aPath = aFD.open();
		if(aPath == null) {
			return;
		}
		String aTexture = ColorBck.Fill.FILE + aPath;
		if(ColorBck.Fill.bitmap(aTexture) == null) {
			MessageBox aMB = new MessageBox(parent.getShell(), SWT.ICON_ERROR | SWT.OK);
			aMB.setText(parent.getShell().getText());
			aMB.setMessage(tde.gui.lngs.get("ColorBck.LoadError") + "\n" + aPath);
			aMB.open();
			return;
		}
		ColorBck.Fill.addFile(aTexture);
		showCategory(ColorBck.Fill.FILES);
		select(aTexture);
	}

	void disposeThumbs() {
		if(thumbs != null) {
			for(Image aI : thumbs) {
				if(aI != null && !bitmapThumbs.containsValue(aI)) {
					aI.dispose();
				}
			}
			thumbs = null;
		}
	}

	void disposeImages() {
		disposeThumbs();
		for(Image aI : bitmapThumbs.values()) {
			aI.dispose();
		}
		bitmapThumbs.clear();
	}
}
