package com.cubaix.TDenlive.ffmpeg;

import java.awt.image.BufferedImage;

public class VideoImage {
	public BufferedImage bi = null;
	public long timePos = -1;
	
	public VideoImage(BufferedImage aBI,long aTimePos) {
		bi = aBI;
		timePos = aTimePos;
	}
}
