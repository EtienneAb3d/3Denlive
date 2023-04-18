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
		Thread aTh = new Thread(new Runnable() {
			@Override
			public void run() {
				try {
					String aPI = aPathIn;
					String aPW = aPathWork;
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
					aCmdA.add("-b:v"); aCmdA.add("300k");
					aCmdA.add("-vcodec"); aCmdA.add("libx264");
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
					p.waitFor();
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
			}
		});
		aTh.start();
	}
}
