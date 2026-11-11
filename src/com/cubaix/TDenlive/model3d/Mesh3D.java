package com.cubaix.TDenlive.model3d;

/**
 * Triangle mesh of a primitive, in its local frame: fits in the cube -0.5..0.5.
 * One normal per vertex (the flat faces have their own vertices).
 */
public class Mesh3D {
	static final int SEGS = 32;//Segments of the round shapes
	public float[] pos = new float[0];
	public float[] nor = new float[0];
	public int[] tris = new int[0];
	public int nbVerts = 0;
	public int nbTris = 0;
	//Lit on both sides (open surface)
	public boolean twoSided = false;

	static Mesh3D[] cache = new Mesh3D[Object3D.NB_PRIMITIVES];
	//Mesh of the groups and of the empty texts
	public static final Mesh3D EMPTY = new Mesh3D();

	/**
	 * Shared mesh of a primitive type (do not modify)
	 */
	public static synchronized Mesh3D get(int aType) {
		if(aType < 0 || aType >= cache.length) {
			aType = Object3D.CUBE;
		}
		if(cache[aType] == null) {
			cache[aType] = build(aType);
		}
		return cache[aType];
	}

	static Mesh3D build(int aType) {
		Mesh3D aM = new Mesh3D();
		switch(aType) {
		case Object3D.SPHERE: aM.sphere(); break;
		case Object3D.CYLINDER: aM.cylinder(); break;
		case Object3D.CONE: aM.cone(); break;
		case Object3D.TORUS: aM.torus(); break;
		case Object3D.PYRAMID: aM.pyramid(); break;
		case Object3D.PLANE: aM.plane(); break;
		case Object3D.TUBE: aM.tube(); break;
		default: aM.cube(); break;
		}
		aM.trim();
		return aM;
	}

	int vertex(double aX, double aY, double aZ, double aNx, double aNy, double aNz) {
		if(nbVerts*3 >= pos.length) {
			pos = java.util.Arrays.copyOf(pos, Math.max(48, pos.length*2));
			nor = java.util.Arrays.copyOf(nor, Math.max(48, nor.length*2));
		}
		double aL = Math.sqrt(aNx*aNx + aNy*aNy + aNz*aNz);
		if(aL < 1e-12) {
			aL = 1;
		}
		pos[nbVerts*3] = (float)aX;
		pos[nbVerts*3+1] = (float)aY;
		pos[nbVerts*3+2] = (float)aZ;
		nor[nbVerts*3] = (float)(aNx/aL);
		nor[nbVerts*3+1] = (float)(aNy/aL);
		nor[nbVerts*3+2] = (float)(aNz/aL);
		return nbVerts++;
	}

	void tri(int aA, int aB, int aC) {
		if(nbTris*3 >= tris.length) {
			tris = java.util.Arrays.copyOf(tris, Math.max(48, tris.length*2));
		}
		tris[nbTris*3] = aA;
		tris[nbTris*3+1] = aB;
		tris[nbTris*3+2] = aC;
		nbTris++;
	}

	void quad(int aA, int aB, int aC, int aD) {
		tri(aA, aB, aC);
		tri(aA, aC, aD);
	}

	void trim() {
		pos = java.util.Arrays.copyOf(pos, nbVerts*3);
		nor = java.util.Arrays.copyOf(nor, nbVerts*3);
		tris = java.util.Arrays.copyOf(tris, nbTris*3);
	}

	/**
	 * Flat face of 3 or 4 points (normal from the first three)
	 */
	void face(double[]... aPts) {
		double[] aN = Math3D.cross(Math3D.sub(aPts[1], aPts[0]), Math3D.sub(aPts[2], aPts[0]));
		int[] aIs = new int[aPts.length];
		for(int p = 0;p < aPts.length;p++) {
			aIs[p] = vertex(aPts[p][0], aPts[p][1], aPts[p][2], aN[0], aN[1], aN[2]);
		}
		for(int p = 1;p < aPts.length-1;p++) {
			tri(aIs[0], aIs[p], aIs[p+1]);
		}
	}

	void cube() {
		double h = 0.5;
		face(new double[] {-h,-h, h}, new double[] { h,-h, h}, new double[] { h, h, h}, new double[] {-h, h, h});//Front +Z
		face(new double[] { h,-h,-h}, new double[] {-h,-h,-h}, new double[] {-h, h,-h}, new double[] { h, h,-h});//Back
		face(new double[] { h,-h, h}, new double[] { h,-h,-h}, new double[] { h, h,-h}, new double[] { h, h, h});//Right
		face(new double[] {-h,-h,-h}, new double[] {-h,-h, h}, new double[] {-h, h, h}, new double[] {-h, h,-h});//Left
		face(new double[] {-h, h, h}, new double[] { h, h, h}, new double[] { h, h,-h}, new double[] {-h, h,-h});//Top
		face(new double[] {-h,-h,-h}, new double[] { h,-h,-h}, new double[] { h,-h, h}, new double[] {-h,-h, h});//Bottom
	}

	void pyramid() {
		double h = 0.5;
		double[] aTop = {0, h, 0};
		double[][] aB = {{-h,-h, h}, { h,-h, h}, { h,-h,-h}, {-h,-h,-h}};
		for(int s = 0;s < 4;s++) {
			face(aB[s], aB[(s+1)%4], aTop);
		}
		face(aB[3], aB[2], aB[1], aB[0]);
	}

	void plane() {
		double h = 0.5;
		face(new double[] {-h, 0, h}, new double[] { h, 0, h}, new double[] { h, 0,-h}, new double[] {-h, 0,-h});
		twoSided = true;
	}

	void sphere() {
		int aStacks = SEGS/2;
		int[][] aIs = new int[aStacks+1][SEGS+1];
		for(int t = 0;t <= aStacks;t++) {
			double aTh = Math.PI*t/aStacks;
			for(int s = 0;s <= SEGS;s++) {
				double aPh = 2*Math.PI*s/SEGS;
				double aX = Math.sin(aTh)*Math.cos(aPh);
				double aY = Math.cos(aTh);
				double aZ = Math.sin(aTh)*Math.sin(aPh);
				aIs[t][s] = vertex(aX*0.5, aY*0.5, aZ*0.5, aX, aY, aZ);
			}
		}
		for(int t = 0;t < aStacks;t++) {
			for(int s = 0;s < SEGS;s++) {
				quad(aIs[t][s], aIs[t][s+1], aIs[t+1][s+1], aIs[t+1][s]);
			}
		}
	}

	/**
	 * Disk of radius aR at height aY (normal up or down), or a ring from aRIn to aR
	 */
	void disk(double aY, double aR, double aRIn, boolean aUp) {
		double aNy = aUp ? 1 : -1;
		if(aRIn <= 0) {
			int aC = vertex(0, aY, 0, 0, aNy, 0);
			int[] aIs = new int[SEGS+1];
			for(int s = 0;s <= SEGS;s++) {
				double aA = 2*Math.PI*s/SEGS;
				aIs[s] = vertex(aR*Math.cos(aA), aY, aR*Math.sin(aA), 0, aNy, 0);
			}
			for(int s = 0;s < SEGS;s++) {
				tri(aC, aIs[s], aIs[s+1]);
			}
			return;
		}
		int[] aOut = new int[SEGS+1];
		int[] aIn = new int[SEGS+1];
		for(int s = 0;s <= SEGS;s++) {
			double aA = 2*Math.PI*s/SEGS;
			aOut[s] = vertex(aR*Math.cos(aA), aY, aR*Math.sin(aA), 0, aNy, 0);
			aIn[s] = vertex(aRIn*Math.cos(aA), aY, aRIn*Math.sin(aA), 0, aNy, 0);
		}
		for(int s = 0;s < SEGS;s++) {
			quad(aIn[s], aOut[s], aOut[s+1], aIn[s+1]);
		}
	}

	/**
	 * Side of a cylinder of radius aR (normals outward, or inward for the inside of a tube)
	 */
	void side(double aR, boolean aOutward) {
		int[] aB = new int[SEGS+1];
		int[] aT = new int[SEGS+1];
		double aK = aOutward ? 1 : -1;
		for(int s = 0;s <= SEGS;s++) {
			double aA = 2*Math.PI*s/SEGS;
			double aC = Math.cos(aA), aS = Math.sin(aA);
			aB[s] = vertex(aR*aC, -0.5, aR*aS, aK*aC, 0, aK*aS);
			aT[s] = vertex(aR*aC, 0.5, aR*aS, aK*aC, 0, aK*aS);
		}
		for(int s = 0;s < SEGS;s++) {
			quad(aB[s], aB[s+1], aT[s+1], aT[s]);
		}
	}

	void cylinder() {
		side(0.5, true);
		disk(0.5, 0.5, 0, true);
		disk(-0.5, 0.5, 0, false);
	}

	void tube() {
		side(0.5, true);
		side(0.3, false);
		disk(0.5, 0.5, 0.3, true);
		disk(-0.5, 0.5, 0.3, false);
	}

	void cone() {
		//Side normal: (cos, r/h, sin) with r = 0.5, h = 1
		for(int s = 0;s < SEGS;s++) {
			double aA0 = 2*Math.PI*s/SEGS;
			double aA1 = 2*Math.PI*(s+1)/SEGS;
			double aAm = (aA0+aA1)/2;
			int aI0 = vertex(0.5*Math.cos(aA0), -0.5, 0.5*Math.sin(aA0), Math.cos(aA0), 0.5, Math.sin(aA0));
			int aI1 = vertex(0.5*Math.cos(aA1), -0.5, 0.5*Math.sin(aA1), Math.cos(aA1), 0.5, Math.sin(aA1));
			int aIt = vertex(0, 0.5, 0, Math.cos(aAm), 0.5, Math.sin(aAm));
			tri(aI0, aI1, aIt);
		}
		disk(-0.5, 0.5, 0, false);
	}

	void torus() {
		double aR = 0.35, aRt = 0.15;
		int aRing = SEGS/2;
		int[][] aIs = new int[SEGS+1][aRing+1];
		for(int u = 0;u <= SEGS;u++) {
			double aU = 2*Math.PI*u/SEGS;
			for(int v = 0;v <= aRing;v++) {
				double aV = 2*Math.PI*v/aRing;
				double aNx = Math.cos(aV)*Math.cos(aU);
				double aNy = Math.sin(aV);
				double aNz = Math.cos(aV)*Math.sin(aU);
				aIs[u][v] = vertex((aR+aRt*Math.cos(aV))*Math.cos(aU), aRt*aNy, (aR+aRt*Math.cos(aV))*Math.sin(aU), aNx, aNy, aNz);
			}
		}
		for(int u = 0;u < SEGS;u++) {
			for(int v = 0;v < aRing;v++) {
				quad(aIs[u][v], aIs[u+1][v], aIs[u+1][v+1], aIs[u][v+1]);
			}
		}
	}

	//
	// Extruded text
	//

	static final java.util.Map<String,Mesh3D> TEXT_CACHE = new java.util.LinkedHashMap<String,Mesh3D>(16, 0.75f, true) {
		private static final long serialVersionUID = 1L;
		@Override
		protected boolean removeEldestEntry(java.util.Map.Entry<String,Mesh3D> aE) {
			return size() > 40;
		}
	};
	static final double TEXT_FLATNESS = 0.4;//In pixels of a font of 100
	static final double SMOOTH_COS = Math.cos(Math.toRadians(35));

	/**
	 * Mesh of a text (lines centered), a line 1 high, the block centered at the origin,
	 * from z = -aExtrusion/2 to aExtrusion/2. Shared (do not modify)
	 */
	public static Mesh3D text(String aText, String aFont, boolean aBold, boolean aItalic, double aExtrusion) {
		String aKey = aFont+"\u0000"+aBold+"\u0000"+aItalic+"\u0000"+aExtrusion+"\u0000"+aText;
		synchronized (TEXT_CACHE) {
			Mesh3D aM = TEXT_CACHE.get(aKey);
			if(aM != null) {
				return aM;
			}
		}
		Mesh3D aM = EMPTY;
		try {
			aM = buildText(aText, aFont, aBold, aItalic, Math.max(0.001, aExtrusion));
		}
		catch(Throwable t) {
			t.printStackTrace(System.err);
		}
		synchronized (TEXT_CACHE) {
			TEXT_CACHE.put(aKey, aM);
		}
		return aM;
	}

	static Mesh3D buildText(String aText, String aFont, boolean aBold, boolean aItalic, double aExtrusion) {
		if(aText == null || aText.isBlank()) {
			return EMPTY;
		}
		//Registers the embedded fonts and those of the project
		com.cubaix.TDenlive.shapes.ShapeText.getFamilies();
		java.awt.Font aF = new java.awt.Font(aFont == null || aFont.isEmpty() ? com.cubaix.TDenlive.shapes.ShapeText.DEFAULT_FONT : aFont
				, (aBold ? java.awt.Font.BOLD : 0) | (aItalic ? java.awt.Font.ITALIC : 0), 1).deriveFont(100f);
		java.awt.font.FontRenderContext aFRC = new java.awt.font.FontRenderContext(null, true, true);
		java.awt.font.LineMetrics aLM = aF.getLineMetrics("Hg", aFRC);
		double aLineH = aLM.getAscent()+aLM.getDescent();
		double aStep = aLineH+aLM.getLeading();
		String[] aLines = aText.replace("\r", "").split("\n", -1);
		//Glyph outlines, lines centered
		java.util.Vector<java.awt.Shape> aGlyphs = new java.util.Vector<java.awt.Shape>();
		double aMaxW = 0;
		java.awt.font.GlyphVector[] aGVs = new java.awt.font.GlyphVector[aLines.length];
		for(int l = 0;l < aLines.length;l++) {
			if(!aLines[l].isEmpty()) {
				aGVs[l] = aF.createGlyphVector(aFRC, aLines[l]);
				aMaxW = Math.max(aMaxW, aGVs[l].getLogicalBounds().getWidth());
			}
		}
		for(int l = 0;l < aLines.length;l++) {
			if(aGVs[l] == null) {
				continue;
			}
			float aX = (float)((aMaxW-aGVs[l].getLogicalBounds().getWidth())/2);
			float aY = (float)(l*aStep+aLM.getAscent());
			for(int g = 0;g < aGVs[l].getNumGlyphs();g++) {
				java.awt.Shape aS = aGVs[l].getGlyphOutline(g, aX, aY);
				if(!aS.getBounds2D().isEmpty()) {
					aGlyphs.add(aS);
				}
			}
		}
		if(aGlyphs.isEmpty()) {
			return EMPTY;
		}
		//Block centered, a line 1 high, y up
		double aBlockH = aStep*(aLines.length-1)+aLineH;
		double aCx = aMaxW/2, aCy = aBlockH/2;
		double aK = 1/aLineH;
		double aZ = aExtrusion/2;
		Mesh3D aM = new Mesh3D();
		for(java.awt.Shape aS : aGlyphs) {
			java.util.Vector<double[]> aContours = contours(aS);
			aM.textCaps(aContours, aCx, aCy, aK, aZ);
			for(double[] aC : aContours) {
				aM.textSide(aS, aC, aCx, aCy, aK, aZ);
			}
		}
		aM.trim();
		return aM;
	}

	/**
	 * Flattened contours {x0, y0, x1, y1...} of an outline (AWT coordinates)
	 */
	static java.util.Vector<double[]> contours(java.awt.Shape aS) {
		java.util.Vector<double[]> aCs = new java.util.Vector<double[]>();
		java.awt.geom.PathIterator aPI = aS.getPathIterator(null, TEXT_FLATNESS);
		double[] aPt = new double[6];
		java.util.Vector<Double> aCur = new java.util.Vector<Double>();
		while(!aPI.isDone()) {
			int aSeg = aPI.currentSegment(aPt);
			if(aSeg == java.awt.geom.PathIterator.SEG_MOVETO) {
				addContour(aCs, aCur);
				aCur = new java.util.Vector<Double>();
				aCur.add(aPt[0]);
				aCur.add(aPt[1]);
			}
			else if(aSeg == java.awt.geom.PathIterator.SEG_LINETO) {
				int aN = aCur.size();
				if(aN < 2 || Math.abs(aCur.elementAt(aN-2)-aPt[0]) > 1e-6 || Math.abs(aCur.elementAt(aN-1)-aPt[1]) > 1e-6) {
					aCur.add(aPt[0]);
					aCur.add(aPt[1]);
				}
			}
			else if(aSeg == java.awt.geom.PathIterator.SEG_CLOSE) {
				addContour(aCs, aCur);
				aCur = new java.util.Vector<Double>();
			}
			aPI.next();
		}
		addContour(aCs, aCur);
		return aCs;
	}

	static void addContour(java.util.Vector<double[]> aCs, java.util.Vector<Double> aCur) {
		int aN = aCur.size();
		//Closing point = first point: removed
		if(aN >= 4 && Math.abs(aCur.elementAt(0)-aCur.elementAt(aN-2)) < 1e-6 && Math.abs(aCur.elementAt(1)-aCur.elementAt(aN-1)) < 1e-6) {
			aCur.setSize(aN-2);
			aN -= 2;
		}
		if(aN < 6) {
			return;
		}
		double[] aC = new double[aN];
		for(int i = 0;i < aN;i++) {
			aC[i] = aCur.elementAt(i);
		}
		aCs.add(aC);
	}

	/**
	 * Front and back faces of a glyph: horizontal bands between the heights of its points, cut into
	 * trapezoids between the edges crossing them (non zero winding rule of the fonts)
	 */
	void textCaps(java.util.Vector<double[]> aContours, double aCx, double aCy, double aK, double aZ) {
		java.util.Vector<double[]> aEdges = new java.util.Vector<double[]>();//x0, y0, x1, y1 (y0 < y1), direction
		java.util.TreeSet<Double> aYs = new java.util.TreeSet<Double>();
		for(double[] aC : aContours) {
			int aN = aC.length/2;
			for(int i = 0;i < aN;i++) {
				double aX0 = aC[i*2], aY0 = aC[i*2+1];
				double aX1 = aC[((i+1)%aN)*2], aY1 = aC[((i+1)%aN)*2+1];
				aYs.add(aY0);
				if(Math.abs(aY1-aY0) < 1e-9) {
					continue;
				}
				if(aY0 < aY1) {
					aEdges.add(new double[] {aX0, aY0, aX1, aY1, 1});
				}
				else {
					aEdges.add(new double[] {aX1, aY1, aX0, aY0, -1});
				}
			}
		}
		Double[] aLevels = aYs.toArray(new Double[0]);
		java.util.Vector<double[]> aCross = new java.util.Vector<double[]>();
		for(int b = 0;b < aLevels.length-1;b++) {
			double aYa = aLevels[b], aYb = aLevels[b+1];
			if(aYb-aYa < 1e-9) {
				continue;
			}
			double aYm = (aYa+aYb)/2;
			aCross.clear();
			for(double[] aE : aEdges) {
				if(aE[1] <= aYm && aE[3] >= aYm) {
					double aT = aE[2]-aE[0];
					double aDy = aE[3]-aE[1];
					double aXa = aE[0]+aT*(aYa-aE[1])/aDy;
					double aXb = aE[0]+aT*(aYb-aE[1])/aDy;
					aCross.add(new double[] {(aXa+aXb)/2, aXa, aXb, aE[4]});
				}
			}
			aCross.sort((aA, aB) -> Double.compare(aA[0], aB[0]));
			int aWinding = 0;
			double[] aLeft = null;
			for(double[] aC : aCross) {
				int aBefore = aWinding;
				aWinding += (int)aC[3];
				if(aBefore == 0 && aWinding != 0) {
					aLeft = aC;
				}
				else if(aBefore != 0 && aWinding == 0 && aLeft != null) {
					trapezoid(aLeft[1], aC[1], aYa, aLeft[2], aC[2], aYb, aCx, aCy, aK, aZ);
					aLeft = null;
				}
			}
		}
	}

	/**
	 * Trapezoid from (aXa0..aXa1 at aYa) to (aXb0..aXb1 at aYb), AWT coordinates, front and back
	 */
	void trapezoid(double aXa0, double aXa1, double aYa, double aXb0, double aXb1, double aYb, double aCx, double aCy, double aK, double aZ) {
		double aY0 = -(aYa-aCy)*aK, aY1 = -(aYb-aCy)*aK;
		double[][] aPts = {{(aXa0-aCx)*aK, aY0}, {(aXa1-aCx)*aK, aY0}, {(aXb1-aCx)*aK, aY1}, {(aXb0-aCx)*aK, aY1}};
		for(int aSide = -1;aSide <= 1;aSide += 2) {
			int[] aIs = new int[4];
			for(int p = 0;p < 4;p++) {
				aIs[p] = vertex(aPts[p][0], aPts[p][1], aSide*aZ, 0, 0, aSide);
			}
			quad(aIs[0], aIs[1], aIs[2], aIs[3]);
		}
	}

	/**
	 * Side wall of a contour, normals outward (tested on the outline), smoothed on the curves
	 */
	void textSide(java.awt.Shape aS, double[] aC, double aCx, double aCy, double aK, double aZ) {
		int aN = aC.length/2;
		double[][] aNor = new double[aN][];//Normal of the edge i → i+1 (AWT coordinates)
		for(int i = 0;i < aN;i++) {
			double aX0 = aC[i*2], aY0 = aC[i*2+1];
			double aX1 = aC[((i+1)%aN)*2], aY1 = aC[((i+1)%aN)*2+1];
			double aDx = aX1-aX0, aDy = aY1-aY0;
			double aL = Math.sqrt(aDx*aDx+aDy*aDy);
			if(aL < 1e-9) {
				aNor[i] = new double[] {0, 0};
				continue;
			}
			double aNx = aDy/aL, aNy = -aDx/aL;
			double aMx = (aX0+aX1)/2, aMy = (aY0+aY1)/2;
			if(aS.contains(aMx+aNx*0.05, aMy+aNy*0.05)) {
				aNx = -aNx;
				aNy = -aNy;
			}
			aNor[i] = new double[] {aNx, aNy};
		}
		for(int i = 0;i < aN;i++) {
			int aJ = (i+1)%aN;
			double[] aN0 = vertexNormal(aNor[(i-1+aN)%aN], aNor[i]);
			double[] aN1 = vertexNormal(aNor[aJ], aNor[i]);
			double aX0 = (aC[i*2]-aCx)*aK, aY0 = -(aC[i*2+1]-aCy)*aK;
			double aX1 = (aC[aJ*2]-aCx)*aK, aY1 = -(aC[aJ*2+1]-aCy)*aK;
			//AWT y down: the y of the normal is reversed
			int aA = vertex(aX0, aY0, aZ, aN0[0], -aN0[1], 0);
			int aB = vertex(aX1, aY1, aZ, aN1[0], -aN1[1], 0);
			int aCc = vertex(aX1, aY1, -aZ, aN1[0], -aN1[1], 0);
			int aD = vertex(aX0, aY0, -aZ, aN0[0], -aN0[1], 0);
			quad(aA, aB, aCc, aD);
		}
	}

	/**
	 * Normal at a point of the edge of normal aOwn, next to the edge of normal aOther:
	 * their average on a curve, its own on a corner
	 */
	static double[] vertexNormal(double[] aOther, double[] aOwn) {
		double aDot = aOther[0]*aOwn[0]+aOther[1]*aOwn[1];
		if(aDot < SMOOTH_COS) {
			return aOwn;
		}
		double aX = aOther[0]+aOwn[0], aY = aOther[1]+aOwn[1];
		double aL = Math.sqrt(aX*aX+aY*aY);
		return aL < 1e-9 ? aOwn : new double[] {aX/aL, aY/aL};
	}
}
