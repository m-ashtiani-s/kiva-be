package com.kiva.common.railway

// zip: combine several independent Results into one.
//
// Unlike bind (which stops at the first failure), zip checks every input and collects ALL failures,
// so a form with a wrong postal code AND a wrong mobile number reports both at once.
// The collected failures are handed to [onFailure], which wraps them as `causes` of one failure.

inline fun <A, B, R, F : ResultFailure> Result.Companion.zip(
    a: Result<A, ResultFailure>,
    b: Result<B, ResultFailure>,
    onFailure: (causes: List<ResultFailure>) -> F,
    combine: (A, B) -> R,
): Result<R, F> {
    if (a is Result.Success && b is Result.Success) return success(combine(a.value, b.value))
    return failure(onFailure(listOfNotNull(a.failureOrNull(), b.failureOrNull())))
}

inline fun <A, B, C, R, F : ResultFailure> Result.Companion.zip(
    a: Result<A, ResultFailure>,
    b: Result<B, ResultFailure>,
    c: Result<C, ResultFailure>,
    onFailure: (causes: List<ResultFailure>) -> F,
    combine: (A, B, C) -> R,
): Result<R, F> {
    if (a is Result.Success && b is Result.Success && c is Result.Success) {
        return success(combine(a.value, b.value, c.value))
    }
    return failure(onFailure(listOfNotNull(a.failureOrNull(), b.failureOrNull(), c.failureOrNull())))
}
