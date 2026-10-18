package com.kiva.infrastructure.httpserver.controller.httprest.web

import com.kiva.application.usecase.admin.usecase.command.CheckPanelAccessUc
import com.kiva.application.usecase.order.AdminOrderFilterQuery
import com.kiva.application.usecase.order.usecase.query.AdminOrderSorts
import com.kiva.application.usecase.order.usecase.query.FindAdminOrdersUc
import com.kiva.application.usecase.order.usecase.query.GetAdminOrderUc
import com.kiva.common.railway.unwrapOr
import com.kiva.domain.aggregate.user.model.valueobject.Role
import com.kiva.infrastructure.common.http.pageable
import com.kiva.infrastructure.common.http.respondFailure
import com.kiva.infrastructure.common.http.respondResult
import com.kiva.infrastructure.httpserver.auth.requireRoles
import com.kiva.infrastructure.httpserver.controller.httprest.model.response.AdminOrderHttpResponse
import com.kiva.infrastructure.httpserver.controller.httprest.model.response.AdminOrderListHttpResponse
import com.kiva.infrastructure.media.MediaUrls
import io.ktor.server.routing.Route
import io.ktor.server.routing.get

/** The use cases of the panel's orders, as the routes need them. */
class AdminOrderUseCases(
    val checkPanelAccess: CheckPanelAccessUc,
    val findOrders: FindAdminOrdersUc,
    val getOrder: GetAdminOrderUc,
)

/** The path parameter every panel order route names its order by. */
private const val ORDER_CODE = "orderCode"

/**
 * The panel's orders (contract tag `Admin · Orders`, `[ADMIN, OPERATOR]`): the queue and one order. Mounted under
 * `/api/v1`. A code that can't be one, or nobody has, is 404 ORDER_NOT_FOUND.
 */
fun Route.webV1AdminOrders(useCases: AdminOrderUseCases, mediaUrls: MediaUrls) {
    requireRoles(useCases.checkPanelAccess, Role.ADMIN, Role.OPERATOR) {
        get("/admin/orders") {
            val pageable = call.pageable(allowedSorts = AdminOrderSorts.names, defaultSort = AdminOrderSorts.DEFAULT, defaultSize = 20, maxSize = 100)
                .unwrapOr { return@get call.respondFailure(it) }
            val params = call.request.queryParameters
            val filters = AdminOrderFilterQuery(
                statuses = params.getAll("status").orEmpty().flatMap { it.split(',') },
                q = params["q"],
                shippingMethod = params["shippingMethod"],
                reserved = params["reserved"],
                awaitingPhotos = params["awaitingPhotos"],
                from = params["from"],
                to = params["to"],
            )
            call.respondResult(useCases.findOrders.execute(FindAdminOrdersUc.Qry(pageable, filters))) { AdminOrderListHttpResponse.from(it) }
        }

        get("/admin/orders/{$ORDER_CODE}") {
            val query = GetAdminOrderUc.Qry(call.parameters[ORDER_CODE].orEmpty())
            call.respondResult(useCases.getOrder.execute(query)) { AdminOrderHttpResponse.from(it, mediaUrls) }
        }
    }
}
