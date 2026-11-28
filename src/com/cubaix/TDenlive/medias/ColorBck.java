package com.cubaix.TDenlive.medias;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.util.Vector;

import com.cubaix.TDenlive.TDenlive;
import com.cubaix.TDenlive.utils.ImageUtils;
import com.cubaix.TDenlive.xml.XmlObject;
import com.cubaix.TDenlive.xml.XmlTag;

public class ColorBck extends StereoImage {
	/**
	 * What fills the frame: a plain color, a procedural texture of two colors, a
	 * bitmap texture (res/textures/, CC0 images of ambientCG) or an image file of the
	 * disk, repeated in tiles.
	 * The tile sizes are in % of the image height (both, so that equal values give
	 * square tiles at any output size), the start position in % of the tile (the
	 * point of the tile at the top left corner of the image).
	 */
	static public class Fill {
		//Texture ids, in the order of the library (PLAIN first, then the checkerboard)
		static public final String PLAIN = "plain";
		static public final String CHECKER = "checker";
		static public final String[] TEXTURES = { PLAIN, CHECKER, "vstripes", "hstripes", "diagonal", "chevrons"
				, "dots", "grid", "bricks", "diamonds", "waves", "rings", "hgradient", "vgradient", "radial" };
		//Category of the procedural textures (TEXTURES); the others come from res/textures/textures.txt
		static public final String GEOMETRIC = "geometric";
		//Prefix of the bitmap texture ids: "bmp:<category>/<name>" = res/textures/<category>/<name>.jpg
		static public final String BITMAP = "bmp:";
		static final String TEXTURES_RES = "/com/cubaix/TDenlive/res/textures/";
		//Prefix of an image of the disk: "file:<path>", in the category FILES (last of the library)
		static public final String FILE = "file:";
		static public final String FILES = "files";
		//Images of the disk chosen or opened during the session, most recent first
		static public Vector<String> files = new Vector<String>();
		static Vector<String> categories = null;
		static java.util.HashMap<String, Vector<String>> byCategory = new java.util.HashMap<String, Vector<String>>();
		static java.util.HashMap<String, java.lang.ref.SoftReference<BufferedImage>> bitmaps = new java.util.HashMap<String, java.lang.ref.SoftReference<BufferedImage>>();
		//Colors of the checkerboard of the monitor (ImageUtils.checkerBck())
		static public final Color CHECKER_GRAY = Color.LIGHT_GRAY;
		static public final Color CHECKER_WHITE = Color.WHITE;

		public Color color = Color.BLACK;
		public Color color2 = Color.WHITE;
		public String texture = PLAIN;
		public double tileW = 2;
		public double tileH = 2;
		public double tileX = 0;
		public double tileY = 0;
		//Rotation of the texture, degrees counterclockwise, around the top left corner of the image
		public double tileRot = 0;

		public Fill copy() {
			Fill aF = new Fill();
			aF.color = color;
			aF.color2 = color2;
			aF.texture = texture;
			aF.tileW = tileW;
			aF.tileH = tileH;
			aF.tileRot = tileRot;
			aF.tileX = tileX;
			aF.tileY = tileY;
			return aF;
		}

		public boolean isPlain() {
			return PLAIN.equals(texture);
		}

		public boolean isBitmap() {
			return isBitmap(texture);
		}

		/**
		 * An image: of the library or of the disk
		 */
		static public boolean isBitmap(String aTexture) {
			return aTexture.startsWith(BITMAP) || aTexture.startsWith(FILE);
		}

		public boolean isFile() {
			return isFile(texture);
		}

		static public boolean isFile(String aTexture) {
			return aTexture.startsWith(FILE);
		}

		/**
		 * Path of an image of the disk, else null
		 */
		public String filePath() {
			return isFile() ? texture.substring(FILE.length()) : null;
		}

		/**
		 * Puts an image of the disk first in the FILES category
		 */
		static synchronized public void addFile(String aTexture) {
			files.remove(aTexture);
			files.add(0, aTexture);
		}

		/**
		 * Short name of a texture: the procedural id, or the name of the image file
		 */
		static public String baseName(String aTexture) {
			if(isFile(aTexture)) {
				return new java.io.File(aTexture.substring(FILE.length())).getName();
			}
			return isBitmap(aTexture) ? aTexture.substring(aTexture.lastIndexOf('/') + 1) : aTexture;
		}

		/**
		 * Categories of the library: GEOMETRIC first, then those of the index of the bitmaps
		 */
		static synchronized public Vector<String> categories() {
			if(categories == null) {
				categories = new Vector<String>();
				categories.add(GEOMETRIC);
				try(java.io.InputStream aIs = ColorBck.class.getResourceAsStream(TEXTURES_RES + "textures.txt")) {
					java.io.BufferedReader aR = new java.io.BufferedReader(new java.io.InputStreamReader(aIs, java.nio.charset.StandardCharsets.UTF_8));
					String aL;
					while((aL = aR.readLine()) != null) {
						aL = aL.trim();
						int aSep = aL.indexOf('|');
						if(aL.isEmpty() || aL.startsWith("#") || aSep <= 0) {
							continue;
						}
						String aCat = aL.substring(0, aSep);
						if(!byCategory.containsKey(aCat)) {
							categories.add(aCat);
							byCategory.put(aCat, new Vector<String>());
						}
						byCategory.get(aCat).add(BITMAP + aCat + "/" + aL.substring(aSep + 1));
					}
				}
				catch(Throwable t) {
					t.printStackTrace(System.err);
				}
				categories.add(FILES);
			}
			return categories;
		}

		static public Vector<String> texturesOf(String aCategory) {
			categories();
			if(GEOMETRIC.equals(aCategory)) {
				return new Vector<String>(java.util.Arrays.asList(TEXTURES));
			}
			if(FILES.equals(aCategory)) {
				return new Vector<String>(files);
			}
			Vector<String> aTs = byCategory.get(aCategory);
			return aTs != null ? aTs : new Vector<String>();
		}

		static public String categoryOf(String aTexture) {
			if(isFile(aTexture)) {
				return FILES;
			}
			if(!isBitmap(aTexture)) {
				return GEOMETRIC;
			}
			int aSlash = aTexture.indexOf('/');
			return aSlash > 0 ? aTexture.substring(BITMAP.length(), aSlash) : GEOMETRIC;
		}

		/**
		 * Image of a bitmap texture (TYPE_INT_RGB), kept while the memory allows it; null if missing
		 */
		static synchronized public BufferedImage bitmap(String aTexture) {
			java.lang.ref.SoftReference<BufferedImage> aRef = bitmaps.get(aTexture);
			BufferedImage aBI = aRef != null ? aRef.get() : null;
			if(aBI == null) {
				try(java.io.InputStream aIs = isFile(aTexture) ? null
						: ColorBck.class.getResourceAsStream(TEXTURES_RES + aTexture.substring(BITMAP.length()) + ".jpg")) {
					BufferedImage aSrc = null;
					if(isFile(aTexture)) {
						String aPath = aTexture.substring(FILE.length());
						if(new java.io.File(aPath).isFile()) {
							aSrc = ImageUtils.loadImage(aPath);
						}
					}
					else if(aIs != null) {
						aSrc = javax.imageio.ImageIO.read(aIs);
					}
					if(aSrc == null) {
						System.err.println("Missing texture: " + aTexture);
						return null;
					}
					aBI = new BufferedImage(aSrc.getWidth(), aSrc.getHeight(), BufferedImage.TYPE_INT_RGB);
					java.awt.Graphics2D aG = aBI.createGraphics();
					aG.drawImage(aSrc, 0, 0, null);
					aG.dispose();
					bitmaps.put(aTexture, new java.lang.ref.SoftReference<BufferedImage>(aBI));
				}
				catch(Throwable t) {
					t.printStackTrace(System.err);
					return null;
				}
			}
			return aBI;
		}

		/**
		 * Width / height of a bitmap texture (1 for the procedural ones)
		 */
		static public double ratioOf(String aTexture) {
			if(!isBitmap(aTexture)) {
				return 1;
			}
			BufferedImage aBI = bitmap(aTexture);
			return aBI != null ? aBI.getWidth() / (double)aBI.getHeight() : 1;
		}

		//Last tile of a bitmap scaled to the tile size (preview redrawn while a slider moves)
		BufferedImage lastTile = null;
		String lastTileKey = null;

		BufferedImage scaledTile(BufferedImage aSrc, int aW, int aH) {
			String aKey = texture + "@" + aW + "x" + aH;
			if(aKey.equals(lastTileKey) && lastTile != null) {
				return lastTile;
			}
			//Halved step by step down to less than twice the size, then bilinear: no aliasing
			BufferedImage aCur = aSrc;
			while(aCur.getWidth() >= 2 * aW && aCur.getHeight() >= 2 * aH) {
				aCur = scale(aCur, aCur.getWidth() / 2, aCur.getHeight() / 2);
			}
			lastTile = (aCur.getWidth() == aW && aCur.getHeight() == aH) ? aCur : scale(aCur, aW, aH);
			lastTileKey = aKey;
			return lastTile;
		}

		static BufferedImage scale(BufferedImage aSrc, int aW, int aH) {
			BufferedImage aBI = new BufferedImage(aW, aH, BufferedImage.TYPE_INT_RGB);
			java.awt.Graphics2D aG = aBI.createGraphics();
			aG.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION, java.awt.RenderingHints.VALUE_INTERPOLATION_BILINEAR);
			aG.setRenderingHint(java.awt.RenderingHints.KEY_RENDERING, java.awt.RenderingHints.VALUE_RENDER_QUALITY);
			aG.drawImage(aSrc, 0, 0, aW, aH, null);
			aG.dispose();
			return aBI;
		}

		/**
		 * Repeats the bitmap scaled to the tile size, from the start position
		 */
		void paintBitmap(int[] aPix, int aW, int aH, double aTW, double aTH, double aOX, double aOY, double aCos, double aSin) {
			BufferedImage aSrc = bitmap(texture);
			if(aSrc == null) {
				java.util.Arrays.fill(aPix, 0xFF000000 | color.getRGB());
				return;
			}
			int aTWi = Math.max(1, (int)Math.round(aTW));
			int aTHi = Math.max(1, (int)Math.round(aTH));
			int[] aT = ((DataBufferInt)scaledTile(aSrc, aTWi, aTHi).getRaster().getDataBuffer()).getData();
			if(tileRot != 0) {
				//Turned: each pixel sampled in the tile (bilinear, repeated)
				for(int y = 0; y < aH; y++) {
					double aPy = y + 0.5;
					for(int x = 0, p = y * aW; x < aW; x++, p++) {
						double aPx = x + 0.5;
						double aTx = (aCos * aPx + aSin * aPy + aOX) / aTW * aTWi - 0.5;
						double aTy = (-aSin * aPx + aCos * aPy + aOY) / aTH * aTHi - 0.5;
						aPix[p] = 0xFF000000 | bilinear(aT, aTWi, aTHi, aTx, aTy);
					}
				}
				return;
			}
			int aOXi = (int)Math.round(aOX) % aTWi;
			int aOYi = (int)Math.round(aOY) % aTHi;
			for(int y = 0; y < aH; y++) {
				int aRow = ((y + aOYi) % aTHi) * aTWi;
				int aTX = aOXi;
				for(int x = 0, p = y * aW; x < aW; x++, p++) {
					aPix[p] = 0xFF000000 | aT[aRow + aTX];
					if(++aTX >= aTWi) {
						aTX = 0;
					}
				}
			}
		}

		/**
		 * RGB of an image aW x aH repeated, at the pixel coordinates (aX, aY), bilinear
		 */
		static int bilinear(int[] aT, int aW, int aH, double aX, double aY) {
			int aX0 = (int)Math.floor(aX), aY0 = (int)Math.floor(aY);
			double aFx = aX - aX0, aFy = aY - aY0;
			aX0 = ((aX0 % aW) + aW) % aW;
			aY0 = ((aY0 % aH) + aH) % aH;
			int aX1 = aX0 + 1 == aW ? 0 : aX0 + 1;
			int aY1 = aY0 + 1 == aH ? 0 : aY0 + 1;
			int aA = aT[aY0 * aW + aX0], aB = aT[aY0 * aW + aX1], aC = aT[aY1 * aW + aX0], aD = aT[aY1 * aW + aX1];
			double aW00 = (1 - aFx) * (1 - aFy), aW10 = aFx * (1 - aFy), aW01 = (1 - aFx) * aFy, aW11 = aFx * aFy;
			int aR = (int)(((aA >> 16) & 0xFF) * aW00 + ((aB >> 16) & 0xFF) * aW10 + ((aC >> 16) & 0xFF) * aW01 + ((aD >> 16) & 0xFF) * aW11 + 0.5);
			int aG = (int)(((aA >> 8) & 0xFF) * aW00 + ((aB >> 8) & 0xFF) * aW10 + ((aC >> 8) & 0xFF) * aW01 + ((aD >> 8) & 0xFF) * aW11 + 0.5);
			int aBl = (int)((aA & 0xFF) * aW00 + (aB & 0xFF) * aW10 + (aC & 0xFF) * aW01 + (aD & 0xFF) * aW11 + 0.5);
			return (aR << 16) | (aG << 8) | aBl;
		}

		/**
		 * Part of the second color (0..1) at the point (aU, aV) of a tile, both in [0, 1[
		 */
		static public double mix(String aTexture, double aU, double aV) {
			switch(aTexture) {
			case CHECKER: return (aU < 0.5) == (aV < 0.5) ? 0 : 1;
			case "vstripes": return aU < 0.5 ? 0 : 1;
			case "hstripes": return aV < 0.5 ? 0 : 1;
			case "diagonal": return frac(aU + aV) < 0.5 ? 0 : 1;
			case "chevrons": return frac(aV + Math.abs(aU - 0.5)) < 0.5 ? 0 : 1;
			case "dots": {
				double aDX = aU - 0.5, aDY = aV - 0.5;
				return aDX * aDX + aDY * aDY < 0.09 ? 1 : 0;
			}
			case "grid": return aU < 0.1 || aV < 0.1 ? 1 : 0;
			case "bricks": {
				//Two rows of bricks per tile, the second one shifted by half a brick
				double aRowV = aV < 0.5 ? aV : aV - 0.5;
				double aRowU = aV < 0.5 ? aU : frac(aU + 0.5);
				return aRowV < 0.06 || aRowU < 0.03 ? 1 : 0;
			}
			case "diamonds": return Math.abs(aU - 0.5) + Math.abs(aV - 0.5) < 0.5 ? 1 : 0;
			case "waves": return frac(aV + 0.15 * Math.sin(2 * Math.PI * aU)) < 0.5 ? 0 : 1;
			case "rings": {
				double aDX = aU - 0.5, aDY = aV - 0.5;
				return frac(Math.sqrt(aDX * aDX + aDY * aDY) * 4) < 0.5 ? 0 : 1;
			}
			//Gradients mirrored in the tile: no seam between the tiles
			case "hgradient": return 1 - Math.abs(2 * aU - 1);
			case "vgradient": return 1 - Math.abs(2 * aV - 1);
			case "radial": {
				double aDX = aU - 0.5, aDY = aV - 0.5;
				return Math.min(1, 2 * Math.sqrt(aDX * aDX + aDY * aDY));
			}
			default: return 0;
			}
		}

		static double frac(double aD) {
			return aD - Math.floor(aD);
		}

		/**
		 * Fills the whole image (TYPE_INT_ARGB), the tiles sized after its height
		 */
		public void paint(BufferedImage aBI) {
			paint(aBI, aBI.getHeight());
		}

		/**
		 * Fills the whole image (TYPE_INT_ARGB), the tiles sized after aRefH: the top left
		 * part of an image of that height, at 100 % (preview)
		 */
		public void paint(BufferedImage aBI, int aRefH) {
			int aW = aBI.getWidth();
			int aH = aBI.getHeight();
			int[] aPix = ((DataBufferInt)aBI.getRaster().getDataBuffer()).getData();
			int aC1 = 0xFF000000 | color.getRGB();
			if(isPlain()) {
				java.util.Arrays.fill(aPix, aC1);
				return;
			}
			double aTW = Math.max(0.5, tileW * aRefH / 100.0);
			double aTH = Math.max(0.5, tileH * aRefH / 100.0);
			double aOX = tileX / 100.0 * aTW;
			double aOY = tileY / 100.0 * aTH;
			//Rotation of the texture: the image points are turned back into the texture (y down:
			//-sin for a counterclockwise turn on the screen)
			double aA = Math.toRadians(tileRot);
			double aCos = Math.cos(aA), aSin = -Math.sin(aA);
			if(isBitmap()) {
				paintBitmap(aPix, aW, aH, aTW, aTH, aOX, aOY, aCos, aSin);
				return;
			}
			int aR1 = color.getRed(), aG1 = color.getGreen(), aB1 = color.getBlue();
			int aR2 = color2.getRed(), aG2 = color2.getGreen(), aB2 = color2.getBlue();
			//2 x 2 samples per pixel: smooth edges
			double[] aSub = { 0.25, 0.75 };
			for(int y = 0; y < aH; y++) {
				for(int x = 0; x < aW; x++) {
					double aM = 0;
					for(double aSY : aSub) {
						double aPy = y + aSY;
						for(double aSX : aSub) {
							double aPx = x + aSX;
							aM += mix(texture, frac((aCos * aPx + aSin * aPy + aOX) / aTW), frac((-aSin * aPx + aCos * aPy + aOY) / aTH));
						}
					}
					aM /= 4;
					int aR = (int)Math.round(aR1 + (aR2 - aR1) * aM);
					int aG = (int)Math.round(aG1 + (aG2 - aG1) * aM);
					int aB = (int)Math.round(aB1 + (aB2 - aB1) * aM);
					aPix[y * aW + x] = 0xFF000000 | (aR << 16) | (aG << 8) | aB;
				}
			}
		}
	}

	Fill fill = new Fill();
	
	public ColorBck(TDenlive aTDe) {
		super(aTDe);
		fill.color = tde.gui.colorsAwt.BLACK;
		isSizeAuto = true;
	}

	@Override
	public int openProject(Vector<XmlObject> aOs, int o) throws Exception {
		return super.openProject(aOs, o,"ColorBck");
	}

	@Override
	public int openProjectSub(Vector<XmlObject> aOs, int o) throws Exception {
		XmlObject aO = aOs.elementAt(o);
		if(aO instanceof XmlTag) {
			XmlTag aT = (XmlTag)aO;
			if("color".equalsIgnoreCase(aT.tagName)) {
				o++;
				fill.color = new Color(Integer.parseInt(aOs.elementAt(o).text));
			}
			if("color2".equalsIgnoreCase(aT.tagName)) {
				o++;
				fill.color2 = new Color(Integer.parseInt(aOs.elementAt(o).text));
			}
			if("texture".equalsIgnoreCase(aT.tagName)) {
				o++;
				fill.texture = aOs.elementAt(o).text.trim();
				if("file".equals(fill.texture)) {
					//Its path follows in <texturePath>
					fill.texture = Fill.FILE;
				}
			}
			if("texturePath".equalsIgnoreCase(aT.tagName)) {
				o++;
				fill.texture = Fill.FILE + findPath(aOs.elementAt(o).text.trim());
				Fill.addFile(fill.texture);
			}
			if("tileW".equalsIgnoreCase(aT.tagName)) {
				o++;
				fill.tileW = Double.parseDouble(aOs.elementAt(o).text);
			}
			if("tileH".equalsIgnoreCase(aT.tagName)) {
				o++;
				fill.tileH = Double.parseDouble(aOs.elementAt(o).text);
			}
			if("tileX".equalsIgnoreCase(aT.tagName)) {
				o++;
				fill.tileX = Double.parseDouble(aOs.elementAt(o).text);
			}
			if("tileRot".equalsIgnoreCase(aT.tagName)) {
				o++;
				fill.tileRot = Double.parseDouble(aOs.elementAt(o).text);
			}
			if("tileY".equalsIgnoreCase(aT.tagName)) {
				o++;
				fill.tileY = Double.parseDouble(aOs.elementAt(o).text);
			}
		}
		return o;
	}

	@Override
	public void saveProject(StringBuffer aSB) throws Exception {
		super.saveProject(aSB,"ColorBck");
	}

	@Override
	void saveProjectSub(StringBuffer aSB) {
		aSB.append("			<color>" + fill.color.getRGB() + "</color>\n");
		if(!fill.isPlain()) {
			aSB.append("			<color2>" + fill.color2.getRGB() + "</color2>\n");
			if(fill.isFile()) {
				//The path alone in its tag: gathered, packed and renamed like the paths of the medias
				aSB.append("			<texture>file</texture>\n");
				aSB.append("			<texturePath>" + com.cubaix.TDenlive.utils.ProjectFiles.forSave(fill.filePath()) + "</texturePath>\n");
			}
			else {
				aSB.append("			<texture>" + fill.texture + "</texture>\n");
			}
			aSB.append("			<tileW>" + fill.tileW + "</tileW>\n");
			aSB.append("			<tileH>" + fill.tileH + "</tileH>\n");
			aSB.append("			<tileX>" + fill.tileX + "</tileX>\n");
			aSB.append("			<tileY>" + fill.tileY + "</tileY>\n");
			aSB.append("			<tileRot>" + fill.tileRot + "</tileRot>\n");
		}
	}

	public void setColor(int aR,int aG, int aB) {
		fill.color = new Color(aR, aG, aB);
	}

	public void setFill(Fill aFill) {
		fill = aFill.copy();
	}
	
	//Output ratio used for the last image (the color fills the whole output frame)
	double lastProcessedRatio = 0;

	@Override
	public java.util.Vector<String> getFilePaths() {
		//No file, but an image of the disk as texture
		java.util.Vector<String> aPs = new java.util.Vector<String>();
		if(fill.isFile()) {
			aPs.add(fill.filePath());
		}
		return aPs;
	}

	@Override
	public double getDisplayRatio() {
		//No size of its own: follows the output ratio
		return 0;
	}

	@Override
	public void reBuild(int aProcessingMode,long aTime) {
		if(workLeft != null && workRight != null
				&& lastProcessedRes == tde.config.processingResValues[aProcessingMode]
						&& lastProcessedHdr == tde.config.processingHdr[aProcessingMode]
								&& lastProcessedRatio == tde.config.outRatio) {
			return;
		}
		workLeft = workRight = null;
		try {
			reloadOrig(aProcessingMode,aTime);
		}
		catch(Throwable t) {
			t.printStackTrace(System.err);
		}
		getWorkLeft(aProcessingMode,aTime);
		getWorkRight(aProcessingMode,aTime);
		lastProcessedRes = tde.config.processingResValues[aProcessingMode];
		lastProcessedHdr = tde.config.processingHdr[aProcessingMode];
	}

	@Override
	BufferedImage loadImage(String aPath, int aProcessingMode, long aTime, boolean aIsLeft) throws Exception {
		int aHeight = tde.config.processingResValues[aProcessingMode];
		lastProcessedRatio = tde.config.outRatio;
		int aWidth = Math.max(1, (int)(aHeight*lastProcessedRatio));
		BufferedImage aBI = ImageUtils.createImage(aWidth, aHeight);
		fill.paint(aBI);
		if(origWidth <= 0) {
			origWidth = aBI.getWidth();
			origHeight = aBI.getHeight();
		}
		return aBI;
	}

	@Override
	void reloadOrig(int aProcessingMode, long aTime) throws Exception {
		loadedLeft = loadedRight = loadImage(null, aProcessingMode, aTime, true);
		fileName = fill.isPlain() ? "Color" : "Texture";
		buildThumb();
	}

	@Override
	public void loadImageLR(String aPathL, String aPathR, int aProcessingMode, long aTime) throws Exception {
		reloadOrig(aProcessingMode, aTime);
	}

	@Override
	public void loadImageX(String aPath, int aProcessingMode, long aTime) throws Exception {
		reloadOrig(aProcessingMode, aTime);
	}

	@Override
	public void loadImageP(String aPath, int aProcessingMode, long aTime) throws Exception {
		reloadOrig(aProcessingMode, aTime);
	}
}
