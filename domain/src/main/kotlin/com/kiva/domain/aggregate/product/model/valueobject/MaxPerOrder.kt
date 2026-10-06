package com.kiva.domain.aggregate.product.model.valueobject

import com.kiva.common.railway.FailureKind
import com.kiva.common.railway.Result
import com.kiva.common.railway.ResultFailure

/**
 * How many of one product a single order may hold (contract `maxPerOrder`, at least 1, 5 when not given).
 * The cart's real limit is the smaller of this and what is in stock.
 */
@JvmInline
value class MaxPerOrder private constructor(val value: Int) {

    // Second wall, for every way in: [makeNew] already refuses bad input with a Failure, so this only ever fails
    // when stored data is wrong. That is a bug: stop loudly.
    init {
        require(value >= 1) { "Invalid MaxPerOrder $value" }
    }

    companion object {
        val DEFAULT = MaxPerOrder(5)

        fun makeNew(raw: Int): Result<MaxPerOrder, Failure> =
            if (raw >= 1) Result.success(MaxPerOrder(raw)) else Result.failure(Failure.Invalid)

        /** For values read back from storage. Still checked by `init`. */
        fun constructFromPersisted(value: Int): MaxPerOrder = MaxPerOrder(value)
    }

    // Always the `maxPerOrder` field of the contract's ProductUpsertRequest.
    sealed class Failure(code: String) : ResultFailure(code, FailureKind.INVALID, field = "maxPerOrder") {
        data object Invalid : Failure("MAX_PER_ORDER_INVALID")
    }
}
