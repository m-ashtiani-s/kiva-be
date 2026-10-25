package com.kiva.infrastructure.httpserver.controller.httprest.web

import com.kiva.application.usecase.content.usecase.query.GetFaqUc
import com.kiva.application.usecase.content.usecase.query.GetPageUc
import com.kiva.application.usecase.content.usecase.query.GetSiteConfigUc
import com.kiva.infrastructure.common.http.respondResultCacheable
import com.kiva.infrastructure.httpserver.controller.httprest.model.response.FaqResponseHttpResponse
import com.kiva.infrastructure.httpserver.controller.httprest.model.response.SiteConfigHttpResponse
import com.kiva.infrastructure.httpserver.controller.httprest.model.response.StaticPageHttpResponse
import io.ktor.server.routing.Route
import io.ktor.server.routing.get

/** The use cases of the site's public content, as the routes need them. */
class ContentUseCases(
    val getPage: GetPageUc,
    val getFaq: GetFaqUc,
    val getSiteConfig: GetSiteConfigUc,
)

/**
 * The site's content for everyone (contract tags `Content` and `Config & Home`): the site's settings, static pages and the
 * FAQ. Mounted under `/api/v1`; no login.
 * Answers are the same for everyone, so they are cached (`ETag`, `max-age=300`; roadmap phase 5, decision 21).
 *
 * @param siteUrl the shop's address, for a page's canonical address and the brand's files.
 */
fun Route.webV1Content(useCases: ContentUseCases, siteUrl: String) {
    get("/config") {
        call.respondResultCacheable(useCases.getSiteConfig.execute(GetSiteConfigUc.Qry)) { SiteConfigHttpResponse.from(it, siteUrl) }
    }
    get("/pages/{slug}") {
        val query = GetPageUc.Qry(call.pathParameters["slug"].orEmpty())
        call.respondResultCacheable(useCases.getPage.execute(query)) { StaticPageHttpResponse.from(it, siteUrl) }
    }
    get("/faq") {
        val query = GetFaqUc.Qry(call.request.queryParameters["q"])
        call.respondResultCacheable(useCases.getFaq.execute(query)) { FaqResponseHttpResponse.from(it) }
    }
}
