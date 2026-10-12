package com.kiva.domain.aggregate.cart.model.policy

import com.kiva.domain.aggregate.cart.model.PurchaseOffer
import com.kiva.domain.aggregate.cart.model.valueobject.CartQuantity

/**
 * How much of a color a cart may hold. Two limits meet here:
 * - what is left of the color ([PurchaseOffer.available]), and
 * - the product's limit per order ([PurchaseOffer.maxPerOrder]), which is **shared by all its colors**: with a limit
 *   of 5, 3 caramel and 3 black of the same bag can't be in one cart.
 *
 * Every line in the cart takes its share of the product's limit, whether it can be bought right now or not: so a
 * color coming back in stock never pushes out the color the shopper is buying. A line also never holds more than
 * [CartQuantity.MAX].
 *
 * The exact stock never leaks: the result is never above the product's limit (5 by default), which is no more than
 * the LOW_STOCK badge already shows.
 */
object CartLimitsPolicy {

    /**
     * How many of [offer] its line may hold, when the product's other colors in the cart hold [othersOfProduct]:
     * `min(available, maxPerOrder − othersOfProduct, 20)` (the contract's `CartItem.maxQuantity`). 0 when it can't
     * be bought at all.
     */
    fun maxQuantity(offer: PurchaseOffer, othersOfProduct: Int): Int =
        if (offer.isPurchasable) minOf(offer.available, roomUnderLimit(offer, othersOfProduct)) else 0

    /**
     * What the product's limit leaves for one more line, stock aside: `maxPerOrder − othersOfProduct`, between 0 and
     * [CartQuantity.MAX].
     */
    fun roomUnderLimit(offer: PurchaseOffer, othersOfProduct: Int): Int {
        require(othersOfProduct >= 0) { "Other colors can't hold a negative quantity: $othersOfProduct" }
        return (offer.maxPerOrder.value - othersOfProduct).coerceIn(0, CartQuantity.MAX)
    }
}
