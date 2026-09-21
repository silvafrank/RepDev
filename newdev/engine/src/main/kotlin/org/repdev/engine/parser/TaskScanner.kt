package org.repdev.engine.parser

/**
 * Pure port of `RepgenParser`'s task-tag scan: a comment token (`commentDepth > 0`) matching one
 * of [RepgenHighlighter.TASK_TOKENS] (same set the syntax highlighter uses to color these),
 * immediately followed by a `:` token, starts a [Task] whose description runs from the tag to
 * the next newline or `]`, whichever comes first. Comment-scoping means a bare identifier named
 * e.g. `todo` in real code never triggers this — only `[todo: ...]`-style tags.
 *
 * [Task.line]/[Task.col] come straight out of `lineColAt`, i.e. 0-based — unlike [RepgenError]'s
 * 1-based ones, this is a straight port of the original's own inconsistency (`RepgenParser` adds
 * 1 for its Error rows but not for Task rows). Preserved rather than silently fixed since it's a
 * display quirk, not a crash.
 *
 * Whether a task with a blank description gets shown is left to the caller: the original scanned
 * and stored every match unconditionally, then skipped blank-description ones only in the
 * table-population loop (a rendering concern) — same split kept here.
 *
 * Preserved quirk: when a `]` closes the tag before the next newline, the description loses its
 * last real character (e.g. `[todo: fix thing]` → `"todo: fix thin"`, not `"...thing"`) — the
 * original computes `bracketPos = indexOf(']') - 1` and then slices with an *exclusive* end at
 * that offset, so it drops one character too many rather than exactly excluding `]`. Cosmetic,
 * not a crash, so it's carried over rather than silently corrected — see TaskScannerTest.
 */
object TaskScanner {
    fun findTasks(tokens: List<Token>, canonicalSource: String, fileName: String): List<Task> {
        val result = mutableListOf<Task>()
        for (tok in tokens) {
            if (tok.commentDepth <= 0) continue
            if (tok.str !in RepgenHighlighter.TASK_TOKENS) continue
            if (tok.after?.str != ":") continue

            val (line, col) = lineColAt(canonicalSource, tok.start)
            val type = when (tok.str) {
                "fixme" -> Task.Type.FIXME
                "bug", "bugbug" -> Task.Type.BUG
                "wtf" -> Task.Type.WTF
                "bm", "bookmark" -> Task.Type.BM
                "test" -> Task.Type.TEST
                "note" -> Task.Type.NOTE
                else -> Task.Type.TODO
            }

            val searchFrom = tok.start + tok.str.length
            val newlinePos = canonicalSource.indexOf('\n', searchFrom).let { if (it == -1) canonicalSource.length else it + 1 }
            val bracketPos = (canonicalSource.indexOf(']', searchFrom) - 1).let { if (it < 0) canonicalSource.length else it }
            val endOffset = minOf(newlinePos, bracketPos)

            val desc = if (endOffset - 1 <= tok.start || endOffset > canonicalSource.length) ""
                else canonicalSource.substring(tok.start, endOffset).trim().removeSuffix("]")

            result.add(Task(fileName = fileName, description = desc, line = line, col = col, type = type))
        }
        return result
    }
}
