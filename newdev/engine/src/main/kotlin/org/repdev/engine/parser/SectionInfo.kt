package org.repdev.engine.parser

/** Ported from com.repdev.parser.SectionInfo — a DEFINE/SETUP/PROCEDURE/etc. section's location, for Goto Section. */
data class SectionInfo(
    val title: String = "",
    val pos: Int = -1,
    val firstInsertPos: Int = -1,
    val lastInsertPos: Int = -1,
) : Comparable<SectionInfo> {
    override fun compareTo(other: SectionInfo): Int = title.compareTo(other.title, ignoreCase = true)
    override fun toString(): String = "$title:$pos:$firstInsertPos:$lastInsertPos"
}
