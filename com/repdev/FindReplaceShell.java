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

import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.StyledText;
import org.eclipse.swt.events.SelectionAdapter;
import org.eclipse.swt.events.SelectionEvent;
import org.eclipse.swt.events.ShellAdapter;
import org.eclipse.swt.events.ShellEvent;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.graphics.Rectangle;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Event;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Listener;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Text;

import com.repdev.parser.RepgenParser;

/**
 * A small find bar docked over the top-right of the editor, VS Code/browser
 * Ctrl+F style, instead of a centered modal dialog. Replace is the rare case
 * (per user feedback most sessions never touch it) so it starts tucked behind
 * a disclosure arrow rather than permanently occupying its own row of buttons.
 */
public class FindReplaceShell {
	private Shell shell, parent;
	private StyledText txt;
	private RepgenParser parser; //Only used to disable it for replace All operations, can always be null
	private Label infoLabel, replaceLabel;
	private Text findText, replaceText;
	private Button caseButton, includeFoldedButton, prevButton, nextButton, replaceButton, replaceAllButton, replaceToggle;
	private boolean replace = true;
	// Whether the Replace row is currently expanded. Persists across attach()
	// (switching tabs) so a user mid-replace isn't collapsed out from under
	// themselves, but always starts collapsed for a file that can't be
	// replaced into (e.g. report output).
	private boolean replaceVisible = false;
	// Which way the next find() goes — set by whichever of findNext()/findPrevious()
	// was called, instead of a persistent "Direction" radio the user had to
	// remember to flip before searching (that's why the old dialog needed an
	// explicit forward/backward setting per search).
	private boolean searchForward = true;
	// Always wraps, like every modern find box (browser Ctrl+F, VS Code, etc).
	// replaceAll() clears this temporarily when replace-text contains find-text,
	// so "foo" -> "foobar" can't loop forever creating new matches of itself.
	private boolean wrapEnabled = true;

	public FindReplaceShell(Shell parent){
		this.parent = parent;
		createGUI();
	}

	public void open(){
		shell.open();
		positionShell();
		shell.setDefaultButton(nextButton);
		findText.setFocus();

		if( txt != null && !txt.getSelectionText().equals(""))
			findText.setText(txt.getSelectionText());

		findText.selectAll();
	}

	public void attach(StyledText txt, RepgenParser parser, boolean replace){
		this.txt = txt;
		this.parser = parser;
		this.replace = replace;

		replaceToggle.setEnabled(replace);
		replaceToggle.setVisible(replace);
		((GridData) replaceToggle.getLayoutData()).exclude = !replace;
		if( !replace )
			setReplaceVisible(false);
		relayout();
	}

	public void attach(StyledText txt, boolean replace){
		attach(txt,null,replace);
	}

	public void close(){
		shell.setVisible(false);
	}

	private void createGUI(){
		GridLayout layout = new GridLayout(7, false);
		layout.marginWidth = 8;
		layout.marginHeight = 8;
		layout.horizontalSpacing = 4;
		layout.verticalSpacing = 6;

		shell = new Shell(parent, SWT.TOOL | SWT.CLOSE);
		shell.setText("Find");
		shell.setImage(RepDevMain.smallFindReplaceImage);
		shell.setLayout(layout);
		shell.addShellListener(new ShellAdapter(){
			public void shellClosed(ShellEvent e) {
				e.doit = false;
				close();
			}
		});

		// Disclosure arrow for the Replace row. Spans both rows so it sits
		// pinned to the left edge regardless of which rows are showing.
		replaceToggle = new Button(shell, SWT.ARROW | SWT.RIGHT);
		replaceToggle.setToolTipText("Show Replace");
		GridData gd = new GridData(SWT.CENTER, SWT.FILL, false, false, 1, 2);
		replaceToggle.setLayoutData(gd);
		replaceToggle.addSelectionListener(new SelectionAdapter(){
			public void widgetSelected(SelectionEvent e){
				setReplaceVisible(!replaceVisible);
				relayout();
			}
		});

		Label findLabel = new Label(shell,SWT.NONE);
		findLabel.setText("Find");

		findText = new Text(shell,SWT.BORDER);
		gd = new GridData(SWT.FILL, SWT.CENTER, true, false);
		gd.widthHint = 240;
		findText.setLayoutData(gd);

		prevButton = new Button(shell,SWT.PUSH);
		prevButton.setText(String.valueOf((char) 0x25B2)); // up-pointing triangle; built from a code point, not a literal glyph, so it survives javac -encoding Cp1252
		prevButton.setToolTipText("Find Previous (Shift+Enter)");
		prevButton.setLayoutData(new GridData(28, SWT.DEFAULT));
		prevButton.addSelectionListener(new SelectionAdapter(){
			public void widgetSelected(SelectionEvent e){
				findPrevious();
			}
		});

		nextButton = new Button(shell,SWT.PUSH);
		nextButton.setText(String.valueOf((char) 0x25BC)); // down-pointing triangle
		nextButton.setToolTipText("Find Next (Enter)");
		nextButton.setLayoutData(new GridData(28, SWT.DEFAULT));
		nextButton.addSelectionListener(new SelectionAdapter(){
			public void widgetSelected(SelectionEvent e){
				findNext();
			}
		});

		// Enter -> next, Shift+Enter -> previous, same as browser/VS Code find
		// boxes.
		findText.addListener(SWT.Traverse, new Listener(){
			public void handleEvent(Event e){
				if(e.detail == SWT.TRAVERSE_RETURN){
					e.doit = false;
					if((e.stateMask & SWT.SHIFT) != 0) findPrevious(); else findNext();
				}
			}
		});

 		caseButton = new Button(shell,SWT.TOGGLE);
 		caseButton.setText("Aa");
 		caseButton.setToolTipText("Match case");
		caseButton.setSelection(Config.getCaseSensitive());
 		caseButton.addSelectionListener(new SelectionAdapter(){
			public void widgetSelected(SelectionEvent e){
				Config.setCaseSensitive(caseButton.getSelection());
			}
		});

 		includeFoldedButton = new Button(shell,SWT.TOGGLE);
 		includeFoldedButton.setText("Folded");
 		includeFoldedButton.setToolTipText("Search inside folded/collapsed sections");
		includeFoldedButton.setSelection(Config.getIncludeFoldedSections());
 		includeFoldedButton.addSelectionListener(new SelectionAdapter(){
			public void widgetSelected(SelectionEvent e){
				Config.setIncludeFoldedSections(includeFoldedButton.getSelection());
			}
		});

		// --- Replace row: hidden until the disclosure arrow is opened ---
		replaceLabel = new Label(shell,SWT.NONE);
		replaceLabel.setText("Replace");
		replaceLabel.setLayoutData(new GridData(SWT.LEFT, SWT.CENTER, false, false));

		replaceText = new Text(shell,SWT.BORDER);
		gd = new GridData(SWT.FILL, SWT.CENTER, true, false);
		gd.widthHint = 240;
		replaceText.setLayoutData(gd);
		// Enter in the Replace field replaces the current match and jumps to
		// the next one — the old dialog's "Replace/Find" button, folded into
		// a keystroke instead of its own permanent button.
		replaceText.addListener(SWT.Traverse, new Listener(){
			public void handleEvent(Event e){
				if(e.detail == SWT.TRAVERSE_RETURN){
					e.doit = false;
					replace();
					findNext();
				}
			}
		});

		replaceButton = new Button(shell,SWT.PUSH);
		replaceButton.setText("Replace");
		replaceButton.setLayoutData(new GridData(SWT.LEFT, SWT.CENTER, false, false));
		replaceButton.addSelectionListener(new SelectionAdapter(){
			public void widgetSelected(SelectionEvent e){
				replace();
			}
		});

		replaceAllButton = new Button(shell,SWT.PUSH);
		replaceAllButton.setText("Replace All");
		gd = new GridData(SWT.FILL, SWT.CENTER, false, false, 3, 1);
		replaceAllButton.setLayoutData(gd);
		replaceAllButton.addSelectionListener(new SelectionAdapter(){
			public void widgetSelected(SelectionEvent e){
				replaceAll();
			}
		});

		infoLabel = new Label(shell,SWT.NONE);
		infoLabel.setData("uitheme-muted", Boolean.TRUE);
		gd = new GridData(SWT.LEFT, SWT.CENTER, true, false, 7, 1);
		infoLabel.setLayoutData(gd);

		setReplaceVisible(false);

		shell.setDefaultButton(nextButton);
		shell.pack();
	}

	/** Shows/hides the Replace row and flips the disclosure arrow. Caller is responsible for calling relayout() if the shell is already on screen. */
	private void setReplaceVisible(boolean visible){
		replaceVisible = visible;
		replaceToggle.setAlignment(visible ? SWT.DOWN : SWT.RIGHT);
		replaceToggle.setToolTipText(visible ? "Hide Replace" : "Show Replace");

		replaceLabel.setVisible(visible);
		replaceText.setVisible(visible);
		replaceButton.setVisible(visible);
		replaceAllButton.setVisible(visible);
		((GridData) replaceLabel.getLayoutData()).exclude = !visible;
		((GridData) replaceText.getLayoutData()).exclude = !visible;
		((GridData) replaceButton.getLayoutData()).exclude = !visible;
		((GridData) replaceAllButton.getLayoutData()).exclude = !visible;
	}

	/** Re-packs and re-docks the bar after its row visibility changed. No-op while closed, so attach() calls between opens don't thrash layout. */
	private void relayout(){
		if( !shell.isVisible() )
			return;
		shell.layout(true, true);
		shell.pack();
		positionShell();
	}

	/**
	 * Centers the bar over the editor window. Re-run after every open()/resize
	 * since the app-wide dark-mode Show filter (RepDevMain) re-centers any
	 * non-main shell the moment it's shown.
	 */
	private void positionShell(){
		Rectangle parentBounds = parent.getBounds();
		Point size = shell.getSize();
		shell.setLocation(parentBounds.x + (parentBounds.width - size.x) / 2, parentBounds.y + (parentBounds.height - size.y) / 2);
	}

	protected void replaceAll() {
		init();

		if( !replace )
			return;

		txt.setRedraw(false);

		if( parser != null)
			parser.setReparse(false);

		searchForward = true;
		boolean savedWrap = wrapEnabled;
		if( wrapEnabled && replaceText.getText().contains(findText.getText()))
			wrapEnabled = false;

		while(true){
			if( !find() )
				break;

			if( !replace() )
				break;
		}

		wrapEnabled = savedWrap;

		if( parser != null){
			parser.setReparse(true);
			parser.reparseAll();
		}

		txt.setRedraw(true);
	}

	protected boolean replace() {
		init();

		if( !replace )
			return false;

		String text = txt.getText();
		String find = findText.getText(), replace = replaceText.getText(), selection = txt.getSelectionText();

		if( !caseButton.getSelection() ){
			text = text.toLowerCase();
			find = find.toLowerCase();
			selection = selection.toLowerCase();
		}

		if( !selection.equals(find) )
			return false;

		txt.replaceTextRange(txt.getSelection().x, txt.getSelection().y - txt.getSelection().x, replace);
		txt.setSelection(txt.getCaretOffset() - replace.length(), txt.getCaretOffset());

		return true;
	}

	private void init(){
		if( txt == null ){
			infoLabel.setText("No document opened");
			return;
		}

		infoLabel.setText("");
	}

	/** Search forward from the caret (wraps to the top at end of document). */
	public boolean findNext(){
		searchForward = true;
		return find();
	}

	/** Search backward from the caret (wraps to the bottom at start of document). */
	public boolean findPrevious(){
		searchForward = false;
		return find();
	}

	protected boolean find() {
		init();

		String text = txt.getText();
		String find = findText.getText(), replace = replaceText.getText();
		int nextPos, lastPos;

		if( !caseButton.getSelection() ){
			text = text.toLowerCase();
			find = find.toLowerCase();
			replace = replace.toLowerCase();
		}

		if( searchForward )
		{
			nextPos = text.indexOf(find, txt.getCaretOffset());

			if( nextPos == -1 && wrapEnabled ){
				nextPos = text.indexOf(find);

				if( nextPos >= txt.getCaretOffset() )
					nextPos = -1;
			}
		}
		else{
			//Might be slow, will check someday with a profiler
			nextPos = -1;
			lastPos = -1;

			while(true){
				nextPos = text.indexOf(find, nextPos + 1);

				if( nextPos + find.length() >= txt.getCaretOffset() || nextPos == -1)
				{
					nextPos = lastPos;
					break;
				}

				lastPos = nextPos;
			}

			if( nextPos == -1 && wrapEnabled ){
				nextPos = txt.getCharCount() - 1;
				lastPos = -1;

				while(true){
					nextPos = text.indexOf(find, txt.getCaretOffset());

					if( nextPos + find.length() < txt.getCaretOffset() || nextPos == lastPos)
					{
						nextPos = lastPos;
						break;
					}

					lastPos = nextPos;
				}
			}

		}

		if( nextPos == -1)
		{
			// Fallback: look inside collapsed fold regions. On a hit, expand the
			// best-positioned fold for the current direction and retry — the
			// previously hidden text is now in the buffer, so the standard
			// search path will land on it.
			if (includeFoldedButton.getSelection() && expandFoldContaining(findText.getText())) {
				return find();
			}
			infoLabel.setText("String not found");
			return false;
		}
		else{
			txt.setSelection(nextPos, nextPos + find.length());
			// Drop Navigation Position // Not currently for ReportComposite windows
			if(txt.getParent() instanceof EditorComposite)
				RepDevMain.mainShell.addToNavHistory(((EditorComposite)txt.getParent()).getFile(), txt.getLineAtOffset(txt.getCaretOffset()));
		}

		return true;
	}

	/**
	 * Scan currently-folded regions for {@code findStr} and, if found, expand
	 * the region best-suited to the current search direction. After return, the
	 * caller should retry the normal find loop — the hit is now visible.
	 *
	 * Direction handling: forward search prefers the closest fold whose
	 * header is at/after the caret line so the retry doesn't need wrap. Backward
	 * prefers the closest fold strictly before the caret line. If no fold in
	 * the current direction matches, falls back to any fold (only when wrap is
	 * on, since the retry needs wrap to reach matches behind/ahead of caret).
	 */
	private boolean expandFoldContaining(String findStr) {
		if (findStr == null || findStr.length() == 0) return false;
		if (!(txt.getParent() instanceof EditorComposite)) return false;
		FoldingManager folding = ((EditorComposite) txt.getParent()).getFolding();
		if (folding == null || !folding.hasActiveFolds()) return false;

		boolean caseSensitive = caseButton.getSelection();
		String needle = caseSensitive ? findStr : findStr.toLowerCase();
		boolean forward = searchForward;
		boolean wrap = wrapEnabled;

		int caretLine;
		try { caretLine = txt.getLineAtOffset(txt.getCaretOffset()); }
		catch (IllegalArgumentException ex) { return false; }

		FoldingManager.FoldRegion bestInDir = null;
		FoldingManager.FoldRegion bestAny = null;
		for (FoldingManager.FoldRegion fr : folding.getFoldedRegions()) {
			String hay = caseSensitive ? fr.hiddenText : fr.hiddenText.toLowerCase();
			if (!hay.contains(needle)) continue;

			// Track the closest in document order, used as the wrap fallback.
			if (bestAny == null
					|| (forward && fr.headerLine < bestAny.headerLine)
					|| (!forward && fr.headerLine > bestAny.headerLine)) {
				bestAny = fr;
			}
			// Forward includes headerLine == caretLine: the body sits at
			// headerLine+1+ in the buffer, which is past the caret offset.
			// Backward must be strict: a fold at the caret line lives after
			// the caret, so backward find() wouldn't reach it.
			boolean inDir = forward ? (fr.headerLine >= caretLine) : (fr.headerLine < caretLine);
			if (!inDir) continue;
			if (bestInDir == null
					|| (forward && fr.headerLine < bestInDir.headerLine)
					|| (!forward && fr.headerLine > bestInDir.headerLine)) {
				bestInDir = fr;
			}
		}

		FoldingManager.FoldRegion pick = bestInDir != null ? bestInDir : (wrap ? bestAny : null);
		if (pick == null) return false;
		folding.toggleAtLine(pick.headerLine);
		return true;
	}
}
