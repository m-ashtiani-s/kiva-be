package com.kiva.application.dto

import com.kiva.common.pagination.Page
import com.kiva.domain.aggregate.product.model.valueobject.BagType
import com.kiva.domain.aggregate.product.model.valueobject.Occasion

/**
 * One page of the shop's list, and the filters it was made with, named as the shop shows them: what the page's
 * title, breadcrumbs and filter chips are made of.
 */
data class ProductListDto(
    val page: Page<ProductCardDto>,
    val applied: AppliedFilters,
) {
    /**
     * The filters of the request in the order they came. Only values that name something are here: a category slug
     * nobody has narrows the list to nothing, but has no name to show on a chip.
     *
     * @property q the search text as typed, trimmed.
     */
    data class AppliedFilters(
        val q: String? = null,
        val categories: List<ProductCardDto.CategoryRef> = emptyList(),
        val colors: List<Swatch> = emptyList(),
        val minPrice: Long? = null,
        val maxPrice: Long? = null,
        val onSale: Boolean = false,
        val inStock: Boolean = false,
        val isNew: Boolean = false,
        val hasVideo: Boolean = false,
        val bagTypes: List<BagType> = emptyList(),
        val materials: List<MaterialRef> = emptyList(),
        val occasions: List<Occasion> = emptyList(),
        /** The running campaign `?campaign=` named (its slug and title), or null: none asked, or none running. */
        val campaign: CampaignRef? = null,
    )

    data class CampaignRef(val slug: String, val title: String)

    data class Swatch(val key: String, val name: String, val hex: String)

    data class MaterialRef(val slug: String, val name: String)
}
