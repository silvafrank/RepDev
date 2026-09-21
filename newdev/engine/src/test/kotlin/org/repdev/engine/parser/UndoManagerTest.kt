package org.repdev.engine.parser

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class UndoManagerTest {
    @Test
    fun `a single edit undoes back to the original text and redoes forward again`() {
        val mgr = UndoManager("hello world")
        mgr.edit(6, 5, "there")
        assertEquals("hello there", mgr.text)

        assertTrue(mgr.canUndo)
        mgr.undo()
        assertEquals("hello world", mgr.text)
        assertFalse(mgr.canUndo)

        assertTrue(mgr.canRedo)
        mgr.redo()
        assertEquals("hello there", mgr.text)
        assertFalse(mgr.canRedo)
    }

    @Test
    fun `edits between commits group into one undo step, uncommitted edits group into one`() {
        val mgr = UndoManager("")
        mgr.edit(0, 0, "a")
        mgr.edit(1, 0, "b")
        mgr.commit()
        mgr.edit(2, 0, "c")
        assertEquals("abc", mgr.text)

        // No commit() was called after "c", so undoing pops just that group.
        mgr.undo()
        assertEquals("ab", mgr.text)

        // "a" and "b" were never separated by a commit(), so they undo together.
        mgr.undo()
        assertEquals("", mgr.text)
        assertFalse(mgr.canUndo)
    }

    @Test
    fun `editSilently mutates text without creating any undo history`() {
        val mgr = UndoManager("")
        mgr.editSilently(0, 0, "loaded from disk")
        assertEquals("loaded from disk", mgr.text)
        assertFalse(mgr.canUndo)
    }

    @Test
    fun `a fresh edit after undo does not clear the stale redo entries`() {
        // Ported quirk, not a bug: the original's undo() has a commented-out `redos.clear()` —
        // it deliberately leaves stale redo entries in place after a new edit. Preserved as-is.
        val mgr = UndoManager("x")
        mgr.edit(1, 0, "y")
        mgr.undo()
        mgr.edit(1, 0, "z")
        assertTrue(mgr.canRedo)
    }

    @Test
    fun `exceeding the limit drops the oldest undo entry`() {
        val mgr = UndoManager("", limit = 2)
        mgr.edit(0, 0, "a")
        mgr.commit()
        mgr.edit(1, 0, "b")
        mgr.commit()
        mgr.edit(2, 0, "c")
        assertEquals("abc", mgr.text)

        mgr.undo() // "c"'s own commit group
        mgr.undo() // "b"'s own commit group
        assertEquals("a", mgr.text)
        // "a"'s change entry was evicted by the limit back when "c" was pushed, so once "b"
        // and "c" are undone there's nothing left in the undo stack at all.
        assertFalse(mgr.canUndo)
    }
}
