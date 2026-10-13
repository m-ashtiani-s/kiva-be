package com.kiva.domain.aggregate.cart.model.valueobject

import com.kiva.common.railway.FailureKind
import com.kiva.common.railway.Result
import com.kiva.common.railway.ResultFailure

/**
 * How many of one color a cart line holds: 1 to [MAX]. The contract takes 1 to 20 per request, and a line never holds
 * more either (cartitem_tbl's CHECK), so adding 15 and then 10 stops at 20.
 */
@JvmInline
value class CartQuantity internal constructor(val value: Int) {

    // Second wall, for every way in: [makeNew] already refuses bad input with a Failure, so this only ever fails
    // when stored data or the cart's own arithmetic is wrong. That is a bug: stop loudly.
    init {
        require(value in 1..MAX) { "Invalid CartQuantity $value" }
    }

    companion object {
        const val MAX = 20

        fun makeNew(raw: Int): Result<CartQuantity, Failure> =
            if (raw in 1..MAX) Result.success(CartQuantity(raw)) else Result.failure(Failure.Invalid)

        /** For values read back from storage. Still checked by `init`. */
        fun constructFromPersisted(value: Int): CartQuantity = CartQuantity(value)
    }

    // Always the `quantity` of the contract's cart requests. Same code and text as the shared Quantity, with this max.
    sealed class Failure(code: String) : ResultFailure(code, FailureKind.INVALID, field = "quantity", meta = mapOf("max" to MAX)) {
        data object Invalid : Failure("QUANTITY_INVALID")
    }
}
