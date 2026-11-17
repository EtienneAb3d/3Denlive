package com.cubaix.TDenlive.utils;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.util.Map;

/**
 * Started from a snap application (VS Code installed as a snap: its terminal, its Run / Debug), the
 * environment carries the snap's GTK / GIO module paths. Their modules, built against the libc of the
 * snap, make the WebKit network process crash (no web page loads). On Linux, before GTK starts, the
 * variables that point into a snap are removed from the native environment of the process (read by GTK,
 * GIO and the WebKit processes it spawns), and the *_VSCODE_SNAP_ORIG values are restored.
 * The launchers do the same (run_3De.sh, 3Denlive.sh); this covers the VS Code launch configuration.
 */
public class SnapEnv {
	static final String[] VARS = {"GTK_PATH", "GTK_EXE_PREFIX", "GTK_IM_MODULE_FILE", "GIO_MODULE_DIR"
			, "GDK_PIXBUF_MODULE_FILE", "GDK_PIXBUF_MODULEDIR", "LOCPATH", "GSETTINGS_SCHEMA_DIR"
			, "LD_LIBRARY_PATH", "LD_PRELOAD"};
	static final String ORIG = "_VSCODE_SNAP_ORIG";

	public static void clean() {
		try {
			if(!System.getProperty("os.name").toLowerCase().contains("linux")) {
				return;
			}
			Map<String, String> aEnv = System.getenv();
			Linker aL = Linker.nativeLinker();
			MethodHandle aUnset = aL.downcallHandle(aL.defaultLookup().find("unsetenv").orElseThrow()
					, FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS));
			MethodHandle aSet = aL.downcallHandle(aL.defaultLookup().find("setenv").orElseThrow()
					, FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.JAVA_INT));
			try(Arena aA = Arena.ofConfined()) {
				for(String aV : VARS) {
					String aVal = aEnv.get(aV);
					if(aVal != null && aVal.contains("/snap/") && aEnv.get(aV+ORIG) == null) {
						int aR = (int)aUnset.invokeExact(aA.allocateFrom(aV));
						System.out.println("Snap environment: "+aV+" removed"+(aR == 0 ? "" : " (failed)"));
					}
				}
				for(Map.Entry<String, String> aE : aEnv.entrySet()) {
					if(aE.getKey().endsWith(ORIG)) {
						String aV = aE.getKey().substring(0, aE.getKey().length()-ORIG.length());
						MemorySegment aName = aA.allocateFrom(aV);
						int aR = aE.getValue().isEmpty() ? (int)aUnset.invokeExact(aName)
								: (int)aSet.invokeExact(aName, aA.allocateFrom(aE.getValue()), 1);
						System.out.println("Snap environment: "+aV+" restored"+(aR == 0 ? "" : " (failed)"));
					}
				}
				String aXdg = aEnv.get("XDG_DATA_DIRS");
				if(aXdg != null && aXdg.contains("/snap/") && aEnv.get("XDG_DATA_DIRS"+ORIG) == null) {
					StringBuilder aSb = new StringBuilder();
					for(String aD : aXdg.split(":")) {
						if(!aD.contains("/snap/")) {
							aSb.append(aSb.length() > 0 ? ":" : "").append(aD);
						}
					}
					int aR = (int)aSet.invokeExact(aA.allocateFrom("XDG_DATA_DIRS"), aA.allocateFrom(aSb.toString()), 1);
					System.out.println("Snap environment: XDG_DATA_DIRS cleaned"+(aR == 0 ? "" : " (failed)"));
				}
			}
		}
		catch(Throwable t) {
			t.printStackTrace(System.err);
		}
	}
}
