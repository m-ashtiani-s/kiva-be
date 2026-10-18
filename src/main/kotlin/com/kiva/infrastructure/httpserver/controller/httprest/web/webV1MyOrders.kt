package com.kiva.infrastructure.httpserver.controller.httprest.web

import com.kiva.application.usecase.cart.usecase.query.GetCartUc
import com.kiva.application.usecase.order.usecase.command.CancelMyOrderUc
import com.kiva.application.usecase.order.usecase.command.ReorderUc
import com.kiva.application.usecase.order.usecase.command.SubmitMediaFeedbackUc
import com.kiva.application.usecase.order.usecase.query.GetMyOrderMediaUc
import com.kiva.application.usecase.order.usecase.query.GetMyOrderUc
import com.kiva.application.usecase.order.usecase.query.ListMyOrdersUc
import com.kiva.application.usecase.reservation.usecase.query.GetMyReservationUc
import com.kiva.common.railway.unwrapOr
import com.kiva.infrastructure.common.http.pageable
import com.kiva.infrastructure.common.http.respondFailure
import com.kiva.infrastructure.common.http.respondResult
import com.kiva.infrastructure.httpserver.auth.ACCESS
import com.kiva.infrastructure.httpserver.auth.accessPrincipal
import com.kiva.infrastructure.httpserver.controller.httprest.model.request.CancelOrderHttpRequest
import com.kiva.infrastructure.httpserver.controller.httprest.model.request.PreShipmentFeedbackHttpRequest
import com.kiva.infrastructure.httpserver.controller.httprest.model.response.CancelOrderHttpResponse
import com.kiva.infrastructure.httpserver.controller.httprest.model.response.CancelTexts
import com.kiva.infrastructure.httpserver.controller.httprest.model.response.CartHttpResponse
import com.kiva.infrastructure.httpserver.controller.httprest.model.response.OrderDetailHttpResponse
import com.kiva.infrastructure.httpserver.controller.httprest.model.response.OrderListHttpResponse
import com.kiva.infrastructure.httpserver.controller.httprest.model.response.OrderSummaryHttpResponse
import com.kiva.infrastructure.httpserver.controller.httprest.model.response.PreShipmentMediaHttpResponse
import com.kiva.infrastructure.httpserver.controller.httprest.model.response.RefundHttpResponse
import com.kiva.infrastructure.httpserver.controller.httprest.model.response.ReorderHttpResponse
import com.kiva.infrastructure.httpserver.controller.httprest.model.response.ReservationInfoHttpResponse
import com.kiva.infrastructure.media.MediaUrls
import io.ktor.http.HttpStatusCode
import io.ktor.server.auth.authenticate
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post

/** The use cases of «سفارش‌های من», as the routes need them. */
class MyOrderUseCases(
    val listMyOrders: ListMyOrdersUc,
    val getMyOrder: GetMyOrderUc,
    val reorder: ReorderUc,
    val getCart: GetCartUc,
    val getMyReservation: GetMyReservationUc,
    val getMyOrderMedia: GetMyOrderMediaUc,
    val submitMediaFeedback: SubmitMediaFeedbackUc,
    val cancelMyOrder: CancelMyOrderUc,
)

/** The path parameter every order route names its order by. */
private const val ORDER_CODE = "orderCode"

/**
 * «سفارش‌های من» (contract tag `Orders`). Mounted under `/api/v1`; every route needs a login, and an order is always the
 * token's customer's: someone else's, or a code that can't be one, is the same 404 as one that doesn't exist. Paying
 * an order again (`POST /me/orders/{orderCode}/payments`) is in webV1Payments.
 */
fun Route.webV1MyOrders(useCases: MyOrderUseCases, mediaUrls: MediaUrls) {
    authenticate(ACCESS) {
        get("/me/orders") {
            // Newest first is the only order; 20 a page by default.
            val pageable = call.pageable().unwrapOr { return@get call.respondFailure(it) }
            val query = ListMyOrdersUc.Qry(call.accessPrincipal().userId, call.request.queryParameters["filter"], pageable)
            call.respondResult(useCases.listMyOrders.execute(query)) { OrderListHttpResponse.from(it, mediaUrls) }
        }

        get("/me/orders/{$ORDER_CODE}") {
            val query = GetMyOrderUc.Qry(call.accessPrincipal().userId, call.parameters[ORDER_CODE].orEmpty())
            call.respondResult(useCases.getMyOrder.execute(query)) { OrderDetailHttpResponse.from(it, mediaUrls) }
        }

        // The photos before shipping, for the lightbox: the batch sent, never the operator's draft.
        get("/me/orders/{$ORDER_CODE}/media") {
            val query = GetMyOrderMediaUc.Qry(call.accessPrincipal().userId, call.parameters[ORDER_CODE].orEmpty())
            call.respondResult(useCases.getMyOrderMedia.execute(query)) { PreShipmentMediaHttpResponse.from(it.photos, it.messenger, mediaUrls) }
        }

        // «همونه!»; the answer is the order's page as GET /me/orders/{orderCode} builds it.
        post("/me/orders/{$ORDER_CODE}/media-feedback") {
            val userId = call.accessPrincipal().userId
            val code = call.parameters[ORDER_CODE].orEmpty()
            val request = call.receive<PreShipmentFeedbackHttpRequest>()
            useCases.submitMediaFeedback.execute(SubmitMediaFeedbackUc.Cmd(userId, code, request.decision)).unwrapOr { return@post call.respondFailure(it) }
            call.respondResult(useCases.getMyOrder.execute(GetMyOrderUc.Qry(userId, code))) { OrderDetailHttpResponse.from(it, mediaUrls) }
        }

        // Only while nothing has moved; the answer has the card as it is now and the refund opened.
        post("/me/orders/{$ORDER_CODE}/cancel") {
            val userId = call.accessPrincipal().userId
            val code = call.parameters[ORDER_CODE].orEmpty()
            val request = call.receive<CancelOrderHttpRequest>()
            val cancelled = useCases.cancelMyOrder.execute(CancelMyOrderUc.Cmd(userId, code, request.reason, request.note)).unwrapOr { return@post call.respondFailure(it) }
            call.respondResult(useCases.getMyOrder.execute(GetMyOrderUc.Qry(userId, code))) { order ->
                CancelOrderHttpResponse(
                    order = OrderSummaryHttpResponse.from(order.summary, mediaUrls),
                    refund = cancelled.refund?.let(RefundHttpResponse::from),
                    message = CancelTexts.cancelled(cancelled.refund != null),
                )
            }
        }

        // «رزرو ۴ روزه» while one holds; 204 when none does.
        get("/me/reservation") {
            val reservation = useCases.getMyReservation.execute(GetMyReservationUc.Qry(call.accessPrincipal().userId)).unwrapOr { it }
            if (reservation == null) call.respond(HttpStatusCode.NoContent) else call.respond(ReservationInfoHttpResponse.from(reservation))
        }

        // The answer carries the whole cart, built as GET /cart builds it, with the issues of bringing it up to date.
        post("/me/orders/{$ORDER_CODE}/reorder") {
            val command = ReorderUc.Cmd(call.accessPrincipal().userId, call.parameters[ORDER_CODE].orEmpty())
            val reorder = useCases.reorder.execute(command).unwrapOr { return@post call.respondFailure(it) }
            val cart = useCases.getCart.execute(GetCartUc.Qry(reorder.change.cartId.value)).unwrapOr { return@post call.respondFailure(it) }
            call.respond(ReorderHttpResponse.from(CartHttpResponse.from(cart, reorder.change.issues, mediaUrls), reorder))
        }
    }
}
