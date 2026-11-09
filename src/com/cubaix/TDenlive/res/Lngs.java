package com.cubaix.TDenlive.res;

import java.io.InputStream;
import java.util.HashMap;
import java.util.Vector;

import com.cubaix.TDenlive.TDenlive;
import com.cubaix.TDenlive.xml.XmlMinimalParser;
import com.cubaix.TDenlive.xml.XmlObject;
import com.cubaix.TDenlive.xml.XmlTag;

public class Lngs {
	//Interface languages: code (name of the XML file), name shown in its own language
	public static final String[][] LANGUAGES = new String[][] {
		new String[] {"EN", "English"}
		,new String[] {"FR", "Français"}
	};
	static final HashMap<String,String> LNGS = new HashMap<String,String>();
	static {
		//Load all to be sure all is parsable
		for(String[] aL : LANGUAGES) {
			try {
				load(aL[0]);
			}
			catch(Throwable t) {
				t.printStackTrace(System.err);
			}
		}
	}
	
	TDenlive tde = null;
	public Lngs(TDenlive aTDe) {
		tde = aTDe;
	}
	
	public String get(String aLabel) {
		String aValue = LNGS.get(tde.config.lng.toUpperCase()+"."+aLabel);
		if(aValue != null) {
			return aValue;
		}
		//EN by default
		aValue = LNGS.get("EN."+aLabel);
		if(aValue != null) {
			return "(EN) "+ aValue;
		}
		//FR if not EN
		aValue = LNGS.get("FR."+aLabel);
		if(aValue != null) {
			return "(FR) "+ aValue;
		}
		return "(??)"+aLabel;
	}
	
	/**
	 * A text got in the language aFromLng (e.g. a processor name), in the current language.
	 * Returned unchanged if it is not a text of aFromLng.
	 */
	public String translate(String aText,String aFromLng) {
		if(aText == null || aFromLng == null) {
			return aText;
		}
		String aPrefix = aFromLng.toUpperCase()+".";
		for(java.util.Map.Entry<String,String> aE : LNGS.entrySet()) {
			if(aE.getKey().startsWith(aPrefix) && aText.equals(aE.getValue())) {
				return get(aE.getKey().substring(aPrefix.length()));
			}
		}
		return aText;
	}

	static final void load(String aLng) throws Exception {
		XmlMinimalParser aP = new XmlMinimalParser();
		InputStream aIS = Lngs.class.getResourceAsStream("lngs/"+aLng.toUpperCase()+".xml");
		Vector<XmlObject> aOs = aP.parse(aIS);
		aIS.close();
		parse(aLng, aOs, 0);
	}
	
	static final int parse(String aLng,Vector<XmlObject> aOs,int aPos) {
		while((aPos < aOs.size())) {
			XmlObject aO = aOs.elementAt(aPos);
			if(aO instanceof XmlTag) {
				XmlTag aT = (XmlTag)aO;
				if(aT.tagName.matches("/.*")) {
					return aPos+1;
				}
				aPos = parse(aLng+"."+aT.getAttr("label"), aOs, aPos+1);
				continue;
			}
			LNGS.put(aLng, aO.text.replaceAll("[\\\\]n", "\n")
					.replaceAll("&lt;", "<")
					.replaceAll("&gt;", ">"));
			System.out.println(aLng+"="+aO.text);
			aPos++;
		}
		return aPos+1;
	}
}
