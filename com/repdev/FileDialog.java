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
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;

import org.eclipse.swt.SWT;
import org.eclipse.swt.events.KeyAdapter;
import org.eclipse.swt.events.KeyEvent;
import org.eclipse.swt.events.ModifyEvent;
import org.eclipse.swt.events.ModifyListener;
import org.eclipse.swt.events.SelectionAdapter;
import org.eclipse.swt.events.SelectionEvent;
import org.eclipse.swt.layout.FormAttachment;
import org.eclipse.swt.layout.FormData;
import org.eclipse.swt.layout.FormLayout;
import org.eclipse.swt.widgets.*;

/**
 * Basic dialog box for both opening and saving files from symitar
 * Watch out, as OPEN and SAVE modes are slightly different
 * 
 * TODO: Shows installed date, rename files, delete installed
 * 
 * @author Jake Poznanski
 *
 */
public class FileDialog {
	Shell shell, parent;
	Mode mode;
	int sym;
	ArrayList<SymitarFile> files = new ArrayList<>();
	Table table;
	Combo typeCombo;
	Text nameText;
	String dir;
	enum TABLE_COLUMN{NAME, SIZE, DATE};

	/** Full, unfiltered list of files for the current type — fetched once, then filtered client-side as the user types. */
	ArrayList<SymitarFile> fullFileList = new ArrayList<>();
	/** fullFileList's names, lower-cased once up front — so filtering on every keystroke isn't re-lowercasing the whole list each time. */
	ArrayList<String> fullFileNamesLower = new ArrayList<>();
	/** Per-type cache of the above two, so flipping the type combo back to one already fetched this session is instant instead of a fresh round-trip. */
	HashMap<FileType, ArrayList<SymitarFile>> listCache = new HashMap<>();
	HashMap<FileType, ArrayList<String>> lowerCache = new HashMap<>();

	public enum Mode {
		SAVE, OPEN,
	}

	public FileDialog(Shell parent, Mode mode, int sym) {
		this.parent = parent;
		this.mode = mode;
		this.sym = sym;
	}

	public FileDialog(Shell parent, Mode mode, String dir) {
		this.parent = parent;
		this.mode = mode;
		this.dir = dir;
	}

	private void create() {
		FormLayout layout = new FormLayout();
		layout.marginTop = 5;
		layout.marginBottom = 5;
		layout.marginLeft = 5;
		layout.marginRight = 5;
		layout.spacing = 5;

		FormData data;

		shell = new Shell(parent, SWT.APPLICATION_MODAL | SWT.DIALOG_TRIM | SWT.RESIZE);
		shell.setText((mode == Mode.OPEN ? "Open" : "Save") + " Symitar File" + (mode == Mode.OPEN ? "(s)" : ""));
		shell.setLayout(layout);
		shell.setMinimumSize(600, 350);
		
		if( mode == Mode.SAVE )
			shell.setImage(RepDevMain.smallActionSaveImage);
		else
			shell.setImage(RepDevMain.smallFileOpenImage);

		Label nameLabel = new Label(shell, SWT.NONE);
		nameLabel.setText("Filename:");

		Label typeLabel = new Label(shell, SWT.NONE);
		typeLabel.setText("Type:");

		typeCombo = new Combo(shell, SWT.DROP_DOWN | SWT.READ_ONLY);

		typeCombo.add("REPGEN");
		
		//if( dir == null){
			typeCombo.add("LETTER");
			typeCombo.add("HELP");
			typeCombo.add("DATA");
		//}
		
		typeCombo.select(0);

		typeCombo.addSelectionListener(new SelectionAdapter() {
			public void widgetSelected(SelectionEvent e) {
				loadFullList();
				filterAndDisplay();
			}
		});

		//TODO: Sortable columns
		table = new Table(shell, (mode == Mode.OPEN ? SWT.MULTI : SWT.SINGLE) | SWT.BORDER | SWT.V_SCROLL | SWT.FULL_SELECTION);
		table.setLinesVisible(false);
		table.setHeaderVisible(true);

		table.addSelectionListener(new SelectionAdapter() {
			public void widgetDefaultSelected(SelectionEvent e) {
				if (table.getSelectionIndex() != -1) {
					if (mode == Mode.SAVE) {
						MessageBox dialog = new MessageBox(shell, SWT.ICON_QUESTION | SWT.OK | SWT.CANCEL);
						dialog.setText("Confirm Overwrite");
						dialog.setMessage("This file already exists, are you sure you want to overwrite it?");

						if (dialog.open() == SWT.CANCEL)
							return;
					}

					files.add((SymitarFile) (table.getSelection())[0].getData());
					shell.close();
				} else if (mode == Mode.SAVE) {
					createFile();
				}
			}
		});

		TableColumn nameCol = new TableColumn(table, SWT.NONE);
		nameCol.setText("Name");
		nameCol.setWidth(300);

		TableColumn sizeCol = new TableColumn(table, SWT.NONE);
		sizeCol.setText("Size");
		sizeCol.setWidth(120);

		TableColumn dateCol = new TableColumn(table, SWT.NONE);
		dateCol.setText("Date");
		dateCol.setWidth(150);
		
		// Add sort indicator and sort data when column selected
		Listener sortListener = new Listener() {
			public void handleEvent(Event e) {
				TableColumn column = (TableColumn)e.widget;
				if(column != table.getSortColumn()){
					table.setSortDirection(SWT.UP);
					table.setSortColumn(column);
				}
				else{
					table.setSortDirection(table.getSortDirection() == SWT.UP ? SWT.DOWN : SWT.UP);
				}
				filterAndDisplay();
			}
		};
		nameCol.addListener(SWT.Selection, sortListener);
		sizeCol.addListener(SWT.Selection, sortListener);
		dateCol.addListener(SWT.Selection, sortListener);

		nameText = new Text(shell, SWT.SINGLE | SWT.BORDER);

		// Arrow keys browse the (already-filtered, already-selected) results without
		// leaving the search box or clicking a row first.
		nameText.addKeyListener(new KeyAdapter() {
			public void keyPressed(KeyEvent e) {
				if (e.keyCode == SWT.ARROW_DOWN) {
					moveSelection(1);
					e.doit = false;
				} else if (e.keyCode == SWT.ARROW_UP) {
					moveSelection(-1);
					e.doit = false;
				}
			}
		});

		nameText.addSelectionListener(new SelectionAdapter() {
			public void widgetDefaultSelected(SelectionEvent e) {
				if (mode == Mode.OPEN) {
					openHighlighted();
					return;
				}

				if (mode == Mode.SAVE && !isTemplate()) {
					SymitarFile exact = findExactMatch(nameText.getText().trim());

					if (exact != null) {
						MessageBox dialog = new MessageBox(shell, SWT.ICON_QUESTION | SWT.OK | SWT.CANCEL);
						dialog.setText("Confirm Overwrite");
						dialog.setMessage("This file already exists, are you sure you want to overwrite it?");

						if (dialog.open() == SWT.CANCEL)
							return;

						files.add(exact);
						shell.close();
					} else if (nameText.getText().trim().length() > 0) {
						createFile();
					}
				}
			}
		});

		nameText.addModifyListener(new ModifyListener() {

			public void modifyText(ModifyEvent e) {
				filterAndDisplay();
			}

		});

		final Button ok = new Button(shell, SWT.PUSH);

		if (mode == Mode.OPEN)
			ok.setText("Open File(s)");
		else
			ok.setText("Save File");

		ok.addSelectionListener(new SelectionAdapter() {
			public void widgetSelected(SelectionEvent e) {
				if (mode == Mode.OPEN) {
					openHighlighted();
				}

				if( mode == Mode.SAVE){
					SymitarFile exact = findExactMatch(nameText.getText().trim());

					if( exact != null ){
						MessageBox dialog = new MessageBox(shell, SWT.ICON_QUESTION | SWT.OK | SWT.CANCEL);
						dialog.setText("Confirm Overwrite");
						dialog.setMessage("This file already exists, are you sure you want to overwrite it?");

						if (dialog.open() == SWT.CANCEL)
							return;

						files.add(exact);
						shell.close();
					}
					else
					{
						createFile();
					}
				}
			}
		});

		Button cancel = new Button(shell, SWT.PUSH);
		cancel.setText("Cancel");
		cancel.addSelectionListener(new SelectionAdapter() {
			public void widgetSelected(SelectionEvent e) {
				shell.close();
			}
		});

		data = new FormData();
		data.left = new FormAttachment(0);
		data.top = new FormAttachment(0);
		nameLabel.setLayoutData(data);

		data = new FormData();
		data.left = new FormAttachment(nameLabel);
		data.right = new FormAttachment(typeLabel);
		data.top = new FormAttachment(0);
		nameText.setLayoutData(data);

		data = new FormData();
		data.right = new FormAttachment(typeCombo);
		data.top = new FormAttachment(0);
		typeLabel.setLayoutData(data);

		data = new FormData();
		// data.left = new FormAttachment(typeLabel);
		data.top = new FormAttachment(0);
		data.right = new FormAttachment(100);
		typeCombo.setLayoutData(data);

		data = new FormData();
		data.left = new FormAttachment(0);
		data.top = new FormAttachment(typeCombo);
		data.bottom = new FormAttachment(cancel);
		data.right = new FormAttachment(100);
		table.setLayoutData(data);

		data = new FormData();
		data.right = new FormAttachment(100);
		data.bottom = new FormAttachment(100);
		cancel.setLayoutData(data);

		data = new FormData();
		data.right = new FormAttachment(cancel);
		data.bottom = new FormAttachment(100);
		ok.setLayoutData(data);

		nameText.setFocus();

		// Fixed, reasonable default — independent of result count, which pack() is not:
		// pack() sizes from the table's *populated* preferred height, so loading the full
		// list before open (below) would otherwise stretch the dialog to fit every row.
		// Long lists scroll instead (table already has SWT.V_SCROLL).
		shell.setSize(700, 480);
		shell.layout(true, true);

		// Show the (empty, wait-cursor) shell FIRST, then fetch. The fetch is a real
		// network round-trip to Symitar — doing it before open() meant the whole
		// window stayed invisible for that entire wait, which read as a sluggish
		// double-click. Pumping the queue once forces the just-opened shell to
		// actually paint before the blocking call below runs on this same thread.
		shell.open();
		while (shell.getDisplay().readAndDispatch()) {}

		loadFullList();
		filterAndDisplay();
	}

	// TODO: Finish up with other template forms
	private boolean isTemplate() {
		return nameText.getText().contains("+");
	}

	/** Opens whatever's currently highlighted in the results table — shared by Enter-in-search-box and the Open button. */
	private void openHighlighted() {
		if (table.getSelectionIndex() == -1)
			return;

		for (TableItem cur : table.getSelection())
			files.add((SymitarFile) cur.getData());

		shell.close();
	}

	/** Moves the table's highlighted row up/down by delta, clamped to the list bounds. */
	private void moveSelection(int delta) {
		int count = table.getItemCount();
		if (count == 0)
			return;

		int idx = table.getSelectionIndex();
		idx = Math.max(0, Math.min(count - 1, (idx < 0 ? 0 : idx) + delta));
		table.setSelection(idx);
		table.showSelection();
	}
	
	private void createFile(){
		if (nameText.getText().trim().length() <= 31){
			if( dir == null)
				files.add(new SymitarFile(sym,nameText.getText().trim(), FileType.valueOf(typeCombo.getText())));
			else
				files.add(new SymitarFile(dir,nameText.getText().trim(), FileType.valueOf(typeCombo.getText())));
			
			shell.close();
		} else {
			DialogUtil.error(shell, "File Save", "File name too long.  It must be 31 characters or less");
		}
	}

	/**
	 * Fetches the complete, unfiltered file list for the current type (search "+" = match all).
	 * Cached per type — switching the type combo back to one already fetched this dialog
	 * session is instant instead of repeating the (slow, remote) round-trip every time.
	 */
	private void loadFullList() {
		FileType type = FileType.valueOf(typeCombo.getText());

		if (listCache.containsKey(type)) {
			fullFileList = listCache.get(type);
			fullFileNamesLower = lowerCache.get(type);
			return;
		}

		shell.setCursor(shell.getDisplay().getSystemCursor(SWT.CURSOR_WAIT));

		try {
			if (dir == null) {
				SymitarSession session = RepDevMain.SYMITAR_SESSIONS.get(sym);
				fullFileList = session.getFileList(type, "+");
			} else {
				fullFileList = Util.getFileList(dir, "+");
			}
		}
		catch (Exception e) {
			e.printStackTrace();
		}
		finally {
			shell.setCursor(shell.getDisplay().getSystemCursor(SWT.CURSOR_ARROW));
		}

		fullFileNamesLower = new ArrayList<>(fullFileList.size());
		for (SymitarFile cur : fullFileList)
			fullFileNamesLower.add(cur.getName().toLowerCase());

		listCache.put(type, fullFileList);
		lowerCache.put(type, fullFileNamesLower);
	}

	/**
	 * Filters the cached full list against the search box — sorts, and repopulates the table.
	 * Runs live on every keystroke since it's just filtering an in-memory list.
	 *
	 * Matching splits the query into whitespace-separated tokens and requires each one to
	 * appear somewhere in the name (in any order) — so "estatement cleanup" finds
	 * "MSVCS.ESTATEMENT.CLEANUP" without needing the exact dotted name or word order.
	 */
	private void filterAndDisplay() {
		String[] tokens = nameText.getText().trim().toLowerCase().split("\\s+");

		ArrayList<SymitarFile> fileList = new ArrayList<>();
		for (int i = 0; i < fullFileList.size(); i++)
			if (matchesAllTokens(fullFileNamesLower.get(i), tokens))
				fileList.add(fullFileList.get(i));

		table.setRedraw(false);
		table.removeAll();

		// If the table sort column and direction has not been set, set them to default.
		if(table.getSortColumn() == null){
			table.setSortColumn(table.getColumn(0));
			table.setSortDirection(SWT.UP);
		}

		// Get the current sort column to pass into the sortFileList method.
		TABLE_COLUMN col;
		if(table.getSortColumn().getText().equalsIgnoreCase("size")){
			col = TABLE_COLUMN.SIZE;
		}
		else if(table.getSortColumn().getText().equalsIgnoreCase("date")){
			col = TABLE_COLUMN.DATE;
		}
		else{
			col = TABLE_COLUMN.NAME;
		}

		// Sort the list prior to populating the table.
		fileList = sortFileList(fileList, col, table.getSortDirection());
		// Populate the table.
		DateFormat dateFormat = DateFormat.getDateTimeInstance(); // hoisted out of the loop below — building one per row per keystroke was pure waste
		for (SymitarFile cur : fileList) {
			TableItem item = new TableItem(table, SWT.NONE);
			item.setText(0, cur.getName());

			if( cur.getType() == FileType.REPGEN )
				if( cur.getOnDemand() )
					item.setImage(0, RepDevMain.smallRepGenDemandImage);
				else
					item.setImage(0, RepDevMain.smallRepGenImage);

			else if( cur.getType() == FileType.DATA )
				item.setImage(0, RepDevMain.smallDataImage);
			else
				item.setImage(0, RepDevMain.smallFileImage);

			item.setText(1, Util.getByteStr(cur.getSize()));
			item.setText(2, dateFormat.format(cur.getModified()));
			item.setData(cur);
		}

		table.setRedraw(true);

		if (table.getItemCount() > 0)
			table.select(0);
	}

	/** True if every non-empty whitespace-separated search token appears somewhere in nameLower, in any order. */
	private boolean matchesAllTokens(String nameLower, String[] tokens) {
		for (String token : tokens)
			if (!token.isEmpty() && !nameLower.contains(token))
				return false;
		return true;
	}

	/** Exact (case-insensitive) filename match against the full list — used to tell whether Save would overwrite an existing file. */
	private SymitarFile findExactMatch(String name) {
		for (SymitarFile cur : fullFileList)
			if (cur.getName().equalsIgnoreCase(name))
				return cur;
		return null;
	}
	
	/**
	 * Sorts an ArrayList of SymitarFile by the given column/direction.
	 * If the sort column is not size or date, it sorts by name.
	 * @param sortColumn column to sort by
	 * @param sortDirection sort ascending or descending.
	 */
	public ArrayList<SymitarFile> sortFileList(ArrayList<SymitarFile> fileList, TABLE_COLUMN sortColumn, int sortDirection){
		Comparator<SymitarFile> cmp;
		if (sortColumn == TABLE_COLUMN.SIZE)
			cmp = Comparator.comparingLong(SymitarFile::getSize);
		else if (sortColumn == TABLE_COLUMN.DATE)
			cmp = Comparator.comparing(SymitarFile::getModified);
		else
			cmp = Comparator.comparing(SymitarFile::getName);

		fileList.sort(sortDirection == SWT.DOWN ? cmp.reversed() : cmp);
		return fileList;
	}

	public ArrayList<SymitarFile> open() {
		create();

		DialogUtil.pumpUntilClosed(shell);

		return files;
	}
}
