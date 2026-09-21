package org.repdev.engine.parser

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ParserDataTypesTest {
    @Test
    fun `Keyword sanitizes name and null-safes description and example`() {
        val kw = Keyword("  end  ", null, null)
        assertEquals("END", kw.name)
        assertEquals("", kw.description)
        assertEquals("", kw.example)
    }

    @Test
    fun `Variable equals and hashCode are name-only, matching duplicate-detection semantics`() {
        val a = Variable("FOO", "file1.rg", 10, "number")
        val b = Variable("FOO", "file2.rg", 99, "character(10)")
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
        assertFalse(Variable("BAR", "file1.rg", 0, "number") == a)
    }

    @Test
    fun `Variable copy constructor and incPos`() {
        val original = Variable("FOO", "file1.rg", 10, "number")
        original.constant = true
        val copy = Variable(original)
        assertEquals(original.name, copy.name)
        assertEquals(original.constant, copy.constant)
        copy.incPos(5)
        assertEquals(15, copy.pos)
        assertEquals(10, original.pos) // copy is independent
    }

    @Test
    fun `SectionInfo defaults and toString`() {
        val blank = SectionInfo()
        assertEquals(-1, blank.pos)
        val s = SectionInfo("PROCEDURE", 0, 12, 40)
        assertEquals("PROCEDURE:0:12:40", s.toString())
    }
}
