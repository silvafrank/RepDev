package org.repdev.engine.parser

/**
 * Pure port of the local (non-Symitar) half of `RepgenParser.BackgroundSymitarErrorChecker` —
 * duplicate-declaration and unused-variable detection. Everything else in that inner class (the
 * server-side compile check via `SymitarSession.errorCheckRepGen`, and every SWT `Table`/`Display`
 * touch around it) is UI/session plumbing that doesn't belong in the engine — see DECISIONS.md §4.
 * These two checks are pure logic over already-parsed token/variable lists and port unchanged.
 */
object RepgenDiagnostics {
    /**
     * One row *per redundant declaration*, matching the original: a name declared 3 times across
     * (any) files produces 3 warning rows for the occurrences that live in [fileName], not 1.
     * `Variable.equals` is name-only (see Variable.kt), so this flags shadowing across files too,
     * same as the original's `var2.equals(var)` scan over the whole `lvars` list.
     */
    fun findDuplicateVariables(vars: List<Variable>, canonicalSource: String, fileName: String): List<RepgenError> {
        val result = mutableListOf<RepgenError>()
        for (v in vars) {
            if (v.filename != fileName) continue
            if (vars.count { it == v } <= 1) continue
            val (line, col) = lineColAt(canonicalSource, v.pos)
            result.add(RepgenError(fileName, "Duplicate variable name: ${v.name.uppercase()}", line + 1, col + 1, RepgenError.Type.WARNING))
        }
        return result
    }

    /**
     * A variable counts as used if its name appears as a non-keyword, non-special-var token
     * outside a DEFINE body / date literal / string literal / comment, in either [ownTokens] or
     * any of [includeTokens]' lists, or — fallback for a variable referenced only inside a
     * collapsed fold, which never makes it into [ownTokens] — inside [hiddenUsageText].
     */
    fun findUnusedVariables(
        vars: List<Variable>,
        ownTokens: List<Token>,
        includeTokens: Collection<List<Token>>,
        keywords: KeywordLayout,
        specialVars: SpecialVariables,
        hiddenUsageText: List<String>,
        canonicalSource: String,
        fileName: String,
    ): List<RepgenError> {
        fun isCandidate(tok: Token) =
            !tok.inDefs && !tok.inDate && !tok.inString && tok.commentDepth == 0 &&
                !keywords.contains(tok.str) && !specialVars.contains(tok.str)

        val result = mutableListOf<RepgenError>()
        for (v in vars) {
            if (v.filename != fileName) continue

            var used = ownTokens.any { it.str == v.name && isCandidate(it) }
            if (!used) used = includeTokens.any { tokens -> tokens.any { it.str == v.name && isCandidate(it) } }
            if (!used) {
                val nameLower = v.name.lowercase()
                used = hiddenUsageText.any { containsWord(it.lowercase(), nameLower) }
            }
            if (used) continue

            val (line, col) = lineColAt(canonicalSource, v.pos)
            result.add(RepgenError(v.filename, "Variable Unused: ${v.name.uppercase()}", line + 1, col + 1, RepgenError.Type.WARNING))
        }
        return result
    }
}
