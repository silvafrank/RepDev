/**
 *  RepDev - RepGen IDE for Symitar
 *  Copyright (C) 2007  Jake Poznanski, Ryan Schultz, Sean Delaney
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
import org.eclipse.swt.events.DisposeEvent;
import org.eclipse.swt.events.DisposeListener;
import org.eclipse.swt.events.PaintEvent;
import org.eclipse.swt.events.PaintListener;
import org.eclipse.swt.graphics.Font;
import org.eclipse.swt.graphics.FontData;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.graphics.Rectangle;
import org.eclipse.swt.widgets.Composite;

/**
 * Drop-in replacement for SWT's native {@link org.eclipse.swt.widgets.Group}:
 * a bold section title with no enclosing box, instead of Win32's dated
 * GroupBox chrome — which is also one of the native-rendering ceilings
 * {@link UITheme} can't recolor (a Group's title text ignores setForeground
 * on Windows; see that class's header comment). Dialogs used to read as a
 * wall of boxed panels; this reads like a modern settings screen instead
 * (VSCode/macOS System Settings-style bold section labels, no border).
 *
 * Usage is identical to Group: construct with (parent, style), call
 * setText(title), setLayout(...), and add children exactly as before — the
 * title reserves its own strip by shrinking getClientArea(), the same trick
 * Group itself uses internally for its border+title trim. No child or layout
 * code at any call site needs to change.
 */
public class Section extends Composite {
	private static final int TITLE_HEIGHT = 24;
	private String title = "";
	private final Font titleFont;

	/** style is accepted only so Group(parent, SWT.NONE) call sites drop in unchanged — Section has no border/box style of its own. */
	public Section(Composite parent, int style) {
		super(parent, SWT.NONE);

		FontData[] fd = getFont().getFontData();
		for (FontData d : fd) d.setStyle(SWT.BOLD);
		titleFont = new Font(parent.getDisplay(), fd);

		addPaintListener(new PaintListener() {
			public void paintControl(PaintEvent e) {
				e.gc.setFont(titleFont);
				e.gc.setForeground(getForeground());
				e.gc.drawText(title, 0, 4, true);
			}
		});
		addDisposeListener(new DisposeListener() {
			public void widgetDisposed(DisposeEvent e) {
				titleFont.dispose();
			}
		});
	}

	public void setText(String text) {
		title = text == null ? "" : text;
		redraw();
	}

	public String getText() {
		return title;
	}

	public Rectangle getClientArea() {
		Rectangle r = super.getClientArea();
		return new Rectangle(r.x, r.y + TITLE_HEIGHT, r.width, Math.max(0, r.height - TITLE_HEIGHT));
	}

	public Point computeSize(int wHint, int hHint, boolean changed) {
		int innerHHint = hHint == SWT.DEFAULT ? SWT.DEFAULT : Math.max(0, hHint - TITLE_HEIGHT);
		Point size = super.computeSize(wHint, innerHHint, changed);
		return new Point(size.x, size.y + TITLE_HEIGHT);
	}
}
