package com.cubaix.TDenlive.GUI;

import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.ScrolledComposite;
import org.eclipse.swt.events.SelectionEvent;
import org.eclipse.swt.events.SelectionListener;
import org.eclipse.swt.events.TreeEvent;
import org.eclipse.swt.events.TreeListener;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.graphics.Rectangle;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Tree;
import org.eclipse.swt.widgets.TreeItem;

import com.cubaix.TDenlive.TDenlive;

public abstract class TreeView extends CompositePanel {
	ScrolledComposite scroller = null;
	Tree tree = null;
	
	public TreeView(TDenlive aTDe,Composite aParent) {
		super(aTDe, aParent);
		createContents();
	}
	
	public void clean() {
		if(tree.isDisposed()) {
			return;
		}
		for(TreeItem aI : tree.getItems()) {
			aI.dispose();
		}
		scroller.setOrigin(0,0);
	}

	void createContents() {
		scroller = new ScrolledComposite (container, SWT.VERTICAL|SWT.FILL);
		scroller.setLayoutData(new GridData(GridData.FILL_BOTH));
		scroller.setBackground(tde.gui.colorsSwt.WHITE);
		
		tree = new Tree(scroller, SWT.NONE);
		scroller.setContent(tree);

		/*
		 * The following listener ensures that the Tree is always large
		 * enough to not need to show its own vertical scrollbar.
		 */
		tree.addTreeListener (new TreeListener () {
			@Override
			public void treeExpanded (TreeEvent e) {
				adaptSize();
			}
			@Override
			public void treeCollapsed (TreeEvent e) {
				adaptSize();
			}
		});
		/*
		 * The following listener ensures that a newly-selected item
		 * in the Tree is always visible.
		 */
		tree.addSelectionListener(new SelectionListener(){
			@Override
			public void widgetDefaultSelected(SelectionEvent arg0) {
			}
			@Override
			public void widgetSelected(SelectionEvent arg0) {
				TreeItem [] selectedItems = tree.getSelection();
				if (selectedItems.length > 0) {
					Rectangle itemRect = selectedItems[0].getBounds();
					Rectangle area = scroller.getClientArea();
					Point origin = scroller.getOrigin();
					if (itemRect.x < origin.x || itemRect.y < origin.y
							|| itemRect.x + itemRect.width > origin.x + area.width
							|| itemRect.y + itemRect.height > origin.y + area.height) {
						scroller.setOrigin(itemRect.x, itemRect.y);
					}
				}
			}
		});
		/*
		 * The following listener scrolls the Tree one item at a time
		 * in response to MouseWheel events.
		 */
		tree.addListener(SWT.MouseWheel, event -> {
			Point origin = scroller.getOrigin();
			if (event.count < 0) {
				origin.y = Math.min(origin.y + tree.getItemHeight(), tree.getSize().y);
			} else {
				origin.y = Math.max(origin.y - tree.getItemHeight(), 0);
			}
			scroller.setOrigin(origin);
		});
		
		tree.addListener(SWT.MouseDoubleClick, event -> {
			TreeItem [] selectedItems = tree.getSelection();
			if (selectedItems.length > 0) {
				if(selectedItems[0].getItems().length > 0) {
					selectedItems[0].setExpanded(!selectedItems[0].getExpanded());
				}
				else {
				}
			}
		});
		
		scroller.addListener(SWT.Resize, event -> {
			adaptSize();
		});

		adaptSize();
	}
	
	void adaptSize() {
		if(tree == null) {
			return;
		}
		tree.computeSize(SWT.DEFAULT, SWT.DEFAULT);
		Point aTreeSize = tree.getSize();
		Rectangle aScrollSize = scroller.getClientArea();
		int aMaxHeight = Math.max(aScrollSize.height, aTreeSize.y);
		if(aTreeSize.x != aScrollSize.width || aTreeSize.y != aMaxHeight) {
			tree.setSize (aScrollSize.width,aMaxHeight);
			scroller.layout();
			adaptSize();
		}
	}

	abstract void populateTree() ;
}
