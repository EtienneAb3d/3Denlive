package com.cubaix.TDenlive.xml;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.util.StringTokenizer;
import java.util.Vector;

public class XmlMinimalParser {

	public XmlMinimalParser() {
	}

	public Vector<XmlObject> parse(String aPath) throws Exception {
		FileInputStream aFIS = new FileInputStream(new File(aPath));
		Vector<XmlObject> aOs = parse(aFIS);
		aFIS.close();
		return aOs;
	}
	
	public Vector<XmlObject> parse(InputStream aIS) throws Exception {
		StringBuffer aSB = new StringBuffer();
		byte[] aBuf = new byte[1024*1024];
		int aLen = 0;
		while((aLen = aIS.read(aBuf)) > 0){
			aSB.append(new String(aBuf,0,aLen,"utf-8"));
		}
		return parseXml(aSB.toString());
	}

	public Vector<XmlObject> parseXml(String aXml) throws Exception {
		Vector<XmlObject> aOs = new Vector<XmlObject>();
		
		StringTokenizer aST = new StringTokenizer(aXml, "[<>]",true);
		while(aST.hasMoreElements()) {
			String aTok = aST.nextToken().trim();
			if(aTok.isEmpty()) {
				continue;
			}
			if(aTok.equals("<")) {
				aOs.add(new XmlTag("<"+aST.nextToken().trim()+">"));
				if(!aST.nextToken().equals(">")) {
					throw new Exception("Not well-formed XML tag");
				}
				continue;
			}
			aOs.add(new XmlObject(aTok.trim()));
		}
		
		return aOs;
	}

}
