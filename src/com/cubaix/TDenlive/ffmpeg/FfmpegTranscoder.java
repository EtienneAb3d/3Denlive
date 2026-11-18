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
		// TODO Auto-generated constructor stub
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
					aCmdA.add("-s"); aCmdA.add(aWidth+"x"+aHeight);
					aCmdA.add("-r"); aCmdA.add(""+aFps);
					aCmdA.add("-an"); //No audio
					//Constant quality (a fixed 300 kbit/s gave blocks at the work resolution of the monitor)
					aCmdA.add("-vcodec"); aCmdA.add("libx264");
					aCmdA.add("-crf"); aCmdA.add("18");
					aCmdA.add("-preset"); aCmdA.add("veryfast");
					aCmdA.add("-pix_fmt"); aCmdA.add("yuv420p");
					aCmdA.add(StringUtils.path2processing(aPW));
					String[] aCmd = new String[aCmdA.size()];
					StringBuffer aCmdSB = new StringBuffer();
					for(int c = 0;c < aCmdA.size();c++) {
						aCmd[c] = aCmdA.get(c);
						aCmdSB.append(aCmd[c]+" ");
					}
					System.out.println("cmd: "+aCmdSB.toString());
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
