package org.repdev.engine.parser

/** Ported from com.repdev.parser.VariableType. Codes match Symitar's db.txt field-type numbers exactly — don't renumber. */
enum class VariableType(val code: Int) {
    CHARACTER(0),
    RATE(2),
    DATE(3),
    NUMBER(4),
    CODE(5),
    MONEY(7),
    FLOAT(10),
    BOOLEAN(100),
    NULL(-1),
}
