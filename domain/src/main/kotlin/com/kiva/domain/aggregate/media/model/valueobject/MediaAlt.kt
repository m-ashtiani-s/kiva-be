package com.kiva.domain.aggregate.media.model.valueobject

import com.kiva.common.railway.FailureKind
import com.kiva.common.railway.Result
import com.kiva.common.railway.ResultFailure
import com.kiva.common.text.normalizePersianLetters

/** A file's alt text, shown as it is: trimmed, Persian letters made one, at most [MAX_LENGTH]. Blank is none. */
@JvmInline
value class MediaAlt private constructor(val value: String) {

    // Second wall: makeNew already refuses bad input with a Failure, so this only fails on broken stored data. A bug: stop loudly.
    init {
        require(value.isNotBlank() && value.length <= MAX_LENGTH) { "Invalid MediaAlt '$value'" }
    }

    companion object {
        const val MAX_LENGTH = 200

        /** Null for a missing or blank alt: the file has none. */
        fun makeNew(raw: String?): Result<MediaAlt?, Failure> {
            val text = raw?.trim()?.normalizePersianLetters()?.ifEmpty { null } ?: return Result.success(null)
            return if (text.length > MAX_LENGTH) Result.failure(Failure.TooLong) else Result.success(MediaAlt(text))
        }

        /** For values read back from storage. Still checked by `init`. */
        fun constructFromPersisted(value: String): MediaAlt = MediaAlt(value)
    }

    sealed class Failure(code: String, meta: Map<String, Any>) : ResultFailure(code, FailureKind.INVALID, field = "alt", meta = meta) {
        data object TooLong : Failure("MEDIA_ALT_TOO_LONG", mapOf("maxLength" to MAX_LENGTH))
    }
}
