package com.kiva.application.dto

import com.kiva.domain.aggregate.product.model.valueobject.BadgeCode
import com.kiva.domain.aggregate.product.model.valueobject.BagType
import com.kiva.domain.aggregate.product.model.valueobject.StockInfo
import com.kiva.domain.aggregate.product.model.valueobject.StockStatus

/**
 * A product card of the shop's lists (the contract's ProductSummary, without its Persian texts, which are the HTTP
 * layer's job). Every shop rule is already applied: the exact stock is not here, only what a shopper may see.
 *
 * @property price the default color's, as the card shows it.
 * @property stock the whole product's: sold out only when none of its colors can be bought, a number only when few
 *   are left in all.
 * @property colors the active colors in display order, each with its own stock status.
 * @property defaultColorKey never null in the shop; null only for a product without an active color, which only the
 *   panel reads.
 */
data class ProductCardDto(
    val id: Long,
    val slug: String,
    val sku: String,
    val name: String,
    val category: CategoryRef,
    val bagType: BagType,
    val price: PriceDto,
    val colors: List<ColorOption>,
    val defaultColorKey: String?,
    val stock: StockInfo,
    val badges: List<BadgeCode>,
    val isNew: Boolean,
    val hasVideo: Boolean,
    val soldCount: Int,
    val freeShippingEligible: Boolean,
) {
    data class CategoryRef(val id: Long, val slug: String, val name: String)

    /** One color swatch of the card. */
    data class ColorOption(
        val variantId: Long,
        val key: String,
        val name: String,
        val hex: String,
        val isLight: Boolean,
        val sortOrder: Int,
        val stockStatus: StockStatus,
    )
}
