package com.cubaix.TDenlive.shapes;

import java.awt.AlphaComposite;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.Stroke;
import java.awt.geom.Path2D;
import java.awt.image.BufferedImage;
import java.util.Vector;

import com.cubaix.TDenlive.utils.ImageUtils;

/**
 * Draws the shapes (AWT), for the rendering and the previews
 */
public class ShapeRenderer {
	/**
	 * Stroke of a shape for a frame of aH pixels high
	 */
	public static Stroke getStroke(Shape aS,double aH) {
		float aW = (float)Math.max(0.5, aS.strokeWidth*aH);
		switch(aS.strokeStyle) {
		case Shape.STYLE_DASHED:
			return new BasicStroke(aW, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 10f, new float[] {4*aW, 2*aW}, 0f);
		case Shape.STYLE_DOTTED:
			return new BasicStroke(aW, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND, 10f, new float[] {0.01f, 2*aW}, 0f);
		case Shape.STYLE_DASHDOT:
			return new BasicStroke(aW, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND, 10f, new float[] {4*aW, 2*aW, 0.01f, 2*aW}, 0f);
		}
		if(aS.isText()) {
			//Glyphs: no miter spikes
			return new BasicStroke(aW, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND);
		}
		return new BasicStroke(aW, aS.isLine() ? BasicStroke.CAP_ROUND : BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 10f);
	}

	/**
	 * Draw the visible shapes in a frame of aW x aH pixels at (aOX,aOY), for the eye aEye
	 * (-1 left, 1 right: shifted by half of their depth, 0: no depth)
	 */
	public static void draw(Graphics2D aG,Vector<Shape> aShapes,double aOX,double aOY,double aW,double aH,int aEye) {
		if(aShapes == null) {
			return;
		}
		aG.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
		aG.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
		aG.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
		for(Shape aS : aShapes) {
			if(aS.hidden || aS.opacity <= 0) {
				continue;
			}
			double aShift = aEye*aS.depth*aW/2;
			aG.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, (float)Math.min(1.0, aS.opacity)));
			Path2D.Double aP = aS.buildPath(aOX, aOY, aW, aH, aShift);
			if(aS.filled && !aS.isLine()) {
				aG.setColor(new Color(aS.fillColor, true));
				aG.fill(aP);
			}
			if(aS.stroked || aS.isLine()) {
				aG.setColor(new Color(aS.strokeColor, true));
				aG.setStroke(getStroke(aS, aH));
				aG.draw(aP);
				Path2D.Double aHead = aS.buildArrowHead(aOX, aOY, aW, aH, aShift);
				if(aHead != null) {
					aG.setStroke(new BasicStroke((float)Math.max(0.5, aS.strokeWidth*aH), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
					aG.fill(aHead);
					aG.draw(aHead);
				}
			}
		}
		aG.setComposite(AlphaComposite.SrcOver);
	}

	/**
	 * Transparent image of aW x aH with the shapes for the eye aEye
	 */
	public static BufferedImage render(Vector<Shape> aShapes,int aW,int aH,int aEye) {
		BufferedImage aBI = ImageUtils.createImage(Math.max(1, aW), Math.max(1, aH));
		Graphics2D aG = (Graphics2D)aBI.getGraphics();
		draw(aG, aShapes, 0, 0, aW, aH, aEye);
		aG.dispose();
		return aBI;
	}
}
