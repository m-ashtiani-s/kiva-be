package com.kiva.domain.aggregate.media.model.valueobject

import com.kiva.common.railway.FailureKind
import com.kiva.common.railway.Result
import com.kiva.common.railway.ResultFailure

/**
 * The public id of an uploaded file (contract `MediaAsset.id`): `med_` and 22 random base62 characters, about 130 bits,
 * so nobody finds a file by counting. Made by MediaIdGenerator, never by the client.
 */
@JvmInline
value class MediaId private constructor(val value: String) {

    // Second wall: makeNew already refuses bad input with a Failure, so this only fails on broken stored data. A bug: stop loudly.
    init {
        require(isValid(value)) { "Invalid MediaId '$value'" }
    }

    companion object {
        const val PREFIX = "med_"
        const val RANDOM_LENGTH = 22
        private val FORMAT = Regex("^med_[0-9A-Za-z]{22}$")

        /** A media id a client sent (a gallery's `mediaId`): trimmed, exactly the format. */
        fun makeNew(raw: String?): Result<MediaId, Failure> {
            val text = raw?.trim().orEmpty()
            return if (isValid(text)) Result.success(MediaId(text)) else Result.failure(Failure.Invalid)
        }

        /** For values read back from storage, and the ones the generator makes. Still checked by `init`. */
        fun constructFromPersisted(value: String): MediaId = MediaId(value)

        private fun isValid(value: String) = FORMAT.matches(value)
    }

    sealed class Failure(code: String) : ResultFailure(code, FailureKind.INVALID) {
        data object Invalid : Failure("MEDIA_ID_INVALID")
    }
}
