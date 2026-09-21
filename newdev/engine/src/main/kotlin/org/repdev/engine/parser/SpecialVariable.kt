package org.repdev.engine.parser

/** Ported from com.repdev.parser.SpecialVariable. */
data class SpecialVariable(val name: String, val description: String, val type: String, val len: Int = -1)
