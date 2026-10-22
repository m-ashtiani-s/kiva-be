package com.kiva.application.usecase.review

import com.kiva.common.logging.getLogger
import com.kiva.domain.aggregate.review.entity.Review
import com.kiva.domain.aggregate.review.model.valueobject.StarRating
import com.kiva.domain.aggregate.review.repository.ProductRatingRepository
import java.time.Instant

/**
 * The one way a product's rating changes (roadmap section 13.5): a review that came into the rating or went out of it,
 * or one that was in it and went away. Every use case that approves, turns down or deletes a review asks it, inside its
 * own transaction, after it locked the review's row (lock 11): when the review's place in the rating changed, the
 * product's rating row is locked right after and saved. Nothing changed, nothing is touched.
 */
class RatingBook(private val productRatingRepository: ProductRatingRepository) {

    private val logger = getLogger()

    /** How a review counted before a change: [rating] stars, [verified] buyer, [counted] at all. */
    data class Counted(val rating: StarRating, val verified: Boolean, val counted: Boolean) {
        companion object {
            fun of(review: Review): Counted = Counted(review.rating, review.isVerifiedBuyer, review.countsInRating)
        }
    }

    /** [review] changed (approved, turned down) from how it counted [before]. */
    fun changed(before: Counted, review: Review, now: Instant) {
        val after = Counted.of(review)
        if (!before.counted && !after.counted) return
        if (before == after) return
        var tally = productRatingRepository.lock(review.productId)
        if (before.counted) tally = tally.minus(before.rating, before.verified)
        if (after.counted) tally = tally.plus(after.rating, after.verified)
        productRatingRepository.save(review.productId, tally, now)
        logger.info("RatingBook: product ${review.productId.value} now ${tally.count} reviews, ${tally.average()} on average")
    }

    /** [review], which was in the rating as it is, was deleted. */
    fun deleted(review: Review, now: Instant) {
        if (!review.countsInRating) return
        val tally = productRatingRepository.lock(review.productId).minus(review.rating, review.isVerifiedBuyer)
        productRatingRepository.save(review.productId, tally, now)
        logger.info("RatingBook: product ${review.productId.value} now ${tally.count} reviews after a deletion")
    }
}
