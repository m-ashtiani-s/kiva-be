package com.kiva.infrastructure.httpserver.controller.httprest.web

import com.kiva.application.usecase.cart.CartCaller
import com.kiva.application.usecase.cart.usecase.command.OpenCartUc
import com.kiva.application.usecase.checkout.usecase.query.GetCheckoutContextUc
import com.kiva.common.railway.unwrapOr
import com.kiva.infrastructure.common.http.respondResult
import com.kiva.infrastructure.httpserver.auth.ACCESS
import com.kiva.infrastructure.httpserver.auth.accessPrincipal
import com.kiva.infrastructure.httpserver.controller.httprest.model.response.CheckoutContextHttpResponse
import io.ktor.server.auth.authenticate
import io.ktor.server.routing.Route
import io.ktor.server.routing.get

/** The use cases of checkout, as the routes need them. */
class CheckoutUseCases(
    val openCart: OpenCartUc,
    val getCheckoutContext: GetCheckoutContextUc,
)

/**
 * Checkout (contract tag `Checkout & Payment`). Mounted under `/api/v1`; every route needs a login, and the cart is
 * always the token's customer's.
 */
fun Route.webV1Checkout(useCases: CheckoutUseCases) {
    authenticate(ACCESS) {
        // Like GET /cart, the cart is opened first: brought up to date with the shop and its issues told once.
        get("/checkout") {
            val userId = call.accessPrincipal().userId
            val change = useCases.openCart.execute(OpenCartUc.Cmd(CartCaller.Customer(userId))).unwrapOr { it }
            val query = GetCheckoutContextUc.Qry(userId, change.cartId.value, call.request.queryParameters["addressId"])
            call.respondResult(useCases.getCheckoutContext.execute(query)) { CheckoutContextHttpResponse.from(it, change.issues) }
        }
    }
}
