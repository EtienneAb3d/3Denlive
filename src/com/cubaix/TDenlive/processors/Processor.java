package com.cubaix.TDenlive.processors;

import java.awt.geom.AffineTransform;
import java.io.BufferedWriter;
import java.util.Vector;

import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.graphics.Rectangle;

import com.cubaix.TDenlive.TDenlive;
import com.cubaix.TDenlive.medias.Media;
import com.cubaix.TDenlive.xml.XmlObject;

public abstract class Processor {
	public Media targetMedia = null;
	public String name = null;
	public String icon = null;
	public boolean isDeactivable = true;
	public boolean isActive = true;
	public boolean isExpended = false;
	public boolean isWarning = false;
	public boolean isUndeletable = false;
	public boolean isUnmovable = false;

	public TDenlive tde = null;

	public Processor(TDenlive aTDe,Media aTargetMedia) {
		tde = aTDe;
		targetMedia = aTargetMedia;
		setDescr();
	}

	/**
	 * Called when the processor is removed from its clip (to release what it gave the clip)
	 */
	public void removed() {}
	
	public abstract String getClassName();
	public abstract int openProject(Vector<XmlObject> aOs,int o) throws Exception;
	public abstract void saveProject(StringBuffer aSB) throws Exception;
	abstract void setDescr();
	public int getNbKeys() {return -1;}
	public int addKeyGT(long aTime){return -1;}
	public void deleteKey(int aKey) {}
	public int getPrevKeyGT(long aTime){return -1;}
	public int getNextKeyGT(long aTime){return -1;}
	public long getTime(int aKey) {return -1;}
	public abstract Image process2Swt(Media aMedia,int aProcessingMode,Rectangle aTargetSize,long aTime);
	public abstract void process2Awt(Media aMedia,int aProcessingMode,long aTime);
	/**
	 * Sound processing, for the processors of the audio clips.
	 * aBuf holds aNbFrames interleaved stereo samples (L R, -1..1) of the clip,
	 * the first one at aTimeMS on the time line. Default: no effect.
	 */
	public void processAudio(Media aMedia,float[] aBuf,int aNbFrames,long aTimeMS,double aFrameMS) {}
	/**
	 * True for the processors working on the sound (only for the audio clips)
	 */
	public boolean isAudio() {return false;}
	/**
	 * Geometry of the image made by this processor (to show and edit the shapes of the clip
	 * where they are displayed): transform from the pixels of its input image (aInW x aInH)
	 * to the pixels of its output image, whose size is put in aOutSize, for the eye aEye
	 * (-1 left, 1 right) at aTime. By default the image is not moved (colors, masks,
	 * deformations that are not affine).
	 */
	public AffineTransform getGeometry(int aEye,long aTime,int aProcessingMode,int aInW,int aInH,int[] aOutSize) {
		aOutSize[0] = aInW;
		aOutSize[1] = aInH;
		return new AffineTransform();
	}

}
