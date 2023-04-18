package com.cubaix.TDenlive;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.util.Vector;

import org.eclipse.swt.graphics.Cursor;

import com.cubaix.TDenlive.GUI.TDeGUI;
import com.cubaix.TDenlive.medias.MediaList;
import com.cubaix.TDenlive.medias.Selected;
import com.cubaix.TDenlive.medias.TimeLineStack;
import com.cubaix.TDenlive.xml.XmlMinimalParser;
import com.cubaix.TDenlive.xml.XmlObject;
import com.cubaix.TDenlive.xml.XmlTag;

public class TDenlive {
	static final public String TDeVersion = "0.25.0";
	
	public String tdeDir = System.getProperty("user.home")+File.separatorChar+"3Denlive";
	{
		if(!new File(tdeDir).exists()) {
			new File(tdeDir).mkdirs();
		}
	}
	public String configPath = tdeDir+File.separatorChar+"3De.conf";
	public String renderDir = tdeDir+File.separatorChar+"Rendered";
	
	public String lastDir = null;
	public String projectDir = null;
	public String projectName = null;
 
	public TDConfig config = null;
	public TDeGUI gui = null;
	
	public MediaList mediaList = null;
	public TimeLineStack timeLineStack = null;
	
	public Selected selected = new Selected();

	public void resetViews() {
		gui.display.syncExec(new Runnable() {
			@Override
			public void run() {
				mediaList.mediaList.clear();
				timeLineStack.videoTimeLineStack.clear();
				timeLineStack.audioTimeLineStack.clear();
				gui.setMultiPropsPanel(gui.MULTIPROPSPANEL_PROPS);
				gui.setMultiMonitorPanel(gui.MULTIMONITORPANEL_MONITOR);
				gui.monitorGUI.setMedia(null);
				timeLineStack.setRequestedTimePosMS(0);
			}
		});
	}
	
	public void openProject(String aPath) {
		try {
			resetViews();
			
			XmlMinimalParser aXMP = new XmlMinimalParser();
			Vector<XmlObject> aOs = aXMP.parse(aPath);
			for(int o = 0;o < aOs.size();o++) {
				XmlObject aO = aOs.elementAt(o);
				if(aO instanceof XmlTag) {
					XmlTag aT = (XmlTag)aO;
					if("outRatio".equalsIgnoreCase(aT.tagName)) {
						o++;
						config.outRatio = Double.parseDouble(aOs.elementAt(o).text);
						continue;
					}
					if("outRatioName".equalsIgnoreCase(aT.tagName)) {
						o++;
						config.outRatioName = aOs.elementAt(o).text;
						continue;
					}
					if("MediaList".equalsIgnoreCase(aT.tagName)) {
						o = mediaList.openProject(aOs, o);
					}
					if("TimeLineList".equalsIgnoreCase(aT.tagName)) {
						o = timeLineStack.openProject(aOs, o);
					}
				}
			}
			if(gui.monitorControlGUI != null) {
				gui.monitorControlGUI.setRatio(config.outRatioName);
			}
			gui.mediaListGUI.redraw();
			gui.timeLinesGUI.redraw();
		}
		catch(Throwable t) {
			t.printStackTrace(System.err);
		}
	}
	
	public void saveProject(String aPath) {
		try {
			StringBuffer aSB = new StringBuffer();
			aSB.append("<3De>\n");
			aSB.append("	<outRatio>"+config.outRatio+"</outRatio>\n");
			aSB.append("	<outRatioName>"+config.outRatioName+"</outRatioName>\n");
			aSB.append("	<MediaList>\n");
			mediaList.saveProject(aSB);
			aSB.append("	</MediaList>\n");
			aSB.append("	<TimeLineList>\n");
			timeLineStack.saveProject(aSB);
			aSB.append("	</TimeLineList>\n");
			aSB.append("</3De>\n");
			BufferedWriter aBW = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(aPath), "UTF-8"));
			aBW.write(aSB.toString());
			aBW.flush();
			aBW.close();
		}
		catch(Throwable t) {
			t.printStackTrace(System.err);
		}
	}
	
	public void start() throws Exception {
		config = new TDConfig();
		
		mediaList = new MediaList(this);
	    gui = new TDeGUI(this);
	    timeLineStack = new TimeLineStack(this);
	    
	    gui.run();
	}
	
	public static void main(String[] args) {
		try {
			new TDenlive().start();
			System.exit(0);//Force exit, because of possible threads keeping it alive
		}
		catch(Throwable t) {
			t.printStackTrace(System.err);
		}
	}

}
