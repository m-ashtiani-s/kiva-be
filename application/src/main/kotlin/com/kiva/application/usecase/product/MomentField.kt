package com.kiva.application.usecase.product

import com.kiva.common.railway.FailureKind
import com.kiva.common.railway.Result
import com.kiva.common.railway.ResultFailure
import java.time.Instant
import java.time.OffsetDateTime
import java.time.format.DateTimeParseException

/**
 * A moment as the contract sends it (`format: date-time`, ISO-8601 with its offset: `2026-11-01T00:00:00Z` or
 * `2026-11-01T03:30:00+03:30`). Null or blank is none; anything else that isn't one is DATE_TIME_INVALID (no field: the
 * caller names it).
 */
object MomentField {

    fun makeNew(raw: String?): Result<Instant?, ResultFailure> {
        val text = raw?.trim()?.ifEmpty { null } ?: return Result.success(null)
        return try {
            Result.success(OffsetDateTime.parse(text).toInstant())
        } catch (e: DateTimeParseException) {
            Result.failure(Invalid)
        }
    }

    data object Invalid : ResultFailure("DATE_TIME_INVALID", FailureKind.INVALID)
}
