package com.kiva.infrastructure.httpserver.controller.httprest.web

import com.kiva.application.usecase.admin.usecase.command.CheckPanelAccessUc
import com.kiva.application.usecase.stockalert.usecase.query.FindAdminStockAlertGroupsUc
import com.kiva.common.railway.unwrapOr
import com.kiva.domain.aggregate.user.model.valueobject.Role
import com.kiva.infrastructure.common.http.pageable
import com.kiva.infrastructure.common.http.respondFailure
import com.kiva.infrastructure.common.http.respondResult
import com.kiva.infrastructure.common.http.toHttpResponse
import com.kiva.infrastructure.httpserver.auth.requireRoles
import com.kiva.infrastructure.httpserver.controller.httprest.model.response.AdminStockAlertGroupHttpResponse
import com.kiva.infrastructure.media.MediaUrls
import io.ktor.server.routing.Route
import io.ktor.server.routing.get

/** The use cases of the panel's «موجود شد خبرم کن» queue, as the route needs them. */
class AdminStockAlertUseCases(
    val checkPanelAccess: CheckPanelAccessUc,
    val findGroups: FindAdminStockAlertGroupsUc,
)

/** The one order of the queue (the contract has no `sort`): most waiting first. */
private const val WAITING = "waiting"

/**
 * The panel's «موجود شد خبرم کن» queue (contract tag `Admin · Catalog`, `[ADMIN, CATALOG_MANAGER]`): only reading, so
 * nothing in the change log. Mounted under `/api/v1`.
 */
fun Route.webV1AdminStockAlerts(useCases: AdminStockAlertUseCases, mediaUrls: MediaUrls) {
    requireRoles(useCases.checkPanelAccess, Role.ADMIN, Role.CATALOG_MANAGER) {
        get("/admin/stock-alerts") {
            val pageable = call.pageable(allowedSorts = setOf(WAITING), defaultSort = WAITING, defaultSize = 20, maxSize = 100)
                .unwrapOr { return@get call.respondFailure(it) }
            val params = call.request.queryParameters
            val query = FindAdminStockAlertGroupsUc.Qry(pageable, params["productId"], params["inStock"])
            call.respondResult(useCases.findGroups.execute(query)) { page -> page.toHttpResponse { AdminStockAlertGroupHttpResponse.from(it, mediaUrls) } }
        }
    }
}
