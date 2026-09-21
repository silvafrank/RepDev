package org.repdev.engine.parser

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private val NO_KEYWORDS = KeywordLayout.load(emptyList())
private val NO_SPECIAL_VARS = SpecialVariables.load(emptyList())

/** A plain non-comment/string/date/defs usage token named [name] at [pos]. */
private fun usageToken(name: String, pos: Int) =
    Token(name, pos, commentDepth = 0, afterDepth = 0, inString = false, afterString = false, inDefs = false, inDate = false, afterDate = false)

class RepgenDiagnosticsTest {
    @Test
    fun `variable declared twice in the same file gets one warning per declaration`() {
        val vars = listOf(Variable("x", "a.pro", 5, "NUMBER"), Variable("x", "a.pro", 10, "NUMBER"))
        val source = "0123456789ABCDEF" // no newlines, so offset == column

        val diags = RepgenDiagnostics.findDuplicateVariables(vars, source, "a.pro")

        assertEquals(2, diags.size)
        assertEquals(listOf(1, 1), diags.map { it.line })
        assertEquals(listOf(6, 11), diags.map { it.col })
        assertTrue(diags.all { it.description.contains("X") })
    }

    @Test
    fun `variable declared once produces no warning`() {
        val vars = listOf(Variable("x", "a.pro", 0, "NUMBER"))
        assertTrue(RepgenDiagnostics.findDuplicateVariables(vars, "x", "a.pro").isEmpty())
    }

    @Test
    fun `unused variable is flagged, one referenced in its own tokens is not`() {
        val vars = listOf(Variable("used", "a.pro", 0, "NUMBER"), Variable("unused", "a.pro", 5, "NUMBER"))
        val ownTokens = listOf(usageToken("used", 20))

        val diags = RepgenDiagnostics.findUnusedVariables(
            vars, ownTokens, includeTokens = emptyList(), keywords = NO_KEYWORDS, specialVars = NO_SPECIAL_VARS,
            hiddenUsageText = emptyList(), canonicalSource = "0123456789ABCDEFGHIJ", fileName = "a.pro",
        )

        assertEquals(1, diags.size)
        assertTrue(diags[0].description.contains("UNUSED"))
    }

    @Test
    fun `variable referenced only in an include file's tokens is not unused`() {
        val vars = listOf(Variable("shared", "a.pro", 0, "NUMBER"))
        val diags = RepgenDiagnostics.findUnusedVariables(
            vars, ownTokens = emptyList(), includeTokens = listOf(listOf(usageToken("shared", 0))),
            keywords = NO_KEYWORDS, specialVars = NO_SPECIAL_VARS, hiddenUsageText = emptyList(),
            canonicalSource = "x", fileName = "a.pro",
        )
        assertTrue(diags.isEmpty())
    }

    @Test
    fun `variable referenced only inside a collapsed fold falls back to hidden usage text`() {
        val vars = listOf(Variable("folded", "a.pro", 0, "NUMBER"))
        val diags = RepgenDiagnostics.findUnusedVariables(
            vars, ownTokens = emptyList(), includeTokens = emptyList(), keywords = NO_KEYWORDS,
            specialVars = NO_SPECIAL_VARS, hiddenUsageText = listOf("total = folded + 1"),
            canonicalSource = "x", fileName = "a.pro",
        )
        assertTrue(diags.isEmpty())
    }

    @Test
    fun `a token that is only a keyword or special var doesn't count as a usage`() {
        val vars = listOf(Variable("define", "a.pro", 0, "NUMBER"))
        val keywords = KeywordLayout.load(listOf("DEFINE|desc|example"))
        val diags = RepgenDiagnostics.findUnusedVariables(
            vars, ownTokens = listOf(usageToken("define", 0)), includeTokens = emptyList(), keywords = keywords,
            specialVars = NO_SPECIAL_VARS, hiddenUsageText = emptyList(), canonicalSource = "x", fileName = "a.pro",
        )
        assertEquals(1, diags.size) // the keyword occurrence doesn't count as a real usage of the variable
    }
}
