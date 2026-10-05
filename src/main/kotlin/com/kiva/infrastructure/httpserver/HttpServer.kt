package com.kiva.infrastructure.httpserver

import com.kiva.infrastructure.common.http.KivaJson
import com.kiva.infrastructure.common.http.configureStatusPages
import com.kiva.infrastructure.httpserver.route.healthRoutes
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.plugins.callid.CallId
import io.ktor.server.plugins.callid.callIdMdc
import io.ktor.server.plugins.calllogging.CallLogging
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.routing.routing
import org.slf4j.event.Level
import java.util.UUID

/** Every public API route lives under this prefix (from the API contract). `/health` stays outside it. */
const val API_V1 = "/api/v1"

/** Response header carrying the id of the request, the same id as `traceId` in error bodies and in the log. */
const val TRACE_ID_HEADER = "X-Trace-Id"

fun Application.configureHttp() {
    install(ContentNegotiation) {
        json(KivaJson)
    }
    // A short random id per request: shown to the client on errors and printed on every log line of that
    // request, so "the error with traceId 4bf92f3577b34da6" can be found in the log.
    install(CallId) {
        generate { UUID.randomUUID().toString().replace("-", "").take(16) }
        replyToHeader(TRACE_ID_HEADER)
    }
    install(CallLogging) {
        level = Level.INFO
        callIdMdc("traceId")
    }
    configureStatusPages()
}

fun Application.configureRouting() {
    routing {
        healthRoutes()
    }
}
