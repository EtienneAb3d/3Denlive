package com.cubaix.TDenlive.model3d;

import java.util.Vector;

import com.cubaix.TDenlive.medias.ColorBck;
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
	//Textured object of the library (ObjectCatalog), its id in asset
	public static final int ASSET = 10;
	public static final int NB_TYPES = 11;
	//Language keys (Model3D.<name>) and saved names
	public static final String[] TYPE_NAMES = {"Cube", "Sphere", "Cylinder", "Cone", "Torus", "Pyramid", "Plane", "Tube", "Text", "Group", "Asset"};

	public int id = 0;
	public int type = CUBE;
	public int parent = -1;//Id of the group, -1 at the root
	public String name = "";//Optional, shown in the tree of the objects
	public double px = 0, py = 0, pz = 0;
	public double rx = 0, ry = 0, rz = 0;
	public double sx = 1, sy = 1, sz = 1;
	public int color = 0x4A90D9;
	//Texture: a texture of the library of the color clips (ColorBck.Fill: procedural between color and
	//color2, image of the library, image of the disk "file:<path>"), PLAIN = none; repeated texRepU x
	//texRepV times on a face (or once around a round side), enlarged by texScale %, shifted by texOffU /
	//texOffV (% of a repeat)
	public String texture = ColorBck.Fill.PLAIN;
	public int color2 = 0xFFFFFF;
	public double texRepU = 1, texRepV = 1;
	public double texOffU = 0, texOffV = 0;
	public double texScale = 100;
	//Rotation of the texture on the faces, degrees counterclockwise around the middle of a face
	public double texRot = 0;
	//Video textures (Texture3D.MEDIA): range of the media time looped, in ms (end -1 = end of the video)
	public long texStartMS = 0;
	public long texEndMS = -1;
	//Texture of each face (faceNames(), Mesh3D.triFace) when not that of the object (texture), null = the same;
	//the colors are shared
	public String[] faceTex = null;
	//Settings of the texture of each face (repeats, offsets, scale, rotation, video range) when not those of the
	//object, null = the same
	public TexParams[] faceParams = null;

	/**
	 * Settings of a texture on a face: repeats, offsets (% of a repeat), scale (%), rotation (degrees), video range
	 */
	public static class TexParams {
		public double repU = 1, repV = 1;
		public double offU = 0, offV = 0;
		public double scale = 100;
		public double rot = 0;
		public long startMS = 0, endMS = -1;

		public TexParams copy() {
			TexParams aP = new TexParams();
			aP.repU = repU; aP.repV = repV;
			aP.offU = offU; aP.offV = offV;
			aP.scale = scale;
			aP.rot = rot;
			aP.startMS = startMS; aP.endMS = endMS;
			return aP;
		}

		/**
		 * Those of the object aO
		 */
		public static TexParams of(Object3D aO) {
			TexParams aP = new TexParams();
			aP.repU = aO.texRepU; aP.repV = aO.texRepV;
			aP.offU = aO.texOffU; aP.offV = aO.texOffV;
			aP.scale = aO.texScale;
			aP.rot = aO.texRot;
			aP.startMS = aO.texStartMS; aP.endMS = aO.texEndMS;
			return aP;
		}

		/**
		 * As those of the object aO
		 */
		public void applyTo(Object3D aO) {
			aO.texRepU = repU; aO.texRepV = repV;
			aO.texOffU = offU; aO.texOffV = offV;
			aO.texScale = scale;
			aO.texRot = rot;
			aO.texStartMS = startMS; aO.texEndMS = endMS;
		}

		/**
		 * Text of the values (contexts of the renderer, save)
		 */
		public String key() {
			return repU+";"+repV+";"+offU+";"+offV+";"+scale+";"+rot+";"+startMS+";"+endMS;
		}

		static TexParams load(String[] aVs, int aFrom) {
			TexParams aP = new TexParams();
			aP.repU = Double.parseDouble(aVs[aFrom]);
			aP.repV = Double.parseDouble(aVs[aFrom+1]);
			aP.offU = Double.parseDouble(aVs[aFrom+2]);
			aP.offV = Double.parseDouble(aVs[aFrom+3]);
			aP.scale = Double.parseDouble(aVs[aFrom+4]);
			aP.rot = Double.parseDouble(aVs[aFrom+5]);
			aP.startMS = Long.parseLong(aVs[aFrom+6].trim());
			aP.endMS = Long.parseLong(aVs[aFrom+7].trim());
			return aP;
		}
	}

	/**
	 * Settings of the texture of the face aF: its own, else those of the object (a copy)
	 */
	public TexParams texParams(int aF) {
		TexParams aP = ownFaceParams(aF);
		return aP != null ? aP : TexParams.of(this);
	}

	/**
	 * Own settings of the face aF, null when it has those of the object
	 */
	public TexParams ownFaceParams(int aF) {
		return faceParams != null && aF >= 0 && aF < faceParams.length ? faceParams[aF] : null;
	}

	/**
	 * Settings of the face aF (null: those of the object again)
	 */
	public void setFaceParams(int aF, TexParams aP) {
		if(aF < 0) {
			return;
		}
		if(faceParams == null) {
			if(aP == null) {
				return;
			}
			faceParams = new TexParams[Math.max(aF+1, faceNames(type).length)];
		}
		if(aF >= faceParams.length) {
			faceParams = java.util.Arrays.copyOf(faceParams, aF+1);
		}
		faceParams[aF] = aP;
		for(TexParams aT : faceParams) {
			if(aT != null) {
				return;
			}
		}
		faceParams = null;
	}
	//Language keys (Texture3D.Face_<name>) of the faces of each type; one face: no texture per face
	static final String[][] FACE_NAMES = {
			{"Front", "Back", "Right", "Left", "Top", "Bottom"}//Cube
			, {}//Sphere
			, {"Side", "Top", "Bottom"}//Cylinder
			, {"Side", "Base"}//Cone
			, {}//Torus
			, {"Front", "Right", "Back", "Left", "Base"}//Pyramid
			, {}//Plane
			, {"Outside", "Inside", "Top", "Bottom"}//Tube
			, {"Front", "Back", "Sides"}//Text
			, {}//Group
			, {}//Asset
	};
	//Text
	public String text = "Text";
	public String fontName = ShapeText.DEFAULT_FONT;
	public boolean bold = false;
	public boolean italic = false;
	public double extrusion = 0.25;//Thickness / height of a line
	//Object of the library: id of ObjectCatalog
	public String asset = "";

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
		copyTextureTo(aO);
		aO.text = text;
		aO.fontName = fontName;
		aO.bold = bold;
		aO.italic = italic;
		aO.extrusion = extrusion;
		aO.asset = asset;
		return aO;
	}

	public void copyTextureTo(Object3D aO) {
		aO.texture = texture;
		aO.color2 = color2;
		aO.texRepU = texRepU; aO.texRepV = texRepV;
		aO.texOffU = texOffU; aO.texOffV = texOffV;
		aO.texScale = texScale;
		aO.texRot = texRot;
		aO.texStartMS = texStartMS;
		aO.texEndMS = texEndMS;
		//The faces of another type are not the same: its own textures per face are kept
		if(aO.type == type) {
			aO.faceTex = faceTex == null ? null : faceTex.clone();
			aO.faceParams = null;
			if(faceParams != null) {
				aO.faceParams = new TexParams[faceParams.length];
				for(int f = 0;f < faceParams.length;f++) {
					aO.faceParams[f] = faceParams[f] == null ? null : faceParams[f].copy();
				}
			}
		}
	}

	public boolean hasTexture() {
		return texture != null && !ColorBck.Fill.PLAIN.equals(texture);
	}

	/**
	 * Names (language keys Texture3D.Face_<name>) of the faces of the type aType, empty when it has one face
	 */
	public static String[] faceNames(int aType) {
		return aType >= 0 && aType < FACE_NAMES.length ? FACE_NAMES[aType] : new String[0];
	}

	/**
	 * Texture of the face aF: its own, else that of the object
	 */
	public String faceTexture(int aF) {
		if(faceTex != null && aF >= 0 && aF < faceTex.length && faceTex[aF] != null) {
			return faceTex[aF];
		}
		return texture;
	}

	/**
	 * Own texture of the face aF, null = that of the object
	 */
	public String ownFaceTexture(int aF) {
		return faceTex != null && aF >= 0 && aF < faceTex.length ? faceTex[aF] : null;
	}

	/**
	 * Texture of the face aF (null: that of the object again)
	 */
	public void setFaceTexture(int aF, String aTexture) {
		if(aF < 0) {
			return;
		}
		if(faceTex == null) {
			if(aTexture == null) {
				return;
			}
			faceTex = new String[Math.max(aF+1, faceNames(type).length)];
		}
		if(aF >= faceTex.length) {
			faceTex = java.util.Arrays.copyOf(faceTex, aF+1);
		}
		faceTex[aF] = aTexture;
		for(String aT : faceTex) {
			if(aT != null) {
				return;
			}
		}
		faceTex = null;
	}

	/**
	 * The textures used: that of the object, then those of the faces (each once)
	 */
	public java.util.List<String> textures() {
		java.util.List<String> aTs = new java.util.ArrayList<String>();
		aTs.add(texture);
		if(faceTex != null) {
			for(String aT : faceTex) {
				if(aT != null && !aTs.contains(aT)) {
					aTs.add(aT);
				}
			}
		}
		return aTs;
	}

	/**
	 * Textured: the object or one of its faces
	 */
	public boolean hasAnyTexture() {
		for(String aT : textures()) {
			if(aT != null && !ColorBck.Fill.PLAIN.equals(aT)) {
				return true;
			}
		}
		return false;
	}

	/**
	 * Paths of the images of the disk used as textures (object and faces)
	 */
	public java.util.List<String> textureFiles() {
		java.util.List<String> aPs = new java.util.ArrayList<String>();
		for(String aT : textures()) {
			if(aT != null && ColorBck.Fill.isFile(aT)) {
				aPs.add(aT.substring(ColorBck.Fill.FILE.length()));
			}
		}
		return aPs;
	}

	/**
	 * Each texture (object and faces) replaced by aMap.apply(texture) (an image of the disk found back...)
	 */
	public void mapTextures(java.util.function.UnaryOperator<String> aMap) {
		texture = aMap.apply(texture);
		if(faceTex != null) {
			for(int f = 0;f < faceTex.length;f++) {
				if(faceTex[f] != null) {
					faceTex[f] = aMap.apply(faceTex[f]);
				}
			}
		}
	}

	/**
	 * Path of an image of the disk used as texture, else null
	 */
	public String textureFile() {
		return texture != null && ColorBck.Fill.isFile(texture) ? texture.substring(ColorBck.Fill.FILE.length()) : null;
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
		if(type == ASSET) {
			return ObjectCatalog.mesh(asset);
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
		if(hasAnyTexture()) {
			if(textureFile() != null) {
				//The path alone in its tag: gathered, packed and renamed like the paths of the medias
				aSB.append(aIndent+"	<texture>file</texture>\n");
				aSB.append(aIndent+"	<texturePath>" + com.cubaix.TDenlive.utils.ProjectFiles.forSave(textureFile()) + "</texturePath>\n");
			}
			else {
				aSB.append(aIndent+"	<texture>" + texture + "</texture>\n");
			}
			aSB.append(aIndent+"	<color2>" + color2 + "</color2>\n");
			aSB.append(aIndent+"	<texRepeat>" + texRepU + ";" + texRepV + "</texRepeat>\n");
			aSB.append(aIndent+"	<texOffset>" + texOffU + ";" + texOffV + "</texOffset>\n");
			aSB.append(aIndent+"	<texScale>" + texScale + "</texScale>\n");
			aSB.append(aIndent+"	<texRot>" + texRot + "</texRot>\n");
			if(texStartMS != 0 || texEndMS >= 0) {
				aSB.append(aIndent+"	<texLoop>" + texStartMS + ";" + texEndMS + "</texLoop>\n");
			}
			if(faceTex != null) {
				for(int f = 0;f < faceTex.length;f++) {
					String aT = faceTex[f];
					if(aT == null) {
						continue;
					}
					if(ColorBck.Fill.isFile(aT)) {
						//The path alone in its tag, as that of the object
						aSB.append(aIndent+"	<faceTexture>" + f + ";file</faceTexture>\n");
						aSB.append(aIndent+"	<faceTexturePath>" + com.cubaix.TDenlive.utils.ProjectFiles.forSave(aT.substring(ColorBck.Fill.FILE.length())) + "</faceTexturePath>\n");
					}
					else {
						aSB.append(aIndent+"	<faceTexture>" + f + ";" + aT + "</faceTexture>\n");
					}
				}
			}
			if(faceParams != null) {
				for(int f = 0;f < faceParams.length;f++) {
					if(faceParams[f] != null) {
						aSB.append(aIndent+"	<faceTexParams>" + f + ";" + faceParams[f].key() + "</faceTexParams>\n");
					}
				}
			}
		}
		if(type == TEXT) {
			aSB.append(aIndent+"	<text>" + Shape.encode(text) + "</text>\n");
			//Same tag as the shapes: the font is copied with the project (see ProjectFonts)
			aSB.append(aIndent+"	<fontName>" + Shape.encode(fontName) + "</fontName>\n");
			aSB.append(aIndent+"	<bold>" + bold + "</bold>\n");
			aSB.append(aIndent+"	<italic>" + italic + "</italic>\n");
			aSB.append(aIndent+"	<extrusion>" + extrusion + "</extrusion>\n");
		}
		if(type == ASSET) {
			aSB.append(aIndent+"	<asset>" + Shape.encode(asset) + "</asset>\n");
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
		//Face of the last <faceTexture> (its path follows in <faceTexturePath>)
		int aFace = -1;
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
			if("texture".equalsIgnoreCase(aT.tagName)) {
				texture = aOs.elementAt(++o).text.trim();
				if("file".equals(texture)) {
					//Its path follows in <texturePath>
					texture = ColorBck.Fill.FILE;
				}
			}
			if("texturePath".equalsIgnoreCase(aT.tagName)) {
				texture = ColorBck.Fill.FILE + aOs.elementAt(++o).text.trim();
			}
			if("faceTexture".equalsIgnoreCase(aT.tagName)) {
				String aV = aOs.elementAt(++o).text.trim();
				int aS = aV.indexOf(';');
				aFace = Integer.parseInt(aV.substring(0, aS));
				String aTex = aV.substring(aS+1);
				setFaceTexture(aFace, "file".equals(aTex) ? ColorBck.Fill.FILE : aTex);
			}
			if("faceTexParams".equalsIgnoreCase(aT.tagName)) {
				String[] aVs = aOs.elementAt(++o).text.trim().split(";");
				setFaceParams(Integer.parseInt(aVs[0].trim()), TexParams.load(aVs, 1));
			}
			if("faceTexturePath".equalsIgnoreCase(aT.tagName)) {
				setFaceTexture(aFace, ColorBck.Fill.FILE + aOs.elementAt(++o).text.trim());
			}
			if("color2".equalsIgnoreCase(aT.tagName)) {
				color2 = Integer.parseInt(aOs.elementAt(++o).text.trim());
			}
			if("texRepeat".equalsIgnoreCase(aT.tagName)) {
				String[] aVs = aOs.elementAt(++o).text.trim().split(";");
				texRepU = Double.parseDouble(aVs[0]);
				texRepV = Double.parseDouble(aVs[1]);
			}
			if("texRot".equalsIgnoreCase(aT.tagName)) {
				texRot = Double.parseDouble(aOs.elementAt(++o).text.trim());
			}
			if("texLoop".equalsIgnoreCase(aT.tagName)) {
				String[] aVs = aOs.elementAt(++o).text.trim().split(";");
				texStartMS = Long.parseLong(aVs[0].trim());
				texEndMS = Long.parseLong(aVs[1].trim());
			}
			if("texScale".equalsIgnoreCase(aT.tagName)) {
				texScale = Double.parseDouble(aOs.elementAt(++o).text.trim());
			}
			if("texOffset".equalsIgnoreCase(aT.tagName)) {
				String[] aVs = aOs.elementAt(++o).text.trim().split(";");
				texOffU = Double.parseDouble(aVs[0]);
				texOffV = Double.parseDouble(aVs[1]);
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
			if("asset".equalsIgnoreCase(aT.tagName)) {
				asset = Shape.decode(aOs.elementAt(++o).text.trim());
			}
		}
		return o;
	}
}
