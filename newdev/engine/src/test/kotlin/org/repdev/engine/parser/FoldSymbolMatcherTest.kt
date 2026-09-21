package org.repdev.engine.parser

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FoldSymbolMatcherTest {
    @Test
    fun `define header line detection`() {
        assertTrue(FoldSymbolMatcher.isDefineHeaderLine("define"))
        assertTrue(FoldSymbolMatcher.isDefineHeaderLine("define x=character(5)"))
        assertTrue(FoldSymbolMatcher.isDefineHeaderLine("define\tx=character(5)"))
        assertFalse(FoldSymbolMatcher.isDefineHeaderLine("defineit"))
        assertFalse(FoldSymbolMatcher.isDefineHeaderLine("do"))
    }

    @Test
    fun `procedure header matches only the exact symbol name`() {
        assertTrue(FoldSymbolMatcher.isProcedureHeaderForSymbol("procedure foo", "foo"))
        assertFalse(FoldSymbolMatcher.isProcedureHeaderForSymbol("procedure foobar", "foo"))
        assertFalse(FoldSymbolMatcher.isProcedureHeaderForSymbol("procedurefoo", "foo"))
        assertFalse(FoldSymbolMatcher.isProcedureHeaderForSymbol("do foo", "foo"))
        assertFalse(FoldSymbolMatcher.isProcedureHeaderForSymbol("procedure bar", "foo"))
    }

    @Test
    fun `assignment detection finds a whole-word match followed by equals`() {
        assertTrue(FoldSymbolMatcher.containsAssignmentOf("x=character(5)", "x"))
        assertTrue(FoldSymbolMatcher.containsAssignmentOf("x = character(5)", "x"))
        // "xy" is not "x": whole-word boundary must hold on both sides.
        assertFalse(FoldSymbolMatcher.containsAssignmentOf("xy=character(5)", "x"))
        // "x" appears but isn't followed by "=" (skipping whitespace) anywhere.
        assertFalse(FoldSymbolMatcher.containsAssignmentOf("y=x+1", "x"))
        assertTrue(FoldSymbolMatcher.containsAssignmentOf("y=1 x = 2", "x"))
    }
}
