package org.repdev.engine.parser

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private val FIXTURE = listOf(
    "DO|Begin a block|DO ... END",
    "END",
)

class KeywordLayoutTest {
    @Test
    fun `parses keyword lines and bareword lines`() {
        val layout = KeywordLayout.load(FIXTURE)
        assertTrue(layout.contains("do"))
        assertTrue(layout.contains("END"))
        assertFalse(layout.contains("nope"))
    }
}
