package org.repdev.engine.parser

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

// Small db.txt-format fixture exercising the two things the original's
// regex/depth-tracking logic has to get right: a nested sub-record (one
// leading-whitespace-char deeper) and a field type code round-tripping to
// the right VariableType.
private val FIXTURE = listOf(
    "***|ACCOUNT|Account Record",
    "ACCT:NUM|Account Number|1|4|10",
    "ACCT:NAME|Account Name|2|0|20",
    " ***|ADDRESS|Address Sub-record",
    "ADDR:LINE1|Address Line 1|1|0|30",
)

class DatabaseLayoutTest {
    @Test
    fun `builds the record tree by indentation depth`() {
        val db = DatabaseLayout.load(FIXTURE)

        assertEquals(1, db.treeRecords.size)
        val account = db.treeRecords[0]
        assertEquals("ACCOUNT", account.name)
        assertEquals(1, account.subRecords.size)
        assertEquals("ADDRESS", account.subRecords[0].name)
        assertEquals(account, account.subRecords[0].root)
    }

    @Test
    fun `parses field type codes into VariableType`() {
        val db = DatabaseLayout.load(FIXTURE)
        val account = db.getRecordByName("account")!!

        assertEquals(VariableType.NUMBER, account.fields.first { it.name == "ACCT:NUM" }.variableType)
        assertEquals(VariableType.CHARACTER, account.fields.first { it.name == "ACCT:NAME" }.variableType)
        assertEquals(20, account.fields.first { it.name == "ACCT:NAME" }.len)
    }

    @Test
    fun `name lookups are case-insensitive`() {
        val db = DatabaseLayout.load(FIXTURE)

        assertTrue(db.containsRecordName("Account"))
        assertTrue(db.recordHasField("ACCOUNT", "acct:num"))
        assertFalse(db.recordHasField("ACCOUNT", "nonexistent"))
        assertNull(db.getRecordByName("nope"))
    }
}
