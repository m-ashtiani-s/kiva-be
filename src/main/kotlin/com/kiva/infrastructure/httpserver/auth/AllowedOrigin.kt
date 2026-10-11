package com.kiva.infrastructure.httpserver.auth

import com.kiva.infrastructure.common.http.problem
import com.kiva.infrastructure.common.http.respondProblem
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall

/**
 * The CSRF guard of the two endpoints the browser sends the refresh cookie to by itself (`/auth/refresh`,
 * `/auth/logout`): they answer only a request whose `Origin` is one of the shop's frontends. A page on another site
 * can make the browser send the cookie (SameSite=Strict already stops that in browsers that honour it); it can't make
 * the browser lie about the Origin. A request without an Origin is refused too: browsers always send one with a POST.
 *
 * @return true when the request may go on; otherwise it has been answered with 403 FORBIDDEN.
 */
suspend fun ApplicationCall.requireAllowedOrigin(allowedOrigins: List<String>): Boolean {
    val origin = request.headers[HttpHeaders.Origin]
    if (origin != null && origin in allowedOrigins) return true
    respondProblem(problem(HttpStatusCode.Forbidden, FORBIDDEN))
    return false
}

const val FORBIDDEN = "FORBIDDEN"
