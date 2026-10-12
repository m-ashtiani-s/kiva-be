package com.kiva.domain.aggregate.cart.model.policy

import java.time.Duration

/**
 * How long a guest's cart is kept untouched (roadmap section 15). Every visit without a token makes a cart, so empty
 * ones go soon; a guest's cart with bags in it waits a month for its guest. A customer's cart is never removed: it is
 * part of their account.
 */
object CartRetentionPolicy {

    /** An empty guest cart untouched this long is removed. */
    val EMPTY_GUEST_CART: Duration = Duration.ofDays(1)

    /** Any guest cart untouched this long is removed. */
    val GUEST_CART: Duration = Duration.ofDays(30)
}
