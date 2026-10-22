package com.kiva.application.repository

import com.kiva.domain.aggregate.review.model.valueobject.RatingTally
import com.kiva.application.dto.GalleryItemDto
import com.kiva.common.valueobject.Slug
import com.kiva.common.valueobject.UserId
import com.kiva.domain.aggregate.color.model.valueobject.ColorKey
import com.kiva.domain.aggregate.product.model.valueobject.PriceTag
import com.kiva.domain.aggregate.product.model.valueobject.ProductId
import com.kiva.domain.aggregate.product.model.valueobject.ProductName
import com.kiva.domain.aggregate.product.model.valueobject.ProductStatus
import com.kiva.domain.aggregate.product.model.valueobject.Sku
import com.kiva.domain.aggregate.product.model.valueobject.StockStatus
import com.kiva.domain.aggregate.product.model.valueobject.VariantId
import java.time.Instant

/**
 * What the panel's product list is narrowed to (`GET /admin/products`), every part already checked. Unlike the shop's
 * ProductFilter, every status is listed unless [status] picks one.
 *
 * @property q the search text through normalizePersian, or null: a product whose search text holds it, whose code or a
 *   color's code starts with it (case aside), or a color of which has exactly this barcode.
 * @property stockStatus OUT_OF_STOCK: nothing left in its active colors; LOW_STOCK: something left, and an active
 *   color under [lowStockThreshold]; IN_STOCK: the rest.
 * @property lowStockThreshold fewer than this many left in a color is "low" (a setting from phase 4 on).
 */
data class AdminProductFilter(
    val q: String? = null,
    val status: ProductStatus? = null,
    val categories: Set<Slug> = emptySet(),
    val stockStatus: StockStatus? = null,
    val lowStockThreshold: Int,
)

/**
 * One row of the panel's product list, read with the exact stock.
 *
 * @property priceTag the card's (the default color's).
 * @property availableTotal what its active colors can still sell together.
 * @property variantsCount every color, active or not.
 * @property lowStockVariants active colors with something left, but fewer than the filter's threshold.
 * @property defaultMedia the first photo or video of the default color, or null.
 */
data class AdminProductQm(
    val productId: ProductId,
    val sku: Sku,
    val name: ProductName,
    val categorySlug: Slug,
    val status: ProductStatus,
    val priceTag: PriceTag,
    val availableTotal: Int,
    val variantsCount: Int,
    val lowStockVariants: Int,
    val soldCount: Int,
    val updatedOn: Instant,
    val defaultMedia: GalleryItemDto?,
    /** Its approved reviews counted (phase 8). */
    val rating: RatingTally = RatingTally.NONE,
)

/**
 * One product as the panel's page needs it, whatever its status: what the shop's page shows ([detail], whose card and
 * variants hold only the active colors) and what only the panel sees.
 *
 * @property version the sequence of its last event: the ETag of the panel's form.
 * @property variants every color, active or not, in display order (position, then id), with the exact stock.
 */
data class AdminProductDetailQm(
    val detail: ProductDetailQm,
    val status: ProductStatus,
    val version: Long,
    val createdOn: Instant,
    val createdBy: UserId,
    val publishAt: Instant?,
    val sortPriority: Int,
    val tags: List<String>,
    val variants: List<Variant>,
) {
    data class Variant(
        val variantId: VariantId,
        val colorKey: ColorKey,
        val sku: Sku,
        val barcode: String?,
        val priceTag: PriceTag,
        val isDefault: Boolean,
        val isActive: Boolean,
        val onHand: Int,
        val reserved: Int,
        val soldCount: Int,
        val media: List<GalleryItemDto>,
    ) {
        /** What can still be sold: on hand minus reserved. */
        val available: Int get() = onHand - reserved
    }
}
