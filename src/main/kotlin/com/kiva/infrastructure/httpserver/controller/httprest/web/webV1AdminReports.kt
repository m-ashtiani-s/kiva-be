package com.kiva.infrastructure.httpserver.controller.httprest.web

import com.kiva.application.usecase.admin.usecase.command.CheckPanelAccessUc
import com.kiva.application.usecase.report.usecase.query.GetReportOverviewUc
import com.kiva.domain.aggregate.user.model.valueobject.Role
import com.kiva.infrastructure.common.http.respondResult
import com.kiva.infrastructure.httpserver.auth.requireRoles
import com.kiva.infrastructure.httpserver.controller.httprest.model.response.AdminReportOverviewHttpResponse
import com.kiva.infrastructure.media.MediaUrls
import io.ktor.server.routing.Route
import io.ktor.server.routing.get

/** The use cases of the panel's dashboard, as the route needs them. */
class AdminReportUseCases(
    val checkPanelAccess: CheckPanelAccessUc,
    val getOverview: GetReportOverviewUc,
)

/** The panel's dashboard (contract tag `Admin · Support`, `GET /admin/reports/overview`). Mounted under `/api/v1`; ADMIN only. */
fun Route.webV1AdminReports(useCases: AdminReportUseCases, urls: MediaUrls) {
    requireRoles(useCases.checkPanelAccess, Role.ADMIN) {
        get("/admin/reports/overview") {
            val parameters = call.request.queryParameters
            val query = GetReportOverviewUc.Qry(from = parameters["from"], to = parameters["to"])
            call.respondResult(useCases.getOverview.execute(query)) { AdminReportOverviewHttpResponse.from(it, urls) }
        }
    }
}
