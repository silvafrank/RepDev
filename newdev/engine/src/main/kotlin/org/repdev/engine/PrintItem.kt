package org.repdev.engine

import java.util.Calendar
import java.util.Date
import java.util.GregorianCalendar

/**
 * Ported from com.repdev.PrintItem. compareTo behavior preserved exactly:
 * same-day items order by batchSeq, otherwise order by full date/time.
 * See PrintItemTest for the parity check against the original semantics.
 */
data class PrintItem(
    val title: String,
    val seq: Int,
    val size: Int,
    val pages: Int,
    val batchSeq: Int,
    val date: Date,
) : Comparable<PrintItem> {
    override fun compareTo(other: PrintItem): Int {
        val cal0 = GregorianCalendar().apply { time = date }
        val cal1 = GregorianCalendar().apply { time = other.date }

        return if (cal0.get(Calendar.DAY_OF_YEAR) == cal1.get(Calendar.DAY_OF_YEAR)) {
            batchSeq.compareTo(other.batchSeq)
        } else {
            cal0.compareTo(cal1)
        }
    }
}
