package com.cubaix.TDenlive.model3d;

import java.awt.image.BufferedImage;

/**
 * Pair of parallel stereo cameras looking at a GaussianScene: center, heading (yaw, around the vertical axis of
 * the photo) and pitch (radians), spacing of the eyes (base) and distance of the screen plane (zero parallax: off-axis
 * shift of the principal points), in the frame of the scene (x right, y down, z forward, metres). A base < 0 or a
 * screen <= 0 is automatic: taken from the scene rendered (screen = depth of its nearest objects, base = screen / 30,
 * the rule of the stereo photographers); a base of 0 gives the same image to both eyes.
 * Navigation (viewer, processors): orbit around the point of the screen plane in front, pan in the plane of the
 * cameras, look around in place, move along the axes.
 */
public class StereoCam {
	public double[] pos = new double[3];
	public double yaw = 0, pitch = 0;
	public double base = -1, screen = -1;

	public StereoCam copy() {
		StereoCam aC = new StereoCam();
		aC.pos = pos.clone();
		aC.yaw = yaw;
		aC.pitch = pitch;
		aC.base = base;
		aC.screen = screen;
		return aC;
	}

	public boolean sameAs(StereoCam aC) {
		return aC != null && pos[0] == aC.pos[0] && pos[1] == aC.pos[1] && pos[2] == aC.pos[2]
				&& yaw == aC.yaw && pitch == aC.pitch && base == aC.base && screen == aC.screen;
	}

	/**
	 * At the viewpoint of the photo (spacing and screen kept)
	 */
	public void resetView() {
		pos = new double[3];
		yaw = pitch = 0;
	}

	/**
	 * Linear between two cameras (an automatic value stays automatic only if it is in both)
	 */
	public static StereoCam lerp(StereoCam aA,StereoCam aB,double aF) {
		StereoCam aC = new StereoCam();
		for(int c = 0;c < 3;c++) {
			aC.pos[c] = aA.pos[c]*(1-aF)+aB.pos[c]*aF;
		}
		aC.yaw = aA.yaw*(1-aF)+aB.yaw*aF;
		aC.pitch = aA.pitch*(1-aF)+aB.pitch*aF;
		aC.base = aA.base > 0 && aB.base > 0 ? aA.base*(1-aF)+aB.base*aF : (aA.base > 0 ? aA.base : aB.base);
		aC.screen = aA.screen > 0 && aB.screen > 0 ? Math.exp(Math.log(aA.screen)*(1-aF)+Math.log(aB.screen)*aF) : (aA.screen > 0 ? aA.screen : aB.screen);
		return aC;
	}

	public String save() {
		return pos[0]+";"+pos[1]+";"+pos[2]+";"+yaw+";"+pitch+";"+base+";"+screen;
	}

	public static StereoCam load(String aText) {
		StereoCam aC = new StereoCam();
		try {
			String[] aV = aText.trim().split(";");
			for(int c = 0;c < 3;c++) {
				aC.pos[c] = Double.parseDouble(aV[c]);
			}
			aC.yaw = Double.parseDouble(aV[3]);
			aC.pitch = Double.parseDouble(aV[4]);
			aC.base = Double.parseDouble(aV[5]);
			aC.screen = Double.parseDouble(aV[6]);
		}
		catch(Throwable t) {
			t.printStackTrace(System.err);
		}
		return aC;
	}

	/** Screen plane distance used with aScene (automatic: depth of its nearest objects) */
	public double screenFor(GaussianScene aScene) {
		return screen > 0 ? screen : Math.max(0.1, aScene == null ? 2 : aScene.nearZ);
	}

	/** Spacing used with aScene (automatic: screen / 30) */
	public double baseFor(GaussianScene aScene) {
		return base > 0 ? base : (base == 0 ? 0 : screenFor(aScene)/30);
	}

	/**
	 * Axes of the pair (right, down, forward) for a heading and a pitch: y down, z forward as in the photo
	 */
	public static double[][] axes(double aYaw,double aPitch) {
		double[] f = {Math.cos(aPitch)*Math.sin(aYaw), -Math.sin(aPitch), Math.cos(aPitch)*Math.cos(aYaw)};
		double[] r = {Math.cos(aYaw), 0, -Math.sin(aYaw)};
		double[] d = {f[1]*r[2]-f[2]*r[1], f[2]*r[0]-f[0]*r[2], f[0]*r[1]-f[1]*r[0]};
		return new double[][] {r, d, f};
	}

	/**
	 * View of one eye (-1 left, 0 center, 1 right) of an image aW x aH of aScene: shifted by half the spacing along
	 * the right axis, the principal point shifted so that both views coincide at the screen plane
	 */
	public GaussianScene.View view(GaussianScene aScene,int aEye,int aW,int aH) {
		double[][] aA = axes(yaw, pitch);
		GaussianScene.View aV = new GaussianScene.View();
		double aScreen = screenFor(aScene);
		double aOff = aEye*baseFor(aScene)/2;
		for(int c = 0;c < 3;c++) {
			aV.eye[c] = pos[c]+aA[0][c]*aOff;
		}
		aV.right = aA[0];
		aV.down = aA[1];
		aV.fwd = aA[2];
		aV.focal = aScene.focalFor(aW);
		//The principal point of the photo when the image has its ratio, else centered
		double aCx = aW/2.0, aCy = aH/2.0;
		if(aScene.cx > 0 && aScene.width > 0 && aScene.height > 0
				&& Math.abs(aW/(double)aH-aScene.width/(double)aScene.height) < 0.02) {
			aCx = aScene.cx*aW/aScene.width;
			aCy = aScene.cy*aH/aScene.height;
		}
		aV.cx = aCx+aOff*aV.focal/aScreen;
		aV.cy = aCy;
		aV.near = Math.max(0.005, aScene.medianZ*0.002);
		aV.lowPass = aScene.lowPassFor(aV.focal);
		aV.bck = 0xFF000000;
		//The mesh of the visible Gaussians (Flash3D scenes), not the Gaussians
		aV.meshFill = true;
		return aV;
	}

	/**
	 * Image of one eye (-1 left, 0 center, 1 right)
	 */
	public BufferedImage render(GaussianScene aScene,int aEye,int aW,int aH) {
		return render(aScene, aEye, aW, aH, true);
	}

	/**
	 * Same, the mesh of the visible Gaussians when aInterp (interpolation of their colors), else the Gaussians
	 */
	public BufferedImage render(GaussianScene aScene,int aEye,int aW,int aH,boolean aInterp) {
		return render(aScene, aEye, aW, aH, aInterp, false);
	}

	/**
	 * Same, the estimated depths in gray instead of the colors when aDepth (GaussianScene.View.depthView)
	 */
	public BufferedImage render(GaussianScene aScene,int aEye,int aW,int aH,boolean aInterp,boolean aDepth) {
		GaussianScene.View aV = view(aScene, aEye, aW, aH);
		aV.meshFill = aInterp;
		aV.depthView = aDepth;
		return aScene.render(aV, aW, aH);
	}

	//== Navigation ==

	public void move(double aRight,double aDown,double aFwd) {
		double[][] aA = axes(yaw, pitch);
		for(int c = 0;c < 3;c++) {
			pos[c] += aA[0][c]*aRight+aA[1][c]*aDown+aA[2][c]*aFwd;
		}
	}

	static double clamp(double aV,double aMin,double aMax) {
		return Math.max(aMin, Math.min(aMax, aV));
	}

	/**
	 * Mouse drag of (aDx, aDy) pixels on a view of focal aFocal: 1 orbit (around the point of the screen plane in
	 * front), 2 pan (the screen plane follows the mouse), 3 look around (turn in place)
	 */
	public void drag(int aKind,int aDx,int aDy,double aFocal,GaussianScene aScene) {
		double aScreen = screenFor(aScene);
		if(aKind == 2) {
			double k = aScreen/aFocal;
			move(-aDx*k, -aDy*k, 0);
		}
		else if(aKind == 3) {
			yaw -= aDx/aFocal;
			pitch = clamp(pitch+aDy/aFocal, -Math.toRadians(85), Math.toRadians(85));
		}
		else {
			double[][] aA = axes(yaw, pitch);
			double[] aPivot = new double[3];
			for(int c = 0;c < 3;c++) {
				aPivot[c] = pos[c]+aA[2][c]*aScreen;
			}
			yaw += aDx*0.005;
			pitch = clamp(pitch-aDy*0.005, -Math.toRadians(85), Math.toRadians(85));
			aA = axes(yaw, pitch);
			for(int c = 0;c < 3;c++) {
				pos[c] = aPivot[c]-aA[2][c]*aScreen;
			}
		}
	}

	/** Distance of a step of the keys and of the wheel (see step(GaussianScene, double[], double, double)) */
	public double step(GaussianScene aScene) {
		return step(aScene, pos, yaw, pitch);
	}

	//Part of the distance to the background in front advanced by a step (a wheel notch)
	public static final double STEP_PART = 0.1;

	/**
	 * Distance of a step of the keys and of the wheel from aPos looking at (aYaw, aPitch): STEP_PART of the distance
	 * to the background in front (GaussianScene.backgroundDistance()), at least 1 % of the median depth of the scene;
	 * nothing in front (moved beyond the scene): a tenth of its median depth
	 */
	public static double step(GaussianScene aScene,double[] aPos,double aYaw,double aPitch) {
		if(aScene == null) {
			return 0.1;
		}
		double aMin = Math.max(0.005, aScene.medianZ*0.01);
		double aD = aScene.backgroundDistance(aPos, axes(aYaw, aPitch)[2]);
		return aD > 0 ? Math.max(aMin, aD*STEP_PART) : Math.max(aMin, aScene.medianZ*0.1);
	}
}
