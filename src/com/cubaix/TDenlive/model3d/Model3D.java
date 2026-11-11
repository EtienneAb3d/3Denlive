package com.cubaix.TDenlive.model3d;

import java.util.Vector;

import com.cubaix.TDenlive.shapes.Shape;
import com.cubaix.TDenlive.xml.XmlObject;
import com.cubaix.TDenlive.xml.XmlTag;

/**
 * A 3D model: primitives, the camera that films it (orbit around a target, Y up) and its stereo settings.
 * The convergence (zero parallax) is at the target.
 */
public class Model3D {
	public String name = "";
	public Vector<Object3D> objects = new Vector<Object3D>();
	public double targetX = 0, targetY = 0.5, targetZ = 0;
	public double yaw = 35;//Degrees, around Y (0: camera on +Z)
	public double pitch = 25;//Degrees, above the horizon
	public double distance = 4;
	public double fov = 40;//Vertical field of view, degrees
	//Eye separation / distance to the target
	public double depth = 0.035;
	//Turntable: degrees per second around the vertical axis of the target
	public double spin = 0;
	//Last id given (see nextId())
	public int lastId = -1;

	public static final double MIN_PITCH = -89.5;
	public static final double MAX_PITCH = 89.5;

	public Model3D copy() {
		Model3D aM = new Model3D();
		aM.name = name;
		aM.objects = Object3D.copyAll(objects);
		aM.targetX = targetX; aM.targetY = targetY; aM.targetZ = targetZ;
		aM.yaw = yaw; aM.pitch = pitch; aM.distance = distance; aM.fov = fov;
		aM.depth = depth;
		aM.spin = spin;
		aM.lastId = lastId;
		return aM;
	}

	/**
	 * Same view as aM (target, angles, distance, field of view)
	 */
	public void copyCameraFrom(Model3D aM) {
		targetX = aM.targetX; targetY = aM.targetY; targetZ = aM.targetZ;
		yaw = aM.yaw; pitch = aM.pitch; distance = aM.distance; fov = aM.fov;
	}

	/**
	 * New id: never one of a deleted object (the keys of the clips keep the moves by id)
	 */
	public int nextId() {
		int aId = lastId+1;
		for(Object3D aO : objects) {
			aId = Math.max(aId, aO.id+1);
		}
		lastId = aId;
		return aId;
	}

	public Object3D findById(int aId) {
		for(Object3D aO : objects) {
			if(aO.id == aId) {
				return aO;
			}
		}
		return null;
	}

	public double[] getTarget() {
		return new double[] {targetX, targetY, targetZ};
	}

	public void setTarget(double[] aT) {
		targetX = aT[0]; targetY = aT[1]; targetZ = aT[2];
	}

	//
	// Hierarchy: Object3D.parent is the id of a group (-1 or unknown: root)
	//

	public Object3D parentOf(Object3D aO) {
		if(aO.parent < 0 || aO.parent == aO.id) {
			return null;
		}
		Object3D aP = findById(aO.parent);
		return aP != null && aP.isGroup() ? aP : null;
	}

	/**
	 * World transform {M (9) = R*S of the chain, t (3)} of a node
	 */
	public double[] world(Object3D aO) {
		double[] aM = aO.localMatrix();
		double[] aT = aO.getPosition();
		Object3D aP = parentOf(aO);
		for(int aDepth = 0;aP != null && aDepth < 64;aDepth++) {
			double[] aPM = aP.localMatrix();
			aT = Math3D.add(Math3D.apply(aPM, aT), aP.getPosition());
			aM = Math3D.mulM(aPM, aM);
			aP = parentOf(aP);
		}
		double[] aW = java.util.Arrays.copyOf(aM, 12);
		aW[9] = aT[0]; aW[10] = aT[1]; aW[11] = aT[2];
		return aW;
	}

	public static double[] matrixOf(double[] aWorld) {
		return java.util.Arrays.copyOf(aWorld, 9);
	}

	public static double[] translationOf(double[] aWorld) {
		return new double[] {aWorld[9], aWorld[10], aWorld[11]};
	}

	public double[] worldPosition(Object3D aO) {
		return translationOf(world(aO));
	}

	/**
	 * Sets the local transform of a node so that its world transform is (aM, aT)
	 */
	public void setWorld(Object3D aO, double[] aM, double[] aT) {
		Object3D aP = parentOf(aO);
		if(aP == null) {
			aO.setLocal(aM, aT);
			return;
		}
		double[] aPW = world(aP);
		double[] aInv = Math3D.invert(matrixOf(aPW));
		aO.setLocal(Math3D.mulM(aInv, aM), Math3D.apply(aInv, Math3D.sub(aT, translationOf(aPW))));
	}

	public Vector<Object3D> children(int aId) {
		Vector<Object3D> aCs = new Vector<Object3D>();
		for(Object3D aO : objects) {
			Object3D aP = parentOf(aO);
			if(aId < 0 ? aP == null : (aP != null && aP.id == aId)) {
				aCs.add(aO);
			}
		}
		return aCs;
	}

	/**
	 * True if aO is aAncestorId or below it
	 */
	public boolean isUnder(Object3D aO, int aAncestorId) {
		for(int aDepth = 0;aO != null && aDepth < 64;aDepth++) {
			if(aO.id == aAncestorId) {
				return true;
			}
			aO = parentOf(aO);
		}
		return false;
	}

	/**
	 * The node and all the nodes below it
	 */
	public Vector<Object3D> subtree(Object3D aRoot) {
		Vector<Object3D> aOs = new Vector<Object3D>();
		for(Object3D aO : objects) {
			if(isUnder(aO, aRoot.id)) {
				aOs.add(aO);
			}
		}
		return aOs;
	}

	/**
	 * The group of the root level that holds the node (the node itself at the root)
	 */
	public Object3D topLevel(Object3D aO) {
		for(int aDepth = 0;aDepth < 64;aDepth++) {
			Object3D aP = parentOf(aO);
			if(aP == null) {
				return aO;
			}
			aO = aP;
		}
		return aO;
	}

	/**
	 * Groups the nodes aIds (those under another of them stay under it) under a new group at their
	 * center, in the group of the first one if they all have the same; returns the group
	 */
	public Object3D group(Vector<Integer> aIds) {
		Vector<Object3D> aTops = new Vector<Object3D>();
		for(Object3D aO : objects) {
			if(!aIds.contains(aO.id)) {
				continue;
			}
			boolean aUnder = false;
			for(Integer aId : aIds) {
				if(aId != aO.id && isUnder(aO, aId)) {
					aUnder = true;
				}
			}
			if(!aUnder) {
				aTops.add(aO);
			}
		}
		if(aTops.isEmpty()) {
			return null;
		}
		int aParent = aTops.firstElement().parent;
		for(Object3D aO : aTops) {
			if(aO.parent != aParent) {
				aParent = -1;
			}
		}
		Object3D aG = new Object3D(Object3D.GROUP);
		aG.id = nextId();
		aG.parent = aParent;
		aG.color = aTops.firstElement().color;
		double[] aC = {0, 0, 0};
		double[][] aWs = new double[aTops.size()][];
		for(int i = 0;i < aTops.size();i++) {
			aWs[i] = world(aTops.elementAt(i));
			aC = Math3D.add(aC, translationOf(aWs[i]));
		}
		aC = Math3D.mul(aC, 1.0/aTops.size());
		objects.add(objects.indexOf(aTops.firstElement()), aG);
		//No rotation / scale of its own, at the center in the space of its parent
		Object3D aP = parentOf(aG);
		if(aP != null) {
			double[] aPW = world(aP);
			aG.setPosition(Math3D.apply(Math3D.invert(matrixOf(aPW)), Math3D.sub(aC, translationOf(aPW))));
		}
		else {
			aG.setPosition(aC);
		}
		for(int i = 0;i < aTops.size();i++) {
			Object3D aO = aTops.elementAt(i);
			aO.parent = aG.id;
			setWorld(aO, matrixOf(aWs[i]), translationOf(aWs[i]));
		}
		return aG;
	}

	/**
	 * Removes a group, its children go to its parent at the same place; returns them
	 */
	public Vector<Object3D> ungroup(Object3D aG) {
		Vector<Object3D> aCs = children(aG.id);
		double[][] aWs = new double[aCs.size()][];
		for(int i = 0;i < aCs.size();i++) {
			aWs[i] = world(aCs.elementAt(i));
		}
		for(int i = 0;i < aCs.size();i++) {
			Object3D aO = aCs.elementAt(i);
			aO.parent = aG.parent;
		}
		int aIdx = objects.indexOf(aG);
		objects.remove(aG);
		for(int i = 0;i < aCs.size();i++) {
			setWorld(aCs.elementAt(i), matrixOf(aWs[i]), translationOf(aWs[i]));
			//At the place of the group in the list
			objects.remove(aCs.elementAt(i));
			objects.add(Math.min(objects.size(), Math.max(0, aIdx+i)), aCs.elementAt(i));
		}
		return aCs;
	}

	/**
	 * Copy of a node and of its subtree (new ids), in the same group; returns the copy of the node
	 */
	public Object3D duplicate(Object3D aRoot) {
		Vector<Object3D> aSub = subtree(aRoot);
		java.util.HashMap<Integer,Integer> aIds = new java.util.HashMap<Integer,Integer>();
		for(Object3D aO : aSub) {
			aIds.put(aO.id, nextId());
		}
		Object3D aCopyRoot = null;
		for(Object3D aO : aSub) {
			Object3D aC = aO.copy();
			aC.id = aIds.get(aO.id);
			if(aO != aRoot) {
				aC.parent = aIds.get(aO.parent);
			}
			else {
				aCopyRoot = aC;
			}
			objects.add(aC);
		}
		return aCopyRoot;
	}

	/**
	 * Removes nodes and the nodes below them
	 */
	public boolean remove(java.util.Collection<Integer> aIds) {
		Vector<Object3D> aGone = new Vector<Object3D>();
		for(Object3D aO : objects) {
			for(Integer aId : aIds) {
				if(isUnder(aO, aId)) {
					aGone.add(aO);
					break;
				}
			}
		}
		return objects.removeAll(aGone);
	}

	/**
	 * World bounds of some nodes and of their subtrees (all if null), null if none
	 */
	public double[] bounds(Vector<Object3D> aObjects) {
		double[] aB = null;
		for(Object3D aO : objects) {
			boolean aIn = aObjects == null;
			if(!aIn) {
				for(Object3D aR : aObjects) {
					if(isUnder(aO, aR.id)) {
						aIn = true;
						break;
					}
				}
			}
			if(aIn) {
				double[] aW = world(aO);
				aB = aO.bounds(aB, matrixOf(aW), translationOf(aW));
			}
		}
		return aB;
	}

	/**
	 * Camera target and distance so that the objects (all if null) fill the frame of ratio aRatio
	 */
	public void frame(Vector<Object3D> aObjects, double aRatio) {
		double[] aB = bounds(aObjects);
		if(aB == null) {
			return;
		}
		setTarget(new double[] {(aB[0]+aB[3])/2, (aB[1]+aB[4])/2, (aB[2]+aB[5])/2});
		double aR = Math.max(0.05, Math3D.length(new double[] {aB[3]-aB[0], aB[4]-aB[1], aB[5]-aB[2]})/2);
		double aHalfV = Math.toRadians(fov/2);
		double aHalfH = Math.atan(Math.tan(aHalfV)*Math.max(0.1, aRatio));
		distance = aR/Math.sin(Math.min(aHalfV, aHalfH));
	}

	public void saveProject(StringBuffer aSB, String aIndent) {
		aSB.append(aIndent+"<Model3D>\n");
		aSB.append(aIndent+"	<name>" + Shape.encode(name) + "</name>\n");
		aSB.append(aIndent+"	<target>" + targetX + ";" + targetY + ";" + targetZ + "</target>\n");
		aSB.append(aIndent+"	<yaw>" + yaw + "</yaw>\n");
		aSB.append(aIndent+"	<pitch>" + pitch + "</pitch>\n");
		aSB.append(aIndent+"	<distance>" + distance + "</distance>\n");
		aSB.append(aIndent+"	<fov>" + fov + "</fov>\n");
		aSB.append(aIndent+"	<depth>" + depth + "</depth>\n");
		aSB.append(aIndent+"	<spin>" + spin + "</spin>\n");
		aSB.append(aIndent+"	<lastId>" + lastId + "</lastId>\n");
		for(Object3D aO : objects) {
			aO.saveProject(aSB, aIndent+"	");
		}
		aSB.append(aIndent+"</Model3D>\n");
	}

	public String toXml() {
		StringBuffer aSB = new StringBuffer();
		saveProject(aSB, "");
		return aSB.toString();
	}

	/**
	 * Reads from the <Model3D> tag at index o, returns the index of </Model3D>
	 */
	public int openProject(Vector<XmlObject> aOs, int o) throws Exception {
		objects.clear();
		for(o++;o < aOs.size();o++) {
			XmlObject aO = aOs.elementAt(o);
			if(!(aO instanceof XmlTag)) {
				continue;
			}
			XmlTag aT = (XmlTag)aO;
			if("/Model3D".equalsIgnoreCase(aT.tagName)) {
				return o;
			}
			if("name".equalsIgnoreCase(aT.tagName)) {
				name = Shape.decode(aOs.elementAt(++o).text.trim());
			}
			if("target".equalsIgnoreCase(aT.tagName)) {
				setTarget(Object3D.parse3(aOs.elementAt(++o).text));
			}
			if("yaw".equalsIgnoreCase(aT.tagName)) {
				yaw = Double.parseDouble(aOs.elementAt(++o).text.trim());
			}
			if("pitch".equalsIgnoreCase(aT.tagName)) {
				pitch = Double.parseDouble(aOs.elementAt(++o).text.trim());
			}
			if("distance".equalsIgnoreCase(aT.tagName)) {
				distance = Double.parseDouble(aOs.elementAt(++o).text.trim());
			}
			if("fov".equalsIgnoreCase(aT.tagName)) {
				fov = Double.parseDouble(aOs.elementAt(++o).text.trim());
			}
			if("depth".equalsIgnoreCase(aT.tagName)) {
				depth = Double.parseDouble(aOs.elementAt(++o).text.trim());
			}
			if("spin".equalsIgnoreCase(aT.tagName)) {
				spin = Double.parseDouble(aOs.elementAt(++o).text.trim());
			}
			if("lastId".equalsIgnoreCase(aT.tagName)) {
				lastId = Integer.parseInt(aOs.elementAt(++o).text.trim());
			}
			if("Object3D".equalsIgnoreCase(aT.tagName)) {
				Object3D aOb = new Object3D();
				o = aOb.openProject(aOs, o);
				objects.add(aOb);
			}
		}
		return o;
	}
}
