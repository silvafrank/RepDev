package org.repdev.engine

import java.io.File
import java.util.Date

/**
 * Ported from com.repdev.SymitarFile — a descriptor for a file that's either
 * local (on disk) or on a Symitar host (identified by `sym` + `name` +
 * [FileType]).
 *
 * Structural change: `getData()`/`saveFile()`/`saveName()` no longer reach
 * into a process-wide `RepDevMain.SYMITAR_SESSIONS.get(sym)` registry or
 * instantiate `SourceControl` directly — those were the two biggest hidden
 * global-singleton dependencies in the original class. Instead this is a
 * plain descriptor; I/O goes through whichever [SymitarSession] the caller
 * already holds (`session.getFile(file)`, `session.saveFile(file, text)`,
 * `session.renameFile(file, newName)`), matching the shape those methods
 * already have in the abstract session interface. Local-file I/O
 * ([readLocal]/[writeLocal]) stays here since it doesn't need a session.
 *
 * `Serializable` was dropped — nothing in the new app persists this via Java
 * serialization (see DECISIONS.md's security list: no native serialization).
 */
data class SymitarFile(
    val name: String,
    val dir: String = "",
    val type: FileType = FileType.REPGEN,
    val sym: Int = 0,
    val local: Boolean = false,
    val modified: Date = Date(0),
    val installed: Date = Date(0),
    val size: Long = -1,
    val onDemand: Boolean = false,
    val syncRepGen: Boolean = false,
    val disableSourceControl: Boolean = false,
    val compareMode: Boolean = false,
) {
    val path: String get() = "$dir\\$name"

    /** For a local file: append a trailing newline if missing — Symitar rejects repgens with no final newline on error check. */
    fun withTrailingNewlineIfRepgen(data: String): String =
        if (type == FileType.REPGEN && data.isNotEmpty() && !data.endsWith("\n")) data + "\n" else data

    fun readLocal(): String? {
        require(local) { "readLocal() called on a non-local SymitarFile" }
        return runCatching { File(path).readText() }.getOrNull()
    }

    fun writeLocal(data: String?) {
        require(local) { "writeLocal() called on a non-local SymitarFile" }
        val file = File(path)
        if (file.exists() && !file.canWrite()) file.setWritable(true)
        file.writeText(data ?: "")
    }

    /** Same field-equality semantics as the original's `equals` override, matching how it disambiguates local vs. remote. */
    fun sameFileAs(other: SymitarFile): Boolean =
        name == other.name && type == other.type &&
            if (local) dir == other.dir else sym == other.sym
}
