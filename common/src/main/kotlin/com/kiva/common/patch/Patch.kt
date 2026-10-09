package com.kiva.common.patch

import com.kiva.common.railway.Result
import com.kiva.common.railway.ResultFailure
import com.kiva.common.railway.mapOnSuccess

/**
 * One field of a partial update (`PATCH`): left out, or sent with a new value.
 *
 * In JSON, a field that isn't there and a field sent as `null` are two different requests: the first keeps what is
 * stored, the second clears it. A plain nullable can't tell them apart; this can:
 * ```kotlin
 * Patch.Unchanged        // {}                     -> keep the first name
 * Patch.Set(null)        // {"firstName": null}    -> clear it
 * Patch.Set("سارا")      // {"firstName": "سارا"}  -> set it
 * ```
 * Whether a field can be cleared is decided by its type: a `Patch<String?>` can be set to null, a `Patch<Boolean>` can't.
 */
sealed interface Patch<out T> {

    /** The field was not sent: keep what is there. */
    data object Unchanged : Patch<Nothing>

    /** The field was sent with this value. For a nullable [T], null means "clear it". */
    data class Set<out T>(val value: T) : Patch<T>
}

/** The value after the patch: the sent one, or [current] if the field wasn't sent. */
fun <T> Patch<T>.applyTo(current: T): T =
    when (this) {
        Patch.Unchanged -> current
        is Patch.Set -> value
    }

/**
 * Checks a sent value with [makeNew] (usually a value object's factory) and keeps the answer as a patch.
 * A field that wasn't sent is not checked at all.
 * ```kotlin
 * patch.gender.mapResult(Gender::makeNew)   // Result<Patch<Gender>, Gender.Failure>
 * ```
 */
inline fun <T, R, F : ResultFailure> Patch<T>.mapResult(makeNew: (T) -> Result<R, F>): Result<Patch<R>, F> =
    when (this) {
        Patch.Unchanged -> Result.success(Patch.Unchanged)
        is Patch.Set -> makeNew(value).mapOnSuccess { Patch.Set(it) }
    }
