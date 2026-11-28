package com.cubaix.TDenlive.ffmpeg;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Vector;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Characteristics of a video or sound file as ffmpeg describes its input (ffmpeg -i): container, duration, bit rate,
 * tags, then each stream (codec, resolution, frame rate, pixel format, colors and HDR, sample rate, channels,
 * language, tags). Lines "[Group] Name - value" of the metadata tree of the media panel (MediaPropsGUI), the names
 * translated by aLng ("MediaInfo.*" keys). Cached by path, size and date.
 */
public class MediaInfo {
	static final HashMap<String,String> CACHE = new HashMap<String,String>();
	static final Pattern INPUT = Pattern.compile("Input #\\d+, ([^,]+(?:,[^,\\s]+)*), from .*");
	static final Pattern DURATION = Pattern.compile("Duration: ([^,]+), start: [^,]+, bitrate: (.+)");
	static final Pattern STREAM = Pattern.compile("Stream #\\d+:(\\d+)(?:\\[[^\\]]*\\])?(?:\\(([a-z]{2,3})\\))?: (\\w+): (.*)");
	static final Pattern META = Pattern.compile("([^:]+?)\\s*: (.*)");
	static final Pattern SIZE = Pattern.compile("(\\d+)x(\\d+)(?: \\[SAR ([\\d:]+) DAR ([\\d:]+)\\]| SAR ([\\d:]+) DAR ([\\d:]+))?");
	static final Pattern PIXFMT = Pattern.compile("(\\w+)(?:\\((.*)\\))?");

	/** Description lines of aPath, "" when ffmpeg is missing or can't read it */
	public static String describe(String aPath, Function<String,String> aLng, String aUiLng) {
		if(aPath == null) {
			return "";
		}
		File aF = new File(aPath);
		String aKey = aF.getAbsolutePath()+"|"+aF.length()+"|"+aF.lastModified();
		synchronized(CACHE) {
			String aOut = CACHE.get(aKey);
			if(aOut == null) {
				aOut = probe(aPath);
				CACHE.put(aKey, aOut);
			}
			return parse(aOut, aF, aLng, aUiLng);
		}
	}

	static String probe(String aPath) {
		String aFfmpeg = Ffmpeg.ffmpegPath();
		if(aFfmpeg == null || !new File(aPath).isFile()) {
			return "";
		}
		try {
			Process aP = new ProcessBuilder(aFfmpeg, "-nostdin", "-hide_banner", "-i", aPath).redirectErrorStream(true).start();
			String aOut = new String(aP.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
			aP.waitFor();
			return aOut;
		}
		catch(Throwable t) {
			t.printStackTrace(System.err);
			return "";
		}
	}

	/** The parts of a stream description separated by commas outside the parentheses / brackets */
	static Vector<String> split(String aS) {
		Vector<String> aParts = new Vector<String>();
		int aDepth = 0;
		StringBuilder aCur = new StringBuilder();
		for(char c : aS.toCharArray()) {
			if(c == '(' || c == '[') {
				aDepth++;
			}
			else if((c == ')' || c == ']') && aDepth > 0) {
				aDepth--;
			}
			if(c == ',' && aDepth == 0) {
				aParts.add(aCur.toString().trim());
				aCur.setLength(0);
				continue;
			}
			aCur.append(c);
		}
		if(aCur.toString().trim().length() > 0) {
			aParts.add(aCur.toString().trim());
		}
		return aParts;
	}

	static String hdrOf(String aTransfer, String aPrimaries) {
		if(aTransfer == null) {
			return null;
		}
		if(aTransfer.contains("smpte2084")) {
			return "HDR10 (PQ)";
		}
		if(aTransfer.contains("arib-std-b67")) {
			return "HLG";
		}
		return null;
	}

	static String parse(String aOut, File aF, Function<String,String> aLng, String aUiLng) {
		if(aOut == null || aOut.isEmpty()) {
			return "";
		}
		StringBuilder aSB = new StringBuilder();
		String aFile = "["+aLng.apply("MediaInfo.File")+"] ";
		String aTags = "["+aLng.apply("MediaInfo.Tags")+"] ";
		StringBuilder aStreams = new StringBuilder();
		String aGroup = null;//Group of the stream being read, null for the global part
		boolean aInMeta = false;
		int aNbV = 0, aNbA = 0, aNbS = 0;
		for(String aLine : aOut.split("\\R")) {
			String aL = aLine.trim();
			if(aL.startsWith("Output #") || aL.startsWith("At least one output")) {
				break;
			}
			Matcher aM = INPUT.matcher(aL);
			if(aM.matches()) {
				aSB.append(aFile).append(aLng.apply("MediaInfo.Container")).append(" - ").append(aM.group(1)).append("\n");
				aSB.append(aFile).append(aLng.apply("MediaInfo.FileSize")).append(" - ")
					.append(String.format("%.1f MB", aF.length()/(1024.0*1024.0))).append("\n");
				continue;
			}
			aM = DURATION.matcher(aL);
			if(aM.matches()) {
				aSB.append(aFile).append(aLng.apply("MediaInfo.Duration")).append(" - ").append(aM.group(1)).append("\n");
				if(!"N/A".equals(aM.group(2).trim())) {
					aSB.append(aFile).append(aLng.apply("MediaInfo.Bitrate")).append(" - ").append(aM.group(2).trim()).append("\n");
				}
				aInMeta = false;
				continue;
			}
			aM = STREAM.matcher(aL);
			if(aM.matches()) {
				String aKind = aM.group(3);
				Vector<String> aParts = split(aM.group(4));
				String aDefault = aL.contains("(default)") ? " "+aLng.apply("MediaInfo.Default") : "";
				if("Video".equals(aKind)) {
					aGroup = "["+aLng.apply("MediaInfo.Video")+" "+(++aNbV)+aDefault+"] ";
				}
				else if("Audio".equals(aKind)) {
					aGroup = "["+aLng.apply("MediaInfo.Audio")+" "+(++aNbA)+aDefault+"] ";
				}
				else if("Subtitle".equals(aKind)) {
					aGroup = "["+aLng.apply("MediaInfo.Subtitle")+" "+(++aNbS)+aDefault+"] ";
				}
				else {
					aGroup = "["+aKind+" "+aM.group(1)+"] ";
				}
				StringBuilder aS = aStreams;
				if(aParts.size() > 0) {
					aS.append(aGroup).append(aLng.apply("MediaInfo.Codec")).append(" - ").append(aParts.get(0)).append("\n");
				}
				if(aM.group(2) != null && !"und".equals(aM.group(2))) {
					aS.append(aGroup).append(aLng.apply("MediaInfo.Language")).append(" - ").append(AudioTracks.languageName(aM.group(2), aUiLng)).append("\n");
				}
				if("Video".equals(aKind)) {
					//Size, aspect, frame rate and bit rate first, then the pixels and colors
					for(int p = 1;p < aParts.size();p++) {
						String aP = aParts.get(p).replace("(default)", "").trim();
						Matcher aSz = SIZE.matcher(aP);
						if(aP.matches("\\d+x\\d+.*") && aSz.lookingAt()) {
							aS.append(aGroup).append(aLng.apply("MediaInfo.Resolution")).append(" - ").append(aSz.group(1)).append(" x ").append(aSz.group(2)).append("\n");
							String aDar = aSz.group(4) != null ? aSz.group(4) : aSz.group(6);
							if(aDar != null) {
								aS.append(aGroup).append(aLng.apply("MediaInfo.Aspect")).append(" - ").append(aDar).append("\n");
							}
						}
						else if(aP.matches("SAR [\\d:]+ DAR [\\d:]+")) {
							aS.append(aGroup).append(aLng.apply("MediaInfo.Aspect")).append(" - ").append(aP.replaceAll(".*DAR ", "")).append("\n");
						}
						else if(aP.endsWith(" fps")) {
							aS.append(aGroup).append(aLng.apply("MediaInfo.Fps")).append(" - ").append(aP.substring(0, aP.length()-4)).append("\n");
						}
						else if(aP.endsWith(" kb/s")) {
							aS.append(aGroup).append(aLng.apply("MediaInfo.Bitrate")).append(" - ").append(aP).append("\n");
						}
					}
					Matcher aPx = aParts.size() > 1 ? PIXFMT.matcher(aParts.get(1).trim()) : null;
					if(aPx != null && aPx.matches() && !aParts.get(1).matches("\\d+x\\d+.*")) {
						String aFmt = aPx.group(1);
						aS.append(aGroup).append(aLng.apply("MediaInfo.PixFmt")).append(" - ").append(aFmt).append("\n");
						Matcher aBits = Pattern.compile("(\\d{2})(?:le|be)$").matcher(aFmt);
						String aDepth = aBits.find() ? aBits.group(1) : (aFmt.contains("yuv") || aFmt.contains("rgb") || aFmt.contains("nv12") ? "8" : null);
						if(aDepth != null) {
							aS.append(aGroup).append(aLng.apply("MediaInfo.BitDepth")).append(" - ").append(aDepth).append("\n");
						}
						String aTransfer = null, aPrimaries = null;
						if(aPx.group(2) != null) {
							for(String aC : aPx.group(2).split(",")) {
								aC = aC.trim();
								if(aC.equals("tv") || aC.equals("pc")) {
									aS.append(aGroup).append(aLng.apply("MediaInfo.Range")).append(" - ")
										.append(aLng.apply(aC.equals("tv") ? "MediaInfo.RangeTv" : "MediaInfo.RangePc")).append("\n");
								}
								else if(aC.equals("progressive")) {
									aS.append(aGroup).append(aLng.apply("MediaInfo.Scan")).append(" - ").append(aLng.apply("MediaInfo.Progressive")).append("\n");
								}
								else if(aC.matches("tt|bb|tb|bt|top first|bottom first|top coded first.*|bottom coded first.*")) {
									aS.append(aGroup).append(aLng.apply("MediaInfo.Scan")).append(" - ").append(aLng.apply("MediaInfo.Interlaced")).append(" (").append(aC).append(")\n");
								}
								else if(!aC.isEmpty()) {
									//matrix/primaries/transfer, or a single space for the three
									aS.append(aGroup).append(aLng.apply("MediaInfo.Colors")).append(" - ").append(aC).append("\n");
									String[] aCs = aC.split("/");
									aPrimaries = aCs.length > 1 ? aCs[1] : aC;
									aTransfer = aCs.length > 2 ? aCs[2] : aC;
								}
							}
						}
						String aHdr = hdrOf(aTransfer, aPrimaries);
						aS.append(aGroup).append(aLng.apply("MediaInfo.Dynamic")).append(" - ").append(aHdr != null ? aHdr : "SDR").append("\n");
					}
				}
				else if("Audio".equals(aKind)) {
					for(int p = 1;p < aParts.size();p++) {
						String aP = aParts.get(p).replace("(default)", "").trim();
						if(aP.endsWith(" Hz")) {
							aS.append(aGroup).append(aLng.apply("MediaInfo.SampleRate")).append(" - ").append(aP).append("\n");
						}
						else if(aP.endsWith(" kb/s")) {
							aS.append(aGroup).append(aLng.apply("MediaInfo.Bitrate")).append(" - ").append(aP).append("\n");
						}
						else if(p == 2) {
							aS.append(aGroup).append(aLng.apply("MediaInfo.Channels")).append(" - ").append(aP).append("\n");
						}
						else if(p == 3) {
							aS.append(aGroup).append(aLng.apply("MediaInfo.SampleFormat")).append(" - ").append(aP).append("\n");
						}
					}
				}
				aInMeta = false;
				continue;
			}
			if(aL.equals("Metadata:")) {
				aInMeta = true;
				continue;
			}
			if(aL.equals("Side data:") || aL.startsWith("Chapter")) {
				aInMeta = false;
				continue;
			}
			if(aInMeta) {
				Matcher aMM = META.matcher(aL);
				if(aMM.matches() && !aMM.group(2).trim().isEmpty()) {
					String aK = aMM.group(1).trim();
					String aV = aMM.group(2).trim();
					if(aGroup == null) {
						aSB.append(aTags).append(aK).append(" - ").append(aV).append("\n");
					}
					else {
						aStreams.append(aGroup).append(aK).append(" - ").append(aV).append("\n");
					}
				}
			}
		}
		aSB.append(aStreams);
		return aSB.toString();
	}
}
