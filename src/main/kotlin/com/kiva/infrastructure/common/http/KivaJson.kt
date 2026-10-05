package com.kiva.infrastructure.common.http

import kotlinx.serialization.json.Json

/**
 * The one JSON setup of the API, used for normal responses and for error (problem) responses alike.
 * - unknown fields in requests are ignored, so an older server doesn't break on a newer client
 * - null fields are left out of responses, so `errors` / `meta` only appear when they have something to say
 */
val KivaJson = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
}
