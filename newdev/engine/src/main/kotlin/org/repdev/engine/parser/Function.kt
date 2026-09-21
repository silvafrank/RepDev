package org.repdev.engine.parser

/**
 * Ported from com.repdev.parser.Function. `arguments` stays a mutable list —
 * FunctionLayout builds a Function then appends to it as it reads each
 * following indented line — everything else is fixed at construction.
 */
class Function(val name: String, val description: String, val returnTypes: List<VariableType>) {
    val arguments: MutableList<Argument> = mutableListOf()
}
