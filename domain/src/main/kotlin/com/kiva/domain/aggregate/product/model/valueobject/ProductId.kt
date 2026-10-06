package com.kiva.domain.aggregate.product.model.valueobject

import com.kiva.common.railway.FailureKind
import com.kiva.common.railway.Result
import com.kiva.common.railway.ResultFailure

/**
 * The numeric id of a product (`/products/12`, `productId` in the API). Ids are handed out from 1 before the
 * product is created (a database sequence, step 3), so zero or negative is never an id.
 */
@JvmInline
value class ProductId private constructor(val value: Long) {

    // Second wall, for every way in: [makeNew] already refuses bad input with a Failure, so this only ever fails
    // when stored data is wrong. That is a bug: stop loudly.
    init {
        require(value > 0) { "Invalid ProductId $value" }
    }

    companion object {
        fun makeNew(raw: Long): Result<ProductId, Failure> =
            if (raw > 0) Result.success(ProductId(raw)) else Result.failure(Failure.Invalid)

        /** For values read back from storage. Still checked by `init`. */
        fun constructFromPersisted(value: Long): ProductId = ProductId(value)
    }

    // No field here: the caller names it (`productId`, `ids`).
    sealed class Failure(code: String) : ResultFailure(code, FailureKind.INVALID) {
        data object Invalid : Failure("PRODUCT_ID_INVALID")
    }
}
