package com.kiva.infrastructure.httpserver.controller.httprest.web

import com.kiva.application.usecase.admin.usecase.command.CheckPanelAccessUc
import com.kiva.application.usecase.inventory.usecase.command.AdjustInventoryUc
import com.kiva.application.usecase.inventory.usecase.query.ListInventoryAdjustmentsUc
import com.kiva.common.railway.unwrapOr
import com.kiva.domain.aggregate.user.model.valueobject.Role
import com.kiva.infrastructure.common.http.pageable
import com.kiva.infrastructure.common.http.respondFailure
import com.kiva.infrastructure.common.http.respondResult
import com.kiva.infrastructure.common.http.toHttpResponse
import com.kiva.infrastructure.httpserver.auth.panelActor
import com.kiva.infrastructure.httpserver.auth.requireRoles
import com.kiva.infrastructure.httpserver.controller.httprest.model.request.InventoryAdjustmentHttpRequest
import com.kiva.infrastructure.httpserver.controller.httprest.model.response.InventoryAdjustmentHttpResponse
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post

/** The use cases of the panel's stock, as the routes need them. */
class AdminInventoryUseCases(
    val checkPanelAccess: CheckPanelAccessUc,
    val adjustInventory: AdjustInventoryUc,
    val listAdjustments: ListInventoryAdjustmentsUc,
)

/** The panel's stock changes and their history (contract tag `Admin · Catalog`, `[ADMIN, CATALOG_MANAGER]`). Mounted under `/api/v1`. */
fun Route.webV1AdminInventory(useCases: AdminInventoryUseCases) {
    requireRoles(useCases.checkPanelAccess, Role.ADMIN, Role.CATALOG_MANAGER) {
        post("/admin/inventory/adjustments") {
            val request = call.receive<InventoryAdjustmentHttpRequest>()
            val command = AdjustInventoryUc.Cmd(request.variantId, request.delta, request.reason, request.note, call.panelActor())
            call.respondResult(useCases.adjustInventory.execute(command), HttpStatusCode.Created) { InventoryAdjustmentHttpResponse.from(it) }
        }

        // Always newest first: the contract has no other order.
        get("/admin/inventory/adjustments") {
            val pageable = call.pageable().unwrapOr { return@get call.respondFailure(it) }
            val query = ListInventoryAdjustmentsUc.Qry(call.request.queryParameters["variantId"], pageable)
            call.respondResult(useCases.listAdjustments.execute(query)) { page -> page.toHttpResponse(InventoryAdjustmentHttpResponse::from) }
        }
    }
}
