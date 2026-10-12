package com.kiva.domain.aggregate.order.model.policy

import com.kiva.domain.aggregate.order.model.valueobject.OrderActions
import com.kiva.domain.aggregate.order.model.valueobject.OrderStatus
import java.time.Instant

/**
 * Which buttons an order's card shows. From the facts the order list has (its read model), not from the aggregate:
 * a list never reads the event store.
 *
 * In this phase only «پرداخت» can show, for an unpaid order before its time to pay ends. Cancelling, a change request,
 * approving the photos, returns and reviews are later phases, so their buttons stay off: no button without an
 * endpoint behind it. Joining a reservation is round 2.
 */
object OrderActionsPolicy {

    fun actionsOf(status: OrderStatus, holdUntil: Instant, now: Instant): OrderActions = OrderActions(
        canCancel = false,
        canRequestChange = false,
        canApproveMedia = false,
        canReturn = false,
        canPay = PaymentPolicy.canStartPayment(status, holdUntil, now),
        canAddToReservation = false,
        canReview = false,
    )
}
