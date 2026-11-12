package com.cubaix.TDenlive.GUI;

import org.eclipse.swt.SWT;
import org.eclipse.swt.browser.Browser;
import org.eclipse.swt.browser.LocationAdapter;
import org.eclipse.swt.browser.LocationEvent;
import org.eclipse.swt.browser.ProgressEvent;
import org.eclipse.swt.browser.ProgressListener;
import org.eclipse.swt.browser.StatusTextEvent;
import org.eclipse.swt.browser.StatusTextListener;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Label;

import com.cubaix.TDenlive.TDenlive;
import com.cubaix.TDenlive.utils.BrowserControl;

public class WebBrowserGUI extends CompositePanel {
	Browser browser = null;
	/** WebKit of JavaFX, when SWT has no browser (Linux without WebKitGTK) */
	FxBrowser fx = null;
	/** Page asked, opened in the browser of the system when no embedded browser is available */
	String url = null;

	public WebBrowserGUI(TDenlive aTDe, Composite aParent) {
		this(aTDe, aParent, null);
	}

	/**
	 * @param aURL page shown, null: the news page
	 */
	public WebBrowserGUI(TDenlive aTDe, Composite aParent, String aURL) {
		super(aTDe, aParent);
		createContents(aURL);
	}

	/**
	 * News page of the web site, in the language of the interface
	 */
	public static String newsURL(TDenlive aTDe) {
		return "https://3denlive.com/news"+aTDe.config.lng.toUpperCase()+".php";
	}

	/**
	 * Demos page of the web site, in the language of the interface (FR, else EN)
	 */
	public static String demosURL(TDenlive aTDe) {
		return "FR".equalsIgnoreCase(aTDe.config.lng) ? "https://3denlive.com/demos/index.php" : "https://3denlive.com/demos/en.php";
	}

	/**
	 * Show another page
	 */
	public void setUrl(String aURL) {
		url = aURL;
		if(browser != null && !browser.isDisposed()) {
			setCookies(aURL);
			browser.setUrl(aURL);
		}
		else if(fx != null) {
			if(!fx.getControl().isDisposed()) {
				fx.load(aURL);
			}
		}
		else if(browser == null) {
			//No embedded browser: the page asked by a button goes to the browser of the system
			BrowserControl.openURL(aURL);
		}
	}

	static void setCookies(String aURL) {
		Browser.setCookie("COOKU=3De; expires=Sun, 10 Jan 2038 07:59:59 GMT", aURL);
		Browser.setCookie("CN=3De; expires=Sun, 10 Jan 2038 07:59:59 GMT", aURL);
	}

	/**
	 * Script run on each loaded page: the links to the demo zips ("Project") become buttons with a
	 * download sign, so that it is clear that a click there opens the project in the application.
	 * ES5 only, idempotent (style and buttons added once).
	 */
	static final String DEMO_BUTTONS_JS =
		"(function(){" +
		"var aLinks=document.getElementsByTagName('a');var aFound=false;" +
		"for(var i=0;i<aLinks.length;i++){" +
		" var aA=aLinks[i];var aH=aA.href||'';" +
		" if(!/^https?:\\/\\/(www\\.)?3denlive\\.com\\/demos\\/[^?#]+\\.zip([?#].*)?$/i.test(aH)){continue;}" +
		" aFound=true;" +
		" if(aA.className.indexOf('tde-demo-btn')>=0){continue;}" +
		" aA.className+=' tde-demo-btn';" +
		" var aI=document.createElement('span');aI.className='tde-demo-ico';aI.appendChild(document.createTextNode('\\u2B07'));" +
		" aA.insertBefore(aI,aA.firstChild);" +
		"}" +
		"if(aFound&&!document.getElementById('tde-demo-style')){" +
		" var aS=document.createElement('style');aS.id='tde-demo-style';" +
		" aS.appendChild(document.createTextNode(" +
		"  'a.tde-demo-btn{display:inline-block;background:#792c7d;color:#fff !important;text-decoration:none;" +
		"padding:6px 16px;margin:4px 6px;border-radius:6px;font-weight:bold;box-shadow:0 2px 4px rgba(0,0,0,.25);" +
		"cursor:pointer;transition:background .15s,transform .1s;}'+" +
		"  'a.tde-demo-btn:hover{background:#00328d;}'+" +
		"  'a.tde-demo-btn:active{transform:translateY(1px);box-shadow:0 1px 2px rgba(0,0,0,.25);}'+" +
		"  '.tde-demo-ico{margin-right:8px;}'));" +
		" (document.head||document.documentElement).appendChild(aS);" +
		"}" +
		"})();";

	void createContents(String aStartURL) {
		String aURL = url = aStartURL != null ? aStartURL : newsURL(tde);
		Browser aBrowser;
		try {
			aBrowser = browser = new Browser(container, SWT.NONE);
		}
		catch(Throwable t) {
			//Linux without WebKitGTK: the WebKit of JavaFX if present, else a message and a button to the system browser
			System.err.println("No SWT web browser: " + t.getMessage());
			browser = null;
			if(createFxContents(aURL)) {
				return;
			}
			createNoBrowserContents();
			if(aStartURL != null) {
				BrowserControl.openURL(aStartURL);
			}
			return;
		}
		try {
			aBrowser.setLayoutData(new GridData(GridData.FILL_BOTH));
			setCookies(aURL);
			//The "Project" zips of the demos page: downloaded, unzipped and opened by the application
			aBrowser.addLocationListener(new LocationAdapter() {
				@Override
				public void changing(LocationEvent aE) {
					if(DemoProjectGUI.isDemoZip(aE.location)) {
						aE.doit = false;
						String aZip = aE.location;
						tde.gui.display.asyncExec(() -> DemoProjectGUI.open(tde, aZip));
					}
				}
			});
			aBrowser.setUrl(aURL);
			aBrowser.addStatusTextListener(new StatusTextListener() {

				@Override
				public void changed(StatusTextEvent arg0) {
					//				String aContent = aBrowser.getText();
					//				if(aContent.indexOf("Roboto") < 0) {
					//					String aMsg = errorPage();
					//					aBrowser.setText(aMsg);
					//				}
				}
			});
			aBrowser.addProgressListener(new ProgressListener() {
				@Override
				public void completed(ProgressEvent arg0) {
					String aContent = aBrowser.getText();
					if(aContent.indexOf("Roboto") < 0) {
						String aMsg = errorPage();
						aBrowser.setText(aMsg);
						return;
					}
					try {
						aBrowser.execute(DEMO_BUTTONS_JS);
					}
					catch(Throwable t) {
						t.printStackTrace(System.err);
					}
				}
				@Override
				public void changed(ProgressEvent arg0) {
				}
			});
		}
		catch(Throwable t) {
			t.printStackTrace(System.err);
		}
	}

	/**
	 * The page in the WebKit of JavaFX (FxBrowser), when SWT has no browser
	 * @return false when JavaFX is absent or fails
	 */
	boolean createFxContents(String aURL) {
		if(!FxBrowser.isAvailable()) {
			return false;
		}
		try {
			fx = new FxBrowser(container,
				aLoc -> fx.execute(DEMO_BUTTONS_JS),
				aLoc -> {
					System.err.println("Web page not loaded: " + aLoc);
					fx.loadContent(errorPage());
				},
				aZip -> tde.gui.display.asyncExec(() -> {
					if(DemoProjectGUI.isDemoZip(aZip)) {
						DemoProjectGUI.open(tde, aZip);
					}
					else {
						BrowserControl.openURL(aZip);
					}
				}));
			fx.getControl().setLayoutData(new GridData(GridData.FILL_BOTH));
			fx.load(aURL);
			System.err.println("Web pages shown with JavaFX");
			return true;
		}
		catch(Throwable t) {
			System.err.println("No JavaFX web browser either:");
			t.printStackTrace(System.err);
			if(fx != null && fx.getControl() != null && !fx.getControl().isDisposed()) {
				fx.getControl().dispose();
			}
			fx = null;
			return false;
		}
	}

	/**
	 * Shown instead of the web page when there is no embedded browser (Linux without WebKitGTK nor JavaFX)
	 */
	void createNoBrowserContents() {
		Composite aBox = new Composite(container, SWT.NONE);
		aBox.setBackground(tde.gui.colorsSwt.WHITE);
		aBox.setLayoutData(new GridData(SWT.CENTER, SWT.CENTER, true, true));
		GridLayout aLayout = new GridLayout(1, false);
		aLayout.verticalSpacing = 12;
		aBox.setLayout(aLayout);
		boolean aLinux = !System.getProperty("os.name").startsWith("Mac OS") && !System.getProperty("os.name").startsWith("Windows");
		Label aMsg = new Label(aBox, SWT.WRAP | SWT.CENTER);
		aMsg.setBackground(tde.gui.colorsSwt.WHITE);
		aMsg.setText(tde.gui.lngs.get("Web.NoBrowser") + (aLinux ? "\n\n" + tde.gui.lngs.get("Web.NoBrowserLinux") : ""));
		GridData aGD = new GridData(SWT.CENTER, SWT.CENTER, true, false);
		aGD.widthHint = 460;
		aMsg.setLayoutData(aGD);
		Button aOpen = new Button(aBox, SWT.PUSH);
		aOpen.setText(tde.gui.lngs.get("Web.OpenInBrowser"));
		aOpen.setLayoutData(new GridData(SWT.CENTER, SWT.CENTER, true, false));
		aOpen.addListener(SWT.Selection, e -> BrowserControl.openURL(url));
	}

	String errorPage() {
		return "<html>" +
				"<head>\n" + 
				"<meta http-equiv=\"content-type\" content=\"text/html; charset=UTF-8\">\n" + 
				"<style type=\"text/css\">\n" + 
				"body{\n" + 
				"  font-family:Roboto, sans-serif;\n" + 
				"  color: #00328dff;\n" + 
				"}\n" + 
				"</style>\n" + 
				"</head>\n" + 
				" <body>\n" + 
				"<center>"
				+ errorMessageEN()
				+ "</center>"
				+ "</body></html>";
	}
	String errorMessageEN() {
		//Should be done later with lgns
		return "I can't open this page from the 3Denlive website.<br/><br/>"
		+ "You are certainly offline, or you have a security system blocking that call from the software.<br/><br/>"
		+ "The News page can be called outside of 3Denlive by clicking the lamp button on the right of the main menu bar.";
	}
}
