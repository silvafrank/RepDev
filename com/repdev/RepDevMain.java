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

import java.io.Console;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.GregorianCalendar;
import java.util.HashMap;

import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.Color;
import org.eclipse.swt.graphics.GC;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.graphics.ImageData;
import org.eclipse.swt.graphics.RGB;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Event;
import org.eclipse.swt.widgets.Listener;
import org.eclipse.swt.widgets.MessageBox;
import org.eclipse.swt.widgets.Shell;

/**
 * Main run class, runs as first startup
 * Provides many application global functions/variables, as well as intializes all the stuff we need
 *
 * TODO: Documentation for RepDev
 *
 * @see Awesomeness
 * @author Jake Poznanski, Ryan Schultz, Sean Delaney
 */
public class RepDevMain {
	public static final HashMap<Integer, SymitarSession> SYMITAR_SESSIONS = new HashMap<>();
	public static HashMap<Integer, SessionInfo> SESSION_INFO = new HashMap<>();
	public static byte [] MASTER_PASSWORD_HASH;
	public static final boolean DEVELOPER = false; //Set this flag to enable saving passwords, this makes it easy for developers to log in and check stuff quickly after making changes
	public static final int VMAJOR = 1;
	public static final int VMINOR = 7;
	public static final int VFIX   = 8;
	public static final String VSPECIAL = ""; // "special" string for release names, beta, etc

	public static final String VERSION = VMAJOR + "." + VMINOR + (VFIX>0?"."+VFIX:"") + (DEVELOPER ? "-dev" : "") + (!VSPECIAL.equals("")? " " + VSPECIAL : "");
	// start.bat (the testdev launcher) passes -DtestDev=true; the installed
	// production copy's own start.bat doesn't, so this only shows up when
	// running from the dev checkout — the easiest way to tell the two apart
	// when both might be open.
	public static final boolean IS_TEST_DEV = Boolean.getBoolean("testDev");
	public static final String NAMESTR = "RepDev v" + VERSION + (IS_TEST_DEV ? " [TestDev]" : "");
	public static boolean FORGET_PASS_ON_EXIT = false; // set in options only please.

	public static MainShell mainShell;
	private static Display display;
	public static Image smallAddImage, smallErrorsImage, smallDataImage, smallFileImage, smallProjectImage, smallRemoveImage, smallRepGenImage, smallSymImage, smallSymOnImage, smallTasksImage, smallActionSaveImage, smallFileAddImage, smallFileRemoveImage,
	smallProjectAddImage, smallProjectRemoveImage, smallRunImage, smallSymAddImage, smallSymRemoveImage, smallDBFieldImage, smallDBRecordImage, smallVariableImage, smallImportImage, smallFileNewImage, smallFileOpenImage, smallDeleteImage,
	smallOptionsImage, smallIndentLessImage, smallIndentMoreImage, smallCutImage, smallCopyImage, smallPasteImage, smallSelectAllImage, smallRedoImage, smallUndoImage, smallFindImage, smallFindReplaceImage, smallExitImage, smallRunFMImage,
	smallWarningImage, smallReportsImage, smallPrintImage, smallFolderImage, smallFolderAddImage, smallFolderRemoveImage, smallActionSaveAsImage, smallProgramIcon, smallInstallImage, smallCompareImage, smallSurroundImage, smallSurroundPrint,
	smallTaskTodo, smallTaskFixme, smallTaskBug, smallTaskWtf, smallTaskTest, smallTaskBookmark, smallTaskNote, smallHighlight, smallHighlightGrey, smallFormatCodeImage, smallInsertSnippetImage, smallFunctionImage, smallSnippetImage, smallKeywordImage, smallDefineVarImage,
	smallRepGenDemandImage, smallDarkModeImage, smallPanelsCollapseImage, smallPanelsExpandImage;
	public static final String IMAGE_DIR = "repdev-icons/";
	/**
	 * Toolbar/tree icon scale as a percent (100 = the shipped 16x16 assets as-is).
	 * Auto-detected from the display's DPI at startup so icons don't shrink to a
	 * sliver on high-res screens, snapped to the same steps Windows itself uses
	 * (100/125/150/.../300).
	 * Ceiling: read once at launch — doesn't live-rescale if the window is later
	 * dragged to a monitor with a different DPI. Revisit if that's a real pain point.
	 */
	public static int ICON_SCALE = 100;

	public static SnippetManager snippetManager;

	private enum CONFIGREV{
		NORMAL, NEW, OUTDATED
	}
	private static CONFIGREV configRev = CONFIGREV.NORMAL;

	public static void main(String[] args) throws Exception {
		display = new Display();
		ICON_SCALE = computeIconScale();

		System.out.println("\nRepDev " + VERSION + " Copyright (C) 2008-2014  RepDev.org Team\n"
				+"This program comes with ABSOLUTELY NO WARRANTY.\n"
				+"This is free software, and you are welcome to redistribute it \n"
				+"under certain conditions.\n");

		System.out.println("Java Runtime version " + System.getProperty("java.runtime.version"));
		System.out.println("---------------------------------------------------------");
		System.out.println("Charset.defaultCharset()                  = " + Charset.defaultCharset());
		System.out.println("System.getProperty(\"file.encoding\")       = " + System.getProperty("file.encoding"));

		try{
			loadSettings();
			createImages();
			createGUI();

			if (!Config.getPasswordValidator().contentEquals("") && RepDevMain.MASTER_PASSWORD_HASH == null) RepDev_SSO.login(mainShell.shell);
			
			while (!mainShell.isDisposed()) {
				// Catch per-iteration, not once around the whole loop: readAndDispatch()
				// runs every UI event (every save, every report run), so a single uncaught
				// exception from any one of them - most likely to surface right when a
				// stale socket from sleep/VPN drop makes some network call misbehave in a
				// way its own try/catch didn't anticipate - used to break out of the loop
				// entirely and take down the whole app via the outer catch + System.exit.
				// Catching it here shows the same error dialog but lets the app keep running.
				try {
					if (!display.readAndDispatch())
						display.sleep();
				} catch (Exception e) {
					e.printStackTrace();
					if (!display.isDisposed())
						new ErrorDialog(e).open();
				}
			}
		} catch(Exception e){
			if( e != null && e.getMessage() != null && e.getMessage().indexOf("[GDI+ is required]") != -1) {
				System.out.println("RepDev Requires GDI+ to be installed in order to run");
				System.out.println("GDI+ can be obtained from: http://www.microsoft.com/downloads/details.aspx?FamilyID=6a63ab9c-df12-4d41-933c-be590feaa05a&DisplayLang=en");
			} else {

				if (display.isDisposed())
					display = new Display();

				e.printStackTrace();

				ErrorDialog errorDialog = new ErrorDialog(e);
				errorDialog.open();

				display.dispose();
			}
		}

		// Save off projects
		ProjectManager.saveAllProjects();
		saveSettings();

		//Close all symitar connections
		for( SymitarSession session : SYMITAR_SESSIONS.values() ){
			if( session != null )
				session.disconnect();
		}

		display.dispose();
		System.exit(0);
	}

	/** Rounds the display's DPI to the nearest of Windows' own scaling steps (100/125/150/.../300). */
	private static int computeIconScale() {
		int pct = Math.round(display.getDPI().x * 100f / 96f);
		return Math.max(100, Math.min(300, Math.round(pct / 25f) * 25));
	}

	/** Loads a 1x (16x16) icon asset and scales it up to ICON_SCALE. */
	private static Image loadIcon(String path) {
		return scaleIcon(new Image(display, path));
	}

	/**
	 * Scales an already-loaded icon to ICON_SCALE, disposing the 1x source. No-op at 100%.
	 * Scales the raw ImageData (not a GC.drawImage onto a blank Image) — win32 GC compositing
	 * silently drops the destination's alpha channel, which was making every icon with real
	 * (non-binary) transparency vanish entirely once scaled. ImageData#scaledTo scales the
	 * pixel + alpha data directly, so transparency survives.
	 */
	private static Image scaleIcon(Image base) {
		if (ICON_SCALE == 100)
			return base;

		ImageData data = base.getImageData();
		ImageData scaled = data.scaledTo(data.width * ICON_SCALE / 100, data.height * ICON_SCALE / 100);
		base.dispose();

		return new Image(display, scaled);
	}

	/**
	 * A "disabled" variant that dims via alpha instead of SWT's default (desaturate-to-
	 * gray) disabled-image algorithm. Toolbar icons here are mostly-transparent glyphs
	 * with a small amount of color — flattened to gray they lose the one cue (color/
	 * shape) that told them apart, so several disabled buttons in a row all read as the
	 * same featureless blob. Keeping the hue and just fading it stays legible as
	 * "inactive" without sacrificing that. Pass to ToolItem#setDisabledImage.
	 */
	public static Image dimIcon(Image src) {
		ImageData data = src.getImageData();
		for (int y = 0; y < data.height; y++)
			for (int x = 0; x < data.width; x++)
				data.setAlpha(x, y, data.getAlpha(x, y) * 2 / 5); // ~40% of original opacity
		return new Image(display, data);
	}

	private static void createImages() {
		smallActionSaveImage = loadIcon(IMAGE_DIR + "small-action-save.png");
		smallAddImage = loadIcon(IMAGE_DIR + "small-add.png");
		smallErrorsImage = loadIcon(IMAGE_DIR + "small-errors.png");
		smallFileAddImage = loadIcon(IMAGE_DIR + "small-file-add.png");
		smallFileRemoveImage = loadIcon(IMAGE_DIR + "small-file-remove.png");
		smallFileImage = loadIcon(IMAGE_DIR + "small-file.png");
		smallDataImage = loadIcon(IMAGE_DIR + "small-data.png");
		smallErrorsImage = loadIcon(IMAGE_DIR + "small-errors.png");
		smallProjectAddImage = loadIcon(IMAGE_DIR + "small-project-add.png");
		smallProjectRemoveImage = loadIcon(IMAGE_DIR + "small-project-remove.png");
		smallProjectImage = loadIcon(IMAGE_DIR + "small-project.png");
		smallRemoveImage = loadIcon(IMAGE_DIR + "small-remove.png");
		smallRepGenImage = loadIcon(IMAGE_DIR + "small-repgen.png");
		smallRepGenDemandImage = loadIcon(IMAGE_DIR + "small-repgen-demand.png");
		smallRunImage = loadIcon(IMAGE_DIR + "small-run.png");
		smallSymImage = loadIcon(IMAGE_DIR + "small-sym.png");
		smallSymOnImage = loadIcon(IMAGE_DIR + "small-sym-on.png");
		smallTasksImage = loadIcon(IMAGE_DIR + "small-tasks.png");
		smallSymAddImage = loadIcon(IMAGE_DIR + "small-sym-add.png");
		smallSymRemoveImage = loadIcon(IMAGE_DIR + "small-sym-remove.png");
		smallDBRecordImage = loadIcon(IMAGE_DIR + "small-db-record.png");
		smallDBFieldImage = loadIcon(IMAGE_DIR + "small-db-field.png");
		smallVariableImage = loadIcon(IMAGE_DIR + "small-variable.png");
		smallImportImage = loadIcon(IMAGE_DIR + "small-import.png");
		smallFileNewImage = loadIcon(IMAGE_DIR + "small-file-new.png");
		smallFileOpenImage = loadIcon(IMAGE_DIR + "small-file-open.png");
		smallDeleteImage = loadIcon(IMAGE_DIR + "small-delete.png");
		smallOptionsImage = loadIcon(IMAGE_DIR + "small-options.png");
		smallIndentLessImage = loadIcon(IMAGE_DIR + "small-indent-less.png");
		smallIndentMoreImage = loadIcon(IMAGE_DIR + "small-indent-more.png");
		smallCutImage = loadIcon(IMAGE_DIR + "small-cut.png");
		smallCopyImage = loadIcon(IMAGE_DIR + "small-copy.png");
		smallPasteImage = loadIcon(IMAGE_DIR + "small-paste.png");
		smallRedoImage = loadIcon(IMAGE_DIR + "small-redo.png");
		smallUndoImage = loadIcon(IMAGE_DIR + "small-undo.png");
		smallSelectAllImage = loadIcon(IMAGE_DIR + "small-select-all.png");
		smallFindImage = loadIcon(IMAGE_DIR + "small-find.png");
		smallFindReplaceImage = loadIcon(IMAGE_DIR + "small-find-replace.png");
		smallExitImage = loadIcon(IMAGE_DIR + "small-exit.png");
		smallRunFMImage = loadIcon(IMAGE_DIR + "small-run-fm.png");
		smallWarningImage = loadIcon(IMAGE_DIR + "small-warning.png");
		smallReportsImage = loadIcon(IMAGE_DIR + "small-reports.png");
		smallPrintImage = loadIcon(IMAGE_DIR + "small-print.png");
		smallFolderImage = loadIcon(IMAGE_DIR + "small-folder.png");
		smallFolderAddImage = loadIcon(IMAGE_DIR + "small-folder-add.png");
		smallFolderRemoveImage = loadIcon(IMAGE_DIR + "small-folder-remove.png");
		smallActionSaveAsImage = loadIcon(IMAGE_DIR + "small-action-save-as.png");

		smallProgramIcon = loadIcon(IMAGE_DIR + "monkeyIcon16.png");
		smallInstallImage = loadIcon(IMAGE_DIR + "small-install-repgen.png");
		smallCompareImage = loadIcon(IMAGE_DIR + "small-compare.png");
		smallSurroundImage = loadIcon(IMAGE_DIR + "small-surround.png");
		smallSurroundPrint = loadIcon(IMAGE_DIR + "small-surround-print.png");

		smallHighlight = loadIcon(IMAGE_DIR + "small-highlight.png");
		smallHighlightGrey = loadIcon(IMAGE_DIR + "small-highlight-grey.png");

		smallTaskTodo = loadIcon(IMAGE_DIR + "small-task-todo.png");
		smallTaskFixme = loadIcon(IMAGE_DIR + "small-task-fixme.png");
		smallTaskBug = loadIcon(IMAGE_DIR + "small-task-bug.png");
		smallTaskWtf = loadIcon(IMAGE_DIR + "small-task-wtf.png");
		smallTaskTest = loadIcon(IMAGE_DIR + "small-task-test.png");
		smallTaskBookmark = loadIcon(IMAGE_DIR + "small-task-bookmark.png");
		smallTaskNote = loadIcon(IMAGE_DIR + "small-task-note.png");

		smallFormatCodeImage = loadIcon(IMAGE_DIR + "small-format-code.png");
		smallInsertSnippetImage = loadIcon(IMAGE_DIR + "small-insert-snippet.png");

		smallFunctionImage = loadIcon(IMAGE_DIR + "small-function.png");
		smallKeywordImage = loadIcon(IMAGE_DIR + "small-keyword.png");
		smallSnippetImage = loadIcon(IMAGE_DIR + "small-snippet.png");
		smallDefineVarImage = loadIcon(IMAGE_DIR + "small-define-var.png");

		// No shipped asset for this one (new feature) — drawn to match the other
		// icons' 16x16 size exactly instead of hand-authoring a PNG blind.
		smallDarkModeImage = scaleIcon(createMoonIcon(display));

		// The "collapse/expand side panels" toolbar button used to borrow the
		// indent-less/indent-more icons (still used as-is for their original job,
		// the editor's actual indent buttons). Those are dark, boxy glyphs that
		// clash hard against this pastel/glossy icon set when reused in the main
		// toolbar — drawn fresh here in the same light palette as everything else.
		smallPanelsCollapseImage = scaleIcon(createPanelToggleIcon(display, false));
		smallPanelsExpandImage = scaleIcon(createPanelToggleIcon(display, true));
	}

	/**
	 * Collapse/expand toggle icon for the "hide side panels" toolbar button — a soft
	 * pastel-blue rounded tile with a chevron, matching the rest of the toolbar's
	 * light glossy icons instead of the dark boxy indent icons it used to borrow.
	 * @param pointOutward false = "collapse" (chevrons point inward, normal view), true = "expand" (chevrons point outward, fullscreen active)
	 */
	private static Image createPanelToggleIcon(Display display, boolean pointOutward) {
		Color transparentColor = new Color(display, 255, 0, 255);
		Color fill = new Color(display, 0xBF, 0xDF, 0xFF); // soft pastel blue -- matches the other icons' light tone
		Color outline = new Color(display, 0x6F, 0xA8, 0xDC);
		Color chevron = new Color(display, 0x2F, 0x5B, 0x8A);

		Image img = new Image(display, 16, 16);
		GC gc = new GC(img);
		gc.setBackground(transparentColor);
		gc.fillRectangle(0, 0, 16, 16);

		gc.setBackground(fill);
		gc.fillRoundRectangle(1, 1, 14, 14, 5, 5);
		gc.setForeground(outline);
		gc.drawRoundRectangle(1, 1, 13, 13, 5, 5);

		gc.setBackground(chevron);
		if (pointOutward) {
			gc.fillPolygon(new int[]{5, 4, 9, 8, 5, 12});
			gc.fillPolygon(new int[]{9, 4, 13, 8, 9, 12});
		} else {
			gc.fillPolygon(new int[]{11, 4, 7, 8, 11, 12});
			gc.fillPolygon(new int[]{7, 4, 3, 8, 7, 12});
		}
		gc.dispose();

		ImageData data = img.getImageData();
		data.transparentPixel = data.palette.getPixel(new RGB(255, 0, 255));
		Image result = new Image(display, data);
		img.dispose();
		transparentColor.dispose();
		fill.dispose();
		outline.dispose();
		chevron.dispose();
		return result;
	}

	/** A simple 16x16 crescent-moon glyph for the dark-mode toolbar toggle. */
	private static Image createMoonIcon(Display display) {
		Color transparentColor = new Color(display, 255, 0, 255);
		Image img = new Image(display, 16, 16);
		GC gc = new GC(img);
		gc.setBackground(transparentColor);
		gc.fillRectangle(0, 0, 16, 16);
		gc.setBackground(new Color(display, 0xF2, 0xC9, 0x4C)); // warm gold, reads on light or dark bg
		gc.fillOval(2, 2, 11, 11);
		gc.setBackground(transparentColor); // carve a second, offset circle out to leave a crescent
		gc.fillOval(5, 0, 11, 11);
		gc.dispose();

		ImageData data = img.getImageData();
		data.transparentPixel = data.palette.getPixel(new RGB(255, 0, 255));
		Image result = new Image(display, data);
		img.dispose();
		transparentColor.dispose();
		return result;
	}

	/**
	 * Loads Config object and settings from a serialized file Also connects to
	 * all syms in the config file
	 *
	 * Also, starts the snippet manager
	 */
	public static void loadSettings() {
		String localFile = "repdev.conf";
		String userFile = System.getProperty("user.home") + System.getProperty("file.separator") + "repdev.conf";
		String loadFile = localFile;

		if( new File(userFile).exists() && !new File(localFile).exists() ){
			System.out.println("The config file is being copied from it's old location in your user folder, to the local Repdev install folder.\nOld Location: " +
								userFile);
			loadFile = userFile;
		}

		try (ObjectInputStream in = new ObjectInputStream(new FileInputStream(loadFile))) {
			Config configObject = (Config) in.readObject();
			Config.setConfig(configObject);
		} catch (ClassCastException e) {
			System.out.println("FILE OUT OF DATE!");
		} catch (IOException e) {
			//Any odd defaults
			Config.setRevision(-1);
			Config.setRunOptionsQueue(-1);
			Config.setLastPassword(""); // only saved if RepDevMain.DEVELOPER
			Config.setLastUserID("");
			Config.setLastUsername("");
			Config.setTerminateHour(20);
			Config.setTerminateMinute(0);
			Config.setListUnusedVars(true);

			System.out.println("Creating data file for the first time.");
			saveSettings();
		} catch (ClassNotFoundException e) {
			e.printStackTrace();
		}
		
		SESSION_INFO = Config.getSessionInfo();
		if(SESSION_INFO == null) {
			SESSION_INFO = new HashMap<>();
		}
		
		SymitarSession session;

		// Start up data
		for (int sym : Config.getSyms()) {

			if( Config.getServer().equalsIgnoreCase("testsession")) //Allows for a testing mode when no symitar server's are available
				session = new TestingSymitarSession();
			else
				session = new DirectSymitarSession();

			if (SESSION_INFO.get(sym) == null) {
				SessionInfo si = new SessionInfo("", "", "", "", "");
				SESSION_INFO.put(sym, si);
			} else {
				session.setServer(SESSION_INFO.get(sym).getServer());
			}
			SYMITAR_SESSIONS.put(sym, session);
		}
		if(Config.getTerminateHour()==0){
			Config.setTerminateHour(20);
			Config.setTerminateMinute(0);
			saveSettings();
		}

		if(Config.getRevision()==-1){
			configRev = CONFIGREV.NEW;
		}
		else if (Config.getRevision()!=Config.REVISION){
			configRev = CONFIGREV.OUTDATED;
		}
	}

	/**
	 * Saves the config object to a file
	 *
	 */

	public static void saveSettings() {
		try {
			// Write the current syms to the Config file
			ArrayList<Integer> newSyms = new ArrayList<>();
			HashMap<Integer, SessionInfo> newSessionInfo = new HashMap<>();

			for (int sym : SYMITAR_SESSIONS.keySet()) {
				newSyms.add(sym);
				newSessionInfo.put(sym, SESSION_INFO.get(sym));
			}

			Config.setSyms(newSyms);
			Config.setSessionInfo(newSessionInfo);


			//Only save passwords if DEVELOPER FLAG is on
			if( !DEVELOPER || FORGET_PASS_ON_EXIT ){
				Config.setLastPassword("");
				Config.setLastUserID("");
			}

			try (ObjectOutputStream out = new ObjectOutputStream(new FileOutputStream("repdev.conf"))) {
				out.writeObject(Config.getConfig());
			}
		} catch (Exception e) {
			System.err.println("Error saving Config data");
			e.printStackTrace();
		}
	}

	private static void createGUI() {
		// Set Default Size
		if(configRev == CONFIGREV.NEW){
			Config.setSashHSize(150);
			Config.setSashVSize(150);
		}

		// Dark chrome + auto-centering for every dialog, applied the moment each
		// shell first shows itself — one filter instead of touching all ~28
		// dialog classes individually. The main window gets themed too but not
		// re-centered (it restores its own saved size/maximized state).
		Display.getDefault().addFilter(SWT.Show, new Listener() {
			public void handleEvent(Event event) {
				if (!(event.widget instanceof Shell)) return;
				Shell s = (Shell) event.widget;
				if (Config.getDarkMode()) UITheme.apply(s);
				if (mainShell == null || s != mainShell.shell) UITheme.center(s);
			}
		});

		mainShell = new MainShell(display);
		mainShell.open();
		createGlobalHotkeys();
		if(configRev != CONFIGREV.NORMAL){
			MessageBox msg = new MessageBox(mainShell.getShell(), SWT.ICON_WARNING);
			msg.setText("RepDev Options");

			if(configRev == CONFIGREV.NEW){
				msg.setMessage("Welcome to RepDev. Please take a few moments to configure your Options.");
			}
			else{
				msg.setMessage("The RepDev Team has added new options.  Please take a few moments to configure them.");
			}
			msg.open();
			OptionsShell.show(mainShell.getShell());
			Config.setRevision(Config.REVISION);
		}
	}

	private static void createGlobalHotkeys(){
		Display.getDefault().addFilter(SWT.KeyDown, new Listener() {
			public void handleEvent(Event e) {
				if( e.stateMask == (SWT.CTRL | SWT.SHIFT) ){
//					if(e.keyCode == SWT.F11)
//						RepDevMain.mainShell.toggleFullScreen();
					switch(e.keyCode) {
					case 'f':
					case 'F':
						RepDevMain.mainShell.toggleFullScreen();
						break;
					case 's':
					case 'S':
						RepDevMain.mainShell.saveAllRepgens();
						break;

					case 'o':
					case 'O':
						RepDevMain.mainShell.showOptions();
						break;
					}

				}
				else if (e.stateMask == SWT.CTRL) {
					switch (e.keyCode) {
					case 'o':
					case 'O':
					case 't':
					case 'T':
						// Open Existing in whichever sym/dir is selected in the explorer tree.
						RepDevMain.mainShell.showFileOpenMenu();
						break;
					case 'n':
					case 'N':
						// New File in whichever sym/dir is selected in the explorer tree.
						RepDevMain.mainShell.showNewFileDialog();
						break;
					}
				}
			}
			});
	}
}
