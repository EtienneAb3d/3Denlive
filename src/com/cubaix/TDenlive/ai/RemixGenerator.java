package com.cubaix.TDenlive.ai;

import java.io.File;
import java.util.Vector;

import com.cubaix.TDenlive.depth.DepthEstimator;
import com.cubaix.TDenlive.depth.DepthModel;
import com.cubaix.TDenlive.ffmpeg.AudioTracks;
import com.cubaix.TDenlive.ffmpeg.Ffmpeg;

/**
 * Separation of the tracks of a sound (AI remix) with Hybrid Transformer Demucs (Meta, ICASSP 2023,
 * https://github.com/facebookresearch/demucs, code and weights MIT), run by res/ai/demucs_separate.py in its own
 * Python environment DEMUCS_ENV (demucs 4.0.1, versions tested), resident (tag "remix <id>"), on CUDA, MPS or the
 * CPU. Models: htdemucs (4 sources: drums, bass, other, vocals; advised), htdemucs_ft (the same fine tuned, one
 * model per source: better, 4 times slower) and htdemucs_6s (+ guitar and piano). Their weights (.th files of
 * dl.fbaipublicfiles.com) go into ~/3Denlive/Models/Demucs/.
 * The sound (first audio stream of a video or a sound file) is extracted by ffmpeg into a 44.1 kHz stereo WAV of
 * a work folder, the sources are written there as WAV files (STEM messages), listened in GUI/AiRemixGUI and saved
 * into a multi-track MP4 (mux(): the original sound and the sources, streams named "[3De] ...", no mix).
 */
public class RemixGenerator extends PythonAI {
	static final long MB = 1024L*1024;
	static final long GB = 1024L*MB;
	static final String DIR = "Demucs";
	static final String URL = "https://dl.fbaipublicfiles.com/demucs/hybrid_transformer/";
	//Versions tested together (no torchaudio input / output used by the script)
	public static final Env DEMUCS_ENV = new Env("demucs-env", "1", new String[] {"torch==2.8.0", "torchaudio==2.8.0"
			, "demucs==4.0.1", "soundfile==0.14.0", "numpy==2.5.5"}, new String[0]);
	//Size of the environment (PyTorch without CUDA / with CUDA), for the user
	static final long ENV_SIZE = 1200*MB;
	static final long ENV_SIZE_CUDA = 5*GB;

	public static class Model {
		public final String id;
		//Weight files (several: one model per source) and their sizes
		final String[] files;
		final long[] sizes;
		//Sources in the order of the model
		public final String[] sources;
		//GPU memory needed (estimate: weights + segments of 7.8 s)
		public final long gpuMemory;

		Model(String aId,String[] aFiles,long[] aSizes,String[] aSources,long aGpu) {
			id = aId;
			files = aFiles;
			sizes = aSizes;
			sources = aSources;
			gpuMemory = aGpu;
		}

		Object[][] files() {
			Object[][] aF = new Object[files.length][];
			for(int f = 0;f < files.length;f++) {
				aF[f] = new Object[] {files[f], sizes[f]};
			}
			return aF;
		}

		public long downloadSize() {
			long aS = 0;
			for(long aL : sizes) {
				aS += aL;
			}
			return aS;
		}

		public long bytesToDownload(String aTdeDir) {
			return PythonAI.bytesToDownload(getRoot(aTdeDir), files());
		}

		public boolean isDownloaded(String aTdeDir) {
			return bytesToDownload(aTdeDir) == 0;
		}
	}

	static final String[] SOURCES4 = {"drums", "bass", "other", "vocals"};
	public static final Model HTDEMUCS = new Model("htdemucs", new String[] {"955717e8-8726e21a.th"}
			, new long[] {84141911L}, SOURCES4, 2*GB);
	//Order of the bag of the remote htdemucs_ft.yaml of demucs: model k = source k
	public static final Model HTDEMUCS_FT = new Model("htdemucs_ft", new String[] {"f7e0c4bc-ba3fe64a.th", "d12395a8-e57c48e6.th"
			, "92cfc3b6-ef3bcb9c.th", "04573f0d-f3cf25b2.th"}, new long[] {84141271L, 84141271L, 84141271L, 84141271L}, SOURCES4, 2*GB);
	public static final Model HTDEMUCS_6S = new Model("htdemucs_6s", new String[] {"5c90dfd2-34c22ccb.th"}
			, new long[] {54996327L}, new String[] {"drums", "bass", "other", "vocals", "guitar", "piano"}, 2*GB);
	public static final Model[] MODELS = {HTDEMUCS, HTDEMUCS_FT, HTDEMUCS_6S};

	public static Model byId(String aId) {
		for(Model aM : MODELS) {
			if(aM.id.equalsIgnoreCase(aId)) {
				return aM;
			}
		}
		return null;
	}

	static File getRoot(String aTdeDir) {
		return new File(DepthModel.getModelsDir(aTdeDir), DIR);
	}

	public static boolean isPlatformSupported() {
		return hasPython();
	}

	/**
	 * Device: null for the GPU, else the reason of the CPU (DepthEstimator.CPU_REASON_*)
	 */
	public static String cpuReason(Model aModel) {
		if("auto".equals(loadedDevice(tag(aModel))) && hasGpu()) {
			return null;
		}
		if(!hasGpu()) {
			return DepthEstimator.CPU_REASON_NO_GPU;
		}
		if(!isMac()) {
			long aAvail = availableGpuMemory();
			if(aAvail >= 0 && aAvail < aModel.gpuMemory) {
				return DepthEstimator.CPU_REASON_GPU_MEMORY;
			}
		}
		return null;
	}

	public static String tag(Model aModel) {
		return "remix "+aModel.id;
	}

	/**
	 * Disk size of the environment to install (0 if installed)
	 */
	public static long envBytesToInstall(String aTdeDir) {
		if(isEnvInstalled(aTdeDir, DEMUCS_ENV)) {
			return 0;
		}
		return DepthEstimator.useGpuRuntime() ? ENV_SIZE_CUDA : ENV_SIZE;
	}

	/** A separated source: its name (drums, bass, other, vocals, guitar, piano) and its WAV */
	public static class Stem {
		public final String name;
		public final File file;

		Stem(String aName,File aFile) {
			name = aName;
			file = aFile;
		}
	}

	Model model;
	//Audio stream of the source separated (several: languages, a remix...), 0: the first
	public int audioTrack = 0;
	public volatile String device = null;
	final Vector<Stem> stems = new Vector<Stem>();

	public RemixGenerator(String aTdeDir,Model aModel,Progress aProgress) {
		super(aTdeDir, aProgress);
		model = aModel;
	}

	@Override
	Env env() {
		return DEMUCS_ENV;
	}

	@Override
	String stepKey() {
		return "AI.RemixSeparating";
	}

	@Override
	boolean handleLine(String aL) {
		if(aL.startsWith("DEVICE ")) {
			device = aL.substring(7).trim();
			System.out.println("AI remix device: "+device);
			return true;
		}
		if(aL.startsWith("STEM ")) {
			String[] aS = aL.substring(5).trim().split(" ", 2);
			if(aS.length == 2) {
				stems.add(new Stem(aS[0], new File(aS[1])));
			}
			return true;
		}
		return false;
	}

	/**
	 * Install what is missing, extract the sound of aInput into aWork, then separate it into the sources of the
	 * model (WAV files in aWork)
	 * @return the sources, null if canceled
	 */
	public Vector<Stem> separate(File aInput,File aWork) throws Exception {
		if(!installEnv() || !downloadModel() || canceled()) {
			return null;
		}
		aWork.mkdirs();
		status("AI.RemixExtracting", null, 0, 0);
		File aWav = new File(aWork, "input.wav");
		extract(aInput, audioTrack, aWav);
		if(canceled()) {
			return null;
		}
		if(!isLoaded(tag(model))) {
			beforeLoad(tag(model));
		}
		File aScript = copyScript("demucs_separate.py", getRoot(tdeDir));
		Vector<String> aCmd = pythonCommand(aScript);
		aCmd.add("--model-dir"); aCmd.add(getRoot(tdeDir).getAbsolutePath());
		aCmd.add("--model"); aCmd.add(model.id);
		for(String aF : model.files) {
			aCmd.add("--weights"); aCmd.add(aF);
		}
		aCmd.add("--device"); aCmd.add(cpuReason(model) == null ? "auto" : "cpu");
		Vector<String> aReq = new Vector<String>();
		aReq.add("--input"); aReq.add(aWav.getAbsolutePath());
		aReq.add("--out-dir"); aReq.add(new File(aWork, "stems").getAbsolutePath());
		stems.clear();
		//input.wav is kept: the original sound in the listening window (the work folder is deleted by it)
		String aDone = runResident(tag(model), model.gpuMemory, aCmd, aReq);
		if(aDone == null || canceled()) {
			return null;
		}
		if(stems.isEmpty()) {
			throw new Exception("No track produced (see log/3De.log)");
		}
		return new Vector<Stem>(stems);
	}

	/**
	 * Audio stream aTrack (0: the first) of aInput as a 44.1 kHz stereo 16 bits WAV (the rate of the models)
	 */
	static void extract(File aInput,int aTrack,File aWav) throws Exception {
		String aFfmpeg = Ffmpeg.ffmpegPath();
		if(aFfmpeg == null) {
			throw new Exception("ffmpeg not found");
		}
		ProcessBuilder aPB = new ProcessBuilder(aFfmpeg, "-nostdin", "-hide_banner", "-v", "error", "-y", "-i", aInput.getAbsolutePath()
				, "-map", "0:a:"+Math.max(0, aTrack), "-vn", "-ac", "2", "-ar", "44100", "-c:a", "pcm_s16le", aWav.getAbsolutePath());
		aPB.redirectErrorStream(true);
		Process aP = aPB.start();
		String aOut = new String(aP.getInputStream().readAllBytes());
		if(aP.waitFor() != 0 || !aWav.isFile() || aWav.length() < 1024) {
			System.err.println(aOut);
			throw new GenerationException("NOSOUND");
		}
	}

	boolean downloadModel() throws Exception {
		Vector<String[]> aFiles = new Vector<String[]>();
		for(int f = 0;f < model.files.length;f++) {
			aFiles.add(new String[] {URL+model.files[f], model.files[f], ""+model.sizes[f]});
		}
		return downloadFiles(getRoot(tdeDir), aFiles, "AI.DownloadingRemixModel");
	}

	/**
	 * Multi-track MP4 (M4A without video), without any mix (the tracks are mixed in the montage): first the
	 * original sound (its stream aTrack; the default stream: what the players play), then each source at 100 %;
	 * the video of aSource copied when it has one. Each stream is named "[3De] <name>" (AudioTracks.REMIX_PREFIX,
	 * in its handler name and title): recognized when the file is put on the time line, where the multi-track
	 * mixer (processors.TrackMixer) starts with all the sources and without the original.
	 * @param aNames names of the streams (original, sources...), without the prefix
	 */
	public static void mux(File aSource,int aTrack,boolean aVideo,Vector<Stem> aStems,String[] aNames,File aOut) throws Exception {
		String aFfmpeg = Ffmpeg.ffmpegPath();
		if(aFfmpeg == null) {
			throw new Exception("ffmpeg not found");
		}
		int aN = aStems.size();
		Vector<String> aCmd = new Vector<String>();
		aCmd.add(aFfmpeg);
		aCmd.add("-nostdin"); aCmd.add("-hide_banner"); aCmd.add("-v"); aCmd.add("error"); aCmd.add("-y");
		aCmd.add("-i"); aCmd.add(aSource.getAbsolutePath());
		for(Stem aS : aStems) {
			aCmd.add("-i"); aCmd.add(aS.file.getAbsolutePath());
		}
		if(aVideo) {
			aCmd.add("-map"); aCmd.add("0:v:0");
			aCmd.add("-c:v"); aCmd.add("copy");
		}
		aCmd.add("-map"); aCmd.add("0:a:"+Math.max(0, aTrack));
		for(int s = 0;s < aN;s++) {
			aCmd.add("-map"); aCmd.add((s+1)+":a");
		}
		aCmd.add("-c:a"); aCmd.add("aac");
		aCmd.add("-b:a"); aCmd.add("192k");
		aCmd.add("-ar"); aCmd.add("48000");
		aCmd.add("-ac"); aCmd.add("2");
		for(int t = 0;t < aNames.length && t < aN+1;t++) {
			String aName = AudioTracks.REMIX_PREFIX+" "+aNames[t];
			aCmd.add("-metadata:s:a:"+t); aCmd.add("handler_name="+aName);
			aCmd.add("-metadata:s:a:"+t); aCmd.add("title="+aName);
		}
		//Players play the original sound
		aCmd.add("-disposition:a"); aCmd.add("0");
		aCmd.add("-disposition:a:0"); aCmd.add("default");
		aCmd.add("-movflags"); aCmd.add("+faststart");
		aCmd.add(aOut.getAbsolutePath());
		System.out.println("AI remix: "+String.join(" ", aCmd));
		Process aP = new ProcessBuilder(aCmd).redirectErrorStream(true).start();
		String aOutText = new String(aP.getInputStream().readAllBytes());
		if(aP.waitFor() != 0 || !aOut.isFile()) {
			System.err.println(aOutText);
			aOut.delete();
			throw new Exception("ffmpeg: "+aOutText.trim());
		}
	}

	/**
	 * The file has a video stream (not a cover image)
	 */
	public static boolean hasVideo(File aFile) {
		String aFfmpeg = Ffmpeg.ffmpegPath();
		if(aFfmpeg == null) {
			return false;
		}
		try {
			Process aP = new ProcessBuilder(aFfmpeg, "-nostdin", "-hide_banner", "-i", aFile.getAbsolutePath()).redirectErrorStream(true).start();
			String aOut = new String(aP.getInputStream().readAllBytes());
			aP.waitFor();
			for(String aL : aOut.split("\\R")) {
				if(aL.trim().matches("Stream #.*: Video: .*") && !aL.contains("attached pic")) {
					return true;
				}
			}
		}
		catch(Throwable t) {
			t.printStackTrace(System.err);
		}
		return false;
	}
}
