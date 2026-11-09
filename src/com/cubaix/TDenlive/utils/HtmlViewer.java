package com.cubaix.TDenlive.utils;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import com.cubaix.TDenlive.TDenlive;

/**
 * HTML pages written next to a rendering or to the instant images, to view them with the 3Denlive stereo
 * player (res/html/stereoplayer.js, embedded in the page: it works offline, from the disk). The project name
 * is the main title, the player and the images take 80% of the page width. The media are referenced by their
 * file name: the page must stay in the same folder.
 */
public class HtmlViewer {
	/** Page of a parallel (side-by-side) video: the player alone. Returns the page, null on error. */
	public static File writeVideoPage(TDenlive aTDe, String aVideoPath) {
		try {
			File aVideo = new File(aVideoPath);
			File aHtml = htmlFile(aVideo);
			StringBuffer aSB = new StringBuffer();
			header(aTDe, aSB);
			aSB.append("<div class=\"view\"><div class=\"stereo-player\" data-src=\"").append(src(aVideo)).append("\"></div></div>\n");
			footer(aSB);
			Files.writeString(aHtml.toPath(), aSB.toString(), StandardCharsets.UTF_8);
			return aHtml;
		}
		catch(Throwable t) {
			t.printStackTrace(System.err);
			return null;
		}
	}

	/**
	 * Page of the instant images: the player on the parallel image, then each image under a centered title.
	 * aPaths = left, right, crossed, parallel (as TDeGUI.saveCurrentImage() writes them).
	 */
	public static File writeImagesPage(TDenlive aTDe, String[] aPaths) {
		try {
			String[] aTitles = new String[] {"Html.Left", "Html.Right", "Html.Crossed", "Html.Parallel"};
			File aParallel = new File(aPaths[3]);
			File aHtml = htmlFile(new File(aPaths[0].replaceAll("_Left[.]png$", ".png")));
			StringBuffer aSB = new StringBuffer();
			header(aTDe, aSB);
			aSB.append("<div class=\"view\"><div class=\"stereo-player\" data-type=\"image\" data-src=\"").append(src(aParallel)).append("\"></div></div>\n");
			for(int i = 0;i < aPaths.length;i++) {
				File aImg = new File(aPaths[i]);
				aSB.append("<h2>").append(esc(aTDe.gui.lngs.get(aTitles[i]))).append("</h2>\n");
				aSB.append("<div class=\"view\"><img src=\"").append(src(aImg)).append("\" alt=\"").append(esc(aImg.getName())).append("\"/>")
					.append("<div class=\"file\">").append(esc(aImg.getName())).append("</div></div>\n");
			}
			footer(aSB);
			Files.writeString(aHtml.toPath(), aSB.toString(), StandardCharsets.UTF_8);
			return aHtml;
		}
		catch(Throwable t) {
			t.printStackTrace(System.err);
			return null;
		}
	}

	/** Same folder and name as the media, .html extension */
	static File htmlFile(File aMedia) {
		String aName = aMedia.getName();
		int aDot = aName.lastIndexOf('.');
		if(aDot > 0) {
			aName = aName.substring(0, aDot);
		}
		return new File(aMedia.getAbsoluteFile().getParentFile(), aName+".html");
	}

	static void header(TDenlive aTDe, StringBuffer aSB) {
		String aTitle = aTDe.projectName == null ? "3Denlive" : new File(aTDe.projectName).getName().replaceAll("(?i)([.]3de)+$", "");
		String aLng = aTDe.config.lng == null ? "en" : aTDe.config.lng.toLowerCase();
		aSB.append("<!DOCTYPE html>\n<html lang=\"").append(esc(aLng)).append("\">\n<head>\n")
			.append("<meta charset=\"UTF-8\">\n")
			.append("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">\n")
			.append("<meta name=\"generator\" content=\"3Denlive ").append(esc(TDenlive.TDeVersion)).append("\">\n")
			.append("<title>").append(esc(aTitle)).append("</title>\n")
			.append("<script>var $lng = \"").append(aLng.replaceAll("[^a-z]", "")).append("\";</script>\n")
			.append("<style>\n")
			.append("body{font-family:Roboto,Arial,sans-serif;color:#00328d;background:#fff;margin:0;padding:16px}\n")
			.append("h1{text-align:center;margin:8px 0 24px}\n")
			.append("h2{text-align:center;font-size:1.3em;margin:40px 0 10px}\n")
			.append(".view{width:80%;margin:0 auto}\n")
			//Views at most 80% of the window height: images narrowed, player width from its image ratio (--sp-arn)
			.append(".view img{display:block;width:auto;height:auto;max-width:100%;max-height:80vh;margin:0 auto}\n")
			.append(".view .sp{max-width:calc(80vh * var(--sp-arn,1.778));margin:0 auto}\n")
			.append(".file{text-align:center;font-size:12px;color:#888;margin-top:6px}\n")
			.append("@media (max-width:600px){.view{width:100%}}\n")
			.append("</style>\n</head>\n<body>\n")
			.append("<h1>").append(esc(aTitle)).append("</h1>\n");
	}

	static void footer(StringBuffer aSB) throws Exception {
		aSB.append("<script>\n").append(playerJs()).append("\n</script>\n</body>\n</html>\n");
	}

	static String playerJs() throws Exception {
		try(InputStream aIs = HtmlViewer.class.getResourceAsStream("/com/cubaix/TDenlive/res/html/stereoplayer.js")) {
			ByteArrayOutputStream aBOS = new ByteArrayOutputStream();
			aIs.transferTo(aBOS);
			//A "</script" in the code would close the tag
			return aBOS.toString(StandardCharsets.UTF_8).replace("</script", "<\\/script");
		}
	}

	/** File name as a relative URL, escaped for an attribute */
	static String src(File aFile) {
		return esc(URLEncoder.encode(aFile.getName(), StandardCharsets.UTF_8).replace("+", "%20"));
	}

	static String esc(String aS) {
		if(aS == null) {
			return "";
		}
		return aS.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
	}
}
