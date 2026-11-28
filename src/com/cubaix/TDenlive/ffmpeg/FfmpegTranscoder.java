package com.cubaix.TDenlive.ffmpeg;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;

import org.eclipse.swt.graphics.Path;

import com.cubaix.TDenlive.utils.StringUtils;

public class FfmpegTranscoder extends Ffmpeg {

	public FfmpegTranscoder() {
	}

	//Work copies being made: their transcoder and start time (progress shown over the monitor)
	static final java.util.concurrent.ConcurrentHashMap<FfmpegTranscoder,Long> ACTIVE = new java.util.concurrent.ConcurrentHashMap<FfmpegTranscoder,Long>();

	/**
	 * Progress of the work copies being made: {ms done, ms in all, ms since the start of the oldest}, null when
	 * the durations are not known yet (or none is being made)
	 */
	public static long[] workCopyProgress() {
		long aDone = 0, aTotal = 0, aStart = Long.MAX_VALUE;
		for(java.util.Map.Entry<FfmpegTranscoder,Long> aE : ACTIVE.entrySet()) {
			FfmpegTranscoder aT = aE.getKey();
			if(aT.duration <= 0) {
				return null;
			}
			aTotal += aT.duration;
			aDone += Math.max(0, Math.min(aT.duration, aT.progressMS));
			aStart = Math.min(aStart, aE.getValue());
		}
		if(aTotal <= 0) {
			return null;
		}
		return new long[] {aDone, aTotal, System.currentTimeMillis()-aStart};
	}

	public void createWorkCopy(String aPathIn,String aPathWork,int aWidth,int aHeight,double aFps) {
		createWorkCopy(aPathIn, aPathWork, aWidth, aHeight, aFps, null);
	}

	/** aDone (may be null) runs in the thread of the encoding once it is over, made or failed */
	public void createWorkCopy(String aPathIn,String aPathWork,int aWidth,int aHeight,double aFps,Runnable aDone) {
		Thread aTh = new Thread(new Runnable() {
			@Override
			public void run() {
				try {
					String aPI = aPathIn;
					String aPW = aPathWork;
					new File(aPW).getAbsoluteFile().getParentFile().mkdirs();
					String ffmpegPath = getFfmpeg();
//					if(File.separatorChar == '/') {
//						ffmpegPath = ffmpegPath.replaceAll(" ", "\\");
//						aPI = aPI.replaceAll(" ", "\\");
//						aPW = aPW.replaceAll(" ", "\\");
//					}
//					else {
//						ffmpegPath = "\""+ffmpegPath+"\"";
//						aPI = "\""+aPI+"\"";
//						aPW = "\""+aPW+"\"";
//					}
//					String aCmd = ffmpegPath
//							+ " -i "+aPI
////							+ " -c:v copy"
//							+ " -s "+aWidth+"x"+aHeight
//							+ " -r "+aFps
//							+ " -an"//No audio
//							+ " -b:v 300k"//Low bitrate
//							//							+ " -threads auto"
//							+ " -vcodec libx264"
//							//							+ " -pix_fmt rgb24"
//							+ " "+StringUtils.path2processing(aPW);
					ArrayList<String> aCmdA = new ArrayList<String>();
					aCmdA.add(ffmpegPath);
					aCmdA.add("-i"); aCmdA.add(aPI);
					//HDR video: the copy is tone mapped to SDR BT.709 (read as such afterwards)
					String aSdr = sdrFilter(aPI);
					//Anaglyph: its layers are views, coded as RGB (libx264rgb: the 3 layers at the same quality; measured on
					//an HLG anaglyph, error of the red layer on fine details 1.1 / 255 against 8.0 in 4:2:0 and 2.9 in 4:4:4)
					boolean aAna = isAnaglyph(aPI);
					if(aAna) {
						//Tagged SDR in the frames (the encoder takes them from the filters, not from -color_trc)
						aSdr = (aSdr == null ? "format=gbrp" : aSdr)+",setparams=color_primaries=bt709:color_trc=bt709:colorspace=gbr";
						aCmdA.add("-vf"); aCmdA.add(aSdr);
						//Read back as SDR as it is (not tone mapped again: the HDR tags of the source would be copied)
						aCmdA.add("-color_primaries"); aCmdA.add("bt709");
						aCmdA.add("-color_trc"); aCmdA.add("bt709");
						aCmdA.add("-colorspace"); aCmdA.add("rgb");
						aSdr = null;
					}
					if(aSdr != null) {
						aCmdA.add("-vf"); aCmdA.add(aSdr);
						aCmdA.add("-color_primaries"); aCmdA.add("bt709");
						aCmdA.add("-color_trc"); aCmdA.add("bt709");
						aCmdA.add("-colorspace"); aCmdA.add("bt709");
					}
					aCmdA.add("-s"); aCmdA.add(aWidth+"x"+aHeight);
					aCmdA.add("-r"); aCmdA.add(""+aFps);
					aCmdA.add("-an"); //No audio
					//Constant quality (a fixed 300 kbit/s gave blocks at the work resolution of the monitor)
					aCmdA.add("-vcodec"); aCmdA.add(aAna ? "libx264rgb" : "libx264");
					aCmdA.add("-crf"); aCmdA.add("18");
					aCmdA.add("-preset"); aCmdA.add("veryfast");
					aCmdA.add("-pix_fmt"); aCmdA.add(aAna ? "gbrp" : "yuv420p");
					aCmdA.add(StringUtils.path2processing(aPW));
					String[] aCmd = new String[aCmdA.size()];
					StringBuffer aCmdSB = new StringBuffer();
					for(int c = 0;c < aCmdA.size();c++) {
						aCmd[c] = aCmdA.get(c);
						aCmdSB.append(aCmd[c]+" ");
					}
					System.out.println("cmd: "+aCmdSB.toString());
					ACTIVE.put(FfmpegTranscoder.this, System.currentTimeMillis());
					Process p = Runtime.getRuntime().exec(aCmd);
					StreamReader ffmpegOut = new StreamReader(p.getInputStream());
					StreamReader ffmpegErr = new StreamReader(p.getErrorStream());
					Thread aThOut = new Thread(ffmpegOut);
					aThOut.start();
					Thread aThIn = new Thread(ffmpegErr);
					aThIn.start();
					int aExit = p.waitFor();
					File aProcessing = new File(StringUtils.path2processing(aPW.replaceAll("\"", "")));
					if(aExit != 0 || aProcessing.length() == 0) {
						//Failed: no work copy, the original is read
						System.err.println("Work copy failed (ffmpeg exit "+aExit+"): "+aPI);
						aProcessing.delete();
						ACTIVE.remove(FfmpegTranscoder.this);
						if(aDone != null) {
							aDone.run();
						}
						return;
					}
					Files.move(Paths.get(StringUtils.path2processing(aPW.replaceAll("\"", "")))
							,Paths.get(aPW.replaceAll("\"", "")),StandardCopyOption.REPLACE_EXISTING);
					aThIn.interrupt();
					aThOut.interrupt();
					ffmpegOut.inputStream.close();
					ffmpegErr.inputStream.close();
					p.destroy();//Be sure it's killed
				}
				catch(Throwable t) {
					t.printStackTrace(System.err);
				}
				ACTIVE.remove(FfmpegTranscoder.this);
				if(aDone != null) {
					try {
						aDone.run();
					}
					catch(Throwable t) {
						t.printStackTrace(System.err);
					}
				}
			}
		});
		aTh.start();
	}
}
