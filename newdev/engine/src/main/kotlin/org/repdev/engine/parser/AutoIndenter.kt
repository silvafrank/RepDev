package org.repdev.engine.parser

/**
 * Pure port of `EditorComposite.computeAutoIndent` — the indent computed for the new line created
 * by pressing Enter. RepGen has no required indentation; this app's convention is one level per
 * still-open block (DO/END, DEFINE/END, PROCEDURE/END, SELECT/END, SORT/END, parens, brackets —
 * [Token.isRealHead]/[Token.isRealEnd]'s pairs), plus a one-line bump after a single-statement
 * IF/ELSEIF...THEN or ELSE, which has no closing token of its own so the bump must revert on the
 * very next line rather than persist.
 *
 * The indent unit isn't assumed fixed: it's measured off the current line against its nearest open
 * block, so a function already using two-space bodies keeps getting two-space bodies instead of
 * being forced to a single global default ([tabStr]).
 *
 * Dropped vs. the original: the fold view/model offset translation
 * (`folding.viewToModel`/`modelToView`). The Compose editor never physically hides folded text
 * (see FoldingModel.kt's design note), so [caretOffset] is always already a model offset — there's
 * no second coordinate space to translate through. Also dropped: the `parser == null || !doParse`
 * early-out for non-RepGen files (help/letter) — that's a "should this even be called" decision
 * the UI layer owns; passing an empty [tokens] list produces the same verbatim-copy fallback.
 */
object AutoIndenter {
    fun computeIndent(tokens: List<Token>, text: String, caretOffset: Int, tabStr: String): String {
        val lines = text.split("\n")
        val (curLineIdx, _) = lineColAt(text, caretOffset)
        val curLineIndent = leadingWhitespace(lines.getOrElse(curLineIdx) { "" })

        if (tokens.isEmpty()) return curLineIndent

        val openHeads = ArrayDeque<Token>()
        var lastReal: Token? = null
        for (t in tokens) {
            if (t.start >= caretOffset) break
            if (t.isRealHead()) openHeads.addLast(t)
            else if (t.isRealEnd() && openHeads.isNotEmpty()) openHeads.removeLast()
            if (t.commentDepth == 0 && !t.inString && !t.inDate) lastReal = t
        }

        var unit = tabStr
        if (openHeads.isNotEmpty()) {
            val (headLineIdx, _) = lineColAt(text, openHeads.last().start)
            val headIndent = leadingWhitespace(lines.getOrElse(headLineIdx) { "" })
            if (curLineIndent.length > headIndent.length) unit = curLineIndent.substring(headIndent.length)
        }

        val indent = StringBuilder()
        repeat(openHeads.size) { indent.append(unit) }

        if (lastReal != null && (lastReal.str == "then" || lastReal.str == "else")) indent.append(unit)

        return indent.toString()
    }

    private fun leadingWhitespace(line: String): String {
        var n = 0
        while (n < line.length && (line[n] == ' ' || line[n] == '\t')) n++
        return line.substring(0, n)
    }
}
