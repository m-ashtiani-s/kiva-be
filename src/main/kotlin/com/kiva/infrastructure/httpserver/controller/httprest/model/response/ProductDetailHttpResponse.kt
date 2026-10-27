package com.kiva.infrastructure.httpserver.controller.httprest.model.response

import com.kiva.application.dto.RatingSummaryDto
import com.kiva.application.dto.GalleryItemDto
import com.kiva.application.dto.ProductDetailDto
import com.kiva.domain.aggregate.product.model.valueobject.PocketInfo
import com.kiva.domain.aggregate.product.model.valueobject.ProductSpecs
import com.kiva.domain.aggregate.product.model.valueobject.StrapInfo
import com.kiva.infrastructure.media.MediaUrls
import com.kiva.common.valueobject.SeoMeta
import kotlinx.serialization.Serializable

/**
 * Contract schema `ProductDetail`: every field of the card (ProductSummary), then the page's own.
 *
 * Not sent yet: `tags` and `perks` (nothing keeps them yet), and the media `id`s (they come
 * with uploads). `ratingSummary` is required, so it goes out empty until reviews exist.
 */
@Serializable
data class ProductDetailHttpResponse(
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
    val selectedColorKey: String?,
    val shortDescription: String? = null,
    val description: String? = null,
    val highlights: List<String>,
    val material: MaterialRefHttpResponse,
    val materialDescription: String? = null,
    val specs: ProductSpecsHttpResponse,
    val specTable: List<SpecRowHttpResponse>,
    val variants: List<ProductVariantHttpResponse>,
    val breadcrumbs: List<BreadcrumbHttpResponse>,
    val ratingSummary: RatingSummaryHttpResponse,
    val stockAlert: ProductStockAlertHttpResponse?,
    val campaign: ProductCampaignHttpResponse?,
    val policies: ProductPoliciesHttpResponse,
    val shareUrl: String,
    val seo: SeoMetaHttpResponse,
    val publishedAt: String? = null,
    val updatedAt: String,
) {
    companion object {
        /** @param siteUrl the shop's address (`https://kiva.ir`): the page's address is always its slug's. */
        fun from(detail: ProductDetailDto, siteUrl: String, urls: MediaUrls): ProductDetailHttpResponse {
            // The card exactly as in the list, its picture in the selected color.
            val card = ProductSummaryHttpResponse.from(detail.card, filterColors = listOfNotNull(detail.selectedColorKey), urls)
            // Asked for by number or code, the canonical address is still the slug's.
            val pageUrl = siteUrl.trimEnd('/') + card.url

            return ProductDetailHttpResponse(
                id = card.id,
                slug = card.slug,
                sku = card.sku,
                name = card.name,
                url = card.url,
                category = card.category,
                bagType = card.bagType,
                price = card.price,
                colors = card.colors,
                colorsCount = card.colorsCount,
                defaultColorKey = card.defaultColorKey,
                displayColorKey = card.displayColorKey,
                image = card.image,
                hoverImage = card.hoverImage,
                badges = card.badges,
                stock = card.stock,
                isNew = card.isNew,
                hasVideo = card.hasVideo,
                soldCount = card.soldCount,
                freeShippingEligible = card.freeShippingEligible,
                rating = card.rating,
                isWishlisted = card.isWishlisted,
                selectedColorKey = detail.selectedColorKey,
                shortDescription = detail.shortDescription,
                description = detail.description,
                highlights = detail.highlights,
                material = MaterialRefHttpResponse(detail.material.id, detail.material.slug, detail.material.name, detail.material.family.name),
                materialDescription = detail.materialDescription,
                specs = ProductSpecsHttpResponse.from(detail.specs),
                specTable = ProductTexts.specTable(detail),
                variants = detail.variants.map { ProductVariantHttpResponse.from(it, urls) },
                breadcrumbs = listOf(
                    BreadcrumbHttpResponse("خانه", "/"),
                    BreadcrumbHttpResponse("فروشگاه", "/products"),
                    BreadcrumbHttpResponse(detail.card.category.name, "/products?category=${detail.card.category.slug}"),
                    BreadcrumbHttpResponse(detail.card.name, url = null),
                ),
                ratingSummary = RatingSummaryHttpResponse.from(detail.card.rating),
                stockAlert = detail.stockAlert?.let { ProductStockAlertHttpResponse(it.subscribed, it.alertId) },
                campaign = detail.campaign?.let { ProductCampaignHttpResponse(it.slug, it.title, it.endsAt.toString()) },
                policies = ProductPoliciesHttpResponse(returnable = detail.returnable, reservable = detail.reservable, maxPerOrder = detail.maxPerOrder),
                shareUrl = pageUrl,
                seo = SeoMetaHttpResponse(title = "${detail.card.name} | کیوا", description = detail.shortDescription, canonicalUrl = pageUrl),
                publishedAt = detail.publishedAt?.toString(),
                updatedAt = detail.updatedAt.toString(),
            )
        }
    }
}

/** Contract schema `MaterialRef`. */
@Serializable
data class MaterialRefHttpResponse(val id: Long, val slug: String, val name: String, val family: String)

/** Contract schema `ProductSpecs`, every part of it. Labels (closure, occasions, straps, pockets) are the server's own Persian. */
@Serializable
data class ProductSpecsHttpResponse(
    val dimensions: DimensionsHttpResponse? = null,
    val weightGrams: Int? = null,
    val closure: ClosureHttpResponse? = null,
    val straps: List<StrapHttpResponse>? = null,
    val pockets: PocketsHttpResponse? = null,
    val laptopMaxInch: Double? = null,
    val capacityLiters: Double? = null,
    val waterproof: Boolean = false,
    val occasions: List<OccasionHttpResponse>,
    val fits: List<String> = emptyList(),
    val lining: LiningHttpResponse? = null,
    val hardware: HardwareHttpResponse? = null,
    val stitching: String? = null,
    val handmade: Boolean = false,
    val madeIn: String? = null,
    val careInstructions: List<String> = emptyList(),
    val warranty: WarrantyHttpResponse? = null,
) {
    companion object {
        fun from(specs: ProductSpecs) = ProductSpecsHttpResponse(
            dimensions = specs.dimensions?.let { DimensionsHttpResponse(it.lengthCm, it.heightCm, it.widthCm) },
            weightGrams = specs.weightGrams,
            closure = specs.closure?.let { ClosureHttpResponse(it.name, ProductTexts.closure(it)) },
            straps = specs.straps.takeIf { it.isNotEmpty() }?.map(StrapHttpResponse::from),
            pockets = specs.pockets?.let(PocketsHttpResponse::from),
            laptopMaxInch = specs.laptopMaxInch,
            capacityLiters = specs.capacityLiters,
            waterproof = specs.waterproof,
            occasions = specs.occasions.sorted().map { OccasionHttpResponse(it.name, ProductTexts.occasion(it)) },
            fits = specs.fits.sorted().map { it.name },
            lining = specs.lining?.let { LiningHttpResponse(it.material, it.color) },
            hardware = specs.hardware?.let { HardwareHttpResponse(it.material, it.finish) },
            stitching = specs.stitching,
            handmade = specs.handmade,
            madeIn = specs.madeIn,
            careInstructions = specs.careInstructions.items,
            warranty = specs.warranty?.let { WarrantyHttpResponse(it.months, it.description) },
        )
    }
}

@Serializable
data class DimensionsHttpResponse(val lengthCm: Double, val heightCm: Double, val widthCm: Double)

@Serializable
data class ClosureHttpResponse(val type: String, val label: String)

/** Contract schema `StrapInfo`. [label] is the panel's, or one made from the kind and the length. */
@Serializable
data class StrapHttpResponse(
    val type: String? = null,
    val label: String,
    val lengthCm: Double? = null,
    val minLengthCm: Double? = null,
    val maxLengthCm: Double? = null,
    val adjustable: Boolean = false,
    val detachable: Boolean = false,
    val material: String? = null,
) {
    companion object {
        fun from(strap: StrapInfo) = StrapHttpResponse(
            type = strap.type?.name,
            label = ProductTexts.strap(strap),
            lengthCm = strap.lengthCm,
            minLengthCm = strap.minLengthCm,
            maxLengthCm = strap.maxLengthCm,
            adjustable = strap.adjustable,
            detachable = strap.detachable,
            material = strap.material,
        )
    }
}

/** Contract schema `PocketInfo`. [summary] is the panel's, or one made from the counts. */
@Serializable
data class PocketsHttpResponse(
    val internal: Int = 0,
    val internalZip: Int = 0,
    val external: Int = 0,
    val phone: Int = 0,
    val cardSlots: Int = 0,
    val billSlots: Int = 0,
    val coinPocket: Boolean = false,
    val detachablePouch: Boolean = false,
    val laptopSleeve: Boolean = false,
    val summary: String,
) {
    companion object {
        fun from(pockets: PocketInfo) = PocketsHttpResponse(
            internal = pockets.internal,
            internalZip = pockets.internalZip,
            external = pockets.external,
            phone = pockets.phone,
            cardSlots = pockets.cardSlots,
            billSlots = pockets.billSlots,
            coinPocket = pockets.coinPocket,
            detachablePouch = pockets.detachablePouch,
            laptopSleeve = pockets.laptopSleeve,
            summary = ProductTexts.pockets(pockets),
        )
    }
}

@Serializable
data class LiningHttpResponse(val material: String? = null, val color: String? = null)

@Serializable
data class HardwareHttpResponse(val material: String? = null, val finish: String? = null)

@Serializable
data class WarrantyHttpResponse(val months: Int, val description: String? = null)

@Serializable
data class OccasionHttpResponse(val code: String, val label: String)

/** Contract schema `SpecRow`. [swatches] only on the colors row; [tone] only on the stock row. */
@Serializable
data class SpecRowHttpResponse(
    val key: String,
    val label: String,
    val value: String,
    val swatches: List<ColorHttpResponse>? = null,
    val tone: String? = null,
)

/** Contract schema `ProductVariant`. [stockAlertSubscribed]: the viewer waits for this color (a guest false; phase 10). */
@Serializable
data class ProductVariantHttpResponse(
    val id: Long,
    val sku: String,
    val color: ColorHttpResponse,
    val isDefault: Boolean,
    val price: PriceInfoHttpResponse,
    val stock: StockInfoHttpResponse,
    val media: List<ProductMediaHttpResponse>,
    val hasVideo: Boolean,
    val stockAlertSubscribed: Boolean?,
) {
    companion object {
        fun from(variant: ProductDetailDto.Variant, urls: MediaUrls) = ProductVariantHttpResponse(
            id = variant.id,
            sku = variant.sku,
            color = variant.color.let { ColorHttpResponse(it.key, it.name, it.hex, it.isLight, it.sortOrder) },
            isDefault = variant.isDefault,
            price = PriceInfoHttpResponse.from(variant.price),
            stock = StockInfoHttpResponse.from(variant.stock),
            media = variant.media.mapIndexed { index, item -> ProductMediaHttpResponse.from(index, item, urls) },
            hasVideo = variant.hasVideo,
            stockAlertSubscribed = variant.stockAlertSubscribed,
        )
    }
}

/**
 * Contract schema `ProductMedia`: the file (MediaAsset, every address full) and its place in the color's gallery. The
 * first of a gallery is the main photo. Every photo is a real one, so `isUnedited` is always true.
 */
@Serializable
data class ProductMediaHttpResponse(
    val id: String,
    val type: String,
    val url: String,
    val thumbnailUrl: String?,
    val srcSet: List<MediaAssetHttpResponse.SrcSetItem>,
    val posterUrl: String?,
    val width: Int,
    val height: Int,
    val durationSec: Int?,
    val mimeType: String,
    val alt: String?,
    val blurhash: String?,
    val view: String?,
    val viewLabel: String?,
    val sortOrder: Int,
    val isPrimary: Boolean,
    val isUnedited: Boolean = true,
) {
    companion object {
        fun from(index: Int, item: GalleryItemDto, urls: MediaUrls): ProductMediaHttpResponse {
            val asset = MediaAssetHttpResponse.from(item.media, urls)
            return ProductMediaHttpResponse(
                id = asset.id,
                type = asset.type,
                url = asset.url,
                thumbnailUrl = asset.thumbnailUrl,
                srcSet = asset.srcSet,
                posterUrl = asset.posterUrl,
                width = asset.width,
                height = asset.height,
                durationSec = asset.durationSec,
                mimeType = asset.mimeType,
                alt = asset.alt,
                blurhash = asset.blurhash,
                view = item.view.name,
                viewLabel = ProductTexts.mediaView(item.view),
                sortOrder = index,
                isPrimary = index == 0,
            )
        }
    }
}

/** Contract schema `Breadcrumb`. The last one, the page itself, has no link. */
@Serializable
data class BreadcrumbHttpResponse(val label: String, val url: String? = null)

/** Contract schema `RatingSummary`. */
@Serializable
data class RatingSummaryHttpResponse(val average: Double, val count: Int, val distribution: List<RatingShareHttpResponse>, val verifiedBuyerPercent: Int?) {
    companion object {
        /** No reviews yet: nothing counted, every star from 5 down to 1. */
        val NONE = RatingSummaryHttpResponse(0.0, 0, (5 downTo 1).map { RatingShareHttpResponse(it, 0, 0.0) }, null)

        fun from(summary: RatingSummaryDto) = RatingSummaryHttpResponse(
            summary.average, summary.count, summary.distribution.map { RatingShareHttpResponse(it.stars, it.count, it.percent) }, summary.verifiedBuyerPercent,
        )
    }
}

@Serializable
data class RatingShareHttpResponse(val stars: Int, val count: Int, val percent: Double)

/** Contract schema `ProductPolicies`, the product's own part; the shop-wide numbers come with the settings. */
@Serializable
data class ProductPoliciesHttpResponse(val returnable: Boolean, val reservable: Boolean, val maxPerOrder: Int)

/** Contract schema `SeoMeta`. */
@Serializable
data class SeoMetaHttpResponse(val title: String, val description: String? = null, val canonicalUrl: String)

/**
 * Contract schema `SeoInput`: a panel form's three SEO fields as they were saved, so a form that sends them back changes
 * nothing. Null texts mean "the page's own name and summary" (what [SeoMetaHttpResponse] then shows).
 */
@Serializable
data class SeoInputHttpResponse(val title: String?, val description: String?, val noIndex: Boolean) {
    companion object {
        fun from(seo: SeoMeta) = SeoInputHttpResponse(seo.title, seo.description, seo.noIndex)
    }
}
