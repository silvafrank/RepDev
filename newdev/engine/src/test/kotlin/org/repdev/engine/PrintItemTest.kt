package org.repdev.engine

import java.util.Date
import kotlin.test.Test
import kotlin.test.assertTrue

// Parity check for the compareTo port — the one non-trivial branch in this
// module's first batch of conversions. Not a full suite, just enough to fail
// loudly if a future edit breaks the "same day -> batchSeq, else -> date" rule.
class PrintItemTest {
    private fun item(epochMillis: Long, batchSeq: Int) =
        PrintItem(title = "T", seq = 0, size = 0, pages = 0, batchSeq = batchSeq, date = Date(epochMillis))

    @Test
    fun `same day orders by batchSeq`() {
        val dayStart = 1_726_000_000_000L
        val earlier = item(dayStart, batchSeq = 1)
        val later = item(dayStart + 3600_000, batchSeq = 2) // +1h, same calendar day
        assertTrue(earlier < later)
        assertTrue(later > earlier)
    }

    @Test
    fun `different day orders by date regardless of batchSeq`() {
        val day1 = item(1_726_000_000_000L, batchSeq = 99)
        val day2 = item(1_726_000_000_000L + 86_400_000L, batchSeq = 1) // +1 day, lower batchSeq
        assertTrue(day1 < day2)
    }
}
