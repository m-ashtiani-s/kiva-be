package com.kiva.domain.aggregate.inventory.model.valueobject

import com.kiva.common.railway.FailureKind
import com.kiva.common.railway.Result
import com.kiva.common.railway.ResultFailure

/**
 * A change to what is in the warehouse, in items: positive came in, negative went out (the contract's `delta`).
 *
 * Never zero, because a change of nothing is not a change. At most [MAX_ABS] either way: no delivery of bags is that
 * big, so a bigger number is a typo (a price typed into the count).
 */
@JvmInline
value class StockDelta private constructor(val value: Int) {

    // Second wall, for every way in: [makeNew] already refuses bad input with a Failure, so this only ever fails
    // when stored data is wrong. That is a bug: stop loudly.
    init {
        require(isValid(value)) { "Invalid StockDelta $value" }
    }

    val isIncrease: Boolean get() = value > 0

    companion object {
        const val MAX_ABS = 10_000

        fun makeNew(raw: Int): Result<StockDelta, Failure> =
            if (isValid(raw)) Result.success(StockDelta(raw)) else Result.failure(Failure.Invalid)

        /** For values read back from storage. Still checked by `init`. */
        fun constructFromPersisted(value: Int): StockDelta = StockDelta(value)

        private fun isValid(value: Int): Boolean = value != 0 && value in -MAX_ABS..MAX_ABS
    }

    // Always the `delta` field of the contract's InventoryAdjustmentRequest.
    sealed class Failure(code: String, meta: Map<String, Any>) :
        ResultFailure(code, FailureKind.INVALID, field = "delta", meta = meta) {
        data object Invalid : Failure("STOCK_DELTA_INVALID", mapOf("maxAbs" to MAX_ABS))
    }
}
