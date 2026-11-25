package com.cubaix.TDenlive.model3d;

/**
 * Lamp of a model (Model3D.lampMode, lampYaw, lampPitch, lampDistance), as held by the keys of a 3D clip
 * (processors.Objects3D): tied to the camera, or a point of the scene placed as the camera (angles around the target)
 */
public class Lamp {
	public int mode = Model3D.LAMP_CAMERA;
	public double yaw = 0, pitch = 50, distance = 6;

	public Lamp copy() {
		Lamp aL = new Lamp();
		aL.mode = mode;
		aL.yaw = yaw;
		aL.pitch = pitch;
		aL.distance = distance;
		return aL;
	}

	public static Lamp of(Model3D aM) {
		Lamp aL = new Lamp();
		aL.mode = aM.lampMode;
		aL.yaw = aM.lampYaw;
		aL.pitch = aM.lampPitch;
		aL.distance = aM.lampDistance;
		return aL;
	}

	public void applyTo(Model3D aM) {
		aM.lampMode = mode;
		aM.lampYaw = yaw;
		aM.lampPitch = pitch;
		aM.lampDistance = distance;
	}

	/**
	 * The same lighting: both tied to the camera, or at the same place of the scene
	 */
	public boolean same(Lamp aL) {
		if(mode != aL.mode) {
			return false;
		}
		if(mode == Model3D.LAMP_CAMERA) {
			return true;
		}
		return Math.abs(distance-aL.distance) < 1e-9 && Math.abs(pitch-aL.pitch) < 1e-9
				&& Math.abs(((yaw-aL.yaw)%360+540)%360-180) < 1e-9;
	}

	/**
	 * Linear, the yaw by the shortest way, the distance logarithmic (as the camera); of different modes: aA (no
	 * way between a lamp tied to the camera and one of the scene)
	 */
	public static Lamp lerp(Lamp aA, Lamp aB, double aF) {
		if(aA.mode != aB.mode) {
			return aA.copy();
		}
		Lamp aL = new Lamp();
		aL.mode = aA.mode;
		double aDYaw = ((aB.yaw-aA.yaw)%360+540)%360-180;
		aL.yaw = aA.yaw+aDYaw*aF;
		aL.pitch = aA.pitch*(1-aF)+aB.pitch*aF;
		aL.distance = aA.distance > 0 && aB.distance > 0 ? Math.exp(Math.log(aA.distance)*(1-aF)+Math.log(aB.distance)*aF)
				: aA.distance*(1-aF)+aB.distance*aF;
		return aL;
	}

	public String save() {
		return mode+";"+yaw+";"+pitch+";"+distance;
	}

	public static Lamp load(String aText) {
		String[] aVs = aText.trim().split(";");
		Lamp aL = new Lamp();
		aL.mode = Integer.parseInt(aVs[0]);
		aL.yaw = Double.parseDouble(aVs[1]);
		aL.pitch = Double.parseDouble(aVs[2]);
		aL.distance = Double.parseDouble(aVs[3]);
		return aL;
	}
}
