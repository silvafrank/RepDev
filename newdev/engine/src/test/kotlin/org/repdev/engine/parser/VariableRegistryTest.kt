package org.repdev.engine.parser

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private val EMPTY_DB = DatabaseLayout.load(emptyList())

class VariableRegistryTest {
    @Test
    fun `simple typed declaration inside define is a non-constant variable`() {
        val src = "define\n  foo = number\nend\n"
        val tk = RepgenTokenizer(EMPTY_DB)
        tk.parse(src, 0, src.length, 0)

        val reg = VariableRegistry()
        reg.rebuild("a.rg", src, tk.tokens)

        val v = reg.vars.first { it.name == "foo" }
        assertEquals("NUMBER", v.type)
        assertFalse(v.constant)
        assertTrue(reg.hasVar("foo"))
    }

    @Test
    fun `direct value initializer is a constant`() {
        val src = "define\n  foo = 5\nend\n"
        val tk = RepgenTokenizer(EMPTY_DB)
        tk.parse(src, 0, src.length, 0)

        val reg = VariableRegistry()
        reg.rebuild("a.rg", src, tk.tokens)

        val v = reg.vars.first { it.name == "foo" }
        assertTrue(v.constant)
    }

    @Test
    fun `character with size is a non-constant variable and type includes the size`() {
        val src = "define\n  foo = character(10)\nend\n"
        val tk = RepgenTokenizer(EMPTY_DB)
        tk.parse(src, 0, src.length, 0)

        val reg = VariableRegistry()
        reg.rebuild("a.rg", src, tk.tokens)

        val v = reg.vars.first { it.name == "foo" }
        assertFalse(v.constant)
        assertTrue(v.type.contains("10"))
    }

    @Test
    fun `rebuild replaces only vars for the given filename and reports changed`() {
        val src = "define\n  foo = number\nend\n"
        val tk = RepgenTokenizer(EMPTY_DB)
        tk.parse(src, 0, src.length, 0)

        val reg = VariableRegistry()
        assertTrue(reg.rebuild("a.rg", src, tk.tokens))
        assertFalse(reg.rebuild("a.rg", src, tk.tokens)) // same vars again -> unchanged

        val otherSrc = "define\n  bar = number\nend\n"
        val tk2 = RepgenTokenizer(EMPTY_DB)
        tk2.parse(otherSrc, 0, otherSrc.length, 0)
        reg.rebuild("b.rg", otherSrc, tk2.tokens)

        assertTrue(reg.vars.any { it.name == "foo" && it.filename == "a.rg" })
        assertTrue(reg.vars.any { it.name == "bar" && it.filename == "b.rg" })
    }
}
