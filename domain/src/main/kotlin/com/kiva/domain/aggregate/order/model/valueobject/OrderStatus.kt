package com.kiva.domain.aggregate.order.model.valueobject

/**
 * Where an order is in its life: the contract's 13 OrderStatus values. The purchase reached the first five, the work
 * on orders (phase 2) the photo before shipping, shipping, delivery and cancelling; returns are phase 7.
 */
enum class OrderStatus {
    /** Placed, its stock held until `holdUntil`, waiting for the money. */
    PENDING_PAYMENT,

    /** A payment attempt failed or was cancelled; the order can still be paid until `holdUntil`. */
    PAYMENT_FAILED,

    /** Not paid in time: its stock went back to the shop. Final. */
    EXPIRED,

    /** Paid, and held with «رزرو ۴ روزه» so later orders can join it (round 2). */
    RESERVED,

    /** Paid; the shop is getting it ready. */
    PROCESSING,

    PHOTO_SENT,
    CHANGE_REQUESTED,
    SHIPPED,
    DELIVERED,
    CANCELLED,
    RETURN_REQUESTED,
    RETURNED,
    REFUNDED,
    ;

    /** Placed and not paid yet: it may still be paid ([PENDING_PAYMENT] or [PAYMENT_FAILED]). */
    val isAwaitingPayment: Boolean get() = this == PENDING_PAYMENT || this == PAYMENT_FAILED

    /** The money came in once, even if the order was cancelled afterwards (the panel's `ordersCount` counts those too). */
    val isPaid: Boolean get() = this != PENDING_PAYMENT && this != PAYMENT_FAILED && this != EXPIRED

    /**
     * A purchase that stands: paid and not cancelled. A cancelled order doesn't take «خرید اول» away from a customer, nor
     * show among a reservation's orders.
     */
    val countsAsPurchase: Boolean get() = isPaid && this != CANCELLED
}
