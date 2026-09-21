package org.repdev.engine.parser

import kotlin.test.Test
import kotlin.test.assertEquals

class LineSurrounderTest {
    @Test
    fun `wraps only the selected line range, leaves the rest untouched`() {
        val text = "one\ntwo\nthree\nfour"
        val result = LineSurrounder.surround(text, startLine = 1, endLine = 2, start = "[", end = "]", escapeBadChars = false)
        assertEquals("one\n[two]\n[three]\nfour", result)
    }

    @Test
    fun `single current line when start equals end`() {
        val text = "one\ntwo\nthree"
        val result = LineSurrounder.surround(text, startLine = 0, endLine = 0, start = "<", end = ">", escapeBadChars = false)
        assertEquals("<one>\ntwo\nthree", result)
    }

    @Test
    fun `escapes double quotes into CTRLCHR calls when requested`() {
        val text = "say \"hi\""
        val result = LineSurrounder.surround(text, startLine = 0, endLine = 0, start = "", end = "", escapeBadChars = true)
        assertEquals("say \"+CTRLCHR(34)+\"hi\"+CTRLCHR(34)+\"", result)
    }

    @Test
    fun `an end line past the last line is clamped`() {
        val text = "only"
        val result = LineSurrounder.surround(text, startLine = 0, endLine = 99, start = "[", end = "]", escapeBadChars = false)
        assertEquals("[only]", result)
    }
}
