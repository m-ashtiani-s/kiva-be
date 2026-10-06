package com.kiva.common.railway

/**
 * A failure tied to a specific input field, keeping the code, kind, causes and meta of the original failure.
 *
 * Shared value objects don't know which field they came from: a [com.kiva.common.valueobject.Money] can be a
 * "price", a "shippingCost" or a "discount". The caller knows, so it names the field with [forField].
 */
data class FieldFailure(val name: String, val original: ResultFailure) :
    ResultFailure(original.code, original.kind, field = name, causes = original.causes, meta = original.meta)

/**
 * Names the input field a failure belongs to, so the client can highlight it:
 * ```kotlin
 * Money.makeNew(toman = request.price).forField("price")   // MONEY_NEGATIVE now comes with field = "price"
 * ```
 */
fun <T> Result<T, ResultFailure>.forField(name: String): Result<T, FieldFailure> = mapOnFailure { FieldFailure(name, it) }
