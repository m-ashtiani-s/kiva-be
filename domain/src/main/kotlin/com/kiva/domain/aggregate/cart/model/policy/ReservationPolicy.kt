package com.kiva.domain.aggregate.cart.model.policy

import com.kiva.domain.aggregate.cart.model.PurchaseOffer
import com.kiva.domain.aggregate.cart.model.valueobject.ReservationUnavailableReason
import com.kiva.domain.aggregate.settings.entity.ShopSettings

/**
 * Whether «رزرو ۴ روزه» can be switched on (roadmap section 13): the same rule for the cart's switch and for an order
 * being placed, so the switch never promises what placing the order then refuses.
 */
object ReservationPolicy {

    /**
     * Why a reservation isn't possible, in the contract's order; null when it is.
     *
     * @param offers the offers of the cart's or the order's lines. Only the lines that can be bought count: a line
     *   that can't is not in the order anyway.
     * @param hasActiveReservation round 2: the customer already has a reservation holding. Unknown for a guest.
     */
    fun unavailableReason(settings: ShopSettings, offers: Collection<PurchaseOffer>, hasActiveReservation: Boolean): ReservationUnavailableReason? =
        when {
            !settings.reservationEnabled -> ReservationUnavailableReason.RESERVATION_DISABLED
            hasActiveReservation -> ReservationUnavailableReason.HAS_ACTIVE_RESERVATION
            offers.any { it.isPurchasable && !it.reservable } -> ReservationUnavailableReason.ITEM_NOT_RESERVABLE
            else -> null
        }
}
