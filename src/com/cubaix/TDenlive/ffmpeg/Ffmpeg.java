package com.cubaix.TDenlive.ffmpeg;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;

import com.cubaix.TDenlive.utils.StringUtils;

public class Ffmpeg {
	volatile long duration = -1;
	volatile double fps = -1;
	//Set by the stderr reader: the input has an audio stream / the input description is fully read
	volatile boolean hasAudioStream = false;
	volatile boolean inputParsed = false;
	//Time of the output written so far (ms), from the progress lines of ffmpeg ("time=..."), -1 if none yet
	volatile long progressMS = -1;

	public Ffmpeg() {
	}

	/**
	 * Frame rate of the input read from the ffmpeg output, -1 if not (yet) known
	 */
	public double getFps() {
		return fps;
	}

	/**
	 * Duration of the input (ms) read from the ffmpeg output, -1 if not (yet) known
	 */
	public long getDuration() {
		return duration;
	}
	
	//Folder holding the bundled binaries, relative to the working directory (like lumas/ and frames/)
	//Filled in dev by Install-Dev.sh, shipped next to the launch script in distributions
	static final String FFMPEG_DIR = "ffmpeg";
	static String ffmpegPath = null;

	String getFfmpeg() {
		if(ffmpegPath == null) {
			ffmpegPath = findFfmpeg();
			System.err.println("ffmpeg: " + ffmpegPath);
		}
		return ffmpegPath;
	}

	static String sharedPath = null;

	/**
	 * Path of ffmpeg for the other programs (see findFfmpeg()), null if not found
	 */
	public static synchronized String ffmpegPath() {
		if(sharedPath == null) {
			sharedPath = findFfmpeg();
		}
		return sharedPath;
	}

	//Transfer of the HDR videos (hdrTransfer()) by path|size|date: "HLG", "PQ" or "" (SDR / unknown)
	static final java.util.Map<String, String> HDR_TRANSFER = new java.util.concurrent.ConcurrentHashMap<String, String>();
	static volatile Boolean hasZscale = null;

	/**
	 * "HLG" or "PQ" when the first video stream of aPath is HDR (transfer arib-std-b67 / smpte2084 in the description
	 * of ffmpeg -i), else null; cached by path, size and date
	 */
	public static String hdrTransfer(String aPath) {
		if(aPath == null) {
			return null;
		}
		File aF = new File(aPath);
		if(!aF.isFile()) {
			return null;
		}
		String aKey = aF.getAbsolutePath()+"|"+aF.length()+"|"+aF.lastModified();
		String aT = HDR_TRANSFER.get(aKey);
		if(aT == null) {
			aT = "";
			String aFfmpeg = ffmpegPath();
			if(aFfmpeg != null) {
				try {
					Process aP = new ProcessBuilder(aFfmpeg, "-nostdin", "-hide_banner", "-i", aPath).redirectErrorStream(true).start();
					try(BufferedReader aR = new BufferedReader(new InputStreamReader(aP.getInputStream(), java.nio.charset.StandardCharsets.UTF_8))) {
						String aL;
						while((aL = aR.readLine()) != null) {
							if(aT.isEmpty() && aL.contains("Stream #") && aL.contains("Video:")) {
								aT = aL.contains("arib-std-b67") ? "HLG" : aL.contains("smpte2084") ? "PQ" : "";
								//The first video stream only
								if(!aT.isEmpty()) {
									break;
								}
							}
						}
					}
					aP.destroy();
				}
				catch(Throwable t) {
					t.printStackTrace(System.err);
				}
			}
			HDR_TRANSFER.put(aKey, aT);
		}
		return aT.isEmpty() ? null : aT;
	}

	/**
	 * Set by the rendering window while it renders an HDR video (HLG / PQ) with the HDR processing (floating point
	 * images): the HDR videos are then decoded in the RENDER mode with their highlights (VideoReader.wantsHdr()),
	 * not tone mapped to SDR
	 */
	public static volatile boolean hdrRendering = false;

	/** The ffmpeg found has the zscale filter (libzimg), needed by the tone mapping */
	public static boolean hasZscale() {
		if(hasZscale == null) {
			boolean aHas = false;
			String aFfmpeg = ffmpegPath();
			if(aFfmpeg != null) {
				try {
					Process aP = new ProcessBuilder(aFfmpeg, "-hide_banner", "-filters").redirectErrorStream(true).start();
					try(BufferedReader aR = new BufferedReader(new InputStreamReader(aP.getInputStream(), java.nio.charset.StandardCharsets.UTF_8))) {
						String aL;
						while((aL = aR.readLine()) != null) {
							if(aL.matches("^\\s*\\S+\\s+zscale\\s.*")) {
								aHas = true;
							}
						}
					}
					aP.waitFor();
				}
				catch(Throwable t) {
					t.printStackTrace(System.err);
				}
			}
			hasZscale = aHas;
			if(!aHas) {
				System.err.println("ffmpeg without zscale: the HDR videos are read without tone mapping");
			}
		}
		return hasZscale;
	}

	/**
	 * Tone mapping of an HDR video (HLG / PQ, BT.2020) to SDR BT.709 for the 8-bit images of the application (monitor,
	 * work copies, film strips, SDR renderings), so that it is not shown dark and dull: linear light (100 nits = SDR
	 * white), BT.2020 -> BT.709, Mobius curve (keeps the levels up to the white, compresses the highlights), BT.709.
	 * Null for an SDR video (or without zscale). To put first in the -vf of a decoding of aPath
	 */
	public static String sdrFilter(String aPath) {
		if(isAnaglyph(aPath)) {
			return anaglyphFilter(aPath);
		}
		if(hdrTransfer(aPath) == null || !hasZscale()) {
			return null;
		}
		return "zscale=t=linear:npl=100,format=gbrpf32le,zscale=p=bt709,tonemap=tonemap=mobius:desat=0"
				+ ",zscale=t=bt709:m=bt709:r=tv,format=yuv420p";
	}

	//Videos imported as anaglyphs (absolute paths, StereoImage.loadImageLR()): their color layers are views, kept apart
	static final java.util.Set<String> ANAGLYPHS = java.util.concurrent.ConcurrentHashMap.newKeySet();

	public static void markAnaglyph(String aPath) {
		if(aPath != null) {
			ANAGLYPHS.add(new File(aPath).getAbsolutePath());
		}
	}

	public static boolean isAnaglyph(String aPath) {
		return aPath != null && ANAGLYPHS.contains(new File(aPath).getAbsolutePath());
	}

	/**
	 * Decoding of an anaglyph video: each color layer is a view, so nothing may mix them. The tone mapping of
	 * sdrFilter() does (BT.2020 -> BT.709 primaries: R709 = 1.66 R - 0.59 G - 0.07 B, the dark post of the cyan view
	 * became a bright ghost in the red view; the Mobius curve scaling the 3 layers by the strongest; the 4:2:0 output
	 * halving the colors, where the red / cyan separation lies). HLG: shown as it is (its signal is made for SDR
	 * screens), only the YUV BT.2020 -> RGB conversion at full color resolution; PQ: each layer to linear light then
	 * to BT.709 on its own (highlights clipped); SDR: null (plain decoding, full color resolution)
	 */
	static String anaglyphFilter(String aPath) {
		String aT = hdrTransfer(aPath);
		if(aT == null) {
			return null;
		}
		if("HLG".equals(aT)) {
			return "scale=in_color_matrix=bt2020:in_range=tv:flags=bicubic+accurate_rnd+full_chroma_int,format=gbrp";
		}
		if(!hasZscale()) {
			return null;
		}
		return "zscale=t=linear:npl=100,format=gbrpf32le,zscale=t=bt709,format=gbrp";
	}

	static String findFfmpeg() {
		//The ffmpeg of the delivery first: the one of the machine may lack features (ffmpeg 8 of Ubuntu decodes only
		//the first 512 x 512 tile of a HEIC tile grid)
		String os = System.getProperty("os.name").toLowerCase();
		boolean isWindows = os.contains("windows");
		boolean isMac = os.contains("mac");

		//Bundled binary: ffmpeg-amd64(.exe), ffmpeg-x86_64-osx, ffmpeg-aarch64-osx, ...
		String suffix = isWindows ? ".exe" : (isMac ? "-osx" : "");
		String arch = System.getProperty("os.arch");
		File aBundled = new File(FFMPEG_DIR, "ffmpeg-" + arch + suffix);
		if(aBundled.isFile()) {
			if(!isWindows && !aBundled.canExecute()) {
				aBundled.setExecutable(true);
			}
			return aBundled.getAbsolutePath();
		}

		//Installed ffmpeg (PATH, then usual package manager locations not always in the PATH of a GUI app)
		String aExe = isWindows ? "ffmpeg.exe" : "ffmpeg";
		String aPath = System.getenv("PATH");
		if(aPath != null) {
			for(String aDir : aPath.split(File.pathSeparator)) {
				File aF = new File(aDir, aExe);
				if(aF.isFile() && aF.canExecute()) {
					return aF.getAbsolutePath();
				}
			}
		}
		for(String aCandidate : new String[] {"/usr/bin/ffmpeg", "/opt/homebrew/bin/ffmpeg", "/usr/local/bin/ffmpeg"}) {
			if(new File(aCandidate).canExecute()) {
				return aCandidate;
			}
		}

		System.err.println("ffmpeg not found: run Install-Dev.sh, or put " + aBundled.getAbsolutePath() + " in place, or install ffmpeg");
		return null;
	}
	
	/**
	 * Read the next BMP image of a stream of concatenated BMPs (ffmpeg image2pipe), null at the end.
	 * Each file is first cut with the size of its header: ImageIO alone may leave the padding
	 * of the last row (width*3 not multiple of 4) in the stream, and then misread all the next images.
	 */
	/** Filter of an HDR video for hdrDecode: linear light (1 = SDR white at 203 nits, BT.2408), BT.709 primaries */
	public static String hdrLinearFilter() {
		return "zscale=t=linear:npl=203,format=gbrpf32le,zscale=p=bt709,format=gbrpf32le";
	}

	//Linear light -> value of the float images (sRGB curve, extended above 1), indexed by the square root of the
	//linear value: SQRT_MAX^2 = 64 x the SDR white (13000 nits, above the 10000 of PQ)
	static final int ENC_SIZE = 1 << 16;
	static final double SQRT_MAX = 8;
	static float[] ENC = null;

	static synchronized float[] encodeTable() {
		if(ENC == null) {
			float[] aT = new float[ENC_SIZE+1];
			for(int i = 0;i <= ENC_SIZE;i++) {
				double s = i*SQRT_MAX/ENC_SIZE, l = s*s;
				aT[i] = (float)(l <= 0.0031308 ? 12.92*l : 1.055*Math.pow(l, 1/2.4)-0.055);
			}
			ENC = aT;
		}
		return ENC;
	}

	/**
	 * Next image of a PFM stream (ffmpeg -vcodec pfm, gbrpf32le of hdrLinearFilter(): linear light, lines bottom to
	 * top) as a float image of the application (ImageUtils.createHdr(): values on the sRGB curve, 1 = SDR white,
	 * the highlights above 1, negative values of colors out of BT.709 clamped to 0, opaque); null at the end
	 */
	public static java.awt.image.BufferedImage readPfm(InputStream aIS) throws IOException {
		String aMagic = pfmToken(aIS);
		if(aMagic == null) {
			return null;
		}
		if(!aMagic.equals("PF")) {
			throw new IOException("Not a color PFM stream: "+aMagic);
		}
		int aW = Integer.parseInt(pfmToken(aIS));
		int aH = Integer.parseInt(pfmToken(aIS));
		double aScale = Double.parseDouble(pfmToken(aIS));
		boolean aLE = aScale < 0;
		byte[] aData = new byte[aW*aH*12];
		new java.io.DataInputStream(aIS).readFully(aData);
		java.awt.image.BufferedImage aBI = com.cubaix.TDenlive.utils.ImageUtils.createHdr(aW, aH);
		final float[] aD = ((java.awt.image.DataBufferFloat)aBI.getRaster().getDataBuffer()).getData();
		final float[] aT = encodeTable();
		final java.nio.FloatBuffer aFB = java.nio.ByteBuffer.wrap(aData).order(aLE ? java.nio.ByteOrder.LITTLE_ENDIAN : java.nio.ByteOrder.BIG_ENDIAN).asFloatBuffer();
		com.cubaix.TDenlive.utils.Par.rows(aH, (aY0, aY1) -> {
			for(int y = aY0;y < aY1;y++) {
				int i = (aH-1-y)*aW*3;
				int o = y*aW*4;
				for(int x = 0;x < aW;x++) {
					for(int c = 0;c < 3;c++) {
						float l = aFB.get(i++);
						double s = l <= 0 ? 0 : Math.min(SQRT_MAX, Math.sqrt(l));
						aD[o++] = aT[(int)(s*(ENC_SIZE/SQRT_MAX)+0.5)];
					}
					aD[o++] = 1f;
				}
			}
		});
		return aBI;
	}

	/** Next token of a PFM header (ends at a white space, one consumed), null at the end of the stream */
	static String pfmToken(InputStream aIS) throws IOException {
		StringBuilder aSB = new StringBuilder();
		int c;
		while((c = aIS.read()) >= 0) {
			if(Character.isWhitespace(c)) {
				if(aSB.length() > 0) {
					return aSB.toString();
				}
				continue;
			}
			aSB.append((char)c);
		}
		if(aSB.length() > 0) {
			throw new IOException("Truncated PFM header");
		}
		return null;
	}

	public static java.awt.image.BufferedImage readBmp(InputStream aIS) throws IOException {
		java.io.DataInputStream aDIS = new java.io.DataInputStream(aIS);
		byte[] aHeader = new byte[14];
		try {
			aDIS.readFully(aHeader);
		}
		catch(java.io.EOFException e) {
			return null;
		}
		if(aHeader[0] != 'B' || aHeader[1] != 'M') {
			throw new IOException("Not a BMP stream");
		}
		int aSize = (aHeader[2] & 0xFF) | (aHeader[3] & 0xFF) << 8 | (aHeader[4] & 0xFF) << 16 | (aHeader[5] & 0xFF) << 24;
		byte[] aFile = new byte[aSize];
		System.arraycopy(aHeader, 0, aFile, 0, 14);
		aDIS.readFully(aFile, 14, aSize-14);
		return javax.imageio.ImageIO.read(new java.io.ByteArrayInputStream(aFile));
	}
	
	static final java.util.regex.Pattern PROGRESS_TIME = java.util.regex.Pattern.compile("time=(\\d+:\\d\\d:\\d\\d[.]\\d+)");

	class StreamReader implements Runnable {

	    protected final InputStream inputStream;

	    StreamReader(InputStream inputStream) {
	        this.inputStream = inputStream;
	    }

	    private BufferedReader getBufferedReader(InputStream is) {
	        return new BufferedReader(new InputStreamReader(is));
	    }

	    @Override
	    public void run() {
	        BufferedReader aBr = getBufferedReader(inputStream);
	        String aLine = "";
	        try {
	            while ((aLine = aBr.readLine()) != null) {
	                System.out.println(aLine);
	            	aLine = aLine.trim();
	            	if(aLine.matches("Duration: [0-9][0-9]:[0-9][0-9]:[0-9][0-9][.][0-9][0-9],.*")) {
	            		String aDuration = aLine.substring("Duration: ".length());
	            		aDuration = aDuration.substring(0, aDuration.indexOf(","));
	            		duration = StringUtils.ffmpeg2Time(aDuration);
	            	}
	            	if(aLine.matches(".*, [0-9.]+ fps,.*")) {
	            		String aFps = aLine.substring(0, aLine.indexOf("fps,"));
	            		aFps = aFps.substring(aFps.lastIndexOf(",")+1).trim();
	            		fps = Double.parseDouble(aFps);
	            	}
	            	if(aLine.startsWith("frame=") || aLine.startsWith("size=")) {
	            		java.util.regex.Matcher aM = PROGRESS_TIME.matcher(aLine);
	            		if(aM.find()) {
	            			try {
	            				progressMS = StringUtils.ffmpeg2Time(aM.group(1));
	            			}
	            			catch(Throwable t) {
	            			}
	            		}
	            	}
	            	if(aLine.matches("Stream #.*: Audio: .*")) {
	            		hasAudioStream = true;
	            	}
	            	if(aLine.startsWith("Output #") || aLine.startsWith("Stream mapping:")
	            			|| aLine.matches(".*matches no streams.*")) {
	            		inputParsed = true;
	            	}
	            }
	        } catch (IOException e) {
//	            e.printStackTrace();
	        }
	    }
	}
}
