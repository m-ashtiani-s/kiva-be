package com.kiva.application.usecase.review

import com.kiva.application.repository.OrderQm
import com.kiva.application.repository.OrderQmRepository
import com.kiva.common.valueobject.UserId
import com.kiva.domain.aggregate.order.model.valueobject.OrderCode
import com.kiva.domain.aggregate.order.model.valueobject.OrderStatus
import com.kiva.domain.aggregate.review.ReviewDomainService.PurchaseLine

/**
 * A customer's purchases as a review sees them (roadmap section 13.2, decisions 3 and 23): the lines of their orders,
 * each with whether it was delivered (and when) or its order cancelled since. For linking a review to what was bought and
 * for «منتظر نظر تو» (Rule 15). Reads the order's read model; inside the caller's transaction.
 */
class ReviewPurchases(private val orderQmRepository: OrderQmRepository) {

    /** The lines of [userId]'s own order [code]; null when they have none by that code. */
    fun ofOrder(userId: UserId, code: OrderCode): List<PurchaseLine>? = orderQmRepository.findOfCustomer(userId, code)?.let(::linesOf)

    /** Every line of [userId]'s delivered orders that weren't cancelled since. */
    fun deliveredOf(userId: UserId): List<PurchaseLine> = orderQmRepository.findDeliveredOfCustomer(userId).flatMap(::linesOf).filter { it.delivered }

    private fun linesOf(order: OrderQm): List<PurchaseLine> = order.lines.map { line ->
        PurchaseLine(
            order.orderId, order.code, line.lineId, line.productId, line.item.color.key, line.item.color.name, line.item.color.hex, order.deliveredOn,
            cancelled = order.status == OrderStatus.CANCELLED,
        )
    }
}
