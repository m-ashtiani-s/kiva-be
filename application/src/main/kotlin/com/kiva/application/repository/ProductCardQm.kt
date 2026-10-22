package com.kiva.application.repository

import com.kiva.domain.aggregate.review.model.valueobject.RatingTally
import com.kiva.application.dto.UploadedMediaDto
import com.kiva.common.valueobject.Money
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
    /** Its approved reviews counted (productrating_tbl, phase 8); none: RatingTally.NONE. */
    val rating: RatingTally = RatingTally.NONE,
) {
    /**
     * One active color.
     *
     * @property available what can still be sold: on hand minus reserved.
     * @property price what this color sells for now (its own price, or the product's): a wishlist's price drop.
     * @property photos its gallery's first two photos (videos left out), in gallery order: the card's picture and the
     *   one shown on hover.
     */
    data class Color(
        val variantId: VariantId,
        val colorKey: ColorKey,
        val isDefault: Boolean,
        val available: Int,
        val price: Money,
        val photos: List<UploadedMediaDto> = emptyList(),
    )
}
