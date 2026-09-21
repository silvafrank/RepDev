package org.repdev.engine

/** Ported from com.repdev.ErrorCheckResult — result of the server error-checking/installing a spec file. */
data class ErrorCheckResult(
    val file: String,
    val errorMessage: String,
    val lineNumber: Int = -1,
    val column: Int = -1,
    val installSize: Int = 0,
    val type: Type,
) {
    enum class Type { ERROR, WARNING, NO_ERROR, INSTALLED_SUCCESSFULLY }
}
