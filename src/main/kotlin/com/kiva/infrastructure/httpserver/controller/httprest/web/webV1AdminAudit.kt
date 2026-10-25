package com.kiva.infrastructure.httpserver.controller.httprest.web

import com.kiva.application.repository.AuditLogQmRepository
import com.kiva.application.usecase.admin.usecase.command.CheckPanelAccessUc
import com.kiva.application.usecase.audit.usecase.query.ListAuditLogUc
import com.kiva.common.railway.unwrapOr
import com.kiva.domain.aggregate.user.model.valueobject.Role
import com.kiva.infrastructure.common.http.pageable
import com.kiva.infrastructure.common.http.respondFailure
import com.kiva.infrastructure.common.http.respondResult
import com.kiva.infrastructure.common.http.toHttpResponse
import com.kiva.infrastructure.httpserver.auth.requireRoles
import com.kiva.infrastructure.httpserver.controller.httprest.model.response.AuditLogEntryHttpResponse
import io.ktor.server.routing.Route
import io.ktor.server.routing.get

/** The use cases of the panel's change log, as the route needs them. */
class AdminAuditUseCases(
    val checkPanelAccess: CheckPanelAccessUc,
    val listAuditLog: ListAuditLogUc,
)

/** The panel's change log (contract tag `Admin · Support`, `GET /admin/audit-log`). Mounted under `/api/v1`; ADMIN only. */
fun Route.webV1AdminAudit(useCases: AdminAuditUseCases) {
    requireRoles(useCases.checkPanelAccess, Role.ADMIN) {
        get("/admin/audit-log") {
            val pageable = call.pageable(allowedSorts = setOf(AuditLogQmRepository.NEWEST), defaultSort = AuditLogQmRepository.NEWEST)
                .unwrapOr { return@get call.respondFailure(it) }
            val parameters = call.request.queryParameters
            val query = ListAuditLogUc.Qry(
                pageable = pageable,
                entityType = parameters["entityType"],
                entityId = parameters["entityId"],
                actorId = parameters["actorId"],
                from = parameters["from"],
                to = parameters["to"],
            )
            call.respondResult(useCases.listAuditLog.execute(query)) { page -> page.toHttpResponse(AuditLogEntryHttpResponse::from) }
        }
    }
}
