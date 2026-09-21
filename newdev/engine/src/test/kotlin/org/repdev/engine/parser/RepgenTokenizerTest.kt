package org.repdev.engine.parser

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private val EMPTY_DB = DatabaseLayout.load(emptyList())

class RepgenTokenizerTest {
    @Test
    fun `full initial parse of a simple define block`() {
        val src = "define\n  foo = 1\nend\n"
        val tk = RepgenTokenizer(EMPTY_DB)
        tk.parse(src, 0, src.length, 0)

        val strs = tk.tokens.map { it.str }
        assertEquals(listOf("define", "foo", "=", "1", "end"), strs)
        assertTrue(tk.tokens[1].inDefs) // "foo" is inside the define block
        assertFalse(tk.tokens[4].inDefs) // "end" itself closes the block
    }

    @Test
    fun `string literal tokens are flagged inString between the quotes`() {
        val src = "\"abc\""
        val tk = RepgenTokenizer(EMPTY_DB)
        tk.parse(src, 0, src.length, 0)

        val strs = tk.tokens.map { it.str }
        assertEquals(listOf("\"", "abc", "\""), strs)
        assertFalse(tk.tokens[0].inString == true && tk.tokens[0].str != "\"") // sanity: no crash path
        assertTrue(tk.tokens[1].inString)
    }

    @Test
    fun `bracket comments increment and decrement comment depth`() {
        val src = "[ comment ] real"
        val tk = RepgenTokenizer(EMPTY_DB)
        tk.parse(src, 0, src.length, 0)

        val byStr = tk.tokens.associateBy { it.str }
        assertEquals(1, byStr["["]!!.commentDepth)
        assertEquals(1, byStr["comment"]!!.commentDepth)
        assertEquals(0, byStr["real"]!!.commentDepth)
    }

    @Test
    fun `colon-paren becomes a single token, trailing bare colon does not crash`() {
        val src = "foo:(1)"
        val tk = RepgenTokenizer(EMPTY_DB)
        tk.parse(src, 0, src.length, 0)
        assertEquals(listOf("foo", ":(", "1", ")"), tk.tokens.map { it.str })

        val trailing = "foo:"
        val tk2 = RepgenTokenizer(EMPTY_DB)
        tk2.parse(trailing, 0, trailing.length, 0) // must not throw
        assertEquals(listOf("foo", ":"), tk2.tokens.map { it.str })
    }

    @Test
    fun `db record and field names merge into single tokens`() {
        val db = DatabaseLayout.load(
            listOf(
                "***|ACCOUNT|Account Record",
                "ACCT:NUM|Account Number|1|4|10",
            ),
        )
        val src = "account acct:num"
        val tk = RepgenTokenizer(db)
        tk.parse(src, 0, src.length, 0)

        assertEquals(listOf("account", "acct:num"), tk.tokens.map { it.str })
    }

    @Test
    fun `incremental edit only re-lexes the affected range, leaving untouched tokens' identity intact`() {
        val src = "define\nend\n" // "define" spans [0,6), "end" spans [7,10)
        val tk = RepgenTokenizer(EMPTY_DB)
        tk.parse(src, 0, src.length, 0)
        val originalDefineToken = tk.tokens[0]

        // Insert a char right at the end of "end" (index 10) — only the "end"
        // token's range is touched, "define" lies entirely before it.
        val edited = "define\nendx\n"
        tk.parse(edited, 10, 11, 10)

        assertTrue(tk.tokens[0] === originalDefineToken) // untouched token identity preserved
        assertEquals("endx", tk.tokens.last().str)
    }
}
