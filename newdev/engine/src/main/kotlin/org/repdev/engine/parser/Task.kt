package org.repdev.engine.parser

/** Ported from com.repdev.parser.Task — a TODO/FIXME/etc. comment found while scanning a file. */
data class Task(val fileName: String, val description: String, val line: Int, val col: Int, val type: Type) {
    enum class Type { TODO, FIXME, BUG, WTF, BM, TEST, NOTE }
}
