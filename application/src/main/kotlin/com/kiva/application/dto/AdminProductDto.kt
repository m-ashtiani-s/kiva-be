package com.kiva.application.dto

import com.kiva.domain.aggregate.product.model.valueobject.ProductStatus
import java.time.Instant

/**
 * One row of the panel's product list (the contract's AdminProductListItem, without its Persian texts): the exact
 * stock, every status.
 *
 * @property price the card's (the default color's).
 * @property totalStock what the active colors can still sell together.
 * @property variantsCount every color, active or not.
 * @property lowStockVariants active colors with something left but fewer than the threshold.
 * @property image the first photo or video of the default color, or null.
 */
data class AdminProductListItemDto(
    val id: Long,
    val sku: String,
    val name: String,
    val category: ProductCardDto.CategoryRef,
    val status: ProductStatus,
    val price: PriceDto,
    val totalStock: Int,
    val variantsCount: Int,
    val lowStockVariants: Int,
    val soldCount: Int,
    val updatedAt: Instant,
    val image: GalleryItemDto?,
    val rating: RatingSummaryDto,
)

/**
 * One product as the panel's page shows it (the contract's AdminProduct): exactly what the shop's page would show
 * ([detail], built the same way; only active colors, the shop's stock rule), plus what only the panel sees.
 *
 * @property variants every color, active or not, with the exact stock.
 * @property version the product's version, the ETag of its form (`W/"{version}"`).
 */
data class AdminProductDto(
    val detail: ProductDetailDto,
    val status: ProductStatus,
    val variants: List<AdminVariantDto>,
    val newUntil: Instant?,
    val sortPriority: Int,
    val publishAt: Instant?,
    val tags: List<String>,
    val createdAt: Instant,
    val createdBy: ActorNameDto,
    val version: Long,
)

/**
 * One color for the panel (the contract's AdminVariant): what the shop's page shows of a color ([shop]), and the
 * exact stock. Stock alerts come in phase 10; until then nobody waits.
 */
data class AdminVariantDto(
    val shop: ProductDetailDto.Variant,
    val barcode: String?,
    val isActive: Boolean,
    val stockOnHand: Int,
    val stockReserved: Int,
    val soldCount: Int,
    val stockAlertSubscribers: Int,
) {
    val stockAvailable: Int get() = stockOnHand - stockReserved
}
