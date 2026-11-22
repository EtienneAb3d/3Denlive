package com.cubaix.TDenlive.ffmpeg;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Locale;
import java.util.Vector;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Audio streams of a media file (several in an MP4 made by the AI remix, or in a film with several languages),
 * read from the description ffmpeg gives of its input (ffmpeg -i), cached by path, size and date.
 * Each stream: its name (title, else the handler name of an MP4 when it is not a default one), its language
 * (ISO 639-2 code, "und" ignored). The streams of a file made by the AI remix (ai.RemixGenerator.mux()) are named
 * "[3De] <name>" (REMIX_PREFIX, names in English: Original, Drums, Bass, Other, Vocals, Guitar, Piano; translated
 * when shown, TrackMixer.showTitle()): the original sound (the default stream, the first) and the tracks separated by
 * Demucs; the multi-track mixer (processors.TrackMixer) starts with all the separated tracks at 100 % and the
 * original muted, for a manual remix. Older remix files: the global comment STEMS_COMMENT followed by the gains of
 * their streams. Another file: its first stream at 100 %, the others muted (as a player plays it).
 */
public class AudioTracks {
	/** Global comment of the files made by the AI remix, followed by the gains of their streams */
	public static final String STEMS_COMMENT = "3Denlive stems";
	/** Prefix of the names of the streams of the files made by the AI remix */
	public static final String REMIX_PREFIX = "[3De]";

	public static class Track {
		/** Name given by the file, null if none */
		public String title = null;
		/** ISO 639-2 code, null if none */
		public String language = null;
		/** Default stream of the file (played by the players) */
		public boolean isDefault = false;

		/** A stream of the AI remix */
		public boolean isRemix() {
			return title != null && title.startsWith(REMIX_PREFIX);
		}
	}

	public final Vector<Track> tracks = new Vector<Track>();
	/** Gains of a file of the AI remix, null for another file */
	public double[] gains = null;

	static final HashMap<String,AudioTracks> CACHE = new HashMap<String,AudioTracks>();
	static final Pattern STREAM = Pattern.compile("Stream #\\d+:\\d+(?:\\[[^\\]]*\\])?(?:\\(([a-z]{2,3})\\))?: (\\w+):.*");
	static final Pattern META = Pattern.compile("(\\w+)\\s*: (.*)");

	/**
	 * Audio streams of a file (empty when it has none or can't be read)
	 */
	public static AudioTracks of(String aPath) {
		if(aPath == null) {
			return new AudioTracks();
		}
		File aF = new File(aPath);
		String aKey = aF.getAbsolutePath()+"|"+aF.length()+"|"+aF.lastModified();
		synchronized (CACHE) {
			AudioTracks aT = CACHE.get(aKey);
			if(aT == null) {
				aT = probe(aPath);
				CACHE.put(aKey, aT);
			}
			return aT;
		}
	}

	static AudioTracks probe(String aPath) {
		AudioTracks aT = new AudioTracks();
		String aFfmpeg = Ffmpeg.ffmpegPath();
		if(aFfmpeg == null || !new File(aPath).isFile()) {
			return aT;
		}
		try {
			Process aP = new ProcessBuilder(aFfmpeg, "-nostdin", "-hide_banner", "-i", aPath).redirectErrorStream(true).start();
			String aOut = new String(aP.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
			aP.waitFor();
			aT.parse(aOut);
		}
		catch(Throwable t) {
			t.printStackTrace(System.err);
		}
		return aT;
	}

	/**
	 * Description of the input by ffmpeg: global metadata (comment), then the streams with their metadata
	 */
	void parse(String aOut) {
		boolean aInStreams = false;
		Track aCur = null;
		for(String aLine : aOut.split("\\R")) {
			String aL = aLine.trim();
			if(aL.startsWith("Output #")) {
				break;
			}
			Matcher aM = STREAM.matcher(aL);
			if(aM.matches()) {
				aInStreams = true;
				aCur = null;
				if("Audio".equals(aM.group(2))) {
					aCur = new Track();
					String aLng = aM.group(1);
					aCur.language = aLng == null || "und".equals(aLng) ? null : aLng;
					aCur.isDefault = aL.endsWith("(default)");
					tracks.add(aCur);
				}
				continue;
			}
			Matcher aMM = META.matcher(aL);
			if(!aMM.matches()) {
				continue;
			}
			String aK = aMM.group(1).toLowerCase();
			String aV = aMM.group(2).trim();
			if(!aInStreams) {
				if("comment".equals(aK) && aV.startsWith(STEMS_COMMENT)) {
					try {
						String[] aG = aV.substring(STEMS_COMMENT.length()).trim().split(";");
						gains = new double[aG.length];
						for(int g = 0;g < aG.length;g++) {
							gains[g] = Double.parseDouble(aG[g].trim());
						}
					}
					catch(Throwable t) {
						gains = null;
					}
				}
			}
			else if(aCur != null && !aV.isEmpty()) {
				if("title".equals(aK) || "name".equals(aK)) {
					aCur.title = aV;
				}
				else if("handler_name".equals(aK) && aCur.title == null && !isDefaultHandler(aV)) {
					aCur.title = aV;
				}
			}
		}
	}

	/** Handler names written by the muxers, not names of the tracks */
	static boolean isDefaultHandler(String aV) {
		String aL = aV.toLowerCase();
		return aL.equals("soundhandler") || aL.contains("sound media handler") || aL.equals("core media audio")
				|| aL.startsWith("isomedia") || aL.startsWith("gpac") || aL.equals("audiohandler")
				|| aL.startsWith("mainconcept") || aL.contains("apple") || aL.startsWith("l-smash");
	}

	public int size() {
		return tracks.size();
	}

	/** Made by the AI remix */
	public boolean isStems() {
		return gains != null || hasRemix();
	}

	boolean hasRemix() {
		for(Track aT : tracks) {
			if(aT.isRemix()) {
				return true;
			}
		}
		return false;
	}

	/**
	 * Stream of the original sound of a remix file: its default "[3De]" stream, else its first "[3De]" one;
	 * -1 if none
	 */
	public int originalIndex() {
		int aFirst = -1;
		for(int k = 0;k < tracks.size();k++) {
			Track aT = tracks.elementAt(k);
			if(aT.isRemix()) {
				if(aT.isDefault) {
					return k;
				}
				if(aFirst < 0) {
					aFirst = k;
				}
			}
		}
		return aFirst;
	}

	/**
	 * Gain of the stream k when the mixer is created: a remix file, all the separated tracks and not the
	 * original; another file, its first stream
	 */
	public double defaultGain(int k) {
		if(gains != null) {
			return k < gains.length ? gains[k] : 0;
		}
		if(hasRemix()) {
			return k < tracks.size() && tracks.elementAt(k).isRemix() && k != originalIndex() ? 1 : 0;
		}
		return k == 0 ? 1 : 0;
	}

	//Bibliographic ISO 639-2 codes (used by the files) whose terminology code is the one of Java
	static final String[][] BIBLIOGRAPHIC = {{"fre", "fra"}, {"ger", "deu"}, {"dut", "nld"}, {"chi", "zho"}, {"cze", "ces"}
			, {"gre", "ell"}, {"per", "fas"}, {"rum", "ron"}, {"slo", "slk"}, {"alb", "sqi"}, {"arm", "hye"}, {"baq", "eus"}
			, {"bur", "mya"}, {"geo", "kat"}, {"ice", "isl"}, {"mac", "mkd"}, {"mao", "mri"}, {"may", "msa"}, {"tib", "bod"}
			, {"wel", "cym"}};

	/**
	 * Name of a language code (2 or 3 letters) in the language of the interface (aLng: EN, FR...), the code if unknown
	 */
	public static String languageName(String aCode,String aLng) {
		if(aCode == null) {
			return null;
		}
		String aC = aCode.toLowerCase();
		for(String[] aB : BIBLIOGRAPHIC) {
			if(aB[0].equals(aC)) {
				aC = aB[1];
			}
		}
		if(aC.length() == 3) {
			for(String aL : Locale.getISOLanguages()) {
				try {
					if(Locale.of(aL).getISO3Language().equals(aC)) {
						aC = aL;
						break;
					}
				}
				catch(Throwable t) {
				}
			}
		}
		Locale aUi = Locale.forLanguageTag(aLng == null ? "en" : aLng.toLowerCase());
		String aN = Locale.forLanguageTag(aC).getDisplayLanguage(aUi);
		if(aN == null || aN.isEmpty() || aN.equalsIgnoreCase(aC)) {
			return aCode;
		}
		return aN.substring(0, 1).toUpperCase(aUi)+aN.substring(1);
	}

	/**
	 * Label of the stream k: its number, its name and / or its language, aNoName if it has neither
	 */
	public String label(int k,String aLng,String aNoName) {
		return label(k, aLng, aNoName, null);
	}

	/**
	 * Label of the stream k, its name shown by aShowTitle (the names of the remix streams, in English in the file,
	 * translated), null: as it is
	 */
	public String label(int k,String aLng,String aNoName,java.util.function.UnaryOperator<String> aShowTitle) {
		if(k < 0 || k >= tracks.size()) {
			return (k+1)+". "+aNoName;
		}
		Track aT = tracks.elementAt(k);
		String aLang = languageName(aT.language, aLng);
		String aTitle = aT.title == null || aShowTitle == null ? aT.title : aShowTitle.apply(aT.title);
		String aN = aTitle != null ? aTitle+(aLang != null ? " ("+aLang+")" : "")
				: aLang != null ? aLang : aNoName;
		return (k+1)+". "+aN;
	}
}
