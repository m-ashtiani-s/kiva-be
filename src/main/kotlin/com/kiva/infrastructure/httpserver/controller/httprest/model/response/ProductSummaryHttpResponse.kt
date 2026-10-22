package com.kiva.infrastructure.httpserver.controller.httprest.model.response

import com.kiva.application.dto.PriceDto
import com.kiva.application.dto.ProductCardDto
import com.kiva.domain.aggregate.product.model.valueobject.StockInfo
import com.kiva.infrastructure.media.MediaUrls
import kotlinx.serialization.Serializable

/**
 * Contract schema `ProductSummary`: a product card.
 *
 * [image] and [hoverImage] are the first two photos of the color the card is drawn in ([displayColorKey]); always
 * there, `null` while that color has none (the client then draws the bag's illustration). A video is never the card's
 * picture. [defaultColorKey] and [displayColorKey] are never null in the shop: only a panel draft without an active
 * color has none. [isWishlisted]: true or false for a customer on an uncached answer, null for a guest (decision 15 of
 * phase 9).
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
    val hoverImage: MediaAssetHttpResponse?,
    val badges: List<ProductBadgeHttpResponse>,
    val stock: StockInfoHttpResponse,
    val isNew: Boolean,
    val hasVideo: Boolean,
    val soldCount: Int,
    val freeShippingEligible: Boolean,
    val rating: RatingBriefHttpResponse,
    val isWishlisted: Boolean?,
) {
    companion object {
        /**
         * @param filterColors the request's color filter. The card shows the first of them the product comes in, so
         *   its picture is in the color the shopper asked for; without one, the default color.
         */
        fun from(card: ProductCardDto, filterColors: List<String>, urls: MediaUrls): ProductSummaryHttpResponse {
            val displayColorKey = filterColors.firstOrNull { wanted -> card.colors.any { it.key == wanted } } ?: card.defaultColorKey
            val displayColor = card.colors.firstOrNull { it.key == displayColorKey }
            return ProductSummaryHttpResponse(
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
                    imageUrl = it.image?.let { photo -> urls.of(photo.pathFor(COLOR_IMAGE_WIDTH)) },
                    thumbnailUrl = it.image?.let { photo -> urls.of(photo.pathFor(COLOR_THUMBNAIL_WIDTH)) },
                )
            },
            colorsCount = card.colors.size,
            defaultColorKey = card.defaultColorKey,
            displayColorKey = displayColorKey,
            image = displayColor?.image?.let { MediaAssetHttpResponse.from(it, urls) },
            hoverImage = displayColor?.hoverImage?.let { MediaAssetHttpResponse.from(it, urls) },
            badges = card.badges.map { ProductTexts.badge(it, card.price, card.stock) },
            stock = StockInfoHttpResponse.from(card.stock),
            isNew = card.isNew,
            hasVideo = card.hasVideo,
            soldCount = card.soldCount,
            freeShippingEligible = card.freeShippingEligible,
            rating = RatingBriefHttpResponse.from(card.rating),
            isWishlisted = card.isWishlisted,
        )
        }

        /** A color option's picture and its swatch: the contract's 480 and 160 versions. */
        const val COLOR_IMAGE_WIDTH = 480
        const val COLOR_THUMBNAIL_WIDTH = 160
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
    val imageUrl: String? = null,
    val thumbnailUrl: String? = null,
)
