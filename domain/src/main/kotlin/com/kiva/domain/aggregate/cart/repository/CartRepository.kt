package com.kiva.domain.aggregate.cart.repository

import com.kiva.common.valueobject.UserId
import com.kiva.domain.aggregate.cart.entity.Cart
import com.kiva.domain.aggregate.cart.model.valueobject.CartPublicId
import com.kiva.domain.aggregate.session.model.valueobject.TokenHash

/**
 * Carts, stored as rows. Every change goes through a lock on the cart's row ([lockForUser], [lockForGuest]) until the
 * transaction ends: two tabs changing one cart run one after the other, and each sees what the other did.
 */
interface CartRepository {

    /** Locks the customer's cart and reads it; null when they have none yet. */
    fun lockForUser(userId: UserId): Cart?

    /** Locks the guest cart of this token and reads it; null when no guest cart has it (a customer's never does). */
    fun lockForGuest(tokenHash: TokenHash): Cart?

    /**
     * Stores a customer's first cart, unless one is there already: two first requests at the same moment both try, one
     * stores it, and the other's is dropped without an error. Lock it afterwards with [lockForUser].
     */
    fun insertForUserIfAbsent(cart: Cart)

    /** Stores a new guest cart. Its token is new, so no other cart has it. */
    fun insertGuest(cart: Cart)

    /** The cart as it is, for reading only: nothing is locked. */
    fun find(publicId: CartPublicId): Cart?

    /** Writes what changed since it was locked: the cart's own values, and its lines added, changed and removed. */
    fun save(cart: Cart)

    /**
     * Deletes a locked cart and its lines: a guest's, once it went into the customer's after a login. Before the
     * customer's cart is saved: its lines moved over with their ids, and a line id is the shop's, unique in every cart.
     */
    fun delete(cart: Cart)
}
