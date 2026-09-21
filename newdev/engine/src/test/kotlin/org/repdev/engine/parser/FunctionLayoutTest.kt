package org.repdev.engine.parser

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private val FIXTURE = listOf(
    "ACOS|Arc cosine of a number|FLOAT",
    "\tNUM|the number|FLOAT,NUMBER",
    "TODAY|Today's date|DATE",
    "BAREWORD",
)

class FunctionLayoutTest {
    @Test
    fun `parses functions with typed arguments`() {
        val layout = FunctionLayout.load(FIXTURE)
        val acos = layout.list.first { it.name == "ACOS" }
        assertEquals(listOf(VariableType.FLOAT), acos.returnTypes)
        assertEquals(1, acos.arguments.size)
        assertEquals("NUM", acos.arguments[0].shortName)
        assertEquals(listOf(VariableType.FLOAT, VariableType.NUMBER), acos.arguments[0].types)
    }

    @Test
    fun `bareword lines become a function with no description or types`() {
        val layout = FunctionLayout.load(FIXTURE)
        val bare = layout.list.first { it.name == "BAREWORD" }
        assertEquals("", bare.description)
        assertTrue(bare.returnTypes.isEmpty())
    }

    @Test
    fun `containsName is case-insensitive`() {
        val layout = FunctionLayout.load(FIXTURE)
        assertTrue(layout.containsName("today"))
        assertFalse(layout.containsName("nope"))
    }
}
