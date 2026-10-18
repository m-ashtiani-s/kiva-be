package com.kiva.infrastructure.httpserver.controller.httprest.web

import com.kiva.application.usecase.admin.usecase.command.CheckPanelAccessUc
import com.kiva.application.usecase.refund.usecase.command.UpdateRefundUc
import com.kiva.domain.aggregate.user.model.valueobject.Role
import com.kiva.infrastructure.common.http.respondResult
import com.kiva.infrastructure.httpserver.auth.panelActor
import com.kiva.infrastructure.httpserver.auth.requireRoles
import com.kiva.infrastructure.httpserver.controller.httprest.model.request.AdminRefundUpdateHttpRequest
import com.kiva.infrastructure.httpserver.controller.httprest.model.response.RefundHttpResponse
import io.ktor.server.request.receive
import io.ktor.server.routing.Route
import io.ktor.server.routing.patch

/** The use cases of the panel's refunds, as the routes need them. */
class AdminRefundUseCases(
    val checkPanelAccess: CheckPanelAccessUc,
    val updateRefund: UpdateRefundUc,
)

/** The path parameter of a refund. */
private const val REFUND_ID = "refundId"

/**
 * Following a refund (contract tag `Admin · Orders`, `[ADMIN]`). Mounted under `/api/v1`. A refund id that isn't a positive
 * number, or that nobody has, is 404 REFUND_NOT_FOUND. Opening one is `POST /admin/orders/{orderCode}/refunds`
 * (webV1AdminOrders).
 */
fun Route.webV1AdminRefunds(useCases: AdminRefundUseCases) {
    requireRoles(useCases.checkPanelAccess, Role.ADMIN) {
        patch("/admin/refunds/{$REFUND_ID}") {
            val request = call.receive<AdminRefundUpdateHttpRequest>()
            val command = UpdateRefundUc.Cmd(call.parameters[REFUND_ID].orEmpty(), request.status, request.referenceId, request.note, call.panelActor())
            call.respondResult(useCases.updateRefund.execute(command)) { RefundHttpResponse.from(it) }
        }
    }
}
