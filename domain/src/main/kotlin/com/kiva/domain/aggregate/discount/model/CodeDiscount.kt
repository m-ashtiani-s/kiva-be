package com.kiva.domain.aggregate.discount.model

import com.kiva.common.valueobject.Money
import com.kiva.common.valueobject.Quantity
import com.kiva.common.valueobject.UserId
import com.kiva.domain.aggregate.cart.model.PurchaseOffer
import com.kiva.domain.aggregate.discount.entity.DiscountCode
import com.kiva.domain.aggregate.order.model.PricedLine

/**
 * A code that applies, and what it takes off: OrderPricing's `codeDiscount` (the cart, the checkout and placing an order
 * alike) and the cart's `AppliedDiscount`. Never more than what the code covers, so never more than the subtotal.
 */
data class CodeDiscount(val code: DiscountCode, val amount: Money)

/** One line as a code looks at it: its color's offer now (price, product, category) and how many. */
data class DiscountLine(val offer: PurchaseOffer, val quantity: Quantity) {
    /** As OrderPricing prices it, so the code's base is the very amount the line costs. */
    val priced: PricedLine get() = PricedLine(offer.priceTag, quantity, offer.isPurchasable)
}

/**
 * Who the code is for. A guest can't be counted (DiscountCode.needsCustomer).
 *
 * @property hasPaidOrder the customer has an order the money came in for: what a first-order code refuses.
 */
sealed interface DiscountShopper {
    data object Guest : DiscountShopper

    data class Customer(val userId: UserId, val hasPaidOrder: Boolean) : DiscountShopper
}

/**
 * How many times a code is used now: held and confirmed uses, in all ([ofCode]) and by the customer ([ofCustomer], 0 for
 * a guest). Read under the code's lock when an order is placed, so two orders never take its last use together.
 */
data class DiscountUsage(val ofCode: Int, val ofCustomer: Int) {
    init {
        require(ofCode >= 0 && ofCustomer in 0..ofCode) { "Invalid usage: $ofCustomer of $ofCode" }
    }
}
