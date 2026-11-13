package com.cubaix.TDenlive.ai;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Vector;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import com.cubaix.TDenlive.depth.DepthEstimator;
import com.cubaix.TDenlive.depth.DepthModel;

/**
 * Rewrites the prompt of the AI video panel (written in any language) into a detailed English prompt in the style
 * of the captions Wan was trained with (subject, motion, place, light, camera), as the prompt extension advised by
 * the authors of Wan. Small vision language model Qwen3-VL-4B-Instruct
 * (https://huggingface.co/Qwen/Qwen3-VL-4B-Instruct, Apache-2.0, multilingual), run by res/ai/prompt_extend.py
 * (transformers) in the shared Python environment, bf16 on CUDA, MPS or the CPU. With a reference image, it sees
 * the image (the first frame) and describes it with the motion asked. Downloaded at its first use into
 * ~/3Denlive/Models/Qwen3-VL-4B-Instruct/.
 * The script stays loaded between the requests (the prompt is rewritten while it is typed): one JSON line per
 * request on its stdin, RESULT / FAIL lines on its stdout; close() stops it (the form is closed).
 */
public class PromptExtender extends PythonAI {
	static final long GB = 1024L*1024*1024;
	public static final String REPO = "Qwen/Qwen3-VL-4B-Instruct";
	static final String DIR = "Qwen3-VL-4B-Instruct";
	static final Object[][] FILES = {
		{"chat_template.json", 0L},
		{"config.json", 0L},
		{"generation_config.json", 0L},
		{"merges.txt", 1671839L},
		{"model.safetensors.index.json", 0L},
		{"model-00001-of-00002.safetensors", 4967229296L},
		{"model-00002-of-00002.safetensors", 3908490048L},
		{"preprocessor_config.json", 0L},
		{"tokenizer.json", 7032403L},
		{"tokenizer_config.json", 0L},
		{"vocab.json", 2776833L},
	};
	//bf16 weights (8.9 GB) + an image of 768 px and a short context; estimate (see the PEAK line in 3De.log).
	//Apple Silicon: free + reclaimable memory for the model and the rest of the system
	public static final long CUDA_MEMORY = 10*GB;
	public static final long MAC_MEMORY = 11*GB;
	//Tokens generated at most (a prompt of about 80 to 160 words)
	public static final int MAX_TOKENS = 400;

	public static File getDir(String aTdeDir) {
		return new File(DepthModel.getModelsDir(aTdeDir), DIR);
	}

	public static long bytesToDownload(String aTdeDir) {
		return PythonAI.bytesToDownload(getDir(aTdeDir), FILES);
	}

	/**
	 * Model + Python environment still to download / install (0: ready)
	 */
	public static long bytesToInstall(String aTdeDir) {
		return bytesToDownload(aTdeDir)+PythonAI.envBytesToInstall(aTdeDir, SHARED);
	}

	/**
	 * GPU memory wanted for a comfortable run on this computer
	 */
	public static long gpuMemory() {
		return isMac() ? MAC_MEMORY : CUDA_MEMORY;
	}

	/**
	 * Free GPU memory (NVIDIA; Apple Silicon: free + reclaimable, see DepthEstimator.bestGpu()), -1 if none / unknown
	 */
	public static long freeGpuMemory() {
		if(!hasGpu()) {
			return -1;
		}
		long[] aBest = DepthEstimator.bestGpu();
		return aBest == null ? -1 : aBest[1];
	}

	/**
	 * The LLM runs comfortably on the GPU with aFree bytes free (freeGpuMemory())
	 */
	public static boolean isComfortable(long aFree) {
		return hasGpu() && aFree >= gpuMemory();
	}

	/**
	 * Device: null for the GPU, else the reason of the CPU (DepthEstimator.CPU_REASON_*)
	 */
	public static String cpuReason() {
		if(!hasGpu()) {
			return DepthEstimator.CPU_REASON_NO_GPU;
		}
		if(!isMac()) {
			long[] aBest = DepthEstimator.bestGpu();
			if(aBest != null && aBest[1] < CUDA_MEMORY) {
				return DepthEstimator.CPU_REASON_GPU_MEMORY;
			}
		}
		return null;
	}

	//Answers of the script: {"R", prompt}, {"E", error code or message}, {"X", null} at its end
	final LinkedBlockingQueue<String[]> answers = new LinkedBlockingQueue<String[]>();
	volatile Process server = null;
	volatile boolean closed = false;
	Thread hook = null;

	public PromptExtender(String aTdeDir,Progress aProgress) {
		super(aTdeDir, aProgress);
	}

	@Override
	String stepKey() {
		return "AI.ExtendingPrompt";
	}

	@Override
	boolean canceled() {
		return closed || super.canceled();
	}

	/**
	 * Install what is missing: the Python environment and the model files (resumed file by file)
	 * @return false if closed / canceled
	 */
	public boolean install() throws Exception {
		if(!installEnv() || canceled()) {
			return false;
		}
		Vector<String[]> aFiles = new Vector<String[]>();
		for(Object[] aF : FILES) {
			aFiles.add(new String[] {HF+REPO+"/resolve/main/"+aF[0], (String)aF[0], ""+aF[1]});
		}
		return downloadFiles(getDir(tdeDir), aFiles, "AI.DownloadingLlm") && !canceled();
	}

	/**
	 * Install what is missing and start the script (model loaded), if not running
	 * @return false if closed
	 */
	public synchronized boolean start() throws Exception {
		if(server != null && server.isAlive()) {
			return true;
		}
		if(!install()) {
			return false;
		}
		File aDir = getDir(tdeDir);
		File aScript = copyScript("prompt_extend.py", aDir);
		Vector<String> aCmd = pythonCommand(aScript);
		aCmd.add("--model-dir"); aCmd.add(aDir.getAbsolutePath());
		aCmd.add("--max-tokens"); aCmd.add(""+MAX_TOKENS);
		aCmd.add("--device"); aCmd.add(cpuReason() == null ? "auto" : "cpu");
		aCmd.add("--serve");
		answers.clear();
		Process aP = scriptProcess(aCmd).start();
		server = aP;
		//Closed while it was starting: stopped at once
		if(closed) {
			server = null;
			kill(aP);
			return false;
		}
		hook = new Thread(() -> kill(aP));
		Runtime.getRuntime().addShutdownHook(hook);
		Thread aErr = new Thread(() -> {
			try(BufferedReader aR = new BufferedReader(new InputStreamReader(aP.getErrorStream(), StandardCharsets.UTF_8))) {
				String aL;
				while((aL = aR.readLine()) != null) {
					System.err.println("[AI] "+aL);
				}
			}
			catch(Throwable t) {
			}
		}, "PromptExtender stderr");
		aErr.setDaemon(true);
		aErr.start();
		Thread aOut = new Thread(() -> {
			try(BufferedReader aR = new BufferedReader(new InputStreamReader(aP.getInputStream(), StandardCharsets.UTF_8))) {
				String aL;
				while((aL = aR.readLine()) != null) {
					if(aL.startsWith("RESULT ")) {
						answers.add(new String[] {"R", unquote(aL.substring(7).trim())});
					}
					else if(aL.startsWith("FAIL ") || aL.startsWith("ERROR ")) {
						answers.add(new String[] {"E", aL.substring(aL.indexOf(' ')+1).trim()});
					}
					else {
						try {
							commonLine(aL);
						}
						catch(Throwable t) {
							t.printStackTrace(System.err);
						}
					}
				}
			}
			catch(Throwable t) {
			}
			answers.add(new String[] {"X", null});
		}, "PromptExtender stdout");
		aOut.setDaemon(true);
		aOut.start();
		return true;
	}

	/**
	 * Rewrite aPrompt (any language) into a detailed English prompt (starts the script if needed).
	 * aImagePath: the reference image the video starts from (the model sees it and the prompt describes how to
	 * animate it), null for a video from the text only.
	 * @return the English prompt, null if closed meanwhile
	 */
	public String extend(String aPrompt,String aImagePath) throws Exception {
		if(!start()) {
			return null;
		}
		Process aP = server;
		String aMode = aImagePath != null ? "i2v" : "t2v";
		String aReq = "{\"mode\": \""+aMode+"\", \"prompt\": \""+quote(aPrompt)+"\""
				+(aImagePath != null ? ", \"image\": \""+quote(aImagePath)+"\"" : "")+"}\n";
		OutputStream aOS = aP.getOutputStream();
		synchronized(aOS) {
			aOS.write(aReq.getBytes(StandardCharsets.UTF_8));
			aOS.flush();
		}
		while(!closed) {
			String[] aA = answers.poll(300, TimeUnit.MILLISECONDS);
			if(aA == null) {
				continue;
			}
			if("R".equals(aA[0])) {
				return aA[1];
			}
			if("E".equals(aA[0])) {
				throw new GenerationException(aA[1]);
			}
			throw new Exception("The rewriting stopped (see log/3De.log)");
		}
		return null;
	}

	/**
	 * Stop the script (and free its memory)
	 */
	public void close() {
		closed = true;
		Process aP = server;
		server = null;
		if(aP != null) {
			kill(aP);
			//Its memory is given back before the next job (the video generation) loads its models
			try {
				aP.waitFor(10, TimeUnit.SECONDS);
			}
			catch(InterruptedException e) {
			}
		}
		cancel();
		if(hook != null) {
			try {
				Runtime.getRuntime().removeShutdownHook(hook);
			}
			catch(Throwable t) {
				//Already quitting
			}
		}
	}

	/**
	 * String for a JSON string literal
	 */
	static String quote(String aS) {
		StringBuilder aSB = new StringBuilder();
		for(char aC : aS.toCharArray()) {
			switch(aC) {
			case '"': aSB.append("\\\""); break;
			case '\\': aSB.append("\\\\"); break;
			case '\n': aSB.append("\\n"); break;
			case '\r': aSB.append("\\r"); break;
			case '\t': aSB.append("\\t"); break;
			default:
				if(aC < 0x20) {
					aSB.append(String.format("\\u%04x", (int)aC));
				}
				else {
					aSB.append(aC);
				}
			}
		}
		return aSB.toString();
	}

	/**
	 * Value of a JSON string literal (with its quotes), as written by json.dumps(ensure_ascii=True)
	 */
	static String unquote(String aJ) {
		if(aJ.length() < 2 || aJ.charAt(0) != '"') {
			return aJ;
		}
		StringBuilder aSB = new StringBuilder();
		for(int i = 1;i < aJ.length()-1;i++) {
			char aC = aJ.charAt(i);
			if(aC != '\\' || i+1 >= aJ.length()-1) {
				aSB.append(aC);
				continue;
			}
			char aN = aJ.charAt(++i);
			switch(aN) {
			case 'n': aSB.append('\n'); break;
			case 'r': aSB.append('\r'); break;
			case 't': aSB.append('\t'); break;
			case 'b': aSB.append('\b'); break;
			case 'f': aSB.append('\f'); break;
			case 'u':
				aSB.append((char)Integer.parseInt(aJ.substring(i+1, i+5), 16));
				i += 4;
				break;
			default: aSB.append(aN);
			}
		}
		return aSB.toString();
	}
}
