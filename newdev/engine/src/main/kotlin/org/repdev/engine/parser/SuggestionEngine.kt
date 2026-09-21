package org.repdev.engine.parser

enum class SuggestionKind { DB_FIELD, VARIABLE, SPECIAL_VARIABLE, FUNCTION, KEYWORD, RECORD }

/** One row of the autocomplete popup. [insertValue] is what gets typed in on accept, [label] is the display text. */
data class Suggestion(val label: String, val insertValue: String, val kind: SuggestionKind, val tooltip: String)

/**
 * Pure-logic port of `SuggestShell.update()`'s non-snippet suggestion list: given where the caret
 * is (as [current]/[tokenStr], the token ending at the caret and its text — `""` means "list
 * everything", e.g. right after `=`/`:`), returns the ranked, filtered candidate list. Everything
 * SWT (popup positioning, `Table`/`StyleRange` painting, key handling for
 * accept/dismiss/arrow-navigate) stays in the UI layer that calls this — same split as
 * `RepgenHighlighter`/`RepgenDiagnostics`.
 *
 * Dropped from the original: snippet-mode suggestions (`SnippetManager`/`Snippet` — that whole
 * subsystem isn't ported yet, see MODERNIZATION_PLAN.md's dialog order) and the rich
 * bold/italic `StyleRange` tooltip formatting (a plain-string [Suggestion.tooltip] instead — a
 * Compose tooltip can restyle at render time same as `RepgenHighlighter` dropped
 * `specialBackground`). The caller also still owns the original's "don't open in a
 * comment/string/date" guard — that needs live caret/StyledText state this function never sees.
 */
object SuggestionEngine {
    fun computeSuggestions(
        current: Token?,
        tokenStr: String,
        vars: List<Variable>,
        specialVars: SpecialVariables,
        functions: FunctionLayout,
        keywords: KeywordLayout,
        db: DatabaseLayout,
    ): List<Suggestion> {
        val isSubfieldContext = current != null && (tokenStr == ":" || current.before?.str == ":")
        if (isSubfieldContext) {
            var record = current!!.before
            if (current.before?.str == ":") record = record?.before
            val recordName = record?.str ?: return emptyList()
            if (!db.containsRecordName(recordName)) return emptyList()
            val dRecord = db.getRecordByName(recordName) ?: return emptyList()

            // Original sorts dRecord's field list in place (via an aliased reference) before
            // iterating it unsorted — same end result, done explicitly instead of by that alias trick.
            return dRecord.fields.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name })
                .filter { tokenStr == ":" || it.name.lowercase().startsWith(tokenStr) }
                .map { field ->
                    val lenSuffix = if (field.len != -1) "(${field.len})" else ""
                    Suggestion(
                        label = "${field.name.uppercase()}   ${field.variableType}",
                        insertValue = field.name.uppercase(),
                        kind = SuggestionKind.DB_FIELD,
                        tooltip = "${field.name.uppercase()}\nType: ${field.variableType}$lenSuffix\n" +
                            "Field Number: ${field.fieldNumber}\n\n${field.description}",
                    )
                }
        }

        val prefix = if (tokenStr == "=" || tokenStr == ":") "" else tokenStr
        val result = mutableListOf<Suggestion>()

        for (v in vars.sorted()) {
            if (!v.name.lowercase().startsWith(prefix)) continue
            val typeText = if (v.constant) v.type else v.type.uppercase()
            result.add(
                Suggestion(
                    label = "${v.name.uppercase()}   $typeText",
                    insertValue = v.name.uppercase(),
                    kind = SuggestionKind.VARIABLE,
                    tooltip = "${v.name.uppercase()}\n" +
                        (if (v.constant) "Constant Value: ${v.type}" else "Type: ${v.type.uppercase()}") +
                        "\nFile: ${v.filename}",
                ),
            )
        }

        // Only show the (many) "@"-prefixed system vars once the user's actually typed something —
        // otherwise every one of them would crowd out everything else in the empty-prefix list.
        for (sv in specialVars.vars) {
            val matches = (prefix.isNotEmpty() && sv.name.lowercase().startsWith(prefix)) ||
                (prefix.isEmpty() && !sv.name.startsWith("@"))
            if (!matches) continue
            result.add(
                Suggestion(
                    label = "${sv.name.uppercase()}   ${sv.type}",
                    insertValue = sv.name.uppercase(),
                    kind = SuggestionKind.SPECIAL_VARIABLE,
                    tooltip = "${sv.name.uppercase()}\nType: ${sv.type.uppercase()}\nSystem Variable\n\n${sv.description}",
                ),
            )
        }

        // Inside a function call's "(", complete on the function name before it, not "(" itself.
        val funcName = if (tokenStr == "(" && current?.before != null) current.before!!.str else prefix

        for (fn in functions.list) {
            if (!fn.name.lowercase().startsWith(funcName)) continue
            val argNames = fn.arguments.joinToString(", ") { it.shortName }
            result.add(
                Suggestion(
                    label = "${fn.name.uppercase()}($argNames)",
                    insertValue = "${fn.name.uppercase()}(",
                    kind = SuggestionKind.FUNCTION,
                    tooltip = "${fn.name.uppercase()}\n${fn.description}\n\nArguments:\n" +
                        fn.arguments.joinToString("") { "\t${it.shortName} - ${it.description} ${it.types}\n" } +
                        "\nReturns: ${fn.returnTypes}",
                ),
            )
        }

        for (kw in keywords.list) {
            if (!kw.name.lowercase().startsWith(funcName)) continue
            result.add(
                Suggestion(
                    label = kw.name,
                    insertValue = kw.name,
                    kind = SuggestionKind.KEYWORD,
                    tooltip = "${kw.name}\n${kw.description}\n\n\nExample: \n${kw.example}",
                ),
            )
        }

        for (record in db.flatRecords) {
            if (!record.name.lowercase().startsWith(prefix)) continue
            result.add(
                Suggestion(
                    label = record.name.uppercase(),
                    insertValue = record.name.uppercase(),
                    kind = SuggestionKind.RECORD,
                    tooltip = "${record.name.uppercase()}\nParent: ${record.root?.name ?: "None"}\n\n${record.description}",
                ),
            )
        }

        return result
    }
}
