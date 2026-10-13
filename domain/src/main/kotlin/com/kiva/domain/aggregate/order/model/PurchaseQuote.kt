package com.kiva.domain.aggregate.order.model

import com.kiva.domain.aggregate.order.model.valueobject.FreeShippingProgress
import com.kiva.domain.aggregate.order.model.valueobject.LineTotals
import com.kiva.domain.aggregate.order.model.valueobject.OrderTotals
import com.kiva.domain.aggregate.order.model.valueobject.ShippingQuote

/**
 * Everything a cart, the checkout page or a new order needs to know about its money and its shipping, worked out once
 * by OrderPricing.quote.
 *
 * @property lines the amounts of each input line, in the same order.
 * @property shippingOptions every option on offer, available or not, in the shop's order.
 * @property shipping the option [totals] use (the contract's `selected`); null only when the shop offers no method.
 * @property freeShipping the free-shipping bar; null when the shop has no free shipping on offer.
 */
data class PurchaseQuote(
    val lines: List<LineTotals>,
    val totals: OrderTotals,
    val shippingOptions: List<ShippingQuote>,
    val shipping: ShippingQuote?,
    val freeShipping: FreeShippingProgress?,
)
