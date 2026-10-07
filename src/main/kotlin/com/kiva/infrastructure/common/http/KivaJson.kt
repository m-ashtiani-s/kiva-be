package com.kiva.infrastructure.common.http

import kotlinx.serialization.json.Json

/**
 * The one JSON setup of the API, used for normal responses and for error (problem) responses alike.
 * - unknown fields in requests are ignored, so an older server doesn't break on a newer client
 * - every field of a response is always there; one without a value is `null`, never left out (contract 1.3.0,
 *   "فیلدهای بی‌مقدار"). The client then always gets the same shape, and a field missing from a response is a bug.
 *   So every nullable property of a response class must be `nullable` in the contract too.
 *   Both switches are needed: explicitNulls writes a null, and encodeDefaults writes a property whose value is its
 *   default (`= null`, `= emptyList()`), which would otherwise be left out.
 * - in requests, an optional field the client left out is null only if its property has a default (`= null`):
 *   with explicitNulls on, a missing field without one is an error.
 */
val KivaJson = Json {
    ignoreUnknownKeys = true
    explicitNulls = true
    encodeDefaults = true
}
