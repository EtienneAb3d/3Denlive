package com.cubaix.TDenlive.GUI;

import org.eclipse.swt.SWT;
import org.eclipse.swt.browser.Browser;
import org.eclipse.swt.browser.ProgressEvent;
import org.eclipse.swt.browser.ProgressListener;
import org.eclipse.swt.browser.StatusTextEvent;
import org.eclipse.swt.browser.StatusTextListener;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.widgets.Composite;

import com.cubaix.TDenlive.TDenlive;

public class WebBrowserGUI extends CompositePanel {

	public WebBrowserGUI(TDenlive aTDe, Composite aParent) {
		super(aTDe, aParent);
		createContents();
	}

	void createContents() {
		try {
			Browser aBrowser = new Browser(container, SWT.NONE);
			aBrowser.setLayoutData(new GridData(GridData.FILL_BOTH));
			String aURL = "http://3denlive.com/news"+tde.config.lng.toUpperCase()+".php";
			Browser.setCookie("COOKU=3De; expires=Sun, 10 Jan 2038 07:59:59 GMT", aURL);
			Browser.setCookie("CN=3De; expires=Sun, 10 Jan 2038 07:59:59 GMT", aURL);
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
		return "I can't open the News page from the 3Denlive website.<br/><br/>"
		+ "You are certainly offline, or you have a security system blocking that call from the software.<br/><br/>"
		+ "The News page can be called outside of 3Denlive by clicking the lampe button on the right of the main menu bar.";
	}
}
