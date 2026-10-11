package com.kiva.domain.aggregate.order.model.valueobject

/**
 * Why an order ships free (the contract's `freeReason` and `shippingFreeReason`). The contract's `PROMO` is not here:
 * nothing makes shipping free by promotion yet.
 */
enum class ShippingFreeReason {
    /** The subtotal reached the shop's threshold, with a method that has free shipping (POST). */
    THRESHOLD,

    /** Round 2: the order joins a held reservation and ships with it, at no extra cost. */
    RESERVATION_CONSOLIDATION,
}
