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
	String[] pathsOut = null;//One per format
	int[] formats = null;
	//Anaglyphs in 4:4:4: their own ffmpeg process (ffmpegAna), given the same images
	boolean anaglyph444 = false;
	FfmpegEncoder ffmpegAna = null;
	String audioPathIn = null;
	int width = 1280;
	int height = 720;
	double fps = 30;
	String ffmpegVideoOpts = "";
	boolean processing = false;
	public int encoded = 0;
	boolean encoding = false;
	//First parallel image, for the preview of the stereo player page
	public volatile BufferedImage firstP = null;
	
	/** aPathsOut[i] in the format aFormats[i] (FfmpegEncoder.FORMAT_SUFFIXES), all in one pass */
	public Renderer(TDenlive aTDe
			,int aWidth,int aHeight
			,double aFps,String aFfmpegVideoOpts
			,String[] aPathsOut,int[] aFormats,String aAudioPathIn,boolean aAnaglyph444) {
		super(aTDe, null);
		audioPathIn = aAudioPathIn;
		width = aWidth;
		height = aHeight;
		fps = aFps;
		ffmpegVideoOpts = aFfmpegVideoOpts;
		pathsOut = aPathsOut;
		formats = aFormats;
		anaglyph444 = aAnaglyph444;
		start();
	}

	void start() {
        try {
        	//The anaglyphs go to a separate encoder in 4:4:4 when asked, the other formats to the main one
        	java.util.ArrayList<Integer> aMain = new java.util.ArrayList<Integer>(), aAna = new java.util.ArrayList<Integer>();
        	for(int o = 0;o < formats.length;o++) {
        		(anaglyph444 && FfmpegEncoder.isAnaglyph(formats[o]) ? aAna : aMain).add(o);
        	}
        	if(!aMain.isEmpty()) {
        		ffmpeg = encoder(aMain, false);
        	}
        	if(!aAna.isEmpty()) {
        		ffmpegAna = encoder(aAna, true);
        	}
        	
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
							//Always one image per pair, black where a view is missing: an error here must
							//neither stop this thread (the rendering would wait for it forever) nor shift the sound
							BufferedImage aP = new BufferedImage(2*width, height, BufferedImage.TYPE_3BYTE_BGR);
							try {
								Graphics2D aG = (Graphics2D)aP.getGraphics();
								aG.setRenderingHint(RenderingHints.KEY_ANTIALIASING,RenderingHints.VALUE_ANTIALIAS_ON);
								aG.setRenderingHint(RenderingHints.KEY_RENDERING,RenderingHints.VALUE_RENDER_QUALITY);
								if(aPair[0] != null) {
									aG.drawImage(aPair[0], 0, 0,width,height
											,0,0,aPair[0].getWidth(),aPair[0].getHeight(), null);
								}
								if(aPair[1] != null) {
									aG.drawImage(aPair[1], width, 0,2*width,height
											,0,0,aPair[1].getWidth(),aPair[1].getHeight(), null);
								}
								aG.dispose();
							}
							catch(Throwable t) {
								t.printStackTrace(System.err);
							}
							if(firstP == null) {
								firstP = aP;
							}
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
								//Written as BMP once for all the encoders
								java.io.ByteArrayOutputStream aBOS = new java.io.ByteArrayOutputStream(3*aP.getWidth()*aP.getHeight()+1024);
								javax.imageio.ImageIO.write(aP, "bmp", aBOS);
								byte[] aBmp = aBOS.toByteArray();
								if(ffmpeg != null) {
									ffmpeg.addBmp(aBmp);
								}
								if(ffmpegAna != null) {
									ffmpegAna.addBmp(aBmp);
								}
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
	
	/** Encoder of the outputs aIdx of pathsOut / formats */
	FfmpegEncoder encoder(java.util.List<Integer> aIdx, boolean aFull444) {
		String[] aPaths = new String[aIdx.size()];
		int[] aFormats = new int[aIdx.size()];
		for(int i = 0;i < aPaths.length;i++) {
			aPaths[i] = pathsOut[aIdx.get(i)];
			aFormats[i] = formats[aIdx.get(i)];
		}
		return new FfmpegEncoder(tde,width,height,fps,ffmpegVideoOpts,aPaths,aFormats,audioPathIn,aFull444);
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
			if(ffmpeg != null) {
				ffmpeg.stop();
			}
			if(ffmpegAna != null) {
				ffmpegAna.stop();
			}
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
