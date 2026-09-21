package org.repdev.engine.parser

/** A foldable head/end block, by 0-based line number in the source text that produced it. */
data class FoldRange(val headerLine: Int, val endLine: Int, val endOffset: Int, val bracket: Boolean)

/**
 * Pure-logic port of the block-detection half of `com.repdev.FoldingManager`. What doesn't port:
 * everything downstream of "the StyledText buffer physically loses the hidden lines" — the
 * model/view coordinate split (`viewToModel`/`modelToView`/`getUnfoldedText`), the batch-collapse
 * bookkeeping (`preBatchHiddenTexts`, orphan-head/closer skipping), and undo-replay hooks all
 * existed solely to keep that physical deletion consistent with the parser and the caret.
 *
 * The Compose editor never deletes anything: `openFile.content` (see app-desktop's `Main.kt`) is
 * always the complete canonical text, and a fold only changes what gets *painted* — the same
 * `visualTransformation`/`OffsetMapping` mechanism `EditorPane` already uses for syntax coloring.
 * That collapses the whole "two coordinate spaces kept in sync by hand" problem the original
 * solved with ~500 lines of bookkeeping down to "recompute the folded view from scratch on every
 * recomposition," which is exactly the same call already made for tokenizing/highlighting.
 *
 * UI wiring (gutter triangle/plusminus painting, guide lines, click-to-toggle, indent guides) is
 * not part of this file — `EditorPane` has no gutter to click yet. Next step once it does.
 */
object FoldingModel {
    /**
     * Straight port of `FoldingManager.recomputeRanges`'s stack-based head/end matching (same
     * token filters: string/date/paren openers and closers don't produce block folds). Dropped:
     * the "is this head/end inside an already-folded region" skip — nothing here is ever hidden
     * from the tokenizer, since folding never touches the underlying text.
     */
    fun computeFoldableRanges(text: String, tokens: List<Token>): List<FoldRange> {
        val lineStarts = lineStartOffsets(text)
        val stack = ArrayDeque<Int>()
        val result = mutableListOf<FoldRange>()

        for (i in tokens.indices) {
            val t = tokens[i]
            val s = t.str
            if (t.isRealHead() && s != "\"" && s != "'" && s != "(" && s != ":(") {
                stack.addLast(i)
            } else if (t.isRealEnd() && s != ")" && s != "\"" && s != "'") {
                if (stack.isEmpty()) continue
                val head = tokens[stack.removeLast()]
                val headerLine = lineStarts.lineAt(head.start)
                val endLine = lineStarts.lineAt(t.start)
                if (endLine - headerLine >= 1) {
                    result.add(FoldRange(headerLine, endLine, t.start, bracket = head.str == "["))
                }
            }
        }
        return result
    }

    /**
     * The folded view of [text]: every line in `(headerLine, endLine]` for each range in
     * [collapsedHeaderLines] is hidden, replaced by a single placeholder appended to the header
     * line. Returns the display text plus a view-line→model-line lookup (`displayLineToModelLine`)
     * so a caller can still map a click on the folded view back to a real source line — the same
     * purpose `modelLineToViewLine`/`viewLineToModelLine` served, just computed fresh instead of
     * incrementally maintained.
     *
     * Ranges are looked up by header line from [ranges]; a header line not present there (stale
     * fold state after an edit removed the block) is silently skipped rather than throwing —
     * callers own reconciling fold state with the current [ranges] list.
     */
    fun collapsedView(text: String, ranges: List<FoldRange>, collapsedHeaderLines: Set<Int>, placeholder: String = " …"): FoldedView {
        val lines = text.split("\n")
        val byHeader = ranges.associateBy { it.headerLine }
        val visibleLines = mutableListOf<String>()
        val displayLineToModelLine = mutableListOf<Int>()

        var modelLine = 0
        while (modelLine < lines.size) {
            val range = if (modelLine in collapsedHeaderLines) byHeader[modelLine] else null
            if (range != null && range.endLine < lines.size) {
                visibleLines.add(lines[modelLine] + placeholder)
                displayLineToModelLine.add(modelLine)
                modelLine = range.endLine + 1
            } else {
                visibleLines.add(lines[modelLine])
                displayLineToModelLine.add(modelLine)
                modelLine++
            }
        }
        return FoldedView(visibleLines.joinToString("\n"), displayLineToModelLine)
    }

    private fun lineStartOffsets(text: String): IntArray {
        val starts = mutableListOf(0)
        for (i in text.indices) if (text[i] == '\n') starts.add(i + 1)
        return starts.toIntArray()
    }

    /** Index of the last line-start offset <= [offset] — i.e. which line [offset] falls on. */
    private fun IntArray.lineAt(offset: Int): Int {
        var lo = 0
        var hi = size - 1
        var line = 0
        while (lo <= hi) {
            val mid = (lo + hi) / 2
            if (this[mid] <= offset) { line = mid; lo = mid + 1 } else hi = mid - 1
        }
        return line
    }
}

data class FoldedView(val text: String, val displayLineToModelLine: List<Int>)
