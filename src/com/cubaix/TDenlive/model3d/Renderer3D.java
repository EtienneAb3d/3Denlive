package com.cubaix.TDenlive.model3d;

import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.util.Set;

/**
 * Software renderer of a Model3D: z-buffer (1/z), Gouraud shading with a light tied to the center camera
 * (same lighting for both eyes), supersampling for the anti-aliasing, transparent or opaque background.
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
			drawObject(aM, aO, aCam, aLight, aHalf, aView, aSpin, aPivot, aSpinDeg != 0);
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

	void drawObject(Model3D aModel, Object3D aO, Camera3D aCam, double[] aLight, double[] aHalf, double[] aView, double[] aSpin, double[] aPivot, boolean aSpinning) {
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
		double[] aCx = new double[aN], aCy = new double[aN], aCz = new double[aN];
		float[] aCr = new float[aN], aCg = new float[aN], aCb = new float[aN];
		for(int v = 0;v < aN;v++) {
			double[] aL = {aMesh.pos[v*3], aMesh.pos[v*3+1], aMesh.pos[v*3+2]};
			double[] aW = Math3D.add(Math3D.apply(aMat, aL), aPos);
			double[] aC = aCam.toCam(aW);
			aCx[v] = aC[0]; aCy[v] = aC[1]; aCz[v] = aC[2];
			double[] aNor = Math3D.normalize(Math3D.apply(aNM, new double[] {aMesh.nor[v*3], aMesh.nor[v*3+1], aMesh.nor[v*3+2]}));
			if(aMesh.twoSided && Math3D.dot(aNor, Math3D.sub(aCam.pos, aW)) < 0) {
				aNor = Math3D.mul(aNor, -1);
			}
			double aDif = Math.max(0, Math3D.dot(aNor, aLight));
			double aSpec = Math.pow(Math.max(0, Math3D.dot(aNor, aHalf)), 32)*0.35;
			double aK = 0.28 + 0.72*aDif;
			aCr[v] = (float)Math.min(255, aBr*aK + 255*aSpec);
			aCg[v] = (float)Math.min(255, aBg*aK + 255*aSpec);
			aCb[v] = (float)Math.min(255, aBb*aK + 255*aSpec);
		}
		int aId = aO.id+1;
		double[][] aPoly = new double[8][];
		for(int t = 0;t < aMesh.nbTris;t++) {
			int aA = aMesh.tris[t*3], aB = aMesh.tris[t*3+1], aC = aMesh.tris[t*3+2];
			double[] aVa = {aCx[aA], aCy[aA], aCz[aA], aCr[aA], aCg[aA], aCb[aA]};
			double[] aVb = {aCx[aB], aCy[aB], aCz[aB], aCr[aB], aCg[aB], aCb[aB]};
			double[] aVc = {aCx[aC], aCy[aC], aCz[aC], aCr[aC], aCg[aC], aCb[aC]};
			if(aVa[2] > Camera3D.NEAR && aVb[2] > Camera3D.NEAR && aVc[2] > Camera3D.NEAR) {
				drawTri(aCam, aVa, aVb, aVc, aId);
				continue;
			}
			//Clipped by the near plane
			int aNP = clipNear(new double[][] {aVa, aVb, aVc}, aPoly);
			for(int p = 1;p < aNP-1;p++) {
				drawTri(aCam, aPoly[0], aPoly[p], aPoly[p+1], aId);
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
	 * Triangle of camera points {x, y, z, r, g, b}
	 */
	void drawTri(Camera3D aCam, double[] aA, double[] aB, double[] aC, int aId) {
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
