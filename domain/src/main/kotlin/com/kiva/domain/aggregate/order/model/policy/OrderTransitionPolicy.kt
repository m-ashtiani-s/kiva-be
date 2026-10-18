package com.kiva.domain.aggregate.order.model.policy

import com.kiva.domain.aggregate.order.model.valueobject.OrderStatus
import com.kiva.domain.aggregate.order.model.valueobject.PhotoStatus

/**
 * One place for «from this status, where to» (roadmap section 7.3): what `POST /admin/orders/{code}/status` takes
 * (ChangeOrderStatusUc), the panel's `allowedTransitions`, who may cancel, and the tests all ask here.
 *
 * | status now | `allowedTransitions` |
 * |---|---|
 * | RESERVED, PROCESSING, PHOTO_SENT, CHANGE_REQUESTED | CANCELLED |
 * | SHIPPED | DELIVERED |
 * | any other | none |
 *
 * PHOTO_SENT (the photos' send), SHIPPED (the shipment), leaving CHANGE_REQUESTED other than by cancelling (phase 3) and
 * the return statuses (phase 7) never come through `…/status`: each has its own endpoint.
 */
object OrderTransitionPolicy {

    /** What staff may still cancel: anything paid that hasn't left with the courier. */
    val STAFF_CANCELLABLE: Set<OrderStatus> = setOf(OrderStatus.RESERVED, OrderStatus.PROCESSING, OrderStatus.PHOTO_SENT, OrderStatus.CHANGE_REQUESTED)

    /** Where `POST …/status` may take an order in [status]. */
    fun allowedFrom(status: OrderStatus): Set<OrderStatus> = when (status) {
        in STAFF_CANCELLABLE -> setOf(OrderStatus.CANCELLED)
        OrderStatus.SHIPPED -> setOf(OrderStatus.DELIVERED)
        else -> emptySet()
    }

    /**
     * The customer cancels only while the order's status hasn't moved (roadmap section 1, row 5): held or being prepared,
     * and no photo ever sent to them, even if a change request brought it back to PROCESSING since. From the first
     * photo on, the operator's work has started.
     */
    fun customerMayCancel(status: OrderStatus, photoEverSent: Boolean): Boolean =
        (status == OrderStatus.RESERVED || status == OrderStatus.PROCESSING) && !photoEverSent

    /**
     * The customer asks for a change only after seeing photos of their bag and before it ships (roadmap section 8.2,
     * row 1): PHOTO_SENT with the photos SENT or APPROVED, so after approving them too. An open request makes the order
     * CHANGE_REQUESTED, so there is never a second one at once.
     */
    fun customerMayRequestChange(status: OrderStatus, photoStatus: PhotoStatus): Boolean =
        status == OrderStatus.PHOTO_SENT && (photoStatus == PhotoStatus.SENT || photoStatus == PhotoStatus.APPROVED)
}
