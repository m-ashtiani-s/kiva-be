package com.kiva.infrastructure.httpserver.controller.httprest.web

import com.kiva.application.usecase.search.usecase.query.GetSearchHintsUc
import com.kiva.application.usecase.search.usecase.query.SearchSuggestUc
import com.kiva.infrastructure.common.http.respondResultCacheable
import com.kiva.infrastructure.httpserver.controller.httprest.model.response.SearchHintsHttpResponse
import com.kiva.infrastructure.common.http.respondResult
import com.kiva.infrastructure.httpserver.auth.ACCESS
import com.kiva.infrastructure.httpserver.controller.httprest.model.response.SearchSuggestHttpResponse
import com.kiva.infrastructure.media.MediaUrls
import io.ktor.server.auth.authenticate
import io.ktor.server.routing.Route
import io.ktor.server.routing.get

/** The use cases of the shop's search, as the routes need them. */
class SearchUseCases(
    val suggest: SearchSuggestUc,
    val hints: GetSearchHintsUc,
)

/**
 * The shop's search (contract tag `Search`): the header's live suggestions and the panel's first hints. Mounted under
 * `/api/v1`. On the suggestions a login is optional and changes nothing (a broken token is still 401, never a guest);
 * they are never cached (nginx caps them per IP). The hints are public and cached for 5 minutes.
 */
fun Route.webV1Search(useCases: SearchUseCases, mediaUrls: MediaUrls) {
    get("/search/hints") {
        call.respondResultCacheable(useCases.hints.execute(GetSearchHintsUc.Qry)) { SearchHintsHttpResponse.from(it) }
    }

    authenticate(ACCESS, optional = true) {
        get("/search/suggest") {
            val params = call.request.queryParameters
            call.respondResult(useCases.suggest.execute(SearchSuggestUc.Qry(params["q"], params["limit"]))) { SearchSuggestHttpResponse.from(it, mediaUrls) }
        }
    }
}
