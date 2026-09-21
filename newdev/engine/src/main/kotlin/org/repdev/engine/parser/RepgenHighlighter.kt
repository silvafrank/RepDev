package org.repdev.engine.parser

/**
 * Semantic classification of a token for editor coloring — mirrors the EStyle categories in
 * com.repdev.SyntaxHighlighter. Color is a separate concern (see [RepgenTheme]); this only says
 * *what a token is*, the same split RepgenTokenizer already draws between tokenizing and painting.
 */
enum class HighlightCategory {
    NORMAL, COMMENT, TASK, STRING, DATE, NUMBER,
    STRUCT1, STRUCT1_INVALID, STRUCT2, STRUCT2_INVALID,
    FUNCTION, KEYWORD, VARIABLE,
}

data class HighlightSpan(val start: Int, val length: Int, val category: HighlightCategory)

/**
 * Pure-logic port of SyntaxHighlighter.getStyleAtView's classification tree (same branch order,
 * same rules). Framework-independent on purpose — no SWT StyleRange, no Compose AnnotatedString —
 * so it's unit-testable without a live editor, same rationale as RepgenTokenizer.
 *
 * Dropped from the original: the `tok.getSpecialBackground()` overlay (live search-match/snippet-
 * field highlight paint). That's per-render UI state decided by whatever owns the caret/selection,
 * not part of the language model — same call Token.kt already made dropping `specialBackground`.
 */
object RepgenHighlighter {
    private val NUMBER_PATTERN = Regex("""\d+(\.\d+)?""")

    // Same list as RepgenParser.taskTokens, matched via a plain `in` against Token.str — safe
    // as an exact lowercase comparison because RepgenTokenizer already lowercases every token's
    // `str`, so "TODO:", "Todo:", and "todo:" all reach this check as the same string.
    val TASK_TOKENS = setOf("todo", "fixme", "bug", "bugbug", "wtf", "bm", "bookmark", "test", "note")

    fun classify(
        tok: Token,
        db: DatabaseLayout,
        functions: FunctionLayout,
        keywords: KeywordLayout,
        specialVars: SpecialVariables,
        vars: VariableRegistry,
    ): HighlightCategory {
        val after = tok.after
        val before = tok.before

        if (tok.commentDepth != 0) {
            return if (tok.str in TASK_TOKENS && after?.str == ":") HighlightCategory.TASK else HighlightCategory.COMMENT
        }
        if (tok.inString) return HighlightCategory.STRING
        if (tok.inDate) return HighlightCategory.DATE
        if (NUMBER_PATTERN.matches(tok.str)) return HighlightCategory.NUMBER

        // Token right before a ":" is a DB record name; right after one is a DB field name.
        if (after?.str == ":") {
            return if (tok.dbRecordValid(db)) HighlightCategory.STRUCT1 else HighlightCategory.STRUCT1_INVALID
        }
        if (before?.str == ":") {
            return if (tok.dbFieldValid(db) || tok.dbFieldValidNoSubFld(db)) HighlightCategory.STRUCT2 else HighlightCategory.STRUCT2_INVALID
        }
        if (after?.str == "(" && functions.containsName(tok.str)) return HighlightCategory.FUNCTION
        if (keywords.contains(tok.str)) return HighlightCategory.KEYWORD
        if (specialVars.contains(tok.str)) return HighlightCategory.VARIABLE
        if (vars.hasVar(tok.str)) return HighlightCategory.VARIABLE

        return HighlightCategory.NORMAL
    }

    /** Classifies every token in [tokens]. Callers slice to the visible range themselves. */
    fun highlight(
        tokens: List<Token>,
        db: DatabaseLayout,
        functions: FunctionLayout,
        keywords: KeywordLayout,
        specialVars: SpecialVariables,
        vars: VariableRegistry,
    ): List<HighlightSpan> = tokens.map {
        HighlightSpan(it.start, it.length(), classify(it, db, functions, keywords, specialVars, vars))
    }
}
