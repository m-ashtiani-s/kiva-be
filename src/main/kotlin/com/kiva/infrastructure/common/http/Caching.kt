package com.kiva.infrastructure.common.http

import com.kiva.common.railway.Result
import com.kiva.common.railway.ResultFailure
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.withCharset
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import java.security.MessageDigest

/**
 * How long a browser or a shared cache may keep a cacheable answer without asking again
 * (contract: `ETag` + `Cache-Control: public, max-age=300`).
 */
const val PUBLIC_MAX_AGE_SECONDS = 300

/**
 * [respondResult] for answers that are the same for everyone and change rarely (the province and city lists, later
 * `/categories` and `/colors`): a success goes out through [respondCacheable], a failure as usual and never cached.
 */
suspend inline fun <T, reified R : Any> ApplicationCall.respondResultCacheable(
    result: Result<T, ResultFailure>,
    mapper: (T) -> R,
) {
    when (result) {
        is Result.Success -> respondCacheable(mapper(result.value))
        is Result.Failure -> respondFailure(result.failure)
    }
}

/**
 * Sends [body] as JSON with an `ETag` made from the body itself and `Cache-Control: public, max-age=…`.
 * When the request's `If-None-Match` already names that tag, the answer is `304 Not Modified` with no body:
 * the client keeps what it has, and only the headers cross the network.
 *
 * The tag is a hash of the body, so it is right whatever changed the data, with no version to bump by hand.
 * The body is still built for every request; what is saved is the transfer, not the work.
 */
suspend inline fun <reified R : Any> ApplicationCall.respondCacheable(body: R, maxAgeSeconds: Int = PUBLIC_MAX_AGE_SECONDS) {
    respondCacheableJson(KivaJson.encodeToString(body), maxAgeSeconds)
}

/** The work of [respondCacheable] once the body is JSON text. */
suspend fun ApplicationCall.respondCacheableJson(json: String, maxAgeSeconds: Int = PUBLIC_MAX_AGE_SECONDS) {
    val etag = weakEtagOf(json)
    // On a 304 too: the client refreshes its copy's freshness from these.
    response.header(HttpHeaders.ETag, etag)
    response.header(HttpHeaders.CacheControl, "public, max-age=$maxAgeSeconds")
    if (request.headers.getAll(HttpHeaders.IfNoneMatch).orEmpty().any { ifNoneMatchHits(it, etag) }) {
        respond(HttpStatusCode.NotModified)
    } else {
        respondText(json, ContentType.Application.Json.withCharset(Charsets.UTF_8))
    }
}

/**
 * A weak tag (`W/"…"`): the first 128 bits of the body's SHA-256, in hex. Weak, because nginx compresses the body on
 * the way out and drops a strong tag from a compressed answer; the meaning is the same, only the bytes differ.
 */
fun weakEtagOf(body: String): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(body.toByteArray(Charsets.UTF_8))
    return "W/\"" + digest.take(16).joinToString("") { "%02x".format(it) } + "\""
}

/**
 * Whether one `If-None-Match` header names [etag]: `*`, or a comma-separated list of tags. Compared weakly, as
 * RFC 9110 asks for If-None-Match: `W/"x"` and `"x"` are the same tag.
 */
fun ifNoneMatchHits(header: String, etag: String): Boolean {
    val wanted = etag.removePrefix("W/")
    return header.split(',').map { it.trim() }.any { it == "*" || it.removePrefix("W/") == wanted }
}
