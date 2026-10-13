package com.kiva.domain.aggregate.geo.model.valueobject

import com.kiva.common.railway.FailureKind
import com.kiva.common.railway.Result
import com.kiva.common.railway.ResultFailure

/**
 * The id of one of Iran's provinces (`provinceId` in the API; Tehran is 8). The ids come from the migration that
 * fills the province list and never change, because saved addresses point at them.
 */
@JvmInline
value class ProvinceId private constructor(val value: Int) {

    // Second wall, for every way in: [makeNew] already refuses bad input with a Failure, so this only ever fails
    // when stored data is wrong. That is a bug: stop loudly.
    init {
        require(value > 0) { "Invalid ProvinceId $value" }
    }

    companion object {
        fun makeNew(raw: Int): Result<ProvinceId, Failure> =
            if (raw > 0) Result.success(ProvinceId(raw)) else Result.failure(Failure.Invalid)

        /** For values read back from storage. Still checked by `init`. */
        fun constructFromPersisted(value: Int): ProvinceId = ProvinceId(value)
    }

    // No field here: the caller names it (`provinceId`, `newAddress.provinceId`).
    sealed class Failure(code: String) : ResultFailure(code, FailureKind.INVALID) {
        data object Invalid : Failure("PROVINCE_ID_INVALID")
    }
}
