package com.kiva.common.railway

/**
 * Base type of every failure a [Result] can carry.
 *
 * Concrete failures are usually `data object`s or `data class`es inside a sealed class:
 * ```kotlin
 * sealed class SkuFailure(code: String) : ResultFailure(code, FailureKind.INVALID, field = "sku") {
 *     data object Blank : SkuFailure("SKU_BLANK")
 *     data class TooLong(val length: Int) : SkuFailure("SKU_TOO_LONG")
 * }
 * ```
 *
 * @property code stable, machine-readable id in UPPER_SNAKE_CASE (`SKU_BLANK`). Clients map it to a message,
 *   so never rename a code that is already in use.
 * @property kind decides the HTTP status at the edge.
 * @property field the input field this failure is about, if any (`"mobile"`), so a form can highlight it.
 * @property causes the inner failures this one wraps, e.g. the value-object failures behind an `INVALID_ADDRESS`.
 *
 * Deliberately no "rejected value" property: failures end up in HTTP responses and logs,
 * and the raw input may be a password or an OTP code. Put safe context in the subclass instead (`TooLong(length)`).
 */
abstract class ResultFailure(
    val code: String,
    val kind: FailureKind,
    val field: String? = null,
    val causes: List<ResultFailure> = emptyList(),
) {
    init {
        require(CODE_FORMAT.matches(code)) { "Failure code must be UPPER_SNAKE_CASE, was '$code'" }
    }

    private companion object {
        val CODE_FORMAT = Regex("[A-Z][A-Z0-9]*(_[A-Z0-9]+)*")
    }
}
