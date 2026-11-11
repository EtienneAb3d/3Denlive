package com.cubaix.TDenlive.model3d;

/**
 * Small vector / 3x3 matrix helpers (matrices are row-major double[9])
 */
public class Math3D {
	public static double[] v(double aX, double aY, double aZ) {
		return new double[] {aX, aY, aZ};
	}

	public static double[] add(double[] aA, double[] aB) {
		return new double[] {aA[0]+aB[0], aA[1]+aB[1], aA[2]+aB[2]};
	}

	public static double[] sub(double[] aA, double[] aB) {
		return new double[] {aA[0]-aB[0], aA[1]-aB[1], aA[2]-aB[2]};
	}

	public static double[] mul(double[] aA, double aK) {
		return new double[] {aA[0]*aK, aA[1]*aK, aA[2]*aK};
	}

	public static double dot(double[] aA, double[] aB) {
		return aA[0]*aB[0] + aA[1]*aB[1] + aA[2]*aB[2];
	}

	public static double[] cross(double[] aA, double[] aB) {
		return new double[] {aA[1]*aB[2]-aA[2]*aB[1], aA[2]*aB[0]-aA[0]*aB[2], aA[0]*aB[1]-aA[1]*aB[0]};
	}

	public static double length(double[] aA) {
		return Math.sqrt(dot(aA, aA));
	}

	public static double[] normalize(double[] aA) {
		double aL = length(aA);
		return aL < 1e-12 ? new double[] {0, 0, 0} : mul(aA, 1/aL);
	}

	public static double[] identity() {
		return new double[] {1,0,0, 0,1,0, 0,0,1};
	}

	public static double[] mulM(double[] aA, double[] aB) {
		double[] aR = new double[9];
		for(int r = 0;r < 3;r++) {
			for(int c = 0;c < 3;c++) {
				aR[r*3+c] = aA[r*3]*aB[c] + aA[r*3+1]*aB[3+c] + aA[r*3+2]*aB[6+c];
			}
		}
		return aR;
	}

	public static double[] apply(double[] aM, double[] aV) {
		return new double[] {
				aM[0]*aV[0] + aM[1]*aV[1] + aM[2]*aV[2],
				aM[3]*aV[0] + aM[4]*aV[1] + aM[5]*aV[2],
				aM[6]*aV[0] + aM[7]*aV[1] + aM[8]*aV[2]};
	}

	/**
	 * Column aC of a matrix (for a rotation: the local axis aC in the world)
	 */
	public static double[] column(double[] aM, int aC) {
		return new double[] {aM[aC], aM[3+aC], aM[6+aC]};
	}

	/**
	 * Rotation of aAngle radians around the axis aAxis (right hand)
	 */
	public static double[] rotation(double[] aAxis, double aAngle) {
		double[] aU = normalize(aAxis);
		double aC = Math.cos(aAngle);
		double aS = Math.sin(aAngle);
		double aT = 1-aC;
		double aX = aU[0], aY = aU[1], aZ = aU[2];
		return new double[] {
				aT*aX*aX+aC, aT*aX*aY-aS*aZ, aT*aX*aZ+aS*aY,
				aT*aX*aY+aS*aZ, aT*aY*aY+aC, aT*aY*aZ-aS*aX,
				aT*aX*aZ-aS*aY, aT*aY*aZ+aS*aX, aT*aZ*aZ+aC};
	}

	/**
	 * R = Rz * Ry * Rx (angles in degrees): X applied first
	 */
	public static double[] euler(double aRx, double aRy, double aRz) {
		double aCx = Math.cos(Math.toRadians(aRx)), aSx = Math.sin(Math.toRadians(aRx));
		double aCy = Math.cos(Math.toRadians(aRy)), aSy = Math.sin(Math.toRadians(aRy));
		double aCz = Math.cos(Math.toRadians(aRz)), aSz = Math.sin(Math.toRadians(aRz));
		return new double[] {
				aCz*aCy, aCz*aSy*aSx - aSz*aCx, aCz*aSy*aCx + aSz*aSx,
				aSz*aCy, aSz*aSy*aSx + aCz*aCx, aSz*aSy*aCx - aCz*aSx,
				-aSy, aCy*aSx, aCy*aCx};
	}

	/**
	 * Inverse of euler(): {rx, ry, rz} in degrees
	 */
	public static double[] toEuler(double[] aM) {
		double aR20 = Math.max(-1, Math.min(1, aM[6]));
		double aRy = Math.asin(-aR20);
		double aRx, aRz;
		if(Math.abs(aR20) < 0.99999) {
			aRx = Math.atan2(aM[7], aM[8]);
			aRz = Math.atan2(aM[3], aM[0]);
		}
		else {
			aRz = 0;
			aRx = Math.atan2(-aM[5], aM[4]);
		}
		return new double[] {clean(Math.toDegrees(aRx)), clean(Math.toDegrees(aRy)), clean(Math.toDegrees(aRz))};
	}

	public static double[] scaleM(double[] aM, double aK) {
		double[] aR = new double[9];
		for(int i = 0;i < 9;i++) {
			aR[i] = aM[i]*aK;
		}
		return aR;
	}

	public static double[] transpose(double[] aM) {
		return new double[] {aM[0], aM[3], aM[6], aM[1], aM[4], aM[7], aM[2], aM[5], aM[8]};
	}

	/**
	 * Inverse of a 3x3 matrix (identity if singular)
	 */
	public static double[] invert(double[] aM) {
		double aA = aM[0], aB = aM[1], aC = aM[2], aD = aM[3], aE = aM[4], aF = aM[5], aG = aM[6], aH = aM[7], aI = aM[8];
		double aDet = aA*(aE*aI-aF*aH) - aB*(aD*aI-aF*aG) + aC*(aD*aH-aE*aG);
		if(Math.abs(aDet) < 1e-18) {
			return identity();
		}
		double aK = 1/aDet;
		return new double[] {
				(aE*aI-aF*aH)*aK, (aC*aH-aB*aI)*aK, (aB*aF-aC*aE)*aK,
				(aF*aG-aD*aI)*aK, (aA*aI-aC*aG)*aK, (aC*aD-aA*aF)*aK,
				(aD*aH-aE*aG)*aK, (aB*aG-aA*aH)*aK, (aA*aE-aB*aD)*aK};
	}

	/**
	 * R * diag(aS)
	 */
	public static double[] rotScale(double[] aR, double[] aS) {
		return new double[] {
				aR[0]*aS[0], aR[1]*aS[1], aR[2]*aS[2],
				aR[3]*aS[0], aR[4]*aS[1], aR[5]*aS[2],
				aR[6]*aS[0], aR[7]*aS[1], aR[8]*aS[2]};
	}

	/**
	 * Splits M ≈ R * diag(S): scales = lengths of the columns, R = orthonormalized columns
	 * (a shear is lost). Returns {R (9), S (3)} as one array of 12
	 */
	public static double[] decompose(double[] aM) {
		double[] aC0 = column(aM, 0), aC1 = column(aM, 1), aC2 = column(aM, 2);
		double aS0 = length(aC0), aS1 = length(aC1), aS2 = length(aC2);
		double[] aX = aS0 < 1e-12 ? new double[] {1, 0, 0} : normalize(aC0);
		double[] aY = sub(aC1, mul(aX, dot(aX, aC1)));
		if(length(aY) < 1e-12) {
			//Degenerate: any axis perpendicular to X
			aY = cross(Math.abs(aX[2]) < 0.9 ? new double[] {0, 0, 1} : new double[] {1, 0, 0}, aX);
		}
		aY = normalize(aY);
		double[] aZ = cross(aX, aY);
		if(dot(aZ, aC2) < 0) {
			//Mirror: the sign goes to the last scale
			aS2 = -aS2;
		}
		return new double[] {aX[0], aY[0], aZ[0], aX[1], aY[1], aZ[1], aX[2], aY[2], aZ[2], aS0, aS1, aS2};
	}

	/**
	 * Quaternion {w, x, y, z} of a rotation matrix
	 */
	public static double[] toQuat(double[] aM) {
		double aTr = aM[0]+aM[4]+aM[8];
		double aW, aX, aY, aZ;
		if(aTr > 0) {
			double aS = Math.sqrt(aTr+1)*2;
			aW = 0.25*aS; aX = (aM[7]-aM[5])/aS; aY = (aM[2]-aM[6])/aS; aZ = (aM[3]-aM[1])/aS;
		}
		else if(aM[0] > aM[4] && aM[0] > aM[8]) {
			double aS = Math.sqrt(1+aM[0]-aM[4]-aM[8])*2;
			aW = (aM[7]-aM[5])/aS; aX = 0.25*aS; aY = (aM[1]+aM[3])/aS; aZ = (aM[2]+aM[6])/aS;
		}
		else if(aM[4] > aM[8]) {
			double aS = Math.sqrt(1+aM[4]-aM[0]-aM[8])*2;
			aW = (aM[2]-aM[6])/aS; aX = (aM[1]+aM[3])/aS; aY = 0.25*aS; aZ = (aM[5]+aM[7])/aS;
		}
		else {
			double aS = Math.sqrt(1+aM[8]-aM[0]-aM[4])*2;
			aW = (aM[3]-aM[1])/aS; aX = (aM[2]+aM[6])/aS; aY = (aM[5]+aM[7])/aS; aZ = 0.25*aS;
		}
		double aL = Math.sqrt(aW*aW+aX*aX+aY*aY+aZ*aZ);
		return new double[] {aW/aL, aX/aL, aY/aL, aZ/aL};
	}

	public static double[] fromQuat(double[] aQ) {
		double aW = aQ[0], aX = aQ[1], aY = aQ[2], aZ = aQ[3];
		return new double[] {
				1-2*(aY*aY+aZ*aZ), 2*(aX*aY-aZ*aW), 2*(aX*aZ+aY*aW),
				2*(aX*aY+aZ*aW), 1-2*(aX*aX+aZ*aZ), 2*(aY*aZ-aX*aW),
				2*(aX*aZ-aY*aW), 2*(aY*aZ+aX*aW), 1-2*(aX*aX+aY*aY)};
	}

	/**
	 * Spherical interpolation of two quaternions (shortest way)
	 */
	public static double[] slerp(double[] aA, double[] aB, double aT) {
		double aDot = aA[0]*aB[0]+aA[1]*aB[1]+aA[2]*aB[2]+aA[3]*aB[3];
		double[] aB2 = aB;
		if(aDot < 0) {
			aDot = -aDot;
			aB2 = new double[] {-aB[0], -aB[1], -aB[2], -aB[3]};
		}
		double aK0, aK1;
		if(aDot > 0.9995) {
			aK0 = 1-aT;
			aK1 = aT;
		}
		else {
			double aTh = Math.acos(aDot);
			double aS = Math.sin(aTh);
			aK0 = Math.sin((1-aT)*aTh)/aS;
			aK1 = Math.sin(aT*aTh)/aS;
		}
		double[] aR = {aA[0]*aK0+aB2[0]*aK1, aA[1]*aK0+aB2[1]*aK1, aA[2]*aK0+aB2[2]*aK1, aA[3]*aK0+aB2[3]*aK1};
		double aL = Math.sqrt(aR[0]*aR[0]+aR[1]*aR[1]+aR[2]*aR[2]+aR[3]*aR[3]);
		return new double[] {aR[0]/aL, aR[1]/aL, aR[2]/aL, aR[3]/aL};
	}

	//Removes the rounding noise (e.g. 89.99999999 → 90)
	static double clean(double aV) {
		double aR = Math.round(aV*1e6)/1e6;
		return aR == -0.0 ? 0 : aR;
	}
}
