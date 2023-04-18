package com.cubaix.TDenlive.salamander;

import static java.awt.RenderingHints.KEY_ALPHA_INTERPOLATION;
import static java.awt.RenderingHints.KEY_ANTIALIASING;
import static java.awt.RenderingHints.KEY_COLOR_RENDERING;
import static java.awt.RenderingHints.KEY_DITHERING;
import static java.awt.RenderingHints.KEY_FRACTIONALMETRICS;
import static java.awt.RenderingHints.KEY_INTERPOLATION;
import static java.awt.RenderingHints.KEY_RENDERING;
import static java.awt.RenderingHints.KEY_STROKE_CONTROL;
import static java.awt.RenderingHints.KEY_TEXT_ANTIALIASING;
import static java.awt.RenderingHints.VALUE_ALPHA_INTERPOLATION_QUALITY;
import static java.awt.RenderingHints.VALUE_ANTIALIAS_ON;
import static java.awt.RenderingHints.VALUE_COLOR_RENDER_QUALITY;
import static java.awt.RenderingHints.VALUE_DITHER_DISABLE;
import static java.awt.RenderingHints.VALUE_FRACTIONALMETRICS_ON;
import static java.awt.RenderingHints.VALUE_INTERPOLATION_BICUBIC;
import static java.awt.RenderingHints.VALUE_RENDER_QUALITY;
import static java.awt.RenderingHints.VALUE_STROKE_PURE;
import static java.awt.RenderingHints.VALUE_TEXT_ANTIALIAS_ON;

import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.Image;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.io.File;
import java.net.URI;
import java.net.URL;
import java.util.HashMap;

import com.cubaix.TDenlive.TDenlive;
import com.cubaix.TDenlive.utils.ImageUtils;
import com.kitfox.svg.SVGDiagram;
import com.kitfox.svg.SVGException;
import com.kitfox.svg.SVGUniverse;

/**
 * Responsible for converting SVG images into rasterized PNG images.
 */
public class SvgRasterizer {
	public final static HashMap<Object, Object> RENDERING_HINTS = new HashMap<Object, Object>();
	static {
		for(Object[] aOs : new Object[][] {
			{KEY_ANTIALIASING,VALUE_ANTIALIAS_ON}
			,{KEY_ALPHA_INTERPOLATION,VALUE_ALPHA_INTERPOLATION_QUALITY}
			,{KEY_COLOR_RENDERING,VALUE_COLOR_RENDER_QUALITY}
			,{KEY_DITHERING,VALUE_DITHER_DISABLE}
			,{KEY_FRACTIONALMETRICS,VALUE_FRACTIONALMETRICS_ON}
			,{KEY_INTERPOLATION,VALUE_INTERPOLATION_BICUBIC}
			,{KEY_RENDERING,VALUE_RENDER_QUALITY}
			,{KEY_STROKE_CONTROL,VALUE_STROKE_PURE}
			,{KEY_TEXT_ANTIALIASING,VALUE_TEXT_ANTIALIAS_ON}}) {
			RENDERING_HINTS.put(aOs[0], aOs[1]);
		}
	}

	TDenlive tde = null;
	public String path = null;
	SvgRasterizer sync = null;
	
	SVGDiagram diagram = null;
	float wDiagram = -1;
	float hDiagram = -1;
	Dimension srcDim = null;

	public SvgRasterizer(TDenlive aTDe,String aPath,SvgRasterizer aSync) {
		tde = aTDe;
		path = aPath;
		sync = aSync;
		
		diagram = loadDiagram(path);
		wDiagram = diagram.getWidth();
		hDiagram = diagram.getHeight();
		srcDim = new Dimension( (int) wDiagram, (int) hDiagram );
	}

	public BufferedImage getImage(int aProcessingMode,long aTimeOffset) {
		try {
			diagram.getUniverse().setCurTime(aTimeOffset/1000.0);
			diagram.getUniverse().updateTime();
		}
		catch(Throwable t) {
			t.printStackTrace(System.err);
		}
		Dimension dstDim = new Dimension((int)(tde.config.processingResValues[aProcessingMode]*tde.config.outRatio)
				, tde.config.processingResValues[aProcessingMode]);
		return rasterize(dstDim);
	}

	private final static SVGUniverse sRenderer = new SVGUniverse();

	/**
	 * Rasterizes a vector graphic to a given size using a {@link BufferedImage}.
	 * The rendering hints are set to produce high quality output.
	 *
	 * @param path   Fully qualified path to the image resource to rasterize.
	 * @param dstDim The output image dimensions.
	 * @return The rasterized {@link Image}.
	 * @throws SVGException Could not open, read, parse, or render SVG data.
	 */
	public BufferedImage rasterize(final Dimension dstDim){
		Dimension scaled = fit( srcDim, dstDim );
		int wScaled = (int) scaled.getWidth();
		int hScaled = (int) scaled.getHeight();
		BufferedImage image = ImageUtils.createImage(wScaled,hScaled);
		try {
			Graphics2D g = image.createGraphics();
			g.setRenderingHints( RENDERING_HINTS );

			AffineTransform transform = g.getTransform();
			transform.setToScale( wScaled / wDiagram, hScaled / hDiagram );

			g.setTransform(transform);
			diagram.render(g);
			g.dispose();
		}
		catch(Exception e) {
			e.printStackTrace(System.err);
		}
		return image;
	}

	/**
	 * Gets an instance of {@link URL} that references a file in the
	 * application's resources.
	 *
	 * @param path The full path (starting at the root), relative to the
	 *             application or JAR file's resources directory.
	 * @return A {@link URL} to the file or {@code null} if the path does not
	 * point to a resource.
	 */
	private URL getResourceUrl( final String path ) {
		return SvgRasterizer.class.getResource(path);
	}

	/**
	 * Loads the resource specified by the given path into an instance of
	 * {@link SVGDiagram} that can be rasterized into a bitmap format. The
	 * {@link SVGUniverse} class will
	 *
	 * @param path The full path (starting at the root), relative to the
	 *             application or JAR file's resources directory.
	 * @return An {@link SVGDiagram} that can be rasterized onto a
	 * {@link BufferedImage}.
	 */
	private SVGDiagram loadDiagram(final String path) {
		SVGDiagram diagram = null;
		try {
			//		URL url = getResourceUrl(path);
			URI uri = sRenderer.loadSVG(new File(path).toURL());
			diagram = sRenderer.getDiagram( uri );
		}		
		catch(Throwable t) {
			t.printStackTrace(System.err);
		}
		return applySettings( diagram );
	}

	/**
	 * Instructs the SVG renderer to rasterize the image even if it would be
	 * clipped.
	 *
	 * @param diagram The {@link SVGDiagram} to render.
	 * @return The same instance with ignore clip heuristics set to {@code true}.
	 */
	private SVGDiagram applySettings( final SVGDiagram diagram ) {
		diagram.setIgnoringClipHeuristic( true );
		return diagram;
	}

	/**
	 * Scales the given source {@link Dimension} to the destination
	 * {@link Dimension}, maintaining the aspect ratio with respect to
	 * the best fit.
	 *
	 * @param src The original vector graphic dimensions to change.
	 * @param dst The desired image dimensions to scale.
	 * @return The given source dimensions scaled to the destination dimensions,
	 * maintaining the aspect ratio.
	 */
	private Dimension fit( final Dimension src, final Dimension dst ) {
		double srcWidth = src.getWidth();
		double srcHeight = src.getHeight();

		// Determine the ratio that will have the best fit.
		double ratio = Math.min(
				dst.getWidth() / srcWidth, dst.getHeight() / srcHeight
				);

		// Scale both dimensions with respect to the best fit ratio.
		return new Dimension( (int) (srcWidth * ratio), (int) (srcHeight * ratio) );
	}
}