package com.kiva.infrastructure.httpserver.controller.httprest.model.response

import com.kiva.application.dto.ProductDetailDto
import com.kiva.domain.aggregate.product.model.valueobject.ProductMedia
import com.kiva.domain.aggregate.product.model.valueobject.ProductSpecs
import kotlinx.serialization.Serializable

/**
 * Contract schema `ProductDetail`: every field of the card (ProductSummary), then the page's own.
 *
 * Not sent yet: `tags`, `perks`, `campaign` and `stockAlert` (nothing keeps them yet), and the media `id`s (they come
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
    val defaultColorKey: String,
    val displayColorKey: String,
    val badges: List<ProductBadgeHttpResponse>,
    val stock: StockInfoHttpResponse,
    val isNew: Boolean,
    val hasVideo: Boolean,
    val soldCount: Int,
    val freeShippingEligible: Boolean,
    val selectedColorKey: String,
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
    val policies: ProductPoliciesHttpResponse,
    val shareUrl: String,
    val seo: SeoMetaHttpResponse,
    val publishedAt: String? = null,
    val updatedAt: String,
) {
    companion object {
        /** @param siteUrl the shop's address (`https://kiva.ir`): the page's address is always its slug's. */
        fun from(detail: ProductDetailDto, siteUrl: String): ProductDetailHttpResponse {
            // The card exactly as in the list, its picture in the selected color.
            val card = ProductSummaryHttpResponse.from(detail.card, filterColors = listOf(detail.selectedColorKey))
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
                badges = card.badges,
                stock = card.stock,
                isNew = card.isNew,
                hasVideo = card.hasVideo,
                soldCount = card.soldCount,
                freeShippingEligible = card.freeShippingEligible,
                selectedColorKey = detail.selectedColorKey,
                shortDescription = detail.shortDescription,
                description = detail.description,
                highlights = detail.highlights,
                material = MaterialRefHttpResponse(detail.material.id, detail.material.slug, detail.material.name, detail.material.family.name),
                materialDescription = detail.materialDescription,
                specs = ProductSpecsHttpResponse.from(detail.specs),
                specTable = ProductTexts.specTable(detail),
                variants = detail.variants.map(ProductVariantHttpResponse::from),
                breadcrumbs = listOf(
                    BreadcrumbHttpResponse("خانه", "/"),
                    BreadcrumbHttpResponse("فروشگاه", "/products"),
                    BreadcrumbHttpResponse(detail.card.category.name, "/products?category=${detail.card.category.slug}"),
                    BreadcrumbHttpResponse(detail.card.name, url = null),
                ),
                ratingSummary = RatingSummaryHttpResponse.NONE,
                policies = ProductPoliciesHttpResponse(returnable = detail.returnable, maxPerOrder = detail.maxPerOrder),
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

/**
 * Contract schema `ProductSpecs`, as far as the domain keeps specs today: the strap and the pockets are one text each,
 * so they go out as one strap's label and the pockets' summary.
 */
@Serializable
data class ProductSpecsHttpResponse(
    val dimensions: DimensionsHttpResponse? = null,
    val weightGrams: Int? = null,
    val closure: ClosureHttpResponse? = null,
    val straps: List<StrapHttpResponse>? = null,
    val pockets: PocketsHttpResponse? = null,
    val occasions: List<OccasionHttpResponse>,
) {
    companion object {
        fun from(specs: ProductSpecs) = ProductSpecsHttpResponse(
            dimensions = specs.dimensions?.let { DimensionsHttpResponse(it.lengthCm, it.heightCm, it.widthCm) },
            weightGrams = specs.weightGrams,
            closure = specs.closure?.let { ClosureHttpResponse(it.name, ProductTexts.closure(it)) },
            straps = specs.strap?.let { listOf(StrapHttpResponse(it)) },
            pockets = specs.pockets?.let { PocketsHttpResponse(it) },
            occasions = specs.occasions.sorted().map { OccasionHttpResponse(it.name, ProductTexts.occasion(it)) },
        )
    }
}

@Serializable
data class DimensionsHttpResponse(val lengthCm: Double, val heightCm: Double, val widthCm: Double)

@Serializable
data class ClosureHttpResponse(val type: String, val label: String)

@Serializable
data class StrapHttpResponse(val label: String)

@Serializable
data class PocketsHttpResponse(val summary: String)

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

/** Contract schema `ProductVariant`. Not sent yet: `stockAlertSubscribed` (no accounts yet). */
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
) {
    companion object {
        fun from(variant: ProductDetailDto.Variant) = ProductVariantHttpResponse(
            id = variant.id,
            sku = variant.sku,
            color = variant.color.let { ColorHttpResponse(it.key, it.name, it.hex, it.isLight, it.sortOrder) },
            isDefault = variant.isDefault,
            price = PriceInfoHttpResponse.from(variant.price),
            stock = StockInfoHttpResponse.from(variant.stock),
            media = variant.media.mapIndexed(ProductMediaHttpResponse::from),
            hasVideo = variant.hasVideo,
        )
    }
}

/** Contract schema `ProductMedia`, without its `id` until uploads exist. The first of a gallery is the main photo. */
@Serializable
data class ProductMediaHttpResponse(
    val type: String,
    val url: String,
    val alt: String? = null,
    val view: String? = null,
    val viewLabel: String? = null,
    val sortOrder: Int,
    val isPrimary: Boolean,
) {
    companion object {
        fun from(index: Int, media: ProductMedia) = ProductMediaHttpResponse(
            type = media.type.name,
            url = media.url,
            alt = media.alt,
            view = media.view?.name,
            viewLabel = media.view?.let { ProductTexts.mediaView(it) },
            sortOrder = index,
            isPrimary = index == 0,
        )
    }
}

/** Contract schema `Breadcrumb`. The last one, the page itself, has no link. */
@Serializable
data class BreadcrumbHttpResponse(val label: String, val url: String? = null)

/** Contract schema `RatingSummary`. */
@Serializable
data class RatingSummaryHttpResponse(val average: Double, val count: Int, val distribution: List<RatingShareHttpResponse>) {
    companion object {
        /** No reviews yet: nothing counted, every star from 5 down to 1. */
        val NONE = RatingSummaryHttpResponse(0.0, 0, (5 downTo 1).map { RatingShareHttpResponse(it, 0, 0.0) })
    }
}

@Serializable
data class RatingShareHttpResponse(val stars: Int, val count: Int, val percent: Double)

/** Contract schema `ProductPolicies`, the product's own part; the shop-wide numbers come with the settings. */
@Serializable
data class ProductPoliciesHttpResponse(val returnable: Boolean, val maxPerOrder: Int)

/** Contract schema `SeoMeta`. */
@Serializable
data class SeoMetaHttpResponse(val title: String, val description: String? = null, val canonicalUrl: String)
