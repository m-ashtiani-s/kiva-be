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
 * @property code stable, machine-readable id in UPPER_SNAKE_CASE (`OUT_OF_STOCK`). The client reacts to it and the
 *   HTTP layer picks the Persian message by it, so never rename a code that is already in use.
 * @property kind decides the HTTP status at the edge.
 * @property field the input field this failure is about, if any (`"phone"`), so a form can highlight it.
 * @property causes the inner failures this one wraps, e.g. the field failures behind an invalid address.
 * @property meta extra values the client needs to react, sent as-is (`"availableQuantity" to 3`,
 *   `"retryAfterSeconds" to 87`). Only plain values: numbers, strings, booleans, lists of them.
 *
 * Deliberately no "rejected value" property: failures end up in HTTP responses and logs,
 * and the raw input may be a password or an OTP code.
 */
abstract class ResultFailure(
    val code: String,
    val kind: FailureKind,
    val field: String? = null,
    val causes: List<ResultFailure> = emptyList(),
    val meta: Map<String, Any> = emptyMap(),
) {
    init {
        require(CODE_FORMAT.matches(code)) { "Failure code must be UPPER_SNAKE_CASE, was '$code'" }
    }

    private companion object {
        val CODE_FORMAT = Regex("[A-Z][A-Z0-9]*(_[A-Z0-9]+)*")
    }
}
