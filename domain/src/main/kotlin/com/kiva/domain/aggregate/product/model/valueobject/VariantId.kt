package com.kiva.domain.aggregate.product.model.valueobject

import com.kiva.common.railway.FailureKind
import com.kiva.common.railway.Result
import com.kiva.common.railway.ResultFailure

/**
 * The numeric id of one color of a product (`variantId` in the API; the cart and stock refer to it).
 * Ids are handed out from 1 before the variant is added, so zero or negative is never an id.
 */
@JvmInline
value class VariantId private constructor(val value: Long) {

    // Second wall, for every way in: [makeNew] already refuses bad input with a Failure, so this only ever fails
    // when stored data is wrong. That is a bug: stop loudly.
    init {
        require(value > 0) { "Invalid VariantId $value" }
    }

    companion object {
        fun makeNew(raw: Long): Result<VariantId, Failure> =
            if (raw > 0) Result.success(VariantId(raw)) else Result.failure(Failure.Invalid)

        /** For values read back from storage. Still checked by `init`. */
        fun constructFromPersisted(value: Long): VariantId = VariantId(value)
    }

    // No field here: the caller names it (`variantId`).
    sealed class Failure(code: String) : ResultFailure(code, FailureKind.INVALID) {
        data object Invalid : Failure("VARIANT_ID_INVALID")
    }
}
