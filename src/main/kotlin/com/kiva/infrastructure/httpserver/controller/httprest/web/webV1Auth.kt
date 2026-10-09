package com.kiva.infrastructure.httpserver.controller.httprest.web

import com.kiva.application.usecase.auth.usecase.command.SendLoginCodeUc
import com.kiva.infrastructure.common.http.respondResult
import com.kiva.infrastructure.httpserver.controller.httprest.model.request.SendOtpHttpRequest
import com.kiva.infrastructure.httpserver.controller.httprest.model.response.SendOtpHttpResponse
import io.ktor.server.plugins.origin
import io.ktor.server.plugins.ratelimit.RateLimitName
import io.ktor.server.plugins.ratelimit.rateLimit
import io.ktor.server.request.receive
import io.ktor.server.routing.Route
import io.ktor.server.routing.post

/** The limit per client address on asking for codes; registered by configureHttp. */
val OTP_SEND_RATE_LIMIT = RateLimitName("otp-send")

/**
 * Login (contract tag `Auth`). Mounted under `/api/v1`. The status of each failure comes from its kind (see
 * respondResult); a 429 also carries Retry-After.
 */
fun Route.webV1Auth(sendLoginCodeUc: SendLoginCodeUc) {
    rateLimit(OTP_SEND_RATE_LIMIT) {
        post("/auth/otp/send") {
            val request = call.receive<SendOtpHttpRequest>()
            val command = SendLoginCodeUc.Cmd(phone = request.phone, purpose = request.purpose, ip = call.request.origin.remoteAddress)
            call.respondResult(sendLoginCodeUc.execute(command)) { phone -> SendOtpHttpResponse.of(phone) }
        }
    }
}
