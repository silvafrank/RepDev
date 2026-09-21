package org.repdev.engine.parser

/** Ported from com.repdev.parser.Field: one field of a DatabaseLayout Record. */
data class Field(
    val name: String,
    val description: String,
    val fieldNumber: Int,
    val variableType: VariableType,
    val len: Int,
) : Comparable<Field> {
    override fun compareTo(other: Field): Int = name.compareTo(other.name, ignoreCase = true)
    override fun toString(): String = "Field: $name"
}
