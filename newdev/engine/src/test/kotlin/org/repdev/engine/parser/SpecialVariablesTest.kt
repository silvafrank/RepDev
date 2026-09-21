package org.repdev.engine.parser

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private val FIXTURE = listOf(
    "TODAY|Today's date|DATE|",
    "SSN|Social security number|CHARACTER|9",
)

class SpecialVariablesTest {
    @Test
    fun `parses vars and defaults missing len to -1`() {
        val vars = SpecialVariables.load(FIXTURE)
        val today = vars.vars.first { it.name == "TODAY" }
        assertEquals(-1, today.len)
        val ssn = vars.vars.first { it.name == "SSN" }
        assertEquals(9, ssn.len)
    }

    @Test
    fun `contains is case-insensitive`() {
        val vars = SpecialVariables.load(FIXTURE)
        assertTrue(vars.contains("today"))
        assertFalse(vars.contains("nope"))
    }
}
