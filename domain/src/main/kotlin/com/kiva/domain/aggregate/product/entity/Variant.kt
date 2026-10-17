package com.kiva.domain.aggregate.product.entity

import com.kiva.domain.aggregate.color.model.valueobject.ColorKey
import com.kiva.domain.aggregate.media.model.valueobject.MediaType
import com.kiva.domain.aggregate.product.model.valueobject.Barcode
import com.kiva.domain.aggregate.product.model.valueobject.PriceTag
import com.kiva.domain.aggregate.product.model.valueobject.ProductMedia
import com.kiva.domain.aggregate.product.model.valueobject.Sku
import com.kiva.domain.aggregate.product.model.valueobject.VariantId

/**
 * One color of a product: its own code, gallery and, when it differs, its own price tag. Stock is not here:
 * it lives in the Inventory aggregate, one per color.
 *
 * It lives inside [Product] and only Product creates or changes it, through Product's events. Outside the domain
 * module it can only be read.
 *
 * Its identity is [id]; two variants with the same id are the same color of the product.
 *
 * @property ownPriceTag null when the color follows the product's price tag.
 * @property isDefault the color a product card shows first. Among the active colors exactly one is the default.
 * @property sortOrder position among the product's colors, smallest first.
 * @property media gallery in order; the first one is the card photo.
 * @property barcode the barcode on this color's label, or null.
 */
@ConsistentCopyVisibility
data class Variant internal constructor(
    val id: VariantId,
    val colorKey: ColorKey,
    val sku: Sku,
    val ownPriceTag: PriceTag?,
    val isDefault: Boolean,
    val isActive: Boolean,
    val sortOrder: Int,
    val media: List<ProductMedia>,
    val barcode: Barcode? = null,
) {
    /** A color with a video gets the «ویدیو دارد» badge and passes the `hasVideo` filter. */
    val hasVideo: Boolean get() = media.any { it.type == MediaType.VIDEO }

    override fun equals(other: Any?): Boolean = other is Variant && other.id == id

    override fun hashCode(): Int = id.hashCode()

    override fun toString(): String = "Variant(id=${id.value}, color=${colorKey.value})"
}
