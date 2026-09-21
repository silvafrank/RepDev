package org.repdev.engine.parser

/**
 * Pure port of the block-matching half of `EditorComposite.handleCaretChange` — given the token the
 * caret sits on, finds its matching head or end token (the pair an editor would highlight, e.g.
 * caret on `do` finds its `end`). Everything else in that method (snippet-mode caret tracking,
 * `Section`-list refresh, redraw-range bookkeeping, `Token.specialBackground` painting) is editor
 * display state that doesn't belong here — see Token.kt's note on why `specialBackground` itself
 * was dropped from the port entirely.
 *
 * Deliberately uses [Token.isHead]/[Token.isEnd] plus the same hand-written comment/date/string
 * depth guards the original had inline, NOT [Token.isRealHead]/[isRealEnd] — those two ask "is this
 * token unambiguously real," this asks "is this token real *or* the specific opening/closing
 * quote/bracket that pairs with what's already on the matching stack," which needs the stack's
 * current top to decide (a `"` deeper in a string doesn't count as another real quote, but the
 * *closing* `"` does, judged against what opened it) — ported as-is, not simplified to the other
 * helper, since the two ask genuinely different questions.
 */
object BlockMatcher {
    /** Index of [caretTokenIndex]'s matching head/end token in [tokens], or null if unmatched or not a head/end at all. */
    fun findMatch(tokens: List<Token>, caretTokenIndex: Int): Int? {
        val cur = tokens.getOrNull(caretTokenIndex) ?: return null
        return when {
            cur.isRealHead() -> findForward(tokens, caretTokenIndex + 1, cur)
            cur.isRealEnd() -> findBackward(tokens, caretTokenIndex - 1, cur)
            else -> null
        }
    }

    private fun findForward(tokens: List<Token>, from: Int, opener: Token): Int? {
        val stack = ArrayDeque<Token>()
        stack.addLast(opener)
        var i = from
        while (i < tokens.size) {
            val t = tokens[i]
            if (t.isHead() && (t.commentDepth == 0 || t.str == "[") &&
                ((!t.inDate || t.str == "'") && stack.isEmpty() || stack.last().str != "'") &&
                ((!t.inString || t.str == "\"") && stack.isEmpty() || stack.last().str != "\"")
            ) {
                stack.addLast(t)
            } else if (t.isEnd() && (t.commentDepth == 0 || t.str == "]") &&
                (!t.inDate || t.str == "'") && (!t.inString || t.str == "\"") && stack.isNotEmpty()
            ) {
                stack.removeLast()
            }
            if (stack.isEmpty()) return i
            i++
        }
        return null
    }

    private fun findBackward(tokens: List<Token>, from: Int, closer: Token): Int? {
        val stack = ArrayDeque<Token>()
        stack.addLast(closer)
        var i = from
        while (i >= 0) {
            val t = tokens[i]
            if (t.isEnd() && (t.commentDepth == 0 || t.str == "]") &&
                ((!t.inDate || t.str == "'") && stack.isEmpty() || stack.last().str != "'") &&
                ((!t.inString || t.str == "\"") && stack.isEmpty() || stack.last().str != "\"")
            ) {
                stack.addLast(t)
            } else if (t.isHead() && (t.commentDepth == 0 || t.str == "[") &&
                (!t.inDate || t.str == "'") && (!t.inString || t.str == "\"") && stack.isNotEmpty()
            ) {
                stack.removeLast()
            }
            if (stack.isEmpty()) return i
            i--
        }
        return null
    }
}
