package com.kiva.common.railway

// Operations on Result.
//
// They are extension functions (not members) so that `F` can appear in "in" positions
// without fighting the `out F` variance of Result.
// They are all `inline`, which gives two things inside the lambdas:
//   - `return` exits the calling function (see unwrapOr)
//   - suspend functions can be called when the caller is suspend (e.g. inside a use case)

/** Transforms the value of a [Result.Success]. A [Result.Failure] passes through untouched. */
inline fun <T, R, F : ResultFailure> Result<T, F>.mapOnSuccess(transform: (T) -> R): Result<R, F> =
    when (this) {
        is Result.Success -> Result.success(transform(value))
        is Result.Failure -> this
    }

/** Transforms the failure of a [Result.Failure], e.g. to wrap it in the caller's own failure type. A [Result.Success] passes through. */
inline fun <T, F : ResultFailure, G : ResultFailure> Result<T, F>.mapOnFailure(transform: (F) -> G): Result<T, G> =
    when (this) {
        is Result.Success -> this
        is Result.Failure -> Result.failure(transform(failure))
    }

/**
 * Chains a next step that can itself fail.
 * Use it instead of [mapOnSuccess] when [transform] returns a Result; [mapOnSuccess] would give `Result<Result<..>>`.
 * After the first failure, the following steps are not executed.
 */
inline fun <T, R, F : ResultFailure> Result<T, F>.bind(transform: (T) -> Result<R, F>): Result<R, F> =
    when (this) {
        is Result.Success -> transform(value)
        is Result.Failure -> this
    }

/** Runs [action] on the value (logging, metrics, ...) and returns the same Result unchanged. */
inline fun <T, F : ResultFailure> Result<T, F>.onSuccess(action: (T) -> Unit): Result<T, F> {
    if (this is Result.Success) action(value)
    return this
}

/** Runs [action] on the failure (logging, metrics, ...) and returns the same Result unchanged. */
inline fun <T, F : ResultFailure> Result<T, F>.onFailure(action: (F) -> Unit): Result<T, F> {
    if (this is Result.Failure) action(failure)
    return this
}

/** Collapses both branches into a single value of type [R]. */
inline fun <T, F : ResultFailure, R> Result<T, F>.fold(onSuccess: (T) -> R, onFailure: (F) -> R): R =
    when (this) {
        is Result.Success -> onSuccess(value)
        is Result.Failure -> onFailure(failure)
    }

/**
 * Returns the value, or runs [onFailure], which must leave the function (`return` or `throw`).
 *
 * ```kotlin
 * val sku = Sku.of(raw).unwrapOr { return Result.failure(PlaceOrderFailure.InvalidSku(it)) }
 * // from here on `sku` is a plain Sku, not a Result
 * ```
 */
inline fun <T, F : ResultFailure> Result<T, F>.unwrapOr(onFailure: (F) -> Nothing): T =
    when (this) {
        is Result.Success -> value
        is Result.Failure -> onFailure(failure)
    }
