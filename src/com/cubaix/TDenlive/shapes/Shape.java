package com.cubaix.TDenlive.shapes;

import java.awt.geom.Path2D;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Vector;

import com.cubaix.TDenlive.xml.XmlObject;
import com.cubaix.TDenlive.xml.XmlTag;

/**
 * A vector shape of a shapes clip: its vertices are in frame coordinates
 * (x: 0..1 of the width, y: 0..1 of the height), so that it does not depend on
 * the processing resolution. The same shape (same id) is found in every key
 * of the Shapes processor, which interpolates it between the keys.
 */
public class Shape {
	public static final int RECT = 0;
	public static final int ELLIPSE = 1;
	public static final int TRIANGLE = 2;
	public static final int RTRIANGLE = 3;
	public static final int DIAMOND = 4;
	public static final int HEXAGON = 5;
	public static final int STAR = 6;
	public static final int ARROW = 7;
	public static final int LINE = 8;
	public static final int LINE_ARROW = 9;
	//Text in the box of its 4 points (TL, TR, BR, BL), stretched to it
	public static final int TEXT = 10;
	//Label keys (Shapes.<name>) and order of the tools
	public static final String[] TYPE_NAMES = {"Rectangle","Ellipse","Triangle","RightTriangle"
			,"Diamond","Hexagon","Star","Arrow","Line","LineArrow","Text"};

	public static final int ALIGN_LEFT = 0;
	public static final int ALIGN_CENTER = 1;
	public static final int ALIGN_RIGHT = 2;
	public static final String[] ALIGN_NAMES = {"AlignLeft","AlignCenter","AlignRight"};
	public static final String DEFAULT_TEXT = "Text";

	public static final int STYLE_SOLID = 0;
	public static final int STYLE_DASHED = 1;
	public static final int STYLE_DOTTED = 2;
	public static final int STYLE_DASHDOT = 3;
	public static final String[] STYLE_NAMES = {"Solid","Dashed","Dotted","DashDot"};

	//Control points of the ellipse Bezier arcs (circle approximation)
	static final double KAPPA = 0.5522847498;

	public int id = 0;
	public int type = RECT;
	public double[] xs = new double[0];
	public double[] ys = new double[0];
	public boolean filled = true;
	public int fillColor = 0xFF2E6FD8;//ARGB
	public boolean stroked = true;
	public int strokeColor = 0xFFFFFFFF;//ARGB
	public double strokeWidth = 0.006;//Part of the frame height
	public int strokeStyle = STYLE_SOLID;
	public double depth = 0;//Parallax, part of the frame width (> 0: behind the screen)
	public double opacity = 1.0;
	//Radius of the rounded corners, part of the frame height (0: sharp corners). Polygons and rectangles only
	public double cornerRadius = 0;
	//Deleted from the clip (from its key on), kept to be restored
	public boolean hidden = false;
	//TEXT: the text (lines separated by \n) and its font
	public String text = DEFAULT_TEXT;
	public String fontName = ShapeText.DEFAULT_FONT;
	public boolean bold = false;
	public boolean italic = false;
	public int align = ALIGN_CENTER;

	public Shape() {
	}

	public Shape copy() {
		Shape aS = new Shape();
		aS.id = id;
		aS.type = type;
		aS.xs = xs.clone();
		aS.ys = ys.clone();
		aS.copyStyle(this);
		aS.hidden = hidden;
		return aS;
	}

	/**
	 * Colors, border, corners, depth, opacity, text and font of aS (not the geometry, not the visibility)
	 */
	public void copyStyle(Shape aS) {
		filled = aS.filled;
		fillColor = aS.fillColor;
		stroked = aS.stroked;
		strokeColor = aS.strokeColor;
		strokeWidth = aS.strokeWidth;
		strokeStyle = aS.strokeStyle;
		depth = aS.depth;
		opacity = aS.opacity;
		cornerRadius = aS.cornerRadius;
		text = aS.text;
		fontName = aS.fontName;
		bold = aS.bold;
		italic = aS.italic;
		align = aS.align;
	}

	public boolean isLine() {
		return type == LINE || type == LINE_ARROW;
	}

	/**
	 * Its corners can be rounded (not the ellipses, lines and texts)
	 */
	public boolean canRound() {
		return type != ELLIPSE && type != TEXT && !isLine();
	}

	public boolean isText() {
		return type == TEXT && xs.length == 4;
	}

	/**
	 * Same text and font as aS (what changes the layout of the glyphs)
	 */
	public boolean sameText(Shape aS) {
		return aS != null && java.util.Objects.equals(text, aS.text) && java.util.Objects.equals(fontName, aS.fontName)
				&& bold == aS.bold && italic == aS.italic && align == aS.align;
	}

	/**
	 * TEXT: width / height of the text block (font units)
	 */
	public double textRatio() {
		return ShapeText.getRatio(this);
	}

	/**
	 * TEXT: after a change of the text or of the font, the width of the box follows the
	 * text (its height, top left corner and orientation stay). aFrameRatio is width / height of the frame
	 */
	public void fitText(double aFrameRatio) {
		if(!isText() || aFrameRatio <= 0) {
			return;
		}
		//In pixels of a frame of height 1
		double aV1X = (xs[1]-xs[0])*aFrameRatio;
		double aV1Y = ys[1]-ys[0];
		double aV3X = (xs[3]-xs[0])*aFrameRatio;
		double aV3Y = ys[3]-ys[0];
		double aHLen = Math.hypot(aV3X, aV3Y);
		double aWLen = Math.hypot(aV1X, aV1Y);
		if(aHLen < 1e-9) {
			return;
		}
		double aNewW = aHLen*textRatio();
		if(aWLen < 1e-9) {
			//Perpendicular to the height
			aV1X = aV3Y;
			aV1Y = -aV3X;
			aWLen = aHLen;
		}
		aV1X *= aNewW/aWLen;
		aV1Y *= aNewW/aWLen;
		xs[1] = xs[0]+aV1X/aFrameRatio;
		ys[1] = ys[0]+aV1Y;
		xs[2] = xs[3]+aV1X/aFrameRatio;
		ys[2] = ys[3]+aV1Y;
	}

	/**
	 * TEXT: replace the text, the box keeps the height of a line, the stretch and the orientation
	 * of the text, and the middle (or the left / right end, with the alignment) of its bottom side:
	 * a text of several lines grows upward. aFrameRatio is width / height of the frame
	 */
	public void setTextKeepLines(String aText,double aFrameRatio) {
		if(!isText() || aFrameRatio <= 0) {
			text = aText;
			return;
		}
		//In pixels of a frame of height 1
		double aV1X = (xs[1]-xs[0])*aFrameRatio;
		double aV1Y = ys[1]-ys[0];
		double aV3X = (xs[3]-xs[0])*aFrameRatio;
		double aV3Y = ys[3]-ys[0];
		double aW = Math.hypot(aV1X, aV1Y);
		double aH = Math.hypot(aV3X, aV3Y);
		double aRatio = textRatio();
		if(aW < 1e-9 || aH < 1e-9 || aRatio <= 0) {
			text = aText;
			fitText(aFrameRatio);
			return;
		}
		double aStretch = aW/(aH*aRatio);
		double aLineH = aH/ShapeText.getLinesFactor(this);
		double aUX = aV1X/aW;
		double aUY = aV1Y/aW;
		double aU3X = aV3X/aH;
		double aU3Y = aV3Y/aH;
		//Anchor on the bottom side
		double aA = align == ALIGN_LEFT ? 0 : (align == ALIGN_RIGHT ? 1 : 0.5);
		double aBX = xs[3]*aFrameRatio+aV1X*aA;
		double aBY = ys[3]+aV1Y*aA;
		text = aText;
		double aNewH = aLineH*ShapeText.getLinesFactor(this);
		double aNewW = aNewH*textRatio()*aStretch;
		double aX3 = aBX-aUX*aNewW*aA;
		double aY3 = aBY-aUY*aNewW*aA;
		double aX0 = aX3-aU3X*aNewH;
		double aY0 = aY3-aU3Y*aNewH;
		xs[0] = aX0/aFrameRatio;
		ys[0] = aY0;
		xs[1] = (aX0+aUX*aNewW)/aFrameRatio;
		ys[1] = aY0+aUY*aNewW;
		xs[2] = (aX3+aUX*aNewW)/aFrameRatio;
		ys[2] = aY3+aUY*aNewW;
		xs[3] = aX3/aFrameRatio;
		ys[3] = aY3;
	}

	public int nbPoints() {
		return xs.length;
	}

	/**
	 * Vertices of a type in a unit box (0,0)-(1,1)
	 */
	static double[][] template(int aType) {
		switch(aType) {
		case ELLIPSE:
		case RECT:
			return new double[][] {{0,1,1,0},{0,0,1,1}};
		case TRIANGLE:
			return new double[][] {{0.5,1,0},{0,1,1}};
		case RTRIANGLE:
			return new double[][] {{0,1,0},{0,1,1}};
		case DIAMOND:
			return new double[][] {{0.5,1,0.5,0},{0,0.5,1,0.5}};
		case HEXAGON:
			return new double[][] {{0.25,0.75,1,0.75,0.25,0},{0,0,0.5,1,1,0.5}};
		case STAR: {
			double[] aX = new double[10];
			double[] aY = new double[10];
			for(int p = 0;p < 10;p++) {
				double aR = (p%2 == 0) ? 0.5 : 0.2;
				double aA = -Math.PI/2+p*Math.PI/5;
				aX[p] = 0.5+aR*Math.cos(aA);
				aY[p] = 0.5+aR*Math.sin(aA)*1.05+0.025;
			}
			return new double[][] {aX,aY};
		}
		case ARROW:
			return new double[][] {{0,0.6,0.6,1,0.6,0.6,0},{0.3,0.3,0,0.5,1,0.7,0.7}};
		case LINE:
		case LINE_ARROW:
			return new double[][] {{0,1},{0,1}};
		}
		return new double[][] {{0,1,1,0},{0,0,1,1}};
	}

	/**
	 * New shape of type aType filling the box from (aX0,aY0) to (aX1,aY1) (frame coordinates,
	 * a line goes from the first point to the second), with the style of aStyle (may be null)
	 */
	public static Shape create(int aType,double aX0,double aY0,double aX1,double aY1,Shape aStyle) {
		Shape aS = new Shape();
		aS.type = aType;
		if(aStyle != null) {
			aS.copyStyle(aStyle);
		}
		if(aType == TEXT && (aS.text == null || aS.text.trim().isEmpty())) {
			aS.text = DEFAULT_TEXT;
		}
		if(!aS.isLine()) {
			double aMinX = Math.min(aX0, aX1);
			double aMaxX = Math.max(aX0, aX1);
			double aMinY = Math.min(aY0, aY1);
			double aMaxY = Math.max(aY0, aY1);
			aX0 = aMinX;aX1 = aMaxX;aY0 = aMinY;aY1 = aMaxY;
		}
		else {
			//A line needs a visible border
			aS.stroked = true;
		}
		double[][] aT = template(aType);
		aS.xs = new double[aT[0].length];
		aS.ys = new double[aT[0].length];
		for(int p = 0;p < aS.xs.length;p++) {
			aS.xs[p] = aX0+aT[0][p]*(aX1-aX0);
			aS.ys[p] = aY0+aT[1][p]*(aY1-aY0);
		}
		return aS;
	}

	/**
	 * Outline of the shape in a frame of aW x aH pixels whose top left corner is at (aOX,aOY),
	 * horizontally shifted by aShiftX pixels (stereo depth). For the lines with an arrow, without its head
	 */
	public Path2D.Double buildPath(double aOX,double aOY,double aW,double aH,double aShiftX) {
		Path2D.Double aP = new Path2D.Double();
		int aN = xs.length;
		if(aN <= 0) {
			return aP;
		}
		double[] aX = new double[aN];
		double[] aY = new double[aN];
		for(int p = 0;p < aN;p++) {
			aX[p] = aOX+aShiftX+xs[p]*aW;
			aY[p] = aOY+ys[p]*aH;
		}
		if(isLine()) {
			aP.moveTo(aX[0], aY[0]);
			for(int p = 1;p < aN;p++) {
				aP.lineTo(aX[p], aY[p]);
			}
			return aP;
		}
		if(type == ELLIPSE && aN == 4) {
			//Oval inscribed in the quadrilateral: from the middle of a side to the middle of the next one
			double[] aMX = new double[4];
			double[] aMY = new double[4];
			for(int p = 0;p < 4;p++) {
				aMX[p] = (aX[p]+aX[(p+1)%4])/2;
				aMY[p] = (aY[p]+aY[(p+1)%4])/2;
			}
			aP.moveTo(aMX[3], aMY[3]);
			for(int p = 0;p < 4;p++) {
				int aPrev = (p+3)%4;
				aP.curveTo(aMX[aPrev]+KAPPA*(aX[p]-aMX[aPrev]), aMY[aPrev]+KAPPA*(aY[p]-aMY[aPrev])
						, aMX[p]+KAPPA*(aX[p]-aMX[p]), aMY[p]+KAPPA*(aY[p]-aMY[p])
						, aMX[p], aMY[p]);
			}
			aP.closePath();
			return aP;
		}
		if(isText()) {
			return ShapeText.outline(this, aX[0], aY[0], aX[1], aY[1], aX[3], aY[3]);
		}
		if(cornerRadius > 0 && aN >= 3 && canRound()) {
			return roundedPolygon(aX, aY, cornerRadius*aH);
		}
		aP.moveTo(aX[0], aY[0]);
		for(int p = 1;p < aN;p++) {
			aP.lineTo(aX[p], aY[p]);
		}
		aP.closePath();
		return aP;
	}

	/**
	 * Closed polygon (pixels) whose corners are circle arcs of radius aR (pixels), each arc
	 * reduced so that it takes at most half of its two sides
	 */
	static Path2D.Double roundedPolygon(double[] aX,double[] aY,double aR) {
		int aN = aX.length;
		//Start and end of the arc of each corner, and its Bezier control points
		double[] aSX = new double[aN];
		double[] aSY = new double[aN];
		double[] aEX = new double[aN];
		double[] aEY = new double[aN];
		double[] aK = new double[aN];
		for(int p = 0;p < aN;p++) {
			int aPrev = (p+aN-1)%aN;
			int aNext = (p+1)%aN;
			double aPX = aX[aPrev]-aX[p];
			double aPY = aY[aPrev]-aY[p];
			double aNX = aX[aNext]-aX[p];
			double aNY = aY[aNext]-aY[p];
			double aPL = Math.hypot(aPX, aPY);
			double aNL = Math.hypot(aNX, aNY);
			aSX[p] = aEX[p] = aX[p];
			aSY[p] = aEY[p] = aY[p];
			aK[p] = 0;
			if(aPL < 1e-9 || aNL < 1e-9) {
				continue;
			}
			//Angle at the vertex, and turn of the outline (angle of the arc)
			double aCos = Math.max(-1, Math.min(1, (aPX*aNX+aPY*aNY)/(aPL*aNL)));
			double aTurn = Math.PI-Math.acos(aCos);
			if(aTurn < 1e-6 || aTurn > Math.PI-1e-6) {
				continue;
			}
			//Distance from the vertex to the points where the arc touches the sides
			double aT = Math.min(aR*Math.tan(aTurn/2), Math.min(aPL, aNL)/2);
			aSX[p] = aX[p]+aPX/aPL*aT;
			aSY[p] = aY[p]+aPY/aPL*aT;
			aEX[p] = aX[p]+aNX/aNL*aT;
			aEY[p] = aY[p]+aNY/aNL*aT;
			//Cubic approximation of the arc: control points toward the vertex
			aK[p] = 4.0/3.0*Math.tan(aTurn/4)/Math.tan(aTurn/2);
		}
		Path2D.Double aP = new Path2D.Double();
		aP.moveTo(aEX[aN-1], aEY[aN-1]);
		for(int p = 0;p < aN;p++) {
			aP.lineTo(aSX[p], aSY[p]);
			if(aK[p] > 0) {
				aP.curveTo(aSX[p]+aK[p]*(aX[p]-aSX[p]), aSY[p]+aK[p]*(aY[p]-aSY[p])
						, aEX[p]+aK[p]*(aX[p]-aEX[p]), aEY[p]+aK[p]*(aY[p]-aEY[p])
						, aEX[p], aEY[p]);
			}
		}
		aP.closePath();
		return aP;
	}

	/**
	 * Polygon of the points (the box of a text), for the selection
	 */
	public Path2D.Double buildPolygon(double aOX,double aOY,double aW,double aH,double aShiftX) {
		Path2D.Double aP = new Path2D.Double();
		for(int p = 0;p < xs.length;p++) {
			if(p == 0) {
				aP.moveTo(aOX+aShiftX+xs[p]*aW, aOY+ys[p]*aH);
			}
			else {
				aP.lineTo(aOX+aShiftX+xs[p]*aW, aOY+ys[p]*aH);
			}
		}
		aP.closePath();
		return aP;
	}

	/**
	 * Head of a line with an arrow (a triangle at its last point), null for the other types
	 */
	public Path2D.Double buildArrowHead(double aOX,double aOY,double aW,double aH,double aShiftX) {
		if(type != LINE_ARROW || xs.length < 2) {
			return null;
		}
		int aL = xs.length-1;
		double aX1 = aOX+aShiftX+xs[aL]*aW;
		double aY1 = aOY+ys[aL]*aH;
		double aX0 = aOX+aShiftX+xs[aL-1]*aW;
		double aY0 = aOY+ys[aL-1]*aH;
		double aLen = Math.hypot(aX1-aX0, aY1-aY0);
		if(aLen <= 0) {
			return null;
		}
		double aSize = Math.max(4*strokeWidth*aH, 0.025*aH);
		aSize = Math.min(aSize, aLen*0.8);
		double aUX = (aX1-aX0)/aLen;
		double aUY = (aY1-aY0)/aLen;
		Path2D.Double aP = new Path2D.Double();
		aP.moveTo(aX1, aY1);
		aP.lineTo(aX1-aSize*aUX-aSize*0.5*aUY, aY1-aSize*aUY+aSize*0.5*aUX);
		aP.lineTo(aX1-aSize*aUX+aSize*0.5*aUY, aY1-aSize*aUY-aSize*0.5*aUX);
		aP.closePath();
		return aP;
	}

	/**
	 * Box of the vertices: {minX, minY, maxX, maxY} (frame coordinates)
	 */
	public double[] getBox() {
		double[] aB = {Double.MAX_VALUE,Double.MAX_VALUE,-Double.MAX_VALUE,-Double.MAX_VALUE};
		for(int p = 0;p < xs.length;p++) {
			aB[0] = Math.min(aB[0], xs[p]);
			aB[1] = Math.min(aB[1], ys[p]);
			aB[2] = Math.max(aB[2], xs[p]);
			aB[3] = Math.max(aB[3], ys[p]);
		}
		return aB;
	}

	static int lerpColor(int aC0,int aC1,double aF) {
		int aC = 0;
		for(int s = 0;s < 32;s += 8) {
			int aV0 = (aC0>>s) & 0xFF;
			int aV1 = (aC1>>s) & 0xFF;
			int aV = (int)Math.round(aV0*(1.0-aF)+aV1*aF);
			aC |= (aV & 0xFF) << s;
		}
		return aC;
	}

	/**
	 * Shape between a0 (aF = 0) and a1 (aF = 1): geometry, colors, width, depth, opacity and corner radius
	 * are interpolated, the type, styles and visibility are those of a0
	 */
	public static Shape lerp(Shape a0,Shape a1,double aF) {
		return lerp(a0, a1, aF, 0);
	}

	/**
	 * Same, aFrameRatio (width / height of the frame, 0 if unknown) for the texts: when the text
	 * changes at the next key, the text of a0 keeps its proportions (its box is not stretched toward
	 * the width of the next text), its height, position and orientation are interpolated
	 */
	public static Shape lerp(Shape a0,Shape a1,double aF,double aFrameRatio) {
		Shape aS = lerpBase(a0, a1, aF);
		if(aFrameRatio > 0 && aF > 0 && a1 != null && aS.isText() && a1.isText() && !a0.sameText(a1)) {
			double aS0 = a0.textStretch(aFrameRatio);
			double aS1 = a1.textStretch(aFrameRatio);
			if(aS0 > 0 && aS1 > 0) {
				aS.keepTextProportions(aFrameRatio, aS0*(1.0-aF)+aS1*aF);
			}
		}
		return aS;
	}

	/**
	 * TEXT: width of the box / width of the text at the height of the box (1: not stretched), 0 if flat
	 */
	double textStretch(double aFrameRatio) {
		double aW = Math.hypot((xs[1]-xs[0])*aFrameRatio, ys[1]-ys[0]);
		double aH = Math.hypot((xs[3]-xs[0])*aFrameRatio, ys[3]-ys[0]);
		double aR = textRatio();
		return aH < 1e-9 || aR <= 0 ? 0 : aW/(aH*aR);
	}

	/**
	 * TEXT: width of the box from its height, the text and the stretch aStretch, around the
	 * anchor of the alignment (left side, middle, right side)
	 */
	void keepTextProportions(double aFrameRatio,double aStretch) {
		double aV1X = (xs[1]-xs[0])*aFrameRatio;
		double aV1Y = ys[1]-ys[0];
		double aV3X = (xs[3]-xs[0])*aFrameRatio;
		double aV3Y = ys[3]-ys[0];
		double aW = Math.hypot(aV1X, aV1Y);
		double aH = Math.hypot(aV3X, aV3Y);
		if(aW < 1e-9 || aH < 1e-9) {
			return;
		}
		double aNewW = aH*textRatio()*aStretch;
		double aUX = aV1X/aW;
		double aUY = aV1Y/aW;
		//Anchor on the top side, in pixels of a frame of height 1
		double aA = align == ALIGN_LEFT ? 0 : (align == ALIGN_RIGHT ? 1 : 0.5);
		double aX0 = xs[0]*aFrameRatio+aV1X*aA-aUX*aNewW*aA;
		double aY0 = ys[0]+aV1Y*aA-aUY*aNewW*aA;
		double aX1 = aX0+aUX*aNewW;
		double aY1 = aY0+aUY*aNewW;
		xs[0] = aX0/aFrameRatio;
		ys[0] = aY0;
		xs[1] = aX1/aFrameRatio;
		ys[1] = aY1;
		xs[2] = (aX1+aV3X)/aFrameRatio;
		ys[2] = aY1+aV3Y;
		xs[3] = (aX0+aV3X)/aFrameRatio;
		ys[3] = aY0+aV3Y;
	}

	static Shape lerpBase(Shape a0,Shape a1,double aF) {
		Shape aS = a0.copy();
		if(a1 == null || aF <= 0) {
			return aS;
		}
		if(a1.xs.length == a0.xs.length) {
			for(int p = 0;p < aS.xs.length;p++) {
				aS.xs[p] = a0.xs[p]*(1.0-aF)+a1.xs[p]*aF;
				aS.ys[p] = a0.ys[p]*(1.0-aF)+a1.ys[p]*aF;
			}
		}
		aS.fillColor = lerpColor(a0.fillColor, a1.fillColor, aF);
		aS.strokeColor = lerpColor(a0.strokeColor, a1.strokeColor, aF);
		aS.strokeWidth = a0.strokeWidth*(1.0-aF)+a1.strokeWidth*aF;
		aS.depth = a0.depth*(1.0-aF)+a1.depth*aF;
		aS.opacity = a0.opacity*(1.0-aF)+a1.opacity*aF;
		aS.cornerRadius = a0.cornerRadius*(1.0-aF)+a1.cornerRadius*aF;
		return aS;
	}

	public static Shape findById(Vector<Shape> aShapes,int aId) {
		if(aShapes == null) {
			return null;
		}
		for(Shape aS : aShapes) {
			if(aS.id == aId) {
				return aS;
			}
		}
		return null;
	}

	public static Vector<Shape> copyAll(Vector<Shape> aShapes) {
		Vector<Shape> aCopy = new Vector<Shape>();
		if(aShapes != null) {
			for(Shape aS : aShapes) {
				aCopy.add(aS.copy());
			}
		}
		return aCopy;
	}

	/**
	 * Free text in the project: URL encoded (the XML parser cuts on < > [ ] and drops the
	 * empty texts), after a '=' so that it is never empty
	 */
	public static String encode(String aS) {
		return "="+URLEncoder.encode(aS == null ? "" : aS, StandardCharsets.UTF_8);
	}

	public static String decode(String aS) {
		if(aS.startsWith("=")) {
			aS = aS.substring(1);
		}
		return URLDecoder.decode(aS, StandardCharsets.UTF_8);
	}

	public void saveProject(StringBuffer aSB,String aIndent) {
		StringBuffer aPts = new StringBuffer();
		for(int p = 0;p < xs.length;p++) {
			if(p > 0) {
				aPts.append(';');
			}
			aPts.append(xs[p]).append(',').append(ys[p]);
		}
		aSB.append(aIndent+"<Shape>\n");
		aSB.append(aIndent+"	<id>" + id + "</id>\n");
		aSB.append(aIndent+"	<type>" + type + "</type>\n");
		aSB.append(aIndent+"	<pts>" + aPts + "</pts>\n");
		aSB.append(aIndent+"	<filled>" + filled + "</filled>\n");
		aSB.append(aIndent+"	<fillColor>" + fillColor + "</fillColor>\n");
		aSB.append(aIndent+"	<stroked>" + stroked + "</stroked>\n");
		aSB.append(aIndent+"	<strokeColor>" + strokeColor + "</strokeColor>\n");
		aSB.append(aIndent+"	<strokeWidth>" + strokeWidth + "</strokeWidth>\n");
		aSB.append(aIndent+"	<strokeStyle>" + strokeStyle + "</strokeStyle>\n");
		aSB.append(aIndent+"	<depth>" + depth + "</depth>\n");
		aSB.append(aIndent+"	<opacity>" + opacity + "</opacity>\n");
		aSB.append(aIndent+"	<hidden>" + hidden + "</hidden>\n");
		if(cornerRadius > 0) {
			aSB.append(aIndent+"	<cornerRadius>" + cornerRadius + "</cornerRadius>\n");
		}
		if(type == TEXT) {
			aSB.append(aIndent+"	<text>" + encode(text) + "</text>\n");
			aSB.append(aIndent+"	<fontName>" + encode(fontName) + "</fontName>\n");
			aSB.append(aIndent+"	<bold>" + bold + "</bold>\n");
			aSB.append(aIndent+"	<italic>" + italic + "</italic>\n");
			aSB.append(aIndent+"	<align>" + align + "</align>\n");
		}
		aSB.append(aIndent+"</Shape>\n");
	}

	/**
	 * Read from the <Shape> tag at o, returns the index of </Shape>
	 */
	public int openProject(Vector<XmlObject> aOs,int o) throws Exception {
		for(o++;o < aOs.size();o++) {
			XmlObject aO = aOs.elementAt(o);
			if(!(aO instanceof XmlTag)) {
				continue;
			}
			XmlTag aT = (XmlTag)aO;
			if("/Shape".equalsIgnoreCase(aT.tagName)) {
				return o;
			}
			if("id".equalsIgnoreCase(aT.tagName)) {
				id = Integer.parseInt(aOs.elementAt(++o).text.trim());
			}
			if("type".equalsIgnoreCase(aT.tagName)) {
				type = Integer.parseInt(aOs.elementAt(++o).text.trim());
			}
			if("pts".equalsIgnoreCase(aT.tagName)) {
				String[] aPts = aOs.elementAt(++o).text.trim().split(";");
				xs = new double[aPts.length];
				ys = new double[aPts.length];
				for(int p = 0;p < aPts.length;p++) {
					String[] aXY = aPts[p].split(",");
					xs[p] = Double.parseDouble(aXY[0]);
					ys[p] = Double.parseDouble(aXY[1]);
				}
			}
			if("filled".equalsIgnoreCase(aT.tagName)) {
				filled = "true".equalsIgnoreCase(aOs.elementAt(++o).text.trim());
			}
			if("fillColor".equalsIgnoreCase(aT.tagName)) {
				fillColor = Integer.parseInt(aOs.elementAt(++o).text.trim());
			}
			if("stroked".equalsIgnoreCase(aT.tagName)) {
				stroked = "true".equalsIgnoreCase(aOs.elementAt(++o).text.trim());
			}
			if("strokeColor".equalsIgnoreCase(aT.tagName)) {
				strokeColor = Integer.parseInt(aOs.elementAt(++o).text.trim());
			}
			if("strokeWidth".equalsIgnoreCase(aT.tagName)) {
				strokeWidth = Double.parseDouble(aOs.elementAt(++o).text.trim());
			}
			if("strokeStyle".equalsIgnoreCase(aT.tagName)) {
				strokeStyle = Integer.parseInt(aOs.elementAt(++o).text.trim());
			}
			if("depth".equalsIgnoreCase(aT.tagName)) {
				depth = Double.parseDouble(aOs.elementAt(++o).text.trim());
			}
			if("opacity".equalsIgnoreCase(aT.tagName)) {
				opacity = Double.parseDouble(aOs.elementAt(++o).text.trim());
			}
			if("hidden".equalsIgnoreCase(aT.tagName)) {
				hidden = "true".equalsIgnoreCase(aOs.elementAt(++o).text.trim());
			}
			if("cornerRadius".equalsIgnoreCase(aT.tagName)) {
				cornerRadius = Double.parseDouble(aOs.elementAt(++o).text.trim());
			}
			if("text".equalsIgnoreCase(aT.tagName)) {
				text = decode(aOs.elementAt(++o).text.trim());
			}
			if("fontName".equalsIgnoreCase(aT.tagName)) {
				fontName = decode(aOs.elementAt(++o).text.trim());
			}
			if("bold".equalsIgnoreCase(aT.tagName)) {
				bold = "true".equalsIgnoreCase(aOs.elementAt(++o).text.trim());
			}
			if("italic".equalsIgnoreCase(aT.tagName)) {
				italic = "true".equalsIgnoreCase(aOs.elementAt(++o).text.trim());
			}
			if("align".equalsIgnoreCase(aT.tagName)) {
				align = Integer.parseInt(aOs.elementAt(++o).text.trim());
			}
		}
		return o;
	}
}
