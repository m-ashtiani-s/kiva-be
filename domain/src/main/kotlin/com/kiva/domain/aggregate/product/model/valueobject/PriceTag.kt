package com.kiva.domain.aggregate.product.model.valueobject

import com.kiva.common.railway.FailureKind
import com.kiva.common.railway.Result
import com.kiva.common.railway.ResultFailure
import com.kiva.common.valueobject.Money

/**
 * What a shopper pays, and when on sale the crossed-out price before the discount (contract `price` and
 * `compareAtPrice`, in toman).
 *
 * The crossed-out price must be above the price; otherwise the "discount" would be zero or a markup.
 * A product has one; a color may have its own instead of following the product's.
 */
@ConsistentCopyVisibility
data class PriceTag private constructor(
    val price: Money,
    val compareAtPrice: Money?,
) {
    // Second wall, for every way in: [makeNew] already refuses bad input with a Failure, so this only ever fails
    // when stored data is wrong. That is a bug: stop loudly.
    init {
        require(compareAtPrice == null || compareAtPrice > price) { "Invalid PriceTag: $compareAtPrice is not above $price" }
    }

    companion object {
        fun makeNew(price: Money, compareAtPrice: Money?): Result<PriceTag, Failure> =
            if (compareAtPrice != null && compareAtPrice <= price) {
                Result.failure(Failure.CompareAtNotAbovePrice)
            } else {
                Result.success(PriceTag(price, compareAtPrice))
            }

        /** For values read back from storage. Still checked by `init`. */
        fun constructFromPersisted(price: Money, compareAtPrice: Money?): PriceTag = PriceTag(price, compareAtPrice)
    }

    // The problem is the crossed-out price, so the form marks that field.
    sealed class Failure(code: String) : ResultFailure(code, FailureKind.INVALID, field = "compareAtPrice") {
        data object CompareAtNotAbovePrice : Failure("COMPARE_AT_PRICE_NOT_ABOVE_PRICE")
    }
}
