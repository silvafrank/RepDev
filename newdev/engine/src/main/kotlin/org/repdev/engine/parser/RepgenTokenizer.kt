package org.repdev.engine.parser

/**
 * Pure-logic port of com.repdev.parser.RepgenParser's tokenizer core:
 * `parse()` (the incremental char-by-char lexer plus DB-name token
 * merging) and `addToken`. See [getFullString], [isNumber], [lineColAt],
 * [containsWord] below for the other pure helpers, and [VariableRegistry]
 * for the `rebuildVars`/`lvars` port.
 *
 * Deliberately NOT ported here (SWT/session-coupled, deferred pending the
 * Compose editor + session layer — see MODERNIZATION_PLAN.md):
 *  - The StyledText redraw-range calculation and HiddenTextProvider
 *    view/model translation that lived at the end of the original
 *    `parse()` — that's "what to repaint," decided by whatever UI owns the
 *    text buffer, not the tokenizer.
 *  - `BackgroundIncludeParser` / `BackgroundSymitarErrorChecker` (`Thread`
 *    subclasses touching `RepDevMain.mainShell`'s SWT `Table`s and a live
 *    `SymitarSession`) — need redesigning as coroutines once
 *    `SymitarFile`/`SymitarSession` exist.
 *
 * Two dead parameters of the original `parse()` — `filename` and `vars` —
 * were never read inside its body; dropped here. One bug fixed: the
 * original's `chars[i+1]` lookahead for `:(` throws
 * `ArrayIndexOutOfBoundsException` when a file ends on a bare `:`;
 * bounds-checked here instead. Everything else, including a couple of odd
 * original quirks, is preserved as-is and called out inline.
 */
class RepgenTokenizer(private val db: DatabaseLayout) {
    val tokens: MutableList<Token> = mutableListOf()
    val lastTokens: MutableList<Token> = mutableListOf()
    val removedTokens: MutableList<Token> = mutableListOf()

    private fun addToken(spot: Int, tok: Token) {
        if (spot > 0 && tokens[spot - 1].str == "procedure" && !tok.inString && tok.commentDepth == 0) {
            tok.tokenType = Token.TokenType.PROCEDURE
        }
        if (spot > 0 && tok.str.equals("=", ignoreCase = true) && tok.inDefs && !tok.inString && tok.commentDepth == 0) {
            tokens[spot - 1].tokenType = Token.TokenType.DEFINED_VARIABLE
        }
        tokens.add(spot, tok)
        lastTokens.add(tok)
    }

    /**
     * Re-tokenizes the `[start, oldEnd)` range of the previous text against
     * the new text, where `str[start, end)` is the replacement (pass
     * `start=0, end=str.length, oldEnd=0` for a full initial parse). Returns
     * `true` if the whole file turned out to be a single DEFINE block
     * (`allDefs` in the original — kept for parity, no current caller reads it).
     */
    fun parse(str: String, start: Int, end: Int, oldEnd: Int): Boolean {
        var allDefs = true
        lastTokens.clear()
        removedTokens.clear()

        var ftoken = 0
        while (ftoken < tokens.size && tokens[ftoken].end < start) ftoken++

        var ltoken = ftoken
        while (ltoken < tokens.size && tokens[ltoken].start <= oldEnd) ltoken++

        val charStart = if (ftoken < tokens.size) minOf(start, tokens[ftoken].start) else start
        val charEnd = if (ltoken < tokens.size) maxOf(end, tokens[ltoken].start + end - oldEnd) else str.length

        val chars = str.substring(charStart, charEnd).lowercase().toCharArray()

        var inString = false
        var inDate = false
        var inDefine = false
        var commentDepth = 0
        if (ftoken > 0) {
            val prev = tokens[ftoken - 1]
            inString = prev.afterString
            inDate = prev.afterDate
            commentDepth = prev.afterDepth
            inDefine = prev.inDefs
        }

        // Captured before removal, per the original — this is the end-state the
        // re-lexed range must reconcile with once it catches up past `fixspot`.
        var oldInString = false
        var oldInDate = false
        var oldCommentDepth = 0
        var oldInDefine = false
        if (ltoken < tokens.size) {
            val t = tokens[ltoken]
            oldInString = t.inString
            oldInDate = t.inDate
            oldCommentDepth = t.commentDepth
            oldInDefine = t.inDefs
        }

        repeat(ltoken - ftoken) { removedTokens.add(tokens.removeAt(ftoken)) }

        var curspot = ftoken
        var cstart = charStart
        var sb = StringBuilder()
        var i = 0
        while (i < chars.size) {
            val cur = chars[i]

            if (cur in 'a'..'z' || cur in '0'..'9' || cur == '#' || cur == '@') {
                sb.append(cur)
            } else {
                val scur = sb.toString().trim()
                if (scur.isNotEmpty()) {
                    if (commentDepth == 0 && !inString) {
                        when (scur) {
                            "define" -> inDefine = true
                            "end" -> { inDefine = false; allDefs = false }
                        }
                    }
                    addToken(curspot, Token(scur, cstart, commentDepth, commentDepth, inString, inString, inDefine, inDate, inDate))
                    curspot++
                }
                sb = StringBuilder()
                cstart = i + charStart

                when {
                    commentDepth == 0 && cur == '"' -> {
                        addToken(curspot, Token("\"", cstart, 0, 0, true, !inString, inDefine, inDate, inDate))
                        curspot++
                        inString = !inString
                    }
                    !inString && cur == '[' -> {
                        commentDepth++
                        addToken(curspot, Token("[", cstart, commentDepth, commentDepth, false, false, inDefine, inDate, inDate))
                        curspot++
                    }
                    !inString && cur == ']' -> {
                        commentDepth--
                        if (commentDepth < 0) commentDepth = 0
                        addToken(curspot, Token("]", cstart, commentDepth + 1, commentDepth, false, false, inDefine, inDate, inDate))
                        curspot++
                    }
                    commentDepth == 0 && !inString && cur == '\'' -> {
                        addToken(curspot, Token("'", cstart, 0, 0, inString, inString, inDefine, true, !inDate))
                        curspot++
                        inDate = !inDate
                    }
                    cur == ':' -> {
                        // Fixed vs. original: bounds-check the ":(" lookahead instead of
                        // indexing chars[i+1] unconditionally (crashed on a trailing ":").
                        if (i + 1 < chars.size && chars[i + 1] == '(') {
                            addToken(curspot, Token(":(", cstart, commentDepth, commentDepth, inString, inString, inDefine, inDate, inDate))
                            i++
                            cstart++
                        } else {
                            addToken(curspot, Token(":", cstart, commentDepth, commentDepth, inString, inString, inDefine, inDate, inDate))
                        }
                        curspot++
                    }
                    !cur.isWhitespace() -> {
                        addToken(curspot, Token(cur.toString(), cstart, commentDepth, commentDepth, inString, inString, inDefine, inDate, inDate))
                        curspot++
                    }
                }
                cstart++
            }
            i++
        }

        val trailing = sb.toString().trim()
        if (trailing.isNotEmpty()) {
            if (commentDepth == 0 && !inString) {
                when (trailing) {
                    "define" -> inDefine = true
                    "end" -> { inDefine = false; allDefs = false }
                }
            }
            addToken(curspot, Token(trailing, cstart, commentDepth, commentDepth, inString, inString, inDefine, inDate, inDate))
            curspot++
        }

        if (end != oldEnd) {
            for (idx in curspot until tokens.size) tokens[idx].incStart(end - oldEnd)
        }

        var fixspot = curspot

        if (inString != oldInString || commentDepth != oldCommentDepth || inDefine != oldInDefine || inDate != oldInDate) {
            fixspot = curspot
            while (fixspot < tokens.size) {
                val tcur = tokens[fixspot]
                val cur = tcur.str

                oldInString = tcur.inString
                oldInDate = tcur.inDate
                oldCommentDepth = tcur.commentDepth
                oldInDefine = tcur.inDefs

                tcur.setInString(inString, inString)
                tcur.setInDate(inDate, inDate)
                tcur.setCDepth(commentDepth, commentDepth)
                tcur.inDefs = inDefine

                if (commentDepth == 0 && cur == "\"") {
                    tcur.setInString(true, !inString)
                    inString = !inString
                } else if (!inString && cur == "[") {
                    commentDepth++
                    tcur.setCDepth(commentDepth, commentDepth)
                } else if (!inString && cur == "]") {
                    commentDepth--
                    if (commentDepth < 0) commentDepth = 0
                    tcur.setCDepth(commentDepth + 1, commentDepth)
                } else if (!inString && commentDepth == 0 && cur == "define") {
                    inDefine = true
                    tcur.inDefs = true
                } else if (!inString && commentDepth == 0 && cur == "end") {
                    inDefine = false
                    tcur.inDefs = false
                } else if (!inString && commentDepth == 0 && cur == "'") {
                    tcur.setInDate(true, !inDate)
                    inDate = !inDate
                } else if (inDefine == oldInDefine && commentDepth == oldCommentDepth && inString == oldInString && inDate == oldInDate) {
                    break
                }
                fixspot++
            }
        }

        for (idx in curspot - 1 downTo 0) {
            if (!tokens[idx].inString && tokens[idx].commentDepth != 0) {
                tokens[idx].setNearTokens(tokens, idx)
                break
            }
        }

        for (idx in maxOf(0, ftoken - 1) until fixspot) {
            tokens[idx].setNearTokens(tokens, idx)
        }

        if (tokens.size > 1 && lastTokens.isNotEmpty()) {
            val first = lastTokens[0]
            val last = lastTokens[lastTokens.size - 1]

            first.before?.let { b ->
                lastTokens.add(0, b)
                b.before?.let { bb -> lastTokens.add(0, bb) }
            }
            // Ported as-is (latent bug in the original): this reads `first.after`
            // twice for trailing context instead of `last.after`. Left in place
            // for behavior parity; null-checked so it can't crash either way.
            if (last.after != null) {
                first.after?.let { a ->
                    lastTokens.add(a)
                    a.after?.let { aa -> lastTokens.add(aa) }
                }
            }

            var idx = 0
            while (idx < lastTokens.size - 1) {
                val cur = lastTokens[idx]
                val curAfter = cur.after
                if (curAfter == null) break

                val mergedTwo = cur.str + " " + curAfter.str
                if ((cur.str == "print" && curAfter.str == "title") ||
                    (db.containsRecordName(mergedTwo) && str.substring(cur.end, curAfter.start) == " ")
                ) {
                    cur.str = mergedTwo
                    tokens.remove(curAfter)
                    cur.setNearTokens(tokens, tokens.indexOf(cur))
                    cur.after?.setNearTokens(tokens, tokens.indexOf(cur.after))
                    continue
                }

                val afterAfter = curAfter.after
                if (afterAfter != null) {
                    if (db.containsFieldName(cur.str + ":" + afterAfter.str) && str.substring(cur.end, afterAfter.start) == ":") {
                        cur.str = cur.str + ":" + afterAfter.str
                        tokens.remove(curAfter)
                        tokens.remove(afterAfter)
                        cur.setNearTokens(tokens, tokens.indexOf(cur))
                        cur.after?.setNearTokens(tokens, tokens.indexOf(cur.after))
                        continue
                    }
                    val afterAfterAfter = afterAfter.after
                    if (afterAfterAfter != null && db.containsFieldName(cur.str + ":1") && curAfter.str == ":(" && afterAfterAfter.str == ")") {
                        cur.str = cur.str + ":(" + afterAfter.str + ")"
                        tokens.remove(curAfter)
                        tokens.remove(afterAfter)
                        tokens.remove(afterAfterAfter)
                        cur.setNearTokens(tokens, tokens.indexOf(cur))
                        cur.after?.setNearTokens(tokens, tokens.indexOf(cur.after))
                        continue
                    }
                }

                idx++
            }
        }

        return allDefs
    }
}

/** Reconstructs a string literal's full text, walking forward from the opening `"` — port of `getFullString`. */
fun getFullString(startQuote: Token, fileData: String): String {
    if (!startQuote.inString) return ""
    val fToken = startQuote.after ?: return ""

    var cur: Token? = startQuote
    var lToken: Token? = null
    while (true) {
        cur = cur?.after ?: break
        if (!cur.inString || cur.str == "\"") break
        lToken = cur
    }

    val endExclusive = minOf(lToken?.end ?: (fileData.length - 1), fileData.length - 1)
    return fileData.substring(fToken.start, endExclusive)
}

/** Port of `isNumber` — whether [str] parses as a plain integer. */
fun isNumber(str: String): Boolean = str.toIntOrNull() != null

/** Port of the static `lineColAt` — 0-based (line, col) for a character offset into [text]. */
fun lineColAt(text: String, offset: Int): Pair<Int, Int> {
    var line = 0
    var col = 0
    val max = minOf(offset, text.length)
    for (idx in 0 until max) {
        if (text[idx] == '\n') { line++; col = 0 } else col++
    }
    return line to col
}

/** Whole-word substring search (both neighbors non-alphanumeric) — port of `containsWord`. Pre-lowercase both inputs for case-insensitive use. */
fun containsWord(haystack: String, word: String): Boolean {
    if (word.isEmpty()) return false
    var from = 0
    while (true) {
        val idx = haystack.indexOf(word, from)
        if (idx < 0) return false
        val before = if (idx == 0) ' ' else haystack[idx - 1]
        val afterIdx = idx + word.length
        val after = if (afterIdx >= haystack.length) ' ' else haystack[afterIdx]
        if (!before.isLetterOrDigit() && !after.isLetterOrDigit()) return true
        from = idx + 1
    }
}
