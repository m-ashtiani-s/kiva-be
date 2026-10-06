package com.kiva.domain.aggregate.product.model.valueobject

import com.kiva.common.railway.FailureKind
import com.kiva.common.railway.Result
import com.kiva.common.railway.ResultFailure

/** The size of a bag in centimeters: length × height × width (the contract's ProductDimensions). */
@ConsistentCopyVisibility
data class Dimensions private constructor(
    val lengthCm: Double,
    val heightCm: Double,
    val widthCm: Double,
) {
    // Second wall, for every way in: [makeNew] already refuses bad input with a Failure, so this only ever fails
    // when stored data is wrong. That is a bug: stop loudly.
    init {
        require(isValid(lengthCm) && isValid(heightCm) && isValid(widthCm)) { "Invalid Dimensions $this" }
    }

    companion object {
        /** No bag is two meters on a side; a bigger number is a typo (cm typed as mm). */
        const val MAX_CM = 200.0

        fun makeNew(lengthCm: Double, heightCm: Double, widthCm: Double): Result<Dimensions, Failure> =
            if (isValid(lengthCm) && isValid(heightCm) && isValid(widthCm)) {
                Result.success(Dimensions(lengthCm, heightCm, widthCm))
            } else {
                Result.failure(Failure.Invalid)
            }

        /** For values read back from storage. Still checked by `init`. */
        fun constructFromPersisted(lengthCm: Double, heightCm: Double, widthCm: Double): Dimensions =
            Dimensions(lengthCm, heightCm, widthCm)

        private fun isValid(cm: Double): Boolean = cm.isFinite() && cm > 0 && cm <= MAX_CM
    }

    sealed class Failure(code: String, meta: Map<String, Any>) :
        ResultFailure(code, FailureKind.INVALID, field = "dimensions", meta = meta) {
        data object Invalid : Failure("DIMENSIONS_INVALID", mapOf("maxCm" to MAX_CM.toInt()))
    }
}
