package com.kiva.application.usecase.review

import com.kiva.application.repository.ProductBriefQm
import com.kiva.application.repository.ProductQmRepository
import com.kiva.common.valueobject.UserId
import com.kiva.domain.aggregate.product.model.valueobject.ProductStatus
import com.kiva.domain.aggregate.review.ReviewDomainService.PurchaseLine
import com.kiva.domain.aggregate.review.repository.ReviewRepository

/**
 * «منتظر نظر تو» (roadmap section 13.2, decision 23), for its own page and the account's header alike (Rule 15): the
 * lines of the customer's delivered orders whose product is published and that they have no live review of, one per
 * product (the latest delivery), the latest delivered first, at most [LIMIT]. Inside the caller's transaction.
 */
class ReviewsAwaiting(
    private val reviewPurchases: ReviewPurchases,
    private val reviewRepository: ReviewRepository,
    private val productQmRepository: ProductQmRepository,
) {

    fun of(userId: UserId): List<Pair<PurchaseLine, ProductBriefQm>> {
        val reviewed = reviewRepository.liveProductIdsOf(userId)
        val latest = reviewPurchases.deliveredOf(userId)
            .filter { it.productId !in reviewed }
            .groupBy { it.productId }
            .map { (_, lines) -> lines.maxBy { checkNotNull(it.deliveredOn) } }
            .sortedByDescending { it.deliveredOn }
        val products = productQmRepository.findBriefs(latest.map { it.productId })
        return latest
            .mapNotNull { line -> products[line.productId]?.takeIf { it.status == ProductStatus.ACTIVE }?.let { line to it } }
            .take(LIMIT)
    }

    companion object {
        const val LIMIT = 50
    }
}
