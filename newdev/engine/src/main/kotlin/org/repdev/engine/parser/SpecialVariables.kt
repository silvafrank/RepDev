package org.repdev.engine.parser

import java.io.File

/**
 * Ported from com.repdev.parser.SpecialVariables — same "name|description|type|len"
 * line format from vars.txt. No longer a process-wide singleton reading a
 * hardcoded path (`getInstance()` in the original); built explicitly via
 * [load], same as [DatabaseLayout].
 */
class SpecialVariables private constructor(lines: List<String>) {
    private val pattern = Regex("(.*)\\|(.*)\\|(.*)\\|(.*)")
    private val nameCache = HashSet<String>()
    val vars: List<SpecialVariable>

    init {
        val result = mutableListOf<SpecialVariable>()
        for (rawLine in lines) {
            val line = rawLine.trim()
            if (line.isEmpty()) continue
            val m = pattern.matchEntire(line) ?: continue
            val len = m.groupValues[4].trim().let { if (it.isEmpty()) -1 else it.toInt() }
            result.add(SpecialVariable(m.groupValues[1], m.groupValues[2], m.groupValues[3], len))
            nameCache.add(m.groupValues[1].lowercase())
        }
        vars = result
    }

    fun contains(name: String): Boolean = nameCache.contains(name.lowercase())

    companion object {
        fun load(file: File): SpecialVariables = SpecialVariables(file.readLines())
        fun load(lines: List<String>): SpecialVariables = SpecialVariables(lines)
    }
}
