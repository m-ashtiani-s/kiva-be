package com.kiva.infrastructure.httpserver.controller.httprest.web

import com.kiva.application.usecase.account.usecase.query.GetMeUc
import com.kiva.infrastructure.common.http.respondResult
import com.kiva.infrastructure.httpserver.auth.ACCESS
import com.kiva.infrastructure.httpserver.auth.accessPrincipal
import com.kiva.infrastructure.httpserver.controller.httprest.model.response.UserHttpResponse
import io.ktor.server.auth.authenticate
import io.ktor.server.routing.Route
import io.ktor.server.routing.get

/**
 * The logged-in user's own account (contract tag `Account`). Mounted under `/api/v1`; every route here needs a login,
 * and the user is always the token's, never one named in the request.
 */
fun Route.webV1Me(getMeUc: GetMeUc) {
    authenticate(ACCESS) {
        get("/me") {
            call.respondResult(getMeUc.execute(GetMeUc.Qry(call.accessPrincipal().userId))) { UserHttpResponse.from(it) }
        }
    }
}
