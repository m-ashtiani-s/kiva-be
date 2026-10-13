package com.kiva.domain.aggregate.cart.repository

import com.kiva.domain.aggregate.cart.model.valueobject.CartItemId
import com.kiva.domain.aggregate.cart.model.valueobject.CartPublicId
import com.kiva.domain.aggregate.cart.model.valueobject.CartToken

/**
 * Hands out the random parts of carts: public ids for carts and their lines, and guest tokens. Implemented with a
 * secure random source in infrastructure; the database's unique keys are the wall behind a (practically impossible)
 * repeat.
 */
interface CartIdGenerator {
    fun nextCartPublicId(): CartPublicId

    fun nextCartItemId(): CartItemId

    /** A new secret for a guest cart: 32 random bytes. */
    fun nextGuestToken(): CartToken
}
