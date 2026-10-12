package com.kiva.infrastructure.httpserver.controller.httprest.web

import com.kiva.application.usecase.cart.usecase.query.GetCartUc
import com.kiva.application.usecase.order.usecase.command.ReorderUc
import com.kiva.application.usecase.order.usecase.query.GetMyOrderUc
import com.kiva.application.usecase.order.usecase.query.ListMyOrdersUc
import com.kiva.common.railway.unwrapOr
import com.kiva.infrastructure.common.http.pageable
import com.kiva.infrastructure.common.http.respondFailure
import com.kiva.infrastructure.common.http.respondResult
import com.kiva.infrastructure.httpserver.auth.ACCESS
import com.kiva.infrastructure.httpserver.auth.accessPrincipal
import com.kiva.infrastructure.httpserver.controller.httprest.model.response.CartHttpResponse
import com.kiva.infrastructure.httpserver.controller.httprest.model.response.OrderDetailHttpResponse
import com.kiva.infrastructure.httpserver.controller.httprest.model.response.OrderListHttpResponse
import com.kiva.infrastructure.httpserver.controller.httprest.model.response.ReorderHttpResponse
import io.ktor.server.auth.authenticate
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
)

/** The path parameter every order route names its order by. */
private const val ORDER_CODE = "orderCode"

/**
 * «سفارش‌های من» (contract tag `Orders`). Mounted under `/api/v1`; every route needs a login, and an order is always the
 * token's customer's: someone else's, or a code that can't be one, is the same 404 as one that doesn't exist. Paying
 * an order again (`POST /me/orders/{orderCode}/payments`) is in webV1Payments.
 */
fun Route.webV1MyOrders(useCases: MyOrderUseCases) {
    authenticate(ACCESS) {
        get("/me/orders") {
            // Newest first is the only order; 20 a page by default.
            val pageable = call.pageable().unwrapOr { return@get call.respondFailure(it) }
            val query = ListMyOrdersUc.Qry(call.accessPrincipal().userId, call.request.queryParameters["filter"], pageable)
            call.respondResult(useCases.listMyOrders.execute(query)) { OrderListHttpResponse.from(it) }
        }

        get("/me/orders/{$ORDER_CODE}") {
            val query = GetMyOrderUc.Qry(call.accessPrincipal().userId, call.parameters[ORDER_CODE].orEmpty())
            call.respondResult(useCases.getMyOrder.execute(query)) { OrderDetailHttpResponse.from(it) }
        }

        // The answer carries the whole cart, built as GET /cart builds it, with the issues of bringing it up to date.
        post("/me/orders/{$ORDER_CODE}/reorder") {
            val command = ReorderUc.Cmd(call.accessPrincipal().userId, call.parameters[ORDER_CODE].orEmpty())
            val reorder = useCases.reorder.execute(command).unwrapOr { return@post call.respondFailure(it) }
            val cart = useCases.getCart.execute(GetCartUc.Qry(reorder.change.cartId.value)).unwrapOr { return@post call.respondFailure(it) }
            call.respond(ReorderHttpResponse.from(CartHttpResponse.from(cart, reorder.change.issues), reorder))
        }
    }
}
