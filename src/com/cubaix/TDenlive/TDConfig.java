package com.cubaix.TDenlive;

import java.io.BufferedWriter;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.util.Vector;

import javax.sound.midi.Patch;

import com.cubaix.TDenlive.xml.XmlMinimalParser;
import com.cubaix.TDenlive.xml.XmlObject;
import com.cubaix.TDenlive.xml.XmlTag;

public class TDConfig {
	final public static int PROCESSING_MODE_WORK = 0;
	final public static int PROCESSING_MODE_FULLSCREEN = 1;
	final public static int PROCESSING_MODE_RENDER = 2;
	final public static int PROCESSING_MODE_AUTOALIGN = 3;
	
	final public static int RATIO_FIRSTMEDIA = 0;
	final public static int RATIO_1_1 = 1;
	final public static int RATIO_4_3 = 2;
	final public static int RATIO_16_9 = 3;
	final public static String[] RATIONAMES = new String[] {
			"1M"//1st media
			,"1:1","4:3","16:9"};
	final public static String[] RATIOSHORTNAMES = new String[] {
			"1M"//1st media
			,"1:1","4:3","16:9"};
	final public static double[] RATIOVALUES = new double[] {
			16.0/9.0//1st media
			,1.0,4.0/3.0,16.0/9.0};

	final public static int RES_FIRSTMEDIA = 0;
	final public static int RES_VHS = 1;
	final public static int RES_SD = 2;
	final public static int RES_DVD = 3;
	final public static int RES_HD = 4;
	final public static int RES_FULLHD = 5;
	final public static int RES_QHD = 6;
	final public static int RES_4K = 7;
	final public static int RES_8K = 8;
	final public static String[] RESNAMES = new String[]{
			"1M"//1st media
			,"288 (VHS)","480 (SD)","576 (DVD)","720 (HD)","1080 (FullHD)","1440 (QHD)","2160 (4K)","4320 (8K)"};
	final public static String[] RESSHORTNAMES = new String[]{
			"1M"//1st media
			,"288","480","576","720","1080","1440","2160","4320"};
	final public static int[] RESVALUES = new int[]{
			720//1st media
			,288,480,576,720,1080,1440,2160,4320};
	final public static String[] HDRLIST = new String[]{"STD","HDR"};
	final public static String[] FPSLIST = new String[] {"25","30","50","60"};
	
	public String lng = "FR";
	
	public String renderDir = "";
	
	public int fontSize = 8;
	public int sashWith = 2;
	public int thumbW = 80;//160/2
	public int thumbH = 45;//90/2
	
	public boolean fastMode = false;
	
	public boolean[] processingHdr = new boolean[] {
			false//WORK
			,false//FULLSCREEN
			,false//RENDER
			,false//ALIGN
	};
	public String[] processingResNames = new String[] {
			RESSHORTNAMES[RES_HD]//WORK HD
			,RESSHORTNAMES[RES_FULLHD]//FULLSCREEN FullHD
			,RESSHORTNAMES[RES_4K]//RENDER 4K
			,"VARIABLE"//ALIGN
	};
	public int[] processingResValues = new int[] {
			//SD=576, HD=720, FullHD=1080, QuadHD=1440, UHD=2160, 8K=4320
			RESVALUES[RES_HD]//WORK HD
			,RESVALUES[RES_FULLHD]//FULLSCREEN FullHD
			,RESVALUES[RES_4K]//RENDER 4K
			,RESVALUES[RES_HD]//ALIGN
	};
	public String outRatioName = RATIONAMES[RATIO_16_9];
	public double origRatio = RATIOVALUES[RATIO_16_9];
	public double outRatio = RATIOVALUES[RATIO_16_9];
	public double outFps = 30;
	
	public int renderedDefaultRes = 5;//FullHD
	
	public void loadGeneral(String aPath) {
		try {
			XmlMinimalParser aXMP = new XmlMinimalParser();
			Vector<XmlObject> aOs = aXMP.parse(aPath);
			for(int o = 0;o < aOs.size();o++) {
				XmlObject aO = aOs.elementAt(o);
				if(aO instanceof XmlTag) {
					XmlTag aT = (XmlTag)aO;
					if("lng".equalsIgnoreCase(aT.tagName)) {
						o++;
						lng = aOs.elementAt(o).text;
						continue;
					}
					if("renderDir".equalsIgnoreCase(aT.tagName)) {
						o++;
						if(!(aOs.elementAt(o) instanceof XmlTag)) {//May be empty
							renderDir = aOs.elementAt(o).text;
						}
						continue;
					}
					if("fastMode".equalsIgnoreCase(aT.tagName)) {
						o++;
						fastMode = "true".equalsIgnoreCase(aOs.elementAt(o).text);
						continue;
					}
					if("processingHdr".equalsIgnoreCase(aT.tagName)) {
						o++;
						String[] aVs = aOs.elementAt(o).text.split("\t");
						processingHdr[0] = "true".equalsIgnoreCase(aVs[0]);
						processingHdr[1] = "true".equalsIgnoreCase(aVs[1]);
						processingHdr[2] = "true".equalsIgnoreCase(aVs[2]);
						continue;
					}
					if("processingResNames".equalsIgnoreCase(aT.tagName)) {
						o++;
						String[] aVs = aOs.elementAt(o).text.split("\t");
						processingResNames[0] = aVs[0];
						processingResNames[1] = aVs[1];
						processingResNames[2] = aVs[2];
						continue;
					}
					if("processingResValues".equalsIgnoreCase(aT.tagName)) {
						o++;
						String[] aVs = aOs.elementAt(o).text.split("\t");
						processingResValues[0] = Integer.parseInt(aVs[0]);
						processingResValues[1] = Integer.parseInt(aVs[1]);
						processingResValues[2] = Integer.parseInt(aVs[2]);
						continue;
					}
					if("outRatio".equalsIgnoreCase(aT.tagName)) {
						o++;
						outRatio = Double.parseDouble(aOs.elementAt(o).text);
						continue;
					}
					if("outRatioName".equalsIgnoreCase(aT.tagName)) {
						o++;
						outRatioName = aOs.elementAt(o).text;
						continue;
					}
				}
			}
			//Bug fix
			if(processingResNames[0].matches("[0-9]+")) {
				processingResValues[0] = Integer.parseInt(processingResNames[0]);
			}
			//Bug fix
			if(processingResNames[1].matches("[0-9]+")) {
				processingResValues[1] = Integer.parseInt(processingResNames[1]);
			}
			//Bug fix
			if(processingResNames[2].matches("[0-9]+")) {
				processingResValues[2] = Integer.parseInt(processingResNames[2]);
			}

		}
		catch(Throwable t) {
			t.printStackTrace(System.err);
		}
	}

	public void saveGeneral(String aPath) {
		try {
			BufferedWriter aOut = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(aPath), "UTF-8"));
			aOut.write("<lng>"+lng+"</lng>\n");
			aOut.write("<renderDir>"+renderDir+"</renderDir>\n");
			aOut.write("<fastMode>"+fastMode+"</fastMode>\n");
			aOut.write("<processingHdr>"+processingHdr[0]+"\t"+processingHdr[1]+"\t"+processingHdr[2]+"</processingHdr>\n");
			aOut.write("<processingResNames>"+processingResNames[0]+"\t"+processingResNames[1]+"\t"+processingResNames[2]+"</processingResNames>\n");
			aOut.write("<processingResValues>"+processingResValues[0]+"\t"+processingResValues[1]+"\t"+processingResValues[2]+"</processingResValues>\n");
			aOut.write("<outRatio>"+outRatio+"</outRatio>\n");
			aOut.write("<outRatioName>"+outRatioName+"</outRatioName>\n");
			aOut.flush();
			aOut.close();
		}
		catch(Throwable t) {
			t.printStackTrace(System.err);
		}
	}
}
