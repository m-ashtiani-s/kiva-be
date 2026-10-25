package com.kiva.domain.aggregate.returnrequest.model.valueobject

import com.kiva.common.railway.FailureKind
import com.kiva.common.railway.Result
import com.kiva.common.railway.ResultFailure

/** A return's id (`ReturnRequest.id`, the path's `returnId`): a positive number from `returnrequestid_seq`. */
@JvmInline
value class ReturnId private constructor(val value: Long) {

    // Second wall: makeNew already refuses bad input with a Failure, so this only fails on broken stored data. A bug: stop loudly.
    init {
        require(value > 0) { "Invalid ReturnId $value" }
    }

    companion object {
        /** From the path or a body, as it came: anything but a positive whole number is no return (the use case's 404, or 400 in a body). */
        fun makeNew(raw: String?): Result<ReturnId, Failure> {
            val value = raw?.trim()?.toLongOrNull()
            return if (value != null && value > 0) Result.success(ReturnId(value)) else Result.failure(Failure.Invalid)
        }

        /** For values read back from storage, and the ones the generator makes. Still checked by `init`. */
        fun constructFromPersisted(value: Long): ReturnId = ReturnId(value)
    }

    sealed class Failure(code: String) : ResultFailure(code, FailureKind.NOT_FOUND) {
        data object Invalid : Failure("RETURN_NOT_FOUND")
    }
}

/**
 * A return's public code (`RT-1012`): `RT-` and 1000 + its id (roadmap section 12.2, decision 20), so it follows from the
 * id and needs no table of its own.
 */
@JvmInline
value class ReturnCode private constructor(val value: String) {

    init {
        require(FORMAT.matches(value)) { "Invalid ReturnCode '$value'" }
    }

    companion object {
        private const val OFFSET = 1000L
        private val FORMAT = Regex("^RT-[0-9]{4,}$")

        /** The code of the return [id]. */
        fun forId(id: ReturnId): ReturnCode = ReturnCode("RT-${OFFSET + id.value}")

        /** For values read back from storage. Still checked by `init`. */
        fun constructFromPersisted(value: String): ReturnCode = ReturnCode(value)
    }
}
