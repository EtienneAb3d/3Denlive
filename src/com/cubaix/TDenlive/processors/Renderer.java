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
	//Images to encode: BufferedImage (SDR, written as BMP) or byte[] (HDR, raw rgb48le of hdrFrame())
	Vector<Object> waitingPs = new Vector<Object>();
	//HDR output (TDConfig.OUTPUT_HDR "HLG" / "PQ"), null = SDR
	String hdr = null;
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
	//Preview: the first pair from previewFromIndex (end of the opening fades of the montage) that is not empty
	//(ImageUtils.isEmptyImage()); the very first pair until then
	public volatile int previewFromIndex = 0;
	int pairIndex = 0;
	boolean previewGood = false;
	
	/** aPathsOut[i] in the format aFormats[i] (FfmpegEncoder.FORMAT_SUFFIXES), all in one pass */
	public Renderer(TDenlive aTDe
			,int aWidth,int aHeight
			,double aFps,String aFfmpegVideoOpts
			,String[] aPathsOut,int[] aFormats,String aAudioPathIn,boolean aAnaglyph444) {
		this(aTDe, aWidth, aHeight, aFps, aFfmpegVideoOpts, aPathsOut, aFormats, aAudioPathIn, aAnaglyph444, null);
	}

	/** aHdr: "HLG" / "PQ" for an HDR video (HEVC 10 bits), null for SDR */
	public Renderer(TDenlive aTDe
			,int aWidth,int aHeight
			,double aFps,String aFfmpegVideoOpts
			,String[] aPathsOut,int[] aFormats,String aAudioPathIn,boolean aAnaglyph444,String aHdr) {
		super(aTDe, null);
		hdr = FfmpegEncoder.isHdr(aHdr) ? aHdr : null;
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
							if(hdr != null) {
								byte[] aRaw = null;
								try {
									aRaw = hdrFrame(aPair[0], aPair[1]);
								}
								catch(Throwable t) {
									t.printStackTrace(System.err);
									aRaw = new byte[2*width*height*6];
								}
								//SDR preview of the stereo player page
								preview(aPair, null);
								synchronized (waitingPs) {
									waitingPs.add(aRaw);
								}
								continue;
							}
							BufferedImage aP = sdrPair(aPair);
							preview(aPair, aP);
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
        	startEncoding();
        }
		catch(Throwable t) {
			t.printStackTrace(System.err);
		}
	}

	/**
	 * The pair side by side in 8 bits: always one image per pair, black where a view is missing (an error here must
	 * neither stop the mixing thread, the rendering would wait for it forever, nor shift the sound)
	 */
	BufferedImage sdrPair(BufferedImage[] aPair) {
		BufferedImage aP = new BufferedImage(2*width, height, BufferedImage.TYPE_3BYTE_BGR);
		try {
			Graphics2D aG = (Graphics2D)aP.getGraphics();
			aG.setRenderingHint(RenderingHints.KEY_ANTIALIASING,RenderingHints.VALUE_ANTIALIAS_ON);
			aG.setRenderingHint(RenderingHints.KEY_RENDERING,RenderingHints.VALUE_RENDER_QUALITY);
			if(aPair[0] != null) {
				aG.drawImage(com.cubaix.TDenlive.utils.FloatDraw.clamped(aPair[0]), 0, 0,width,height
						,0,0,aPair[0].getWidth(),aPair[0].getHeight(), null);
			}
			if(aPair[1] != null) {
				aG.drawImage(com.cubaix.TDenlive.utils.FloatDraw.clamped(aPair[1]), width, 0,2*width,height
						,0,0,aPair[1].getWidth(),aPair[1].getHeight(), null);
			}
			aG.dispose();
		}
		catch(Throwable t) {
			t.printStackTrace(System.err);
		}
		return aP;
	}

	void startEncoding() {
        try {
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
								Object aO = null;
								synchronized (waitingPs) {
						        	encoding = true;//Be sure to set it before taking the pair
						        	aO = waitingPs.remove(0);
								}
								encoded++;
								byte[] aBmp;
								if(aO instanceof byte[]) {
									//HDR: raw image
									aBmp = (byte[])aO;
								}
								else {
									//Written as BMP once for all the encoders
									BufferedImage aP = (BufferedImage)aO;
									java.io.ByteArrayOutputStream aBOS = new java.io.ByteArrayOutputStream(3*aP.getWidth()*aP.getHeight()+1024);
									javax.imageio.ImageIO.write(aP, "bmp", aBOS);
									aBmp = aBOS.toByteArray();
								}
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
		return new FfmpegEncoder(tde,width,height,fps,ffmpegVideoOpts,aPaths,aFormats,audioPathIn,aFull444,hdr);
	}

	//HDR: the values of the images are display referred (sRGB curve, 1 = white of the SDR images, above 1 for the
	//highlights of the HDR processing); that white is put at the reference level of BT.2408 (203 nits)
	static final double REF_NITS = 203;
	static final int EOTF_STEPS = 8192;//Per unit of value
	static final double V_MAX = 8;
	//HLG: reference display of 1000 nits, the SDR white at REF_NITS = HLG_WHITE of it; system gamma (BT.2100)
	static final double HLG_WHITE = REF_NITS/1000.0;
	static final double HLG_GAMMA = 1.2;
	static final int OETF_SIZE = 65536;
	static float[] EOTF = null;//Value -> linear light (1 = reference white)
	static final java.util.HashMap<String,int[]> OETFS = new java.util.HashMap<String,int[]>();
	//BT.709 -> BT.2020 primaries, linear light (ITU-R BT.2087)
	static final double[][] M709_2020 = {
		{0.627404, 0.329283, 0.043313},
		{0.069097, 0.919540, 0.011362},
		{0.016391, 0.088013, 0.895595}};

	static synchronized float[] eotf() {
		if(EOTF == null) {
			float[] aL = new float[(int)(V_MAX*EOTF_STEPS)+1];
			for(int i = 0;i < aL.length;i++) {
				double v = i/(double)EOTF_STEPS;
				aL[i] = (float)(v <= 0.04045 ? v/12.92 : Math.pow((v+0.055)/1.055, 2.4));
			}
			EOTF = aL;
		}
		return EOTF;
	}

	/** Linear light (1 = reference white): its ratio to the signal-linear input y of the OETF (0..1) */
	static double linearScale(String aHdr) {
		if("PQ".equals(aHdr)) {
			return REF_NITS/10000.0;
		}
		//HLG: reference white at 75 % of the signal
		double a = 0.17883277, b = 0.28466892, c = 0.55991073;
		return (Math.exp((0.75-c)/a)+b)/12.0;
	}

	/** OETF of aHdr on 16 bits, indexed by sqrt(y) (y = signal-linear 0..1): fine steps in the darks */
	static synchronized int[] oetf(String aHdr) {
		int[] aT = OETFS.get(aHdr);
		if(aT == null) {
			aT = new int[OETF_SIZE];
			for(int i = 0;i < OETF_SIZE;i++) {
				double s = i/(double)(OETF_SIZE-1);
				double y = s*s;
				double e;
				if("PQ".equals(aHdr)) {
					double m1 = 0.1593017578125, m2 = 78.84375, c1 = 0.8359375, c2 = 18.8515625, c3 = 18.6875;
					double p = Math.pow(y, m1);
					e = Math.pow((c1+c2*p)/(1+c3*p), m2);
				}
				else {
					double a = 0.17883277, b = 0.28466892, c = 0.55991073;
					e = y <= 1/12.0 ? Math.sqrt(3*y) : a*Math.log(12*y-b)+c;
				}
				aT[i] = (int)Math.round(Math.max(0, Math.min(1, e))*65535);
			}
			OETFS.put(aHdr, aT);
		}
		return aT;
	}

	/**
	 * The pair side by side (2 x width by height) as raw rgb48le in BT.2020 and the transfer of hdr: each view scaled
	 * bilinearly to width x height, composited over black (alpha), 8 bits or HDR images; lines on all the cores
	 */
	byte[] hdrFrame(BufferedImage aL, BufferedImage aR) {
		final int aW2 = 2*width;
		final byte[] aOut = new byte[aW2*height*6];
		final float[] aEotf = eotf();
		final int[] aOetf = oetf(hdr);
		final double aK = linearScale(hdr);
		final boolean aHlg = "HLG".equals(hdr);
		for(int e = 0;e < 2;e++) {
			final BufferedImage aSrc = e == 0 ? aL : aR;
			if(aSrc == null) {
				continue;
			}
			final int aX0 = e*width;
			final int aSW = aSrc.getWidth(), aSH = aSrc.getHeight();
			final java.awt.image.Raster aRas = aSrc.getRaster();
			final int aBands = aRas.getNumBands();
			final boolean aFloat = aRas.getDataBuffer() instanceof java.awt.image.DataBufferFloat;
			final float aNorm = aFloat ? 1f : 1f/255f;
			final boolean aAlpha = aSrc.getColorModel().hasAlpha() && aBands > 3;
			com.cubaix.TDenlive.utils.Par.rows(height, (aY0, aY1) -> {
				float[] aRow0 = new float[aSW*aBands], aRow1 = new float[aSW*aBands];
				int aLoaded0 = -1, aLoaded1 = -1;
				for(int y = aY0;y < aY1;y++) {
					double aSy = Math.max(0, Math.min(aSH-1, (y+0.5)*aSH/(double)height-0.5));
					int y0 = (int)aSy, y1 = Math.min(aSH-1, y0+1);
					float fy = (float)(aSy-y0);
					if(aLoaded0 != y0) {
						if(aLoaded1 == y0) {
							float[] aT = aRow0; aRow0 = aRow1; aRow1 = aT;
							aLoaded0 = y0; aLoaded1 = -1;
						}
						else {
							aRas.getPixels(0, y0, aSW, 1, aRow0);
							aLoaded0 = y0;
						}
					}
					if(aLoaded1 != y1) {
						aRas.getPixels(0, y1, aSW, 1, aRow1);
						aLoaded1 = y1;
					}
					int o = (y*aW2+aX0)*6;
					double[] aLin = new double[3];
					double[] aL2020 = new double[3];
					for(int x = 0;x < width;x++) {
						double aSx = Math.max(0, Math.min(aSW-1, (x+0.5)*aSW/(double)width-0.5));
						int x0 = (int)aSx, x1 = Math.min(aSW-1, x0+1);
						float fx = (float)(aSx-x0);
						int i00 = x0*aBands, i01 = x1*aBands;
						float aA = 1;
						if(aAlpha) {
							aA = aNorm*((aRow0[i00+3]*(1-fx)+aRow0[i01+3]*fx)*(1-fy)+(aRow1[i00+3]*(1-fx)+aRow1[i01+3]*fx)*fy);
						}
						for(int c = 0;c < 3;c++) {
							float v = aNorm*aA*((aRow0[i00+c]*(1-fx)+aRow0[i01+c]*fx)*(1-fy)+(aRow1[i00+c]*(1-fx)+aRow1[i01+c]*fx)*fy);
							int aI = (int)(Math.max(0, Math.min(V_MAX, v))*EOTF_STEPS);
							aLin[c] = aEotf[aI];
						}
						for(int c = 0;c < 3;c++) {
							double[] m = M709_2020[c];
							aL2020[c] = Math.max(0, m[0]*aLin[0]+m[1]*aLin[1]+m[2]*aLin[2]);
						}
						double aOotf = aK;
						if(aHlg) {
							//Display light (fraction of the 1000 nits of the HLG reference display) back to scene light:
							//inverse OOTF of BT.2100, system gamma 1.2, on the luminance (white of 203 nits -> 75 %)
							double aYd = (0.2627*aL2020[0]+0.6780*aL2020[1]+0.0593*aL2020[2])*HLG_WHITE;
							aOotf = aYd > 1e-9 ? HLG_WHITE*Math.pow(aYd, 1/HLG_GAMMA-1) : 0;
						}
						for(int c = 0;c < 3;c++) {
							double aYs = Math.max(0, Math.min(1, aL2020[c]*aOotf));
							int aV = aOetf[(int)(Math.sqrt(aYs)*(OETF_SIZE-1)+0.5)];
							aOut[o++] = (byte)(aV & 0xFF);
							aOut[o++] = (byte)((aV >> 8) & 0xFF);
						}
					}
				}
			});
		}
		return aOut;
	}

	/**
	 * Preview of the pages (firstP): the first pair, replaced by the first one from previewFromIndex that is not
	 * empty (not black: a fade in from black)
	 */
	void preview(BufferedImage[] aPair, BufferedImage aSdr) {
		int aIdx = pairIndex++;
		if(previewGood) {
			return;
		}
		boolean aGood = aIdx >= previewFromIndex && !com.cubaix.TDenlive.utils.ImageUtils.isEmptyImage(aPair[0]);
		if(firstP == null || aGood) {
			firstP = aSdr != null ? aSdr : sdrPair(aPair);
			previewGood = aGood;
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
