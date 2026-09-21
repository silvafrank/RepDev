/**
 *  RepDev - RepGen IDE for Symitar
 *  Copyright (C) 2007  Jake Poznanski, Ryan Schultz, Sean Delaney
 *  http://repdev.org/ <support@repdev.org>
 *
 *  This program is free software: you can redistribute it and/or modify
 *   it under the terms of the GNU General Public License as published by
 *   the Free Software Foundation, either version 3 of the License, or
 *   (at your option) any later version.
 *
 *   This program is distributed in the hope that it will be useful,
 *   but WITHOUT ANY WARRANTY; without even the implied warranty of
 *   MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *   GNU General Public License for more details.
 *
 *   You should have received a copy of the GNU General Public License
 *   along with this program.  If not, see <http://www.gnu.org/licenses/>.
*/

package com.repdev;

import java.text.DateFormat;

import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.CTabItem;
import org.eclipse.swt.custom.StyledText;
import org.eclipse.swt.custom.TableEditor;
import org.eclipse.swt.events.KeyAdapter;
import org.eclipse.swt.events.KeyEvent;
import org.eclipse.swt.events.SelectionAdapter;
import org.eclipse.swt.events.SelectionEvent;
import org.eclipse.swt.graphics.Color;
import org.eclipse.swt.graphics.Font;
import org.eclipse.swt.graphics.RGB;
import org.eclipse.swt.layout.FillLayout;
import org.eclipse.swt.layout.FormAttachment;
import org.eclipse.swt.layout.FormData;
import org.eclipse.swt.layout.FormLayout;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Event;
import org.eclipse.swt.widgets.Link;
import org.eclipse.swt.widgets.Listener;
import org.eclipse.swt.widgets.Sash;
import org.eclipse.swt.widgets.Table;
import org.eclipse.swt.widgets.TableColumn;
import org.eclipse.swt.widgets.TableItem;


public class ReportComposite extends Composite implements TabTextView{
	private StyledText txt;
	private Table table;
	private SymitarFile file = null;
	private Sequence seq;
	private CTabItem tabItem;
	private int sym;

	/**
	 * Either send a single report to view as a SymitarFile, or a batch seq to view a bnch from the same run
	 * @param parent
	 * @param file
	 * @param sym
	 * @param seq
	 */
	public ReportComposite(Composite parent, CTabItem item, SymitarFile file) {
		super(parent, SWT.NONE);
		this.file = file;
		this.sym = file.getSym();
		this.tabItem = item;
		
		buildGUI();
	}
	
	public ReportComposite(Composite parent, CTabItem item, Sequence seq) {
		super(parent, SWT.NONE);
		this.seq = seq;
		this.sym = seq.getSym();
		this.tabItem = item;
		
		buildGUI();
	}
	
	public StyledText getStyledText(){
		return txt;
	}

	private void buildGUI() {
		setLayout(new FormLayout());
		
		txt = new StyledText(this, SWT.H_SCROLL | SWT.V_SCROLL | SWT.READ_ONLY);
		txt.setFont(new Font(Display.getCurrent(), "Courier New", 9, SWT.NORMAL));
		txt.setBackground(new Color(Display.getCurrent(),new RGB(255,255,225)));
		
		txt.addKeyListener(new KeyAdapter() {
			public void keyPressed(KeyEvent e) {				
				if (e.stateMask == SWT.CTRL) {
					switch (e.keyCode) {
					case 'a':
					case 'A':
						txt.selectAll();
						break;
					case 'f':
					case 'F':
						RepDevMain.mainShell.showFindWindow();
						break;
					case 'p':
					case 'P':
						RepDevMain.mainShell.print();
						break;
					}
				}
				else{
					if( e.keyCode == SWT.F3 )
						RepDevMain.mainShell.findNext();
				}


			}

			public void keyReleased(KeyEvent e) {

			}
		});
		
		table = new Table(this, SWT.V_SCROLL | SWT.BORDER | SWT.SINGLE | SWT.FULL_SELECTION);
		table.setLinesVisible(true);
		table.setHeaderVisible(true);
		table.addSelectionListener(new SelectionAdapter(){
			@Override
			public void widgetSelected(SelectionEvent e) {
				openTableItem();
			}			
		});
		
		final TableColumn titleCol = new TableColumn(table,SWT.NONE);
		titleCol.setText("Title");
		titleCol.setWidth(230);

		final TableColumn seqCol = new TableColumn(table,SWT.NONE);
		seqCol.setText("Sequence");
		seqCol.setWidth(70);

		final TableColumn pagesCol = new TableColumn(table,SWT.NONE);
		pagesCol.setText("Pages");
		pagesCol.setWidth(50);

		final TableColumn sizeCol = new TableColumn(table,SWT.NONE);
		sizeCol.setText("Size");
		sizeCol.setWidth(70);

		final TableColumn dateCol = new TableColumn(table,SWT.NONE);
		dateCol.setText("Date");
		dateCol.setWidth(150);

		TableColumn col = new TableColumn(table,SWT.NONE);
		col.setText("Options");
		// 200px was too narrow for "Print: Local Host LPT  Run as FM" at normal
		// font sizes - the Link wraps, and since the row height is never set to
		// fit two lines, the wrapped "Run as FM" anchor gets clipped off and is
		// invisible even though it's still there and clickable if you resize the column.
		col.setWidth(300);
		
		// Title (column 0) fills whatever's left of the table's actual width
		// instead of a fixed 230px, so long report names stop getting clipped.
		final int MIN_TITLE_WIDTH = 100;
		table.addControlListener(new org.eclipse.swt.events.ControlAdapter(){
			public void controlResized(org.eclipse.swt.events.ControlEvent e) {
				int fixed = 0;
				for (TableColumn c : table.getColumns())
					if (c != titleCol) fixed += c.getWidth();
				int titleWidth = table.getClientArea().width - fixed;
				if (titleWidth < MIN_TITLE_WIDTH) titleWidth = MIN_TITLE_WIDTH;
				if (titleCol.getWidth() != titleWidth) titleCol.setWidth(titleWidth);
			}
		});

		FormData data = new FormData();
		data.left = new FormAttachment(0);
		data.right = new FormAttachment(100);
		data.top = new FormAttachment(0);
		// Real height (fit to content, up to a cap) set below once item count is known.
		table.setLayoutData(data);

		// Draggable divider so the results list can be resized instead of being
		// stuck scrolling one row at a time with the arrow keys.
		Sash tableSash = new Sash(this, SWT.HORIZONTAL | SWT.SMOOTH);
		FormData frmSash = new FormData();
		frmSash.left = new FormAttachment(0);
		frmSash.right = new FormAttachment(100);
		frmSash.top = new FormAttachment(table);
		tableSash.setLayoutData(frmSash);
		tableSash.addListener(SWT.Selection, new Listener(){
			public void handleEvent(Event e) {
				int min = table.getHeaderHeight() + table.getItemHeight();
				int max = getSize().y - min;
				int h = Math.max(min, Math.min(e.y, max));
				data.height = h;
				layout(true, true);
			}
		});

		FormData frmTxt = new FormData();
		frmTxt.top = new FormAttachment(tableSash);
		frmTxt.left = new FormAttachment(0);
		frmTxt.right = new FormAttachment(100);
		frmTxt.bottom = new FormAttachment(100);
		txt.setLayoutData(frmTxt);


		if( file != null){
			txt.setText(file.getData());
			
			TableItem row = new TableItem(table,SWT.NONE);
			row.setText(0, "");
			row.setText(1, file.getName());
		}
		else
		{
			for( final PrintItem item : RepDevMain.SYMITAR_SESSIONS.get(sym).getPrintItems(seq)){
				TableItem row = new TableItem(table,SWT.NONE);
				row.setText(0, item.getTitle());
				row.setText(1, String.valueOf(item.getSeq()));
				row.setText(2, String.valueOf(item.getPages()));
				row.setText(3, Util.getByteStr(item.getSize()));
				row.setText(4, DateFormat.getDateTimeInstance().format(item.getDate()));
				
				TableEditor editor = new TableEditor(table);
				editor.grabHorizontal=true;
				editor.grabVertical=true;
				
				Composite labelComposite = new Composite(table,SWT.NONE);
				FillLayout layout = new FillLayout();
				labelComposite.setLayout(layout);
				
				Link printLocal = new Link(labelComposite,SWT.NONE);
				printLocal.setText("Print: <a href=\"local\">Local</a> <a href=\"lpt\">Host LPT</a>  <a href=\"fm\">Run as FM</a>");
				printLocal.setBackground(table.getDisplay().getSystemColor(SWT.COLOR_LIST_BACKGROUND));
				printLocal.addSelectionListener(new SelectionAdapter(){

					@Override
					public void widgetSelected(SelectionEvent e) {
						if( e.text.equals("local")){
							openTableItem(item);
							RepDevMain.mainShell.print();
						}
						else if(e.text.equals("lpt"))
							LPTPrintShell.print(getDisplay(), getShell(), new SymitarFile(sym,String.valueOf(item.getSeq()),FileType.REPORT));
						else if( e.text.equals("fm"))
							runFM(item);
					}
					
				});
				
				editor.setEditor(labelComposite, row, 5);
				
				row.setData(item);
			}
		}
		
		// Fixed guesses (70/50/70/150) clipped whenever real data ran longer -
		// pack() sizes each to its actual content (or header, whichever is wider).
		seqCol.pack();
		pagesCol.pack();
		sizeCol.pack();
		dateCol.pack();

		if( table.getItemCount() > 0 ){
			table.setSelection(0);
			openTableItem();
		}
		else
			txt.setText("Error loading file");

		// Default height fits however many rows are actually there (up to 8,
		// so a huge batch run doesn't crowd out the preview below) instead of
		// the old fixed 48px sliver that only ever showed ~2 rows regardless
		// of how many report files came back. Still just a starting point —
		// the sash above lets it be dragged taller or shorter from here.
		int rowsToShow = Math.max(1, Math.min(table.getItemCount(), 8));
		data.height = table.getHeaderHeight() + table.getItemHeight() * rowsToShow + 4;
	}
	
	protected void runFM(PrintItem item) {
		//RunFMResult result = RepDevMain.SYMITAR_SESSIONS.get(sym).runBatchFM(item.getTitle(), SymitarSession.FMFile.ACCOUNT, -1);
		
		//System.out.println("FM Name: " + result.getResultTitle());
		//System.out.println("Queue Seq: " + result.getSeq());
		
		FMShell.runFM(getDisplay(), getShell(), sym, item.getTitle());
	}

	protected void openTableItem(PrintItem item) {
		String data = new SymitarFile(sym,String.valueOf(item.getSeq()),FileType.REPORT).getData();
	
		if( data != null)
			txt.setText( data);
	}

	private void openTableItem(){
		PrintItem item = null;
		
		if( table.getSelection()[0].getData() == null )
			return;
		else
			item = (PrintItem)table.getSelection()[0].getData();
		
		openTableItem(item);
	}

	public SymitarFile getFile() {
		return file;
	}
}
