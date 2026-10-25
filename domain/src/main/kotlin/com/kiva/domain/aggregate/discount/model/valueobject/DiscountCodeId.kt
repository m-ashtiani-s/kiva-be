package com.kiva.domain.aggregate.discount.model.valueobject

import com.kiva.common.railway.FailureKind
import com.kiva.common.railway.Result
import com.kiva.common.railway.ResultFailure

/** The id of a discount code (its row's), given out by the repository when the panel makes one. */
@JvmInline
value class DiscountCodeId private constructor(val value: Long) {

    init {
        require(value > 0) { "Invalid DiscountCodeId $value" }
    }

    companion object {
        /** An id as a path sent it; the panel answers a malformed one as it answers one nobody has (404). */
        fun makeNew(raw: Long): Result<DiscountCodeId, Failure> = if (raw > 0) Result.success(DiscountCodeId(raw)) else Result.failure(Failure.Invalid)

        /** For values read back from storage. Still checked by `init`. */
        fun constructFromPersisted(value: Long): DiscountCodeId = DiscountCodeId(value)
    }

    sealed class Failure(code: String) : ResultFailure(code, FailureKind.INVALID) {
        data object Invalid : Failure("DISCOUNT_CODE_ID_INVALID")
    }
}

/** How a code takes money off: a percent of what it covers, or a fixed amount in toman (contract `type`). */
enum class DiscountType {
    PERCENT,
    FIXED,
}

/**
 * Where an order's use of a code is: held while the order waits for its money (it counts against the code's limits all
 * the same), confirmed once paid, released when the order expires (and the code may be used again).
 */
enum class DiscountRedemptionStatus {
    HELD,
    CONFIRMED,
    RELEASED,
}
