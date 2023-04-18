package com.cubaix.TDenlive.xml;

import java.util.HashMap;
import java.util.StringTokenizer;

public class XmlTag extends XmlObject {
	public String tagName = null;
	public HashMap<String,String> attrs = new HashMap<String,String>();
	
	public XmlTag(String aText) throws Exception {
		super(aText);
		parse();
	}
	
	public String getAttr(String aAttrName) {
		return attrs.get(aAttrName.toLowerCase());
	}
	
	void parse() throws Exception {
		StringTokenizer aST = new StringTokenizer(text,"[<> \t\n\r]");
		tagName = aST.nextToken().toLowerCase();
		parseAttrs(text.substring(text.toLowerCase().indexOf(tagName)+tagName.length()).trim());
	}

	void parseAttrs(String aAttrs) throws Exception {
		StringTokenizer aST = new StringTokenizer(aAttrs,"[<>=\"']",true);
		while(aST.hasMoreElements()) {
			String aTok = aST.nextToken().trim();
			if(aTok.isEmpty() || aTok.matches("[>\"']")) {
				continue;
			}
			String aAttrName = aTok;
			String aEqual = aST.nextToken().trim();
			while(aEqual.isEmpty()) {
				aEqual = aST.nextToken().trim();
			}
			if(!aEqual.equals("=")) {
				throw new Exception("No well-formed XML tag attr");
			}
			String aAttrValue = aST.nextToken();
			while(aAttrValue.isEmpty() || aAttrValue.matches("[\"']")) {
				aAttrValue = aST.nextToken().trim();
			}
			attrs.put(aAttrName.toLowerCase(), aAttrValue);
		}
	}
}
