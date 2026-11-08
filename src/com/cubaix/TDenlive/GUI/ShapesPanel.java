package com.cubaix.TDenlive.GUI;

import java.util.Vector;

import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.Color;
import org.eclipse.swt.graphics.GC;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.graphics.Path;
import org.eclipse.swt.graphics.RGB;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.ColorDialog;
import org.eclipse.swt.widgets.Combo;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Spinner;
import org.eclipse.swt.widgets.ToolBar;
import org.eclipse.swt.widgets.ToolItem;

import com.cubaix.TDenlive.TDenlive;
import com.cubaix.TDenlive.GUI.widgets.LabelScale;
import com.cubaix.TDenlive.shapes.Shape;
import com.cubaix.TDenlive.shapes.ShapesEditor;

/**
 * Tools (selection, shapes to create) and properties of the selected shape
 * (fill, border, opacity, depth, visibility, order) of a ShapesEditor.
 * Without a selection, the properties are those of the next created shape.
 */
public class ShapesPanel implements ShapesEditor.Listener {
	static final int ICON_SIZE = 20;
	TDenlive tde = null;
	ShapesEditor editor = null;
	//In a clip: a deleted shape is only hidden, and can be shown again
	boolean isClip = false;
	public Composite container = null;
	Vector<Image> images = new Vector<Image>();
	ToolItem[] toolItems = null;
	ToolItem delete = null;
	ToolItem front = null;
	ToolItem back = null;
	ToolItem duplicate = null;
	Button visible = null;
	Button fill = null;
	Button fillColor = null;
	Button stroke = null;
	Button strokeColor = null;
	Spinner strokeWidth = null;
	Combo strokeStyle = null;
	LabelScale opacityLS = null;
	LabelScale depthLS = null;
	Label selectionL = null;
	boolean updating = false;

	public ShapesPanel(TDenlive aTDe,Composite aParent,ShapesEditor aEditor,boolean aIsClip) {
		tde = aTDe;
		editor = aEditor;
		isClip = aIsClip;
		createContents(aParent);
		editor.addListener(this);
		container.addDisposeListener(e -> {
			editor.removeListener(this);
			for(Image aI : images) {
				aI.dispose();
			}
			images.clear();
		});
		update();
	}

	/**
	 * Icon of a shape type (drawn), -1 for the selection arrow
	 */
	Image createIcon(int aType) {
		Image aI = new Image(tde.gui.display, ICON_SIZE, ICON_SIZE);
		GC aGC = new GC(aI);
		aGC.setAntialias(SWT.ON);
		aGC.setBackground(tde.gui.colorsSwt.WHITE);
		aGC.fillRectangle(0, 0, ICON_SIZE, ICON_SIZE);
		Color aFill = new Color(tde.gui.display, 46, 111, 216);
		aGC.setBackground(aFill);
		aGC.setForeground(tde.gui.colorsSwt.CUBAIX_BLUE);
		aGC.setLineWidth(1);
		java.awt.Shape aAwt = null;
		if(aType < 0) {
			java.awt.geom.Path2D.Double aP = new java.awt.geom.Path2D.Double();
			double[][] aPts = {{5,2},{5,16},{8.5,12.5},{11,18},{13,17},{10.5,11.5},{15,11.5}};
			aP.moveTo(aPts[0][0], aPts[0][1]);
			for(int p = 1;p < aPts.length;p++) {
				aP.lineTo(aPts[p][0], aPts[p][1]);
			}
			aP.closePath();
			aAwt = aP;
			aGC.setBackground(tde.gui.colorsSwt.WHITE);
		}
		else {
			Shape aS = Shape.create(aType, 2.5/ICON_SIZE, 3.5/ICON_SIZE, (ICON_SIZE-2.5)/ICON_SIZE, (ICON_SIZE-3.5)/ICON_SIZE, null);
			if(aS.isLine()) {
				aS.ys[0] = (ICON_SIZE-4.0)/ICON_SIZE;
				aS.ys[1] = 4.0/ICON_SIZE;
				aGC.setLineWidth(2);
			}
			aS.strokeWidth = 0.1;
			aAwt = aS.buildPath(0, 0, ICON_SIZE, ICON_SIZE, 0);
			java.awt.Shape aHead = aS.buildArrowHead(0, 0, ICON_SIZE, ICON_SIZE, 0);
			if(aHead != null) {
				Path aHP = toSwtPath(aGC, aHead);
				aGC.setBackground(tde.gui.colorsSwt.CUBAIX_BLUE);
				aGC.fillPath(aHP);
				aHP.dispose();
				aGC.setBackground(aFill);
			}
			if(aS.isLine()) {
				aAwt = new java.awt.geom.Line2D.Double(aS.xs[0]*ICON_SIZE, aS.ys[0]*ICON_SIZE
						, aS.xs[1]*ICON_SIZE-(aHead != null ? 3 : 0), aS.ys[1]*ICON_SIZE+(aHead != null ? 3 : 0));
			}
		}
		Path aP = toSwtPath(aGC, aAwt);
		if(!(aAwt instanceof java.awt.geom.Line2D)) {
			aGC.fillPath(aP);
		}
		aGC.drawPath(aP);
		aP.dispose();
		aGC.dispose();
		aFill.dispose();
		images.add(aI);
		return aI;
	}

	static Path toSwtPath(GC aGC,java.awt.Shape aShape) {
		Path aP = new Path(aGC.getDevice());
		java.awt.geom.PathIterator aPI = aShape.getPathIterator(null);
		float[] aC = new float[6];
		while(!aPI.isDone()) {
			switch(aPI.currentSegment(aC)) {
			case java.awt.geom.PathIterator.SEG_MOVETO: aP.moveTo(aC[0], aC[1]); break;
			case java.awt.geom.PathIterator.SEG_LINETO: aP.lineTo(aC[0], aC[1]); break;
			case java.awt.geom.PathIterator.SEG_QUADTO: aP.quadTo(aC[0], aC[1], aC[2], aC[3]); break;
			case java.awt.geom.PathIterator.SEG_CUBICTO: aP.cubicTo(aC[0], aC[1], aC[2], aC[3], aC[4], aC[5]); break;
			case java.awt.geom.PathIterator.SEG_CLOSE: aP.close(); break;
			}
			aPI.next();
		}
		return aP;
	}

	/**
	 * Color swatch for the color buttons
	 */
	Image swatch(int aARGB) {
		Image aI = new Image(tde.gui.display, 28, 14);
		GC aGC = new GC(aI);
		Color aC = new Color(tde.gui.display, (aARGB>>16)&0xFF, (aARGB>>8)&0xFF, aARGB&0xFF);
		aGC.setBackground(aC);
		aGC.fillRectangle(0, 0, 28, 14);
		aGC.setForeground(tde.gui.colorsSwt.GRAY);
		aGC.drawRectangle(0, 0, 27, 13);
		aGC.dispose();
		aC.dispose();
		images.add(aI);
		return aI;
	}

	void setSwatch(Button aB,int aARGB) {
		Image aOld = aB.getImage();
		aB.setImage(swatch(aARGB));
		if(aOld != null) {
			images.remove(aOld);
			aOld.dispose();
		}
	}

	Composite row(Composite aParent,int aNbCols) {
		Composite aRow = new Composite(aParent, SWT.NONE);
		aRow.setLayoutData(new GridData(GridData.FILL_HORIZONTAL));
		tde.gui.applyColorFont(aRow);
		GridLayout aGL = new GridLayout(aNbCols, false);
		aGL.marginWidth = 2;
		aGL.marginHeight = 1;
		aGL.horizontalSpacing = 4;
		aGL.verticalSpacing = 0;
		aRow.setLayout(aGL);
		return aRow;
	}

	Label label(Composite aParent,String aKey) {
		Label aL = new Label(aParent, SWT.NONE);
		tde.gui.applyColorFont(aL);
		aL.setText(tde.gui.lngs.get(aKey));
		return aL;
	}

	int pickColor(int aARGB) {
		ColorDialog aCD = new ColorDialog(container.getShell());
		aCD.setRGB(new RGB((aARGB>>16)&0xFF, (aARGB>>8)&0xFF, aARGB&0xFF));
		RGB aRGB = aCD.open();
		if(aRGB == null) {
			return -1;
		}
		return 0xFF000000 | (aRGB.red << 16) | (aRGB.green << 8) | aRGB.blue;
	}

	void createContents(Composite aParent) {
		container = new Composite(aParent, SWT.NONE);
		container.setLayoutData(new GridData(GridData.FILL_HORIZONTAL));
		tde.gui.applyColorFont(container);
		GridLayout aGL = new GridLayout(1, false);
		tde.gui.applyNoMargin(aGL);
		container.setLayout(aGL);

		//Tools
		ToolBar aTools = new ToolBar(container, SWT.FLAT | SWT.WRAP);
		aTools.setLayoutData(new GridData(GridData.FILL_HORIZONTAL));
		tde.gui.applyColorFont(aTools);
		toolItems = new ToolItem[Shape.TYPE_NAMES.length+1];
		for(int t = -1;t < Shape.TYPE_NAMES.length;t++) {
			final int aType = t;
			ToolItem aTI = new ToolItem(aTools, SWT.RADIO);
			aTI.setImage(createIcon(aType));
			aTI.setToolTipText(tde.gui.lngs.get(aType < 0 ? "Shapes.Select" : "Shapes."+Shape.TYPE_NAMES[aType]));
			aTI.addListener(SWT.Selection, e -> {
				if(updating || !aTI.getSelection()) {
					return;
				}
				editor.setTool(aType);
			});
			toolItems[t+1] = aTI;
		}

		//Actions on the selected shape
		Composite aActions = row(container, 2);
		ToolBar aActTB = new ToolBar(aActions, SWT.FLAT);
		tde.gui.applyColorFont(aActTB);
		delete = new ToolItem(aActTB, SWT.PUSH);
		delete.setImage(tde.gui.imgsSwt.getIcon("trash.png"));
		delete.setToolTipText(tde.gui.lngs.get(isClip ? "Tip.ShapesHide" : "Tip.ShapesDelete"));
		delete.addListener(SWT.Selection, e -> editor.deleteSelected());
		duplicate = new ToolItem(aActTB, SWT.PUSH);
		duplicate.setImage(tde.gui.imgsSwt.getIcon("clone.png"));
		duplicate.setToolTipText(tde.gui.lngs.get("Tip.ShapesDuplicate"));
		duplicate.addListener(SWT.Selection, e -> editor.duplicateSelected());
		front = new ToolItem(aActTB, SWT.PUSH);
		front.setImage(tde.gui.imgsSwt.getIcon("arrowUp.png"));
		front.setToolTipText(tde.gui.lngs.get("Tip.ShapesFront"));
		front.addListener(SWT.Selection, e -> editor.reorderSelected(true));
		back = new ToolItem(aActTB, SWT.PUSH);
		back.setImage(tde.gui.imgsSwt.getIcon("arrowDown.png"));
		back.setToolTipText(tde.gui.lngs.get("Tip.ShapesBack"));
		back.addListener(SWT.Selection, e -> editor.reorderSelected(false));
		if(isClip) {
			visible = new Button(aActions, SWT.CHECK);
			tde.gui.applyColorFont(visible);
			visible.setText(tde.gui.lngs.get("Shapes.Visible"));
			tde.gui.tip(visible, "ShapesVisible");
			visible.addListener(SWT.Selection, e -> {
				if(!updating) {
					editor.setSelectedHidden(!visible.getSelection());
				}
			});
		}
		else {
			new Label(aActions, SWT.NONE);
		}

		selectionL = new Label(container, SWT.NONE);
		tde.gui.applyColorFont(selectionL);
		selectionL.setLayoutData(new GridData(GridData.FILL_HORIZONTAL));

		//Fill
		Composite aFillR = row(container, 2);
		fill = new Button(aFillR, SWT.CHECK);
		tde.gui.applyColorFont(fill);
		fill.setText(tde.gui.lngs.get("Shapes.Fill"));
		fill.addListener(SWT.Selection, e -> {
			if(!updating) {
				boolean aOn = fill.getSelection();
				editor.editSelected(s -> s.filled = aOn);
			}
		});
		fillColor = new Button(aFillR, SWT.PUSH);
		tde.gui.tip(fillColor, "ShapesColor");
		fillColor.addListener(SWT.Selection, e -> {
			int aC = pickColor(editor.style.fillColor);
			if(aC != -1) {
				editor.editSelected(s -> {s.fillColor = aC;s.filled = true;});
				update();
			}
		});

		//Border
		Composite aStrokeR = row(container, 4);
		stroke = new Button(aStrokeR, SWT.CHECK);
		tde.gui.applyColorFont(stroke);
		stroke.setText(tde.gui.lngs.get("Shapes.Border"));
		stroke.addListener(SWT.Selection, e -> {
			if(!updating) {
				boolean aOn = stroke.getSelection();
				editor.editSelected(s -> s.stroked = aOn);
			}
		});
		strokeColor = new Button(aStrokeR, SWT.PUSH);
		tde.gui.tip(strokeColor, "ShapesColor");
		strokeColor.addListener(SWT.Selection, e -> {
			int aC = pickColor(editor.style.strokeColor);
			if(aC != -1) {
				editor.editSelected(s -> {s.strokeColor = aC;s.stroked = true;});
				update();
			}
		});
		strokeWidth = new Spinner(aStrokeR, SWT.BORDER);
		tde.gui.applyColorFont(strokeWidth);
		strokeWidth.setValues(6, 1, 200, 0, 1, 10);
		tde.gui.tip(strokeWidth, "ShapesWidth");
		strokeWidth.addListener(SWT.Modify, e -> {
			if(!updating) {
				double aW = strokeWidth.getSelection()/1000.0;
				editor.editSelected(s -> s.strokeWidth = aW);
			}
		});
		strokeStyle = new Combo(aStrokeR, SWT.READ_ONLY);
		tde.gui.applyColorFont(strokeStyle);
		for(String aN : Shape.STYLE_NAMES) {
			strokeStyle.add(tde.gui.lngs.get("Shapes."+aN));
		}
		tde.gui.tip(strokeStyle, "ShapesStyle");
		strokeStyle.addListener(SWT.Selection, e -> {
			if(!updating) {
				int aSt = strokeStyle.getSelectionIndex();
				editor.editSelected(s -> s.strokeStyle = aSt);
			}
		});

		//Opacity and depth
		Label aOL = label(container, "Shapes.Opacity");
		aOL.setLayoutData(new GridData(GridData.FILL_HORIZONTAL));
		opacityLS = new LabelScale(tde,container,"O",0,100,100,100) {
			@Override
			public void change(int aValue) {
				if(!updating) {
					editor.editSelected(s -> s.opacity = aValue/100.0);
				}
			}
		}.tip("ShapesOpacity");
		Label aDL = label(container, "Shapes.Depth");
		aDL.setLayoutData(new GridData(GridData.FILL_HORIZONTAL));
		depthLS = new LabelScale(tde,container,tde.gui.lngs.get("Shapes.DepthLetter"),-1000,0,1000,0) {
			@Override
			public void change(int aValue) {
				if(!updating) {
					editor.editSelected(s -> s.depth = aValue/10000.0);
				}
			}
		}.tip("ShapesDepth");
	}

	/**
	 * Controls from the selected shape (or the style of the next ones)
	 */
	public void update() {
		if(container.isDisposed()) {
			return;
		}
		updating = true;
		try {
			Shape aSel = editor.getSelected();
			Shape aS = aSel != null ? aSel : editor.style;
			int aTool = editor.getTool();
			for(int t = 0;t < toolItems.length;t++) {
				toolItems[t].setSelection(t == aTool+1);
			}
			delete.setEnabled(aSel != null && !(isClip && aSel.hidden));
			duplicate.setEnabled(aSel != null);
			front.setEnabled(aSel != null);
			back.setEnabled(aSel != null);
			if(visible != null) {
				visible.setEnabled(aSel != null);
				visible.setSelection(aSel != null && !aSel.hidden);
			}
			String aText = aSel != null ? tde.gui.lngs.get("Shapes."+Shape.TYPE_NAMES[Math.max(0, Math.min(Shape.TYPE_NAMES.length-1, aSel.type))])
					+(aSel.hidden ? " ("+tde.gui.lngs.get("Shapes.Hidden")+")" : "")
					: tde.gui.lngs.get(aTool >= 0 ? "Shapes.HintCreate" : "Shapes.HintSelect");
			if(!aText.equals(selectionL.getText())) {
				selectionL.setText(aText);
			}
			fill.setSelection(aS.filled);
			fill.setEnabled(!aS.isLine());
			fillColor.setEnabled(!aS.isLine());
			setSwatch(fillColor, aS.fillColor);
			stroke.setSelection(aS.stroked);
			setSwatch(strokeColor, aS.strokeColor);
			int aW = (int)Math.round(aS.strokeWidth*1000);
			if(strokeWidth.getSelection() != aW) {
				strokeWidth.setSelection(aW);
			}
			strokeStyle.select(aS.strokeStyle);
			opacityLS.setSelection((int)Math.round(aS.opacity*100));
			depthLS.setSelection((int)Math.round(aS.depth*10000));
		}
		finally {
			updating = false;
		}
	}

	@Override
	public void shapesChanged() {
	}

	@Override
	public void selectionChanged() {
		update();
	}
}
