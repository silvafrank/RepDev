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

class TaskScannerTest {
    @Test
    fun `todo tag inside a bracket comment is found with its description`() {
        val text = "[todo: fix the thing]\n"
        val tasks = TaskScanner.findTasks(tokenize(text), text, "a.pro")

        assertEquals(1, tasks.size)
        assertEquals(Task.Type.TODO, tasks[0].type)
        // Not "...thing" — see TaskScanner's kdoc: the original's own off-by-one drops the last
        // real character before a closing "]", preserved rather than silently fixed.
        assertEquals("todo: fix the thin", tasks[0].description)
        assertEquals(0, tasks[0].line)
        assertEquals(1, tasks[0].col) // 0-based, points at "todo" right after the opening "["
    }

    @Test
    fun `fixme, bug and bugbug map to their own types`() {
        assertEquals(Task.Type.FIXME, TaskScanner.findTasks(tokenize("[fixme: x]\n"), "[fixme: x]\n", "a.pro").single().type)
        assertEquals(Task.Type.BUG, TaskScanner.findTasks(tokenize("[bug: x]\n"), "[bug: x]\n", "a.pro").single().type)
        assertEquals(Task.Type.BUG, TaskScanner.findTasks(tokenize("[bugbug: x]\n"), "[bugbug: x]\n", "a.pro").single().type)
    }

    @Test
    fun `description is cut at the next newline when there's no closing bracket first`() {
        val text = "[ todo: line one\n  more stuff ]\n"
        val tasks = TaskScanner.findTasks(tokenize(text), text, "a.pro")
        assertEquals(1, tasks.size)
        assertEquals("todo: line one", tasks[0].description)
    }

    @Test
    fun `a bare identifier named todo outside a comment is not a task`() {
        val text = "define\n  todo = 1\nend\n"
        assertTrue(TaskScanner.findTasks(tokenize(text), text, "a.pro").isEmpty())
    }

    @Test
    fun `todo not followed by a colon is not a task`() {
        val text = "[todo fix this]\n"
        assertTrue(TaskScanner.findTasks(tokenize(text), text, "a.pro").isEmpty())
    }
}
