package com.kiva.domain.aggregate.order.model.policy

import com.kiva.common.valueobject.Money
import com.kiva.domain.aggregate.order.entity.Order
import com.kiva.domain.aggregate.order.model.valueobject.OrderActions
import com.kiva.domain.aggregate.order.model.valueobject.OrderStatus
import com.kiva.domain.aggregate.order.model.valueobject.PhotoStatus
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
    val now: Instant,
) {
    companion object {
        /** The facts of [order] at [now]. */
        fun of(order: Order, now: Instant): OrderActionFacts =
            OrderActionFacts(order.status, order.holdUntil, order.photoEverSent, order.photoStatus, order.balanceDue, now)
    }
}

/**
 * Which buttons an order's card shows.
 *
 * «پرداخت» shows for an unpaid order before its time to pay ends, and for a paid one that owes something after a change
 * (PaymentPolicy.canPayBalance); «افزودن محصول» for an order held with «رزرو ۴ روزه», «لغو سفارش» while the customer may
 * still cancel it (OrderTransitionPolicy.customerMayCancel), «همونه!» while photos sent to them wait for an answer, and
 * «درخواست تغییر» after they saw the photos, approved or not, until it ships, one at a time
 * (OrderTransitionPolicy.customerMayRequestChange). Returns (phase 7) and reviews (phase 8) are later, so their buttons
 * stay off: no button without an endpoint behind it.
 */
object OrderActionsPolicy {

    fun actionsOf(facts: OrderActionFacts): OrderActions = OrderActions(
        canCancel = OrderTransitionPolicy.customerMayCancel(facts.status, facts.photoEverSent),
        canRequestChange = OrderTransitionPolicy.customerMayRequestChange(facts.status, facts.photoStatus),
        canApproveMedia = facts.status == OrderStatus.PHOTO_SENT && facts.photoStatus == PhotoStatus.SENT,
        canReturn = false,
        canPay = PaymentPolicy.canStartPayment(facts.status, facts.holdUntil, facts.now) || PaymentPolicy.canPayBalance(facts.status, facts.balanceDue),
        canAddToReservation = facts.status == OrderStatus.RESERVED,
        canReview = false,
    )
}
