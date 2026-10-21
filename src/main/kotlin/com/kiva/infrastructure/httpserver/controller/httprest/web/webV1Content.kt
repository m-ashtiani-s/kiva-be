package com.kiva.infrastructure.httpserver.controller.httprest.web

import com.kiva.application.usecase.content.usecase.query.GetFaqUc
import com.kiva.application.usecase.content.usecase.query.GetPageUc
import com.kiva.infrastructure.common.http.respondResultCacheable
import com.kiva.infrastructure.httpserver.controller.httprest.model.response.FaqResponseHttpResponse
import com.kiva.infrastructure.httpserver.controller.httprest.model.response.StaticPageHttpResponse
import io.ktor.server.routing.Route
import io.ktor.server.routing.get

/** The use cases of the site's public content, as the routes need them. */
class ContentUseCases(
    val getPage: GetPageUc,
    val getFaq: GetFaqUc,
)

/**
 * The site's content for everyone (contract tag `Content`): static pages and the FAQ. Mounted under `/api/v1`; no login.
 * Answers are the same for everyone, so they are cached (`ETag`, `max-age=300`; roadmap phase 5, decision 21).
 *
 * @param siteUrl the shop's address, for a page's canonical address.
 */
fun Route.webV1Content(useCases: ContentUseCases, siteUrl: String) {
    get("/pages/{slug}") {
        val query = GetPageUc.Qry(call.pathParameters["slug"].orEmpty())
        call.respondResultCacheable(useCases.getPage.execute(query)) { StaticPageHttpResponse.from(it, siteUrl) }
    }
    get("/faq") {
        val query = GetFaqUc.Qry(call.request.queryParameters["q"])
        call.respondResultCacheable(useCases.getFaq.execute(query)) { FaqResponseHttpResponse.from(it) }
    }
}
