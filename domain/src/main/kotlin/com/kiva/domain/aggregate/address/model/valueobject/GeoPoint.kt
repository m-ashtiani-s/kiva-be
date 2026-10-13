package com.kiva.domain.aggregate.address.model.valueobject

import com.kiva.common.railway.FailureKind
import com.kiva.common.railway.Result
import com.kiva.common.railway.ResultFailure

/**
 * A point picked on the map, for a courier [پیشنهادی]: latitude and longitude in degrees. Both or neither; a point is
 * not money, so a Double is right here.
 */
@ConsistentCopyVisibility
data class GeoPoint private constructor(val lat: Double, val lng: Double) {

    // Second wall, for every way in: [makeNew] already refuses bad input with a Failure, so this only ever fails
    // when stored data is wrong. That is a bug: stop loudly.
    init {
        require(lat in -90.0..90.0 && lng in -180.0..180.0) { "Invalid GeoPoint" }
    }

    // A place someone lives: kept out of logs like the rest of the address.
    override fun toString(): String = "GeoPoint(***)"

    companion object {
        /** Optional: neither given means no point (null); only one of them, or one out of range, is LOCATION_INVALID. */
        fun makeNew(lat: Double?, lng: Double?): Result<GeoPoint?, Failure> = when {
            lat == null && lng == null -> Result.success(null)
            lat == null || lng == null -> Result.failure(Failure.Invalid)
            lat.isNaN() || lng.isNaN() || lat !in -90.0..90.0 || lng !in -180.0..180.0 -> Result.failure(Failure.Invalid)
            else -> Result.success(GeoPoint(lat, lng))
        }

        /** For values read back from storage. Still checked by `init`. */
        fun constructFromPersisted(lat: Double, lng: Double): GeoPoint = GeoPoint(lat, lng)
    }

    // Always the `location` field of the contract's AddressInput.
    sealed class Failure(code: String) : ResultFailure(code, FailureKind.INVALID, field = "location") {
        data object Invalid : Failure("LOCATION_INVALID")
    }
}
