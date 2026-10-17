package com.kiva.infrastructure.common.http

import com.kiva.common.railway.FailureKind
import com.kiva.common.railway.ResultFailure
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.plugins.callid.callId
import io.ktor.server.request.path
import io.ktor.server.response.respondText
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * The body of every error response, as the API contract defines it
 * (RFC 7807 "problem details" + Kiva's own fields), sent as `application/problem+json`:
 * ```json
 * { "status": 400, "code": "VALIDATION_ERROR", "message": "چند تا از فیلدها رو باید درست کنی",
 *   "instance": "/api/v1/me/addresses", "traceId": "4bf92f3577b34da6",
 *   "errors": [ { "field": "postalCode", "code": "POSTAL_CODE_INVALID", "message": "..." } ] }
 * ```
 * - `code` is for the client's logic, `message` is shown to the user as-is.
 * - `errors` only for field problems, `meta` only when the failure carries extra values.
 */
@Serializable
data class ProblemHttpResponse(
    val status: Int,
    val code: String,
    val message: String,
    val instance: String? = null,
    val traceId: String? = null,
    val errors: List<FieldErrorHttpResponse>? = null,
    val meta: JsonObject? = null,
)

@Serializable
data class FieldErrorHttpResponse(val field: String, val code: String, val message: String)

/** The one place where a business failure kind becomes an HTTP status. */
fun FailureKind.toHttpStatus(): HttpStatusCode = when (this) {
    FailureKind.INVALID -> HttpStatusCode.BadRequest
    FailureKind.UNAUTHORIZED -> HttpStatusCode.Unauthorized
    FailureKind.FORBIDDEN -> HttpStatusCode.Forbidden
    FailureKind.NOT_FOUND -> HttpStatusCode.NotFound
    FailureKind.CONFLICT -> HttpStatusCode.Conflict
    FailureKind.BUSINESS_RULE -> HttpStatusCode.UnprocessableEntity
    FailureKind.RATE_LIMITED -> HttpStatusCode.TooManyRequests
    FailureKind.PRECONDITION_FAILED -> HttpStatusCode.PreconditionFailed
    FailureKind.PAYLOAD_TOO_LARGE -> HttpStatusCode.PayloadTooLarge
    FailureKind.UNSUPPORTED_MEDIA_TYPE -> HttpStatusCode.UnsupportedMediaType
}

/**
 * Builds the problem for a failure.
 *
 * - INVALID with field problems inside -> `400 VALIDATION_ERROR`, every field problem listed in `errors`
 *   with its full path (`newAddress.postalCode`).
 * - Anything else -> the failure's own code, the status of its kind, its Persian message and meta.
 */
fun ResultFailure.toProblem(traceId: String?, instance: String?): ProblemHttpResponse {
    val fieldErrors = if (kind == FailureKind.INVALID) fieldErrorsOf(this, parentPath = null) else emptyList()
    if (fieldErrors.isNotEmpty()) {
        return ProblemHttpResponse(
            status = HttpStatusCode.BadRequest.value,
            code = ErrorMessages.VALIDATION_ERROR,
            message = ErrorMessages.messageFor(ErrorMessages.VALIDATION_ERROR),
            instance = instance,
            traceId = traceId,
            errors = fieldErrors,
            meta = meta.toJsonOrNull(),
        )
    }
    return ProblemHttpResponse(
        status = kind.toHttpStatus().value,
        code = code,
        message = ErrorMessages.messageFor(code, kind, meta),
        instance = instance,
        traceId = traceId,
        meta = meta.toJsonOrNull(),
    )
}

/** Leaves of the failure tree that name a field, with the field path built from every level. */
private fun fieldErrorsOf(failure: ResultFailure, parentPath: String?): List<FieldErrorHttpResponse> {
    val path = listOfNotNull(parentPath, failure.field).joinToString(".").ifEmpty { null }
    if (failure.causes.isNotEmpty()) return failure.causes.flatMap { fieldErrorsOf(it, path) }
    if (path == null) return emptyList()
    return listOf(FieldErrorHttpResponse(path, failure.code, ErrorMessages.messageFor(failure.code, failure.kind, failure.meta)))
}

/** A problem that doesn't come from a failure (unreadable body, unknown route, crash). */
fun ApplicationCall.problem(status: HttpStatusCode, code: String, message: String = ErrorMessages.messageFor(code)) =
    ProblemHttpResponse(status = status.value, code = code, message = message, instance = request.path(), traceId = callId)

suspend fun ApplicationCall.respondProblem(problem: ProblemHttpResponse) {
    respondText(
        text = KivaJson.encodeToString(ProblemHttpResponse.serializer(), problem),
        contentType = ContentType.Application.ProblemJson,
        status = HttpStatusCode.fromValue(problem.status),
    )
}

private fun Map<String, Any>.toJsonOrNull(): JsonObject? = if (isEmpty()) null else JsonObject(mapValues { it.value.toJson() })

private fun Any?.toJson(): JsonElement = when (this) {
    null -> JsonNull
    is Number -> JsonPrimitive(this)
    is Boolean -> JsonPrimitive(this)
    is String -> JsonPrimitive(this)
    is Iterable<*> -> JsonArray(map { it.toJson() })
    is Map<*, *> -> JsonObject(entries.associate { (k, v) -> k.toString() to v.toJson() })
    else -> JsonPrimitive(toString())
}
