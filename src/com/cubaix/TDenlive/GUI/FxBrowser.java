package com.cubaix.TDenlive.GUI;

import java.io.File;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.net.CookieHandler;
import java.net.URI;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import org.eclipse.swt.SWT;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;

/**
 * Embedded web browser with the WebKit of JavaFX, used on Linux when SWT has no browser (WebKitGTK not
 * installed): the Linux delivery stays self-contained. JavaFX (jars + natives of the Gluon SDK, put by
 * ./Install-Dev.sh into distrib/Linux/javafx/, and into javafx/ of the delivery) is not on the classpath:
 * it is loaded with its own URLClassLoader and called by reflection, so nothing is needed to compile or
 * on the other platforms. The WebView is shown in an SWT FXCanvas (the SWT thread is the JavaFX thread).
 * JavaFX needs X11 (the launchers set GDK_BACKEND=x11). Its videos use the libavcodec of the system.
 */
public class FxBrowser {
	/** Folders of the JavaFX jars, relative to the working directory: delivery, then development (distrib/) */
	static final String[] DIRS = {"javafx", "Linux/javafx"};
	static final String ZIP_ALERT = "TDE-ZIP:";

	static ClassLoader loader = null;
	static String fixesJs = null;

	Control canvas;
	Object engine;

	/**
	 * Folder of the JavaFX jars, null when absent (or not on Linux)
	 */
	static File fxDir() {
		String aOs = System.getProperty("os.name");
		if(aOs.startsWith("Mac OS") || aOs.startsWith("Windows")) {
			return null;
		}
		for(String aD : DIRS) {
			File aDir = new File(aD);
			if(new File(aDir, "javafx-swt.jar").isFile() && new File(aDir, "javafx.web.jar").isFile()) {
				return aDir;
			}
		}
		return null;
	}

	public static boolean isAvailable() {
		return fxDir() != null;
	}

	static synchronized ClassLoader loader() throws Exception {
		if(loader == null) {
			File aDir = fxDir();
			if(aDir == null) {
				throw new IllegalStateException("JavaFX not found in " + Arrays.toString(DIRS));
			}
			List<URL> aUrls = new ArrayList<>();
			for(File aF : aDir.listFiles()) {
				if(aF.getName().endsWith(".jar")) {
					aUrls.add(aF.getAbsoluteFile().toURI().toURL());
				}
			}
			//Parent: the application loader, so that FXCanvas sees the same SWT classes
			loader = new URLClassLoader(aUrls.toArray(new URL[0]), FxBrowser.class.getClassLoader());
		}
		return loader;
	}

	static Class<?> cls(String aName) throws Exception {
		return Class.forName(aName, true, loader());
	}

	/**
	 * Public method of a public JavaFX type (the objects are often of non public classes)
	 */
	static Object call(String aClass, Object aObj, String aMethod, Class<?>[] aTypes, Object... aArgs) throws Exception {
		Method aM = cls(aClass).getMethod(aMethod, aTypes);
		return aM.invoke(aObj, aArgs);
	}

	static Object call(String aClass, Object aObj, String aMethod) throws Exception {
		return call(aClass, aObj, aMethod, new Class<?>[0]);
	}

	/**
	 * Implementation of a JavaFX functional interface (ChangeListener, EventHandler...)
	 */
	static Object proxy(String aInterface, String aMethod, Consumer<Object[]> aDo) throws Exception {
		Class<?> aI = cls(aInterface);
		return Proxy.newProxyInstance(loader(), new Class<?>[] {aI}, (aProxy, aM, aArgs) -> {
			switch(aM.getName()) {
			case "equals": return aProxy == aArgs[0];
			case "hashCode": return System.identityHashCode(aProxy);
			case "toString": return "FxBrowser." + aI.getSimpleName();
			}
			if(aM.getName().equals(aMethod)) {
				try {
					aDo.accept(aArgs);
				}
				catch(Throwable t) {
					t.printStackTrace(System.err);
				}
			}
			return null;
		});
	}

	static String fixesJs() {
		if(fixesJs == null) {
			try(InputStream aIs = FxBrowser.class.getResourceAsStream("/com/cubaix/TDenlive/res/html/javafx_fixes.js")) {
				fixesJs = new String(aIs.readAllBytes(), StandardCharsets.UTF_8);
			}
			catch(Throwable t) {
				t.printStackTrace(System.err);
				fixesJs = "";
			}
		}
		return fixesJs;
	}

	/**
	 * @param aParent SWT parent of the FXCanvas
	 * @param aOnLoaded called with the location of each page loaded (scripts to run...)
	 * @param aOnFailed called with the location of a page that could not be loaded
	 * @param aOnZip called with the URL of a zip link clicked (not downloaded by JavaFX)
	 */
	public FxBrowser(Composite aParent, Consumer<String> aOnLoaded, Consumer<String> aOnFailed, Consumer<String> aOnZip) throws Exception {
		Thread aT = Thread.currentThread();
		ClassLoader aOldCL = aT.getContextClassLoader();
		aT.setContextClassLoader(loader());
		try {
			//The toolkit must stay alive when the last FXCanvas is disposed (rebuilt window contents)
			call("javafx.application.Platform", null, "setImplicitExit", new Class<?>[] {boolean.class}, false);
			canvas = (Control)cls("javafx.embed.swt.FXCanvas").getConstructor(Composite.class, int.class).newInstance(aParent, SWT.NONE);
			Object aView = cls("javafx.scene.web.WebView").getConstructor().newInstance();
			engine = call("javafx.scene.web.WebView", aView, "getEngine");
			Object aScene = cls("javafx.scene.Scene").getConstructor(cls("javafx.scene.Parent")).newInstance(aView);
			call("javafx.embed.swt.FXCanvas", canvas, "setScene", new Class<?>[] {cls("javafx.scene.Scene")}, aScene);
		}
		finally {
			aT.setContextClassLoader(aOldCL);
		}

		Object aWorker = call("javafx.scene.web.WebEngine", engine, "getLoadWorker");
		Object aState = call("javafx.concurrent.Worker", aWorker, "stateProperty");
		call("javafx.beans.value.ObservableValue", aState, "addListener", new Class<?>[] {cls("javafx.beans.value.ChangeListener")},
			proxy("javafx.beans.value.ChangeListener", "changed", aArgs -> {
				String aNew = String.valueOf(aArgs[2]);
				if("SUCCEEDED".equals(aNew)) {
					execute(fixesJs());
					aOnLoaded.accept(getLocation());
				}
				else if("FAILED".equals(aNew)) {
					aOnFailed.accept(getLocation());
				}
			}));
		call("javafx.scene.web.WebEngine", engine, "setOnAlert", new Class<?>[] {cls("javafx.event.EventHandler")},
			proxy("javafx.event.EventHandler", "handle", aArgs -> {
				try {
					Object aData = call("javafx.scene.web.WebEvent", aArgs[0], "getData");
					String aMsg = String.valueOf(aData);
					if(aMsg.startsWith(ZIP_ALERT)) {
						aOnZip.accept(aMsg.substring(ZIP_ALERT.length()));
					}
				}
				catch(Throwable t) {
					t.printStackTrace(System.err);
				}
			}));
	}

	public Control getControl() {
		return canvas;
	}

	public void load(String aURL) {
		try {
			setCookies(aURL);
			call("javafx.scene.web.WebEngine", engine, "load", new Class<?>[] {String.class}, aURL);
		}
		catch(Throwable t) {
			t.printStackTrace(System.err);
		}
	}

	public void loadContent(String aHtml) {
		try {
			call("javafx.scene.web.WebEngine", engine, "loadContent", new Class<?>[] {String.class}, aHtml);
		}
		catch(Throwable t) {
			t.printStackTrace(System.err);
		}
	}

	public Object execute(String aJs) {
		try {
			return call("javafx.scene.web.WebEngine", engine, "executeScript", new Class<?>[] {String.class}, aJs);
		}
		catch(Throwable t) {
			t.printStackTrace(System.err);
			return null;
		}
	}

	public String getLocation() {
		try {
			return (String)call("javafx.scene.web.WebEngine", engine, "getLocation");
		}
		catch(Throwable t) {
			t.printStackTrace(System.err);
			return null;
		}
	}

	/**
	 * Same cookies as WebBrowserGUI.setCookies() for the SWT browser (JavaFX uses the CookieHandler of Java,
	 * installed with the first WebEngine)
	 */
	static void setCookies(String aURL) {
		try {
			CookieHandler aCH = CookieHandler.getDefault();
			if(aCH != null && aURL != null && aURL.startsWith("http")) {
				aCH.put(new URI(aURL), Map.of("Set-Cookie", List.of(
					"COOKU=3De; expires=Sun, 10 Jan 2038 07:59:59 GMT",
					"CN=3De; expires=Sun, 10 Jan 2038 07:59:59 GMT")));
			}
		}
		catch(Throwable t) {
			t.printStackTrace(System.err);
		}
	}
}
