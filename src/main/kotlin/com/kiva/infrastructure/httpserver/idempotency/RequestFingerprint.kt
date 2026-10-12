package com.kiva.infrastructure.httpserver.idempotency

import com.kiva.application.port.RequestHash
import com.kiva.infrastructure.httpserver.IDEMPOTENCY_KEY_HEADER
import io.ktor.server.application.ApplicationCall
import kotlinx.serialization.SerializationStrategy
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * The fingerprint of a request that takes an `Idempotency-Key` (roadmap section 12): SHA-256 of its canonical form,
 * so «the same request» means the same thing asked, not the same bytes.
 *
 * The canonical form is the request as the route decoded it, written again by one fixed JSON writer: every field
 * written, defaults and nulls included, so a field left out and the same field sent with its default are one request;
 * keys in alphabetical order at every level; no spaces. The path's parameters are part of it too: a retry of another
 * payment with the same key is another request. Numbers and texts are compared exactly as decoded.
 */
object RequestFingerprint {

    private val canonical = Json {
        encodeDefaults = true
        explicitNulls = true
    }

    /**
     * The fingerprint of [request], decoded with [serializer], at a path with [pathParameters] (e.g.
     * `paymentId = pay_…`).
     */
    fun <T> of(serializer: SerializationStrategy<T>, request: T, pathParameters: Map<String, String> = emptyMap()): RequestHash =
        RequestHash.of(canonicalFormOf(canonical.encodeToJsonElement(serializer, request), pathParameters))

    /** The canonical text itself; [of] hashes it. */
    fun canonicalFormOf(body: JsonElement, pathParameters: Map<String, String>): String =
        sorted(JsonObject(mapOf("body" to body, "path" to JsonObject(pathParameters.mapValues { JsonPrimitive(it.value) })))).toString()

    private fun sorted(element: JsonElement): JsonElement = when (element) {
        is JsonObject -> JsonObject(element.toSortedMap().mapValues { sorted(it.value) })
        is JsonArray -> JsonArray(element.map(::sorted))
        else -> element
    }
}

/** The `Idempotency-Key` header as it arrived, or null; the use case checks it (400 on `Idempotency-Key`). */
fun ApplicationCall.idempotencyKeyHeader(): String? = request.headers[IDEMPOTENCY_KEY_HEADER]
