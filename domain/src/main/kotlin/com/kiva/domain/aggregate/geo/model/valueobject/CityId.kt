package com.kiva.domain.aggregate.geo.model.valueobject

import com.kiva.common.railway.FailureKind
import com.kiva.common.railway.Result
import com.kiva.common.railway.ResultFailure

/**
 * The id of a city (`cityId` in the API; the city of Tehran is 394). Unique across the whole country, not only inside
 * its province. The ids come from the migration that fills the city list and never change, because saved addresses
 * point at them.
 */
@JvmInline
value class CityId private constructor(val value: Int) {

    // Second wall, for every way in: [makeNew] already refuses bad input with a Failure, so this only ever fails
    // when stored data is wrong. That is a bug: stop loudly.
    init {
        require(value > 0) { "Invalid CityId $value" }
    }

    companion object {
        fun makeNew(raw: Int): Result<CityId, Failure> =
            if (raw > 0) Result.success(CityId(raw)) else Result.failure(Failure.Invalid)

        /** For values read back from storage. Still checked by `init`. */
        fun constructFromPersisted(value: Int): CityId = CityId(value)
    }

    // No field here: the caller names it (`cityId`, `newAddress.cityId`).
    sealed class Failure(code: String) : ResultFailure(code, FailureKind.INVALID) {
        data object Invalid : Failure("CITY_ID_INVALID")
    }
}
