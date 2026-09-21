package org.repdev.engine.parser

/**
 * Pure-logic port of com.repdev.parser.RepgenParser's `rebuildVars` — scans a
 * token stream for `IDENTIFIER = <type>` declarations inside a DEFINE block
 * and derives each variable's type string via the same lookahead rules as
 * the original (string/date/rate/money/character(N)/array).
 *
 * Dropped from the original: the StyledText redraw-on-change side effect
 * (`asyncExec { txt.redrawRange(...) }`) — that's a UI reaction to "did the
 * var list change," decided by whatever owns the editor, not this scan.
 * `changed` is returned instead so a caller can decide what to do with it.
 */
class VariableRegistry {
    private val allVars = mutableListOf<Variable>()

    /** Name-only mirror for O(1) "is this a known local var" checks — same perf rationale as the original's `lvarNames`. */
    private val names = HashSet<String>()

    val vars: List<Variable> get() = allVars

    fun hasVar(name: String): Boolean = names.contains(name)

    /** Replaces all vars previously recorded for [fileName]. Returns true if the var list for that file actually changed. */
    fun rebuild(fileName: String, data: String, tokens: List<Token>): Boolean {
        val oldVars = allVars.filter { it.filename == fileName }
        allVars.removeAll(oldVars)

        val newVars = mutableListOf<Variable>()
        for (tcur in tokens) {
            val eq = tcur.after
            val typeStart = eq?.after
            if (eq == null || typeStart == null) continue
            if (!(tcur.inDefs && tcur.commentDepth == 0 && !tcur.inString && !tcur.inDate && eq.str == "=")) continue

            var typeToken: Token? = typeStart
            var isConstant = true
            var type = typeStart.str.uppercase()

            if (typeStart.inString) {
                type = "\"" + getFullString(typeStart, data) + "\""
            }

            if (typeToken?.inDate == true) {
                repeat(6) {
                    typeToken = typeToken?.after ?: return@repeat
                    typeToken?.let { type += it.str }
                }
            }

            val afterRate = typeToken?.after
            val afterAfterRate = afterRate?.after
            if (typeToken != null && isNumber(typeToken!!.str) && afterRate?.str == "." && afterAfterRate != null && isNumber(afterAfterRate.str)) {
                repeat(2) {
                    typeToken = typeToken?.after ?: return@repeat
                    typeToken?.let { type += it.str }
                }
            }

            if (typeToken?.str == "$") {
                while (true) {
                    typeToken = typeToken?.after ?: break
                    val t = typeToken!!
                    if (!isNumber(t.str) && t.str != "," && t.str != ".") break
                    type += t.str
                }
            }

            if (typeToken?.str == "character" && typeToken?.after?.str == "(") {
                var curTok: Token? = typeToken
                while (curTok != null && curTok.str != ")") {
                    curTok = curTok.after
                    curTok?.let { type += it.str.uppercase() }
                }
                typeToken = curTok
                isConstant = false
            }

            if (VariableType.entries.any { it.name.equals(type, ignoreCase = true) }) {
                isConstant = false
            }

            if (typeToken?.after?.str == "array") {
                while (true) {
                    typeToken = typeToken?.after ?: break
                    val t = typeToken!!
                    if (!isNumber(t.str) && t.str != ")" && t.str != "(" && t.str != "array" && t.str != ",") break
                    type += (if (isNumber(t.str) || t.str == ")") "" else " ") + t.str.uppercase()
                }
            }

            val newVar = Variable(tcur.str, fileName, tcur.start, type)
            newVar.constant = isConstant
            newVars.add(newVar)
        }

        var changed = oldVars.size != newVars.size
        if (!changed) {
            changed = newVars.any { v -> oldVars.none { it == v } }
        }

        allVars.addAll(newVars)
        names.clear()
        allVars.mapTo(names) { it.name }

        return changed
    }
}
