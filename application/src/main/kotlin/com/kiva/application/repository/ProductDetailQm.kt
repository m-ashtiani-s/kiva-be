package com.kiva.application.repository

import com.kiva.application.dto.GalleryItemDto
import com.kiva.common.valueobject.Slug
import com.kiva.domain.aggregate.color.model.valueobject.ColorKey
import com.kiva.domain.aggregate.product.model.valueobject.Highlights
import com.kiva.domain.aggregate.product.model.valueobject.MaterialDescription
import com.kiva.domain.aggregate.product.model.valueobject.PriceTag
import com.kiva.domain.aggregate.product.model.valueobject.ProductDescription
import com.kiva.domain.aggregate.product.model.valueobject.ProductSpecs
import com.kiva.domain.aggregate.product.model.valueobject.ShortDescription
import com.kiva.domain.aggregate.product.model.valueobject.Sku
import com.kiva.domain.aggregate.product.model.valueobject.VariantId
import java.time.Instant

/**
 * One product as its page needs it, read from the read model: its card, and what only the page shows.
 *
 * @property variants the active colors in display order (the same as [card]'s colors), with their codes, price tags
 *   and galleries. The exact stock is here too: only StockPolicy turns it into what a shopper may see.
 * @property publishedOn the last time it went into the shop.
 * @property updatedOn the last change to the product itself.
 */
data class ProductDetailQm(
    val card: ProductCardQm,
    val materialSlug: Slug,
    val materialDescription: MaterialDescription?,
    val shortDescription: ShortDescription?,
    val description: ProductDescription?,
    val highlights: Highlights,
    val specs: ProductSpecs,
    val returnable: Boolean,
    val reservable: Boolean,
    val publishedOn: Instant?,
    val updatedOn: Instant,
    val variants: List<Variant>,
) {
    /** One active color: its own code, what it costs (its own price tag or the product's), stock and gallery. */
    data class Variant(
        val variantId: VariantId,
        val colorKey: ColorKey,
        val sku: Sku,
        val priceTag: PriceTag,
        val isDefault: Boolean,
        val available: Int,
        val media: List<GalleryItemDto>,
    )
}
