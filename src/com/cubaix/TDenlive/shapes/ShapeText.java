package com.cubaix.TDenlive.shapes;

import java.awt.Font;
import java.awt.GraphicsEnvironment;
import java.awt.font.FontRenderContext;
import java.awt.font.GlyphVector;
import java.awt.font.LineMetrics;
import java.awt.geom.AffineTransform;
import java.awt.geom.Path2D;
import java.io.InputStream;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeSet;

import com.cubaix.TDenlive.res.FontsAwt;

/**
 * Outlines of the text of the TEXT shapes (AWT glyphs): the lines of the text laid out
 * with their font, in a unit box (0,0)-(1,1) that the shape maps on its 4 points.
 * The Roboto fonts of the application are registered, so that they are found everywhere,
 * and the fonts copied with the project (see ProjectFonts).
 */
public class ShapeText {
	public static final String DEFAULT_FONT = "Roboto";
	static final float FONT_SIZE = 100f;
	static final FontRenderContext FRC = new FontRenderContext(null, true, true);
	static final String[] EMBEDDED = {"Roboto-Regular.ttf","Roboto-Bold.ttf","Roboto-Italic.ttf","Roboto-BoldItalic.ttf"
			,"Roboto-Light.ttf","Roboto-LightItalic.ttf","Roboto-Medium.ttf","Roboto-MediumItalic.ttf"
			,"Roboto-Thin.ttf","Roboto-ThinItalic.ttf","Roboto-Black.ttf","Roboto-BlackItalic.ttf"};
	static boolean registered = false;
	static String[] families = null;

	/**
	 * Text laid out in a unit box: outline and width / height of the text block
	 */
	static class Layout {
		Path2D.Double unit = new Path2D.Double();
		double ratio = 1.0;
		//Height of the text block / height of one line
		double linesFactor = 1.0;
	}

	//Last layouts (a shape is copied at each frame: the cache is by content)
	static final Map<String,Layout> CACHE = new LinkedHashMap<String,Layout>(64, 0.75f, true) {
		private static final long serialVersionUID = 1L;
		@Override
		protected boolean removeEldestEntry(Map.Entry<String,Layout> aE) {
			return size() > 200;
		}
	};

	static synchronized void register() {
		if(registered) {
			return;
		}
		registered = true;
		GraphicsEnvironment aGE = GraphicsEnvironment.getLocalGraphicsEnvironment();
		for(String aF : EMBEDDED) {
			try(InputStream aIS = FontsAwt.class.getResourceAsStream("fonts/"+aF)) {
				if(aIS != null) {
					aGE.registerFont(Font.createFont(Font.TRUETYPE_FONT, aIS));
				}
			}
			catch(Throwable t) {
				t.printStackTrace(System.err);
			}
		}
	}

	/**
	 * New fonts registered (see ProjectFonts): the families and the layouts are computed again
	 */
	static void fontsChanged() {
		synchronized (ShapeText.class) {
			families = null;
		}
		synchronized (CACHE) {
			CACHE.clear();
		}
	}

	/**
	 * Font families available (sorted), with the embedded ones
	 */
	public static synchronized String[] getFamilies() {
		if(families != null) {
			return families;
		}
		register();
		TreeSet<String> aSet = new TreeSet<String>(String.CASE_INSENSITIVE_ORDER);
		try {
			aSet.addAll(Arrays.asList(GraphicsEnvironment.getLocalGraphicsEnvironment().getAvailableFontFamilyNames()));
		}
		catch(Throwable t) {
			t.printStackTrace(System.err);
		}
		aSet.add(DEFAULT_FONT);
		families = aSet.toArray(new String[0]);
		return families;
	}

	static Layout layout(Shape aS) {
		String aKey = aS.fontName+"\u0000"+aS.bold+"\u0000"+aS.italic+"\u0000"+aS.align+"\u0000"+aS.text;
		synchronized (CACHE) {
			Layout aL = CACHE.get(aKey);
			if(aL != null) {
				return aL;
			}
		}
		register();
		Layout aL = new Layout();
		try {
			Font aF = new Font(aS.fontName == null || aS.fontName.isEmpty() ? DEFAULT_FONT : aS.fontName
					, (aS.bold ? Font.BOLD : 0) | (aS.italic ? Font.ITALIC : 0), 1).deriveFont(FONT_SIZE);
			String[] aLines = (aS.text == null ? "" : aS.text).replace("\r", "").split("\n", -1);
			LineMetrics aLM = aF.getLineMetrics("Hg", FRC);
			double aLineH = aLM.getAscent()+aLM.getDescent();
			double aStep = aLineH+aLM.getLeading();
			GlyphVector[] aGVs = new GlyphVector[aLines.length];
			double[] aWs = new double[aLines.length];
			double aW = 0;
			for(int l = 0;l < aLines.length;l++) {
				if(aLines[l].isEmpty()) {
					continue;
				}
				aGVs[l] = aF.createGlyphVector(FRC, aLines[l]);
				aWs[l] = aGVs[l].getLogicalBounds().getWidth();
				aW = Math.max(aW, aWs[l]);
			}
			if(aW <= 0) {
				aW = aLineH/2;
			}
			double aH = aStep*(aLines.length-1)+aLineH;
			Path2D.Double aP = new Path2D.Double(Path2D.WIND_NON_ZERO);
			for(int l = 0;l < aLines.length;l++) {
				if(aGVs[l] == null) {
					continue;
				}
				double aX = 0;
				if(aS.align == Shape.ALIGN_CENTER) {
					aX = (aW-aWs[l])/2;
				}
				else if(aS.align == Shape.ALIGN_RIGHT) {
					aX = aW-aWs[l];
				}
				aP.append(aGVs[l].getOutline((float)aX, (float)(l*aStep+aLM.getAscent())), false);
			}
			aP.transform(AffineTransform.getScaleInstance(1.0/aW, 1.0/aH));
			aL.unit = aP;
			aL.ratio = aW/aH;
			aL.linesFactor = aH/aLineH;
		}
		catch(Throwable t) {
			t.printStackTrace(System.err);
		}
		synchronized (CACHE) {
			CACHE.put(aKey, aL);
		}
		return aL;
	}

	/**
	 * Width / height of the text block of the shape
	 */
	public static double getRatio(Shape aS) {
		return layout(aS).ratio;
	}

	/**
	 * Height of the text block of the shape / height of one of its lines (1 for one line)
	 */
	public static double getLinesFactor(Shape aS) {
		return layout(aS).linesFactor;
	}

	/**
	 * Outline of the text in the parallelogram of the points (pixels): unit (0,0) -> aX0,aY0,
	 * (1,0) -> aX1,aY1, (0,1) -> aX3,aY3
	 */
	static Path2D.Double outline(Shape aS,double aX0,double aY0,double aX1,double aY1,double aX3,double aY3) {
		Path2D.Double aP = new Path2D.Double(layout(aS).unit);
		aP.transform(new AffineTransform(aX1-aX0, aY1-aY0, aX3-aX0, aY3-aY0, aX0, aY0));
		return aP;
	}
}
