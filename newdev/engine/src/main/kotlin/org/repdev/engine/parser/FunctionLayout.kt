package org.repdev.engine.parser

import java.io.File

/**
 * Ported from com.repdev.parser.FunctionLayout — same two-regex line format
 * as the original functions.txt (function line: "name|desc|comma,types";
 * tab-indented argument line under it: "\tshortName|desc|comma,types").
 * No longer a singleton reading a hardcoded relative path; built via [load].
 *
 * Bad type names (`VariableType.valueOf` failures) and duplicate function
 * names are skipped with a message rather than thrown, matching the
 * original's tolerance for a slightly malformed functions.txt.
 */
class FunctionLayout private constructor(lines: List<String>) {
    private val funcPattern = Regex("(.*)\\|(.*)\\|(.*)")
    private val argPattern = Regex("\\t(.*)\\|(.*)\\|(.*)")

    private val functionMap = HashMap<String, Function>()
    val list: List<Function>

    init {
        val functions = mutableListOf<Function>()
        var current: Function? = null

        for (line in lines) {
            if (line.isEmpty()) continue

            val argMatch = if (line.startsWith("\t")) argPattern.matchEntire(line) else null
            if (argMatch != null) {
                val cur = current ?: continue
                val types = parseTypes(argMatch.groupValues[3], line) ?: continue
                cur.arguments.add(Argument(argMatch.groupValues[1], argMatch.groupValues[2], types))
                continue
            }

            val funcMatch = funcPattern.matchEntire(line)
            val name: String
            val description: String
            val types: List<VariableType>
            if (funcMatch != null) {
                name = funcMatch.groupValues[1]
                description = funcMatch.groupValues[2]
                types = parseTypes(funcMatch.groupValues[3], line) ?: continue
            } else {
                name = line
                description = ""
                types = emptyList()
            }

            if (functionMap.containsKey(name.lowercase())) {
                println("Duplicate Function Entry: $name")
            }
            val fn = Function(name, description, types)
            current = fn
            functions.add(fn)
            functionMap[name.lowercase()] = fn
        }

        list = functions.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name })
    }

    private fun parseTypes(csv: String, line: String): List<VariableType>? =
        try {
            csv.split(",").filter { it.isNotBlank() }.map { VariableType.valueOf(it.trim()) }
        } catch (e: IllegalArgumentException) {
            System.err.println(line)
            null
        }

    fun containsName(name: String): Boolean = functionMap.containsKey(name.lowercase())

    companion object {
        fun load(file: File): FunctionLayout = FunctionLayout(file.readLines())
        fun load(lines: List<String>): FunctionLayout = FunctionLayout(lines)
    }
}
