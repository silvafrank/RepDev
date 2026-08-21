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
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.MessageBox;
import org.eclipse.swt.widgets.Shell;

/**
 * Small helpers that collapse boilerplate duplicated across most of RepDev's
 * dialog {@code Shell} classes: the modal event-pump loop, and the plain
 * OK-only error {@code MessageBox}. Not a base class - the dialogs differ too
 * much in modality flags, construction style, and return conventions to share
 * one; these are just the two patterns that were genuinely identical
 * everywhere they appeared.
 *
 * @author RepDev
 */
public final class DialogUtil {

	private DialogUtil() {
	}

	/**
	 * Blocks the calling thread, pumping the display's event loop, until
	 * {@code shell} is disposed. Standard modal-dialog wait loop.
	 */
	public static void pumpUntilClosed(Shell shell) {
		Display display = shell.getDisplay();
		while (!shell.isDisposed()) {
			if (!display.readAndDispatch())
				display.sleep();
		}
	}

	/**
	 * Shows a simple OK/error {@code MessageBox} parented on {@code parent}.
	 */
	public static void error(Shell parent, String title, String message) {
		MessageBox dialog = new MessageBox(parent, SWT.OK | SWT.ICON_ERROR);
		dialog.setText(title);
		dialog.setMessage(message);
		dialog.open();
	}
}
