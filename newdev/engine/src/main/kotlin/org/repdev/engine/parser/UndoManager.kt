package org.repdev.engine.parser

/**
 * One recorded edit: [oldLength] characters at [start] were replaced with a new stretch of
 * text; [length] is that new stretch's length, [replacedText] is what used to be there. Same
 * shape as SWT's `ExtendedModifyEvent(start, length, replacedText)`, which is what
 * `EditorComposite`'s undo/redo captured directly off the live widget.
 */
data class TextEdit(val start: Int, val length: Int, val replacedText: String)

/**
 * Ported from `EditorComposite`'s undo/redo: two `Stack<TextChange>` (`undos`/`redos`), grouped
 * into logical steps by commit markers, replayed by re-running each recorded edit in reverse.
 *
 * The original's cleverest bit carries over unchanged: replaying an edit during [undo] or [redo]
 * goes through the exact same capture path as a live keystroke, so the reverse of a reverse is
 * automatically the original edit again — no separate "compute the inverse" logic needed, just
 * point capture at the other stack while replaying (`undoMode` 1 vs. 2 in the original).
 *
 * **Dropped from this port:** the fold-op undo variant (`FOLD_OP_*`, `pushFoldUndo`,
 * `applyFoldUndo`/`applyFoldRedo`). Those existed only because the old editor physically deleted
 * folded text from the buffer, so folding needed its own undo trail. §7's `FoldingModel` design
 * note already established the new editor never mutates the buffer to fold — folding is a
 * view-only computation over the full canonical text — so there is nothing left for a fold
 * operation to undo.
 *
 * **View state (caret offset, scroll/top-index) is the caller's job**, same split as every other
 * ported module here: [undo]/[redo] return the edits they replayed so the caller can move its own
 * caret to the last one's [TextEdit.start], but this class only ever tracks [text].
 */
class UndoManager(initialText: String, private val limit: Int = 1000) {
    var text: String = initialText
        private set

    private sealed class Entry {
        object Commit : Entry()
        data class Change(val edit: TextEdit) : Entry()
    }

    private val undos = ArrayDeque<Entry>()
    private val redos = ArrayDeque<Entry>()

    private enum class RecordTarget { NONE, UNDO, REDO }
    private var recordTarget = RecordTarget.NONE

    val canUndo: Boolean get() = undos.isNotEmpty()
    val canRedo: Boolean get() = redos.isNotEmpty()

    /** Apply a user edit and record it onto the undo stack (clobbering any redo history's shape only via normal push). */
    fun edit(start: Int, oldLength: Int, newText: String): TextEdit {
        recordTarget = RecordTarget.UNDO
        return applyEdit(start, oldLength, newText)
    }

    /** Apply an edit without recording it — for programmatic/initial-load changes that shouldn't be undoable. */
    fun editSilently(start: Int, oldLength: Int, newText: String): TextEdit {
        recordTarget = RecordTarget.NONE
        return applyEdit(start, oldLength, newText)
    }

    /** End the current logical undo group, so a later [undo] stops here instead of merging with what follows. */
    fun commit() {
        if (undos.isEmpty() || undos.last() !is Entry.Commit) undos.addLast(Entry.Commit)
    }

    /** Replay the last undo group in reverse, returning the edits applied (oldest first). */
    fun undo(): List<TextEdit> {
        if (!canUndo) return emptyList()
        if (undos.last() is Entry.Commit) undos.removeLast()
        recordTarget = RecordTarget.REDO
        val applied = mutableListOf<TextEdit>()
        while (undos.isNotEmpty()) {
            val entry = undos.removeLast()
            if (entry is Entry.Commit) break
            applied += applyEdit((entry as Entry.Change).edit)
        }
        redos.addLast(Entry.Commit)
        recordTarget = RecordTarget.NONE
        return applied
    }

    /** Replay the last redo group, returning the edits applied (oldest first). */
    fun redo(): List<TextEdit> {
        if (!canRedo) return emptyList()
        if (redos.last() is Entry.Commit) redos.removeLast()
        recordTarget = RecordTarget.UNDO
        val applied = mutableListOf<TextEdit>()
        while (redos.isNotEmpty()) {
            val entry = redos.removeLast()
            if (entry is Entry.Commit) break
            applied += applyEdit((entry as Entry.Change).edit)
        }
        undos.addLast(Entry.Commit)
        recordTarget = RecordTarget.NONE
        return applied
    }

    private fun applyEdit(edit: TextEdit): TextEdit = applyEdit(edit.start, edit.length, edit.replacedText)

    private fun applyEdit(start: Int, oldLength: Int, newText: String): TextEdit {
        val replaced = text.substring(start, start + oldLength)
        text = text.substring(0, start) + newText + text.substring(start + oldLength)
        val change = TextEdit(start, newText.length, replaced)
        when (recordTarget) {
            RecordTarget.UNDO -> push(undos, change)
            RecordTarget.REDO -> push(redos, change)
            RecordTarget.NONE -> {}
        }
        return change
    }

    private fun push(stack: ArrayDeque<Entry>, change: TextEdit) {
        stack.addLast(Entry.Change(change))
        if (stack.size > limit) stack.removeFirst()
    }
}
