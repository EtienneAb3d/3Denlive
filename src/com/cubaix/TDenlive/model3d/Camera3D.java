package com.cubaix.TDenlive.model3d;

/**
 * Camera of a model for one eye (aEye: -1 left, 0 center, 1 right). The eyes are shifted along the
 * right axis, with parallel axes and an off-axis projection: zero parallax at the target distance.
 */
public class Camera3D {
	public static final double NEAR = 0.01;
	public double[] pos;//Center camera
	public double[] right, up, fwd;
	public double[] eyePos;
	public double f;//Focal length in pixels
	public double cx, cy;//Image center
	public double dist;
	public double eyeShift;//Signed offset of the eye along right

	/**
	 * @param aFrameH height in pixels of the output frame (field of view of the model)
	 */
	public Camera3D(Model3D aM, double aCx, double aCy, double aFrameH, int aEye) {
		dist = Math.max(1e-3, aM.distance);
		double aYaw = Math.toRadians(aM.yaw);
		double aPitch = Math.toRadians(Math.max(Model3D.MIN_PITCH, Math.min(Model3D.MAX_PITCH, aM.pitch)));
		double[] aT = aM.getTarget();
		pos = Math3D.add(aT, Math3D.mul(new double[] {Math.cos(aPitch)*Math.sin(aYaw), Math.sin(aPitch), Math.cos(aPitch)*Math.cos(aYaw)}, dist));
		fwd = Math3D.normalize(Math3D.sub(aT, pos));
		right = Math3D.normalize(Math3D.cross(fwd, new double[] {0, 1, 0}));
		up = Math3D.cross(right, fwd);
		f = (aFrameH/2)/Math.tan(Math.toRadians(aM.fov/2));
		cx = aCx;
		cy = aCy;
		eyeShift = aEye*aM.depth*dist/2;
		eyePos = Math3D.add(pos, Math3D.mul(right, eyeShift));
	}

	/**
	 * Coordinates in the eye frame {x right, y up, z forward}
	 */
	public double[] toCam(double[] aP) {
		double aDx = aP[0]-eyePos[0], aDy = aP[1]-eyePos[1], aDz = aP[2]-eyePos[2];
		return new double[] {
				aDx*right[0] + aDy*right[1] + aDz*right[2],
				aDx*up[0] + aDy*up[1] + aDz*up[2],
				aDx*fwd[0] + aDy*fwd[1] + aDz*fwd[2]};
	}

	/**
	 * Screen point of a camera point (z > 0)
	 */
	public double screenX(double aX, double aZ) {
		return cx + f*(aX/aZ + eyeShift/dist);
	}

	public double screenY(double aY, double aZ) {
		return cy - f*aY/aZ;
	}

	/**
	 * {x, y, z} on the screen of a world point, null behind the camera
	 */
	public double[] project(double[] aP) {
		double[] aC = toCam(aP);
		if(aC[2] <= NEAR) {
			return null;
		}
		return new double[] {screenX(aC[0], aC[2]), screenY(aC[1], aC[2]), aC[2]};
	}

	/**
	 * World move of a screen move of (aDx, aDy) pixels at the depth aZ
	 */
	public double[] screenDelta(double aDx, double aDy, double aZ) {
		double aK = aZ/f;
		return Math3D.add(Math3D.mul(right, aDx*aK), Math3D.mul(up, -aDy*aK));
	}
}
