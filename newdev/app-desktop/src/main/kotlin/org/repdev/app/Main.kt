package org.repdev.app

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import java.io.File
import javax.swing.JFileChooser
import org.repdev.engine.parser.AutoIndenter
import org.repdev.engine.parser.CategoryStyle
import org.repdev.engine.parser.DatabaseLayout
import org.repdev.engine.parser.FunctionLayout
import org.repdev.engine.parser.HighlightCategory
import org.repdev.engine.parser.KeywordLayout
import org.repdev.engine.parser.RepgenHighlighter
import org.repdev.engine.parser.RepgenTheme
import org.repdev.engine.parser.RepgenTokenizer
import org.repdev.engine.parser.RgbColor
import org.repdev.engine.parser.SpecialVariables
import org.repdev.engine.parser.UndoManager
import org.repdev.engine.parser.VariableRegistry

/**
 * Compose Desktop UI shell: file tree + tabs + editor + light/dark theme,
 * now with RepGen syntax highlighting driven by the engine's
 * RepgenTokenizer/RepgenHighlighter/RepgenTheme.
 *
 * Scope note: autocomplete/error checking, session connect (Telnet/SSH), Run
 * Reports/FM etc. still plug in later — see MODERNIZATION_PLAN.md.
 */
fun main() = application {
    var darkTheme by remember { mutableStateOf(true) }

    Window(onCloseRequest = ::exitApplication, title = "RepDev") {
        MaterialTheme(colorScheme = if (darkTheme) darkColorScheme() else lightColorScheme()) {
            RepDevApp(darkTheme = darkTheme, onToggleTheme = { darkTheme = !darkTheme })
        }
    }
}

/**
 * [undo] is the source of truth for [content]; [fieldValue] mirrors it plus the live caret/
 * selection Compose needs. All three stay in sync through [applyEdit]/[applyUndo]/[applyRedo] below
 * — nothing else should write `fieldValue` directly.
 */
private class OpenFile(val file: File, initialContent: String) {
    val undo = UndoManager(initialContent)
    var fieldValue by mutableStateOf(TextFieldValue(initialContent))
    var dirty by mutableStateOf(false)

    // Tracks whether the next edit continues the current typing run (caret sitting right where the
    // last edit left it) or starts a new one — ponytail's stand-in for the original's explicit
    // commit() call sites, which weren't captured during the parser-side port. Coalesces a
    // continuous run of keystrokes into one undo step and splits on any caret jump; not identical
    // to the original's grouping, revisit if it feels wrong in practice.
    var lastEditEnd: Int? = null
    val content: String get() = fieldValue.text
}

/** Smallest (start, oldLength, newText) edit turning [old] into [new] — common prefix/suffix diff,
 *  since BasicTextField only ever hands back the whole new string, never a discrete edit. */
private fun diffEdit(old: String, new: String): Triple<Int, Int, String> {
    val maxCommon = minOf(old.length, new.length)
    var prefix = 0
    while (prefix < maxCommon && old[prefix] == new[prefix]) prefix++
    var suffix = 0
    while (suffix < maxCommon - prefix && old[old.length - 1 - suffix] == new[new.length - 1 - suffix]) suffix++
    return Triple(prefix, old.length - prefix - suffix, new.substring(prefix, new.length - suffix))
}

/**
 * db.txt/functions.txt/keywords.txt/vars.txt/styles/[name].xml are loaded the same way the
 * original did: plain files next to the running app, not anything a session downloads. Walks up
 * from the working directory looking for the styles/ dir (an IDE run and a packaged run land in
 * different cwds) and falls back to empty definitions / RepgenTheme.DEFAULT if they're missing,
 * rather than failing to start — highlighting just degrades to NORMAL/keyword-less until found.
 */
private fun findRepoRoot(): File? {
    var dir: File? = File(System.getProperty("user.dir")).absoluteFile
    while (dir != null) {
        if (File(dir, "styles").isDirectory) return dir
        dir = dir.parentFile
    }
    return null
}

private class RepgenDefinitions(root: File?) {
    val db = root.loadOrEmpty("db.txt", DatabaseLayout::load) { DatabaseLayout.load(emptyList()) }
    val functions = root.loadOrEmpty("functions.txt", FunctionLayout::load) { FunctionLayout.load(emptyList()) }
    val keywords = root.loadOrEmpty("keywords.txt", KeywordLayout::load) { KeywordLayout.load(emptyList()) }
    val specialVars = root.loadOrEmpty("vars.txt", SpecialVariables::load) { SpecialVariables.load(emptyList()) }
}

private fun <T> File?.loadOrEmpty(name: String, load: (File) -> T, empty: () -> T): T =
    this?.resolve(name)?.takeIf { it.exists() }?.let(load) ?: empty()

// ponytail: no alpha channel in RgbColor, editor content is always fully opaque.
private fun RgbColor.toColor(): Color = Color((0xFF shl 24) or this)

private fun RepgenTheme.styleFor(category: HighlightCategory): CategoryStyle = when (category) {
    HighlightCategory.NORMAL -> CategoryStyle(editorFg)
    HighlightCategory.COMMENT -> comment
    HighlightCategory.TASK -> task
    HighlightCategory.STRING -> string
    HighlightCategory.DATE -> date
    HighlightCategory.NUMBER -> number
    HighlightCategory.STRUCT1 -> struct1
    HighlightCategory.STRUCT1_INVALID -> struct1Invalid
    HighlightCategory.STRUCT2 -> struct2
    HighlightCategory.STRUCT2_INVALID -> struct2Invalid
    HighlightCategory.FUNCTION -> function
    HighlightCategory.KEYWORD -> keyword
    HighlightCategory.VARIABLE -> variable
}

/**
 * Tokenizes+classifies the whole file and paints it via RepgenTheme. Re-tokenizes the full text
 * on every keystroke rather than incrementally patching around the edit (RepgenTokenizer.parse's
 * start/end/oldEnd params support that, FoldingManager.java's HiddenTextProvider is the reference
 * for how) — fine at typical .PRO/.SET file sizes.
 * ponytail: O(file size) per keystroke; switch to incremental re-parse if large files feel laggy.
 */
private fun highlightedText(text: String, theme: RepgenTheme, defs: RepgenDefinitions): AnnotatedString {
    val tokenizer = RepgenTokenizer(defs.db)
    tokenizer.parse(text, 0, text.length, 0)
    val vars = VariableRegistry().apply { rebuild("", text, tokenizer.tokens) }
    val spans = RepgenHighlighter.highlight(tokenizer.tokens, defs.db, defs.functions, defs.keywords, defs.specialVars, vars)
    return buildAnnotatedString {
        append(text)
        for (span in spans) {
            val style = theme.styleFor(span.category)
            addStyle(
                SpanStyle(
                    color = (style.fg ?: theme.editorFg).toColor(),
                    background = style.bg?.toColor() ?: Color.Unspecified,
                    fontWeight = if (style.bold) FontWeight.Bold else FontWeight.Normal,
                    fontStyle = if (style.italic) FontStyle.Italic else FontStyle.Normal,
                ),
                span.start,
                span.start + span.length,
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
private fun RepDevApp(darkTheme: Boolean, onToggleTheme: () -> Unit) {
    var rootDir by remember { mutableStateOf(File(System.getProperty("user.dir"))) }
    var expandedDirs by remember { mutableStateOf(setOf<String>()) }
    val openFiles = remember { mutableStateListOf<OpenFile>() }
    var selectedIndex by remember { mutableStateOf<Int?>(null) }

    // Loaded once per app run — not per-session yet. TODO: pick the theme from Config.style
    // once there's a settings UI/Config wired into app-desktop at all.
    val repoRoot = remember { findRepoRoot() }
    val definitions = remember(repoRoot) { RepgenDefinitions(repoRoot) }
    val repgenTheme = remember(repoRoot) {
        repoRoot?.resolve("styles/default.xml")?.takeIf { it.exists() }?.let(RepgenTheme::load) ?: RepgenTheme.DEFAULT
    }

    fun openFile(file: File) {
        val existing = openFiles.indexOfFirst { it.file == file }
        if (existing >= 0) {
            selectedIndex = existing
            return
        }
        val text = runCatching { file.readText() }.getOrElse { "// Could not read file: ${it.message}" }
        openFiles.add(OpenFile(file, text))
        selectedIndex = openFiles.lastIndex
    }

    fun closeTab(index: Int) {
        openFiles.removeAt(index)
        selectedIndex = when {
            openFiles.isEmpty() -> null
            selectedIndex == null -> null
            index < selectedIndex!! -> selectedIndex!! - 1
            index == selectedIndex!! -> minOf(index, openFiles.lastIndex)
            else -> selectedIndex
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("RepDev") },
                actions = {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(end = 16.dp)) {
                        Text(if (darkTheme) "Dark" else "Light", modifier = Modifier.padding(end = 8.dp))
                        Switch(checked = darkTheme, onCheckedChange = { onToggleTheme() })
                    }
                },
            )
        },
    ) { padding ->
        Row(Modifier.fillMaxSize().padding(padding)) {
            Column(Modifier.width(240.dp).fillMaxHeight()) {
                TextButton(onClick = {
                    val chooser = JFileChooser(rootDir).apply { fileSelectionMode = JFileChooser.DIRECTORIES_ONLY }
                    if (chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) {
                        rootDir = chooser.selectedFile
                        expandedDirs = setOf()
                    }
                }) { Text("Open Folder…") }
                Text(rootDir.absolutePath, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(horizontal = 8.dp))
                HorizontalDivider()
                FileTree(
                    rootDir = rootDir,
                    expandedDirs = expandedDirs,
                    onToggleDir = { path -> expandedDirs = if (path in expandedDirs) expandedDirs - path else expandedDirs + path },
                    onOpenFile = ::openFile,
                )
            }
            VerticalDivider()
            Column(Modifier.fillMaxSize()) {
                TabStrip(
                    openFiles = openFiles,
                    selectedIndex = selectedIndex,
                    onSelect = { selectedIndex = it },
                    onClose = ::closeTab,
                )
                val current = selectedIndex?.let { openFiles.getOrNull(it) }
                if (current == null) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text("Open a file from the tree to start editing", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                } else {
                    EditorPane(
                        openFile = current,
                        theme = repgenTheme,
                        definitions = definitions,
                        onSave = {
                            current.file.writeText(current.content)
                            current.dirty = false
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun FileTree(
    rootDir: File,
    expandedDirs: Set<String>,
    onToggleDir: (String) -> Unit,
    onOpenFile: (File) -> Unit,
) {
    val nodes = remember(rootDir, expandedDirs) { visibleNodes(rootDir, expandedDirs) }
    LazyColumn(Modifier.fillMaxSize()) {
        items(nodes) { node ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable {
                        if (node.file.isDirectory) onToggleDir(node.file.absolutePath) else onOpenFile(node.file)
                    }
                    .padding(start = (12 + node.depth * 16).dp, top = 4.dp, bottom = 4.dp, end = 8.dp),
            ) {
                val prefix = if (node.file.isDirectory) {
                    if (node.file.absolutePath in expandedDirs) "▾ " else "▸ "
                } else {
                    "  "
                }
                Text(prefix + node.file.name, maxLines = 1)
            }
        }
    }
}

private data class TreeNode(val file: File, val depth: Int)

// ponytail: rescans the directory on every expand/collapse rather than caching
// per-node children; fine at project-folder sizes, revisit if trees get huge.
private fun visibleNodes(rootDir: File, expandedDirs: Set<String>): List<TreeNode> {
    val result = mutableListOf<TreeNode>()
    fun walk(dir: File, depth: Int) {
        val children = dir.listFiles()?.sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() })) ?: return
        for (child in children) {
            result.add(TreeNode(child, depth))
            if (child.isDirectory && child.absolutePath in expandedDirs) walk(child, depth + 1)
        }
    }
    walk(rootDir, 0)
    return result
}

@Composable
private fun TabStrip(
    openFiles: SnapshotStateList<OpenFile>,
    selectedIndex: Int?,
    onSelect: (Int) -> Unit,
    onClose: (Int) -> Unit,
) {
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
        openFiles.forEachIndexed { index, openFile ->
            val selected = index == selectedIndex
            Row(
                Modifier
                    .background(if (selected) MaterialTheme.colorScheme.surfaceVariant else Color.Transparent)
                    .clickable { onSelect(index) }
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text((if (openFile.dirty) "• " else "") + openFile.file.name)
                Spacer(Modifier.width(8.dp))
                Text("×", modifier = Modifier.clickable { onClose(index) }.padding(horizontal = 4.dp))
            }
        }
    }
}

// TODO: read from Config.tabSize once app-desktop has Config wired in at all (same gap noted on
// repgenTheme above) — 0 means "a real tab", same as the original's getTabStr().
private const val TAB_STR = "\t"

/** Applies a captured (start, oldLength, newText) edit to [openFile] through its [UndoManager],
 *  committing first if this edit doesn't continue the run the previous one left off. */
private fun OpenFile.applyEdit(start: Int, oldLength: Int, newText: String, caretAfter: Int) {
    if (lastEditEnd != start) undo.commit()
    undo.edit(start, oldLength, newText)
    lastEditEnd = start + newText.length
    dirty = true
    fieldValue = TextFieldValue(undo.text, TextRange(caretAfter))
}

private fun OpenFile.applyUndo() {
    val edits = undo.undo()
    if (edits.isEmpty()) return
    lastEditEnd = null
    dirty = true
    fieldValue = TextFieldValue(undo.text, TextRange(edits.last().start))
}

private fun OpenFile.applyRedo() {
    val edits = undo.redo()
    if (edits.isEmpty()) return
    lastEditEnd = null
    dirty = true
    val last = edits.last()
    fieldValue = TextFieldValue(undo.text, TextRange(last.start + last.length))
}

/** Enter key: insert a newline plus whatever indent AutoIndenter computes for the line it creates,
 *  as a single undo step (mirrors EditorComposite's verify-listener that intercepted Enter instead
 *  of letting the widget insert a bare newline). */
private fun OpenFile.applyEnterWithIndent(definitions: RepgenDefinitions) {
    val selection = fieldValue.selection
    val start = selection.min
    val end = selection.max
    val afterNewline = content.substring(0, start) + "\n" + content.substring(end)
    val tokens = RepgenTokenizer(definitions.db).apply { parse(afterNewline, 0, afterNewline.length, 0) }.tokens
    val indent = AutoIndenter.computeIndent(tokens, afterNewline, start + 1, TAB_STR)
    val inserted = "\n" + indent
    applyEdit(start, end - start, inserted, caretAfter = start + inserted.length)
}

@Composable
private fun EditorPane(openFile: OpenFile, theme: RepgenTheme, definitions: RepgenDefinitions, onSave: () -> Unit) {
    Column(Modifier.fillMaxSize().background(theme.editorBg.toColor())) {
        Row(Modifier.padding(4.dp)) {
            TextButton(onClick = onSave, enabled = openFile.dirty) { Text("Save") }
            TextButton(onClick = { openFile.applyUndo() }, enabled = openFile.undo.canUndo) { Text("Undo") }
            TextButton(onClick = { openFile.applyRedo() }, enabled = openFile.undo.canRedo) { Text("Redo") }
        }
        BasicTextField(
            value = openFile.fieldValue,
            onValueChange = { new ->
                if (new.text != openFile.content) {
                    val (start, oldLen, newText) = diffEdit(openFile.content, new.text)
                    openFile.applyEdit(start, oldLen, newText, caretAfter = new.selection.end)
                } else {
                    // Caret/selection moved without changing text — closes the current typing run.
                    openFile.lastEditEnd = null
                    openFile.fieldValue = new
                }
            },
            textStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = theme.fontSize.sp, color = theme.editorFg.toColor()),
            visualTransformation = { text ->
                TransformedText(highlightedText(text.text, theme, definitions), OffsetMapping.Identity)
            },
            modifier = Modifier.fillMaxSize().padding(8.dp).onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when {
                    event.key == Key.Enter || event.key == Key.NumPadEnter -> {
                        openFile.applyEnterWithIndent(definitions)
                        true
                    }
                    event.isCtrlPressed && event.key == Key.Z && !event.isShiftPressed -> {
                        openFile.applyUndo()
                        true
                    }
                    event.isCtrlPressed && (event.key == Key.Y || (event.key == Key.Z && event.isShiftPressed)) -> {
                        openFile.applyRedo()
                        true
                    }
                    else -> false
                }
            },
        )
    }
}

@Composable
private fun VerticalDivider() {
    Box(Modifier.fillMaxHeight().width(1.dp).background(MaterialTheme.colorScheme.outlineVariant))
}
