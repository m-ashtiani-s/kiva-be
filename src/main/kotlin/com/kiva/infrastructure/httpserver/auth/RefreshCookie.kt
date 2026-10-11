package com.kiva.infrastructure.httpserver.auth

import com.kiva.infrastructure.httpserver.API_V1
import io.ktor.http.HttpHeaders
import io.ktor.server.response.ApplicationResponse
import java.time.Duration

/**
 * The cookie that carries the refresh token (`kiva_rt`, contract SetRefreshCookie):
 * - HttpOnly: the page's JavaScript, and so any XSS, can't read it
 * - Secure: only over HTTPS (browsers treat http://localhost as secure, so development works too)
 * - SameSite=Strict: another site can't make the browser send it
 * - Path=/api/v1/auth: the browser sends it only to the login endpoints, not with every API call
 *
 * Written as the header itself, not through Ktor's cookie API: that one refuses a Secure cookie on a plain-HTTP
 * request, which is every request behind our TLS-ending nginx and in development, and it would add an encoding marker.
 */
object RefreshCookie {
    const val NAME = "kiva_rt"
    const val PATH = "$API_V1/auth"
    private const val ATTRIBUTES = "Path=$PATH; Secure; HttpOnly; SameSite=Strict"

    // base64url only: nothing in it can end the value or start another attribute.
    private val TOKEN = Regex("[A-Za-z0-9_-]+")

    fun header(token: String, maxAge: Duration): String {
        require(TOKEN.matches(token)) { "A refresh token is base64url" }
        require(!maxAge.isNegative) { "A cookie's Max-Age can't be negative" }
        return "$NAME=$token; Max-Age=${maxAge.seconds}; $ATTRIBUTES"
    }

    /** Tells the browser to forget the cookie. */
    fun clearingHeader(): String = "$NAME=; Max-Age=0; $ATTRIBUTES"
}

fun ApplicationResponse.setRefreshCookie(token: String, maxAge: Duration) {
    headers.append(HttpHeaders.SetCookie, RefreshCookie.header(token, maxAge))
}

fun ApplicationResponse.clearRefreshCookie() {
    headers.append(HttpHeaders.SetCookie, RefreshCookie.clearingHeader())
}
