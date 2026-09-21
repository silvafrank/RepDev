package org.repdev.engine.parser

/**
 * Ported from `com.repdev.parser.Formatter`. Rewrites an already-tokenized RepGen source:
 * collapses horizontal inter-token whitespace to a single space (governed by the
 * `noSpaceBefore`/`noSpaceAfter` punctuation lists), forces a reindented newline after `do`/`end`
 * and before an upcoming `do`/`end`, and otherwise preserves the original's own line breaks
 * (reindented) while leaving string/comment contents alone.
 *
 * **`tabStr` is a constructor parameter, not read from a global**, same call as §11's
 * `AutoIndenter` — the caller (once there's an editor wired to `Config`) computes it the same way
 * `EditorComposite.getTabStr()` did.
 *
 * All the quirks below are preserved exactly, not cleaned up, per this port's "translate the
 * algorithm, document the quirks" discipline:
 * - `noSpaceBefore`/`noSpaceAfter` are checked with plain substring [String.contains], the same
 *   as the original's `String.contains`. A compound token like `:(` is checked as the literal
 *   two-character sequence `:(`, which doesn't appear in either punctuation string (it has `(:`
 *   not `:(`) — so `:(` never suppresses the space around it, unlike a bare `:` or `(`. Preserved
 *   as-is; it's an edge case that doesn't come up for the single-character tokens these lists
 *   were written for.
 * - The "no second newline before a following `else`/`end`" suppression is checked purely by
 *   looking at what the *next* token's text is, regardless of whether the *current* token was
 *   `do` or `end` — so it would (harmlessly, since the syntax doesn't occur) also apply to a
 *   stray `do` immediately followed by `else`/`end`.
 */
class Formatter(private val oldFile: String, private val tokens: List<Token>, private val tabStr: String) {

    private val noSpaceBefore = "():.,%=+-/*<>"
    private val noSpaceAfter = "(:.=+-/*$<>"
    private val newLineAfter = listOf("do", "end")
    private val nNewLineAfter = intArrayOf(1, 2)
    private val newLineBefore = listOf("do", "end")
    private val nNewLineBefore = intArrayOf(1, 1)

    private fun whitespaceAfter(cur: Token): String {
        val after = cur.after
        return if (after == null) oldFile.substring(cur.end) else oldFile.substring(cur.end, after.start)
    }

    private fun tokenText(cur: Token): String = oldFile.substring(cur.start, cur.end)

    private fun processBeforeAndAfter(out: StringBuilder, cur: Token, indent: String) {
        var addedNewline = false

        if (noSpaceAfter.contains(cur.str)) {
            // no space after this token
        } else if (cur.after != null && noSpaceBefore.contains(cur.after!!.str)) {
            // no space before the next token
        } else {
            out.append(" ")
        }

        var index = newLineAfter.indexOf(cur.str)
        if (index >= 0) {
            val afterStr = cur.after?.str
            val offset = if (afterStr == "else" || afterStr == "end") 1 else 0
            repeat(nNewLineAfter[index] - offset) { out.append("\n").append(indent) }
            addedNewline = true
        }

        val after = cur.after
        if (after != null) {
            index = newLineBefore.indexOf(after.str)
            if (index >= 0) {
                repeat(nNewLineBefore[index]) { out.append("\n").append(indent) }
                addedNewline = true
            }
        }

        if (!addedNewline) {
            val ws = whitespaceAfter(cur)
            if (ws.contains("\n")) {
                out.append(ws.replace(" ", "").replace("\t", "").replace("\n", "\n$indent"))
            }
        }
    }

    /** All the code formatting logic is in here (same as the original's naming/comment). */
    fun format(): String {
        val out = StringBuilder()
        var indent = ""

        for (cur in tokens) {
            val after = cur.after
            out.append(tokenText(cur))

            if (cur.isRealHead()) indent += tabStr
            if (after != null && after.isRealEnd()) {
                indent = indent.substring(0, (indent.length - tabStr.length).coerceAtLeast(0))
            }

            when {
                !cur.inString && cur.commentDepth == 0 && !cur.inDate -> processBeforeAndAfter(out, cur, indent)
                cur.inString && cur.commentDepth == 0 && !cur.inDate -> {
                    if (after != null && after.inString) out.append(whitespaceAfter(cur))
                    else processBeforeAndAfter(out, cur, indent)
                }
                cur.commentDepth > 0 -> {
                    if (cur.isRealEnd() && cur.str == "]") processBeforeAndAfter(out, cur, indent)
                    else out.append(whitespaceAfter(cur))
                }
            }
        }

        return out.toString()
    }
}
