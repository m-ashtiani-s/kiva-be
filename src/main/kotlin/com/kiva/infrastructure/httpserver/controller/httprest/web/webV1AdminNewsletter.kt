package com.kiva.infrastructure.httpserver.controller.httprest.web

import com.kiva.application.usecase.admin.usecase.command.CheckPanelAccessUc
import com.kiva.application.usecase.newsletter.usecase.query.FindNewsletterSubscriptionsUc
import com.kiva.common.railway.unwrapOr
import com.kiva.domain.aggregate.user.model.valueobject.Role
import com.kiva.infrastructure.common.http.pageable
import com.kiva.infrastructure.common.http.respondFailure
import com.kiva.infrastructure.common.http.respondResult
import com.kiva.infrastructure.common.http.toHttpResponse
import com.kiva.infrastructure.httpserver.auth.requireRoles
import com.kiva.infrastructure.httpserver.controller.httprest.model.response.NewsletterSubscriptionHttpResponse
import io.ktor.server.routing.Route
import io.ktor.server.routing.get

/** The use cases of the panel's newsletter list, as the routes need them. */
class AdminNewsletterUseCases(
    val checkPanelAccess: CheckPanelAccessUc,
    val findSubscriptions: FindNewsletterSubscriptionsUc,
)

/** The one order of the list (the contract has no `sort`). */
private const val NEWEST = "newest"

/**
 * The newsletter's subscribers in the panel (contract tag `Admin · Marketing`, `[ADMIN]` only: the emails are personal).
 * Mounted under `/api/v1`. Only the list: the newsletter is sent with an outside tool.
 */
fun Route.webV1AdminNewsletter(useCases: AdminNewsletterUseCases) {
    requireRoles(useCases.checkPanelAccess, Role.ADMIN) {
        get("/admin/newsletter/subscriptions") {
            val pageable = call.pageable(allowedSorts = setOf(NEWEST), defaultSort = NEWEST, defaultSize = 20, maxSize = 100)
                .unwrapOr { return@get call.respondFailure(it) }
            val params = call.request.queryParameters
            val query = FindNewsletterSubscriptionsUc.Qry(pageable, params["status"], params["q"])
            call.respondResult(useCases.findSubscriptions.execute(query)) { page -> page.toHttpResponse { NewsletterSubscriptionHttpResponse.from(it) } }
        }
    }
}
