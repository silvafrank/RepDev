package org.repdev.engine.parser

import kotlin.test.Test
import kotlin.test.assertEquals

private val DB = DatabaseLayout.load(emptyList())

private fun tokenize(text: String): List<Token> {
    val tk = RepgenTokenizer(DB)
    tk.parse(text, 0, text.length, 0)
    return tk.tokens
}

private fun format(text: String, tabStr: String = "\t"): String =
    Formatter(text, tokenize(text), tabStr).format()

// Note: the original always appends a trailing space after the very last token (unless that
// token is itself in the no-space-after punctuation list) - `cur.getAfter() == null` falls
// through to the plain "add a space" default, same as any other token. Not a bug, preserved.
class FormatterTest {
    @Test
    fun `collapses extra horizontal whitespace between tokens to one space`() {
        assertEquals("x=1 ", format("x   =   1"))
    }

    @Test
    fun `no-space-before punctuation gets no space, comma still gets one after`() {
        assertEquals("x=1 ", format("x = 1"))
        assertEquals("a(1, 2) ", format("a ( 1 , 2 )"))
    }

    @Test
    fun `do forces a newline and one indent level for what follows`() {
        assertEquals("do \n\tx=1 ", format("do\n  x=1"))
    }

    @Test
    fun `end forces two newlines and drops back one indent level`() {
        assertEquals("do \n\tx=1 \nend \n\n", format("do\n  x=1\nend"))
    }

    @Test
    fun `an else right after end suppresses one of end's two newlines`() {
        assertEquals("do \n\tx=1 \nend \nelse ", format("do\n  x=1\nend else"))
    }

    @Test
    fun `nested blocks indent one level per open do`() {
        assertEquals("do \n\t\n\tdo \n\t\tx=1 ", format("do\n do\n  x=1"))
    }

    @Test
    fun `existing line breaks outside the do end rule are preserved and reindented`() {
        // A blank-line separated pair of statements at top level keeps its own newline, just
        // stripped of horizontal whitespace and reindented (here, no indent to add).
        assertEquals("x=1 \n\ny=2 ", format("x=1\n\ny=2"))
    }

    @Test
    fun `whitespace inside a string is preserved exactly, untouched`() {
        assertEquals("\"a   b\" ", format("\"a   b\""))
    }

    @Test
    fun `whitespace inside a comment is preserved except before the closing bracket`() {
        assertEquals("[a   b] ", format("[a   b]"))
    }
}
