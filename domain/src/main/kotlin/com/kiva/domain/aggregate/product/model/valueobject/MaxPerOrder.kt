package com.kiva.domain.aggregate.product.model.valueobject

import com.kiva.common.railway.FailureKind
import com.kiva.common.railway.Result
import com.kiva.common.railway.ResultFailure

/**
 * How many of one product a single order may hold (contract `maxPerOrder`, 1 to [MAX], 5 when not given).
 * The cart's real limit is the smaller of this and what is in stock; a cart row never holds more than [MAX].
 */
@JvmInline
value class MaxPerOrder private constructor(val value: Int) {

    // Second wall, for every way in: [makeNew] already refuses bad input with a Failure, so this only ever fails
    // when stored data is wrong. That is a bug: stop loudly.
    init {
        require(value in 1..MAX) { "Invalid MaxPerOrder $value" }
    }

    companion object {
        const val MAX = 20

        val DEFAULT = MaxPerOrder(5)

        fun makeNew(raw: Int): Result<MaxPerOrder, Failure> =
            if (raw in 1..MAX) Result.success(MaxPerOrder(raw)) else Result.failure(Failure.Invalid)

        /** For values read back from storage. Still checked by `init`. */
        fun constructFromPersisted(value: Int): MaxPerOrder = MaxPerOrder(value)
    }

    // Always the `maxPerOrder` field of the contract's ProductUpsertRequest.
    sealed class Failure(code: String) : ResultFailure(code, FailureKind.INVALID, field = "maxPerOrder", meta = mapOf("max" to MAX)) {
        data object Invalid : Failure("MAX_PER_ORDER_INVALID")
    }
}
