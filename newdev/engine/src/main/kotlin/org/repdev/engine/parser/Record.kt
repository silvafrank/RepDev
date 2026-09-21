package org.repdev.engine.parser

/**
 * Ported from com.repdev.parser.Record. Kept as a mutable class rather than
 * a data class: DatabaseLayout builds the tree top-down while parsing
 * db.txt, filling in `subRecords`/`fields`/`root` after construction, and
 * `root` is a back-reference (a data class `equals`/`hashCode` over that
 * would recurse into a cycle).
 */
class Record(
    var name: String,
    var description: String,
    var root: Record?,
) {
    var subRecords: MutableList<Record> = mutableListOf()
    var fields: MutableList<Field> = mutableListOf()

    override fun toString(): String = "Record: $name"
}
