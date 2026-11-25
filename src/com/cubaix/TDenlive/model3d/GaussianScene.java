package com.cubaix.TDenlive.model3d;

import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Vector;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Scene of 3D Gaussians (3D Gaussian Splatting, Kerbl et al. 2023) read from a binary PLY of that format, such as
 * those made from one photo by Flash3D (ai.SceneGenerator): position, scale (log) and rotation (w x y z), opacity
 * (before the sigmoid), color as spherical harmonics (f_dc, f_rest of degree 1 when present).
 * Coordinates: those of the file, for Flash3D the frame of the camera of the photo (x right, y down, z forward,
 * metres), whose intrinsics are in the comment "3Denlive camera fx fy cx cy width height".
 * render(): software splatting (EWA): each Gaussian projected to a 2D ellipse (Jacobian of the perspective, low pass
 * of 0.3 pixel at the size of the scene, see lowPassFor()), sorted by depth, blended front to back per pixel (stopped when opaque), in bands of lines on all
 * the cores. Off-axis shift of the principal point for parallel stereo cameras (zero parallax at a distance).
 */
public class GaussianScene {
	static final double SH_C0 = 0.28209479177387814;
	static final double SH_C1 = 0.4886025119029199;

	public int n;
	//Position (3 per Gaussian)
	public float[] pos;
	//Covariance xx xy xz yy yz zz (6 per Gaussian)
	float[] cov;
	//Base color 0.5 + C0 f_dc (3 per Gaussian), degree 1 coefficients (9: per channel, 3 coefficients) or null
	float[] base;
	float[] sh1;
	//Opacity 0..1
	float[] opacity;
	//Camera of the photo (fx <= 0: unknown, a field of view of 60 degrees then)
	public double fx = -1, fy = -1, cx = -1, cy = -1;
	public int width = 0, height = 0;
	//File of the photo named in the comments (may be null)
	public String source = null;
	//Its file, size and date when read by cached() (key of the caches of the views filled, model3d.SmartFill), else null
	public String fileKey = null;
	//Depth (z) of the Gaussians: 5 %, median, 95 %
	public double nearZ = 1, medianZ = 2, farZ = 10;

	/**
	 * Read a binary little endian PLY of float properties
	 */
	public static GaussianScene load(File aFile) throws IOException {
		byte[] aAll = Files.readAllBytes(aFile.toPath());
		if(aAll.length > COMPACT_MAGIC.length() && new String(aAll, 0, COMPACT_MAGIC.length(), "US-ASCII").equals(COMPACT_MAGIC)) {
			return loadCompact(aFile, aAll);
		}
		int aEnd = indexOf(aAll, "end_header\n".getBytes("US-ASCII"));
		if(aEnd < 0 || !new String(aAll, 0, Math.min(3, aAll.length), "US-ASCII").equals("ply")) {
			throw new IOException("Not a PLY file: "+aFile);
		}
		String aHeader = new String(aAll, 0, aEnd, "US-ASCII");
		int aData = aEnd+"end_header\n".length();
		GaussianScene aS = new GaussianScene();
		Vector<String> aProps = new Vector<String>();
		int aCount = -1;
		boolean aInVertex = false;
		for(String aL : aHeader.split("\\r?\\n")) {
			String[] aT = aL.trim().split("\\s+");
			if(aT.length == 0) {
				continue;
			}
			if(aT[0].equals("format") && (aT.length < 2 || !aT[1].equals("binary_little_endian"))) {
				throw new IOException("Only binary little endian PLY files are read: "+aFile);
			}
			if(aT[0].equals("comment") && aT.length >= 9 && aT[1].equals("3Denlive") && aT[2].equals("camera")) {
				aS.fx = Double.parseDouble(aT[3]);
				aS.fy = Double.parseDouble(aT[4]);
				aS.cx = Double.parseDouble(aT[5]);
				aS.cy = Double.parseDouble(aT[6]);
				aS.width = Integer.parseInt(aT[7]);
				aS.height = Integer.parseInt(aT[8]);
			}
			else if(aT[0].equals("comment") && aL.contains("3Denlive ") && aL.contains(" scene of ")) {
				aS.source = aL.substring(aL.indexOf("scene of ")+9).trim();
			}
			else if(aT[0].equals("element")) {
				aInVertex = aT.length >= 3 && aT[1].equals("vertex");
				if(aInVertex) {
					aCount = Integer.parseInt(aT[2]);
				}
				else if(aCount >= 0) {
					//Another element after the vertices: ignored (they come first)
					break;
				}
			}
			else if(aT[0].equals("property") && aInVertex) {
				if(aT.length != 3 || !(aT[1].equals("float") || aT[1].equals("float32"))) {
					throw new IOException("Only float properties are read: "+aL);
				}
				aProps.add(aT[2]);
			}
		}
		HashMap<String,Integer> aIdx = new HashMap<String,Integer>();
		for(int p = 0;p < aProps.size();p++) {
			aIdx.put(aProps.get(p), p);
		}
		for(String aNeeded : new String[] {"x", "y", "z", "f_dc_0", "f_dc_1", "f_dc_2", "opacity", "scale_0", "scale_1", "scale_2", "rot_0", "rot_1", "rot_2", "rot_3"}) {
			if(!aIdx.containsKey(aNeeded)) {
				throw new IOException("Not a 3D Gaussian Splatting PLY (no "+aNeeded+"): "+aFile);
			}
		}
		int aStride = aProps.size();
		if(aCount < 0 || aData+(long)aCount*aStride*4 > aAll.length) {
			throw new IOException("Truncated PLY file: "+aFile);
		}
		ByteBuffer aBB = ByteBuffer.wrap(aAll, aData, aAll.length-aData).order(ByteOrder.LITTLE_ENDIAN);
		java.nio.FloatBuffer aFB = aBB.slice().order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer();
		//f_rest of degree 1: 3 coefficients per channel (files of degree 3: the first 3 of the 15 of each channel)
		int aRestPerChannel = 0;
		while(aIdx.containsKey("f_rest_"+aRestPerChannel)) {
			aRestPerChannel++;
		}
		aRestPerChannel /= 3;
		boolean aSh1 = aRestPerChannel >= 3;
		int n = aCount;
		aS.n = n;
		aS.pos = new float[3*n];
		aS.cov = new float[6*n];
		aS.base = new float[3*n];
		aS.sh1 = aSh1 ? new float[9*n] : null;
		aS.opacity = new float[n];
		int iX = aIdx.get("x"), iY = aIdx.get("y"), iZ = aIdx.get("z");
		int iD = aIdx.get("f_dc_0"), iO = aIdx.get("opacity"), iS = aIdx.get("scale_0"), iR = aIdx.get("rot_0");
		int iRest = aSh1 ? aIdx.get("f_rest_0") : -1;
		float[] aRow = new float[aStride];
		for(int i = 0;i < n;i++) {
			aFB.get(aRow);
			aS.pos[3*i] = aRow[iX];
			aS.pos[3*i+1] = aRow[iY];
			aS.pos[3*i+2] = aRow[iZ];
			for(int c = 0;c < 3;c++) {
				aS.base[3*i+c] = (float)(0.5+SH_C0*aRow[iD+c]);
				if(aSh1) {
					for(int k = 0;k < 3;k++) {
						aS.sh1[9*i+3*c+k] = (float)(SH_C1*aRow[iRest+c*aRestPerChannel+k]);
					}
				}
			}
			aS.opacity[i] = (float)(1/(1+Math.exp(-aRow[iO])));
			double aSx = Math.exp(aRow[iS]), aSy = Math.exp(aRow[iS+1]), aSz = Math.exp(aRow[iS+2]);
			aS.setCov(i, aSx, aSy, aSz, aRow[iR], aRow[iR+1], aRow[iR+2], aRow[iR+3]);
		}
		aS.depthStats();
		return aS;
	}

	/**
	 * Distance from aPos (frame of the photo) to the background seen along aFwd (unit vector): 90 % of the distances
	 * along aFwd of the Gaussians in a cone of 30 degrees around it (about 100000 sampled), -1 when none is in front
	 */
	public double backgroundDistance(double[] aPos,double[] aFwd) {
		int aStride = Math.max(1, n/100000);
		float[] aD = new float[n/aStride+1];
		int aNb = 0;
		final double aCos2 = 0.75;//cos(30 degrees)^2
		for(int i = 0;i < n;i += aStride) {
			double dx = pos[3*i]-aPos[0], dy = pos[3*i+1]-aPos[1], dz = pos[3*i+2]-aPos[2];
			double f = dx*aFwd[0]+dy*aFwd[1]+dz*aFwd[2];
			if(f <= 0 || f*f < aCos2*(dx*dx+dy*dy+dz*dz)) {
				continue;
			}
			aD[aNb++] = (float)f;
		}
		if(aNb < 16) {
			return -1;
		}
		Arrays.sort(aD, 0, aNb);
		return aD[Math.min(aNb-1, (int)(aNb*0.9))];
	}

	void depthStats() {
		float[] aZ = new float[n];
		for(int i = 0;i < n;i++) {
			aZ[i] = pos[3*i+2];
		}
		Arrays.sort(aZ);
		if(n > 0) {
			nearZ = aZ[(int)(n*0.05)];
			medianZ = aZ[n/2];
			farZ = aZ[Math.min(n-1, (int)(n*0.95))];
			//Range of the depths, without the 1 % at each end (a few Gaussians far away would set the scale)
			depthLow = Math.max(1e-3, aZ[(int)(n*0.01)]);
			depthHigh = Math.max(depthLow+1e-3, aZ[Math.min(n-1, (int)(n*0.99))]);
			depthRange = depthHigh-depthLow;
		}
	}

	//Range of the depths of the scene (1 % to 99 %): the scale of the gray of the depths (depthGray())
	public double depthRange = 1;
	public double depthLow = 1, depthHigh = 2;

	/**
	 * Perspective effect of a depth: 1 at the nearest depth of the scene, 0 at the deepest, following 1 / depth in
	 * between (the parallax a move of the cameras gives)
	 */
	public float perspective(float aZ) {
		double aInvN = 1/depthLow, aInvF = 1/depthHigh;
		double w = (1/Math.max(1e-3, aZ)-aInvF)/(aInvN-aInvF);
		return (float)Math.max(0, Math.min(1, w));
	}

	/**
	 * Gray of a depth (0..255): white for the nearest depth of the scene, black for the deepest, following 1 / depth
	 * (perspective(), as a depth map)
	 */
	public int depthGray(float aZ) {
		return clamp255(perspective(aZ)*255);
	}

	/**
	 * Covariance of the Gaussian i from its scales and its rotation (quaternion w x y z, normalized here)
	 */
	void setCov(int i,double aSx,double aSy,double aSz,double w,double x,double y,double z) {
		double aN = Math.sqrt(w*w+x*x+y*y+z*z);
		if(aN < 1e-12) {
			w = 1;
			x = y = z = 0;
		}
		else {
			w /= aN; x /= aN; y /= aN; z /= aN;
		}
		//R columns scaled: M = R S, covariance M M^T
		double m00 = (1-2*(y*y+z*z))*aSx, m01 = 2*(x*y-w*z)*aSy, m02 = 2*(x*z+w*y)*aSz;
		double m10 = 2*(x*y+w*z)*aSx, m11 = (1-2*(x*x+z*z))*aSy, m12 = 2*(y*z-w*x)*aSz;
		double m20 = 2*(x*z-w*y)*aSx, m21 = 2*(y*z+w*x)*aSy, m22 = (1-2*(x*x+y*y))*aSz;
		cov[6*i] = (float)(m00*m00+m01*m01+m02*m02);
		cov[6*i+1] = (float)(m00*m10+m01*m11+m02*m12);
		cov[6*i+2] = (float)(m00*m20+m01*m21+m02*m22);
		cov[6*i+3] = (float)(m10*m10+m11*m11+m12*m12);
		cov[6*i+4] = (float)(m10*m20+m11*m21+m12*m22);
		cov[6*i+5] = (float)(m20*m20+m21*m21+m22*m22);
	}

	//Compact format of the scenes of the frames of a video (flash3d_scene.py --compact): magic, then the line
	//"n fx fy cx cy width height", then little endian: positions float16 x 3n, log scales float16 x 3n,
	//rotations int8 x 4n (w x y z times 127), colors and opacity uint8 x 4n (r g b alpha); about 20 bytes a Gaussian
	public static final String COMPACT_MAGIC = "3DGSC1";
	public static final String COMPACT_EXT = ".3gs";

	static GaussianScene loadCompact(File aFile,byte[] aAll) throws IOException {
		int aL1 = indexOf(aAll, "\n".getBytes("US-ASCII"));
		int aL2 = aL1 < 0 ? -1 : aL1+1+indexOf(Arrays.copyOfRange(aAll, aL1+1, Math.min(aAll.length, aL1+200)), "\n".getBytes("US-ASCII"));
		if(aL1 < 0 || aL2 <= aL1) {
			throw new IOException("Bad compact scene: "+aFile);
		}
		String[] aT = new String(aAll, aL1+1, aL2-aL1-1, "US-ASCII").trim().split("\\s+");
		GaussianScene aS = new GaussianScene();
		int n = Integer.parseInt(aT[0]);
		aS.fx = Double.parseDouble(aT[1]);
		aS.fy = Double.parseDouble(aT[2]);
		aS.cx = Double.parseDouble(aT[3]);
		aS.cy = Double.parseDouble(aT[4]);
		aS.width = Integer.parseInt(aT[5]);
		aS.height = Integer.parseInt(aT[6]);
		int aData = aL2+1;
		if(aData+(long)n*20 > aAll.length) {
			throw new IOException("Truncated compact scene: "+aFile);
		}
		ByteBuffer aBB = ByteBuffer.wrap(aAll).order(ByteOrder.LITTLE_ENDIAN);
		aS.n = n;
		aS.pos = new float[3*n];
		aS.cov = new float[6*n];
		aS.base = new float[3*n];
		aS.sh1 = null;
		aS.opacity = new float[n];
		int oPos = aData, oScale = oPos+6*n, oRot = oScale+6*n, oCol = oRot+4*n;
		for(int i = 0;i < 3*n;i++) {
			aS.pos[i] = Float.float16ToFloat(aBB.getShort(oPos+2*i));
		}
		for(int i = 0;i < n;i++) {
			double aSx = Math.exp(Float.float16ToFloat(aBB.getShort(oScale+6*i)));
			double aSy = Math.exp(Float.float16ToFloat(aBB.getShort(oScale+6*i+2)));
			double aSz = Math.exp(Float.float16ToFloat(aBB.getShort(oScale+6*i+4)));
			aS.setCov(i, aSx, aSy, aSz, aAll[oRot+4*i], aAll[oRot+4*i+1], aAll[oRot+4*i+2], aAll[oRot+4*i+3]);
			for(int c = 0;c < 3;c++) {
				aS.base[3*i+c] = (aAll[oCol+4*i+c]&0xFF)/255f;
			}
			aS.opacity[i] = (aAll[oCol+4*i+3]&0xFF)/255f;
		}
		aS.depthStats();
		return aS;
	}

	//Scenes read last, by file (the frames of a video played again, the same scene in several clips)
	static final java.util.LinkedHashMap<String,java.lang.ref.SoftReference<GaussianScene>> CACHE = new java.util.LinkedHashMap<String,java.lang.ref.SoftReference<GaussianScene>>(16, 0.75f, true);
	static final int CACHE_MAX = 8;

	/**
	 * The scene of a file (PLY or compact), read once and kept while memory allows; null if it can't be read
	 */
	public static GaussianScene cached(File aFile) {
		String aKey = aFile.getAbsolutePath()+"|"+aFile.length()+"|"+aFile.lastModified();
		synchronized(CACHE) {
			java.lang.ref.SoftReference<GaussianScene> aR = CACHE.get(aKey);
			GaussianScene aS = aR == null ? null : aR.get();
			if(aS != null) {
				return aS;
			}
		}
		try {
			GaussianScene aS = load(aFile);
			aS.fileKey = aKey;
			synchronized(CACHE) {
				CACHE.put(aKey, new java.lang.ref.SoftReference<GaussianScene>(aS));
				while(CACHE.size() > CACHE_MAX) {
					CACHE.remove(CACHE.keySet().iterator().next());
				}
			}
			return aS;
		}
		catch(Throwable t) {
			t.printStackTrace(System.err);
			return null;
		}
	}

	static int indexOf(byte[] aB,byte[] aWhat) {
		int aMax = Math.min(aB.length-aWhat.length, 1<<20);
		for(int i = 0;i <= aMax;i++) {
			boolean aOk = true;
			for(int j = 0;j < aWhat.length && aOk;j++) {
				aOk = aB[i+j] == aWhat[j];
			}
			if(aOk) {
				return i;
			}
		}
		return -1;
	}

	/**
	 * The scene has the grid of the pixels of its photo: its visible Gaussians on them, row by row (a Flash3D scene,
	 * width x height more behind them; a Depth Pro scene, only them)
	 */
	public boolean hasGrid() {
		return width > 1 && height > 1 && (n == width*height || n == 2*width*height);
	}

	/**
	 * Mesh of the visible Gaussians of a Flash3D scene (two triangles per square of 4 neighbor pixels of the photo,
	 * their centers as vertices), into aOut (ARGB, the background of the view where no triangle): z-buffer (1/z),
	 * colors of the vertices interpolated with the perspective. Seen from aside, the triangles between the foreground
	 * and the background stretch over what the photo did not see and fill it with the colors of both sides.
	 * Bands of lines on all the cores (each band goes through all the triangles: they are small).
	 */
	void renderMesh(View aV,int aW,int aH,int[] aOut) {
		int gw = width, gh = height, nv = gw*gh;
		float[] sx = new float[nv], sy = new float[nv], iz = new float[nv];
		double[] r = aV.right, d = aV.down, f = aV.fwd;
		double aF = aV.focal;
		//Each vertex on its own: by ranges on all the cores
		com.cubaix.TDenlive.utils.Par.rows(nv, (k0, k1) -> {
			for(int k = k0;k < k1;k++) {
				double dx = pos[3*k]-aV.eye[0], dy = pos[3*k+1]-aV.eye[1], dz = pos[3*k+2]-aV.eye[2];
				double Z = f[0]*dx+f[1]*dy+f[2]*dz;
				if(Z < aV.near) {
					//Behind the eye
					iz[k] = -1;
					continue;
				}
				double X = r[0]*dx+r[1]*dy+r[2]*dz;
				double Y = d[0]*dx+d[1]*dy+d[2]*dz;
				sx[k] = (float)(aF*X/Z+aV.cx);
				sy[k] = (float)(aF*Y/Z+aV.cy);
				iz[k] = (float)(1/Z);
			}
		});
		//Thresholding: the triangles seen edge on from the camera of the photo (|cos| of their normal with the direction
		//of the depth below aV.cosCut) are holes; cosines made once per scene (triCos())
		final float[] aCos = aV.cosCut > 0 ? triCos() : null;
		final float aCosCut = (float)aV.cosCut;
		int aThreads = Math.max(1, Runtime.getRuntime().availableProcessors());
		int aBandH = Math.max(4, aH/(aThreads*4));
		int aBands = (aH+aBandH-1)/aBandH;
		//The triangles of each band, in the order of the grid (the same drawing order as going through all of them: the
		//same image), so that a band only goes through its own ones
		final int[][] aBandTris = bandTriangles(gw, gh, sx, sy, iz, aH, aBandH, aBands, aCos, aCosCut);
		AtomicInteger aNextBand = new AtomicInteger(0);
		Thread[] aTh = new Thread[aThreads];
		for(int t = 0;t < aThreads;t++) {
			aTh[t] = new Thread(() -> {
				float[] aZB = new float[aW*aBandH];
				int b;
				while((b = aNextBand.getAndIncrement()) < aBands) {
					int y0 = b*aBandH, y1 = Math.min(aH, y0+aBandH);
					Arrays.fill(aZB, 0, (y1-y0)*aW, 0f);
					//No triangle: the background of the view, transparent (a hole of the mesh)
					Arrays.fill(aOut, y0*aW, y1*aW, aV.bck&0x00FFFFFF);
					for(int aT : aBandTris[b]) {
						int k = aT>>1;
						if((aT&1) == 0) {
							triangle(k, k+1, k+gw, sx, sy, iz, aW, y0, y1, aZB, aOut, aV.depthView);
						}
						else {
							triangle(k+1, k+gw+1, k+gw, sx, sy, iz, aW, y0, y1, aZB, aOut, aV.depthView);
						}
					}
					if(aV.depthOut != null) {
						System.arraycopy(aZB, 0, aV.depthOut, y0*aW, (y1-y0)*aW);
					}
				}
			});
			aTh[t].start();
		}
		join(aTh);
	}

	/**
	 * Triangles (2k: (k, k+1, k+gw), 2k+1: (k+1, k+gw+1, k+gw)) of each band of aBandH lines that triangle() may draw
	 * in it: in front of the eye, not cut by aCosCut, and lines [ceil(minY-0.5), floor(maxY-0.5)] crossing the band
	 * (the same test as triangle(); the others are left out by it anyway). In the order of the grid.
	 */
	static int[][] bandTriangles(int gw,int gh,float[] sx,float[] sy,float[] iz,int aH,int aBandH,int aBands,float[] aCos,float aCosCut) {
		//Chunks of rows of the grid on all the cores, each with its lists per band; joined in the order of the chunks
		//(the order of the grid)
		int aRows = Math.max(0, gh-1);
		int aNC = Math.max(1, com.cubaix.TDenlive.utils.Par.bandsFor(aRows));
		int[][][] aParts = new int[aNC][][];
		com.cubaix.TDenlive.utils.Par.each(aNC, c -> {
			int aGy0 = c*aRows/aNC, aGy1 = (c+1)*aRows/aNC;
			int aNT = 2*(aGy1-aGy0)*gw;
			//First and last band of each triangle of the chunk (-1: none)
			int[] aB0 = new int[aNT], aB1 = new int[aNT];
			int[] aCount = new int[aBands];
			for(int gy = aGy0;gy < aGy1;gy++) {
				for(int gx = 0;gx < gw-1;gx++) {
					int k = gy*gw+gx;
					for(int h = 0;h < 2;h++) {
						int t = 2*k+h, u = t-2*aGy0*gw;
						aB0[u] = -1;
						if(aCos != null && aCos[t] < aCosCut) {
							continue;
						}
						int a = h == 0 ? k : k+1, b = h == 0 ? k+1 : k+gw+1, cc = k+gw;
						if(iz[a] <= 0 || iz[b] <= 0 || iz[cc] <= 0) {
							continue;
						}
						float aMinY = Math.min(sy[a], Math.min(sy[b], sy[cc])), aMaxY = Math.max(sy[a], Math.max(sy[b], sy[cc]));
						int ya = Math.max(0, (int)Math.ceil(aMinY-0.5f)), yb = Math.min(aH-1, (int)Math.floor(aMaxY-0.5f));
						if(ya > yb) {
							continue;
						}
						aB0[u] = ya/aBandH;
						aB1[u] = yb/aBandH;
						for(int bd = aB0[u];bd <= aB1[u];bd++) {
							aCount[bd]++;
						}
					}
				}
			}
			int[][] aLists = new int[aBands][];
			for(int bd = 0;bd < aBands;bd++) {
				aLists[bd] = new int[aCount[bd]];
				aCount[bd] = 0;
			}
			for(int gy = aGy0;gy < aGy1;gy++) {
				for(int gx = 0;gx < gw-1;gx++) {
					int k = gy*gw+gx;
					for(int h = 0;h < 2;h++) {
						int t = 2*k+h, u = t-2*aGy0*gw;
						if(aB0[u] < 0) {
							continue;
						}
						for(int bd = aB0[u];bd <= aB1[u];bd++) {
							aLists[bd][aCount[bd]++] = t;
						}
					}
				}
			}
			aParts[c] = aLists;
		});
		int[][] aLists = new int[aBands][];
		com.cubaix.TDenlive.utils.Par.each(aBands, bd -> {
			int aN = 0;
			for(int c = 0;c < aNC;c++) {
				aN += aParts[c][bd].length;
			}
			int[] aL = new int[aN];
			int aAt = 0;
			for(int c = 0;c < aNC;c++) {
				System.arraycopy(aParts[c][bd], 0, aL, aAt, aParts[c][bd].length);
				aAt += aParts[c][bd].length;
			}
			aLists[bd] = aL;
		});
		return aLists;
	}

	//|cos| of the normal of each triangle of the mesh with the direction of the depth (from the camera of the photo to
	//its center): 2 per square of the grid, made once
	float[] triCos = null;

	/**
	 * |cos| of the angle between the normal of each triangle of the mesh (in the frame of the photo) and the ray of the
	 * camera of the photo to its center: 1 for a triangle facing the camera, 0 for a triangle seen edge on (the slopes
	 * of depth the depth estimate puts at the edges of the objects); index 2k: (k, k+1, k+w), 2k+1: (k+1, k+w+1, k+w)
	 */
	synchronized float[] triCos() {
		if(triCos != null) {
			return triCos;
		}
		int gw = width, gh = height;
		float[] aC = new float[2*gw*gh];
		for(int gy = 0;gy < gh-1;gy++) {
			for(int gx = 0;gx < gw-1;gx++) {
				int k = gy*gw+gx;
				aC[2*k] = cosOf(k, k+1, k+gw);
				aC[2*k+1] = cosOf(k+1, k+gw+1, k+gw);
			}
		}
		triCos = aC;
		return aC;
	}

	float cosOf(int a,int b,int c) {
		double ax = pos[3*a], ay = pos[3*a+1], az = pos[3*a+2];
		double ux = pos[3*b]-ax, uy = pos[3*b+1]-ay, uz = pos[3*b+2]-az;
		double vx = pos[3*c]-ax, vy = pos[3*c+1]-ay, vz = pos[3*c+2]-az;
		double nx = uy*vz-uz*vy, ny = uz*vx-ux*vz, nz = ux*vy-uy*vx;
		double mx = (ax+pos[3*b]+pos[3*c])/3, my = (ay+pos[3*b+1]+pos[3*c+1])/3, mz = (az+pos[3*b+2]+pos[3*c+2])/3;
		double aN = Math.sqrt(nx*nx+ny*ny+nz*nz)*Math.sqrt(mx*mx+my*my+mz*mz);
		return aN < 1e-20 ? 1 : (float)Math.abs((nx*mx+ny*my+nz*mz)/aN);
	}

	//Depth ratio of the vertices of a triangle beyond which it crosses a depth edge
	static final float EDGE = 1.1f;

	/**
	 * Image of the mesh of the visible Gaussians alone (View.meshFill, thresholded at View.cosCut), supersampled twice up to
	 * the full HD (smooth edges): the Gaussians are not drawn over it (their splats, small and partly transparent,
	 * showed as a texture of points when seen closer). aTrans: the part of each pixel no triangle covers.
	 */
	void renderMeshImage(View aV,int aW,int aH,int[] aPix,float[] aTrans) {
		int ss = aW*(long)aH <= 2200000 ? 2 : 1;
		if(ss == 1) {
			renderMesh(aV, aW, aH, aPix);
			for(int p = 0;p < aW*aH;p++) {
				if(aV.depthOut != null && (aPix[p]>>>24) == 0) {
					aV.depthOut[p] = 0;
				}
				boolean aIn = (aPix[p]>>>24) != 0;
				aPix[p] |= 0xFF000000;
				if(aTrans != null) {
					aTrans[p] = aIn ? 0 : 1;
				}
			}
			return;
		}
		View aV2 = new View();
		aV2.eye = aV.eye;
		aV2.right = aV.right;
		aV2.down = aV.down;
		aV2.fwd = aV.fwd;
		aV2.focal = aV.focal*ss;
		aV2.cx = aV.cx*ss;
		aV2.cy = aV.cy*ss;
		aV2.near = aV.near;
		aV2.bck = aV.bck;
		aV2.cosCut = aV.cosCut;
		aV2.depthView = aV.depthView;
		int aW2 = aW*ss, aH2 = aH*ss;
		int[] aBig = new int[aW2*aH2];
		if(aV.depthOut != null) {
			aV2.depthOut = new float[aW2*aH2];
		}
		renderMesh(aV2, aW2, aH2, aBig);
		int aBR = (aV.bck>>16)&0xFF, aBG = (aV.bck>>8)&0xFF, aBB = aV.bck&0xFF;
		float aK = 1f/(ss*ss);
		//Each pixel on its own: bands of lines on all the cores
		com.cubaix.TDenlive.utils.Par.rows(aH, (aY0, aY1) -> {
		for(int y = aY0;y < aY1;y++) {
			for(int x = 0;x < aW;x++) {
				int r = 0, g = 0, b = 0, aN = 0;
				for(int dy = 0;dy < ss;dy++) {
					int aRow = (y*ss+dy)*aW2+x*ss;
					for(int dx = 0;dx < ss;dx++) {
						int c = aBig[aRow+dx];
						if((c>>>24) != 0) {
							r += (c>>16)&0xFF;
							g += (c>>8)&0xFF;
							b += c&0xFF;
							aN++;
						}
					}
				}
				if(aV.depthOut != null) {
					//The nearest of the covered sub pixels
					float aZ = 0;
					for(int dy = 0;dy < ss;dy++) {
						int aRow = (y*ss+dy)*aW2+x*ss;
						for(int dx = 0;dx < ss;dx++) {
							if((aBig[aRow+dx]>>>24) != 0) {
								aZ = Math.max(aZ, aV2.depthOut[aRow+dx]);
							}
						}
					}
					aV.depthOut[y*aW+x] = aZ;
				}
				float t = 1-aN*aK;
				//Covered part with its colors, the rest with the background (premultiplied over it)
				int rr = clamp255(r*aK+t*aBR), gg = clamp255(g*aK+t*aBG), bl = clamp255(b*aK+t*aBB);
				aPix[y*aW+x] = 0xFF000000|(rr<<16)|(gg<<8)|bl;
				if(aTrans != null) {
					aTrans[y*aW+x] = t;
				}
			}
		}
		});
	}

	/**
	 * One triangle of the mesh in the band [y0, y1[ (pixel centers inside it)
	 */
	void triangle(int a,int b,int c,float[] sx,float[] sy,float[] iz,int aW,int y0,int y1,float[] aZB,int[] aOut,boolean aDepth) {
		float za = iz[a], zb = iz[b], zc = iz[c];
		if(za <= 0 || zb <= 0 || zc <= 0) {
			return;
		}
		float da0 = pos[3*a+2], db0 = pos[3*b+2], dc0 = pos[3*c+2];
		float ax = sx[a], ay = sy[a], bx = sx[b], by = sy[b], cx = sx[c], cy = sy[c];
		float aMinY = Math.min(ay, Math.min(by, cy)), aMaxY = Math.max(ay, Math.max(by, cy));
		int ya = Math.max(y0, (int)Math.ceil(aMinY-0.5f)), yb = Math.min(y1-1, (int)Math.floor(aMaxY-0.5f));
		if(ya > yb) {
			return;
		}
		float aMinX = Math.min(ax, Math.min(bx, cx)), aMaxX = Math.max(ax, Math.max(bx, cx));
		int xa = Math.max(0, (int)Math.ceil(aMinX-0.5f)), xb = Math.min(aW-1, (int)Math.floor(aMaxX-0.5f));
		if(xa > xb) {
			return;
		}
		float aArea = (bx-ax)*(cy-ay)-(by-ay)*(cx-ax);
		if(Math.abs(aArea) < 1e-9f) {
			return;
		}
		float aInv = 1/aArea;
		//Colors of the vertices: across a depth edge (the depths of the photo differ by more than EDGE), the triangle
		//covers what the foreground hid: the colors of its far vertices only (the background continued)
		int ca = a, cb = b, cc = c;
		float da = pos[3*a+2], db = pos[3*b+2], dc = pos[3*c+2];
		float aNear = Math.min(da, Math.min(db, dc)), aFar = Math.max(da, Math.max(db, dc));
		if(aNear > 0 && aFar > aNear*EDGE) {
			float aMid = (float)Math.sqrt(aNear*aFar);
			int aFarV = da == aFar ? a : (db == aFar ? b : c);
			if(da < aMid) {
				ca = aFarV;
			}
			if(db < aMid) {
				cb = aFarV;
			}
			if(dc < aMid) {
				cc = aFarV;
			}
		}
		float ra = base[3*ca], ga = base[3*ca+1], ba = base[3*ca+2];
		float rb = base[3*cb], gb = base[3*cb+1], bb = base[3*cb+2];
		float rc = base[3*cc], gc = base[3*cc+1], bc = base[3*cc+2];
		for(int y = ya;y <= yb;y++) {
			float py = y+0.5f;
			int aRow = (y-y0)*aW;
			for(int x = xa;x <= xb;x++) {
				float px = x+0.5f;
				//Barycentric coordinates (signed areas, whatever the orientation)
				float wa = ((bx-px)*(cy-py)-(by-py)*(cx-px))*aInv;
				float wb = ((cx-px)*(ay-py)-(cy-py)*(ax-px))*aInv;
				float wc = 1-wa-wb;
				if(wa < 0 || wb < 0 || wc < 0) {
					continue;
				}
				float z = wa*za+wb*zb+wc*zc;
				int p = aRow+x;
				if(z <= aZB[p]) {
					continue;
				}
				float ka = wa*za/z, kb = wb*zb/z, kc = wc*zc/z;
				aZB[p] = z;
				int rr, gg, bl;
				if(aDepth) {
					//The depth of the pixel (in the photo, interpolated with the perspective) in gray
					rr = gg = bl = depthGray(ka*da0+kb*db0+kc*dc0);
				}
				else {
					rr = clamp255((ka*ra+kb*rb+kc*rc)*255);
					gg = clamp255((ka*ga+kb*gb+kc*gc)*255);
					bl = clamp255((ka*ba+kb*bb+kc*bc)*255);
				}
				aOut[y*aW+x] = 0xFF000000|(rr<<16)|(gg<<8)|bl;
			}
		}
	}

	//Smoothing of the depth of the scenes (slider S of the scene processors, SceneMedia, viewer): Gaussian blur in
	//pixels of an image of DVD_LINES lines, proportional to the resolution (smoothedDvd())
	public static final double DVD_LINES = 576;
	public static final double DEFAULT_BLUR = 8.0;

	/**
	 * smoothed() by aBlur pixels of an image of DVD_LINES lines: aBlur x height / DVD_LINES cells of the grid
	 */
	public GaussianScene smoothedDvd(double aBlur) {
		return smoothed(aBlur*height/DVD_LINES);
	}

	//Last smoothed version of this scene (smoothed()) and its sigma
	java.lang.ref.SoftReference<GaussianScene> smoothedScene = null;
	double smoothedSigma = -1;

	/**
	 * This scene with the depth of its grid (the Gaussians on the pixels of the photo, hasGrid()) smoothed by a Gaussian
	 * blur of aSigma cells of the grid (as the depth map it was made from, blurred), this scene when there is no grid
	 * or aSigma is negligible. The blur is done on the inverse depths (the scale of a depth map); each Gaussian is moved
	 * along its ray from the camera of the photo (the origin) and resized with its depth. Kept for the next call
	 */
	public synchronized GaussianScene smoothed(double aSigma) {
		if(!hasGrid() || aSigma < 0.05) {
			return this;
		}
		if(smoothedScene != null && Math.abs(smoothedSigma-aSigma) < 1e-6) {
			GaussianScene aS = smoothedScene.get();
			if(aS != null) {
				return aS;
			}
		}
		int gw = width, gh = height, ng = gw*gh;
		float[] aInv = new float[ng];
		for(int i = 0;i < ng;i++) {
			float z = pos[3*i+2];
			aInv[i] = z > 1e-6f ? 1/z : 0;
		}
		float[] aBlur = blur(aInv, gw, gh, aSigma);
		GaussianScene aS = new GaussianScene();
		aS.n = n;
		aS.pos = pos.clone();
		aS.cov = cov.clone();
		aS.base = base;
		aS.sh1 = sh1;
		aS.opacity = opacity;
		aS.fx = fx;
		aS.fy = fy;
		aS.cx = cx;
		aS.cy = cy;
		aS.width = width;
		aS.height = height;
		aS.source = source;
		aS.fileKey = fileKey == null ? null : fileKey+String.format(java.util.Locale.ROOT, "|smooth%.3f", aSigma);
		for(int i = 0;i < ng;i++) {
			float z = pos[3*i+2];
			if(z <= 1e-6f || aBlur[i] <= 1e-9f) {
				continue;
			}
			float k = (1/aBlur[i])/z;
			for(int c = 0;c < 3;c++) {
				aS.pos[3*i+c] = pos[3*i+c]*k;
			}
			float k2 = k*k;
			for(int c = 0;c < 6;c++) {
				aS.cov[6*i+c] = cov[6*i+c]*k2;
			}
		}
		aS.depthStats();
		smoothedScene = new java.lang.ref.SoftReference<GaussianScene>(aS);
		smoothedSigma = aSigma;
		return aS;
	}

	/**
	 * Separable Gaussian blur of aV (aW x aH) of aSigma pixels, the values 0 (no depth) left out, edges repeated
	 */
	static float[] blur(float[] aV,int aW,int aH,double aSigma) {
		int r = Math.max(1, (int)Math.ceil(3*aSigma));
		float[] aK = new float[2*r+1];
		for(int i = -r;i <= r;i++) {
			aK[i+r] = (float)Math.exp(-i*i/(2*aSigma*aSigma));
		}
		float[] aTmp = new float[aV.length], aOut = new float[aV.length];
		for(int y = 0;y < aH;y++) {
			int aRow = y*aW;
			for(int x = 0;x < aW;x++) {
				float s = 0, w = 0;
				for(int i = -r;i <= r;i++) {
					float v = aV[aRow+Math.max(0, Math.min(aW-1, x+i))];
					if(v > 0) {
						s += v*aK[i+r];
						w += aK[i+r];
					}
				}
				aTmp[aRow+x] = w > 0 ? s/w : 0;
			}
		}
		for(int y = 0;y < aH;y++) {
			for(int x = 0;x < aW;x++) {
				float s = 0, w = 0;
				for(int i = -r;i <= r;i++) {
					float v = aTmp[Math.max(0, Math.min(aH-1, y+i))*aW+x];
					if(v > 0) {
						s += v*aK[i+r];
						w += aK[i+r];
					}
				}
				aOut[y*aW+x] = w > 0 ? s/w : 0;
			}
		}
		return aOut;
	}

	/**
	 * Gaussians of the visible surface of a Flash3D scene: it predicts 2 per pixel, the first ones (indices below
	 * width x height) on the surface seen by the photo, the second ones behind them (what it hides, invented, blurred);
	 * n for another scene
	 */
	public int firstLayer() {
		return width > 0 && height > 0 && n == 2*width*height ? width*height : n;
	}

	/**
	 * Focal length (pixels) for an image aW wide: that of the photo scaled, else a field of view of 60 degrees
	 */
	public double focalFor(int aW) {
		if(fx > 0 && width > 0) {
			return fx*aW/(double)width;
		}
		return aW/2.0/Math.tan(Math.toRadians(30));
	}

	/**
	 * A view: the eye position, its axes (right, down, forward: unit, orthogonal), the focal length and principal
	 * point in pixels of the output image
	 */
	public static class View {
		public double[] eye = new double[3];
		public double[] right = {1, 0, 0};
		public double[] down = {0, 1, 0};
		public double[] fwd = {0, 0, 1};
		public double focal = 500;
		public double cx = 0, cy = 0;
		//Nearest depth drawn
		public double near = 0.01;
		//Background ARGB
		public int bck = 0xFF000000;
		//Low pass filter (variance in pixels added to the 2D Gaussians; see lowPassFor())
		public double lowPass = 0.3;
		//Only the Gaussians of index below this are drawn (-1: all): firstLayer() for the visible surface alone
		public int maxIndex = -1;
		//Mesh of the visible Gaussians drawn instead of the Gaussians (Flash3D scenes; StereoCam.view() sets it): its
		//triangles stretch over what the photo did not see
		public boolean meshFill = false;
		//The estimated depth of each Gaussian / pixel of the mesh in shades of gray (depthGray(): white near, black far)
		//instead of the colors of the photo
		public boolean depthView = false;
		//Thresholding (smart filling): the triangles of the mesh whose |cos| of the normal with the direction of the depth
		//(triCos()) is below this are holes (0: none)
		public double cosCut = 0;
		//Mesh only: receives 1/z of the nearest triangle of each pixel (0: none), aW x aH (null: not wanted)
		public float[] depthOut = null;
	}

	/**
	 * Low pass of a view of focal aFocal: 0.3 pixel at the size of the Gaussians (as their training), scaled with
	 * the enlargement, so that an image larger than the photo of the scene shows no grid of points between them
	 */
	public double lowPassFor(double aFocal) {
		double k = fx > 0 ? aFocal/fx : 1;
		return 0.3*Math.max(1, k*k);
	}

	/**
	 * Image aW x aH of the scene seen from aV (ARGB, opaque)
	 */
	public BufferedImage render(View aV,int aW,int aH) {
		BufferedImage aBI = new BufferedImage(aW, aH, BufferedImage.TYPE_INT_ARGB);
		int[] aPix = ((DataBufferInt)aBI.getRaster().getDataBuffer()).getData();
		float[] aTrans = new float[aW*aH];
		render(aV, aW, aH, aPix, aTrans);
		toAlpha(aPix, aTrans, aV.bck);
		return aBI;
	}

	/**
	 * The pixels aPix, blended over the background aBck by their transmittance aTrans, made transparent where nothing
	 * covers them: alpha = 1 - aTrans, colors unpremultiplied (the lines below in the time line stack show through
	 * the holes of the mesh and between the Gaussians)
	 */
	public static void toAlpha(int[] aPix,float[] aTrans,int aBck) {
		int aBR = (aBck>>16)&0xFF, aBG = (aBck>>8)&0xFF, aBB = aBck&0xFF;
		for(int p = 0;p < aPix.length;p++) {
			float t = aTrans[p];
			if(t < 1/512f) {
				continue;
			}
			float a = 1-t;
			int aA = clamp255(a*255);
			if(aA == 0) {
				aPix[p] = 0;
				continue;
			}
			int c = aPix[p];
			int r = clamp255((((c>>16)&0xFF)-t*aBR)/a), g = clamp255((((c>>8)&0xFF)-t*aBG)/a), b = clamp255(((c&0xFF)-t*aBB)/a);
			aPix[p] = (aA<<24)|(r<<16)|(g<<8)|b;
		}
	}

	//Buffers of the projected Gaussians, kept from one image to the next (render() is synchronized)
	float[] bU, bV, bCA, bCB, bCC, bOp;
	int[] bRad, bCol;
	long[] bKeys;

	public void render(View aV,int aW,int aH,int[] aPix) {
		render(aV, aW, aH, aPix, null);
	}

	/**
	 * Same, aTrans (null: not wanted) receiving the transmittance left at each pixel (1: no Gaussian, the background).
	 * View.meshFill on a Flash3D scene: the mesh of its visible Gaussians instead (renderMeshImage())
	 */
	public synchronized void render(View aV,int aW,int aH,int[] aPix,float[] aTrans) {
		if(aV.meshFill && hasGrid()) {
			renderMeshImage(aV, aW, aH, aPix, aTrans);
			return;
		}
		final int aLimit = aV.maxIndex;
		int aThreads = Math.max(1, Runtime.getRuntime().availableProcessors());
		if(bU == null) {
			bU = new float[n]; bV = new float[n]; bCA = new float[n]; bCB = new float[n]; bCC = new float[n];
			bOp = new float[n];
			bRad = new int[n];
			bCol = new int[n];
			bKeys = new long[n];
		}
		//Projection of the Gaussians (in parallel chunks)
		float[] aU = bU, aVv = bV, aCA = bCA, aCB = bCB, aCC = bCC;
		float[] aOp = bOp;
		int[] aRad = bRad;
		int[] aCol = bCol;
		long[] aKeys = bKeys;
		AtomicInteger aNext = new AtomicInteger(0);
		int aChunk = 8192;
		int[] aNbVisible = new int[(n+aChunk-1)/aChunk];
		Thread[] aTh = new Thread[aThreads];
		for(int t = 0;t < aThreads;t++) {
			aTh[t] = new Thread(() -> {
				int c;
				while((c = aNext.getAndIncrement())*aChunk < n) {
					int aFrom = c*aChunk, aTo = Math.min(n, aFrom+aChunk);
					int aK = aFrom;
					for(int i = aFrom;i < aTo;i++) {
						if((aLimit < 0 || i < aLimit) && project(aV, aW, aH, i, aU, aVv, aCA, aCB, aCC, aOp, aRad, aCol, aKeys, aK)) {
							aK++;
						}
					}
					aNbVisible[c] = aK-aFrom;
				}
			});
			aTh[t].start();
		}
		join(aTh);
		//Visible ones packed, sorted front to back (keys: depth bits, index)
		int aNb = 0;
		for(int c = 0;c < aNbVisible.length;c++) {
			System.arraycopy(aKeys, c*aChunk, aKeys, aNb, aNbVisible[c]);
			aNb += aNbVisible[c];
		}
		Arrays.parallelSort(aKeys, 0, aNb);
		long[] aSorted = aKeys;
		final int aCount = aNb;
		//Bands of lines
		int aBandH = Math.max(4, aH/(aThreads*4));
		int aBands = (aH+aBandH-1)/aBandH;
		AtomicInteger aNextBand = new AtomicInteger(0);
		int aBR = (aV.bck>>16)&0xFF, aBG = (aV.bck>>8)&0xFF, aBBl = aV.bck&0xFF;
		for(int t = 0;t < aThreads;t++) {
			aTh[t] = new Thread(() -> {
				float[] aT = new float[aW*aBandH];
				float[] aR = new float[aW*aBandH], aG = new float[aW*aBandH], aB = new float[aW*aBandH];
				int b;
				while((b = aNextBand.getAndIncrement()) < aBands) {
					int y0 = b*aBandH, y1 = Math.min(aH, y0+aBandH);
					int aPixels = (y1-y0)*aW;
					Arrays.fill(aT, 0, aPixels, 1f);
					Arrays.fill(aR, 0, aPixels, 0f);
					Arrays.fill(aG, 0, aPixels, 0f);
					Arrays.fill(aB, 0, aPixels, 0f);
					int aOpaque = 0;
					for(int s = 0;s < aCount && aOpaque < aPixels;s++) {
						int i = (int)aSorted[s];
						float u = aU[i], v = aVv[i];
						int r = aRad[i];
						int aYa = Math.max(y0, (int)Math.floor(v-r)), aYb = Math.min(y1-1, (int)Math.ceil(v+r));
						if(aYa > aYb) {
							continue;
						}
						int aXa = Math.max(0, (int)Math.floor(u-r)), aXb = Math.min(aW-1, (int)Math.ceil(u+r));
						if(aXa > aXb) {
							continue;
						}
						float a = aCA[i], bb = aCB[i], cc = aCC[i], op = aOp[i];
						int aC = aCol[i];
						float cr = ((aC>>16)&0xFF)/255f, cg = ((aC>>8)&0xFF)/255f, cb = (aC&0xFF)/255f;
						for(int y = aYa;y <= aYb;y++) {
							float dy = y+0.5f-v;
							int aRowO = (y-y0)*aW;
							for(int x = aXa;x <= aXb;x++) {
								int p = aRowO+x;
								float tr = aT[p];
								if(tr < 1e-3f) {
									continue;
								}
								float dx = x+0.5f-u;
								float aPow = -0.5f*(a*dx*dx+cc*dy*dy)-bb*dx*dy;
								if(aPow > 0 || aPow < -5.6f) {
									continue;
								}
								float al = op*fastExp(aPow);
								if(al < 1/255f) {
									continue;
								}
								if(al > 0.99f) {
									al = 0.99f;
								}
								float w = al*tr;
								aR[p] += cr*w;
								aG[p] += cg*w;
								aB[p] += cb*w;
								tr *= 1-al;
								aT[p] = tr;
								if(tr < 1e-3f) {
									aOpaque++;
								}
							}
						}
					}
					for(int p = 0;p < aPixels;p++) {
						float tr = aT[p];
						int rr = clamp255(aR[p]*255+tr*aBR), gg = clamp255(aG[p]*255+tr*aBG), bl = clamp255(aB[p]*255+tr*aBBl);
						aPix[y0*aW+p] = 0xFF000000|(rr<<16)|(gg<<8)|bl;
						if(aTrans != null) {
							aTrans[y0*aW+p] = tr;
						}
					}
				}
			});
			aTh[t].start();
		}
		join(aTh);
	}

	static int clamp255(float aV) {
		int v = (int)(aV+0.5f);
		return v < 0 ? 0 : v > 255 ? 255 : v;
	}

	//e^x on -EXP_MIN..0, linear between the entries (error below 1e-5)
	static final float EXP_MIN = 5.6f;
	static final int EXP_N = 2048;
	static final float[] EXP = new float[EXP_N+2];
	static {
		for(int i = 0;i < EXP.length;i++) {
			EXP[i] = (float)Math.exp(-i*EXP_MIN/EXP_N);
		}
	}

	/**
	 * e^x for x in -EXP_MIN..0
	 */
	static float fastExp(float aX) {
		float t = -aX*(EXP_N/EXP_MIN);
		int k = (int)t;
		float fr = t-k;
		return EXP[k]+(EXP[k+1]-EXP[k])*fr;
	}

	static void join(Thread[] aTh) {
		for(Thread aT : aTh) {
			try {
				aT.join();
			}
			catch(InterruptedException e) {
				Thread.currentThread().interrupt();
			}
		}
	}

	/**
	 * Projection of the Gaussian i into slot aK: center, conic (inverse 2D covariance), radius, color seen from the
	 * eye, opacity, sort key
	 * @return false if not visible
	 */
	boolean project(View aV,int aW,int aH,int i,float[] aU,float[] aVv,float[] aCA,float[] aCB,float[] aCC,float[] aOp
			,int[] aRad,int[] aCol,long[] aKeys,int aK) {
		double dx = pos[3*i]-aV.eye[0], dy = pos[3*i+1]-aV.eye[1], dz = pos[3*i+2]-aV.eye[2];
		double[] r = aV.right, d = aV.down, f = aV.fwd;
		double Z = f[0]*dx+f[1]*dy+f[2]*dz;
		if(Z < aV.near) {
			return false;
		}
		double X = r[0]*dx+r[1]*dy+r[2]*dz;
		double Y = d[0]*dx+d[1]*dy+d[2]*dz;
		double aF = aV.focal;
		double u = aF*X/Z+aV.cx, v = aF*Y/Z+aV.cy;
		//Rows of T = J W (Jacobian of the projection times the rotation of the view)
		double aIz = aF/Z, aIz2X = aF*X/(Z*Z), aIz2Y = aF*Y/(Z*Z);
		double t00 = aIz*r[0]-aIz2X*f[0], t01 = aIz*r[1]-aIz2X*f[1], t02 = aIz*r[2]-aIz2X*f[2];
		double t10 = aIz*d[0]-aIz2Y*f[0], t11 = aIz*d[1]-aIz2Y*f[1], t12 = aIz*d[2]-aIz2Y*f[2];
		int o = 6*i;
		double sxx = cov[o], sxy = cov[o+1], sxz = cov[o+2], syy = cov[o+3], syz = cov[o+4], szz = cov[o+5];
		//S T1^T and S T0^T
		double p0 = sxx*t00+sxy*t01+sxz*t02, p1 = sxy*t00+syy*t01+syz*t02, p2 = sxz*t00+syz*t01+szz*t02;
		double q0 = sxx*t10+sxy*t11+sxz*t12, q1 = sxy*t10+syy*t11+syz*t12, q2 = sxz*t10+syz*t11+szz*t12;
		double a = t00*p0+t01*p1+t02*p2+aV.lowPass;
		double b = t00*q0+t01*q1+t02*q2;
		double c = t10*q0+t11*q1+t12*q2+aV.lowPass;
		double aDet = a*c-b*b;
		if(!(aDet > 1e-12)) {
			return false;
		}
		double aMid = 0.5*(a+c);
		double aL1 = aMid+Math.sqrt(Math.max(0.1, aMid*aMid-aDet));
		double aR = Math.ceil(3*Math.sqrt(aL1));
		//Larger than the image (a Gaussian at the eye): skipped
		if(aR > 2*Math.max(aW, aH)) {
			return false;
		}
		if(u+aR < 0 || u-aR >= aW || v+aR < 0 || v-aR >= aH) {
			return false;
		}
		aU[i] = (float)u;
		aVv[i] = (float)v;
		aCA[i] = (float)(c/aDet);
		aCB[i] = (float)(-b/aDet);
		aCC[i] = (float)(a/aDet);
		aOp[i] = opacity[i];
		aRad[i] = (int)aR;
		//Color seen from the eye (degree 1: direction of the Gaussian from the eye, in the frame of the scene)
		double cr = base[3*i], cg = base[3*i+1], cb = base[3*i+2];
		if(sh1 != null) {
			double aLen = Math.sqrt(dx*dx+dy*dy+dz*dz);
			double x = dx/aLen, y = dy/aLen, z = dz/aLen;
			int s = 9*i;
			cr += -y*sh1[s]+z*sh1[s+1]-x*sh1[s+2];
			cg += -y*sh1[s+3]+z*sh1[s+4]-x*sh1[s+5];
			cb += -y*sh1[s+6]+z*sh1[s+7]-x*sh1[s+8];
		}
		if(aV.depthView) {
			//Its estimated depth in gray
			int g = depthGray(pos[3*i+2]);
			aCol[i] = (g<<16)|(g<<8)|g;
		}
		else {
			aCol[i] = (clamp255((float)(cr*255))<<16)|(clamp255((float)(cg*255))<<8)|clamp255((float)(cb*255));
		}
		aKeys[aK] = ((long)Float.floatToIntBits((float)Z)<<32)|i;
		return true;
	}
}
