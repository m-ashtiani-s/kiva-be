package com.kiva.infrastructure.httpserver.controller.httprest.model.response

import com.kiva.application.dto.ProductFacetsDto
import com.kiva.application.dto.ProductListDto
import com.kiva.infrastructure.common.http.PageMetaHttpResponse
import com.kiva.infrastructure.common.http.toHttpResponse
import com.kiva.infrastructure.common.http.toPersianNumber
import kotlinx.serialization.Serializable

/**
 * Contract schema `ProductListResponse`: one page of cards, and the page's title, breadcrumbs and filter chips as the
 * prototype's shop page shows them. [facets] only when the request asked for them (`includeFacets=true`).
 * Not sent yet: `subtitle`.
 */
@Serializable
data class ProductListHttpResponse(
    val items: List<ProductSummaryHttpResponse>,
    val meta: PageMetaHttpResponse,
    val title: String,
    val breadcrumbs: List<BreadcrumbHttpResponse>,
    val appliedFilters: List<AppliedFilterHttpResponse>,
    val sort: String,
    val facets: ProductFacetsHttpResponse? = null,
) {
    companion object {
        fun from(list: ProductListDto, facets: ProductFacetsDto?, sort: String): ProductListHttpResponse {
            val applied = list.applied
            val page = list.page.toHttpResponse { ProductSummaryHttpResponse.from(it, filterColors = applied.colors.map { color -> color.key }) }
            val oneCategory = applied.categories.singleOrNull()

            return ProductListHttpResponse(
                items = page.items,
                meta = page.meta,
                // As the prototype: a single category names the page, otherwise the sale view or the whole shop.
                title = when {
                    oneCategory != null -> oneCategory.name
                    applied.onSale && applied.categories.isEmpty() -> "تخفیف‌خورده‌ها"
                    else -> "فروشگاه کیوا"
                },
                breadcrumbs = if (oneCategory == null) {
                    listOf(BreadcrumbHttpResponse("خانه", "/"), BreadcrumbHttpResponse("فروشگاه"))
                } else {
                    listOf(BreadcrumbHttpResponse("خانه", "/"), BreadcrumbHttpResponse("فروشگاه", "/products"), BreadcrumbHttpResponse(oneCategory.name))
                },
                appliedFilters = chipsOf(applied),
                sort = sort,
                facets = facets?.let(ProductFacetsHttpResponse::from),
            )
        }

        /** One chip per filter value, in the prototype's order; a chip names the value it removes. */
        private fun chipsOf(applied: ProductListDto.AppliedFilters): List<AppliedFilterHttpResponse> = buildList {
            applied.q?.let { add(AppliedFilterHttpResponse("q", it, "جستجو: «$it»")) }
            applied.categories.forEach { add(AppliedFilterHttpResponse("category", it.slug, it.name)) }
            applied.colors.forEach { add(AppliedFilterHttpResponse("color", it.key, it.name, swatchHex = it.hex)) }
            priceLabel(applied.minPrice, applied.maxPrice)?.let { add(AppliedFilterHttpResponse("price", value = null, label = it)) }
            if (applied.onSale) add(AppliedFilterHttpResponse("onSale", value = null, label = "تخفیف‌خورده"))
            if (applied.inStock) add(AppliedFilterHttpResponse("inStock", value = null, label = "موجود"))
            if (applied.isNew) add(AppliedFilterHttpResponse("isNew", value = null, label = "جدید"))
            if (applied.hasVideo) add(AppliedFilterHttpResponse("hasVideo", value = null, label = "ویدیو دار"))
            applied.bagTypes.forEach { add(AppliedFilterHttpResponse("bagType", it.name, ProductTexts.bagType(it))) }
            applied.materials.forEach { add(AppliedFilterHttpResponse("material", it.slug, it.name)) }
            applied.occasions.forEach { add(AppliedFilterHttpResponse("occasion", it.name, ProductTexts.occasion(it))) }
        }

        private fun priceLabel(min: Long?, max: Long?): String? = when {
            min != null && max != null -> "${min.toPersianNumber()} تا ${max.toPersianNumber()} تومان"
            min != null -> "از ${min.toPersianNumber()} تومان"
            max != null -> "تا ${max.toPersianNumber()} تومان"
            else -> null
        }
    }
}

/** Contract schema `AppliedFilter`: a chip of a filter in use. [value] is null for a filter that is on or off. */
@Serializable
data class AppliedFilterHttpResponse(
    val key: String,
    val value: String? = null,
    val label: String,
    val swatchHex: String? = null,
)

/** Contract schema `ProductFacets`. [price] is left out when no product matches. */
@Serializable
data class ProductFacetsHttpResponse(
    val categories: List<FacetOptionHttpResponse>,
    val colors: List<FacetOptionHttpResponse>,
    val materials: List<FacetOptionHttpResponse>,
    val bagTypes: List<FacetOptionHttpResponse>,
    val price: FacetPriceHttpResponse? = null,
    val onSaleCount: Int,
    val inStockCount: Int,
    val newCount: Int,
    val withVideoCount: Int,
) {
    companion object {
        fun from(facets: ProductFacetsDto) = ProductFacetsHttpResponse(
            categories = facets.categories.map(FacetOptionHttpResponse::from),
            colors = facets.colors.map(FacetOptionHttpResponse::from),
            materials = facets.materials.map(FacetOptionHttpResponse::from),
            bagTypes = facets.bagTypes.map { FacetOptionHttpResponse(it.bagType.name, ProductTexts.bagType(it.bagType), it.count, it.selected) },
            price = facets.price?.let { FacetPriceHttpResponse(it.min, it.max, it.step) },
            onSaleCount = facets.onSaleCount,
            inStockCount = facets.inStockCount,
            newCount = facets.newCount,
            withVideoCount = facets.withVideoCount,
        )
    }
}

/** Contract schema `FacetOption`. [hex] only for colors. */
@Serializable
data class FacetOptionHttpResponse(
    val value: String,
    val label: String,
    val count: Int,
    val selected: Boolean,
    val hex: String? = null,
) {
    companion object {
        fun from(option: ProductFacetsDto.Option) = FacetOptionHttpResponse(option.value, option.name, option.count, option.selected, option.hex)
    }
}

@Serializable
data class FacetPriceHttpResponse(val min: Long, val max: Long, val step: Long)
