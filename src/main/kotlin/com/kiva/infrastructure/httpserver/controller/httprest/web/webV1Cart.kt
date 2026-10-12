package com.kiva.infrastructure.httpserver.controller.httprest.web

import com.kiva.application.dto.CartChange
import com.kiva.application.dto.CartDto
import com.kiva.application.usecase.cart.usecase.command.AddCartItemUc
import com.kiva.application.usecase.cart.usecase.command.ChangeCartItemUc
import com.kiva.application.usecase.cart.usecase.command.ChooseShippingMethodUc
import com.kiva.application.usecase.cart.usecase.command.ClearCartUc
import com.kiva.application.usecase.cart.usecase.command.OpenCartUc
import com.kiva.application.usecase.cart.usecase.command.RemoveCartItemUc
import com.kiva.application.usecase.cart.usecase.command.SetCartReservationUc
import com.kiva.application.usecase.cart.usecase.query.GetCartUc
import com.kiva.common.railway.Result
import com.kiva.common.railway.ResultFailure
import com.kiva.common.railway.unwrapOr
import com.kiva.infrastructure.common.http.respondFailure
import com.kiva.infrastructure.httpserver.auth.ACCESS
import com.kiva.infrastructure.httpserver.cart.cartCaller
import com.kiva.infrastructure.httpserver.cart.sendNewCartToken
import com.kiva.infrastructure.httpserver.controller.httprest.model.request.AddCartItemHttpRequest
import com.kiva.infrastructure.httpserver.controller.httprest.model.request.ChooseShippingMethodHttpRequest
import com.kiva.infrastructure.httpserver.controller.httprest.model.request.SetCartReservationHttpRequest
import com.kiva.infrastructure.httpserver.controller.httprest.model.request.UpdateCartItemHttpRequest
import com.kiva.infrastructure.httpserver.controller.httprest.model.response.CartHttpResponse
import com.kiva.infrastructure.httpserver.controller.httprest.model.response.CartMutationHttpResponse
import com.kiva.infrastructure.httpserver.controller.httprest.model.response.CartTexts
import com.kiva.infrastructure.httpserver.controller.httprest.model.response.RemovedItemHttpResponse
import io.ktor.server.application.ApplicationCall
import io.ktor.server.auth.authenticate
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.patch
import io.ktor.server.routing.post
import io.ktor.server.routing.put

/** The use cases of the cart, as the routes need them. */
class CartUseCases(
    val openCart: OpenCartUc,
    val getCart: GetCartUc,
    val addCartItem: AddCartItemUc,
    val changeCartItem: ChangeCartItemUc,
    val removeCartItem: RemoveCartItemUc,
    val clearCart: ClearCartUc,
    val chooseShippingMethod: ChooseShippingMethodUc,
    val setCartReservation: SetCartReservationUc,
)

/** The path parameter every line route names its line by. */
private const val ITEM_ID = "itemId"

/**
 * The cart (contract tag `Cart`). Mounted under `/api/v1`. A login is optional: with a valid access token it is the
 * customer's cart, without one the guest's of `X-Cart-Token` (see CartAccess). Every route is a command, `GET` included
 * (it records the cart as seen); after it the answer is built by GetCartUc, and a new guest cart's token goes in the
 * `X-Cart-Token` header.
 */
fun Route.webV1Cart(useCases: CartUseCases) {
    authenticate(ACCESS, optional = true) {
        get("/cart") {
            call.respondCart(useCases, useCases.openCart.execute(OpenCartUc.Cmd(call.cartCaller())))
        }

        delete("/cart") {
            call.respondCart(useCases, useCases.clearCart.execute(ClearCartUc.Cmd(call.cartCaller())))
        }

        post("/cart/items") {
            val request = call.receive<AddCartItemHttpRequest>()
            val result = useCases.addCartItem.execute(AddCartItemUc.Cmd(call.cartCaller(), request.variantId, request.quantity))
            call.respondCartMutation(useCases, result) { change, cart ->
                val line = cart.items.firstOrNull { it.id == change.affectedItemId?.value }
                line?.let { CartTexts.added(it.product.name, it.color.name, change.addedQuantity ?: 0) }
            }
        }

        patch("/cart/items/{$ITEM_ID}") {
            val request = call.receive<UpdateCartItemHttpRequest>()
            val command = ChangeCartItemUc.Cmd(call.cartCaller(), call.itemIdSegment(), request.quantity, request.variantId)
            call.respondCartMutation(useCases, useCases.changeCartItem.execute(command)) { _, _ -> null }
        }

        delete("/cart/items/{$ITEM_ID}") {
            val result = useCases.removeCartItem.execute(RemoveCartItemUc.Cmd(call.cartCaller(), call.itemIdSegment()))
            call.respondCartMutation(useCases, result) { change, _ ->
                change.removedItem?.let { CartTexts.removed(it.productName, it.colorName) }
            }
        }

        put("/cart/shipping-method") {
            val request = call.receive<ChooseShippingMethodHttpRequest>()
            call.respondCart(useCases, useCases.chooseShippingMethod.execute(ChooseShippingMethodUc.Cmd(call.cartCaller(), request.method)))
        }

        put("/cart/reservation") {
            val request = call.receive<SetCartReservationHttpRequest>()
            call.respondCart(useCases, useCases.setCartReservation.execute(SetCartReservationUc.Cmd(call.cartCaller(), request.enabled)))
        }
    }
}

/** A command's failure as a problem; its success as the whole cart (contract `Cart`). */
private suspend fun ApplicationCall.respondCart(useCases: CartUseCases, result: Result<CartChange, ResultFailure>) {
    val change = result.unwrapOr { return respondFailure(it) }
    val cart = useCases.getCart.execute(GetCartUc.Qry(change.cartId.value)).unwrapOr { return respondFailure(it) }
    sendNewCartToken(change)
    respond(CartHttpResponse.from(cart, change.issues))
}

/** Like [respondCart], wrapped as the contract's `CartMutationResponse`, with the toast [message] gives. */
private suspend fun ApplicationCall.respondCartMutation(
    useCases: CartUseCases,
    result: Result<CartChange, ResultFailure>,
    message: (CartChange, CartDto) -> String?,
) {
    val change = result.unwrapOr { return respondFailure(it) }
    val cart = useCases.getCart.execute(GetCartUc.Qry(change.cartId.value)).unwrapOr { return respondFailure(it) }
    sendNewCartToken(change)
    respond(
        CartMutationHttpResponse(
            cart = CartHttpResponse.from(cart, change.issues),
            affectedItemId = change.affectedItemId?.value,
            removedItem = change.removedItem?.let(RemovedItemHttpResponse::from),
            message = message(change, cart),
        ),
    )
}

// Always present: the routes only match when the segment is there.
private fun ApplicationCall.itemIdSegment(): String = parameters[ITEM_ID].orEmpty()
