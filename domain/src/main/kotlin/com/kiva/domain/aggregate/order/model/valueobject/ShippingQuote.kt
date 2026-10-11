package com.kiva.domain.aggregate.order.model.valueobject

import com.kiva.common.valueobject.Money
import com.kiva.domain.aggregate.settings.entity.ShippingMethod

/**
 * One shipping option, priced for a cart or an order (the contract's ShippingOptionQuote, without the Persian
 * description and `selected`, which the HTTP layer adds). Made by ShippingPolicy.
 *
 * @property method the method as the shop has set it up: name, icon, `baseCost` and delivery days come from it.
 * @property cost what the order pays for it: the method's base cost, or 0 when it ships free or nothing can be shipped.
 * @property isFree there is something to ship and it costs nothing. An empty cart costs 0 but does not ship «free».
 * @property freeReason why it ships free; null when it doesn't (or when the method's own price is 0).
 * @property delivery when it would arrive.
 * @property unavailableReason why it can't be chosen; null when it can.
 */
data class ShippingQuote(
    val method: ShippingMethod,
    val cost: Money,
    val isFree: Boolean,
    val freeReason: ShippingFreeReason?,
    val delivery: DeliveryWindow,
    val unavailableReason: ShippingUnavailableReason?,
) {
    /** Can be chosen for this order. */
    val available: Boolean get() = unavailableReason == null

    init {
        require(cost == Money.ZERO || cost == method.baseCost) { "A shipping cost is 0 or the base cost, not $cost (${method.code})" }
        require(!isFree || cost == Money.ZERO) { "Free shipping that costs $cost (${method.code})" }
        require(freeReason == null || isFree) { "A free reason on shipping that is not free (${method.code})" }
    }
}
