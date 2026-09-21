package org.repdev.engine.parser

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private val DB_FIXTURE = listOf(
    "***|ACCOUNT|Account Record",
    "NUM|Account Number|1|4|10",
    "NAME|Account Name|2|0|20",
)
private val NO_KEYWORDS = KeywordLayout.load(emptyList())
private val NO_FUNCTIONS = FunctionLayout.load(emptyList())
private val NO_SPECIAL_VARS = SpecialVariables.load(emptyList())
private val NO_DB = DatabaseLayout.load(emptyList())

private fun tokenize(text: String): List<Token> {
    val tk = RepgenTokenizer(DatabaseLayout.load(emptyList()))
    tk.parse(text, 0, text.length, 0)
    return tk.tokens
}

class SuggestionEngineTest {
    @Test
    fun `variable suggestions are filtered by prefix and sorted`() {
        val vars = listOf(Variable("balance", "a.pro", 0, "NUMBER"), Variable("branch", "a.pro", 0, "NUMBER"), Variable("other", "a.pro", 0, "NUMBER"))
        val result = SuggestionEngine.computeSuggestions(null, "b", vars, NO_SPECIAL_VARS, NO_FUNCTIONS, NO_KEYWORDS, NO_DB)

        assertEquals(listOf("BALANCE", "BRANCH"), result.map { it.insertValue })
        assertTrue(result.all { it.kind == SuggestionKind.VARIABLE })
    }

    @Test
    fun `empty prefix hides at-prefixed special vars but shows the rest`() {
        val specialVars = SpecialVariables.load(listOf("@teller|Teller number|NUMBER|", "date|Current date|DATE|"))
        val result = SuggestionEngine.computeSuggestions(null, "", emptyList(), specialVars, NO_FUNCTIONS, NO_KEYWORDS, NO_DB)

        assertEquals(listOf("DATE"), result.map { it.insertValue })
    }

    @Test
    fun `typing at brings up the at-prefixed special vars`() {
        val specialVars = SpecialVariables.load(listOf("@teller|Teller number|NUMBER|", "date|Current date|DATE|"))
        val result = SuggestionEngine.computeSuggestions(null, "@t", emptyList(), specialVars, NO_FUNCTIONS, NO_KEYWORDS, NO_DB)

        assertEquals(listOf("@TELLER"), result.map { it.insertValue })
    }

    @Test
    fun `function suggestion shows argument names and inserts an open paren`() {
        val functions = FunctionLayout.load(listOf("round|Rounds a number|NUMBER", "\tval|the value|NUMBER", "\tplaces|decimal places|NUMBER"))
        val result = SuggestionEngine.computeSuggestions(null, "rou", emptyList(), NO_SPECIAL_VARS, functions, NO_KEYWORDS, NO_DB)

        assertEquals(1, result.size)
        assertEquals("ROUND(val, places)", result[0].label)
        assertEquals("ROUND(", result[0].insertValue)
    }

    @Test
    fun `record suggestions match by name prefix`() {
        val db = DatabaseLayout.load(DB_FIXTURE)
        val result = SuggestionEngine.computeSuggestions(null, "acc", emptyList(), NO_SPECIAL_VARS, NO_FUNCTIONS, NO_KEYWORDS, db)

        assertEquals(listOf("ACCOUNT"), result.filter { it.kind == SuggestionKind.RECORD }.map { it.insertValue })
    }

    @Test
    fun `colon after a known record lists its fields, sorted, regardless of any prefix`() {
        val db = DatabaseLayout.load(DB_FIXTURE)
        // "account:num" would merge into a single "account:num" token (real DB-field-name merge
        // rule in RepgenTokenizer), so use a bare trailing colon to keep `current` as the ":" token.
        val tokens = tokenize("account: ")
        val colon = tokens.first { it.str == ":" }

        val result = SuggestionEngine.computeSuggestions(colon, ":", emptyList(), NO_SPECIAL_VARS, NO_FUNCTIONS, NO_KEYWORDS, db)

        assertEquals(listOf("NAME", "NUM"), result.map { it.insertValue })
        assertTrue(result.all { it.kind == SuggestionKind.DB_FIELD })
    }

    @Test
    fun `mid-subfield typing steps back through the colon to find the record`() {
        val db = DatabaseLayout.load(DB_FIXTURE)
        // "num" alone isn't a known field name, so it won't get merged into "account:num" by the
        // tokenizer — it stays as separate "account", ":", "num" tokens, exactly the case this
        // two-step .before traversal (current -> ":" -> "account") exists to handle.
        val tokens = tokenize("account:num")
        val current = tokens.last { it.str == "num" }

        val result = SuggestionEngine.computeSuggestions(current, "num", emptyList(), NO_SPECIAL_VARS, NO_FUNCTIONS, NO_KEYWORDS, db)

        assertEquals(listOf("NUM"), result.map { it.insertValue })
    }

    @Test
    fun `colon after an unknown record yields no suggestions at all`() {
        val db = DatabaseLayout.load(DB_FIXTURE)
        val tokens = tokenize("nope:")
        val colon = tokens.last { it.str == ":" }

        assertTrue(SuggestionEngine.computeSuggestions(colon, ":", emptyList(), NO_SPECIAL_VARS, NO_FUNCTIONS, NO_KEYWORDS, db).isEmpty())
    }
}
