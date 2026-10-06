package com.kiva.application.repository

import com.kiva.common.valueobject.Slug
import com.kiva.domain.aggregate.color.model.valueobject.ColorKey
import com.kiva.domain.aggregate.product.model.valueobject.BagType
import com.kiva.domain.aggregate.product.model.valueobject.MaxPerOrder
import com.kiva.domain.aggregate.product.model.valueobject.PriceTag
import com.kiva.domain.aggregate.product.model.valueobject.ProductId
import com.kiva.domain.aggregate.product.model.valueobject.ProductName
import com.kiva.domain.aggregate.product.model.valueobject.Sku
import com.kiva.domain.aggregate.product.model.valueobject.VariantId
import java.time.Instant

/**
 * One product as the shop's lists need it, read from the read model. The exact stock is here too: only StockPolicy
 * turns it into what a shopper may see.
 *
 * @property priceTag the card's: the default color's (PricePolicy.cardPriceTag).
 * @property colors the active colors, in display order. A product in the shop has at least one, and one is the default.
 */
data class ProductCardQm(
    val productId: ProductId,
    val slug: Slug,
    val sku: Sku,
    val name: ProductName,
    val categorySlug: Slug,
    val bagType: BagType,
    val priceTag: PriceTag,
    val hasVideo: Boolean,
    val soldCount: Int,
    val newUntil: Instant?,
    val maxPerOrder: MaxPerOrder,
    val colors: List<Color>,
) {
    /** One active color. @property available what can still be sold: on hand minus reserved. */
    data class Color(
        val variantId: VariantId,
        val colorKey: ColorKey,
        val isDefault: Boolean,
        val available: Int,
    )
}
