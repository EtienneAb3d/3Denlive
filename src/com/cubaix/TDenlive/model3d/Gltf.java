package com.cubaix.TDenlive.model3d;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.imageio.ImageIO;

/**
 * Reader of glTF 2.0 files (.glb, or .gltf with its .bin and images: relative files or data URIs) into
 * one Mesh3D for the objects of the library (ObjectCatalog): the meshes of the nodes of the scene
 * flattened with their transforms (matrix or TRS), triangles only, POSITION / NORMAL (computed flat
 * when missing) / TEXCOORD_n / COLOR_0, one Mesh3D.Material per glTF material (baseColorFactor,
 * baseColorTexture with KHR_texture_transform, alphaMode MASK / BLEND tested at 0.5, doubleSided).
 * The skins and animations are ignored (the mesh in its bind pose). Not supported: Draco / meshopt
 * compression, KTX2 textures, sparse accessors. The result is centered and scaled so that its largest
 * side is 1 (it fits in the cube -0.5..0.5 like the primitives), Y up as in glTF.
 */
public class Gltf {
	Map<String,Object> json;
	File dir;
	byte[] glbBin;
	HashMap<Integer, ByteBuffer> buffers = new HashMap<Integer, ByteBuffer>();
	HashMap<Integer, Texture3D> textures = new HashMap<Integer, Texture3D>();

	//Mesh being built
	FloatList pos = new FloatList(), nor = new FloatList(), uv = new FloatList(), col = new FloatList();
	IntList tris = new IntList(), triMat = new IntList();
	ArrayList<Mesh3D.Material> mats = new ArrayList<Mesh3D.Material>();
	HashMap<Integer,Integer> matIndex = new HashMap<Integer,Integer>();

	public static Mesh3D load(File aFile) throws Exception {
		Gltf aG = new Gltf();
		aG.dir = aFile.getAbsoluteFile().getParentFile();
		byte[] aBytes = Files.readAllBytes(aFile.toPath());
		String aText;
		if(aBytes.length >= 12 && aBytes[0] == 'g' && aBytes[1] == 'l' && aBytes[2] == 'T' && aBytes[3] == 'F') {
			ByteBuffer aBB = ByteBuffer.wrap(aBytes).order(ByteOrder.LITTLE_ENDIAN);
			int aLen = Math.min(aBytes.length, aBB.getInt(8));
			int aP = 12;
			aText = null;
			while(aP+8 <= aLen) {
				int aCLen = aBB.getInt(aP);
				int aCType = aBB.getInt(aP+4);
				if(aCType == 0x4E4F534A) {
					aText = new String(aBytes, aP+8, aCLen, StandardCharsets.UTF_8);
				}
				else if(aCType == 0x004E4942) {
					aG.glbBin = java.util.Arrays.copyOfRange(aBytes, aP+8, aP+8+aCLen);
				}
				aP += 8+aCLen;
			}
			if(aText == null) {
				throw new Exception("GLB without JSON: "+aFile);
			}
		}
		else {
			aText = new String(aBytes, StandardCharsets.UTF_8);
		}
		aG.json = obj(new Json(aText).value());
		aG.checkExtensions();
		aG.readScene();
		return aG.build();
	}

	void checkExtensions() throws Exception {
		for(Object aE : list(json.get("extensionsRequired"))) {
			String aN = String.valueOf(aE);
			if(!"KHR_texture_transform".equals(aN) && !"KHR_materials_unlit".equals(aN) && !aN.startsWith("KHR_materials_")) {
				throw new Exception("glTF extension not supported: "+aN);
			}
		}
	}

	void readScene() throws Exception {
		List<Object> aNodes = list(json.get("nodes"));
		List<Object> aScenes = list(json.get("scenes"));
		List<Object> aRoots;
		if(!aScenes.isEmpty()) {
			int aS = (int)num(json.get("scene"), 0);
			aRoots = list(obj(aScenes.get(Math.min(aS, aScenes.size()-1))).get("nodes"));
		}
		else {
			//No scene: the nodes that are nobody's child
			boolean[] aChild = new boolean[aNodes.size()];
			for(Object aN : aNodes) {
				for(Object aC : list(obj(aN).get("children"))) {
					aChild[(int)num(aC, 0)] = true;
				}
			}
			aRoots = new ArrayList<Object>();
			for(int n = 0;n < aNodes.size();n++) {
				if(!aChild[n]) {
					aRoots.add((double)n);
				}
			}
			if(aNodes.isEmpty() && !list(json.get("meshes")).isEmpty()) {
				//Meshes alone
				for(int m = 0;m < list(json.get("meshes")).size();m++) {
					addMesh(m, identity());
				}
				return;
			}
		}
		for(Object aR : aRoots) {
			node((int)num(aR, 0), identity(), 0);
		}
	}

	void node(int aN, double[] aParent, int aDepth) throws Exception {
		if(aDepth > 64) {
			return;
		}
		Map<String,Object> aNode = obj(list(json.get("nodes")).get(aN));
		double[] aM = mul(aParent, localMatrix(aNode));
		if(aNode.get("mesh") != null) {
			addMesh((int)num(aNode.get("mesh"), 0), aM);
		}
		for(Object aC : list(aNode.get("children"))) {
			node((int)num(aC, 0), aM, aDepth+1);
		}
	}

	static double[] identity() {
		return new double[] {1,0,0,0, 0,1,0,0, 0,0,1,0, 0,0,0,1};
	}

	//4x4 matrices column major, as glTF
	static double[] mul(double[] aA, double[] aB) {
		double[] aR = new double[16];
		for(int c = 0;c < 4;c++) {
			for(int r = 0;r < 4;r++) {
				double aS = 0;
				for(int k = 0;k < 4;k++) {
					aS += aA[k*4+r]*aB[c*4+k];
				}
				aR[c*4+r] = aS;
			}
		}
		return aR;
	}

	static double[] localMatrix(Map<String,Object> aNode) {
		List<Object> aMat = list(aNode.get("matrix"));
		if(aMat.size() == 16) {
			double[] aM = new double[16];
			for(int i = 0;i < 16;i++) {
				aM[i] = num(aMat.get(i), 0);
			}
			return aM;
		}
		double[] aT = vec(aNode.get("translation"), new double[] {0, 0, 0});
		double[] aQ = vec(aNode.get("rotation"), new double[] {0, 0, 0, 1});
		double[] aS = vec(aNode.get("scale"), new double[] {1, 1, 1});
		double x = aQ[0], y = aQ[1], z = aQ[2], w = aQ[3];
		double[] aR = {
				1-2*(y*y+z*z), 2*(x*y+z*w), 2*(x*z-y*w),
				2*(x*y-z*w), 1-2*(x*x+z*z), 2*(y*z+x*w),
				2*(x*z+y*w), 2*(y*z-x*w), 1-2*(x*x+y*y)};
		return new double[] {
				aR[0]*aS[0], aR[1]*aS[0], aR[2]*aS[0], 0,
				aR[3]*aS[1], aR[4]*aS[1], aR[5]*aS[1], 0,
				aR[6]*aS[2], aR[7]*aS[2], aR[8]*aS[2], 0,
				aT[0], aT[1], aT[2], 1};
	}

	static double[] vec(Object aO, double[] aDef) {
		List<Object> aL = list(aO);
		if(aL.size() != aDef.length) {
			return aDef;
		}
		double[] aV = new double[aDef.length];
		for(int i = 0;i < aV.length;i++) {
			aV[i] = num(aL.get(i), aDef[i]);
		}
		return aV;
	}

	void addMesh(int aMesh, double[] aM) throws Exception {
		Map<String,Object> aMeshO = obj(list(json.get("meshes")).get(aMesh));
		//Normal matrix: inverse transpose of the 3x3 part
		double[] aM3 = {aM[0], aM[1], aM[2], aM[4], aM[5], aM[6], aM[8], aM[9], aM[10]};
		double aDet = aM3[0]*(aM3[4]*aM3[8]-aM3[7]*aM3[5]) - aM3[3]*(aM3[1]*aM3[8]-aM3[7]*aM3[2]) + aM3[6]*(aM3[1]*aM3[5]-aM3[4]*aM3[2]);
		boolean aFlip = aDet < 0;
		for(Object aPO : list(aMeshO.get("primitives"))) {
			Map<String,Object> aP = obj(aPO);
			int aMode = (int)num(aP.get("mode"), 4);
			if(aMode != 4 && aMode != 5 && aMode != 6) {
				continue;
			}
			Map<String,Object> aAttr = obj(aP.get("attributes"));
			if(aAttr.get("POSITION") == null) {
				continue;
			}
			if(obj(aP.get("extensions")).containsKey("KHR_draco_mesh_compression")) {
				throw new Exception("Draco compressed glTF not supported");
			}
			float[] aPos = accessor((int)num(aAttr.get("POSITION"), 0));
			int aN = aPos.length/3;
			float[] aNor = aAttr.get("NORMAL") != null ? accessor((int)num(aAttr.get("NORMAL"), 0)) : null;
			int aMatI = aP.get("material") != null ? (int)num(aP.get("material"), 0) : -1;
			Mesh3D.Material aMat = material(aMatI);
			int aTC = aMat.texCoord;
			float[] aUV = aAttr.get("TEXCOORD_"+aTC) != null ? accessor((int)num(aAttr.get("TEXCOORD_"+aTC), 0)) : null;
			float[] aCol = null;
			int aColN = 0;
			if(aAttr.get("COLOR_0") != null) {
				int aCA = (int)num(aAttr.get("COLOR_0"), 0);
				aCol = accessor(aCA);
				aColN = "VEC3".equals(obj(list(json.get("accessors")).get(aCA)).get("type")) ? 3 : 4;
			}
			int[] aIdx;
			if(aP.get("indices") != null) {
				float[] aF = accessor((int)num(aP.get("indices"), 0));
				aIdx = new int[aF.length];
				for(int i = 0;i < aF.length;i++) {
					aIdx[i] = (int)aF[i];
				}
			}
			else {
				aIdx = new int[aN];
				for(int i = 0;i < aN;i++) {
					aIdx[i] = i;
				}
			}
			//Strips and fans to triangles
			if(aMode == 5 || aMode == 6) {
				IntList aT = new IntList();
				for(int i = 2;i < aIdx.length;i++) {
					if(aMode == 5) {
						if((i & 1) == 0) {
							aT.add(aIdx[i-2]); aT.add(aIdx[i-1]); aT.add(aIdx[i]);
						}
						else {
							aT.add(aIdx[i-1]); aT.add(aIdx[i-2]); aT.add(aIdx[i]);
						}
					}
					else {
						aT.add(aIdx[0]); aT.add(aIdx[i-1]); aT.add(aIdx[i]);
					}
				}
				aIdx = aT.toArray();
			}
			int aMI = matIndex.computeIfAbsent(aMatI, k -> {
				mats.add(aMat);
				return mats.size()-1;
			});
			if(aNor == null) {
				//Flat normals: own vertices per triangle
				for(int t = 0;t+2 < aIdx.length;t += 3) {
					int[] aV = {aIdx[t], aIdx[t+1], aIdx[t+2]};
					double[][] aW = new double[3][];
					for(int k = 0;k < 3;k++) {
						aW[k] = point(aM, aPos, aV[k]);
					}
					double[] aNrm = Math3D.cross(Math3D.sub(aW[1], aW[0]), Math3D.sub(aW[2], aW[0]));
					if(aFlip) {
						aNrm = Math3D.mul(aNrm, -1);
					}
					int aBase = pos.size/3;
					for(int k = 0;k < 3;k++) {
						vertex(aW[k], aNrm, aUV, aCol, aColN, aV[k], aMat);
					}
					tri(aBase, aBase+(aFlip ? 2 : 1), aBase+(aFlip ? 1 : 2), aMI);
				}
				continue;
			}
			double[] aNM = normalMatrix(aM3, aDet);
			int aBase = pos.size/3;
			for(int v = 0;v < aN;v++) {
				double[] aNrm = {aNor[v*3], aNor[v*3+1], aNor[v*3+2]};
				aNrm = new double[] {aNM[0]*aNrm[0]+aNM[3]*aNrm[1]+aNM[6]*aNrm[2], aNM[1]*aNrm[0]+aNM[4]*aNrm[1]+aNM[7]*aNrm[2], aNM[2]*aNrm[0]+aNM[5]*aNrm[1]+aNM[8]*aNrm[2]};
				vertex(point(aM, aPos, v), aNrm, aUV, aCol, aColN, v, aMat);
			}
			for(int t = 0;t+2 < aIdx.length;t += 3) {
				if(aFlip) {
					tri(aBase+aIdx[t], aBase+aIdx[t+2], aBase+aIdx[t+1], aMI);
				}
				else {
					tri(aBase+aIdx[t], aBase+aIdx[t+1], aBase+aIdx[t+2], aMI);
				}
			}
		}
	}

	static double[] normalMatrix(double[] aM3, double aDet) {
		if(Math.abs(aDet) < 1e-30) {
			return aM3;
		}
		//Inverse transpose = cofactor matrix / det (columns major in, same layout out)
		double a = aM3[0], b = aM3[3], c = aM3[6], d = aM3[1], e = aM3[4], f = aM3[7], g = aM3[2], h = aM3[5], i = aM3[8];
		double[] aCof = {
				(e*i-f*h), -(b*i-c*h), (b*f-c*e),
				-(d*i-f*g), (a*i-c*g), -(a*f-c*d),
				(d*h-e*g), -(a*h-b*g), (a*e-b*d)};
		//aCof is the adjugate row major (inverse = aCof / det); the inverse transpose in column major
		//has the same layout
		double[] aN = new double[9];
		for(int k = 0;k < 9;k++) {
			aN[k] = aCof[k]/aDet;
		}
		return aN;
	}

	static double[] point(double[] aM, float[] aPos, int aV) {
		double x = aPos[aV*3], y = aPos[aV*3+1], z = aPos[aV*3+2];
		return new double[] {aM[0]*x+aM[4]*y+aM[8]*z+aM[12], aM[1]*x+aM[5]*y+aM[9]*z+aM[13], aM[2]*x+aM[6]*y+aM[10]*z+aM[14]};
	}

	void vertex(double[] aP, double[] aN, float[] aUV, float[] aCol, int aColN, int aV, Mesh3D.Material aMat) {
		pos.add((float)aP[0]); pos.add((float)aP[1]); pos.add((float)aP[2]);
		double aL = Math.sqrt(aN[0]*aN[0]+aN[1]*aN[1]+aN[2]*aN[2]);
		if(aL < 1e-20) {
			aL = 1;
		}
		nor.add((float)(aN[0]/aL)); nor.add((float)(aN[1]/aL)); nor.add((float)(aN[2]/aL));
		double aU = 0, aVv = 0;
		if(aUV != null && aV*2+1 < aUV.length) {
			aU = aUV[aV*2];
			aVv = aUV[aV*2+1];
			if(aMat.texTransform != null) {
				double[] aT = aMat.texTransform;
				double aU2 = aT[0]*aU + aT[2]*aVv + aT[4];
				double aV2 = aT[1]*aU + aT[3]*aVv + aT[5];
				aU = aU2;
				aVv = aV2;
			}
		}
		//glTF: v down from the top of the image; Texture3D: v up from its bottom
		uv.add((float)aU); uv.add((float)(1-aVv));
		float aR = 1, aG = 1, aB = 1;
		if(aCol != null && aV*aColN+2 < aCol.length) {
			aR = srgb(aCol[aV*aColN]); aG = srgb(aCol[aV*aColN+1]); aB = srgb(aCol[aV*aColN+2]);
		}
		col.add(aR); col.add(aG); col.add(aB);
	}

	/**
	 * Linear 0..1 to sRGB 0..1
	 */
	static float srgb(double aL) {
		aL = Math.max(0, Math.min(1, aL));
		return (float)(aL <= 0.0031308 ? 12.92*aL : 1.055*Math.pow(aL, 1/2.4) - 0.055);
	}

	void tri(int aA, int aB, int aC, int aMat) {
		tris.add(aA); tris.add(aB); tris.add(aC);
		triMat.add(aMat);
	}

	Mesh3D.Material material(int aM) throws Exception {
		Mesh3D.Material aMat = new Mesh3D.Material();
		if(aM < 0) {
			return aMat;
		}
		Map<String,Object> aO = obj(list(json.get("materials")).get(aM));
		Map<String,Object> aPbr = obj(aO.get("pbrMetallicRoughness"));
		//Older files (Sketchfab): specular-glossiness, its diffuse is the base color
		Map<String,Object> aSG = obj(obj(aO.get("extensions")).get("KHR_materials_pbrSpecularGlossiness"));
		if(!aSG.isEmpty()) {
			aPbr = new HashMap<String,Object>();
			aPbr.put("baseColorFactor", aSG.get("diffuseFactor"));
			aPbr.put("baseColorTexture", aSG.get("diffuseTexture"));
		}
		double[] aF = vec(aPbr.get("baseColorFactor"), new double[] {1, 1, 1, 1});
		//Linear in glTF, the renderer works on sRGB values (the textures are sRGB)
		aMat.r = srgb(aF[0]);
		aMat.g = srgb(aF[1]);
		aMat.b = srgb(aF[2]);
		aMat.alphaCut = !"OPAQUE".equals(str(aO.get("alphaMode"), "OPAQUE"));
		aMat.doubleSided = Boolean.TRUE.equals(aO.get("doubleSided"));
		Map<String,Object> aBT = obj(aPbr.get("baseColorTexture"));
		if(aBT.get("index") != null) {
			aMat.texCoord = (int)num(aBT.get("texCoord"), 0);
			aMat.tex = texture((int)num(aBT.get("index"), 0));
			Map<String,Object> aTT = obj(obj(aBT.get("extensions")).get("KHR_texture_transform"));
			if(!aTT.isEmpty()) {
				if(aTT.get("texCoord") != null) {
					aMat.texCoord = (int)num(aTT.get("texCoord"), 0);
				}
				double[] aOff = vec(aTT.get("offset"), new double[] {0, 0});
				double[] aSc = vec(aTT.get("scale"), new double[] {1, 1});
				double aRot = num(aTT.get("rotation"), 0);
				double aC = Math.cos(aRot), aS = Math.sin(aRot);
				//T * R * S of the spec, R = [[c, s], [-s, c]] (rows): {m00, m10, m01, m11, tx, ty}
				aMat.texTransform = new double[] {aC*aSc[0], -aS*aSc[0], aS*aSc[1], aC*aSc[1], aOff[0], aOff[1]};
			}
		}
		return aMat;
	}

	Texture3D texture(int aT) throws Exception {
		Texture3D aTex = textures.get(aT);
		if(aTex != null || textures.containsKey(aT)) {
			return aTex;
		}
		Map<String,Object> aTO = obj(list(json.get("textures")).get(aT));
		Object aSrc = aTO.get("source");
		if(aSrc == null) {
			textures.put(aT, null);
			return null;
		}
		Map<String,Object> aImg = obj(list(json.get("images")).get((int)num(aSrc, 0)));
		byte[] aBytes;
		if(aImg.get("bufferView") != null) {
			aBytes = bufferView((int)num(aImg.get("bufferView"), 0));
		}
		else {
			aBytes = uriBytes(str(aImg.get("uri"), ""));
		}
		BufferedImage aBI = null;
		try {
			aBI = ImageIO.read(new ByteArrayInputStream(aBytes));
		}
		catch(Throwable t) {
			t.printStackTrace(System.err);
		}
		if(aBI == null) {
			System.err.println("glTF image not readable (PNG / JPEG only): "+aImg.get("uri"));
			textures.put(aT, null);
			return null;
		}
		aTex = Texture3D.of(aBI);
		textures.put(aT, aTex);
		return aTex;
	}

	byte[] uriBytes(String aUri) throws Exception {
		if(aUri.startsWith("data:")) {
			int aC = aUri.indexOf(',');
			return java.util.Base64.getDecoder().decode(aUri.substring(aC+1));
		}
		String aPath = java.net.URLDecoder.decode(aUri.replace("+", "%2B"), StandardCharsets.UTF_8);
		File aF = new File(dir, aPath);
		if(!aF.isFile()) {
			throw new Exception("glTF file missing: "+aF);
		}
		return Files.readAllBytes(aF.toPath());
	}

	ByteBuffer buffer(int aB) throws Exception {
		ByteBuffer aBB = buffers.get(aB);
		if(aBB == null) {
			Map<String,Object> aBO = obj(list(json.get("buffers")).get(aB));
			byte[] aBytes;
			if(aBO.get("uri") == null) {
				if(glbBin == null) {
					throw new Exception("glTF buffer without data");
				}
				aBytes = glbBin;
			}
			else {
				aBytes = uriBytes(str(aBO.get("uri"), ""));
			}
			aBB = ByteBuffer.wrap(aBytes).order(ByteOrder.LITTLE_ENDIAN);
			buffers.put(aB, aBB);
		}
		return aBB;
	}

	byte[] bufferView(int aV) throws Exception {
		Map<String,Object> aBV = obj(list(json.get("bufferViews")).get(aV));
		ByteBuffer aBB = buffer((int)num(aBV.get("buffer"), 0));
		int aOff = (int)num(aBV.get("byteOffset"), 0);
		int aLen = (int)num(aBV.get("byteLength"), 0);
		byte[] aR = new byte[aLen];
		System.arraycopy(aBB.array(), aOff, aR, 0, aLen);
		return aR;
	}

	/**
	 * Values of an accessor as floats (normalized integers to 0..1 / -1..1)
	 */
	float[] accessor(int aA) throws Exception {
		Map<String,Object> aAO = obj(list(json.get("accessors")).get(aA));
		if(aAO.get("sparse") != null) {
			throw new Exception("glTF sparse accessors not supported");
		}
		int aCount = (int)num(aAO.get("count"), 0);
		int aComps;
		switch(str(aAO.get("type"), "SCALAR")) {
		case "VEC2": aComps = 2; break;
		case "VEC3": aComps = 3; break;
		case "VEC4": aComps = 4; break;
		case "MAT4": aComps = 16; break;
		default: aComps = 1; break;
		}
		int aCT = (int)num(aAO.get("componentType"), 5126);
		boolean aNorm = Boolean.TRUE.equals(aAO.get("normalized"));
		float[] aR = new float[aCount*aComps];
		if(aAO.get("bufferView") == null) {
			return aR;
		}
		Map<String,Object> aBV = obj(list(json.get("bufferViews")).get((int)num(aAO.get("bufferView"), 0)));
		ByteBuffer aBB = buffer((int)num(aBV.get("buffer"), 0));
		int aSize = aCT == 5126 || aCT == 5125 ? 4 : (aCT == 5122 || aCT == 5123 ? 2 : 1);
		int aStride = (int)num(aBV.get("byteStride"), 0);
		if(aStride <= 0) {
			aStride = aSize*aComps;
		}
		int aBase = (int)num(aBV.get("byteOffset"), 0) + (int)num(aAO.get("byteOffset"), 0);
		for(int i = 0;i < aCount;i++) {
			for(int c = 0;c < aComps;c++) {
				int aP = aBase + i*aStride + c*aSize;
				float aV;
				switch(aCT) {
				case 5120: aV = aBB.get(aP); if(aNorm) aV = Math.max(aV/127f, -1); break;
				case 5121: aV = aBB.get(aP) & 0xFF; if(aNorm) aV /= 255f; break;
				case 5122: aV = aBB.getShort(aP); if(aNorm) aV = Math.max(aV/32767f, -1); break;
				case 5123: aV = aBB.getShort(aP) & 0xFFFF; if(aNorm) aV /= 65535f; break;
				case 5125: aV = (float)(aBB.getInt(aP) & 0xFFFFFFFFL); break;
				default: aV = aBB.getFloat(aP); break;
				}
				aR[i*aComps+c] = aV;
			}
		}
		return aR;
	}

	/**
	 * Mesh centered on its bounds, largest side 1
	 */
	Mesh3D build() {
		Mesh3D aM = new Mesh3D();
		aM.pos = pos.toArray();
		aM.nor = nor.toArray();
		aM.uv = uv.toArray();
		aM.vcol = col.toArray();
		aM.tris = tris.toArray();
		aM.triMat = triMat.toArray();
		aM.mats = mats.toArray(new Mesh3D.Material[0]);
		aM.nbVerts = aM.pos.length/3;
		aM.nbTris = aM.tris.length/3;
		for(Mesh3D.Material aMat : aM.mats) {
			if(aMat.doubleSided) {
				aM.twoSided = true;
			}
		}
		if(aM.nbVerts > 0) {
			double[] aMin = {Double.MAX_VALUE, Double.MAX_VALUE, Double.MAX_VALUE};
			double[] aMax = {-Double.MAX_VALUE, -Double.MAX_VALUE, -Double.MAX_VALUE};
			//Bounds of the vertices used by triangles
			for(int t : aM.tris) {
				for(int c = 0;c < 3;c++) {
					aMin[c] = Math.min(aMin[c], aM.pos[t*3+c]);
					aMax[c] = Math.max(aMax[c], aM.pos[t*3+c]);
				}
			}
			double aSide = Math.max(aMax[0]-aMin[0], Math.max(aMax[1]-aMin[1], aMax[2]-aMin[2]));
			double aK = aSide > 0 ? 1/aSide : 1;
			for(int v = 0;v < aM.nbVerts;v++) {
				for(int c = 0;c < 3;c++) {
					aM.pos[v*3+c] = (float)((aM.pos[v*3+c] - (aMin[c]+aMax[c])/2)*aK);
				}
			}
			aM.size = new double[] {(aMax[0]-aMin[0])*aK, (aMax[1]-aMin[1])*aK, (aMax[2]-aMin[2])*aK};
		}
		return aM;
	}

	//== Lists of primitives ==

	static class FloatList {
		float[] a = new float[1024];
		int size = 0;

		void add(float aV) {
			if(size == a.length) {
				a = java.util.Arrays.copyOf(a, a.length*2);
			}
			a[size++] = aV;
		}

		float[] toArray() {
			return java.util.Arrays.copyOf(a, size);
		}
	}

	static class IntList {
		int[] a = new int[1024];
		int size = 0;

		void add(int aV) {
			if(size == a.length) {
				a = java.util.Arrays.copyOf(a, a.length*2);
			}
			a[size++] = aV;
		}

		int[] toArray() {
			return java.util.Arrays.copyOf(a, size);
		}
	}

	//== JSON ==

	@SuppressWarnings("unchecked")
	static Map<String,Object> obj(Object aO) {
		return aO instanceof Map ? (Map<String,Object>)aO : new HashMap<String,Object>();
	}

	@SuppressWarnings("unchecked")
	static List<Object> list(Object aO) {
		return aO instanceof List ? (List<Object>)aO : new ArrayList<Object>();
	}

	static double num(Object aO, double aDef) {
		return aO instanceof Number ? ((Number)aO).doubleValue() : aDef;
	}

	static String str(Object aO, String aDef) {
		return aO instanceof String ? (String)aO : aDef;
	}

	/**
	 * Minimal JSON parser: objects (LinkedHashMap), arrays (ArrayList), numbers (Double), strings,
	 * booleans, null
	 */
	public static class Json {
		final String s;
		int p = 0;

		public Json(String aS) {
			s = aS;
		}

		public static Object parse(String aS) throws Exception {
			return new Json(aS).value();
		}

		void ws() {
			while(p < s.length() && Character.isWhitespace(s.charAt(p))) {
				p++;
			}
		}

		Object value() throws Exception {
			ws();
			if(p >= s.length()) {
				throw new Exception("JSON: unexpected end");
			}
			char c = s.charAt(p);
			switch(c) {
			case '{': {
				p++;
				LinkedHashMap<String,Object> aM = new LinkedHashMap<String,Object>();
				ws();
				if(s.charAt(p) == '}') {
					p++;
					return aM;
				}
				while(true) {
					ws();
					String aK = string();
					ws();
					expect(':');
					aM.put(aK, value());
					ws();
					if(s.charAt(p) == ',') {
						p++;
						continue;
					}
					expect('}');
					return aM;
				}
			}
			case '[': {
				p++;
				ArrayList<Object> aL = new ArrayList<Object>();
				ws();
				if(s.charAt(p) == ']') {
					p++;
					return aL;
				}
				while(true) {
					aL.add(value());
					ws();
					if(s.charAt(p) == ',') {
						p++;
						continue;
					}
					expect(']');
					return aL;
				}
			}
			case '"':
				return string();
			case 't':
				p += 4;
				return Boolean.TRUE;
			case 'f':
				p += 5;
				return Boolean.FALSE;
			case 'n':
				p += 4;
				return null;
			default: {
				int aS = p;
				while(p < s.length() && "+-0123456789.eE".indexOf(s.charAt(p)) >= 0) {
					p++;
				}
				if(aS == p) {
					throw new Exception("JSON: unexpected '"+c+"' at "+p);
				}
				return Double.parseDouble(s.substring(aS, p));
			}
			}
		}

		void expect(char aC) throws Exception {
			if(p >= s.length() || s.charAt(p) != aC) {
				throw new Exception("JSON: '"+aC+"' expected at "+p);
			}
			p++;
		}

		String string() throws Exception {
			expect('"');
			StringBuilder aSB = new StringBuilder();
			while(p < s.length()) {
				char c = s.charAt(p++);
				if(c == '"') {
					return aSB.toString();
				}
				if(c == '\\') {
					char e = s.charAt(p++);
					switch(e) {
					case 'n': aSB.append('\n'); break;
					case 't': aSB.append('\t'); break;
					case 'r': aSB.append('\r'); break;
					case 'b': aSB.append('\b'); break;
					case 'f': aSB.append('\f'); break;
					case 'u': aSB.append((char)Integer.parseInt(s.substring(p, p+4), 16)); p += 4; break;
					default: aSB.append(e); break;
					}
				}
				else {
					aSB.append(c);
				}
			}
			throw new Exception("JSON: unterminated string");
		}
	}
}
