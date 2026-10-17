package com.kiva.infrastructure.httpserver.controller.httprest.model.response

import com.kiva.application.dto.AdminProductDto
import com.kiva.application.dto.AdminProductListItemDto
import com.kiva.application.dto.AdminVariantDto
import com.kiva.infrastructure.common.http.VersionETag
import com.kiva.infrastructure.media.MediaUrls
import kotlinx.serialization.Serializable

/** Contract schema `RatingBrief`. Reviews come in phase 8; until then every product has none. */
@Serializable
data class RatingBriefHttpResponse(val average: Double, val count: Int) {
    companion object {
        val NONE = RatingBriefHttpResponse(0.0, 0)
    }
}

/**
 * Contract schema `AdminProductListItem`, every field always. [imageUrl] is the default color's first media (its small
 * size from step 1.9 on), or null.
 */
@Serializable
data class AdminProductListItemHttpResponse(
    val id: Long,
    val sku: String,
    val name: String,
    val imageUrl: String?,
    val category: CategoryRefHttpResponse,
    val status: String,
    val price: PriceInfoHttpResponse,
    val totalStock: Int,
    val variantsCount: Int,
    val lowStockVariants: Int,
    val soldCount: Int,
    val rating: RatingBriefHttpResponse,
    val updatedAt: String,
) {
    companion object {
        fun from(item: AdminProductListItemDto, urls: MediaUrls) = AdminProductListItemHttpResponse(
            id = item.id,
            sku = item.sku,
            name = item.name,
            imageUrl = item.image?.let { urls.of(it.media.thumbnailPath) },
            category = CategoryRefHttpResponse(item.category.id, item.category.slug, item.category.name),
            status = item.status.name,
            price = PriceInfoHttpResponse.from(item.price),
            totalStock = item.totalStock,
            variantsCount = item.variantsCount,
            lowStockVariants = item.lowStockVariants,
            soldCount = item.soldCount,
            rating = RatingBriefHttpResponse.NONE,
            updatedAt = item.updatedAt.toString(),
        )
    }
}

/**
 * Contract schema `AdminProduct`: every field of `ProductDetail`, made by the shop's own builder
 * (ProductDetailHttpResponse.from), so the panel sees exactly what a shopper would; then the panel's fields. The
 * shopper-only fields are always empty here (`isWishlisted`, `stockAlert`, `campaign` null, `perks` empty).
 *
 * [etag] is the same text as the `ETag` header.
 */
@Serializable
data class AdminProductHttpResponse(
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
    val rating: RatingBriefHttpResponse,
    val soldCount: Int,
    val freeShippingEligible: Boolean,
    val isWishlisted: Boolean?,
    val selectedColorKey: String?,
    val shortDescription: String?,
    val description: String?,
    val highlights: List<String>,
    val material: MaterialRefHttpResponse,
    val materialDescription: String?,
    val specs: ProductSpecsHttpResponse,
    val specTable: List<SpecRowHttpResponse>,
    val variants: List<ProductVariantHttpResponse>,
    val breadcrumbs: List<BreadcrumbHttpResponse>,
    val ratingSummary: RatingSummaryHttpResponse,
    val policies: ProductPoliciesHttpResponse,
    val perks: List<PerkHttpResponse>,
    val tags: List<String>,
    val campaign: ProductCampaignHttpResponse?,
    val stockAlert: ProductStockAlertHttpResponse?,
    val shareUrl: String,
    val seo: SeoMetaHttpResponse,
    val publishedAt: String?,
    val updatedAt: String,
    val status: String,
    val adminVariants: List<AdminVariantHttpResponse>,
    val newUntil: String?,
    val sortPriority: Int,
    val publishAt: String?,
    val viewsCount: Int?,
    val conversionRate: Double?,
    val createdAt: String,
    val createdBy: String,
    val etag: String,
) {
    companion object {
        /** @param siteUrl the shop's address, for the page's canonical address (as the shop's page builds it). */
        fun from(product: AdminProductDto, siteUrl: String, urls: MediaUrls): AdminProductHttpResponse {
            val shop = ProductDetailHttpResponse.from(product.detail, siteUrl, urls)
            return AdminProductHttpResponse(
                id = shop.id,
                slug = shop.slug,
                sku = shop.sku,
                name = shop.name,
                url = shop.url,
                category = shop.category,
                bagType = shop.bagType,
                price = shop.price,
                colors = shop.colors,
                colorsCount = shop.colorsCount,
                defaultColorKey = shop.defaultColorKey,
                displayColorKey = shop.displayColorKey,
                image = shop.image,
                hoverImage = null,
                badges = shop.badges,
                stock = shop.stock,
                isNew = shop.isNew,
                hasVideo = shop.hasVideo,
                rating = RatingBriefHttpResponse.NONE,
                soldCount = shop.soldCount,
                freeShippingEligible = shop.freeShippingEligible,
                isWishlisted = null,
                selectedColorKey = shop.selectedColorKey,
                shortDescription = shop.shortDescription,
                description = shop.description,
                highlights = shop.highlights,
                material = shop.material,
                materialDescription = shop.materialDescription,
                specs = shop.specs,
                specTable = shop.specTable,
                variants = shop.variants,
                breadcrumbs = shop.breadcrumbs,
                ratingSummary = shop.ratingSummary,
                policies = shop.policies,
                perks = emptyList(),
                tags = product.tags,
                campaign = null,
                stockAlert = null,
                shareUrl = shop.shareUrl,
                seo = shop.seo,
                publishedAt = shop.publishedAt,
                updatedAt = shop.updatedAt,
                status = product.status.name,
                adminVariants = product.variants.map { AdminVariantHttpResponse.from(it, urls) },
                newUntil = product.newUntil?.toString(),
                sortPriority = product.sortPriority,
                publishAt = product.publishAt?.toString(),
                // No view counts are gathered: zero would be a lie (contract).
                viewsCount = null,
                conversionRate = null,
                createdAt = product.createdAt.toString(),
                createdBy = PanelTexts.actorName(product.createdBy),
                etag = VersionETag.of(product.version),
            )
        }
    }
}

/** Contract schema `Perk`. */
@Serializable
data class PerkHttpResponse(val icon: String?, val title: String, val subtitle: String?)

/** `ProductDetail.campaign` (phase 11). */
@Serializable
data class ProductCampaignHttpResponse(val slug: String, val title: String, val endsAt: String)

/** `ProductDetail.stockAlert` (phase 10). */
@Serializable
data class ProductStockAlertHttpResponse(val subscribed: Boolean, val alertId: Long?)

/** Contract schema `AdminVariant`: the shop's ProductVariant of the color, then the exact stock. */
@Serializable
data class AdminVariantHttpResponse(
    val id: Long,
    val sku: String,
    val color: ColorHttpResponse,
    val isDefault: Boolean,
    val price: PriceInfoHttpResponse,
    val stock: StockInfoHttpResponse,
    val media: List<ProductMediaHttpResponse>,
    val hasVideo: Boolean,
    val barcode: String?,
    val isActive: Boolean,
    val stockOnHand: Int,
    val stockReserved: Int,
    val stockAvailable: Int,
    val soldCount: Int,
    val stockAlertSubscribers: Int,
) {
    companion object {
        fun from(variant: AdminVariantDto, urls: MediaUrls): AdminVariantHttpResponse {
            val shop = ProductVariantHttpResponse.from(variant.shop, urls)
            return AdminVariantHttpResponse(
                id = shop.id,
                sku = shop.sku,
                color = shop.color,
                isDefault = shop.isDefault,
                price = shop.price,
                stock = shop.stock,
                media = shop.media,
                hasVideo = shop.hasVideo,
                barcode = variant.barcode,
                isActive = variant.isActive,
                stockOnHand = variant.stockOnHand,
                stockReserved = variant.stockReserved,
                stockAvailable = variant.stockAvailable,
                soldCount = variant.soldCount,
                stockAlertSubscribers = variant.stockAlertSubscribers,
            )
        }
    }
}
