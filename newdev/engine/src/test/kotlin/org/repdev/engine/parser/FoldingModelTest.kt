package org.repdev.engine.parser

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private val DB = DatabaseLayout.load(emptyList())

private fun tokenize(text: String): List<Token> {
    val tk = RepgenTokenizer(DB)
    tk.parse(text, 0, text.length, 0)
    return tk.tokens
}

class FoldingModelTest {
    @Test
    fun `multi-line define end block is foldable`() {
        val text = "define\n  foo = 1\nend\n"
        val ranges = FoldingModel.computeFoldableRanges(text, tokenize(text))
        assertEquals(1, ranges.size)
        assertEquals(FoldRange(headerLine = 0, endLine = 2, endOffset = ranges[0].endOffset, bracket = false), ranges[0])
    }

    @Test
    fun `single-line block is not foldable`() {
        // "do" / "end" on the same line — endLine - headerLine must be >= 1 to fold.
        val text = "do end\n"
        val ranges = FoldingModel.computeFoldableRanges(text, tokenize(text))
        assertTrue(ranges.isEmpty())
    }

    @Test
    fun `bracket comment spanning lines is foldable and flagged as bracket`() {
        val text = "[\n  a comment\n]\n"
        val ranges = FoldingModel.computeFoldableRanges(text, tokenize(text))
        assertEquals(1, ranges.size)
        assertTrue(ranges[0].bracket)
        assertEquals(0, ranges[0].headerLine)
        assertEquals(2, ranges[0].endLine)
    }

    @Test
    fun `nested blocks pair head to nearest matching end via the stack`() {
        val text = "procedure\n  do\n    x\n  end\nend\n"
        val ranges = FoldingModel.computeFoldableRanges(text, tokenize(text)).sortedBy { it.headerLine }
        assertEquals(2, ranges.size)
        assertEquals(FoldRange(headerLine = 0, endLine = 4, endOffset = ranges[0].endOffset, bracket = false), ranges[0]) // outer procedure/end
        assertEquals(FoldRange(headerLine = 1, endLine = 3, endOffset = ranges[1].endOffset, bracket = false), ranges[1]) // inner do/end
    }

    @Test
    fun `collapsing a range hides its body behind a placeholder and remaps display lines`() {
        val text = "before\ndefine\n  foo = 1\nend\nafter\n"
        val ranges = FoldingModel.computeFoldableRanges(text, tokenize(text))
        val defineRange = ranges.single { it.headerLine == 1 }

        val folded = FoldingModel.collapsedView(text, ranges, setOf(defineRange.headerLine))
        val displayLines = folded.text.split("\n")

        assertEquals(listOf("before", "define …", "after", ""), displayLines)
        // Display line 1 (the collapsed header) still maps back to model line 1; display line 2
        // ("after") maps to model line 4, skipping the two hidden lines in between.
        assertEquals(listOf(0, 1, 4, 5), folded.displayLineToModelLine)
    }

    @Test
    fun `no collapsed lines returns the text unchanged`() {
        val text = "define\n  foo = 1\nend\n"
        val ranges = FoldingModel.computeFoldableRanges(text, tokenize(text))
        val folded = FoldingModel.collapsedView(text, ranges, emptySet())
        assertEquals(text, folded.text)
    }
}
