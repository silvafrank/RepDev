package org.repdev.engine

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Non-secret metadata for one open SYM session. Ported from Config.java's
 * per-session SessionInfo, minus the credential fields: `aixPassword` and
 * `userID` were stored here as ciphertext in the original (still readable
 * by anyone who guessed/brute-forced RepDev_SSO's hardcoded salt — see
 * DECISIONS.md). They now live only in [org.repdev.engine.security.CredentialVault],
 * keyed by sym id, and never touch this file — encrypted or not.
 */
@Serializable
data class SessionInfo(
    val description: String = "",
    val server: String = "",
    val aixUsername: String = "",
)

@Serializable
data class WindowSize(val width: Int, val height: Int)

/**
 * Ported from Config.java. Two structural changes beyond the language port:
 *
 * 1. Immutable data class instead of a mutable static singleton (`Config.me`
 *    with ~40 getter/setter pairs) — callers hold a `Config` value and
 *    replace it, which is also just what Compose's state model wants.
 * 2. No credential fields at all: the original's `lastUsername`/`lastPassword`
 *    were "encrypted" with a same-object-stored rotation key (see
 *    DECISIONS.md — trivially reversible, and via an operator-precedence bug
 *    not even randomized), gated only by a compile-time DEVELOPER flag.
 *    Nothing like it exists here; real per-session secrets go through
 *    CredentialVault, which is the only place a credential is ever written
 *    to disk, and only as AES-GCM ciphertext.
 *
 * `port` defaults to 22 (SSH), not the original's 23 (Telnet) — see
 * MODERNIZATION_PLAN.md §4 finding 4, which asks for SSH by default with
 * Telnet, if kept at all, opt-in and loudly flagged (not deleted — some
 * consoles genuinely have no SSH enabled). `DirectSymitarSession` keeps the
 * original's `port == 22 → SSH, else → Telnet` branch and logs a plaintext-
 * transport warning on every Telnet connect; see DECISIONS.md §5.
 */
@Serializable
data class Config(
    val syms: List<Int> = emptyList(),
    val sessionInfo: Map<Int, SessionInfo> = emptyMap(),
    val server: String = "127.0.0.1",
    val port: Int = 22,
    val tabSize: Int = 0, // 0 = regular tab
    val style: String = "default",
    val liveSym: Int = 1999,
    val liveSymColor: String = "FFD7E4",
    val useSourceControl: Boolean = false,
    val sourceControlDir: String = "",
    val revision: Int = -1,
    val windowMaximized: Boolean = false,
    val windowSize: WindowSize? = null,
    val listUnusedVars: Boolean = false,
    val wrapSearch: Boolean = false,
    val caseSensitive: Boolean = false,
    val neverTerminateKeepAlive: Boolean = false,
    val includeFoldedSections: Boolean = false,
    val terminateHour: Int = 0,
    val terminateMinute: Int = 0,
    val sashHSize: Int = 0,
    val sashVSize: Int = 0,
    val backupProjectFiles: Boolean = false,
    val noErrorCheckSuffix: String = ".PRO,.SET,.DEF,.INC",
    val noErrorCheckPrefix: String = "INC.",
    val fileNameInWinTitle: Boolean = true,
    val hostInTitle: Boolean = true,
    // Stored right-way-round now (true = the feature is on). The original
    // inverted these ("lightMode", "hideLineNumbers", "foldingDisabled") as a
    // workaround for Java serialization defaulting missing fields to `false`
    // on old config files. A JSON data class with a real default value per
    // field doesn't have that problem, so the inversion trick is gone too.
    val darkMode: Boolean = true,
    val showLineNumbers: Boolean = true,
    val foldingEnabled: Boolean = true,
    val recentFiles: List<String> = emptyList(),
    val mountedDirs: List<String> = emptyList(),
) {
    companion object {
        /** Bump whenever a new option is added, same convention as the original REVISION const. */
        const val REVISION = 6

        private val json = Json { prettyPrint = true; ignoreUnknownKeys = true }

        /** Returns the default [Config] if [file] doesn't exist yet — no separate "first run" path needed. */
        fun load(file: File): Config =
            if (file.exists()) json.decodeFromString(file.readText()) else Config()

        fun save(config: Config, file: File) {
            file.writeText(json.encodeToString(config))
        }
    }
}
