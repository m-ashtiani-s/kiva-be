package com.kiva.infrastructure.common.http

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.request.httpMethod
import io.ktor.server.request.uri
import io.ktor.server.response.respond
import org.slf4j.LoggerFactory

private val logger = LoggerFactory.getLogger("com.kiva.infrastructure.common.http.StatusPages")

/**
 * Safety net for exceptions that escape a route. Expected failures never get here; they travel as Result.
 *
 * - Body Ktor could not read (broken JSON, missing field, wrong type) -> 400 `MALFORMED_REQUEST`.
 * - Anything else is a bug or an outage -> 500 `INTERNAL_ERROR`.
 *
 * The client only ever sees the code. Exception messages and stack traces go to the log,
 * because they can reveal table names, SQL or secrets.
 */
fun Application.configureStatusPages() {
    install(StatusPages) {
        exception<BadRequestException> { call, cause ->
            logger.info("Malformed request {} {}: {}", call.request.httpMethod.value, call.request.uri, cause.message)
            call.respond(HttpStatusCode.BadRequest, FailureHttpResponse(code = "MALFORMED_REQUEST"))
        }
        exception<Throwable> { call, cause ->
            logger.error("Unhandled error on ${call.request.httpMethod.value} ${call.request.uri}", cause)
            call.respond(HttpStatusCode.InternalServerError, FailureHttpResponse(code = "INTERNAL_ERROR"))
        }
    }
}
