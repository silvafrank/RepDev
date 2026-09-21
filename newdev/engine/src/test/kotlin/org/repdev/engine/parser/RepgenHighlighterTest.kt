package org.repdev.engine.parser

import kotlin.test.Test
import kotlin.test.assertEquals

private val DB = DatabaseLayout.load(
    listOf(
        "***|ACCOUNT|Account Record",
        "ACCT:NUM|Account Number|1|4|10",
    ),
)
private val FUNCTIONS = FunctionLayout.load(listOf("substr|substring|CHARACTER,NUMBER,NUMBER"))
private val KEYWORDS = KeywordLayout.load(listOf("if|conditional|if x then"))
private val SPECIAL_VARS = SpecialVariables.load(listOf("sysdate|current date|CHARACTER|10"))

/** Classifies every token in [src] (no local vars — see the dedicated define-block test for those). */
private fun classifyAll(src: String): List<Pair<String, HighlightCategory>> {
    val tk = RepgenTokenizer(DB)
    tk.parse(src, 0, src.length, 0)
    val vars = VariableRegistry()
    return tk.tokens.map { it.str to RepgenHighlighter.classify(it, DB, FUNCTIONS, KEYWORDS, SPECIAL_VARS, vars) }
}

class RepgenHighlighterTest {
    @Test
    fun `comments and task tags, case-insensitive since the tokenizer lowercases identifiers`() {
        val lower = classifyAll("[ todo: fix this ]").toMap()
        assertEquals(HighlightCategory.TASK, lower["todo"])
        assertEquals(HighlightCategory.COMMENT, lower["fix"])

        val upper = classifyAll("[ TODO: fix this ]").toMap()
        assertEquals(HighlightCategory.TASK, upper["todo"]) // "TODO" tokenizes to "todo"
    }

    @Test
    fun `strings and numbers`() {
        val result = classifyAll("\"abc\" 123").toMap()
        assertEquals(HighlightCategory.STRING, result["abc"])
        assertEquals(HighlightCategory.NUMBER, result["123"])
    }

    @Test
    fun `decimal literals tokenize as separate digit runs, so only the digits get colored, not the dot`() {
        // Quirk carried over from the original: RepgenTokenizer never merges "45" "." "6" into one
        // token, and NUMBER_PATTERN only ever sees one token at a time — so a decimal literal ends
        // up with its integer and fractional parts colored and the "." itself left NORMAL.
        val result = classifyAll("45.6").toMap()
        assertEquals(HighlightCategory.NUMBER, result["45"])
        assertEquals(HighlightCategory.NUMBER, result["6"])
        assertEquals(HighlightCategory.NORMAL, result["."])
    }

    @Test
    fun `db record and field structural highlighting, valid and invalid`() {
        val valid = classifyAll("account:acct:num").toMap()
        assertEquals(HighlightCategory.STRUCT1, valid["account"])
        assertEquals(HighlightCategory.STRUCT2, valid["acct:num"])

        val invalid = classifyAll("bogus:").toMap()
        assertEquals(HighlightCategory.STRUCT1_INVALID, invalid["bogus"])
    }

    @Test
    fun `functions keywords and special vars`() {
        val result = classifyAll("substr(1) if sysdate").toMap()
        assertEquals(HighlightCategory.FUNCTION, result["substr"])
        assertEquals(HighlightCategory.KEYWORD, result["if"])
        assertEquals(HighlightCategory.VARIABLE, result["sysdate"])
    }

    @Test
    fun `local variables from a define block`() {
        val src = "define\n  foo = 1\nend\nfoo\n"
        val tk = RepgenTokenizer(DB)
        tk.parse(src, 0, src.length, 0)
        val vars = VariableRegistry()
        vars.rebuild("test.pro", src, tk.tokens)

        // The bare "foo" reference after the define block should classify as a variable.
        val lastFoo = tk.tokens.last { it.str == "foo" }
        assertEquals(HighlightCategory.VARIABLE, RepgenHighlighter.classify(lastFoo, DB, FUNCTIONS, KEYWORDS, SPECIAL_VARS, vars))
    }

    @Test
    fun `plain identifier falls back to normal`() {
        val result = classifyAll("somevar").toMap()
        assertEquals(HighlightCategory.NORMAL, result["somevar"])
    }
}
