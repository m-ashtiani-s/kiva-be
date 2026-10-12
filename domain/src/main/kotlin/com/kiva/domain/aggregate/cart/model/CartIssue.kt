package com.kiva.domain.aggregate.cart.model

import com.kiva.domain.aggregate.cart.entity.CartLine
import com.kiva.domain.aggregate.cart.model.valueobject.CartItemId
import com.kiva.domain.aggregate.product.model.valueobject.VariantId

/**
 * Something that changed in a cart since the shopper last saw it (the contract's CartIssue, without the Persian
 * message, which the HTTP layer writes). Each is raised once: the same change of the cart records it as seen.
 *
 * @property itemId the line it is about; for a line that is gone (cut to 0, or merged into another), its old id.
 * @property variantId the color of that line, so the message can name the bag even when the line is gone.
 * @property previousValue and [currentValue]: the price or the quantity before and after, when the code has them.
 */
data class CartIssue(
    val code: CartIssueCode,
    val itemId: CartItemId?,
    val variantId: VariantId?,
    val previousValue: Long?,
    val currentValue: Long?,
) {
    companion object {
        internal fun priceChanged(line: CartLine, previous: Long, current: Long): CartIssue = CartIssue(
            if (current > previous) CartIssueCode.PRICE_INCREASED else CartIssueCode.PRICE_DECREASED,
            line.itemId, line.variantId, previous, current,
        )

        internal fun outOfStock(line: CartLine): CartIssue = CartIssue(CartIssueCode.OUT_OF_STOCK, line.itemId, line.variantId, null, null)

        internal fun variantUnavailable(line: CartLine): CartIssue =
            CartIssue(CartIssueCode.VARIANT_UNAVAILABLE, line.itemId, line.variantId, null, null)

        /** [current] 0 means the line is gone: the product's limit had no room left for it. */
        internal fun quantityReduced(itemId: CartItemId, variantId: VariantId, previous: Int, current: Int): CartIssue =
            CartIssue(CartIssueCode.QUANTITY_REDUCED, itemId, variantId, previous.toLong(), current.toLong())

        internal fun shippingMethodChanged(): CartIssue = CartIssue(CartIssueCode.SHIPPING_METHOD_CHANGED, null, null, null, null)
    }
}

/**
 * Values from the contract's `CartIssue.code`. Its `DISCOUNT_REMOVED` comes with discount codes in round 2 (step 20).
 */
enum class CartIssueCode {
    /** The price went up since the shopper saw it. */
    PRICE_INCREASED,

    /** The price went down since the shopper saw it. */
    PRICE_DECREASED,

    /** Was in stock, none is left now. The line stays, but can't be bought. */
    OUT_OF_STOCK,

    /** The product or this color left the shop. The line stays, but can't be bought. */
    VARIANT_UNAVAILABLE,

    /** Fewer than asked for or than before: what is left, or the product's limit, is lower. 0 means the line went. */
    QUANTITY_REDUCED,

    /** The method the cart had is no longer offered; the cart moved to the shop's default. */
    SHIPPING_METHOD_CHANGED,
}
