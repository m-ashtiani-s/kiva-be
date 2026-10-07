package com.kiva.infrastructure.common.http

import com.kiva.common.event.ConcurrentChangeException
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.plugins.NotFoundException
import io.ktor.server.plugins.ContentTransformationException
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.request.httpMethod
import io.ktor.server.request.uri
import org.slf4j.LoggerFactory

private val logger = LoggerFactory.getLogger("com.kiva.infrastructure.common.http.StatusPages")

/**
 * Safety net for everything that is not a business failure. Expected failures never get here; they travel as Result.
 * Every response is a problem (application/problem+json), same shape as business failures.
 *
 * - Body Ktor could not read (broken JSON, missing field, wrong type) -> 400 VALIDATION_ERROR
 * - Body in a format we don't read (not JSON) -> 415 VALIDATION_ERROR
 * - No route for this path -> 404 NOT_FOUND
 * - Someone else saved a change to the same thing between our read and our save -> 409 CHANGED_BY_SOMEONE_ELSE
 * - Anything else is a bug or an outage -> 500 INTERNAL_ERROR
 *
 * The client never sees exception messages or stack traces; they go to the log with the traceId,
 * because they can reveal table names, SQL or secrets.
 */
fun Application.configureStatusPages() {
    install(StatusPages) {
        // Thrown when there is no JSON to read at all (e.g. text/plain); broken JSON is a BadRequestException instead.
        exception<ContentTransformationException> { call, cause ->
            logger.info("Unsupported media type {} {}: {}", call.request.httpMethod.value, call.request.uri, cause.message)
            call.respondProblem(call.problem(HttpStatusCode.UnsupportedMediaType, ErrorMessages.VALIDATION_ERROR, "اطلاعات باید با فرمت JSON فرستاده بشه"))
        }
        exception<BadRequestException> { call, cause ->
            logger.info("Unreadable request {} {}: {}", call.request.httpMethod.value, call.request.uri, cause.message)
            call.respondProblem(call.problem(HttpStatusCode.BadRequest, ErrorMessages.VALIDATION_ERROR, "اطلاعات ارسالی قابل خوندن نیست"))
        }
        exception<NotFoundException> { call, _ ->
            call.respondProblem(call.problem(HttpStatusCode.NotFound, ErrorMessages.NOT_FOUND))
        }
        // A 404 without a problem body means no route matched. Our own 404s (PRODUCT_NOT_FOUND, ...) are
        // already problems and must reach the client unchanged, so they are left alone.
        status(HttpStatusCode.NotFound) { _ ->
            val alreadyProblem = content.contentType?.match(ContentType.Application.ProblemJson) == true
            if (!alreadyProblem) call.respondProblem(call.problem(HttpStatusCode.NotFound, ErrorMessages.NOT_FOUND))
        }
        // A race, not a bug: the user can look at the new state and try again. Nothing of the request was saved.
        exception<ConcurrentChangeException> { call, cause ->
            logger.info("Concurrent change on {} {}: {}", call.request.httpMethod.value, call.request.uri, cause.message)
            call.respondProblem(call.problem(HttpStatusCode.Conflict, ErrorMessages.CHANGED_BY_SOMEONE_ELSE))
        }
        exception<Throwable> { call, cause ->
            logger.error("Unhandled error on ${call.request.httpMethod.value} ${call.request.uri}", cause)
            call.respondProblem(call.problem(HttpStatusCode.InternalServerError, ErrorMessages.INTERNAL_ERROR))
        }
    }
}
