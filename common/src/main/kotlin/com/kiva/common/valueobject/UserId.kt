package com.kiva.common.valueobject

import com.kiva.common.railway.FailureKind
import com.kiva.common.railway.Result
import com.kiva.common.railway.ResultFailure

/**
 * Who did something: the id of a user account (contract `User.id`, an integer), or [SYSTEM] when no person was
 * behind it (the dev seeder, scheduled jobs such as expiring unpaid orders).
 *
 * Accounts are numbered from 1, so [SYSTEM] (0) can never be mistaken for a person. Never borrow a real id such as 1
 * for system work: that person would then appear as the author of changes they never made.
 */
@JvmInline
value class UserId private constructor(val value: Long) {

    // Second wall, for every way in: [makeNew] already refuses bad input with a Failure, so this only ever fails
    // when stored data is wrong. That is a bug: stop loudly.
    init {
        require(value >= 0) { "Invalid UserId $value" }
    }

    companion object {
        /** Work done by the shop itself, not by a person. */
        val SYSTEM = UserId(0)

        /** A real account id (for example the `sub` of an access token). 0 is not an account: the system never logs in. */
        fun makeNew(raw: Long): Result<UserId, Failure> =
            if (raw > 0) Result.success(UserId(raw)) else Result.failure(Failure.Invalid)

        /** For values read back from storage, [SYSTEM] included. Still checked by `init`. */
        fun constructFromPersisted(value: Long): UserId = UserId(value)
    }

    sealed class Failure(code: String) : ResultFailure(code, FailureKind.INVALID) {
        data object Invalid : Failure("USER_ID_INVALID")
    }
}
