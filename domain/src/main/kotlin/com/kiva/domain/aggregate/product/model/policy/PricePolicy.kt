package com.kiva.domain.aggregate.product.model.policy

import com.kiva.common.valueobject.Money
import com.kiva.domain.aggregate.product.entity.Product
import com.kiva.domain.aggregate.product.entity.Variant
import com.kiva.domain.aggregate.product.model.valueobject.PriceTag

/**
 * How the shop reads prices: what a color costs, what the card shows, and how big the discount is.
 * Pure calculations: no database, no clock, so the same input always gives the same answer.
 */
object PricePolicy {

    /** What one color costs: its own price tag, or the product's when it has none. */
    fun priceTagOf(product: Product, variant: Variant): PriceTag = variant.ownPriceTag ?: product.priceTag

    /** The price on the product card: the default color's; the product's own while it has no active color. */
    fun cardPriceTag(product: Product): PriceTag =
        product.defaultVariant()?.let { priceTagOf(product, it) } ?: product.priceTag

    /**
     * `round((1 - price / compareAtPrice) × 100)` from the contract, 0 when there is no crossed-out price.
     *
     * Worked out in whole numbers (halves rounded up), not with Double: 1,980,000 of 2,400,000 is exactly 17.5% and must
     * show 18, while Double arithmetic lands on 17.4999... or 17.5000...01 depending on the order of operations.
     */
    fun discountPercent(tag: PriceTag): Int {
        val compareAt = tag.compareAtPrice?.toman ?: return 0
        val discount = compareAt - tag.price.toman
        // round(discount / compareAt × 100) = floor((discount × 200 + compareAt) / (2 × compareAt))
        return ((Math.multiplyExact(discount, 200) + compareAt) / Math.multiplyExact(compareAt, 2)).toInt()
    }

    /** How much cheaper than the crossed-out price (`compareAtPrice − price`), zero when not on sale. */
    fun discountAmount(tag: PriceTag): Money = tag.compareAtPrice?.let { it - tag.price } ?: Money.ZERO

    /**
     * On sale means the discount shows as at least 1%: a crossed-out price only a few toman higher rounds to 0% and is
     * not shown as a sale (the prototype does the same). This is what the `onSale` filter and the SALE badge use.
     */
    fun isOnSale(tag: PriceTag): Boolean = discountPercent(tag) > 0

    /** The price alone reaches the free-shipping threshold (contract `freeShippingEligible`; the threshold comes from config). */
    fun qualifiesForFreeShipping(tag: PriceTag, freeShippingThreshold: Money): Boolean = tag.price >= freeShippingThreshold
}
