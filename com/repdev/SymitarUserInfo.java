package com.repdev;

import com.jcraft.jsch.UIKeyboardInteractive;
import com.jcraft.jsch.UserInfo;

/**
 * Handles Symitar's keyboard-interactive SSH authentication.
 * The server prompts for "user name" then "Password" via
 * keyboard-interactive, rather than using standard password auth.
 */
public class SymitarUserInfo implements UserInfo, UIKeyboardInteractive {
	private final String username;
	private final String password;

	// Tracks conversation progress across rounds. Earlier logic guessed each
	// round's role from the prompt text/echo flag alone; if the guess for the
	// username round was wrong (or AIX re-asks a round we don't recognize),
	// the server's PAM stack silently retries with a fresh round, and each
	// retry counts against sshd's MaxAuthTries - a few wrong guesses in a row
	// is exactly what trips "Too many authentication failures". Tracking
	// whether we've already answered a username round removes the guesswork
	// for every round after the first: once the username is sent, anything
	// unrecognized is answered as the password, matching the documented
	// "user name then Password" sequence instead of re-guessing from echo.
	private boolean usernameSent = false;

	public SymitarUserInfo(String username, String password) {
		this.username = username;
		this.password = password;
	}

	// UIKeyboardInteractive - handles the Symitar prompts
	public String[] promptKeyboardInteractive(String destination, String name,
			String instruction, String[] prompt, boolean[] echo) {
		String[] response = new String[prompt.length];
		for (int i = 0; i < prompt.length; i++) {
			String p = prompt[i].toLowerCase();
			boolean isUsername;

			if (p.contains("password")) {
				isUsername = false;
			} else if (p.contains("user") || p.contains("login") || p.contains("name")) {
				isUsername = true;
			} else {
				// Wording didn't match what we expect. Rather than guess from the
				// echo flag (which some AIX PAM configs report incorrectly), fall
				// back on conversation state: the server always asks for the
				// username before the password, so anything unrecognized after
				// we've already sent the username must be the password round.
				isUsername = !usernameSent;
			}

			// AIX servers here chain multiple PAM modules behind keyboard-interactive,
			// each independently re-prompting "Password:" within the *same* auth
			// attempt - answering it again with the same password each time is
			// correct, not a retry of a rejected answer. Don't try to guess wrong
			// password from a repeated prompt; just answer every round like the
			// legacy plink-based flow did. If the password is actually wrong, JSch
			// itself throws once every method is exhausted (see the "Auth fail"
			// catch in DirectSymitarSession).
			System.out.println("SSH keyboard-interactive prompt: \"" + prompt[i] + "\" (echo=" + echo[i]
					+ ") -> answered as " + (isUsername ? "USERNAME" : "PASSWORD"));

			response[i] = isUsername ? username : password;
			if (isUsername) usernameSent = true;
		}
		return response;
	}

	// UserInfo
	public String getPassphrase() { return null; }
	public String getPassword() { return password; }
	public boolean promptPassword(String message) { return true; }
	public boolean promptPassphrase(String message) { return false; }
	public boolean promptYesNo(String message) { return true; }
	public void showMessage(String message) { System.out.println(message); }

	/**
	 * ponytail: no test framework in this project - plain assert-based
	 * self-check for the prompt/role decision logic. Run directly:
	 *   java -ea -cp .;jsch.jar com.repdev.SymitarUserInfo
	 */
	public static void main(String[] args) {
		SymitarUserInfo info = new SymitarUserInfo("jdoe", "secret");

		assertEq("jdoe", info.promptKeyboardInteractive("h", "n", "i",
				new String[]{"user name:"}, new boolean[]{true})[0], "recognized username prompt");
		assertEq("secret", info.promptKeyboardInteractive("h", "n", "i",
				new String[]{"Password:"}, new boolean[]{false})[0], "recognized password prompt");

		// Fresh conversation: first unrecognized prompt should be treated as username...
		SymitarUserInfo info2 = new SymitarUserInfo("jdoe", "secret");
		assertEq("jdoe", info2.promptKeyboardInteractive("h", "n", "i",
				new String[]{"???"}, new boolean[]{true})[0], "unrecognized prompt before username sent");
		// ...and the next unrecognized prompt should be treated as password, regardless of echo.
		assertEq("secret", info2.promptKeyboardInteractive("h", "n", "i",
				new String[]{"???"}, new boolean[]{true})[0], "unrecognized prompt after username sent");

		// A repeated password prompt (multiple PAM modules chained behind
		// keyboard-interactive) must be answered again, not rejected.
		SymitarUserInfo info3 = new SymitarUserInfo("jdoe", "secret");
		info3.promptKeyboardInteractive("h", "n", "i", new String[]{"Password:"}, new boolean[]{false});
		assertEq("secret", info3.promptKeyboardInteractive("h", "n", "i",
				new String[]{"Password:"}, new boolean[]{false})[0], "repeated password prompt answered again");

		System.out.println("SymitarUserInfo self-check passed.");
	}

	private static void assertEq(String expected, String actual, String label) {
		if (!expected.equals(actual))
			throw new AssertionError(label + ": expected \"" + expected + "\" but got \"" + actual + "\"");
	}
}
