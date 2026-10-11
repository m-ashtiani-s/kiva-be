package com.kiva.domain.aggregate.address.model.valueobject

import com.kiva.common.railway.FailureKind
import com.kiva.common.railway.Result
import com.kiva.common.railway.ResultFailure

/**
 * The number of a saved address (`addressId` in the API, an integer by the contract). Handed out from a database
 * sequence before the address is saved, so zero or negative is never an id.
 */
@JvmInline
value class AddressId private constructor(val value: Long) {

    // Second wall, for every way in: [makeNew] already refuses bad input with a Failure, so this only ever fails
    // when stored data is wrong. That is a bug: stop loudly.
    init {
        require(value > 0) { "Invalid AddressId $value" }
    }

    companion object {
        fun makeNew(raw: Long): Result<AddressId, Failure> =
            if (raw > 0) Result.success(AddressId(raw)) else Result.failure(Failure.Invalid)

        /** For values read back from storage. Still checked by `init`. */
        fun constructFromPersisted(value: Long): AddressId = AddressId(value)
    }

    // No field here: the caller names it (`addressId`).
    sealed class Failure(code: String) : ResultFailure(code, FailureKind.INVALID) {
        data object Invalid : Failure("ADDRESS_ID_INVALID")
    }
}
