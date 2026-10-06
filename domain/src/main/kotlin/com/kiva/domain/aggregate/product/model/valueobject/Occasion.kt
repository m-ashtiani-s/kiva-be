package com.kiva.domain.aggregate.product.model.valueobject

import com.kiva.common.railway.FailureKind
import com.kiva.common.railway.Result
import com.kiva.common.railway.ResultFailure

/** What a bag suits («مناسب برای»), for the spec table and the occasion filter. Values from the API contract. */
enum class Occasion {
    /** روزمره */
    DAILY,

    /** دانشگاه */
    UNIVERSITY,

    /** محل کار */
    WORK,

    /** رسمی */
    FORMAL,

    /** مهمانی و مجالس */
    PARTY,

    /** سفر */
    TRAVEL,

    /** خرید */
    SHOPPING,

    /** دورهمی */
    GATHERING,
    ;

    companion object {
        /** The occasion as the contract sends it (`DAILY`), exactly; anything else is OCCASION_INVALID. */
        fun makeNew(raw: String): Result<Occasion, Failure> =
            entries.firstOrNull { it.name == raw }?.let { Result.success(it) } ?: Result.failure(Failure.Invalid)
    }

    // No field here: it is one item of the `occasions` list, and the caller names which.
    sealed class Failure(code: String) : ResultFailure(code, FailureKind.INVALID) {
        data object Invalid : Failure("OCCASION_INVALID")
    }
}
