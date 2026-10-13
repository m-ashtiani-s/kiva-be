package com.kiva.domain.aggregate.cart.model

import com.kiva.domain.aggregate.order.model.valueobject.ShippingConsolidation

/**
 * What the customer's «رزرو ۴ روزه» means for their cart right now (round 2), as the use case found it: whether they hold
 * one ([hasActiveReservation]: the switch can't be turned on), and whether an order from this cart joins it
 * ([consolidation]: to the group's address, only with the group's method, shipping free). A guest has [NONE]: who they
 * are is unknown until they log in, and placing an order checks again.
 */
data class ReservationStanding(val hasActiveReservation: Boolean, val consolidation: ShippingConsolidation?) {
    init {
        require(consolidation == null || hasActiveReservation) { "Only a held reservation can be joined" }
    }

    companion object {
        val NONE = ReservationStanding(hasActiveReservation = false, consolidation = null)
    }
}
