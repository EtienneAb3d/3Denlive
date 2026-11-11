package com.cubaix.TDenlive.model3d;

import java.util.Vector;

import com.cubaix.TDenlive.shapes.Shape;
import com.cubaix.TDenlive.shapes.ShapeText;
import com.cubaix.TDenlive.xml.XmlObject;
import com.cubaix.TDenlive.xml.XmlTag;

/**
 * A node of a 3D model: a primitive, an extruded text or a group (no mesh, its transform applies to
 * its children). local = R * S * mesh + position, R = Rz * Ry * Rx (degrees); world = world of the
 * parent * local (see Model3D.world())
 */
public class Object3D {
	public static final int CUBE = 0;
	public static final int SPHERE = 1;
	public static final int CYLINDER = 2;
	public static final int CONE = 3;
	public static final int TORUS = 4;
	public static final int PYRAMID = 5;
	public static final int PLANE = 6;
	public static final int TUBE = 7;
	//The buttons of the modeler add the types before NB_PRIMITIVES, then the text
	public static final int NB_PRIMITIVES = 8;
	public static final int TEXT = 8;
	public static final int GROUP = 9;
	public static final int NB_TYPES = 10;
	//Language keys (Model3D.<name>) and saved names
	public static final String[] TYPE_NAMES = {"Cube", "Sphere", "Cylinder", "Cone", "Torus", "Pyramid", "Plane", "Tube", "Text", "Group"};

	public int id = 0;
	public int type = CUBE;
	public int parent = -1;//Id of the group, -1 at the root
	public String name = "";//Optional, shown in the tree of the objects
	public double px = 0, py = 0, pz = 0;
	public double rx = 0, ry = 0, rz = 0;
	public double sx = 1, sy = 1, sz = 1;
	public int color = 0x4A90D9;
	//Text
	public String text = "Text";
	public String fontName = ShapeText.DEFAULT_FONT;
	public boolean bold = false;
	public boolean italic = false;
	public double extrusion = 0.25;//Thickness / height of a line

	public Object3D() {
	}

	public Object3D(int aType) {
		type = aType;
	}

	public Object3D copy() {
		Object3D aO = new Object3D(type);
		aO.id = id;
		aO.parent = parent;
		aO.name = name;
		copyTransformTo(aO);
		aO.color = color;
		aO.text = text;
		aO.fontName = fontName;
		aO.bold = bold;
		aO.italic = italic;
		aO.extrusion = extrusion;
		return aO;
	}

	public void copyTransformTo(Object3D aO) {
		aO.px = px; aO.py = py; aO.pz = pz;
		aO.rx = rx; aO.ry = ry; aO.rz = rz;
		aO.sx = sx; aO.sy = sy; aO.sz = sz;
	}

	public static Vector<Object3D> copyAll(Vector<Object3D> aObjects) {
		Vector<Object3D> aCopy = new Vector<Object3D>();
		for(Object3D aO : aObjects) {
			aCopy.add(aO.copy());
		}
		return aCopy;
	}

	public boolean isGroup() {
		return type == GROUP;
	}

	public Mesh3D mesh() {
		if(type == GROUP) {
			return Mesh3D.EMPTY;
		}
		if(type == TEXT) {
			return Mesh3D.text(text, fontName, bold, italic, extrusion);
		}
		return Mesh3D.get(type);
	}

	public double[] getPosition() {
		return new double[] {px, py, pz};
	}

	public void setPosition(double[] aP) {
		px = aP[0]; py = aP[1]; pz = aP[2];
	}

	public double[] getRotation() {
		return Math3D.euler(rx, ry, rz);
	}

	public void setRotation(double[] aM) {
		double[] aE = Math3D.toEuler(aM);
		rx = aE[0]; ry = aE[1]; rz = aE[2];
	}

	public double[] getScale() {
		return new double[] {sx, sy, sz};
	}

	public void setScale(double[] aS) {
		sx = aS[0]; sy = aS[1]; sz = aS[2];
	}

	/**
	 * R * S of the local transform
	 */
	public double[] localMatrix() {
		return Math3D.rotScale(getRotation(), getScale());
	}

	/**
	 * Sets the local transform from a matrix (R * S, the shear is lost) and a position
	 */
	public void setLocal(double[] aM, double[] aT) {
		double[] aD = Math3D.decompose(aM);
		setRotation(java.util.Arrays.copyOf(aD, 9));
		setScale(new double[] {aD[9], aD[10], aD[11]});
		setPosition(aT);
	}

	/**
	 * Bounds {minX, minY, minZ, maxX, maxY, maxZ} of the mesh through the world transform (aM, aT),
	 * merged into aB if not null
	 */
	public double[] bounds(double[] aB, double[] aM, double[] aT) {
		Mesh3D aMesh = mesh();
		if(aMesh.nbVerts == 0) {
			return aB;
		}
		if(aB == null) {
			aB = new double[] {Double.MAX_VALUE, Double.MAX_VALUE, Double.MAX_VALUE, -Double.MAX_VALUE, -Double.MAX_VALUE, -Double.MAX_VALUE};
		}
		for(int v = 0;v < aMesh.nbVerts;v++) {
			double[] aW = Math3D.add(Math3D.apply(aM, new double[] {aMesh.pos[v*3], aMesh.pos[v*3+1], aMesh.pos[v*3+2]}), aT);
			for(int c = 0;c < 3;c++) {
				aB[c] = Math.min(aB[c], aW[c]);
				aB[c+3] = Math.max(aB[c+3], aW[c]);
			}
		}
		return aB;
	}

	public void saveProject(StringBuffer aSB, String aIndent) {
		aSB.append(aIndent+"<Object3D>\n");
		aSB.append(aIndent+"	<id>" + id + "</id>\n");
		aSB.append(aIndent+"	<type>" + TYPE_NAMES[type] + "</type>\n");
		if(parent >= 0) {
			aSB.append(aIndent+"	<parent>" + parent + "</parent>\n");
		}
		if(name != null && !name.isEmpty()) {
			aSB.append(aIndent+"	<name>" + Shape.encode(name) + "</name>\n");
		}
		aSB.append(aIndent+"	<pos>" + px + ";" + py + ";" + pz + "</pos>\n");
		aSB.append(aIndent+"	<rot>" + rx + ";" + ry + ";" + rz + "</rot>\n");
		aSB.append(aIndent+"	<scale>" + sx + ";" + sy + ";" + sz + "</scale>\n");
		aSB.append(aIndent+"	<color>" + color + "</color>\n");
		if(type == TEXT) {
			aSB.append(aIndent+"	<text>" + Shape.encode(text) + "</text>\n");
			//Same tag as the shapes: the font is copied with the project (see ProjectFonts)
			aSB.append(aIndent+"	<fontName>" + Shape.encode(fontName) + "</fontName>\n");
			aSB.append(aIndent+"	<bold>" + bold + "</bold>\n");
			aSB.append(aIndent+"	<italic>" + italic + "</italic>\n");
			aSB.append(aIndent+"	<extrusion>" + extrusion + "</extrusion>\n");
		}
		aSB.append(aIndent+"</Object3D>\n");
	}

	static double[] parse3(String aText) {
		String[] aVs = aText.trim().split(";");
		return new double[] {Double.parseDouble(aVs[0]), Double.parseDouble(aVs[1]), Double.parseDouble(aVs[2])};
	}

	/**
	 * Reads from the <Object3D> tag at index o, returns the index of </Object3D>
	 */
	public int openProject(Vector<XmlObject> aOs, int o) throws Exception {
		for(o++;o < aOs.size();o++) {
			XmlObject aO = aOs.elementAt(o);
			if(!(aO instanceof XmlTag)) {
				continue;
			}
			XmlTag aT = (XmlTag)aO;
			if("/Object3D".equalsIgnoreCase(aT.tagName)) {
				return o;
			}
			if("id".equalsIgnoreCase(aT.tagName)) {
				id = Integer.parseInt(aOs.elementAt(++o).text.trim());
			}
			if("type".equalsIgnoreCase(aT.tagName)) {
				String aName = aOs.elementAt(++o).text.trim();
				for(int t = 0;t < TYPE_NAMES.length;t++) {
					if(TYPE_NAMES[t].equalsIgnoreCase(aName)) {
						type = t;
					}
				}
			}
			if("parent".equalsIgnoreCase(aT.tagName)) {
				parent = Integer.parseInt(aOs.elementAt(++o).text.trim());
			}
			if("name".equalsIgnoreCase(aT.tagName)) {
				name = Shape.decode(aOs.elementAt(++o).text.trim());
			}
			if("pos".equalsIgnoreCase(aT.tagName)) {
				setPosition(parse3(aOs.elementAt(++o).text));
			}
			if("rot".equalsIgnoreCase(aT.tagName)) {
				double[] aR = parse3(aOs.elementAt(++o).text);
				rx = aR[0]; ry = aR[1]; rz = aR[2];
			}
			if("scale".equalsIgnoreCase(aT.tagName)) {
				setScale(parse3(aOs.elementAt(++o).text));
			}
			if("color".equalsIgnoreCase(aT.tagName)) {
				color = Integer.parseInt(aOs.elementAt(++o).text.trim());
			}
			if("text".equalsIgnoreCase(aT.tagName)) {
				text = Shape.decode(aOs.elementAt(++o).text.trim());
			}
			if("fontName".equalsIgnoreCase(aT.tagName)) {
				fontName = Shape.decode(aOs.elementAt(++o).text.trim());
			}
			if("bold".equalsIgnoreCase(aT.tagName)) {
				bold = Boolean.parseBoolean(aOs.elementAt(++o).text.trim());
			}
			if("italic".equalsIgnoreCase(aT.tagName)) {
				italic = Boolean.parseBoolean(aOs.elementAt(++o).text.trim());
			}
			if("extrusion".equalsIgnoreCase(aT.tagName)) {
				extrusion = Double.parseDouble(aOs.elementAt(++o).text.trim());
			}
		}
		return o;
	}
}
