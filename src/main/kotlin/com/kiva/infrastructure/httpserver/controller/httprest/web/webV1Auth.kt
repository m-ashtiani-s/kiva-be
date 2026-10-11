package com.kiva.infrastructure.httpserver.controller.httprest.web

import com.kiva.application.usecase.account.usecase.query.GetMeUc
import com.kiva.application.usecase.auth.usecase.command.LogoutUc
import com.kiva.application.usecase.auth.usecase.command.RefreshSessionUc
import com.kiva.application.usecase.auth.usecase.command.SendLoginCodeUc
import com.kiva.application.usecase.auth.usecase.command.VerifyLoginCodeUc
import com.kiva.common.railway.unwrapOr
import com.kiva.infrastructure.common.http.KivaJson
import com.kiva.infrastructure.common.http.respondFailure
import com.kiva.infrastructure.common.http.respondResult
import com.kiva.infrastructure.httpserver.auth.RefreshCookie
import com.kiva.infrastructure.httpserver.auth.clearRefreshCookie
import com.kiva.infrastructure.httpserver.auth.requireAllowedOrigin
import com.kiva.infrastructure.httpserver.auth.setRefreshCookie
import com.kiva.infrastructure.httpserver.controller.httprest.model.request.LogoutHttpRequest
import com.kiva.infrastructure.httpserver.controller.httprest.model.request.SendOtpHttpRequest
import com.kiva.infrastructure.httpserver.controller.httprest.model.request.VerifyOtpHttpRequest
import com.kiva.infrastructure.httpserver.controller.httprest.model.response.AuthHttpResponse
import com.kiva.infrastructure.httpserver.controller.httprest.model.response.AuthTokensHttpResponse
import com.kiva.infrastructure.httpserver.controller.httprest.model.response.SendOtpHttpResponse
import io.ktor.http.HttpStatusCode
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.plugins.origin
import io.ktor.server.plugins.ratelimit.RateLimitName
import io.ktor.server.plugins.ratelimit.rateLimit
import io.ktor.server.request.receive
import io.ktor.server.request.receiveText
import io.ktor.server.request.userAgent
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.RoutingCall
import io.ktor.server.routing.post
import kotlinx.serialization.SerializationException

/** The limit per client address on asking for codes; registered by configureHttp. */
val OTP_SEND_RATE_LIMIT = RateLimitName("otp-send")

/** The use cases of login, as the routes need them. */
class AuthUseCases(
    val sendLoginCode: SendLoginCodeUc,
    val verifyLoginCode: VerifyLoginCodeUc,
    val getMe: GetMeUc,
    val refreshSession: RefreshSessionUc,
    val logout: LogoutUc,
)

/**
 * Login (contract tag `Auth`). Mounted under `/api/v1`. The status of each failure comes from its kind (see
 * respondResult); a 429 also carries Retry-After.
 *
 * The refresh token travels only in the `kiva_rt` cookie, which the browser sends by itself to the `/auth` endpoints; so
 * `/auth/refresh` and `/auth/logout` answer only the shop's own frontends ([allowedOrigins], checked on `Origin`).
 */
fun Route.webV1Auth(useCases: AuthUseCases, allowedOrigins: List<String>) {
    rateLimit(OTP_SEND_RATE_LIMIT) {
        post("/auth/otp/send") {
            val request = call.receive<SendOtpHttpRequest>()
            val command = SendLoginCodeUc.Cmd(phone = request.phone, purpose = request.purpose, ip = call.request.origin.remoteAddress)
            call.respondResult(useCases.sendLoginCode.execute(command)) { phone -> SendOtpHttpResponse.of(phone) }
        }
    }

    // The session's refresh token goes only into the cookie; the body has the access token and the user.
    post("/auth/otp/verify") {
        val request = call.receive<VerifyOtpHttpRequest>()
        val command = VerifyLoginCodeUc.Cmd(
            phone = request.phone,
            code = request.code,
            deviceName = request.device?.name,
            userAgent = call.request.userAgent(),
            ip = call.request.origin.remoteAddress,
        )
        val session = useCases.verifyLoginCode.execute(command).unwrapOr { return@post call.respondFailure(it) }
        val user = useCases.getMe.execute(GetMeUc.Qry(session.userId)).unwrapOr { return@post call.respondFailure(it) }

        session.refreshToken?.let { call.response.setRefreshCookie(it, session.refreshTokenMaxAge) }
        call.respond(AuthHttpResponse.of(session, user))
    }

    // No body: the cookie is the whole request. A refused cookie is dropped, so the browser doesn't keep sending it.
    post("/auth/refresh") {
        if (!call.requireAllowedOrigin(allowedOrigins)) return@post
        val command = RefreshSessionUc.Cmd(call.refreshCookie(), ip = call.request.origin.remoteAddress)
        val session = useCases.refreshSession.execute(command).unwrapOr {
            call.response.clearRefreshCookie()
            return@post call.respondFailure(it)
        }

        session.refreshToken?.let { call.response.setRefreshCookie(it, session.refreshTokenMaxAge) }
        call.respond(AuthTokensHttpResponse.of(session))
    }

    // Always 204 with the cookie cleared, whatever was behind it; no access token needed.
    post("/auth/logout") {
        if (!call.requireAllowedOrigin(allowedOrigins)) return@post
        val command = LogoutUc.Cmd(call.refreshCookie(), allDevices = call.logoutRequest().allDevices, ip = call.request.origin.remoteAddress)
        useCases.logout.execute(command)

        call.response.clearRefreshCookie()
        call.respond(HttpStatusCode.NoContent)
    }
}

/** The refresh token cookie as the browser sent it, not decoded: it is base64url, which needs no decoding. */
private fun RoutingCall.refreshCookie(): String? = request.cookies.rawCookies[RefreshCookie.NAME]

/** The optional body of a logout: none, or `{"allDevices": true}`. */
private suspend fun RoutingCall.logoutRequest(): LogoutHttpRequest {
    val body = receiveText()
    if (body.isBlank()) return LogoutHttpRequest()
    return try {
        KivaJson.decodeFromString(LogoutHttpRequest.serializer(), body)
    } catch (e: SerializationException) {
        throw BadRequestException("Unreadable logout body", e)
    }
}
