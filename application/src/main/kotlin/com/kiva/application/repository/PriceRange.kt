package com.kiva.application.repository

import com.kiva.common.railway.FailureKind
import com.kiva.common.railway.Result
import com.kiva.common.railway.ResultFailure
import com.kiva.common.valueobject.Money

/** The price filter of the shop's list (`minPrice`, `maxPrice`), both ends included. Either end may be left open. */
@ConsistentCopyVisibility
data class PriceRange private constructor(
    val min: Money?,
    val max: Money?,
) {
    companion object {
        val ANY = PriceRange(null, null)

        fun makeNew(min: Money?, max: Money?): Result<PriceRange, Failure> =
            if (min != null && max != null && min > max) Result.failure(Failure.MinAboveMax) else Result.success(PriceRange(min, max))
    }

    // The client marks the upper end: it is the one usually typed second.
    sealed class Failure(code: String) : ResultFailure(code, FailureKind.INVALID, field = "maxPrice") {
        data object MinAboveMax : Failure("PRICE_RANGE_INVALID")
    }
}
