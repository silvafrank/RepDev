package org.repdev.engine

import java.text.DateFormat
import java.util.Date

/** Ported from com.repdev.Sequence. Was a hand-written getter/setter bag; a data class is the same shape. */
data class Sequence(val sym: Int, val seq: Int, val date: Date) {
    // Original toString() format preserved exactly (used in UI lists).
    override fun toString(): String =
        "${DateFormat.getDateTimeInstance().format(date)} - Sequence: $seq"
}
