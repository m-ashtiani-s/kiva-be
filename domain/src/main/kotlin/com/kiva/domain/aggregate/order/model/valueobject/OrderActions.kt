package com.kiva.domain.aggregate.order.model.valueobject

/**
 * Which buttons an order's card shows (the contract's `OrderSummary.actions`), worked out by OrderActionsPolicy.
 *
 * @property canPay «پرداخت» → `POST /me/orders/{code}/payments`.
 * @property canAddToReservation round 2: the order holds a reservation others can join.
 */
data class OrderActions(
    val canCancel: Boolean,
    val canRequestChange: Boolean,
    val canApproveMedia: Boolean,
    val canReturn: Boolean,
    val canPay: Boolean,
    val canAddToReservation: Boolean,
    val canReview: Boolean,
)
