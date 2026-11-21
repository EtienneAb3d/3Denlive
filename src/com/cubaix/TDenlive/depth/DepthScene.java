package com.cubaix.TDenlive.depth;

import java.awt.image.BufferedImage;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;

import com.cubaix.TDenlive.model3d.GaussianScene;

/**
 * 3D scene of Gaussians built from a depth map of Depth Anything V2 (ai.SceneGenerator.DEPTH_SMALL / DEPTH_LARGE,
 * no Python): one Gaussian per pixel of a grid of about aPixels pixels at the ratio of the photo, row by row (the
 * grid of model3d.GaussianScene.hasGrid(): meshed like a Depth Pro scene), nothing behind (the holes are made and
 * filled by model3d.SmartFill).
 * Depth Anything gives a relative inverse depth (affine: scale and shift unknown); its levels low / high (1st and
 * 99th percentiles, smoothed over the frames of a video by the caller) become the inverse depths of Z_FAR and Z_NEAR,
 * linearly in between (sky and beyond: Z_FAR). The camera is assumed with a field of view of FOV_DEG on the larger
 * side of the photo, principal point at the center. Each Gaussian is a disc facing the camera whose radius is the
 * size of its pixel at its depth (RADIUS pixel; thin in depth), opaque, of the color of the photo (average of the
 * pixels of its cell). Same PLY / compact files as depthpro_scene.py.
 */
public class DepthScene {
	static final double SH_C0 = 0.28209479177387814;
	//Depths (metres) of the nearest and farthest levels of the map: ratio of the depths of the scene
	public static final double Z_NEAR = 1.0;
	public static final double Z_FAR = 10.0;
	//Assumed field of view on the larger side (degrees): a usual camera (about 26 mm in 24 x 36)
	public static final double FOV_DEG = 60;
	//Radius of the disc of a Gaussian (pixels of the grid): its pixel, a little overlap with its neighbors
	public static final double RADIUS = 0.6;
	static final double OPACITY = 0.995;

	/**
	 * Grid at the ratio of the photo, about aPixels pixels, never larger than the photo (as depthpro_scene.gridSize())
	 */
	public static int[] gridSize(int aW,int aH,int aPixels) {
		double aR = aW/(double)aH;
		int h = Math.max(2, (int)Math.round(Math.sqrt(aPixels/aR)));
		int w = Math.max(2, (int)Math.round(h*aR));
		if(w > aW || h > aH) {
			w = aW;
			h = aH;
		}
		return new int[] {w, h};
	}

	/**
	 * Write the scene of the photo aImg with its depth aDepth (aDW x aDH, the higher the nearer, levels aLow..aHigh)
	 * into aOut: PLY, or compact (GaussianScene.COMPACT_MAGIC) when aCompact
	 */
	public static void write(BufferedImage aImg,float[] aDepth,int aDW,int aDH,float aLow,float aHigh,int aPixels
			,boolean aCompact,String aSourceName,File aOut) throws Exception {
		int aIW = aImg.getWidth();
		int aIH = aImg.getHeight();
		int[] aG = gridSize(aIW, aIH, aPixels);
		int aGW = aG[0];
		int aGH = aG[1];
		int n = aGW*aGH;
		double aF = 0.5*Math.max(aGW, aGH)/Math.tan(Math.toRadians(FOV_DEG/2));
		double aCx = aGW/2.0;
		double aCy = aGH/2.0;
		float[] aCol = cellColors(aImg, aGW, aGH);
		double aInvNear = 1/Z_NEAR;
		double aInvFar = 1/Z_FAR;
		double aSpan = aHigh > aLow ? aHigh-aLow : 1;
		float[] aPos = new float[3*n];
		float[] aScale = new float[3*n];
		for(int y = 0;y < aGH;y++) {
			//Bilinear sample of the depth at the center of the cell
			double aSy = Math.max(0, Math.min(aDH-1, (y+0.5)*aDH/aGH-0.5));
			int y0 = (int)aSy;
			int y1 = Math.min(aDH-1, y0+1);
			double fy = aSy-y0;
			for(int x = 0;x < aGW;x++) {
				double aSx = Math.max(0, Math.min(aDW-1, (x+0.5)*aDW/aGW-0.5));
				int x0 = (int)aSx;
				int x1 = Math.min(aDW-1, x0+1);
				double fx = aSx-x0;
				double aD = (aDepth[y0*aDW+x0]*(1-fx)+aDepth[y0*aDW+x1]*fx)*(1-fy)
						+(aDepth[y1*aDW+x0]*(1-fx)+aDepth[y1*aDW+x1]*fx)*fy;
				double t = Math.max(0, Math.min(1, (aD-aLow)/aSpan));
				double z = 1/(aInvFar+t*(aInvNear-aInvFar));
				int i = y*aGW+x;
				aPos[3*i] = (float)((x+0.5-aCx)*z/aF);
				aPos[3*i+1] = (float)((y+0.5-aCy)*z/aF);
				aPos[3*i+2] = (float)z;
				float s = (float)(RADIUS*z/aF);
				aScale[3*i] = s;
				aScale[3*i+1] = s;
				aScale[3*i+2] = 0.1f*s;
			}
		}
		aOut.getAbsoluteFile().getParentFile().mkdirs();
		File aTmp = new File(aOut.getPath()+".part");
		try(OutputStream aOS = new BufferedOutputStream(new FileOutputStream(aTmp), 1 << 20)) {
			String aCam = String.format(java.util.Locale.ROOT, "%.6f %.6f %.6f %.6f %d %d", aF, aF, aCx, aCy, aGW, aGH);
			if(aCompact) {
				aOS.write((GaussianScene.COMPACT_MAGIC+"\n"+n+" "+aCam+"\n").getBytes("US-ASCII"));
				ByteBuffer aBB = ByteBuffer.allocate(20*n).order(ByteOrder.LITTLE_ENDIAN);
				for(int i = 0;i < 3*n;i++) {
					aBB.putShort(Float.floatToFloat16(Math.max(-65000, Math.min(65000, aPos[i]))));
				}
				for(int i = 0;i < 3*n;i++) {
					aBB.putShort(Float.floatToFloat16((float)Math.log(Math.max(1e-7, aScale[i]))));
				}
				for(int i = 0;i < n;i++) {
					aBB.put((byte)127).put((byte)0).put((byte)0).put((byte)0);
				}
				for(int i = 0;i < n;i++) {
					for(int c = 0;c < 3;c++) {
						aBB.put((byte)Math.round(aCol[3*i+c]*255));
					}
					aBB.put((byte)Math.round(OPACITY*255));
				}
				aOS.write(aBB.array());
			}
			else {
				String[] aProps = {"x", "y", "z", "nx", "ny", "nz", "f_dc_0", "f_dc_1", "f_dc_2",
						"opacity", "scale_0", "scale_1", "scale_2", "rot_0", "rot_1", "rot_2", "rot_3"};
				StringBuilder aH = new StringBuilder("ply\nformat binary_little_endian 1.0\n");
				aH.append("comment 3Denlive depth map scene of ").append(aSourceName.replace('\n', ' ')).append('\n');
				aH.append("comment 3Denlive camera ").append(aCam).append('\n');
				aH.append("element vertex ").append(n).append('\n');
				for(String aP : aProps) {
					aH.append("property float ").append(aP).append('\n');
				}
				aH.append("end_header\n");
				aOS.write(aH.toString().getBytes("US-ASCII"));
				float aLogit = (float)Math.log(OPACITY/(1-OPACITY));
				ByteBuffer aBB = ByteBuffer.allocate(aProps.length*4*aGW).order(ByteOrder.LITTLE_ENDIAN);
				for(int y = 0;y < aGH;y++) {
					aBB.clear();
					for(int x = 0;x < aGW;x++) {
						int i = y*aGW+x;
						aBB.putFloat(aPos[3*i]).putFloat(aPos[3*i+1]).putFloat(aPos[3*i+2]);
						aBB.putFloat(0).putFloat(0).putFloat(0);
						for(int c = 0;c < 3;c++) {
							aBB.putFloat((float)((aCol[3*i+c]-0.5)/SH_C0));
						}
						aBB.putFloat(aLogit);
						for(int c = 0;c < 3;c++) {
							aBB.putFloat((float)Math.log(aScale[3*i+c]));
						}
						aBB.putFloat(1).putFloat(0).putFloat(0).putFloat(0);
					}
					aOS.write(aBB.array(), 0, aBB.position());
				}
			}
		}
		Files.move(aTmp.toPath(), aOut.toPath(), StandardCopyOption.REPLACE_EXISTING);
	}

	/**
	 * Colors 0..1 of the cells of the grid: average of the pixels of the photo in each cell (box filter)
	 */
	static float[] cellColors(BufferedImage aImg,int aGW,int aGH) {
		int aIW = aImg.getWidth();
		int aIH = aImg.getHeight();
		float[] aCol = new float[3*aGW*aGH];
		int[] aRow = new int[aIW];
		double[] aSum = new double[3*aGW];
		int[] aCount = new int[aGW];
		int[] aCellX = new int[aIW];
		for(int x = 0;x < aIW;x++) {
			aCellX[x] = Math.min(aGW-1, (int)((long)x*aGW/aIW));
		}
		int aSrcY = 0;
		for(int gy = 0;gy < aGH;gy++) {
			java.util.Arrays.fill(aSum, 0);
			java.util.Arrays.fill(aCount, 0);
			int aEndY = Math.max(aSrcY+1, (int)Math.min(aIH, (long)(gy+1)*aIH/aGH));
			for(int y = aSrcY;y < aEndY;y++) {
				aImg.getRGB(0, y, aIW, 1, aRow, 0, aIW);
				for(int x = 0;x < aIW;x++) {
					int p = aRow[x];
					int c = aCellX[x];
					aSum[3*c] += (p >> 16) & 0xFF;
					aSum[3*c+1] += (p >> 8) & 0xFF;
					aSum[3*c+2] += p & 0xFF;
					aCount[c]++;
				}
			}
			aSrcY = Math.min(aIH-1, aEndY);
			for(int gx = 0;gx < aGW;gx++) {
				int i = gy*aGW+gx;
				double aN = Math.max(1, aCount[gx])*255.0;
				aCol[3*i] = (float)(aSum[3*gx]/aN);
				aCol[3*i+1] = (float)(aSum[3*gx+1]/aN);
				aCol[3*i+2] = (float)(aSum[3*gx+2]/aN);
			}
		}
		return aCol;
	}
}
