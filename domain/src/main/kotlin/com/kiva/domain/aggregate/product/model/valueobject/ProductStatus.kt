package com.kiva.domain.aggregate.product.model.valueobject

import com.kiva.common.railway.FailureKind
import com.kiva.common.railway.Result
import com.kiva.common.railway.ResultFailure

/**
 * Where a product is in its life. Values from the API contract.
 * Which moves between them are allowed is a rule of the product itself, not of this list.
 */
enum class ProductStatus {
    /** Being prepared; shoppers can't see it. Every product starts here. */
    DRAFT,

    /** Published: in the shop, can be bought. */
    ACTIVE,

    /** Taken out of the shop for good. Old orders still point to it, so it is never deleted. */
    ARCHIVED,
    ;

    companion object {
        /** The status as the contract sends it (`ACTIVE`), exactly; anything else is PRODUCT_STATUS_INVALID. */
        fun makeNew(raw: String): Result<ProductStatus, Failure> =
            entries.firstOrNull { it.name == raw }?.let { Result.success(it) } ?: Result.failure(Failure.Invalid)
    }

    // Always the `status` field of the contract's ProductUpsertRequest.
    sealed class Failure(code: String) : ResultFailure(code, FailureKind.INVALID, field = "status") {
        data object Invalid : Failure("PRODUCT_STATUS_INVALID")
    }
}
