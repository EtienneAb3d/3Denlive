package com.cubaix.TDenlive.processors;

import java.awt.AlphaComposite;
import java.awt.Color;
import java.awt.Composite;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.Shape;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferFloat;
import java.awt.image.DataBufferInt;
import java.io.BufferedWriter;
import java.util.Vector;

import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.graphics.Rectangle;

import com.cubaix.TDenlive.TDConfig;
import com.cubaix.TDenlive.TDenlive;
import com.cubaix.TDenlive.medias.Media;
import com.cubaix.TDenlive.utils.ImageUtils;
import com.cubaix.TDenlive.xml.XmlObject;
import com.cubaix.TDenlive.xml.XmlTag;

public class Cropper extends Processor {
	public double leftL = 0.0;
	public double leftR = 0.0;
	public double rightL = 1.0;
	public double rightR = 1.0;
	public double top = 0.0;
	public double bottom = 1.0;
	public double angle = 0.0;
	public double shearXL = 0.0;
	public double shearXR = 0.0;
	public double transparency = 1.0;
	
	public boolean showCutLines = true;

	public Cropper(TDenlive aTDe, Media aTargetMedia) {
		super(aTDe, aTargetMedia);
		isExpended = true;
	}

	@Override
	public String getClassName() {return "Cropper";}

	@Override
	public int openProject(Vector<XmlObject> aOs, int o) throws Exception {
		for(;o < aOs.size();o++) {
			XmlObject aO = aOs.elementAt(o);
			if(aO instanceof XmlTag) {
				XmlTag aT = (XmlTag)aO;
				if("/Cropper".equalsIgnoreCase(aT.tagName)) {
					return o;
				}
				if("showCutLines".equalsIgnoreCase(aT.tagName)) {
					o++;
					showCutLines = "true".equals(aOs.elementAt(o).text);
				}
				if("leftL".equalsIgnoreCase(aT.tagName)) {
					o++;
					leftL = Double.parseDouble(aOs.elementAt(o).text);
				}
				if("leftR".equalsIgnoreCase(aT.tagName)) {
					o++;
					leftR = Double.parseDouble(aOs.elementAt(o).text);
				}
				if("rightL".equalsIgnoreCase(aT.tagName)) {
					o++;
					rightL = Double.parseDouble(aOs.elementAt(o).text);
				}
				if("rightR".equalsIgnoreCase(aT.tagName)) {
					o++;
					rightR = Double.parseDouble(aOs.elementAt(o).text);
				}
				if("top".equalsIgnoreCase(aT.tagName)) {
					o++;
					top = Double.parseDouble(aOs.elementAt(o).text);
				}
				if("bottom".equalsIgnoreCase(aT.tagName)) {
					o++;
					bottom = Double.parseDouble(aOs.elementAt(o).text);
				}
				if("angle".equalsIgnoreCase(aT.tagName)) {
					o++;
					angle = Double.parseDouble(aOs.elementAt(o).text);
				}
				if("shearXL".equalsIgnoreCase(aT.tagName)) {
					o++;
					shearXL = Double.parseDouble(aOs.elementAt(o).text);
				}
				if("shearXR".equalsIgnoreCase(aT.tagName)) {
					o++;
					shearXR = Double.parseDouble(aOs.elementAt(o).text);
				}
				if("transparency".equalsIgnoreCase(aT.tagName)) {
					o++;
					transparency = Double.parseDouble(aOs.elementAt(o).text);
				}
			}
		}
		return o;
	}

	@Override
	public void saveProject(StringBuffer aSB) throws Exception {
		aSB.append("						<Cropper>\n");
		aSB.append("							<showCutLines>" + showCutLines + "</showCutLines>\n");
		aSB.append("							<leftL>" + leftL + "</leftL>\n");
		aSB.append("							<leftR>" + leftR + "</leftR>\n");
		aSB.append("							<rightL>" + rightL + "</rightL>\n");
		aSB.append("							<rightR>" + rightR + "</rightR>\n");
		aSB.append("							<top>" + top + "</top>\n");
		aSB.append("							<bottom>" + bottom + "</bottom>\n");
		aSB.append("							<angle>" + angle + "</angle>\n");
		aSB.append("							<shearXL>" + shearXL + "</shearXL>\n");
		aSB.append("							<shearXR>" + shearXR + "</shearXR>\n");
		aSB.append("							<transparency>" + transparency + "</transparency>\n");
		aSB.append("						</Cropper>\n");
	}

	@Override
	void setDescr() {
		name = tde.gui.lngs.get("Processor.Cropper");
		icon = "rectFrame.gif";
	}

	public double getLeftL() {
		return leftL;
	}

	public void setLeftL(double leftL) {
		this.leftL = leftL;
	}

	public double getLeftR() {
		return leftR;
	}

	public void setLeftR(double leftR) {
		this.leftR = leftR;
	}

	public double getRightL() {
		return rightL;
	}

	public void setRightL(double rightL) {
		this.rightL = rightL;
	}

	public double getRightR() {
		return rightR;
	}

	public void setRightR(double rightR) {
		this.rightR = rightR;
	}

	public double getTop() {
		return top;
	}

	public void setTop(double top) {
		this.top = top;
	}

	public double getBottom() {
		return bottom;
	}

	public void setBottom(double bottom) {
		this.bottom = bottom;
	}

	public double getAngle() {
		return angle;
	}

	public void setAngle(double angle) {
		this.angle = angle;
	}

	public double getShearX() {
		return shearXL;
	}

	public void setShearX(double shearX) {
		this.shearXL = shearX;
	}

	public double getShearY() {
		return shearXR;
	}

	public void setShearY(double shearY) {
		this.shearXR = shearY;
	}

	public double getTransparency() {
		return transparency;
	}

	public void setTransparency(double transparency) {
		this.transparency = transparency;
	}

	public boolean isShowCutLines() {
		return showCutLines;
	}

	public void setShowCutLines(boolean showCutLines) {
		this.showCutLines = showCutLines;
	}

	@Override
	public Image process2Swt(Media aMedia, int aProcessingMode, Rectangle aTargetSize, long aTime) {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public void process2Awt(Media aMedia, int aProcessingMode, long aTime) {
		try {
			Thread aThL = new Thread(new Runnable() {
				@Override
				public void run() {
					BufferedImage aBIL = aMedia.getWorkLeft(aProcessingMode,aTime);
					BufferedImage aProcessedBIL = 
//							tde.config.processingHdr[aProcessingMode]?
//							processAwtHdr(aBIL,aProcessingMode,true):
								processAwtImage(aBIL,aProcessingMode,true,tde.config.processingHdr[aProcessingMode]);
					aMedia.setWorkLeft(aProcessedBIL);
				}
			});
			aThL.start();
			Thread aThR = new Thread(new Runnable() {
				@Override
				public void run() {
					BufferedImage aBIR = aMedia.getWorkRight(aProcessingMode,aTime);
					BufferedImage aProcessedBIR = 
//							tde.config.processingHdr[aProcessingMode]?
//							processAwtHdr(aBIR,aProcessingMode,false):
								processAwtImage(aBIR,aProcessingMode,false,tde.config.processingHdr[aProcessingMode]);
					aMedia.setWorkRight(aProcessedBIR);
				}
			});
			aThR.start();
			aThL.join();
			aThR.join();
		}
		catch(Throwable t) {
			t.printStackTrace(System.err);
		}
	}

	BufferedImage processAwtImage(BufferedImage aBI,int aProcessingMode,boolean aIsL,boolean aIsHdr) {
		int aWidth = aBI.getWidth();
		int aHeight = aBI.getHeight();
		BufferedImage aFBI = aIsHdr?ImageUtils.createHdr(aWidth, aHeight)
				:ImageUtils.createImage(aWidth, aHeight);

		AffineTransform aRotL = null;
		AffineTransform aRotR = null;
		if(aIsL && (angle != 0 || shearXL != 0)) {
			aRotL = AffineTransform.getTranslateInstance(aWidth/2, aHeight/2);
			aRotL.concatenate(AffineTransform.getRotateInstance(Math.PI*angle/180.0));
			aRotL.concatenate(AffineTransform.getShearInstance(shearXL, 0));
			aRotL.concatenate(AffineTransform.getTranslateInstance(-aWidth/2, -aHeight/2));
		}
		if(!aIsL && (angle != 0 || shearXR != 0)) {
			aRotR = AffineTransform.getTranslateInstance(aWidth/2, aHeight/2);
			aRotR.concatenate(AffineTransform.getRotateInstance(Math.PI*angle/180.0));
			aRotR.concatenate(AffineTransform.getShearInstance(shearXR, 0));
			aRotR.concatenate(AffineTransform.getTranslateInstance(-aWidth/2, -aHeight/2));
		}
		
		//Create mask
		Graphics2D aFG = (Graphics2D)aFBI.getGraphics();
		aFG.setColor(Color.WHITE);
		aFG.fillRect(0, 0, aWidth, aHeight);
		if(!tde.config.fastMode || aProcessingMode == TDConfig.PROCESSING_MODE_RENDER) {
			aFG.setRenderingHint(RenderingHints.KEY_ANTIALIASING,RenderingHints.VALUE_ANTIALIAS_ON);
			aFG.setRenderingHint(RenderingHints.KEY_RENDERING,RenderingHints.VALUE_RENDER_QUALITY);
		}
		
		aFG.setColor(new Color((int)(255-transparency*255)));//Blue mask
		if(aIsL) {
			if(aRotL != null) {
				try {
					aFG.setTransform(aRotL.createInverse());
				}
				catch(Throwable t) {
					t.printStackTrace(System.err);
				}
			}
			if(leftL > 0) {
				aFG.fillRect(-aWidth, -aHeight, (int)(leftL*aWidth)+aWidth, 3*aHeight);
			}
			if(rightL < 1) {
				aFG.fillRect((int)(rightL*aWidth), -aHeight,2*aWidth, 3*aHeight);
			}
		}
		else {
			if(aRotR != null) {
				try {
					aFG.setTransform(aRotR.createInverse());
				}
				catch(Throwable t) {
					t.printStackTrace(System.err);
				}
			}
			if(leftR > 0) {
				aFG.fillRect(-aWidth, -aHeight, (int)(leftR*aWidth)+aWidth, 3*aHeight);
			}
			if(rightR < 1) {
				aFG.fillRect((int)(rightR*aWidth), -aHeight,2*aWidth, 3*aHeight);
			}
		}
		if(top > 0) {
			aFG.fillRect(-aWidth, -aHeight, 3*aWidth, aHeight+(int)(top*aHeight));
		}
		if(bottom < 1) {
			aFG.fillRect(-aWidth, (int)(bottom*aHeight), 3*aWidth, 2*aHeight);
		}
		
		//Apply mask
		if(aIsHdr) {
			float[] aDB = ((DataBufferFloat)aBI.getRaster().getDataBuffer()).getData();
			float[] aFDB = ((DataBufferFloat)aFBI.getRaster().getDataBuffer()).getData();
			for(int x = 0;x<aWidth;x++) {
				for(int y = 0;y<aHeight;y++) {
					float aR = aDB[y*aWidth*4+x*4];//Red
					float aG = aDB[y*aWidth*4+x*4+1];//Green
					float aB = aDB[y*aWidth*4+x*4+2];//Blue
					float aAlpha = aDB[y*aWidth*4+x*4+3]//Alpha
							*aFDB[y*aWidth*4+x*4+2];//Blue
				    aFDB[y*aWidth*4+x*4]=(float)aR;//Red
				    aFDB[y*aWidth*4+x*4+1]=(float)aG;//Green
				    aFDB[y*aWidth*4+x*4+2]=(float)aB;//Blue
				    aFDB[y*aWidth*4+x*4+3]=aAlpha;//Alpha
				}
			}
		}
		else {
			int[] aDB = ((DataBufferInt)aBI.getRaster().getDataBuffer()).getData();
			int[] aFDB = ((DataBufferInt)aFBI.getRaster().getDataBuffer()).getData();
			for(int p = 0;p < aDB.length;p++) {
				double aAlpha = 255.0
						*(((aDB[p]>>24)&0xFF)/255.0) 
						*((aFDB[p] & 0xFF)/255.0);
				aAlpha = aAlpha < 0 ? 0 : (aAlpha > 255 ? 255 : aAlpha);
				aFDB[p] =
						//					aDB[p];
						//					aFDB[p];
						(((int)aAlpha)&0xFF)<<24
						|(aDB[p]&0xFFFFFF);
			}
		}
			
		if(showCutLines && aProcessingMode == TDConfig.PROCESSING_MODE_WORK) {
			aFG = (Graphics2D)aFBI.getGraphics();
			if(!tde.config.fastMode || aProcessingMode == TDConfig.PROCESSING_MODE_RENDER) {
				aFG.setRenderingHint(RenderingHints.KEY_ANTIALIASING,RenderingHints.VALUE_ANTIALIAS_ON);
				aFG.setRenderingHint(RenderingHints.KEY_RENDERING,RenderingHints.VALUE_RENDER_QUALITY);
			}
			aFG.setColor(Color.YELLOW);
			if(aIsL) {
				if(aRotL != null) {
					try {
						aFG.setTransform(aRotL.createInverse());
					}
					catch(Throwable t) {
						t.printStackTrace(System.err);
					}
				}
				if(leftL > 0) {
					aFG.drawLine((int)(leftL*aWidth), 0,(int)(leftL*aWidth), aHeight);
				}
				if(rightL < 1) {
					aFG.drawLine((int)(rightL*aWidth), 0,(int)(rightL*aWidth), aHeight);
				}
			}
			else {
				if(aRotR != null) {
					try {
						aFG.setTransform(aRotR.createInverse());
					}
					catch(Throwable t) {
						t.printStackTrace(System.err);
					}
				}
				if(leftR > 0) {
					aFG.drawLine((int)(leftR*aWidth), 0,(int)(leftR*aWidth), aHeight);
				}
				if(rightR < 1) {
					aFG.drawLine((int)(rightR*aWidth), 0,(int)(rightR*aWidth), aHeight);
				}
			}
			if(top > 0) {
				aFG.drawLine(0,(int)(top*aHeight),aWidth,(int)(top*aHeight));
			}
			if(bottom < 1) {
				aFG.drawLine(0,(int)(bottom*aHeight),aWidth,(int)(bottom*aHeight));
			}
		}
		
		return aFBI;
	}

	
//	BufferedImage processAwtHdr(BufferedImage aBI,int aProcessingMode,boolean aIsL) {
//		int aWidth = aBI.getWidth();
//		int aHeight = aBI.getHeight();
//		BufferedImage aFBI = ImageUtils.createHdr(aWidth, aHeight);
//	float[] aDB = ((DataBufferFloat)aBI.getRaster().getDataBuffer()).getData();
//		float[] aFDB = ((DataBufferFloat)aFBI.getRaster().getDataBuffer()).getData();
//
//		AffineTransform aRotL = null;
//		AffineTransform aRotR = null;
//		if(aIsL && (angle != 0 || shearXL != 0)) {
//			aRotL = AffineTransform.getTranslateInstance(aWidth/2, aHeight/2);
//			aRotL.concatenate(AffineTransform.getRotateInstance(Math.PI*angle/180.0));
//			aRotL.concatenate(AffineTransform.getShearInstance(shearXL, 0));
//			aRotL.concatenate(AffineTransform.getTranslateInstance(-aWidth/2, -aHeight/2));
//		}
//		if(!aIsL && (angle != 0 || shearXR != 0)) {
//			aRotR = AffineTransform.getTranslateInstance(aWidth/2, aHeight/2);
//			aRotR.concatenate(AffineTransform.getRotateInstance(Math.PI*angle/180.0));
//			aRotR.concatenate(AffineTransform.getShearInstance(shearXR, 0));
//			aRotR.concatenate(AffineTransform.getTranslateInstance(-aWidth/2, -aHeight/2));
//		}
//		for(int aX = 0;aX<aWidth;aX++) {
//			for(int aY = 0;aY<aHeight;aY++) {
//				int x = aX;
//				int y = aY;
//				float aR = aDB[y*aWidth*4+x*4];//Red
//				float aG = aDB[y*aWidth*4+x*4+1];//Green
//				float aB = aDB[y*aWidth*4+x*4+2];//Blue
//				float aAlpha = aDB[y*aWidth*4+x*4+3];//Alpha
//
//			    double aNewR = aR,aNewG = aG,aNewB = aB;
//
//			    if(aIsL) {
//					if(aRotL != null) {
//						Shape aS = aRotL.createTransformedShape(new java.awt.Rectangle(aX, aY,aX, aY));
//						double[] aSeg = new double[4];
//						aS.getPathIterator(null).currentSegment(aSeg);
//						x = (int)aSeg[0];
//						y = (int)aSeg[1];
//					}
//			    	if(leftL > 0 && x < leftL*aWidth) {
//			    		aAlpha = 0;
//			    	}
//			    	if(rightL < 1 && x > rightL*aWidth) {
//			    		aAlpha = 0;
//			    	}
//			    }
//			    else {
//					if(aRotR != null) {
//						Shape aS = aRotR.createTransformedShape(new java.awt.Rectangle(aX, aY,aX, aY));
//						double[] aSeg = new double[4];
//						aS.getPathIterator(null).currentSegment(aSeg);
//						x = (int)aSeg[0];
//						y = (int)aSeg[1];
//					}
//			    	if(leftR > 0 && x < leftR*aWidth) {
//			    		aAlpha = 0;
//			    	}
//			    	if(rightR < 1 && x > rightR*aWidth) {
//			    		aAlpha = 0;
//			    	}
//			    }
//		    	if(top > 0 && y < top*aHeight) {
//		    		aAlpha = 0;
//		    	}
//		    	if(bottom < 1 && y > bottom*aHeight) {
//		    		aAlpha = 0;
//		    	}
//			    
//			    aFDB[aY*aWidth*4+aX*4]=(float)aNewR;//Red
//			    aFDB[aY*aWidth*4+aX*4+1]=(float)aNewG;//Green
//			    aFDB[aY*aWidth*4+aX*4+2]=(float)aNewB;//Blue
//			    aFDB[aY*aWidth*4+aX*4+3]=aAlpha;//Alpha
//			}
//		}
//		
//		if(showCutLines && aProcessingMode == TDConfig.PROCESSING_MODE_WORK) {
//			Graphics2D aG = (Graphics2D)aFBI.getGraphics();
//			aG.setColor(Color.YELLOW);
//			if(aIsL) {
//				if(aRotL != null) {
//					try {
//						aG.setTransform(aRotL.createInverse());
//					}
//					catch(Throwable t) {
//						t.printStackTrace(System.err);
//					}
//				}
//				aG.drawLine((int)(leftL*aWidth), 0,(int)(leftL*aWidth), aHeight);
//				aG.drawLine((int)(rightL*aWidth), 0,(int)(rightL*aWidth), aHeight);
//				aG.drawLine(0,(int)(top*aHeight),aWidth,(int)(top*aHeight));
//				aG.drawLine(0,(int)(bottom*aHeight),aWidth,(int)(bottom*aHeight));
//			}
//			else {
//				if(aRotR != null) {
//					try {
//						aG.setTransform(aRotR.createInverse());
//					}
//					catch(Throwable t) {
//						t.printStackTrace(System.err);
//					}
//				}
//				aG.drawLine((int)(leftR*aWidth), 0,(int)(leftR*aWidth), aHeight);
//				aG.drawLine((int)(rightR*aWidth), 0,(int)(rightR*aWidth), aHeight);
//				aG.drawLine(0,(int)(top*aHeight),aWidth,(int)(top*aHeight));
//				aG.drawLine(0,(int)(bottom*aHeight),aWidth,(int)(bottom*aHeight));
//			}
//		}
//
//		return aFBI;
//
//	}
}
