package com.kiva.common.valueobject

import com.kiva.common.railway.FailureKind
import com.kiva.common.railway.Result
import com.kiva.common.railway.ResultFailure

/**
 * How many items of one thing: a cart line, an order line, what an order reserves in stock. At least 1, because
 * "zero of it" is not a line but no line at all. No shop order holds [MAX] of one bag, so more is a typo; it also
 * keeps every sum of quantities far from overflowing.
 */
@JvmInline
value class Quantity private constructor(val value: Int) {

    // Second wall, for every way in: [makeNew] already refuses bad input with a Failure, so this only ever fails
    // when stored data is wrong. That is a bug: stop loudly.
    init {
        require(value in 1..MAX) { "Invalid Quantity $value" }
    }

    companion object {
        const val MAX = 10_000

        fun makeNew(raw: Int): Result<Quantity, Failure> =
            if (raw in 1..MAX) Result.success(Quantity(raw)) else Result.failure(Failure.Invalid)

        /** For values read back from storage. Still checked by `init`. */
        fun constructFromPersisted(value: Int): Quantity = Quantity(value)
    }

    // The contract's cart and order lines call the field `quantity`.
    sealed class Failure(code: String, meta: Map<String, Any>) :
        ResultFailure(code, FailureKind.INVALID, field = "quantity", meta = meta) {
        data object Invalid : Failure("QUANTITY_INVALID", mapOf("max" to MAX))
    }
}
