package org.repdev.engine.parser

/**
 * Ported from com.repdev.parser.Keyword. Sanitizes on construction like the
 * original: name trimmed+uppercased, description/example trimmed (blank if
 * absent) — kept as a plain class rather than a data class since the
 * constructor transforms its inputs rather than storing them verbatim.
 */
class Keyword(name: String, description: String?, example: String?) {
    val name: String = name.trim().uppercase()
    val description: String = description?.trim() ?: ""
    val example: String = example?.trim() ?: ""
}
