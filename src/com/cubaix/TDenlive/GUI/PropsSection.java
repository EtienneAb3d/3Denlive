package com.cubaix.TDenlive.GUI;

import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Table;
import org.eclipse.swt.widgets.TableColumn;
import org.eclipse.swt.widgets.TableItem;

import com.cubaix.TDenlive.TDenlive;

/**
 * Section of the panel: a title, a table whose rows are clickable (one action per row data), and a text when
 * it is empty
 */
public class PropsSection {
TDenlive tde;
	Composite box;
	Table table;
	Label none;

	public PropsSection(TDenlive aTDe, Composite aParent, String aTitleKey, String aNoneKey, String aTipKey, String[] aColKeys, java.util.function.Consumer<Object> aOnClick) {
		tde = aTDe;
		box = new Composite(aParent, SWT.NONE);
		box.setLayoutData(new GridData(GridData.FILL_HORIZONTAL));
		GridLayout aGL = new GridLayout(1, false);
		aGL.marginWidth = 3;
		aGL.marginHeight = 2;
		aGL.verticalSpacing = 2;
		box.setLayout(aGL);
		box.setBackground(tde.gui.colorsSwt.WHITE);
		Label aTitle = new Label(box, SWT.NONE);
		aTitle.setBackground(tde.gui.colorsSwt.WHITE);
		aTitle.setForeground(tde.gui.colorsSwt.CUBAIX_BLUE);
		aTitle.setFont(tde.gui.fontsSwt.robotoBold);
		aTitle.setText(tde.gui.lngs.get(aTitleKey));
		none = new Label(box, SWT.NONE);
		none.setBackground(tde.gui.colorsSwt.WHITE);
		none.setForeground(tde.gui.colorsSwt.GRAY);
		none.setFont(tde.gui.fontsSwt.roboto);
		none.setText(tde.gui.lngs.get(aNoneKey));
		none.setLayoutData(new GridData(GridData.FILL_HORIZONTAL));
		table = new Table(box, SWT.SINGLE | SWT.FULL_SELECTION | SWT.V_SCROLL | SWT.BORDER);
		table.setLayoutData(new GridData(GridData.FILL_HORIZONTAL));
		table.setHeaderVisible(true);
		table.setLinesVisible(true);
		table.setFont(tde.gui.fontsSwt.roboto);
		table.setForeground(tde.gui.colorsSwt.CUBAIX_BLUE);
		tde.gui.tip(table, aTipKey);
		for(String aK : aColKeys) {
			TableColumn aCol = new TableColumn(table, SWT.LEFT);
			aCol.setText(tde.gui.lngs.get(aK));
		}
		//Each click, also on the row already selected
		table.addListener(SWT.MouseUp, e -> {
			TableItem aI = table.getItem(new Point(e.x, e.y));
			if(aI != null && aI.getData() != null) {
				aOnClick.accept(aI.getData());
			}
		});
		table.addListener(SWT.DefaultSelection, e -> {
			if(e.item != null && e.item.getData() != null) {
				aOnClick.accept(e.item.getData());
			}
		});
	}

	/**
	 * The rows (texts of the columns) and the data given to the click of each
	 */
	public void fill(java.util.List<String[]> aRows, java.util.List<Object> aDatas) {
		if(table.isDisposed()) {
			return;
		}
		table.removeAll();
		for(int r = 0;r < aRows.size();r++) {
			TableItem aI = new TableItem(table, SWT.NONE);
			aI.setText(aRows.get(r));
			aI.setData(aDatas.get(r));
		}
		for(TableColumn aCol : table.getColumns()) {
			aCol.pack();
		}
		boolean aAny = !aRows.isEmpty();
		((GridData)table.getLayoutData()).exclude = !aAny;
		table.setVisible(aAny);
		((GridData)none.getLayoutData()).exclude = aAny;
		none.setVisible(!aAny);
		if(aAny) {
			//At most 6 rows shown, scrolled beyond
			int aNb = Math.min(6, aRows.size());
			((GridData)table.getLayoutData()).heightHint = table.getHeaderHeight() + aNb*table.getItemHeight() + 2*table.getBorderWidth();
		}
	}
}
