package org.repdev.engine.parser

/** Ported from com.repdev.parser.Argument. Immutable — nothing ever mutated a constructed one. */
data class Argument(val shortName: String, val description: String, val types: List<VariableType>)
