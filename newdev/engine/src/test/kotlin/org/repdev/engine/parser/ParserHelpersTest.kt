package org.repdev.engine.parser

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private val EMPTY_DB = DatabaseLayout.load(emptyList())

class ParserHelpersTest {
    @Test
    fun `getFullString reconstructs the literal between quotes`() {
        val src = "\"hello world\""
        val tk = RepgenTokenizer(EMPTY_DB)
        tk.parse(src, 0, src.length, 0)
        val openQuote = tk.tokens.first { it.str == "\"" }
        assertEquals("hello world", getFullString(openQuote, src))
    }

    @Test
    fun `isNumber accepts integers only`() {
        assertTrue(isNumber("42"))
        assertFalse(isNumber("4.2"))
        assertFalse(isNumber("abc"))
    }

    @Test
    fun `lineColAt counts newlines before the offset`() {
        val text = "abc\ndef\nghi"
        assertEquals(0 to 0, lineColAt(text, 0))
        assertEquals(1 to 0, lineColAt(text, 4))
        assertEquals(1 to 2, lineColAt(text, 6))
    }

    @Test
    fun `containsWord requires non-alphanumeric boundaries`() {
        assertTrue(containsWord("the foo bar", "foo"))
        assertFalse(containsWord("the foobar", "foo"))
    }
}
