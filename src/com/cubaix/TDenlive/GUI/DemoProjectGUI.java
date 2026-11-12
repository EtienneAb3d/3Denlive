package com.cubaix.TDenlive.GUI;

import java.io.File;
import java.io.FileInputStream;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import org.eclipse.swt.SWT;
import org.eclipse.swt.events.SelectionAdapter;
import org.eclipse.swt.events.SelectionEvent;
import org.eclipse.swt.graphics.Rectangle;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.MessageBox;
import org.eclipse.swt.widgets.ProgressBar;
import org.eclipse.swt.widgets.Shell;

import com.cubaix.TDenlive.TDenlive;
import com.cubaix.TDenlive.depth.Downloader;

/**
 * Demo project of the demos page (zip link "Project" of https://3denlive.com/demos/): downloaded into
 * ~/3Denlive/Demos/<name>.zip, unzipped into ~/3Denlive/Demos/<name>/ (a leading <name>/ folder of the
 * zip is not doubled), then its .3De is opened. A demo already unzipped is opened again, or downloaded
 * again on request (its files replaced).
 */
public class DemoProjectGUI {
	/** Zip links of the demos page handled by the application instead of the browser */
	static final String ZIP_URL_REGEX = "(?i)^https?://(www\\.)?3denlive\\.com/demos/[^?#]+\\.zip([?#].*)?$";

	TDenlive tde;
	String url;
	String name;
	File demosDir;
	File projectDir;

	Shell shell;
	Label statusL;
	ProgressBar bar;
	volatile boolean canceled = false;

	public static boolean isDemoZip(String aUrl) {
		return aUrl != null && aUrl.matches(ZIP_URL_REGEX);
	}

	/**
	 * Downloads, unzips and opens the demo project of the link (GUI thread)
	 */
	public static void open(TDenlive aTDe,String aUrl) {
		new DemoProjectGUI(aTDe, aUrl).start();
	}

	DemoProjectGUI(TDenlive aTDe,String aUrl) {
		tde = aTDe;
		url = aUrl;
		String aPath = URI.create(aUrl.replaceAll("[?#].*$", "")).getPath();
		String aFile = aPath.substring(aPath.lastIndexOf('/')+1);
		name = aFile.replaceAll("(?i)[.]zip$", "").replaceAll("[^A-Za-z0-9_.-]", "_");
		if(name.isEmpty() || name.startsWith(".")) {
			name = "Demo"+name;
		}
		demosDir = new File(tde.tdeDir, "Demos").getAbsoluteFile();
		projectDir = new File(demosDir, name);
	}

	String lng(String aKey) {
		return tde.gui.lngs.get(aKey);
	}

	void start() {
		TDeGUI aGUI = tde.gui;
		//The montage in progress first
		if(tde.isModified()) {
			MessageBox aMB = new MessageBox(aGUI.shell, SWT.ICON_WARNING|SWT.YES|SWT.NO|SWT.CANCEL);
			aMB.setText(lng("Demos.Title"));
			String aName = tde.projectName == null ? lng("Generic.Untitled") : tde.projectName;
			aMB.setMessage(aName+"\n"+lng("Demos.SaveBefore"));
			int aAnswer = aMB.open();
			if(aAnswer == SWT.CANCEL || (aAnswer == SWT.YES && !aGUI.saveProject(false))) {
				return;
			}
		}
		File aExisting = findProject(projectDir);
		if(aExisting != null) {
			MessageBox aMB = new MessageBox(aGUI.shell, SWT.ICON_QUESTION|SWT.YES|SWT.NO|SWT.CANCEL);
			aMB.setText(lng("Demos.Title"));
			aMB.setMessage(name+"\n"+lng("Demos.AlreadyThere")+"\n"+projectDir.getPath());
			int aAnswer = aMB.open();
			if(aAnswer == SWT.CANCEL) {
				return;
			}
			if(aAnswer == SWT.YES) {
				aGUI.openProject(aExisting.getPath());
				return;
			}
		}
		showProgress();
		Thread aTh = new Thread(() -> run(), "DemoProject");
		aTh.setDaemon(true);
		aTh.start();
	}

	void showProgress() {
		shell = new Shell(tde.gui.shell, SWT.DIALOG_TRIM);
		shell.setText(lng("Demos.Title")+" - "+name);
		shell.setLayout(new GridLayout(1, false));
		tde.gui.applyColorFont(shell);
		shell.setImage(tde.gui.imgsSwt.getIcon("3Denlive32.png"));
		statusL = new Label(shell, SWT.WRAP);
		tde.gui.applyColorFont(statusL);
		statusL.setText(lng("Demos.Downloading"));
		GridData aLGD = new GridData(GridData.FILL_HORIZONTAL);
		aLGD.widthHint = 420;
		statusL.setLayoutData(aLGD);
		bar = new ProgressBar(shell, SWT.SMOOTH | SWT.HORIZONTAL);
		bar.setMinimum(0);
		bar.setMaximum(1000);
		GridData aPGD = new GridData(GridData.FILL_HORIZONTAL);
		aPGD.widthHint = 420;
		bar.setLayoutData(aPGD);
		Button aCancel = new Button(shell, SWT.PUSH);
		aCancel.setText(lng("Demos.Cancel"));
		aCancel.setImage(tde.gui.imgsSwt.getIcon("stop.gif"));
		tde.gui.applyColorFont(aCancel);
		aCancel.setLayoutData(new GridData(GridData.HORIZONTAL_ALIGN_END));
		aCancel.addSelectionListener(new SelectionAdapter() {
			@Override
			public void widgetSelected(SelectionEvent e) {
				canceled = true;
			}
		});
		//Closing the window cancels
		shell.addListener(SWT.Close, e -> canceled = true);
		shell.pack();
		Rectangle aP = tde.gui.shell.getBounds();
		Rectangle aS = shell.getBounds();
		shell.setLocation(aP.x + (aP.width - aS.width) / 2, aP.y + (aP.height - aS.height) / 2);
		shell.open();
	}

	void status(String aKey,long aDone,long aTotal) {
		tde.gui.display.asyncExec(() -> {
			if(shell == null || shell.isDisposed()) {
				return;
			}
			String aText = lng(aKey);
			if(aTotal > 0) {
				aText += "  "+(aDone >> 20)+" / "+(aTotal >> 20)+" MB";
				bar.setSelection((int)(1000L*aDone/aTotal));
			}
			statusL.setText(aText);
		});
	}

	/**
	 * Worker thread: download, unzip, then open in the GUI thread
	 */
	void run() {
		File aZip = new File(demosDir, name+".zip");
		String aError = null;
		File aProject = null;
		try {
			File aPart = new File(demosDir, name+".zip.download");
			aPart.delete();
			boolean aDone = Downloader.download(url, aPart, 0, new Downloader.Progress() {
				@Override
				public void downloaded(long aDone,long aTotal) {
					status("Demos.Downloading", aDone, aTotal);
				}
				@Override
				public boolean isCanceled() {
					return canceled;
				}
			});
			if(aDone && !canceled) {
				Files.move(aPart.toPath(), aZip.toPath(), StandardCopyOption.REPLACE_EXISTING);
				status("Demos.Extracting", 0, 0);
				unzip(aZip);
				if(!canceled) {
					aProject = findProject(projectDir);
					if(aProject == null) {
						aError = lng("Demos.NoProject");
					}
				}
			}
		}
		catch(Throwable t) {
			t.printStackTrace(System.err);
			aError = t.getMessage() == null ? t.toString() : t.getMessage();
		}
		final String aErr = aError;
		final File aPrj = canceled ? null : aProject;
		tde.gui.display.asyncExec(() -> {
			if(shell != null && !shell.isDisposed()) {
				shell.dispose();
			}
			if(tde.gui.shell.isDisposed()) {
				return;
			}
			if(aErr != null) {
				MessageBox aMB = new MessageBox(tde.gui.shell, SWT.ICON_ERROR|SWT.OK);
				aMB.setText(lng("Demos.Title"));
				aMB.setMessage(lng("Demos.Error")+"\n"+url+"\n\n"+aErr);
				aMB.open();
				return;
			}
			if(aPrj != null) {
				tde.gui.openProject(aPrj.getPath());
			}
		});
	}

	/**
	 * Unzip into Demos/<name>/, never outside of it. A first folder named as the zip is not repeated.
	 * The files are replaced (a demo downloaded again).
	 */
	void unzip(File aZip) throws Exception {
		File aRoot = projectDir.getCanonicalFile();
		aRoot.mkdirs();
		String aPrefix = name+"/";
		try(ZipInputStream aZIS = new ZipInputStream(new FileInputStream(aZip))) {
			ZipEntry aZE;
			while((aZE = aZIS.getNextEntry()) != null && !canceled) {
				String aName = aZE.getName().replace('\\', '/');
				if(aName.equalsIgnoreCase(name) || aName.equalsIgnoreCase(aPrefix)) {
					continue;
				}
				if(aName.regionMatches(true, 0, aPrefix, 0, aPrefix.length())) {
					aName = aName.substring(aPrefix.length());
				}
				if(aName.isEmpty() || aName.startsWith("__MACOSX/") || aName.endsWith("/.DS_Store") || aName.equals(".DS_Store")) {
					continue;
				}
				File aF = new File(aRoot, aName).getCanonicalFile();
				if(!aF.toPath().startsWith(aRoot.toPath()) || aF.equals(aRoot)) {
					System.err.println("Demo zip entry outside of its folder, skipped: "+aZE.getName());
					continue;
				}
				if(aZE.isDirectory()) {
					aF.mkdirs();
					continue;
				}
				aF.getParentFile().mkdirs();
				Files.copy(aZIS, aF.toPath(), StandardCopyOption.REPLACE_EXISTING);
				if(aZE.getTime() > 0) {
					aF.setLastModified(aZE.getTime());
				}
			}
		}
	}

	/**
	 * The .3De of the demo folder: <name>.3De if there, else the first one found (shallowest first)
	 */
	static File findProject(File aDir) {
		if(!aDir.isDirectory()) {
			return null;
		}
		File[] aFs = aDir.listFiles();
		if(aFs == null) {
			return null;
		}
		java.util.Arrays.sort(aFs);
		File aSame = new File(aDir, aDir.getName()+".3De");
		if(aSame.isFile()) {
			return aSame;
		}
		for(File aF : aFs) {
			if(aF.isFile() && aF.getName().toLowerCase().endsWith(".3de")) {
				return aF;
			}
		}
		for(File aF : aFs) {
			if(aF.isDirectory() && !aF.getName().equals("WorkCache")) {
				File aP = findProject(aF);
				if(aP != null) {
					return aP;
				}
			}
		}
		return null;
	}
}
