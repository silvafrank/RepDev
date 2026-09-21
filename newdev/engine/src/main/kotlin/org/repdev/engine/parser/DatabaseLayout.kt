package org.repdev.engine.parser

import java.io.File

/**
 * Ported from com.repdev.parser.DatabaseLayout. Parsing/caching logic
 * preserved exactly (same regexes, same tree-building by indentation depth,
 * same lowercase name caches for fast lookups) — see DatabaseLayoutTest for
 * the parity check against a small db.txt fixture.
 *
 * One structural change: this is no longer a process-wide singleton that
 * reads a hardcoded "db.txt" from the working directory
 * (`DatabaseLayout.getInstance()` in the original). It's built explicitly
 * from a file (or a line list, for tests) and held by whatever owns a
 * project/session — plain global mutable state has no reason to survive a
 * rewrite that's already touching every call site.
 */
class DatabaseLayout private constructor(lines: List<String>) {
    private val recPattern = Regex("(.*)\\*\\*\\*\\|(.*)\\|(.*)")
    private val fieldPattern = Regex("([\\s]*)([a-zA-Z0-9:]*)\\|(.*)\\|(.*)\\|(.*)\\|(.*)")

    val treeRecords: MutableList<Record> = mutableListOf()
    val flatRecords: List<Record>

    private val lowerCaseRecordNames = HashSet<String>()
    private val lowerCaseFieldNames = HashSet<String>()
    private val recordByName = HashMap<String, Record>()
    private val fieldNamesByRecord = HashMap<String, Set<String>>()

    init {
        var currentRecord: Record? = null
        var rootRecord: Record? = null
        var lastDepth = 0

        for (line in lines) {
            if (line.contains("***")) {
                val m = recPattern.matchEntire(line) ?: continue
                val depth = m.groupValues[1].length

                if (depth > lastDepth) {
                    rootRecord = currentRecord
                } else if (depth < lastDepth) {
                    repeat(lastDepth - depth) { rootRecord = rootRecord?.root }
                }

                val record = Record(m.groupValues[2], m.groupValues[3], rootRecord)
                currentRecord = record

                if (depth == 0) treeRecords.add(record) else rootRecord?.subRecords?.add(record)
                lastDepth = depth
            } else {
                val m = fieldPattern.matchEntire(line) ?: continue
                val type = m.groupValues[5].toInt()
                val variableType = VariableType.entries.firstOrNull { it.code == type } ?: VariableType.NULL
                val len = m.groupValues[6].let { if (it != "null") it.toInt() else -1 }

                currentRecord?.fields?.add(Field(m.groupValues[2], m.groupValues[3], m.groupValues[4].toInt(), variableType, len))
            }
        }

        flatRecords = flatten(treeRecords)
        for (record in flatRecords) {
            val recName = record.name.lowercase()
            lowerCaseRecordNames.add(recName)
            recordByName[recName] = record

            val fieldNames = record.fields.mapTo(HashSet()) { it.name.lowercase() }
            lowerCaseFieldNames.addAll(fieldNames)
            fieldNamesByRecord[recName] = fieldNames
        }
    }

    private fun flatten(list: List<Record>): List<Record> =
        list.flatMap { listOf(it) + flatten(it.subRecords) }

    fun getRecordByName(name: String): Record? = recordByName[name.lowercase()]

    fun recordHasField(recordName: String, fieldName: String): Boolean =
        fieldNamesByRecord[recordName.lowercase()]?.contains(fieldName.lowercase()) ?: false

    fun containsRecordName(name: String): Boolean = lowerCaseRecordNames.contains(name.lowercase())

    fun containsFieldName(name: String): Boolean = lowerCaseFieldNames.contains(name.lowercase())

    companion object {
        fun load(file: File): DatabaseLayout = DatabaseLayout(file.readLines())
        fun load(lines: List<String>): DatabaseLayout = DatabaseLayout(lines)
    }
}
