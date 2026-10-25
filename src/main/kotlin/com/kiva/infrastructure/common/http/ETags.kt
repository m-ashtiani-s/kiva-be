package com.kiva.infrastructure.common.http

import com.kiva.common.railway.Result
import com.kiva.common.railway.ResultFailure
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.header
import io.ktor.server.response.respond

/**
 * The version of a thing the panel edits, as an ETag (roadmap section 5.3): weak, because the body is not byte for
 * byte the same between two reads of one version (`W/"12"` for version 12). The body's `etag` field is the same text.
 */
object VersionETag {
    fun of(version: Long): String = "W/\"$version\""
}

/**
 * [respondResult] for a body that has a version: on success the `ETag` header says it too ([etagOf]), so the panel can
 * send it back in `If-Match`. A failure is answered as usual.
 */
suspend inline fun <T, reified R : Any> ApplicationCall.respondResultWithETag(
    result: Result<T, ResultFailure>,
    successStatus: HttpStatusCode = HttpStatusCode.OK,
    etagOf: (T) -> String,
    mapper: (T) -> R,
) {
    when (result) {
        is Result.Success -> {
            response.header(HttpHeaders.ETag, etagOf(result.value))
            respond(successStatus, mapper(result.value))
        }
        is Result.Failure -> respondFailure(result.failure)
    }
}
