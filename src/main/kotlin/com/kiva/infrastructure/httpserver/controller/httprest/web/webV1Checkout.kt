package com.kiva.infrastructure.httpserver.controller.httprest.web

import com.kiva.application.port.RequestHash
import com.kiva.application.usecase.cart.CartCaller
import com.kiva.application.usecase.cart.usecase.command.OpenCartUc
import com.kiva.application.usecase.checkout.usecase.command.PlaceOrderUc
import com.kiva.application.usecase.checkout.usecase.command.StartPaymentUc
import com.kiva.application.usecase.checkout.usecase.query.GetCheckoutContextUc
import com.kiva.application.usecase.checkout.usecase.query.GetPaymentInitUc
import com.kiva.application.usecase.order.usecase.query.GetOrderSummaryUc
import com.kiva.common.railway.unwrapOr
import com.kiva.common.valueobject.UserId
import com.kiva.infrastructure.common.http.respondFailure
import com.kiva.infrastructure.common.http.respondResult
import com.kiva.infrastructure.httpserver.auth.ACCESS
import com.kiva.infrastructure.httpserver.auth.accessPrincipal
import com.kiva.infrastructure.httpserver.controller.httprest.model.request.PlaceOrderHttpRequest
import com.kiva.infrastructure.httpserver.controller.httprest.model.response.CheckoutContextHttpResponse
import com.kiva.infrastructure.httpserver.controller.httprest.model.response.OrderSummaryHttpResponse
import com.kiva.infrastructure.httpserver.controller.httprest.model.response.PaymentInitHttpResponse
import com.kiva.infrastructure.httpserver.controller.httprest.model.response.PlaceOrderHttpResponse
import com.kiva.infrastructure.httpserver.idempotency.RequestFingerprint
import com.kiva.infrastructure.httpserver.idempotency.idempotencyKeyHeader
import com.kiva.infrastructure.media.MediaUrls
import io.ktor.http.HttpStatusCode
import io.ktor.server.auth.authenticate
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post

/** The use cases of checkout, as the routes need them. */
class CheckoutUseCases(
    val openCart: OpenCartUc,
    val getCheckoutContext: GetCheckoutContextUc,
    val placeOrder: PlaceOrderUc,
    val startPayment: StartPaymentUc,
    val getOrderSummary: GetOrderSummaryUc,
    val getPaymentInit: GetPaymentInitUc,
)

/**
 * Checkout (contract tag `Checkout & Payment`). Mounted under `/api/v1`; every route needs a login, and the cart is
 * always the token's customer's.
 *
 * Placing an order is three steps, so the gateway is never called inside a transaction: PlaceOrderUc commits the
 * order, StartPaymentUc opens the gateway's session, and queries build the answer. A repeat with the same
 * `Idempotency-Key` walks the same three steps and gets the same order, the same attempt, and the same answer.
 */
fun Route.webV1Checkout(useCases: CheckoutUseCases, mediaUrls: MediaUrls) {
    authenticate(ACCESS) {
        // Like GET /cart, the cart is opened first: brought up to date with the shop and its issues told once.
        get("/checkout") {
            val userId = call.accessPrincipal().userId
            val change = useCases.openCart.execute(OpenCartUc.Cmd(CartCaller.Customer(userId))).unwrapOr { it }
            val query = GetCheckoutContextUc.Qry(userId, change.cartId.value, call.request.queryParameters["addressId"])
            call.respondResult(useCases.getCheckoutContext.execute(query)) { CheckoutContextHttpResponse.from(it, change.issues, mediaUrls) }
        }

        post("/orders") {
            val userId = call.accessPrincipal().userId
            val request = call.receive<PlaceOrderHttpRequest>()
            val fingerprint = RequestFingerprint.of(PlaceOrderHttpRequest.serializer(), request)
            val placed = useCases.placeOrder.execute(request.toCommand(userId, call.idempotencyKeyHeader(), fingerprint))
                .unwrapOr { return@post call.respondFailure(it) }

            // An order with nothing to pay is paid already: no attempt, no gateway.
            val payment = placed.paymentId?.let { paymentId ->
                // The gateway refused or didn't answer: 422 with the order and the attempt to go on from.
                useCases.startPayment.execute(StartPaymentUc.Cmd(userId, paymentId)).unwrapOr { return@post call.respondFailure(it) }
                useCases.getPaymentInit.execute(GetPaymentInitUc.Qry(userId, paymentId)).unwrapOr { return@post call.respondFailure(it) }
            }
            val order = useCases.getOrderSummary.execute(GetOrderSummaryUc.Qry(userId, placed.orderCode)).unwrapOr { return@post call.respondFailure(it) }
            call.respond(HttpStatusCode.Created, PlaceOrderHttpResponse(OrderSummaryHttpResponse.from(order, mediaUrls), payment?.let(PaymentInitHttpResponse::from)))
        }
    }
}

private fun PlaceOrderHttpRequest.toCommand(userId: UserId, idempotencyKey: String?, fingerprint: RequestHash) = PlaceOrderUc.Cmd(
    userId = userId,
    idempotencyKey = idempotencyKey,
    requestHash = fingerprint,
    addressId = addressId,
    newAddress = newAddress?.toFields(),
    newAddressAsDefault = newAddress?.setAsDefault,
    saveNewAddress = saveNewAddress,
    messenger = preShipmentMessenger?.let { PlaceOrderUc.MessengerFields(it.channel, it.phone, it.username, it.note, it.saveAsDefault) },
    shippingMethod = shippingMethod,
    reserve = reserve,
    paymentGateway = paymentGateway,
    customerNote = customerNote,
    gift = gift?.let { PlaceOrderUc.GiftFields(it.message, it.hidePrices) },
    expectedPayable = expectedPayable,
    acceptTerms = acceptTerms,
)
