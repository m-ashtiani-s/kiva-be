package com.kiva.domain.aggregate.cart.model.valueobject

import com.kiva.common.railway.FailureKind
import com.kiva.common.railway.Result
import com.kiva.common.railway.ResultFailure

/**
 * The public id of a cart line (`ci_` and 10 random letters and digits): the contract's `CartItem.id` and the
 * `{itemId}` of its paths. Random, so it says nothing about how many lines other carts have. Handed out by
 * CartIdGenerator; a guest's line keeps it when it moves into the customer's cart.
 */
@JvmInline
value class CartItemId private constructor(val value: String) {

    // Second wall, for every way in: [makeNew] already refuses bad input with a Failure, so this only ever fails
    // when stored or generated data is wrong. That is a bug: stop loudly.
    init {
        require(FORMAT.matches(value)) { "Invalid CartItemId '$value'" }
    }

    companion object {
        // matches() checks the whole string, so no ^...$ is needed.
        private val FORMAT = Regex("ci_[0-9A-Za-z]{10}")

        /** From a path. The route answers a malformed one with CART_ITEM_NOT_FOUND, like any id not in the cart. */
        fun makeNew(raw: String): Result<CartItemId, Failure> =
            if (FORMAT.matches(raw)) Result.success(CartItemId(raw)) else Result.failure(Failure.Invalid)

        /** For values from the generator or read back from storage. Still checked by `init`. */
        fun constructFromPersisted(value: String): CartItemId = CartItemId(value)
    }

    sealed class Failure(code: String) : ResultFailure(code, FailureKind.INVALID, field = "itemId") {
        data object Invalid : Failure("CART_ITEM_ID_INVALID")
    }
}
