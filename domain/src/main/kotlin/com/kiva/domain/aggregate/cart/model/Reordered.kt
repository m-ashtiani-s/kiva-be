package com.kiva.domain.aggregate.cart.model

import com.kiva.domain.aggregate.product.model.valueobject.VariantId

/**
 * What «خرید دوباره» put in the cart: how many bags were added in all, and the colors that couldn't be, each with why.
 * A color added only in part (cut to what fits) counts as added, with the cart's QUANTITY_REDUCED.
 */
data class Reordered(val addedQuantity: Int, val skipped: List<Skipped>) {

    data class Skipped(val variantId: VariantId, val reason: Reason)

    /** The contract's reasons (`WishlistAddToCartResponse.skipped[].reason`). */
    enum class Reason {
        /** None left. */
        OUT_OF_STOCK,

        /** Out of the shop, or gone: a product taken out, a color switched off. */
        UNAVAILABLE,

        /** The cart already holds all of it the shop allows: the product's limit, or what is left. */
        ALREADY_AT_MAX,
    }
}
