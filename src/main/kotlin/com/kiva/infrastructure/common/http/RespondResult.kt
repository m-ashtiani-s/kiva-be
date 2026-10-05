package com.kiva.infrastructure.common.http

import com.kiva.common.railway.Result
import com.kiva.common.railway.ResultFailure
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.plugins.callid.callId
import io.ktor.server.request.path
import io.ktor.server.response.respond

/**
 * Sends a use case result as the HTTP response.
 * - Success: [successStatus] with the body built by [mapper] (use case output -> response model).
 * - Failure: a problem response (see [toProblem]).
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
    respondProblem(failure.toProblem(traceId = callId, instance = request.path()))
}
