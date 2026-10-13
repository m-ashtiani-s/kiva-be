package com.kiva.domain.aggregate.order.model.valueobject

/**
 * Where an order is in its life: the contract's 13 OrderStatus values. This phase reaches only the first five; the
 * rest (the photo before shipping, shipping, cancelling, returns) belong to the phases after the purchase.
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

    /** The money came in. */
    val isPaid: Boolean get() = this != PENDING_PAYMENT && this != PAYMENT_FAILED && this != EXPIRED
}
