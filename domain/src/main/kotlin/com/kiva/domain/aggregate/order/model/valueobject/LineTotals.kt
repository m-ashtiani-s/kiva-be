package com.kiva.domain.aggregate.order.model.valueobject

import com.kiva.common.valueobject.Money

/**
 * The amounts of one line (the contract's `lineTotal`, `lineCompareAtTotal` and `lineDiscount`). Made by OrderPricing.
 *
 * @property total price × quantity.
 * @property compareAtTotal crossed-out price × quantity; null when the line is not on sale.
 * @property discount [compareAtTotal] − [total], or 0.
 */
data class LineTotals(
    val total: Money,
    val compareAtTotal: Money?,
    val discount: Money,
) {
    init {
        require((compareAtTotal ?: total) == total + discount) { "Line amounts don't add up: $total + $discount != $compareAtTotal" }
    }
}
