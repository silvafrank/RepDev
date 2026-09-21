package org.repdev.engine.parser

import kotlin.test.Test
import kotlin.test.assertEquals

private val DB = DatabaseLayout.load(emptyList())

private fun tokenize(text: String): List<Token> {
    val tk = RepgenTokenizer(DB)
    tk.parse(text, 0, text.length, 0)
    return tk.tokens
}

class AutoIndenterTest {
    @Test
    fun `no tokens falls back to copying the current line's indent verbatim`() {
        val text = "  foo"
        assertEquals("  ", AutoIndenter.computeIndent(emptyList(), text, caretOffset = text.length, tabStr = "\t"))
    }

    @Test
    fun `one level of indent inside an open do block`() {
        val text = "do\n"
        val caret = text.length
        assertEquals("\t", AutoIndenter.computeIndent(tokenize(text), text, caret, tabStr = "\t"))
    }

    @Test
    fun `two levels of indent inside nested open blocks`() {
        val text = "do\n\tdo\n"
        val caret = text.length
        assertEquals("\t\t", AutoIndenter.computeIndent(tokenize(text), text, caret, tabStr = "\t"))
    }

    @Test
    fun `a closed block contributes no indent`() {
        val text = "do\nend\n"
        val caret = text.length
        assertEquals("", AutoIndenter.computeIndent(tokenize(text), text, caret, tabStr = "\t"))
    }

    @Test
    fun `then with no block bumps one extra level that reverts on the following line`() {
        val text = "if x then\n"
        val caret = text.length
        assertEquals("\t", AutoIndenter.computeIndent(tokenize(text), text, caret, tabStr = "\t"))
    }

    @Test
    fun `indent unit is measured off the current line, not the configured default`() {
        // Header line "do" has no indent, but the caret's own line is already indented two
        // spaces past it -- that two-space delta becomes the unit, not "\t".
        val text = "do\n  x=1"
        val caret = text.length
        assertEquals("  ", AutoIndenter.computeIndent(tokenize(text), text, caret, tabStr = "\t"))
    }
}
