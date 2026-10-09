package com.kiva.domain.aggregate.session.model.valueobject

import com.kiva.common.railway.FailureKind
import com.kiva.common.railway.Result
import com.kiva.common.railway.ResultFailure

/**
 * The id of a login session: the `sid` of every access token of it. Handed out from 1 (a database sequence) before the
 * session is stored, so zero or negative is never an id.
 */
@JvmInline
value class SessionId private constructor(val value: Long) {

    // Second wall, for every way in: [makeNew] already refuses bad input with a Failure, so this only ever fails
    // when stored data is wrong. That is a bug: stop loudly.
    init {
        require(value > 0) { "Invalid SessionId $value" }
    }

    companion object {
        /** From a token's `sid`. */
        fun makeNew(raw: Long): Result<SessionId, Failure> =
            if (raw > 0) Result.success(SessionId(raw)) else Result.failure(Failure.Invalid)

        /** For values from the sequence or read back from storage. Still checked by `init`. */
        fun constructFromPersisted(value: Long): SessionId = SessionId(value)
    }

    sealed class Failure(code: String) : ResultFailure(code, FailureKind.INVALID) {
        data object Invalid : Failure("SESSION_ID_INVALID")
    }
}
