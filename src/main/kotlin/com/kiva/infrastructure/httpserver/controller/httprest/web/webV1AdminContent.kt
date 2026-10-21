package com.kiva.infrastructure.httpserver.controller.httprest.web

import com.kiva.application.usecase.admin.usecase.command.CheckPanelAccessUc
import com.kiva.application.usecase.content.usecase.command.SavePageUc
import com.kiva.application.usecase.content.usecase.query.GetPageUc
import com.kiva.application.usecase.content.usecase.query.ListPagesUc
import com.kiva.common.railway.unwrapOr
import com.kiva.domain.aggregate.user.model.valueobject.Role
import com.kiva.infrastructure.common.http.respondFailure
import com.kiva.infrastructure.common.http.respondResult
import com.kiva.infrastructure.httpserver.auth.panelActor
import com.kiva.infrastructure.httpserver.auth.requireRoles
import com.kiva.infrastructure.httpserver.controller.httprest.model.request.StaticPageUpsertHttpRequest
import com.kiva.infrastructure.httpserver.controller.httprest.model.response.AdminStaticPageSummaryHttpResponse
import com.kiva.infrastructure.httpserver.controller.httprest.model.response.StaticPageHttpResponse
import io.ktor.server.request.receive
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.put

/** The use cases of the panel's content pages, as the routes need them. */
class AdminContentUseCases(
    val checkPanelAccess: CheckPanelAccessUc,
    val listPages: ListPagesUc,
    val getPage: GetPageUc,
    val savePage: SavePageUc,
)

/**
 * The panel's content (contract tags `Admin · Content` and `Admin · Marketing`; ADMIN and CONTENT_EDITOR): static pages.
 * Mounted under `/api/v1`. Nothing here is cached: a form must see what it just saved.
 *
 * @param siteUrl the shop's address, for a page's canonical address.
 */
fun Route.webV1AdminContent(useCases: AdminContentUseCases, siteUrl: String) {
    requireRoles(useCases.checkPanelAccess, Role.ADMIN, Role.CONTENT_EDITOR) {
        get("/admin/pages") {
            call.respondResult(useCases.listPages.execute(ListPagesUc.Qry)) { pages -> pages.map(AdminStaticPageSummaryHttpResponse::from) }
        }
        // Made or rewritten, the answer is the page as the site shows it.
        put("/admin/pages/{slug}") {
            val request = call.receive<StaticPageUpsertHttpRequest>()
            val command = SavePageUc.Cmd(call.pathParameters["slug"].orEmpty(), request.toFields(), call.panelActor())
            val slug = useCases.savePage.execute(command).unwrapOr { return@put call.respondFailure(it) }
            call.respondResult(useCases.getPage.execute(GetPageUc.Qry(slug.value))) { StaticPageHttpResponse.from(it, siteUrl) }
        }
    }
}
