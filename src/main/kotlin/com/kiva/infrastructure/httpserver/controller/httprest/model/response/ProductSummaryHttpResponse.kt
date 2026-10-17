package com.kiva.infrastructure.httpserver.controller.httprest.model.response

import com.kiva.application.dto.PriceDto
import com.kiva.application.dto.ProductCardDto
import com.kiva.domain.aggregate.product.model.valueobject.StockInfo
import kotlinx.serialization.Serializable

/**
 * Contract schema `ProductSummary`: a product card.
 *
 * [image] is always there and `null` until photos are uploaded (the client then draws the bag's illustration).
 * [defaultColorKey] and [displayColorKey] are never null in the shop: only a panel draft without an active color has none.
 * Not sent yet: `hoverImage` (no uploads yet), `rating` (no reviews yet) and `isWishlisted` (no accounts yet); none
 * of them is required by the contract.
 */
@Serializable
data class ProductSummaryHttpResponse(
    val id: Long,
    val slug: String,
    val sku: String,
    val name: String,
    val url: String,
    val category: CategoryRefHttpResponse,
    val bagType: String,
    val price: PriceInfoHttpResponse,
    val colors: List<ProductColorOptionHttpResponse>,
    val colorsCount: Int,
    val defaultColorKey: String?,
    val displayColorKey: String?,
    val image: MediaAssetHttpResponse?,
    val badges: List<ProductBadgeHttpResponse>,
    val stock: StockInfoHttpResponse,
    val isNew: Boolean,
    val hasVideo: Boolean,
    val soldCount: Int,
    val freeShippingEligible: Boolean,
) {
    companion object {
        /**
         * @param filterColors the request's color filter. The card shows the first of them the product comes in, so
         *   its picture is in the color the shopper asked for; without one, the default color.
         */
        fun from(card: ProductCardDto, filterColors: List<String>) = ProductSummaryHttpResponse(
            id = card.id,
            slug = card.slug,
            sku = card.sku,
            name = card.name,
            url = "/product/${card.slug}",
            category = CategoryRefHttpResponse(card.category.id, card.category.slug, card.category.name),
            bagType = card.bagType.name,
            price = PriceInfoHttpResponse.from(card.price),
            colors = card.colors.map {
                ProductColorOptionHttpResponse(
                    variantId = it.variantId,
                    color = ColorHttpResponse(it.key, it.name, it.hex, it.isLight, it.sortOrder),
                    stockStatus = it.stockStatus.name,
                )
            },
            colorsCount = card.colors.size,
            defaultColorKey = card.defaultColorKey,
            displayColorKey = filterColors.firstOrNull { wanted -> card.colors.any { it.key == wanted } } ?: card.defaultColorKey,
            // No uploads yet, so no photo of any color: always null for now.
            image = null,
            badges = card.badges.map { ProductTexts.badge(it, card.price, card.stock) },
            stock = StockInfoHttpResponse.from(card.stock),
            isNew = card.isNew,
            hasVideo = card.hasVideo,
            soldCount = card.soldCount,
            freeShippingEligible = card.freeShippingEligible,
        )
    }
}

/** Contract schema `CategoryRef`. */
@Serializable
data class CategoryRefHttpResponse(val id: Long, val slug: String, val name: String)

/** Contract schema `PriceInfo`, in toman. [compareAtPrice] is left out when there is no discount. */
@Serializable
data class PriceInfoHttpResponse(
    val price: Long,
    val compareAtPrice: Long? = null,
    val discountPercent: Int,
    val discountAmount: Long,
    val currency: String,
) {
    companion object {
        /** The contract's only currency: IRT, toman. */
        const val TOMAN = "IRT"

        fun from(price: PriceDto) =
            PriceInfoHttpResponse(price.price, price.compareAtPrice, price.discountPercent, price.discountAmount, TOMAN)
    }
}

/** Contract schema `StockInfo`. [availableQuantity] only when few are left: the exact stock never leaves the shop. */
@Serializable
data class StockInfoHttpResponse(
    val status: String,
    val availableQuantity: Int? = null,
    val maxOrderQuantity: Int,
    val label: String,
) {
    companion object {
        fun from(stock: StockInfo) =
            StockInfoHttpResponse(stock.status.name, stock.shownQuantity, stock.maxOrderQuantity, ProductTexts.stockLabel(stock))
    }
}

/** Contract schema `ProductBadge`. */
@Serializable
data class ProductBadgeHttpResponse(
    val code: String,
    val label: String,
    val tone: String,
    val icon: String? = null,
)

/** Contract schema `ProductColorOption`. No `imageUrl` yet: no uploads. */
@Serializable
data class ProductColorOptionHttpResponse(
    val variantId: Long,
    val color: ColorHttpResponse,
    val stockStatus: String,
)
