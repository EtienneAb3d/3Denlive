package com.cubaix.TDenlive.utils;

import java.io.File;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Vector;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Subtitles of a SubRip (.srt) or WebVTT (.vtt) file: the cues with their times
 * (ms from the start of the video) and their text (one or several lines, \n separated).
 */
public class SubtitleFile {
	public static class Cue {
		public long startMS = 0;
		public long endMS = 0;
		public String text = "";

		public Cue(long aStartMS,long aEndMS,String aText) {
			startMS = aStartMS;
			endMS = aEndMS;
			text = aText;
		}
	}

	//[hh:]mm:ss[,.]mmm --> [hh:]mm:ss[,.]mmm
	static final Pattern TIMES = Pattern.compile(
			"((?:\\d+:)?\\d{1,2}:\\d{1,2}(?:[,.]\\d{1,3})?)\\s*-->\\s*((?:\\d+:)?\\d{1,2}:\\d{1,2}(?:[,.]\\d{1,3})?)");

	/**
	 * Cues of the file, sorted by start time (empty if none found)
	 */
	public static Vector<Cue> read(String aPath) throws Exception {
		String aContent = decode(Files.readAllBytes(new File(aPath).toPath()));
		String[] aLines = aContent.replace("\r\n", "\n").replace('\r', '\n').split("\n", -1);
		Vector<Cue> aCues = new Vector<Cue>();
		for(int l = 0;l < aLines.length;l++) {
			Matcher aM = TIMES.matcher(aLines[l]);
			if(!aM.find()) {
				continue;
			}
			long aStart = parseTime(aM.group(1));
			long aEnd = parseTime(aM.group(2));
			StringBuilder aText = new StringBuilder();
			for(l++;l < aLines.length && !aLines[l].trim().isEmpty();l++) {
				if(TIMES.matcher(aLines[l]).find()) {
					//Next cue without a blank line before it
					l--;
					break;
				}
				String aLine = clean(aLines[l]);
				if(aLine.isEmpty()) {
					continue;
				}
				if(aText.length() > 0) {
					aText.append('\n');
				}
				aText.append(aLine);
			}
			if(aText.length() > 0 && aEnd > aStart) {
				aCues.add(new Cue(aStart, aEnd, aText.toString()));
			}
		}
		aCues.sort((a, b) -> Long.compare(a.startMS, b.startMS));
		return aCues;
	}

	/**
	 * UTF-8 (with or without BOM), UTF-16 with a BOM, else Windows-1252
	 */
	static String decode(byte[] aBytes) {
		if(aBytes.length >= 2 && (aBytes[0] & 0xFF) == 0xFF && (aBytes[1] & 0xFF) == 0xFE) {
			return new String(aBytes, 2, aBytes.length-2, StandardCharsets.UTF_16LE);
		}
		if(aBytes.length >= 2 && (aBytes[0] & 0xFF) == 0xFE && (aBytes[1] & 0xFF) == 0xFF) {
			return new String(aBytes, 2, aBytes.length-2, StandardCharsets.UTF_16BE);
		}
		int aOff = 0;
		if(aBytes.length >= 3 && (aBytes[0] & 0xFF) == 0xEF && (aBytes[1] & 0xFF) == 0xBB && (aBytes[2] & 0xFF) == 0xBF) {
			aOff = 3;
		}
		try {
			return StandardCharsets.UTF_8.newDecoder()
					.onMalformedInput(CodingErrorAction.REPORT)
					.onUnmappableCharacter(CodingErrorAction.REPORT)
					.decode(ByteBuffer.wrap(aBytes, aOff, aBytes.length-aOff)).toString();
		}
		catch(CharacterCodingException e) {
			Charset aCS = Charset.isSupported("windows-1252") ? Charset.forName("windows-1252") : StandardCharsets.ISO_8859_1;
			return new String(aBytes, aCS);
		}
	}

	/**
	 * Without the formatting tags (<i>, <font ...>, <c.color>, {\an8}...), the spaces trimmed
	 */
	static String clean(String aLine) {
		return aLine.replaceAll("<[^>]*>", "").replaceAll("\\{\\\\[^}]*\\}", "")
				.replace("&nbsp;", " ").replace("&lt;", "<").replace("&gt;", ">").replace("&amp;", "&")
				.replace(' ', ' ').trim();
	}

	static long parseTime(String aT) {
		String[] aParts = aT.replace(',', '.').split(":");
		long aMS = 0;
		for(int p = 0;p < aParts.length-1;p++) {
			aMS = aMS*60+Long.parseLong(aParts[p].trim());
		}
		aMS *= 60*1000;
		String aSec = aParts[aParts.length-1].trim();
		int aDot = aSec.indexOf('.');
		if(aDot < 0) {
			return aMS+Long.parseLong(aSec)*1000;
		}
		String aFrac = (aSec.substring(aDot+1)+"00").substring(0, 3);
		return aMS+Long.parseLong(aSec.substring(0, aDot))*1000+Long.parseLong(aFrac);
	}

	/**
	 * Cue shown at aMS (ms from the start of the video), null if none: among the cues
	 * that cover aMS, the last started (the automatic subtitles overlap)
	 */
	public static Cue cueAt(Vector<Cue> aCues,long aMS) {
		Cue aFound = null;
		for(Cue aC : aCues) {
			if(aC.startMS > aMS) {
				break;
			}
			if(aMS < aC.endMS) {
				aFound = aC;
			}
		}
		return aFound;
	}
}
