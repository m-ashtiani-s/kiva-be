package com.kiva.infrastructure.httpserver.cart

import com.kiva.application.dto.CartChange
import com.kiva.application.usecase.cart.CartCaller
import com.kiva.infrastructure.httpserver.CART_TOKEN_HEADER
import com.kiva.infrastructure.httpserver.auth.AccessPrincipal
import io.ktor.server.application.ApplicationCall
import io.ktor.server.auth.principal
import io.ktor.server.response.header

/**
 * Whose cart a request is about. Only inside `authenticate(ACCESS, optional = true)`: a valid access token makes it the
 * customer's cart and any `X-Cart-Token` is ignored; without one it is the guest's of that header. An expired or broken
 * access token never gets here: the authentication answers 401 first, so a customer is never silently a guest.
 */
fun ApplicationCall.cartCaller(): CartCaller =
    principal<AccessPrincipal>()?.let { CartCaller.Customer(it.userId) } ?: CartCaller.Guest(request.headers[CART_TOKEN_HEADER])

/** Hands the token of a guest cart this request made to the browser, once; nothing when the cart was already known. */
fun ApplicationCall.sendNewCartToken(change: CartChange) {
    change.newToken?.let { response.header(CART_TOKEN_HEADER, it.reveal()) }
}
