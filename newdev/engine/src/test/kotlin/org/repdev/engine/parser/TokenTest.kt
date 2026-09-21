package org.repdev.engine.parser

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private val FIXTURE = listOf(
    "***|ACCOUNT|Account Record",
    "ACCT:NUM|Account Number|1|4|10",
)

private fun tok(str: String, inString: Boolean = false, commentDepth: Int = 0) =
    Token(str, pos = 0, commentDepth = commentDepth, afterDepth = commentDepth, inString = inString, afterString = inString, inDefs = false, inDate = false, afterDate = false)

class TokenTest {
    @Test
    fun `a close-quote is a real end only if the token before it was already in the string`() {
        val open = tok("\"", inString = false)
        val inner = tok("abc", inString = true)
        val close = tok("\"", inString = true)
        val chain = listOf(open, inner, close)
        chain.forEachIndexed { i, t -> t.setNearTokens(chain, i) }

        assertTrue(open.isRealHead()) // not in a string yet, so the string-guard doesn't block it
        assertFalse(inner.isRealEnd()) // "abc" isn't even an end token
        assertTrue(close.isRealEnd()) // in a string, and the token before it was too
    }

    @Test
    fun `plain end tokens outside a comment are real ends`() {
        val end = tok("end")
        assertTrue(end.isEnd())
        assertTrue(end.isRealEnd())
    }

    @Test
    fun `tokens inside a comment are not real heads or ends`() {
        val head = tok("(", commentDepth = 1)
        assertTrue(head.isHead())
        assertFalse(head.isRealHead()) // commentDepth != 0 and it's not the "[" special case
    }

    @Test
    fun `field validity checks against a real DatabaseLayout`() {
        val db = DatabaseLayout.load(FIXTURE)
        val record = tok("ACCOUNT")
        val colon = tok(":")
        val field = tok("ACCT:NUM")
        val chain = listOf(record, colon, field)
        chain.forEachIndexed { i, t -> t.setNearTokens(chain, i) }

        assertTrue(field.dbFieldValid(db))
        assertFalse(tok("NOPE").dbRecordValid(db))
    }

    @Test
    fun `dbFieldValid is false, not a crash, when there's no token before it`() {
        assertFalse(tok("ACCT:NUM").dbFieldValid(DatabaseLayout.load(FIXTURE)))
    }
}
