package com.cubaix.TDenlive.ai;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Vector;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * A generation script started in resident mode (res/ai/ai_serve.py, --serve): its model stays loaded after a
 * generation, until another model has to be loaded (PythonAI.beforeLoad()). The command of the model (script,
 * model folder, device...) is its key; each generation is a request: a JSON list of the other arguments on its
 * stdin, the usual messages (STATUS, STEP, DONE, ERROR...) then END on its stdout.
 */
class ScriptServer implements PythonAI.Resident {
	//End of the stdout of the process
	static final String EOF = "\u0000EOF";

	final String key;
	//Which model (see PythonAI.Resident) and its device argument
	final String tag;
	final String device;
	//Memory of its model (estimate)
	final long memory;
	final Process process;
	final LinkedBlockingQueue<String> lines = new LinkedBlockingQueue<String>();
	final Thread hook;

	ScriptServer(String aKey,String aTag,String aDevice,long aMemory,Process aP) {
		key = aKey;
		tag = aTag;
		device = aDevice;
		memory = aMemory;
		process = aP;
		//The application quits: the script (and its model in memory) stops too
		hook = new Thread(() -> PythonAI.kill(aP));
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
		}, "AI script stderr");
		aErr.setDaemon(true);
		aErr.start();
		Thread aOut = new Thread(() -> {
			try(BufferedReader aR = new BufferedReader(new InputStreamReader(aP.getInputStream(), StandardCharsets.UTF_8))) {
				String aL;
				while((aL = aR.readLine()) != null) {
					lines.add(aL);
				}
			}
			catch(Throwable t) {
			}
			lines.add(EOF);
			PythonAI.residentGone(ScriptServer.this);
			System.out.println("AI: resident model ended ("+key+")");
		}, "AI script stdout");
		aOut.setDaemon(true);
		aOut.start();
	}

	/**
	 * The resident script of the command aCmd (model arguments), started if it is not the one running (the
	 * model kept loaded is unloaded first)
	 */
	static ScriptServer obtain(PythonAI aGen,String aTag,long aMemory,Vector<String> aCmd) throws Exception {
		String aKey = String.join(" ", aCmd);
		PythonAI.Resident aR = PythonAI.resident();
		if(aR instanceof ScriptServer && ((ScriptServer)aR).key.equals(aKey) && ((ScriptServer)aR).process.isAlive()) {
			System.out.println("AI: model already loaded ("+aKey+")");
			return (ScriptServer)aR;
		}
		PythonAI.beforeLoad(aKey);
		Vector<String> aC = new Vector<String>(aCmd);
		aC.add("--serve");
		int aD = aCmd.indexOf("--device");
		String aDevice = aD >= 0 && aD+1 < aCmd.size() ? aCmd.get(aD+1) : "auto";
		ScriptServer aS = new ScriptServer(aKey, aTag, aDevice, aMemory, aGen.scriptProcess(aC).start());
		PythonAI.setResident(aS);
		return aS;
	}

	@Override
	public String residentKey() {
		return key;
	}

	@Override
	public String residentTag() {
		return tag;
	}

	@Override
	public String residentDevice() {
		return device;
	}

	@Override
	public boolean residentAlive() {
		return process.isAlive();
	}

	@Override
	public long residentMemory() {
		return memory;
	}

	/**
	 * One generation (aArgs: the arguments of the request); messages to aGen. A cancel kills the script (its
	 * computation can't be stopped otherwise).
	 * @return the path of the DONE message, null if canceled
	 */
	synchronized String request(PythonAI aGen,Vector<String> aArgs) throws Exception {
		StringBuilder aJ = new StringBuilder("[");
		for(int i = 0;i < aArgs.size();i++) {
			aJ.append(i > 0 ? ", " : "").append('"').append(PromptExtender.quote(aArgs.get(i))).append('"');
		}
		aJ.append("]\n");
		System.out.println("AI request: "+aArgs);
		aGen.process = process;
		String aDone = null;
		String aError = null;
		try {
			OutputStream aOS = process.getOutputStream();
			aOS.write(aJ.toString().getBytes(StandardCharsets.UTF_8));
			aOS.flush();
			while(true) {
				if(aGen.canceled()) {
					unload();
					return null;
				}
				String aL = lines.poll(300, TimeUnit.MILLISECONDS);
				if(aL == null) {
					continue;
				}
				if(aL == EOF) {
					if(aGen.canceled()) {
						return null;
					}
					if(aError != null) {
						throw new PythonAI.GenerationException(aError);
					}
					throw new Exception("The script stopped (see log/3De.log)");
				}
				if(aL.equals("END")) {
					break;
				}
				if(aL.equals("READY")) {
					continue;
				}
				if(aL.startsWith("DONE ")) {
					aDone = aL.substring(5).trim();
				}
				else if(aL.startsWith("ERROR ")) {
					aError = aL.substring(6).trim();
				}
				else {
					aGen.commonLine(aL);
				}
			}
		}
		finally {
			//Still alive: idle, its model loaded (a killed one stays as the process of aGen until its end)
			if(process.isAlive() && !aGen.canceled()) {
				aGen.process = null;
			}
		}
		if(aError != null) {
			//Out of memory: the memory is given back, the model loaded again at the next request
			if("OOM".equals(aError)) {
				unload();
			}
			throw new PythonAI.GenerationException(aError);
		}
		if(aDone == null) {
			throw new Exception("Nothing produced (see log/3De.log)");
		}
		return aDone;
	}

	/**
	 * Stop the script and wait for its end (its memory given back before the next model is loaded)
	 */
	@Override
	public void unload() {
		PythonAI.residentGone(this);
		PythonAI.kill(process);
		try {
			process.waitFor(10, TimeUnit.SECONDS);
		}
		catch(InterruptedException e) {
		}
		try {
			Runtime.getRuntime().removeShutdownHook(hook);
		}
		catch(Throwable t) {
			//Already quitting
		}
	}
}
