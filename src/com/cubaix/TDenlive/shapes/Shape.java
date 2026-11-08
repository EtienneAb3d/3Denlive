package com.cubaix.TDenlive.shapes;

import java.awt.geom.Path2D;
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
	//Label keys (Shapes.<name>) and order of the tools
	public static final String[] TYPE_NAMES = {"Rectangle","Ellipse","Triangle","RightTriangle"
			,"Diamond","Hexagon","Star","Arrow","Line","LineArrow"};

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
	//Deleted from the clip (from its key on), kept to be restored
	public boolean hidden = false;

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
	 * Colors, border, depth and opacity of aS (not the geometry, not the visibility)
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
	}

	public boolean isLine() {
		return type == LINE || type == LINE_ARROW;
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
		aP.moveTo(aX[0], aY[0]);
		for(int p = 1;p < aN;p++) {
			aP.lineTo(aX[p], aY[p]);
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
	 * Shape between a0 (aF = 0) and a1 (aF = 1): geometry, colors, width, depth and opacity
	 * are interpolated, the type, styles and visibility are those of a0
	 */
	public static Shape lerp(Shape a0,Shape a1,double aF) {
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
		}
		return o;
	}
}
