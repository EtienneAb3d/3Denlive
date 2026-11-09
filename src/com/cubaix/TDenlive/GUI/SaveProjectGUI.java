package com.cubaix.TDenlive.GUI;

import java.io.File;

import org.eclipse.swt.SWT;
import org.eclipse.swt.events.SelectionAdapter;
import org.eclipse.swt.events.SelectionEvent;
import org.eclipse.swt.graphics.Rectangle;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.DirectoryDialog;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.MessageBox;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Text;

import com.cubaix.TDenlive.TDenlive;

/**
 * Save panel of the project: name, folder (typed or chosen), and the option to create a new folder
 * for the project (named after it, in the chosen folder). Shows the file that will be written.
 * open() returns the path of the .3De file, null if canceled.
 */
public class SaveProjectGUI {
	TDenlive tde;
	Shell shell;
	Text nameT;
	Text dirT;
	Button newDirB;
	Label pathL;
	String result = null;

	public SaveProjectGUI(TDenlive aTDe) {
		tde = aTDe;
	}

	String lng(String aKey) {
		return tde.gui.lngs.get(aKey);
	}

	/**
	 * Project name typed, without extension nor characters forbidden in file names
	 */
	String name() {
		return nameT.getText().trim().replaceAll("(?i)([.]3de)+$", "").replaceAll("[\\\\/:*?\"<>|]", "_").trim();
	}

	/**
	 * Project file for the current choices, null if incomplete
	 */
	File target() {
		String aName = name();
		String aDir = dirT.getText().trim();
		if(aName.isEmpty() || aDir.isEmpty()) {
			return null;
		}
		File aD = new File(aDir);
		if(newDirB.getSelection()) {
			aD = new File(aD, aName);
		}
		return new File(aD, aName+".3De");
	}

	void update() {
		File aT = target();
		pathL.setText(aT == null ? " " : aT.getAbsolutePath());
	}

	public String open() {
		boolean aSaved = tde.projectDir != null && tde.projectName != null;
		shell = new Shell(tde.gui.shell, SWT.DIALOG_TRIM | SWT.APPLICATION_MODAL | SWT.RESIZE);
		shell.setText(lng("SaveProject.Title"));
		shell.setLayout(new GridLayout(3, false));
		tde.gui.applyColorFont(shell);
		shell.setImage(tde.gui.imgsSwt.getIcon("3Denlive32.png"));

		Label aNL = new Label(shell, SWT.NONE);
		tde.gui.applyColorFont(aNL);
		aNL.setText(lng("SaveProject.Name"));
		nameT = new Text(shell, SWT.BORDER | SWT.SINGLE);
		tde.gui.applyColorFont(nameT);
		GridData aNGD = new GridData(GridData.FILL_HORIZONTAL);
		aNGD.horizontalSpan = 2;
		aNGD.widthHint = 420;
		nameT.setLayoutData(aNGD);
		nameT.setText(aSaved ? tde.projectName.replaceAll("(?i)([.]3de)+$", "") : lng("SaveProject.DefaultName"));

		Label aDL = new Label(shell, SWT.NONE);
		tde.gui.applyColorFont(aDL);
		aDL.setText(lng("SaveProject.Folder"));
		dirT = new Text(shell, SWT.BORDER | SWT.SINGLE);
		tde.gui.applyColorFont(dirT);
		dirT.setLayoutData(new GridData(GridData.FILL_HORIZONTAL));
		dirT.setText(aSaved ? tde.projectDir : (tde.lastDir == null ? tde.tdeDir : tde.lastDir));
		Button aBrowse = new Button(shell, SWT.PUSH);
		tde.gui.applyColorFont(aBrowse);
		aBrowse.setText(lng("SaveProject.Browse"));
		aBrowse.addSelectionListener(new SelectionAdapter() {
			@Override
			public void widgetSelected(SelectionEvent e) {
				DirectoryDialog aDD = new DirectoryDialog(shell, SWT.OPEN);
				aDD.setText(lng("SaveProject.Folder"));
				if(new File(dirT.getText().trim()).isDirectory()) {
					aDD.setFilterPath(dirT.getText().trim());
				}
				String aDir = aDD.open();
				if(aDir != null) {
					dirT.setText(aDir);
				}
			}
		});

		new Label(shell, SWT.NONE);
		newDirB = new Button(shell, SWT.CHECK);
		tde.gui.applyColorFont(newDirB);
		newDirB.setText(lng("SaveProject.NewFolder"));
		GridData aCGD = new GridData(GridData.FILL_HORIZONTAL);
		aCGD.horizontalSpan = 2;
		newDirB.setLayoutData(aCGD);
		//A new project gets its own folder by default
		newDirB.setSelection(!aSaved);

		Label aPT = new Label(shell, SWT.NONE);
		tde.gui.applyColorFont(aPT);
		aPT.setText(lng("SaveProject.File"));
		pathL = new Label(shell, SWT.WRAP);
		tde.gui.applyColorFont(pathL);
		GridData aPGD = new GridData(GridData.FILL_HORIZONTAL);
		aPGD.horizontalSpan = 2;
		aPGD.widthHint = 420;
		pathL.setLayoutData(aPGD);

		nameT.addListener(SWT.Modify, e -> update());
		dirT.addListener(SWT.Modify, e -> update());
		newDirB.addListener(SWT.Selection, e -> update());
		update();

		Composite aButtons = new Composite(shell, SWT.NONE);
		GridData aBGD = new GridData(GridData.HORIZONTAL_ALIGN_END);
		aBGD.horizontalSpan = 3;
		aBGD.verticalIndent = 8;
		aButtons.setLayoutData(aBGD);
		tde.gui.applyColorFont(aButtons);
		GridLayout aBGL = new GridLayout(2, true);
		tde.gui.applyNoMargin(aBGL);
		aButtons.setLayout(aBGL);
		Button aCancel = new Button(aButtons, SWT.PUSH);
		aCancel.setText(lng("SaveProject.Cancel"));
		tde.gui.applyColorFont(aCancel);
		aCancel.setLayoutData(new GridData(GridData.FILL_HORIZONTAL));
		aCancel.addSelectionListener(new SelectionAdapter() {
			@Override
			public void widgetSelected(SelectionEvent e) {
				shell.close();
			}
		});
		Button aOk = new Button(aButtons, SWT.PUSH);
		aOk.setText(lng("Generic.Save"));
		aOk.setImage(tde.gui.imgsSwt.getIcon("save.gif"));
		tde.gui.applyColorFont(aOk);
		aOk.setLayoutData(new GridData(GridData.FILL_HORIZONTAL));
		aOk.addSelectionListener(new SelectionAdapter() {
			@Override
			public void widgetSelected(SelectionEvent e) {
				File aT = target();
				if(aT == null) {
					nameT.setFocus();
					return;
				}
				File aDir = aT.getAbsoluteFile().getParentFile();
				if(!aDir.isDirectory() && !aDir.mkdirs()) {
					MessageBox aMB = new MessageBox(shell, SWT.ICON_ERROR|SWT.OK);
					aMB.setText(lng("SaveProject.Title"));
					aMB.setMessage(lng("SaveProject.FolderError")+"\n"+aDir.getPath());
					aMB.open();
					return;
				}
				result = aT.getAbsolutePath();
				shell.close();
			}
		});
		shell.setDefaultButton(aOk);

		shell.pack();
		Rectangle aP = tde.gui.shell.getBounds();
		Rectangle aS = shell.getBounds();
		shell.setLocation(aP.x + (aP.width - aS.width) / 2, aP.y + (aP.height - aS.height) / 2);
		shell.open();
		nameT.setFocus();
		nameT.selectAll();
		while(!shell.isDisposed()) {
			if(!tde.gui.display.readAndDispatch()) {
				tde.gui.display.sleep();
			}
		}
		return result;
	}
}
