package com.kiva.infrastructure.httpserver.controller.httprest.model.request

import kotlinx.serialization.Serializable

/** The contract's AddCartItemRequest. Without a quantity, one. The use case checks both numbers. */
@Serializable
data class AddCartItemHttpRequest(val variantId: Long, val quantity: Int = 1)

/** The contract's UpdateCartItemRequest: a new quantity, a new color of the same bag, or both. */
@Serializable
data class UpdateCartItemHttpRequest(val quantity: Int? = null, val variantId: Long? = null)

/** The body of `PUT /cart/shipping-method`. The use case checks the method. */
@Serializable
data class ChooseShippingMethodHttpRequest(val method: String)

/** The body of `PUT /cart/reservation`. */
@Serializable
data class SetCartReservationHttpRequest(val enabled: Boolean)

/**
 * The body of `POST /cart/merge`. The token is required; one that reaches no guest cart (malformed included) merges
 * nothing and is no error.
 */
@Serializable
data class MergeCartHttpRequest(val guestCartToken: String) {
    // The token opens a cart, if this is ever printed.
    override fun toString(): String = "MergeCartHttpRequest(guestCartToken=****)"
}
