package com.kiva.common.railway

/**
 * The outcome of an operation that can fail in an expected way:
 * either [Success] with a value, or [Failure] with the reason.
 *
 * - `sealed`: these two are the only possible kinds, so a `when` over a Result needs no `else`.
 * - `out T, out F`: a `Result<Int, X>` can be used where a `Result<Number, X>` is expected.
 *   Together with `Nothing` this is what lets `Result.success(x)` be returned from a function
 *   declared as `Result<X, SomeFailure>`.
 *
 * Note: Kotlin's standard library also has a `kotlin.Result`. Always import this one explicitly.
 */
sealed class Result<out T, out F : ResultFailure> {

    /** No failure exists here, so the failure type is `Nothing`. */
    data class Success<out T>(val value: T) : Result<T, Nothing>()

    /** No value exists here, so the value type is `Nothing`. */
    data class Failure<out F : ResultFailure>(val failure: F) : Result<Nothing, F>()

    val isSuccess: Boolean get() = this is Success
    val isFailure: Boolean get() = this is Failure

    /** The value, or `null` when this is a [Failure]. */
    fun getOrNull(): T? = (this as? Success)?.value

    /** The failure, or `null` when this is a [Success]. */
    fun failureOrNull(): F? = (this as? Failure)?.failure

    companion object {
        fun <T> success(value: T): Result<T, Nothing> = Success(value)

        fun <F : ResultFailure> failure(failure: F): Result<Nothing, F> = Failure(failure)
    }
}
