package com.kiva.domain.aggregate.review.repository

import com.kiva.domain.aggregate.product.model.valueobject.ProductId
import com.kiva.domain.aggregate.review.model.valueobject.RatingTally
import java.time.Instant

/**
 * The products' ratings (`productrating_tbl`; roadmap section 13.2, decision 15), apart from the product's read model.
 * Changed only by RatingBook, under the row's lock.
 */
interface ProductRatingRepository {

    /** Locks the product's rating, making it empty first when it has none yet; and reads it. */
    fun lock(productId: ProductId): RatingTally

    /** The product's rating for reading only; RatingTally.NONE when it has none. */
    fun find(productId: ProductId): RatingTally

    /** The ratings of [productIds] that have one, in one read (a page of cards). */
    fun findAll(productIds: Collection<ProductId>): Map<ProductId, RatingTally>

    fun save(productId: ProductId, tally: RatingTally, now: Instant)
}
