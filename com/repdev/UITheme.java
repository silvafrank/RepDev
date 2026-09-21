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
import org.eclipse.swt.custom.CTabFolder;
import org.eclipse.swt.custom.StyledText;
import org.eclipse.swt.graphics.Color;
import org.eclipse.swt.graphics.GC;
import org.eclipse.swt.graphics.Rectangle;
import org.eclipse.swt.widgets.Combo;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Event;
import org.eclipse.swt.widgets.List;
import org.eclipse.swt.widgets.Listener;
import org.eclipse.swt.widgets.Monitor;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Table;
import org.eclipse.swt.widgets.Text;
import org.eclipse.swt.widgets.Tree;

/**
 * Subtle dark chrome for the app's shells (dialogs, trees, tables, labels, etc),
 * toggleable live from the toolbar (see MainShell) and persisted via
 * {@link Config#getDarkMode()}. The code editor keeps its own colors from the
 * user's chosen styles/*.xml (see {@link Style} / {@link SyntaxHighlighter}) —
 * StyledText is skipped here so the two theming systems don't fight.
 *
 * Ceiling: Windows renders several native controls with its own chrome no
 * matter what setBackground/setForeground says.
 *  - Push buttons: face + text stay OS-default regardless — left alone (worse
 *    to half-color one and not the other).
 *  - Table/Tree column headers: native-drawn, stay OS-default above a themed body.
 *  - Table/Tree SELECTED rows: win32 paints the selection highlight using the
 *    OS's own system colors, ignoring item.setForeground()/setBackground() —
 *    unlike the unselected rows, which *do* follow them. Worked around below via
 *    an SWT.EraseItem hook that suppresses the native highlight paint and draws
 *    our own when a row is selected in dark mode, instead of leaving whichever
 *    row happens to be selected pinned to native colors.
 * A fully native-matching dark mode needs Windows' own dark-mode APIs
 * (undocumented uxtheme.dll calls via JNI) — a much bigger, OS-version-fragile
 * undertaking, skipped here; ask if that's worth pursuing.
 */
final class UITheme {
	private UITheme() {}

	// Read by the SWT.EraseItem hook below at paint time, so a single hook
	// installed once per Tree/Table stays correct across toggles without
	// needing to be re-attached. Seeded from the persisted setting rather than
	// hardcoded false — RepDevMain/MainShell call UITheme.apply() directly (not
	// setTheme()) for dialogs and the main shell at startup when dark mode was
	// already on last session, and this flag needs to already be correct then,
	// not just after the toolbar button is clicked.
	private static boolean dark = Config.getDarkMode();

	// Matches the editor's own VSCode Dark+-style palette (styles/GhostRider.xml)
	// instead of a separate, muted "Darcula-ish" gray — the two used to sit right
	// next to each other looking like different apps, and the old BG/FG pair
	// (0x2B2B2B/0xBB) was low-contrast enough on its own to read as washed-out.
	static final Color BG = new Color(null, 0x1E, 0x1E, 0x1E);
	static final Color FG = new Color(null, 0xD4, 0xD4, 0xD4);
	// One step lighter than BG, for controls that read as inset "fields"
	// (text boxes, lists, trees, tables) against the shell background.
	static final Color BG_FIELD = new Color(null, 0x2D, 0x2D, 0x30);
	// For controls marked setData("uitheme-muted", TRUE) — a secondary-emphasis
	// label (e.g. a section header) that wants to read as dimmer than normal body
	// text in BOTH themes, not just get flattened to whatever apply()/unapply()
	// would otherwise assign everything else.
	static final Color MUTED_DARK = new Color(null, 0x8A, 0x8A, 0x8E);
	static final Color MUTED_LIGHT = new Color(null, 0x60, 0x60, 0x60);

	/** Recursively applies the dark palette to a control and its children. */
	static void apply(Control c) {
		if (c instanceof StyledText) return; // editor keeps its own style.xml colors
		if (c.getData("uitheme-muted") != null) {
			c.setBackground(BG);
			c.setForeground(MUTED_DARK);
		} else {
			c.setBackground(isFieldLike(c) ? BG_FIELD : BG);
			c.setForeground(FG);
		}
		// CTabFolder (the open-file tab strip) keeps a SEPARATE pair of colors for
		// whichever tab is selected — plain setBackground/setForeground above only
		// covers the folder chrome and the *unselected* tabs. Left alone, the active
		// tab (the one you're looking at almost the whole time) stays pinned to its
		// SWT default of black-on-white, a bright island in an otherwise dark app.
		if (c instanceof CTabFolder) {
			((CTabFolder) c).setSelectionBackground(BG_FIELD);
			((CTabFolder) c).setSelectionForeground(FG);
		}
		if (c instanceof Tree || c instanceof Table) {
			hookSelectionErase(c);
		}
		if (c instanceof Composite) {
			for (Control child : ((Composite) c).getChildren()) apply(child);
		}
	}

	/**
	 * Installs (once) an SWT.EraseItem listener that repaints a selected row's
	 * background itself instead of letting win32's native highlight — which
	 * ignores item-level colors — win. Guarded by widget data so re-running
	 * apply() on the same Tree/Table doesn't stack duplicate listeners.
	 */
	private static void hookSelectionErase(Control c) {
		if (c.getData("uitheme-erase-hooked") != null) return;
		c.setData("uitheme-erase-hooked", Boolean.TRUE);
		c.addListener(SWT.EraseItem, new Listener() {
			public void handleEvent(Event event) {
				if (!dark || (event.detail & SWT.SELECTED) == 0) return;
				event.detail &= ~SWT.SELECTED;
				GC gc = event.gc;
				Color oldBg = gc.getBackground();
				gc.setBackground(BG_FIELD);
				gc.fillRectangle(event.x, event.y, event.width, event.height);
				gc.setBackground(oldBg);
			}
		});
	}

	/** Recursively resets a control and its children back to the OS default look. */
	static void unapply(Control c) {
		if (c instanceof StyledText) return;
		if (c.getData("uitheme-muted") != null) {
			c.setBackground(null);
			c.setForeground(MUTED_LIGHT);
		} else {
			c.setBackground(null);
			c.setForeground(null);
		}
		if (c instanceof CTabFolder) {
			((CTabFolder) c).setSelectionBackground((Color) null);
			((CTabFolder) c).setSelectionForeground(null);
		}
		if (c instanceof Composite) {
			for (Control child : ((Composite) c).getChildren()) unapply(child);
		}
	}

	/** Live entry point for the toolbar toggle: re-themes an already-open shell in place. */
	static void setTheme(Control root, boolean darkMode) {
		dark = darkMode;
		if (dark) apply(root); else unapply(root);
		root.redraw();
	}

	private static boolean isFieldLike(Control c) {
		return c instanceof Text || c instanceof List || c instanceof Table
				|| c instanceof Tree || c instanceof Combo;
	}

	/** Centers a dialog shell on whichever monitor it (or its parent) is on. */
	static void center(Shell shell) {
		Rectangle shellBounds = shell.getBounds();
		Monitor monitor = shell.getMonitor();
		Rectangle monBounds = monitor.getBounds();
		int x = monBounds.x + (monBounds.width - shellBounds.width) / 2;
		int y = monBounds.y + (monBounds.height - shellBounds.height) / 2;
		shell.setLocation(x, y);
	}
}
