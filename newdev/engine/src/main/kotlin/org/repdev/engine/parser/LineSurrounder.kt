package org.repdev.engine.parser

/**
 * Pure port of `EditorComposite.surroundEachLineWith` — wraps every line in `[startLine, endLine]`
 * (0-based, inclusive) with [start]/[end], leaving lines outside that range untouched. Splitting on
 * `"\n"` already strips the line terminator the original stripped by hand (its `j`-scanning loop
 * trimming trailing `\n`/`\r` off each `StyledText.getText` slice) — [String.split] never includes
 * the delimiter, so that loop has no equivalent to port here.
 *
 * [escapeBadChars] reproduces the one escape the original had: a literal `"` inside a wrapped line
 * becomes `"+CTRLCHR(34)+"` (34 = `"`.code) so a `"`-delimited RepGen string built from these lines
 * doesn't break. The original's bad-char list was a single-element array with a `//TODO: Add more
 * to list later` — still just `"` here, not expanded, since nothing downstream asked for more.
 */
object LineSurrounder {
    fun surround(text: String, startLine: Int, endLine: Int, start: String, end: String, escapeBadChars: Boolean): String {
        val lines = text.split("\n")
        val lastLine = lines.size - 1
        val clampedEnd = if (endLine > lastLine) maxOf(lastLine, startLine + 1) else endLine

        return lines.mapIndexed { i, line ->
            if (i < startLine || i > clampedEnd) return@mapIndexed line
            val body = if (escapeBadChars) line.replace("\"", "\"+CTRLCHR(34)+\"") else line
            start + body + end
        }.joinToString("\n")
    }
}
