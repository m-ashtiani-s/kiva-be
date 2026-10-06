package com.kiva.application.dto

import com.kiva.domain.aggregate.product.model.valueobject.BagType

/**
 * The counts beside the shop's filters (the contract's ProductFacets, without its Persian texts, which are the HTTP
 * layer's job). Every value of every filter is here, in its own display order, with how many products it would give
 * among the other filters of the request; one that gives none says 0, so the filter panel never jumps around.
 *
 * @property price the cheapest and dearest current price, rounded out to [PriceRange.step]; null when nothing matches.
 */
data class ProductFacetsDto(
    val categories: List<Option>,
    val colors: List<Option>,
    val materials: List<Option>,
    val bagTypes: List<BagTypeOption>,
    val price: PriceRange?,
    val onSaleCount: Int,
    val inStockCount: Int,
    val newCount: Int,
    val withVideoCount: Int,
) {
    /** @property value the slug or key a filter takes. @property hex only for colors. */
    data class Option(val value: String, val name: String, val count: Int, val selected: Boolean, val hex: String? = null)

    data class BagTypeOption(val bagType: BagType, val count: Int, val selected: Boolean)

    data class PriceRange(val min: Long, val max: Long, val step: Long)
}
