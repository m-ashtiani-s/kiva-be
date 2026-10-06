package com.kiva.domain.aggregate.order.model.valueobject

import com.kiva.common.railway.FailureKind
import com.kiva.common.railway.Result
import com.kiva.common.railway.ResultFailure
import com.kiva.common.text.normalizeDigits

/**
 * The public code of an order (`KV-218340`): what the shopper sees, types on the tracking page and quotes to support.
 * The order's numeric id never leaves the server; this does. Format from the contract's OrderCode.
 *
 * Here before the Order aggregate itself, because stock is reserved for an order by its code (step 4).
 */
@JvmInline
value class OrderCode private constructor(val value: String) {

    // Second wall, for every way in: [makeNew] already refuses bad input with a Failure, so this only ever fails
    // when stored data is wrong. That is a bug: stop loudly.
    init {
        require(FORMAT.matches(value)) { "Invalid OrderCode '$value'" }
    }

    companion object {
        // matches() checks the whole string, so no ^...$ is needed.
        private val FORMAT = Regex("KV-[0-9]{4,8}")

        /**
         * A code typed by someone: spaces around it are trimmed, letters made uppercase and Persian digits made English,
         * so `kv-218340` and `KV-۲۱۸۳۴۰` work too.
         */
        fun makeNew(raw: String): Result<OrderCode, Failure> {
            val normalized = raw.trim().uppercase().normalizeDigits()
            return if (FORMAT.matches(normalized)) Result.success(OrderCode(normalized)) else Result.failure(Failure.Invalid)
        }

        /** For values read back from storage. Still checked by `init`. */
        fun constructFromPersisted(value: String): OrderCode = OrderCode(value)
    }

    // Always the `orderCode` of the contract's paths and bodies.
    sealed class Failure(code: String) : ResultFailure(code, FailureKind.INVALID, field = "orderCode") {
        data object Invalid : Failure("ORDER_CODE_INVALID")
    }
}
