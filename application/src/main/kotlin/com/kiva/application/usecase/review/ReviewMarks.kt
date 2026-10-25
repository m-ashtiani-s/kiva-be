package com.kiva.application.usecase.review

import com.kiva.application.repository.OrderQm
import com.kiva.application.repository.ProductQmRepository
import com.kiva.common.valueobject.UserId
import com.kiva.domain.aggregate.order.model.policy.ReviewedProducts
import com.kiva.domain.aggregate.product.model.valueobject.ProductStatus
import com.kiva.domain.aggregate.review.repository.ReviewRepository

/**
 * What «نظر بده» on the customer's orders depends on (roadmap section 13.2, decision 22): the products they have a live
 * review of, and which of their orders' products are published. Two reads for a whole page of orders. Inside the
 * caller's transaction.
 */
class ReviewMarks(
    private val reviewRepository: ReviewRepository,
    private val productQmRepository: ProductQmRepository,
) {

    fun of(userId: UserId, orders: List<OrderQm>): ReviewedProducts {
        val productIds = orders.flatMap { order -> order.lines.map { it.productId } }.distinct()
        if (productIds.isEmpty()) return ReviewedProducts.NONE
        val published = productQmRepository.findBriefs(productIds).filterValues { it.status == ProductStatus.ACTIVE }.keys
        return ReviewedProducts(reviewRepository.liveProductIdsOf(userId), published)
    }
}
