package com.kiva.domain.aggregate.order.model.policy

import com.kiva.domain.aggregate.order.model.valueobject.OrderActions
import com.kiva.domain.aggregate.order.model.valueobject.OrderStatus
import java.time.Instant

/**
 * Which buttons an order's card shows. From the facts the order list has (its read model), not from the aggregate:
 * a list never reads the event store.
 *
 * In this phase «پرداخت» shows for an unpaid order before its time to pay ends, and «افزودن محصول» for an order held
 * with «رزرو ۴ روزه» (its next order to the same address joins it). Cancelling, a change request, approving the photos,
 * returns and reviews are later phases, so their buttons stay off: no button without an endpoint behind it.
 */
object OrderActionsPolicy {

    fun actionsOf(status: OrderStatus, holdUntil: Instant, now: Instant): OrderActions = OrderActions(
        canCancel = false,
        canRequestChange = false,
        canApproveMedia = false,
        canReturn = false,
        canPay = PaymentPolicy.canStartPayment(status, holdUntil, now),
        canAddToReservation = status == OrderStatus.RESERVED,
        canReview = false,
    )
}
