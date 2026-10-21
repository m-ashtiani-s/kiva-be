package com.kiva.infrastructure.httpserver.controller.httprest.web

import com.kiva.application.usecase.admin.usecase.command.CheckPanelAccessUc
import com.kiva.application.usecase.returnrequest.usecase.command.DecideReturnUc
import com.kiva.application.usecase.returnrequest.usecase.command.ScheduleReturnPickupUc
import com.kiva.application.usecase.returnrequest.usecase.query.FindAdminReturnsUc
import com.kiva.application.usecase.returnrequest.usecase.query.GetAdminReturnUc
import com.kiva.application.usecase.returnrequest.usecase.query.ReturnSorts
import com.kiva.common.railway.unwrapOr
import com.kiva.domain.aggregate.user.model.valueobject.Role
import com.kiva.infrastructure.common.http.pageable
import com.kiva.infrastructure.common.http.respondFailure
import com.kiva.infrastructure.common.http.respondResult
import com.kiva.infrastructure.common.http.toHttpResponse
import com.kiva.infrastructure.httpserver.auth.panelActor
import com.kiva.infrastructure.httpserver.auth.requireRoles
import com.kiva.infrastructure.httpserver.controller.httprest.model.request.AdminReturnDecisionHttpRequest
import com.kiva.infrastructure.httpserver.controller.httprest.model.request.AdminReturnPickupHttpRequest
import com.kiva.infrastructure.httpserver.controller.httprest.model.response.AdminReturnRequestHttpResponse
import com.kiva.infrastructure.media.MediaUrls
import io.ktor.server.request.receive
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put

/** The use cases of the panel's returns, as the routes need them. */
class AdminReturnUseCases(
    val checkPanelAccess: CheckPanelAccessUc,
    val findReturns: FindAdminReturnsUc,
    val getReturn: GetAdminReturnUc,
    val decide: DecideReturnUc,
    val schedulePickup: ScheduleReturnPickupUc,
)

/** The path parameter every panel return route names its return by. */
private const val RETURN_ID = "returnId"

/**
 * The panel's returns (contract tag `Admin · Orders`, `[ADMIN, OPERATOR]`): the queue, and the steps staff take on one.
 * Mounted under `/api/v1`. A return id that can't be one, or nobody has, is 404 RETURN_NOT_FOUND. Every step answers
 * with the return as the panel sees it now. A refund of a return is `POST /admin/orders/{orderCode}/refunds` with its
 * `returnId` (webV1AdminOrders).
 */
fun Route.webV1AdminReturns(useCases: AdminReturnUseCases, mediaUrls: MediaUrls) {
    requireRoles(useCases.checkPanelAccess, Role.ADMIN, Role.OPERATOR) {
        get("/admin/returns") {
            val pageable = call.pageable(allowedSorts = ReturnSorts.names, defaultSort = ReturnSorts.DEFAULT, defaultSize = 20, maxSize = 100)
                .unwrapOr { return@get call.respondFailure(it) }
            val query = FindAdminReturnsUc.Qry(pageable, call.request.queryParameters["status"])
            call.respondResult(useCases.findReturns.execute(query)) { page -> page.toHttpResponse { AdminReturnRequestHttpResponse.from(it, mediaUrls) } }
        }

        post("/admin/returns/{$RETURN_ID}/decision") {
            val id = call.parameters[RETURN_ID].orEmpty()
            val request = call.receive<AdminReturnDecisionHttpRequest>()
            val command = DecideReturnUc.Cmd(id, request.approve, request.shippingPaidBy, request.instructions, request.rejectionReason, call.panelActor())
            useCases.decide.execute(command).unwrapOr { return@post call.respondFailure(it) }
            call.respondResult(useCases.getReturn.execute(GetAdminReturnUc.Qry(id))) { AdminReturnRequestHttpResponse.from(it, mediaUrls) }
        }

        put("/admin/returns/{$RETURN_ID}/pickup") {
            val id = call.parameters[RETURN_ID].orEmpty()
            val request = call.receive<AdminReturnPickupHttpRequest>()
            val command = ScheduleReturnPickupUc.Cmd(id, request.carrier, request.scheduledFor, request.trackingCode, request.note, request.notifyCustomer, call.panelActor())
            useCases.schedulePickup.execute(command).unwrapOr { return@put call.respondFailure(it) }
            call.respondResult(useCases.getReturn.execute(GetAdminReturnUc.Qry(id))) { AdminReturnRequestHttpResponse.from(it, mediaUrls) }
        }
    }
}
