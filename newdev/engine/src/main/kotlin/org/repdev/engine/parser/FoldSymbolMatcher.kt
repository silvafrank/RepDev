package org.repdev.engine.parser

/**
 * Pure string predicates from `EditorComposite`'s "jump to definition inside a folded region"
 * fallback (`expandFoldContainingSymbol` and its three line-classifying helpers). These decide
 * *whether* a folded block's header/body defines a given symbol; actually walking the fold list,
 * toggling fold state, and moving the caret stays in the UI/editor layer — this is only the text
 * matching those decisions are based on.
 */
object FoldSymbolMatcher {
    /** True if [trimmedLowerLine] (already trim()+lowercase()'d by the caller) starts the DEFINE keyword. */
    fun isDefineHeaderLine(trimmedLowerLine: String): Boolean =
        trimmedLowerLine == "define" || trimmedLowerLine.startsWith("define ") || trimmedLowerLine.startsWith("define\t")

    /** True if [trimmedLowerLine] is `PROCEDURE <lowerSymbol>` (lowered on both sides by the caller). */
    fun isProcedureHeaderForSymbol(trimmedLowerLine: String, lowerSymbol: String): Boolean {
        if (!trimmedLowerLine.startsWith("procedure")) return false
        val kwEnd = "procedure".length
        if (kwEnd >= trimmedLowerLine.length) return false
        if (trimmedLowerLine[kwEnd].isLetterOrDigit()) return false
        val rest = trimmedLowerLine.substring(kwEnd).trim()
        if (!rest.startsWith(lowerSymbol)) return false
        val after = lowerSymbol.length
        if (after >= rest.length) return true
        return !rest[after].isLetterOrDigit()
    }

    /**
     * True if [word] appears in [haystack] as a whole word immediately followed (skipping
     * spaces/tabs) by `=` — the shape of a RepGen variable assignment/definition. Both inputs must
     * already be lowercased by the caller.
     */
    fun containsAssignmentOf(haystack: String, word: String): Boolean {
        var from = 0
        while (true) {
            val idx = haystack.indexOf(word, from)
            if (idx < 0) return false
            val before = if (idx == 0) ' ' else haystack[idx - 1]
            if (before.isLetterOrDigit()) { from = idx + 1; continue }
            val afterIdx = idx + word.length
            val after = if (afterIdx >= haystack.length) ' ' else haystack[afterIdx]
            if (after.isLetterOrDigit()) { from = idx + 1; continue }
            var p = afterIdx
            while (p < haystack.length) {
                val c = haystack[p]
                if (c == ' ' || c == '\t') { p++; continue }
                if (c == '=') return true
                break
            }
            from = idx + 1
        }
    }
}
