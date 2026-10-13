package com.kiva.domain.aggregate.cart.model.valueobject

/**
 * The public id of a cart (`cart_` and 10 random letters and digits): the contract's `Cart.id`, and the cart's
 * identity. Random, so it says nothing about how many carts there are. Handed out by CartIdGenerator.
 *
 * Only for showing and for finding the cart's row: it gives access to nothing. A guest reaches their cart with its
 * CartToken, a customer with their login.
 */
@JvmInline
value class CartPublicId private constructor(val value: String) {

    // Never typed by anyone: made by the generator or read back from storage, so a wrong one is a bug.
    init {
        require(FORMAT.matches(value)) { "Invalid CartPublicId '$value'" }
    }

    companion object {
        // matches() checks the whole string, so no ^...$ is needed.
        private val FORMAT = Regex("cart_[0-9A-Za-z]{10}")

        /** For values from the generator or read back from storage. Still checked by `init`. */
        fun constructFromPersisted(value: String): CartPublicId = CartPublicId(value)
    }
}
