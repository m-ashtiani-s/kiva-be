package com.kiva.infrastructure.httpserver.controller.httprest.web

import com.kiva.application.usecase.account.usecase.query.GetMeUc
import com.kiva.application.usecase.auth.usecase.command.SendLoginCodeUc
import com.kiva.application.usecase.auth.usecase.command.VerifyLoginCodeUc
import com.kiva.common.railway.unwrapOr
import com.kiva.infrastructure.common.http.respondFailure
import com.kiva.infrastructure.common.http.respondResult
import com.kiva.infrastructure.httpserver.auth.setRefreshCookie
import com.kiva.infrastructure.httpserver.controller.httprest.model.request.SendOtpHttpRequest
import com.kiva.infrastructure.httpserver.controller.httprest.model.request.VerifyOtpHttpRequest
import com.kiva.infrastructure.httpserver.controller.httprest.model.response.AuthHttpResponse
import com.kiva.infrastructure.httpserver.controller.httprest.model.response.SendOtpHttpResponse
import io.ktor.server.plugins.origin
import io.ktor.server.plugins.ratelimit.RateLimitName
import io.ktor.server.plugins.ratelimit.rateLimit
import io.ktor.server.request.receive
import io.ktor.server.request.userAgent
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.post

/** The limit per client address on asking for codes; registered by configureHttp. */
val OTP_SEND_RATE_LIMIT = RateLimitName("otp-send")

/**
 * Login (contract tag `Auth`). Mounted under `/api/v1`. The status of each failure comes from its kind (see
 * respondResult); a 429 also carries Retry-After.
 */
fun Route.webV1Auth(sendLoginCodeUc: SendLoginCodeUc, verifyLoginCodeUc: VerifyLoginCodeUc, getMeUc: GetMeUc) {
    rateLimit(OTP_SEND_RATE_LIMIT) {
        post("/auth/otp/send") {
            val request = call.receive<SendOtpHttpRequest>()
            val command = SendLoginCodeUc.Cmd(phone = request.phone, purpose = request.purpose, ip = call.request.origin.remoteAddress)
            call.respondResult(sendLoginCodeUc.execute(command)) { phone -> SendOtpHttpResponse.of(phone) }
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
        val session = verifyLoginCodeUc.execute(command).unwrapOr { return@post call.respondFailure(it) }
        val user = getMeUc.execute(GetMeUc.Qry(session.userId)).unwrapOr { return@post call.respondFailure(it) }

        call.response.setRefreshCookie(session.refreshToken, session.refreshTokenMaxAge)
        call.respond(AuthHttpResponse.of(session, user))
    }
}
