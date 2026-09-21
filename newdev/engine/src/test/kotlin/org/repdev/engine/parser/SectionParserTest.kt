package org.repdev.engine.parser

import kotlin.test.Test
import kotlin.test.assertEquals

private fun tok(str: String, pos: Int) =
    Token(str, pos, commentDepth = 0, afterDepth = 0, inString = false, afterString = false, inDefs = false, inDate = false, afterDate = false)

class SectionParserTest {
    @Test
    fun `finds a simple define section`() {
        val text = "define\nend\n"
        val tokens = listOf(tok("define", 0), tok("end", 7))
        val sections = parseSections(tokens, text)

        assertEquals(1, sections.size)
        assertEquals(SectionInfo("define", 0, 7, 7), sections[0])
    }

    @Test
    fun `procedure section title comes from the token after 'procedure'`() {
        val text = "procedure MyProc\nend\n"
        val chain = listOf(tok("procedure", 0), tok("MyProc", 10), tok("end", 17))
        chain.forEachIndexed { i, t -> t.setNearTokens(chain, i) }

        val sections = parseSections(chain, text)

        assertEquals(1, sections.size)
        assertEquals(SectionInfo("MyProc", 0, 17, 17), sections[0])
    }

    @Test
    fun `nested do-end blocks inside a section do not close it early`() {
        val text = "define\ndo\nend\nend\n"
        val tokens = listOf(tok("define", 0), tok("do", 7), tok("end", 10), tok("end", 14))
        val sections = parseSections(tokens, text)

        assertEquals(1, sections.size)
        assertEquals(14, sections[0].lastInsertPos)
    }

    @Test
    fun `sectionAt finds the enclosing section, empty string otherwise`() {
        val sections = listOf(SectionInfo("define", 0, 7, 7))
        assertEquals("define", sectionAt(sections, 3))
        assertEquals("", sectionAt(sections, 100))
    }
}
