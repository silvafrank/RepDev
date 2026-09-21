package org.repdev.engine.parser

/**
 * Ported from com.repdev.parser.Variable. `equals` is by name only, matching
 * the original (used to detect duplicate/shadowing declarations across
 * files) — but the original overrode `equals` without `hashCode`, a latent
 * bug (breaks in any hash-based collection) fixed here since it's a one-liner.
 */
class Variable(val name: String, val filename: String, pos: Int, var type: String) : Comparable<Variable> {
    var pos: Int = pos
        private set
    var constant: Boolean = false

    constructor(old: Variable) : this(old.name, old.filename, old.pos, old.type) {
        constant = old.constant
    }

    fun incPos(amount: Int) {
        pos += amount
    }

    override fun toString(): String = "$pos:$name"
    override fun equals(other: Any?): Boolean = other is Variable && other.name == name
    override fun hashCode(): Int = name.hashCode()
    override fun compareTo(other: Variable): Int = name.compareTo(other.name, ignoreCase = true)
}
