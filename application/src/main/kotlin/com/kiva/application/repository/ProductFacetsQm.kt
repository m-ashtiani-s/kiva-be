package com.kiva.application.repository

import com.kiva.common.valueobject.Money
import com.kiva.common.valueobject.Slug
import com.kiva.domain.aggregate.color.model.valueobject.ColorKey
import com.kiva.domain.aggregate.product.model.valueobject.BagType

/**
 * What the shop's filters can still find, from the read model: for each value of each filter, how many products it
 * would give. Each is counted with every other filter of the request applied, but not its own (disjunctive faceting),
 * so picking lilac doesn't make the other colors show zero.
 *
 * A value no product in the shop has is missing from its map.
 *
 * @property minPrice / maxPrice the cheapest and dearest card price among them; null when there are none.
 * @property onSale / inStock / new / withVideo how many of them pass that switch.
 */
data class ProductFacetsQm(
    val categories: Map<Slug, Int>,
    val colors: Map<ColorKey, Int>,
    val materials: Map<Slug, Int>,
    val bagTypes: Map<BagType, Int>,
    val minPrice: Money?,
    val maxPrice: Money?,
    val onSale: Int,
    val inStock: Int,
    val new: Int,
    val withVideo: Int,
)
