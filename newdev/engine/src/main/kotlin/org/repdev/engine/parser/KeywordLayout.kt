package org.repdev.engine.parser

import java.io.File

/**
 * Ported from com.repdev.parser.KeywordLayout — same "name|description|example"
 * line format as keywords.txt. No longer a singleton reading a hardcoded
 * relative path; built via [load].
 */
class KeywordLayout private constructor(lines: List<String>) {
    private val pattern = Regex("(.*)\\|(.*)\\|(.*)")
    private val keywordMap = HashMap<String, Keyword>()
    val list: List<Keyword>

    init {
        val keywords = mutableListOf<Keyword>()
        for (line in lines) {
            if (line.isEmpty()) continue
            val m = pattern.matchEntire(line)
            val kw = if (m != null) Keyword(m.groupValues[1], m.groupValues[2], m.groupValues[3]) else Keyword(line, "", "")
            keywords.add(kw)
            keywordMap[kw.name] = kw
        }
        list = keywords.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name })
    }

    fun contains(name: String): Boolean = keywordMap.containsKey(name.uppercase())

    companion object {
        fun load(file: File): KeywordLayout = KeywordLayout(file.readLines())
        fun load(lines: List<String>): KeywordLayout = KeywordLayout(lines)
    }
}
