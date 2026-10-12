package com.kiva.domain.aggregate.cart.model.valueobject

/**
 * Why the «رزرو ۴ روزه» switch can't be turned on for a cart (the contract's `reservation.unavailableReason` and
 * `meta.reason` of RESERVATION_UNAVAILABLE). Checked in this order.
 */
enum class ReservationUnavailableReason {
    /** The shop has switched reservations off (until step 19 always). */
    RESERVATION_DISABLED,

    /** Round 2: the customer already has a reservation holding; an order to the same address joins it instead. */
    HAS_ACTIVE_RESERVATION,

    /** At least one bag the cart can buy can't be reserved. */
    ITEM_NOT_RESERVABLE,
}
