package com.kiva.application.dto

import com.kiva.domain.aggregate.review.model.valueobject.RatingTally
import com.kiva.domain.aggregate.review.model.valueobject.StarRating

/**
 * A product's rating as the shop shows it (the contract's RatingSummary; roadmap section 13.2, decision 16): only its
 * approved reviews, rounded as the contract says; a product without any is all zero, its buyers' share null.
 *
 * @property distribution from five stars down to one.
 */
data class RatingSummaryDto(val average: Double, val count: Int, val distribution: List<Share>, val verifiedBuyerPercent: Int?) {

    data class Share(val stars: Int, val count: Int, val percent: Double)

    companion object {
        fun from(tally: RatingTally) = RatingSummaryDto(
            average = tally.average(),
            count = tally.count,
            distribution = (StarRating.MAX downTo StarRating.MIN).map { Share(it, tally.countOf(it), tally.percentOf(it)) },
            verifiedBuyerPercent = tally.verifiedPercent(),
        )
    }
}
