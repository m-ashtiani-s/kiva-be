package com.kiva.domain.aggregate.order.model.policy

import com.kiva.common.valueobject.Money
import com.kiva.domain.aggregate.order.entity.Order
import com.kiva.domain.aggregate.order.model.valueobject.OrderActions
import com.kiva.domain.aggregate.order.model.valueobject.OrderStatus
import com.kiva.domain.aggregate.order.model.valueobject.PhotoStatus
import com.kiva.domain.aggregate.product.model.valueobject.ProductId
import java.time.Instant

/**
 * What an order's buttons depend on, from wherever the order is read: its read model for the lists and pages (a list
 * never reads the event store), the aggregate for the tests. Both give the same facts, so the same buttons.
 *
 * Each later phase adds the facts its buttons need (the balance due, returns, reviews).
 */
data class OrderActionFacts(
    val status: OrderStatus,
    val holdUntil: Instant,
    val photoEverSent: Boolean,
    val photoStatus: PhotoStatus,
    val balanceDue: Money,
    val returnableUntil: Instant?,
    val returnSettled: Boolean,
    val now: Instant,
    /** The products of its lines (phase 8: «نظر بده»). */
    val lineProductIds: Set<ProductId> = emptySet(),
    /** What the customer reviewed and which products are published; none known (the panel, a guest): no button. */
    val reviews: ReviewedProducts = ReviewedProducts.NONE,
) {
    companion object {
        /** The facts of [order] at [now], with what the customer reviewed ([reviews]). */
        fun of(order: Order, now: Instant, reviews: ReviewedProducts = ReviewedProducts.NONE): OrderActionFacts = OrderActionFacts(
            order.status, order.holdUntil, order.photoEverSent, order.photoStatus, order.balanceDue, order.returnableUntil, order.returnSettled, now,
            order.lines.map { it.productId }.toSet(), reviews,
        )
    }
}

/**
 * What «نظر بده» needs to know beyond the order (roadmap section 13.2, decision 22): the products its customer has a live
 * review of ([reviewed]), and which products are in the shop now ([published]).
 */
data class ReviewedProducts(val reviewed: Set<ProductId>, val published: Set<ProductId>) {
    /** Whether the line of [productId] shows «نظر داده‌ای». */
    fun isReviewed(productId: ProductId): Boolean = productId in reviewed

    companion object {
        val NONE = ReviewedProducts(emptySet(), emptySet())
    }
}

/**
 * Which buttons an order's card shows.
 *
 * «پرداخت» shows for an unpaid order before its time to pay ends, and for a paid one that owes something after a change
 * (PaymentPolicy.canPayBalance); «افزودن محصول» for an order held with «رزرو ۴ روزه», «لغو سفارش» while the customer may
 * still cancel it (OrderTransitionPolicy.customerMayCancel), «همونه!» while photos sent to them wait for an answer, and
 * «درخواست تغییر» after they saw the photos, approved or not, until it ships, one at a time
 * (OrderTransitionPolicy.customerMayRequestChange), and «مرجوع کردن» while Order.returnRefusal would let a return be asked
 * for: delivered, within its time to return ([OrderActionFacts.returnableUntil], set only when a line is returnable), and
 * no return of it closed or refunded. «نظر بده» shows on a delivered order with a line whose product is published and
 * not reviewed by its customer yet (phase 8, decision 22: by product, since one live review is per product).
 */
object OrderActionsPolicy {

    fun actionsOf(facts: OrderActionFacts): OrderActions = OrderActions(
        canCancel = OrderTransitionPolicy.customerMayCancel(facts.status, facts.photoEverSent),
        canRequestChange = OrderTransitionPolicy.customerMayRequestChange(facts.status, facts.photoStatus),
        canApproveMedia = facts.status == OrderStatus.PHOTO_SENT && facts.photoStatus == PhotoStatus.SENT,
        canReturn = facts.status == OrderStatus.DELIVERED && !facts.returnSettled && facts.returnableUntil?.let { !facts.now.isAfter(it) } == true,
        canPay = PaymentPolicy.canStartPayment(facts.status, facts.holdUntil, facts.now) || PaymentPolicy.canPayBalance(facts.status, facts.balanceDue),
        canAddToReservation = facts.status == OrderStatus.RESERVED,
        canReview = facts.status == OrderStatus.DELIVERED &&
            facts.lineProductIds.any { it in facts.reviews.published && !facts.reviews.isReviewed(it) },
    )
}
