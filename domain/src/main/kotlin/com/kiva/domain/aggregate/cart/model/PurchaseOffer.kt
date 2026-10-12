package com.kiva.domain.aggregate.cart.model

import com.kiva.domain.aggregate.category.model.valueobject.CategoryId
import com.kiva.domain.aggregate.product.model.valueobject.MaxPerOrder
import com.kiva.domain.aggregate.product.model.valueobject.PriceTag
import com.kiva.domain.aggregate.product.model.valueobject.ProductId
import com.kiva.domain.aggregate.product.model.valueobject.VariantId

/**
 * What the shop offers of one color right now: everything the cart and a new order need to know about it. Read from
 * the catalog's read model on every request; the cart never stores a price or a stock number of its own.
 *
 * @property priceTag what one costs now: the color's own price tag, or the product's.
 * @property available what can still be sold: in stock minus reserved for orders. Never negative.
 * @property maxPerOrder the product's limit per order, shared by all its colors.
 * @property inShop the product is published (ACTIVE) and this color is switched on. Whether any is left is
 *   [available]'s business; see [isPurchasable].
 * @property reservable can be held by «رزرو ۴ روزه».
 */
data class PurchaseOffer(
    val variantId: VariantId,
    val productId: ProductId,
    val categoryId: CategoryId,
    val priceTag: PriceTag,
    val available: Int,
    val maxPerOrder: MaxPerOrder,
    val inShop: Boolean,
    val reservable: Boolean,
) {
    init {
        require(available >= 0) { "Available stock can't be negative: $available (variant ${variantId.value})" }
    }

    /** Can be bought right now: in the shop, and some is left (the contract's `CartItem.purchasable`). */
    val isPurchasable: Boolean get() = inShop && available > 0
}
