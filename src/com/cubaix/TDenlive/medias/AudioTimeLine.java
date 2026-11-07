package com.cubaix.TDenlive.medias;

import java.awt.Graphics2D;
import java.util.Vector;

import org.eclipse.swt.graphics.Rectangle;

import com.cubaix.TDenlive.TDenlive;
import com.cubaix.TDenlive.xml.XmlObject;
import com.cubaix.TDenlive.xml.XmlTag;

/**
 * Time line of sounds: clips of audio medias, or the sound track of video medias.
 * Its clips are only mixed (see audio.AudioMixer), never rendered as images.
 * The active checkbox mutes / unmutes the whole time line.
 */
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
				if("isActive".equalsIgnoreCase(aT.tagName)) {
					o++;
					isActive = !"false".equalsIgnoreCase(aOs.elementAt(o).text);
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
		aSB.append("				<isActive>" + isActive + "</isActive>\n");
		for(Clip aTL : clipList) {
			aTL.saveProject(aSB);
		}
		aSB.append("			</AudioTimeLine>\n");
	}

	@Override
	protected Clip newClip(Media aMedia) {
		Clip aC = new Clip(tde, aMedia);
		aC.keepOnlyTimeController();
		return aC;
	}

	@Override
	protected Rectangle drawClipAwt(Clip aC, Graphics2D aGC, int aX, int aY, Rectangle aClippingR) {
		return aC.drawAudioAwt(aGC, aX, aY, aClippingR);
	}

	@Override
	public int getRowH() {
		//The whole line is the sound
		return tde.config.thumbH;
	}

	@Override
	protected boolean hasSoundIcon() {
		//The checkbox already mutes the line
		return false;
	}
}
