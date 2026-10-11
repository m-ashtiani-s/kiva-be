package com.kiva.domain.aggregate.order.model.valueobject

import java.time.LocalDate

/**
 * When a shipment should arrive, as dates in Tehran (the contract's `estimatedDelivery.fromDate` and `toDate`).
 * Made by DeliveryWindowPolicy.
 */
data class DeliveryWindow(val fromDate: LocalDate, val toDate: LocalDate) {
    init {
        require(fromDate <= toDate) { "Invalid DeliveryWindow $fromDate..$toDate" }
    }
}
