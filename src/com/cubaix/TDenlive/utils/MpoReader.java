package com.cubaix.TDenlive.utils;

import java.awt.image.BufferedImage;
import java.io.BufferedInputStream;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.util.Vector;

import javax.imageio.ImageIO;

public class MpoReader {
    int pos = 0;
	int posSplit = -1;
	
    public MpoReader() {
	}
    
    public BufferedImage[] process(String aPath) throws Exception {
    	long aTime = System.currentTimeMillis();
    	
    	File aFile = new File(aPath);
        final int aBufSize = (int)aFile.length();
    	final BufferedInputStream aFIS = new BufferedInputStream(new FileInputStream(aFile),aBufSize);
    	final byte[] aBuf = new byte[aBufSize];
    	final int[] aMarker = new int[] {0xFF, 0xD8, 0xFF, 0xE1};
    	Vector<Integer> aMarkerPos = new Vector<Integer>();
        int aPosMarker = 0;
		aFIS.read(aBuf);
		pos = aBufSize/3;
    	while(pos < aBufSize) {
    		if((aBuf[pos]&0xFF) == aMarker[aPosMarker]) {
    			aPosMarker++;
    		}
    		else {
    			if(aPosMarker > 0) {
    				pos -= aPosMarker;
    				if(pos < 0) {
    					pos = 0;
    				}
    				aPosMarker = 0;
    			}
    		}
    		pos++;
    		if(aPosMarker == 4) {
    			System.out.println("Found marker: "+(pos-4));
    			aPosMarker = 0;
    			aMarkerPos.add(pos-4);
    		}
    	}
    	aFIS.close();
    	
    	System.out.println("End: "+pos);
    	
    	System.out.println("Duration1: "+(System.currentTimeMillis()-aTime)+" ms");

    	posSplit = aMarkerPos.size() >= 2 ? aMarkerPos.elementAt(1):aMarkerPos.elementAt(0);

    	ByteArrayInputStream aBAIS = new ByteArrayInputStream(aBuf);
    	
    	BufferedImage aBI1 = ImageIO.read(aBAIS);
    	
    	System.out.println("Duration2: "+(System.currentTimeMillis()-aTime)+" ms");
    	
    	aBAIS.reset();
    	aBAIS.skip(posSplit);

    	BufferedImage aBI2 = ImageIO.read(aBAIS);
    	
    	aBAIS.close();

    	System.out.println("Duration3: "+(System.currentTimeMillis()-aTime)+" ms");
    	System.out.println("");
    	
    	return new BufferedImage[] {aBI1,aBI2};
    }
    
    public static void main(String[] args) {
    	try {
    		for(int i = 0;i < 10;i++) {
    			new MpoReader().process("/home/etienne/tmp/STEREO/BobAldridge_P1010366.MPO");
    		}
    	}
    	catch(Throwable t) {
    		t.printStackTrace(System.err);
    	}
    }
}
