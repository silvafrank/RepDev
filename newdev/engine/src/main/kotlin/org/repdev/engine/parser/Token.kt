package org.repdev.engine.parser

/**
 * Ported from com.repdev.parser.Token. Linking (before/after) and the
 * head/end/isRealHead/isRealEnd block-matching logic are preserved exactly
 * — see TokenTest.
 *
 * Dropped from the original: `specialBackground` (an SWT `Color`),
 * `backgroundReason`, and `currentVar` (a `SnippetVariable` back-reference).
 * Those are editor *display* state — which color to paint behind a token for
 * a live search-match/snippet-field highlight — not something the language
 * model should carry. The Compose editor looks that up itself from
 * cursor/selection state instead of the parser writing UI colors into its
 * own token stream.
 *
 * `dbFieldValid`/`dbFieldValidNoSubFld`/`dbRecordValid` take a
 * [DatabaseLayout] parameter instead of reaching for a process-wide
 * singleton (`DatabaseLayout.getInstance()` in the original — note the
 * original's `dbFieldValid(ArrayList<Record>)` didn't even use that
 * parameter, it reached for the singleton anyway).
 */
class Token(
    var str: String,
    var pos: Int,
    var commentDepth: Int,
    var afterDepth: Int,
    var inString: Boolean,
    var afterString: Boolean,
    var inDefs: Boolean,
    var inDate: Boolean,
    var afterDate: Boolean,
) {
    var before: Token? = null
        private set
    var after: Token? = null
        private set
    var tokenType: TokenType? = null

    enum class TokenType { CONSTANT, DEFINED_VARIABLE, PROCEDURE, SYMITAR_KEYWORD }

    fun setNearTokens(tokens: List<Token>?, myPos: Int) {
        before = tokens?.getOrNull(myPos - 1)
        after = tokens?.getOrNull(myPos + 1)
    }

    val start: Int get() = pos
    val end: Int get() = pos + str.length
    fun length(): Int = str.length

    fun incStart(amount: Int) {
        pos += amount
    }

    fun setCDepth(before: Int, after: Int) {
        commentDepth = before
        afterDepth = after
    }

    fun setInString(before: Boolean, after: Boolean) {
        inString = before
        afterString = after
    }

    fun setInDate(before: Boolean, after: Boolean) {
        inDate = before
        afterDate = after
    }

    /** Null-safe where the original NPEs on a missing `before.before` — see [org.repdev.engine.Config] for the same rationale. */
    fun dbFieldValid(db: DatabaseLayout): Boolean {
        val record = before?.before ?: return false
        if (!record.dbRecordValid(db)) return false
        return db.recordHasField(record.str, str)
    }

    fun dbFieldValidNoSubFld(db: DatabaseLayout): Boolean {
        val record = before?.before ?: return false
        if (!record.dbRecordValid(db)) return false

        val tmpField = str.split(":")
        val rec = db.getRecordByName(record.str) ?: return false

        for (field in rec.fields) {
            val tmp = field.name.lowercase().split(":")
            if (tmp.isNotEmpty() && tmp[0] == tmpField[0]) {
                if (tmpField.size > 1 && tmpField[1].startsWith("(") && tmpField[1].endsWith(")")) {
                    return true
                }
            }
        }
        return false
    }

    fun dbRecordValid(db: DatabaseLayout): Boolean = db.containsRecordName(str)

    override fun toString(): String = "$pos:$str($commentDepth,$inString,$inDefs)"

    fun isHead(): Boolean = str in HEADS
    fun isEnd(): Boolean = str in ENDS

    // The "real" variants also know about tokens that can be either the start or end of a
    // block, like quotes — a `"` only opens a string if we're not already in one.
    fun isRealHead(): Boolean =
        isHead() &&
            (commentDepth == 0 || (str == "[" && after?.let { it.commentDepth > 0 } == true)) &&
            (!inDate || (str == "'" && after?.inDate == true)) &&
            (!inString || (str == "\"" && after?.inString == true))

    fun isRealEnd(): Boolean =
        isEnd() &&
            (commentDepth == 0 || (str == "]" && before?.let { it.commentDepth > 0 } == true)) &&
            (!inDate || (str == "'" && before?.inDate == true)) &&
            (!inString || (str == "\"" && before?.inString == true))

    companion object {
        private val HEADS = setOf("setup", "print title", "select", "define", "do", "total", "headers", ":(", "(", "\"", "'", "[", "procedure", "sort")
        private val ENDS = setOf("end", ")", "\"", "'", "]")
    }
}
