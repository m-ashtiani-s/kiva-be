package com.kiva.infrastructure.common.http

import com.kiva.common.railway.FailureKind
import com.kiva.common.railway.Result
import com.kiva.common.railway.ResultFailure
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respond

/** The one place where a business failure kind becomes an HTTP status. */
fun FailureKind.toHttpStatus(): HttpStatusCode = when (this) {
    FailureKind.INVALID -> HttpStatusCode.BadRequest
    FailureKind.NOT_FOUND -> HttpStatusCode.NotFound
    FailureKind.CONFLICT -> HttpStatusCode.Conflict
    FailureKind.UNAUTHORIZED -> HttpStatusCode.Unauthorized
    FailureKind.FORBIDDEN -> HttpStatusCode.Forbidden
}

/**
 * Sends a use case result as the HTTP response.
 * - Success: [successStatus] with the body built by [mapper] (use case output -> response model).
 * - Failure: status from the failure's kind, body [FailureHttpResponse].
 *
 * ```kotlin
 * get("/products/{slug}") {
 *     call.respondResult(getProduct.execute(GetProductQry(slug))) { ProductHttpResponse.from(it) }
 * }
 * ```
 */
suspend inline fun <T, reified R : Any> ApplicationCall.respondResult(
    result: Result<T, ResultFailure>,
    successStatus: HttpStatusCode = HttpStatusCode.OK,
    mapper: (T) -> R,
) {
    when (result) {
        is Result.Success -> respond(successStatus, mapper(result.value))
        is Result.Failure -> respondFailure(result.failure)
    }
}

/** For commands that return nothing: success becomes `204 No Content` with an empty body. */
suspend fun ApplicationCall.respondResult(result: Result<Unit, ResultFailure>) {
    when (result) {
        is Result.Success -> respond(HttpStatusCode.NoContent)
        is Result.Failure -> respondFailure(result.failure)
    }
}

suspend fun ApplicationCall.respondFailure(failure: ResultFailure) {
    respond(failure.kind.toHttpStatus(), FailureHttpResponse.from(failure))
}
