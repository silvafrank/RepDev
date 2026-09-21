package org.repdev.engine.parser

/**
 * Pure port of the section-scanning algorithm from
 * com.repdev.parser.BackgroundSectionParser (`parseSections`/`isSectionHead`/
 * `safeSubstring`) — walks a token stream tracking do/end nesting depth and
 * records a [SectionInfo] for each top-level DEFINE/SETUP/SELECT/SORT/TOTAL/
 * PROCEDURE/"print title" block.
 *
 * Deliberately NOT ported here: the `Thread`-based background re-parse
 * loop, the `synchronized` current-section cache (`getSectionInfo`/`exist`/
 * `getPos`/etc.), and `whereAmI`'s live state. Those exist only to keep a
 * StyledText-driven editor responsive by recomputing off the UI thread and
 * caching the last lookup — an editor-integration concern (debounce a
 * coroutine over `parseSections` results), not parsing logic. Revisit once
 * the Compose editor needs Goto-Section.
 */
fun parseSections(tokens: List<Token>, text: String): List<SectionInfo> {
    val sectionHeads = setOf("define", "print title", "setup", "select", "sort", "total", "procedure")
    val result = mutableListOf<SectionInfo>()
    var curDepth = 0
    var title = ""
    var pos = -1
    var firstInsertPos = -1

    for (tok in tokens) {
        if (tok.isRealHead()) {
            curDepth++
        } else if (tok.isRealEnd()) {
            curDepth--
        }

        if (tok.str in sectionHeads && !tok.inDate && !tok.inString && tok.commentDepth == 0) {
            curDepth = 1
            val titleTok = if (tok.str == "procedure") tok.after else tok
            val range = titleTok?.let { safeSubstring(text, it.start, it.end) }
            if (range == null) continue
            title = range
            pos = tok.start
            val nl = if (pos in 0..text.length) text.indexOf('\n', pos) else -1
            firstInsertPos = nl + 1
        } else if (curDepth == 0) {
            if (tok.str == "end" && tok.isRealEnd()) {
                result.add(SectionInfo(title, pos, firstInsertPos, tok.start))
            }
        }
    }

    return result
}

/** Returns the title of the section containing [offset], or "" if none — port of `whereAmI`. */
fun sectionAt(sections: List<SectionInfo>, offset: Int): String =
    sections.firstOrNull { offset >= it.pos && offset <= it.lastInsertPos + 3 }?.title ?: ""

private fun safeSubstring(src: String, start: Int, end: Int): String? {
    if (start < 0 || end < start || end > src.length) return null
    return src.substring(start, end)
}
