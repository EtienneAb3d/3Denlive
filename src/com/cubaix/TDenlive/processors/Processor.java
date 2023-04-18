package com.cubaix.TDenlive.processors;

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

}
