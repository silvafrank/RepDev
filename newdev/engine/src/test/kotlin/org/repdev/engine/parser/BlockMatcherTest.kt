package org.repdev.engine.parser

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

private val DB = DatabaseLayout.load(emptyList())

private fun tokenize(text: String): List<Token> {
    val tk = RepgenTokenizer(DB)
    tk.parse(text, 0, text.length, 0)
    return tk.tokens
}

class BlockMatcherTest {
    @Test
    fun `caret on a head token finds its matching end, forward`() {
        val tokens = tokenize("do\nx=1\nend\n")
        val doIdx = tokens.indexOfFirst { it.str == "do" }
        val endIdx = tokens.indexOfFirst { it.str == "end" }

        assertEquals(endIdx, BlockMatcher.findMatch(tokens, doIdx))
    }

    @Test
    fun `caret on an end token finds its matching head, backward`() {
        val tokens = tokenize("do\nx=1\nend\n")
        val doIdx = tokens.indexOfFirst { it.str == "do" }
        val endIdx = tokens.indexOfFirst { it.str == "end" }

        assertEquals(doIdx, BlockMatcher.findMatch(tokens, endIdx))
    }

    @Test
    fun `nested blocks match to the correct pair, not the nearest end`() {
        val tokens = tokenize("do\n  do\n  end\nend\n")
        val outerDo = tokens.indexOfFirst { it.str == "do" }
        val innerDo = tokens.indexOfLast { it.str == "do" }
        val innerEnd = tokens.indexOfFirst { it.str == "end" }
        val outerEnd = tokens.indexOfLast { it.str == "end" }

        assertEquals(innerEnd, BlockMatcher.findMatch(tokens, innerDo))
        assertEquals(outerEnd, BlockMatcher.findMatch(tokens, outerDo))
    }

    @Test
    fun `an unmatched head returns null`() {
        val tokens = tokenize("do\nx=1\n")
        val doIdx = tokens.indexOfFirst { it.str == "do" }
        assertNull(BlockMatcher.findMatch(tokens, doIdx))
    }

    @Test
    fun `a token that's neither a head nor an end returns null`() {
        val tokens = tokenize("do\nx=1\nend\n")
        val xIdx = tokens.indexOfFirst { it.str == "x" }
        assertNull(BlockMatcher.findMatch(tokens, xIdx))
    }

    @Test
    fun `a quote inside a comment doesn't confuse the string-quote matcher`() {
        // The bracket-comment's own head/end ("[" / "]") is what should match here, not any
        // quote-like token that might appear inside the comment text.
        val tokens = tokenize("[ note: uses \"quotes\" sometimes ]\n")
        val openIdx = tokens.indexOfFirst { it.str == "[" }
        val closeIdx = tokens.indexOfFirst { it.str == "]" }

        assertEquals(closeIdx, BlockMatcher.findMatch(tokens, openIdx))
    }
}
