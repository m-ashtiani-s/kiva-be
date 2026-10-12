package com.kiva.domain.aggregate.cart.model.valueobject

import com.kiva.common.valueobject.UserId
import com.kiva.domain.aggregate.session.model.valueobject.TokenHash

/**
 * Whose cart it is: a customer's (one cart each, found by the login) or a guest's (found by the hash of its token).
 * Exactly one of the two, which cart_tbl checks as well.
 */
sealed interface CartOwner {

    data class User(val userId: UserId) : CartOwner {
        override fun toString(): String = "User(${userId.value})"
    }

    /** [tokenHash] is all that is kept of the guest's token; its toString shows no bytes. */
    data class Guest(val tokenHash: TokenHash) : CartOwner {
        override fun toString(): String = "Guest"
    }
}
