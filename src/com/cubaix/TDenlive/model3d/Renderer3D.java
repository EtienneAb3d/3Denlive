package com.cubaix.TDenlive.model3d;

import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.util.Set;

/**
 * Software renderer of a Model3D: z-buffer (1/z), Gouraud shading with a light tied to the center camera
 * (same lighting for both eyes), supersampling for the anti-aliasing, transparent or opaque background.
 * A textured object: texture coordinates interpolated with the perspective, an image sampled at the
 * mipmap level of each triangle (Texture3D), lit by the diffuse factor and the highlight of its vertices.
 */
public class Renderer3D {
	public static class Options {
		public int ss = 2;//Supersampling (1 = none)
		public int bck = 0;//ARGB, 0 = transparent
		public boolean grid = false;//Floor grid (editor)
		public double frameH = -1;//Height of the output frame in pixels (field of view), -1 = image height
		public Set<Integer> selected = null;//Ids of the objects outlined
		public boolean ids = false;//Id buffer of the result
		public int outline = 0xFFFF9A1F;
		//Media textures (Texture3D.MEDIA): processing mode and media time of their images
		public int mode = com.cubaix.TDenlive.TDConfig.PROCESSING_MODE_WORK;
		public long timeMS = 0;
		//>= 0: the media time of the video textures, shown as it is (not looped over their range: preview of a time)
		public long texTimeMS = -1;
		//Set by the rendering: a media texture was missing (not loaded yet), drawn in the color of its object
		public boolean missing = false;
	}

	public static class Result {
		public BufferedImage image;
		public int[] ids;//Object id + 1 per pixel, 0 = none
		public int width, height;

		public int idAt(int aX, int aY) {
			if(ids == null || aX < 0 || aY < 0 || aX >= width || aY >= height) {
				return -1;
			}
			return ids[aY*width+aX]-1;
		}
	}

	/**
	 * Texture of the object being drawn: an image (tex), or a procedural texture between c1 and c2
	 */
	static class TexCtx {
		Texture3D tex;
		String procedural;
		int c1, c2;
		int level;//Mipmap level of the current triangle
		//Material of an object of the library: the texels below alpha 0.5 not drawn; the texture
		//multiplied by the color of the vertices (indices 8, 9, 10 of the points, 0..1)
		boolean alphaCut = false;
		boolean tinted = false;
		//An image / a video (Texture3D.isPicture()): one image per repeat cell, its ratio kept, fitted whole in the
		//cell (fu, fv >= 1: the image coordinates per cell coordinate, set per triangle from the shape of its part),
		//enlarged by 1 / k around the middle, moved by offU / offV, the color of the object (outside) around it
		boolean picture = false;
		double fu = 1, fv = 1, k = 1, offU = 0, offV = 0;
		boolean wrapU = false, wrapV = false;
		int outside = 0;
		//Texels per uv unit relative to the image (mipmap level): fu * fv * k^2
		double areaK = 1;

		int sample(double aU, double aV) {
			if(picture) {
				double aCu = wrapU ? aU - Math.floor(aU) : aU;
				double aCv = wrapV ? aV - Math.floor(aV) : aV;
				double aS = 0.5 + (aCu-0.5)*fu*k + offU;
				double aT = 0.5 + (aCv-0.5)*fv*k + offV;
				if(aS < 0 || aS > 1 || aT < 0 || aT > 1) {
					return outside;
				}
				return tex.sampleClamp(aS, aT, level);
			}
			if(tex != null) {
				return tex.sample(aU, aV, level);
			}
			double aM = com.cubaix.TDenlive.medias.ColorBck.Fill.mix(procedural, aU-Math.floor(aU), 1-(aV-Math.floor(aV)));
			int aR = (int)(((c1>>16)&0xFF) + (((c2>>16)&0xFF)-((c1>>16)&0xFF))*aM + 0.5);
			int aG = (int)(((c1>>8)&0xFF) + (((c2>>8)&0xFF)-((c1>>8)&0xFF))*aM + 0.5);
			int aB = (int)((c1&0xFF) + ((c2&0xFF)-(c1&0xFF))*aM + 0.5);
			return (aR<<16)|(aG<<8)|aB;
		}
	}

	//Sample buffers
	int w, h;
	int[] col;
	float[] zb;
	int[] idb;

	/**
	 * Image of the model seen by one eye (-1 left, 0 center, 1 right), turned by aSpinDeg around the
	 * vertical axis of the target
	 */
	public static Result render(Model3D aM, int aW, int aH, int aEye, double aSpinDeg, Options aOpt) {
		return new Renderer3D().doRender(aM, Math.max(1, aW), Math.max(1, aH), aEye, aSpinDeg, aOpt == null ? new Options() : aOpt);
	}

	Result doRender(Model3D aM, int aW, int aH, int aEye, double aSpinDeg, Options aOpt) {
		int aSS = Math.max(1, aOpt.ss);
		w = aW*aSS;
		h = aH*aSS;
		col = new int[w*h];
		zb = new float[w*h];
		if(aOpt.bck != 0) {
			java.util.Arrays.fill(col, aOpt.bck);
		}
		boolean aIds = aOpt.ids || aOpt.selected != null;
		idb = aIds ? new int[w*h] : null;
		double aFrameH = (aOpt.frameH > 0 ? aOpt.frameH : aH)*aSS;
		Camera3D aCam = new Camera3D(aM, w/2.0, h/2.0, aFrameH, aEye);
		//Light from the center camera: same shading for both eyes
		Camera3D aCenter = aEye == 0 ? aCam : new Camera3D(aM, w/2.0, h/2.0, aFrameH, 0);
		double[] aView = Math3D.mul(aCenter.fwd, -1);
		double[] aLight = Math3D.normalize(Math3D.add(Math3D.add(Math3D.mul(aView, 0.75), Math3D.mul(aCenter.up, 0.6)), Math3D.mul(aCenter.right, -0.45)));
		double[] aHalf = Math3D.normalize(Math3D.add(aLight, aView));
		double[] aSpin = Math3D.rotation(new double[] {0, 1, 0}, Math.toRadians(aSpinDeg));
		double[] aPivot = aM.getTarget();

		for(Object3D aO : aM.objects) {
			drawObject(aM, aO, aCam, aLight, aHalf, aView, aSpin, aPivot, aSpinDeg != 0, aEye, aOpt);
		}
		if(aOpt.grid) {
			drawGrid(aCam);
		}

		Result aR = new Result();
		aR.width = aW;
		aR.height = aH;
		aR.image = new BufferedImage(aW, aH, BufferedImage.TYPE_INT_ARGB);
		int[] aOut = ((DataBufferInt)aR.image.getRaster().getDataBuffer()).getData();
		downsample(aOut, aW, aH, aSS);
		if(aIds) {
			aR.ids = new int[aW*aH];
			int aOff = aSS/2;
			for(int y = 0;y < aH;y++) {
				for(int x = 0;x < aW;x++) {
					aR.ids[y*aW+x] = idb[(y*aSS+aOff)*w + x*aSS+aOff];
				}
			}
			if(aOpt.selected != null && !aOpt.selected.isEmpty()) {
				outline(aOut, aR.ids, aW, aH, aOpt.selected, aOpt.outline);
			}
		}
		return aR;
	}

	void drawObject(Model3D aModel, Object3D aO, Camera3D aCam, double[] aLight, double[] aHalf, double[] aView, double[] aSpin, double[] aPivot, boolean aSpinning, int aEye, Options aOpt) {
		Mesh3D aMesh = aO.mesh();
		int aN = aMesh.nbVerts;
		if(aN == 0) {
			return;
		}
		double[] aW0 = aModel.world(aO);
		double[] aMat = Model3D.matrixOf(aW0);
		double[] aPos = Model3D.translationOf(aW0);
		if(aSpinning) {
			aMat = Math3D.mulM(aSpin, aMat);
			aPos = Math3D.add(aPivot, Math3D.apply(aSpin, Math3D.sub(aPos, aPivot)));
		}
		//Normals: inverse transpose of the world matrix
		double[] aNM = Math3D.transpose(Math3D.invert(aMat));
		double aBr = (aO.color >> 16) & 0xFF, aBg = (aO.color >> 8) & 0xFF, aBb = aO.color & 0xFF;
		//Object of the library with its own materials (a texture chosen for the object replaces them)
		boolean aOwn = aMesh.mats != null && !aO.hasAnyTexture();
		if(aOwn) {
			drawOwnMaterials(aMesh, aMat, aPos, aNM, aCam, aLight, aHalf, aO.id+1);
			return;
		}
		//Texture of each face (Object3D.faceTexture()), one context per texture; null = the color of the object
		int aNF = Math.max(1, aMesh.nbFaces);
		TexCtx[] aFaceTex = new TexCtx[aNF];
		//Settings of the texture of each face (Object3D.texParams()); one context per texture and settings
		Object3D.TexParams[] aFaceP = new Object3D.TexParams[aNF];
		double[] aFaceCos = new double[aNF], aFaceSin = new double[aNF];
		java.util.HashMap<String, TexCtx> aCtxs = new java.util.HashMap<String, TexCtx>();
		boolean aAnyTex = false, aAnyPicture = false;
		for(int f = 0;f < aNF;f++) {
			aFaceP[f] = aO.texParams(f);
			aFaceCos[f] = Math.cos(Math.toRadians(aFaceP[f].rot));
			aFaceSin[f] = Math.sin(Math.toRadians(aFaceP[f].rot));
			String aT = aO.faceTexture(f);
			if(aT == null || com.cubaix.TDenlive.medias.ColorBck.Fill.PLAIN.equals(aT)) {
				continue;
			}
			String aCK = aT + "|" + aFaceP[f].key();
			if(!aCtxs.containsKey(aCK)) {
				aCtxs.put(aCK, texCtx(aO, aT, aFaceP[f], aEye, aOpt));
			}
			aFaceTex[f] = aCtxs.get(aCK);
			aAnyTex |= aFaceTex[f] != null;
			aAnyPicture |= aFaceTex[f] != null && aFaceTex[f].picture;
		}
		//Face of each vertex (the faces have their own vertices)
		int[] aVF = new int[aN];
		if(aMesh.triFace != null) {
			for(int t = 0;t < aMesh.nbTris;t++) {
				int aF = Math.min(aNF-1, aMesh.faceOf(t));
				aVF[aMesh.tris[t*3]] = aVF[aMesh.tris[t*3+1]] = aVF[aMesh.tris[t*3+2]] = aF;
			}
		}
		double[] aCx = new double[aN], aCy = new double[aN], aCz = new double[aN];
		float[] aCr = new float[aN], aCg = new float[aN], aCb = new float[aN];
		//Textured: r = diffuse factor, g = highlight (0..255), u, v
		float[] aTu = aAnyTex ? new float[aN] : null, aTv = aAnyTex ? new float[aN] : null;
		double[] aWp = aAnyPicture ? new double[aN*3] : null;
		for(int v = 0;v < aN;v++) {
			double[] aL = {aMesh.pos[v*3], aMesh.pos[v*3+1], aMesh.pos[v*3+2]};
			double[] aW = Math3D.add(Math3D.apply(aMat, aL), aPos);
			double[] aC = aCam.toCam(aW);
			aCx[v] = aC[0]; aCy[v] = aC[1]; aCz[v] = aC[2];
			if(aWp != null) {
				aWp[v*3] = aW[0]; aWp[v*3+1] = aW[1]; aWp[v*3+2] = aW[2];
			}
			double[] aNor = Math3D.normalize(Math3D.apply(aNM, new double[] {aMesh.nor[v*3], aMesh.nor[v*3+1], aMesh.nor[v*3+2]}));
			if(aMesh.twoSided && Math3D.dot(aNor, Math3D.sub(aCam.pos, aW)) < 0) {
				aNor = Math3D.mul(aNor, -1);
			}
			double aDif = Math.max(0, Math3D.dot(aNor, aLight));
			double aSpec = Math.pow(Math.max(0, Math3D.dot(aNor, aHalf)), 32)*0.35;
			double aK = 0.28 + 0.72*aDif;
			TexCtx aTex = aFaceTex[aVF[v]];
			if(aTex != null) {
				aCr[v] = (float)aK;
				aCg[v] = (float)(255*aSpec);
				Object3D.TexParams aP = aFaceP[aVF[v]];
				double aTexCos = aFaceCos[aVF[v]], aTexSin = aFaceSin[aVF[v]];
				double aTexK = 100/Math.max(1, aP.scale);
				//Turned around the middle of the face (the texture turns counterclockwise: its
				//coordinates turn the other way), then repeated and scaled
				double aU0 = aMesh.uv[v*2]-0.5, aV0 = aMesh.uv[v*2+1]-0.5;
				double aU = aTexCos*aU0 + aTexSin*aV0 + 0.5;
				double aV = -aTexSin*aU0 + aTexCos*aV0 + 0.5;
				if(aTex.picture) {
					//Cells of the repeats; the image fitted in each one by the sampler
					aTu[v] = (float)(aU*aP.repU);
					aTv[v] = (float)(aV*aP.repV);
					continue;
				}
				aTu[v] = (float)(aU*aP.repU*aTexK + aP.offU/100);
				aTv[v] = (float)(aV*aP.repV*aTexK + aP.offV/100);
				continue;
			}
			aCr[v] = (float)Math.min(255, aBr*aK + 255*aSpec);
			aCg[v] = (float)Math.min(255, aBg*aK + 255*aSpec);
			aCb[v] = (float)Math.min(255, aBb*aK + 255*aSpec);
		}
		int aId = aO.id+1;
		double[][] aPoly = new double[8][];
		//Image: ratio width / height of the cell of each part of the mesh (triangles sharing vertices)
		double[] aCellRatio = aAnyPicture ? cellRatios(aMesh, aWp, aTu, aTv) : null;
		for(int t = 0;t < aMesh.nbTris;t++) {
			int aA = aMesh.tris[t*3], aB = aMesh.tris[t*3+1], aC = aMesh.tris[t*3+2];
			TexCtx aTex = aFaceTex[Math.min(aNF-1, aMesh.faceOf(t))];
			if(aTex != null && aTex.picture) {
				//Fitted whole: the larger side of the image on the cell, the color of the object on the rest
				double aR = aCellRatio[t];
				double aImgRatio = aTex.tex.width()/(double)Math.max(1, aTex.tex.height());
				aTex.fu = Math.max(1, aR/aImgRatio);
				aTex.fv = Math.max(1, aImgRatio/aR);
				aTex.areaK = aTex.fu*aTex.fv*aTex.k*aTex.k;
			}
			double[] aVa, aVb, aVc;
			if(aTex != null) {
				aVa = new double[] {aCx[aA], aCy[aA], aCz[aA], aCr[aA], aCg[aA], 0, aTu[aA], aTv[aA]};
				aVb = new double[] {aCx[aB], aCy[aB], aCz[aB], aCr[aB], aCg[aB], 0, aTu[aB], aTv[aB]};
				aVc = new double[] {aCx[aC], aCy[aC], aCz[aC], aCr[aC], aCg[aC], 0, aTu[aC], aTv[aC]};
			}
			else {
				aVa = new double[] {aCx[aA], aCy[aA], aCz[aA], aCr[aA], aCg[aA], aCb[aA]};
				aVb = new double[] {aCx[aB], aCy[aB], aCz[aB], aCr[aB], aCg[aB], aCb[aB]};
				aVc = new double[] {aCx[aC], aCy[aC], aCz[aC], aCr[aC], aCg[aC], aCb[aC]};
			}
			if(aVa[2] > Camera3D.NEAR && aVb[2] > Camera3D.NEAR && aVc[2] > Camera3D.NEAR) {
				drawTri(aCam, aVa, aVb, aVc, aId, aTex);
				continue;
			}
			//Clipped by the near plane
			int aNP = clipNear(new double[][] {aVa, aVb, aVc}, aPoly);
			for(int p = 1;p < aNP-1;p++) {
				drawTri(aCam, aPoly[0], aPoly[p], aPoly[p+1], aId, aTex);
			}
		}
	}

	/**
	 * Context of the texture aTexture of the object aO (not PLAIN), null when its image is missing (the color of the
	 * object; a media not loaded yet sets aOpt.missing)
	 */
	static TexCtx texCtx(Object3D aO, String aTexture, Object3D.TexParams aP, int aEye, Options aOpt) {
		TexCtx aTex = new TexCtx();
		if(Texture3D.isMedia(aTexture)) {
			//A media of the media list: the view of the eye at the time
			aTex.tex = aOpt.texTimeMS >= 0 ? Texture3D.ofMedia(aTexture, aEye, aOpt.mode, aOpt.texTimeMS)
					: Texture3D.ofMedia(aTexture, aEye, aOpt.mode, aOpt.timeMS, aP.startMS, aP.endMS);
			if(aTex.tex == null) {
				aOpt.missing = true;
				return null;
			}
		}
		else if(com.cubaix.TDenlive.medias.ColorBck.Fill.isBitmap(aTexture)) {
			aTex.tex = Texture3D.get(aTexture);
			if(aTex.tex == null) {
				return null;
			}
		}
		else {
			aTex.procedural = aTexture;
			aTex.c1 = aO.color;
			aTex.c2 = aO.color2;
			return aTex;
		}
		if(Texture3D.isPicture(aTexture)) {
			aTex.picture = true;
			aTex.k = 100/Math.max(1, aP.scale);
			aTex.offU = aP.offU/100;
			aTex.offV = aP.offV/100;
			aTex.wrapU = aP.repU > 1;
			aTex.wrapV = aP.repV > 1;
			aTex.outside = aO.color & 0xFFFFFF;
		}
		return aTex;
	}

	/**
	 * Per triangle, the ratio width / height in the world of a texture cell (1 x 1 in aTu / aTv) over its part of the
	 * mesh: the triangles linked by shared vertices (a face of a cube, the side of a cylinder, a sphere), lengths of
	 * dP/du and dP/dv averaged over the part (weighted by the uv area), so that one image keeps one shape on a part
	 */
	static double[] cellRatios(Mesh3D aMesh, double[] aWp, float[] aTu, float[] aTv) {
		int aNT = aMesh.nbTris;
		int[] aParent = new int[aMesh.nbVerts];
		for(int v = 0;v < aParent.length;v++) {
			aParent[v] = v;
		}
		for(int t = 0;t < aNT;t++) {
			int aA = find(aParent, aMesh.tris[t*3]);
			for(int i = 1;i < 3;i++) {
				int aB = find(aParent, aMesh.tris[t*3+i]);
				if(aA != aB) {
					aParent[aB] = aA;
				}
			}
		}
		java.util.HashMap<Integer, double[]> aSums = new java.util.HashMap<Integer, double[]>();
		int[] aPart = new int[aNT];
		for(int t = 0;t < aNT;t++) {
			int aA = aMesh.tris[t*3], aB = aMesh.tris[t*3+1], aC = aMesh.tris[t*3+2];
			aPart[t] = find(aParent, aA);
			double aDu1 = aTu[aB]-aTu[aA], aDv1 = aTv[aB]-aTv[aA], aDu2 = aTu[aC]-aTu[aA], aDv2 = aTv[aC]-aTv[aA];
			double aDet = aDu1*aDv2 - aDu2*aDv1;
			if(Math.abs(aDet) < 1e-12) {
				continue;
			}
			double aLu = 0, aLv = 0;
			double[] aPu = new double[3], aPv = new double[3];
			for(int c = 0;c < 3;c++) {
				double aE1 = aWp[aB*3+c]-aWp[aA*3+c], aE2 = aWp[aC*3+c]-aWp[aA*3+c];
				aPu[c] = (aE1*aDv2 - aE2*aDv1)/aDet;
				aPv[c] = (aE2*aDu1 - aE1*aDu2)/aDet;
			}
			aLu = Math.sqrt(aPu[0]*aPu[0]+aPu[1]*aPu[1]+aPu[2]*aPu[2]);
			aLv = Math.sqrt(aPv[0]*aPv[0]+aPv[1]*aPv[1]+aPv[2]*aPv[2]);
			double aW = Math.abs(aDet);
			double[] aS = aSums.computeIfAbsent(aPart[t], x -> new double[2]);
			aS[0] += aLu*aW;
			aS[1] += aLv*aW;
		}
		double[] aR = new double[aNT];
		for(int t = 0;t < aNT;t++) {
			double[] aS = aSums.get(aPart[t]);
			aR[t] = aS != null && aS[0] > 0 && aS[1] > 0 ? aS[0]/aS[1] : 1;
		}
		return aR;
	}

	static int find(int[] aParent, int aV) {
		while(aParent[aV] != aV) {
			aParent[aV] = aParent[aParent[aV]];
			aV = aParent[aV];
		}
		return aV;
	}

	/**
	 * Mesh of an object of the library: per triangle its material (color x vertex color, texture tinted
	 * by them, alpha mask), lit like the other objects
	 */
	void drawOwnMaterials(Mesh3D aMesh, double[] aMat, double[] aPos, double[] aNM, Camera3D aCam, double[] aLight, double[] aHalf, int aId) {
		int aN = aMesh.nbVerts;
		double[] aCx = new double[aN], aCy = new double[aN], aCz = new double[aN];
		float[] aK = new float[aN], aS = new float[aN];
		for(int v = 0;v < aN;v++) {
			double[] aW = Math3D.add(Math3D.apply(aMat, new double[] {aMesh.pos[v*3], aMesh.pos[v*3+1], aMesh.pos[v*3+2]}), aPos);
			double[] aC = aCam.toCam(aW);
			aCx[v] = aC[0]; aCy[v] = aC[1]; aCz[v] = aC[2];
			double[] aNor = Math3D.normalize(Math3D.apply(aNM, new double[] {aMesh.nor[v*3], aMesh.nor[v*3+1], aMesh.nor[v*3+2]}));
			if(aMesh.twoSided && Math3D.dot(aNor, Math3D.sub(aCam.pos, aW)) < 0) {
				aNor = Math3D.mul(aNor, -1);
			}
			double aDif = Math.max(0, Math3D.dot(aNor, aLight));
			aK[v] = (float)(0.28 + 0.72*aDif);
			aS[v] = (float)(255*Math.pow(Math.max(0, Math3D.dot(aNor, aHalf)), 32)*0.35);
		}
		TexCtx[] aCtx = new TexCtx[aMesh.mats.length];
		for(int m = 0;m < aCtx.length;m++) {
			Mesh3D.Material aM = aMesh.mats[m];
			if(aM.tex != null) {
				aCtx[m] = new TexCtx();
				aCtx[m].tex = aM.tex;
				aCtx[m].alphaCut = aM.alphaCut;
				aCtx[m].tinted = true;
			}
		}
		double[][] aPoly = new double[8][];
		double[][] aV = new double[3][];
		for(int t = 0;t < aMesh.nbTris;t++) {
			int aMI = aMesh.triMat != null && t < aMesh.triMat.length ? aMesh.triMat[t] : 0;
			Mesh3D.Material aM = aMesh.mats.length > 0 ? aMesh.mats[Math.max(0, Math.min(aMesh.mats.length-1, aMI))] : new Mesh3D.Material();
			TexCtx aTex = aMesh.mats.length > 0 ? aCtx[Math.max(0, Math.min(aCtx.length-1, aMI))] : null;
			for(int k = 0;k < 3;k++) {
				int i = aMesh.tris[t*3+k];
				double aR = aM.r, aG = aM.g, aB = aM.b;
				if(aMesh.vcol != null) {
					aR *= aMesh.vcol[i*3]; aG *= aMesh.vcol[i*3+1]; aB *= aMesh.vcol[i*3+2];
				}
				if(aTex != null) {
					aV[k] = new double[] {aCx[i], aCy[i], aCz[i], aK[i], aS[i], 0, aMesh.uv[i*2], aMesh.uv[i*2+1], aR, aG, aB};
				}
				else {
					aV[k] = new double[] {aCx[i], aCy[i], aCz[i],
							Math.min(255, 255*aR*aK[i] + aS[i]), Math.min(255, 255*aG*aK[i] + aS[i]), Math.min(255, 255*aB*aK[i] + aS[i])};
				}
			}
			if(aV[0][2] > Camera3D.NEAR && aV[1][2] > Camera3D.NEAR && aV[2][2] > Camera3D.NEAR) {
				drawTri(aCam, aV[0], aV[1], aV[2], aId, aTex);
				continue;
			}
			int aNP = clipNear(new double[][] {aV[0], aV[1], aV[2]}, aPoly);
			for(int p = 1;p < aNP-1;p++) {
				drawTri(aCam, aPoly[0], aPoly[p], aPoly[p+1], aId, aTex);
			}
		}
	}

	static int clipNear(double[][] aIn, double[][] aOut) {
		int aN = 0;
		for(int i = 0;i < aIn.length;i++) {
			double[] aP = aIn[i];
			double[] aQ = aIn[(i+1)%aIn.length];
			boolean aPIn = aP[2] > Camera3D.NEAR;
			boolean aQIn = aQ[2] > Camera3D.NEAR;
			if(aPIn) {
				aOut[aN++] = aP;
			}
			if(aPIn != aQIn) {
				double aT = (Camera3D.NEAR*1.0001 - aP[2])/(aQ[2]-aP[2]);
				double[] aI = new double[aP.length];
				for(int c = 0;c < aP.length;c++) {
					aI[c] = aP[c] + (aQ[c]-aP[c])*aT;
				}
				aOut[aN++] = aI;
			}
		}
		return aN;
	}

	/**
	 * Triangle of camera points {x, y, z, r, g, b}, or textured {x, y, z, diffuse, highlight, -, u, v}
	 */
	void drawTri(Camera3D aCam, double[] aA, double[] aB, double[] aC, int aId, TexCtx aTex) {
		double aX0 = aCam.screenX(aA[0], aA[2]), aY0 = aCam.screenY(aA[1], aA[2]);
		double aX1 = aCam.screenX(aB[0], aB[2]), aY1 = aCam.screenY(aB[1], aB[2]);
		double aX2 = aCam.screenX(aC[0], aC[2]), aY2 = aCam.screenY(aC[1], aC[2]);
		double aArea = (aX1-aX0)*(aY2-aY0) - (aX2-aX0)*(aY1-aY0);
		if(Math.abs(aArea) < 1e-12) {
			return;
		}
		int aMinX = (int)Math.max(0, Math.floor(Math.min(aX0, Math.min(aX1, aX2))));
		int aMaxX = (int)Math.min(w-1, Math.ceil(Math.max(aX0, Math.max(aX1, aX2))));
		int aMinY = (int)Math.max(0, Math.floor(Math.min(aY0, Math.min(aY1, aY2))));
		int aMaxY = (int)Math.min(h-1, Math.ceil(Math.max(aY0, Math.max(aY1, aY2))));
		if(aMinX > aMaxX || aMinY > aMaxY) {
			return;
		}
		//Barycentric weights: w = a*x + b*y + c
		double aA0 = (aY1-aY2)/aArea, aB0 = (aX2-aX1)/aArea, aC0 = (aX1*aY2-aX2*aY1)/aArea;
		double aA1 = (aY2-aY0)/aArea, aB1 = (aX0-aX2)/aArea, aC1 = (aX2*aY0-aX0*aY2)/aArea;
		double aIz0 = 1/aA[2], aIz1 = 1/aB[2], aIz2 = 1/aC[2];
		//Texture: u/z and v/z are linear on the screen
		double aUz0 = 0, aUz1 = 0, aUz2 = 0, aVz0 = 0, aVz1 = 0, aVz2 = 0;
		if(aTex != null) {
			aUz0 = aA[6]*aIz0; aUz1 = aB[6]*aIz1; aUz2 = aC[6]*aIz2;
			aVz0 = aA[7]*aIz0; aVz1 = aB[7]*aIz1; aVz2 = aC[7]*aIz2;
			if(aTex.tex != null) {
				//Mipmap level: texels of the triangle per pixel of the screen
				double aTexArea = Math.abs((aB[6]-aA[6])*(aC[7]-aA[7]) - (aC[6]-aA[6])*(aB[7]-aA[7]))*aTex.tex.width()*aTex.tex.height()*aTex.areaK;
				aTex.level = aTex.tex.level(Math.sqrt(aTexArea/Math.abs(aArea)));
			}
		}
		final double aEps = -1e-7;
		for(int y = aMinY;y <= aMaxY;y++) {
			double aPy = y+0.5;
			int aRow = y*w;
			for(int x = aMinX;x <= aMaxX;x++) {
				double aPx = x+0.5;
				double aW0 = aA0*aPx + aB0*aPy + aC0;
				if(aW0 < aEps) {
					continue;
				}
				double aW1 = aA1*aPx + aB1*aPy + aC1;
				if(aW1 < aEps) {
					continue;
				}
				double aW2 = 1-aW0-aW1;
				if(aW2 < aEps) {
					continue;
				}
				float aIz = (float)(aW0*aIz0 + aW1*aIz1 + aW2*aIz2);
				int i = aRow+x;
				if(aIz <= zb[i]) {
					continue;
				}
				if(aTex != null) {
					double aZ = 1/(aW0*aIz0 + aW1*aIz1 + aW2*aIz2);
					int aT = aTex.sample((aW0*aUz0 + aW1*aUz1 + aW2*aUz2)*aZ, (aW0*aVz0 + aW1*aVz1 + aW2*aVz2)*aZ);
					if(aTex.alphaCut && (aT>>>24) < 128) {
						continue;
					}
					zb[i] = aIz;
					double aK = aW0*aA[3] + aW1*aB[3] + aW2*aC[3];
					double aS = aW0*aA[4] + aW1*aB[4] + aW2*aC[4];
					double aKr = aK, aKg = aK, aKb = aK;
					if(aTex.tinted) {
						//Color of the vertices, interpolated with the perspective
						aKr *= (aW0*aA[8]*aIz0 + aW1*aB[8]*aIz1 + aW2*aC[8]*aIz2)*aZ;
						aKg *= (aW0*aA[9]*aIz0 + aW1*aB[9]*aIz1 + aW2*aC[9]*aIz2)*aZ;
						aKb *= (aW0*aA[10]*aIz0 + aW1*aB[10]*aIz1 + aW2*aC[10]*aIz2)*aZ;
					}
					col[i] = 0xFF000000 | (clamp((int)(((aT>>16)&0xFF)*aKr + aS)) << 16)
							| (clamp((int)(((aT>>8)&0xFF)*aKg + aS)) << 8) | clamp((int)((aT&0xFF)*aKb + aS));
					if(idb != null) {
						idb[i] = aId;
					}
					continue;
				}
				zb[i] = aIz;
				int aR = (int)(aW0*aA[3] + aW1*aB[3] + aW2*aC[3]);
				int aG = (int)(aW0*aA[4] + aW1*aB[4] + aW2*aC[4]);
				int aBl = (int)(aW0*aA[5] + aW1*aB[5] + aW2*aC[5]);
				col[i] = 0xFF000000 | (clamp(aR) << 16) | (clamp(aG) << 8) | clamp(aBl);
				if(idb != null) {
					idb[i] = aId;
				}
			}
		}
	}

	static int clamp(int aV) {
		return aV < 0 ? 0 : (aV > 255 ? 255 : aV);
	}

	/**
	 * Floor grid (y = 0), hidden by the objects in front of it; X axis reddish, Z axis bluish
	 */
	void drawGrid(Camera3D aCam) {
		int aN = 10;
		double aStep = 0.5;
		for(int i = -aN;i <= aN;i++) {
			//Line along X at z = i (X axis for i = 0), line along Z at x = i (Z axis for i = 0)
			line(aCam, new double[] {-aN*aStep, 0, i*aStep}, new double[] {aN*aStep, 0, i*aStep}, i == 0 ? 0xD0E05050 : 0x60B0B0B0);
			line(aCam, new double[] {i*aStep, 0, -aN*aStep}, new double[] {i*aStep, 0, aN*aStep}, i == 0 ? 0xD05070F0 : 0x60B0B0B0);
		}
	}

	/**
	 * World segment, depth tested, blended
	 */
	void line(Camera3D aCam, double[] aP, double[] aQ, int aColor) {
		double[] aCp = aCam.toCam(aP);
		double[] aCq = aCam.toCam(aQ);
		if(aCp[2] <= Camera3D.NEAR && aCq[2] <= Camera3D.NEAR) {
			return;
		}
		if(aCp[2] <= Camera3D.NEAR || aCq[2] <= Camera3D.NEAR) {
			double aT = (Camera3D.NEAR*1.0001 - aCp[2])/(aCq[2]-aCp[2]);
			double[] aI = {aCp[0]+(aCq[0]-aCp[0])*aT, aCp[1]+(aCq[1]-aCp[1])*aT, aCp[2]+(aCq[2]-aCp[2])*aT};
			if(aCp[2] <= Camera3D.NEAR) {
				aCp = aI;
			}
			else {
				aCq = aI;
			}
		}
		double aX0 = aCam.screenX(aCp[0], aCp[2]), aY0 = aCam.screenY(aCp[1], aCp[2]);
		double aX1 = aCam.screenX(aCq[0], aCq[2]), aY1 = aCam.screenY(aCq[1], aCq[2]);
		double aIz0 = 1/aCp[2], aIz1 = 1/aCq[2];
		int aSteps = (int)Math.ceil(Math.max(Math.abs(aX1-aX0), Math.abs(aY1-aY0)));
		if(aSteps > 20000) {
			aSteps = 20000;
		}
		int aA = aColor >>> 24;
		int aLr = (aColor >> 16) & 0xFF, aLg = (aColor >> 8) & 0xFF, aLb = aColor & 0xFF;
		for(int s = 0;s <= aSteps;s++) {
			double aT = aSteps == 0 ? 0 : s/(double)aSteps;
			int x = (int)(aX0 + (aX1-aX0)*aT);
			int y = (int)(aY0 + (aY1-aY0)*aT);
			if(x < 0 || y < 0 || x >= w || y >= h) {
				continue;
			}
			int i = y*w+x;
			float aIz = (float)(aIz0 + (aIz1-aIz0)*aT);
			if(aIz < zb[i]*0.999f) {
				continue;
			}
			int aC = col[i];
			int aR = ((aC >> 16) & 0xFF)*(255-aA)/255 + aLr*aA/255;
			int aG = ((aC >> 8) & 0xFF)*(255-aA)/255 + aLg*aA/255;
			int aB = (aC & 0xFF)*(255-aA)/255 + aLb*aA/255;
			int aNA = Math.max(aC >>> 24, aA);
			col[i] = (aNA << 24) | (aR << 16) | (aG << 8) | aB;
		}
	}

	/**
	 * Average of the samples, alpha premultiplied
	 */
	void downsample(int[] aOut, int aW, int aH, int aSS) {
		if(aSS == 1) {
			System.arraycopy(col, 0, aOut, 0, aOut.length);
			return;
		}
		int aNb = aSS*aSS;
		for(int y = 0;y < aH;y++) {
			for(int x = 0;x < aW;x++) {
				long aSa = 0, aSr = 0, aSg = 0, aSb = 0;
				for(int sy = 0;sy < aSS;sy++) {
					int aRow = (y*aSS+sy)*w + x*aSS;
					for(int sx = 0;sx < aSS;sx++) {
						int aC = col[aRow+sx];
						int aA = aC >>> 24;
						aSa += aA;
						aSr += ((aC >> 16) & 0xFF)*aA;
						aSg += ((aC >> 8) & 0xFF)*aA;
						aSb += (aC & 0xFF)*aA;
					}
				}
				if(aSa == 0) {
					aOut[y*aW+x] = 0;
					continue;
				}
				int aA = (int)(aSa/aNb);
				aOut[y*aW+x] = (aA << 24) | ((int)(aSr/aSa) << 16) | ((int)(aSg/aSa) << 8) | (int)(aSb/aSa);
			}
		}
	}

	/**
	 * Selected objects: their border pixels in the outline color
	 */
	static void outline(int[] aOut, int[] aIds, int aW, int aH, Set<Integer> aSelected, int aColor) {
		for(int y = 0;y < aH;y++) {
			for(int x = 0;x < aW;x++) {
				int i = y*aW+x;
				int aId = aIds[i];
				if(aId == 0 || !aSelected.contains(aId-1)) {
					continue;
				}
				if(x == 0 || y == 0 || x == aW-1 || y == aH-1
						|| aIds[i-1] != aId || aIds[i+1] != aId || aIds[i-aW] != aId || aIds[i+aW] != aId) {
					aOut[i] = aColor;
				}
			}
		}
	}
}
