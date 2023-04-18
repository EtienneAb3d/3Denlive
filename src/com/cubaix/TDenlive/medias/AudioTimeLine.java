package com.cubaix.TDenlive.medias;

import java.util.Vector;

import com.cubaix.TDenlive.TDenlive;
import com.cubaix.TDenlive.xml.XmlObject;
import com.cubaix.TDenlive.xml.XmlTag;

public class AudioTimeLine extends VideoTimeLine {

	public AudioTimeLine(TDenlive aTDe, String aName) {
		super(aTDe, aName);
	}

	public int openProject(Vector<XmlObject> aOs,int o) throws Exception{
		for(;o < aOs.size();o++) {
			XmlObject aO = aOs.elementAt(o);
			if(aO instanceof XmlTag) {
				XmlTag aT = (XmlTag)aO;
				if("/AudioTimeLine".equalsIgnoreCase(aT.tagName)) {
					return o;
				}
				if("Clip".equalsIgnoreCase(aT.tagName)) {
					Clip aC = new Clip(tde, null);
					clipList.add(aC);
					o = aC.openProject(aOs, o);
				}
			}
		}
		return o;
	}

	@Override
	public void saveProject(StringBuffer aSB) throws Exception {
		aSB.append("			<AudioTimeLine>\n");
		for(Clip aTL : clipList) {
			aTL.saveProject(aSB);
		}
		aSB.append("			</AudioTimeLine>\n");
	}
}
