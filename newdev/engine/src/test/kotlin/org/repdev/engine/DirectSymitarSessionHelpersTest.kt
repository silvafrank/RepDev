package org.repdev.engine

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Covers the pure helpers extracted out of DirectSymitarSession (queue selection,
 * Symitar date parsing, EASE menu selection parsing) — the wire-protocol methods
 * themselves need a live/fake socket and are out of scope for a unit test.
 */
class DirectSymitarSessionHelpersTest {
    @Test
    fun `pickBatchQueue keeps the requested queue when it is available`() {
        val available = BooleanArray(10).also { it[3] = true }
        val counts = IntArray(10) { -1 }.also { it[3] = 5 } // busy, but explicitly requested
        assertEquals(3, pickBatchQueue(3, available, counts))
    }

    @Test
    fun `pickBatchQueue falls back to the first empty available queue`() {
        val available = BooleanArray(10).also { it[2] = true; it[5] = true }
        val counts = IntArray(10) { -1 }.also { it[2] = 3; it[5] = 0 } // 2 busy, 5 empty
        assertEquals(5, pickBatchQueue(-1, available, counts))
    }

    @Test
    fun `pickBatchQueue falls back to last available queue if none are empty`() {
        val available = BooleanArray(10).also { it[2] = true; it[5] = true }
        val counts = IntArray(10) { -1 }.also { it[2] = 1; it[5] = 1 } // both busy
        assertEquals(5, pickBatchQueue(-1, available, counts))
    }

    @Test
    fun `parseHhMmSsToSeconds converts to seconds since midnight`() {
        assertEquals(3723, parseHhMmSsToSeconds("01:02:03"))
        assertEquals(0, parseHhMmSsToSeconds("00:00:00"))
    }

    @Test
    fun `parseSymitarDate parses MMddyyyy with no time`() {
        val date = parseSymitarDate("03152026", null)
        val cal = java.util.GregorianCalendar().apply { time = date }
        assertEquals(2, cal.get(java.util.Calendar.MONTH)) // March = 2
        assertEquals(15, cal.get(java.util.Calendar.DAY_OF_MONTH))
        assertEquals(2026, cal.get(java.util.Calendar.YEAR))
    }

    @Test
    fun `parseSymitarDate parses MMddyyyy with military time`() {
        val date = parseSymitarDate("03152026", "2312")
        val cal = java.util.GregorianCalendar().apply { time = date }
        assertEquals(23, cal.get(java.util.Calendar.HOUR_OF_DAY))
        assertEquals(12, cal.get(java.util.Calendar.MINUTE))
    }

    @Test
    fun `getEaseSelection finds the selection number for the given sym`() {
        val screen = "1 - SYM 001\n2 - SYM 042\n3 - SYM 099\nSelection :"
        assertEquals(2, getEaseSelection(screen, 42))
    }

    @Test
    fun `getEaseSelection returns -1 when the sym is not in the menu`() {
        val screen = "1 - SYM 001\n2 - SYM 042\nSelection :"
        assertEquals(-1, getEaseSelection(screen, 999))
    }
}
