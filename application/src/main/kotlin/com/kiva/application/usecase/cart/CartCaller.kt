package com.kiva.application.usecase.cart

import com.kiva.common.valueobject.UserId

/**
 * Whose cart a request is about, as the HTTP layer found it: a customer (a valid access token; any cart token is then
 * ignored) or a guest (with the `X-Cart-Token` header as it arrived, or none).
 */
sealed interface CartCaller {

    data class Customer(val userId: UserId) : CartCaller {
        override fun toString(): String = "Customer(${userId.value})"
    }

    /** [token] exactly as sent; checked by the use case. A malformed one counts as unknown: a new cart is started. */
    class Guest(val token: String?) : CartCaller {
        override fun toString(): String = "Guest(token=${if (token == null) "none" else "****"})"
    }
}
