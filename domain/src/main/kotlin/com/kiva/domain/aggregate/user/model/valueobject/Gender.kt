package com.kiva.domain.aggregate.user.model.valueobject

import com.kiva.common.railway.FailureKind
import com.kiva.common.railway.Result
import com.kiva.common.railway.ResultFailure

/** What the customer chose in their profile. Values from the API contract. */
enum class Gender {
    FEMALE,
    MALE,

    /** Not chosen. Every account starts here. */
    UNSPECIFIED,
    ;

    companion object {
        /** The value as the contract sends it (`FEMALE`), exactly; anything else is GENDER_INVALID. */
        fun makeNew(raw: String): Result<Gender, Failure> =
            entries.firstOrNull { it.name == raw }?.let { Result.success(it) } ?: Result.failure(Failure.Invalid)
    }

    // Always the `gender` field of the contract's UpdateProfileRequest.
    sealed class Failure(code: String) : ResultFailure(code, FailureKind.INVALID, field = "gender") {
        data object Invalid : Failure("GENDER_INVALID")
    }
}
