package com.cubaix.TDenlive.ai;

import java.io.File;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Vector;

import com.cubaix.TDenlive.depth.DepthEstimator;
import com.cubaix.TDenlive.depth.DepthModel;

/**
 * Objects delimited by clicks and tracked in a video, for the masks (AI "Mask" panel, GUI/AiMaskGUI): SAM 2.1 of
 * Meta (Apache-2.0, facebook/sam2.1-hiera-* in the transformers format), run by res/ai/sam_track.py in the shared
 * Python environment, resident (one request per click: the model and the tracking session stay loaded).
 * The frames of the work copy (3Denlive extracts them) are read by the script when it needs them; the result of a
 * frame is a 16-bit PNG of labels (bit k-1: object k). The session lives in the script: when the resident script is
 * not the one that holds it (unloaded for another model), it is opened again and the points given again.
 * SAM 3 is not offered: its weights are gated (an access asked to Meta) under its own licence.
 */
public class MaskGenerator extends PythonAI {
	static final long GB = 1024L*1024*1024;
	static final long MB = 1024L*1024;
	static final String DIR = "SAM2";
	//Objects of a session (bits of the 16-bit labels)
	public static final int MAX_OBJECTS = 16;

	public static class Model {
		public final String id;
		//Name shown
		public final String name;
		final String repo;
		final String dir;
		//Parameters (for the user)
		public final String params;
		final long size;
		//Memory needed (estimates: weights + the activations of a 1024 x 1024 frame + the memory of 7 frames)
		public final long gpuMemory;

		Model(String aId,String aName,String aRepo,String aParams,long aSize,long aGpu) {
			id = aId;
			name = aName;
			repo = aRepo;
			dir = aRepo.substring(aRepo.indexOf('/')+1);
			params = aParams;
			size = aSize;
			gpuMemory = aGpu;
		}

		//The weights in the transformers format and their configuration (the preprocessing is done by the script)
		Object[][] files() {
			return new Object[][] {{"config.json", 0L}, {"model.safetensors", size}};
		}

		public File getDir(String aTdeDir) {
			return new File(getRoot(aTdeDir), dir);
		}

		public long downloadSize() {
			return size;
		}

		public long bytesToDownload(String aTdeDir) {
			return PythonAI.bytesToDownload(getDir(aTdeDir), files());
		}

		public boolean isDownloaded(String aTdeDir) {
			return bytesToDownload(aTdeDir) == 0;
		}
	}

	public static final Model TINY = new Model("Tiny", "Tiny", "facebook/sam2.1-hiera-tiny", "39 M", 155908064L, 1*GB);
	public static final Model SMALL = new Model("Small", "Small", "facebook/sam2.1-hiera-small", "46 M", 184305280L, 1*GB);
	public static final Model BASE_PLUS = new Model("BasePlus", "Base+", "facebook/sam2.1-hiera-base-plus", "81 M", 323476296L, 2*GB);
	public static final Model LARGE = new Model("Large", "Large", "facebook/sam2.1-hiera-large", "224 M", 897897416L, 3*GB);
	public static final Model[] MODELS = {TINY, SMALL, BASE_PLUS, LARGE};

	public static Model byId(String aId) {
		for(Model aM : MODELS) {
			if(aM.id.equalsIgnoreCase(aId)) {
				return aM;
			}
		}
		return null;
	}

	/**
	 * Model advised for this computer: Tiny on the CPU; on Apple Silicon Base+ (the time of a tracked frame is
	 * mostly the memory attention, the same for all; Large adds its larger image encoder), on an NVIDIA GPU the
	 * largest that fits in the GPU memory available
	 */
	public static Model defaultModel() {
		if(!hasGpu()) {
			return TINY;
		}
		long aAvail = availableGpuMemory();
		Model[] aOrder = isMac() ? new Model[] {BASE_PLUS, SMALL, TINY} : new Model[] {LARGE, BASE_PLUS, SMALL, TINY};
		for(Model aM : aOrder) {
			if(aAvail < 0 || aAvail >= aM.gpuMemory) {
				return aM;
			}
		}
		return TINY;
	}

	static File getRoot(String aTdeDir) {
		return new File(DepthModel.getModelsDir(aTdeDir), DIR);
	}

	/**
	 * Python and PyTorch exist for this platform (GPU or CPU)
	 */
	public static boolean isPlatformSupported() {
		return hasPython();
	}

	/**
	 * Device: null for the GPU, else the reason of the CPU (DepthEstimator.CPU_REASON_*)
	 */
	public static String cpuReason(Model aModel) {
		//Already loaded on the GPU: stays there (its own memory is not free)
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

	/**
	 * Name of aModel kept loaded (PythonAI.Resident)
	 */
	public static String tag(Model aModel) {
		return "mask "+aModel.id;
	}

	/**
	 * Labels of a frame written by the script
	 */
	public interface Listener {
		void labels(int aFrame);
	}

	Model model;
	Listener listener = null;
	//Device used by the script (DEVICE message): cuda, mps or cpu
	public volatile String device = null;
	//Key of the STEP messages (tracking or writing)
	volatile String stepKey = "AI.MaskTracking";
	//Last tracking: the whole video (else resumed), its first frame (TRACKMODE message)
	public volatile boolean trackFull = true;
	public volatile int trackFrom = 0;
	//Command of the resident script (model arguments), made once
	Vector<String> command = null;
	//Arguments of the open request of the session, and the points given since (by object / view / frame: the last
	//list of each), given again to a new resident script
	Vector<String> openArgs = null;
	final LinkedHashMap<String,Vector<String>> points = new LinkedHashMap<String,Vector<String>>();
	//The resident script that holds the session
	ScriptServer sessionServer = null;

	public MaskGenerator(String aTdeDir,Model aModel,Progress aProgress,Listener aListener) {
		super(aTdeDir, aProgress);
		model = aModel;
		listener = aListener;
	}

	public Model getModel() {
		return model;
	}

	@Override
	String stepKey() {
		return stepKey;
	}

	@Override
	boolean handleLine(String aL) {
		if(aL.startsWith("LABELS ")) {
			try {
				int aF = Integer.parseInt(aL.substring(7).trim());
				if(listener != null) {
					listener.labels(aF);
				}
			}
			catch(Throwable t) {
				t.printStackTrace(System.err);
			}
			return true;
		}
		if(aL.startsWith("TRACKMODE ")) {
			String[] aS = aL.split(" ");
			trackFull = "full".equals(aS[1]);
			try {
				trackFrom = Integer.parseInt(aS[2]);
			}
			catch(Throwable t) {
				trackFrom = 0;
			}
			stepKey = trackFull ? "AI.MaskTracking" : "AI.MaskResuming";
			return true;
		}
		if(aL.startsWith("DEVICE ")) {
			String aD = aL.substring(7).trim();
			if(!aD.equals(device)) {
				device = aD;
				System.out.println("AI mask device: "+device);
			}
			return true;
		}
		return false;
	}

	/**
	 * Python environment and model (downloaded at the first use)
	 * @return false if canceled
	 */
	public boolean install() throws Exception {
		if(!installEnv() || canceled()) {
			return false;
		}
		Vector<String[]> aFiles = new Vector<String[]>();
		for(Object[] aF : model.files()) {
			aFiles.add(new String[] {HF+model.repo+"/resolve/main/"+aF[0], (String)aF[0], ""+aF[1]});
		}
		return downloadFiles(model.getDir(tdeDir), aFiles, "AI.DownloadingMaskModel") && !canceled();
	}

	/**
	 * The resident script of the model, the session opened in it (again if it is a new one)
	 */
	ScriptServer server() throws Exception {
		if(command == null) {
			File aScript = copyScript("sam_track.py", getRoot(tdeDir));
			command = pythonCommand(aScript);
			command.add("--model-dir"); command.add(model.getDir(tdeDir).getAbsolutePath());
			command.add("--device"); command.add(cpuReason(model) == null ? "auto" : "cpu");
		}
		//Another model kept loaded is unloaded first (ScriptServer.obtain())
		ScriptServer aS = ScriptServer.obtain(this, tag(model), model.gpuMemory, command);
		if(aS != sessionServer && openArgs != null) {
			sessionServer = null;
			aS.request(this, openArgs);
			Vector<Vector<String>> aAll;
			synchronized(points) {
				aAll = new Vector<Vector<String>>(points.values());
			}
			for(Vector<String> aP : aAll) {
				aS.request(this, aP);
			}
			sessionServer = aS;
		}
		return aS;
	}

	/**
	 * Request to the script
	 * @return the text of its DONE message, null if canceled
	 */
	String request(Vector<String> aArgs) throws Exception {
		ScriptServer aS = server();
		if(canceled()) {
			return null;
		}
		return aS.request(this, aArgs);
	}

	static Vector<String> args(String... aArgs) {
		Vector<String> aV = new Vector<String>();
		for(String aA : aArgs) {
			aV.add(aA);
		}
		return aV;
	}

	/**
	 * A new video: its frames (<n>.jpg|png from 0 in aFrames), the views in the frames (x, y, w, h each), the
	 * folder of the labels; loads the model
	 */
	public void open(File aFrames,int aCount,int[][] aViews,File aLabels) throws Exception {
		StringBuilder aV = new StringBuilder();
		for(int[] aR : aViews) {
			aV.append(aV.length() > 0 ? ";" : "").append(aR[0]).append(',').append(aR[1]).append(',').append(aR[2]).append(',').append(aR[3]);
		}
		openArgs = args("--op", "open", "--frames", aFrames.getAbsolutePath(), "--count", ""+aCount
				, "--views", aV.toString(), "--labels", aLabels.getAbsolutePath());
		synchronized(points) {
			points.clear();
		}
		//Opened by server() (a new session for the resident script)
		sessionServer = null;
		server();
	}

	/**
	 * Points of the object aObj in the view aView on the frame aFrame (view coordinates of the work frame; {x, y,
	 * 1 in / 0 out} each), all those of that frame, and its box ({x0, y0, x1, y1}, null: none): replace the previous
	 * prompt of that frame (points, box or mask); none: removed
	 */
	public void points(int aObj,int aView,int aFrame,Vector<double[]> aPoints,double[] aBox) throws Exception {
		StringBuilder aP = new StringBuilder();
		for(double[] aPt : aPoints) {
			aP.append(aP.length() > 0 ? ";" : "").append(String.format(java.util.Locale.ROOT, "%.1f,%.1f,%d", aPt[0], aPt[1], (int)aPt[2]));
		}
		String aB = aBox == null ? "" : String.format(java.util.Locale.ROOT, "%.1f,%.1f,%.1f,%.1f", aBox[0], aBox[1], aBox[2], aBox[3]);
		Vector<String> aArgs = args("--op", "points", "--obj", ""+aObj, "--view", ""+aView, "--frame", ""+aFrame, "--points", aP.toString(), "--box", aB);
		remember(aObj, aView, aFrame, aPoints.isEmpty() && aBox == null ? null : aArgs);
		request(aArgs);
	}

	/**
	 * Mask painted for the object aObj in the view aView on the frame aFrame (PNG of the size of the view in the work
	 * frame, white = in): replaces the previous prompt of that frame; the result of that frame is this mask
	 */
	public void mask(int aObj,int aView,int aFrame,File aPng) throws Exception {
		Vector<String> aArgs = args("--op", "mask", "--obj", ""+aObj, "--view", ""+aView, "--frame", ""+aFrame, "--mask", aPng.getAbsolutePath());
		remember(aObj, aView, aFrame, aArgs);
		request(aArgs);
	}

	/**
	 * The last prompt of an object / view / frame, given again to a new resident script (null: none)
	 */
	void remember(int aObj,int aView,int aFrame,Vector<String> aArgs) {
		String aKey = aObj+"/"+aView+"/"+aFrame;
		synchronized(points) {
			points.remove(aKey);
			if(aArgs != null) {
				points.put(aKey, aArgs);
			}
		}
	}

	/**
	 * The object removed (and its labels)
	 */
	public void delete(int aObj) throws Exception {
		synchronized(points) {
			for(java.util.Iterator<Map.Entry<String,Vector<String>>> aI = points.entrySet().iterator();aI.hasNext();) {
				if(aI.next().getKey().startsWith(aObj+"/")) {
					aI.remove();
				}
			}
		}
		request(args("--op", "delete", "--obj", ""+aObj));
	}

	/**
	 * Tracking of the objects (STEP messages): over the whole video (aResume < 0), or resumed from the frame aResume
	 * to the end, the frames before kept (plus those of a stopped tracking not computed yet; the whole video when the
	 * session can't resume: never tracked, an object added, a removal; see trackFull / trackFrom); aStop created:
	 * stopped
	 * @return false if stopped
	 */
	public boolean track(File aStop,int aResume) throws Exception {
		aStop.delete();
		stepKey = "AI.MaskTracking";
		Vector<String> aArgs = args("--op", "track", "--stop-file", aStop.getAbsolutePath());
		if(aResume >= 0) {
			aArgs.add("--resume");
			aArgs.add(""+aResume);
		}
		try {
			return request(aArgs) != null;
		}
		catch(GenerationException e) {
			if("STOPPED".equals(e.code)) {
				return false;
			}
			throw e;
		}
		finally {
			aStop.delete();
		}
	}

	/**
	 * The mask of all the objects (white on black), the views side by side at aViewW x aViewH each: a PNG (one
	 * frame) or a video at aFps (H.264 by aFfmpeg)
	 */
	public File export(File aOut,int aViewW,int aViewH,String aFfmpeg,String aFps) throws Exception {
		stepKey = "AI.MaskWriting";
		String aDone = request(args("--op", "export", "--out", aOut.getAbsolutePath(), "--view-size", aViewW+"x"+aViewH
				, "--ffmpeg", aFfmpeg, "--fps", aFps));
		if(aDone == null || !aOut.isFile()) {
			throw new Exception("No mask produced (see log/3De.log)");
		}
		return aOut;
	}

	/**
	 * End of the session: its memory given back, the model stays loaded
	 */
	public void close() {
		openArgs = null;
		synchronized(points) {
			points.clear();
		}
		try {
			Resident aR = resident();
			if(aR == sessionServer && aR != null && aR.residentAlive()) {
				sessionServer.request(this, args("--op", "close"));
			}
		}
		catch(Throwable t) {
			t.printStackTrace(System.err);
		}
		sessionServer = null;
	}
}
