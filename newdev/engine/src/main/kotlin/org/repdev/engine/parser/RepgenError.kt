package org.repdev.engine.parser

/**
 * Ported from com.repdev.parser.Error — renamed to avoid shadowing
 * `kotlin.Error` (a `Throwable`) for anyone reading call sites elsewhere in
 * the engine. The constructor that wrapped a session `ErrorCheckResult` is
 * dropped for now; it comes back once the session layer is ported — see
 * MODERNIZATION_PLAN.md.
 */
data class RepgenError(val fileName: String, val description: String, val line: Int, val col: Int, val type: Type) {
    enum class Type { SYMITAR_ERROR, WARNING, ERROR }
}
