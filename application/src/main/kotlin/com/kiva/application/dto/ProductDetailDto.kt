package com.kiva.application.dto

import com.kiva.domain.aggregate.material.model.valueobject.MaterialFamily
import com.kiva.domain.aggregate.product.model.valueobject.ProductMedia
import com.kiva.domain.aggregate.product.model.valueobject.ProductSpecs
import com.kiva.domain.aggregate.product.model.valueobject.StockInfo
import java.time.Instant

/**
 * A product's page (the contract's ProductDetail, without its Persian texts, which are the HTTP layer's job): its card,
 * the color shown first, and what only the page shows. As on the card, no exact stock: only what a shopper may see.
 *
 * @property selectedColorKey the color asked for (`?color=`) when the product comes in it, otherwise the default (null
 *   only for a product without an active color, which only the panel reads).
 * @property variants the colors in the shop, in display order, each with its own price tag, stock and gallery.
 */
data class ProductDetailDto(
    val card: ProductCardDto,
    val selectedColorKey: String?,
    val shortDescription: String?,
    val description: String?,
    val highlights: List<String>,
    val material: MaterialRef,
    val materialDescription: String?,
    val specs: ProductSpecs,
    val variants: List<Variant>,
    val returnable: Boolean,
    val reservable: Boolean,
    val maxPerOrder: Int,
    val publishedAt: Instant?,
    val updatedAt: Instant,
) {
    data class MaterialRef(val id: Long, val slug: String, val name: String, val family: MaterialFamily)

    data class Swatch(val key: String, val name: String, val hex: String, val isLight: Boolean, val sortOrder: Int)

    /** @property media the gallery in order; the first is the main photo. */
    data class Variant(
        val id: Long,
        val sku: String,
        val color: Swatch,
        val isDefault: Boolean,
        val price: PriceDto,
        val stock: StockInfo,
        val media: List<ProductMedia>,
        val hasVideo: Boolean,
    )
}
