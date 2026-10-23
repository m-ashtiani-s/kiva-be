package com.kiva.infrastructure.httpserver.controller.httprest.web

import com.kiva.application.usecase.admin.usecase.command.CheckPanelAccessUc
import com.kiva.application.usecase.contact.usecase.command.UpdateContactMessageUc
import com.kiva.application.usecase.contact.usecase.query.FindAdminContactMessagesUc
import com.kiva.common.railway.unwrapOr
import com.kiva.domain.aggregate.contact.model.ContactMessageChange
import com.kiva.domain.aggregate.user.model.valueobject.Role
import com.kiva.infrastructure.common.http.pageable
import com.kiva.infrastructure.common.http.respondFailure
import com.kiva.infrastructure.common.http.respondResult
import com.kiva.infrastructure.common.http.toHttpResponse
import com.kiva.infrastructure.httpserver.auth.panelActor
import com.kiva.infrastructure.httpserver.auth.requireRoles
import com.kiva.infrastructure.httpserver.controller.httprest.model.request.UpdateContactMessageHttpRequest
import com.kiva.infrastructure.httpserver.controller.httprest.model.response.AdminContactMessageHttpResponse
import io.ktor.server.request.receive
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.patch

/** The use cases of the panel's contact messages, as the routes need them. */
class AdminSupportUseCases(
    val checkPanelAccess: CheckPanelAccessUc,
    val findContactMessages: FindAdminContactMessagesUc,
    val updateContactMessage: UpdateContactMessageUc,
)

/** The path parameter of a contact message. */
private const val TICKET_CODE = "ticketCode"

/** The one order of the list (the contract has no `sort`). */
private const val NEWEST = "newest"

/**
 * The panel's contact messages (contract tag `Admin · Support`, `[ADMIN, SUPPORT]`): the list and the change of one
 * (status, internal note, reply by SMS or email). Mounted under `/api/v1`. A code that can't be one, or nobody's, is 404.
 */
fun Route.webV1AdminSupport(useCases: AdminSupportUseCases) {
    requireRoles(useCases.checkPanelAccess, Role.ADMIN, Role.SUPPORT) {
        get("/admin/contact-messages") {
            val pageable = call.pageable(allowedSorts = setOf(NEWEST), defaultSort = NEWEST, defaultSize = 20, maxSize = 100)
                .unwrapOr { return@get call.respondFailure(it) }
            val params = call.request.queryParameters
            val query = FindAdminContactMessagesUc.Qry(pageable, params["status"], params["topic"])
            call.respondResult(useCases.findContactMessages.execute(query)) { page -> page.toHttpResponse { AdminContactMessageHttpResponse.from(it) } }
        }

        patch("/admin/contact-messages/{$TICKET_CODE}") {
            val request = call.receive<UpdateContactMessageHttpRequest>()
            val input = ContactMessageChange.Input(request.status, request.reply, request.replyChannel, request.internalNote)
            val command = UpdateContactMessageUc.Cmd(call.parameters[TICKET_CODE].orEmpty(), input, call.panelActor())
            call.respondResult(useCases.updateContactMessage.execute(command)) { AdminContactMessageHttpResponse.from(it) }
        }
    }
}
