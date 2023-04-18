package com.cubaix.TDenlive.processors;

import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.BufferedWriter;
import java.util.Vector;

import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.graphics.Rectangle;

import com.cubaix.TDenlive.TDenlive;
import com.cubaix.TDenlive.ffmpeg.FfmpegEncoder;
import com.cubaix.TDenlive.medias.Media;
import com.cubaix.TDenlive.xml.XmlObject;

public class Renderer extends Processor {
	FfmpegEncoder ffmpeg = null;
	Vector<BufferedImage[]> waitingPairs = new Vector<BufferedImage[]>();
	Vector<BufferedImage> waitingPs = new Vector<BufferedImage>();
	String pathOut = null;
	int width = 1280;
	int height = 720;
	int fps = 30;
	boolean processing = false;
	public int encoded = 0;
	boolean encoding = false;
	
	public Renderer(TDenlive aTDe,int aWidth,int aHeight,int aFps,String aPathOut) {
		super(aTDe, null);
		width = aWidth;
		height = aHeight;
		fps = aFps;
		pathOut = aPathOut;
		start();
	}

	void start() {
        try {
        	ffmpeg = new FfmpegEncoder(tde,width,height,fps,pathOut);
        	
        	processing = true;
        	
        	Thread aThMix = new Thread(new Runnable() {
				@Override
				public void run() {
					while(processing) {
						BufferedImage[] aPair = null;
						synchronized (waitingPairs) {
							if(waitingPairs.size() > 0) {
								aPair = waitingPairs.remove(0);
							}
						}
						if(aPair != null) {
				        	encoding = true;//Be sure the flag is set before taking the pair
							BufferedImage aP = new BufferedImage(2*width, height, BufferedImage.TYPE_3BYTE_BGR);
							Graphics2D aG = (Graphics2D)aP.getGraphics();
							aG.setRenderingHint(RenderingHints.KEY_ANTIALIASING,RenderingHints.VALUE_ANTIALIAS_ON);
							aG.setRenderingHint(RenderingHints.KEY_RENDERING,RenderingHints.VALUE_RENDER_QUALITY);
							aG.drawImage(aPair[0], 0, 0,width,height
									,0,0,aPair[0].getWidth(),aPair[0].getHeight(), null);
							aG.drawImage(aPair[1], width, 0,2*width,height
									,0,0,aPair[1].getWidth(),aPair[1].getHeight(), null);
							aG.dispose();
							synchronized (waitingPs) {
								waitingPs.add(aP);
							}
						}
						try {
							Thread.sleep(10);
						}
						catch(Throwable t) {
							t.printStackTrace(System.err);
						}
					}
				}
			});
        	aThMix.start();
        	
        	Thread aThEnc = new Thread(new Runnable() {
				@Override
				public void run() {
					while(processing) {
						boolean aCanEncode = false;
						synchronized (waitingPs) {
							if(waitingPs.size() > 0) {
								aCanEncode = true;
							}
						}
						try {
							if(aCanEncode) {
								BufferedImage aP = null;
								synchronized (waitingPs) {
						        	encoding = true;//Be sure to set it before taking the pair
						        	aP = waitingPs.remove(0);
								}
								encoded++;
								ffmpeg.addImg(aP);
					        	encoding = false;
							}
							Thread.sleep(10);
						}
						catch(Throwable t) {
							t.printStackTrace(System.err);
						}
					}
				}
			});
        	aThEnc.start();

        }
		catch(Throwable t) {
			t.printStackTrace(System.err);
		}
    }
	
	public void addPair(BufferedImage aL,BufferedImage aR) {
		boolean aCanAdd = false;
		while(!aCanAdd) {
			synchronized (waitingPairs) {
				if(waitingPairs.size() < 5 && waitingPs.size() < 5) {
					aCanAdd = true;
				}
			}
			if(!aCanAdd) {
				try {
					Thread.sleep(10);
				}
				catch(Throwable t) {
					t.printStackTrace(System.err);
				}
				continue;
			}
			synchronized (waitingPairs) {
				waitingPairs.add(new BufferedImage[] {aL,aR});
			}
		}
	}
	
	public void stop() {
		boolean aCanStop = false;
		while(!aCanStop) {
			synchronized (waitingPs) {
				if(waitingPairs.size() <= 0 && waitingPs.size() <= 0 && encoding == false) {
					aCanStop = true;
				}
			}
			if(!aCanStop) {
				try {
					Thread.sleep(10);
				}
				catch(Throwable t) {
					t.printStackTrace(System.err);
				}
				continue;
			}
		}
		try {
			processing = false;
			ffmpeg.stop();
		}
		catch(Throwable t) {
			t.printStackTrace(System.err);
		}
	}
	
	@Override
	public String getClassName() {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public int openProject(Vector<XmlObject> aOs, int o) throws Exception {
		// TODO Auto-generated method stub
		return 0;
	}

	@Override
	public void saveProject(StringBuffer aSB) throws Exception {}

	@Override
	void setDescr() {
		// TODO Auto-generated method stub

	}

	@Override
	public Image process2Swt(Media aMedia, int aProcessingMode, Rectangle aTargetSize, long aTime) {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public void process2Awt(Media aMedia, int aProcessingMode, long aTime) {
		// TODO Auto-generated method stub

	}

}
